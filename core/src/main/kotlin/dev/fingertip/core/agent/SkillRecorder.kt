package dev.fingertip.core.agent

import dev.fingertip.core.skill.Launch
import dev.fingertip.core.skill.Skill
import dev.fingertip.core.skill.SkillValidator
import dev.fingertip.core.skill.Sleep
import dev.fingertip.core.skill.Step
import dev.fingertip.core.skill.WaitFor

/**
 * Turns a successful agent run into a replayable skill.
 *
 * This is the payoff of the whole design. The reasoning tier is slow and costs a
 * planner call per action; running it once and keeping the result converts that
 * into a per-task-type cost. The next time the user asks, the task replays
 * locally, instantly, offline and identically.
 *
 * It works only because the agent's action vocabulary is already the skill format,
 * so recording is bookkeeping rather than translation.
 */
object SkillRecorder {

    /**
     * Compiles a trajectory into a skill.
     *
     * @param goal the user's own phrasing, kept as the first utterance so the same
     *   request matches next time.
     * @param app package the task belongs to, when known.
     */
    fun record(
        goal: String,
        trajectory: List<Step>,
        app: String? = null,
        id: String? = null,
    ): Skill? {
        val steps = harden(trajectory)
        if (steps.isEmpty()) return null

        // Infer the app from the trajectory before deriving the id, so a recorded
        // skill is namespaced by the app it drives rather than a generic bucket.
        val resolvedApp = app ?: steps.filterIsInstance<Launch>().firstOrNull()?.app

        val skill = Skill(
            id = id ?: deriveId(goal, resolvedApp),
            title = goal.trim().trimEnd('!', '.', '?').replaceFirstChar(Char::titlecase),
            app = resolvedApp,
            utterances = listOf(goal.trim().lowercase()),
            notes = "Recorded automatically from an agent run. Not yet human-reviewed.",
            steps = steps,
        )

        // A recorded skill that cannot pass the same gate as a hand-written one is
        // not worth saving; it would fail confusingly on replay instead.
        return skill.takeIf { SkillValidator.isValid(it) }
    }

    /**
     * Makes a live trajectory safe to replay later.
     *
     * The agent succeeded partly because it re-observed the screen between every
     * action, which absorbed loading time for free. A straight replay has no such
     * pauses, so a bare recording is reliably flaky. Inserting a wait after each
     * navigation restores that tolerance, and fixed sleeps are dropped because
     * they encode one run's timing rather than the app's actual behaviour.
     */
    private fun harden(trajectory: List<Step>): List<Step> {
        val hardened = mutableListOf<Step>()
        trajectory.forEach { step ->
            when (step) {
                is Sleep -> Unit
                is Launch -> {
                    hardened += step
                    // Tapping the first frame after launch is the classic flake:
                    // it is usually still a splash screen.
                    hardened += WaitFor(nextSelector(trajectory, step) ?: return@forEach)
                }
                else -> hardened += step
            }
        }
        return hardened
    }

    /** Selector of the first action following [after], to wait on before acting. */
    private fun nextSelector(trajectory: List<Step>, after: Step): dev.fingertip.core.screen.Selector? {
        val index = trajectory.indexOf(after)
        if (index < 0) return null
        return trajectory.drop(index + 1).firstNotNullOfOrNull { selectorOf(it) }
    }

    private fun selectorOf(step: Step): dev.fingertip.core.screen.Selector? = when (step) {
        is dev.fingertip.core.skill.Tap -> step.selector
        is dev.fingertip.core.skill.LongPress -> step.selector
        is dev.fingertip.core.skill.TypeText -> step.selector
        is dev.fingertip.core.skill.Capture -> step.selector
        is WaitFor -> step.selector
        else -> null
    }

    /** `search google for the weather` -> `agent.search_google_for_the_weather`. */
    fun deriveId(goal: String, app: String? = null): String {
        val slug = goal.trim().lowercase()
            .replace(NON_WORD, "_")
            .trim('_')
            .take(48)
            .ifBlank { "recorded_task" }
        val namespace = app?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "agent"
        return "$namespace.$slug"
    }

    private val NON_WORD = Regex("[^a-z0-9]+")
}
