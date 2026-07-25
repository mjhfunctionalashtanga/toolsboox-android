package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.ot.CardTreatment
import com.toolsboox.ot.InkMount
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The daily Pickings COVER — the band across the top of the day's default board that makes the
 * first picking page of the day read as the front of the day's gathering rather than just another
 * form. Paste-up, not chrome: a big tape strip with the page's name on it, then up to four recent
 * pick boards taped down as little cards you can hop to.
 *
 * The band is pixels in the template, like the intake strips and the day page's events, so a tap
 * has to resolve against the same rectangles the band was drawn into — [draw] records them and
 * [tileAt] answers for the fragment's tap handler. If the two ever disagreed, taps would open the
 *  wrong board with nothing on screen to explain why; recording at draw time is what makes that
 * impossible (the DayEventHits lesson).
 *
 * Only the DEFAULT daily board wears the cover. A named board is already a destination you chose;
 * putting doors to other boards on it would turn every board into a hallway.
 */
object PickingsCover {

    /** Where the band ends. Below this the classic Pickings template resumes, compressed. */
    const val BAND_BOTTOM = 340f

    /** Where the compressed template's NOTES/QUOTES panels begin (their headers sit just above). */
    const val CONTENT_TOP = 380f

    /**
     * How far back the cover looks for recent boards. The pickings page's almanac strip is a
     * day-stepper with period jump slots — it carries no "current period" selection to scope by —
     * so recency here is simply last-touched, walking back day by day from the viewed date.
     * Two weeks is far enough that an active board is never missed and near enough that the
     * cover never resurrects something you've genuinely finished with.
     */
    private const val LOOKBACK_DAYS = 14L
    private const val MAX_TILES = 4

    /** A drawn tile and where it landed, so a tap can find the board it shows. */
    data class Tile(val date: LocalDate, val key: String, val name: String, val rect: RectF)

    @Volatile
    private var tiles: List<Tile> = emptyList()

    /** The tile under a canvas-space point, or null. */
    fun tileAt(x: Float, y: Float): Tile? = tiles.firstOrNull { it.rect.contains(x, y) }

    /** Forget the recorded tap zones — called when a page WITHOUT the cover is drawn. */
    fun clear() {
        tiles = emptyList()
    }

    /** A recent board: where it lives, and whether its content is already in the loaded day. */
    private data class Recent(val date: LocalDate, val key: String, val name: String, val sameDay: Boolean)

    /**
     * Draw the band onto the daily board's template and record the tap zones. Must run AFTER
     * the classic template's full-page white fill, or the fill wipes the cover.
     */
    fun draw(context: Context, canvas: Canvas, calendarDay: CalendarDay) {
        val date = LocalDate.of(calendarDay.year, calendarDay.month, calendarDay.day)
        drawHeaderTape(canvas, date)

        val recent = recents(context, date)
        val slotLeft = 400f
        val slotRight = 1364f
        val gap = 20f
        val tileW = (slotRight - slotLeft - (MAX_TILES - 1) * gap) / MAX_TILES
        val tileTop = 42f
        val tileH = 258f

        if (recent.isEmpty()) {
            drawEmptySlot(canvas, RectF(slotLeft, tileTop, slotRight, tileTop + tileH))
            tiles = emptyList()
            return
        }

        val recorded = mutableListOf<Tile>()
        recent.forEachIndexed { i, r ->
            val left = slotLeft + i * (tileW + gap)
            val rect = RectF(left, tileTop, left + tileW, tileTop + tileH)
            // Alternate leans, like things taped down one after another rather than typeset.
            drawTile(canvas, calendarDay, r, rect, if (i % 2 == 0) -1.6f else 1.8f)
            recorded.add(Tile(r.date, r.key, r.name, rect))
        }
        tiles = recorded
    }

    // ---- gathering ------------------------------------------------------------------------------

    /** A named board's key carries its creation moment — "pickings-<millis>". */
    private fun keyMillis(key: String): Long = key.substringAfter("pickings-", "").toLongOrNull() ?: 0L

    /**
     * Up to four recent OTHER pick boards: this day's named boards first (their content is in the
     * loaded day, so their tiles can show the real thing), then named boards from the days behind,
     * newest day first. If there's still room, the nearest previous day that was written at all
     * contributes its DAILY board — the base template's own thread, yesterday's picking one tap away.
     */
    /** Tiles Michael has held-and-hidden: "date|key" strings, global (a hidden Thursday stays
     *  hidden from every later cover — hiding is a judgment about the board, not about today). */
    private fun hiddenSet(context: Context): MutableSet<String> =
        context.getSharedPreferences("pickings_cover", 0)
            .getStringSet("hidden_tiles", emptySet())!!.toMutableSet()

