package dev.fingertip.core.agent

import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.skill.Capture
import dev.fingertip.core.skill.Launch
import dev.fingertip.core.skill.SkillInterpreter
import dev.fingertip.core.skill.SkillJson
import dev.fingertip.core.skill.SkillLibrary
import dev.fingertip.core.skill.SkillResult
import dev.fingertip.core.skill.SkillValidator
import dev.fingertip.core.skill.Speak
import dev.fingertip.core.skill.Tap
import dev.fingertip.core.skill.TypeText
import dev.fingertip.core.skill.WaitFor
import dev.fingertip.core.testing.FakeDevice
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The economic argument of the architecture, as an executable test.
 *
 * The expensive reasoning tier runs **once** for a novel task and emits a skill.
 * Every later run replays that skill with **zero planner calls**, so the cost is
 * per task *type* rather than per invocation. That is what makes an agent someone
 * uses dozens of times a day affordable, fast and usable offline.
 */
class LearnThenReplayTest {

    private fun phone() = FakeDevice.build {
        screen(
            "home", "com.android.launcher", title = "Home",
            root = Nodes.container(Nodes.button("Google"), Nodes.button("Phone")),
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
                    label = "Results",
                ),
            ),
        )
        app("com.google.android.googlequicksearchbox", "google")
        onTap("google", "Search", goTo = "results", delayMs = 400)
        startAt("home")
    }

    private val goal = "search google for the weather in cairo"

    private fun teachingPlanner(): Planner {
        val script = listOf(
            PlanDecision.Act(Launch("com.google.android.googlequicksearchbox"), "open Google"),
            PlanDecision.Act(TypeText(Selector(viewId = "search_box"), "weather in Cairo"), "type"),
            PlanDecision.Act(Tap(Selector(desc = "Search", role = Role.BUTTON)), "submit"),
            PlanDecision.Act(Capture(Selector(role = Role.LIST_ITEM, index = 0), name = "answer"), "read"),
            PlanDecision.Act(Speak("{answer}"), "speak"),
            PlanDecision.Done("That's the top result."),
        )
        var index = 0
        return Planner { script.getOrElse(index++) { PlanDecision.GiveUp("exhausted") } }
    }

    @Test
    fun `learns a novel task once then replays it with no planner calls`() {
        // --- first run: expensive, reasons its way through -------------------
        val learning = phone()
        var plannerCalls = 0
        val countingPlanner = teachingPlanner().let { inner ->
            Planner { request -> plannerCalls++; inner.next(request) }
        }

        val learned = assertIs<AgentResult.Success>(
            AgentLoop(learning, countingPlanner).run(goal),
        )
        assertTrue(plannerCalls > 1, "the first run should require reasoning")

        // --- compile the trajectory into a skill ----------------------------
        val skill = assertNotNull(
            SkillRecorder.record(goal, learned.trajectory, app = "com.google.android.googlequicksearchbox"),
            "the successful run produced no skill",
        )
        assertTrue(SkillValidator.isValid(skill), SkillValidator.validate(skill).toString())

        // --- second run: replay on a fresh phone, no planner at all ---------
        val replaying = phone()
        val spoken = mutableListOf<String>()
        val replayed = SkillInterpreter(replaying, onSpeak = spoken::add).execute(skill)

        val success = assertIs<SkillResult.Success>(replayed, "replay failed: $replayed")
        assertEquals("results", replaying.currentScreen)
        assertEquals(listOf("Search"), replaying.tapLog)
        assertTrue(
            spoken.any { "34 degrees" in it },
            "the replayed skill never spoke the answer: $spoken",
        )
        assertEquals(learned.trajectory.count { it is Speak }, success.spoken.size)
    }

    @Test
    fun `the recorded skill survives a round trip through JSON`() {
        // Skills are shipped as files and reviewed in pull requests, so the
        // recording has to be durable, not just in-memory.
        val device = phone()
        val learned = assertIs<AgentResult.Success>(AgentLoop(device, teachingPlanner()).run(goal))
        val skill = assertNotNull(SkillRecorder.record(goal, learned.trajectory))

        val decoded = SkillJson.decode(SkillJson.encode(skill))

        assertEquals(skill, decoded)
        val replay = SkillInterpreter(phone()).execute(decoded)
        assertIs<SkillResult.Success>(replay)
    }

    @Test
    fun `the recorded skill is reachable by the phrase the user actually said`() {
        val device = phone()
        val learned = assertIs<AgentResult.Success>(AgentLoop(device, teachingPlanner()).run(goal))
        val skill = assertNotNull(SkillRecorder.record(goal, learned.trajectory))

        val library = SkillLibrary(listOf(skill))
        val match = assertNotNull(
            library.match(goal),
            "the user's own phrasing does not match the skill recorded from it",
        )

        assertEquals(skill.id, match.skill.id)
        // A close paraphrase should also hit the fast path.
        assertNotNull(library.match("search google for weather in cairo"))
    }

    @Test
    fun `recording inserts a wait after launch so replay is not flaky`() {
        // The agent tolerated load time for free by re-observing between actions.
        // A straight replay has no such pause, so the recording must add one.
        val device = phone()
        val learned = assertIs<AgentResult.Success>(AgentLoop(device, teachingPlanner()).run(goal))

        val skill = assertNotNull(SkillRecorder.record(goal, learned.trajectory))

        val launchIndex = skill.steps.indexOfFirst { it is Launch }
        assertTrue(launchIndex >= 0, "no launch step recorded")
        assertIs<WaitFor>(
            skill.steps[launchIndex + 1],
            "a launch must be followed by a wait, otherwise replay taps a splash screen",
        )
        assertTrue(
            SkillValidator.validate(skill).none { "waitFor" in it.message },
            "validator still flags the recorded skill as flaky: ${SkillValidator.validate(skill)}",
        )
    }

    @Test
    fun `derives a readable id and title from the goal`() {
        val skill = assertNotNull(
            SkillRecorder.record(
                "Search Google for the weather!",
                listOf(Launch("com.google.android.googlequicksearchbox"), Tap(Selector(desc = "Search"))),
            ),
        )

        assertEquals("googlequicksearchbox.search_google_for_the_weather", skill.id)
        assertContains(skill.title, "Search Google")
        // Recorded skills must announce that no human has reviewed them yet.
        assertContains(skill.notes ?: "", "Not yet human-reviewed")
    }

    @Test
    fun `refuses to record a run that produced nothing`() {
        assertEquals(null, SkillRecorder.record(goal, emptyList()))
    }
}
