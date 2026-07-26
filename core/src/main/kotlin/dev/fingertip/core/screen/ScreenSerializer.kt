package dev.fingertip.core.screen

import dev.fingertip.core.privacy.RedactedSnapshot

/**
 * Renders a redacted screen as compact text for a language model.
 *
 * Why text and not a screenshot: the accessibility tree already carries every
 * label, role and interactivity flag as structured data. Serialising it is
 * dramatically cheaper and lower-latency than image input, and it is *more*
 * reliable — no OCR errors, no guessing whether a grey button is disabled.
 * Screenshots stay as a fallback for unlabelled icons and canvas-drawn UI.
 *
 * Accepts [RedactedSnapshot] only, so raw screen text cannot be serialised for
 * egress by mistake.
 */
class ScreenSerializer(private val options: Options = Options()) {

    data class Options(
        /** Hard cap on emitted nodes; the tree is truncated breadth-first-ish beyond it. */
        val maxNodes: Int = 120,
        val maxDepth: Int = 14,
        /** Long labels get ellipsised; chat bodies can be thousands of chars. */
        val maxLabelChars: Int = 120,
        /** Include pixel bounds. Off by default: the agent acts via handles, not coordinates. */
        val includeBounds: Boolean = false,
    )

    fun serialize(redacted: RedactedSnapshot): String {
        val snapshot = redacted.snapshot
        val sb = StringBuilder()

        sb.append("app=").append(snapshot.packageName)
        snapshot.windowTitle?.takeIf { it.isNotBlank() }?.let { sb.append(" screen=\"").append(it).append('"') }
        if (!snapshot.screenshotAvailable) sb.append(" (screenshot blocked by app)")
        if (redacted.redactionCount > 0) sb.append(" (").append(redacted.redactionCount).append(" value(s) redacted)")
        sb.append('\n')

        val pruned = prune(snapshot.root)
        if (pruned == null) {
            sb.append("  <no readable elements>\n")
            return sb.toString()
        }

        var budget = options.maxNodes
        fun emit(node: Node, depth: Int) {
            if (budget <= 0 || depth > options.maxDepth) return
            budget--
            sb.append("  ".repeat(depth)).append(render(node)).append('\n')
            node.children.forEach { emit(it, depth + 1) }
        }
        emit(pruned, 0)

        if (budget <= 0) sb.append("  ... (truncated)\n")
        return sb.toString()
    }

    private fun render(node: Node): String = buildString {
        append('[').append(node.handle).append("] ").append(node.role.wire)
        node.label?.let { append(' ').append('"').append(ellipsise(it)).append('"') }

        val flags = buildList {
            if (node.clickable) add("tap")
            if (node.longClickable) add("longpress")
            if (node.editable) add("type")
            if (node.scrollable) add("scroll")
            if (node.focused) add("focused")
            node.checked?.let { add(if (it) "checked" else "unchecked") }
            if (node.isPassword) add("password")
        }
        if (flags.isNotEmpty()) flags.joinTo(this, separator = ",", prefix = " (", postfix = ")")

        if (options.includeBounds && !node.bounds.isEmpty) {
            append(" @").append(node.bounds.left).append(',').append(node.bounds.top)
                .append('-').append(node.bounds.right).append(',').append(node.bounds.bottom)
        }
    }

    private fun ellipsise(text: String): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        return if (flat.length <= options.maxLabelChars) flat
        else flat.take(options.maxLabelChars - 1).trimEnd() + "\u2026"
    }

    /**
     * Drops noise and flattens pass-through wrappers.
     *
     * Android layouts are full of nested FrameLayouts that carry no label and no
     * behaviour. Emitting them wastes tokens and, worse, buries the meaningful
     * elements under so much indentation that the model loses track of structure.
     */
    private fun prune(node: Node): Node? {
        if (!node.visible) return null
        // Zero-area nodes are off-screen or collapsed; a user cannot touch them.
        if (node.bounds.isEmpty && node.children.isEmpty()) return null

        val keptChildren = node.children.mapNotNull(::prune)
        val meaningful = node.label != null || node.isInteractive

        return when {
            meaningful -> node.copy(children = keptChildren)
            // Pure wrapper around a single element: replace it with that element.
            keptChildren.size == 1 -> keptChildren.first()
            keptChildren.isEmpty() -> null
            // Unlabelled but branching: keep as structure so grouping survives.
            else -> node.copy(children = keptChildren)
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
