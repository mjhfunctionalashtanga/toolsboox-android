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
    val truncated: Boolean = false,   // oversized on the server; only a bounded slice was fetched
    /** The sender's original markup, "" when they sent none — see [Mime.Parsed.html]. */
    val html: String = "",
    /** `Message-ID` / `References` as received — what a reply threads onto (audit 09-10). */
    val messageId: String = "",
    val references: String = "",
    /** `In-Reply-To` as received (2026-10-10, critique C2): clients that send only this header
     *  still thread — folded into the row's references by MailSync.toInbox. */
    val inReplyTo: String = "",
    /** Attachment metadata (never the bytes — those are fetched on the tap that asks). */
    val attachments: List<Mime.Att> = emptyList(),
)

/**
 * Just enough MIME to turn a fetched RFC 822 message into headers + a readable plaintext body.
 * Walks multipart trees to prefer text/plain (falling back to stripped text/html), decodes the
 * common transfer encodings (base64, quoted-printable) and the common charsets, and unwraps the
 * =?utf-8?B?...?= encoded-words that show up in Subject/From. A direct port of iOS
 * App/Mail/MIMEParser.swift -- a pragmatic reader for a cozy inbox, not a full RFC 2045 engine.
 */
object Mime {
    /**
     * [html] is the message's ORIGINAL markup, unstripped — "" when the sender only sent text.
     *
     * The walk used to strip html to text and throw the markup away, so a designed letter arrived
     * as a wall of run-together words and there was nothing left to render properly even if you
     * wanted to. [text] is still the stripped reading (search, snippets, the corpus, and the plain
     * mode all want it); [html] is kept alongside so the reader can show the letter as sent.
     */
    data class Parsed(
        val headers: Map<String, String>, val text: String, val html: String = "",
        val attachments: List<Att> = emptyList(),
    )

    /** One attachment's metadata. [size] is the decoded estimate (base64 shrinks ~3/4) — a chip
     *  label, not an accounting figure; the true bytes arrive only on the tap that asks. */
    data class Att(val filename: String, val mime: String, val size: Int)

