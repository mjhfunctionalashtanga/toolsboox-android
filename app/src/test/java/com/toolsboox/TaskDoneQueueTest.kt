package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.TaskDoneQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * THE TESTS THAT KEEP THE WIDGET OUT OF THE DAY FILES.
 *
 * The Task Checklist widget's ✓ used to load→mark→save the day file itself, on a bare Thread,
 * outside DayLocks — racing the app's per-pen-up saves of the same file through a SHARED temp
 * file name. That tore Michael's today-file ("day file exists but wouldn't load", live, on
 * device). The fix is a single-writer write-channel: the widget appends (date, itemId) marks
 * to a queue file, and the APP drains the queue through CalendarDayService under DayLocks.
 * These tests hold the drain's contract:
 *
 *  • marks apply the same semantics the in-place write had (done + same-day dedupe twins),
 *  • done is monotonic and drains are idempotent (a crash mid-drain re-applies, never un-does),
 *  • the read-only guard is honored — a day file that EXISTS but won't parse is never saved
 *    over (the fresh-day fallback would replace real content with an empty day), and its marks
 *    stay queued,
 *  • moot marks (no day file / task deleted) leave the queue instead of wedging it.
 *
 * See also [DayFileConcurrentSaveTest] for the torn-write regression itself.
 */
class TaskDoneQueueTest {

    private fun service(): CalendarDayService {
        val moshi = Moshi.Builder()
            .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
        return CalendarDayService().apply { this.moshi = moshi }
    }

    private fun task(text: String, done: Boolean = false) = LedgerItem(
        id = UUID.randomUUID().toString().lowercase(),
        kind = LedgerItem.Kind.TASK, text = text, date = Date(), done = done
    )

    private fun writeDay(svc: CalendarDayService, root: File, date: LocalDate, vararg items: LedgerItem): CalendarDay {
        val day = CalendarDay(date.year, date.monthValue, date.dayOfMonth, Locale.US, startHour = 7)
        day.ledgerItems.addAll(items)
        svc.save(root, date, day)
        return day
    }

    private fun tmp() = Files.createTempDirectory("tdq").toFile()

    @Test
    fun enqueueAndPendingRoundTrip() {
        val q = File(tmp(), "queue.txt")
        val d = LocalDate.of(2026, 8, 12)
        TaskDoneQueue.enqueueTo(q, d, "abc")
        TaskDoneQueue.enqueueTo(q, d.minusDays(3), "def")
        TaskDoneQueue.enqueueTo(q, d, "")            // blank id: refused at the door
        q.appendText("not a mark line\n")            // corruption: skipped, not fatal

        val pending = TaskDoneQueue.pendingIn(q)
        assertEquals(2, pending.size)
        assertEquals(TaskDoneQueue.Mark(d, "abc"), pending[0])
        assertEquals(TaskDoneQueue.Mark(d.minusDays(3), "def"), pending[1])
    }

    @Test
    fun drainMarksTaskAndItsSameDayTwinDone() {
        val root = tmp(); val q = File(tmp(), "queue.txt")
        val svc = service()
        val d = LocalDate.of(2026, 8, 12)
        val a = task("water the plants")
        val twin = task("Water the PLANTS")   // dedupe twin: same words, different case/copy
        val other = task("call the plumber")
        writeDay(svc, root, d, a, twin, other)

        TaskDoneQueue.enqueueTo(q, d, a.id)
        val changed = TaskDoneQueue.drainFile(q, svc, root)

        assertEquals(setOf(d), changed)
        val reloaded = svc.loadOrNull(root, d, Locale.US)!!
        assertTrue(reloaded.ledgerItems.first { it.id == a.id }.done)
        assertTrue("same-day twin checks off together", reloaded.ledgerItems.first { it.id == twin.id }.done)
        assertFalse("unrelated task untouched", reloaded.ledgerItems.first { it.id == other.id }.done)
        assertTrue("applied marks leave the queue", TaskDoneQueue.pendingIn(q).isEmpty())
    }

