package com.toolsboox.plugin.reader

import com.toolsboox.plugin.reader.ot.CbzDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 🗜 The shared CBZ reader, pinned (Michael 2026-10-11, comics slice 2).
 *
 * - The `cbz1` key is pinned BYTE FOR BYTE by one vector (computed independently with Python's
 *   hashlib) — the iOS twin must produce the same string for the same members, the way
 *   ReadAlongPairingTest pins `fileKey`.
 * - A CBZ written the Mihon/Suwayomi way — a STORED member with a data descriptor (flag bit 3,
 *   zero local sizes), on which `ZipInputStream` throws "only DEFLATED entries can have EXT
 *   descriptor" — reads through the central directory.
 * - Names decode as zip.js decodes them: UTF-8 flag, else a valid Unicode Path field, else CP437.
 */
class CbzDirectoryTest {

    private fun member(name: String, crc: Long, size: Long) =
        CbzDirectory.Member(name, name.toByteArray(Charsets.UTF_8), 0, crc, size, size, 0)

    @Test
    fun cbz1KeyVector() {
        val members = listOf(
            member("002.jpg", 0x1234abcdL, 1000),
            member("001.JPG", 0x0000ff00L, 20),
            member("ComicInfo.xml", 0xdeadbeefL, 5),      // metadata never moves the key
            member("sub/", 0, 0),                          // nor does a folder
            member("sub/010.png", 0xffffffffL, 4294967295L),
        )
        // sha1("001.JPG\0" "0000ff00\0" "20\n" "002.jpg\0" "1234abcd\0" "1000\n"
        //      "sub/010.png\0" "ffffffff\0" "4294967295\n")
        assertEquals("cbz1:a8c07434547c96fc4113dfb62b229d13735c6467", CbzDirectory.cbz1Key(members))
        // Independent of the stored order, and of metadata edits:
        assertEquals(CbzDirectory.cbz1Key(members), CbzDirectory.cbz1Key(members.reversed().filter { it.name != "ComicInfo.xml" }))
        // Any page edit moves it.
        val edited = members.map { if (it.name == "002.jpg") member("002.jpg", 0x1234abceL, 1000) else it }
        assertTrue(CbzDirectory.cbz1Key(edited) != CbzDirectory.cbz1Key(members))
        // No pages, no key.
        assertNull(CbzDirectory.cbz1Key(listOf(member("ComicInfo.xml", 1, 1))))
    }

    private fun raw(bytes: ByteArray, crc: Long, size: Long, name: String? = null) =
        CbzDirectory.Member(name ?: CbzDirectory.decodeName(bytes, 0, ByteArray(0)), bytes, 0, crc, size, size, 0)

