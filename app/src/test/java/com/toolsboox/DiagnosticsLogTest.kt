package com.toolsboox

import com.toolsboox.ot.DiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The diagnostics ring and the report shapes, pinned.
 *
 * These exist because the report is what the owner pastes into a support thread from a device
 * with no adb: if the crash file's first two lines drift, Settings › Diagnostics stops being
 * able to say "Last crash: <time> · <cause>", and the ring's filter is the difference between
 * evidence and noise.
 */
class DiagnosticsLogTest {

    private val utc = ZoneOffset.UTC
    private val t0 = 1_757_547_662_123L   // 2025-09-10T23:41:02.123Z

    @Test
    fun `the ring keeps the newest lines and drops the oldest`() {
        val ring = DiagnosticsLog(capacity = 5)
        (1..8).forEach { ring.add("line $it") }
        assertEquals(5, ring.size())
        assertEquals(listOf("line 4", "line 5", "line 6", "line 7", "line 8"), ring.tail())
        assertEquals(listOf("line 7", "line 8"), ring.tail(2))
        ring.clear()
        assertEquals(0, ring.size())
        assertEquals("", ring.dump())
    }

    @Test
    fun `warnings and errors are always kept, debug never, info only from the field rooms`() {
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.WARN, "Anything", "whatever"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.ERROR, null, "whatever"))
        assertFalse(DiagnosticsLog.keeps(DiagnosticsLog.DEBUG, "MailSync", "chatter"))
        assertFalse(DiagnosticsLog.keeps(DiagnosticsLog.VERBOSE, "MailSync", "chatter"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "MailSync", "fetched 12"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "UltrabridgeSyncWorker", "pushed"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "Audiobookshelf", "progress"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "FeedFetchWorker", "12 new"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "SomeFragment", "abs: token refreshed"))
        assertTrue(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "NotesHarvest", "Notes harvest · 2026-10-11/0 · read 1 of 4 bands · 812 ms"))
        assertFalse(DiagnosticsLog.keeps(DiagnosticsLog.INFO, "MainActivity", "Navigate to today"))
    }

    @Test
    fun `a ring line is time, level, tag, message and the throwable's first frames`() {
        val plain = DiagnosticsLog.formatLine(t0, DiagnosticsLog.WARN, "MailSync", "IMAP timed out  ", null, utc)
        assertEquals("23:41:02.123 W/MailSync: IMAP timed out", plain)

        val withThrowable = DiagnosticsLog.formatLine(t0, DiagnosticsLog.ERROR, null, "boom",
            IllegalStateException("bad state"), utc)
        val lines = withThrowable.lines()
        assertEquals("23:41:02.123 E/Ledgable: boom", lines[0])
        assertEquals("java.lang.IllegalStateException: bad state", lines[1].trim())
        assertTrue("frames ride along", lines.size in 3..8)
    }

    @Test
    fun `a crash file names the moment on line one and the cause on line two, and reads back`() {
        val text = DiagnosticsLog.formatCrash(
            epochMillis = t0,
            firstLine = "java.lang.NullPointerException: no page\nsecond line ignored",
            appLine = "Ledgable 1.06.212 (212) prodStandard/debug",
            deviceLine = "Bigme B6 · Android 11 (API 30)",
            thread = "main",
            stack = "java.lang.NullPointerException: no page\n\tat com.toolsboox.Foo.bar(Foo.kt:1)",
            logTail = listOf("23:40:59.000 W/MailSync: IMAP timed out"),
            zone = utc,
        )
        val lines = text.lines()
        assertEquals("Ledgable crash · 2025-09-10T23:41:02Z", lines[0])
        assertEquals("java.lang.NullPointerException: no page", lines[1])
        assertEquals("app: Ledgable 1.06.212 (212) prodStandard/debug", lines[2])
        assertEquals("device: Bigme B6 · Android 11 (API 30)", lines[3])
        assertEquals("thread: main", lines[4])
        assertTrue(text.contains(DiagnosticsLog.STACK_RULE))
        assertTrue(text.contains(DiagnosticsLog.LOG_RULE + "\n23:40:59.000 W/MailSync: IMAP timed out\n"))

        assertEquals("2025-09-10T23:41:02Z" to "java.lang.NullPointerException: no page", DiagnosticsLog.summaryOf(text))
        assertNull(DiagnosticsLog.summaryOf("thread: main\nwhen: yesterday"))   // the pre-door shape
        assertEquals("crash-2025-09-10T23-41-02.txt", DiagnosticsLog.crashFileName(t0, utc))
    }

    @Test
    fun `an empty log tail says so instead of ending the file mid-air`() {
        val text = DiagnosticsLog.formatCrash(t0, "x", "app", "device", "main", "stack", emptyList(), utc)
        assertTrue(text.endsWith(DiagnosticsLog.LOG_RULE + "\n(nothing logged before the crash)\n"))
    }

    @Test
    fun `the report opens with a one-line summary and carries crash then log`() {
        val report = DiagnosticsLog.formatReport(
            appLine = "Ledgable 1.06.212 (212) prodStandard/debug",
            deviceLine = "Bigme B6 · Android 11 (API 30)",
            crashSummary = "2025-09-10T23:41:02Z" to "java.lang.NullPointerException: no page",
            crashText = "Ledgable crash · 2025-09-10T23:41:02Z\njava.lang.NullPointerException: no page\n",
            logLines = listOf("a", "b"),
        )
        val lines = report.lines()
        assertEquals("Ledgable report — Ledgable 1.06.212 (212) prodStandard/debug · Bigme B6 · Android 11 (API 30) · " +
            "crashed 2025-09-10T23:41:02Z: java.lang.NullPointerException: no page", lines[0])
        assertTrue(report.indexOf("═══ LAST CRASH ═══") < report.indexOf("═══ RECENT LOG (2 lines) ═══"))
        assertTrue(report.endsWith("a\nb\n"))

        val quiet = DiagnosticsLog.formatReport("app", "device", null, null, emptyList())
        assertEquals("Ledgable report — app · device · no crash recorded", quiet.lines()[0])
        assertFalse(quiet.contains("LAST CRASH"))
        assertTrue(quiet.contains("(nothing kept yet"))
    }
}
