package com.toolsboox.plugin.reader

import com.toolsboox.plugin.reader.ot.ComicSettingsCarry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a comic's settings come from under a new fleet key (Michael 2026-10-11, slice 2 review). */
class ComicSettingsCarryTest {

    private val names = listOf("panels", "balloons", "rtl", "flow", "spread", "zoom", "panel_at")

    @Test
    fun theSameFileInAnotherFolderWins() {
        // The Palma's comics folder re-pointed: same name, same size, new folder.
        val prefs = setOf("comic_rtl_Old Root/Manga/Ch 1.cbz#1234", "comic_rtl_Ch 1", "comic_zoom_Ch 1")
        assertEquals("Old Root/Manga/Ch 1.cbz#1234",
            ComicSettingsCarry.source("New Root/Manga/Ch 1.cbz#1234", "Ch 1", prefs, names))
        // A book at the shelf root has no folder in its key.
        assertEquals("Ch 1.cbz#1234", ComicSettingsCarry.source("Manga/Ch 1.cbz#1234", "Ch 1",
            setOf("comic_panel_at_Ch 1.cbz#1234"), names))
    }

    @Test
    fun otherwiseTheFileNameKey() {
        // Another series' "Ch 1.cbz" (a different size) is never the source…
        val prefs = setOf("comic_rtl_Manga/Ch 1.cbz#1234", "comic_rtl_Ch 1")
        assertEquals("Ch 1", ComicSettingsCarry.source("Western/Ch 1.cbz#9999", "Ch 1", prefs, names))
        // …nor is a name that merely ends the same ("XCh 1.cbz#1234" is not "Ch 1.cbz#1234").
        assertEquals("Ch 1", ComicSettingsCarry.source("B/Ch 1.cbz#1234", "Ch 1",
            setOf("comic_rtl_A/XCh 1.cbz#1234"), names))
        // The key itself is not its own source, and a key without a size never looks for one.
        assertEquals("Ch 1", ComicSettingsCarry.source("A/Ch 1.cbz#1234", "Ch 1",
            setOf("comic_rtl_A/Ch 1.cbz#1234"), names))
        assertEquals("Ch 1", ComicSettingsCarry.source("Ch 1", "Ch 1", setOf("comic_rtl_x/Ch 1"), names))
    }
}
