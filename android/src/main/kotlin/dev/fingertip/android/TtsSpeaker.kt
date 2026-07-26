package dev.fingertip.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Text-to-speech with the properties a blind user actually needs.
 *
 * - **Interruptible.** [stop] silences immediately; barge-in latency is the
 *   single biggest factor in whether a voice agent feels usable.
 * - **Queued, not clobbering.** Consecutive utterances from one skill queue up
 *   rather than cutting each other off mid-word.
 * - **Faster than default.** Experienced screen reader users listen far faster
 *   than sighted people expect; the stock rate feels patronisingly slow.
 * - **Buffers until ready.** TTS initialisation is asynchronous, so anything said
 *   before the engine is up is held rather than dropped.
 */
class TtsSpeaker(context: Context) : SkillRunner.Speaker {

    private val pending = ConcurrentLinkedQueue<String>()

    @Volatile
    private var ready = false

    @Volatile
    private var stopped = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            flush()
        }
    }.apply {
        setSpeechRate(DEFAULT_RATE)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit

            @Deprecated("Required override on older API levels")
            override fun onError(utteranceId: String?) = Unit
        })
    }

    /** Let the user tune the rate; the useful range is much wider than most apps expose. */
    fun setRate(rate: Float) {
        tts.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
    }

    override fun say(text: String) {
        if (text.isBlank()) return
        stopped = false
        if (!ready) {
            pending += text
            return
        }
        // QUEUE_ADD so a skill's utterances do not truncate one another.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId())
    }

    override fun stop() {
        stopped = true
        pending.clear()
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun flush() {
        while (true) {
            val next = pending.poll() ?: return
            if (stopped) {
                pending.clear()
                return
            }
            tts.speak(next, TextToSpeech.QUEUE_ADD, null, utteranceId())
        }
    }

    private fun utteranceId(): String = "fingertip-${counter++}"

    private companion object {
        const val DEFAULT_RATE = 1.35f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 4.0f
        var counter = 0L
    }
}
