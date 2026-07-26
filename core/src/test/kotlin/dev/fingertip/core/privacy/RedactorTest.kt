package dev.fingertip.core.privacy

import dev.fingertip.core.screen.ScreenSnapshot
import dev.fingertip.core.testing.Nodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {

    private val redactor = Redactor()

    private fun snapshotOf(vararg nodes: dev.fingertip.core.screen.Node) =
        ScreenSnapshot.of("com.example.bank", Nodes.container(*nodes), title = "Sign in")

    @Test
    fun `masks the contents of password fields`() {
        val snapshot = snapshotOf(
            Nodes.editText(hint = "Password", value = "hunter2-correct-horse", isPassword = true),
        )

        val result = redactor.redact(snapshot)
        val field = result.snapshot.nodes.single { it.isPassword }

        assertEquals("[redacted]", field.text)
        assertEquals(1, result.redactionCount)
    }

    @Test
    fun `keeps the password field label so the agent can still describe the screen`() {
        val snapshot = snapshotOf(
            Nodes.editText(hint = "Password", value = "s3cret", isPassword = true),
        )

        val field = redactor.redact(snapshot).snapshot.nodes.single { it.isPassword }

        // Losing the hint would leave the agent unable to tell the user what to type where.
        assertEquals("Password", field.contentDescription)
    }

    @Test
    fun `a Password label that is not an input survives untouched`() {
        val snapshot = snapshotOf(Nodes.text("Password", id = "label_password"))

        val node = redactor.redact(snapshot).snapshot.nodes.single { it.role == dev.fingertip.core.screen.Role.TEXT }

        assertEquals("Password", node.text)
    }

    @Test
    fun `masks fields named as secrets even without the password flag`() {
        // Some apps forget to set the password flag; the field id still gives it away.
        val snapshot = snapshotOf(
            Nodes.editText(hint = "Card CVV", value = "492", id = "input_cvv"),
        )

        val result = redactor.redact(snapshot)

        assertTrue(result.snapshot.nodes.none { it.text == "492" })
        assertEquals(1, result.redactionCount)
    }

    @Test
    fun `masks card numbers with and without separators`() {
        assertEquals("[redacted]", redactor.redactText("4111111111111111"))
        assertEquals("[redacted]", redactor.redactText("4111 1111 1111 1111"))
        assertEquals("Card [redacted] expires 10/28", redactor.redactText("Card 4111-1111-1111-1111 expires 10/28"))
    }

    @Test
    fun `masks one-time codes only when the context says they are codes`() {
        assertEquals(
            "Your verification code is [redacted]",
            redactor.redactText("Your verification code is 483920"),
        )
        // Without OTP context, a bare number is ordinary content and must survive.
        assertEquals("You have 483920 points", redactor.redactText("You have 483920 points"))
    }

    @Test
    fun `masks IBANs`() {
        assertEquals("Transfer to [redacted]", redactor.redactText("Transfer to GB33BUKB20201555555555"))
    }

    @Test
    fun `leaves ordinary content alone`() {
        val untouched = listOf(
            "Are you coming to dinner?",
            "Battery 47%",
            "14:02",
            "Meeting at 9:30 on 12 May",
            "Order #4821 shipped",
        )
        untouched.forEach { assertEquals(it, redactor.redactText(it)) }
    }

    @Test
    fun `redaction count reflects every masked value`() {
        val snapshot = snapshotOf(
            Nodes.editText(hint = "Password", value = "abc", isPassword = true),
            Nodes.text("Card 4111 1111 1111 1111"),
        )

        assertEquals(2, redactor.redact(snapshot).redactionCount)
    }

    @Test
    fun `redacting is idempotent`() {
        val snapshot = snapshotOf(Nodes.text("Card 4111111111111111"))

        val once = redactor.redact(snapshot).snapshot
        val twice = redactor.redact(once)

        assertEquals(0, twice.redactionCount)
        assertFalse(twice.snapshot.nodes.any { it.text?.contains("4111") == true })
    }

    @Test
    fun `preserves tree structure and handles`() {
        val snapshot = snapshotOf(
            Nodes.text("hello"),
            Nodes.editText(hint = "Password", value = "x", isPassword = true),
        )

        val redacted = redactor.redact(snapshot).snapshot

        assertEquals(snapshot.nodes.size, redacted.nodes.size)
        assertEquals(snapshot.nodes.map { it.handle }, redacted.nodes.map { it.handle })
    }
}
