package com.toolsboox

import com.toolsboox.ot.LedgerMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The media store's two pure contracts (WIRE-MEDIA-BY-REFERENCE.md): the ref SHAPE — 64
 * lowercase hex chars of the SHA-256 of the decoded bytes, dot, `png`/`jpg` — and the
 * resolution ORDER — inline `data` first, then the ref, then nothing, with every failure a
 * null and never a throw. Exercised through [LedgerMedia]'s Context-free core so this runs
 * as a plain JVM test, the same way the sync round-trip suite does.
 */
class LedgerMediaTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val goodStem = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun fileForIn(dir: File): (String) -> File? = { ref ->
        if (LedgerMedia.isValidRef(ref)) File(dir, ref) else null
    }

    // ── Ref shape ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun ref_shape_accepts_exactly_the_wire_format() {
        assertTrue(LedgerMedia.isValidRef("$goodStem.png"))
        assertTrue(LedgerMedia.isValidRef("$goodStem.jpg"))
    }

    @Test
    fun ref_shape_rejects_everything_else() {
        assertFalse("empty", LedgerMedia.isValidRef(""))
        assertFalse("no extension", LedgerMedia.isValidRef(goodStem))
        assertFalse("wrong extension", LedgerMedia.isValidRef("$goodStem.jpeg"))
        assertFalse("uppercase hex", LedgerMedia.isValidRef("${goodStem.uppercase()}.png"))
        assertFalse("short stem", LedgerMedia.isValidRef("${goodStem.dropLast(1)}.png"))
        assertFalse("long stem", LedgerMedia.isValidRef("${goodStem}f.png"))
        assertFalse("non-hex stem", LedgerMedia.isValidRef("z" + goodStem.drop(1) + ".png"))
        // A ref is wire data and must never become a path with directory parts in it.
        assertFalse("path traversal", LedgerMedia.isValidRef("../$goodStem.png"))
        assertFalse("nested path", LedgerMedia.isValidRef("a/$goodStem.png"))
        assertFalse("plain filename", LedgerMedia.isValidRef("card.png"))
    }

    // ── The checksum the filename carries ─────────────────────────────────────────────────────

    @Test
    fun sha256_hex_matches_known_vectors() {
        // The name's stem is sha256 of the DECODED bytes; these are the standard vectors.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            LedgerMedia.sha256Hex(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LedgerMedia.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    // ── Resolution order ──────────────────────────────────────────────────────────────────────

    @Test
    fun inline_data_beats_the_ref() {
        val dir = temp.newFolder()
        val ref = "$goodStem.png"
        File(dir, ref).writeBytes("file bytes".toByteArray())
        val inline = java.util.Base64.getEncoder().encodeToString("inline bytes".toByteArray())
        assertArrayEquals(
            "a build that wrote inline meant it",
            "inline bytes".toByteArray(),
            LedgerMedia.resolveBytes(inline, ref, fileForIn(dir))
        )
    }

    @Test
    fun empty_data_falls_to_the_ref() {
        val dir = temp.newFolder()
        val ref = "$goodStem.png"
        File(dir, ref).writeBytes("file bytes".toByteArray())
        assertArrayEquals(
            "file bytes".toByteArray(),
            LedgerMedia.resolveBytes("", ref, fileForIn(dir))
        )
    }

    @Test
    fun bad_ref_resolves_to_null_not_a_throw() {
        val dir = temp.newFolder()
        assertNull(LedgerMedia.resolveBytes("", "card.png", fileForIn(dir)))
        assertNull(LedgerMedia.resolveBytes("", "../$goodStem.png", fileForIn(dir)))
    }

    @Test
    fun missing_file_resolves_to_null_not_a_throw() {
        // The fetch-on-demand case: a valid ref whose blob hasn't synced yet is a placeholder,
        // never a dropped element or an exception.
        val dir = temp.newFolder()
        assertNull(LedgerMedia.resolveBytes("", "$goodStem.png", fileForIn(dir)))
    }

    @Test
    fun no_data_and_no_ref_is_no_face() {
        val dir = temp.newFolder()
        assertNull(LedgerMedia.resolveBytes("", "", fileForIn(dir)))
    }

    @Test
    fun undecodable_inline_data_is_null_not_a_fall_through() {
        // A broken inline payload is a broken face — it does NOT fall through to the ref,
        // because writers never emit both and a ref beside non-empty data means nothing.
        val dir = temp.newFolder()
        val ref = "$goodStem.png"
        File(dir, ref).writeBytes("file bytes".toByteArray())
        assertNull(LedgerMedia.resolveBytes("!!!!", ref, fileForIn(dir)))
    }
}
