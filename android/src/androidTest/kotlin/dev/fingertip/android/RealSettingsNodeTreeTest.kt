package dev.fingertip.android

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.ScreenSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests against Android's real Settings application.
 *
 * These tests intentionally never take a screenshot. They consume the same
 * AccessibilityNodeInfo tree that the production service consumes, proving the
 * fast structured-data path against a real system app rather than a fabricated
 * fixture or OCR output.
 */
@RunWith(AndroidJUnit4::class)
class RealSettingsNodeTreeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Keep Fingertip's real AccessibilityService bound while UiAutomation reads
    // the same tree. The default UiAutomation mode suppresses other services,
    // which would make the test pass while the production service was absent.
    private val automation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )
    private val context = instrumentation.targetContext

    @Before
    fun openSettingsHome() {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        automation.waitForIdle(500, 15_000)
        awaitCapture { it.snapshot.packageName == SETTINGS_PACKAGE }
    }

    @Test
    fun readsRealSettingsAsCompactStructuredDataWithoutPixels() {
        val capture = awaitCapture { candidate ->
            candidate.snapshot.nodes.count { it.visible && it.label != null } >= 5
        }
        val snapshot = capture.snapshot

        assertEquals(SETTINGS_PACKAGE, snapshot.packageName)
        assertFalse("Settings tree unexpectedly empty", snapshot.nodes.isEmpty())
        assertTrue(
            "Expected at least 5 readable labels, got ${snapshot.nodes.mapNotNull { it.label }}",
            snapshot.nodes.count { it.visible && it.label != null } >= 5,
        )
        assertTrue(
            "Expected Settings to expose actionable nodes",
            snapshot.nodes.any { it.visible && it.enabled && it.isInteractive },
        )
        assertTrue(
            "Expected semantic roles, got ${snapshot.nodes.map { it.role }.toSet()}",
            snapshot.nodes.any { it.role in setOf(Role.TEXT, Role.BUTTON, Role.LIST, Role.LIST_ITEM) },
        )
        assertEquals(
            "Every model handle must still map to the exact live Android node used for actions",
            snapshot.nodes.size,
            capture.handles().size,
        )

        val wire = ScreenSerializer().serialize(Redactor().redact(snapshot))
        assertTrue("Wire representation should identify Settings: $wire", "app=$SETTINGS_PACKAGE" in wire)
        assertTrue("Node-tree wire payload grew unexpectedly: ${wire.length} chars", wire.length < 20_000)
        assertFalse("Raw Android class names leaked into the compact model", "android.widget." in wire)

        // Emit only the already-redacted representation for the Gradle report.
        // This gives a human-readable proof of what the model receives without
        // ever writing raw screen content or image pixels.
        println("FINGERTIP_NODE_TREE_BEGIN\n$wire\nFINGERTIP_NODE_TREE_END")
    }

    @Test
    fun tapsTheLabelByWalkingToItsClickableAncestor() {
        val before = awaitCapture { candidate ->
            candidate.snapshot.nodes.any { it.label?.contains("Network", ignoreCase = true) == true }
        }
        val targetIndex = before.snapshot.nodes.indexOfFirst {
            it.visible && it.label?.contains("Network", ignoreCase = true) == true
        }
        assertTrue(
            "No Network-labelled node on Settings home: ${before.snapshot.nodes.mapNotNull { it.label }}",
            targetIndex >= 0,
        )

        val labelledNode = before.liveNodes[targetIndex]
        // On AOSP Settings the text node itself is normally not clickable; its
        // row is. This exercises the same ancestor walk used in production.
        assertTrue(
            "Semantic click was rejected for ${before.snapshot.nodes[targetIndex].label}",
            AndroidNodeActions.clickViaAccessibility(labelledNode),
        )

        automation.waitForIdle(500, 15_000)
        val after = awaitCapture { candidate ->
            candidate.snapshot.packageName == SETTINGS_PACKAGE &&
                candidate.snapshot.nodes.mapNotNull { it.label }.toSet() !=
                before.snapshot.nodes.mapNotNull { it.label }.toSet()
        }

        val labels = after.snapshot.nodes.mapNotNull { it.label }
        assertTrue(
            "Navigation produced no network controls: $labels",
            labels.any {
                it.contains("Internet", ignoreCase = true) ||
                    it.contains("Wi-Fi", ignoreCase = true) ||
                    it.contains("SIM", ignoreCase = true)
            },
        )
    }

    @Test
    fun enforcesANodeBudgetOnARealWideTree() {
        val root = awaitRoot()
        val capture = AndroidNodeTree(AndroidNodeTree.Limits(maxNodes = 5, maxDepth = 40)).capture(
            root = root,
            title = "Settings",
            capturedAtMs = System.currentTimeMillis(),
            screenshotAvailable = true,
        )

        assertTrue("Strict budget exceeded: ${capture.snapshot.nodes.size}", capture.snapshot.nodes.size <= 5)
        assertEquals(capture.snapshot.nodes.size, capture.liveNodes.size)
        assertTrue("A five-node Settings capture should report truncation", capture.truncated)
    }

    private fun awaitCapture(
        timeoutMs: Long = 20_000,
        predicate: (AndroidNodeTree.Capture) -> Boolean,
    ): AndroidNodeTree.Capture {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: AndroidNodeTree.Capture? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                last = AndroidNodeTree().capture(
                    root = root,
                    title = root.window?.title?.toString(),
                    capturedAtMs = System.currentTimeMillis(),
                    screenshotAvailable = true,
                )
                if (predicate(last)) return last
            }
            SystemClock.sleep(200)
        }
        throw AssertionError(
            "Timed out waiting for Settings node tree. Last package=" +
                "${last?.snapshot?.packageName}, labels=${last?.snapshot?.nodes?.mapNotNull { it.label }}",
        )
    }

    private fun awaitRoot(timeoutMs: Long = 20_000): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() == SETTINGS_PACKAGE) return root
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out waiting for Settings root accessibility node")
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}
