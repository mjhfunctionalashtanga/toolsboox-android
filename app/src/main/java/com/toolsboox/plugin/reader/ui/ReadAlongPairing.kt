package com.toolsboox.plugin.reader.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * READ-ALONG: an Audiobookshelf audiobook paired with an EPUB on the shelf, so the narration
 * streams while the reader's page follows (and the page, when turned, moves the narration).
 *
 * This object is the PURE half — no Android in it, so the chapter matcher is unit-tested with a
 * small TOC and chapter list ([ReadAlongPairingTest]) and the iOS twin
 * (`LedgerCore/ReadAlongPairing.swift`) says the same words. The stateful half (the ticker that
 * turns pages, the picker, the sidecar file) is [ReadAlong].
 *
 * THE MAPPING RULE (Readest's, PR #5856 + #5863): match Audiobookshelf chapters to EPUB TOC
 * entries by NORMALIZED TITLE first — "Chapter 12: The Storm" and "12 - The Storm" are both
 * "the storm" once the chapter word, the numbers and the punctuation are gone — then FUZZY on
 * that key (bigram Dice ≥ 0.8), then by ORDINAL: equal chapter numbers ("Chapter III" ≡ "Chapter
 * 3" ≡ "Three"), and finally sequential fill between the pairs already found, the way Readest's
 * anchor+offset walks outward from one known match. Every accepted pair keeps both lists in
 * order (no crossing), because a chapter list and a table of contents are the same book told
 * twice. Audio chapters nobody could map (1.1, 1.2, "Opening credits") are ABSORBED by the mapped
 * chapter before them — their time belongs to that chapter's span, so the recording never skips
 * them (#5863). Chapters before the first mapped one belong to nobody and play only if scrubbed to.
 *
 * WIRE — `readalong/<fileKey>.json`, one file per book, beside `reading-state.json` on the hub so
 * it rides the same sync (newest `updated` wins; a re-pair on one device reaches the others):
 * ```
 * { "absItemId": "...", "absServerHost": "audiobooks.example.com", "mode": "chapters",
 *   "bookTitle": "...", "audioTitle": "...", "audioChapterCount": 44, "updated": 1690000000000,
 *   "chapterMap": [ { "audioChapterIndex": 3, "tocHref": "ch01.xhtml", "sectionIndex": 4,
 *                     "how": "title" | "fuzzy" | "ordinal" | "manual" }, … ] }
 * ```
 * Byte-stable twin of the Swift `ReadAlongPairing` — do not rename fields.
 */
object ReadAlongPairing {

    /** One EPUB TOC entry: its href, its label, and the spine section it lands in (-1 = unknown). */
    data class TextChapter(val href: String, val label: String, val sectionIndex: Int)

    /** One Audiobookshelf chapter on the item's GLOBAL timeline (seconds). */
    data class AudioChapter(val index: Int, val start: Double, val end: Double, val title: String)

    /** One accepted pair. [how] says which rule made it, so the Re-pair door can show its work. */
    data class Mapping(val audioChapterIndex: Int, val tocHref: String, val sectionIndex: Int, val how: String)

    data class Pairing(
        val absItemId: String,
        val absServerHost: String,
        val bookTitle: String,
        val audioTitle: String,
        val audioChapterCount: Int,
        val chapterMap: List<Mapping>,
        val updated: Long,
        val mode: String = MODE_CHAPTERS,
        /** The reader's key for the BOOK side — stamped at save so Now Playing can find its way
         *  back to the page (09-11). Additive; absent on pairings written before it existed. */
        val bookKey: String? = null,
    ) {
        val matched: Int get() = chapterMap.size
        /** "42 of 44 chapters matched" — the one sentence the user sees after pairing. */
        val summary: String get() = "$matched of $audioChapterCount chapters matched"
    }

    /** A mapped chapter's OWN span on the global timeline, with the unmapped chapters after it absorbed. */
    data class Span(val mapping: Mapping, val start: Double, val end: Double)

    const val MODE_CHAPTERS = "chapters"
    const val HOW_TITLE = "title"
    const val HOW_FUZZY = "fuzzy"
    const val HOW_ORDINAL = "ordinal"
    const val HOW_MANUAL = "manual"

    /** Readest's fuzzy floor. */
    const val FUZZY_MIN = 0.8

    // ── Matching ─────────────────────────────────────────────────────────────────────────────

    fun match(toc: List<TextChapter>, audio: List<AudioChapter>): List<Mapping> {
        if (toc.isEmpty() || audio.isEmpty()) return emptyList()
        val tKeys = toc.map { normalizeTitle(it.label) }
        val aKeys = audio.map { normalizeTitle(it.title) }
        val tNums = toc.map { chapterNumber(it.label) }
        val aNums = audio.map { chapterNumber(it.title) }
        val usedA = BooleanArray(audio.size)
        val usedT = BooleanArray(toc.size)
        val pairs = mutableListOf<Triple<Int, Int, String>>()   // (audio index, toc index, how)

        // A pair may join only if it keeps both lists in order against every pair already in.
        fun monotonic(a: Int, t: Int) = pairs.all { (pa, pt, _) -> pa != a && pt != t && ((pa < a) == (pt < t)) }
        fun accept(a: Int, t: Int, how: String) { pairs += Triple(a, t, how); usedA[a] = true; usedT[t] = true }

        // Pass 1 — exact normalized title, unique on both sides.
        val tCount = tKeys.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        val aCount = aKeys.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        for (a in audio.indices) {
            val k = aKeys[a]
            if (k.length < 2 || aCount[k] != 1 || tCount[k] != 1) continue
            val t = tKeys.indexOf(k)
            if (t >= 0 && !usedT[t] && monotonic(a, t)) accept(a, t, HOW_TITLE)
        }

        // Pass 2 — fuzzy on the same key, best scores first.
        val candidates = mutableListOf<Triple<Int, Int, Double>>()
        for (a in audio.indices) {
            if (usedA[a] || aKeys[a].length < 3) continue
            for (t in toc.indices) {
                if (usedT[t] || tKeys[t].length < 3) continue
                val s = similarity(aKeys[a], tKeys[t])
                if (s >= FUZZY_MIN) candidates += Triple(a, t, s)
            }
        }
        for ((a, t, _) in candidates.sortedWith(compareByDescending<Triple<Int, Int, Double>> { it.third }.thenBy { it.first })) {
            if (!usedA[a] && !usedT[t] && monotonic(a, t)) accept(a, t, HOW_FUZZY)
        }

        // Pass 3 — ordinal by chapter NUMBER, unique among what is still unmatched.
        val tNumCount = toc.indices.filter { !usedT[it] }.mapNotNull { tNums[it] }.groupingBy { it }.eachCount()
        val aNumCount = audio.indices.filter { !usedA[it] }.mapNotNull { aNums[it] }.groupingBy { it }.eachCount()
        for (a in audio.indices) {
            if (usedA[a]) continue
            val n = aNums[a] ?: continue
            if (aNumCount[n] != 1 || tNumCount[n] != 1) continue
            val t = toc.indices.firstOrNull { !usedT[it] && tNums[it] == n } ?: continue
            if (monotonic(a, t)) accept(a, t, HOW_ORDINAL)
        }

        // Pass 4 — sequential fill between the pairs found (Readest's anchor+offset, walked from
        // each anchor): before the first anchor the fill is right-aligned against it, after an
        // anchor it is left-aligned from it, and only as many as both sides have.
        val anchors = pairs.sortedBy { it.first }
        fun fill(aFrom: Int, aTo: Int, tFrom: Int, tTo: Int, rightAlign: Boolean) {
            val aa = (aFrom until aTo).filter { !usedA[it] }
            val tt = (tFrom until tTo).filter { !usedT[it] }
            val n = minOf(aa.size, tt.size)
            if (n == 0) return
            val aPick = if (rightAlign) aa.takeLast(n) else aa.take(n)
            val tPick = if (rightAlign) tt.takeLast(n) else tt.take(n)
            for (i in 0 until n) if (monotonic(aPick[i], tPick[i])) accept(aPick[i], tPick[i], HOW_ORDINAL)
        }
        if (anchors.isEmpty()) {
            fill(0, audio.size, 0, toc.size, rightAlign = false)
        } else {
            fill(0, anchors.first().first, 0, anchors.first().second, rightAlign = true)
            for (i in anchors.indices) {
                val (a1, t1, _) = anchors[i]
                val a2 = anchors.getOrNull(i + 1)?.first ?: audio.size
                val t2 = anchors.getOrNull(i + 1)?.second ?: toc.size
                fill(a1 + 1, a2, t1 + 1, t2, rightAlign = false)
            }
        }

        return pairs.sortedBy { it.first }.map { (a, t, how) ->
            Mapping(audioChapterIndex = a, tocHref = toc[t].href, sectionIndex = toc[t].sectionIndex, how = how)
        }
    }

    private val CHAPTER_WORDS = setOf("chapter", "chapters", "chap", "ch", "part", "book", "section", "sec")
    private val ROMAN = Regex("^m{0,3}(cm|cd|d?c{0,3})(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")
    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)

    private fun romanValue(s: String): Int {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
        var total = 0
        for (i in s.indices) {
            val cur = v[s[i]] ?: return 0
            val next = if (i + 1 < s.length) v[s[i + 1]] ?: 0 else 0
            total += if (cur < next) -cur else cur
        }
        return total
    }

    /** The tokens of a title, lowercased, punctuation gone — with a flag for "this token was ALL CAPS
     *  in the original" (how a roman numeral tells itself apart from the word "mix"). */
    private fun tokens(s: String): List<Pair<String, Boolean>> =
        s.replace(Regex("""[\p{Punct}–—‘’“”·•]+"""), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.lowercase() to (it.length <= 7 && it == it.uppercase() && it != it.lowercase()) }

    private fun isRoman(tok: String, caps: Boolean, afterChapterWord: Boolean, alone: Boolean) =
        tok.isNotEmpty() && ROMAN.matches(tok) && (caps || afterChapterWord || alone)

    private fun numberWord(tok: String): Int? {
        val i = ONES.indexOf(tok); if (i >= 0) return i
        TENS[tok]?.let { return it }
        // twenty-one arrives as "twenty one" (the hyphen went with the punctuation) — see chapterNumber.
        return null
    }

    /**
     * The title with the chapter word, every number (digits, roman numerals, number words) and all
     * punctuation stripped, lowercased, single-spaced. "Chapter 12: The Storm" → "the storm";
     * "Chapter 12" → "" (which is what makes the ordinal fallback necessary).
     */
    fun normalizeTitle(s: String): String {
        val toks = tokens(s)
        val out = ArrayList<String>()
        var prevChapterWord = false
        for ((tok, caps) in toks) {
            val drop = tok in CHAPTER_WORDS || tok.all { it.isDigit() } ||
                isRoman(tok, caps, prevChapterWord, toks.size == 1) || numberWord(tok) != null ||
                (tok.endsWith("th") || tok.endsWith("st") || tok.endsWith("nd") || tok.endsWith("rd")) && tok.dropLast(2).isNotEmpty() && tok.dropLast(2).all { it.isDigit() }
            if (!drop) out += tok
            prevChapterWord = tok in CHAPTER_WORDS
        }
        return out.joinToString(" ")
    }

    /**
     * The chapter's number, when the title carries one: the number right after a chapter word,
     * else the first plain integer / roman numeral / number word. "1.1" is a SUB-chapter and has
     * no number here (it should be absorbed, not matched). Null when none.
     */
    fun chapterNumber(s: String): Int? {
        // "1.1" / "2.3" — the dotted sub-chapter shape; no chapter number of its own.
        if (Regex("""^\s*\d+\.\d+""").containsMatchIn(s)) return null
        val toks = tokens(s)
        fun valueAt(i: Int, afterChapterWord: Boolean): Int? {
            val (tok, caps) = toks[i]
            if (tok.all { it.isDigit() } && tok.length <= 4) return tok.toInt()
            if (isRoman(tok, caps, afterChapterWord, toks.size == 1)) return romanValue(tok).takeIf { it > 0 }
            val w = numberWord(tok) ?: return null
            // "twenty one" → 21
            if (w >= 20 && w % 10 == 0 && i + 1 < toks.size) numberWord(toks[i + 1].first)?.let { if (it in 1..9) return w + it }
            return w
        }
        for (i in toks.indices) if (toks[i].first in CHAPTER_WORDS && i + 1 < toks.size) valueAt(i + 1, true)?.let { return it }
        for (i in toks.indices) valueAt(i, false)?.let { return it }
        return null
    }

    /** Sørensen–Dice over character bigrams — 1.0 identical, 0.0 nothing shared. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        fun grams(s: String) = (0 until s.length - 1).map { s.substring(it, it + 2) }.groupingBy { it }.eachCount()
        val ga = grams(a); val gb = grams(b)
        var shared = 0
        for ((g, n) in ga) shared += minOf(n, gb[g] ?: 0)
        return 2.0 * shared / (ga.values.sum() + gb.values.sum())
    }

    // ── Spans & lookups (what playback and the page-follower ask) ────────────────────────────

    /**
     * Each mapped chapter's span on the global timeline, in recording order: from its own start to
     * the NEXT mapped chapter's start — so the unmapped chapters after it (and any gap) are absorbed
     * (#5863). The last span runs to [totalDuration] (or the last chapter's end).
     */
    fun spans(map: List<Mapping>, audio: List<AudioChapter>, totalDuration: Double): List<Span> {
        val byIndex = audio.associateBy { it.index }
        val sorted = map.filter { byIndex.containsKey(it.audioChapterIndex) }.sortedBy { it.audioChapterIndex }
        val tail = maxOf(totalDuration, audio.maxOfOrNull { it.end } ?: 0.0)
        return sorted.mapIndexed { i, m ->
            val start = byIndex.getValue(m.audioChapterIndex).start
            val end = sorted.getOrNull(i + 1)?.let { byIndex.getValue(it.audioChapterIndex).start } ?: tail
            Span(m, start, maxOf(start, end))
        }
    }

    /** The span the playhead is inside (the last one starting at or before [seconds]); null before the first. */
    fun spanAt(spans: List<Span>, seconds: Double): Span? = spans.lastOrNull { it.start <= seconds + 0.001 }

    /**
     * The span a SECTION of the book is narrated by: the first mapped entry in that section; when
     * the section has none of its own (a chapter split across files, front matter), the nearest
     * mapped entry BEFORE it; before everything, the first span.
     */
    fun spanFor(spans: List<Span>, sectionIndex: Int): Span? {
        if (spans.isEmpty()) return null
        spans.firstOrNull { it.mapping.sectionIndex == sectionIndex }?.let { return it }
        return spans.filter { it.mapping.sectionIndex in 0 until sectionIndex }.maxByOrNull { it.mapping.sectionIndex }
            ?: spans.first()
    }

    /**
     * The whole stretch of recording a section owns — from its first mapped entry's start to its
     * last mapped entry's end (several TOC entries can share one XHTML file). Falls back like
     * [spanFor]. The "approximate" timing: a reader 40% into the section starts 40% into this.
     */
    fun sectionRange(spans: List<Span>, sectionIndex: Int): Pair<Double, Double>? {
        val own = spans.filter { it.mapping.sectionIndex == sectionIndex }
        if (own.isNotEmpty()) return own.first().start to own.last().end
        val s = spanFor(spans, sectionIndex) ?: return null
        return s.start to s.end
    }

    /** Where to start when a reader stands [fraction] of the way through [sectionIndex]. */
    fun startSeconds(spans: List<Span>, sectionIndex: Int, fraction: Double): Double? {
        val (start, end) = sectionRange(spans, sectionIndex) ?: return null
        return start + (end - start) * fraction.coerceIn(0.0, 1.0)
    }

    // ── The sidecar file ─────────────────────────────────────────────────────────────────────

    /** Java's String.hashCode, spelled out so the Swift twin computes the same 32 bits over UTF-16. */
    fun javaHash(s: String): Int {
        var h = 0
        for (c in s) h = 31 * h + c.code
        return h
    }

    /**
     * `readalong/<fileKey>.json` — the book key (`folder/name#size`, or a bare name) made safe for
     * a WebDAV path, with its hash appended so two keys that sanitize alike stay two files.
     */
    fun fileKey(bookKey: String): String {
        val safe = bookKey.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(80).ifBlank { "book" }
        return safe + "-" + Integer.toHexString(javaHash(bookKey))
    }

    fun remotePath(bookKey: String) = "readalong/" + fileKey(bookKey) + ".json"

    fun toJson(p: Pairing): String {
        val map = JSONArray()
        for (m in p.chapterMap) map.put(
            JSONObject().put("audioChapterIndex", m.audioChapterIndex).put("tocHref", m.tocHref)
                .put("sectionIndex", m.sectionIndex).put("how", m.how)
        )
        return JSONObject()
            .put("absItemId", p.absItemId).put("absServerHost", p.absServerHost).put("mode", p.mode)
            .put("bookTitle", p.bookTitle).put("audioTitle", p.audioTitle)
            .put("audioChapterCount", p.audioChapterCount).put("updated", p.updated)
            .put("chapterMap", map)
            .apply { p.bookKey?.let { put("bookKey", it) } }
            .toString()
    }

    fun fromJson(text: String?): Pairing? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            val id = o.optString("absItemId"); if (id.isBlank()) return null
            val arr = o.optJSONArray("chapterMap") ?: JSONArray()
            val map = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                Mapping(m.optInt("audioChapterIndex", -1), m.optString("tocHref"), m.optInt("sectionIndex", -1),
                    m.optString("how").ifBlank { HOW_ORDINAL })
            }.filter { it.audioChapterIndex >= 0 }
            Pairing(
                absItemId = id, absServerHost = o.optString("absServerHost"),
                bookTitle = o.optString("bookTitle"), audioTitle = o.optString("audioTitle"),
                audioChapterCount = o.optInt("audioChapterCount", map.size), chapterMap = map,
                updated = o.optLong("updated", 0L), mode = o.optString("mode").ifBlank { MODE_CHAPTERS },
                bookKey = o.optString("bookKey").ifBlank { null },
            )
        }.getOrNull()
    }

    /** Newest `updated` wins — the one merge rule a single-record sidecar needs. */
    fun newest(a: Pairing?, b: Pairing?): Pairing? = when {
        a == null -> b
        b == null -> a
        b.updated > a.updated -> b
        else -> a
    }

    /**
     * The Re-pair door's one edit: point audio chapter [audioChapterIndex] at [toc] (or at nothing,
     * when [toc] is null — "no text, absorb it into the chapter before"), dropping any other pair
     * that used the same audio chapter or the same TOC entry. Marked [HOW_MANUAL]; stamped now.
     */
    fun repair(p: Pairing, audioChapterIndex: Int, toc: TextChapter?, now: Long): Pairing {
        val kept = p.chapterMap.filter { it.audioChapterIndex != audioChapterIndex && (toc == null || it.tocHref != toc.href) }
        val added = toc?.let { Mapping(audioChapterIndex, it.href, it.sectionIndex, HOW_MANUAL) }
        return p.copy(chapterMap = (kept + listOfNotNull(added)).sortedBy { it.audioChapterIndex }, updated = now)
    }
}
