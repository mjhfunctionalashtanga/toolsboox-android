package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Stroke
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import java.util.UUID

/**
 * COPYING PAGES INTO A NEW NOTE — the export door's "➕ Add to a new note" engine
 * (DESIGN-notes-ia-2026-08-22: "export and transplant are the same door — the page-ops machinery").
 *
 * NON-DESTRUCTIVE BY CONSTRUCTION. The selected pages' content is COPIED under the new note's own
 * keys ("write-<millis>", "write-<millis>#1", …); the source keys, their strokes and their elements
 * are not touched, moved or rekeyed — rekeying existing noteStrokes entries is the forbidden move
 * (it breaks the byte-identical Android/iOS convergence fixtures), and a NEW key in the NEW note's
 * namespace is additive, which is allowed.
 *
 * EVERY COPY GETS A FRESH IDENTITY, and this is load-bearing, not tidiness:
 *
 *  • Strokes are reminted the way [com.toolsboox.ot.StrokeClipboard.stampAt] reminted a paste —
 *    "geometry moves, identity re-mints, and every style field (color, strokeWidth, inkStyle)
 *    must ride along unchanged." Here the geometry doesn't even move; only identity is new.
 *    Keeping the source strokeId would put ONE id on TWO pages of the same day file, and both
 *    the merge ([com.toolsboox.plugin.calendar.fi.CalendarDayMerger] unions strokes by strokeId,
 *    tombstones subtract by strokeId ACROSS pages) and a later erase of the original would then
 *    silently take the copy with it.
 *  • Elements are reminted for the same reason with sharper teeth: the day's image/text elements
 *    are FLAT lists unioned by elementId, so a shared id isn't even two copies — it is one
 *    element that the union would collapse, losing whichever page's copy merged second.
 *
 * What the copy KEEPS: gramId (lineage — "where used" should group the copy with its original)
 * and attachmentId (a duplicated A/V gram shares one attachment; [LedgerDocumentPages.erase]
 * already counts references before dropping one).
 *
 * A blank source page still registers its destination key (empty stroke list): merely visiting a
 * page writes its key, and the new note's ‹ N › pager counts pages off exactly that — a three-page
 * selection must arrive as a three-page note even when its middle page was never inked.
 */
object NotePageCopy {

    /**
     * Copy [selection]'s pages (in order) into [day] under [destBase]'s page keys — page 1 → the
     * base key, page n → "#(n-1)" — and return the destination keys, index-aligned with the
     * selection. Mutates [day] only by ADDING; the caller owns persisting it.
     */
    fun copyInto(day: CalendarDay, selection: List<String>, destBase: String): List<String> {
        val now = System.currentTimeMillis()
        val destKeys = selection.indices.map { LedgerDocuments.subPageKey(destBase, it) }
        selection.forEachIndexed { i, src ->
            val dest = destKeys[i]
            day.noteStrokes[dest] = (day.noteStrokes[src] ?: emptyList()).map { s ->
                Stroke(
                    UUID.randomUUID(), now, s.strokePoints.map { it.copy() },
                    s.color, s.strokeWidth, s.inkStyle
                )
            }
            day.imageElements.addAll(
                day.imageElements.filter { it.page == src }
                    .map { it.copy(elementId = UUID.randomUUID(), timestamp = now, page = dest) }
            )
            day.textElements.addAll(
                day.textElements.filter { it.pageKey == src }
                    .map { it.copy(elementId = UUID.randomUUID(), timestamp = now, pageKey = dest) }
            )
        }
        return destKeys
    }
}
