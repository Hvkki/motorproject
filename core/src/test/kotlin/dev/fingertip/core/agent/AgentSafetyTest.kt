package dev.fingertip.core.agent

import dev.fingertip.core.screen.Selector
import dev.fingertip.core.skill.Back
import dev.fingertip.core.skill.Tap
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The confirmation gate on irreversible actions.
 *
 * A sighted user can see "Send £400" under their thumb and stop. Someone relying
 * on a screen reader is trusting the agent's account of the screen, so an agent
 * that taps first and narrates afterwards can spend money with no opportunity to
 * intervene. These tests pin that behaviour down.
 */
class AgentSafetyTest {

    private fun bankingApp() = FakeDevice.build {
        screen(
            "transfer", "com.example.bank", title = "Transfer",
            root = Nodes.container(
                Nodes.text("To: Mum"),
                Nodes.text("Amount: 400.00"),
                Nodes.button("Send money"),
                Nodes.button("Back to accounts"),
            ),
        )
    }

    @Test
    fun `refuses an irreversible action when confirmation is denied`() {
        val device = bankingApp()
        val planner = Planner { PlanDecision.Act(Tap(Selector(text = "Send money")), "send it") }
        val spoken = mutableListOf<String>()

        val result = AgentLoop(
            device, planner,
            onSpeak = spoken::add,
            confirm = { false },
        ).run("send 400 to mum")

        val refused = assertIs<AgentResult.Refused>(result, "expected refusal, got $result")
        assertContains(refused.question, "Send money")
        // The critical assertion: nothing was committed.
        assertTrue(device.tapLog.isEmpty(), "an unconfirmed payment was tapped: ${device.tapLog}")
        assertTrue(spoken.any { "irreversible" in it }, "the user was not warned: $spoken")
    }

    @Test
    fun `proceeds with an irreversible action once confirmed`() {
        val device = bankingApp()
        val planner = object : Planner {
            var calls = 0
            override fun next(request: PlanRequest): PlanDecision {
                calls++
                return if (calls == 1) {
                    PlanDecision.Act(Tap(Selector(text = "Send money")), "send it")
                } else {
                    PlanDecision.Done("Sent.")
                }
            }
        }
        val questions = mutableListOf<String>()

        val result = AgentLoop(
            device, planner,
            confirm = { question -> questions += question; true },
        ).run("send 400 to mum")

        assertIs<AgentResult.Success>(result)
        assertEquals(listOf("Send money"), device.tapLog)
        assertEquals(1, questions.size, "the user should be asked exactly once")
    }

    @Test
    fun `denies by default when no confirmer is wired up`() {
        // Forgetting to connect the prompt must not silently authorise spending.
        val device = bankingApp()
        val planner = Planner { PlanDecision.Act(Tap(Selector(text = "Send money")), "send it") }

        val result = AgentLoop(device, planner).run("send money")

        assertIs<AgentResult.Refused>(result)
        assertTrue(device.tapLog.isEmpty())
    }

    @Test
    fun `does not gate ordinary navigation`() {
        // Over-gating trains the user to say yes without listening, which is worse
        // than no gate at all.
        val device = bankingApp()
        val planner = object : Planner {
            var calls = 0
            override fun next(request: PlanRequest): PlanDecision {
                calls++
                return if (calls == 1) {
                    PlanDecision.Act(Tap(Selector(text = "Back to accounts")), "go back")
                } else {
                    PlanDecision.Done("Back on accounts.")
                }
            }
        }
        var asked = 0

        val result = AgentLoop(device, planner, confirm = { asked++; true }).run("go back")

        assertIs<AgentResult.Success>(result)
        assertEquals(0, asked, "navigation should not require confirmation")
        assertEquals(listOf("Back to accounts"), device.tapLog)
    }

    @Test
    fun `does not gate non-committing steps`() {
        val device = bankingApp()
        val planner = object : Planner {
            var calls = 0
            override fun next(request: PlanRequest) =
                if (calls++ == 0) PlanDecision.Act(Back, "leave") else PlanDecision.Done("done")
        }
        var asked = 0

        assertIs<AgentResult.Success>(AgentLoop(device, planner, confirm = { asked++; true }).run("leave"))
        assertEquals(0, asked)
    }

    @Test
    fun `risk policy matches whole words only`() {
        val policy = RiskPolicy()
        val send = Nodes.button("Send message")
        val sender = Nodes.button("Sender details")

        assertEquals("send", policy.irreversibleReason(Tap(Selector(text = "x")), send))
        // "sender" is not "send"; gating it would be noise.
        assertEquals(null, policy.irreversibleReason(Tap(Selector(text = "x")), sender))
    }

    @Test
    fun `risk policy accepts caller-supplied phrases`() {
        val policy = RiskPolicy(extraPhrases = setOf("book"))

        assertEquals(
            "book",
            policy.irreversibleReason(Tap(Selector(text = "x")), Nodes.button("Book flight")),
        )
    }

    @Test
    fun `risk policy ignores steps that cannot commit anything`() {
        val policy = RiskPolicy()

        assertEquals(null, policy.irreversibleReason(Back, Nodes.button("Send money")))
    }
}
