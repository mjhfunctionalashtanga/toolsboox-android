package com.toolsboox

import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.LedgerTaskCarryOver
import com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The duplicate task, from the real file it was found in.
 *
 * `day-2026-07-21-v2.json` held "get bent" twice — once as `li-…` typed on the day page, once as
 * `log-…` made from the reading log — and the day page painted a text box for each, one row under
 * the other. Every id-keyed check waved the pair through, because the id is the one thing the two
 * copies don't share. The fixtures below use those exact ids and words.
 */
class LedgerTaskDedupeTest {

    private fun task(id: String, text: String, done: Boolean = false, source: String? = null) =
        LedgerItem(
            id = id, kind = LedgerItem.Kind.TASK, text = text, date = Date(0),
            done = done, source = source
        )

    private fun event(id: String, text: String) =
        LedgerItem(id = id, kind = LedgerItem.Kind.EVENT, text = text, date = Date(0))

    // --- key ------------------------------------------------------------------------------------

    @Test
    fun `case and punctuation are not a difference`() {
        assertEquals(LedgerTaskDedupe.key("Get bent"), LedgerTaskDedupe.key("get bent"))
        assertEquals(LedgerTaskDedupe.key("Call Dad."), LedgerTaskDedupe.key("call dad"))
        assertEquals(LedgerTaskDedupe.key("email  Bob"), LedgerTaskDedupe.key("Email Bob!"))
    }

    @Test
    fun `different tasks that start alike stay different`() {
        assertTrue(LedgerTaskDedupe.key("call Dad") != LedgerTaskDedupe.key("call Dad's landlord"))
    }

    @Test
    fun `text with no letters at all has an empty key`() {
        assertEquals("", LedgerTaskDedupe.key("..... "))
    }

    // --- containsTask ---------------------------------------------------------------------------

    @Test
    fun `the real pair is recognised as already present`() {
        val existing = listOf(task("li-6de7f34c-e45f-44f4-a9e3-9a8fba0c4c19", "get bent", source = "manual"))
        assertTrue(LedgerTaskDedupe.containsTask(existing, "get bent"))
        assertFalse(LedgerTaskDedupe.containsTask(existing, "get unbent"))
    }

    @Test
    fun `an empty task never counts as already present`() {
        assertFalse(LedgerTaskDedupe.containsTask(listOf(task("a", "")), ""))
    }

    @Test
    fun `an event with the same words does not block a task`() {
        assertFalse(LedgerTaskDedupe.containsTask(listOf(event("e1", "yoga class")), "yoga class"))
    }

    // --- dedupe ---------------------------------------------------------------------------------

    @Test
    fun `the two ids collapse to one row and the first keeps its place`() {
        val items = listOf(
            task("li-6de7f34c-e45f-44f4-a9e3-9a8fba0c4c19", "get bent", source = "manual"),
            task("log-db63b34c-3a7f-48e8-aaf3-229a61f41c93", "get bent", source = "log")
        )
        val out = LedgerTaskDedupe.dedupe(items)
        assertEquals(1, out.size)
        assertEquals("li-6de7f34c-e45f-44f4-a9e3-9a8fba0c4c19", out[0].id)
    }

    @Test
    fun `two events with the same title are a normal week, not a mistake`() {
        val items = listOf(event("e1", "Yoga Class"), event("e2", "Yoga Class"))
        assertEquals(2, LedgerTaskDedupe.dedupe(items).size)
    }

    @Test
    fun `blank tasks are not folded into each other`() {
        // display=INK items carry their words in the ink, not in `text`; folding them on an empty
        // key would silently delete every handwritten task but the first.
        val items = listOf(task("a", ""), task("b", ""), task("c", ""))
        assertEquals(3, LedgerTaskDedupe.dedupe(items).size)
    }

    // Carry-over retired 2026-08-10 (Michael: "they aren't making the workflow better") — an
    // undone task stays on its own day and the page DRAWS what is still open instead of copying
    // it forward. Its dedupe contract lives on in OpenTasksTest.
}
