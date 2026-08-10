package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.OpenTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Date

/**
 * The successor to carry-over's contract: "still open" is a QUESTION the view asks, and this is
 * the answer's rules — undone only, one copy of a task said twice, nothing the hand has already
 * written today, newest day first, done left in history.
 */
class OpenTasksTest {

    private val service = CalendarDayService().apply {
        moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.toolsboox.ot.LocaleJsonAdapter())
            .add(com.toolsboox.ot.DateJsonAdapter())
            .add(com.toolsboox.ot.UUIDJsonAdapter())
            .build()
    }

    private fun task(id: String, text: String, done: Boolean = false) = LedgerItem(
        id = id, kind = LedgerItem.Kind.TASK, text = text, date = Date(0L)
    ).apply { this.done = done }

    private fun root(): File = File(
        System.getProperty("java.io.tmpdir"), "open-tasks-${System.nanoTime()}"
    ).apply { mkdirs() }

    private fun save(root: File, date: LocalDate, items: List<LedgerItem>) {
        val day = CalendarDay(date.year, date.monthValue, date.dayOfMonth, startHour = null)
        day.ledgerItems.addAll(items)
        service.save(root, date, day)
    }

    @Test
    fun `undone from earlier days surfaces, done stays history`() {
        val root = root()
        val today = LocalDate.of(2026, 8, 10)
        save(root, today.minusDays(1), listOf(
            task("a", "call Dad"), task("b", "get bent", done = true)
        ))
        OpenTasks.invalidate()
        val open = OpenTasks.fresh(service, root, today, emptyList())
        assertEquals(listOf("call Dad"), open.map { it.item.text })
        assertEquals(today.minusDays(1), open[0].sourceDay)
    }

    @Test
    fun `a task said twice across days surfaces once, newest day first`() {
        val root = root()
        val today = LocalDate.of(2026, 8, 10)
        save(root, today.minusDays(3), listOf(task("old", "get bent"), task("c", "email Bob")))
        save(root, today.minusDays(1), listOf(task("new", "Get bent")))
        OpenTasks.invalidate()
        val open = OpenTasks.fresh(service, root, today, emptyList())
        assertEquals(listOf("Get bent", "email Bob"), open.map { it.item.text })
        assertEquals("new", open[0].item.id)
    }

    @Test
    fun `a task already written by hand today does not ghost`() {
        val root = root()
        val today = LocalDate.of(2026, 8, 10)
        save(root, today.minusDays(1), listOf(task("y", "get bent"), task("z", "call Dad")))
        OpenTasks.invalidate()
        val open = OpenTasks.fresh(
            service, root, today,
            todayItems = listOf(task("t", "Get Bent"))
        )
        assertEquals(listOf("call Dad"), open.map { it.item.text })
    }

    @Test
    fun `the parked answer is served for the day it was earned and no other`() {
        val root = root()
        val today = LocalDate.of(2026, 8, 10)
        save(root, today.minusDays(1), listOf(task("a", "call Dad")))
        OpenTasks.invalidate()
        OpenTasks.fresh(service, root, today, emptyList())
        assertEquals(1, OpenTasks.cachedFor(today)?.size)
        assertTrue(OpenTasks.cachedFor(today.plusDays(1)) == null)
        OpenTasks.invalidate()
        assertTrue(OpenTasks.cachedFor(today) == null)
    }
}
