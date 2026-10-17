package com.toolsboox.ot

import android.content.Context
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SAVE THE WEB AS A BOOK — EinkBro's most-loved lesson (GPL-3.0 kin; the lesson taken, the
 * writer our own): a page worth keeping becomes an EPUB on the Bookshelf, where the library
 * hub carries it to every device and the reader's highlights, positions and quiet footer all
 * already apply. A tab was a promise to come back; a book is the promise kept with interest.
 *
 * The EPUB is the minimal honest one: `mimetype` STORED first (the spec's magic), a container
 * pointing at one OPF, one XHTML chapter (the page, scripts stripped), and the page's images
 * fetched in beside it (a book that still needs the network is not a book). An image that
 * won't come quietly keeps its remote URL — the text is the cargo.
 */
object WebEpub {

    private val IMG_SRC = Regex("""<img[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val STRIP = listOf(
        Regex("""<script\b[\s\S]*?</script>""", RegexOption.IGNORE_CASE),
        Regex("""<iframe\b[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE),
        Regex("""<!--[\s\S]*?-->"""),
        Regex("""\son[a-z]+=["'][^"']*["']""", RegexOption.IGNORE_CASE),
    )

    /** Build the EPUB from [html] into [out]. Network for images — call off the main thread.
     *  Returns the number of images embedded. */
    fun write(
        @Suppress("UNUSED_PARAMETER") context: Context,
        out: java.io.OutputStream, title: String, sourceUrl: String, html: String,
        maxImages: Int = 12, maxImageBytes: Int = 1_500_000,
    ): Int {
        var body = html
        for (r in STRIP) body = r.replace(body, "")

        // The images come along: fetch, name, and re-point — remote stays for the ones that don't.
        data class Img(val url: String, val name: String, val bytes: ByteArray, val mime: String)
        val images = mutableListOf<Img>()
        for (m in IMG_SRC.findAll(body).map { it.groupValues[1] }.distinct().take(maxImages)) {
            runCatching {
                val conn = java.net.URL(m).openConnection().apply {
                    connectTimeout = 10_000; readTimeout = 15_000
                }
                val mime = (conn.contentType ?: "").substringBefore(';')
                val bytes = conn.getInputStream().use { it.readBytes() }
                if (bytes.size in 1..maxImageBytes && mime.startsWith("image/")) {
                    val ext = when (mime) {
                        "image/png" -> "png"; "image/gif" -> "gif"
                        "image/webp" -> "webp"; "image/svg+xml" -> "svg"; else -> "jpg"
                    }
                    images += Img(m, "img${images.size}.$ext", bytes, mime)
                }
            }
        }
        for (img in images) body = body.replace(img.url, img.name)

        val safeTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val chapter = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$safeTitle</title>
<meta charset="utf-8"/>
<style>body{font-family:serif;line-height:1.5;margin:0.5em}img{max-width:100%;height:auto}</style>
</head>
<body>
<p style="font-size:0.8em;color:#555">$safeTitle · saved from <a href="$sourceUrl">${sourceUrl.take(80)}</a></p>
$body
</body></html>"""

        val manifest = buildString {
            append("""<item id="chap" href="page.xhtml" media-type="application/xhtml+xml"/>""")
            for ((i, img) in images.withIndex())
                append("""<item id="im$i" href="${img.name}" media-type="${img.mime}"/>""")
        }
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:ledgable:${sourceUrl.hashCode()}</dc:identifier>
<dc:title>$safeTitle</dc:title>
<dc:language>en</dc:language>
<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest>$manifest</manifest>
<spine><itemref idref="chap"/></spine>
</package>"""

        ZipOutputStream(out).use { zip ->
            // mimetype MUST be first and STORED — it is the file-format magic.
            val mt = "application/epub+zip".toByteArray()
            zip.setMethod(ZipOutputStream.STORED)
            zip.putNextEntry(ZipEntry("mimetype").apply {
                size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            })
            zip.write(mt); zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("META-INF/container.xml", """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            put("OEBPS/page.xhtml", chapter.toByteArray())
            for (img in images) put("OEBPS/${img.name}", img.bytes)
        }
        return images.size
    }
}
