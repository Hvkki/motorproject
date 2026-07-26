package dev.fingertip.core.screen

import dev.fingertip.core.privacy.Redactor
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenSerializerTest {

    private val redactor = Redactor()
    private val serializer = ScreenSerializer()

    private fun render(snapshot: ScreenSnapshot, serializer: ScreenSerializer = this.serializer) =
        serializer.serialize(redactor.redact(snapshot))

    @Test
    fun `header names the app and screen`() {
        val out = render(
            ScreenSnapshot.of("com.whatsapp", Nodes.container(Nodes.button("Send")), title = "Chats"),
        )

        assertContains(out, "app=com.whatsapp")
        assertContains(out, "screen=\"Chats\"")
    }

    @Test
    fun `emits handles roles labels and interaction flags`() {
        val out = render(ScreenSnapshot.of("com.example", Nodes.container(Nodes.button("Send"))))

        val line = out.lines().first { "Send" in it }
        assertContains(line, "button")
        assertContains(line, "\"Send\"")
        assertContains(line, "(tap)")
        assertTrue(Regex("\\[\\d+]").containsMatchIn(line), "expected a handle in: $line")
    }

    @Test
    fun `collapses layout wrappers that carry no meaning`() {
        // Real Android trees nest these several deep around every element.
        val nested = Nodes.wrapper(Nodes.wrapper(Nodes.wrapper(Nodes.button("Send"))))
        val out = render(ScreenSnapshot.of("com.example", nested))

        val containerLines = out.lines().count { "container" in it }
        assertEquals(0, containerLines, "pass-through wrappers should vanish:\n$out")
        assertContains(out, "\"Send\"")
    }

    @Test
    fun `keeps unlabelled containers that actually group things`() {
        val out = render(
            ScreenSnapshot.of(
                "com.example",
                Nodes.wrapper(Nodes.button("Yes"), Nodes.button("No")),
            ),
        )

        assertContains(out, "\"Yes\"")
        assertContains(out, "\"No\"")
    }

    @Test
    fun `drops zero-area and invisible nodes`() {
        val root = Nodes.container(
            Nodes.button("Visible"),
            Nodes.button("Hidden").copy(visible = false),
            Nodes.button("Collapsed").copy(bounds = Bounds.ZERO),
        )
        val out = render(ScreenSnapshot.of("com.example", root))

        assertContains(out, "Visible")
        assertTrue("Hidden" !in out, out)
        assertTrue("Collapsed" !in out, out)
    }

    @Test
    fun `ellipsises long labels`() {
        val long = "word ".repeat(200)
        val out = render(
            ScreenSnapshot.of("com.example", Nodes.container(Nodes.text(long))),
            ScreenSerializer(ScreenSerializer.Options(maxLabelChars = 40)),
        )

        val line = out.lines().first { "word" in it }
        assertTrue(line.length < 120, "label was not truncated: $line")
        assertContains(line, "\u2026")
    }

    @Test
    fun `truncates at the node budget`() {
        val many = (1..300).map { Nodes.listItem("Item $it") }
        val out = render(
            ScreenSnapshot.of("com.example", Nodes.container(*many.toTypedArray())),
            ScreenSerializer(ScreenSerializer.Options(maxNodes = 20)),
        )

        assertContains(out, "truncated")
        assertTrue(out.lines().size < 30, "budget not enforced, got ${out.lines().size} lines")
    }

    @Test
    fun `announces when the app blocks screen capture`() {
        val out = render(
            ScreenSnapshot.of(
                "com.example.bank",
                Nodes.container(Nodes.text("Balance")),
                screenshotAvailable = false,
            ),
        )

        assertContains(out, "screenshot blocked")
    }

    @Test
    fun `notes how many values were redacted`() {
        val out = render(
            ScreenSnapshot.of(
                "com.example.bank",
                Nodes.container(Nodes.editText(hint = "Password", value = "abc", isPassword = true)),
            ),
        )

        assertContains(out, "1 value(s) redacted")
    }

    @Test
    fun `reports an empty screen rather than nothing at all`() {
        val out = render(ScreenSnapshot.of("com.example", Nodes.wrapper()))

        assertContains(out, "no readable elements")
    }

    @Test
    fun `output stays small for a realistic screen`() {
        // The economic argument for text over screenshots: this must stay cheap.
        val root = Nodes.container(
            Nodes.list(
                *(1..30).map { Nodes.listItem("Contact $it - last message preview text here") }.toTypedArray(),
                label = "Conversations",
            ),
            Nodes.button("New chat", byDescription = true),
        )
        val out = render(ScreenSnapshot.of("com.whatsapp", root, title = "Chats"))

        assertTrue(out.length < 3_000, "serialised screen grew to ${out.length} chars")
        assertContains(out, "Contact 1")
    }
}
