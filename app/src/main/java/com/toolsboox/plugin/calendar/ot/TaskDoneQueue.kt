package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The write-channel between the Task Checklist widget's ✓ and the day files — the fix for a live
 * data-corruption bug, so the WHY gets written down in full.
 *
 * WHAT HAPPENED. The widget checkbox's receiver (TaskDoneReceiver, added in 1.06.56) did its own
 * load→mark→save of the day file on a bare Thread, outside [DayLocks] — while the app saves the
 * same file from its presenters (pen-up saves land per stroke). Everything is ONE process (no
 * android:process anywhere in the manifest), but nothing serialized those two writers, and
 * [CalendarDayService.save] wrote every save through the SAME temp file name
 * (`day-…-v2.json.tmp`). Two concurrent saves therefore interleaved bytes in one temp file —
 * and worse: the first writer's ATOMIC_MOVE renames the temp to the real name while the second
 * writer's FileWriter still holds an open fd on that inode, so the second writer keeps writing
 * INTO THE RENAMED TARGET. The "atomic" day file ends up torn. That is how a today-file got into
 * the "day file exists but wouldn't load" state on one of Michael's devices.
 *
 * THE RULE, from here on: DAY FILES HAVE ONE WRITER — the app's surfaces and services, always
 * inside [DayLocks.withDay]. The widget flow (receivers, RemoteViewsFactory binder threads,
 * WidgetRefreshWorker) never writes a day file. It writes HERE instead: the ✓ appends a
 * (date, itemId) mark to this app-private queue file, refreshes its own face optimistically
 * (the checklist factory hides pending rows), and pokes the app. The app consumes the queue —
 * immediately when it is alive and resumed (MainActivity registers [onEnqueued]), and on the
 * next foreground otherwise. This is the iOS pattern: append to a queue the app drains, plus an
 * in-process nudge for when the app is already up.
 *
 * Marks are idempotent (done is monotonic — only ever set, never cleared), so a double drain or
 * a drain racing a page reload is harmless; the queue entry is only removed once its day file
 * save succeeded, so a crash mid-drain re-applies rather than loses.
 */
object TaskDoneQueue {

    /** Guards the queue file only. Day-file work happens under [DayLocks], never under this. */
    private val queueLock = Any()

    /** Serializes whole drains — two triggers (onResume + a poke) must not double-apply saves. */
    private val drainLock = Any()

    private const val FILE_NAME = "widget-done-queue.txt"

    /** One requested done-mark: the day whose file owns the task, and the task's id there. */
    data class Mark(val date: LocalDate, val itemId: String)

    /**
     * The in-process nudge. MainActivity sets this while it is resumed (and clears it on pause):
     * a widget tap then drains within a breath instead of waiting for the next foreground. A
     * plain volatile callback, not a broadcast — the receiver and the activity share a process,
     * so the "broadcast the app handles when alive" of the iOS design degenerates to this.
     */
    @Volatile
    var onEnqueued: (() -> Unit)? = null

    private fun queueFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /**
     * Append a mark (widget side). Never touches a day file. Returns quietly on any I/O trouble —
     * a failed enqueue costs one tap, never a day file.
     */
    fun enqueue(context: Context, date: LocalDate, itemId: String) {
        enqueueTo(queueFile(context), date, itemId)
        // Nudge the app if it is up; if not, the marks wait for the next foreground drain.
        runCatching { onEnqueued?.invoke() }
    }

    /** The queued marks, oldest first. Unparseable lines are dropped on the next rewrite. */
    fun pending(context: Context): List<Mark> = pendingIn(queueFile(context))

    // The File-addressed core — internal so the JVM unit tests (no Context on that bench) can
    // exercise the real logic against a temp dir, not a mock of it.

    internal fun enqueueTo(file: File, date: LocalDate, itemId: String) {
        if (itemId.isBlank()) return
        runCatching {
            synchronized(queueLock) {
                file.appendText("$date|$itemId\n")
            }
        }.onFailure { Timber.w(it, "TaskDoneQueue enqueue failed") }
    }

    internal fun pendingIn(file: File): List<Mark> = runCatching {
        synchronized(queueLock) { pendingUnlocked(file) }
    }.getOrDefault(emptyList())

    /** Remove the given marks from the queue file (after their save succeeded, or they proved moot). */
    private fun removeFrom(file: File, applied: Collection<Mark>) {
        if (applied.isEmpty()) return
        val gone = applied.toSet()
        runCatching {
            synchronized(queueLock) {
                if (!file.exists()) return
                val left = pendingUnlocked(file).filterNot { it in gone }
                if (left.isEmpty()) file.delete()
                else file.writeText(left.joinToString("\n") { "${it.date}|${it.itemId}" } + "\n")
            }
        }.onFailure { Timber.w(it, "TaskDoneQueue remove failed") }
    }

