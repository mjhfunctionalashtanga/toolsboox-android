package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.feeds.ot.ArticleKey
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * WHICH GRAM PICKS (AND LOG HIGHLIGHTS) CAME FROM THIS ARTICLE — the reverse index that lets a pick
 * light its passage back up on the page (Michael 2026-10-12: "gram picks should highlight back
 * too").
 *
 * A pick is an image element in the day file of the day it was made, its passage in `cardText`,
 * the article in `sourceLink` — scattered across every day of the ledger. Opening an article must
 * not walk a year of day files, so this keeps, per day, the passages that day holds for each article
 * key ([ArticleKey.of]):
 *  - PICKS: any card with an http(s) source and words of its own (`cardText`) — made by Highlight,
 *    by the old Make pick, from the share card, on another device; wherever it was moved since
 *    (a board on the same day keeps its id). Not All Stars' link cards (their words are the title,
 *    not a passage), not stickers.
 *  - LINES: article Log lines with a passage (`excerpt`) — the highlights made before the mark
 *    store existed (an iOS ❝ since build 223 made a line and no card).
 * Old picks need no migration step anyone sees: they are READ here, at paint time, and become
 * marks only when you act on one (ArticleHighlights' adoption).
 *
 * DERIVED, PER DEVICE, NEVER SYNCED — [PickingsCards]' rules, for its reasons: every device holds
 * the day files this is computed from. Fed by the same one throat ([CalendarDayService.save] →
 * [record]) and repaired by the same free backfill ([CalendarDayService.load] → [refreshIfStale]);
 * a day a sync installed behind the app's back reads stale by its file time and is re-read with
 * a slim decode by [repairStale] (no strokes, no base64), newest first, bounded per pass.
 */
object ArticlePickIndex {

    /** One passage on one day: a pick ([elementId] set) or a Log line ([lineId] set). */
    data class Hit(
        val date: LocalDate,
        val key: String,
        val text: String,
        val elementId: String = "",
        val page: String = "",
        val lineId: String = "",
        val url: String = "",
        /** A Log line with a note or media on it — the highlight wears the "noted" look. */
        val noted: Boolean = false,
        /** A pick's own label (`sourceLabel` — the share card's, a star's): the guard against a link
         *  card whose words are only its label ([ArticleMarkSet.assemble]). */
        val label: String = "",
        /** A line's typed words, the first [NOTE_PREVIEW] characters — what the chooser's "open" row
         *  and the Annotations list show without opening the day. */
        val note: String = "",
        /** A line carries handwriting (an `ink-` attachment). */
        val ink: Boolean = false,
        /** What rides on the line, as glyphs (`✍ 📷 🎥 🎤` — add-to-pick pin §6): the Annotations
         *  list's summary. Empty on entries indexed before it (filled as their day is re-read). */
        val glyphs: String = "",
    )

    private const val FILE = "article-picks.json"
    private const val TEXT_MAX = 4000
    const val NOTE_PREVIEW = 120
    /**
     * What the index holds changed (review 2026-10-12): 2 = podcast moments and `listen-` lines left
     * it, picks carry their label, lines their note preview and ink, and a Log line with a note but
     * no passage is in it (the Annotations list's old whole-article notes). A file of another version
     * is dropped and the days are re-read by [repairStale] — the slim decode, newest first.
     */
    private const val VERSION = 2

    @Volatile private var appContext: Context? = null

    fun attach(context: Context) { appContext = context.applicationContext }

    private val lock = Any()

    /** date → (the day file's mtime when indexed, its hits). Loaded once, kept in memory. */
    private var memo: MutableMap<LocalDate, Pair<Long, List<Hit>>>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun loaded(context: Context): MutableMap<LocalDate, Pair<Long, List<Hit>>> {
        memo?.let { return it }
        val out = HashMap<LocalDate, Pair<Long, List<Hit>>>()
        runCatching {
            val f = file(context)
            val root = if (f.exists()) JSONObject(f.readText()) else null
            if (root != null && root.optInt("v") == VERSION) {
                val days = root.optJSONObject("days") ?: JSONObject()
                for (d in days.keys()) {
                    val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: continue
                    val o = days.getJSONObject(d)
                    val arr = o.optJSONArray("h") ?: JSONArray()
                    out[date] = o.optLong("m") to (0 until arr.length()).map { i ->
                        val h = arr.getJSONObject(i)
                        Hit(date, h.optString("k"), h.optString("t"), h.optString("id"),
                            h.optString("pg"), h.optString("ln"), h.optString("u"), h.optBoolean("n"),
                            h.optString("lb"), h.optString("nt"), h.optBoolean("ik"), h.optString("gl"))
                    }
                }
            }
        }.onFailure { Timber.w(it, "article pick index unreadable; rebuilding") }
        memo = out
        return out
    }

    private fun persist(context: Context, map: Map<LocalDate, Pair<Long, List<Hit>>>) {
        runCatching {
            val days = JSONObject()
            for ((date, entry) in map) {
                if (entry.second.isEmpty() && entry.first == 0L) continue
                days.put(date.toString(), JSONObject().put("m", entry.first).put("h", JSONArray().apply {
                    entry.second.forEach {
                        put(JSONObject().put("k", it.key).put("t", it.text).put("id", it.elementId)
                            .put("pg", it.page).put("ln", it.lineId).put("u", it.url).put("n", it.noted)
                            .apply {
                                if (it.label.isNotEmpty()) put("lb", it.label)
                                if (it.note.isNotEmpty()) put("nt", it.note)
                                if (it.ink) put("ik", true)
                                if (it.glyphs.isNotEmpty()) put("gl", it.glyphs)
                            })
                    }
                }))
            }
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject().put("v", VERSION).put("days", days).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Timber.w(it, "article pick index write failed") }
    }

    // ── What a day holds ──────────────────────────────────────────────────────────────────────

    /** A card is a pick that can highlight back: an http(s) source, its own words, not a link card
     *  on All Stars, not a sticker. Public so the controller's live re-read agrees with the index. */
    fun isPassagePick(page: String, sourceLink: String, cardText: String, decorative: Boolean, intakeKind: String): Boolean =
        !decorative && cardText.isNotBlank() && sourceLink.startsWith("http", ignoreCase = true) &&
            page != CalendarDayPageIntake.INTAKE_PAGE && intakeKind.isBlank()

    /** The passage a stored selection really is: the link lines WebSelectionBridge appends
     *  ("label → https://…" / a bare URL, one per line, after the text) come off (critique #13). */
    fun passageOf(raw: String): String {
        val lines = raw.trim().lines()
        var end = lines.size
        while (end > 1) {
            val l = lines[end - 1].trim()
            val isLink = l.startsWith("http://") || l.startsWith("https://") ||
                Regex("""\s→\s+https?://\S+$""").containsMatchIn(l)
            if (!isLink) break
            end--
        }
        return lines.take(end).joinToString("\n").trim()
    }

    private val keyMemo = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    private fun keyOf(url: String): String = synchronized(keyMemo) { keyMemo.getOrPut(url) { ArticleKey.of(url) } }

    /**
     * A card made at a MOMENT of a recording, not on a passage of the text: its source carries the
     * media fragment (`…#t=1421` — the podcast transport's ◈/❝, "@ 23:41" over the episode). Its
     * words are a clock time, never in the show notes, so drawn back it could only ever sit in the
     * Annotations list as "not on this version". iOS's `ArticlePassage.isMomentLink`, to the
     * character (review 2026-10-12, #4: iOS had the guard and Android's index did not, so the two
     * apps drew different highlight sets).
     */
    fun isMomentLink(sourceLink: String): Boolean {
        val hash = sourceLink.indexOf('#')
        if (hash < 0) return false
        return sourceLink.substring(hash + 1).split('&').any { part ->
            part.startsWith("t=") && part.length > 2 &&
                part.substring(2).all { it.isDigit() || it == '.' || it == ':' || it == ',' }
        }
    }

    /** One Log line as the index reads it, whichever decode it came from. Null: not an article's
     *  highlight or note — a reply (↩), a podcast moment's `listen-` line, a removed one, a ★. */
    private fun lineHit(
        date: LocalDate, id: String, kind: String, url: String, excerpt: String, source: String,
        note: String, attachments: List<String>, starred: Boolean, dead: Set<String>,
    ): Hit? {
        if (kind != "article" || !url.startsWith("http", ignoreCase = true) || source.startsWith("↩") ||
            id.startsWith("listen-") || id.lowercase() in dead) return null
        val passage = passageOf(excerpt).take(TEXT_MAX)
        val words = note.trim()
        // A line with no passage is still a note ABOUT the piece when it carries words or media
        // (iOS's index keeps it, so the Annotations list shows it). A ★'s line is not: its "note"
        // is the piece's own blurb (or "★ starred Oct 3" on the published day's companion).
        if (passage.isBlank() && (starred || (words.isBlank() && attachments.isEmpty()))) return null
        return Hit(date, keyOf(url), passage, lineId = id, url = url,
            noted = words.isNotBlank() || attachments.isNotEmpty(),
            note = words.take(NOTE_PREVIEW),
            ink = attachments.any { it.startsWith("ink-") },
            glyphs = glyphsOf(attachments))
    }

    /** The glyph summary of a line's attachments by their filenames (ink-…, photo-…, video-…,
     *  voice-…; the extensions when a name is someone else's). */
    fun glyphsOf(filenames: List<String>): String {
        val n = filenames.map { it.lowercase() }
        val ink = n.count { it.startsWith("ink-") }
        val video = n.count { it.startsWith("video-") || it.endsWith(".mp4") || it.endsWith(".mov") }
        val voice = n.count { it.startsWith("voice-") || it.endsWith(".m4a") || it.endsWith(".aac") }
        val photo = n.size - ink - video - voice
        return AddToPickWords.summary(ink, photo, video, voice)
    }

    private fun pickHit(
        date: LocalDate, id: String, page: String, link: String, label: String, cardText: String,
        decorative: Boolean, intakeKind: String, dead: Set<String>,
    ): Hit? {
        if (id.lowercase() in dead) return null
        if (!isPassagePick(page, link, cardText, decorative, intakeKind) || isMomentLink(link)) return null
        return Hit(date, keyOf(link), passageOf(cardText).take(TEXT_MAX), elementId = id, page = page,
            url = link, label = label)
    }

    internal fun hitsOf(date: LocalDate, day: CalendarDay): List<Hit> {
        val out = ArrayList<Hit>()
        // A card or line held BESIDE its own tombstone (an older build's merge unioned it back) is
        // gone — the same read rule as the day decode's (CalendarDayMerger.liveLines): a removed
        // highlight's card must not light its passage back up.
        val dead = day.deletedElementIds.map { it.lowercase() }.toSet()
        for (e in day.imageElements) {
            pickHit(date, e.elementId.toString(), e.page, e.sourceLink, e.sourceLabel, e.cardText,
                e.decorative, e.intakeKind, dead)?.let { out += it }
        }
        for (r in day.readingEvents) {
            val kind = if (r.kind == com.toolsboox.plugin.calendar.da.v2.ReadingEvent.Kind.ARTICLE) "article" else ""
            lineHit(date, r.id, kind, r.url.orEmpty(), r.excerpt.orEmpty(), r.source.orEmpty(), r.note.orEmpty(),
                r.attachments.orEmpty().map { it.filename }, r.starred, dead)?.let { out += it }
        }
        return out
    }

    private fun hitsOfSlice(date: LocalDate, slice: CalendarDayService.DayLitePicks): List<Hit> {
        val out = ArrayList<Hit>()
        val dead = slice.deletedElementIds.orEmpty().mapNotNull { it?.lowercase() }.toSet()
        for (e in slice.imageElements.orEmpty()) {
            e ?: continue
            pickHit(date, e.elementId.orEmpty(), e.page.orEmpty(), e.sourceLink.orEmpty(), e.sourceLabel.orEmpty(),
                e.cardText.orEmpty(), e.decorative == true, e.intakeKind.orEmpty(), dead)?.let { out += it }
        }
        for (r in slice.readingEvents.orEmpty()) {
            r ?: continue
            lineHit(date, r.id.orEmpty(), r.kind.orEmpty(), r.url.orEmpty(), r.excerpt.orEmpty(), r.source.orEmpty(),
                r.note.orEmpty(), r.attachments.orEmpty().mapNotNull { it?.filename ?: it?.id }, r.starred == true, dead)?.let { out += it }
        }
        return out
    }

    // ── Writing ───────────────────────────────────────────────────────────────────────────────

    /** The save funnel's hook: [day] was just written for [date]. Cheap — no decode, a write only
     *  when the day's passages changed. */
    fun record(date: LocalDate, day: CalendarDay) {
        val context = appContext ?: return
        runCatching {
            val m = LedgerPaths.dayFile(context, date)?.lastModified() ?: System.currentTimeMillis()
            put(context, date, m, hitsOf(date, day))
        }.onFailure { Timber.w(it, "article pick index record failed for $date") }
    }

    /** The load funnel's hook: a full decode happened anyway — re-index if the file is newer. */
    fun refreshIfStale(date: LocalDate, day: CalendarDay, dayFile: File) {
        val context = appContext ?: return
        val held = synchronized(lock) { loaded(context)[date] }
        if (held != null && held.first >= dayFile.lastModified()) return
        runCatching { put(context, date, dayFile.lastModified(), hitsOf(date, day)) }
    }

    /** True when the day's passages changed (and, with [persistNow], the file was rewritten). */
    private fun put(context: Context, date: LocalDate, m: Long, hits: List<Hit>, persistNow: Boolean = true): Boolean {
        synchronized(lock) {
            val map = loaded(context)
            val held = map[date]
            map[date] = m to hits
            // The file is rewritten only when the passages changed; a newer mtime alone stays in
            // memory (after a restart that day is re-read once — a slim decode, not a write per
            // pen-up save).
            val changed = held == null || held.second != hits
            if (changed && persistNow) persist(context, map)
            return changed
        }
    }

    /** One repair at a time: two article opens in a row must not walk the ledger twice. */
    private val repairing = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Reading ───────────────────────────────────────────────────────────────────────────────

    /** Every pick and Log passage indexed for article [key], oldest day first. */
    fun hits(context: Context, key: String): List<Hit> = synchronized(lock) {
        loaded(context).values.asSequence().flatMap { it.second.asSequence() }
            .filter { it.key == key }.sortedBy { it.date }.toList()
    }

    /**
     * Repair up to [limit] day files that changed behind the index (a sync's install, or a day
     * never indexed), newest first, with a SLIM decode. Off the main thread. Returns how many were
     * read; 0 means the index is current — call again while it returns [limit].
     */
    fun repairStale(context: Context, service: CalendarDayService, limit: Int = 60): Int {
        // At most one walk of the calendar folder per [SCAN_EVERY_MS] once it is current (iOS's
        // `rows(for:every: 20)`, review 2026-10-12): an article opened every few seconds while
        // paging the list must not list and stat a year of day files each time. A save on this
        // device indexes itself ([record]); only another device's sync waits for the next walk.
        val now = System.currentTimeMillis()
        if (current && now - lastScanAt < SCAN_EVERY_MS) return 0
        if (!repairing.compareAndSet(false, true)) return 0
        try {
            val read = repairStaleOnce(context, service, limit)
            lastScanAt = System.currentTimeMillis()
            current = read < limit
            return read
        } finally { repairing.set(false) }
    }

    private const val SCAN_EVERY_MS = 20_000L
    @Volatile private var lastScanAt = 0L
    @Volatile private var current = false

    /** Load the index off the main thread before the first article opens (its first read parses
     *  the whole file — a beat on a B6, review 2026-10-12 #5). */
    fun warm(context: Context) {
        val app = context.applicationContext
        Thread { runCatching { synchronized(lock) { loaded(app) } } }.apply { isDaemon = true }.start()
    }

    private fun repairStaleOnce(context: Context, service: CalendarDayService, limit: Int): Int {
        val root = File(LedgerPaths.documentsRoot(context), "calendar")
        if (!root.isDirectory) return 0
        val files = root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.startsWith("day-") && it.name.endsWith("-v2.json") }
            .mapNotNull { f -> PickingsCards.dateOf(f.name)?.let { it to f } }
            .sortedByDescending { it.first }
            .toList()
        val known = synchronized(lock) { HashMap(loaded(context)) }
        var done = 0
        var dirty = false
        for ((date, f) in files) {
            if (done >= limit) break
            val held = known[date]
            val mtime = f.lastModified()
            if (held != null && held.first >= mtime) continue
            val slice = runCatching { service.loadPicksSlice(f) }.getOrNull()
            // Written once per pass, not once per day: a first run over a year of days would
            // otherwise rewrite a growing file hundreds of times.
            if (put(context, date, mtime, slice?.let { hitsOfSlice(date, it) } ?: emptyList(), persistNow = false)) dirty = true
            done++
        }
        if (dirty || done > 0) synchronized(lock) { persist(context, loaded(context)) }
        return done
    }
}
