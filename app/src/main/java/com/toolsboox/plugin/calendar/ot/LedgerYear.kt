package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Attachment
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ListenState
import com.toolsboox.plugin.calendar.da.v2.MediaLogEntry
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.da.v2.ReadingSession
import com.toolsboox.plugin.calendar.da.v2.ReadingState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * YOUR YEAR IN THE LEDGER — a review of what the owner has already logged, for a chosen span.
 *
 * Nothing new is collected. Every number here is read off records the Ledger already keeps: the
 * day files (reading events, grams, text, ink), the listen-state and reading-session sidecars, the
 * media log, the reading-state sidecar (finished books), the book-opens sidecar and the mail
 * store. The engine is pure — it is handed those rows and a span — so the same synthetic inputs
 * produce the same totals on both forks (iOS `LedgerYear` in LedgerCore is the twin, word for
 * word; `YearInLedgerTest` here and `LedgerYearTests` there build the same set).
 *
 * Voice: numbers with one line of plain words each. No colour carries meaning, no chart junk —
 * the only picture is a handful of text-sized blocks for the reading hours, which read on e-ink.
 */
object LedgerYear {

    // ── Span ──────────────────────────────────────────────────────────────────────────────────

    enum class Span(val title: String, val label: String) {
        WEEK("Week", "This week"),
        MONTH("Month", "This month"),
        YEAR("Year", "This year"),
        ALL("All", "All time");

        /** `[from, to)` in epoch millis. Week starts Monday; month on the 1st; year on Jan 1;
         *  ALL from the epoch. `to` is the start of tomorrow, so today counts in every span. */
        fun bounds(today: LocalDate, zone: ZoneId): Pair<Long, Long> {
            val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val from = when (this) {
                WEEK -> today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli()
                MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
                YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
                ALL -> 0L
            }
            return from to to
        }
    }

    // ── Inputs ────────────────────────────────────────────────────────────────────────────────

    /** One letter, either direction. [name]/[email] are the OTHER party — who wrote to you, or
     *  who you wrote to. Built by the host from its inbox store (both forks mark sent rows by id). */
    data class Letter(val name: String, val email: String, val date: Long, val sent: Boolean)

    /** One book's recorded opens (the `reading-opens.json` row). */
    data class BookOpen(val name: String, val opens: List<Long>)

    data class Input(
        val days: List<CalendarDay> = emptyList(),
        val listen: List<ListenState> = emptyList(),
        val sessions: List<ReadingSession> = emptyList(),
        val mediaLog: List<MediaLogEntry> = emptyList(),
        val readingStates: List<ReadingState> = emptyList(),
        val bookOpens: List<BookOpen> = emptyList(),
        val letters: List<Letter> = emptyList(),
    )

    // ── Output ────────────────────────────────────────────────────────────────────────────────

    /** A big number and one line of plain words. [detail] is an optional second line (the
     *  episode count under hours listened, the hour blocks under the busiest hour). */
    data class Stat(val id: String, val number: String, val words: String, val detail: String? = null)

    data class Ranked(val name: String, val count: Int)

    data class Review(
        val span: Span,
        val from: Long,
        val to: Long,
        val stats: List<Stat>,
        val topAuthors: List<Ranked>,
        val topFeeds: List<Ranked>,
        val topCorrespondents: List<Ranked>,
        // The raw counts, kept beside the rendered rows so a test asserts numbers, not strings.
        val booksOpened: Int,
        val booksFinished: Int,
        val bookMinutes: Long,
        val articlesRead: Int,
        val articlesStarred: Int,
        val highlights: Int,
        val notes: Int,
        val listenMinutes: Long,
        val episodes: Int,
        val episodesFinished: Int,
        val lettersReceived: Int,
        val lettersSent: Int,
        val voiceMemos: Int,
        val photos: Int,
        val videos: Int,
        val inkDays: Int,
        val streak: Int,
        /** 0–23, or null when nothing was read. */
        val busiestHour: Int?,
        /** Six four-hour blocks, midnight first. */
        val hourBlocks: List<Int>,
    ) {
        val isEmpty: Boolean
            get() = booksOpened == 0 && booksFinished == 0 && bookMinutes == 0L && articlesRead == 0 &&
                articlesStarred == 0 && highlights == 0 && notes == 0 && listenMinutes == 0L &&
                lettersReceived == 0 && lettersSent == 0 && voiceMemos == 0 && photos == 0 &&
                videos == 0 && inkDays == 0
    }

