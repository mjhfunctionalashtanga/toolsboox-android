package com.toolsboox.plugin.feeds.ot

import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.reader.ui.BookNote
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/**
 * WHAT AN ARTICLE WEARS — every highlight to draw on it, once each. Pure (ArticleMarkSetTest).
 *
 * Michael 2026-10-12: "gram picks should highlight back too". Three sources meet here:
 *  1. MARKS (the article's own store) — a Highlight, a note, an adopted old one. They lead.
 *  2. PICKS from [ArticlePickIndex] whose source is this article — the old Make pick, the share
 *     card, another device — not already a mark's own pick, and not a passage a mark already
 *     draws. Two picks of one passage are one highlight (the newer stands for both).
 *  3. LOG LINES with a passage — the highlights made before marks existed (an iOS ❝ since 223
 *     left a line and no card) — not a mark's own line, not a passage already drawn; a line that
 *     pairs with a pick (the same passage, the old Android ❝'s line + card) rides on that pick.
 * One highlight per passage (passages compared by [norm], the page script's own normalisation).
 * Picks and lines are READ here at paint time — no migration; they become marks only when acted
 * on ([adoptedId]).
 */
object ArticleMarkSet {

    /** One highlight as drawn: [id] is a mark's id, "pick:<elementId>" or "line:<lineId>". */
    data class Shown(
        val id: String,
        val text: String,
        val prefix: String = "",
        val suffix: String = "",
        val noted: Boolean = false,
        val mark: BookNote? = null,
        val pick: ArticlePickIndex.Hit? = null,
        val line: ArticlePickIndex.Hit? = null,
    ) {
        /** The day its CARD lives on — where the index found it, else the mark's own card day, else
         *  the line's. */
        val cardDay: LocalDate?
            get() = pick?.date ?: mark?.cardDay ?: line?.date
        /** The day its LOG LINE lives on — where the index found it, else the mark's own line day,
         *  else the pick's. Not always [cardDay]: an old pick paired with an old line of the same
         *  passage may sit days apart, and a note must find the line where it is, not make it again
         *  on the card's day. */
        val lineDay: LocalDate?
            get() = line?.date ?: mark?.lineDay ?: pick?.date
    }

    const val PICK = "pick:"
    const val LINE = "line:"

