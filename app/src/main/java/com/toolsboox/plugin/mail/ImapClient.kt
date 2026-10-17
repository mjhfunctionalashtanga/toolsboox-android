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
 * blocks; [fetchRecent], [fetchFull], [search] and [fetchJunk] hop to Dispatchers.IO. INBOX is the
 * one mailbox it SELECTs; [fetchJunk] (2026-10-11) finds the Junk folder by LIST and reads it with
 * EXAMINE, read-only — and [fetchFull] / [fetchRaw] with `junk = true` open that same folder the
 * same read-only way, so a Junk letter's "load the rest" and attachments come from where it lives.
 */
class ImapClient(host: String, port: Int, useTLS: Boolean) {
    /** `UIDVALIDITY` of the last SELECTed INBOX (0 = unknown) — the UID namespace the fetched ids
     *  were minted in. When the server bumps it every stored `acct:<id>:uid:<n>` names a different
     *  message (audit 09-10); MailSync compares it with the last value it saw and drops the window. */
    @Volatile var lastUidValidity: Long = 0L
    private val conn = MailConnection(host, port, useTLS)
    private var n = 0

    private fun nextTag(): String { n += 1; return "A%03d".format(n) }

    private data class Reply(val lines: List<String>, val literals: List<ByteArray>, val ok: Boolean)

    suspend fun fetchRecent(user: String, pass: String, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) { run(user, pass, limit) }

    /** Fetch ONE message whole by its IMAP [uid] -- the on-demand "load the rest" for a message
     *  [fetchRecent] truncated. Throws [MailMessageTooLarge] past [ON_DEMAND_FETCH_MAX], a plain
     *  [MailException] when the message has left the INBOX (moved / deleted since the refresh).
     *  [junk]: the uid is the account's Junk folder's (a [fetchJunk] letter) — opened read-only,
     *  as iOS's `fetchFull(junk:)` does. */
    suspend fun fetchFull(user: String, pass: String, uid: Long, junk: Boolean = false): FetchedMessage =
        withContext(Dispatchers.IO) {
            if (junk) junkSession(user, pass) { fetchWhole(uid, JUNK_PLACE) }
            else session(user, pass) { fetchWhole(uid, INBOX_PLACE) }
        }

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
        session(user, pass) { exists -> window(exists, limit) }

    /**
     * 🚫 ONE ACCOUNT'S JUNK FOLDER, read-only (Michael 2026-10-11: "Mail accounts also have
     * individual sents, junks, trash"). The folder is found by LIST: the one the server marks with
     * the RFC 6154 special-use `\Junk` attribute, else the common names — Junk, Spam, Gmail's
     * "[Gmail]/Spam", Outlook's "Junk E-mail" — matched on the folder's last segment, so
     * "INBOX.Junk" counts too. It is opened with EXAMINE (read-only: the server changes no flag)
     * and its newest [limit] come through the same UID-addressed, size-probed window a refresh
     * uses. Returns the folder's name and its letters, or null when the account has no such
     * folder. Nothing is moved or deleted — this client has no MOVE/COPY verb.
     */
    suspend fun fetchJunk(user: String, pass: String, limit: Int): Pair<String, List<FetchedMessage>>? =
        withContext(Dispatchers.IO) {
            loggedIn(user, pass) {
                val (name, exists) = openJunk() ?: return@loggedIn null
                name to window(exists, limit)
            }
        }

    /** LIST, choose the Junk folder ([junkMailbox]), EXAMINE it — (name, EXISTS), or null when the
     *  account has none. Inside a logged-in session. */
    private fun openJunk(): Pair<String, Int>? {
        val list = command("LIST \"\" \"*\"")
        if (!list.ok) throw MailException("The server wouldn't list its folders.")
        val name = junkMailbox(list.lines, list.literals) ?: return null
        val ex = command("EXAMINE ${quote(name)}")
        if (!ex.ok) throw MailException("Couldn't open $name.")
        return name to parseExists(ex.lines)
    }

    /** Log in and open the Junk folder read-only for [block] — the on-demand fetches of a letter
     *  [fetchJunk] listed. Throws when the account no longer has a Junk folder. */
    private fun <T> junkSession(user: String, pass: String, block: () -> T): T =
        loggedIn(user, pass) {
            openJunk() ?: throw MailException("This account has no Junk folder any more.")
            block()
        }

