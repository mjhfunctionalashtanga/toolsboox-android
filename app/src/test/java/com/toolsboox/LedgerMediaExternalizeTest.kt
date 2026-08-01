package com.toolsboox

import com.squareup.moshi.Moshi
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.CryptoUtils
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LedgerMedia
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

/**
 * The Phase W write half of the media wire (WIRE-MEDIA-BY-REFERENCE.md): the one-chokepoint
 * externalization [LedgerMedia.externalizeDay] performs at the day-save seam. Everything here
 * is the pinned contract, tested against the wire's invariants: over-threshold inline payloads
 * move to `<sha256-of-bytes>.<ext>` in the media dir and the element keeps `data = ""` plus the
 * ref; under-threshold and unnameable bytes stay inline; `elementId`/`timestamp` are never
 * touched and `gramId` is backfilled from the base64 BEFORE it leaves the file; a failed blob
 * write leaves the payload inline; and the whole pass is idempotent. Context-free, like the
 * resolver tests, because the write half deliberately takes a plain [File] media dir.
 */
class LedgerMediaExternalizeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val moshi: Moshi = Moshi.Builder()
        .add(LocaleJsonAdapter())
        .add(DateJsonAdapter())
        .add(UUIDJsonAdapter())
        .build()

    // ── Fixture pixels ────────────────────────────────────────────────────────────────────────

    /** Bytes wearing the PNG magic, [size] long — enough truth for the magic-and-size gates. */
    private fun fakePng(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }.also {
        it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte(); it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte()
    }

    /** Bytes wearing the JPEG magic. */
    private fun fakeJpg(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun element(data: String) = ImageElement(
        x = 10f, y = 20f, width = 300f, height = 400f, data = data
    )

    private fun dayOf(
        elements: List<ImageElement> = emptyList(),
        items: List<LedgerItem> = emptyList()
    ) = CalendarDay(
        2026, 7, 31, java.util.Locale.US, mutableListOf(), mutableListOf(), true, 6,
        imageElements = elements.toMutableList(),
        ledgerItems = items.toMutableList()
    )

    // The wire threshold is on DECODED bytes: strictly over 64 KiB goes by reference.
    private val overSize = LedgerMedia.INLINE_MAX_BYTES + 1
    private val underSize = LedgerMedia.INLINE_MAX_BYTES

    // ── imageElements[].data ──────────────────────────────────────────────────────────────────

    @Test
    fun over_threshold_png_externalizes_with_lineage_intact() {
        val mediaDir = temp.newFolder()
        val bytes = fakePng(overSize)
        val base64 = b64(bytes)
        val el = element(base64)
        val idBefore = el.elementId
        val tsBefore = el.timestamp

        LedgerMedia.externalizeDay(dayOf(listOf(el)), mediaDir)

        val expectedName = LedgerMedia.sha256Hex(bytes) + ".png"
        val blob = File(mediaDir, expectedName)
        assertTrue("blob exists under its own checksum", blob.isFile)
        assertArrayEquals("blob holds the decoded bytes", bytes, blob.readBytes())
        assertEquals(expectedName, el.dataRef)
        assertEquals("data stays present as an EMPTY key, never removed", "", el.data)
        // The lineage rule: gramId is md5 of the BASE64 STRING — exactly the contentKey /
        // "where used" derivation — backfilled before the base64 left the file.
        assertEquals(CryptoUtils.md5Hash(base64.toByteArray()), el.gramId)
        // The pixels are identical, so identity and merge-standing must not change.
        assertEquals(idBefore, el.elementId)
        assertEquals(tsBefore, el.timestamp)
    }

    @Test
    fun existing_gram_id_is_never_overwritten() {
        val mediaDir = temp.newFolder()
        val el = element(b64(fakePng(overSize))).apply { gramId = "pre-edit-lineage" }
        LedgerMedia.externalizeDay(dayOf(listOf(el)), mediaDir)
        assertEquals("", el.data)
        assertEquals("a seeded lineage survives migration", "pre-edit-lineage", el.gramId)
    }

    @Test
    fun under_threshold_stays_inline() {
        val mediaDir = temp.newFolder()
        val base64 = b64(fakePng(underSize))
        val el = element(base64)
        LedgerMedia.externalizeDay(dayOf(listOf(el)), mediaDir)
        assertEquals("small stickers cost nothing inline", base64, el.data)
        assertEquals("", el.dataRef)
        assertNull(el.gramId)
        assertEquals("no file written", 0, mediaDir.listFiles()!!.size)
    }

    @Test
    fun jpeg_bytes_get_the_jpg_extension() {
        val mediaDir = temp.newFolder()
        val bytes = fakeJpg(overSize)
        val el = element(b64(bytes))
        LedgerMedia.externalizeDay(dayOf(listOf(el)), mediaDir)
        assertEquals(LedgerMedia.sha256Hex(bytes) + ".jpg", el.dataRef)
        assertTrue(File(mediaDir, el.dataRef).isFile)
    }

    @Test
    fun unnameable_bytes_stay_inline() {
        // Over-threshold but no known magic: never externalize bytes we can't name honestly —
        // the ref's extension is a promise about what the file is.
        val mediaDir = temp.newFolder()
        val base64 = b64(ByteArray(overSize) { 0x42 })
        val el = element(base64)
        LedgerMedia.externalizeDay(dayOf(listOf(el)), mediaDir)
        assertEquals(base64, el.data)
        assertEquals("", el.dataRef)
        assertEquals(0, mediaDir.listFiles()!!.size)
    }

    @Test
    fun externalization_is_idempotent() {
        val mediaDir = temp.newFolder()
        val el = element(b64(fakePng(overSize)))
        val day = dayOf(listOf(el))

        LedgerMedia.externalizeDay(day, mediaDir)
        val filesAfterFirst = mediaDir.listFiles()!!.map { it.name }.sorted()
        val snapshot = Triple(el.dataRef, el.gramId, el.timestamp)
        val mtime = File(mediaDir, el.dataRef).lastModified()

        LedgerMedia.externalizeDay(day, mediaDir)

        assertEquals("no new files on re-save", filesAfterFirst, mediaDir.listFiles()!!.map { it.name }.sorted())
        assertEquals("no field churn on re-save", snapshot, Triple(el.dataRef, el.gramId, el.timestamp))
        assertEquals("", el.data)
        assertEquals("the blob was not rewritten", mtime, File(mediaDir, el.dataRef).lastModified())
    }

    @Test
    fun same_content_twice_is_one_file() {
        // Content-addressing is the point: the same gram placed twice is ONE blob.
        val mediaDir = temp.newFolder()
        val base64 = b64(fakePng(overSize))
        val a = element(base64)
        val b = element(base64)
        LedgerMedia.externalizeDay(dayOf(listOf(a, b)), mediaDir)
        assertEquals(a.dataRef, b.dataRef)
        assertEquals(1, mediaDir.listFiles()!!.size)
    }

    @Test
    fun failed_blob_write_keeps_the_payload_inline() {
        // The media "dir" is a plain file, so every write into it fails — the full-disk stand-in.
        // Losing pixels to a full disk is not an option: no ref may exist without its bytes.
        val notADir = temp.newFile()
        val base64 = b64(fakePng(overSize))
        val el = element(base64)
        LedgerMedia.externalizeDay(dayOf(listOf(el)), notADir)
        assertEquals("payload survives", base64, el.data)
        assertEquals("no ref without bytes on disk", "", el.dataRef)
    }

    // ── ledgerItems[].crop ────────────────────────────────────────────────────────────────────

    private fun item(crop: String?) = LedgerItem(
        id = "item-1", kind = LedgerItem.Kind.TASK, text = "call the shala",
        date = Date(1_720_900_000_000), crop = crop, done = true, stage = "doing"
    )

    @Test
    fun over_threshold_base64_crop_externalizes() {
        val mediaDir = temp.newFolder()
        val bytes = fakePng(overSize)
        val day = dayOf(items = listOf(item(b64(bytes))))

        LedgerMedia.externalizeDay(day, mediaDir)

        val migrated = day.ledgerItems.single()
        assertEquals(LedgerMedia.sha256Hex(bytes) + ".png", migrated.cropRef)
        assertNull("the payload leaves the file entirely", migrated.crop)
        assertTrue(File(mediaDir, migrated.cropRef!!).isFile)
        // The copy carries the rest of the item untouched.
        assertEquals("item-1", migrated.id)
        assertTrue(migrated.done)
        assertEquals("doing", migrated.stage)
    }

    @Test
    fun filename_crop_is_never_touched() {
        // The dual-typed crop's other meaning: an attachments FILENAME (the OCR legacy).
        // It must never be externalized or altered — the try-decode/magic/size gates all fail.
        val mediaDir = temp.newFolder()
        val day = dayOf(items = listOf(item("crop-2026-07-31-abc.png")))
        LedgerMedia.externalizeDay(day, mediaDir)
        val untouched = day.ledgerItems.single()
        assertEquals("crop-2026-07-31-abc.png", untouched.crop)
        assertNull(untouched.cropRef)
        assertEquals(0, mediaDir.listFiles()!!.size)
    }

    @Test
    fun under_threshold_base64_crop_stays_inline() {
        val mediaDir = temp.newFolder()
        val base64 = b64(fakePng(underSize))
        val day = dayOf(items = listOf(item(base64)))
        LedgerMedia.externalizeDay(day, mediaDir)
        assertEquals(base64, day.ledgerItems.single().crop)
        assertNull(day.ledgerItems.single().cropRef)
    }

    // ── The wire round trip ───────────────────────────────────────────────────────────────────

    @Test
    fun externalized_day_reencodes_with_data_present_as_an_empty_key() {
        // The presence rule both decoders hard-require: `data` REMAINS a key on the wire, as ""
        // beside the non-empty ref — an absent `data` would throw on iOS and refuse the day.
        val mediaDir = temp.newFolder()
        val day = dayOf(listOf(element(b64(fakePng(overSize)))))
        LedgerMedia.externalizeDay(day, mediaDir)

        val json = moshi.adapter(CalendarDay::class.java).toJson(day)
        assertTrue("data survives as an empty key", json.contains("\"data\":\"\""))
        assertTrue("the ref is on the wire", json.contains("\"dataRef\":\"${day.imageElements[0].dataRef}\""))

        // And it decodes back to the externalized shape, not to a dropped field.
        val decoded = moshi.adapter(CalendarDay::class.java).fromJson(json)!!
        assertEquals("", decoded.imageElements[0].data)
        assertEquals(day.imageElements[0].dataRef, decoded.imageElements[0].dataRef)
        assertEquals(day.imageElements[0].gramId, decoded.imageElements[0].gramId)
    }

    @Test
    fun emit_flag_off_would_leave_everything_inline() {
        // The rollback story: EMIT_MEDIA_REFS is the whole Phase W switch. This can't flip the
        // const at runtime, so it guards the shipped value instead — the suite below only means
        // what it says while the writer is actually on.
        assertTrue(LedgerMedia.EMIT_MEDIA_REFS)
    }
}