    @Test
    fun drainIsIdempotentAndNeverUndoes() {
        val root = tmp(); val q = File(tmp(), "queue.txt")
        val svc = service()
        val d = LocalDate.of(2026, 8, 12)
        val a = task("already done", done = true)
        writeDay(svc, root, d, a)

        // A stale widget tap on a task the app already checked: a no-op, not an un-do —
        // and no save, so nothing reports "changed".
        TaskDoneQueue.enqueueTo(q, d, a.id)
        val changed = TaskDoneQueue.drainFile(q, svc, root)
        assertTrue(changed.isEmpty())
        assertTrue(svc.loadOrNull(root, d, Locale.US)!!.ledgerItems.first().done)
        assertTrue(TaskDoneQueue.pendingIn(q).isEmpty())

        // Draining an empty queue is free.
        assertTrue(TaskDoneQueue.drainFile(q, svc, root).isEmpty())
    }

    @Test
    fun mootMarksAreDroppedNotWedged() {
        val root = tmp(); val q = File(tmp(), "queue.txt")
        val svc = service()
        val d = LocalDate.of(2026, 8, 12)
        writeDay(svc, root, d, task("something real"))

        TaskDoneQueue.enqueueTo(q, d, "no-such-task-id")          // task deleted since the tap
        TaskDoneQueue.enqueueTo(q, d.minusDays(9), "whatever")    // no day file at all
        val changed = TaskDoneQueue.drainFile(q, svc, root)

        assertTrue(changed.isEmpty())
        assertTrue("moot marks must not wedge the queue", TaskDoneQueue.pendingIn(q).isEmpty())
    }

    @Test
    fun unreadableDayFileIsNeverSavedOver() {
        val root = tmp(); val q = File(tmp(), "queue.txt")
        val svc = service()
        val d = LocalDate.of(2026, 8, 12)
        // The exact on-device failure: a file that EXISTS but won't load. The old load()
        // fallback would hand back a fresh empty day — saving that would replace whatever the
        // corrupt file still holds (maybe repairable, maybe recoverable from sync) with one
        // done flag on an empty page.
        val dir = File(root, "calendar/2026/08").apply { mkdirs() }
        val corrupt = File(dir, "day-2026-08-12-v2.json").apply { writeText("{ torn mid-write") }

        TaskDoneQueue.enqueueTo(q, d, "some-task")
        val changed = TaskDoneQueue.drainFile(q, svc, root)

        assertTrue(changed.isEmpty())
        assertEquals("{ torn mid-write", corrupt.readText())
        assertEquals("marks wait for a readable day", 1, TaskDoneQueue.pendingIn(q).size)
    }
}

/**
 * The torn-write regression, at the seam it happened: [CalendarDayService.save] used ONE fixed
 * temp name per day file, so two concurrent saves interleaved bytes in the same temp inode —
 * and the first ATOMIC_MOVE renamed that inode into place while the second writer's still-open
 * FileWriter kept writing INTO THE INSTALLED FILE (an fd follows its inode through a rename).
 * save() now takes a process-wide per-target lock and a unique temp name per attempt: hammer it
 * from two threads and the surviving file must always parse.
 */
class DayFileConcurrentSaveTest {

    @Test
    fun concurrentSavesNeverTearTheDayFile() {
        val moshi = Moshi.Builder()
            .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
        val svc = CalendarDayService().apply { this.moshi = moshi }
        val root = Files.createTempDirectory("tear").toFile()
        val d = LocalDate.of(2026, 8, 12)

        // Big enough that a write takes real time (the tear needs an in-flight writer).
        fun day(tag: String): CalendarDay {
            val day = CalendarDay(2026, 8, 12, Locale.US, startHour = 7)
            repeat(200) { i ->
                day.ledgerItems.add(LedgerItem(
                    id = "$tag-$i", kind = LedgerItem.Kind.TASK,
                    text = "task $tag $i " + "x".repeat(300), date = Date()))
            }
            return day
        }

        val boom = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val threads = (0 until 2).map { t ->
            Thread {
                runCatching { repeat(15) { svc.save(root, d, day("w$t")) } }
                    .onFailure { boom.set(it) }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        boom.get()?.let { throw it }

        val reloaded = svc.loadOrNull(root, d, Locale.US)
        assertNotNull("the installed day file must always parse", reloaded)
        assertEquals(200, reloaded!!.ledgerItems.size)

        // And no temp leavings: every unique .tmp either moved into place or was swept.
        val strays = File(root, "calendar/2026/08").listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue("no stranded temp files", strays.isNullOrEmpty())
    }
}
