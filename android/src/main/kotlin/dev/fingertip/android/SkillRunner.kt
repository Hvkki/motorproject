package dev.fingertip.android

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
 * Owns the background thread that skills run on, and the ability to stop them.
 *
 * Two responsibilities that must not be split apart:
 *
 * 1. **Off the main thread.** The interpreter blocks while polling for screens.
 *    Running that on the main thread freezes the UI and gets the service killed.
 *
 * 2. **Barge-in.** A blind user must be able to interrupt at any moment. When
 *    they speak, we stop talking *immediately* and abandon the running skill.
 *    An agent that keeps narrating over its user is unusable, no matter how
 *    accurate it is. [cancel] is therefore as important as [run].
 */
class SkillRunner(
    private val device: Device,
    private val library: SkillLibrary,
    private val speaker: Speaker,
    private val redactor: Redactor = Redactor(),
    private val speech: SpeechFormatter = SpeechFormatter(),
) {

    /** Text-to-speech, abstracted so the runner stays testable and swappable. */
    interface Speaker {
        /** Queues an utterance. */
        fun say(text: String)

        /** Stops mid-word and drops anything queued. Called on barge-in. */
        fun stop()
    }

    /** What to do when no skill matches — escalate to the reasoning agent. */
    fun interface Escalation {
        /**
         * Called with the user's request and the current redacted screen.
         *
         * This is the boundary where an expensive model gets involved: it should
         * explore the task and ideally emit a new [Skill] to be reviewed and added
         * to the library, so the next attempt takes the fast path.
         */
        fun onUnknownRequest(utterance: String, screen: dev.fingertip.core.privacy.RedactedSnapshot)
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fingertip-skill").apply { isDaemon = true }
    }
    private val inFlight = AtomicReference<Future<*>?>(null)

    var escalation: Escalation? = null

    /**
     * Interprets a spoken request.
     *
     * Returns true when a known skill was dispatched. False means we escalated,
     * which is the honest answer — guessing at a near-miss could send a message
     * or spend money.
     */
    fun handleUtterance(utterance: String): Boolean {
        cancel()

        val match = library.match(utterance)
        if (match == null) {
            speaker.say("I don't know how to do that yet. Let me work it out.")
            submit { escalation?.onUnknownRequest(utterance, redactor.redact(device.snapshot())) }
            return false
        }
        run(match.skill)
        return true
    }

    /** Runs a skill in the background, speaking as it goes. */
    fun run(skill: Skill) {
        cancel()
        submit {
            val interpreter = SkillInterpreter(
                device = device,
                redactor = redactor,
                speech = speech,
                // Speak as each utterance is produced rather than batching at the
                // end, so the user hears progress on a slow screen.
                onSpeak = speaker::say,
            )

            when (val result = interpreter.execute(skill)) {
                is SkillResult.Success -> Unit // Everything worth saying was already spoken.
                is SkillResult.Failure -> {
                    if (!Thread.currentThread().isInterrupted) {
                        speaker.say(result.message)
                        result.screen?.let { screen ->
                            escalation?.onUnknownRequest(skill.title, screen)
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops speech and abandons any running skill.
     *
     * Speech is stopped first: silence must be immediate, even if the worker
     * thread takes a moment to notice the interrupt.
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
        val future = executor.submit {
            try {
                block()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        inFlight.set(future)
    }
}
