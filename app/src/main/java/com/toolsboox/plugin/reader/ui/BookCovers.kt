package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import com.toolsboox.plugin.reader.ot.CbzDirectory
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The picture on the front of a book.
 *
 * Michael's punchlist: "All Books surface with pictures of books and an add option." A shelf of
 * filenames is a list; a shelf of covers is a shelf, and the difference is entirely about how fast
 * you find the one you meant.
 *
 * Three sources, in descending order of how much they tell you:
 *
 *  • EPUB — the real cover, pulled out of the zip. No OPF parsing: the manifest would be the
 *    correct route and it costs a second pass over the archive to answer a question the FILENAMES
 *    already answer nearly always. An image whose path says "cover" is the cover.
 *  • CBZ — the first image in the archive, which is the front of a comic by construction.
 *  • Anything else (PDF, audiobooks, formats we cannot open) — a DRAWN cover: title and author on
 *    a book-shaped card. Not a placeholder, a legible object; the point of the shelf is to be
 *    scannable, and a grid of identical grey rectangles is worse than a grid of filenames.
 *
 * Cached to disk by name+size, because unzipping every book on every shelf draw is precisely the
 * kind of work that makes an e-ink device feel broken. Size is in the key so a replaced book — a
 * better scan, a fixed EPUB — gets a fresh cover rather than the stale one forever.
 */
object BookCovers {

    private const val W = 320
    private const val H = 460

    /**
     * Cache generation. Bump it whenever the EXTRACTOR gets better: the cache is keyed by the
     * book, not by what the extractor could do at the time, so a cover that fell to the drawn
     * card under an older extractor is served forever unless the key changes. 09-04: the Green
     * Lantern CBZs (webp pages) got their grey cards cached before -123 taught the extractor
     * webp, and the fix never got to run — "comic cover still not appearing" for a whole day.
     */
    private const val GEN = "g3"

    /** How long a DRAWN fallback is trusted before the extractor is tried again. Real art is
     *  kept indefinitely; a failure is not a fact about the book, only about that attempt. */
    private const val DRAWN_TTL_MS = 24L * 60 * 60 * 1000

    private fun cacheDir(context: Context) = File(context.cacheDir, "covers").apply {
        mkdirs()
        // Sweep the previous generation lazily, once: stale placeholders would otherwise sit
        // in cache until the system trims it.
        val marker = File(this, ".$GEN")
        if (!marker.exists()) {
            listFiles()?.forEach { if (!it.name.startsWith("$GEN-") && !it.name.startsWith(".")) it.delete() }
            runCatching { marker.createNewFile() }
        }
    }

    private fun cacheFile(context: Context, entry: BookshelfSource.Entry) =
        File(cacheDir(context), "$GEN-${entry.sizeBytes}-${entry.name.replace('/', '_')}.png")

    /** Drawn fallbacks are ALSO keyed by build: a new build usually means a better extractor, so a
     *  failure recorded under the previous one is retried (09-04: -193's fix was masked for a day
     *  by -192's own freshly-cached grey card). */
    private fun drawnCacheFile(context: Context, entry: BookshelfSource.Entry) =
        File(cacheDir(context), "$GEN-${com.toolsboox.BuildConfig.VERSION_NAME}-${entry.sizeBytes}-${entry.name.replace('/', '_')}.drawn.png")

