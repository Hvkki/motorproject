package dev.fingertip.core.screen

import kotlinx.serialization.Serializable

/**
 * Declarative way to point at a node.
 *
 * Every non-null field is a constraint and all of them must hold (AND). String
 * comparisons are case-insensitive and whitespace-trimmed, because on-screen
 * casing changes between Android versions and app updates far more often than
 * the words themselves do — matching case-sensitively is a top source of skills
 * that silently rot.
 */
@Serializable
data class Selector(
    /** Exact match against [Node.text]. */
    val text: String? = null,
    /** Substring match against [Node.text]. */
    val textContains: String? = null,
    /** Exact match against [Node.contentDescription]. */
    val desc: String? = null,
    /** Substring match against [Node.contentDescription]. */
    val descContains: String? = null,
    /** Exact match against [Node.viewId] (entry name, or full `pkg:id/name`). */
    val viewId: String? = null,
    /** Substring match against [Node.label] — text, else desc, else view id. */
    val labelContains: String? = null,
    val role: Role? = null,
    val clickable: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    /**
     * Which match to take when several qualify, in pre-order.
     * Negative indexes count from the end, so -1 is the last match — useful for
     * "the newest message in a chat log".
     * Null means "require exactly one match" is NOT enforced; the first is taken.
     */
    val index: Int? = null,
) {
    init {
        require(!isEmpty) { "Selector must constrain at least one attribute" }
    }

    val isEmpty: Boolean
        get() = text == null && textContains == null && desc == null && descContains == null &&
            viewId == null && labelContains == null && role == null && clickable == null &&
            editable == null && scrollable == null

    /** True when [node] satisfies every constraint. Ignores [index]. */
    fun matches(node: Node): Boolean {
        text?.let { if (!node.text.eqLoose(it)) return false }
        textContains?.let { if (!node.text.containsLoose(it)) return false }
        desc?.let { if (!node.contentDescription.eqLoose(it)) return false }
        descContains?.let { if (!node.contentDescription.containsLoose(it)) return false }
        labelContains?.let { if (!node.label.containsLoose(it)) return false }
        viewId?.let { candidate ->
            val actual = node.viewId ?: return false
            // Accept both `name` and `com.app:id/name` so skills stay readable.
            val matchesId = actual.eqLoose(candidate) ||
                actual.substringAfterLast('/').eqLoose(candidate.substringAfterLast('/'))
            if (!matchesId) return false
        }
        role?.let { if (node.role != it) return false }
        clickable?.let { if (node.clickable != it) return false }
        editable?.let { if (node.editable != it) return false }
        scrollable?.let { if (node.scrollable != it) return false }
        return true
    }

    /**
     * All matching nodes in pre-order, restricted to nodes the user could
     * actually perceive or act on (visible and enabled).
     */
    fun findAll(snapshot: ScreenSnapshot): List<Node> =
        snapshot.nodes.filter { it.visible && it.enabled && matches(it) }

    /** The single node this selector designates, applying [index]. Null when unmatched. */
    fun findOne(snapshot: ScreenSnapshot): Node? {
        val matches = findAll(snapshot)
        if (matches.isEmpty()) return null
        val i = index ?: return matches.first()
        val resolved = if (i < 0) matches.size + i else i
        return matches.getOrNull(resolved)
    }

    /** Compact human description, used in failure messages read aloud to the user. */
    fun describe(): String {
        val parts = buildList {
            text?.let { add("text=\"$it\"") }
            textContains?.let { add("text contains \"$it\"") }
            desc?.let { add("description=\"$it\"") }
            descContains?.let { add("description contains \"$it\"") }
            labelContains?.let { add("label contains \"$it\"") }
            viewId?.let { add("id=$it") }
            role?.let { add("role=${it.wire}") }
            clickable?.let { add(if (it) "clickable" else "not clickable") }
            editable?.let { add(if (it) "editable" else "not editable") }
            scrollable?.let { add(if (it) "scrollable" else "not scrollable") }
            index?.let { add("index=$it") }
        }
        return parts.joinToString(", ")
    }

    private companion object {
        fun String?.eqLoose(other: String): Boolean =
            this != null && this.trim().equals(other.trim(), ignoreCase = true)

        fun String?.containsLoose(other: String): Boolean =
            this != null && this.trim().contains(other.trim(), ignoreCase = true)
    }
}