    // ── Words (shared with iOS, verbatim) ─────────────────────────────────────────────────────

    const val TITLE = "Your year in the Ledger"
    const val DOOR_TITLE = "Your year…"
    const val EMPTY_WORDS = "Nothing logged in this span yet. Read, listen, write a line in ink — it all lands here."
    const val TOP_AUTHORS_TITLE = "Top authors"
    const val TOP_FEEDS_TITLE = "Top feeds"
    const val TOP_CORRESPONDENTS_TITLE = "Top correspondents"
    val HOUR_BLOCK_NAMES = listOf("night", "dawn", "morning", "afternoon", "evening", "late")

    // ── Compute ───────────────────────────────────────────────────────────────────────────────

    fun review(
        input: Input, span: Span,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): Review {
        val (from, to) = span.bounds(today, zone)
        fun inSpan(ms: Long) = ms >= from && ms < to

        // ── Books ─────────────────────────────────────────────────────────────────────────────
        val booksOpened = input.bookOpens.filter { row -> row.opens.any { inSpan(it) } }
            .map { it.name }.toSet().size
        val booksFinished = input.readingStates
            .filter { !it.isDeleted && it.percent >= 0.95 && inSpan(it.updated) }
            .map { bookTitle(it.id) }.toSet().size
        val bookMs = input.sessions
            .filter { !it.isDeleted && inSpan(it.start) &&
                (it.medium == ReadingSession.BOOK || it.medium == ReadingSession.COMIC) }
            .sumOf { it.durationMs }

        // ── Articles (the media log is the record of reading; the day files hold the marks) ───
        val articlesRead = input.mediaLog
            .filter { !it.isDeleted && it.kind == MediaLogEntry.READ && inSpan(it.at) }
            .map { it.id }.toSet().size

        val starredKeys = HashSet<String>()
        var highlights = 0
        var notes = 0
        var voice = 0; var photos = 0; var videos = 0
        val authorCounts = HashMap<String, Int>()
        val feedCounts = HashMap<String, Int>()
        val hours = IntArray(24)
        val inkDayOrdinals = HashSet<Long>()
        val activeDayOrdinals = HashSet<Long>()

        for (day in input.days) {
            val date = runCatching { LocalDate.of(day.year, day.month, day.day) }.getOrNull() ?: continue
            val ordinal = date.toEpochDay()
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            if (!inSpan(dayStart) && !inSpan(dayStart + 86_399_999L)) continue

            val hasInk = day.calendarStrokes.values.any { it.isNotEmpty() } ||
                day.noteStrokes.values.any { it.isNotEmpty() }
            if (hasInk) { inkDayOrdinals.add(ordinal); activeDayOrdinals.add(ordinal) }

            for (e in day.readingEvents) {
                val at = e.date.time
                if (!inSpan(at)) continue
                // A star lives on two days (starred + published); the `-pub` row is the same
                // star seen from its publish day, not a second mark.
                if (e.id.endsWith("-pub")) continue
                val isReply = e.source?.startsWith("↩") == true
                activeDayOrdinals.add(ordinal)
                hours[hourOfDay(at, zone)]++
                if (e.kind == ReadingEvent.Kind.ARTICLE && !isReply && e.starred) starredKeys.add(starKey(e))
                val hasExcerpt = !e.excerpt.isNullOrBlank()
                val hasNote = !e.note.isNullOrBlank()
                if (hasExcerpt && !isReply) highlights++
                if (hasNote && !isReply) notes++
                val src = e.source?.trim().orEmpty()
                if (!isReply && src.isNotEmpty() && (hasExcerpt || hasNote || e.starred)) {
                    when (e.kind) {
                        ReadingEvent.Kind.BOOK -> authorCounts[src] = (authorCounts[src] ?: 0) + 1
                        ReadingEvent.Kind.ARTICLE -> feedCounts[src] = (feedCounts[src] ?: 0) + 1
                    }
                }
                for (a in e.attachments.orEmpty()) when (a.kind) {
                    Attachment.Kind.AUDIO -> voice++
                    Attachment.Kind.PHOTO -> photos++
                    Attachment.Kind.VIDEO -> videos++
                }
            }
            for (g in day.avGrams) {
                val at = g.date?.time ?: dayStart
                if (!inSpan(at)) continue
                activeDayOrdinals.add(ordinal)
                when (g.kind) {
                    Attachment.Kind.AUDIO -> voice++
                    Attachment.Kind.PHOTO -> photos++
                    Attachment.Kind.VIDEO -> videos++
                }
            }
            for (t in day.textElements) {
                if (t.text.isBlank()) continue
                val at = if (t.timestamp > 0) t.timestamp else dayStart
                if (!inSpan(at)) continue
                activeDayOrdinals.add(ordinal)
                notes++
            }
        }
        for (m in input.mediaLog) {
            if (!m.isDeleted && inSpan(m.at) && m.kind == MediaLogEntry.READ) hours[hourOfDay(m.at, zone)]++
        }

        // ── Listening: the playhead IS the record of how much was heard ───────────────────────
        val heard = input.listen.filter { !it.isDeleted && inSpan(it.updated) && it.positionMs > 0 }
        val listenMs = heard.sumOf { it.positionMs }
        val episodesFinished = heard.count { it.done }

        // ── Letters ───────────────────────────────────────────────────────────────────────────
        val letters = input.letters.filter { inSpan(it.date) }
        val received = letters.count { !it.sent }
        val sent = letters.count { it.sent }
        val corr = HashMap<String, Int>()
        for (l in letters) {
            val key = l.name.trim().ifEmpty { l.email }
            if (key.isEmpty()) continue
            corr[key] = (corr[key] ?: 0) + 1
        }

        // ── Ink, streak, hour ─────────────────────────────────────────────────────────────────
        val inkDays = inkDayOrdinals.size
        val streak = longestRun(activeDayOrdinals)
        val busiest: Int? = if (hours.any { it > 0 }) {
            var best = 0
            for (h in 1 until 24) if (hours[h] > hours[best]) best = h   // ties → the earlier hour
            best
        } else null
        val blocks = MutableList(6) { 0 }
        for (h in 0 until 24) blocks[h / 4] += hours[h]

        // ── Rows ──────────────────────────────────────────────────────────────────────────────
        val stats = ArrayList<Stat>()
        stats.add(Stat("booksOpened", "$booksOpened", noun(booksOpened, "book opened", "books opened")))
        stats.add(Stat("booksFinished", "$booksFinished", noun(booksFinished, "book finished", "books finished")))
        stats.add(Stat("bookHours", hoursText(bookMs), "hours with a book open"))
        stats.add(Stat("articlesRead", "$articlesRead", noun(articlesRead, "article read", "articles read")))
        stats.add(Stat("articlesStarred", "${starredKeys.size}", noun(starredKeys.size, "article starred", "articles starred")))
        stats.add(Stat("highlights", "$highlights", noun(highlights, "highlight kept", "highlights kept")))
        stats.add(Stat("notes", "$notes", noun(notes, "note kept", "notes kept")))
        stats.add(Stat("listenHours", hoursText(listenMs), "hours listened",
            detail = if (heard.isEmpty()) null
            else "across ${plural(heard.size, "episode", "episodes")} · $episodesFinished finished"))
        stats.add(Stat("lettersIn", "$received", noun(received, "letter received", "letters received")))
        stats.add(Stat("lettersOut", "$sent", noun(sent, "letter sent", "letters sent")))
        stats.add(Stat("voice", "$voice", noun(voice, "voice memo kept", "voice memos kept")))
        stats.add(Stat("photos", "$photos", noun(photos, "photo kept", "photos kept")))
        if (videos > 0) stats.add(Stat("videos", "$videos", noun(videos, "video clip kept", "video clips kept")))
        stats.add(Stat("inkDays", "$inkDays", noun(inkDays, "day with ink on it", "days with ink on them")))
        stats.add(Stat("streak", "$streak", if (streak == 1) "day in your longest streak" else "days in your longest streak"))
        stats.add(Stat("busiestHour", busiest?.let { hourText(it) } ?: "—",
            if (busiest == null) "no reading hour yet" else "the hour you read most",
            detail = if (busiest == null) null else hourBars(blocks)))

        return Review(
            span = span, from = from, to = to, stats = stats,
            topAuthors = top(authorCounts), topFeeds = top(feedCounts), topCorrespondents = top(corr),
            booksOpened = booksOpened, booksFinished = booksFinished, bookMinutes = bookMs / 60_000,
            articlesRead = articlesRead, articlesStarred = starredKeys.size, highlights = highlights,
            notes = notes, listenMinutes = listenMs / 60_000, episodes = heard.size,
            episodesFinished = episodesFinished, lettersReceived = received, lettersSent = sent,
            voiceMemos = voice, photos = photos, videos = videos, inkDays = inkDays, streak = streak,
            busiestHour = busiest, hourBlocks = blocks,
        )
    }

