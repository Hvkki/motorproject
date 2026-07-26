package dev.fingertip.core.agent

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

/**
 * [HttpTransport] over `HttpsURLConnection`, with no third-party dependencies.
 *
 * Deliberately dependency-free: this runs inside an accessibility service that
 * blind users depend on, and every library added is more code with access to
 * screen content and a key. `HttpsURLConnection` ships with Android.
 *
 * Plain HTTP is refused outright. Screen descriptions and an API key travel in
 * these requests, so an accidental `http://` in configuration must fail rather
 * than silently transmit in clear text.
 */
class UrlConnectionTransport : HttpTransport {

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
    ): HttpResponse {
        if (!url.startsWith("https://", ignoreCase = true)) {
            return HttpResponse(0, "", networkError = "refusing to send credentials over plain HTTP")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                readTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                doOutput = true
                // Avoid buffering the whole request twice for a payload we already
                // have in memory.
                val payload = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(payload.size)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                outputStream.use { it.write(payload) }
            }

            val status = connection.responseCode
            // Errors arrive on the error stream, and that is where the provider's
            // explanation lives, so read whichever is present.
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            HttpResponse(status, text)
        } catch (timeout: SocketTimeoutException) {
            HttpResponse(0, "", networkError = "timed out after ${timeoutMs}ms")
        } catch (host: UnknownHostException) {
            // The common case in this product: the user is simply offline.
            HttpResponse(0, "", networkError = "no network connection")
        } catch (io: IOException) {
            HttpResponse(0, "", networkError = io.message ?: "network error")
        } finally {
            connection?.disconnect()
        }
    }
}
