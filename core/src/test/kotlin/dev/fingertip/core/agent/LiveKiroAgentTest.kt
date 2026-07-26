package dev.fingertip.core.agent

import dev.fingertip.core.screen.Role
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end run against the **real** Kiro CLI.
 *
 * Opt-in: skipped unless `KIRO_API_KEY` is set and `kiro-cli` is on PATH, so the
 * ordinary suite stays hermetic, fast and free. Every other test in this package
 * uses a scripted planner; this one proves the wiring against a live model.
 *
 * Run it with:
 *
 *     KIRO_API_KEY=... ./gradlew :core:test --tests '*LiveKiroAgentTest*'
 *
 * Note it consumes subscription credits, one call per agent step.
 */
class LiveKiroAgentTest {

    private val enabled: Boolean =
        !System.getenv("KIRO_API_KEY").isNullOrBlank() && kiroCliOnPath()

    private fun kiroCliOnPath(): Boolean =
        System.getenv("PATH").orEmpty().split(':').any { dir ->
            java.io.File(dir, "kiro-cli").canExecute()
        }

    /** A launcher, a search app and a results screen. */
    private fun phone() = FakeDevice.build {
        screen(
            "home", "com.android.launcher", title = "Home",
            root = Nodes.container(
                Nodes.button("Phone"),
                Nodes.button("Google"),
                Nodes.button("Camera"),
            ),
        )
        screen(
            "google", "com.google.android.googlequicksearchbox", title = "Google",
            root = Nodes.container(
                Nodes.editText(hint = "Search or type a query", id = "search_box"),
                Nodes.button("Submit search", byDescription = true),
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
        // A live model may open the app by tapping its launcher icon rather than
        // issuing a launch, so both routes must work.
        //
        // Transitions are instant here, unlike the scripted tests. FakeDevice's
        // clock is virtual and only advances when something calls sleep(), whereas
        // a real planner call burns wall-clock time the virtual clock never sees.
        // A delayed transition would therefore never settle, and the agent would
        // keep seeing the old screen. Delay handling is covered by the scripted
        // tests, which control the clock.
        onTap("home", "Google", goTo = "google")
        onTap("google", "Submit search", goTo = "results")
        startAt("home")
    }

    @Test
    fun `real Kiro drives the phone to the answer`() {
        if (!enabled) {
            println("SKIPPED LiveKiroAgentTest: set KIRO_API_KEY and put kiro-cli on PATH to run it.")
            return
        }

        val device = phone()
        val transcript = mutableListOf<String>()
        val planner = KiroCliPlanner(
            config = KiroCliPlanner.Config(
                // Low effort is plenty for choosing one tap, and keeps the run cheap.
                extraArgs = listOf("--effort", "low"),
                timeoutMs = 120_000,
            ),
        )
        val spoken = mutableListOf<String>()

        val result = AgentLoop(
            device = device,
            planner = Planner { request ->
                val decision = planner.next(request)
                transcript += "step ${request.stepNumber}: $decision"
                decision
            },
            config = AgentLoop.Config(maxSteps = 10, totalBudgetMs = 600_000),
            onSpeak = spoken::add,
        ).run("search google for the weather in cairo and tell me the temperature")

        println("=== LIVE KIRO TRANSCRIPT ===")
        transcript.forEach(::println)
        println("=== SPOKEN ===")
        spoken.forEach(::println)
        println("=== final screen: ${device.currentScreen} ===")
        println("=== taps: ${device.tapLog} typed: ${device.typeLog} ===")

        // Assert on outcomes a competent agent must reach, not on an exact
        // trajectory: a live model may legitimately choose a different route.
        assertIs<AgentResult.Success>(result, "live run did not succeed. Transcript:\n" + transcript.joinToString("\n"))
        assertTrue(
            device.currentScreen == "results",
            "the agent never reached the results screen. Transcript:\n" + transcript.joinToString("\n"),
        )
        assertTrue(
            spoken.any { "34" in it },
            "the temperature was never spoken. Spoken: $spoken",
        )
    }

    @Test
    fun `real Kiro returns a well formed decision for a single screen`() {
        if (!enabled) {
            println("SKIPPED: no KIRO_API_KEY or kiro-cli on PATH.")
            return
        }

        val device = phone()
        val snapshot = device.snapshot()
        val redacted = dev.fingertip.core.privacy.Redactor().redact(snapshot)
        val request = PlanRequest(
            goal = "open the Google app",
            screen = redacted,
            wire = dev.fingertip.core.screen.ScreenSerializer().serialize(redacted),
            history = emptyList(),
            stepNumber = 1,
        )

        val decision = KiroCliPlanner(
            config = KiroCliPlanner.Config(extraArgs = listOf("--effort", "low")),
        ).next(request)

        println("live decision: $decision")
        // Anything but a parse failure means the prompt and codec agree with the
        // real model's output format.
        assertTrue(
            decision !is PlanDecision.GiveUp || "unusable" !in decision.reason,
            "Kiro's reply could not be parsed: $decision",
        )
        if (decision is PlanDecision.Act) {
            val step = decision.step
            assertTrue(
                step is dev.fingertip.core.skill.Tap || step is dev.fingertip.core.skill.Launch,
                "expected a tap or launch to open an app, got $step",
            )
            // It must reference something genuinely on screen.
            if (step is dev.fingertip.core.skill.Tap) {
                assertTrue(
                    step.selector.findOne(snapshot) != null ||
                        step.selector.role == Role.BUTTON,
                    "selector matches nothing on the real screen: ${step.selector.describe()}",
                )
            }
        }
    }
}
