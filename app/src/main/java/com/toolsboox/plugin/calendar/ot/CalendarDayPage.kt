package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import com.toolsboox.R
import com.toolsboox.ot.Creator
import com.toolsboox.ot.OnGestureListener
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ui.CalendarDayFragment
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Create daily template of calendar plugin.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
class CalendarDayPage {

    /** A recent note (or the doodle) rendered small — handed in by the fragment, which owns
     *  renderPageBitmap — then drawn into its zone and made tappable. */
    data class DashThumb(val pageKey: String, val bitmap: android.graphics.Bitmap?, val title: String = "")

    /** A page-region hit target on the dashboard — the PickingsCover idiom: recorded at draw
     *  time in canvas space, hit-tested on a finger tap, never a View. */
    data class DashHit(val rect: android.graphics.RectF, val kind: Kind, val pageKey: String = "") {
        enum class Kind { NOTE, DOODLE, TASK_PROMOTE, MEMO_PROMOTE, BOOK }
    }

    companion object {
        // Cell width
        private const val cew = 645.0f  // widened 07-24: the old 600 left a ~200px dead band right of Tasks ("margin far greater than it needs")

        // Cell height
        private const val ceh = 50.0f

        // Left offset
        private const val lo = 20.0f

        // Top offset
        private const val to = (1872.0f - 35 * ceh) / 2.0f

        // ── Right-column dashboard (the today page IS the schedule page; CORRECTION 3, 2026-08-23) ──
        // "They're meant to be part of the page, not laid on top of the page." The dashboard is DRAWN
        // INTO this template — dividing lines, zone labels, note-mini bitmaps, the doodle preview —
        // never floating Views. ONE source of truth here for the draw and the tap hit-rects (the pane
        // is pixels; a second guess at its layout would drift). Square corners throughout — plain
        // drawRect/drawLine, so nothing is rounded ("I do not like the rounded edges").
        //
        // The LEFT column stays the schedule grid (lo..lo+cew). The RIGHT column is split EXACTLY
        // 50/50 vertically — deliberate, so the column lifts out as a widget later:
        //   TOP 50%:    Tasks (write-by-hand) over a Memo strip (write-by-hand).
        //   BOTTOM 50%: three recent-note thumbnails, then a landscape-ratio Doodle area.
        private val rcL = lo + cew + 50.0f              // right column left edge  (715)
        private val rcR = lo + 2 * cew + 50.0f          // right column right edge (1360)
        private val rcT = to                             // top of the content band (61)
        private val rcB = to + 35 * ceh                  // bottom of the content band (1811)
        private val rcMid = (rcT + rcB) / 2.0f           // the load-bearing 50/50 line
        private val memoTop = rcMid - 5 * ceh            // Memo = its label bar + ~4 writing lines
        // A thin strip along the BOTTOM of the Tasks write region carries the weather forecast + the
        // moon phase (Michael 08-23: "the weather forecast on there, as well as the moon … at the
        // bottom of tasks, no new label"). It sits UNDER the handwriting lines, still inside the
        // Tasks zone, so the hand keeps the top of the region: the ruled guide stops above it and
        // [tasksWriteRect] ends at [tasksStripTop], so a promote never gathers the strip as if it
        // were a jotted task. No label of its own — it reads as the page's own quiet almanac.
        private val tasksStripH = ceh * 1.4f
        private val tasksStripTop = memoTop - tasksStripH
        private val doodleAreaH = (rcR - rcL) * 0.55f    // wider-than-tall: the 6" landscape tile
        private val doodleLabelTop = rcB - ceh - doodleAreaH
        private val thumbsTop = rcMid + ceh + 10.0f
        private val thumbsBottom = doodleLabelTop - 10.0f

        private val dashBitmapPaint = android.graphics.Paint().apply {
            isFilterBitmap = true; isAntiAlias = true
        }

        @Volatile
        private var dashHits: List<DashHit> = emptyList()

        /** The Tasks / Memo writing regions in canvas space, so a promote can gather the strokes
         *  jotted inside them — "Tasks is meant to be a place to write by hand." */
        @Volatile
        var tasksWriteRect: android.graphics.RectF? = null
            private set

        @Volatile
        var memoWriteRect: android.graphics.RectF? = null
            private set

        /** The Doodle region — an inkable area of the page (you draw on it directly). */
        @Volatile
        var doodleWriteRect: android.graphics.RectF? = null
            private set

        /** The dashboard hit target under a canvas-space point, or null. */
        fun dashHitAt(x: Float, y: Float): DashHit? = dashHits.firstOrNull { it.rect.contains(x, y) }

        /** Forget the dashboard's recorded rects — called when a note page is drawn, so a stale
         *  rectangle can't send a tap on some other page off to a zone (the PickingsCover.clear rule). */
        fun clearDashHits() {
            dashHits = emptyList()
            tasksWriteRect = null
            memoWriteRect = null
            doodleWriteRect = null
        }

        /**
         * Process touch event on the calendar page and navigate to the view of calendar.
         *
         * @param view the surface view
         * @param motionEvent the motion event
         * @param gestureResult the gesture result
         * @param fragment the parent fragment
         * @param calendarDay the calendar data class
         * @return true
         */
        fun onTouchEvent(
            view: View, motionEvent: MotionEvent, gestureResult: Int,
            fragment: CalendarDayFragment, calendarDay: CalendarDay
        ): Boolean {
            if (motionEvent.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return true

            val year = calendarDay.year
            val month = calendarDay.month
            val day = calendarDay.day
            val locale = calendarDay.locale

            val localDate = LocalDate.of(year, month, day)

            when (gestureResult) {
                OnGestureListener.LTR -> {
                    CalendarNavigator.toDayPage(fragment, localDate.minusDays(1L))
                    return true
                }

                OnGestureListener.RTL -> {
                    CalendarNavigator.toDayPage(fragment, localDate.plusDays(1L))
                    return true
                }

                // THE SCHEDULE FACE JOINS THE WALK (Michael, 2026-08-23: "when I slide with my
                // finger from today it goes to all stars, then gram pics, then todays note").
                //
                // It never did. Left/right stepped DAYS — which is right, this is the calendar, and
                // that stays — but the vertical pair did its own thing: down went to the week, and
                // up was hardcoded to "pickings", a page that is not even a station any more since
                // the 08-23 ruling made Pickings a template. So Today was the one surface where a
                // swipe meant something different from everywhere else, and the walk could only be
                // entered from inside it. Both directions now go through the SAME two functions the
                // note pages use, which is what makes the gesture mean one thing across the day.
                OnGestureListener.UTD -> {
                    fragment.ritualStepBack()
                    return true
                }

                OnGestureListener.DTU -> {
                    fragment.ritualStepForward()
                    return true
                }
            }

            return true
        }

        /** A drawn still-open ghost row and where it landed, so a tap can act on the task —
         *  recorded at draw time, hit-tested on tap, exactly the PickingsCover/DayEventHits
         *  discipline: the panel is pixels, and a second guess at its layout would drift. */
        data class GhostRow(val open: OpenTasks.Open, val rect: android.graphics.RectF)

        @Volatile
        private var ghostRows: List<GhostRow> = emptyList()

        /** The still-open task under a canvas-space point, or null. */
        fun ghostAt(x: Float, y: Float): OpenTasks.Open? =
            ghostRows.firstOrNull { it.rect.contains(x, y) }?.open

        /** Forget the recorded rows — called when a page without the panel is drawn, so a stale
         *  rectangle can't send a tap on some other page off to a task. */
        fun clearGhostRows() {
            ghostRows = emptyList()
        }

        /** Grey for what is drawn, never written — the ghost rows must read as the page's own
         *  murmur, not as ink pretending to be the hand's. */
        private val ghostText = android.text.TextPaint(Creator.textSmallBlack).apply {
            color = android.graphics.Color.rgb(110, 110, 110)
        }
        private val ghostSince = android.text.TextPaint(Creator.textSmallBlackRight).apply {
            color = android.graphics.Color.rgb(150, 150, 150)
        }

        /** The one-line caption over the ghost block. Lighter than the rows it introduces —
         *  it explains them, it doesn't compete with them. Michael's 08-12 page: the ghosts
         *  need to SAY what they are; a first-time reader saw grey rows appear on today's
         *  page and had no way to know they were older days' leftovers, or that they answer
         *  to a tap. */
        private val ghostCaption = android.text.TextPaint(Creator.textSmallBlack).apply {
            color = android.graphics.Color.rgb(150, 150, 150)
        }

        // The compact ⚡ GLIMPSE and its hit-testing are gone. It lived in the bottom slice of the
        // Roots band and had already been drawn down to nothing there — the wins render as the panel
        // rows below, which is the honest place for them — so when the Roots band retired, the
        // glimpse's last pixels went with it. QuickWinsEngine is untouched: the panel rows and the
        // home-screen widget still read it, and only the second, silent way of showing the same
        // answer has been taken out.

        /**
         * Draw the daily template of calendar plugin.
         *
         * @param context the context
         * @param canvas the canvas
         * @param calendarDay data class
         * @param calendarEvents the list of calendar events
         * @param recentNotes the three most-recent note pages, rendered small (dashboard Notes zone)
         * @param doodle the standing daily doodle page, rendered small (dashboard Doodle zone)
         */
        fun drawPage(
            context: Context, canvas: Canvas, calendarDay: CalendarDay, calendarEvents: List<CalendarEvent>,
            recentNotes: List<DashThumb> = emptyList(), doodle: DashThumb? = null,
            recordHits: Boolean = true
        ) {
            val schedulesText = context.getString(R.string.calendar_day_schedules)

            canvas.drawRect(0.0f, 0.0f, 1404.0f, 1872.0f, Creator.fillWhite)

            // Schedules title
            canvas.drawRect(lo, to, lo + cew, to + ceh, Creator.fillGrey80)
            canvas.drawText(schedulesText, lo + 10.0f, to + ceh - 10.0f, Creator.textDefaultWhite)

            calendarEvents.sortedWith(compareBy({ it.startDate }, { it.endDate }))
            val laneOne = mutableListOf<CalendarEvent>()
            val laneTwo = mutableListOf<CalendarEvent>()
            val laneFull = mutableListOf<CalendarEvent>()
            val outside = mutableListOf<CalendarEvent>()

            // THE DAY, THEN THE SETTING, NEVER A LITERAL — and never `!!`. The non-null assertion
            // was only ever safe because the loader stamped a value onto every day on the way past;
            // that stamp is gone (it cost Michael 116 days of his chosen 7am), so a day may honestly
            // arrive with no opinion and [PagePrefs] answers for it. Resolved identically in
            // [DayEventHits], so the tap targets can never disagree with the pixels.
            val startHour = PagePrefs.startHourOf(context, calendarDay)
            if (startHour < 0) {
                outside.addAll(calendarEvents)
            } else if (!calendarDay.hasLanes) {
                outside.addAll(calendarEvents)
            } else {
                for (event in calendarEvents) {
                    if (event.allDay) {
                        outside.add(event)
                        continue
                    }

                    val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (startLocalDate.hour * 60 + startLocalDate.minute < startHour * 60) {
                        outside.add(event)
                        continue
                    }
                    val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (endLocalDate.hour * 60 + endLocalDate.minute > (startHour + 17) * 60) {
                        outside.add(event)
                        continue
                    }

                    if (checkOverlap(event, laneOne)) {
                        if (checkOverlap(event, laneTwo)) {
                            outside.add(event)
                        } else {
                            laneTwo.add(event)
                        }
                    } else {
                        laneOne.add(event)
                    }
                }

                for (event in calendarEvents) {
                    if (event.allDay) continue
                    val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (startLocalDate.hour * 60 + startLocalDate.minute < startHour * 60) continue
                    val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    if (endLocalDate.hour * 60 + endLocalDate.minute > (startHour + 17) * 60) continue

                    if (!checkFullWidth(event, laneOne, laneTwo)) {
                        laneFull.add(event)
                    }
                }
            }

            // Schedules grid
            canvas.drawLine(lo, to + ceh, lo + cew, to + ceh, Creator.lineDefaultBlack)
            for (i in 1..34) {
                if (i % 2 == 1) {
                    canvas.drawLine(lo, to + i * ceh, lo + cew, to + i * ceh, Creator.lineDefaultGrey50)
                    canvas.drawText(":00", lo + 115.0f, to + 35.0f + i * ceh, Creator.textSmallBlackRight)
                    if (startHour > -1) {
                        val localTime = LocalTime.of(i / 2 + startHour, 0, 0)
                        if (DateFormat.is24HourFormat(context)) {
                            val hourText = localTime.format(DateTimeFormatter.ofPattern("HH"))
                            canvas.drawText(hourText, lo + 40.0f, to + 20.0f + (i + 1) * ceh, Creator.text60BlackCenter)
                        } else {
                            val hourText = localTime.format(DateTimeFormatter.ofPattern("h"))
                            val ampmText = localTime.format(DateTimeFormatter.ofPattern("a"))
                            canvas.drawText(hourText, lo + 70.0f, to + 40.0f + (i) * ceh, Creator.textDefaultBlackRight)
                            canvas.drawText(ampmText, lo + 70.0f, to + 30.0f + (i + 1) * ceh, Creator.textDefaultBlackRight)
                        }
                    }
                } else {
                    canvas.drawLine(lo + 80.0f, to + i * ceh, lo + cew, to + i * ceh, Creator.lineDefaultGrey50)
                    canvas.drawRect(lo + 80.0f, to + i * ceh, lo + cew, to + i * ceh + ceh, Creator.fillGrey20)
                    canvas.drawText(":30", lo + 115.0f, to + 35.0f + i * ceh, Creator.textSmallBlackRight)
                }
            }
            canvas.drawLine(lo, to + 35 * ceh, lo + cew, to + 35 * ceh, Creator.lineDefaultBlack)
            canvas.drawLine(lo + 120.0f, to + ceh, lo + 120.0f, to + 35 * ceh, Creator.lineDefaultBlack)

            if (laneOne.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneOne, 120.0f, (cew - 120.0f) / 2)
            }
            if (laneTwo.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneTwo, 120.0f + (cew - 120.0f) / 2, (cew - 120.0f) / 2)
            }
            if (laneFull.isNotEmpty()) {
                drawEventLane(canvas, startHour, laneFull, 120.0f, cew - 120.0f)
            }

            // The RIGHT column is the dashboard, DRAWN INTO this same template bitmap (part of the
            // page, not laid on top). Tasks/Memo are write-by-hand regions; Notes/Doodle are painted
            // minis with page-region hit-rects. No ghost rows here: Tasks is now the hand's own place
            // to write, not a rendered row-list.
            ghostRows = emptyList()
            val date = LocalDate.of(calendarDay.year, calendarDay.month, calendarDay.day)
            drawDashboardRight(context, canvas, date, recentNotes, doodle, recordHits)
        }

