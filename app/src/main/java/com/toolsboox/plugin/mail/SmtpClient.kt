package com.toolsboox.plugin.mail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A minimal SMTP client: connect over implicit TLS, EHLO, AUTH LOGIN, then a single plaintext
 * message (MAIL FROM / RCPT TO / DATA) with dot-stuffing. Implicit TLS only (port 465). A direct
 * port of iOS App/Mail/SMTPClient.swift.
 *
 * The message-accepted 250 comes back from the DATA terminator BEFORE we say QUIT -- so the mail is
 * already delivered by the time we clean up. QUIT (and reading its 221) is best-effort AFTER that,
 * never before: reversing the order would risk tearing the socket down before the server confirms
 * the message (the ordering bug the iOS side fixed).
 */
class SmtpClient(host: String, port: Int, useTLS: Boolean) {
    private val conn = MailConnection(host, port, useTLS)

    data class Outgoing(
        val fromEmail: String,
        val fromName: String,
        val toEmail: String,
        val subject: String,
        val body: String,
        val inReplyTo: String? = null,
        /** The original's References + its Message-ID — the whole chain, so a deep thread stays one. */
        val references: String? = null,
        /**
         * Files to send alongside the text — in practice, the handwriting.
         *
         * Michael, 2026-08-04: "Reply to Rebecca from gram worked great, but I'm not sure it
         * included the handwriting as an image. I would like it to." A reply written by hand and
         * delivered as a transcription is a different object from the one he wrote; the point of
         * writing it by hand is that the recipient sees the hand.
         */
        val attachments: List<Attachment> = emptyList(),
        /** This letter's own Message-ID, "<…@…>" — the caller mints it ([newMessageId]) so the sent
         *  row can keep it (2026-10-10, gap #4: a random one was made up here and thrown away, so
         *  nothing could ever thread back onto a letter sent from Ledgable). Null = mint one here. */
        val messageId: String? = null,
    )

    companion object {
        /** A fresh Message-ID on the sender's own domain, not `.local` (spam filters score a
         *  non-routable Message-ID domain). */
        fun newMessageId(fromEmail: String): String =
            "<${UUID.randomUUID()}@${fromEmail.trim().substringAfterLast('@', "").ifBlank { "ledger.invalid" }}>"
    }

