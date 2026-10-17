package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * A LOG LINE CHANGED IN PLACE, OR TAKEN OFF — on the day it lives on, under that day's lock.
 *
 * Michael 2026-10-12, of the article highlight: "It goes in the day it was picked's log." A note
 * added to a highlight days later is not a new line on today; it is the SAME line, on the day you
 * highlighted, with the note on it. So a line is found by id on its own day (± a day for a clock
 * that crossed midnight in another time zone — design critique #14, not the old 800-day scan),
 * changed, and stamped [ReadingEvent.updatedAt], which is what lets the day merge keep the edit
 * (critique #2). A removal tombstones the id in `deletedElementIds` — the marker the merger honours
 * for lines now — so the other device's copy can never hand it back to search, the corpus, Your Year
 * or the Journal.
 *
 * Every write is load → mutate → save under [DayLocks.withDay]: the open day page saves per pen-up,
 * and an unlocked writer drops its items.
 */
object ReadingEventEdit {

    /** The days a line made on [day] might sit on, nearest first. */
    fun candidateDays(day: LocalDate): List<LocalDate> = listOf(day, day.minusDays(1), day.plusDays(1))

    /**
     * Change line [id] on [day] (or a neighbour) with [change]; the result is stamped updatedAt.
     * Returns the day it was found on, or null when no such line exists there.
     *
     * A DAY THAT WON'T READ IS NOT A DAY WITHOUT THE LINE (day-file guard): each candidate goes
     * through [CalendarDayService.loadToEdit], and when the line was found on none of them but
     * [day] itself — the line's own day — is there and won't read, the line is most likely in it,
     * so this throws that day's [CalendarDayService.UnreadableDayException] (the caller says
     * "Couldn't … — <page> won't read") instead of answering "not here". A neighbour that won't read
     * is skipped, as before. Nothing is written over an unreadable day either way.
     */
    fun update(
        service: CalendarDayService, root: File, day: LocalDate, id: String,
        /** More to write on the day the line is found, in the same locked save (an attachment taken
         *  off a pick tombstones its id there — add-to-pick pin §1). */
        alsoOnDay: ((CalendarDay) -> Unit)? = null,
        change: (ReadingEvent) -> ReadingEvent
    ): LocalDate? {
        var refused: CalendarDayService.UnreadableDayException? = null
        for (d in candidateDays(day)) {
            val hit = DayLocks.withDay(d) {
                val cal = try { service.loadToEdit(root, d) } catch (e: CalendarDayService.UnreadableDayException) {
                    if (d == day) refused = e
                    null
                } ?: return@withDay false
                val i = cal.readingEvents.indexOfFirst { it.id == id }
                if (i < 0) return@withDay false
                cal.readingEvents[i] = change(cal.readingEvents[i]).copy(updatedAt = Date())
                alsoOnDay?.invoke(cal)
                service.save(root, d, cal)
                true
            }
            if (hit) return d
        }
        refused?.let { throw it }
        return null
    }

    /**
     * The day to WRITE onto: today through the writer's door ([CalendarDayService.loadForWriteOrThrow]
     * — a fresh today is how a day begins), any other day only as it already stands on disk
     * ([CalendarDayService.loadToEdit]) — null when its file is missing (review 2026-10-12 #2). A
     * past day minted fresh here became the "newer" side of the next merge, and the merge takes the
     * newer side's day-level fields wholesale (its start hour — the archive damage
     * [CalendarDayService.loadOrNull] documents).
     *
     * A file that exists but won't read (the 84 MB photo day) THROWS
     * [CalendarDayService.UnreadableDayException] — today or not — so the caller can say which page
     * won't read; it is never overwritten with an empty one.
     */
    fun loadForWriteOrThrow(service: CalendarDayService, root: File, day: LocalDate): CalendarDay? =
        if (day == LocalDate.now()) service.loadForWriteOrThrow(root, day, null, Locale.getDefault())
        else service.loadToEdit(root, day)

    /** [loadForWriteOrThrow] with the refusal as a null too, for a caller that only needs "not written". */
    fun loadForWrite(service: CalendarDayService, root: File, day: LocalDate): CalendarDay? =
        try { loadForWriteOrThrow(service, root, day) } catch (e: CalendarDayService.UnreadableDayException) { null }

    /** Add [event] to [day] if no line with its id is there yet, stamped updatedAt. False when a
     *  past day isn't on this device ([loadForWriteOrThrow]) — nothing was saved; THROWS
     *  [CalendarDayService.UnreadableDayException] when the day is there but won't read. */
    fun addIfAbsent(service: CalendarDayService, root: File, day: LocalDate, event: ReadingEvent,
                    alsoOnDay: ((CalendarDay) -> Unit)? = null): Boolean =
        DayLocks.withDay(day) {
            val cal = loadForWriteOrThrow(service, root, day) ?: return@withDay false
            if (cal.readingEvents.none { it.id == event.id }) cal.readingEvents.add(event.copy(updatedAt = Date()))
            alsoOnDay?.invoke(cal)
            service.save(root, day, cal)
            true
        }

    /** The line [id] as it stands on [day] (or a neighbour), with the day it was found on. */
    fun find(service: CalendarDayService, root: File, day: LocalDate, id: String): Pair<LocalDate, ReadingEvent>? {
        for (d in candidateDays(day)) {
            val cal = service.loadOrNull(root, d, Locale.getDefault()) ?: continue
            cal.readingEvents.firstOrNull { it.id == id }?.let { return d to it }
        }
        return null
    }

    /**
     * Take line [id] off [day] (or a neighbour) and tombstone it. [alsoOnDay] runs inside the same
     * lock and save on the day the line was found (the highlight's pick goes in the same write).
     * True when a line was removed.
     *
     * NOT HERE IS NOT GONE (review 2026-10-12 #1): a highlight made on the iPhone and removed on the
     * B6 before its day synced has no line in this copy — and returning quietly left it live
     * everywhere, the next day merge handing it back to the Log, search, the corpus and Your Year.
     * So when no copy here holds it, the removal is RECORDED on [day] all the same ([recordRemoval]),
     * where the merge will meet the line; [alsoOnDay] runs there too. When [day] is there but won't
     * read, nothing is written and [CalendarDayService.UnreadableDayException] is thrown.
     */
    fun remove(service: CalendarDayService, root: File, day: LocalDate, id: String,
               alsoOnDay: ((CalendarDay) -> Unit)? = null): Boolean {
        // The line's own day that won't read most likely holds it: found on no readable copy, the
        // removal is refused with that day's words ([recordRemoval] on [day] throws them) rather
        // than recorded as if the line weren't there. A neighbour that won't read is skipped.
        var refused: CalendarDayService.UnreadableDayException? = null
        for (d in candidateDays(day)) {
            val hit = DayLocks.withDay(d) {
                val cal = try { service.loadToEdit(root, d) } catch (e: CalendarDayService.UnreadableDayException) {
                    if (d == day) refused = e
                    null
                } ?: return@withDay false
                if (cal.readingEvents.none { it.id == id }) return@withDay false
                cal.readingEvents.removeAll { it.id == id }
                tombstone(cal, id)
                alsoOnDay?.invoke(cal)
                service.save(root, d, cal)
                true
            }
            if (hit) return true
        }
        refused?.let { throw it }
        recordRemoval(service, root, day, listOf(id), alsoOnDay)
        return false
    }

    /**
     * Record that [ids] (Log lines, cards) are removed, on [day], whether or not this device's copy
     * of the day holds them: the day as it stands gets the markers (and [alsoOnDay]); a day this
     * device has never held gets a REMOVAL CARRIER ([CalendarDayService.saveRemovalCarrier] — the
     * markers alone, deferring to every real copy of the day); a file that exists but won't read is
     * left alone (never overwritten — it is someone's day) and THROWS
     * [CalendarDayService.UnreadableDayException], so the caller says which page won't read.
     * True when the markers were written.
     */
    fun recordRemoval(service: CalendarDayService, root: File, day: LocalDate, ids: Collection<String>,
                      alsoOnDay: ((CalendarDay) -> Unit)? = null): Boolean {
        val live = ids.filter { it.isNotBlank() }
        if (live.isEmpty()) return false
        return DayLocks.withDay(day) {
            val cal = service.loadToEdit(root, day)
            if (cal != null) {
                val dead = live.map { it.lowercase() }.toSet()
                cal.readingEvents.removeAll { it.id.lowercase() in dead }
                cal.imageElements.removeAll { it.elementId.toString().lowercase() in dead }
                live.forEach { tombstone(cal, it) }
                alsoOnDay?.invoke(cal)
                service.save(root, day, cal)
            } else service.saveRemovalCarrier(root, day, live)
            true
        }
    }

    /** Record [id]'s removal where the merger looks (lines share the element markers). */
    fun tombstone(day: CalendarDay, id: String) {
        if (day.deletedElementIds.none { it.equals(id, ignoreCase = true) }) day.deletedElementIds.add(id)
    }

    /** Record that attachment [id] came off a line on [day] — `att:<id>`, the form both apps write
     *  and read ([com.toolsboox.plugin.calendar.fi.CalendarDayMerger.attachmentTombstone]). */
    fun tombstoneAttachment(day: CalendarDay, id: String) =
        tombstone(day, com.toolsboox.plugin.calendar.fi.CalendarDayMerger.attachmentTombstone(id))
}
