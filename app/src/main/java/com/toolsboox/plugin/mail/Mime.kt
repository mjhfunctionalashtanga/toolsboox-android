package com.toolsboox.plugin.mail

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale

/** One message pulled off a server -- the shape the inbox needs. Mirrors iOS FetchedMessage. */
data class FetchedMessage(
    val uid: String,
    val subject: String,
    val fromName: String,
    val fromEmail: String,
    val date: Long,          // epoch millis
    val body: String,
)

/**
 * Just enough MIME to turn a fetched RFC 822 message into headers + a readable plaintext body.
 * Walks multipart trees to prefer text/plain (falling back to stripped text/html), decodes the
 * common transfer encodings (base64, quoted-printable) and the common charsets, and unwraps the
 * =?utf-8?B?...?= encoded-words that show up in Subject/From. A direct port of iOS
 * App/Mail/MIMEParser.swift -- a pragmatic reader for a cozy inbox, not a full RFC 2045 engine.
 */
object Mime {
    data class Parsed(val headers: Map<String, String>, val text: String)

    fun parse(data: ByteArray): Parsed {
        val raw = latinOrUtf8(data)
        val (headBlock, body) = splitHeadersBody(raw)
        val headers = parseHeaders(headBlock)
        val text = extractText(headers, body).trim()
        return Parsed(headers, text)
    }

    // Headers

    /** Split at the first blank line; everything before is the header block. */
    private fun splitHeadersBody(s: String): Pair<String, String> {
        val i = s.indexOf("\r\n\r\n")
        if (i >= 0) return s.substring(0, i) to s.substring(i + 4)
        val j = s.indexOf("\n\n")
        if (j >= 0) return s.substring(0, j) to s.substring(j + 2)
        return s to ""
    }

    /** Unfold continuation lines, split Key: value, lowercase the keys. */
    fun parseHeaders(block: String): Map<String, String> {
        val unfolded = ArrayList<String>()
        for (line in block.replace("\r\n", "\n").split("\n")) {
            val first = line.firstOrNull()
            if ((first == ' ' || first == '\t') && unfolded.isNotEmpty()) {
                unfolded[unfolded.size - 1] += " " + line.trim()
            } else unfolded.add(line)
        }
        val out = LinkedHashMap<String, String>()
        for (line in unfolded) {
            val c = line.indexOf(':')
            if (c < 0) continue
            val key = line.substring(0, c).trim().lowercase()
            val v = line.substring(c + 1).trim()
            if (key.isNotEmpty()) out[key] = v
        }
        return out
    }

    // Body

