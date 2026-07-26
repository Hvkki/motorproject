package dev.fingertip.core.screen

import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectorTest {

    private val screen = ScreenSnapshot.of(
        packageName = "com.whatsapp",
        title = "Chats",
        root = Nodes.container(
            Nodes.list(
                Nodes.listItem("Mom"),
                Nodes.listItem("Ali"),
                Nodes.listItem("Sara"),
                label = "Conversations",
            ),
            Nodes.button("New chat", id = "com.whatsapp:id/fab", byDescription = true),
            Nodes.button("Archived", enabled = false),
        ),
    )

    @Test
    fun `matches exact text case-insensitively`() {
        assertEquals("Mom", Selector(text = "mom").findOne(screen)?.text)
        assertEquals("Mom", Selector(text = "  MOM  ").findOne(screen)?.text)
    }

    @Test
    fun `matches content description`() {
        assertEquals("New chat", Selector(desc = "New chat").findOne(screen)?.contentDescription)
    }

    @Test
    fun `labelContains spans text and description`() {
        // Matches via contentDescription, where there is no text at all.
        val byDescription = Selector(labelContains = "chat", role = Role.BUTTON).findOne(screen)
        assertEquals("New chat", byDescription?.contentDescription)
        assertNull(byDescription?.text)

        // ...and via text on a node with no description.
        assertEquals("Sara", Selector(labelContains = "ara").findOne(screen)?.text)
    }

    @Test
    fun `viewId matches both bare and fully qualified forms`() {
        assertEquals("New chat", Selector(viewId = "fab").findOne(screen)?.contentDescription)
        assertEquals("New chat", Selector(viewId = "com.whatsapp:id/fab").findOne(screen)?.contentDescription)
    }

    @Test
    fun `combines constraints with AND`() {
        assertNull(Selector(text = "Mom", role = Role.BUTTON).findOne(screen))
        assertEquals("Mom", Selector(text = "Mom", role = Role.LIST_ITEM).findOne(screen)?.text)
    }

    @Test
    fun `positive index selects the nth match in pre-order`() {
        assertEquals("Mom", Selector(role = Role.LIST_ITEM, index = 0).findOne(screen)?.text)
        assertEquals("Ali", Selector(role = Role.LIST_ITEM, index = 1).findOne(screen)?.text)
    }

    @Test
    fun `negative index counts from the end`() {
        // The idiom for "the newest item in a chronological list".
        assertEquals("Sara", Selector(role = Role.LIST_ITEM, index = -1).findOne(screen)?.text)
        assertEquals("Ali", Selector(role = Role.LIST_ITEM, index = -2).findOne(screen)?.text)
    }

    @Test
    fun `out of range index yields nothing rather than the wrong element`() {
        assertNull(Selector(role = Role.LIST_ITEM, index = 9).findOne(screen))
        assertNull(Selector(role = Role.LIST_ITEM, index = -9).findOne(screen))
    }

    @Test
    fun `disabled elements are not matched`() {
        // Tapping a greyed-out control silently does nothing, which is the worst
        // outcome for a user who cannot see that it was disabled.
        assertNull(Selector(text = "Archived").findOne(screen))
    }

    @Test
    fun `invisible elements are not matched`() {
        val hidden = ScreenSnapshot.of(
            "com.example",
            Nodes.container(Nodes.listItem("Ghost").copy(visible = false)),
        )
        assertNull(Selector(text = "Ghost").findOne(hidden))
    }

    @Test
    fun `scrollable flag selects the container`() {
        assertEquals(Role.LIST, Selector(scrollable = true).findOne(screen)?.role)
    }

    @Test
    fun `an unconstrained selector is rejected at construction`() {
        // A selector matching everything would tap an arbitrary element.
        assertFailsWith<IllegalArgumentException> { Selector() }
    }

    @Test
    fun `describe produces something readable in a failure message`() {
        val described = Selector(text = "Send", role = Role.BUTTON, index = 0).describe()
        assertTrue("Send" in described, described)
        assertTrue("button" in described, described)
    }
}