    fun hideTile(context: Context, tile: Tile) {
        val set = hiddenSet(context); set.add("${tile.date}|${tile.key}")
        context.getSharedPreferences("pickings_cover", 0).edit()
            .putStringSet("hidden_tiles", set).apply()
    }

    private fun recents(context: Context, date: LocalDate): List<Recent> {
        val out = mutableListOf<Recent>()
        PickingsStore.list(context, date)
            .filter { it.key != PickingsStore.DEFAULT_KEY }
            .sortedByDescending { keyMillis(it.key) }
            .forEach { out.add(Recent(date, it.key, it.name, true)) }
        for (back in 1..LOOKBACK_DAYS) {
            if (out.size >= MAX_TILES) break
            val d = date.minusDays(back)
            PickingsStore.listSaved(context, d)
                .filter { it.key != PickingsStore.DEFAULT_KEY }
                .sortedByDescending { keyMillis(it.key) }
                .forEach { out.add(Recent(d, it.key, it.name, false)) }
        }
        if (out.size < MAX_TILES) {
            for (back in 1..LOOKBACK_DAYS) {
                val d = date.minusDays(back)
                if (dayFileExists(context, d)) {
                    out.add(Recent(d, PickingsStore.DEFAULT_KEY, "Pickings", false))
                    break
                }
            }
        }
        val hidden2 = hiddenSet(context)
        return out.filterNot { hidden2.contains("${it.date}|${it.key}") }.take(MAX_TILES)
    }

    /**
     * Whether a day was written at ALL — the cheapest honest signal for "yesterday's daily
     * picking probably exists" without parsing a multi-megabyte day JSON on the render path.
     * A false positive just opens that day's empty template, which is the page's normal answer.
     */
    private fun dayFileExists(context: Context, date: LocalDate): Boolean {
        val root = ledgerRoot(context) ?: return false
        val y = date.format(DateTimeFormatter.ofPattern("yyyy"))
        val m = date.format(DateTimeFormatter.ofPattern("MM"))
        val d = date.format(DateTimeFormatter.ofPattern("dd"))
        val dir = File(root, "calendar/$y/$m")
        return File(dir, "day-$y-$m-$d-v2.json").exists() || File(dir, "day-$y-$m-$d.json").exists()
    }

