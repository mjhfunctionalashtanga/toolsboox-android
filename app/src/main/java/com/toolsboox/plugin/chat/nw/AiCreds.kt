package com.toolsboox.plugin.chat.nw

import android.content.Context

/** Shared read of the Ask-my-Ledger AI credentials (provider, key, model) from the encrypted chat
 *  prefs — so any surface (reader, day page, chat) can call the LLM without duplicating the setup. */
object AiCreds {
    fun get(context: Context): Triple<String, String, String>? = runCatching {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context, "ledger_chat_encrypted_prefs",
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val provider = prefs.getString("ledger_chat_provider", "anthropic") ?: "anthropic"
        val key = prefs.getString("ledger_chat_api_key_$provider", "")?.trim().orEmpty()
        val default = if (provider == "openai") "gpt-4o" else "claude-sonnet-5"
        val model = prefs.getString("ledger_chat_model_$provider", default) ?: default
        if (key.isBlank()) null else Triple(provider, key, model)
    }.getOrNull()
}
