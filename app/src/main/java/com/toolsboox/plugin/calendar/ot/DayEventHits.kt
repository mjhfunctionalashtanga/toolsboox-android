package com.toolsboox.plugin.calendar.ot

import android.graphics.RectF
import com.toolsboox.plugin.calendar.da.v1.CalendarEvent
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import java.time.Instant
import java.time.ZoneId

/**
 * Where each event ended up on the drawn day page, so a tap can find it.
 *
 * The page is a picture: events are painted onto the template, and the picture has no idea it
 * contains anything. Touching one and having nothing happen is the natural consequence, and also
 * plainly wrong — you can see the thing, so you should be able to open it.
 *
 * Rather than hit-test against a second, parallel guess at the layout, this records the same
 * rectangles [CalendarDayPage] draws into. Both call [layout], so a tap can never disagree with a
 * pixel: if the two drifted apart, taps would land on the wrong event and there would be nothing
 * on screen to explain why.
 */
object DayEventHits {

    private const val CEW = 645f  // keep in step with CalendarDayPage.cew
    private const val CEH = 50f
    private const val LO = 20f
    private val TO = (1872f - 35 * CEH) / 2f

    /** An event and the rectangle it occupies in page design space. */
    data class Hit(val event: CalendarEvent, val rect: RectF, val onSchedule: Boolean)

    /**
     * Split [events] the way the page does, and give each one its rectangle.
     *
     * An event goes to the Stars & Events list ("outside") when it is all-day, when it starts
     * before the grid's first hour, when it ends after its last, or when both lanes are already
     * busy at that time. The Whitman-Walker case is the second of those: an appointment running
     * 00:00–23:59 is not flagged all-day, but a grid that begins at 5am has no row to put it in.
     */
    fun layout(day: CalendarDay, events: List<CalendarEvent>): List<Hit> {
        val startHour = day.startHour ?: 5
        val laneOne = mutableListOf<CalendarEvent>()
        val laneTwo = mutableListOf<CalendarEvent>()
        val outside = mutableListOf<CalendarEvent>()

        if (startHour < 0 || !day.hasLanes) {
            outside.addAll(events)
        } else {
            for (event in events) {
                if (event.allDay) { outside.add(event); continue }
                val s = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                if (s.hour * 60 + s.minute < startHour * 60) { outside.add(event); continue }
                val e = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                if (e.hour * 60 + e.minute > (startHour + 17) * 60) { outside.add(event); continue }
                when {
                    !overlaps(event, laneOne) -> laneOne.add(event)
                    !overlaps(event, laneTwo) -> laneTwo.add(event)
                    else -> outside.add(event)
                }
            }
        }

        val hits = mutableListOf<Hit>()
        val laneFullWidth = laneTwo.isEmpty()
        for ((lane, llo, lw) in listOf(
            Triple(laneOne, 120f, if (laneFullWidth) CEW - 120f else (CEW - 120f) / 2),
            Triple(laneTwo, 120f + (CEW - 120f) / 2, (CEW - 120f) / 2)
        )) {
            for (event in lane) {
                val s = Instant.ofEpochMilli(event.startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val e = Instant.ofEpochMilli(event.endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val top = (s.hour * 60 + s.minute - startHour * 60) / 30f * CEH + CEH
                val bottom = (e.hour * 60 + e.minute - startHour * 60) / 30f * CEH + CEH
                hits += Hit(event, RectF(LO + llo + 5f, TO + top, LO + llo + lw - 5f, TO + bottom), true)
            }
        }

        // The Stars & Events rows, in the order the page lists them: one row each, starting under
        // the title bar at 19*ceh. Only the first eight are drawn, so only those can be touched.
        var row = 0
        for (event in outside.take(8)) {
            val top = TO + (19 + row * 2) * CEH
            hits += Hit(event, RectF(LO + CEW + 50f, top, LO + 2 * CEW + 50f, top + 2 * CEH), false)
            row++
        }
        return hits
    }

    /** The event drawn at this point in page design space, if any. Topmost/last wins. */
    fun at(day: CalendarDay, events: List<CalendarEvent>, x: Float, y: Float): CalendarEvent? =
        layout(day, events).lastOrNull { it.rect.contains(x, y) }?.event

    private fun overlaps(event: CalendarEvent, lane: List<CalendarEvent>): Boolean =
        lane.any { event.startDate < it.endDate && it.startDate < event.endDate }
}
