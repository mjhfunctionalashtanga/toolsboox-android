package com.toolsboox.ot

import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The in-memory log ring and the report shapes — pure Kotlin, no Android in it, so a JVM
 * unit test can pin the format the owner will paste into a support thread.
 *
 * WHAT IT KEEPS: the last [capacity] lines of WARN and above, plus INFO lines from the rooms
 * that misbehave silently in the field (mail, sync, Audiobookshelf, feeds). Everything else is
 * noise to a crash report and is left to logcat.
 *
 * WHY A RING AND NOT A FILE: a file appended on every line is a write per log call on a device
 * whose flash is the slowest thing in it. The ring costs nothing until it is asked for —
 * on background, on crash, or on "Copy report" — and then it is written once.
 */
class DiagnosticsLog(val capacity: Int = DEFAULT_CAPACITY) {

    private val lines = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(line: String) {
        while (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** The newest [n] lines, oldest first. */
    @Synchronized
    fun tail(n: Int = capacity): List<String> =
        if (n >= lines.size) lines.toList() else lines.toList().takeLast(n)

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun clear() = lines.clear()

    /** The ring as the text that lands in log.txt and in the report. */
    fun dump(n: Int = capacity): String = tail(n).joinToString("\n")

    companion object {
        const val DEFAULT_CAPACITY = 400
        /** How many ring lines ride along inside a crash file. */
        const val CRASH_TAIL = 200

        // android.util.Log's priorities, restated so this file needs no Android at all.
        const val VERBOSE = 2
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        const val ERROR = 6
        const val ASSERT = 7

        /** The rooms whose INFO lines are kept: matched against the tag and the message start.
         *  "synth" (Michael, 2026-10-12, B6 -322: "It does not put in it's write back pic"): the ❖
         *  summon's `synth[<id>]` trail — start, request, reply, landed where — was logcat-only, so
         *  a field report could prove nothing once the cable was off.
         *  "pan:" / "zoom:" (Michael 2026-10-12: "Panning after zoom feels stilted" · "16x zoom"):
         *  one line per pan and per zoom settle — frames, the worst one, the redraw and the scale it
         *  was drawn at — so "it feels stilted" arrives with its numbers. Matched with the colon,
         *  the lines' own head, so a "panel …" line stays out. */
        val KEPT_INFO_ROOMS = listOf("mail", "sync", "abs", "audiobookshelf", "feed", "harvest", "synth",
            "pan:", "zoom:")

        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private val ISO_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

        /** Whether a log line of this priority/tag/message belongs in the ring. */
        fun keeps(priority: Int, tag: String?, message: String): Boolean {
            if (priority >= WARN) return true
            if (priority < INFO) return false
            val t = (tag ?: "").lowercase()
            val head = message.take(24).lowercase()
            return KEPT_INFO_ROOMS.any { room -> t.contains(room) || head.contains(room) }
        }

        fun letter(priority: Int): Char = when (priority) {
            VERBOSE -> 'V'; DEBUG -> 'D'; INFO -> 'I'; WARN -> 'W'; ERROR -> 'E'; ASSERT -> 'A'
            else -> '?'
        }

        /** One ring line: `23:41:02.123 W/MailSync: message` plus the throwable's first frames. */
        fun formatLine(
            epochMillis: Long, priority: Int, tag: String?, message: String, t: Throwable?,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            val time = LINE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
            val sb = StringBuilder(time).append(' ').append(letter(priority)).append('/')
                .append(tag ?: "Ledgable").append(": ").append(message.trimEnd())
            if (t != null) {
                // Six frames is enough to name the culprit; the full trace belongs to a crash
                // file, not to every warning.
                stackText(t).lineSequence().take(7).forEach { sb.append("\n    ").append(it.trim()) }
            }
            return sb.toString()
        }

        fun stackText(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString().trimEnd()
        }

        fun isoTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            ISO_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

        /** `crash-2026-09-10T23-41-02.txt` — ISO order, no colons, sorts by time as a name. */
        fun crashFileName(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "crash-" + FILE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) + ".txt"

        const val CRASH_HEADER = "Ledgable crash · "
        const val STACK_RULE = "═══ STACK ═══"
        const val LOG_RULE = "═══ LAST LOG LINES ═══"

        /**
         * The crash file. Line 1 names the moment, line 2 is the one-line cause — those two are
         * what the Diagnostics door shows as "Last crash: <time> · <first line>", so they are
         * fixed here and read back by [summaryOf].
         */
        fun formatCrash(
            epochMillis: Long, firstLine: String, appLine: String, deviceLine: String,
            thread: String, stack: String, logTail: List<String>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            val sb = StringBuilder()
            sb.append(CRASH_HEADER).append(isoTime(epochMillis, zone)).append('\n')
            sb.append(firstLine.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "(no message)" }).append('\n')
            sb.append("app: ").append(appLine).append('\n')
            sb.append("device: ").append(deviceLine).append('\n')
            sb.append("thread: ").append(thread).append('\n')
            sb.append('\n').append(STACK_RULE).append('\n').append(stack.trimEnd()).append('\n')
            sb.append('\n').append(LOG_RULE).append('\n')
            if (logTail.isEmpty()) sb.append("(nothing logged before the crash)\n")
            else logTail.forEach { sb.append(it).append('\n') }
            return sb.toString()
        }

        /** (time, first line) from a crash file's head, or null if it is not one of ours. */
        fun summaryOf(crashText: String): Pair<String, String>? {
            val lines = crashText.lineSequence().take(2).toList()
            if (lines.size < 2 || !lines[0].startsWith(CRASH_HEADER)) return null
            return lines[0].removePrefix(CRASH_HEADER).trim() to lines[1].trim()
        }

        /**
         * The whole report, clipboard- and share-ready. The first line is the summary a reader
         * sees before anything else: what device, what build, and whether there is a crash.
         */
        fun formatReport(
            appLine: String, deviceLine: String, crashSummary: Pair<String, String>?,
            crashText: String?, logLines: List<String>,
        ): String {
            val sb = StringBuilder()
            val head = if (crashSummary != null) "crashed ${crashSummary.first}: ${crashSummary.second}" else "no crash recorded"
            sb.append("Ledgable report — ").append(appLine).append(" · ").append(deviceLine).append(" · ").append(head).append('\n')
            sb.append('\n')
            if (crashText != null) {
                sb.append("═══ LAST CRASH ═══\n").append(crashText.trimEnd()).append("\n\n")
            }
            sb.append("═══ RECENT LOG (").append(logLines.size).append(" lines) ═══\n")
            if (logLines.isEmpty()) sb.append("(nothing kept yet — mail, sync, books and feeds lines land here as they happen)\n")
            else logLines.forEach { sb.append(it).append('\n') }
            return sb.toString()
        }
    }
}
