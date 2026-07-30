package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import timber.log.Timber
import java.time.LocalDate

/**
 * Where an exported page CAME FROM, carried on the export itself.
 *
 * The Send / Export sheet ([LedgerSendExport]) could already put a page in front of other people
 * six ways — a PNG in a message, a PDF in an email, a WordPress post that the POSSE cron mirrors
 * onward to Bluesky — and every one of them arrived bare. A picture of a handwritten page with no
 * date, no surface and no address is a picture of a handwritten page: three weeks later neither he
 * nor the person he sent it to can say which day it was written on, which of the five making
 * surfaces it came off, or where the published version lives. The ink is the least ambiguous thing
 * on the page and the context is the most; carrying the context is what makes the file re-findable.
 *
 * WHAT IS ON IT, and the reason for each:
 *  • **The surface and the day.** Together they are the page's address in the Ledger — the only two
 *    facts that let him navigate back to it from a file that left the device weeks ago.
 *  • **The document's title.** [LedgerDocuments] is the one query that knows it (the explicit name
 *    if it has one, else the date it was started), so this asks it rather than inventing a second
 *    naming rule that would drift out of agreement with the chip and the directory.
 *  • **The source links found on the page.** The same list [LedgerLinkedPdf] indexes. A synthesis
 *    is made OUT of things; naming them is the difference between a claim and a citation.
 *  • **The canonical URL, when there is one.** Only ever present after the page has actually been
 *    published — see [rememberPublished]. A URL printed on an export of an unpublished page would
 *    be a 404 with his domain on it, so nothing writes one speculatively.
 *
 * DRAWN, NOT ATTACHED. It is baked into the PNG's pixels and drawn onto the PDF's index page rather
 * than living only in file metadata, because every destination that matters here strips metadata:
 * a PNG posted to Bluesky, mailed through the essay route, or set as a WordPress featured image is
 * re-encoded on the way and arrives with its EXIF gone. Pixels survive re-encoding; tEXt chunks do
 * not. The PDF gets BOTH — a visible block and a real `/Info` dictionary — because a PDF is the one
 * export that reaches a reader which can actually show you its metadata.
 */
object LedgerProvenance {

    /** Where the canonical URLs are remembered. Plain prefs, not the encrypted store: these are
     *  public addresses of published pages, and putting them behind the keystore would only mean
     *  losing them on a restore for no privacy gained. */
    private const val PREFS = "ledger_provenance"

    /**
     * One page's provenance, as the stamp and the PDF's metadata see it.
     *
     * [surface] is the making surface's own name ("Write", "Synthesize", "Pickings", "Text Notes",
     * or "Day" for the day page itself) — [LedgerDocuments.label] is where four of the five come
     * from. [sourceLinks] is in page order and may be empty; [canonicalUrl] is null until the page
     * has been published from this device.
     */
    data class Stamp(
        val surface: String,
        val date: LocalDate?,
        val title: String,
        val canonicalUrl: String?,
        val sourceLinks: List<String>,
    )

    /* -----------------------------------------------------------------------------------
     * Remembering where a page was published to
     * --------------------------------------------------------------------------------- */

    /**
     * The identity a page is remembered under: the day plus the note-page key, which is exactly the
     * pair that addresses a page everywhere else in the app (`ledger://<date>/<key>`). Deliberately
     * NOT the title — a document can be renamed, and a rename must not orphan the URL its published
     * version already lives at.
     */
    fun keyFor(date: LocalDate?, pageKey: String?): String =
        (date?.toString() ?: "no-date") + "/" + (pageKey?.substringBefore('#') ?: "default")

