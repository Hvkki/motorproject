package dev.fingertip.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Shapes requests and responses for one model provider's HTTPS API.
 *
 * Exists so the phone can reach a model **directly**, with no backend of your own
 * to run, pay for or keep patched. The trade-off is that the API key lives on the
 * device, which is why [HttpPlanner] never holds it and the Android layer keeps it
 * in encrypted storage.
 */
sealed interface ModelProvider {
    val name: String

    /** Default model when the caller does not choose one. */
    val defaultModel: String

    fun endpoint(model: String): String

    /** Auth and content headers. The key is used here and nowhere else. */
    fun headers(apiKey: String): Map<String, String>

    fun body(model: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String

    /** The assistant's text, or null when the response has an unexpected shape. */
    fun extractText(responseBody: String): String?

    /** Provider-specific error text, for a diagnosable message. */
    fun extractError(responseBody: String): String?

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Looks up a provider by name, case-insensitively. */
        fun byName(name: String): ModelProvider? = when (name.lowercase().replace("-", "")) {
            "anthropic", "claude" -> Anthropic
            "gemini", "google" -> Gemini
            "openai" -> OpenAiCompatible()
            // Needs a base URL, so it cannot be built from a name alone.
            "kiro" -> null
            else -> null
        }
    }
}

/** Anthropic Messages API. */
data object Anthropic : ModelProvider {
    override val name = "anthropic"
    override val defaultModel = "claude-haiku-4-5"

    /** Pinned: an unpinned version header is a silent breaking-change waiting to happen. */
    private const val API_VERSION = "2023-06-01"

    override fun endpoint(model: String) = "https://api.anthropic.com/v1/messages"

    override fun headers(apiKey: String) = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to API_VERSION,
        "content-type" to "application/json",
    )

    override fun body(model: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
        buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    },
                )
            }
        }.toString()

    override fun extractText(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["content"]!!.jsonArray
            .firstNotNullOf { block ->
                block.jsonObject["text"]?.jsonPrimitive?.content
            }
    }.getOrNull()

    override fun extractError(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
}

/** Google Gemini generateContent API. */
data object Gemini : ModelProvider {
    override val name = "gemini"
    override val defaultModel = "gemini-2.5-flash"

    override fun endpoint(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    override fun headers(apiKey: String) = mapOf(
        // Header rather than a query parameter: a key in a URL ends up in logs,
        // proxies and crash reports.
        "x-goog-api-key" to apiKey,
        "content-type" to "application/json",
    )

    override fun body(model: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
        buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemPrompt) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", userPrompt) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                // Native JSON mode: removes the whole class of "model wrapped it in
                // prose" failures rather than parsing around them.
                put("responseMimeType", "application/json")
                put("maxOutputTokens", maxTokens)
                put("temperature", 0)
            }
        }.toString()

    override fun extractText(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["candidates"]!!.jsonArray.first()
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.content }
    }.getOrNull()

    override fun extractError(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
}

/**
 * A self-hosted bridge that runs `kiro-cli` on the caller's own machine.
 *
 * This is how a phone uses a Kiro subscription. `KIRO_API_KEY` authenticates a
 * local CLI process rather than an HTTPS endpoint, so something has to run the CLI
 * — see `bridge/kiro_bridge.py`.
 *
 * The Kiro key never leaves the bridge host. The device holds only a bridge token,
 * separate on purpose: it rotates independently, and a compromised phone costs a
 * token rather than the subscription.
 *
 * The default model was chosen by measurement rather than reputation. On this
 * workload `claude-haiku-4.5` was both the fastest and fully correct on a
 * multi-item parsing task.
 */
data class KiroBridge(
    /** Base URL of your bridge, e.g. `https://abc123.ngrok.app`. */
    private val baseUrl: String,
    override val defaultModel: String = "claude-haiku-4.5",
    /** Kiro effort level. `low` is ample for choosing a single action. */
    private val effort: String = "low",
) : ModelProvider {
    override val name = "kiro"

    override fun endpoint(model: String) = "${baseUrl.trimEnd('/')}/plan"

    override fun headers(apiKey: String) = mapOf(
        // This is the bridge token, not the Kiro key.
        "Authorization" to "Bearer $apiKey",
        "content-type" to "application/json",
    )

    override fun body(model: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
        buildJsonObject {
            put("model", model)
            put("effort", effort)
            // The bridge joins these: kiro-cli takes a single prompt argument.
            put("system", systemPrompt)
            put("prompt", userPrompt)
        }.toString()

    override fun extractText(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["text"]!!.jsonPrimitive.content
    }.getOrNull()

    override fun extractError(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()
}

/**
 * Any OpenAI-compatible chat-completions endpoint.
 *
 * Covers OpenAI itself plus the many gateways and local servers that copy the
 * schema, so a user can point the app at whatever they already pay for.
 */
data class OpenAiCompatible(
    private val baseUrl: String = "https://api.openai.com/v1",
    override val defaultModel: String = "gpt-4.1-mini",
) : ModelProvider {
    override val name = "openai"

    override fun endpoint(model: String) = "${baseUrl.trimEnd('/')}/chat/completions"

    override fun headers(apiKey: String) = mapOf(
        "Authorization" to "Bearer $apiKey",
        "content-type" to "application/json",
    )

    override fun body(model: String, systemPrompt: String, userPrompt: String, maxTokens: Int): String =
        buildJsonObject {
            put("model", model)
            put("max_completion_tokens", maxTokens)
            put("temperature", 0)
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    },
                )
            }
        }.toString()

    override fun extractText(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["choices"]!!.jsonArray.first()
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
    }.getOrNull()

    override fun extractError(responseBody: String): String? = runCatching {
        ModelProvider.json.parseToJsonElement(responseBody)
            .jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
}

/** Minimal JSON object accessor used by providers, kept internal to this file. */
private operator fun JsonObject.get(key: String) = this[key]