    /** The newest [limit] of a selected mailbox holding [exists] messages, newest first. */
    private fun window(exists: Int, limit: Int): List<FetchedMessage> {
        if (exists <= 0) return emptyList()
        val start = maxOf(1, exists - limit + 1)
        // Address the window by UID, not sequence (audit 09-10): a `* n EXPUNGE` the server
        // emits mid-refresh (mail deleted on the phone) shifts every later sequence number, so
        // one message was fetched twice and one skipped. One ranged probe maps the window to
        // UIDs; each message is then fetched by its own immutable name.
        val uids = runCatching {
            val map = command("FETCH $start:$exists (UID)")
            map.lines.filter { it.startsWith("* ") && it.uppercase().contains(" FETCH ") }
                .mapNotNull { l -> token(l, "UID ")?.toLongOrNull() }
                .sortedDescending()
        }.getOrDefault(emptyList())
        val out = ArrayList<FetchedMessage>()
        val expected = exists - start + 1
        if (uids.isNotEmpty() && uids.size < expected)
            timber.log.Timber.w("imap: UID probe answered ${uids.size} of $expected — sequence fallback")
        if (uids.isNotEmpty() && uids.size >= expected) {
            for (uid in uids) {
                try { fetchByUid(uid)?.let { out.add(it) } }
                catch (e: Exception) { if (out.isEmpty()) throw e; timber.log.Timber.w(e, "imap: partial window kept (${out.size})"); break }
            }
            return out
        }
        var seq = exists
        while (seq >= start) {
            // A flaky socket on message 18 of 25 used to throw the seventeen already parsed
            // away with it (audit 09-10). Newest-first, so what we have is the most recent —
            // keep it, as the iPad does; only an empty window re-throws.
            try { fetchOne(seq)?.let { out.add(it) } }
            catch (e: Exception) { if (out.isEmpty()) throw e; timber.log.Timber.w(e, "imap: partial window kept (${out.size})"); break }
            seq -= 1
        }
        return out
    }

    /** Connect, greet, LOGIN, SELECT INBOX, hand [block] the mailbox's EXISTS count, LOGOUT.
     *  The one session skeleton both fetch paths ride. */
    private fun <T> session(user: String, pass: String, block: (exists: Int) -> T): T =
        loggedIn(user, pass) {
            val sel = command("SELECT INBOX")
            if (!sel.ok) throw MailException("Couldn't open INBOX.")
            sel.lines.forEach { l ->
                val i = l.indexOf("[UIDVALIDITY ", ignoreCase = true)
                if (i >= 0) lastUidValidity = l.substring(i + 13).takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
            }
            block(parseExists(sel.lines))
        }

