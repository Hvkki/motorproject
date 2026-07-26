package dev.fingertip.core.agent

import dev.fingertip.core.device.Device
import dev.fingertip.core.privacy.RedactedSnapshot
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.ScreenSerializer
import dev.fingertip.core.skill.FailureReason
import dev.fingertip.core.skill.LongPress
import dev.fingertip.core.skill.Speak
import dev.fingertip.core.skill.Step
import dev.fingertip.core.skill.StepExecutor
import dev.fingertip.core.skill.StepOutcome
import dev.fingertip.core.skill.Tap
import dev.fingertip.core.speech.SpeechFormatter

/** How an agent run ended. */
sealed interface AgentResult {
    /** Ordered list of steps that actually succeeded; compilable into a skill. */
    val trajectory: List<Step>

    /** Everything spoken to the user, in order. */
    val spoken: List<String>

    data class Success(
        val summary: String,
        override val trajectory: List<Step>,
        override val spoken: List<String>,
        val plannerCalls: Int,
        val elapsedMs: Long,
    ) : AgentResult

    /** The agent needs an answer before it can continue. Not a failure. */
    data class NeedsUser(
        val question: String,
        override val trajectory: List<Step>,
        override val spoken: List<String>,
    ) : AgentResult

    /** An irreversible action was declined or unconfirmed. Nothing was committed. */
    data class Refused(
        val action: String,
        val question: String,
        override val trajectory: List<Step>,
        override val spoken: List<String>,
    ) : AgentResult

    data class Failed(
        val reason: Reason,
        val detail: String,
        /** Speech-ready explanation. */
        val message: String,
        val screen: RedactedSnapshot?,
        override val trajectory: List<Step>,
        override val spoken: List<String>,
        val plannerCalls: Int,
    ) : AgentResult {
        enum class Reason {
            /** The planner gave up. */
            PLANNER_GAVE_UP,

            /** Ran out of steps or wall-clock time. */
            BUDGET_EXCEEDED,

            /**
             * The same action was chosen on the same screen twice.
             *
             * Without this an agent can tap a dead button forever, which for a
             * blind user is an unexplained silence.
             */
            LOOP_DETECTED,

            /** A step kept failing and the planner could not route around it. */
            STUCK,
        }
    }
}

/**
 * The reasoning tier: pursues a goal nobody wrote a skill for.
 *
 * Observe the screen, decide one action, perform it, observe again. This is what
 * makes "search Google for the weather" possible without a pre-written recipe.
 *
 * It is deliberately the *slow* tier. Every iteration costs a planner call, so
 * [AgentResult.Success.trajectory] is meant to be compiled into a skill by
 * [SkillRecorder] and replayed for free thereafter.
 *
 * Everything in this class except [Planner] is deterministic, which is why the
 * safety behaviour — confirmation gates, loop detection, budgets — is fully
 * testable without a model.
 */
