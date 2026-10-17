package com.toolsboox.plugin.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MIME regression corpus — real messages built by the sites' own PHPMailer (see the iOS twin
 * in Scripts/mime-harness). Every name, address, card and order id in them is a made-up one
 * (example.org / example.com, "Alex Example", a card shown as ***XXXX); the MIME shapes are untouched.
 * iOS lost the HTML half of most letters for weeks to a one-character slip in its multipart
 * splitter (09-08); these pin that the Kotlin parser keeps both readings, the attachments, the
 * inline images and the encoded headers, per message shape.
 */
class MimeCorpusTest {
    private fun load(name: String): Mime.Parsed {
        val bytes = javaClass.getResourceAsStream("/mime/$name.eml")!!.readBytes()
        return Mime.parse(bytes, embedCids = true)
    }

    @Test fun alternative8bit() { val p = load("alt-8bit"); assertEquals("plain alt", p.text); assertTrue(p.html.contains("<h1>Héllo — “quotes”</h1>")) }
    @Test fun alternativeQuotedPrintable() { val p = load("alt-qp"); assertTrue(p.text.startsWith("plain alt line")); assertTrue(p.html.contains("Héllo")); assertTrue(p.html.contains("example.org/x?a=1&b=2")) }
    @Test fun alternativeBase64() { val p = load("alt-base64"); assertEquals("plain alt", p.text); assertTrue(p.html.contains("Héllo")) }
    @Test fun htmlOnly() { val p = load("html-only"); assertTrue(p.html.contains("<h1>")); assertTrue(p.text.contains("Héllo")) }
    @Test fun plainOnly() { val p = load("plain-only"); assertEquals("Just plain text.\nSecond line.", p.text.replace("\r\n", "\n")); assertTrue(p.html.isBlank()) }
    @Test fun relatedCidEmbeds() { val p = load("related-cid"); assertTrue(p.html.contains("data:image/png;base64,")); assertTrue(!p.html.contains("cid:img1")) }
    @Test fun mixedAttachments() {
        val p = load("mixed-attach")
        assertTrue(p.html.contains("<h1>"))
        val names = p.attachments.map { it.filename }
        assertTrue(names.toString(), names.contains("résumé été.pdf") && names.contains("notes.txt"))
    }
    @Test fun latin1Body() { val p = load("latin1"); assertTrue(p.html.contains("Café crème à la française")) }
    @Test fun realFluentCartRenewal() {
        val p = load("phpmailer-fluentcart-renewal")
        assertTrue(p.text.startsWith("You got a new Renewal"))
        assertTrue("html must survive a PHPMailer alternative", p.html.contains("<!DOCTYPE html"))
    }
    @Test fun encodedSubject() {
        val bytes = javaClass.getResourceAsStream("/mime/alt-8bit.eml")!!.readBytes()
        val p = Mime.parse(bytes)
        assertEquals("Corpus: alt-8bit — café résumé 日本", Mime.decodeEncodedWords(p.headers["subject"] ?: ""))
    }
}
