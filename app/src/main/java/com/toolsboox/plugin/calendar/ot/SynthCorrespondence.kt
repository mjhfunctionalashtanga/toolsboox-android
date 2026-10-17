package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * THE CORRESPONDENCE'S MEMORY — the only new persistence the Synthesis rebirth adds
 * (DESIGN-synthesis-claude-2026-08-30 §8).
 *
 * The page IS the transcript: his ink and the ⟡ reply cards ride the day JSON like every other
 * element, and an older build (or iOS, until its twin) renders all of it correctly with no idea
 * a conversation happened. What CAN'T live on the page is the bookkeeping that makes the loop
 * cheap and coherent: which strokes the correspondent has already read (the delta watermark —
 * Penombra's one real lesson: send only the NEW ink), and the cached text of prior turns so an
 * old exchange costs no re-OCR and no re-tokenizing of images.
 *
 * ── THE SIDECAR ─────────────────────────────────────────────────────────────────────────────
 *
 * `correspondence/pages.json`, the [NotePageTemplates] shape: a JSON array of rows read with
 * opt*, keyed "date|key", the NEWER `at` winning on merge — a correspondence row is a VALUE
 * that advances, not an entry that accumulates. No tombstones: forgetting a conversation is
 * just the row aging out (rows are capped, oldest dropped). Stored strokes carry no reliable
 * timestamps (they arrive with t=0 — the PanelOcr lesson), so the watermark is a SEEN-ID SET,
 * not a time: delta = the page's strokes whose ids the row hasn't recorded.
 *
 *     [{"date":"2026-08-30","key":"synthesize","at":1756500000000,
 *       "seen":["uuid",…], "turns":[{"at":…,"who":"ink|machine","text":"…"}]}]
 *
 * Day JSON untouched — no wire change, no fixture churn.
 *
 * SPEAKERS (Michael, 2026-10-12 — a persona joins the conversation, ruling 4): a machine turn a
 * persona wrote carries `"persona":"<full name>"`. It is OMITTED for the Correspondent, so every
 * old row and every Correspondent row keeps today's bytes, and [transcriptTail] tells the asking
 * voice which turns were its own ("You replied:") and which another voice's ("Spiral replied:") —
 * a joining voice never claims another's words.
 */
object SynthCorrespondence {

    /** One page's correspondence state. [seen] = stroke ids already read; [turns] = the cached
     *  transcript, oldest first. */
    class Row(
        val date: String,
        val key: String,
        var at: Long,
        val seen: MutableSet<String>,
        val turns: MutableList<Turn>,
    )

    /** [persona] = the voice that wrote a machine turn ("" = the Correspondent). */
    class Turn(val at: Long, val who: String, val text: String, val persona: String = "")

    /** A turn on the wire: `{at,who,text}` — plus `persona` only when it isn't the Correspondent. */
    fun turnJson(t: Turn): JSONObject {
        val o = JSONObject().put("at", t.at).put("who", t.who).put("text", t.text)
        if (t.persona.isNotBlank()) o.put("persona", t.persona)
        return o
    }

    fun turnFrom(o: JSONObject): Turn = Turn(o.optLong("at"), o.optString("who"), o.optString("text"), o.optString("persona"))

    private const val DIR = "correspondence"
    private const val FILE = "pages.json"

    /** Pages remembered; a conversation older than the cap's horizon starts fresh — which is a
     *  first-Synth whole-page read, never a wrong reply. */
    private const val MAX_ROWS = 100

    /** Turns kept per page — the context ring reads the tail anyway. */
    private const val MAX_TURNS = 40

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, FILE)

    private fun idOf(date: String, key: String) = "$date|$key"

    private fun loadAll(context: Context): MutableList<Row> {
        val out = mutableListOf<Row>()
        val f = file(context)
        if (!f.exists()) return out
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = o.optString("date"); val key = o.optString("key")
                if (date.isBlank() || key.isBlank()) continue
                val seen = mutableSetOf<String>()
                o.optJSONArray("seen")?.let { s -> for (j in 0 until s.length()) seen.add(s.optString(j)) }
                val turns = mutableListOf<Turn>()
                o.optJSONArray("turns")?.let { t ->
                    for (j in 0 until t.length()) {
                        val to = t.optJSONObject(j) ?: continue
                        turns.add(turnFrom(to))
                    }
                }
                out.add(Row(date, key, o.optLong("at"), seen, turns))
            }
        }
        return out
    }

    private fun saveAll(context: Context, rows: List<Row>) {
        // Newest-first cap: the working set stays, ancient conversations age out whole.
        val kept = rows.sortedByDescending { it.at }.take(MAX_ROWS)
        val arr = JSONArray()
        for (r in kept) {
            val o = JSONObject()
            o.put("date", r.date); o.put("key", r.key); o.put("at", r.at)
            o.put("seen", JSONArray(r.seen.toList()))
            val turns = JSONArray()
            for (t in r.turns.takeLast(MAX_TURNS)) turns.put(turnJson(t))
            o.put("turns", turns)
            arr.put(o)
        }
        runCatching { file(context).writeText(arr.toString()) }
    }

    fun row(context: Context, date: LocalDate, key: String): Row? =
        loadAll(context).firstOrNull { idOf(it.date, it.key) == idOf(date.toString(), key) }

    /**
     * Record one completed exchange: the ink the correspondent just read (as text), its reply,
     * and every stroke id now on the page — the watermark covers the whole page at reply time,
     * so the next Synth's delta is exactly what was written after this one.
     */
    fun record(
        context: Context, date: LocalDate, key: String,
        nowSeen: Collection<String>, inkText: String, replyText: String,
        resumed: Boolean = false,
        persona: String = "",
    ) {
        val all = loadAll(context)
        val now = System.currentTimeMillis()
        val row = all.firstOrNull { idOf(it.date, it.key) == idOf(date.toString(), key) }
            ?: Row(date.toString(), key, now, mutableSetOf(), mutableListOf()).also { all.add(it) }
        row.at = now
        row.seen.addAll(nowSeen)
        // ONCE PER SUMMON (2026-10-12 review): a [resumed] landing still records — the kill may have
        // come between the card's save and this line, and the watermark must advance — but if the
        // kill came after this line the turn is already here, and a second copy would ride every
        // later prompt as a repeated exchange. Only a resumed landing asks: a fresh summon whose
        // ink and reply happen to repeat the last exchange is still a turn of its own.
        if (!(resumed && isRepeat(row, inkText, replyText))) {
            if (inkText.isNotBlank()) row.turns.add(Turn(now, "ink", inkText))
            row.turns.add(Turn(now, "machine", replyText, SynthVoice.normalize(persona)))
        }
        saveAll(context, all)
    }

    /** This exchange is already the row's last one — the same reply, after the same ink. */
    fun isRepeat(row: Row?, inkText: String, replyText: String): Boolean {
        val turns = row?.turns ?: return false
        val last = turns.lastOrNull() ?: return false
        if (last.who != "machine" || last.text != replyText) return false
        if (inkText.isBlank()) return true
        val before = turns.getOrNull(turns.size - 2) ?: return false
        return before.who == "ink" && before.text == inkText
    }

    /** The tail of the conversation as plain lines for the context ring — "He wrote: …" /
     *  "You replied: …", oldest first, bounded — as [voice] reads it (see [transcriptTail]). */
    fun transcriptTail(context: Context, date: LocalDate, key: String, turns: Int = 8, voice: String = ""): String {
        val row = row(context, date, key) ?: return ""
        // Each speaker as the voice it is NOW (renames followed — review of b29cabbc): a turn Coach
        // wrote is still its own once Coach is "Coach — v2".
        val personas = runCatching { com.toolsboox.plugin.chat.nw.PersonaStore.rows(context) }.getOrDefault(emptyList())
        return transcriptTail(row.turns, voice, turns) { SynthPersonas.canonical(personas, it) }
    }

    /**
     * The transcript as the asking [voice] reads it: its own turns are "You replied: ", another
     * voice's are "<Short> replied: " ("The Correspondent replied: "), his ink is "He wrote: ". A
     * Correspondent-only page asked by the Correspondent reads byte for byte as before.
     * [canonical] names each speaker (and the asking voice) as the voice it stands for now —
     * [SynthPersonas.canonical]; the identity when there is no persona set to consult.
     */
    fun transcriptTail(turns: List<Turn>, voice: String, n: Int, canonical: (String) -> String = { it }): String {
        fun who(name: String) = SynthVoice.normalize(canonical(SynthVoice.normalize(name)))
        val asking = who(voice)
        return turns.takeLast(n).joinToString("\n") { t ->
            val speaker = who(t.persona)
            val lead = when {
                t.who != "machine" -> "He wrote: "
                speaker == asking -> "You replied: "
                else -> SynthVoice.shortName(speaker) + " replied: "
            }
            lead + t.text.trim()
        }
    }
}
