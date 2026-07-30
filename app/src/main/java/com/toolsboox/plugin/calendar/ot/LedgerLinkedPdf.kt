package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * A page exported as a PDF whose URLs are real, tappable annotations — the iPad's linked-PDF
 * export, ported to a platform whose PDF writer has no annotation API at all.
 *
 * TWO THINGS MAKE A LINK REACHABLE HERE, and the second is the one that always works:
 *
 *  1. **Annotations on the page itself.** Written after the fact by [PdfLinkAnnotations], because
 *     [PdfDocument] cannot emit them. The rectangles come from the vision model's approximate word
 *     boxes (see `VisionOcr.recognizeUrlBoxes`) — the only source of geometry for handwriting there
 *     is. This step is allowed to fail: a PDF whose cross-reference table is not the classic form,
 *     an unparseable page object, anything unexpected, and the annotation pass is abandoned whole.
 *
 *  2. **An index page.** Appended to every export regardless, listing each link exactly and in full
 *     as selectable text. This is what the iPad appends for the same reason — an approximate rect
 *     over handwriting can sit slightly off the words, and a reader who cannot hit the link on the
 *     page must still be able to read it, select it, or type it. The index entries get annotations
 *     too, and theirs are exact, because we drew that text and know precisely where it went.
 *
 * [Result.annotated] is the honest answer to "is this actually linked", carried back so the caller
 * can say so in the toast rather than claiming a linkedness the file may not have.
 */
object LedgerLinkedPdf {

    /** Matches the app's other PDF exports (see [CalendarPdfRenderer]) so a Ledger PDF is always
     *  the same shape, whichever surface produced it. */
    private const val PAGE_W = 1404
    private const val PAGE_H = 1872
    private const val MARGIN = 72f

    /**
     * What the export turned out to be. [annotated] is true only when the annotation pass ran AND
     * the resulting file still parses as a PDF on this device; [linkCount] is how many links the
     * index page lists, which is the count that is true either way.
     */
    data class Result(val file: File, val annotated: Boolean, val linkCount: Int)

    /**
     * Write [page] (already rendered — ink, grams and text, as the surface drew them) plus an index
     * of [urls] to [out], and try to make the links real.
     *
     * [boxes] maps a URL to its NORMALIZED box on [page] (0..1, top-left origin), as the vision
     * model placed it; pass an empty map when there was no key or the model placed nothing, and the
     * body page simply carries no annotations while the index page still carries all of them.
     */
    fun render(
        context: Context,
        title: String,
        page: Bitmap?,
        urls: List<String>,
        boxes: Map<String, RectF>,
        out: File,
        provenance: LedgerProvenance.Stamp? = null,
    ): Result {
        val links = ArrayList<PdfLinkAnnotations.Link>()
        val pdf = PdfDocument()
        try {
            // ---- Page 1: the page as it looks.
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
            val p1 = pdf.startPage(info)
            p1.canvas.drawColor(Color.WHITE)
            var drawn: RectF? = null
            if (page != null && !page.isRecycled) {
                val avail = RectF(MARGIN, MARGIN, PAGE_W - MARGIN, PAGE_H - MARGIN)
                val scale = minOf(avail.width() / page.width, avail.height() / page.height)
                val w = page.width * scale
                val h = page.height * scale
                val dst = RectF(avail.left + (avail.width() - w) / 2f, avail.top, avail.left + (avail.width() - w) / 2f + w, avail.top + h)
                p1.canvas.drawBitmap(page, null, dst, Paint().apply { isAntiAlias = true; isFilterBitmap = true })
                drawn = dst
            }
            pdf.finishPage(p1)

            // A normalized box is relative to the BITMAP, so it has to be mapped through the rect
            // the bitmap was actually drawn into — not the page — or every link lands in the margin.
            drawn?.let { d ->
                for (url in urls) {
                    val b = boxes[url] ?: continue
                    val l = d.left + b.left * d.width()
                    val r = d.left + b.right * d.width()
                    val t = d.top + b.top * d.height()
                    val bo = d.top + b.bottom * d.height()
                    links.add(toPdfSpace(url, 0, l, t, r, bo))
                }
            }

            // ---- Page 2: the colophon — where this came from, and what it points at.
            //
            // Written whenever there is EITHER, which is why the condition is no longer "are there
            // links": a page with no URLs on it still has a surface, a day and a title, and that
            // was the whole complaint about the bare export. Provenance and the link index share
            // this page rather than getting one each ([LedgerProvenance.drawBlock] draws the top
            // of it) — a reader should not have to turn past a two-line page to reach the links.
            if (urls.isNotEmpty() || provenance != null) {
                val info2 = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create()
                val p2 = pdf.startPage(info2)
                p2.canvas.drawColor(Color.WHITE)
                var y = MARGIN + 50f
                if (provenance != null) {
                    y = LedgerProvenance.drawBlock(p2.canvas, provenance, MARGIN, y, PAGE_W - MARGIN)
                }
                if (urls.isNotEmpty()) links.addAll(drawIndex(p2.canvas, title, urls, y))
                pdf.finishPage(p2)
            }

            out.parentFile?.mkdirs()
            FileOutputStream(out).use { pdf.writeTo(it) }
        } catch (e: Exception) {
            Timber.w(e, "linked pdf render failed")
            return Result(out, false, urls.size)
        } finally {
            pdf.close()
        }

        // The incremental-update pass now carries TWO payloads — the link annotations and the
        // document's /Info dictionary — so it runs whenever either exists. A page with no links but
        // real provenance still gets rewritten, which is how a PDF of a plain handwritten page ends
        // up knowing its own surface and date.
        val info = provenance?.let { LedgerProvenance.pdfInfo(it) } ?: emptyMap()
        if (links.isEmpty() && info.isEmpty()) return Result(out, false, urls.size)

        // ---- The annotation pass, and its own verification.
        val annotated = try {
            val original = out.readBytes()
            val patched = PdfLinkAnnotations.inject(original, links, info)
            if (patched == null) false
            else {
                out.writeBytes(patched)
                // Prove the file still opens BEFORE anyone is handed it. PdfRenderer is the same
                // parser the device's own viewer uses, so a file it will not open is a file we
                // must not ship — put the untouched bytes back and export as a plain PDF.
                //
                // "Annotated" stays the answer to "are the LINKS real", not "did the rewrite
                // succeed": a metadata-only rewrite on a page with no links must not make the
                // toast claim tappable links that were never there.
                if (opens(out)) links.isNotEmpty() else { out.writeBytes(original); false }
            }
        } catch (e: Exception) {
            Timber.w(e, "link annotation pass failed")
            false
        }
        return Result(out, annotated, urls.size)
    }

