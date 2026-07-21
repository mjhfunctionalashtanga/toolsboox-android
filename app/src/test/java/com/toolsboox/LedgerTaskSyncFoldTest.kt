package com.toolsboox

import com.toolsboox.plugin.calendar.nw.LedgerTaskSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Line folding for the VTODO we push to CalDAV.
 *
 * The reason this is worth a test: an OCR'd task is a whole handwritten sentence, so it runs past
 * the 75-octet line limit constantly, and an over-long line makes the whole object invalid —
 * strict servers reject it and lenient ones truncate the words. Either way the failure is silent
 * and lands on the far side, where you would only notice it as a task that never arrived.
 *
 * The 75 is OCTETS, not characters, which is the part that is easy to get wrong the moment
 * someone writes an em-dash or an accent.
 */
class LedgerTaskSyncFoldTest {

    private fun lines(folded: String) = folded.removeSuffix("\r\n").split("\r\n")

    @Test
    fun `a short line is returned whole`() {
        assertEquals("SUMMARY:milk\r\n", LedgerTaskSync.fold("SUMMARY:milk"))
    }

    @Test
    fun `a line of exactly 75 octets is not folded`() {
        val line = "S:" + "a".repeat(73)
        assertEquals(75, line.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, lines(LedgerTaskSync.fold(line)).size)
    }

    @Test
    fun `a 76 octet line folds into two`() {
        val line = "S:" + "a".repeat(74)
        val out = lines(LedgerTaskSync.fold(line))
        assertEquals(2, out.size)
        assertTrue("continuation must start with one space", out[1].startsWith(" "))
    }

    @Test
    fun `no emitted line exceeds 75 octets`() {
        val line = "SUMMARY:" + "ashtanga vinyasa krama ".repeat(30)
        for (l in lines(LedgerTaskSync.fold(line))) {
            assertTrue("line too long: ${l.toByteArray(Charsets.UTF_8).size}",
                l.toByteArray(Charsets.UTF_8).size <= 75)
        }
    }

    @Test
    fun `unfolding restores the original`() {
        val line = "SUMMARY:" + "call Dad about the thing on Friday ".repeat(9)
        val out = lines(LedgerTaskSync.fold(line))
        // RFC 5545 unfolding: drop CRLF + the single leading space of each continuation.
        val rejoined = out.first() + out.drop(1).joinToString("") { it.removePrefix(" ") }
        assertEquals(line, rejoined)
    }

    @Test
    fun `multi-byte characters are never split across a fold`() {
        // Em-dashes are three octets each; a naive 75-CHARACTER fold cuts one in half and the
        // line arrives as replacement characters.
        val line = "SUMMARY:" + "—".repeat(60)
        val out = lines(LedgerTaskSync.fold(line))
        for (l in out) assertTrue("lost a character to the fold", !l.contains('�'))
        val rejoined = out.first() + out.drop(1).joinToString("") { it.removePrefix(" ") }
        assertEquals(line, rejoined)
    }
}