    /** The page script's normalisation (article-marks.js normText): straight quotes, invisible
     *  characters gone, every run of whitespace one space, trimmed. */
    fun norm(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when (ch) {
                '\u2018', '\u2019', '\u201A', '\u2032' -> '\''
                '\u201C', '\u201D', '\u201E', '\u2033' -> '"'
                '\u00AD', '\u200B', '\u200C', '\u200D', '\uFEFF' -> null
                else -> if (ch.isWhitespace() || ch == '\u00A0' || ch == '\u2007' || ch == '\u202F') ' ' else ch
            } ?: continue
            if (c == ' ' && (out.isEmpty() || out.last() == ' ')) continue
            out.append(c)
        }
        return out.toString().trimEnd()
    }

    /** The anchor a mark carries in [BookNote.cfi]: `{"exact","prefix","suffix"}`. */
    fun anchorJson(exact: String, prefix: String, suffix: String): String =
        JSONObject().put("exact", exact).put("prefix", prefix).put("suffix", suffix).toString()

    fun anchorOf(mark: BookNote): Triple<String, String, String> {
        val o = runCatching { JSONObject(mark.cfi) }.getOrNull()
        return Triple(o?.optString("exact")?.ifBlank { null } ?: mark.text,
            o?.optString("prefix").orEmpty(), o?.optString("suffix").orEmpty())
    }

    /** [marks] INCLUDING tombstones (they draw nothing but keep their card and line from coming
     *  back); [hits] from [ArticlePickIndex.hits]; [title] the article's, for the link-card guard. */
    fun assemble(marks: List<BookNote>, hits: List<ArticlePickIndex.Hit>, title: String = ""): List<Shown> {
        val out = ArrayList<Shown>()
        val drawn = HashSet<String>()
        val picks = hits.filter { it.elementId.isNotBlank() }
        val lines = hits.filter { it.lineId.isNotBlank() }
        val claimedPicks = HashSet<String>()
        val claimedLines = HashSet<String>()
        for (m in marks) {
            // A REMOVED highlight still claims its card and line: a card kept on a board when its
            // highlight was removed ("Keep the card") must not paint the passage straight back.
            if (m.pickId.isNotBlank()) claimedPicks += m.pickId.lowercase()
            if (m.logId.isNotBlank()) claimedLines += m.logId
            if (m.deletedAt != null || m.type != BookNote.ANNOTATION || m.text.isBlank()) continue
            val (exact, prefix, suffix) = anchorOf(m)
            val pick = picks.firstOrNull { it.elementId.equals(m.pickId, ignoreCase = true) }
            val line = lines.firstOrNull { it.lineId == m.logId }
            val n = norm(exact)
            if (!drawn.add(n)) continue
            out += Shown(m.id, exact, prefix, suffix, noted = line?.noted == true, mark = m, pick = pick, line = line)
        }
        // THE LINK-CARD GUARD (iOS's, review 2026-10-12 #4): a card whose words are only the
        // article's title or its own label — a link placed on a day page, the player's capture, a
        // star — is a pointer at the piece, not a passage of it. Drawn back it lit the page's <h1>,
        // and 🗑 on that "highlight" would have taken the link card off its page.
        val titleKey = norm(title).lowercase()
        // Picks, newest first so the newer of two picks of one passage stands for both.
        for (p in picks.sortedByDescending { it.date }) {
            if (p.elementId.lowercase() in claimedPicks) continue
            val n = norm(p.text)
            if (titleKey.isNotEmpty() && n.lowercase() == titleKey) continue
            if (p.label.isNotBlank() && n == norm(p.label)) continue
            if (n.isBlank() || !drawn.add(n)) continue
            val pair = lines.filter { it.lineId !in claimedLines && norm(it.text) == n }
                .sortedWith(compareBy({ it.date != p.date }, { -it.date.toEpochDay() })).firstOrNull()
            if (pair != null) claimedLines += pair.lineId
            out += Shown(PICK + p.elementId, p.text, noted = pair?.noted == true, pick = p, line = pair)
        }
        for (l in lines.sortedByDescending { it.date }) {
            if (l.lineId in claimedLines) continue
            val n = norm(l.text)
            if (titleKey.isNotEmpty() && n.lowercase() == titleKey) continue
            if (n.isBlank() || !drawn.add(n)) continue
            out += Shown(LINE + l.lineId, l.text, noted = l.noted, line = l)
        }
        return out
    }

    /**
     * EVERY SOURCE OF ONE DRAWN PASSAGE — [target] first, then each other live mark, pick and Log
     * line of this article whose passage is the same ([norm]). [assemble] draws one highlight per
     * passage and hides the rest, so removing only the drawn one let a hidden twin paint the passage
     * straight back on the next open (review 2026-10-12 #3): an old ❝ card beside an old Make pick
     * of the same words, two devices highlighting the same sentence before a sync, a card "Bring in a
     * picking" carried onto a page under a new id. 🗑 takes them all (asking once when any card sits
     * on a board). A card or line a REMOVED mark still claims — kept on a board by an earlier "Keep
     * the pick" — is not a source: it was settled then.
     */
    fun sourcesOf(target: Shown, marks: List<BookNote>, hits: List<ArticlePickIndex.Hit>): List<Shown> {
        val n = norm(target.text)
        if (n.isBlank()) return listOf(target)
        val out = mutableListOf(target)
        val usedPicks = HashSet<String>()
        val usedLines = HashSet<String>()
        fun claim(pickId: String?, lineId: String?) {
            pickId?.takeIf { it.isNotBlank() }?.let { usedPicks += it.lowercase() }
            lineId?.takeIf { it.isNotBlank() }?.let { usedLines += it }
        }
        claim(target.pick?.elementId, target.line?.lineId)
        claim(target.mark?.pickId, target.mark?.logId)
        for (m in marks) if (m.deletedAt != null) claim(m.pickId, m.logId)
        val picks = hits.filter { it.elementId.isNotBlank() }
        val lines = hits.filter { it.lineId.isNotBlank() }
        for (m in marks) {
            if (m.deletedAt != null || m.type != BookNote.ANNOTATION || m.id == target.mark?.id) continue
            val (exact, prefix, suffix) = anchorOf(m)
            if (norm(exact) != n) continue
            val p = picks.firstOrNull { it.elementId.equals(m.pickId, ignoreCase = true) }
            val l = lines.firstOrNull { it.lineId == m.logId }
            out += Shown(m.id, exact, prefix, suffix, mark = m, pick = p, line = l)
            claim(m.pickId, m.logId)
        }
        for (p in picks) {
            if (p.elementId.lowercase() in usedPicks || norm(p.text) != n) continue
            out += Shown(PICK + p.elementId, p.text, pick = p)
            claim(p.elementId, null)
        }
        for (l in lines) {
            if (l.lineId in usedLines || norm(l.text) != n) continue
            out += Shown(LINE + l.lineId, l.text, noted = l.noted, line = l)
            claim(null, l.lineId)
        }
        return out
    }

    /** The JSON the page script paints. */
    fun paintJson(shown: List<Shown>): String = JSONArray().apply {
        shown.forEach {
            put(JSONObject().put("id", it.id).put("exact", it.text).put("prefix", it.prefix)
                .put("suffix", it.suffix).put("noted", it.noted))
        }
    }.toString()

    /**
     * The mark id an old pick or Log line takes when it is first acted on — DETERMINISTIC, so two
     * devices adopting the same one make the same mark and the merge pools them (design critique
     * #13): a pick's is `mfpk-<its element id>`, a line's is its own id plus a short hash of the
     * passage (old iOS ids `mfhl-<id>-<count>` collide across devices; the passage tells them apart).
     */
    fun adoptedId(shown: Shown): String = when {
        shown.mark != null -> shown.mark.id
        shown.pick != null -> "mfpk-" + shown.pick.elementId.lowercase()
        else -> (shown.line?.lineId ?: "line") + "-" + hash8(norm(shown.text))
    }

    fun hash8(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
}
