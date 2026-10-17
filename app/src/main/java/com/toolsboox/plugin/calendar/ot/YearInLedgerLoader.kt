package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.reader.ui.BookOpens
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Gathers the year review's inputs — OFF the main thread; a year is a few hundred day files — and
 * keeps one computed [LedgerYear.Review] per span for the session. Nothing is written anywhere.
 * The numbers themselves come from [LedgerYear], the engine both forks share.
 */
object YearInLedgerLoader {
    private val cache = ConcurrentHashMap<LedgerYear.Span, LedgerYear.Review>()

    fun cached(span: LedgerYear.Span): LedgerYear.Review? = cache[span]

    /** Forget the session's reviews (a surface that just logged something can ask for a recount). */
    fun invalidate() = cache.clear()

    /** Call OFF the main thread. */
    fun review(
        context: Context, service: CalendarDayService, root: File, span: LedgerYear.Span,
    ): LedgerYear.Review {
        cache[span]?.let { return it }
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val (from, to) = span.bounds(today, zone)
        val app = context.applicationContext

        // The inbox store is memory-resident; warm is the "read the last download if memory is
        // cold" step and is safe off-main by design. Sent rows are marked by id on both forks.
        runCatching { InboxStore.warm(app) }
        val letters = runCatching { InboxStore.messages(app) }.getOrDefault(emptyList()).map { m ->
            val sent = InboxStore.isSent(m.id)
            LedgerYear.Letter(
                name = if (sent) m.toName else m.fromName,
                email = if (sent) m.toEmail else m.fromEmail,
                date = m.date, sent = sent,
            )
        }

        val input = LedgerYear.Input(
            days = days(service, root, from, to, zone),
            listen = ListenStateStore.loadAll(app),
            sessions = ReadingSessionStore.loadAll(app),
            mediaLog = MediaLogStore.loadAll(app),
            readingStates = ReadingStateStore.loadAll(app),
            bookOpens = BookOpens.all(app).map { LedgerYear.BookOpen(it.key, it.value) },
            letters = letters,
        )
        return LedgerYear.review(input, span, today, zone).also { cache[span] = it }
    }

    /**
     * The day files whose date falls inside `[from, to)` — the same walk the reading log makes
     * (`day-YYYY-MM-DD-v2.json` under `calendar/`), so nothing is missed and nothing is guessed.
     * A file that won't load is skipped, not fatal: one bad day must not blank the year.
     */
    fun days(service: CalendarDayService, root: File, from: Long, to: Long, zone: ZoneId): List<CalendarDay> {
        val calendarRoot = File(root, "calendar")
        if (!calendarRoot.exists()) return emptyList()
        val fromDay = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val toDay = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val rx = Regex("""^day-(\d{4})-(\d{2})-(\d{2})-v2\.json$""")
        val out = ArrayList<CalendarDay>()
        calendarRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val m = rx.find(file.name) ?: return@forEach
                val date = runCatching {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull() ?: return@forEach
                if (date.isBefore(fromDay) || !date.isBefore(toDay)) return@forEach
                val day = runCatching { service.load(file) }
                    .onFailure { Timber.w(it, "year-in-ledger: skipping ${file.name}") }
                    .getOrNull() ?: return@forEach
                out.add(day)
            }
        return out
    }
}
