package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the cross-device sync contract: a day JSON written by the iOS Ledger that
 * carries `readingEvents` and `avGrams` MUST survive a load→save on this app,
 * unchanged. Before the fields were added to [CalendarDay], Moshi silently dropped
 * them on re-serialize and an Android save erased the iPad's reading data.
 */
class CalendarDaySyncRoundTripTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(LocaleJsonAdapter())
        .add(DateJsonAdapter())
        .add(UUIDJsonAdapter())
        .build()

    /** Minimal iOS-shaped day JSON (millis dates, lowercase enum raw values). */
    private val iosDayJson = """
        {
          "year": 2026, "month": 7, "day": 13, "locale": "en-US",
          "events": [], "readingProgress": [], "hasLanes": true, "startHour": null,
          "calendarStrokes": {}, "calendarValues": {}, "noteStrokes": {},
          "textElements": [], "imageElements": [],
          "readingEvents": [
            {
              "id": "bookhl-ABC", "kind": "book", "date": 1720900000000,
              "title": "Light on Yoga", "source": "B.K.S. Iyengar",
              "excerpt": "Sthira sukham asanam.", "note": "steady + easeful",
              "location": "epubcfi(/6/4!/4/2)"
            },
            {
              "id": "art-42", "kind": "article", "date": 1720901111111,
              "title": "On Practice", "source": "Ashtanga Tech",
              "url": "https://ashtanga.tech/on-practice",
              "image": "https://ashtanga.tech/img/hero.jpg"
            }
          ],
          "avGrams": [
            { "id": "av-1", "kind": "audio", "filename": "voice-1.m4a", "duration": 12.5, "date": 1720902222222 }
          ],
          "deletedStrokeIds": ["s-1"], "deletedElementIds": [],
          "created": 1720800000000, "updated": 1720999999999
        }
    """.trimIndent()

    @Test
    fun ios_reading_data_survives_load_then_save() {
        val adapter = moshi.adapter(CalendarDay::class.java)

        val day = adapter.fromJson(iosDayJson)
        assertNotNull("day should parse", day)
        requireNotNull(day)

        // Parsed correctly.
        assertEquals(2, day.readingEvents.size)
        assertEquals(1, day.avGrams.size)
        assertEquals("Light on Yoga", day.readingEvents[0].title)
        assertEquals("steady + easeful", day.readingEvents[0].note)
        assertEquals("voice-1.m4a", day.avGrams[0].filename)
        assertEquals(12.5, day.avGrams[0].duration!!, 0.0001)
        assertEquals(listOf("s-1"), day.deletedStrokeIds)

        // Re-serialized JSON still carries the reading data (the regression we guard).
        val out = adapter.toJson(day)
        assertTrue("readingEvents preserved", out.contains("\"readingEvents\""))
        assertTrue("book highlight preserved", out.contains("Light on Yoga"))
        assertTrue("article star preserved", out.contains("on-practice"))
        assertTrue("avGrams preserved", out.contains("voice-1.m4a"))
        // Enum raw values stay wire-compatible with iOS (lowercase).
        assertTrue("book kind lowercased", out.contains("\"kind\":\"book\""))
        assertTrue("audio kind lowercased", out.contains("\"kind\":\"audio\""))

        // And a full second round-trip is stable.
        val day2 = adapter.fromJson(out)
        requireNotNull(day2)
        assertEquals(2, day2.readingEvents.size)
        assertEquals(1, day2.avGrams.size)
    }
}
