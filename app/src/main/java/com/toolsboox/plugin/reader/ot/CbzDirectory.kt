package com.toolsboox.plugin.reader.ot

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * 🗜 A CBZ READ THE WAY THE READER READS IT — by its CENTRAL DIRECTORY, over a seekable channel.
 *
 * Moved here from BookCovers (Michael 2026-10-11, comics slice 2) so the cover pass and the
 * balloon index share one zip reader. The reason it exists at all is BookCovers' 09-04 root
 * cause: Mihon/keiyoushi/Suwayomi write STORED members that carry a DATA DESCRIPTOR (flag bit 3,
 * local sizes zero), and `ZipInputStream` throws "only DEFLATED entries can have EXT descriptor"
 * on the very first one. The balloon index still used `ZipInputStream`, so on exactly the books
 * the fleet reads most (the Palma's Suwayomi downloads) it read NO pages and every page showed
 * whole. The central directory always has the real sizes and offsets, and it gives random access
 * — a page is read when it is wanted, in any order.
 *
 * Three rules live here because three callers must agree on them byte for byte:
 *  - **Names** decode the way zip.js decodes them (foliate's CBZ loader): UTF-8 when flag bit 11
 *    is set, else the Info-ZIP Unicode Path extra field (0x7075) when its CRC matches the raw
 *    name, else CP437. A page's spine index is its position among these names, so a name decoded
 *    differently here would hand balloons to the wrong page.
 *  - **The page order** ([pages], [naturalCompare], [isPageName]) — comic-book.js carries the
 *    same rule in JavaScript; BalloonIndexTest and scripts/test-comic-page-order.mjs pin the same
 *    vectors on both sides.
 *  - **The `cbz1` key** ([cbz1Key]) — the balloon cache's identity, independent of folder and
 *    device; pinned by a test vector so the iOS twin can match it exactly.
 *
 * Zip64 archives (over 65k members or 4 GB) are declined — a comic is neither.
 */
object CbzDirectory {

    /** One central-directory record. [rawName] is the name as stored; [name] as zip.js decodes it. */
    class Member(
        val name: String, val rawName: ByteArray, val method: Int, val crc: Long,
        val csize: Long, val usize: Long, val localOffset: Long,
    )

    /** A closeable seekable channel over a book — a File directly, or the resolver's descriptor
     *  for a tree document (the ExternalStorage provider backs it with a real file). */
    class Chan(val channel: FileChannel, private val closeables: List<AutoCloseable>) : AutoCloseable {
        override fun close() { closeables.forEach { runCatching { it.close() } } }
    }

    /** No page is bigger than this; a member that claims more is refused, not inflated. */
    private const val MAX_MEMBER = 64L * 1024 * 1024

    // comic-book.js's list. Matched CASE-INSENSITIVELY (ASCII only, so both languages agree on
    // every name): a `.JPG` page used to vanish from the book and from the index alike.
    private val EXTS = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg", ".jxl", ".avif")

    /**
     * Open [file], or [uri] through the resolver, as a seekable channel. A provider that only
     * streams (a pipe has no size) is not seekable: then null — unless [spool], which copies the
     * stream once into the cache and deletes the copy on close (the balloon pass needs random
     * access; a cover hunt has its own streaming fallback and passes false).
     */
    fun open(context: Context, file: File?, uri: Uri?, spool: Boolean = false): Chan? {
        file?.let { f ->
            val fis = runCatching { java.io.FileInputStream(f) }.getOrNull() ?: return null
            return Chan(fis.channel, listOf(fis))
        }
        uri ?: return null
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val fis = java.io.FileInputStream(pfd.fileDescriptor)
            val ch = fis.channel
            if (runCatching { ch.size() }.getOrDefault(0L) > 0L) return Chan(ch, listOf(fis, pfd))
            runCatching { fis.close() }; runCatching { pfd.close() }
        }
        if (!spool) return null
        // A spool left behind by a process that died mid-pass is swept on the next one.
        runCatching {
            val stale = System.currentTimeMillis() - 6 * 3600_000L
            context.cacheDir.listFiles { f -> f.name.startsWith("cbz-spool-") && f.lastModified() < stale }
                ?.forEach { it.delete() }
        }
        val tmp = File(context.cacheDir, "cbz-spool-${System.nanoTime()}.cbz")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); return null }
        val fis = runCatching { java.io.FileInputStream(tmp) }.getOrNull() ?: run { tmp.delete(); return null }
        return Chan(fis.channel, listOf(fis, AutoCloseable { tmp.delete() }))
    }

    private fun readFully(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n < 0) break
            p += n
        }
        b.flip()
        return b
    }

    /** Every central-directory record, in stored order. Empty when this is not a zip we read. */
    fun read(ch: FileChannel): List<Member> {
        val size = ch.size()
        if (size < 22) return emptyList()
        // EOCD: 22 bytes + up to 65535 of comment, at the very end. Scan backwards for its signature.
        val tailLen = minOf(size, 22L + 65535L).toInt()
        val tail = readFully(ch, size - tailLen, tailLen)
        var eocd = -1
        var i = tailLen - 22
        while (i >= 0) {
            if (tail.getInt(i) == 0x06054b50) { eocd = i; break }
            i--
        }
        if (eocd < 0) return emptyList()
        val count = tail.getShort(eocd + 10).toInt() and 0xffff
        val cdSize = tail.getInt(eocd + 12).toLong() and 0xffffffffL
        val cdOffset = tail.getInt(eocd + 16).toLong() and 0xffffffffL
        if (count == 0xffff || cdOffset == 0xffffffffL) return emptyList()   // zip64: not for comics
        // A directory claiming more bytes than lie between its offset and the end of the file is
        // a corrupt archive, not a reason to allocate gigabytes — this runs on every comic open.
        if (cdOffset > size || cdSize > size - cdOffset || cdSize > Int.MAX_VALUE) return emptyList()
        val cd = readFully(ch, cdOffset, cdSize.toInt())
        val out = ArrayList<Member>(count)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.limit() || cd.getInt(p) != 0x02014b50) return out
            val flags = cd.getShort(p + 8).toInt() and 0xffff
            val method = cd.getShort(p + 10).toInt() and 0xffff
            val crc = cd.getInt(p + 16).toLong() and 0xffffffffL
            val csize = cd.getInt(p + 20).toLong() and 0xffffffffL
            val usize = cd.getInt(p + 24).toLong() and 0xffffffffL
            val nlen = cd.getShort(p + 28).toInt() and 0xffff
            val xlen = cd.getShort(p + 30).toInt() and 0xffff
            val clen = cd.getShort(p + 32).toInt() and 0xffff
            val local = cd.getInt(p + 42).toLong() and 0xffffffffL
            if (p + 46 + nlen + xlen > cd.limit()) return out
            val nameBytes = ByteArray(nlen)
            cd.position(p + 46); cd.get(nameBytes)
            val extra = ByteArray(xlen)
            cd.get(extra); cd.position(0)
            out += Member(decodeName(nameBytes, flags, extra), nameBytes, method, crc, csize, usize, local)
            p += 46 + nlen + xlen + clen
        }
        return out
    }

    /** The member's bytes, inflated if it was deflated. Null for methods we don't handle. */
    fun bytes(ch: FileChannel, m: Member): ByteArray? {
        if (m.method != 0 && m.method != 8) return null
        if (m.csize > MAX_MEMBER || m.usize > MAX_MEMBER) return null   // no page is that big
        val lh = readFully(ch, m.localOffset, 30)
        if (lh.limit() < 30 || lh.getInt(0) != 0x04034b50) return null
        val nlen = lh.getShort(26).toInt() and 0xffff
        val xlen = lh.getShort(28).toInt() and 0xffff
        val data = readFully(ch, m.localOffset + 30 + nlen + xlen, m.csize.toInt())
        val raw = ByteArray(data.limit()); data.get(raw)
        if (m.method == 0) return raw
        val inf = java.util.zip.Inflater(true)
        return try {
            inf.setInput(raw)
            val out = ByteArray(m.usize.toInt())
            var got = 0
            while (got < out.size && !inf.finished()) {
                val n = inf.inflate(out, got, out.size - got)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                got += n
            }
            if (got == out.size) out else out.copyOf(got)
        } finally { inf.end() }
    }

    // ---- names, the zip.js way ------------------------------------------------------------

    // zip.js's CP437 table, exactly (its control bytes are the DOS glyphs, 0x7F is ⌂, and 0xFF
    // is a plain space) — a name that decodes differently here is a page index that drifts.
    private const val CP437_LOW =
        "\u0000☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼"
    private const val CP437_HIGH =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
        "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

    /** zip.js's rule: flag bit 11 → UTF-8; else a valid Unicode Path field; else CP437. */
    internal fun decodeName(raw: ByteArray, flags: Int, extra: ByteArray): String {
        if (flags and 0x0800 != 0) return String(raw, Charsets.UTF_8)
        unicodePath(raw, extra)?.let { return it }
        val sb = StringBuilder(raw.size)
        for (b in raw) {
            val c = b.toInt() and 0xff
            sb.append(when {
                c < 0x20 -> CP437_LOW[c]
                c < 0x7f -> c.toChar()
                c == 0x7f -> '\u2302'
                else -> CP437_HIGH[c - 0x80]
            })
        }
        return sb.toString()
    }

    /** Info-ZIP Unicode Path (0x7075): version 1, CRC-32 of the raw name, then the UTF-8 name. */
    private fun unicodePath(raw: ByteArray, extra: ByteArray): String? {
        val b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 4 <= extra.size) {
            val id = b.getShort(p).toInt() and 0xffff
            val len = b.getShort(p + 2).toInt() and 0xffff
            if (p + 4 + len > extra.size) return null
            if (id == 0x7075 && len >= 5 && extra[p + 4].toInt() == 1) {
                val crc = b.getInt(p + 5).toLong() and 0xffffffffL
                val c = java.util.zip.CRC32().apply { update(raw) }.value
                if (crc == c) return String(extra, p + 9, len - 5, Charsets.UTF_8)
            }
            p += 4 + len
        }
        return null
    }

    // ---- the page order (twin: comic-book.js) ----------------------------------------------

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    /** An image page by comic-book.js's list, extension matched ASCII-case-insensitively. */
    fun isPageName(name: String): Boolean {
        if (name.endsWith("/")) return false
        val tail = name.takeLast(6).map(::asciiLower).joinToString("")
        return EXTS.any { tail.endsWith(it) }
    }

    /**
     * NATURAL page order (Mihon's ArchivePageLoader rule, our own comparison — Michael
     * 2026-10-11): `page2` before `page10`. Written out by hand, never a locale sort, so Kotlin,
     * JavaScript (comic-book.js) and Swift (the iPad's BalloonGeometry.naturalLess) agree on
     * every name:
     *  - runs of ASCII digits compare as numbers (leading zeros ignored; by length, then digit by
     *    digit, so no run is ever too long to compare);
     *  - every other UTF-16 code unit compares AS ITSELF — case is not folded, as on the iPad, so
     *    one CBZ has the same page numbers on both apps and only digit runs differ from the old
     *    plain sort (fewer saved positions move);
     *  - a name that runs out first sorts first;
     *  - names still equal (`p01` / `p1`) fall back to plain code-unit order, so the order is
     *    total and never depends on the input order.
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                var si = i; while (si < a.length && a[si] == '0') si++
                var sj = j; while (sj < b.length && b[sj] == '0') sj++
                var ei = si; while (ei < a.length && a[ei] in '0'..'9') ei++
                var ej = sj; while (ej < b.length && b[ej] in '0'..'9') ej++
                if (ei - si != ej - sj) return if (ei - si < ej - sj) -1 else 1
                for (k in 0 until ei - si) {
                    if (a[si + k] != b[sj + k]) return if (a[si + k] < b[sj + k]) -1 else 1
                }
                i = ei; j = ej
                continue
            }
            if (ca != cb) return if (ca < cb) -1 else 1
            i++; j++
        }
        val ra = a.length - i; val rb = b.length - j
        if (ra != rb) return if (ra < rb) -1 else 1
        return a.compareTo(b).coerceIn(-1, 1)
    }

    /** The book's pages in reading order — the spine comic-book.js builds from the same names. */
    fun pages(members: List<Member>): List<Member> =
        members.filter { isPageName(it.name) }.sortedWith { x, y -> naturalCompare(x.name, y.name) }

    /**
     * The balloon cache's identity: `cbz1:` + SHA-1 over the page members, sorted by RAW name
     * bytes (unsigned), one line each — `rawName \0 crc32 as 8 lowercase hex \0 size in decimal \n`.
     * Folder-independent (the Palma's `downloads/…` and another device's `Mihon/downloads/…` meet),
     * nearly free (the directory is read anyway), changed by any page edit and NOT by a ComicInfo
     * refresh. Two downloads of one chapter agree even when their zip timestamps differ. Null when
     * the archive has no pages.
     *
     * THE RAW BYTES DECIDE, end to end, exactly as the iPad's `BalloonGeometry.cbz1Key` does:
     * which members are pages is read off the raw name (a Unicode Path field can rename a member
     * in [Member.name], never here), and two members with the same raw name sort by CRC, then by
     * size, so the archive's stored order never leaks into the hash.
     */
    fun cbz1Key(members: List<Member>): String? {
        val pages = members.filter { isPageRaw(it.rawName) }
        if (pages.isEmpty()) return null
        val sorted = pages.sortedWith { x, y ->
            compareRaw(x.rawName, y.rawName).takeIf { it != 0 }
                ?: x.crc.compareTo(y.crc).takeIf { it != 0 }
                ?: x.usize.compareTo(y.usize)
        }
        val md = MessageDigest.getInstance("SHA-1")
        for (m in sorted) {
            md.update(m.rawName)
            md.update(0)
            md.update("%08x".format(m.crc).toByteArray(Charsets.US_ASCII))
            md.update(0)
            md.update(m.usize.toString().toByteArray(Charsets.US_ASCII))
            md.update('\n'.code.toByte())
        }
        return "cbz1:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    /** [isPageName] over the raw name bytes: not a folder, an image extension with ASCII folded. */
    internal fun isPageRaw(raw: ByteArray): Boolean {
        if (raw.isNotEmpty() && raw.last() == '/'.code.toByte()) return false
        return EXTS.any { e ->
            val eb = e.toByteArray(Charsets.US_ASCII)
            raw.size >= eb.size && eb.indices.all { k ->
                val c = raw[raw.size - eb.size + k].toInt() and 0xff
                (if (c in 0x41..0x5A) c + 32 else c) == eb[k].toInt()
            }
        }
    }

    private fun compareRaw(a: ByteArray, b: ByteArray): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            val x = a[k].toInt() and 0xff; val y = b[k].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
