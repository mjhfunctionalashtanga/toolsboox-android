package com.toolsboox.plugin.chat.nw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved QUESTION — the second axis beside [Persona], not a second flavour of it.
 *
 * Michael, on having more than one kind of saved prompt: "Having presets for prompts (not just
 * personas) is good with the ability to add/edit. Useful for recurring questions or workflows."
 *
 * The obvious place to put that was [PersonaStore], and it is the wrong place. A persona is a
 * STANCE: it rides the system turn, it stays for the whole visit, the interviewer is still the
 * interviewer ten questions later. A preset is ONE user turn, spent the moment you tap it, and the
 * next tap can be a different one. Folding presets into personas would have meant switching the
 * active persona to "summarise this" and then remembering to switch it back — a mode where there
 * was none, which is exactly the thing this app keeps refusing to build.
 *
 * So: two stores, the same shape line for line, deliberately independent. A preset question can be
 * asked IN a persona's voice, and neither has to know about the other — picking a preset never
 * touches [PersonaStore.activeName].
 *
 * @param name what the chip says — short, because it sits in a scrolling row of them
 * @param prompt the question actually asked. The passage is NOT pasted in here: it rides the
 *   grounding (`ask_context`), the same channel a typed question uses, so a preset reads
 *   identically whether it is spent on a sent highlight or on nothing at all. A preset with
 *   `{passage}` in it would be a second way to deliver the same text, and two channels for one
 *   fact is how they drift apart.
 */
data class AskPreset(val name: String, val prompt: String)

/**
 * Saved prompt presets for Ask my Ledger. Mirrors [PersonaStore] exactly — plain prefs, keyed by
 * name, upsert/delete, per-name seed markers. A preset is small, per-device and worth nothing on
 * another machine, for the same reasons a persona is, so neither goes near the sidecar sync.
 *
 * Unlike a persona there is no "active" preset, and that absence is the whole design. An active
 * preset would be a mode: a saved question silently prepended to the next thing you typed, which
 * you would then have to remember to turn off. A preset is spent when it is tapped.
 */
object AskPresetStore {
    private const val PREFS = "ledger_ask_presets"
    private const val KEY_LIST = "list"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun all(context: Context): List<AskPreset> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); AskPreset(o.optString("name"), o.optString("prompt"))
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, presets: List<AskPreset>) {
        val arr = JSONArray()
        for (p in presets) arr.put(JSONObject().put("name", p.name).put("prompt", p.prompt))
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Add or replace a preset by name. */
    fun upsert(context: Context, preset: AskPreset) {
        save(context, all(context).filter { it.name != preset.name } + preset)
    }

    fun delete(context: Context, name: String) {
        save(context, all(context).filter { it.name != name })
    }

    // --- Presets that ship with the app ----------------------------------------------------------

    /**
     * Put the built-in presets in place, once — same per-name marker discipline as
     * [PersonaStore.seedDefaults], and for the same reason: a preset you can't edit or throw away
     * is a menu someone else wrote. Seeded rather than hard-coded, only added if absent by name,
     * and a deleted one stays deleted.
     *
     * Five, not fifteen. These are the questions worth one tap when a passage is already in hand;
     * anything rarer is worth typing. Each prompt stands alone as a question because the passage
     * arrives through the grounding, never through the prompt text.
     */
    fun seedDefaults(context: Context) {
        val p = prefs(context)
        for (preset in BUILT_IN) {
            val marker = "seeded_preset_" + preset.name
            if (p.getBoolean(marker, false)) continue
            if (all(context).none { it.name == preset.name }) upsert(context, preset)
            p.edit().putBoolean(marker, true).apply()
        }
    }

    private val BUILT_IN: List<AskPreset> by lazy {
        listOf(
            AskPreset(
                "What is this?",
                "Say what this is about in plain language, in a few sentences. No preamble, no " +
                    "summary of what I asked."
            ),
            AskPreset(
                "Where else?",
                "Where else does this turn up in my own Ledger? Search my highlights, annotations, " +
                    "notes and pages for what rhymes with it, and cite the days."
            ),
            AskPreset(
                "Push back",
                "Argue against this. Take the strongest case on the other side, then say plainly " +
                    "where that case holds and where it doesn't."
            ),
            AskPreset(
                "Make it a task",
                "Turn this into one concrete thing I could actually do, and add it to my Ledger as " +
                    "a task."
            ),
            AskPreset(
                "Draft a note",
                "Draft a short note about this in my own voice — what I think, not a summary of " +
                    "what it says. Then save it as a note."
            ),
        )
    }
}
