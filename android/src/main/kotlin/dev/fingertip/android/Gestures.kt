package dev.fingertip.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Synthetic touch gestures, used only when the accessibility actions fail.
 *
 * Prefer ACTION_CLICK and friends: they respect the app's own semantics, work
 * with any screen geometry, and cannot land on the wrong element because the
 * layout shifted. These are the escape hatch for canvas-drawn and
 * custom-rendered UI that exposes no usable actions.
 *
 * `dispatchGesture` is asynchronous, so each call blocks on a latch to keep the
 * [dev.fingertip.core.device.Device] contract synchronous. Must therefore be
 * called off the main thread.
 */
internal object Gestures {

    private const val TAP_DURATION_MS = 60L
    private const val DISPATCH_TIMEOUT_MS = 5_000L

    fun tap(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(service, path, 0, TAP_DURATION_MS)
    }

    fun swipe(
        service: AccessibilityService,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return dispatch(service, path, 0, durationMs.coerceAtLeast(1))
    }

    private fun dispatch(
        service: AccessibilityService,
        path: Path,
        startTimeMs: Long,
        durationMs: Long,
    ): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val latch = CountDownLatch(1)
        var completed = false

        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!accepted) return false

        return try {
            latch.await(DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
