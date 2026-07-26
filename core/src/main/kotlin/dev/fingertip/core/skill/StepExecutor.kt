package dev.fingertip.core.skill

import dev.fingertip.core.device.Device
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.ScreenSnapshot
import dev.fingertip.core.screen.Selector
import dev.fingertip.core.speech.SpeechFormatter

/** Outcome of a single [Step]. */
sealed interface StepOutcome {
    data object Ok : StepOutcome

    data class Failed(val reason: FailureReason, val detail: String) : StepOutcome
}

/**
 * Executes one [Step] against a [Device].
 *
 * Shared deliberately by both tiers. [SkillInterpreter] replays a fixed list of
 * steps; the reasoning agent decides them one at a time. Running both through the
 * same code means a step behaves identically whether it was written by a human,
 * compiled into a skill, or chosen by a model seconds ago — and a trajectory the
 * agent discovers can be saved as a skill with no translation layer.
 */
class StepExecutor(
    private val device: Device,
    private val speech: SpeechFormatter = SpeechFormatter(),
    private val config: Config = Config(),
    private val onSpeak: (String) -> Unit = {},
) {
    data class Config(
        /**
         * Grace period when an element is not immediately present.
         *
         * Without it nearly every action is flaky, because taps land while the
         * screen is still animating.
         */
        val implicitWaitMs: Long = 2_000,
        val pollMs: Long = 200,
    )

    fun execute(step: Step, captures: MutableMap<String, String>): StepOutcome {
        when (step) {
            is Launch -> {
                if (!device.launchApp(step.app)) {
                    return StepOutcome.Failed(
                        FailureReason.APP_LAUNCH_FAILED,
                        "Could not launch ${step.app}",
                    )
                }
                if (awaitCondition(config.implicitWaitMs) { it.packageName == step.app } == null) {
                    return StepOutcome.Failed(
                        FailureReason.TIMEOUT,
                        "${step.app} did not come to the foreground",
                    )
                }
            }

            is WaitFor -> {
                if (await(step.selector, step.timeoutMs, step.pollMs) == null) {
                    return StepOutcome.Failed(
                        FailureReason.TIMEOUT,
                        "Timed out after ${step.timeoutMs}ms waiting for ${step.selector.describe()}",
                    )
                }
            }

            is Tap -> {
                val node = resolve(step.selector) ?: return notFound(step.selector)
                if (!device.tap(node.handle)) {
                    return StepOutcome.Failed(
                        FailureReason.ACTION_REJECTED,
                        "Tap rejected on handle ${node.handle} (${step.selector.describe()})",
                    )
                }
            }

            is LongPress -> {
                val node = resolve(step.selector) ?: return notFound(step.selector)
                if (!device.longPress(node.handle)) {
                    return StepOutcome.Failed(
                        FailureReason.ACTION_REJECTED,
                        "Long press rejected on handle ${node.handle}",
                    )
                }
            }

            is TypeText -> {
                val node = resolve(step.selector) ?: return notFound(step.selector)
                if (!device.setText(node.handle, speech.interpolate(step.text, captures))) {
                    return StepOutcome.Failed(
                        FailureReason.ACTION_REJECTED,
                        "setText rejected on handle ${node.handle}",
                    )
                }
            }

            is Scroll -> {
                val node = resolve(step.selector) ?: return notFound(step.selector)
                if (!device.scroll(node.handle, step.direction)) {
                    return StepOutcome.Failed(
                        FailureReason.ACTION_REJECTED,
                        "Cannot scroll ${step.direction} on handle ${node.handle}",
                    )
                }
            }

            is ScrollUntil -> {
                if (resolve(step.target, waitMs = 0) != null) return StepOutcome.Ok
                repeat(step.maxScrolls) {
                    // Re-resolve each iteration: scrolling recycles views, so a
                    // handle from before the first scroll may already be stale.
                    val container = resolve(step.container) ?: return notFound(step.container)
                    if (!device.scroll(container.handle, step.direction)) {
                        return StepOutcome.Failed(
                            FailureReason.ELEMENT_NOT_FOUND,
                            "Reached end of list without finding ${step.target.describe()}",
                        )
                    }
                    device.sleep(config.pollMs)
                    if (resolve(step.target, waitMs = 0) != null) return StepOutcome.Ok
                }
                return StepOutcome.Failed(
                    FailureReason.ELEMENT_NOT_FOUND,
                    "Gave up after ${step.maxScrolls} scrolls looking for ${step.target.describe()}",
                )
            }

            is Back -> if (!device.pressBack()) {
                return StepOutcome.Failed(FailureReason.ACTION_REJECTED, "Back gesture rejected")
            }

            is Home -> if (!device.pressHome()) {
                return StepOutcome.Failed(FailureReason.ACTION_REJECTED, "Home gesture rejected")
            }

            is Capture -> {
                val node = resolve(step.selector)
                if (node == null) {
                    if (!step.optional) return notFound(step.selector)
                    captures[step.name] = ""
                    return StepOutcome.Ok
                }
                // Raw on purpose: reading the user their own screen is the product.
                captures[step.name] = when (step.field) {
                    Field.TEXT -> node.text.orEmpty()
                    Field.DESC -> node.contentDescription.orEmpty()
                    Field.LABEL -> node.label.orEmpty()
                }
            }

            is Speak -> {
                val missing = speech.placeholdersIn(step.template) - captures.keys
                if (missing.isNotEmpty()) {
                    return StepOutcome.Failed(
                        FailureReason.MISSING_CAPTURE,
                        "Template references unknown capture(s): ${missing.sorted().joinToString()}",
                    )
                }
                val utterance = speech.interpolate(step.template, captures)
                if (utterance.isNotBlank()) onSpeak(utterance)
            }

            is Sleep -> device.sleep(step.millis)

            is Assert -> {
                val waitMs = if (step.present) config.implicitWaitMs else 0
                val found = resolve(step.selector, waitMs) != null
                if (found != step.present) {
                    return StepOutcome.Failed(
                        FailureReason.ASSERTION_FAILED,
                        "Expected present=${step.present} for ${step.selector.describe()}, was $found",
                    )
                }
            }
        }
        return StepOutcome.Ok
    }

    /** Finds a node, polling to absorb animation and load time. */
    fun resolve(selector: Selector, waitMs: Long = config.implicitWaitMs): Node? =
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

    private fun notFound(selector: Selector) =
        StepOutcome.Failed(FailureReason.ELEMENT_NOT_FOUND, "No node matched ${selector.describe()}")
}