        // ── Zone geometry for anyone who crops this page (the home-screen widgets) ─────────────
        // ONE source of truth: the widgets used to keep a private copy of this template and a
        // private guess at where its zones sat, and drifted for two weeks after the 08-23 rewrite
        // (they still showed a 17-row Tasks list and a reading-progress Notes block). They now draw
        // THIS page and crop by THESE rects.
        /** The schedule column — label bar through the last half-hour row, with its margin. */
        fun scheduleColumnRect() = android.graphics.RectF(0.0f, 0.0f, lo + cew + 20.0f, 1872.0f)
        /** Right column, top half: Tasks (with the almanac strip) over Memo. */
        fun rightTopHalfRect() = android.graphics.RectF(rcL, rcT, rcR, rcMid)
        /** Right column, bottom half: Notes minis over the Bookshelf. */
        fun rightBottomHalfRect() = android.graphics.RectF(rcL, rcMid, rcR, rcB)

        /**
         * The right-column dashboard, painted into the template. Two columns overall (Schedule left);
         * the right column split EXACTLY 50/50 — TOP: Tasks + Memo write-by-hand regions; BOTTOM:
         * three recent-note minis and a landscape-ratio Doodle area. All corners square (plain
         * drawRect/drawLine). Records the tap hit-rects and the two writing regions so the fragment
         * resolves finger taps (the PickingsCover idiom) and gathers promote strokes.
         */
        private fun drawDashboardRight(
            context: Context, canvas: Canvas, date: LocalDate, recentNotes: List<DashThumb>, doodle: DashThumb?,
            recordHits: Boolean = true
        ) {
            val tasksText = context.getString(R.string.calendar_day_tasks)
            val hits = mutableListOf<DashHit>()

            // Outer frame + the load-bearing 50/50 divider.
            canvas.drawRect(rcL, rcT, rcR, rcB, Creator.lineDefaultBlack)
            canvas.drawLine(rcL, rcMid, rcR, rcMid, Creator.lineDefaultBlack)

            // ── TOP half: Tasks (with a weather/moon strip at its foot) over Memo ────────────────────
            drawZoneLabel(canvas, rcT, tasksText)
            hits.add(DashHit(promoteGlyphRect(rcT), DashHit.Kind.TASK_PROMOTE))
            drawRuled(canvas, rcT + ceh, tasksStripTop)
            if (recordHits) tasksWriteRect = android.graphics.RectF(rcL, rcT + ceh, rcR, tasksStripTop)
            drawTasksWeatherMoon(context, canvas, date)

            canvas.drawLine(rcL, memoTop, rcR, memoTop, Creator.lineDefaultBlack)
            drawZoneLabel(canvas, memoTop, "Memo")
            hits.add(DashHit(promoteGlyphRect(memoTop), DashHit.Kind.MEMO_PROMOTE))
            drawRuled(canvas, memoTop + ceh, rcMid)
            if (recordHits) memoWriteRect = android.graphics.RectF(rcL, memoTop + ceh, rcR, rcMid)

            // ── BOTTOM half: three note minis (title BELOW each), then a landscape Doodle ─────────────
            drawZoneLabel(canvas, rcMid, "Notes")
            val gap = 14.0f
            val cellW = (rcR - rcL - 4 * gap) / 3.0f
            val titleH = ceh                                   // a line under each thumb for its name
            for (i in 0 until 3) {
                val cl = rcL + gap + i * (cellW + gap)
                // The thumbnail cell leaves a title line below it.
                val cell = android.graphics.RectF(cl, thumbsTop, cl + cellW, thumbsBottom - titleH)
                canvas.drawRect(cell, Creator.lineDefaultGrey50)
                val thumb = recentNotes.getOrNull(i)
                if (thumb?.bitmap != null) {
                    drawBitmapFit(canvas, thumb.bitmap, cell)
                    hits.add(DashHit(cell, DashHit.Kind.NOTE, thumb.pageKey))
                    // The note's name, centered under its thumbnail (ellipsized to the cell).
                    val name = thumb.title.ifBlank { "" }
                    if (name.isNotEmpty()) {
                        val clipped = android.text.TextUtils.ellipsize(
                            name, Creator.textSmallBlack, cellW, android.text.TextUtils.TruncateAt.END
                        ).toString()
                        val tw = Creator.textSmallBlack.measureText(clipped)
                        canvas.drawText(clipped, cell.centerX() - tw / 2f, thumbsBottom - 10.0f, Creator.textSmallBlack)
                    }
                }
            }
            if (recentNotes.isEmpty()) {
                canvas.drawText("No notes yet", rcL + gap, thumbsTop + 40.0f, Creator.textSmallBlack)
            }

            // BOOKSHELF (was Doodle — Michael, 09-03: "turn Doodle into a bookshelf surfacer
            // like you did for notes above it"): the three books the hand was most recently in,
            // as covers with titles; a tap opens the book. Doodling still has its standing page
            // through Notes' templates — this strip surfaces the reading the way the minis
            // surface the writing.
            canvas.drawLine(rcL, doodleLabelTop, rcR, doodleLabelTop, Creator.lineDefaultBlack)
            drawZoneLabel(canvas, doodleLabelTop, "Bookshelf")
            if (recordHits) doodleWriteRect = android.graphics.RectF(rcL, doodleLabelTop + ceh, rcR, rcB)
            runCatching {
                val touched = com.toolsboox.plugin.reader.ui.ReaderPositionStore.lastReadTimes(context)
                // The playhead store keys by NAME WITHOUT EXTENSION (bookNoteKey strips it),
                // but Entry.name is the filename WITH it — so a bare containsKey(name) never
                // matched and the zone was always empty (09-03: "bookshelf on today isn't
                // showing covers"). Strip the extension to meet the key it was filed under.
                fun keyOf(e: com.toolsboox.plugin.reader.ui.BookshelfSource.Entry) =
                    e.name.substringBeforeLast('.')
                val entries = com.toolsboox.plugin.reader.ui.BookshelfSource.list(context)
                    .filter { touched.containsKey(keyOf(it)) }
                    .sortedByDescending { touched[keyOf(it)] ?: 0L }
                    .take(3)
                val bTop = doodleLabelTop + ceh + 10.0f
                val bBottom = rcB - titleH
                for ((i, e) in entries.withIndex()) {
                    val cl = rcL + gap + i * (cellW + gap)
                    val cell = android.graphics.RectF(cl, bTop, cl + cellW, bBottom)
                    canvas.drawRect(cell, Creator.lineDefaultGrey50)
                    runCatching {
                        drawBitmapFit(canvas, com.toolsboox.plugin.reader.ui.BookCovers.cover(context, e), cell)
                    }
                    hits.add(DashHit(cell, DashHit.Kind.BOOK, e.name))
                    val clipped = android.text.TextUtils.ellipsize(
                        e.title, Creator.textSmallBlack, cellW, android.text.TextUtils.TruncateAt.END
                    ).toString()
                    val tw = Creator.textSmallBlack.measureText(clipped)
                    canvas.drawText(clipped, cell.centerX() - tw / 2f, rcB - 10.0f, Creator.textSmallBlack)
                }
                if (entries.isEmpty())
                    canvas.drawText("Nothing mid-read yet", rcL + gap, bTop + 40.0f, Creator.textSmallBlack)
            }

            if (recordHits) dashHits = hits
        }

