package dev.fingertip.core.screen

import kotlinx.serialization.Serializable

/**
 * Rectangle in screen pixels. Mirrors android.graphics.Rect semantics
 * (right/bottom are exclusive) without depending on the Android SDK.
 */
@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    /** A zero-area rect: Android reports these for off-screen / collapsed views. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)
    }
}

/**
 * Coarse semantic role, normalised from Android's className strings.
 *
 * Deliberately small: the agent reasons far more reliably over a handful of
 * meaningful roles than over the long tail of `android.widget.*` class names,
 * and it keeps the serialised screen cheap to send.
 */
@Serializable
enum class Role {
    BUTTON,
    TEXT,
    EDIT_TEXT,
    IMAGE,
    LIST,
    LIST_ITEM,
    CHECKBOX,
    SWITCH,
    TAB,
    DIALOG,
    WEB_VIEW,
    CONTAINER,
    UNKNOWN,
    ;

    /** Lowercase name used in the serialised screen and in skill JSON. */
    val wire: String get() = name.lowercase()
}

/**
 * One element of the accessibility tree, platform-neutral.
 *
 * [handle] is assigned by [ScreenSnapshot.of] in pre-order and is stable only
 * *within* a single snapshot. The Android adapter keeps a handle -> live node map
 * for the most recent snapshot so actions can be dispatched against real nodes.
 */
@Serializable
data class Node(
    val handle: Int = UNASSIGNED,
    val role: Role = Role.UNKNOWN,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds.ZERO,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    /**
     * True when Android marks this field as a password input.
     *
     * This is the single most important flag in the whole model: [dev.fingertip.core.privacy.Redactor]
     * uses it to guarantee password contents never reach a network boundary.
     */
    val isPassword: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val children: List<Node> = emptyList(),
) {
    /**
     * Best human-readable label: visible text, else the content description,
     * else a humanised view id. Null when the node carries no label at all.
     */
    val label: String?
        get() = text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: viewId?.takeIf { it.isNotBlank() }?.let { humaniseViewId(it) }

    /** Actionable in some way the agent can drive. */
    val isInteractive: Boolean
        get() = clickable || longClickable || editable || scrollable

    /** Pre-order walk including this node. */
    fun flatten(): List<Node> = buildList {
        add(this@Node)
        children.forEach { addAll(it.flatten()) }
    }

    companion object {
        const val UNASSIGNED = -1

        private val ID_SEPARATORS = Regex("[_\\-.]+")

        /** `btn_send_message` -> `btn send message`; keeps it recognisable without pretending it is a real label. */
        internal fun humaniseViewId(id: String): String =
            id.substringAfterLast('/')
                .replace(ID_SEPARATORS, " ")
                .trim()
    }
}

/**
 * An immutable capture of one screen state.
 *
 * @param screenshotAvailable false when the window is FLAG_SECURE (banking, DRM).
 *   The node tree is often still readable in that case, so this is a hint for
 *   degrading gracefully, not a hard failure.
 */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val windowTitle: String? = null,
    val root: Node,
    val capturedAtMs: Long = 0L,
    val screenshotAvailable: Boolean = true,
) {
    /** All nodes in pre-order. */
    val nodes: List<Node> by lazy { root.flatten() }

    fun nodeByHandle(handle: Int): Node? = nodes.firstOrNull { it.handle == handle }

    companion object {
        /**
         * Builds a snapshot, assigning stable pre-order handles starting at 1.
         *
         * Always construct through this rather than the primary constructor so
         * handles are guaranteed unique and dense.
         */
        fun of(
            packageName: String,
            root: Node,
            title: String? = null,
            capturedAtMs: Long = 0L,
            screenshotAvailable: Boolean = true,
        ): ScreenSnapshot {
            var next = 1
            fun assign(node: Node): Node {
                val handle = next++
                // Children are numbered after the parent, giving a readable pre-order.
                return node.copy(handle = handle, children = node.children.map(::assign))
            }
            return ScreenSnapshot(
                packageName = packageName,
                windowTitle = title,
                root = assign(root),
                capturedAtMs = capturedAtMs,
                screenshotAvailable = screenshotAvailable,
            )
        }
    }
}
