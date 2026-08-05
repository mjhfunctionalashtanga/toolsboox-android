package com.toolsboox

import com.toolsboox.plugin.mail.SmtpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outgoing message, asserted at the byte level.
 *
 * This is worth more than most tests here because the blast radius is asymmetric: attachments are
 * a new feature, but they are bolted onto the ONE function that builds every message this client
 * has ever sent. A malformed boundary or a missing header would not break "replies with pictures",
 * it would break replies.
 *
 * So the no-attachment case is pinned as the unchanged single-part message it has always been, and
 * the multipart case is checked against the wire rules that actually get messages rejected: the
 * 76-column base64 wrap (RFC 2045) and the closing boundary (RFC 2046).
 */
class SmtpMultipartTest {

    private val client = SmtpClient("smtp.example.com", 465, true)

    private fun plain() = SmtpClient.Outgoing(
        fromEmail = "me@example.com", fromName = "Michael",
        toEmail = "you@example.com", subject = "Hello", body = "A letter.",
    )

    @Test
    fun `with no attachments the message is unchanged single-part`() {
        val out = client.buildMessage(plain())
        assertTrue(out.contains("Content-Type: text/plain; charset=UTF-8"))
        assertTrue(out.contains("Content-Transfer-Encoding: 8bit"))
        // No MIME machinery at all when there is nothing to separate.
        assertFalse(out.contains("multipart"))
        assertFalse(out.contains("boundary"))
        assertTrue(out.endsWith("A letter."))
    }

    @Test
    fun `an attached image produces a well-formed multipart body`() {
        val out = client.buildMessage(plain().copy(
            attachments = listOf(SmtpClient.Attachment("hand.png", "image/png", ByteArray(200) { 7 }))
        ))
        val boundary = Regex("boundary=\"([^\"]+)\"").find(out)!!.groupValues[1]

        assertTrue(out.contains("Content-Type: multipart/mixed; boundary=\"$boundary\""))
        // Text part first — it is what threads and searches, and what a reader without attachment
        // support still sees.
        val textAt = out.indexOf("Content-Type: text/plain")
        val imgAt = out.indexOf("Content-Type: image/png")
        assertTrue(textAt in 1..<imgAt)

        assertTrue(out.contains("Content-Disposition: attachment; filename=\"hand.png\""))
        assertTrue(out.contains("Content-Transfer-Encoding: base64"))
        // The closing boundary has the trailing "--" — without it the message is unterminated and
        // many servers reject it outright.
        assertTrue(out.trimEnd().endsWith("--$boundary--"))
        // Exactly two parts opened, plus the close.
        assertEquals(3, Regex(Regex.escape("--$boundary")).findAll(out).count())
    }

    @Test
    fun `base64 never exceeds the 76-column line limit`() {
        // RFC 5321 caps a wire line at 1000 octets; an unwrapped attachment is one enormous line
        // and gets refused. Checked against a payload big enough to need many wraps.
        val lines = client.base64Lines(ByteArray(5000) { it.toByte() }).split("\r\n")
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 76 })
    }

    @Test
    fun `attachment equality is by content, not reference`() {
        val a = SmtpClient.Attachment("x.png", "image/png", byteArrayOf(1, 2, 3))
        val b = SmtpClient.Attachment("x.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `dot-stuffing still applies to the text part inside a multipart`() {
        // A line that is just "." ends DATA. That hazard does not go away because the body is now
        // one part of several — it gets worse, because the message continues after it.
        val out = client.buildMessage(plain().copy(
            body = "one\n.\ntwo",
            attachments = listOf(SmtpClient.Attachment("a.png", "image/png", byteArrayOf(9))),
        ))
        assertTrue(out.contains("\r\n..\r\n"))
    }
}
