package com.toolsboox.plugin.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A forwarded letter (message/rfc822) is read out inside the letter, not dropped (audit 09-10). */
class MimeForwardTest {
    private val eml = """
        |From: Ann <ann@example.com>
        |Subject: FWD: see below
        |Content-Type: multipart/mixed; boundary="B1"
        |
        |--B1
        |Content-Type: text/plain; charset=utf-8
        |
        |See below.
        |--B1
        |Content-Type: message/rfc822
        |
        |From: Bob <bob@example.com>
        |Date: Mon, 1 Sep 2025 10:00:00 +0000
        |Subject: The original
        |Content-Type: multipart/alternative; boundary="B2"
        |
        |--B2
        |Content-Type: text/plain; charset=utf-8
        |
        |Original words here.
        |--B2
        |Content-Type: text/html; charset=utf-8
        |
        |<p>Original <b>words</b> here.</p>
        |--B2--
        |--B1
        |Content-Type: application/pdf; name="inner.pdf"
        |Content-Disposition: attachment; filename="inner.pdf"
        |Content-Transfer-Encoding: base64
        |
        |AAAA
        |--B1--
        |""".trimMargin().replace("\n", "\r\n")

    @Test fun forwardedTextAndHtmlRideAlong() {
        val p = Mime.parse(eml.toByteArray(Charsets.UTF_8))
        assertTrue(p.text.startsWith("See below."))
        assertTrue(p.text.contains("Forwarded message"))
        assertTrue(p.text.contains("From: Bob <bob@example.com>"))
        assertTrue(p.text.contains("Original words here."))
        assertTrue(p.html.contains("<b>words</b>"))
        assertEquals(listOf("inner.pdf"), p.attachments.map { it.filename })
    }
}
