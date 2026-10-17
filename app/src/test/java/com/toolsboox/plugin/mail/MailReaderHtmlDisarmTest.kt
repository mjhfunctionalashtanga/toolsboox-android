package com.toolsboox.plugin.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The letter runs with scripts ON now (for the highlight bridge), so the no-code promise lives
 * in [MailReaderHtml.disarm]. These pin what must be gone and what must stay.
 */
class MailReaderHtmlDisarmTest {

    private val letter = """
        <html><head><title>Hi</title>
        <link rel="stylesheet" href="https://track.example/x.css">
        <meta http-equiv="refresh" content="0;url=https://evil.example">
        <script>alert(1)</script></head>
        <body style="background:#fff" onload="alert(2)">
          <table width="600"><tr><td style="color:#333;font-size:14px">
            <p>Dear <b>reader</b>, <a href="javascript:alert(3)">click</a>
            and <a href="https://good.example/page" onclick="alert(4)">this</a>.</p>
            <img src="https://good.example/pic.png" onerror="alert(5)" style="width:100px">
            <iframe src="https://evil.example"></iframe>
            <object data="x"></object><embed src="y">
            <svg><script>alert(6)</script><a xlink:href="JavaScript:alert(7)">svg</a></svg>
            <a href="  java&#10;script:alert(8)">split</a>
          </td></tr></table>
        </body></html>
    """.trimIndent()

    @Test
    fun everythingExecutableGoes() {
        val out = MailReaderHtml.disarm(letter).lowercase()
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("<iframe"))
        assertFalse(out.contains("<object"))
        assertFalse(out.contains("<embed"))
        assertFalse(out.contains("<link"))
        assertFalse(out.contains("http-equiv"))
        assertFalse(out.contains("onload"))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("javascript:"))
        assertFalse(out.contains("alert("))
    }

    @Test
    fun theDesignStays() {
        val out = MailReaderHtml.disarm(letter)
        assertTrue(out.contains("<table width=\"600\">"))
        assertTrue(out.contains("style=\"color:#333;font-size:14px\""))
        assertTrue(out.contains("style=\"background:#fff\""))
        assertTrue(out.contains("href=\"https://good.example/page\""))
        assertTrue(out.contains("src=\"https://good.example/pic.png\""))
        assertTrue(out.contains("<b>reader</b>"))
        // A head is still there for the readings' inject() to find.
        assertTrue(out.contains("<head>"))
    }

    @Test
    fun bothReadingsRunThroughIt() {
        assertFalse(MailReaderHtml.asSent(letter).contains("alert("))
        assertFalse(MailReaderHtml.reader(letter).contains("alert("))
        // A head-less fragment still comes out as a whole document with the viewport injected.
        val frag = MailReaderHtml.asSent("<p onclick=\"x()\">bare</p>")
        assertTrue(frag.contains("viewport"))
        assertFalse(frag.contains("onclick"))
        assertTrue(frag.contains("<p>bare</p>"))
    }
}
