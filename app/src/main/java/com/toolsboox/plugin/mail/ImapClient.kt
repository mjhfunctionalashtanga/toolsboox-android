package com.toolsboox.plugin.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** The one refusal the UI words differently: the whole message won't fit on this device, ever. */
class MailMessageTooLarge(sizeBytes: Long) :
    MailException("Too large to fetch on this device (${sizeBytes / (1024 * 1024)} MB).")

/**
 * A minimal IMAP client: connect over implicit TLS, LOGIN, SELECT INBOX, and FETCH the most recent
 * messages (BODY.PEEK, so nothing is marked read), handing each to the MIME parser. Sizes ride in
 * first (RFC822.SIZE): a message under [FULL_FETCH_MAX] comes whole; anything larger -- almost
 * always attachments -- comes as headers plus a [TEXT_SLICE]-byte body slice and is flagged
 * truncated, so a 40 MB video mail never gets allocated on an e-ink tablet. [fetchFull] is the
 * deliberate exception: the reader asked for THIS one message whole, so it comes whole -- still
 * size-probed first, refused past [ON_DEMAND_FETCH_MAX]. Enough of the protocol -- tagged
 * commands, untagged replies, {n} literals -- to be correct; not a full IMAP engine (no IDLE).
 * [search] runs a server-side UID SEARCH (subject/from/text) and pulls the newest hits back
 * through the same bounded fetch. A direct port of iOS App/Mail/IMAPClient.swift. Every call
 * blocks; [fetchRecent], [fetchFull] and [search] hop to Dispatchers.IO.
 */
class ImapClient(host: String, port: Int, useTLS: Boolean) {
    private val conn = MailConnection(host, port, useTLS)
    private var n = 0

    private fun nextTag(): String { n += 1; return "A%03d".format(n) }

    private data class Reply(val lines: List<String>, val literals: List<ByteArray>, val ok: Boolean)

    suspend fun fetchRecent(user: String, pass: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) { run(user, pass, limit) }

    /** Fetch ONE message whole by its IMAP [uid] -- the on-demand "load the rest" for a message
     *  [fetchRecent] truncated. Throws [MailMessageTooLarge] past [ON_DEMAND_FETCH_MAX], a plain
     *  [MailException] when the message has left the INBOX (moved / deleted since the refresh). */
    suspend fun fetchFull(user: String, pass: String, uid: Long): FetchedMessage =
        withContext(Dispatchers.IO) { session(user, pass) { fetchWhole(uid) } }

    /**
     * Server-side search: `UID SEARCH` for [query] in the subject, the sender, or the body text,
     * newest [limit] matches fetched through the same size-probed bounded path as [fetchRecent]
     * (headers + a body slice for oversized mail — a search hit is never a 40 MB allocation
     * either). TEXT makes the server walk whole bodies and can be slow on some servers;
     * acceptable — the reader asked for a deep search.
     */
    suspend fun search(user: String, pass: String, query: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            session(user, pass) { _ ->
                // OR is binary and prefix in IMAP, so subject ∨ from ∨ text = OR OR A B C. The
                // query rides as one quoted string ([quote] escapes backslash and double-quote);
                // control chars are stripped first — a raw CR/LF inside a quoted string would
                // split the command and desync the session. Quoted strings are 7-bit per RFC
                // 3501: a non-ASCII query may be refused by strict servers (proper support needs
                // literal syntax + CHARSET, which this minimal client doesn't speak); that
                // refusal surfaces as this account's error, never a crash.
                val q = quote(query.replace(Regex("[\\u0000-\\u001F]"), " ").trim())
                val r = command("UID SEARCH OR OR SUBJECT $q FROM $q TEXT $q")
                if (!r.ok) throw MailException("The server refused the search.")
                parseSearchUids(r.lines)
                    .sortedDescending()            // UIDs ascend with arrival: highest = newest
                    .take(limit)
                    .mapNotNull { fetchByUid(it) }
            }
        }

    private fun run(user: String, pass: String, limit: Int): List<FetchedMessage> =
        session(user, pass) { exists ->
            if (exists <= 0) return@session emptyList()
            val start = maxOf(1, exists - limit + 1)
            val out = ArrayList<FetchedMessage>()
            var seq = exists
            while (seq >= start) {
                fetchOne(seq)?.let { out.add(it) }
                seq -= 1
            }
            out
        }

    /** Connect, greet, LOGIN, SELECT INBOX, hand [block] the mailbox's EXISTS count, LOGOUT.
     *  The one session skeleton both fetch paths ride. */
    private fun <T> session(user: String, pass: String, block: (exists: Int) -> T): T {
        // A read-timeout, a mid-FETCH socket drop, or a parse throw must NOT leak the TLS socket: the
        // finally closes on every path (double-close via logout() is harmless). Servers routinely drop
        // slow connections mid-fetch, so this runs on essentially every refresh.
        try {
            conn.start()
            conn.readLine()                                   // server greeting: * OK ...

            val login = command("LOGIN ${quote(user)} ${quote(pass)}")
            if (!login.ok) throw MailException("The server rejected the username or password.")

            val sel = command("SELECT INBOX")
            if (!sel.ok) throw MailException("Couldn't open INBOX.")
            val result = block(parseExists(sel.lines))
            logout()
            return result
        } finally {
            close()
        }
    }

