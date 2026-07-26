package dev.fingertip.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.fingertip.core.skill.SkillLibrary

/**
 * The accessibility service entry point.
 *
 * ## Coexisting with TalkBack
 *
 * Almost every user of this app already runs TalkBack, and that is the single
 * biggest design constraint in the project. Rules that follow from it:
 *
 * - **Never** request `canRequestTouchExplorationMode` or
 *   `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`. Two services competing for touch
 *   exploration makes the phone unusable, and TalkBack must keep winning: it is
 *   how the user navigates everything outside this app.
 * - **Never** request `FLAG_REQUEST_FILTER_KEY_EVENTS`. It would swallow
 *   TalkBack's keyboard shortcuts.
 * - This service is a *reader and actor*, not a navigation layer. It observes the
 *   tree and dispatches actions on request; it does not intercept the user's own
 *   gestures.
 * - Our speech goes through our own [SkillRunner.Speaker]. Do not try to route it
 *   through TalkBack's announcements — the two queues would interleave
 *   unpredictably and the user could not tell which agent was talking.
 *
 * ## Play Store policy
 *
 * Using AccessibilityService is only permitted when accessibility is the app's
 * genuine purpose, which it is here. `android:isAccessibilityTool="true"` in the
 * config declares that, and the store listing must describe the accessibility
 * function prominently. See the manifest and `fingertip_accessibility_config.xml`.
 *
 * ## Not compiled in this repository's Gradle build
 *
 * The `:android` module is intentionally excluded from `settings.gradle.kts` so
 * the core test suite runs anywhere with just a JDK. Wire it up in an
 * environment that has the Android SDK; see `android/README.md`.
 */
class FingertipAccessibilityService : AccessibilityService() {

    private lateinit var device: AccessibilityDevice
    private lateinit var runner: SkillRunner
    private var speaker: TtsSpeaker? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        device = AccessibilityDevice(this)
        val tts = TtsSpeaker(this).also { speaker = it }
        runner = SkillRunner(
            device = device,
            library = SkillPack.load(this),
            speaker = tts,
        )
        // Wire this to the model-backed explorer that authors new skills.
        runner.escalation = SkillRunner.Escalation { _, _ -> /* see docs/architecture.md */ }
    }

    /**
     * Deliberately does almost nothing.
     *
     * It is tempting to react to every window change, but this callback fires
     * constantly and reading the tree here would drain the battery for no
     * benefit. Screens are read on demand, when a skill actually needs one.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** The system is taking the service away — stop talking at once. */
    override fun onInterrupt() {
        runner.cancel()
    }

    override fun onDestroy() {
        runner.shutdown()
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }

    /**
     * Entry point for the voice layer.
     *
     * Called from whatever captures speech (a foreground activity, a tile, a
     * hardware button). Returns false when the request was escalated rather than
     * matched to a known skill.
     */
    fun onSpokenRequest(utterance: String): Boolean = runner.handleUtterance(utterance)

    /** Barge-in: the user started speaking, so stop everything immediately. */
    fun onUserInterrupted() = runner.cancel()
}

/** Loads the bundled skill pack. Replace with a versioned pack synced from the skills repo. */
private object SkillPack {
    fun load(service: AccessibilityService): SkillLibrary {
        val documents = service.assets.list("skills")
            ?.filter { it.endsWith(".json") }
            ?.map { name -> service.assets.open("skills/$name").bufferedReader().use { it.readText() } }
            ?: emptyList()
        return SkillLibrary.fromJson(documents)
    }
}