    /** Draw "Links on this page" and each URL, returning an exact annotation rect for each line —
     *  exact because this is the one place where we put the text down ourselves. [startY] is where
     *  the caller has got to, so the index sits under the provenance block instead of over it. */
    private fun drawIndex(
        canvas: Canvas, title: String, urls: List<String>, startY: Float
    ): List<PdfLinkAnnotations.Link> {
        val out = ArrayList<PdfLinkAnnotations.Link>()
        val head = Paint().apply { color = Color.BLACK; textSize = 44f; isAntiAlias = true; isFakeBoldText = true }
        val sub = Paint().apply { color = Color.BLACK; textSize = 30f; isAntiAlias = true }
        val body = Paint().apply { color = Color.BLACK; textSize = 32f; isAntiAlias = true }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f }

        var y = startY
        canvas.drawText(title.take(48).ifBlank { "Ledger page" }, MARGIN, y, head)
        y += 46f
        canvas.drawText("Links on this page", MARGIN, y, sub)
        y += 18f
        canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
        y += 60f

        for ((i, url) in urls.withIndex()) {
            if (y > PAGE_H - MARGIN) break   // one index page; more than ~45 links is not a page
            val label = "${i + 1}.  $url"
            canvas.drawText(label, MARGIN, y, body)
            val width = body.measureText(label)
            // Underlined, because a link that looks like a link is the point of listing it.
            canvas.drawLine(MARGIN, y + 8f, MARGIN + width, y + 8f, rule)
            out.add(toPdfSpace(url, 1, MARGIN, y - 34f, MARGIN + width, y + 12f))
            y += 58f
        }
        return out
    }

    /** Canvas space is top-down; PDF user space is bottom-up. Every rect crosses here exactly once. */
    private fun toPdfSpace(url: String, pageIndex: Int, l: Float, t: Float, r: Float, b: Float) =
        PdfLinkAnnotations.Link(url, pageIndex, l, PAGE_H - b, r, PAGE_H - t)

    /** Does the device's own PDF parser accept this file? */
    private fun opens(file: File): Boolean = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r -> r.pageCount > 0 }
        }
    } catch (e: Throwable) {
        Timber.w(e, "annotated pdf failed to reopen — reverting to the plain export")
        false
    }

    /** Every http(s) address in [text], de-duplicated, in the order they appear. The trailing
     *  punctuation trim matters: "see https://x.test/a." must not carry the sentence's full stop
     *  into the annotation's URI. */
    fun urlsIn(text: String): List<String> =
        Regex("""https?://\S+""").findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '>', ';', '"', '\'') }
            .filter { it.length > 10 }
            .distinct()
            .toList()
}
