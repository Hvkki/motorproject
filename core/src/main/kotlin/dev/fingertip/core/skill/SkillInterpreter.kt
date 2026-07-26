package dev.fingertip.core.skill

import dev.fingertip.core.device.Device
import dev.fingertip.core.device.ScrollDirection
import dev.fingertip.core.privacy.RedactedSnapshot
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.ScreenSnapshot
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.speech.SpeechFormatter

/** Why a skill stopped. Drives both what the user hears and whether we escalate. */
enum class FailureReason {
    /** The element simply is not there. Usually means the app's UI changed. */
    ELEMENT_NOT_FOUND,

    /** A [WaitFor] expired. Often a slow network rather than a broken skill. */
    TIMEOUT,

    /** The element was found but the OS refused the action. */
    ACTION_REJECTED,

    APP_LAUNCH_FAILED,

    /** An [Assert] did not hold: we are somewhere unexpected. Stop rather than tap blindly. */
    ASSERTION_FAILED,

    /** A [Speak] template referenced a variable no [Capture] produced. Authoring bug. */
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
     * the expensive reasoning agent to repair or regenerate the skill.
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
 * Executes [Skill]s against a [Device], deterministically and with no model call.
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
        /**
         * Grace period applied when an element is not immediately present.
         *
         * Without this, virtually every skill is flaky: taps land while the screen
         * is still animating. With it, most explicit waits become unnecessary.
         */
        val implicitWaitMs: Long = 2_000,
        val pollMs: Long = 200,
        /** Whole-skill ceiling. A blind user must never be trapped in a silent agent. */
        val totalBudgetMs: Long = 60_000,
        /** Announce each step. Off by default: too chatty for short skills. */
        val announceSteps: Boolean = false,
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

