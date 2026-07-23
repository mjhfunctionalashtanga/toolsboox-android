package com.toolsboox.plugin.calendar.da.v1

import com.squareup.moshi.JsonClass

/**
 * Calendar event data class.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */

@JsonClass(generateAdapter = true)
data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String,
    val allDay: Boolean,
    val startDate: Long,
    val endDate: Long,
    val calendarColor: Long,
    val eventColor: Long,
    /** Where it is. Read from the device calendar; blank when the event doesn't say.
     *  New fields with defaults, so day files written by older builds still decode. */
    val location: String = "",
    /** Which calendar it came from — "Work", "Family" — so a busy day is readable at a glance. */
    val calendarName: String = "",
    /** Who called it, when the calendar says. */
    val organizer: String = "",
)