    /** One attached file. [bytes] is the raw content — base64 happens on the way onto the wire. */
    data class Attachment(
        val filename: String,
        val mimeType: String,
        val bytes: ByteArray,
    ) {
        // ByteArray in a data class gives reference equality from equals/hashCode, which is a
        // correctness trap rather than a style one — two identical attachments would compare
        // unequal. Content equality, since that is what "the same attachment" means.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Attachment) return false
            return filename == other.filename && mimeType == other.mimeType &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int =
            31 * (31 * filename.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
    }

    suspend fun send(login: String, password: String, message: Outgoing) =
        withContext(Dispatchers.IO) { run(login, password, message) }

    private fun run(login: String, password: String, m: Outgoing) {
        // A broken pipe on any write (EHLO / MAIL FROM / the body sendRaw) must not leak the socket:
        // the finally closes on every path (a double-close after the graceful QUIT is harmless).
        try {
            conn.start()
            expect(2, "greeting")                          // 220

            cmd("EHLO ledger.local", 2)
            eightBitMime = lastReplyLines.any { it.uppercase().contains("8BITMIME") }
            cmd("AUTH LOGIN", 3)                           // 334
            cmd(b64(login), 3)                             // 334
            try { cmd(b64(password), 2) }                  // 235
            catch (e: Exception) { throw MailException("The server rejected the username or password.") }

            cmd("MAIL FROM:<${addr(m.fromEmail)}>", 2)
            cmd("RCPT TO:<${addr(m.toEmail)}>", 2)
            cmd("DATA", 3)                                 // 354

            conn.sendRaw((buildMessage(m) + "\r\n.\r\n").toByteArray(Charsets.UTF_8))
            expect(2, "message body")                      // 250 -- the message is now accepted

            // Cleanup only (the mail is already delivered): say QUIT AFTER the 250, then read its 221
            // if it comes. Never before the 250.
            try { conn.send("QUIT") } catch (e: Exception) { }
            try { readReply() } catch (e: Exception) { }
        } finally {
            close()
        }
    }

    // Message

    internal fun buildMessage(m: Outgoing): String {
        val date = smtpDate(Date())
        val from = addr(m.fromEmail); val to = addr(m.toEmail)
        val fromField = if (m.fromName.isEmpty()) from else "${mimeName(m.fromName)} <$from>"
        val headers = arrayListOf(
            "From: $fromField",
            "To: <$to>",
            "Subject: ${mimeName(m.subject)}",
            "Date: $date",
            // The caller's id when it minted one (the sent row keeps it), else a fresh one.
            "Message-ID: " + (m.messageId?.let { hdr(it) }?.takeIf { it.startsWith("<") && it.endsWith(">") }
                ?: newMessageId(from)),
            "MIME-Version: 1.0",
        )
        val irt = m.inReplyTo?.let { hdr(it) }?.takeIf { it.isNotBlank() }
        if (irt != null) {
            headers.add(folded("In-Reply-To", irt))
            headers.add(folded("References", m.references?.let { hdr(it) }?.takeIf { it.isNotBlank() } ?: irt))
        }

        // No attachments → the plain single-part message this client has always sent. Kept as its
        // own shape rather than a one-part multipart: every mail client on earth renders it, and a
        // message with nothing attached should not pay a MIME boundary to say so.
        if (m.attachments.isEmpty()) {
            val (cte, text) = textPart(m.body)
            headers.add("Content-Type: text/plain; charset=UTF-8")
            headers.add("Content-Transfer-Encoding: $cte")
            return headers.joinToString("\r\n") + "\r\n\r\n" + text
        }

        // multipart/mixed: the text first, then the files. `mixed` rather than `related` because
        // these are attachments to be seen and saved, not resources a text/html part refers to.
        val boundary = "----ledger-${UUID.randomUUID()}"
        headers.add("Content-Type: multipart/mixed; boundary=\"$boundary\"")
        val sb = StringBuilder(headers.joinToString("\r\n")).append("\r\n\r\n")
        // Preamble for the handful of clients that show nothing at all when they cannot render
        // multipart. Ignored by everything modern.
        sb.append("This is a multi-part message in MIME format.\r\n\r\n")
        sb.append("--").append(boundary).append("\r\n")
        val (cte, text) = textPart(m.body)
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: ").append(cte).append("\r\n\r\n")
        sb.append(text).append("\r\n")
        for (att in m.attachments) {
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Type: ").append(att.mimeType)
                .append("; name=\"").append(att.filename).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"")
                .append(att.filename).append("\"\r\n\r\n")
            // Base64 wrapped at 76 columns per RFC 2045. Unwrapped base64 is a single enormous
            // line, which some SMTP servers refuse outright (RFC 5321 caps a line at 1000 octets).
            sb.append(base64Lines(att.bytes)).append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString()
    }

    /**
     * The text part's encoding + wire bytes. 8bit when every line is short; quoted-printable when
     * any line runs past ~900 octets (audit 09-10): RFC 5321 caps a line at 1000 octets, and a
     * pasted synthesis or a long paragraph typed without a return is one line — some servers
     * refuse it (500 line too long), others wrap it wherever they like.
     */
    internal fun textPart(body: String): Pair<String, String> {
        val long = body.split("\n").any { it.toByteArray(Charsets.UTF_8).size > 900 }
        val nonAscii = body.any { it.code >= 0x80 }
        return if (long || (nonAscii && !eightBitMime)) "quoted-printable" to quotedPrintable(body) else "8bit" to dotStuff(body)
    }

    /** RFC 2045 §6.7 quoted-printable with soft breaks at 76 — dot-stuffed too (a QP line can start with "."). */
    internal fun quotedPrintable(body: String): String {
        val out = StringBuilder()
        var col = 0
        fun emit(tok: String) {
            if (col + tok.length > 75) { out.append("=\r\n"); col = 0 }
            out.append(tok); col += tok.length
        }
        val bytes = body.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == 0x0A -> { out.append("\r\n"); col = 0 }
                c in 33..126 && c != 0x3D -> emit(c.toChar().toString())
                c == 0x20 || c == 0x09 -> emit(c.toChar().toString())
                else -> emit("=%02X".format(c))
            }
        }
        // Trailing whitespace before a line break must be encoded; simplest: encode any space/tab at EOL.
        return out.toString().split("\r\n").joinToString("\r\n") { line ->
            val fixed = if (line.endsWith(" ") || line.endsWith("\t")) line.dropLast(1) + "=%02X".format(line.last().code) else line
            if (fixed.startsWith(".")) ".$fixed" else fixed
        }
    }

    /** Base64, hard-wrapped at 76 characters, CRLF-joined — the line limit is a wire rule, not taste. */
    internal fun base64Lines(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).chunked(76).joinToString("\r\n")

    /** Dot-stuffing: a line that is just "." would end DATA, so lines starting with "." get doubled. */
    private fun dotStuff(body: String): String =
        body.replace("\r\n", "\n").split("\n")
            .joinToString("\r\n") { if (it.startsWith(".")) "." + it else it }

    /** MIME encoded-word for a header value if it isn't plain ASCII (so unicode subjects survive).
     *  Control characters count as "not plain" (audit 09-10): a CR/LF in a subject was pure ASCII
     *  and went out verbatim — a second header line the recipient's server would honour. */
    private fun mimeName(s: String): String =
        if (s.all { it.code in 32..126 }) s
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

    /** A long header (References grows by one id per reply) folded at the spaces between its
     *  tokens — CRLF + space, RFC 5322 §2.2.3 — so no line nears the 998-character limit some
     *  servers refuse or split at (2026-10-10 review; iOS `SMTPClient.folded`, same 76 columns).
     *  Short values stay one line, byte-identical. */
    internal fun folded(name: String, value: String): String {
        val lines = ArrayList<String>()
        var line = "$name:"
        for (tok in value.split(' ').filter { it.isNotEmpty() }) {
            if (line.length + 1 + tok.length > 76 && line.length > name.length + 1) {
                lines.add(line); line = ""
            }
            line += " $tok"
        }
        lines.add(line)
        return lines.joinToString("\r\n")
    }

    /** A header value with line breaks and NULs folded out — one line, whatever came in. */
    private fun hdr(s: String): String = s.replace(Regex("[\\r\\n\\u0000]+"), " ").trim()

    /** An addr-spec fit for `MAIL FROM`/`RCPT TO` and the angle brackets — refused, not "fixed",
     *  when it carries anything that could end the command or the bracket (audit 09-10). */
    private fun addr(s: String): String {
        val a = s.trim()
        if (a.isEmpty() || a.any { it.code < 33 || it == '<' || it == '>' || it == ',' || it == ';' || it == '"' }
            || a.count { it == '@' } != 1 || a.startsWith("@") || a.endsWith("@"))
            throw MailException("\"$s\" isn't an email address.")
        return a
    }

    private fun smtpDate(d: Date): String =
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(d)

    // Wire

    private fun cmd(line: String, expectFirst: Int) {
        conn.send(line)
        expect(expectFirst, line.split(" ").firstOrNull() ?: "command")
    }

    /** Read a (possibly multi-line) SMTP reply and require its code's first digit. */
    private fun expect(firstDigit: Int, what: String) {
        val (code, text) = readReply()
        if (code / 100 != firstDigit) { close(); throw MailException("SMTP $what failed: $code $text") }
    }

    /** SMTP replies are "250-line" continuations ending in "250 line"; return the final code + text. */
    /** Every line of the last reply — EHLO's capability list lives here (audit 09-10: it was read
     *  and thrown away, so `8bit` was asserted rather than negotiated). */
    private var lastReplyLines = ArrayList<String>()
    /** Whether the server advertised 8BITMIME on EHLO; without it text parts go quoted-printable. */
    private var eightBitMime = true

    private fun readReply(): Pair<Int, String> {
        var lastCode = 0
        var lastText = ""
        lastReplyLines = ArrayList()
        while (true) {
            val line = conn.readLine()
            lastReplyLines.add(line)
            lastCode = line.take(3).toIntOrNull() ?: 0
            lastText = line.drop(3).trim(' ', '-')
            // A dash after the code means more lines follow; a space (or a short line) means this is the last.
            if (line.length < 4 || line[3] != '-') break
        }
        return lastCode to lastText
    }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun close() { conn.close() }
}
