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
    )

    suspend fun send(login: String, password: String, message: Outgoing) =
        withContext(Dispatchers.IO) { run(login, password, message) }

    private fun run(login: String, password: String, m: Outgoing) {
        conn.start()
        expect(2, "greeting")                          // 220

        cmd("EHLO ledger.local", 2)
        cmd("AUTH LOGIN", 3)                           // 334
        cmd(b64(login), 3)                             // 334
        try { cmd(b64(password), 2) }                  // 235
        catch (e: Exception) { close(); throw MailException("The server rejected the username or password.") }

        cmd("MAIL FROM:<${m.fromEmail}>", 2)
        cmd("RCPT TO:<${m.toEmail}>", 2)
        cmd("DATA", 3)                                 // 354

        conn.sendRaw((buildMessage(m) + "\r\n.\r\n").toByteArray(Charsets.UTF_8))
        expect(2, "message body")                      // 250 -- the message is now accepted

        // Cleanup only (the mail is already delivered): say QUIT AFTER the 250, then read its 221 if
        // it comes. Never before the 250.
        try { conn.send("QUIT") } catch (e: Exception) { }
        try { readReply() } catch (e: Exception) { }
        close()
    }

    // Message

    private fun buildMessage(m: Outgoing): String {
        val date = smtpDate(Date())
        val fromField = if (m.fromName.isEmpty()) m.fromEmail else "${mimeName(m.fromName)} <${m.fromEmail}>"
        val headers = arrayListOf(
            "From: $fromField",
            "To: <${m.toEmail}>",
            "Subject: ${mimeName(m.subject)}",
            "Date: $date",
            "Message-ID: <${UUID.randomUUID()}@ledger.local>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: 8bit"
        )
        val irt = m.inReplyTo
        if (!irt.isNullOrEmpty()) { headers.add("In-Reply-To: $irt"); headers.add("References: $irt") }
        return headers.joinToString("\r\n") + "\r\n\r\n" + dotStuff(m.body)
    }

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
