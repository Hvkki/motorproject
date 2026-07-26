package dev.fingertip.android

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Semantic actions against live accessibility nodes.
 *
 * These are always attempted before synthesising touch coordinates. A semantic
 * click survives font scaling, rotation, split screen and different device
 * sizes; a coordinate tap does not.
 */
internal object AndroidNodeActions {
    private const val MAX_ANCESTOR_HOPS = 6

    /**
     * Clicks [node] or its nearest enabled clickable ancestor.
     *
     * Android commonly exposes a row label as a non-clickable TextView inside a
     * clickable container. Skills select the meaningful label, so walking upward
     * is essential — otherwise the agent can see the control but cannot use it.
     */
    fun clickViaAccessibility(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            hops++
        }
        return false
    }
}
