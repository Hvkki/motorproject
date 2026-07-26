package dev.fingertip.core.agent

import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.skill.Capture
import dev.fingertip.core.skill.Launch
import dev.fingertip.core.skill.Speak
import dev.fingertip.core.skill.Step
import dev.fingertip.core.skill.Tap
import dev.fingertip.core.skill.TypeText
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reasoning tier: pursuing a goal for which no skill exists.
 *
 * A scripted [Planner] stands in for the language model, so the loop, safety
 * gates, self-correction and skill recording are all verified deterministically
 * with no network and no API key.
 */
class AgentLoopTest {

    /** A phone with a launcher, a Google app and a results screen. */
    private fun phone() = FakeDevice.build {
        screen(
            "home", "com.android.launcher", title = "Home",
            root = Nodes.container(
                Nodes.button("Phone"),
                Nodes.button("Google"),
                Nodes.button("Messages"),
            ),
        )
        screen(
            "google", "com.google.android.googlequicksearchbox", title = "Google",
            root = Nodes.container(
                Nodes.editText(hint = "Search", id = "search_box"),
                Nodes.button("Search", byDescription = true),
            ),
        )
        screen(
            "results", "com.google.android.googlequicksearchbox", title = "Search results",
            root = Nodes.container(
                Nodes.list(
                    Nodes.listItem("Weather in Cairo: 34 degrees, sunny"),
                    Nodes.listItem("Cairo 10-day forecast"),
                    label = "Results",
                ),
            ),
        )
        app("com.google.android.googlequicksearchbox", "google")
        onTap("google", "Search", goTo = "results", delayMs = 400)
        startAt("home")
    }

    /** Returns decisions in order, recording what it was shown. */
    private class ScriptedPlanner(private vararg val decisions: PlanDecision) : Planner {
        val seen = mutableListOf<PlanRequest>()
        var calls = 0
            private set

        override fun next(request: PlanRequest): PlanDecision {
            seen += request
            val decision = decisions.getOrElse(calls) {
                PlanDecision.GiveUp("script exhausted after $calls calls")
            }
            calls++
            return decision
        }
    }

    private val searchGoogle = arrayOf(
        PlanDecision.Act(Launch("com.google.android.googlequicksearchbox"), "open Google"),
        PlanDecision.Act(
            TypeText(Selector(viewId = "search_box"), "weather in Cairo"),
            "type the query",
        ),
        // Role-constrained on purpose: the text field carries the same "Search"
        // description, so an unconstrained selector would match it first.
        PlanDecision.Act(Tap(Selector(desc = "Search", role = Role.BUTTON)), "submit the search"),
        PlanDecision.Act(
            Capture(Selector(role = Role.LIST_ITEM, index = 0), name = "answer"),
            "read the top result",
        ),
        PlanDecision.Act(Speak("{answer}"), "tell the user"),
        PlanDecision.Done("That's the top result."),
    )

    // --- the capability the product needs -----------------------------------

    @Test
    fun `searches Google by tapping, with no skill written for it`() {
        val device = phone()
        val planner = ScriptedPlanner(*searchGoogle)
        val spoken = mutableListOf<String>()

        val result = AgentLoop(device, planner, onSpeak = spoken::add).run("search google for the weather in cairo")

        val success = assertIs<AgentResult.Success>(result, "expected success, got $result")
        assertEquals("results", device.currentScreen)
        assertEquals(listOf("Search"), device.tapLog)
        assertEquals(listOf("Search" to "weather in Cairo"), device.typeLog)
        assertTrue(
            spoken.any { "34 degrees" in it },
            "the answer was never spoken aloud: $spoken",
        )
        assertTrue(success.spoken.any { "top result" in it }, "summary not spoken: ${success.spoken}")
        // How long it took is the last thing said, and the only progress signal on
        // a long task.
        assertContains(success.spoken.last(), "Took")
    }

    @Test
    fun `the planner only ever sees redacted screen content`() {
        // The privacy boundary for the reasoning tier: this payload leaves the
        // device, so it must never carry secrets.
        val device = FakeDevice.build {
            screen(
                "bank", "com.example.bank", title = "Sign in",
                root = Nodes.container(
                    Nodes.editText(hint = "Password", value = "hunter2", isPassword = true),
                    Nodes.text("Card 4111 1111 1111 1111"),
                    Nodes.text("Your verification code is 483920"),
                    Nodes.button("Continue"),
                ),
            )
        }
        val planner = ScriptedPlanner(PlanDecision.Done("nothing to do"))

        AgentLoop(device, planner).run("read my balance")

        val wire = planner.seen.single().wire
        listOf("hunter2", "4111", "483920").forEach {
            assertTrue(it !in wire, "secret '$it' reached the planner: $wire")
        }
        assertContains(wire, "[redacted]")
        // The label survives so the agent can still describe the screen.
        assertContains(wire, "\"Password\"")
    }

