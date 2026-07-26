package dev.fingertip.core.speech

import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.screen.ScreenSnapshot
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechFormatterTest {

    private val speech = SpeechFormatter()
    private val redactor = Redactor()

    @Test
    fun `fills placeholders`() {
        assertEquals(
            "Last message: Are you coming?",
            speech.interpolate("Last message: {message}", mapOf("message" to "Are you coming?")),
        )
    }

    @Test
    fun `drops unresolved placeholders rather than speaking braces`() {
        // Hearing "Last message: {message}" read aloud is worse than silence.
        assertEquals("Last message:", speech.interpolate("Last message: {message}", emptyMap()))
    }

    @Test
    fun `reports the placeholders a template needs`() {
        assertEquals(
            setOf("a", "b"),
            speech.placeholdersIn("{a} and {b} and {a}"),
        )
    }

    @Test
    fun `collapses whitespace from screen text`() {
        assertEquals("one two three", speech.clean("  one \n\t two    three  "))
    }

    @Test
    fun `truncates long speech at a sentence boundary`() {
        val formatter = SpeechFormatter(SpeechFormatter.Options(maxSpokenChars = 60))
        val text = "First sentence here. Second sentence here. Third sentence goes on and on and on."

        val out = formatter.clean(text)

        assertTrue(out.length <= 60, "got ${out.length}: $out")
        assertTrue(out.endsWith("."), out)
    }

    @Test
    fun `describes a list screen by naming its items`() {
        val snapshot = ScreenSnapshot.of(
            "com.whatsapp",
            Nodes.container(
                Nodes.list(
                    Nodes.listItem("Mom"),
                    Nodes.listItem("Ali"),
                    Nodes.listItem("Sara"),
                    label = "Conversations",
                ),
            ),
            title = "Chats",
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        assertContains(described, "Whatsapp")
        assertContains(described, "Chats")
        assertContains(described, "3 items")
        assertContains(described, "Mom")
    }

    @Test
    fun `summarises the remainder of a long list`() {
        val items = (1..12).map { Nodes.listItem("Contact $it") }
        val snapshot = ScreenSnapshot.of(
            "com.example",
            Nodes.container(Nodes.list(*items.toTypedArray(), label = "People")),
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        assertContains(described, "12 items")
        assertContains(described, ", and 7 more.")
    }

    @Test
    fun `the item list ends with punctuation so speech does not run on`() {
        val snapshot = ScreenSnapshot.of(
            "com.example.bank",
            Nodes.container(
                Nodes.list(Nodes.listItem("Current account"), label = "Accounts"),
                Nodes.editText(hint = "Password", value = "x", isPassword = true),
            ),
            title = "Accounts",
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        // Without the full stop, TTS reads "...Current account 1 sensitive value hidden".
        assertContains(described, "Current account. ")
    }

    @Test
    fun `falls back to naming the controls when there is no list`() {
        val snapshot = ScreenSnapshot.of(
            "com.example",
            Nodes.container(Nodes.button("Accept"), Nodes.button("Decline")),
            title = "Permission",
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        assertContains(described, "Controls")
        assertContains(described, "Accept")
    }

    @Test
    fun `explains a blocked screen capture in plain language`() {
        val snapshot = ScreenSnapshot.of(
            "com.example.bank",
            Nodes.container(Nodes.text("Balance")),
            screenshotAvailable = false,
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        assertContains(described, "blocks screen capture")
    }

    @Test
    fun `tells the user when values were hidden`() {
        val snapshot = ScreenSnapshot.of(
            "com.example.bank",
            Nodes.container(Nodes.editText(hint = "Password", value = "abc", isPassword = true)),
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        // Silent degradation is disorienting; say that something was hidden.
        assertContains(described, "1 sensitive value hidden")
    }

    @Test
    fun `failure explanation offers a way forward`() {
        val message = speech.describeFailure("Read my last WhatsApp message", "tap text=\"Archived\"", "com.whatsapp")

        assertContains(message, "Read my last WhatsApp message")
        assertContains(message, "Whatsapp")
        assertContains(message, "figure it out")
    }

    @Test
    fun `describes nodes the way a person would`() {
        assertEquals("Send, button", speech.describeNode(Nodes.button("Send")))
        assertEquals("Password, password field", speech.describeNode(Nodes.editText("Password", isPassword = true)))
        assertEquals("Wi-Fi, switched on", speech.describeNode(Nodes.switch("Wi-Fi", checked = true)))
        assertEquals("Search mail, text field", speech.describeNode(Nodes.editText("Search mail")))
    }

    @Test
    fun `never reads internal structure aloud`() {
        val snapshot = ScreenSnapshot.of(
            "com.whatsapp",
            Nodes.container(Nodes.list(Nodes.listItem("Mom"), label = "Conversations")),
            title = "Chats",
        )

        val described = speech.describeScreen(redactor.redact(snapshot))

        listOf("listitem", "handle", "clickable", "Node(", "role=").forEach {
            assertTrue(it !in described, "leaked internals ('$it'): $described")
        }
    }
}
