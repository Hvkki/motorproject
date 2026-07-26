package dev.fingertip.core.device

import dev.fingertip.core.screen.Bounds
import dev.fingertip.core.screen.ScreenSnapshot

/**
 * Everything the core needs from a phone, and nothing more.
 *
 * This is the seam that keeps the interpreter, selectors, redaction and speech
 * logic testable on a plain JVM. The Android implementation is a thin adapter
 * over AccessibilityService; the test implementation is a scripted fake.
 *
 * Actions are addressed by [dev.fingertip.core.screen.Node.handle] rather than by
 * coordinates so the adapter can dispatch against the real accessibility node
 * (`performAction`) and fall back to a synthetic gesture only when it must.
 * Coordinate taps are fragile: they break on font scaling, split screen, and
 * every device with a different aspect ratio.
 */
interface Device {

    /** Physical screen bounds, for swipe geometry. */
    val screenBounds: Bounds

    /**
     * Captures the current screen. Implementations must return a snapshot built
     * via [ScreenSnapshot.of] so handles are valid, and should record the handle
     * -> live node mapping for subsequent action calls.
     */
    fun snapshot(): ScreenSnapshot

    /** Brings [packageName] to the foreground. False when not installed or blocked. */
    fun launchApp(packageName: String): Boolean

    /** Activates the node, preferring the accessibility click action. */
    fun tap(handle: Int): Boolean

    fun longPress(handle: Int): Boolean

    /** Replaces the contents of an editable node. */
    fun setText(handle: Int, text: String): Boolean

    /** Scrolls the node in [direction]; false when it cannot scroll further. */
    fun scroll(handle: Int, direction: ScrollDirection): Boolean

    /** Freehand swipe in screen coordinates, for gesture-only surfaces. */
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean

    fun pressBack(): Boolean

    fun pressHome(): Boolean

    /** Blocks the calling thread. Abstracted so tests can use a virtual clock. */
    fun sleep(millis: Long)

    /** Milliseconds since epoch. Abstracted for the same reason. */
    fun nowMs(): Long
}

enum class ScrollDirection { FORWARD, BACKWARD, UP, DOWN, LEFT, RIGHT }
