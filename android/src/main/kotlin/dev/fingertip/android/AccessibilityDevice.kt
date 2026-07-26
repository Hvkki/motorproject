package dev.fingertip.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.fingertip.core.device.Device
import dev.fingertip.core.device.ScrollDirection
import dev.fingertip.core.screen.Bounds
import dev.fingertip.core.screen.Node
import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.ScreenSnapshot

/**
 * Adapts Android's AccessibilityService to the platform-neutral [Device] port.
 *
 * This is the only class in the project that knows Android exists. Everything
 * interesting — selectors, redaction, skill replay, speech phrasing — lives in
 * `:core` and is unit-tested on a plain JVM. Keep this file thin and boring; if
 * logic starts accumulating here, it belongs in core where it can be tested.
 *
 * THREADING: [snapshot] and the action methods must not run on the main thread.
 * [dev.fingertip.core.skill.SkillInterpreter] blocks in [sleep] while polling for
 * screens, which would freeze the UI and trip the watchdog. [SkillRunner] owns
 * that background thread; do not call this class directly from a callback.
 */
class AccessibilityDevice(
    private val service: AccessibilityService,
    private val limits: Limits = Limits(),
) : Device {

    data class Limits(
        /** Guard against pathological trees (deeply nested WebViews). */
        val maxNodes: Int = 800,
        val maxDepth: Int = 40,
    )

    /**
     * handle -> live node, rebuilt on every [snapshot].
     *
     * Actions address nodes by handle rather than coordinates so we can use the
     * real accessibility actions. Coordinate taps break under font scaling, split
     * screen, and any device with different dimensions than the one a skill was
     * recorded on.
     */
    private var liveNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()

    override val screenBounds: Bounds
        get() {
            val metrics = service.resources.displayMetrics
            return Bounds(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

    override fun snapshot(): ScreenSnapshot {
        val root = service.rootInActiveWindow
            ?: return ScreenSnapshot.of(
                packageName = UNKNOWN_PACKAGE,
                root = Node(role = Role.CONTAINER),
                title = null,
            )

        val collected = mutableListOf<AccessibilityNodeInfo>()
        val tree = convert(root, depth = 0, collected = collected)

        val snapshot = ScreenSnapshot.of(
            packageName = root.packageName?.toString() ?: UNKNOWN_PACKAGE,
            root = tree,
            title = activeWindowTitle(),
            capturedAtMs = nowMs(),
            screenshotAvailable = !isSecureWindow(),
        )

        // ScreenSnapshot.of assigns handles in pre-order; convert() collected the
        // source nodes in the same pre-order, so the two zip up exactly.
        liveNodes = snapshot.nodes.map { it.handle }.zip(collected).toMap()
        return snapshot
    }

    private fun convert(
        source: AccessibilityNodeInfo,
        depth: Int,
        collected: MutableList<AccessibilityNodeInfo>,
    ): Node {
        collected += source

        val rect = Rect().also { source.getBoundsInScreen(it) }
        val children = if (depth >= limits.maxDepth || collected.size >= limits.maxNodes) {
            emptyList()
        } else {
            (0 until source.childCount).mapNotNull { index ->
                source.getChild(index)?.let { child -> convert(child, depth + 1, collected) }
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
     * Normalises Android's class names into a small role vocabulary.
     *
     * Collection info is checked before class names: a chat row might be any
     * ViewGroup subclass, but `collectionItemInfo` reliably marks it as a list
     * item, which is what "read my last message" depends on.
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
            // A clickable node with no recognisable class still behaves like a button.
            node.isClickable -> Role.BUTTON
            else -> Role.UNKNOWN
        }
    }

    private fun activeWindowTitle(): String? =
        service.windows
            .firstOrNull { it.isActive }
            ?.title
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    /**
     * Heuristic for FLAG_SECURE windows (banking, DRM video).
     *
     * Android exposes no direct "is this window secure" query, so this is a best
     * effort. It only downgrades a hint in the snapshot — the node tree usually
     * remains readable, which is why screenshots are a fallback rather than the
     * primary input. Never gate functionality on this being accurate.
     */
    private fun isSecureWindow(): Boolean = false

    override fun launchApp(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { service.startActivity(intent) }.isSuccess
    }

    override fun tap(handle: Int): Boolean {
        val node = liveNodes[handle] ?: return false
        // Walk up to the nearest clickable ancestor: labels are frequently
        // non-clickable children of the row that actually handles the tap.
        clickableSelfOrAncestor(node)?.let {
            if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return tapByGesture(node)
    }

    override fun longPress(handle: Int): Boolean {
        val node = liveNodes[handle] ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    override fun setText(handle: Int, text: String): Boolean {
        val node = liveNodes[handle] ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true
        // Some editors ignore ACTION_SET_TEXT; focusing first often unblocks it.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun scroll(handle: Int, direction: ScrollDirection): Boolean {
        val node = liveNodes[handle] ?: return false
        val action = when (direction) {
            ScrollDirection.FORWARD, ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD, ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            // Directional scrolling arrived in API 29; fall back to logical scrolling.
            ScrollDirection.LEFT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            ScrollDirection.RIGHT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
        }
        // Returns false at the end of the list, which the interpreter treats as
        // "cannot scroll further" rather than an error.
        return node.performAction(action)
    }

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean =
        Gestures.swipe(service, fromX, fromY, toX, toY, durationMs)

    override fun pressBack(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun pressHome(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    override fun sleep(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            // Cancellation: the user spoke over us. Restore the flag so the
            // runner's loop sees it and unwinds promptly.
            Thread.currentThread().interrupt()
        }
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /** Last resort when no ancestor accepts ACTION_CLICK: synthesise a touch. */
    private fun tapByGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return false
        return Gestures.tap(service, rect.centerX(), rect.centerY())
    }

    private companion object {
        const val UNKNOWN_PACKAGE = "unknown"
        const val MAX_ANCESTOR_HOPS = 6
    }
}