    // ── Share text (the same content as plain text) ───────────────────────────────────────────

    fun shareText(r: Review, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = ArrayList<String>()
        out.add(TITLE.uppercase())
        out.add("${r.span.label} · ${rangeText(r, zone)}")
        out.add("")
        if (r.isEmpty) out.add(EMPTY_WORDS)
        else for (s in r.stats) {
            out.add("${s.number}  ${s.words}")
            s.detail?.let { out.add("    $it") }
        }
        fun section(name: String, rows: List<Ranked>, unit: (Int) -> String) {
            if (rows.isEmpty()) return
            out.add("")
            out.add(name.uppercase())
            rows.forEachIndexed { i, row -> out.add("${i + 1}. ${row.name} — ${unit(row.count)}") }
        }
        section(TOP_AUTHORS_TITLE, r.topAuthors) { plural(it, "highlight", "highlights") }
        section(TOP_FEEDS_TITLE, r.topFeeds) { plural(it, "mark", "marks") }
        section(TOP_CORRESPONDENTS_TITLE, r.topCorrespondents) { plural(it, "letter", "letters") }
        return out.joinToString("\n")
    }

    /** "1 Jan – 11 Sep 2026" — or "up to 11 Sep 2026" for all time. */
    fun rangeText(r: Review, zone: ZoneId = ZoneId.systemDefault()): String {
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        val short = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        val end = Instant.ofEpochMilli(r.to - 1).atZone(zone).toLocalDate()
        if (r.span == Span.ALL) return "up to ${end.format(full)}"
        val start = Instant.ofEpochMilli(r.from).atZone(zone).toLocalDate()
        return "${start.format(short)} – ${end.format(full)}"
    }

