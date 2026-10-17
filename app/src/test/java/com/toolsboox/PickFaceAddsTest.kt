package com.toolsboox

import com.toolsboox.da.Attachment
import com.toolsboox.da.FaceSlot
import com.toolsboox.plugin.calendar.ot.GramStudio
import com.toolsboox.plugin.calendar.ot.PickFaceAdds
import com.toolsboox.plugin.feeds.ot.AddToPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * WHICH OF A LINE'S ATTACHMENTS THE FACE WEARS (review 2026-10-16): newest by date as iOS reads it
 * (#14), what a face must have on this device before it is re-drawn (#3), and a slot's file found
 * by its id on either app's names (#7).
 */
class PickFaceAddsTest {

    private fun att(id: String, kind: Attachment.Kind, name: String, at: Long?) =
        Attachment(id, kind, name, if (kind == Attachment.Kind.PHOTO) null else 10.0, at?.let { Date(it) })

    @Test
    fun `newest first is by date, a tie the later in the line - not the line's order`() {
        // A merge pooled the B6's newer voice note AHEAD of the iPhone's older photo.
        val line = listOf(
            att("v2", Attachment.Kind.AUDIO, "voice-v2.m4a", 300L),
            att("p1", Attachment.Kind.PHOTO, "photo-p1.jpg", 100L),
            att("p2", Attachment.Kind.PHOTO, "photo-p2.jpg", 200L),
            att("p3", Attachment.Kind.PHOTO, "photo-p3.jpg", 200L),
        )
        assertEquals(listOf("v2", "p3", "p2", "p1"), PickFaceAdds.newestFirst(line).map { it.id })
    }

    @Test
    fun `the media slot is the newest video, else the newest voice note, by date`() {
        val line = listOf(
            att("v-new", Attachment.Kind.VIDEO, "video-v-new.mp4", 500L),
            att("v-old", Attachment.Kind.VIDEO, "video-v-old.mp4", 100L),
            att("a1", Attachment.Kind.AUDIO, "voice-a1.m4a", 900L),
        ).reversed()
        assertEquals("v-new", PickFaceAdds.mediaClip(line)?.id)
        assertEquals("a1", PickFaceAdds.mediaClip(line.filter { it.kind == Attachment.Kind.AUDIO })?.id)
        assertNull(PickFaceAdds.mediaClip(emptyList()))
    }

    @Test
    fun `a face waits for the files it draws, and only for a while`() {
        val dir = createTempDir()
        try {
            val now = 10L * 24 * 60 * 60 * 1000
            File(dir, "photo-here.jpg").writeText("x")
            val line = listOf(
                att("ink-old", Attachment.Kind.PHOTO, "ink-ink-old.png", now - 1000),
                att("ink-new", Attachment.Kind.PHOTO, "ink-ink-new.png", now - 500),
                att("here", Attachment.Kind.PHOTO, "photo-here.jpg", now - 400),
                att("away", Attachment.Kind.PHOTO, "photo-away.jpg", now - 300),
                att("third", Attachment.Kind.PHOTO, "photo-third.jpg", now - 2000),
                att("lost", Attachment.Kind.VIDEO, "video-lost.mp4", now - 8L * 24 * 60 * 60 * 1000),
                att("voice", Attachment.Kind.AUDIO, "voice-voice.m4a", now),
            )
            // Drawn: the newest handwriting and the two newest visuals — never a voice note.
            assertEquals(listOf("ink-new", "away", "here"), PickFaceAdds.drawn(line).map { it.id })
            // Missing and worth waiting for: the new ink and the photo on its way.
            assertEquals(listOf("ink-new", "away"), PickFaceAdds.missing(dir, line, now).map { it.id })
            // A file past the wait is taken as lost — the face is drawn without it.
            assertTrue(PickFaceAdds.missing(dir, line.filter { it.id == "lost" }, now).isEmpty())
            assertFalse(PickFaceAdds.isInk(att("p", Attachment.Kind.PHOTO, "photo-p.png", 1L)))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a slot's file is found by its id - on either app's names, any case`() {
        val dir = createTempDir()
        try {
            File(dir, "photo-0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90.jpg").writeText("x")
            File(dir, "ink-AB12CD.png").writeText("x")
            File(dir, "video-EE77.mov").writeText("x")
            File(dir, "voice-ff00.m4a").writeText("x")
            assertEquals("photo-0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90.jpg",
                AddToPick.byName(dir, FaceSlot("photo", "0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90", listOf(0, 0, 1, 1)))?.filename)
            // (The test machine's disk may not tell case apart; the device's does.)
            assertTrue(AddToPick.byName(dir, FaceSlot("ink", "ab12cd", listOf(0, 0, 1, 1)))?.filename
                .equals("ink-AB12CD.png", ignoreCase = true))
            assertEquals(Attachment.Kind.VIDEO, AddToPick.byName(dir, FaceSlot("video", "EE77", listOf(0, 0, 1, 1)))?.kind)
            assertEquals(Attachment.Kind.AUDIO, AddToPick.byName(dir, FaceSlot("voice", "FF00", listOf(0, 0, 1, 1)))?.kind)
            assertNull(AddToPick.byName(dir, FaceSlot("photo", "nope", listOf(0, 0, 1, 1))))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `past 420 chars counts code points, as iOS counts scalars`() {
        val emoji = "🎤"
        assertFalse(GramStudio.isLong(emoji.repeat(300)))   // 600 UTF-16 units, 300 chars
        assertTrue(GramStudio.isLong(emoji.repeat(421)))
        assertFalse(GramStudio.isLong("a".repeat(420)))
        assertTrue(GramStudio.isLong("a".repeat(421)))
    }
}