    private fun extractText(headers: Map<String, String>, body: String): String {
        val ctype = (headers["content-type"] ?: "text/plain").lowercase()

        if (ctype.contains("multipart/")) {
            val boundary = param(headers["content-type"] ?: "", "boundary")
            if (boundary != null) {
                val parts = splitMultipart(body, boundary)
                // Prefer text/plain; fall back to text/html (stripped). Depth-first through nested parts.
                var htmlFallback: String? = null
                for (part in parts) {
                    val (ph, pb) = splitHeadersBody(part)
                    val phs = parseHeaders(ph)
                    val pct = (phs["content-type"] ?: "text/plain").lowercase()
                    when {
                        pct.contains("multipart/") -> {
                            val nested = extractText(phs, pb)
                            if (nested.isNotEmpty()) return nested
                        }
                        pct.contains("text/plain") -> {
                            val t = decodeBody(phs, pb)
                            if (t.trim().isNotEmpty()) return t
                        }
                        pct.contains("text/html") && htmlFallback == null -> {
                            htmlFallback = stripHtml(decodeBody(phs, pb))
                        }
                    }
                }
                return htmlFallback ?: ""
            }
        }

        val decoded = decodeBody(headers, body)
        return if (ctype.contains("text/html")) stripHtml(decoded) else decoded
    }

    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delim = "--$boundary"
        val parts = ArrayList<String>()
        for (chunk in body.split(delim)) {
            var c = chunk
            if (c.startsWith("\r\n")) c = c.substring(2) else if (c.startsWith("\n")) c = c.substring(1)
            if (c.startsWith("--") || c.trim().isEmpty()) continue
            parts.add(c)
        }
        return parts
    }

    /** Decode a single part's body per its Content-Transfer-Encoding + charset. */
    private fun decodeBody(headers: Map<String, String>, body: String): String {
        val cte = (headers["content-transfer-encoding"] ?: "").lowercase()
        val charset = param(headers["content-type"] ?: "", "charset")?.lowercase()
        val bytes: ByteArray = when (cte) {
            "base64" -> try {
                Base64.getMimeDecoder().decode(body.filter { !it.isWhitespace() })
            } catch (e: Exception) { body.toByteArray(Charsets.UTF_8) }
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> return decodeCharset(body.toByteArray(Charsets.UTF_8), charset)   // 7bit/8bit/binary: already text
        }
        return decodeCharset(bytes, charset)
    }

    // Encodings

    fun decodeQuotedPrintable(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        val chars = s.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < chars.size) {
            val b = chars[i].toInt() and 0xFF
            if (b == 0x3D) {  // '='
                val n1 = if (i + 1 < chars.size) chars[i + 1].toInt() and 0xFF else -1
                if (n1 == 0x0D || n1 == 0x0A) {  // soft line break
                    val n2 = if (i + 2 < chars.size) chars[i + 2].toInt() and 0xFF else -1
                    i += if (n1 == 0x0D && n2 == 0x0A) 3 else 2
                    continue
                }
                if (i + 2 < chars.size) {
                    val hi = hex(chars[i + 1]); val lo = hex(chars[i + 2])
                    if (hi != null && lo != null) { out.write((hi shl 4) or lo); i += 3; continue }
                }
            }
            out.write(b); i += 1
        }
        return out.toByteArray()
    }

    private fun hex(b: Byte): Int? = when (val v = b.toInt() and 0xFF) {
        in 0x30..0x39 -> v - 0x30
        in 0x41..0x46 -> v - 0x41 + 10
        in 0x61..0x66 -> v - 0x61 + 10
        else -> null
    }

    private fun decodeCharset(data: ByteArray, charset: String?): String {
        if (charset == null) return latinOrUtf8(data)
        return try {
            when {
                charset.contains("utf-8") -> String(data, Charsets.UTF_8)
                charset.contains("iso-8859-1") || charset.contains("latin1") -> String(data, Charsets.ISO_8859_1)
                charset.contains("windows-1252") || charset.contains("cp1252") -> String(data, charset("windows-1252"))
                else -> latinOrUtf8(data)
            }
        } catch (e: Exception) { latinOrUtf8(data) }
    }

    private fun latinOrUtf8(data: ByteArray): String =
        try { String(data, Charsets.UTF_8) } catch (e: Exception) { String(data, Charsets.ISO_8859_1) }

    /** A key="value" / key=value parameter from a header value (content-type, etc.). */
    private fun param(header: String, key: String): String? {
        val idx = header.indexOf("$key=", ignoreCase = true)
        if (idx < 0) return null
        var rest = header.substring(idx + key.length + 1)
        if (rest.startsWith("\"")) {
            rest = rest.substring(1)
            val end = rest.indexOf('"')
            if (end >= 0) return rest.substring(0, end)
        }
        val end = rest.indexOfFirst { it == ';' || it == ' ' }
        return (if (end < 0) rest else rest.substring(0, end)).trim()
    }

    // Encoded-words (Subject / From)

    /** Decode any =?charset?B?...?= / =?charset?Q?...?= encoded-words in a header value. */
    fun decodeEncodedWords(s: String): String {
        if (!s.contains("=?")) return s
        val result = StringBuilder()
        var rest = s
        while (true) {
            val start = rest.indexOf("=?")
            if (start < 0) break
            result.append(rest, 0, start)
            val after = rest.substring(start + 2)
            // charset ? enc ? text ?=
            val c1 = after.indexOf('?')
            if (c1 < 0) { result.append("=?"); rest = after; continue }
            val charset = after.substring(0, c1).lowercase()
            val afterCharset = after.substring(c1 + 1)
            val c2 = afterCharset.indexOf('?')
            if (c2 < 0) { result.append("=?"); rest = after; continue }
            val enc = afterCharset.substring(0, c2).lowercase()
            val afterEnc = afterCharset.substring(c2 + 1)
            val close = afterEnc.indexOf("?=")
            if (close < 0) { result.append("=?"); rest = after; continue }
            val payload = afterEnc.substring(0, close)
            val data: ByteArray = if (enc == "b") {
                try { Base64.getMimeDecoder().decode(payload.filter { !it.isWhitespace() }) } catch (e: Exception) { ByteArray(0) }
            } else {
                decodeQuotedPrintable(payload.replace('_', ' '))
            }
            result.append(decodeCharset(data, charset))
            rest = afterEnc.substring(close + 2)
            // Skip whitespace that only separates adjacent encoded-words.
            val next = rest.indexOf("=?")
            if (next >= 0 && rest.substring(0, next).all { it == ' ' || it == '\t' }) rest = rest.substring(next)
        }
        result.append(rest)
        return result.toString()
    }

    // HTML

    /** A rough text/html to text: drop script/style (DOTALL, so multi-line blocks go too), tags to
     *  spaces, decode a few entities. */
    fun stripHtml(html: String): String {
        var s = html.replace(
            Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " "
        )
        s = s.replace(Regex("<[^>]+>"), " ")
        for ((a, b) in listOf(
            "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&nbsp;" to " ", "&rsquo;" to "’", "&mdash;" to "—"
        )) s = s.replace(a, b)
        return s.replace(Regex("[ \\t]*\\n[ \\t]*(\\n[ \\t]*)+"), "\n\n")
            .replace(Regex("[ \\t]{2,}"), " ")
    }
}

/** Parse From: style values into a display name + address. Mirrors iOS AddressParse. */
object AddressParse {
    fun nameAndEmail(raw: String): Pair<String, String> {
        val decoded = Mime.decodeEncodedWords(raw).trim()
        val lt = decoded.lastIndexOf('<')
        val gt = decoded.lastIndexOf('>')
        if (lt >= 0 && gt >= 0 && lt < gt) {
            val email = decoded.substring(lt + 1, gt).trim()
            val name = decoded.substring(0, lt).trim().trim('"', '\'')
            return (if (name.isEmpty()) email else name) to email
        }
        return decoded to decoded
    }
}

/** RFC 822 date parsing (with a couple of common variants), for the message date. Mirrors iOS MailDate. */
object MailDate {
    private val formats = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z", "EEE, d MMM yyyy HH:mm:ss",
        "d-MMM-yyyy HH:mm:ss Z"                 // IMAP INTERNALDATE ("17-Jul-2026 10:30:00 +0000")
    )

    /** Epoch millis, or null if none of the formats match. */
    fun parseMillis(s: String): Long? {
        val cleaned = s.replace(Regex("\\(.*\\)"), "").trim()
        for (fmt in formats) {
            try {
                val f = SimpleDateFormat(fmt, Locale.US); f.isLenient = false
                return (f.parse(cleaned) ?: continue).time
            } catch (e: Exception) { /* try the next format */ }
        }
        return null
    }
}
