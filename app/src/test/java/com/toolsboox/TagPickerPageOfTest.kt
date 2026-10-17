package com.toolsboox

import com.toolsboox.ot.TagPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * "Tag this page…" records a PAGE occurrence (Michael 2026-10-11: "tags aren't complete") — so the
 * picker has to tell a whole page from an element, including Android's sub-page keys, whose '#'
 * LedgerUri.parse splits off as a fragment.
 */
class TagPickerPageOfTest {

    private val d = LocalDate.parse("2026-10-11")

    @Test
    fun `a whole page is a page`() {
        assertEquals(d to "0", TagPicker.pageOf("ledger://2026-10-11/0"))
        assertEquals(d to "write-1753", TagPicker.pageOf("ledger://2026-10-11/write-1753"))
        assertEquals(d to "default", TagPicker.pageOf("ledger://2026-10-11/default"))
    }

    @Test
    fun `a sub-page keeps its whole key`() {
        assertEquals(d to "write-1753#2", TagPicker.pageOf("ledger://2026-10-11/write-1753#2"))
    }

    @Test
    fun `an element is not a page`() {
        assertNull(TagPicker.pageOf("ledger://2026-10-11/0#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"))
        assertNull(TagPicker.pageOf("ledger://2026-10-11/write-1753#2#2b1f0c9e-7d0a-4f43-9a3b-0d6f4b7c1e21"))
    }

    @Test
    fun `other addresses are objects`() {
        assertNull(TagPicker.pageOf("mail://abc"))
        assertNull(TagPicker.pageOf("web://https://example.com"))
        assertNull(TagPicker.pageOf("journal://42"))
        assertNull(TagPicker.pageOf("ledger://not-a-date/0"))
    }
}
