package com.toolsboox

import com.toolsboox.plugin.textnotes.TextNote
import com.toolsboox.plugin.textnotes.TextNotesStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text-notes wire contract around the additive "images" key (DESIGN-notes-bridge-2026-08-22,
 * "Bridged Boox notes carry their ink"): the VPS bridge attaches media-lane refs to notes that
 * reproduce a Boox note, and this store — which rewrites daily files WHOLESALE on every save —
 * must carry the key through parse and serialize, or a device edit of a bridged day would strip
 * its ink and push the loss fleet-wide through sync. Exercised through the internal parse/serialize
 * pair, which IS the wire the bridge writes to.
 */
class TextNotesInkWireTest {

    private val ref1 = "media/e138865082e3169d255c44daa7a971680243dc312cef544fd71a99f57c2c87b0.jpg"
    private val ref2 = "media/384e2433ce740b32e589959c0584f39ac9e3bf76710c476cf4c8d760b35771c1.jpg"

    /** What the bridge writes: id/title/body/u/updatedAt plus images. */
    private val bridged =
        """[{"id":"wp-26190","title":"Notebook-15","body":"OCR text","u":1787000000000,""" +
            """"updatedAt":1787000000000,"images":["$ref1","$ref2"]}]"""

    @Test
    fun parse_reads_the_bridge_images() {
        val notes = TextNotesStore.parse(bridged)
        assertEquals(1, notes.size)
        assertEquals(listOf(ref1, ref2), notes[0].images)
    }

    @Test
    fun a_note_without_the_key_parses_to_no_ink() {
        val notes = TextNotesStore.parse("""[{"id":"tn-1","title":"t","body":"b","u":5}]""")
        assertEquals(1, notes.size)
        assertTrue(notes[0].images.isEmpty())
    }

    @Test
    fun round_trip_preserves_the_ink_through_a_device_save() {
        // parse -> serialize is exactly what a device-side save does to a pulled day file.
        val roundTripped = TextNotesStore.parse(TextNotesStore.serialize(TextNotesStore.parse(bridged)))
        assertEquals(listOf(ref1, ref2), roundTripped[0].images)
    }

    @Test
    fun an_imageless_note_serializes_without_the_key() {
        // Additive means invisible until used: the JSON of a plain note is what it always was.
        val out = TextNotesStore.serialize(listOf(TextNote("tn-1", "t", "b", 5L)))
        assertFalse(out.contains("images"))
        val o = JSONArray(out).getJSONObject(0)
        assertEquals(setOf("id", "title", "body", "u"), o.keys().asSequence().toSet())
    }

    @Test
    fun a_mixed_day_keeps_each_note_its_own_shape() {
        val notes = TextNotesStore.parse(
            """[{"id":"tn-1","title":"mine","body":"typed","u":9},""" +
                """{"id":"wp-26185","title":"Notebook-14","body":"OCR","u":8,"images":["$ref1"]}]"""
        )
        val again = TextNotesStore.parse(TextNotesStore.serialize(notes))
        assertTrue(again[0].images.isEmpty())
        assertEquals(listOf(ref1), again[1].images)
    }
}