    /** The same Documents root every other reader of the day files uses. */
    private fun ledgerRoot(context: Context): File? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
        else File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "toolsBoox"
        )

    // ---- drawing --------------------------------------------------------------------------------

    /**
     * The masthead: one big strip of tape with PICKINGS on it, leaning the house −20° — the
     * [InkMount] tape spec (opaque white, thin black rule, 40:14) blown up to header size.
     */
    private fun drawHeaderTape(canvas: Canvas, date: LocalDate) {
        val cx = 190f
        val cy = 150f
        val w = 330f
        val h = w / InkMount.TAPE_ASPECT
        canvas.save()
        canvas.rotate(-InkMount.TAPE_TILT_DEG, cx, cy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.BLACK
        canvas.drawRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, paint)
        val title = TextPaint().apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 46f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
        }
        canvas.drawText("PICKINGS", cx, cy + 2f, title)
        val sub = TextPaint().apply {
            color = Color.argb(170, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 20f
            typeface = Typeface.MONOSPACE; isAntiAlias = true
        }
        canvas.drawText("the daily picking · " + date.format(DateTimeFormatter.ofPattern("EEE d MMM")), cx, cy + 34f, sub)
        canvas.restore()
    }

    /** Nothing recent yet: a dashed slot saying what will appear here, instead of silence. */
    private fun drawEmptySlot(canvas: Canvas, rect: RectF) {
        val dashed = Paint().apply {
            color = Color.argb(90, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
        }
        canvas.drawRect(rect, dashed)
        val hint = TextPaint().apply {
            color = Color.argb(140, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC); isAntiAlias = true
        }
        canvas.drawText("recent pick boards land here", rect.centerX(), rect.centerY() - 6f, hint)
        canvas.drawText("circle ink → ❝ Picking, or name a new board", rect.centerX(), rect.centerY() + 26f, hint)
    }

    /**
     * One recent board as a little taped-down card: white ground, hard rule, a caption strip,
     * and either a real miniature of the board (same-day boards — their strokes and grams are
     * already in the loaded [calendarDay], so the render is cheap) or a quote-mark face for a
     * board whose day JSON we won't parse just to decorate a thumbnail.
     */
    private fun drawTile(canvas: Canvas, calendarDay: CalendarDay, r: Recent, rect: RectF, tilt: Float) {
        canvas.save()
        canvas.rotate(tilt, rect.centerX(), rect.centerY())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = 0xFF111111.toInt()
        canvas.drawRect(rect, paint)

        val capH = 56f
        val art = RectF(rect.left + 8f, rect.top + 8f, rect.right - 8f, rect.bottom - capH)

        val strokes = if (r.sameDay) calendarDay.noteStrokes[r.key].orEmpty() else emptyList()
        val images = if (r.sameDay) calendarDay.imageElements.filter { it.page == r.key } else emptyList()
        val hasArt = r.sameDay && (strokes.isNotEmpty() || images.isNotEmpty())
        if (hasArt) drawBoardArt(canvas, art, strokes, images) else drawQuoteFace(canvas, art)

        // Caption: the board's name, then what it is — counts for a live board, the day otherwise.
        val hairline = Paint().apply {
            color = Color.argb(120, 0, 0, 0); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true
        }
        canvas.drawLine(rect.left, rect.bottom - capH, rect.right, rect.bottom - capH, hairline)
        val name = TextPaint().apply {
            color = Color.BLACK; textSize = 23f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
        }
        val label = ellipsize(r.name, name, rect.width() - 24f)
        canvas.drawText(label, rect.left + 12f, rect.bottom - capH + 26f, name)
        val meta = TextPaint().apply {
            color = Color.argb(150, 0, 0, 0); textSize = 17f; typeface = Typeface.MONOSPACE; isAntiAlias = true
        }
        val metaText =
            if (r.sameDay) listOfNotNull(
                images.size.takeIf { it > 0 }?.let { "$it gram" + if (it == 1) "" else "s" },
                strokes.size.takeIf { it > 0 }?.let { "$it strokes" }
            ).joinToString(" · ").ifBlank { "today" }
            else r.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        canvas.drawText(ellipsize(metaText, meta, rect.width() - 24f), rect.left + 12f, rect.bottom - capH + 48f, meta)

        // Taped at the top corners — the shared spec, sized to the card it's holding down.
        CardTreatment.drawTape(canvas, rect.left + 14f, rect.top + 2f, -InkMount.TAPE_TILT_DEG, rect.width().toInt())
        CardTreatment.drawTape(canvas, rect.right - 14f, rect.top + 2f, InkMount.TAPE_TILT_DEG, rect.width().toInt())
        canvas.restore()
    }

    /**
     * A true miniature: the board's content bounds, fitted into the art box. The newest gram is
     * drawn (one decode, from bytes already in memory — never the disk), then the ink over it,
     * so the tile is a picture OF the board rather than a stand-in for it.
     */
    private fun drawBoardArt(
        canvas: Canvas, art: RectF,
        strokes: List<com.toolsboox.da.Stroke>, images: List<com.toolsboox.da.ImageElement>
    ) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) for (p in s.strokePoints) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        for (img in images) {
            if (img.x < minX) minX = img.x; if (img.y < minY) minY = img.y
            if (img.x + img.width > maxX) maxX = img.x + img.width
            if (img.y + img.height > maxY) maxY = img.y + img.height
        }
        if (minX > maxX || minY > maxY) { drawQuoteFace(canvas, art); return }
        val bw = (maxX - minX).coerceAtLeast(1f)
        val bh = (maxY - minY).coerceAtLeast(1f)
        // Contain the bounds, but never blow a lone doodle up past recognisability.
        val scale = minOf(art.width() / bw, art.height() / bh, 0.4f)
        val ox = art.left + (art.width() - bw * scale) / 2f
        val oy = art.top + (art.height() - bh * scale) / 2f

        canvas.save()
        canvas.clipRect(art)
        canvas.translate(ox, oy)
        canvas.scale(scale, scale)
        canvas.translate(-minX, -minY)

        // One image only — the newest — so four tiles never queue up eight decodes on the
        // render path. Anything older still shapes the bounds, so the layout stays honest.
        images.maxByOrNull { it.timestamp }?.let { img ->
            runCatching {
                val bytes = Base64.decode(img.data, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, RectF(img.x, img.y, img.x + img.width, img.y + img.height), null)
                    bmp.recycle()
                }
            }
        }

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            if (s.strokePoints.isEmpty()) continue
            ink.strokeWidth = (s.strokeWidth).coerceAtLeast(1f / scale)
            val path = Path()
            s.strokePoints.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            canvas.drawPath(path, ink)
        }
        canvas.restore()
    }

    /** The face for a board we won't open just to draw it: the mark of the place, a big quote. */
    private fun drawQuoteFace(canvas: Canvas, art: RectF) {
        val glyph = TextPaint().apply {
            color = Color.argb(120, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 96f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
        }
        canvas.drawText("❝", art.centerX(), art.centerY() + 34f, glyph)
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