        /** A dashboard zone label bar: grey fill + white text + a drawn "→" promote glyph at its
         *  right end (the tap affordance the fragment maps to [DashHit.Kind.TASK_PROMOTE] /
         *  [DashHit.Kind.MEMO_PROMOTE]). Square corners. */
        private fun drawZoneLabel(canvas: Canvas, top: Float, text: String) {
            canvas.drawRect(rcL, top, rcR, top + ceh, Creator.fillGrey80)
            canvas.drawText(text, rcL + 12.0f, top + ceh - 12.0f, Creator.textDefaultWhite)
            canvas.drawText("→", rcR - ceh + 12.0f, top + ceh - 12.0f, Creator.textDefaultWhite)
        }

        /** The "→" promote hit-rect at the right end of a zone's label bar. */
        private fun promoteGlyphRect(top: Float): android.graphics.RectF =
            android.graphics.RectF(rcR - ceh, top, rcR, top + ceh)

        /** The weather forecast + moon phase, tucked along the bottom of the Tasks region — no zone
         *  label of its own (Michael 08-23), a quiet strip UNDER the handwriting lines. Reuses
         *  [WeatherMoon]'s already-fetched cache (refreshed on resume, never on this draw path): the
         *  summary line — current glyph + temp, the day's high/low, precip, and the moon phase, or
         *  moon-only on a day that isn't today / when offline — over a compact hour-by-hour
         *  temperature sparkline for the day (drawn only when a forecast is actually cached). */
        private fun drawTasksWeatherMoon(context: Context, canvas: Canvas, date: LocalDate) {
            // A faint rule fences the strip off from the writing lines above it.
            canvas.drawLine(rcL, tasksStripTop, rcR, tasksStripTop, Creator.lineDefaultGrey50)

            val padL = rcL + 12.0f
            val padR = rcR - 12.0f
            // The summary line: weather (today, if cached) then the moon; moon-only otherwise.
            Creator.drawEllipsizedText(
                canvas, WeatherMoon.summary(context, date), Creator.textSmallBlack,
                padL, tasksStripTop + 28.0f, padR - padL
            )

            // The day's hourly temperatures as a compact sparkline in the lower band. Empty ⇒ nothing
            // drawn, exactly as a day with no cached forecast should read.
            val temps = WeatherMoon.hourly(context, date)
            if (temps.size >= 2) {
                val lo = temps.minOrNull() ?: return
                val hi = temps.maxOrNull() ?: return
                val span = (hi - lo).takeIf { it > 0.5f } ?: 1.0f
                val top = tasksStripTop + 36.0f
                val bottom = memoTop - 8.0f
                val stepX = (padR - padL) / (temps.size - 1)
                var px = padL
                var py = bottom - (temps[0] - lo) / span * (bottom - top)
                for (i in 1 until temps.size) {
                    val x = padL + i * stepX
                    val y = bottom - (temps[i] - lo) / span * (bottom - top)
                    canvas.drawLine(px, py, x, y, Creator.lineDefaultGrey50)
                    px = x; py = y
                }
            }
        }