    /** Connect, greet, LOGIN, run [block], LOGOUT — the skeleton under [session] (INBOX) and
     *  [fetchJunk] (a folder found by LIST). */
    private fun <T> loggedIn(user: String, pass: String, block: () -> T): T {
        // A read-timeout, a mid-FETCH socket drop, or a parse throw must NOT leak the TLS socket: the
        // finally closes on every path (double-close via logout() is harmless). Servers routinely drop
        // slow connections mid-fetch, so this runs on essentially every refresh.
        try {
            conn.start()
            conn.readLine()                                   // server greeting: * OK ...

            val login = command("LOGIN ${quote(user)} ${quote(pass)}")
            if (!login.ok) throw MailException("The server rejected the username or password.")

            val result = block()
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
        // Unknown size ⇒ fetch whole (audit 09-10): a server that omits or spaces RFC822.SIZE
        // differently used to truncate EVERY message forever; MailConnection's literal cap backstops.
        val size = token(metaLine, "RFC822.SIZE ")?.toLongOrNull() ?: -1L
        val truncated = size > FULL_FETCH_MAX

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
    private fun fetchWhole(uid: Long, place: String): FetchedMessage {
        val (metaLine, raw) = wholeRaw(uid, place)
        return parseFetch(metaLine, raw, truncated = false)
            ?: throw MailException("The message came back unreadable.")
    }

    /** Size-probe [uid] in the open mailbox, then its complete BODY.PEEK[] — (meta line, bytes).
     *  [place] words the vanished-message refusal ("the inbox", "Junk"). */
    private fun wholeRaw(uid: Long, place: String): Pair<String, ByteArray> {
        val meta = command("UID FETCH $uid (UID RFC822.SIZE INTERNALDATE)")
        if (!meta.ok) throw MailException("The server refused the fetch.")
        // A UID FETCH of a vanished message succeeds with no untagged FETCH data -- distinguish it.
        if (meta.lines.none { it.startsWith("*") && it.uppercase().contains(" FETCH ") })
            throw MailException("That message is no longer in $place on the server.")
        val metaLine = meta.lines.joinToString(" ")
        val size = token(metaLine, "RFC822.SIZE ")?.toLongOrNull() ?: -1L
        if (size > ON_DEMAND_FETCH_MAX) throw MailMessageTooLarge(size)

        val body = command("UID FETCH $uid (BODY.PEEK[])")
        if (!body.ok) throw MailException("The server refused the fetch.")
        val raw = body.literals.firstOrNull()
            ?: throw MailException("That message is no longer in $place on the server.")
        return metaLine to raw
    }

    /** The whole message RAW — the attachment carve's feedstock ([Mime.partBytes]); same size
     *  ceiling and vanished-message honesty as [fetchFull], no parse. [junk] as for [fetchFull]. */
    suspend fun fetchRaw(user: String, pass: String, uid: Long, junk: Boolean = false): ByteArray =
        withContext(Dispatchers.IO) {
            if (junk) junkSession(user, pass) { wholeRaw(uid, JUNK_PLACE).second }
            else session(user, pass) { wholeRaw(uid, INBOX_PLACE).second }
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
        /** How a vanished-message refusal names the mailbox it looked in. */
        private const val INBOX_PLACE = "the inbox"
        private const val JUNK_PLACE = "Junk"

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

        /** `* LIST (attrs) delim name` — the name quoted, a bare atom, or a `{n}` literal whose
         *  text rode in the reply's literals (the line then ends at the `{n}`). */
        private val LIST_LINE = Regex("""^\* LIST \(([^)]*)\) (NIL|"(?:[^"\\]|\\.)*") (.+)$""", RegexOption.IGNORE_CASE)

        /** The names a Junk folder goes by when the server doesn't mark it, in the order they're
         *  tried — iOS's `JunkFolder.commonNames`, word for word, so both apps find the same folder:
         *  the plain ones, then Gmail's, Outlook's, Yahoo's, and the `INBOX.`-prefixed namespaces
         *  Dovecot / cPanel hosts use. Matched on the WHOLE name (case aside), never a last segment,
         *  so a filing folder like "Clients/Spam" is never taken for the account's Junk. */
        internal val JUNK_NAMES = listOf(
            "Junk", "Spam", "[Gmail]/Spam", "[Google Mail]/Spam", "Junk E-mail", "Junk Email",
            "Bulk Mail", "INBOX.Junk", "INBOX.Spam", "INBOX/Junk", "INBOX/Spam",
        )

        /**
         * The Junk folder out of a LIST reply, chosen as iOS's `JunkFolder.choose` does: the first
         * openable folder carrying the special-use `\Junk` attribute (RFC 6154 — Gmail, Fastmail,
         * iCloud and Dovecot all send it on a plain LIST), else the first of [JUNK_NAMES] present.
         * A `\Noselect` / `\NonExistent` folder is never chosen. The name goes back to the server
         * exactly as it came (a modified-UTF-7 name is never decoded). [literals] are the reply's
         * `{n}` literals in order, for a name sent that way. Null when there is none.
         */
        internal fun junkMailbox(lines: List<String>, literals: List<ByteArray> = emptyList()): String? {
            var lit = 0
            // (attributes, name) per openable folder, in the server's order.
            val boxes = lines.mapNotNull { l ->
                // Every line that announced a literal consumed the next one, LIST or not.
                val literalName = if (literalLength(l) != null) literals.getOrNull(lit++)?.toString(Charsets.UTF_8) else null
                val m = LIST_LINE.find(l.trim()) ?: return@mapNotNull null
                val attrs = m.groupValues[1].split(' ').filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
                if ("\\noselect" in attrs || "\\nonexistent" in attrs) return@mapNotNull null
                val raw = m.groupValues[3].trim()
                val name = when {
                    raw.endsWith("}") && raw.startsWith("{") -> literalName ?: return@mapNotNull null
                    raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                        raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    else -> raw
                }
                if (name.isEmpty()) null else attrs to name
            }
            boxes.firstOrNull { "\\junk" in it.first }?.let { return it.second }
            for (common in JUNK_NAMES) boxes.firstOrNull { it.second.equals(common, ignoreCase = true) }?.let { return it.second }
            return null
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

            // Whole messages embed their inline cid images; a truncated slice must not — its
            // base64 is cut mid-part and would embed garbage (the roster still lists what the
            // slice could see; "load the rest" completes both).
            val parsed = Mime.parse(raw, embedCids = !truncated)
            val subject = Mime.decodeEncodedWords(parsed.headers["subject"] ?: "").trim()
            val (name, email) = AddressParse.nameAndEmail(parsed.headers["from"] ?: "")
            val date = parsed.headers["date"]?.let { MailDate.parseMillis(it) } ?: internalDate ?: System.currentTimeMillis()

            return FetchedMessage(
                uid = uid,
                subject = if (subject.isEmpty()) "(no subject)" else subject,
                fromName = name, fromEmail = email, date = date, body = parsed.text,
                truncated = truncated, html = parsed.html, attachments = parsed.attachments,
                messageId = (parsed.headers["message-id"] ?: "").trim(),
                references = (parsed.headers["references"] ?: "").trim().replace(Regex("\\s+"), " "),
                inReplyTo = (parsed.headers["in-reply-to"] ?: "").trim().replace(Regex("\\s+"), " ")
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