    /** [pendingIn] without taking the lock — for callers already inside [queueLock]. */
    private fun pendingUnlocked(f: File): List<Mark> =
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { line ->
            val bar = line.indexOf('|')
            if (bar <= 0) return@mapNotNull null
            val date = runCatching { LocalDate.parse(line.substring(0, bar)) }.getOrNull()
                ?: return@mapNotNull null
            val id = line.substring(bar + 1).trim()
            if (id.isEmpty()) null else Mark(date, id)
        }

    /**
     * Consume the queue — THE ONLY PLACE WIDGET DONE-MARKS BECOME DAY-FILE WRITES, and it runs
     * in the app's normal writer discipline: every load→mark→save cycle inside [DayLocks.withDay],
     * off the main thread (callers dispatch to IO).
     *
     * Per mark, the same semantics the receiver's in-place write had:
     *  • done is set on the named task AND its same-day dedupe twins (a typed copy and a
     *    reading-log copy of one task check off together, or the unchecked twin resurfaces);
     *  • done is MONOTONIC — this only ever sets it, so a stale mark can never un-do a task.
     *
     * The read-only guard is honored here too: a day whose file EXISTS but wouldn't load
     * ([CalendarDayService.loadOrNull] null while [CalendarDayService.exists] true) is never
     * saved over — [CalendarDayService.load]'s fresh-day fallback would replace the real
     * (unreadable, maybe repairable) content with an empty day carrying one done flag. Those
     * marks stay queued for a later drain. A mark whose day file doesn't exist at all, or whose
     * task id is gone (deleted), is moot and dropped.
     *
     * @return the dates whose files actually changed — so the caller can tell an open day page
     *   to re-read (re-read, never save: the page's in-memory copy is the stale one now).
     */
    fun drain(context: Context, service: CalendarDayService, rootPath: File): Set<LocalDate> =
        drainFile(queueFile(context), service, rootPath)

    internal fun drainFile(queue: File, service: CalendarDayService, rootPath: File): Set<LocalDate> {
        synchronized(drainLock) {
            val marks = pendingIn(queue)
            if (marks.isEmpty()) return emptySet()

            val changedDates = mutableSetOf<LocalDate>()
            val done = mutableListOf<Mark>()

            for ((date, dayMarks) in marks.groupBy { it.date }) {
                // Marks join the global remove-list ONLY after their day's block ran to completion
                // — a save that throws must leave its marks queued for the next drain, not
                // half-consumed (the crash-mid-drain re-applies; idempotence makes that free).
                val dayDone = mutableListOf<Mark>()
                runCatching {
                    DayLocks.withDay(date) {
                        if (!service.exists(rootPath, date)) {
                            // No file — the tasks these marks name cannot exist. Moot.
                            dayDone.addAll(dayMarks)
                            return@withDay
                        }
                        val day = service.loadOrNull(rootPath, date, Locale.getDefault())
                        if (day == null) {
                            // Exists but wouldn't load: the presenter's read-only guard case.
                            // Saving would destroy the real content. Keep the marks and wait.
                            Timber.w("TaskDoneQueue: day file for $date unreadable; keeping ${dayMarks.size} mark(s)")
                            return@withDay
                        }
                        var changed = false
                        for (mark in dayMarks) {
                            val target = day.ledgerItems.firstOrNull { it.id == mark.itemId }
                            if (target == null) {
                                dayDone.add(mark)   // task deleted since the tap — moot
                                continue
                            }
                            val key = LedgerTaskDedupe.key(target.text)
                            for (li in day.ledgerItems) {
                                if (li.kind == LedgerItem.Kind.TASK && !li.done &&
                                    (li.id == mark.itemId ||
                                        (key.isNotEmpty() && LedgerTaskDedupe.key(li.text) == key))
                                ) {
                                    li.done = true; changed = true
                                }
                            }
                            dayDone.add(mark)   // applied (or already done — idempotent either way)
                        }
                        if (changed) {
                            service.save(rootPath, date, day)
                            changedDates.add(date)
                        }
                    }
                    done.addAll(dayDone)
                }.onFailure { Timber.w(it, "TaskDoneQueue drain failed for $date; marks kept") }
            }

            removeFrom(queue, done)
            return changedDates
        }
    }
}
