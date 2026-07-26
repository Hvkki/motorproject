package dev.fingertip.core.skill

import dev.fingertip.core.device.Device
import dev.fingertip.core.privacy.RedactedSnapshot
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.speech.SpeechFormatter

/** Why a skill stopped. Drives both what the user hears and whether we escalate. */
enum class FailureReason {
    /** The element simply is not there. Usually means the app's UI changed. */
    ELEMENT_NOT_FOUND,

    /** A wait expired. Often a slow network rather than a broken skill. */
    TIMEOUT,

    /** The element was found but the OS refused the action. */
    ACTION_REJECTED,

    APP_LAUNCH_FAILED,

    /** An assertion did not hold: we are somewhere unexpected. Stop rather than tap blindly. */
    ASSERTION_FAILED,

    /** A template referenced a variable no capture produced. Authoring bug. */
    MISSING_CAPTURE,

    /** Exceeded the overall budget; guards against loops. */
    BUDGET_EXCEEDED,
}

sealed interface SkillResult {
    val skillId: String

    data class Success(
        override val skillId: String,
        /** Values read off the screen. Raw, on-device only — never auto-transmitted. */
        val captures: Map<String, String>,
        /** Everything spoken, in order. */
        val spoken: List<String>,
        val elapsedMs: Long,
    ) : SkillResult

    /**
     * A failure is not just an error — it is the **escalation signal**.
     *
     * [screen] is redacted precisely because this payload is what gets handed to
     * the reasoning agent to repair or regenerate the skill.
     */
    data class Failure(
        override val skillId: String,
        val stepIndex: Int,
        val step: Step,
        val reason: FailureReason,
        /** Speech-ready explanation. */
        val message: String,
        /** Technical detail for the repair agent and for bug reports. */
        val detail: String,
        val screen: RedactedSnapshot?,
        val elapsedMs: Long,
    ) : SkillResult
}

/**
 * Replays [Skill]s deterministically, with no model call.
 *
 * Everything expensive and non-deterministic lives outside this class. That
 * separation is what makes the fast path fast: a known task is a sequence of
 * accessibility actions and nothing else.
 */
class SkillInterpreter(
    private val device: Device,
    private val redactor: Redactor = Redactor(),
    private val speech: SpeechFormatter = SpeechFormatter(),
    private val config: Config = Config(),
    /** Invoked as each utterance is produced, so TTS can start before the skill ends. */
    private val onSpeak: (String) -> Unit = {},
    /** Optional progress hook for slow steps (earcons, haptics). */
    private val onProgress: (String) -> Unit = {},
) {
    data class Config(
        val implicitWaitMs: Long = 2_000,
        val pollMs: Long = 200,
        /** Whole-skill ceiling. A blind user must never be trapped in a silent agent. */
        val totalBudgetMs: Long = 60_000,
        /** Announce each step. Off by default: too chatty for short skills. */
        val announceSteps: Boolean = false,
    )

    private val executor = StepExecutor(
        device = device,
        speech = speech,
        config = StepExecutor.Config(
            implicitWaitMs = config.implicitWaitMs,
            pollMs = config.pollMs,
        ),
        onSpeak = onSpeak,
    )

    fun execute(skill: Skill): SkillResult {
        val startedAt = device.nowMs()
        val captures = LinkedHashMap<String, String>()
        val spoken = mutableListOf<String>()

        skill.steps.forEachIndexed { index, step ->
            if (device.nowMs() - startedAt > config.totalBudgetMs) {
                return fail(
                    skill, index, step, FailureReason.BUDGET_EXCEEDED,
                    detail = "Exceeded ${config.totalBudgetMs}ms budget",
                    startedAt = startedAt,
                )
            }
            if (config.announceSteps) onProgress(speech.describeProgress(step.describe))

            // Capture spoken output by wrapping the executor's callback per step.
            val before = captures.toMap()
            when (val outcome = executeTracking(step, captures, spoken)) {
                is StepOutcome.Ok -> Unit
                is StepOutcome.Failed -> return fail(
                    skill, index, step, outcome.reason, outcome.detail, startedAt,
                )
            }
            check(captures.keys.containsAll(before.keys)) { "captures must not shrink" }
        }

        return SkillResult.Success(
            skillId = skill.id,
            captures = captures,
            spoken = spoken,
            elapsedMs = device.nowMs() - startedAt,
        )
    }

    /**
     * Runs a step, recording anything spoken.
     *
     * A dedicated executor per Speak step keeps the interpreter's `spoken` list in
     * order while still forwarding to the caller's TTS immediately.
     */
    private fun executeTracking(
        step: Step,
        captures: MutableMap<String, String>,
        spoken: MutableList<String>,
    ): StepOutcome {
        if (step !is Speak) return executor.execute(step, captures)

        val recording = StepExecutor(
            device = device,
            speech = speech,
            config = StepExecutor.Config(config.implicitWaitMs, config.pollMs),
            onSpeak = { utterance ->
                spoken += utterance
                onSpeak(utterance)
            },
        )
        return recording.execute(step, captures)
    }

    private fun fail(
        skill: Skill,
        index: Int,
        step: Step,
        reason: FailureReason,
        detail: String,
        startedAt: Long,
    ): SkillResult.Failure = SkillResult.Failure(
        skillId = skill.id,
        stepIndex = index,
        step = step,
        reason = reason,
        message = speech.describeFailure(skill.title, step.describe, skill.app),
        // Scrubbed: this string travels with bug reports and repair requests.
        detail = redactor.redactText(detail),
        screen = runCatching { redactor.redact(device.snapshot()) }.getOrNull(),
        elapsedMs = device.nowMs() - startedAt,
    )
}
