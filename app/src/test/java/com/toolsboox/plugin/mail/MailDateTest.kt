package com.toolsboox.plugin.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The Date: header shapes real mail carries (audit 09-10) — none may fall through to `now`. */
class MailDateTest {
    @Test fun standard() = assertEquals(1_756_987_200_000L, MailDate.parseMillis("Thu, 4 Sep 2025 12:00:00 +0000"))
    @Test fun gmtName() = assertEquals(1_756_987_200_000L, MailDate.parseMillis("Thu, 4 Sep 2025 12:00:00 GMT"))
    @Test fun twoDigitYear() = assertNotNull(MailDate.parseMillis("4 Sep 25 12:00:00 -0500"))
    @Test fun twoComments() = assertEquals(1_704_121_200_000L, MailDate.parseMillis("Mon, 1 Jan 2024 (Happy) 10:00:00 -0500 (EST)"))
    @Test fun estName() = assertEquals(1_704_121_200_000L, MailDate.parseMillis("Mon, 1 Jan 2024 10:00:00 EST"))
    @Test fun internalDate() = assertNotNull(MailDate.parseMillis("17-Jul-2026 10:30:00 +0000"))
}

class SmtpTextPartTest {
    private val smtp = SmtpClient("localhost", 465, true)
    @Test fun shortStays8bit() = assertEquals("8bit", smtp.textPart("hello\nworld").first)
    @Test fun longGoesQp() {
        val (cte, text) = smtp.textPart("x".repeat(2000) + " é\n.dot")
        assertEquals("quoted-printable", cte)
        assert(text.split("\r\n").all { it.length <= 76 }) { "a QP line ran past 76" }
        assert(text.contains("=C3=A9")) { "é not encoded" }
        assert(text.contains("\r\n..dot")) { "dot not stuffed" }
    }
}

class MimeParamTest {
    @Test fun plainQuoted() = assertEquals("a b.pdf", Mime.param("attachment; filename=\"a b.pdf\"", "filename"))
    @Test fun plainBare() = assertEquals("x.pdf", Mime.param("attachment; filename=x.pdf; size=3", "filename"))
    @Test fun rfc2231Single() = assertEquals("résumé.pdf", Mime.param("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf", "filename"))
    @Test fun rfc2231Continued() = assertEquals("résumé.pdf",
        Mime.param("attachment;\r\n filename*0*=UTF-8''r%C3%A9sum%C3%A9;\r\n filename*1=\".pdf\"", "filename"))
    @Test fun nameNotInsideFilename() = assertEquals(null, Mime.param("attachment; filename=\"x.pdf\"", "name"))
    @Test fun boundaryWithSpaces() = assertEquals("----=_Part 1=2", Mime.param("multipart/mixed;\r\n\tboundary=\"----=_Part 1=2\"", "boundary"))
    @Test fun charsetParam() = assertEquals("utf-8", Mime.param("text/plain; charset=utf-8", "charset"))
}
