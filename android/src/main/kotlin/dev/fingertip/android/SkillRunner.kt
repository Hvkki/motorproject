package dev.fingertip.android

import dev.fingertip.core.agent.AgentLoop
import dev.fingertip.core.agent.AgentResult
import dev.fingertip.core.agent.Planner
import dev.fingertip.core.agent.RiskPolicy
import dev.fingertip.core.agent.SkillRecorder
import dev.fingertip.core.device.Device
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.skill.Skill
import dev.fingertip.core.skill.SkillInterpreter
import dev.fingertip.core.skill.SkillLibrary
import dev.fingertip.core.skill.SkillResult
import dev.fingertip.core.speech.SpeechFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Routes a spoken request to the fast tier or the reasoning tier, on a background
 * thread, cancellably.
 *
 * Three responsibilities that must not be split apart:
 *
 * 1. **Tier selection.** A known request replays a skill locally with no model
 *    call. An unknown one escalates to [AgentLoop], which reasons over the screen
 *    and taps its way through. This is what makes both "read my last message" and
 *    "search Google for the weather" work, at very different costs.
 *
 * 2. **Off the main thread.** Both tiers block while polling for screens. On the
 *    main thread that freezes the UI and gets the service killed.
 *
 * 3. **Barge-in.** A blind user must be able to interrupt at any moment. When they
 *    speak, we stop talking *immediately* and abandon the running task. An agent
 *    that narrates over its user is unusable however accurate it is, so [cancel]
 *    matters as much as [handleUtterance].
 */
class SkillRunner(
    private val device: Device,
    private var library: SkillLibrary,
    private val speaker: Speaker,
    private val redactor: Redactor = Redactor(),
    private val speech: SpeechFormatter = SpeechFormatter(),
    private val risk: RiskPolicy = RiskPolicy(),
    /**
     * The reasoning tier. Null means fast-tier only: unknown requests are declined
     * rather than improvised, which is the correct default for an app that can
     * spend money.
     */
    private val planner: Planner? = null,
    /**
     * Asked before anything irreversible, and expected to block until the user
     * answers. Denies by default so an unwired prompt cannot authorise a payment.
     */
    private val confirm: (String) -> Boolean = { false },
    /**
     * Called when the agent completes a novel task and a skill was recorded.
     *
     * Persist it and the same request takes the fast path next time. This is where
     * a per-invocation cost becomes a per-task-type cost.
     */
    private val onSkillLearned: (Skill) -> Unit = {},
) {

    /** Text-to-speech, abstracted so the runner stays testable and swappable. */
    interface Speaker {
        /** Queues an utterance. */
        fun say(text: String)

        /** Stops mid-word and drops anything queued. Called on barge-in. */
        fun stop()
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fingertip-agent").apply { isDaemon = true }
    }
    private val inFlight = AtomicReference<Future<*>?>(null)

    /** Adds a newly learned or freshly synced skill to the fast tier. */
    fun addSkill(skill: Skill) {
        library = SkillLibrary(library.skills.filterNot { it.id == skill.id } + skill)
    }

    /**
     * Handles a spoken request.
     *
     * Returns true when a known skill matched, false when the request escalated to
     * the reasoning tier or was declined.
     */
    fun handleUtterance(utterance: String): Boolean {
        cancel()

        val match = library.match(utterance)
        if (match != null) {
            replay(match.skill)
            return true
        }

        if (planner == null) {
            speaker.say("I don't know how to do that yet.")
            return false
        }
        reason(utterance, planner)
        return false
    }

    /** Fast tier: replay a known skill. */
    fun replay(skill: Skill) {
        cancel()
        submit {
            val interpreter = SkillInterpreter(
                device = device,
                redactor = redactor,
                speech = speech,
                // Speak as each utterance is produced rather than batching, so the
                // user hears progress while a slow screen loads.
                onSpeak = speaker::say,
            )
            when (val result = interpreter.execute(skill)) {
                is SkillResult.Success -> Unit // Everything worth saying was said.
                is SkillResult.Failure -> {
                    if (Thread.currentThread().isInterrupted) return@submit
                    speaker.say(result.message)
                    // A stale skill is the strongest signal that the app changed,
                    // so retry the goal with the reasoning tier.
                    planner?.let { reasonNow(skill.title, it) }
                }
            }
        }
    }

    /** Slow tier: pursue a goal nobody wrote a skill for. */
    private fun reason(goal: String, planner: Planner) {
        submit { reasonNow(goal, planner) }
    }

    private fun reasonNow(goal: String, planner: Planner) {
        if (Thread.currentThread().isInterrupted) return
        val loop = AgentLoop(
            device = device,
            planner = planner,
            redactor = redactor,
            speech = speech,
            risk = risk,
            onSpeak = speaker::say,
            confirm = confirm,
        )
        when (val result = loop.run(goal)) {
            is AgentResult.Success -> {
                // Compile the successful trajectory so this stops being expensive.
                SkillRecorder.record(goal, result.trajectory)?.let { learned ->
                    addSkill(learned)
                    onSkillLearned(learned)
                }
            }
            // The loop has already spoken in every other case: the question it
            // needs answered, the confirmation it wants, or why it stopped.
            is AgentResult.NeedsUser, is AgentResult.Refused, is AgentResult.Failed -> Unit
        }
    }

    /**
     * Stops speech and abandons any running task.
     *
     * Speech is stopped first: silence must be immediate, even if the worker thread
     * takes a moment to notice the interrupt.
     */
    fun cancel() {
        speaker.stop()
        inFlight.getAndSet(null)?.cancel(true)
    }

    fun shutdown() {
        cancel()
        executor.shutdownNow()
    }

    private fun submit(block: () -> Unit) {
        inFlight.set(
            executor.submit {
                try {
                    block()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            },
        )
    }
}
