package dev.fingertip.core.agent

import dev.fingertip.core.screen.Node
import dev.fingertip.core.skill.LongPress
import dev.fingertip.core.skill.Step
import dev.fingertip.core.skill.Tap

/**
 * Decides which actions must be confirmed before the agent performs them.
 *
 * This exists because of who the user is. A sighted person can see "Send £400"
 * under their thumb and stop. Someone relying on a screen reader is trusting the
 * agent's description of the screen, so an agent that taps first and narrates
 * afterwards can spend money or send a message with no chance to intervene.
 *
 * The rule is asymmetric on purpose: pausing on a harmless button costs a second,
 * while not pausing on a payment is unrecoverable. False positives are cheap and
 * false negatives are not.
 */
class RiskPolicy(private val extraPhrases: Set<String> = emptySet()) {

    /**
     * Non-null when [step] would commit something the user cannot undo.
     * The returned text is the phrase that triggered it, for the spoken prompt.
     */
    fun irreversibleReason(step: Step, target: Node?): String? {
        // Only committing gestures matter. Reading, scrolling and navigating back
        // are all safe, and gating them would make the agent unusable.
        if (step !is Tap && step !is LongPress) return null
        val label = target?.label?.lowercase() ?: return null
        return (IRREVERSIBLE_PHRASES + extraPhrases).firstOrNull { phrase ->
            containsWord(label, phrase)
        }
    }

    /** Phrasing for the spoken confirmation prompt. */
    fun confirmationQuestion(target: Node?, phrase: String): String {
        val what = target?.label?.takeIf { it.isNotBlank() } ?: phrase
        return "This looks irreversible: \"$what\". Should I do it? Say yes to continue."
    }

    private fun containsWord(haystack: String, needle: String): Boolean {
        val index = haystack.indexOf(needle)
        if (index < 0) return false
        // Word-boundary check so "send" does not fire on "sender name" alone
        // while still matching "send message".
        val beforeOk = index == 0 || !haystack[index - 1].isLetterOrDigit()
        val end = index + needle.length
        val afterOk = end == haystack.length || !haystack[end].isLetterOrDigit()
        return beforeOk && afterOk
    }

    private companion object {
        /**
         * Kept short and specific. A long list would gate ordinary navigation and
         * train the user to say yes without listening, which is worse than no gate.
         */
        val IRREVERSIBLE_PHRASES = setOf(
            "send", "pay", "transfer", "buy", "purchase", "order", "checkout",
            "delete", "remove", "uninstall", "erase", "reset", "wipe",
            "call", "sign out", "log out", "unsubscribe", "confirm",
        )
    }
}