            val outcome = runStep(skill, index, step, captures, spoken, startedAt)
            if (outcome != null) return outcome
        }

        return SkillResult.Success(
            skillId = skill.id,
            captures = captures,
            spoken = spoken,
            elapsedMs = device.nowMs() - startedAt,
        )
    }

    /** Returns null to continue, or a [SkillResult.Failure] to abort. */
    private fun runStep(
        skill: Skill,
        index: Int,
        step: Step,
        captures: MutableMap<String, String>,
        spoken: MutableList<String>,
        startedAt: Long,
    ): SkillResult? {
        when (step) {
            is Launch -> {
                if (!device.launchApp(step.app)) {
                    return fail(
                        skill, index, step, FailureReason.APP_LAUNCH_FAILED,
                        detail = "Could not launch ${step.app}",
                        startedAt = startedAt,
                    )
                }
                // Confirm the app actually came forward before proceeding.
                val arrived = awaitCondition(config.implicitWaitMs) { it.packageName == step.app }
                if (arrived == null) {
                    return fail(
                        skill, index, step, FailureReason.TIMEOUT,
                        detail = "${step.app} did not come to the foreground",
                        startedAt = startedAt,
                    )
                }
            }

            is WaitFor -> {
                if (await(step.selector, step.timeoutMs, step.pollMs) == null) {
                    return fail(
                        skill, index, step, FailureReason.TIMEOUT,
                        detail = "Timed out after ${step.timeoutMs}ms waiting for ${step.selector.describe()}",
                        startedAt = startedAt,
                    )
                }
            }

            is Tap -> {
                val node = resolve(step.selector)
                    ?: return notFound(skill, index, step, step.selector, startedAt)
                if (!device.tap(node.handle)) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "Tap rejected on handle ${node.handle} (${step.selector.describe()})",
                        startedAt = startedAt,
                    )
                }
            }

            is LongPress -> {
                val node = resolve(step.selector)
                    ?: return notFound(skill, index, step, step.selector, startedAt)
                if (!device.longPress(node.handle)) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "Long press rejected on handle ${node.handle}",
                        startedAt = startedAt,
                    )
                }
            }

            is TypeText -> {
                val node = resolve(step.selector)
                    ?: return notFound(skill, index, step, step.selector, startedAt)
                val value = speech.interpolate(step.text, captures)
                if (!device.setText(node.handle, value)) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "setText rejected on handle ${node.handle}",
                        startedAt = startedAt,
                    )
                }
            }

            is Scroll -> {
                val node = resolve(step.selector)
                    ?: return notFound(skill, index, step, step.selector, startedAt)
                if (!device.scroll(node.handle, step.direction)) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "Cannot scroll ${step.direction} on handle ${node.handle}",
                        startedAt = startedAt,
                    )
                }
            }

            is ScrollUntil -> {
                if (resolve(step.target, waitMs = 0) != null) return null
                repeat(step.maxScrolls) {
                    // Re-resolve every iteration: scrolling can recycle views, so a
                    // handle captured before the first scroll may already be stale.
                    val container = resolve(step.container)
                        ?: return notFound(skill, index, step, step.container, startedAt)
                    if (!device.scroll(container.handle, step.direction)) {
                        // Hit the end of the list without finding it.
                        return fail(
                            skill, index, step, FailureReason.ELEMENT_NOT_FOUND,
                            detail = "Reached end of list without finding ${step.target.describe()}",
                            startedAt = startedAt,
                        )
                    }
                    device.sleep(config.pollMs)
                    if (resolve(step.target, waitMs = 0) != null) return null
                }
                return fail(
                    skill, index, step, FailureReason.ELEMENT_NOT_FOUND,
                    detail = "Gave up after ${step.maxScrolls} scrolls looking for ${step.target.describe()}",
                    startedAt = startedAt,
                )
            }

            is Back -> {
                if (!device.pressBack()) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "Back gesture rejected", startedAt = startedAt,
                    )
                }
            }

            is Home -> {
                if (!device.pressHome()) {
                    return fail(
                        skill, index, step, FailureReason.ACTION_REJECTED,
                        detail = "Home gesture rejected", startedAt = startedAt,
                    )
                }
            }

            is Capture -> {
                val node = resolve(step.selector)
                if (node == null) {
                    if (step.optional) {
                        captures[step.name] = ""
                        return null
                    }
                    return notFound(skill, index, step, step.selector, startedAt)
                }
                // Deliberately the RAW value: reading the user their own screen is the product.
                captures[step.name] = when (step.field) {
                    Field.TEXT -> node.text.orEmpty()
                    Field.DESC -> node.contentDescription.orEmpty()
                    Field.LABEL -> node.label.orEmpty()
                }
            }

            is Speak -> {
                val missing = speech.placeholdersIn(step.template) - captures.keys
                if (missing.isNotEmpty()) {
                    return fail(
                        skill, index, step, FailureReason.MISSING_CAPTURE,
                        detail = "Template references unknown capture(s): ${missing.sorted().joinToString()}",
                        startedAt = startedAt,
                    )
                }
                val utterance = speech.interpolate(step.template, captures)
                if (utterance.isNotBlank()) {
                    spoken += utterance
                    onSpeak(utterance)
                }
            }

            is Sleep -> device.sleep(step.millis)

            is Assert -> {
                val found = resolve(step.selector, waitMs = if (step.present) config.implicitWaitMs else 0) != null
                if (found != step.present) {
                    return fail(
                        skill, index, step, FailureReason.ASSERTION_FAILED,
                        detail = "Expected present=${step.present} for ${step.selector.describe()}, was $found",
                        startedAt = startedAt,
                    )
                }
            }
        }
        return null
    }

    /** Finds a node, polling for up to [waitMs] to absorb animation and load time. */
    private fun resolve(selector: Selector, waitMs: Long = config.implicitWaitMs): Node? =
        await(selector, waitMs, config.pollMs)

    private fun await(selector: Selector, timeoutMs: Long, pollMs: Long): Node? {
        val deadline = device.nowMs() + timeoutMs
        while (true) {
            selector.findOne(device.snapshot())?.let { return it }
            if (device.nowMs() >= deadline) return null
            device.sleep(pollMs.coerceAtLeast(1))
        }
    }

    private fun awaitCondition(timeoutMs: Long, predicate: (ScreenSnapshot) -> Boolean): ScreenSnapshot? {
        val deadline = device.nowMs() + timeoutMs
        while (true) {
            val snapshot = device.snapshot()
            if (predicate(snapshot)) return snapshot
            if (device.nowMs() >= deadline) return null
            device.sleep(config.pollMs)
        }
    }

    private fun notFound(skill: Skill, index: Int, step: Step, selector: Selector, startedAt: Long) =
        fail(
            skill, index, step, FailureReason.ELEMENT_NOT_FOUND,
            detail = "No node matched ${selector.describe()}",
            startedAt = startedAt,
        )

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
