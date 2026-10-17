package com.toolsboox.plugin.calendar.ot

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Synthesize document's voice: who answers next ([persona], the full name; "" = the
 * Correspondent), when that was chosen ([at], epoch ms), and every voice that has held the pen
 * ([voices], first-seen order) — the page's history, so a voice that joined later reads as joined.
 */
data class VoiceRow(
    val date: String,
    val key: String,
    val persona: String,
    val at: Long,
    val voices: List<String>,
)

/**
 * ❖ THE PAGE REMEMBERS ITS VOICE (Michael, 2026-10-12, rulings 2 and 4: "on a synth page, it can go
 * back and forth writing on that page right" — and a second voice JOINS and answers next).
 *
 * `correspondence/voices.json` — beside pending.json locally, the same path remotely, synced. It is
 * deliberately NOT called personas.json: two different "personas" files would invite the wrong read.
 *
 *     [{"date":"2026-10-12","key":"synthesize-1760284800000","persona":"Spiral — what came back around",
 *       "at":1760290000000,"voices":["","Spiral — what came back around"]}]
 *
 *  • [VoiceRow.key] is the DOCUMENT key — the sub-page stripped at write AND read ("synthesize#2" →
 *    "synthesize"); [VoiceRow.date] is the day file the page lives in (the date pending.json and
 *    pages.json already carry), so the daily `synthesize` page has one voice per day.
 *  • No row = the Correspondent: every existing page reads exactly as today; no migration.
 *  • Merge per `date|key`: the newer `at` wins the persona (a tie: the greater name); the voices are
 *    the winner's list plus the loser's missing entries, in their order. 200 rows, newest first.
 *
 * Pure — pinned by `convergence-fixtures/synth-voices-*.json`, identical with iOS's LedgerCore twin.
 */
object SynthVoices {

    const val DIR = "correspondence"
    const val FILE = "voices.json"
    const val REMOTE = "$DIR/$FILE"

    const val MAX_ROWS = 200
    /** Voices remembered per page; the page's first voice always stays. */
    const val MAX_VOICES = 8

    fun base(key: String): String = key.substringBefore('#')

    fun idOf(date: String, key: String): String = "$date|${base(key)}"

    fun idOf(r: VoiceRow): String = idOf(r.date, r.key)

    // ── The wire ────────────────────────────────────────────────────────────────────────────

    fun toJson(r: VoiceRow): JSONObject = JSONObject()
        .put("date", r.date).put("key", r.key).put("persona", r.persona).put("at", r.at)
        .put("voices", JSONArray(r.voices))

    /** Tolerant read: a row with no date or key is dropped; a missing persona is the
     *  Correspondent, a missing `at` is 0, missing voices are none. The key is read as its base. */
    fun parse(text: String): List<VoiceRow> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val date = o.optString("date"); val key = o.optString("key")
                if (date.isBlank() || key.isBlank()) return@mapNotNull null
                val voices = mutableListOf<String>()
                o.optJSONArray("voices")?.let { a ->
                    for (j in 0 until a.length()) if (!a.isNull(j)) a.optString(j).let { if (it !in voices) voices.add(it) }
                }
                VoiceRow(date, base(key), o.optString("persona"), o.optLong("at"), voices)
            }
        }.getOrDefault(emptyList())
    }

    fun encode(rows: List<VoiceRow>): String = JSONArray().apply { rows.forEach { put(toJson(it)) } }.toString()

    // ── The rules ───────────────────────────────────────────────────────────────────────────

    /** The page's first voice stays; past [MAX_VOICES] the oldest of the rest go. */
    fun capVoices(voices: List<String>): List<String> =
        if (voices.size <= MAX_VOICES) voices else listOf(voices[0]) + voices.takeLast(MAX_VOICES - 1)

    /** Newest first, at most [MAX_ROWS] — a stable order (ties by id) so both apps write the same. */
    fun cap(rows: List<VoiceRow>): List<VoiceRow> =
        rows.sortedWith(compareByDescending<VoiceRow> { it.at }.thenBy { idOf(it) }).take(MAX_ROWS)

    fun rowFor(rows: List<VoiceRow>, date: String, key: String): VoiceRow? {
        val id = idOf(date, key)
        return rows.firstOrNull { idOf(it) == id }
    }

    /**
     * He picked [persona] for the page: it answers next, at [now], and joins [VoiceRow.voices] if it
     * hadn't held the pen. A FIRST row on a page that already holds a reply card ([hasReply] — the
     * Correspondent wrote it, since no row meant the Correspondent) starts with `["", persona]`.
     */
    fun set(rows: List<VoiceRow>, date: String, key: String, persona: String, now: Long, hasReply: Boolean): List<VoiceRow> {
        val k = base(key)
        val prior = rowFor(rows, date, k)
        val row = if (prior != null) {
            prior.copy(persona = persona, at = maxOf(now, prior.at + 1),
                voices = if (persona in prior.voices) prior.voices else capVoices(prior.voices + persona))
        } else {
            VoiceRow(date, k, persona, now,
                if (hasReply && persona != SynthVoice.CORRESPONDENT) listOf(SynthVoice.CORRESPONDENT, persona) else listOf(persona))
        }
        return cap(listOf(row) + rows.filterNot { idOf(it) == idOf(row) })
    }

    /** Does [a] win the persona over [b] for the same page? The newer `at`; on a tie the greater
     *  persona, then the greater voices list (joined by "\n") — so both apps converge. */
    fun wins(a: VoiceRow, b: VoiceRow): Boolean = when {
        a.at != b.at -> a.at > b.at
        a.persona != b.persona -> a.persona > b.persona
        else -> a.voices.joinToString("\n") >= b.voices.joinToString("\n")
    }

    /** Per `date|key`: the newer `at` wins the persona; the voices are the union, winner's order
     *  first. Commutative. */
    fun merge(a: List<VoiceRow>, b: List<VoiceRow>): List<VoiceRow> {
        val byId = LinkedHashMap<String, VoiceRow>()
        for (r in a + b) {
            val id = idOf(r)
            val mine = byId[id]
            byId[id] = if (mine == null) r.copy(key = base(r.key)) else {
                val (win, lose) = if (wins(r, mine)) r to mine else mine to r
                win.copy(key = base(win.key), voices = capVoices(win.voices + lose.voices.filter { it !in win.voices }))
            }
        }
        return cap(byId.values.toList())
    }
}