    fun parse(data: ByteArray, embedCids: Boolean = false): Parsed {
        // The raw message rides through as ISO-8859-1 — a byte-TRANSPARENT carrier (latin-1 maps
        // all 256 byte values 1:1 onto chars), so each part can recover its ORIGINAL bytes and
        // decode them by its own declared charset. Decoding the whole message as UTF-8 up front
        // looked fine on Gmail but quietly replaced every non-UTF-8 byte with U+FFFD before the
        // part charsets ever had a say — the charset was decided once, at the wrong layer.
        val raw = String(data, Charsets.ISO_8859_1)
        val (headBlock, body) = splitHeadersBody(raw)
        // Header values may carry raw UTF-8 bytes (sloppy senders skip encoded-words); lift them
        // out of the carrier so From/Subject read right without touching the body bytes.
        val headers = parseHeaders(headBlock).mapValues { (_, v) -> fromCarrier(v) }
        val text = extractText(headers, body).trim()
        var html = extractHtml(headers, body).trim()
        // Inline cid: images embed as data: URIs so they render in place (they arrived WITH the
        // letter and cost no request — [MailReaderHtml.blockRemote] leaves data: alone). Only on
        // a WHOLE message: a truncated slice's base64 is cut mid-part and would embed garbage.
        if (embedCids && html.isNotBlank()) html = embedCidImages(html, headers, body)
        // Forwarded messages and bounce reports (audit 09-10): a `message/rfc822` part is a whole
        // letter inside the letter, and `message/delivery-status` is the reason a bounce bounced.
        // Neither has a filename, so both used to vanish — "FWD: see below" arrived as one line.
        val fwdText = StringBuilder(); val fwdHtml = StringBuilder(); val fwdAtts = ArrayList<Att>()
        var nestedSeen = 0
        walkParts(headers, body) { phs, pb ->
            val ct = (phs["content-type"] ?: "").substringBefore(';').trim().lowercase()
            when (ct) {
                "message/rfc822" -> {
                    if (nestedSeen >= 4) return@walkParts
                    nestedSeen++
                    val nested = runCatching { parse(pb.toByteArray(Charsets.ISO_8859_1), embedCids) }.getOrNull() ?: return@walkParts
                    val from = decodeEncodedWords(nested.headers["from"] ?: "").trim()
                    val subj = decodeEncodedWords(nested.headers["subject"] ?: "").trim()
                    val date = (nested.headers["date"] ?: "").trim()
                    fwdText.append("\n\n———— Forwarded message ————\nFrom: ").append(from)
                        .append("\nDate: ").append(date).append("\nSubject: ").append(subj).append("\n\n").append(nested.text)
                    fwdHtml.append("<hr/><p><b>Forwarded message</b><br/>From: ").append(escapeHtml(from))
                        .append("<br/>Date: ").append(escapeHtml(date)).append("<br/>Subject: ").append(escapeHtml(subj)).append("</p>")
                        .append(nested.html.ifBlank { "<pre style=\"white-space:pre-wrap\">" + escapeHtml(nested.text) + "</pre>" })
                    fwdAtts.addAll(nested.attachments)
                }
                "message/delivery-status" -> {
                    val status = decodeBody(phs, pb).trim()
                    if (status.isNotBlank()) {
                        fwdText.append("\n\n———— Delivery status ————\n").append(status)
                        fwdHtml.append("<hr/><p><b>Delivery status</b></p><pre style=\"white-space:pre-wrap\">").append(escapeHtml(status)).append("</pre>")
                    }
                }
            }
        }
        val fullText = (text + fwdText).trim()
        val fullHtml = if (fwdHtml.isEmpty()) html
            else (html.ifBlank { "<p>" + escapeHtml(text).replace("\n", "<br/>") + "</p>" }) + fwdHtml
        return Parsed(headers, fullText, fullHtml, collectAttachments(headers, body) + fwdAtts)
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Every leaf part, depth-first — the one walk the attachment roster, the cid embeds and the
     * on-demand byte carve all ride, so the three can never disagree about what a "part" is.
     */
    private fun walkParts(headers: Map<String, String>, body: String, visit: (Map<String, String>, String) -> Unit) {
        val ctype = (headers["content-type"] ?: "text/plain").lowercase()
        if (ctype.contains("multipart/")) {
            val boundary = param(headers["content-type"] ?: "", "boundary") ?: return
            for (part in splitMultipart(body, boundary)) {
                val (ph, pb) = splitHeadersBody(part)
                val phs = parseHeaders(ph)
                if ((phs["content-type"] ?: "").lowercase().contains("multipart/")) walkParts(phs, pb, visit)
                else visit(phs, pb)
            }
        } else visit(headers, body)
    }

    /** The part's declared filename (disposition first, content-type name second), decoded. */
    private fun partFilename(phs: Map<String, String>): String? {
        val name = param(phs["content-disposition"] ?: "", "filename")
            ?: param(phs["content-type"] ?: "", "name")
        return name?.let { decodeEncodedWords(it).trim().ifBlank { null } }
    }

    /**
     * The attachment roster — parts dispositioned `attachment`, or carrying a filename that isn't
     * an inline cid image (those are the letter's own pictures, embedded in place, not files).
     * The iPad's MIMEParser used to `continue` past these and the letter view could never know a
     * file existed; this walk is the Android side of that fix.
     */
    private fun collectAttachments(headers: Map<String, String>, body: String): List<Att> {
        val out = ArrayList<Att>()
        walkParts(headers, body) { phs, pb ->
            val disp = (phs["content-disposition"] ?: "").lowercase()
            val name = partFilename(phs) ?: return@walkParts
            val mime = (phs["content-type"] ?: "application/octet-stream")
                .substringBefore(';').trim().lowercase().ifBlank { "application/octet-stream" }
            // The common inline shape is `image/png; name=logo.png` + Content-ID and NO disposition
            // (audit 09-10): those pictures are already in the letter, not chips under it — unless
            // the sender explicitly dispositioned them as a file. Matches the iPad's rule.
            val inlineCid = phs["content-id"] != null && mime.startsWith("image/") && !disp.contains("attachment")
            if (!inlineCid) {
                val cte = (phs["content-transfer-encoding"] ?: "").lowercase()
                val size = if (cte == "base64") pb.length * 3 / 4 else pb.length
                out.add(Att(name, mime, size))
            }
        }
        return out
    }

    private const val CID_IMAGE_CAP = 600_000       // one image past this stays a broken ref
    private const val CID_MESSAGE_CAP = 3_000_000   // total embedded bytes per letter

    /** `cid:` refs become `data:` URIs, capped per image and per message (the iPad's caps). */
    private fun embedCidImages(html: String, headers: Map<String, String>, body: String): String {
        if (!html.contains("cid:", ignoreCase = true)) return html
        var out = html
        var budget = CID_MESSAGE_CAP
        walkParts(headers, body) { phs, pb ->
            val cid = phs["content-id"]?.trim()?.removeSurrounding("<", ">") ?: return@walkParts
            val ctype = (phs["content-type"] ?: "").substringBefore(';').trim().lowercase()
            if (!ctype.startsWith("image/")) return@walkParts
            // Whole token only (audit 09-10): a prefix replace turned `cid:logo10` into logo1's
            // bytes plus a stray "0".
            val token = Regex("(?i)cid:" + Regex.escape(cid) + "(?=[\"'\\s)>]|$)")
            if (!token.containsMatchIn(out)) return@walkParts
            val bytes = decodeBodyData(phs, pb)
            if (bytes.isEmpty() || bytes.size > CID_IMAGE_CAP || bytes.size > budget) return@walkParts
            budget -= bytes.size
            val uri = "data:$ctype;base64," + Base64.getEncoder().encodeToString(bytes)
            out = token.replace(out) { uri }
        }
        return out
    }

    /**
     * Carve one attachment's BYTES out of a whole raw message, by filename — the tap-to-view
     * path. Returns bytes + mime, or null when the name isn't found (the message changed on the
     * server between the roster and the tap — say so, don't crash).
     */
    fun partBytes(data: ByteArray, filename: String): Pair<ByteArray, String>? {
        val raw = String(data, Charsets.ISO_8859_1)
        val (headBlock, body) = splitHeadersBody(raw)
        val headers = parseHeaders(headBlock)
        var found: Pair<ByteArray, String>? = null
        walkParts(headers, body) { phs, pb ->
            if (found == null && partFilename(phs) == filename) {
                val mime = (phs["content-type"] ?: "application/octet-stream")
                    .substringBefore(';').trim().lowercase().ifBlank { "application/octet-stream" }
                found = decodeBodyData(phs, pb) to mime
            }
        }
        return found
    }

    /**
     * The message's original html, or "" when it sent none.
     *
     * A separate walk from [extractText] rather than a second return value threaded through it:
     * that function's job is "give me the best READABLE text", and it returns early the moment it
     * finds a text/plain part — which is exactly when the html part still exists and is the thing
     * this wants. Teaching it to carry both would mean it could no longer return early, and the
     * early return is the behaviour that keeps a plain-text part winning over a marketing shell.
     *
     * Picks the first html part with real markup in it, depth-first, for the same reason the text
     * walk skips empty html parts: an empty lead-in part (a tracking shell, a bare wrapper div) is
     * common and would otherwise win over the part holding the actual letter.
     */
    private fun extractHtml(headers: Map<String, String>, body: String): String {
        val ctype = (headers["content-type"] ?: "text/plain").lowercase()
        if (ctype.contains("multipart/")) {
            val boundary = param(headers["content-type"] ?: "", "boundary") ?: return ""
            for (part in splitMultipart(body, boundary)) {
                val (ph, pb) = splitHeadersBody(part)
                val phs = parseHeaders(ph)
                val pct = (phs["content-type"] ?: "text/plain").lowercase()
                when {
                    pct.contains("multipart/") -> {
                        val nested = extractHtml(phs, pb)
                        if (nested.isNotBlank()) return nested
                    }
                    // An attached .html file is a FILE, not the letter (audit 09-10; iOS already
                    // guarded this): skipped here, listed by collectAttachments.
                    (phs["content-disposition"] ?: "").lowercase().contains("attachment") -> Unit
                    pct.contains("text/html") -> {
                        val h = decodeBody(phs, pb)
                        // "Real markup" = it strips to something. A part that's all tracking
                        // pixel and no words is not the letter.
                        if (stripHtml(h).isNotBlank()) return h
                    }
                }
            }
            return ""
        }
        return if (ctype.contains("text/html")) decodeBody(headers, body) else ""
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
                            if (nested.isNotBlank()) return nested
                        }
                        // An attached .txt is a file, not the message (audit 09-10).
                        (phs["content-disposition"] ?: "").lowercase().contains("attachment") -> Unit
                        pct.contains("text/plain") -> {
                            val t = decodeBody(phs, pb)
                            if (t.isNotBlank()) return t
                        }
                        pct.contains("text/html") -> {
                            // Keep the first html part that strips to something READABLE. Locking
                            // in whichever html part came first meant an empty lead-in part (a
                            // tracking shell, a bare wrapper div) blanked the whole message even
                            // when the next html part held all the text.
                            if (htmlFallback.isNullOrBlank()) {
                                val h = stripHtml(decodeBody(phs, pb)).trim()
                                if (h.isNotBlank()) htmlFallback = h
                            }
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
        // Everything before the FIRST delimiter is the preamble ("This is a multi-part message
        // in MIME format.") — never a part, so it must never be mistaken for the body.
        for (chunk in body.split(delim).drop(1)) {
            var c = chunk
            if (c.startsWith("\r\n")) c = c.substring(2) else if (c.startsWith("\n")) c = c.substring(1)
            if (c.startsWith("--") || c.trim().isEmpty()) continue
            parts.add(c)
        }
        return parts
    }

    /** Decode a single part's body per its Content-Transfer-Encoding + charset. The part rides in
     *  as carrier text (ISO-8859-1 chars ≡ original bytes), so ISO_8859_1 here RECOVERS bytes —
     *  it is not a guess about the part's language. */
    private fun decodeBody(headers: Map<String, String>, body: String): String =
        decodeCharset(decodeBodyData(headers, body), param(headers["content-type"] ?: "", "charset")?.lowercase())

    /** The transfer-decode alone — the byte truth an attachment or cid image wants whole. */
    private fun decodeBodyData(headers: Map<String, String>, body: String): ByteArray {
        val cte = (headers["content-transfer-encoding"] ?: "").lowercase()
        return when (cte) {
            "base64" -> try {
                Base64.getMimeDecoder().decode(body.filter { !it.isWhitespace() })
            } catch (e: Exception) { body.toByteArray(Charsets.ISO_8859_1) }
            "quoted-printable" -> decodeQuotedPrintable(body)
            else -> body.toByteArray(Charsets.ISO_8859_1)   // 7bit/8bit/binary: the original bytes
        }
    }

    // Encodings

    fun decodeQuotedPrintable(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        val chars = s.toByteArray(Charsets.ISO_8859_1)   // carrier chars back to their bytes
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
        val name = charset?.trim()?.trim('"')
        if (name.isNullOrBlank()) return utf8OrLatin1(data)
        return try {
            when {
                // Mislabeled "utf-8" is common enough that a strict decode with a latin-1 net
                // beats trusting the label outright.
                name.contains("utf-8") -> utf8OrLatin1(data)
                // Any charset the platform knows (gb2312, shift_jis, koi8-r, iso-2022-jp…) —
                // the old three-name whitelist quietly mangled everything else.
                java.nio.charset.Charset.isSupported(name) ->
                    String(data, java.nio.charset.Charset.forName(name))
                else -> utf8OrLatin1(data)
            }
        } catch (e: Exception) { utf8OrLatin1(data) }
    }

    /** Strict UTF-8 or honest latin-1 — never U+FFFD confetti. The old `String(data, UTF_8)`
     *  NEVER throws (the constructor substitutes malformed input), so its latin-1 "fallback" was
     *  dead code and every non-UTF-8 byte rendered as �. A REPORTing decoder actually falls back. */
    private fun utf8OrLatin1(data: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data)).toString()
    } catch (e: Exception) { String(data, Charsets.ISO_8859_1) }

    /** Lift a header value out of the byte-transparent carrier: its latin-1 chars ARE the raw
     *  bytes, which are usually ASCII or raw UTF-8 from senders who skip encoded-words. */
    private fun fromCarrier(s: String): String = utf8OrLatin1(s.toByteArray(Charsets.ISO_8859_1))

    /** A key="value" / key=value parameter from a header value (content-type, etc.). */
    /**
     * A header parameter, RFC 2231 included (audit 09-10): `filename*=UTF-8''r%C3%A9sum%C3%A9.pdf`,
     * split continuations `filename*0*=…; filename*1=…`, and the plain `filename="…"` — matched at a
     * parameter boundary, so `name=` never matches inside `filename=`. The old scan looked for the
     * literal `key=` only, so every 2231 attachment was listed as "attachment.pdf".
     */
    internal fun param(header: String, key: String): String? {
        // key=value  |  key*=charset'lang'pct  |  key*N=value  |  key*N*=pct
        val re = Regex("(?i)(?:^|;)\\s*" + Regex.escape(key) + "(\\*(\\d+))?(\\*)?\\s*=\\s*(\"([^\"]*)\"|[^;\\s]+)")
        val hits = re.findAll(header).toList()
        if (hits.isEmpty()) return null
        var charset: String? = null
        fun value(m: MatchResult): String = m.groupValues[5].ifEmpty { m.groupValues[4] }
        fun pct(v: String): String {
            // charset'lang'value on the first extended piece; later pieces are bare pct-encoded
            var body = v
            if (charset == null && v.count { it == '\'' } >= 2) {
                charset = v.substringBefore('\'').ifBlank { "UTF-8" }
                body = v.substringAfter('\'').substringAfter('\'')
            }
            val cs = runCatching { java.nio.charset.Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '%' && i + 2 < body.length + 0 && i + 2 <= body.length - 1 && body[i + 1].isHex() && body[i + 2].isHex()) {
                    bytes.write(body.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { bytes.write(c.toString().toByteArray(cs)); i += 1 }
            }
            return String(bytes.toByteArray(), cs)
        }
        val plain = hits.firstOrNull { it.groupValues[1].isEmpty() && it.groupValues[3].isEmpty() }
        val extended = hits.filter { it.groupValues[1].isNotEmpty() || it.groupValues[3].isNotEmpty() }
        if (extended.isEmpty()) return plain?.let { value(it) }
        // Continuations in section order; a single `key*=` is section 0.
        val sb = StringBuilder()
        for (m in extended.sortedBy { it.groupValues[2].toIntOrNull() ?: 0 }) {
            val v = value(m)
            sb.append(if (m.groupValues[3].isNotEmpty()) pct(v) else v)
        }
        return sb.toString().ifBlank { plain?.let { value(it) } }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

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
        "d-MMM-yyyy HH:mm:ss Z",                // IMAP INTERNALDATE ("17-Jul-2026 10:30:00 +0000")
        "EEE, d MMM yy HH:mm:ss Z", "d MMM yy HH:mm:ss Z",   // obsolete two-digit years (list servers)
        "EEE, d MMM yyyy HH:mm:ss", "d MMM yyyy HH:mm:ss",
    )
    // Obsolete zone names → numeric offsets (RFC 5322 §4.3); SimpleDateFormat's Z reads only numbers reliably.
    private val zones = mapOf("UT" to "+0000", "GMT" to "+0000", "Z" to "+0000",
        "EST" to "-0500", "EDT" to "-0400", "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600", "PST" to "-0800", "PDT" to "-0700")

    /** Epoch millis, or null if none of the formats match. */
    fun parseMillis(s: String): Long? {
        // Non-greedy comment strip (audit 09-10): `\(.*\)` ate everything between two comments.
        var cleaned = s.replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.replace(Regex("\\b(UT|GMT|Z|[ECMP][SD]T)$")) { zones[it.value] ?: it.value }
        for (fmt in formats) {
            try {
                val f = SimpleDateFormat(fmt, Locale.US); f.isLenient = false
                return (f.parse(cleaned) ?: continue).time
            } catch (e: Exception) { /* try the next format */ }
        }
        return null
    }
}
