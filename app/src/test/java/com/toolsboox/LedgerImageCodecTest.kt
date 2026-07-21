package com.toolsboox

import com.toolsboox.ot.LedgerImageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a photograph from becoming 39 MB of JSON.
 *
 * Only the quality constant and the contract are checked here — `Bitmap` is an Android framework
 * class with no behaviour under plain JUnit, so `looksPhotographic` is exercised on-device rather
 * than pretended at with a mock that would only test the mock.
 */
class LedgerImageCodecTest {

    @Test
    fun `photo quality stays in the range that is invisible on e-ink but small`() {
        // Below ~70 JPEG artefacts become visible in flat greys; above ~90 the saving evaporates.
        assertTrue(LedgerImageCodec.PHOTO_QUALITY in 70..90)
        assertEquals(82, LedgerImageCodec.PHOTO_QUALITY)
    }
}
