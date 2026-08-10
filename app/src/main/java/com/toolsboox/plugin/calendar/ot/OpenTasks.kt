package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.time.LocalDate

/**
 * What is still OPEN from the days before — the answer the today page's ghost rows draw.
 *
 * This replaces task carry-over's data copies: an undone task no longer gets re-minted into each
 * new day file (plus a typed text box stamped onto the page, linked back by matching words). The
 * task stays where the hand made it, and "still open" is a QUESTION the view asks — computed
 * here, drawn as render-only rows, never persisted. One task, one record, one day file.
 *
 * Same parking discipline as QuickWinsEngine: the render path reads [cachedFor] and never walks
 * the disk; [fresh] re-earns the answer in the background and the page repaints only when it
 * actually moved.
 */
object OpenTasks {

    /** One still-open task and the day whose file holds it. */
    data class Open(val item: LedgerItem, val sourceDay: LocalDate)

    /** How far back "still open" reaches. Beyond this a task is history, not a nudge — and the
     *  walk stays bounded on a ledger with years of days. */
    private const val LOOKBACK_DAYS = 30L

    /** More rows than the Tasks section could ever have free. */
    private const val MAX_OPEN = 12

    @Volatile private var parkedDay: LocalDate? = null
    @Volatile private var parked: List<Open> = emptyList()

    /** The parked answer for [day], or null when nothing has been earned yet today. */
    fun cachedFor(day: LocalDate): List<Open>? = if (parkedDay == day) parked else null

    /**
     * Walk the lookback window's day files (slim decode — no stroke arrays) and collect undone
     * TASKs, newest first. Tasks already said on [day] itself (by id or by words) are excluded:
     * the page must not ghost a row the hand has already written. Blocking; call from IO.
     */
    fun fresh(
        dayService: CalendarDayService,
        root: File,
        day: LocalDate,
        todayItems: List<LedgerItem>,
    ): List<Open> {
        val saidToday = HashSet<String>()
        val idsToday = HashSet<String>()
        todayItems.filter { it.kind == LedgerItem.Kind.TASK }.forEach {
            idsToday.add(it.id)
            saidToday.add(LedgerTaskDedupe.key(it.text))
        }
        val out = ArrayList<Open>()
        var d = day.minusDays(1)
        val floor = day.minusDays(LOOKBACK_DAYS)
        while (!d.isBefore(floor) && out.size < MAX_OPEN) {
            val y = "%04d".format(d.year); val m = "%02d".format(d.monthValue); val dd = "%02d".format(d.dayOfMonth)
            val file = File(File(root, "calendar/$y/$m"), "day-$y-$m-$dd-v2.json")
            if (file.exists()) {
                val items = runCatching { dayService.loadLedgerItems(file) }.getOrNull().orEmpty()
                for (it in items) {
                    if (it.kind != LedgerItem.Kind.TASK || it.done || it.text.isBlank()) continue
                    if (it.id in idsToday) continue
                    if (!saidToday.add(LedgerTaskDedupe.key(it.text))) continue
                    out.add(Open(it, d))
                    if (out.size >= MAX_OPEN) break
                }
            }
            d = d.minusDays(1)
        }
        parkedDay = day
        parked = out
        return out
    }

    /** Forget the parked answer — a task was just marked done from a ghost row. */
    fun invalidate() {
        parkedDay = null
        parked = emptyList()
    }
}