class AgentLoop(
    private val device: Device,
    private val planner: Planner,
    private val redactor: Redactor = Redactor(),
    private val serializer: ScreenSerializer = ScreenSerializer(),
    private val speech: SpeechFormatter = SpeechFormatter(),
    private val risk: RiskPolicy = RiskPolicy(),
    private val config: Config = Config(),
    private val onSpeak: (String) -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    /**
     * Asked before anything irreversible. Denies by default.
     *
     * A default of "yes" would mean forgetting to wire this up silently authorises
     * the agent to spend money, so the unwired state must be the safe one.
     */
    private val confirm: (String) -> Boolean = { false },
) {
    data class Config(
        /** Hard ceiling on planner calls. Each one costs money and time. */
        val maxSteps: Int = 25,
        val totalBudgetMs: Long = 180_000,
        val implicitWaitMs: Long = 2_000,
        val pollMs: Long = 200,
        /** Consecutive failures tolerated before giving up on the goal. */
        val maxConsecutiveFailures: Int = 3,
        /** Narrate each action. Useful while learning, chatty once trusted. */
        val announceActions: Boolean = false,
        /** Say how long the task took once it succeeds. */
        val announceDuration: Boolean = true,
    )

    private val spoken = mutableListOf<String>()
    private val trajectory = mutableListOf<Step>()

    private val executor = StepExecutor(
        device = device,
        speech = speech,
        config = StepExecutor.Config(config.implicitWaitMs, config.pollMs),
        onSpeak = { utterance ->
            spoken += utterance
            onSpeak(utterance)
        },
    )

    fun run(goal: String): AgentResult {
        val startedAt = device.nowMs()
        val history = mutableListOf<String>()
        val captures = LinkedHashMap<String, String>()
        // Signature of (screen, action) pairs already attempted.
        val attempted = mutableSetOf<String>()
        var plannerCalls = 0
        var consecutiveFailures = 0

        while (true) {
            if (plannerCalls >= config.maxSteps) {
                return failed(
                    AgentResult.Failed.Reason.BUDGET_EXCEEDED,
                    "Reached the ${config.maxSteps}-step limit",
                    goal, plannerCalls,
                )
            }
            if (device.nowMs() - startedAt > config.totalBudgetMs) {
                return failed(
                    AgentResult.Failed.Reason.BUDGET_EXCEEDED,
                    "Exceeded ${config.totalBudgetMs}ms",
                    goal, plannerCalls,
                )
            }

            val snapshot = device.snapshot()
            // Redaction happens here, before the planner can see anything.
            val redacted = redactor.redact(snapshot)
            val wire = serializer.serialize(redacted)

            plannerCalls++
            val decision = planner.next(
                PlanRequest(
                    goal = goal,
                    screen = redacted,
                    wire = wire,
                    history = history.toList(),
                    stepNumber = plannerCalls,
                ),
            )

            when (decision) {
                is PlanDecision.Done -> {
                    if (decision.summary.isNotBlank()) speakNow(decision.summary)
                    if (config.announceDuration) {
                        // How long it took is genuinely useful feedback: it tells the
                        // user whether the agent worked or merely thought about it,
                        // and it is the only progress signal on a long task.
                        val elapsed = device.nowMs() - startedAt
                        speakNow("Took ${speech.describeDuration(elapsed)}.")
                    }
                    return AgentResult.Success(
                        summary = decision.summary,
                        trajectory = trajectory.toList(),
                        spoken = spoken.toList(),
                        plannerCalls = plannerCalls,
                        elapsedMs = device.nowMs() - startedAt,
                    )
                }

                is PlanDecision.AskUser -> {
                    speakNow(decision.question)
                    return AgentResult.NeedsUser(
                        question = decision.question,
                        trajectory = trajectory.toList(),
                        spoken = spoken.toList(),
                    )
                }

                is PlanDecision.GiveUp -> return failed(
                    AgentResult.Failed.Reason.PLANNER_GAVE_UP,
                    decision.reason,
                    goal, plannerCalls,
                )

                is PlanDecision.Act -> {
                    val step = decision.step

                    // Loop detection uses the screen plus the action, so revisiting
                    // a screen is fine but repeating a move that changed nothing
                    // is not.
                    val signature = "${wire.hashCode()}|${step.describe}"
                    if (!attempted.add(signature)) {
                        return failed(
                            AgentResult.Failed.Reason.LOOP_DETECTED,
                            "Repeated \"${step.describe}\" on an unchanged screen",
                            goal, plannerCalls,
                        )
                    }

                    // Confirmation gate, evaluated before the action runs.
                    resolveRisk(step)?.let { question ->
                        speakNow(question)
                        if (!confirm(question)) {
                            return AgentResult.Refused(
                                action = step.describe,
                                question = question,
                                trajectory = trajectory.toList(),
                                spoken = spoken.toList(),
                            )
                        }
                    }

                    if (config.announceActions && step !is Speak) {
                        onProgress(speech.describeProgress(step.describe))
                    }

                    when (val outcome = executor.execute(step, captures)) {
                        is StepOutcome.Ok -> {
                            trajectory += step
                            history += "did: ${step.describe}"
                            consecutiveFailures = 0
                        }

                        is StepOutcome.Failed -> {
                            consecutiveFailures++
                            // Report the failure back so the planner can adapt
                            // instead of being told only "something went wrong".
                            history += "failed: ${step.describe} (${outcome.reason}) ${outcome.detail}"
                            if (consecutiveFailures >= config.maxConsecutiveFailures) {
                                return failed(
                                    AgentResult.Failed.Reason.STUCK,
                                    "$consecutiveFailures consecutive failures; last: ${outcome.detail}",
                                    goal, plannerCalls, outcome.reason,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Non-null when the step needs confirmation; the value is the spoken question. */
    private fun resolveRisk(step: Step): String? {
        val selector = when (step) {
            is Tap -> step.selector
            is LongPress -> step.selector
            else -> return null
        }
        // Resolve without waiting: this is a check, not the action itself.
        val target = executor.resolve(selector, waitMs = 0)
        val phrase = risk.irreversibleReason(step, target) ?: return null
        return risk.confirmationQuestion(target, phrase)
    }

    private fun speakNow(text: String) {
        val cleaned = speech.clean(text)
        if (cleaned.isBlank()) return
        spoken += cleaned
        onSpeak(cleaned)
    }

    private fun failed(
        reason: AgentResult.Failed.Reason,
        detail: String,
        goal: String,
        plannerCalls: Int,
        stepReason: FailureReason? = null,
    ): AgentResult.Failed {
        val message = speech.clean(
            "I couldn't finish \"$goal\". ${explain(reason)} " +
                "Say \"try again\" if you'd like me to have another go.",
        )
        speakNow(message)
        return AgentResult.Failed(
            reason = reason,
            detail = redactor.redactText(stepReason?.let { "$it: $detail" } ?: detail),
            message = message,
            screen = runCatching { redactor.redact(device.snapshot()) }.getOrNull(),
            trajectory = trajectory.toList(),
            spoken = spoken.toList(),
            plannerCalls = plannerCalls,
        )
    }

    private fun explain(reason: AgentResult.Failed.Reason): String = when (reason) {
        AgentResult.Failed.Reason.PLANNER_GAVE_UP -> "I couldn't work out how to do it."
        AgentResult.Failed.Reason.BUDGET_EXCEEDED -> "It was taking too long, so I stopped."
        AgentResult.Failed.Reason.LOOP_DETECTED -> "I was going in circles, so I stopped."
        AgentResult.Failed.Reason.STUCK -> "The app didn't respond the way I expected."
    }
}