    /**
     * Size first, body second: the whole message only when RFC822.SIZE says it fits under
     * [FULL_FETCH_MAX]; otherwise headers + a bounded body slice, flagged truncated. An unknown
     * size (server omitted or unparseable) takes the bounded path -- never the unbounded one.
     */
    private fun fetchOne(seq: Int): FetchedMessage? = fetchBounded("FETCH $seq")

    /** A search hit, fetched by UID through the same bounded path the refresh window uses. */
    private fun fetchByUid(uid: Long): FetchedMessage? = fetchBounded("UID FETCH $uid")

    /** [ref] addresses one message — "FETCH <seq>" or "UID FETCH <uid>"; the size probe and the
     *  bounded-slice fallback are identical either way. */
    private fun fetchBounded(ref: String): FetchedMessage? {
        val meta = command("$ref (UID RFC822.SIZE INTERNALDATE)")
        if (!meta.ok) return null
        val metaLine = meta.lines.joinToString(" ")
        val size = token(metaLine, "RFC822.SIZE ")?.toLongOrNull() ?: -1L
        val truncated = size < 0 || size > FULL_FETCH_MAX

        val body = command(
            if (truncated) "$ref (BODY.PEEK[HEADER] BODY.PEEK[TEXT]<0.$TEXT_SLICE>)"
            else "$ref (BODY.PEEK[])"
        )
        if (!body.ok) return null
        val raw: ByteArray = if (truncated) {
            val header = body.literals.getOrNull(0) ?: return null
            val text = body.literals.getOrNull(1) ?: ByteArray(0)
            // BODY[HEADER] should end with the delimiting blank line; some servers omit it, and
            // without it the MIME split reads the whole slice as headers.
            if (endsWithBlankLine(header)) header + text
            else header + "\r\n".toByteArray(Charsets.US_ASCII) + text
        } else body.literals.firstOrNull() ?: return null

        return parseFetch(metaLine, raw, truncated)
    }

    /**
     * The whole-message path [fetchFull] runs inside a session: probe the size, then UID FETCH the
     * complete BODY.PEEK[]. The probe keeps the refusal honest -- [MailMessageTooLarge], worded for
     * the truncation note -- instead of the generic oversized-literal error [MailConnection.readBytes]
     * would throw mid-stream (which still backstops a server whose SIZE lied or was omitted).
     */
    private fun fetchWhole(uid: Long): FetchedMessage {
        val meta = command("UID FETCH $uid (UID RFC822.SIZE INTERNALDATE)")
        if (!meta.ok) throw MailException("The server refused the fetch.")
        // A UID FETCH of a vanished message succeeds with no untagged FETCH data -- distinguish it.
        if (meta.lines.none { it.startsWith("*") && it.uppercase().contains(" FETCH ") })
            throw MailException("That message is no longer in the inbox on the server.")
        val metaLine = meta.lines.joinToString(" ")
        val size = token(metaLine, "RFC822.SIZE ")?.toLongOrNull() ?: -1L
        if (size > ON_DEMAND_FETCH_MAX) throw MailMessageTooLarge(size)

        val body = command("UID FETCH $uid (BODY.PEEK[])")
        if (!body.ok) throw MailException("The server refused the fetch.")
        val raw = body.literals.firstOrNull()
            ?: throw MailException("That message is no longer in the inbox on the server.")
        return parseFetch(metaLine, raw, truncated = false)
            ?: throw MailException("The message came back unreadable.")
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
        /** Messages over this many bytes are not fetched whole (attachments ride in BODY[]). */
        private const val FULL_FETCH_MAX = 1_048_576L
        /** Body slice fetched for an oversized message -- enough for any readable lead text. */
        private const val TEXT_SLICE = 65_536
        /** Ceiling for the on-demand [fetchFull] -- generous (the reader asked), but under
         *  MailConnection's 32 MB literal cap so the refusal happens HERE, with honest words,
         *  before a mid-literal failure could. */
        private const val ON_DEMAND_FETCH_MAX = 24L * 1024 * 1024

        private fun endsWithBlankLine(b: ByteArray): Boolean =
            b.size >= 4 && b[b.size - 4] == 0x0D.toByte() && b[b.size - 3] == 0x0A.toByte() &&
                b[b.size - 2] == 0x0D.toByte() && b[b.size - 1] == 0x0A.toByte()

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

        /** The UIDs out of the untagged `* SEARCH 4 77 103` reply lines (empty hit list = no lines
         *  past the two keywords — fine). */
        private fun parseSearchUids(lines: List<String>): List<Long> =
            lines.filter { it.uppercase().startsWith("* SEARCH") }
                .flatMap { it.trim().split(" ").drop(2) }
                .mapNotNull { it.toLongOrNull() }

        private fun parseFetch(metaLine: String, raw: ByteArray, truncated: Boolean): FetchedMessage? {
            val uid = token(metaLine, "UID ") ?: UUID.randomUUID().toString()
            val internalDate = quoted(metaLine, "INTERNALDATE ")?.let { MailDate.parseMillis(it) }

            val parsed = Mime.parse(raw)
            val subject = Mime.decodeEncodedWords(parsed.headers["subject"] ?: "").trim()
            val (name, email) = AddressParse.nameAndEmail(parsed.headers["from"] ?: "")
            val date = parsed.headers["date"]?.let { MailDate.parseMillis(it) } ?: internalDate ?: System.currentTimeMillis()

            return FetchedMessage(
                uid = uid,
                subject = if (subject.isEmpty()) "(no subject)" else subject,
                fromName = name, fromEmail = email, date = date, body = parsed.text,
                truncated = truncated
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
