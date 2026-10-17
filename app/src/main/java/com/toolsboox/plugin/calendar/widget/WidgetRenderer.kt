package com.toolsboox.plugin.calendar.widget

import android.content.ContentUris
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.CalendarContract
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import com.squareup.moshi.Moshi
import com.toolsboox.R
import com.toolsboox.da.Stroke
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.CalendarDayPage
import java.io.File
import java.io.FileReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min
import kotlin.math.roundToInt

object WidgetRenderer {

    const val CW = 1404f
    const val CH = 1872f

    private const val cew = 645f  // in lockstep with CalendarDayPage.cew (widened 07-24; third copy found the hard way)
    private const val ceh = 50f
    private const val lo = 20f
    private val to = (CH - 35 * ceh) / 2f

    enum class Mode { FULL, SCHEDULE, TASKS_NOTES }

    fun render(context: Context, date: LocalDate, widthDp: Int, heightDp: Int, mode: Mode): Bitmap {
        // A day with no file yet is still today's page — blank ruling, live events, the almanac.
        val calendarDay = loadCalendarDay(context, date)
            ?: CalendarDay(date.year, date.monthValue, date.dayOfMonth, startHour = null)
        val events = loadCalendarEvents(context, date)
        // THE DAY, THEN THE SETTING, NEVER A LITERAL. This was `?: 5`, which meant a widget could
        // print a grid at 5am beside a day page printing the same handwriting at 7 — and the two
        // pictures disagreeing about where 9am is, on the same screen.
        val startHour = com.toolsboox.plugin.calendar.ot.PagePrefs.startHourOf(context, calendarDay)

        val fullBitmap = Bitmap.createBitmap(CW.toInt(), CH.toInt(), Bitmap.Config.ARGB_8888)
        val fullCanvas = Canvas(fullBitmap)

        // THE PAGE ITSELF (09-04). The widgets kept a private copy of the day template and went
        // stale the moment the page changed — after the 08-23 dashboard rewrite the home screen
        // still showed a 17-row checkbox Tasks list and a reading-progress "Notes" block the page
        // no longer had. Now the widget draws exactly what the app draws: the schedule grid, and
        // the right-column dashboard (Tasks + almanac, Memo, three note minis, Bookshelf), with
        // recordHits=false so a background render never overwrites the live page's tap targets.
        CalendarDayPage.drawPage(
            context, fullCanvas, calendarDay, events,
            recentNotes = noteMinis(context, calendarDay), doodle = null, recordHits = false
        )
        drawStrokes(fullCanvas, calendarDay.calendarStrokes[CalendarDay.DEFAULT_STYLE] ?: emptyList())

        val cropped = when (mode) {
            Mode.FULL -> fullBitmap
            Mode.SCHEDULE -> compositeSchedule(fullBitmap, startHour)
            Mode.TASKS_NOTES -> compositeTasksNotes(fullBitmap)
        }

        if (mode != Mode.FULL) fullBitmap.recycle()

        val cropW = cropped.width.toFloat()
        val cropH = cropped.height.toFloat()

        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()

        val fitScale = min(widthPx / cropW, heightPx / cropH)
        val rawW = (cropW * fitScale).roundToInt()
        val rawH = (cropH * fitScale).roundToInt()
        // The computed RemoteViews cap, not the old fixed 768 long side — the day-page widgets
        // ship at the widget's true pixel size now too (see WidgetBitmapBudget for the ceiling
        // arithmetic and why the fixed cap made every widget soft on a modern launcher).
        val capScale = WidgetBitmapBudget.capScale(context, rawW, rawH)
        val outW = maxOf(1, (rawW * capScale).roundToInt())
        val outH = maxOf(1, (rawH * capScale).roundToInt())

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(outBitmap)
        outCanvas.drawBitmap(cropped, Rect(0, 0, cropped.width, cropped.height), Rect(0, 0, outW, outH), null)
        cropped.recycle()

        return outBitmap
    }

