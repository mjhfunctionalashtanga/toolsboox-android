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

    /** The one picker: built-ins, then customs, then ＋ New engine… / ✎ Manage engines…. */
    fun pick(context: Context, title: String, onPick: (Engine) -> Unit) {
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
