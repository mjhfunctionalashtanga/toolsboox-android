package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Synthesize engine library — like Ask's personas, but for transformations. The three
 * built-ins (3 Questions / Writing Prompt / Essay Outline) always lead; the user's own engines
 * (a name + a prompt) follow. One picker serves every synthesis entry point: the Synthesize
 * page, this-page baskets, single objects, and the Log basket.
 */
object SynthEngines {

    data class Engine(val name: String, val prompt: String, val custom: Boolean = false)

    private const val PREFS = "ledger_synth_engines"
    private const val KEY_LIST = "list"

    fun builtIns(): List<Engine> = listOf(
        Engine("?  3 Questions",
            "From the gathered material, pose the 3 most GENERATIVE questions it raises — questions that push the author's own thinking further, not comprehension checks. Numbered, one line each. No preamble."),
        Engine("✎  Writing Prompt",
            "From the gathered material, write ONE vivid writing prompt (2–3 sentences) the author could start writing from immediately, in second person. No preamble."),
        Engine("≡  Essay Outline",
            "From the gathered material, draft an essay outline: a working title on the first line, then 4–6 section headers, each with one guiding sentence. Plain text, no markdown. No preamble.")
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun customs(context: Context): List<Engine> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_LIST, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Engine(o.optString("name"), o.optString("prompt"), custom = true)
        }
    }.getOrDefault(emptyList())

    fun all(context: Context): List<Engine> = builtIns() + customs(context)

    private fun save(context: Context, engines: List<Engine>) {
        val arr = JSONArray()
        for (e in engines) arr.put(JSONObject().put("name", e.name).put("prompt", e.prompt))
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun upsert(context: Context, engine: Engine) {
        save(context, customs(context).filter { it.name != engine.name } + engine.copy(custom = true))
    }

    fun delete(context: Context, name: String) {
        save(context, customs(context).filter { it.name != name })
    }

    /** Seed the starter library ONCE (deletions stick afterwards) — a working variety of lenses. */
    private fun seedDefaults(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("seeded", false)) return
        val starters = listOf(
            Engine("⚡  Counterargument",
                "Steelman the OPPOSING view of the gathered material: state the strongest counterargument a thoughtful critic would make, in one tight paragraph, then one sentence on what the author's position must answer to survive it. No preamble."),
            Engine("🗣  Sermon Sketch",
                "Shape the gathered material into a short dharma-talk sketch: an opening image or story (2–3 sentences), the one teaching at its heart (1–2 sentences), a turn that complicates it (2 sentences), and a closing line that lands softly. Plain text. No preamble."),
            Engine("🧘  Class Plan",
                "Turn the gathered material into a yoga class teaching arc: a one-line theme, an opening framing to say to students, 3 waypoints in the practice where the theme surfaces (each one line), and a closing reflection. Plain text. No preamble."),
            Engine("✉  Letter Draft",
                "Draft a warm, direct LETTER from the author to a friend, built from the gathered material — share what the author has been thinking about and end with one genuine question to the recipient. 150–250 words, first person. No preamble, no signature."),
            Engine("🜂  Distill",
                "Distill the gathered material to its essence: ONE paragraph (4–6 sentences) that says the truest thing all of it is circling. No lists, no preamble."),
            Engine("🪞  Patterns",
                "Name the recurring PATTERNS across the gathered material — the themes, tensions, or obsessions that appear more than once. 3–4 patterns, each one line naming it plus one line of evidence. No preamble."),
            Engine("🔗  Connections",
                "Find the UNEXPECTED connections between items in the gathered material — pairings the author likely hasn't noticed. 3 connections, each: 'X ↔ Y — why they speak to each other' in two lines. No preamble."),
            Engine("🃏  Aphorisms",
                "Compress the gathered material into 3 aphorisms — short, sayable, slightly dangerous. One line each, numbered. No explanation, no preamble."),
            Engine("🌱  Next Actions",
                "From the gathered material, name 3–5 concrete NEXT ACTIONS the author could take this week — each one line, starting with a verb, small enough to actually do. No preamble."),
            Engine("📚  Reading Path",
                "Given the gathered material, suggest a short READING PATH: 3 books or essays that would deepen exactly this thread, each with one line on why it belongs next. No preamble.")
        )
        save(context, starters)
        p.edit().putBoolean("seeded", true).apply()
    }

    /** The one picker: built-ins, then customs, then ＋ New engine… / ✎ Manage engines…. */
    fun pick(context: Context, title: String, onPick: (Engine) -> Unit) {
        seedDefaults(context)
        val engines = all(context)
        val labels = engines.map { it.name } + listOf("＋  New engine…", "✎  Manage engines…")
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < engines.size -> onPick(engines[which])
                    which == engines.size -> promptEdit(context, null) { pick(context, title, onPick) }
                    else -> manage(context) { pick(context, title, onPick) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Name + prompt editor for a new or existing custom engine. */
    private fun promptEdit(context: Context, existing: Engine?, onDone: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val nameIn = android.widget.EditText(context).apply {
            hint = "Engine name (e.g. ⚡ Counterargument)"; setSingleLine(); setText(existing?.name ?: "")
        }
        val promptIn = android.widget.EditText(context).apply {
            hint = "The prompt. It receives the gathered material as input."
            setLines(5); setText(existing?.prompt ?: "")
        }
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0); addView(nameIn); addView(promptIn)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(if (existing == null) "New engine" else "Edit engine")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = nameIn.text.toString().trim()
                val prompt = promptIn.text.toString().trim()
                if (name.isNotBlank() && prompt.isNotBlank()) {
                    if (existing != null && existing.name != name) delete(context, existing.name)
                    upsert(context, Engine(name, prompt))
                }
                onDone()
            }
            .setNegativeButton("Cancel") { _, _ -> onDone() }
            .show()
    }

    /** Edit/delete the custom engines (built-ins are fixed). */
    private fun manage(context: Context, onDone: () -> Unit) {
        val customs = customs(context)
        if (customs.isEmpty()) { promptEdit(context, null, onDone); return }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Manage engines")
            .setItems(customs.map { it.name }.toTypedArray()) { _, which ->
                val e = customs[which]
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(e.name)
                    .setItems(arrayOf("✎  Edit", "🗑  Delete")) { _, action ->
                        if (action == 0) promptEdit(context, e, onDone)
                        else { delete(context, e.name); onDone() }
                    }
                    .setNegativeButton("Cancel") { _, _ -> onDone() }
                    .show()
            }
            .setNegativeButton("Close") { _, _ -> onDone() }
            .show()
    }
}