    /**
     * The dashboard's three Notes minis, the way the fragment builds them but with no fragment:
     * the day's dated-note pages in [DatedNote] order (doodle excluded), each drawn at full page
     * size — the notes template, then its grams, then its ink — and scaled to the mini width.
     * A blank day has none, and the zone says "No notes yet" exactly as the page does.
     */
    private fun noteMinis(context: Context, day: CalendarDay): List<CalendarDayPage.DashThumb> {
        val doodle = com.toolsboox.plugin.calendar.ot.DatedNote.DOODLE
        val keys: Collection<String> =
            day.noteStrokes.filterValues { it.isNotEmpty() }.keys + day.imageElements.map { it.page }
        val pages = com.toolsboox.plugin.calendar.ot.DatedNote.order(keys, null)
            .filterNot { it == doodle }.takeLast(3)
        if (pages.isEmpty()) return emptyList()
        // The fragment reads the template from the app's MAIN prefs (its injected SharedPreferences).
        val template = context.getSharedPreferences("MAIN", Context.MODE_PRIVATE).getInt("calendarNoteTemplate", 0)
        return pages.map { key ->
            val bmp = runCatching {
                val full = Bitmap.createBitmap(CW.toInt(), CH.toInt(), Bitmap.Config.ARGB_8888)
                val c = Canvas(full)
                com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.drawPage(context, c, day, template, key)
                com.toolsboox.plugin.calendar.ot.CalendarPdfRenderer.drawImageElements(
                    c, day.imageElements.filter { it.page == key }, context)
                drawStrokes(c, day.noteStrokes[key] ?: emptyList())
                val mini = Bitmap.createScaledBitmap(full, 320, (CH * 320f / CW).roundToInt(), true)
                if (mini !== full) full.recycle()
                mini
            }.getOrNull()
            CalendarDayPage.DashThumb(key, bmp, noteTitle(key))
        }
    }

    /** The fragment's dashNoteTitle, verbatim: "Page n" for numeric pages, else the document label. */
    private fun noteTitle(pageKey: String): String {
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        pageKey.toIntOrNull()?.let { return "Page ${it + 1}" }
        return runCatching { docs.label(docs.surfaceOf(pageKey) ?: pageKey) }.getOrNull()
            ?: pageKey.substringBefore('-').replaceFirstChar { it.uppercase() }
    }

