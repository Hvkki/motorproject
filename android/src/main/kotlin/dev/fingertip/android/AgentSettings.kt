package dev.fingertip.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.fingertip.core.agent.ModelProvider
import dev.fingertip.core.agent.OpenAiCompatible

/**
 * Stores the model API key and provider choice on the device.
 *
 * With no backend, the key lives on the phone, so where it lives matters.
 * [EncryptedSharedPreferences] keeps it encrypted at rest under a key held in the
 * Android Keystore, which is hardware-backed on most modern devices. That defeats
 * casual extraction from a backup or an unencrypted preferences file. It does not
 * defeat a rooted device or malware running as this app — no client-side storage
 * can, and claiming otherwise would be dishonest.
 *
 * The practical consequence for the user is worth stating plainly in the UI: the
 * key is theirs, it stays on their phone, and it can be revoked at the provider.
 */
class AgentSettings(context: Context) {

    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * The API key, or null when unset.
     *
     * Read on each use rather than cached so revoking it in settings takes effect
     * at once and it spends as little time in memory as possible.
     */
    val apiKey: String? get() = preferences.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String?) {
        preferences.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, value.trim())
        }.apply()
    }

    /** True when the reasoning tier can run. Skills replay regardless. */
    val isAgentConfigured: Boolean get() = apiKey != null

    var providerName: String
        get() = preferences.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(value) = preferences.edit().putString(KEY_PROVIDER, value).apply()

    /** Optional model override; null means the provider's default. */
    var model: String?
        get() = preferences.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit().putString(KEY_MODEL, value?.trim()).apply()

    /** Base URL for OpenAI-compatible gateways and local servers. */
    var openAiBaseUrl: String?
        get() = preferences.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = preferences.edit().putString(KEY_BASE_URL, value?.trim()).apply()

    /**
     * Whether the agent may act on irreversible steps at all.
     *
     * Off by default. Even with spoken confirmation wired up, a user should be able
     * to forbid the agent from ever tapping "Pay" — and the safe state is the one
     * that applies before anybody has made a decision.
     */
    var allowIrreversibleActions: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_IRREVERSIBLE, false)
        set(value) = preferences.edit().putBoolean(KEY_ALLOW_IRREVERSIBLE, value).apply()

    /** Resolves the configured provider, falling back to the default. */
    fun provider(): ModelProvider {
        val configured = ModelProvider.byName(providerName)
        return when {
            configured is OpenAiCompatible && openAiBaseUrl != null ->
                OpenAiCompatible(baseUrl = openAiBaseUrl!!)
            configured != null -> configured
            else -> ModelProvider.byName(DEFAULT_PROVIDER)!!
        }
    }

    private companion object {
        const val FILE_NAME = "fingertip_agent_settings"
        const val KEY_API_KEY = "model_api_key"
        const val KEY_PROVIDER = "model_provider"
        const val KEY_MODEL = "model_name"
        const val KEY_BASE_URL = "openai_base_url"
        const val KEY_ALLOW_IRREVERSIBLE = "allow_irreversible"
        const val DEFAULT_PROVIDER = "gemini"
    }
}
