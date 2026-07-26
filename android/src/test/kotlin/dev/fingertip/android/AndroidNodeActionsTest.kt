package dev.fingertip.android

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies semantic clicking against real `AccessibilityNodeInfo` objects.
 *
 * This is the behaviour every skill depends on: Android apps routinely expose a
 * row's label as a non-clickable TextView inside a clickable container. Selectors
 * match the meaningful label, so the click must walk upward to find the node that
 * actually handles it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidNodeActionsTest {

    @Suppress("DEPRECATION")
    private fun node(
        text: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        isClickable = clickable
        isEnabled = enabled
        isVisibleToUser = true
    }

    @Test
    fun `clicks a node that is itself clickable`() {
        val button = node(text = "Send", clickable = true)

        assertTrue(AndroidNodeActions.clickViaAccessibility(button))
        assertTrue(
            AccessibilityNodeInfo.ACTION_CLICK in shadowOf(button).performedActions,
        )
    }

    @Test
    fun `walks up from a label to its clickable row`() {
        val label = node(text = "Network and internet")
        val row = node(clickable = true)
        shadowOf(row).addChild(label)

        assertTrue(AndroidNodeActions.clickViaAccessibility(label))

        // The row receives the click, not the label.
        assertTrue(AccessibilityNodeInfo.ACTION_CLICK in shadowOf(row).performedActions)
        assertFalse(AccessibilityNodeInfo.ACTION_CLICK in shadowOf(label).performedActions)
    }

    @Test
    fun `skips a disabled ancestor and keeps looking`() {
        val label = node(text = "Statements")
        val disabledRow = node(clickable = true, enabled = false)
        val enabledCard = node(clickable = true)
        shadowOf(disabledRow).addChild(label)
        shadowOf(enabledCard).addChild(disabledRow)

        assertTrue(AndroidNodeActions.clickViaAccessibility(label))

        assertTrue(AccessibilityNodeInfo.ACTION_CLICK in shadowOf(enabledCard).performedActions)
        assertFalse(AccessibilityNodeInfo.ACTION_CLICK in shadowOf(disabledRow).performedActions)
    }

    @Test
    fun `reports failure when nothing in the chain is clickable`() {
        // Returning false is what makes AccessibilityDevice fall back to a
        // synthetic gesture rather than silently doing nothing.
        val label = node(text = "Decorative")
        val wrapper = node()
        shadowOf(wrapper).addChild(label)

        assertFalse(AndroidNodeActions.clickViaAccessibility(label))
        assertTrue(shadowOf(label).performedActions.isEmpty())
        assertTrue(shadowOf(wrapper).performedActions.isEmpty())
    }

    @Test
    fun `stops climbing after a bounded number of hops`() {
        // Without a bound, a deep decorative hierarchy could walk to the window
        // root and click something entirely unrelated to what the user asked for.
        val label = node(text = "Buried")
        var current = label
        val ancestors = mutableListOf<AccessibilityNodeInfo>()
        repeat(9) {
            val parent = node()
            shadowOf(parent).addChild(current)
            ancestors += parent
            current = parent
        }
        val clickableRoot = node(clickable = true)
        shadowOf(clickableRoot).addChild(current)

        assertFalse(
            "must not reach a clickable node 10 levels above the label",
            AndroidNodeActions.clickViaAccessibility(label),
        )
        assertEquals(0, shadowOf(clickableRoot).performedActions.size)
    }
}