    /**
     * Record that this page now lives at [url]. Called on a SUCCESSFUL publish only, with the
     * `link` WordPress itself returned — never a URL we guessed from a slug, because WP sanitises
     * slugs (percent-signs, duplicates, non-ASCII) in ways that make a client-side guess wrong
     * often enough to matter, and a wrong canonical URL is worse than none.
     */
    fun rememberPublished(context: Context, date: LocalDate?, pageKey: String?, url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(keyFor(date, pageKey), url.trim()).apply()
        }.onFailure { Timber.w(it, "provenance: could not remember the published URL") }
    }

    /** The canonical URL for this page, or null if it has never been published from this device. */
    fun publishedUrl(context: Context, date: LocalDate?, pageKey: String?): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyFor(date, pageKey), null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /* -----------------------------------------------------------------------------------
     * Building one
     * --------------------------------------------------------------------------------- */

    /**
     * The stamp for a page. [text] is the page's words — typed, or recognised — and is read ONLY
     * for the links in it, using [LedgerLinkedPdf.urlsIn] so the stamp's source list and the PDF's
     * index page can never disagree about what counts as a link on this page.
     */
    fun of(
        context: Context,
        surface: String,
        date: LocalDate?,
        pageKey: String?,
        title: String,
        text: String,
    ): Stamp = Stamp(
        surface = surface,
        date = date,
        title = title.trim(),
        canonicalUrl = publishedUrl(context, date, pageKey),
        // Capped at eight: past that the block stops being a citation and starts being a second
        // page of small print underneath a picture. The PDF's index page still lists them all.
        sourceLinks = LedgerLinkedPdf.urlsIn(text).take(8),
    )

    /** "Write · 2026-07-29 · Rooms on Other People's Land" — the one line that says where this is
     *  from. The title is dropped when it IS the date, which is how an unnamed document is titled;
     *  repeating it would spend a third of the line saying the same thing twice. */
    fun headline(stamp: Stamp): String {
        val day = stamp.date?.toString().orEmpty()
        val parts = mutableListOf<String>()
        if (stamp.surface.isNotBlank()) parts.add(stamp.surface)
        if (day.isNotBlank()) parts.add(day)
        if (stamp.title.isNotBlank() && stamp.title != day) parts.add(stamp.title)
        return parts.joinToString("  ·  ")
    }

    /** The sources line, hosts only. A stamp is read at a glance from a picture — the full URLs are
     *  on the PDF's index page, where they are selectable text and worth their width; here what is
     *  wanted is "who is this made out of", which is the host. */
    fun sourcesLine(stamp: Stamp): String? {
        if (stamp.sourceLinks.isEmpty()) return null
        val hosts = stamp.sourceLinks.mapNotNull { hostOf(it) }.distinct()
        if (hosts.isEmpty()) return null
        return "Sources:  " + hosts.joinToString("  ·  ")
    }

    private fun hostOf(url: String): String? = runCatching {
        android.net.Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /* -----------------------------------------------------------------------------------
     * On the PNG
     * --------------------------------------------------------------------------------- */

    /**
     * Return a NEW bitmap: [source] with a provenance band drawn beneath it.
     *
     * Grown rather than overprinted. The alternative — drawing the stamp over the bottom of the
     * page — puts type on top of whatever the last two lines of handwriting were, and on a page
     * that ran to the bottom margin (which is most of them) that destroys content to describe it.
     * Adding height costs a few hundred pixels on a file that is already a megapixel-plus.
     *
     * Everything here is the e-ink idiom the rest of the app draws in: pure black on pure white, a
     * hairline rule rather than a tinted panel, no shadow, no grey. A 50%-grey caption band renders
     * on a Carta panel as a dither pattern that is harder to read than the handwriting above it.
     */
    fun stampPng(source: Bitmap, stamp: Stamp): Bitmap {
        val w = source.width.coerceAtLeast(1)
        // The stamp is sized against the app's own page width so it looks the same weight whether
        // it came off a 1404-wide day canvas or a 1000-wide rendered note. Floored, because below
        // about half scale the type stops being legible on e-ink at reading distance.
        val scale = (w / 1404f).coerceIn(0.5f, 2.0f)
        val pad = 28f * scale
        val head = TextPaint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 30f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val body = TextPaint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 25f * scale
            typeface = android.graphics.Typeface.SANS_SERIF
        }

        val innerWidth = (w - pad * 2).toInt().coerceAtLeast(80)
        val lines = mutableListOf<Pair<String, TextPaint>>()
        lines.add(headline(stamp) to head)
        stamp.canonicalUrl?.let { lines.add(it to body) }
        sourcesLine(stamp)?.let { lines.add(it to body) }

        // Each line is laid out once to learn its height, then drawn from the same layout — two
        // passes over StaticLayout rather than guessing at line heights from textSize, which is
        // what makes a band that is a few pixels too short clip its own last descender.
        val layouts = lines.map { (text, paint) ->
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, innerWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        val gap = 8f * scale
        val bandHeight = (pad + layouts.sumOf { it.height } + gap * (layouts.size - 1).coerceAtLeast(0) + pad).toInt()

        val out = Bitmap.createBitmap(w, source.height + bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)

        // The rule is the whole separation between page and provenance: one black hairline, full
        // bleed, exactly as every framed container in this app draws its own edge.
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = (2f * scale).coerceAtLeast(1f) }
        val ruleY = source.height + (pad / 2f)
        canvas.drawLine(0f, ruleY, w.toFloat(), ruleY, rule)

        var y = source.height + pad
        for (layout in layouts) {
            canvas.save()
            canvas.translate(pad, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }
        return out
    }

    /* -----------------------------------------------------------------------------------
     * On the PDF
     * --------------------------------------------------------------------------------- */

    /**
     * Draw the provenance block on a PDF page at [left]/[top], within [right], and return the y to
     * carry on from. Shares [LedgerLinkedPdf]'s index page rather than getting a page of its own:
     * "where this came from" and "what it points at" are the same paragraph of an export's colophon,
     * and a two-line page on its own would be a page the reader has to turn past.
     */
    fun drawBlock(canvas: Canvas, stamp: Stamp, left: Float, top: Float, right: Float): Float {
        val label = Paint().apply {
            color = Color.BLACK; textSize = 24f; isAntiAlias = true; isFakeBoldText = true
        }
        val body = TextPaint().apply {
            color = Color.BLACK; textSize = 28f; isAntiAlias = true
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        val width = (right - left).toInt().coerceAtLeast(80)

        var y = top
        canvas.drawText("PROVENANCE", left, y, label)
        y += 14f
        canvas.drawLine(left, y, right, y, rule)
        y += 34f

        val rows = mutableListOf<String>()
        rows.add(headline(stamp))
        stamp.canonicalUrl?.let { rows.add("Published at  $it") }
        for (text in rows) {
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, body, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            canvas.save(); canvas.translate(left, y); layout.draw(canvas); canvas.restore()
            y += layout.height + 10f
        }
        return y + 24f
    }

    /**
     * The PDF `/Info` dictionary. Custom keys alongside the standard ones — the format allows any
     * key in Info, and `/LedgerSurface` + `/LedgerDate` say precisely what `/Subject` can only
     * imply, for anything that reads this file back mechanically.
     *
     * `/Producer` is deliberately absent: Android's writer sets its own and this is an INCREMENTAL
     * update, so claiming to be the producer of bytes Skia wrote would be a small lie in a field
     * whose only job is to be true.
     */
    fun pdfInfo(stamp: Stamp): Map<String, String> {
        val info = linkedMapOf<String, String>()
        info["Title"] = stamp.title.ifBlank { headline(stamp) }
        info["Subject"] = headline(stamp)
        info["Creator"] = "Ledger"
        info["LedgerSurface"] = stamp.surface
        stamp.date?.let { info["LedgerDate"] = it.toString() }
        stamp.canonicalUrl?.let { info["LedgerCanonicalUrl"] = it }
        if (stamp.sourceLinks.isNotEmpty()) info["Keywords"] = stamp.sourceLinks.joinToString(", ")
        info["CreationDate"] = pdfDate(System.currentTimeMillis())
        return info
    }

    /** PDF's own date form: `D:YYYYMMDDHHmmSSOHH'mm'`. A reader that cannot parse the offset shows
     *  the file as undated, so the apostrophes and the sign are not optional decoration. */
    private fun pdfDate(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("'D:'yyyyMMddHHmmss", java.util.Locale.US)
        val stamp = fmt.format(java.util.Date(millis))
        val offsetMinutes = java.util.TimeZone.getDefault().getOffset(millis) / 60000
        val sign = if (offsetMinutes < 0) '-' else '+'
        val abs = kotlin.math.abs(offsetMinutes)
        return String.format(java.util.Locale.US, "%s%c%02d'%02d'", stamp, sign, abs / 60, abs % 60)
    }
}
