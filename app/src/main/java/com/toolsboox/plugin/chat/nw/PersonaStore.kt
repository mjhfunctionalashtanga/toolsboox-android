package com.toolsboox.plugin.chat.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved AI persona: a name + the system-prompt text that shapes Ask-my-Ledger's voice/role. */
data class Persona(val name: String, val prompt: String)

/**
 * Saved personas for Ask my Ledger. Each is a reusable system prompt; one can be active at a time
 * (or none). A prompt is not sensitive, so this lives in plain prefs.
 */
object PersonaStore {
    private const val PREFS = "ledger_personas"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun all(context: Context): List<Persona> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); Persona(o.optString("name"), o.optString("prompt"))
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, personas: List<Persona>) {
        val arr = JSONArray()
        for (p in personas) arr.put(JSONObject().put("name", p.name).put("prompt", p.prompt))
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Add or replace a persona by name. */
    fun upsert(context: Context, persona: Persona) {
        val list = all(context).filter { it.name != persona.name } + persona
        save(context, list)
    }

    fun delete(context: Context, name: String) {
        save(context, all(context).filter { it.name != name })
        if (activeName(context) == name) setActive(context, null)
    }

    fun activeName(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActive(context: Context, name: String?) {
        prefs(context).edit().apply { if (name.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, name) }.apply()
    }

    /** The active persona's prompt, or null (None). */
    fun activePrompt(context: Context): String? {
        val name = activeName(context) ?: return null
        return all(context).firstOrNull { it.name == name }?.prompt?.takeIf { it.isNotBlank() }
    }
}