    /**
     * Schedule widget: morning on the left, afternoon on the right — the column folded on the
     * 1:00pm gridline OF THIS DAY'S GRID. The fold row used to be the literal 17 ("1:00pm"),
     * which is only 1pm when startHour is 5; the day's startHour is dynamic now
     * ([com.toolsboox.plugin.calendar.ot.PagePrefs.startHourOf]), so the row is computed
     * ([ScheduleSplit.splitRow]) and falls back to the middle when 1pm isn't in the drawn range.
     *
     * Whatever row the fold lands on, the two crops tile the WHOLE column — top pane 0..splitY,
     * bottom pane splitY..CH — so all 35 rows (header + 34 half-hour cells) are always shown;
     * only the fold moves. The output height is the taller pane's, so an off-centre fold means
     * one pane carries blank tail rather than the other losing hours.
     */
    private fun compositeSchedule(fullBitmap: Bitmap, startHour: Int): Bitmap {
        val splitY = (to + ScheduleSplit.splitRow(startHour) * ceh).toInt()
        val col = CalendarDayPage.scheduleColumnRect()
        val xLeft = col.left.toInt()
        val xRight = col.right.toInt()
        val halfW = xRight - xLeft

        val topH = splitY
        val botH = CH.toInt() - splitY
        val outH = maxOf(topH, botH)

        val out = Bitmap.createBitmap(halfW * 2, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        // Left: morning (header + everything before the fold)
        canvas.drawBitmap(fullBitmap, Rect(xLeft, 0, xRight, topH), Rect(0, 0, halfW, topH), null)
        // Right: afternoon (the fold onwards, through the column's bottom border)
        canvas.drawBitmap(fullBitmap, Rect(xLeft, splitY, xRight, CH.toInt()), Rect(halfW, 0, halfW * 2, botH), null)
        return out
    }

    /**
     * Tasks/Notes widget: the page's right column, its two halves side by side — Tasks (with the
     * almanac strip) + Memo on the left, Notes minis + Bookshelf on the right. The page split the
     * column EXACTLY 50/50 "so the column lifts out as a widget later"; this is that later.
     */
    private fun compositeTasksNotes(fullBitmap: Bitmap): Bitmap {
        val top = CalendarDayPage.rightTopHalfRect()
        val bot = CalendarDayPage.rightBottomHalfRect()
        val pad = 6
        val halfW = top.width().toInt() + 2 * pad
        val halfH = maxOf(top.height(), bot.height()).toInt() + 2 * pad

        val out = Bitmap.createBitmap(halfW * 2, halfH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        fun blit(r: RectF, dstLeft: Int) {
            val src = Rect(r.left.toInt() - pad, r.top.toInt() - pad, r.right.toInt() + pad, r.bottom.toInt() + pad)
            canvas.drawBitmap(fullBitmap, src, Rect(dstLeft, 0, dstLeft + src.width(), src.height()), null)
        }
        blit(top, 0)
        blit(bot, halfW)
        return out
    }

    // ---- Data loading ----

    private fun buildMoshi(): Moshi = com.toolsboox.ot.LedgerMoshi.standard()

    fun loadCalendarDay(context: Context, date: LocalDate): CalendarDay? {
        val rootPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        } else {
            File(Environment.getExternalStorageDirectory(), "toolsBoox")
        } ?: return null

        val year = date.format(DateTimeFormatter.ofPattern("yyyy"))
        val month = date.format(DateTimeFormatter.ofPattern("MM"))
        val day = date.format(DateTimeFormatter.ofPattern("dd"))
        val path = File(rootPath, "calendar/$year/$month")

        val v2File = File(path, "day-$year-$month-$day-v2.json")
        val v1File = File(path, "day-$year-$month-$day.json")

        try {
            val moshi = buildMoshi()
            if (v2File.exists()) {
                FileReader(v2File).use { reader ->
                    return moshi.adapter(CalendarDay::class.java).fromJson(reader.readText())
                }
            }
            if (v1File.exists()) {
                FileReader(v1File).use { reader ->
                    val v1 = moshi.adapter(com.toolsboox.plugin.calendar.da.v1.CalendarDay::class.java)
                        .fromJson(reader.readText())
                    return v1?.let { CalendarDay.convert(it) }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun loadCalendarEvents(context: Context, date: LocalDate): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val julianDay = 2440588 + date.toEpochDay()

        val uriBuilder = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon()
        ContentUris.appendId(uriBuilder, julianDay)
        ContentUris.appendId(uriBuilder, julianDay)

        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_COLOR,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.START_DAY,
            CalendarContract.Instances.END_DAY
        )

        try {
            context.contentResolver.query(
                uriBuilder.build(), projection,
                CalendarContract.Calendars.VISIBLE + "=?", arrayOf("1"), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val eventColor = 0xff000000 or (cursor.getLong(1))
                    val calendarColor = 0xff000000 or (cursor.getLong(2))
                    val title = cursor.getString(3) ?: ""
                    val description = cursor.getString(4) ?: ""
                    val allDay = cursor.getInt(5) > 0
                    val dtStart = cursor.getLong(6)
                    val dtEnd = cursor.getLong(7)
                    val startDay = cursor.getLong(8)
                    val endDay = cursor.getLong(9)

                    if (startDay > julianDay || endDay < julianDay) continue

                    events.add(CalendarEvent(id, title, description, allDay, dtStart, dtEnd, calendarColor, eventColor))
                }
            }
        } catch (_: SecurityException) {
        }

        events.sortBy { it.startDate }
        return events
    }

    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (stroke in strokes) {
            val points = stroke.strokePoints
            if (points.isEmpty()) continue

            paint.color = stroke.color
            paint.strokeWidth = stroke.strokeWidth

            // Quadratic Béziers through the segment midpoints, the page surface's construction.
            // The old quadTo(pre, pt) put the control ON the segment start, which degenerates to
            // a straight polyline — the documented source of the jagged look on sparse samples.
            val path = Path()
            path.moveTo(points[0].x, points[0].y)
            if (points.size == 1) {
                path.lineTo(points[0].x + 0.01f, points[0].y)
            } else {
                for (i in 1 until points.size - 1) {
                    val c = points[i]
                    val n = points[i + 1]
                    path.quadTo(c.x, c.y, (c.x + n.x) / 2f, (c.y + n.y) / 2f)
                }
                path.lineTo(points[points.size - 1].x, points[points.size - 1].y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
