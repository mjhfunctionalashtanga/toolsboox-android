package com.toolsboox.plugin.reader

import com.toolsboox.plugin.reader.ot.BalloonGeometry
import com.toolsboox.plugin.reader.ot.BalloonGeometry.Line
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 💬 YOLO and the page's text, combined (Michael 2026-10-11, comics slice 2). The first test is
 * the iPad's BalloonGeometryTests.testTextMergesIntoBalloons, verbatim (its 0–1 lines turned
 * into pixels of the same 1000×1500 page), so the two apps cannot drift apart.
 */
class BalloonGeometryTest {

    private val W = 1000f
    private val H = 1500f

    /** A line given in page-relative 0–1 [x, y, w, h], as the iPad's vector writes it. */
    private fun line(x: Double, y: Double, w: Double, h: Double, text: String) =
        Line((x * W).toFloat(), (y * H).toFloat(), ((x + w) * W).toFloat(), ((y + h) * H).toFloat(), text)

    @Test
    fun theIpadVector() {
        val b0 = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f)
        val b1 = floatArrayOf(0.6f, 0.5f, 0.3f, 0.1f)
        val lines = listOf(
            line(0.12, 0.15, 0.22, 0.02, "HOW ARE YOU?"),
            line(0.12, 0.12, 0.2, 0.02, "HELLO THERE."),
            line(0.5, 0.95, 0.03, 0.02, "12"),           // a page number: no letter, never a stop
            line(0.05, 0.7, 0.25, 0.02, "MEANWHILE..."),
            line(0.05, 0.725, 0.15, 0.02, "IN GOTHAM"),
            line(0.7, 0.52, 0.01, 0.02, "A"),            // one character: noise
            line(0.33, 0.18, 0.1, 0.02, "HALF IN"),      // 70% inside b0: joins it
            line(0.36, 0.13, 0.2, 0.02, "EDGE TEXT"),    // 20% inside b0: its own balloon
        )
        val out = BalloonGeometry.combine(listOf(b0, b1), lines, W, H)
        assertEquals(listOf("HELLO THERE.\nHOW ARE YOU?\nHALF IN", "", "MEANWHILE...\nIN GOTHAM", "EDGE TEXT"), out.texts)
        assertEquals(4, out.rects.size)
        assertEquals(b0.toList(), out.rects[0].toList())
        assertEquals(b1.toList(), out.rects[1].toList())
        val expect = listOf(
            doubleArrayOf(0.0, 0.678390625, 0.3537890625, 0.08821875),
            doubleArrayOf(0.3152109375, 0.117140625, 0.289578125, 0.04571875))
        for ((r, e) in out.rects.drop(2).zip(expect)) for (i in 0 until 4) assertEquals(e[i], r[i].toDouble(), 1e-5)
        // No text at all: the YOLO balloons pass through with empty words.
        val bare = BalloonGeometry.combine(listOf(b0), emptyList(), W, H)
        assertEquals(listOf(b0.toList()), bare.rects.map { it.toList() })
        assertEquals(listOf(""), bare.texts)
    }

    /** The iPad's BalloonGeometryTests.testLetteringAddsStopsOnlyWhereYoloFoundNone, verbatim
     *  (review, 2026-10-11 — fewer taps): lettering adds stops only where YOLO found none. On a
     *  page YOLO read, the stops are YOLO's, in order, and lines inside them are their words; a
     *  caption or a sound effect outside them adds no tap. */
    @Test
    fun letteringAddsStopsOnlyWhereYoloFoundNone() {
        val b0 = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f)
        val lines = listOf(
            line(0.12, 0.12, 0.2, 0.02, "HELLO THERE."),
            line(0.05, 0.7, 0.25, 0.02, "MEANWHILE..."),   // outside every balloon
            line(0.6, 0.3, 0.1, 0.03, "KRAKOOM"),          // a sound effect
        )
        val read = BalloonGeometry.walkStops(listOf(b0), lines, W, H)
        assertEquals(listOf(b0.toList()), read.rects.map { it.toList() })
        assertEquals(listOf("HELLO THERE."), read.texts)
        // The merge itself still finds the stray lettering (the shared vector is unchanged)…
        assertEquals(3, BalloonGeometry.combine(listOf(b0), lines, W, H).rects.size)
        // …but YOLO found nothing: the lettering IS the walk (the 09-13 text fallback), exactly the merge.
        val none = BalloonGeometry.walkStops(emptyList(), lines, W, H)
        val merged = BalloonGeometry.combine(emptyList(), lines, W, H)
        assertEquals(merged.rects.map { it.toList() }, none.rects.map { it.toList() })
        assertEquals(merged.texts, none.texts)
        assertEquals(3, none.rects.size)
        // Nothing read at all: nothing to walk, nothing to say.
        val blank = BalloonGeometry.walkStops(emptyList(), emptyList(), W, H)
        assertEquals(0, blank.rects.size)
        assertEquals(0, blank.texts.size)
    }

    @Test
    fun closeBalloonsNeverShareWords() {
        // Two balloons a few pixels apart: lines attach ONE BY ONE, before any merging, so the
        // merge rule (which would glue lines within a line-height) never crosses balloons.
        val yolo = listOf(
            floatArrayOf(0.10f, 0.10f, 0.20f, 0.05f),   // x 100–300
            floatArrayOf(0.305f, 0.10f, 0.20f, 0.05f),  // x 305–505
        )
        val lines = listOf(Line(120f, 160f, 290f, 210f, "LEFT"), Line(315f, 160f, 495f, 210f, "RIGHT"))
        val out = BalloonGeometry.combine(yolo, lines, W, H)
        assertEquals(listOf("LEFT", "RIGHT"), out.texts)
    }

    @Test
    fun aLineMostlyOutsideStaysLoose() {
        // Only 40% of the line lies inside the balloon: below ATTACH_MIN, so it becomes a
        // balloon of its own instead of lending its words to the wrong one.
        val yolo = listOf(floatArrayOf(0.10f, 0.10f, 0.10f, 0.10f))      // x 100–200
        val lines = listOf(Line(160f, 160f, 260f, 200f, "HALF OUT"))     // x 160–260: 40 of 100 inside
        val out = BalloonGeometry.combine(yolo, lines, W, H)
        assertEquals(listOf("", "HALF OUT"), out.texts)
    }

    @Test
    fun noYoloIsTheTextFallback() {
        // YOLO found nothing (a DC page): text alone finds the balloons — split lines within a
        // line-height merge into one balloon; a one-letter line and a bare number are dropped.
        val lines = listOf(
            Line(100f, 100f, 300f, 130f, "I KNEW"),
            Line(100f, 140f, 280f, 170f, "IT WAS YOU."),
            Line(800f, 1200f, 805f, 1210f, "x"),
            Line(900f, 1400f, 930f, 1420f, "12"),
        )
        val out = BalloonGeometry.combine(emptyList(), lines, W, H)
        assertEquals(1, out.rects.size)
        assertEquals(listOf("I KNEW\nIT WAS YOU."), out.texts)
    }
}