    /** THE iPAD'S VECTOR (BalloonGeometryTests.testCbz1KeySharedVector), byte for byte — the two
     *  apps must name one chapter's balloon cache the same, so each suite pins the other's too. */
    @Test
    fun cbz1KeyMatchesTheIpadVector() {
        val members = listOf(
            raw("page10.jpg".toByteArray(), 0x1a2b3c4dL, 12345),
            raw("Page2.JPG".toByteArray(), 0x0000beefL, 7),
            raw("ComicInfo.xml".toByteArray(), 0xffffffffL, 100),
            raw("art/".toByteArray(), 0, 0),
            raw("page1.png".toByteArray(), 0xdeadbeefL, 999999),
            raw(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x2E, 0x6A, 0x70, 0x67), 0x01020304L, 42),  // "caf\xE9.jpg", cp437
        )
        val key = "cbz1:7e875463d95370303b94d001da978856146fb23e"
        assertEquals(key, CbzDirectory.cbz1Key(members))
        assertEquals(key, CbzDirectory.cbz1Key(members.reversed()))
    }

    @Test
    fun cbz1KeyReadsRawBytesAndBreaksTies() {
        // A Unicode Path field may RENAME a member (here to a non-page) — the key still reads the
        // raw bytes, as the iPad does, so the page stays in the hash.
        val renamed = listOf(raw("001.jpg".toByteArray(), 7, 70, name = "001.txt"))
        assertEquals(CbzDirectory.cbz1Key(listOf(raw("001.jpg".toByteArray(), 7, 70))), CbzDirectory.cbz1Key(renamed))
        // …and the reverse: a raw non-page renamed to look like a page is not a page.
        assertNull(CbzDirectory.cbz1Key(listOf(raw("001.bin".toByteArray(), 7, 70, name = "001.jpg"))))
        // Two members with one raw name: ordered by CRC, then size — never by stored order.
        val a = raw("dup.jpg".toByteArray(), 2, 10)
        val b = raw("dup.jpg".toByteArray(), 1, 10)
        val c = raw("dup.jpg".toByteArray(), 1, 5)
        assertEquals(CbzDirectory.cbz1Key(listOf(a, b, c)), CbzDirectory.cbz1Key(listOf(c, a, b)))
        assertEquals(CbzDirectory.cbz1Key(listOf(a, b, c)), CbzDirectory.cbz1Key(listOf(b, c, a)))
    }

    /** THE iPAD'S REPEATED-NAME VECTOR (BalloonGeometryTests.testCbz1KeyKotlinVectorAndRepeatedNames,
     *  review 2026-10-11), byte for byte: a hand-repacked CBZ with REPEATED raw names (audit 09-11)
     *  sorts same name → CRC (unsigned: 0x7fffffff before 0x80000000) → size, so the archive's
     *  stored order never reaches the hash. Recomputed with Python's hashlib. */
    @Test
    fun cbz1KeyMatchesTheIpadRepeatedNameVector() {
        val repeated = listOf(
            raw("001.jpg".toByteArray(), 2, 10),
            raw("001.jpg".toByteArray(), 1, 20),
            raw("001.jpg".toByteArray(), 1, 5),
            raw("002.png".toByteArray(), 0x80000000L, 1),
            raw("002.png".toByteArray(), 0x7fffffffL, 1),
        )
        val key = "cbz1:95961f02fbc953194b335b4ffb48490c2ef3d7a6"
        assertEquals(key, CbzDirectory.cbz1Key(repeated))
        assertEquals(key, CbzDirectory.cbz1Key(repeated.reversed()))
        assertEquals(key, CbzDirectory.cbz1Key(listOf(repeated[2], repeated[4], repeated[0], repeated[3], repeated[1])))
    }

    @Test
    fun aDirectoryClaimingMoreThanTheFileIsDeclined() {
        // A corrupt EOCD whose directory size runs past the end of the file: decline it, never
        // allocate what it claims.
        val zip = mihonStyleZip("0001.jpg", ByteArray(10))
        val eocd = zip.size - 22
        ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd + 12, 0x7fffffff)
        val f = File.createTempFile("corrupt", ".cbz").apply { deleteOnExit(); writeBytes(zip) }
        RandomAccessFile(f, "r").use { raf -> assertEquals(emptyList<CbzDirectory.Member>(), CbzDirectory.read(raf.channel)) }
    }

    /** A one-member zip, STORED, with a data descriptor — the shape Mihon's downloader writes. */
    private fun mihonStyleZip(name: String, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(data) }.value.toInt()
        val n = name.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        fun le(size: Int, block: ByteBuffer.() -> Unit) =
            out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array())
        // local header: sizes and CRC zero, flag bit 3 → they follow the data
        le(30) { putInt(0x04034b50); putShort(20); putShort(0x0008); putShort(0); putShort(0); putShort(0)
            putInt(0); putInt(0); putInt(0); putShort(n.size.toShort()); putShort(0) }
        out.write(n); out.write(data)
        le(16) { putInt(0x08074b50); putInt(crc); putInt(data.size); putInt(data.size) }
        val cdOffset = out.size()
        le(46) { putInt(0x02014b50); putShort(20); putShort(20); putShort(0x0008); putShort(0)
            putShort(0); putShort(0); putInt(crc); putInt(data.size); putInt(data.size)
            putShort(n.size.toShort()); putShort(0); putShort(0); putShort(0); putShort(0); putInt(0); putInt(0) }
        out.write(n)
        val cdSize = out.size() - cdOffset
        le(22) { putInt(0x06054b50); putShort(0); putShort(0); putShort(1); putShort(1)
            putInt(cdSize); putInt(cdOffset); putShort(0) }
        return out.toByteArray()
    }

    @Test
    fun readsStoredMembersWithADataDescriptor() {
        val page = ByteArray(300) { (it * 7).toByte() }
        val f = File.createTempFile("mihon", ".cbz").apply { deleteOnExit(); writeBytes(mihonStyleZip("0001.jpg", page)) }
        RandomAccessFile(f, "r").use { raf ->
            val ch = raf.channel
            val members = CbzDirectory.read(ch)
            assertEquals(listOf("0001.jpg"), members.map { it.name })
            assertEquals(300L, members[0].usize)
            assertEquals(CRC32().apply { update(page) }.value, members[0].crc)
            assertArrayEquals(page, CbzDirectory.bytes(ch, members[0]))
            assertEquals(listOf("0001.jpg"), CbzDirectory.pages(members).map { it.name })
        }
    }

    @Test
    fun readsDeflatedMembersInNaturalOrder() {
        val f = File.createTempFile("plain", ".cbz").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            for (name in listOf("p10.jpg", "ComicInfo.xml", "p2.JPG", "p1.jpg")) {
                z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(name.toByteArray()); z.closeEntry()
            }
        }
        RandomAccessFile(f, "r").use { raf ->
            val members = CbzDirectory.read(raf.channel)
            val pages = CbzDirectory.pages(members)
            assertEquals(listOf("p1.jpg", "p2.JPG", "p10.jpg"), pages.map { it.name })
            assertArrayEquals("p10.jpg".toByteArray(), CbzDirectory.bytes(raf.channel, pages[2]))
        }
    }

    @Test
    fun namesDecodeTheZipJsWay() {
        // No flag → CP437 (0x80 is Ç; 0x01 is ☺; 0xFF is a plain space in zip.js's table).
        assertEquals("Ça☺ ", CbzDirectory.decodeName(byteArrayOf(0x80.toByte(), 'a'.code.toByte(), 1, 0xff.toByte()), 0, ByteArray(0)))
        // Flag bit 11 → UTF-8.
        assertEquals("é.jpg", CbzDirectory.decodeName("é.jpg".toByteArray(Charsets.UTF_8), 0x0800, ByteArray(0)))
        // A Unicode Path field whose CRC matches the raw name wins over CP437…
        val raw = byteArrayOf(0x80.toByte(), '.'.code.toByte(), 'j'.code.toByte(), 'p'.code.toByte(), 'g'.code.toByte())
        val uni = "é.jpg".toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(raw) }.value.toInt()
        val extra = ByteBuffer.allocate(4 + 5 + uni.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x7075).putShort((5 + uni.size).toShort()).put(1).putInt(crc).put(uni).array()
        assertEquals("é.jpg", CbzDirectory.decodeName(raw, 0, extra))
        // …is ignored when its CRC doesn't match (zip.js: `valid` false)…
        val stale = extra.copyOf().also { it[5] = (it[5] + 1).toByte() }
        assertEquals("Ç.jpg", CbzDirectory.decodeName(raw, 0, stale))
        // …and never overrides a name flagged UTF-8.
        assertEquals("\uFFFD.jpg", CbzDirectory.decodeName(raw, 0x0800, extra))
    }
}