    /**
     * The cover for [entry]. Blocking — call it off the main thread.
     *
     * Never returns null: a book with no extractable art still gets a drawn one, so the caller
     * never has to decide what an absent cover looks like and the grid never has a hole in it.
     */
    fun cover(context: Context, entry: BookshelfSource.Entry): Bitmap {
        val cached = cacheFile(context, entry)
        if (cached.exists()) {
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }
        val drawnCached = drawnCacheFile(context, entry)
        if (drawnCached.exists() && System.currentTimeMillis() - drawnCached.lastModified() < DRAWN_TTL_MS) {
            BitmapFactory.decodeFile(drawnCached.absolutePath)?.let { return it }
        }
        val art = runCatching { extract(context, entry) }
            .onFailure { timber.log.Timber.w(it, "cover: extract failed for %s", entry.name) }.getOrNull()
        if (art != null) {
            runCatching { cached.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            runCatching { drawnCached.delete() }
            return art
        }
        val bmp = drawn(entry)
        runCatching { drawnCached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        return bmp
    }

    /** Whether a cover is already cached — lets a caller draw the shelf now and fill art in after. */
    fun isCached(context: Context, entry: BookshelfSource.Entry): Boolean =
        cacheFile(context, entry).exists() || drawnCacheFile(context, entry).exists()

    private fun extract(context: Context, entry: BookshelfSource.Entry): Bitmap? = when (entry.extension) {
        // .webp and .gif were missing (09-03: "comic cover still not appearing") — modern
        // comics and the keiyoushi CBZs we write are mostly WEBP, so the old jpg/png-only
        // filter matched nothing and every comic fell to the drawn placeholder.
        // The name hunt first (cheap, streaming); then what the book DECLARES as its cover in
        // the OPF; then the largest image in the archive, which in a novel is the cover nine
        // times in ten. Z-library and calibre epubs name their cover anything (09-13: The
        // Shards, three copies, all title cards) — the name hunt alone missed every one.
        "epub" -> fromZip(context, entry) { path ->
            val p = path.lowercase()
            p.contains("cover") && isImagePath(p)
        } ?: epubDeclaredCover(context, entry) ?: largestImage(context, entry)
        // CBZ has no "cover" name — the cover is the FIRST page by name, so take the earliest
        // image rather than whatever the zip happens to store first (order isn't guaranteed).
        "cbz" -> firstImageInZip(context, entry)
        "pdf" -> firstPdfPage(context, entry)
        else -> null
    }

    /**
     * A PDF's first page, rendered. Michael, 2026-08-08: "PDFs need covers — they're not loading."
     *
     * They were not loading because nothing tried: the extractor knew about zips and a PDF is not
     * one, so every PDF fell through to a drawn card. Which was honest but wrong — a PDF HAS a
     * cover, it is just page one, and a scanned book whose cover is its first page is exactly the
     * case where a title card helps least.
     *
     * PdfRenderer needs a seekable file descriptor, so an entry behind a declared tree has to be
     * materialised first. That is a copy of a possibly-large file for a thumbnail, which is why the
     * result is cached like every other cover and why this runs only when the cover is first asked
     * for rather than on a shelf scan.
     */
    private fun firstPdfPage(context: Context, entry: BookshelfSource.Entry): Bitmap? {
        val file = BookshelfSource.materialise(context, entry) ?: return null
        return runCatching {
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount < 1) return@use null
                    renderer.openPage(0).use { page ->
                        // Rendered at the tile's own aspect, then fitted — asking the renderer for
                        // the shelf size directly would squash a landscape page.
                        val scale = minOf(
                            W.toFloat() / page.width.coerceAtLeast(1),
                            H.toFloat() / page.height.coerceAtLeast(1)
                        )
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        // White first: a PDF page renders with transparency where it has no ink,
                        // and a transparent thumbnail reads as black in most viewers.
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        fit(bmp)
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * First archive member matching [wanted], decoded and fitted.
     *
     * Streamed, and it stops at the first hit: a book is tens of megabytes and the cover is
     * usually in the first few entries, so reading to the end of the archive to be thorough would
     * cost seconds per book for no better answer.
     */
    private fun isImagePath(p: String): Boolean =
        p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") ||
            p.endsWith(".webp") || p.endsWith(".gif")

    /**
     * The alphabetically-first image in a CBZ — the cover.
     *
     * ROOT CAUSE, 09-04 ("Green Lantern cover not loading" for a day): Mihon/keiyoushi write
     * their CBZs with STORED members that carry a DATA DESCRIPTOR (local header flag bit 3,
     * sizes zero) — and `ZipInputStream` throws "only DEFLATED entries can have EXT descriptor"
     * on the very first member, so the streaming path never saw an image at all. The reader
     * never noticed because it copies the file and opens it with random access. So: read the
     * CENTRAL DIRECTORY (which always has the real sizes and offsets) through a seekable
     * descriptor — the SAF provider hands one out without a copy — and pull just the one member.
     * The streaming pass stays as the fallback for anything that isn't seekable.
     */
    private fun firstImageInZip(context: Context, entry: BookshelfSource.Entry): Bitmap? {
        seekableCover(context, entry)?.let { return it }
        val names = runCatching {
            openStream(context, entry)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    val out = mutableListOf<String>()
                    var e = zip.nextEntry; var looked = 0
                    while (e != null && looked < 400) {
                        if (!e.isDirectory && isImagePath(e.name.lowercase())) out += e.name
                        looked++; e = zip.nextEntry
                    }
                    out
                }
            }
        }.getOrNull().orEmpty()
        val first = names.sortedWith(String.CASE_INSENSITIVE_ORDER).firstOrNull() ?: return null
        // The name pass looked at up to 400 members; the fetch must be allowed to reach the one
        // it chose (these CBZs store pages out of order — 001 was 3rd in #16, but nothing says
        // it can't be 50th), so it gets the same budget rather than the 40-entry cover hunt.
        return fromZip(context, entry, maxScanned = 400) { it == first }
    }

    /**
     * The cover the epub itself names: META-INF/container.xml → the OPF → `<meta name="cover"
     * content="ID">` (EPUB 2) or `<item properties="cover-image">` (EPUB 3), resolved against the
     * OPF's folder. Over the central directory, so nothing is inflated but the two small XMLs and
     * the one image.
     */
    private fun epubDeclaredCover(context: Context, entry: BookshelfSource.Entry): Bitmap? = runCatching {
        openChannel(context, entry)?.use { chan ->
            val ch = chan.channel
            val members = CbzDirectory.read(ch)
            // EPUB names are UTF-8 by spec, flagged or not; the shared reader decodes unflagged
            // names as zip.js does (CP437), so a lookup here accepts either spelling.
            fun CbzDirectory.Member.named(n: String) = name == n || String(rawName, Charsets.UTF_8) == n
            fun text(name: String): String? = members.firstOrNull { it.named(name) }
                ?.let { CbzDirectory.bytes(ch, it) }?.toString(Charsets.UTF_8)
            val container = text("META-INF/container.xml") ?: return@use null
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return@use null
            val opf = text(opfPath) ?: return@use null
            val dir = opfPath.substringBeforeLast('/', "")
            fun href(itemAttr: String): String? =
                Regex("<item\\b[^>]*$itemAttr[^>]*>", RegexOption.IGNORE_CASE).find(opf)?.value
                    ?.let { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            val coverId = Regex("<meta\\b[^>]*name=\"cover\"[^>]*content=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)
                ?: Regex("<meta\\b[^>]*content=\"([^\"]+)\"[^>]*name=\"cover\"", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)
            val rel = href("properties=\"[^\"]*cover-image[^\"]*\"")
                ?: coverId?.let { href("id=\"${Regex.escape(it)}\"") }
                ?: return@use null
            val decoded = java.net.URLDecoder.decode(rel, "UTF-8")
            val full = if (dir.isBlank()) decoded else "$dir/$decoded"
            val norm = full.split('/').fold(mutableListOf<String>()) { acc, seg ->
                when (seg) { "", "." -> {}; ".." -> { if (acc.isNotEmpty()) acc.removeAt(acc.size - 1) }; else -> acc.add(seg) }; acc
            }.joinToString("/")
            val m = members.firstOrNull { it.named(norm) } ?: return@use null
            // The declared cover may be an XHTML page wrapping the image — then take the image it names.
            if (!isImagePath(norm.lowercase())) {
                val page = CbzDirectory.bytes(ch, m)?.toString(Charsets.UTF_8) ?: return@use null
                val img = Regex("(?:src|xlink:href)=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: return@use null
                val pdir = norm.substringBeforeLast('/', "")
                val ipath = (if (pdir.isBlank()) img else "$pdir/$img").split('/').fold(mutableListOf<String>()) { acc, seg ->
                    when (seg) { "", "." -> {}; ".." -> { if (acc.isNotEmpty()) acc.removeAt(acc.size - 1) }; else -> acc.add(seg) }; acc
                }.joinToString("/")
                val im = members.firstOrNull { it.named(ipath) } ?: return@use null
                return@use CbzDirectory.bytes(ch, im)?.let { decodeSampled(it) }?.let { b -> fit(b).also { if (it !== b) b.recycle() } }
            }
            CbzDirectory.bytes(ch, m)?.let { decodeSampled(it) }?.let { b -> fit(b).also { if (it !== b) b.recycle() } }
        }
    }.onFailure { timber.log.Timber.w(it, "cover: OPF pass failed for %s", entry.name) }.getOrNull()

    /** The largest image in the archive, by stored size — a novel's cover, nearly always. */
    private fun largestImage(context: Context, entry: BookshelfSource.Entry): Bitmap? = runCatching {
        openChannel(context, entry)?.use { chan ->
            val ch = chan.channel
            val m = CbzDirectory.read(ch)
                .filter { !it.name.endsWith("/") && isImagePath(it.name.lowercase()) }
                .maxByOrNull { it.usize } ?: return@use null
            CbzDirectory.bytes(ch, m)?.let { decodeSampled(it) }?.let { b -> fit(b).also { if (it !== b) b.recycle() } }
        }
    }.onFailure { timber.log.Timber.w(it, "cover: largest-image pass failed for %s", entry.name) }.getOrNull()

    /** Cover via the central directory over a seekable channel; null if not seekable or no image. */
    private fun seekableCover(context: Context, entry: BookshelfSource.Entry): Bitmap? = runCatching {
        openChannel(context, entry)?.use { chan ->
            val ch = chan.channel
            val members = CbzDirectory.read(ch)
            val cover = members
                .filter { !it.name.endsWith("/") && isImagePath(it.name.lowercase()) }
                .minWithOrNull(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?: return@use null
            val bytes = CbzDirectory.bytes(ch, cover) ?: return@use null
            val bmp = decodeSampled(bytes) ?: return@use null
            val out = fit(bmp)
            if (bmp !== out) bmp.recycle()
            out
        }
    }.onFailure { timber.log.Timber.w(it, "cover: seekable zip failed for %s", entry.name) }.getOrNull()

    /** The shared central-directory reader (moved to [CbzDirectory], Michael 2026-10-11 — the
     *  balloon index reads Mihon/Suwayomi CBZs through it too). */
    private fun openChannel(context: Context, entry: BookshelfSource.Entry): CbzDirectory.Chan? =
        CbzDirectory.open(context, entry.file, entry.uri)

    private fun fromZip(
        context: Context, entry: BookshelfSource.Entry, maxScanned: Int = MAX_ENTRIES_SCANNED,
        wanted: (String) -> Boolean,
    ): Bitmap? {
        val src = openStream(context, entry) ?: return null
        return src.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var e = zip.nextEntry
                var looked = 0
                while (e != null) {
                    if (!e.isDirectory && wanted(e.name)) {
                        val bytes = zip.readBytes()
                        val bmp = decodeSampled(bytes)
                        if (bmp != null) {
                            val out = fit(bmp)
                            // fit() drew a NEW tile-sized bitmap; the page it was drawn from is
                            // dead the moment that returns. Recycling it matters here in a way it
                            // never did for ebook covers — a comic shelf decodes one of these per
                            // book, and the GC does not run between them fast enough to save you.
                            if (bmp !== out) bmp.recycle()
                            return@use out
                        }
                    }
                    // A CBZ whose first entries are junk (metadata, a ComicInfo.xml, a stray
                    // thumbnail) must not turn into a scan of a 280 MB archive. The cover is at the
                    // front of a comic or it is not worth finding.
                    if (++looked > maxScanned) return@use null
                    e = zip.nextEntry
                }
                null
            }
        }
    }

    /** How many zip entries to look at before giving up on finding a cover. */
    private const val MAX_ENTRIES_SCANNED = 40

    /**
     * Decode to roughly the tile size, NEVER full resolution.
     *
     * This is the bug that took the shelf down on 2026-08-24. `decodeByteArray` with no options
     * decodes at native size, and a comic page is 2000×3000 or larger — about 24 MB as ARGB_8888,
     * for a thumbnail a couple of hundred pixels wide. One of those is survivable; a shelf of 159
     * comics is not, and the app slowed and then died.
     *
     * It never showed on ebooks because an epub cover is already thumbnail-shaped. Comics are the
     * first books on this shelf whose pages are big, which is why a latent waste became a crash the
     * moment mihon's library came into scope.
     *
     * Two passes: measure with `inJustDecodeBounds`, then decode with a power-of-two `inSampleSize`.
     * The decoder never allocates the full page at all — this is cheaper than decoding and scaling,
     * not just tidier.
     */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565   // a cover has no alpha; half the bytes
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private fun openStream(context: Context, entry: BookshelfSource.Entry): java.io.InputStream? =
        when {
            entry.file != null -> runCatching { entry.file.inputStream() }.getOrNull()
            entry.uri != null -> runCatching { context.contentResolver.openInputStream(entry.uri) }.getOrNull()
            else -> null
        }

    /** Scale to the shelf tile, letterboxed onto white — covers have every aspect ratio there is. */
    private fun fit(src: Bitmap): Bitmap {
        // RGB_565, not ARGB_8888. A cover is opaque — it is letterboxed onto solid white below —
        // so the alpha channel is 590 KB per tile spent on nothing. On a shelf of hundreds that
        // is the difference between fitting in the heap and being killed by the system.
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val r = minOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val w = src.width * r
        val h = src.height * r
        c.drawBitmap(src, null, RectF((W - w) / 2f, (H - h) / 2f, (W + w) / 2f, (H + h) / 2f), null)
        return out
    }

    /** A cover for a book that has none: title and author, set large, on a bordered card. */
    private fun drawn(entry: BookshelfSource.Entry): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK
        }
        c.drawRect(4f, 4f, W - 4f, H - 4f, border)
        // A spine line, so a drawn cover still reads as a BOOK at a glance rather than as a note.
        c.drawLine(28f, 8f, 28f, H - 8f, border)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 30f
        }
        var y = 90f
        for (line in wrap(entry.title, tp, W - 70f, 7)) {
            c.drawText(line, 46f, y, tp)
            y += 38f
        }
        val kind = entry.extension.uppercase()
        if (kind.isNotBlank()) {
            val kp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY; textSize = 22f
            }
            c.drawText(kind, 46f, H - 34f, kp)
        }
        return out
    }

    private fun wrap(text: String, paint: TextPaint, width: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) <= width) line = StringBuilder(candidate)
            else {
                if (line.isNotEmpty()) out += line.toString()
                line = StringBuilder(w)
                if (out.size == maxLines) return out
            }
        }
        if (out.size < maxLines && line.isNotEmpty()) out += line.toString()
        return out
    }
}
