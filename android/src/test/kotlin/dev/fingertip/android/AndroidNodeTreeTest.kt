package dev.fingertip.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.Role
import dev.fingertip.core.screen.ScreenSerializer
import dev.fingertip.core.screen.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the Android perception layer against the real framework classes.
 *
 * Robolectric runs actual `AccessibilityNodeInfo` code on the JVM, so these tests
 * verify the traversal, role mapping, node budget and semantic-click behaviour in
 * seconds without KVM or an emulator.
 *
 * What this does NOT prove, and what still needs real hardware: the layout of a
 * genuine third-party app, real gesture dispatch, and TalkBack coexistence. Those
 * live in `RealSettingsNodeTreeTest` under `androidTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidNodeTreeTest {

    // --- fixture helpers -----------------------------------------------------

    // obtain() is deprecated in favour of the constructor, but Robolectric's
    // shadow is built around the pooled factory, so it stays the reliable choice
    // for fixtures.
    @Suppress("DEPRECATION")
    private fun node(
        className: String = "android.widget.TextView",
        text: String? = null,
        description: String? = null,
        bounds: Rect = Rect(0, 0, 1080, 120),
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        password: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        packageName: String = "com.example.app",
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.className = className
        this.packageName = packageName
        this.text = text
        this.contentDescription = description
        setBoundsInScreen(bounds)
        isClickable = clickable
        isEnabled = enabled
        isVisibleToUser = visible
        isPassword = password
        isEditable = editable
        isScrollable = scrollable
    }

    private fun AccessibilityNodeInfo.withChildren(
        vararg children: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo {
        children.forEach { shadowOf(this).addChild(it) }
        return this
    }

    private fun capture(
        root: AccessibilityNodeInfo,
        limits: AndroidNodeTree.Limits = AndroidNodeTree.Limits(),
    ) = AndroidNodeTree(limits).capture(
        root = root,
        title = "Test Screen",
        capturedAtMs = 1_000L,
        screenshotAvailable = true,
    )

    // --- traversal -----------------------------------------------------------

    @Test
    fun `reads package title and structure from the real node tree`() {
        val root = node(className = "android.widget.FrameLayout", packageName = "com.example.bank")
            .withChildren(
                node(text = "Current account"),
                node(className = "android.widget.Button", text = "Transfer", clickable = true),
            )

        val snapshot = capture(root).snapshot

        assertEquals("com.example.bank", snapshot.packageName)
        assertEquals("Test Screen", snapshot.windowTitle)
        assertEquals(3, snapshot.nodes.size)
        assertEquals(listOf("Current account", "Transfer"), snapshot.nodes.drop(1).map { it.label })
    }

    @Test
    fun `keeps one live Android node per model handle`() {
        // This invariant is what lets an action address a node by handle. If the
        // two lists ever drift, taps would land on the wrong element.
        val root = node(className = "android.widget.LinearLayout").withChildren(
            node(text = "One"),
            node(text = "Two").withChildren(node(text = "Nested")),
        )

        val capture = capture(root)

        assertEquals(capture.snapshot.nodes.size, capture.liveNodes.size)
        assertEquals(4, capture.handles().size)
        capture.snapshot.nodes.forEach { model ->
            val live = capture.handles()[model.handle]
            assertNotNull("handle ${model.handle} has no live node", live)
            assertEquals(model.text, live?.text?.toString())
        }
    }

    @Test
    fun `assigns dense pre-order handles`() {
        val root = node(className = "android.widget.LinearLayout").withChildren(
            node(text = "First"),
            node(text = "Second"),
        )

        val handles = capture(root).snapshot.nodes.map { it.handle }

        assertEquals(listOf(1, 2, 3), handles)
    }

    // --- role mapping --------------------------------------------------------

    @Test
    fun `maps real Android class names onto semantic roles`() {
        val cases = mapOf(
            "android.widget.Button" to Role.BUTTON,
            "android.widget.ImageButton" to Role.BUTTON,
            "android.widget.TextView" to Role.TEXT,
            "android.widget.ImageView" to Role.IMAGE,
            "android.widget.CheckBox" to Role.CHECKBOX,
            "android.widget.Switch" to Role.SWITCH,
            "android.webkit.WebView" to Role.WEB_VIEW,
            "androidx.recyclerview.widget.RecyclerView" to Role.LIST,
            "android.widget.ScrollView" to Role.LIST,
            "android.widget.FrameLayout" to Role.CONTAINER,
        )

        cases.forEach { (className, expected) ->
            val snapshot = capture(node(className = className)).snapshot
            assertEquals("wrong role for $className", expected, snapshot.root.role)
        }
    }

    @Test
    fun `treats an editable field as a text input regardless of class name`() {
        val snapshot = capture(node(className = "com.example.CustomInput", editable = true)).snapshot

        assertEquals(Role.EDIT_TEXT, snapshot.root.role)
    }

    @Test
    fun `treats an unrecognised clickable node as a button`() {
        // Custom widgets are common; a clickable one still behaves like a button.
        val snapshot = capture(node(className = "com.example.FancyThing", clickable = true)).snapshot

        assertEquals(Role.BUTTON, snapshot.root.role)
    }

    // --- budget --------------------------------------------------------------

    @Test
    fun `enforces the node budget across wide sibling lists`() {
        // The earlier implementation checked the budget only before descending, so
        // one wide parent could blow past it. This pins the real bound.
        val children = (1..50).map { node(text = "Row $it") }
        val root = node(className = "android.widget.LinearLayout").withChildren(*children.toTypedArray())

        val capture = capture(root, AndroidNodeTree.Limits(maxNodes = 8))

        assertEquals(8, capture.snapshot.nodes.size)
        assertEquals(8, capture.liveNodes.size)
        assertTrue("truncation should be reported", capture.truncated)
    }

    @Test
    fun `enforces the depth budget`() {
        var deepest = node(text = "Bottom")
        repeat(12) { level ->
            deepest = node(className = "android.widget.FrameLayout", text = "Level $level")
                .withChildren(deepest)
        }

        val capture = capture(deepest, AndroidNodeTree.Limits(maxNodes = 500, maxDepth = 3))

        assertTrue(capture.truncated)
        assertTrue("depth not bounded: ${capture.snapshot.nodes.size}", capture.snapshot.nodes.size <= 4)
    }

    @Test
    fun `does not report truncation for a tree within budget`() {
        val root = node(className = "android.widget.LinearLayout").withChildren(node(text = "Only"))

        assertFalse(capture(root, AndroidNodeTree.Limits(maxNodes = 50)).truncated)
    }

    // --- state fidelity ------------------------------------------------------

    @Test
    fun `carries visibility and enablement through to selectors`() {
        val root = node(className = "android.widget.LinearLayout").withChildren(
            node(className = "android.widget.Button", text = "Enabled", clickable = true),
            node(className = "android.widget.Button", text = "Disabled", clickable = true, enabled = false),
            node(className = "android.widget.Button", text = "Offscreen", clickable = true, visible = false),
        )

        val snapshot = capture(root).snapshot

        assertNotNull(Selector(text = "Enabled").findOne(snapshot))
        // Selector deliberately refuses disabled and invisible nodes, so a skill
        // cannot "tap" something the user could never touch.
        assertEquals(null, Selector(text = "Disabled").findOne(snapshot))
        assertEquals(null, Selector(text = "Offscreen").findOne(snapshot))
    }

    @Test
    fun `preserves real screen bounds`() {
        val snapshot = capture(node(bounds = Rect(30, 210, 1050, 330))).snapshot

        val bounds = snapshot.root.bounds
        assertEquals(30, bounds.left)
        assertEquals(210, bounds.top)
        assertEquals(1050, bounds.right)
        assertEquals(330, bounds.bottom)
        assertEquals(540, bounds.centerX)
    }

    // --- privacy end to end --------------------------------------------------

    @Test
    fun `a real password node is redacted before it can be serialised`() {
        val root = node(className = "android.widget.LinearLayout", packageName = "com.example.bank")
            .withChildren(
                node(
                    className = "android.widget.EditText",
                    text = "hunter2",
                    description = "Password",
                    password = true,
                    editable = true,
                ),
                node(text = "Card 4111 1111 1111 1111"),
                node(text = "Your verification code is 483920"),
            )

        val snapshot = capture(root).snapshot
        val wire = ScreenSerializer().serialize(Redactor().redact(snapshot))

        assertFalse("password leaked: $wire", "hunter2" in wire)
        assertFalse("card number leaked: $wire", "4111" in wire)
        assertFalse("one-time code leaked: $wire", "483920" in wire)
        // The hint survives so the agent can still tell the user what to type.
        assertTrue("password field lost its label: $wire", "\"Password\"" in wire)
        assertTrue(wire.contains("[redacted]"))
    }

    @Test
    fun `serialised output stays compact and free of framework class names`() {
        val rows = (1..25).map {
            node(className = "android.widget.TextView", text = "Contact $it with a preview line")
        }
        val root = node(className = "android.widget.FrameLayout")
            .withChildren(
                node(className = "androidx.recyclerview.widget.RecyclerView", scrollable = true)
                    .withChildren(*rows.toTypedArray()),
            )

        val wire = ScreenSerializer().serialize(Redactor().redact(capture(root).snapshot))

        assertTrue("payload too large: ${wire.length}", wire.length < 3_000)
        assertFalse("leaked framework class names: $wire", "android.widget" in wire)
        assertTrue(wire.contains("Contact 1"))
    }
}
