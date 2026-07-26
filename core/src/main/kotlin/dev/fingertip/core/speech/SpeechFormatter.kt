package dev.fingertip.core.speech

import dev.fingertip.core.privacy.RedactedSnapshot
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.Role

/**
 * Turns internal state into something worth hearing.
 *
 * This class exists because the default failure mode of an accessibility agent
 * is to narrate its own data structures. "listitem, index zero, clickable true"
 * is technically accurate and completely useless. Every method here answers
 * "what would a sighted friend sitting next to you actually say?"
 */
class SpeechFormatter(private val options: Options = Options()) {

    data class Options(
        /** Beyond this, speech becomes a monologue the user cannot interrupt usefully. */
        val maxSpokenChars: Int = 400,
        /** How many list items to enumerate before summarising the remainder. */
        val maxItems: Int = 5,
    )

    /**
     * Fills `{name}` placeholders from [values].
     *
     * Unresolved placeholders are dropped rather than spoken literally — hearing
     * "Last message: {message}" is worse than hearing "Last message:".
     */
    fun interpolate(template: String, values: Map<String, String>): String {
        val filled = PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]] ?: ""
        }
        return clean(filled)
    }

    /** Names the placeholders a template requires, so skills can be validated up front. */
    fun placeholdersIn(template: String): Set<String> =
        PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()

    /**
     * One-breath summary of a screen, for "where am I?" / "what's on screen?".
     *
     * Leads with app and screen name because orientation matters more than detail
     * when you cannot see; details are available on request.
     */
    fun describeScreen(redacted: RedactedSnapshot): String {
        val snapshot = redacted.snapshot
        val parts = mutableListOf<String>()

        val appName = prettyPackage(snapshot.packageName)
        parts += snapshot.windowTitle?.takeIf { it.isNotBlank() }
            ?.let { "$appName, $it." }
            ?: "$appName."

        val items = snapshot.nodes.filter { it.visible && it.role == Role.LIST_ITEM && it.label != null }
        if (items.isNotEmpty()) {
            val shown = items.take(options.maxItems).mapNotNull { it.label }.map(::clean)
            // Terminate the sentence properly: without punctuation, TTS runs the
            // last item straight into whatever follows.
            val remainder = items.size - shown.size
            val tail = if (remainder > 0) ", and $remainder more." else "."
            parts += "${items.size} item${plural(items.size)}: ${shown.joinToString("; ")}$tail"
        } else {
            val actions = snapshot.nodes
                .filter { it.visible && it.enabled && it.clickable && it.label != null }
                .mapNotNull { it.label }
                .distinct()
            if (actions.isNotEmpty()) {
                val shown = actions.take(options.maxItems).map(::clean)
                parts += "Controls: ${shown.joinToString("; ")}."
            }
        }

        if (!snapshot.screenshotAvailable) {
            parts += "This app blocks screen capture, so I can only read labelled elements."
        }
        if (redacted.redactionCount > 0) {
            parts += "${redacted.redactionCount} sensitive value${plural(redacted.redactionCount)} hidden."
        }
        return clean(parts.joinToString(" "))
    }

    /**
     * Explains a failure in terms of the user's goal, not the selector that missed.
     *
     * The user does not care that `text="Send"` failed to match; they care that
     * the app looks different than expected and what they can do next.
     */
    fun describeFailure(skillTitle: String, stepDescription: String, appName: String?): String {
        val where = appName?.let { " in ${prettyPackage(it)}" } ?: ""
        return clean(
            "I couldn't finish \"$skillTitle\"$where. It got stuck trying to $stepDescription. " +
                "The app may have changed. Say \"figure it out\" and I'll work through it step by step.",
        )
    }

    /** Short progress note, e.g. while a slow screen loads. */
    fun describeProgress(stepDescription: String): String = clean("Working: $stepDescription.")

    /** Collapses whitespace and caps length at a sentence boundary where possible. */
    fun clean(text: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= options.maxSpokenChars) return flat
        val hard = flat.take(options.maxSpokenChars)
        val cut = hard.lastIndexOfAny(charArrayOf('.', '!', '?', ';'))
        return if (cut > options.maxSpokenChars / 2) hard.take(cut + 1) else "${hard.trimEnd()}\u2026"
    }

    /** Reads a single node the way a person would refer to it. */
    fun describeNode(node: Node): String {
        val label = node.label?.let(::clean) ?: "unlabelled ${node.role.wire}"
        val kind = when {
            node.isPassword -> "password field"
            node.editable -> "text field"
            node.role == Role.BUTTON -> "button"
            node.role == Role.CHECKBOX || node.role == Role.SWITCH ->
                if (node.checked == true) "switched on" else "switched off"
            else -> null
        }
        return if (kind == null) label else "$label, $kind"
    }

    /** `com.whatsapp` -> `Whatsapp`. Crude, but better than reading the package aloud. */
    private fun prettyPackage(packageName: String): String {
        if (!packageName.contains('.')) return packageName.replaceFirstChar(Char::titlecase)
        val candidate = packageName.split('.')
            .filter { it.isNotBlank() && it !in GENERIC_SEGMENTS }
            .lastOrNull()
            ?: return packageName
        return candidate.replaceFirstChar(Char::titlecase)
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private companion object {
        val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)}")
        val WHITESPACE = Regex("\\s+")
        val GENERIC_SEGMENTS = setOf("com", "org", "net", "io", "app", "android", "google", "mobile")
    }
}
