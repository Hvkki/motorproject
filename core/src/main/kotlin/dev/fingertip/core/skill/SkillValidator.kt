package dev.fingertip.core.skill

import dev.fingertip.core.speech.SpeechFormatter

/**
 * Static checks on a skill, before it ever touches a device.
 *
 * This is the gate for machine-generated skills. When a reasoning agent writes a
 * skill and opens a pull request, CI runs this first: it catches the authoring
 * mistakes an agent actually makes — speaking a variable it never captured,
 * tapping before waiting, referencing the wrong package — without needing an
 * emulator. Cheap, deterministic review that a human can trust.
 */
object SkillValidator {

    data class Problem(
        val severity: Severity,
        val stepIndex: Int?,
        val message: String,
    ) {
        override fun toString(): String {
            val where = stepIndex?.let { "step $it: " } ?: ""
            return "[${severity.name.lowercase()}] $where$message"
        }
    }

    enum class Severity {
        /** Will misbehave at runtime. Block the merge. */
        ERROR,

        /** Likely to be flaky or unpleasant. Worth a human look. */
        WARNING,
    }

    private val speech = SpeechFormatter()

    fun validate(skill: Skill): List<Problem> {
        val problems = mutableListOf<Problem>()

        if (skill.title.isBlank()) {
            problems += Problem(Severity.ERROR, null, "Title is blank; it is spoken to the user")
        }
        if (skill.utterances.isEmpty()) {
            problems += Problem(
                Severity.WARNING, null,
                "No utterances, so this skill can only be triggered by its title",
            )
        }

        // Track captures as they become available, in order.
        val available = mutableSetOf<String>()
        var sawLaunch = false

        skill.steps.forEachIndexed { index, step ->
            when (step) {
                is Launch -> {
                    sawLaunch = true
                    if (skill.app != null && step.app != skill.app) {
                        problems += Problem(
                            Severity.WARNING, index,
                            "Launches ${step.app} but the skill declares app=${skill.app}",
                        )
                    }
                }

                is Capture -> {
                    if (step.name.isBlank()) {
                        problems += Problem(Severity.ERROR, index, "Capture has a blank name")
                    }
                    if (!available.add(step.name)) {
                        problems += Problem(
                            Severity.WARNING, index,
                            "Capture '${step.name}' overwrites an earlier value",
                        )
                    }
                }

                is Speak -> {
                    val missing = speech.placeholdersIn(step.template) - available
                    if (missing.isNotEmpty()) {
                        problems += Problem(
                            Severity.ERROR, index,
                            "Speaks {${missing.sorted().joinToString("}, {")}} " +
                                "before any capture defines it",
                        )
                    }
                    if (step.template.isBlank()) {
                        problems += Problem(Severity.ERROR, index, "Speak template is empty")
                    }
                }

                is TypeText -> {
                    val missing = speech.placeholdersIn(step.text) - available
                    if (missing.isNotEmpty()) {
                        problems += Problem(
                            Severity.ERROR, index,
                            "Types {${missing.sorted().joinToString("}, {")}} before it is captured",
                        )
                    }
                }

                is Sleep -> {
                    if (step.millis > MAX_REASONABLE_SLEEP_MS) {
                        problems += Problem(
                            Severity.WARNING, index,
                            "Sleeps ${step.millis}ms; prefer waitFor so the skill adapts to real load time",
                        )
                    }
                }

                else -> Unit
            }

            // A tap immediately after launching is the classic flaky pattern:
            // the first frame is usually a splash screen.
            if (step is Tap && index > 0 && skill.steps[index - 1] is Launch) {
                problems += Problem(
                    Severity.WARNING, index,
                    "Taps directly after launch; insert a waitFor so the screen has settled",
                )
            }
        }

        if (skill.app != null && !sawLaunch) {
            problems += Problem(
                Severity.WARNING, null,
                "Declares app=${skill.app} but never launches it; assumes it is already open",
            )
        }

        val speaksSomething = skill.steps.any { it is Speak }
        val capturesSomething = skill.steps.any { it is Capture }
        if (capturesSomething && !speaksSomething) {
            problems += Problem(
                Severity.WARNING, null,
                "Captures values but never speaks; a blind user gets no feedback",
            )
        }

        return problems
    }

    /** True when nothing blocks the merge. */
    fun isValid(skill: Skill): Boolean = validate(skill).none { it.severity == Severity.ERROR }

    private const val MAX_REASONABLE_SLEEP_MS = 3_000L
}
