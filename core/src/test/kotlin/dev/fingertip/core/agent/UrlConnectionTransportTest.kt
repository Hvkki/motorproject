package dev.fingertip.core.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrlConnectionTransportTest {

    @Test
    fun `refuses plain HTTP rather than sending credentials in the clear`() {
        // A stray http:// in configuration must fail loudly. These requests carry
        // an API key and a description of the user's screen.
        val response = UrlConnectionTransport().post(
            url = "http://api.example.com/v1/messages",
            headers = mapOf("x-api-key" to "secret"),
            body = "{}",
            timeoutMs = 1_000,
        )

        assertEquals(0, response.status)
        assertContains(response.networkError ?: "", "plain HTTP")
    }

    @Test
    fun `reports an unreachable host as a network error`() {
        val response = UrlConnectionTransport().post(
            // Reserved by RFC 6761 for exactly this: guaranteed not to resolve.
            url = "https://fingertip-does-not-exist.invalid/v1/messages",
            headers = emptyMap(),
            body = "{}",
            timeoutMs = 3_000,
        )

        assertEquals(0, response.status)
        assertTrue(response.networkError != null, "an unresolvable host must surface as a network error")
    }
}