    // ── Helpers (public so the test can pin the same words the iOS twin pins) ─────────────────

    fun plural(n: Int, one: String, many: String) = "$n ${noun(n, one, many)}"

    /** The bare noun for a count — the row's number already leads, so the words don't repeat it. */
    fun noun(n: Int, one: String, many: String) = if (n == 1) one else many

    /** Hours to one decimal, "0" when nothing — "12.5", "0.3", "0". */
    fun hoursText(ms: Long): String {
        if (ms <= 0) return "0"
        val tenths = (ms + 180_000) / 360_000        // round to the nearest 0.1 h
        return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    /** "9 pm", "12 noon", "12 midnight", "6 am". */
    fun hourText(h: Int): String = when {
        h == 0 -> "12 midnight"
        h == 12 -> "12 noon"
        h < 12 -> "$h am"
        else -> "${h - 12} pm"
    }

    /** Six lines — "morning    ▮▮▮▮▮▮▯▯▯▯" — ten cells, filled in proportion to the busiest block. */
    fun hourBars(blocks: List<Int>): String {
        val peak = maxOf(blocks.maxOrNull() ?: 0, 1)
        val width = HOUR_BLOCK_NAMES.maxOf { it.length }
        return HOUR_BLOCK_NAMES.zip(blocks).joinToString("\n") { (name, n) ->
            val filled = if (n == 0) 0 else maxOf(1, Math.round(n.toDouble() / peak * 10).toInt())
            name.padEnd(width) + "  " + "▮".repeat(filled) + "▯".repeat(10 - filled)
        }
    }

    /** The title a [ReadingState.id] (`folder/name.epub#size`) names. */
    fun bookTitle(id: String): String {
        var s = id.substringBeforeLast('#')
        s = s.substringAfterLast('/')
        val dot = s.lastIndexOf('.')
        if (dot > 0 && s.length - dot <= 6) s = s.substring(0, dot)
        return s
    }

    /** Top five, most first; ties by name so the order is stable across forks. */
    fun top(counts: Map<String, Int>, limit: Int = 5): List<Ranked> =
        counts.map { Ranked(it.key, it.value) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.name })
            .take(limit)

    /** The longest run of consecutive day ordinals. */
    fun longestRun(ordinals: Set<Long>): Int {
        var best = 0
        for (d in ordinals) {
            if (ordinals.contains(d - 1)) continue
            var n = 1
            while (ordinals.contains(d + n)) n++
            if (n > best) best = n
        }
        return best
    }

    private fun hourOfDay(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    /** One star per article per span: the URL when there is one, else the id with the `-pub`
     *  companion suffix folded away. */
    private fun starKey(e: ReadingEvent): String {
        val u = e.url
        if (!u.isNullOrEmpty()) return u
        return e.id.removeSuffix("-pub")
    }
}
