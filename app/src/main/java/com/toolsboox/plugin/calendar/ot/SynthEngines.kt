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

    /** The PROPOSE-A-TOPIC prompt: the same synthesist, handed the Ledger's rhymes with a topic he
     *  named, told to aggregate and cite — "novel, aggregated, not garbagey slop" (09-11). */
    const val TOPIC_PROMPT = "You are the synthesist on Michael's own ledger. He names a TOPIC and hands you the picks, highlights and notes of his own that rhyme with it, each tagged with where it came from.\n\nWrite ONE aggregated piece, 120–220 words, plain text, no markdown, no bullet points, no emojis, no preamble. Everything in it must come from the material — no outside facts, no filler, no throat-clearing. Name the source tag in brackets after each point you take from a piece, exactly as given. Say what these pieces make TOGETHER about the topic — the claim true of them jointly — and where they pull against each other. End with one line he could carry further.\n\nVoice: sardonic, straightforward, kind. Short sentences. If the material does not really bear on the topic, say so in one line and stop."

    /** The approved SYNTHESIZE persona (08-03, "as is") LEADS the engines — Chat's persona and the ⚗
     *  engines were two doors on one product; now the persona is the first engine. */
    fun builtIns(): List<Engine> = listOf(
        Engine("❖  Synthesize", com.toolsboox.plugin.chat.nw.PersonaStore.SYNTHESIZE_PROMPT),
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

    /**
     * The one picker: built-ins, then customs, then ＋ New engine… / ✎ Manage engines….
     *
     * [present] is how every dialog in this flow reaches the screen. A fragment caller MUST pass
     * its `showModal` ("stylus on 'Synthesize' pop up from notes FREEZES app" — shown raw, the
     * Onyx raw ink session kept consuming the stylus under the dialog, so pen taps never arrived:
     * the guarded-dialog freeze class, one more site). The bare default exists only for callers
     * with no fragment in hand.
     */
    /**
     * HOW MUCH OF THE LEDGER rides in with the material (Michael, 09-11: "synthesize to be able
     * to bring in more or less of our ledger provenance and stores as relevant"). 0 = the piece
     * alone; 1 = the few passages of his own that rhyme hardest with it; 2 = a dozen, looser.
     * The rhymes come from the same corpus the Rhizome and Quick Wins read — notes, marginalia,
     * kept mail, book highlights — found by meaning, so the engine writes against what he already
     * thinks, not just what he just read.
     */
    const val CONTEXT_PREF = "synth_ledger_context"
    private val contextLabels = listOf("Off", "Close rhymes", "Wide")
    fun contextLevel(context: Context): Int =
        context.getSharedPreferences("ledger_synth_prefs", Context.MODE_PRIVATE).getInt(CONTEXT_PREF, 1)
    private fun setContextLevel(context: Context, level: Int) =
        context.getSharedPreferences("ledger_synth_prefs", Context.MODE_PRIVATE).edit().putInt(CONTEXT_PREF, level).apply()

    /** The Ledger's rhymes with [material] as a labelled block to append — "" when the dial is off
     *  or nothing rhymes. Blocking (embeddings + a corpus walk): call off the main thread. */
    fun ledgerContext(context: Context, corpus: com.toolsboox.plugin.chat.fi.LedgerCorpusService, root: java.io.File, material: String): String {
        val level = contextLevel(context)
        if (level <= 0) return ""
        val topN = if (level >= 2) 12 else 4
        val minScore = if (level >= 2) 0.46 else 0.52
        val snippets = runCatching {
            corpus.gather(root, Spiral.SCOPE).filter { Spiral.isSubstantial(it.text) }.let { Spiral.dedupe(it) { s -> s.text } }
        }.getOrDefault(emptyList())
        val found = SemanticRoots.neighbors(context, material.take(2000), snippets, topN = topN, minScore = minScore)
        if (found.isEmpty()) return ""
        val lines = found.map { r ->
            val own = if (r.snippet.own.isBlank()) "" else " — the author's note: ${r.snippet.own}"
            "• [${r.snippet.citation}] ${r.snippet.text.take(400)}$own"
        }
        return "\n\n— FROM THE AUTHOR'S OWN LEDGER (notes, marginalia, kept mail that rhyme with the material; weave these in where they genuinely connect, and say which) —\n" +
            lines.joinToString("\n")
    }

    fun pick(
        context: Context, title: String,
        present: (androidx.appcompat.app.AlertDialog) -> Unit = { it.show() },
        /** 🧭 Propose a topic… — the page-level door (null on the readers). */
        onTopic: ((String) -> Unit)? = null,
        /** 🔗 Grams from my sites… — every named thing on the page becomes a link gram to its page
         *  on his WordPress sites (null where there is no page). */
        onSiteLinks: (() -> Unit)? = null,
        /** 🗂 Group picks into sections… — the page's picks sorted and laid out under headers. */
        onGroup: (() -> Unit)? = null,
        /** ✒ The Correspondent's reply — a held pick's conversation door (null elsewhere). */
        onCorrespond: (() -> Unit)? = null,
        /** The door's words — the page's voice answers it: "✒  Spiral replies…" on a voiced page. */
        correspondLabel: String = "✒  " + SynthVoice.correspondRow(SynthVoice.CORRESPONDENT),
        onPick: (Engine) -> Unit,
    ) {
        seedDefaults(context)
        val engines = all(context)
        // The dial leads the list (a tap cycles it and re-presents the picker); the page doors
        // follow it where the caller offers them; then the engines.
        val again = { pick(context, title, present, onTopic, onSiteLinks, onGroup, onCorrespond, correspondLabel, onPick) }
        val head = mutableListOf<Pair<String, () -> Unit>>(
            "🌿  From your Ledger: ${contextLabels[contextLevel(context).coerceIn(0, 2)]}" to {
                setContextLevel(context, (contextLevel(context) + 1) % 3); again() })
        if (onTopic != null) head += "🧭  Propose a topic…" to { askTopic(context, present, onTopic) }
        if (onSiteLinks != null) head += "🔗  Grams from my sites…" to onSiteLinks
        if (onGroup != null) head += "🗂  Group picks into sections…" to onGroup
        if (onCorrespond != null) head += correspondLabel to onCorrespond
        val labels = head.map { it.first } + engines.map { it.name } + listOf("＋  New engine…", "✎  Manage engines…")
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, raw ->
                val which = raw - head.size
                when {
                    raw < head.size -> head[raw].second()
                    which < engines.size -> onPick(engines[which])
                    which == engines.size -> promptEdit(context, null, present) { again() }
                    else -> manage(context, present) { again() }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        present(dialog)
    }

    /** The topic asked for in one field — "Gather & write" hands it to the caller. */
    private fun askTopic(context: Context, present: (androidx.appcompat.app.AlertDialog) -> Unit, onTopic: (String) -> Unit) {
        val dp = context.resources.displayMetrics.density
        val input = android.widget.EditText(context).apply {
            hint = "A topic, a question, a thread you keep circling"; setLines(2); gravity = android.view.Gravity.TOP
        }
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(android.widget.TextView(context).apply {
                text = "The picks and highlights across your Ledger that rhyme with it are gathered and written up as one piece, with its sources, on a new note."
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, (10 * dp).toInt())
            })
            addView(input)
        }
        present(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle("Propose a topic").setView(box)
            .setPositiveButton("Gather & write") { _, _ ->
                val t = input.text.toString().trim(); if (t.isNotBlank()) onTopic(t)
            }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    /** Name + prompt editor for a new or existing custom engine. */
    private fun promptEdit(
        context: Context, existing: Engine?,
        present: (androidx.appcompat.app.AlertDialog) -> Unit = { it.show() },
        onDone: () -> Unit,
    ) {
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
        // Guarded against outside touch: the engine's name and prompt mid-edit are work — a stray
        // touch must not throw them away. The dialog itself reaches the screen through [present],
        // so a fragment caller's modal door pauses raw ink under it like everywhere else.
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
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
            .create()
            .apply { setCanceledOnTouchOutside(false) }
            .let(present)
    }

    /** Edit/delete the custom engines (built-ins are fixed). */
    private fun manage(
        context: Context,
        present: (androidx.appcompat.app.AlertDialog) -> Unit = { it.show() },
        onDone: () -> Unit,
    ) {
        val customs = customs(context)
        if (customs.isEmpty()) { promptEdit(context, null, present, onDone); return }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle("Manage engines")
            .setItems(customs.map { it.name }.toTypedArray()) { _, which ->
                val e = customs[which]
                val actions = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
                    .setTitle(e.name)
                    .setItems(arrayOf("✎  Edit", "🗑  Delete")) { _, action ->
                        if (action == 0) promptEdit(context, e, present, onDone)
                        else { delete(context, e.name); onDone() }
                    }
                    .setNegativeButton("Cancel") { _, _ -> onDone() }
                    .create()
                present(actions)
            }
            .setNegativeButton("Close") { _, _ -> onDone() }
            .create()
        present(dialog)
    }
}