    @Test
    fun `feeds failures back so the planner can correct itself`() {
        val device = phone()
        val planner = ScriptedPlanner(
            // First attempt targets something that does not exist.
            PlanDecision.Act(Tap(Selector(text = "Chrome")), "try Chrome"),
            PlanDecision.Act(Launch("com.google.android.googlequicksearchbox"), "use Google instead"),
            PlanDecision.Done("Google is open."),
        )

        val result = AgentLoop(device, planner).run("open a search app")

        assertIs<AgentResult.Success>(result)
        // The second request must contain the failure, otherwise the planner is
        // guessing blind and will simply repeat itself.
        val historyAtSecondCall = planner.seen[1].history
        assertTrue(
            historyAtSecondCall.any { "failed" in it && "Chrome" in it },
            "failure was not reported back: $historyAtSecondCall",
        )
        assertEquals("google", device.currentScreen)
    }

    @Test
    fun `records only the steps that actually worked`() {
        val device = phone()
        val planner = ScriptedPlanner(
            PlanDecision.Act(Tap(Selector(text = "Nonexistent")), "a miss"),
            PlanDecision.Act(Launch("com.google.android.googlequicksearchbox"), "open Google"),
            PlanDecision.Done("done"),
        )

        val result = assertIs<AgentResult.Success>(AgentLoop(device, planner).run("open google"))

        assertEquals(listOf<Step>(Launch("com.google.android.googlequicksearchbox")), result.trajectory)
    }

    // --- budgets and loops ---------------------------------------------------

    @Test
    fun `stops when the same action repeats on an unchanged screen`() {
        // A dead button would otherwise be tapped forever, which a blind user
        // experiences as unexplained silence.
        val device = phone()
        val planner = object : Planner {
            var calls = 0
            override fun next(request: PlanRequest): PlanDecision {
                calls++
                return PlanDecision.Act(Tap(Selector(text = "Phone")), "tap the same thing")
            }
        }

        val result = AgentLoop(device, planner).run("do something impossible")

        val failure = assertIs<AgentResult.Failed>(result)
        assertEquals(AgentResult.Failed.Reason.LOOP_DETECTED, failure.reason)
        assertTrue(planner.calls <= 3, "planner was called ${planner.calls} times before detection")
        assertContains(failure.message, "going in circles")
    }

    @Test
    fun `enforces the planner call budget`() {
        val device = phone()
        var call = 0
        // Each decision differs, so loop detection cannot fire; only the budget can.
        val planner = Planner {
            PlanDecision.Act(Tap(Selector(labelContains = "x${call++}")), "unique miss")
        }

        val result = AgentLoop(
            device, planner,
            config = AgentLoop.Config(maxSteps = 4, maxConsecutiveFailures = 99),
        ).run("never-ending task")

        val failure = assertIs<AgentResult.Failed>(result)
        assertEquals(AgentResult.Failed.Reason.BUDGET_EXCEEDED, failure.reason)
    }

    @Test
    fun `gives up after repeated consecutive failures`() {
        val device = phone()
        var call = 0
        val planner = Planner {
            PlanDecision.Act(Tap(Selector(labelContains = "missing${call++}")), "miss")
        }

        val result = AgentLoop(
            device, planner,
            config = AgentLoop.Config(maxConsecutiveFailures = 2),
        ).run("tap something absent")

        val failure = assertIs<AgentResult.Failed>(result)
        assertEquals(AgentResult.Failed.Reason.STUCK, failure.reason)
    }

    @Test
    fun `asking the user is not treated as a failure`() {
        val device = phone()
        val planner = ScriptedPlanner(PlanDecision.AskUser("Which account did you mean?"))
        val spoken = mutableListOf<String>()

        val result = AgentLoop(device, planner, onSpeak = spoken::add).run("transfer money")

        val needsUser = assertIs<AgentResult.NeedsUser>(result)
        assertContains(needsUser.question, "Which account")
        assertTrue(spoken.any { "Which account" in it }, "the question was never asked aloud")
        assertTrue(device.tapLog.isEmpty(), "nothing should have been tapped")
    }

    @Test
    fun `explains a planner giving up in plain language`() {
        val device = phone()
        val planner = ScriptedPlanner(PlanDecision.GiveUp("no route to the goal"))

        val failure = assertIs<AgentResult.Failed>(AgentLoop(device, planner).run("do the impossible"))

        assertEquals(AgentResult.Failed.Reason.PLANNER_GAVE_UP, failure.reason)
        // What the user hears must be about their goal, not internals.
        assertContains(failure.message, "do the impossible")
        listOf("Selector", "PlanDecision", "handle", "null").forEach {
            assertTrue(it !in failure.message, "leaked '$it': ${failure.message}")
        }
    }
}
