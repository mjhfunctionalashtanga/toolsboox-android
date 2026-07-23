package com.toolsboox.plugin.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * A minimal IMAP client: connect over implicit TLS, LOGIN, SELECT INBOX, and FETCH the most recent
 * messages whole (BODY.PEEK[], so nothing is marked read), handing each to the MIME parser. Enough
 * of the protocol -- tagged commands, untagged replies, {n} literals -- to be correct; not a full
 * IMAP engine (no IDLE, no server-side search, no partial fetch). A direct port of iOS
 * App/Mail/IMAPClient.swift. Every call blocks; [fetchRecent] hops to Dispatchers.IO.
 */
class ImapClient(host: String, port: Int, useTLS: Boolean) {
    private val conn = MailConnection(host, port, useTLS)
    private var n = 0

    private fun nextTag(): String { n += 1; return "A%03d".format(n) }

    private data class Reply(val lines: List<String>, val literals: List<ByteArray>, val ok: Boolean)

    suspend fun fetchRecent(user: String, pass: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) { run(user, pass, limit) }

    private fun run(user: String, pass: String, limit: Int): List<FetchedMessage> {
        conn.start()
        conn.readLine()                                   // server greeting: * OK ...

        val login = command("LOGIN ${quote(user)} ${quote(pass)}")
        if (!login.ok) { close(); throw MailException("The server rejected the username or password.") }

        val sel = command("SELECT INBOX")
        if (!sel.ok) { close(); throw MailException("Couldn't open INBOX.") }
        val exists = parseExists(sel.lines)
        if (exists <= 0) { logout(); return emptyList() }

        val start = maxOf(1, exists - limit + 1)
        val out = ArrayList<FetchedMessage>()
        var seq = exists
        while (seq >= start) {
            val r = command("FETCH $seq (UID INTERNALDATE BODY.PEEK[])")
            if (r.ok) parseFetch(r.lines, r.literals)?.let { out.add(it) }
            seq -= 1
        }
        logout()
        return out
    }

    // Command / response

    private fun command(cmd: String): Reply {
        val tag = nextTag()
        conn.send("$tag $cmd")
        return readUntilTagged(tag)
    }

    /** Read the untagged reply lines up to the tagged completion, pulling {n} literals inline. */
    private fun readUntilTagged(tag: String): Reply {
        val lines = ArrayList<String>()
        val literals = ArrayList<ByteArray>()
        while (true) {
            val line = conn.readLine()
            val len = literalLength(line)
            if (len != null) {
                lines.add(line)
                literals.add(conn.readBytes(len))
                continue
            }
            lines.add(line)
            if (line.startsWith("$tag ")) {
                val ok = line.substring(tag.length + 1).uppercase().startsWith("OK")
                return Reply(lines, literals, ok)
            }
        }
    }

    private fun logout() { try { command("LOGOUT") } catch (e: Exception) { }; close() }
    private fun close() { conn.close() }

    companion object {
        /** A line ending in {123} announces a 123-byte literal to follow. */
        private fun literalLength(line: String): Int? {
            if (!line.endsWith("}")) return null
            val brace = line.lastIndexOf('{')
            if (brace < 0) return null
            return line.substring(brace + 1, line.length - 1).toIntOrNull()
        }

        private fun parseExists(lines: List<String>): Int {
            for (l in lines) if (l.startsWith("*") && l.uppercase().endsWith(" EXISTS")) {
                val parts = l.split(" ")
                if (parts.size >= 3) parts[1].toIntOrNull()?.let { return it }
            }
            return 0
        }

        private fun parseFetch(lines: List<String>, literals: List<ByteArray>): FetchedMessage? {
            val raw = literals.firstOrNull() ?: return null
            val joined = lines.joinToString(" ")
            val uid = token(joined, "UID ") ?: UUID.randomUUID().toString()
            val internalDate = quoted(joined, "INTERNALDATE ")?.let { MailDate.parseMillis(it) }

            val parsed = Mime.parse(raw)
            val subject = Mime.decodeEncodedWords(parsed.headers["subject"] ?: "").trim()
            val (name, email) = AddressParse.nameAndEmail(parsed.headers["from"] ?: "")
            val date = parsed.headers["date"]?.let { MailDate.parseMillis(it) } ?: internalDate ?: System.currentTimeMillis()

            return FetchedMessage(
                uid = uid,
                subject = if (subject.isEmpty()) "(no subject)" else subject,
                fromName = name, fromEmail = email, date = date, body = parsed.text
            )
        }

        /** The whitespace-delimited token right after [marker]. */
        private fun token(s: String, marker: String): String? {
            val r = s.indexOf(marker); if (r < 0) return null
            val rest = s.substring(r + marker.length)
            val end = rest.indexOfFirst { it == ' ' || it == ')' }
            val t = if (end < 0) rest else rest.substring(0, end)
            return t.ifEmpty { null }
        }

        /** The "..."-quoted value right after [marker]. */
        private fun quoted(s: String, marker: String): String? {
            val r = s.indexOf(marker + "\""); if (r < 0) return null
            val rest = s.substring(r + marker.length + 1)
            val end = rest.indexOf('"'); if (end < 0) return null
            return rest.substring(0, end)
        }

        /** IMAP-quote a string (backslash-escape backslash and double-quote). */
        private fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