        /** Faint ruled guide lines through a write-by-hand region, so the hand knows where to jot. */
        private fun drawRuled(canvas: Canvas, top: Float, bottom: Float) {
            var y = top + ceh
            while (y < bottom - 1.0f) {
                canvas.drawLine(rcL, y, rcR, y, Creator.lineDefaultGrey50)
                y += ceh
            }
        }

        /** Draw [bmp] fitted (aspect-preserving, centred) inside [rect] with a small inset. */
        private fun drawBitmapFit(canvas: Canvas, bmp: android.graphics.Bitmap, rect: android.graphics.RectF) {
            if (bmp.width <= 0 || bmp.height <= 0) return
            val pad = 8.0f
            val availW = rect.width() - 2 * pad
            val availH = rect.height() - 2 * pad
            if (availW <= 0 || availH <= 0) return
            val s = minOf(availW / bmp.width, availH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            val dl = rect.left + (rect.width() - w) / 2
            val dt = rect.top + (rect.height() - h) / 2
            canvas.drawBitmap(bmp, null, android.graphics.RectF(dl, dt, dl + w, dt + h), dashBitmapPaint)
        }

        private fun drawEventLane(canvas: Canvas, startHour: Int, lane: MutableList<CalendarEvent>, llo: Float, lw: Float) {
            for (event in lane) {
                val startLocalDate = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val endLocalDate = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val cehs = (startLocalDate.hour * 60 + startLocalDate.minute - startHour * 60) / 30.0f * ceh + 1.0f * ceh
                val cehe = (endLocalDate.hour * 60 + endLocalDate.minute - startHour * 60) / 30.0f * ceh + 1.0f * ceh
                canvas.drawRect(lo + llo + 5.0f, to + cehs, lo + llo + lw - 5.0f, to + cehe, Creator.fillGrey10)
                canvas.drawRect(lo + llo + 5.0f, to + cehs, lo + llo + lw - 5.0f, to + cehe, Creator.lineDefaultBlack)

                Creator.drawEllipsizedText(
                    canvas, event.title, Creator.textSmallBlack,
                    lo + llo + 15.0f, to + cehs + ceh * 0.66f - 10.0f, lw - 20.0f
                )
            }
        }

        private fun checkOverlap(event: CalendarEvent, lane: MutableList<CalendarEvent>): Boolean {
            for (laneEvent in lane) {
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            return false
        }

        private fun checkFullWidth(event: CalendarEvent, laneOne: MutableList<CalendarEvent>, laneTwo: MutableList<CalendarEvent>): Boolean {
            for (laneEvent in laneOne) {
                if (laneEvent.id == event.id) continue
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            for (laneEvent in laneTwo) {
                if (laneEvent.id == event.id) continue
                if (event.startDate in laneEvent.startDate..<laneEvent.endDate) return true
                if (laneEvent.startDate in event.startDate..<event.endDate) return true
            }

            laneOne.remove(event)
            laneTwo.remove(event)

            return false
        }
    }
}
