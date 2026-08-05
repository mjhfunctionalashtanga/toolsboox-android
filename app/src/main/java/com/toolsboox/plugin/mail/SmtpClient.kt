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
        /**
         * Files to send alongside the text — in practice, the handwriting.
         *
         * Michael, 2026-08-04: "Reply to Rebecca from gram worked great, but I'm not sure it
         * included the handwriting as an image. I would like it to." A reply written by hand and
         * delivered as a transcription is a different object from the one he wrote; the point of
         * writing it by hand is that the recipient sees the hand.
         */
        val attachments: List<Attachment> = emptyList(),
    )

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
            cmd("AUTH LOGIN", 3)                           // 334
            cmd(b64(login), 3)                             // 334
            try { cmd(b64(password), 2) }                  // 235
            catch (e: Exception) { throw MailException("The server rejected the username or password.") }

            cmd("MAIL FROM:<${m.fromEmail}>", 2)
            cmd("RCPT TO:<${m.toEmail}>", 2)
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
        val fromField = if (m.fromName.isEmpty()) m.fromEmail else "${mimeName(m.fromName)} <${m.fromEmail}>"
        val headers = arrayListOf(
            "From: $fromField",
            "To: <${m.toEmail}>",
            "Subject: ${mimeName(m.subject)}",
            "Date: $date",
            "Message-ID: <${UUID.randomUUID()}@ledger.local>",
            "MIME-Version: 1.0",
        )
        val irt = m.inReplyTo
        if (!irt.isNullOrEmpty()) { headers.add("In-Reply-To: $irt"); headers.add("References: $irt") }

        // No attachments → the plain single-part message this client has always sent. Kept as its
        // own shape rather than a one-part multipart: every mail client on earth renders it, and a
        // message with nothing attached should not pay a MIME boundary to say so.
        if (m.attachments.isEmpty()) {
            headers.add("Content-Type: text/plain; charset=UTF-8")
            headers.add("Content-Transfer-Encoding: 8bit")
            return headers.joinToString("\r\n") + "\r\n\r\n" + dotStuff(m.body)
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
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n")
        sb.append(dotStuff(m.body)).append("\r\n")
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

    /** Base64, hard-wrapped at 76 characters, CRLF-joined — the line limit is a wire rule, not taste. */
    internal fun base64Lines(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).chunked(76).joinToString("\r\n")

    /** Dot-stuffing: a line that is just "." would end DATA, so lines starting with "." get doubled. */
    private fun dotStuff(body: String): String =
        body.replace("\r\n", "\n").split("\n")
            .joinToString("\r\n") { if (it.startsWith(".")) "." + it else it }

    /** MIME encoded-word for a header value if it isn't plain ASCII (so unicode subjects survive). */
    private fun mimeName(s: String): String =
        if (s.all { it.code < 128 }) s
        else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8)) + "?="

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
    private fun readReply(): Pair<Int, String> {
        var lastCode = 0
        var lastText = ""
        while (true) {
            val line = conn.readLine()
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
