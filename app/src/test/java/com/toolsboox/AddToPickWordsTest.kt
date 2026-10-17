package com.toolsboox

import com.toolsboox.plugin.calendar.ot.AddToPickWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE WORDS OF "＋ ADD TO THIS PICK" — the iOS twin's AddToPickWordsTests: same strings, same order
 * (Michael 2026-10-11: "Keep parity with features and names between Android and Apple.").
 */
class AddToPickWordsTest {

    @Test
    fun `the ladder is the canon chooser's order with the extras taken out`() {
        assertEquals(listOf("✍  Handwrite a note", "🎤  Record a voice note", "📷  Take a photo",
            "⌨  Type a note", "🖼  Choose a picture", "🎥  Video"), AddToPickWords.LADDER)
        assertEquals("＋  Add to this pick", AddToPickWords.ADD)
        assertEquals("🗑  Remove from this pick…", AddToPickWords.REMOVE)
    }

    @Test
    fun `a star is never in the add ladder - an add never stars`() {
        assertFalse(AddToPickWords.LADDER.any { it.contains("★") })
        assertFalse(AddToPickWords.golden().any { it.contains("★") })
    }

    @Test
    fun `the after-step is four seconds and costs nothing to ignore`() {
        assertEquals("❝ Highlighted — its pick is in All Picks", AddToPickWords.HIGHLIGHTED)
        assertEquals("＋ Add", AddToPickWords.ADD_ACTION)
        assertEquals(4000, AddToPickWords.AFTER_STEP_MS)
    }

    @Test
    fun `the failures say what happened, never a silent nothing`() {
        assertEquals("Recording stopped at 0:12 — kept.", AddToPickWords.recordingStopped("0:12"))
        assertEquals("Video kept · 3:00 — the most a pick holds.", AddToPickWords.VIDEO_CAPPED)
        assertEquals(180, AddToPickWords.VIDEO_MAX_S)
        assertTrue(AddToPickWords.CAMERA_OFF_VIDEO.endsWith("to record a video."))
        assertEquals("Video · 0:41", AddToPickWords.videoNoPoster("0:41"))
    }

    @Test
    fun `the words section lists every string once in the pin's order`() {
        val g = AddToPickWords.golden()
        assertEquals(g.distinct(), g)
        assertEquals("＋  Add to this pick", g.first())
        assertEquals(AddToPickWords.REMOVED_ELSEWHERE, g.last())
        assertTrue(g.indexOf(AddToPickWords.STUDIO) < g.indexOf(AddToPickWords.SAVE_HIGHLIGHT))
    }

    @Test
    fun `the annotations row sums up what rides on the line`() {
        assertEquals("✍ 📷 🎥 🎤", AddToPickWords.summary(1, 2, 1, 3))
        assertEquals("🎤", AddToPickWords.summary(0, 0, 0, 1))
        assertEquals("", AddToPickWords.summary(0, 0, 0, 0))
    }
}
