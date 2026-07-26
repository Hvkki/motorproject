package dev.fingertip.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.fingertip.core.screen.Bounds
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.ScreenSnapshot

/**
 * Converts Android's live accessibility tree into Fingertip's compact,
 * platform-neutral screen model.
 *
 * This is the primary way the agent perceives a phone. It does not take a
 * screenshot, run OCR, or infer where controls are from pixels. Android already
 * exposes labels, roles, bounds, state and supported actions as structured data;
 * consuming that tree is faster, cheaper and usually more accurate.
 *
 * Screenshots remain a fallback for canvas-drawn UI and unlabelled images. They
 * must never become the default perception path.
 */
internal class AndroidNodeTree(
    private val limits: Limits = Limits(),
) {
    data class Limits(
        /** Guard against pathological trees such as deeply nested WebViews. */
        val maxNodes: Int = 800,
        val maxDepth: Int = 40,
    ) {
        init {
            require(maxNodes > 0) { "maxNodes must be positive" }
            require(maxDepth >= 0) { "maxDepth must not be negative" }
        }
    }

    /**
     * A model snapshot plus the live nodes from which it was derived.
     *
     * [liveNodes] is ordered exactly like [ScreenSnapshot.nodes], so handles can
     * be zipped to Android nodes for actions. Keeping this correspondence in the
     * converter prevents the model and action map from drifting apart.
     */
    data class Capture(
        val snapshot: ScreenSnapshot,
        val liveNodes: List<AccessibilityNodeInfo>,
        /** True when traversal stopped at [Limits.maxNodes] or [Limits.maxDepth]. */
        val truncated: Boolean,
    ) {
        init {
            require(snapshot.nodes.size == liveNodes.size) {
                "model/live-node mismatch: ${snapshot.nodes.size} model nodes, " +
                    "${liveNodes.size} Android nodes"
            }
        }

        fun handles(): Map<Int, AccessibilityNodeInfo> =
            snapshot.nodes.map { it.handle }.zip(liveNodes).toMap()
    }

    fun capture(
        root: AccessibilityNodeInfo,
        title: String?,
        capturedAtMs: Long,
        screenshotAvailable: Boolean,
    ): Capture {
        val state = TraversalState()
        val modelRoot = convert(root, depth = 0, state = state)
            ?: Node(role = Role.CONTAINER)

        val snapshot = ScreenSnapshot.of(
            packageName = root.packageName?.toString() ?: UNKNOWN_PACKAGE,
            root = modelRoot,
            title = title,
            capturedAtMs = capturedAtMs,
            screenshotAvailable = screenshotAvailable,
        )

        return Capture(
            snapshot = snapshot,
            liveNodes = state.liveNodes,
            truncated = state.truncated,
        )
    }

    private class TraversalState {
        val liveNodes = mutableListOf<AccessibilityNodeInfo>()
        var truncated = false
    }

    /**
     * Pre-order traversal with a strict global node budget.
     *
     * The original implementation checked the budget only before entering a
     * parent's child loop. A wide parent could therefore add hundreds of
     * siblings after the budget was already exhausted. Checking on every call
     * makes maxNodes a real bound rather than a suggestion.
     */
    private fun convert(
        source: AccessibilityNodeInfo,
        depth: Int,
        state: TraversalState,
    ): Node? {
        if (state.liveNodes.size >= limits.maxNodes) {
            state.truncated = true
            return null
        }

        state.liveNodes += source
        val rect = Rect().also { source.getBoundsInScreen(it) }

        val children = mutableListOf<Node>()
        if (depth >= limits.maxDepth) {
            if (source.childCount > 0) state.truncated = true
        } else {
            for (index in 0 until source.childCount) {
                if (state.liveNodes.size >= limits.maxNodes) {
                    state.truncated = true
                    break
                }
                source.getChild(index)?.let { child ->
                    convert(child, depth + 1, state)?.let(children::add)
                }
            }
        }

        return Node(
            role = roleOf(source),
            text = source.text?.toString(),
            contentDescription = source.contentDescription?.toString(),
            viewId = source.viewIdResourceName,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = source.isClickable,
            longClickable = source.isLongClickable,
            editable = source.isEditable,
            scrollable = source.isScrollable,
            focused = source.isAccessibilityFocused || source.isFocused,
            checked = if (source.isCheckable) source.isChecked else null,
            isPassword = source.isPassword,
            enabled = source.isEnabled,
            visible = source.isVisibleToUser,
            children = children,
        )
    }

    /**
     * Normalises Android's large class-name vocabulary into stable semantic roles.
     *
     * Collection metadata takes precedence over class names. A chat row may be a
     * custom ViewGroup, but collectionItemInfo still identifies it as a list item.
     */
    private fun roleOf(node: AccessibilityNodeInfo): Role {
        if (node.collectionItemInfo != null) return Role.LIST_ITEM
        if (node.collectionInfo != null) return Role.LIST

        val className = node.className?.toString().orEmpty()
        return when {
            className.endsWith("EditText") || node.isEditable -> Role.EDIT_TEXT
            className.endsWith("Switch") || className.endsWith("ToggleButton") -> Role.SWITCH
            className.endsWith("CheckBox") || className.endsWith("RadioButton") -> Role.CHECKBOX
            className.endsWith("Button") || className.endsWith("ImageButton") -> Role.BUTTON
            className.endsWith("WebView") -> Role.WEB_VIEW
            className.endsWith("ImageView") -> Role.IMAGE
            className.endsWith("TextView") -> Role.TEXT
            className.contains("TabWidget") || className.contains("TabLayout") -> Role.TAB
            className.endsWith("RecyclerView") || className.endsWith("ListView") ||
                className.endsWith("GridView") || className.endsWith("ScrollView") -> Role.LIST
            className.contains("Layout") || className.contains("ViewGroup") -> Role.CONTAINER
            node.isClickable -> Role.BUTTON
            else -> Role.UNKNOWN
        }
    }

    private companion object {
        const val UNKNOWN_PACKAGE = "unknown"
    }
}
