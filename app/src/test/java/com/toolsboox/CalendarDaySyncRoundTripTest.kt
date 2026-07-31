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

    /**
     * A/V gram elements: a day written by a build that predates the media fields must still
     * open (they default to a plain image gram), and a day carrying them must survive a
     * load→save unchanged. This is the read-both half of the lockstep contract — neither
     * platform may drop the other's media refs on re-serialize.
     */
    @Test
    fun av_gram_image_elements_round_trip_and_old_files_still_open() {
        val adapter = moshi.adapter(CalendarDay::class.java)

        val json = """
            {
              "year": 2026, "month": 7, "day": 20, "locale": "en-US",
              "events": [], "readingProgress": [], "hasLanes": true, "startHour": null,
              "calendarStrokes": {}, "calendarValues": {}, "noteStrokes": {},
              "textElements": [], "readingEvents": [], "avGrams": [],
              "imageElements": [
                {
                  "elementId": "11111111-1111-1111-1111-111111111111", "timestamp": 1720900000000,
                  "x": 10.0, "y": 20.0, "width": 300.0, "height": 200.0,
                  "data": "AAAA", "page": "pickings"
                },
                {
                  "elementId": "22222222-2222-2222-2222-222222222222", "timestamp": 1720900000001,
                  "x": 40.0, "y": 50.0, "width": 300.0, "height": 200.0,
                  "data": "POSTER", "page": "pickings",
                  "mediaKind": "video", "attachmentId": "av-9", "durationMs": 41000,
                  "mediaUrl": "https://pub-x.r2.dev/ledgr-abc.mp4",
                  "mediaTitle": "Backbend, Tuesday", "mediaDate": "2026-07-18"
                }
              ],
              "deletedStrokeIds": [], "deletedElementIds": [],
              "created": 1720800000000, "updated": 1720999999999
            }
        """.trimIndent()

        val day = adapter.fromJson(json)
        requireNotNull(day)
        assertEquals(2, day.imageElements.size)

        // An element written before the media fields existed opens as a plain image gram.
        val plain = day.imageElements[0]
        assertEquals("", plain.mediaKind)
        assertEquals("", plain.attachmentId)
        assertEquals(0, plain.durationMs)

        // A video gram keeps its poster frame in `data` — so every surface that already draws
        // grams renders it — plus the pointer to the sounding part.
        val video = day.imageElements[1]
        assertEquals("video", video.mediaKind)
        assertEquals("POSTER", video.data)
        assertEquals("av-9", video.attachmentId)
        assertEquals(41000, video.durationMs)
        assertEquals("https://pub-x.r2.dev/ledgr-abc.mp4", video.mediaUrl)
        assertEquals("Backbend, Tuesday", video.mediaTitle)
        assertEquals("2026-07-18", video.mediaDate)

        // Re-serialize: the media refs must not be dropped (the sync regression we guard).
        val out = adapter.toJson(day)
        assertTrue("mediaKind preserved", out.contains("\"mediaKind\":\"video\""))
        assertTrue("attachment ref preserved", out.contains("av-9"))
        assertTrue("remote url preserved", out.contains("ledgr-abc.mp4"))
        assertTrue("title preserved", out.contains("Backbend, Tuesday"))

        val day2 = adapter.fromJson(out)
        requireNotNull(day2)
        assertEquals("video", day2.imageElements[1].mediaKind)
        assertEquals(41000, day2.imageElements[1].durationMs)
    }

    /**
     * Media-by-reference fields (WIRE-MEDIA-BY-REFERENCE.md): `dataRef` on an image element and
     * `cropRef` on a ledger item must survive decode→encode — this is the exact hazard the wire
     * brief's phasing exists for (both forks drop unknown JSON keys on re-save, so a build that
     * loses these fields silently deletes the image everywhere, via sync). And a day written
     * before the fields existed must still open, with the defaults ("" / null).
     */
    @Test
    fun media_refs_survive_round_trip_and_old_files_still_open() {
        val adapter = moshi.adapter(CalendarDay::class.java)

        val json = """
            {
              "year": 2026, "month": 7, "day": 31, "locale": "en-US",
              "events": [], "readingProgress": [], "hasLanes": true, "startHour": null,
              "calendarStrokes": {}, "calendarValues": {}, "noteStrokes": {},
              "textElements": [], "readingEvents": [], "avGrams": [],
              "imageElements": [
                {
                  "elementId": "33333333-3333-3333-3333-333333333333", "timestamp": 1722400000000,
                  "x": 10.0, "y": 20.0, "width": 300.0, "height": 200.0,
                  "data": "", "page": "pickings",
                  "dataRef": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08.jpg"
                },
                {
                  "elementId": "44444444-4444-4444-4444-444444444444", "timestamp": 1722400000001,
                  "x": 40.0, "y": 50.0, "width": 300.0, "height": 200.0,
                  "data": "AAAA", "page": "pickings"
                }
              ],
              "ledgerItems": [
                {
                  "id": "task-ref-1", "kind": "task", "text": "pinned face",
                  "date": 1722400000000, "display": "ink", "done": false,
                  "cropRef": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.png"
                }
              ],
              "deletedStrokeIds": [], "deletedElementIds": [],
              "created": 1722300000000, "updated": 1722499999999
            }
        """.trimIndent()

        val day = adapter.fromJson(json)
        requireNotNull(day)

        // Parsed: an externalized element carries "" + ref; a pre-migration one defaults.
        val externalized = day.imageElements[0]
        assertEquals("", externalized.data)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08.jpg", externalized.dataRef)
        val inline = day.imageElements[1]
        assertEquals("AAAA", inline.data)
        assertEquals("old element opens with the default (no ref)", "", inline.dataRef)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.png",
            day.ledgerItems[0].cropRef
        )
        assertEquals("cropRef with no crop still opens", null, day.ledgerItems[0].crop)

        // Re-serialize: the refs must not be dropped, and `data` must stay a present key.
        val out = adapter.toJson(day)
        assertTrue("dataRef preserved", out.contains("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08.jpg"))
        assertTrue("cropRef preserved", out.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.png"))
        assertTrue("empty data stays a present key on the wire", out.contains("\"data\":\"\""))

        // And a full second round-trip is stable.
        val day2 = adapter.fromJson(out)
        requireNotNull(day2)
        assertEquals(externalized.dataRef, day2.imageElements[0].dataRef)
        assertEquals(day.ledgerItems[0].cropRef, day2.ledgerItems[0].cropRef)
    }
}
