package com.toolsboox.plugin.reader.ui

import android.content.Context

/**
 * HOW TIGHT THE SHELF PACKS — the Bookshelf grid's one spacing dial.
 *
 * Michael's punchlist, 2026-08-11: "Bookshelf needs clear spacing adjustments available in
 * wrench." The grid was one hard-coded shape — four columns, 150dp covers — which is a fine
 * shape and the wrong number of them: a shelf of forty books wants to be scanned dense, and a
 * shelf read at arm's length on an e-ink slab wants covers you can actually recognise. The
 * difference between those is not a preference about pixels, it is a preference about the
 * PERSON's distance from the screen, so it has to be theirs to set.
 *
 * A LADDER, NOT A DIAL. Three named rungs — compact / cozy / large — rather than sliders for
 * columns, cover height and padding separately, because those three numbers are one decision
 * ("how big is a book on my shelf") and offering them separately manufactures two decisions
 * that don't exist (eliminate decisions with no loss: nobody wants five columns of 210dp
 * covers). Each rung carries every number the grid needs, tuned together; COZY is exactly the
 * numbers the shelf has always drawn, so an untouched setting changes nothing.
 *
 * Persisted in the reader's own prefs file ("ledger_reader_prefs" — the shelf and the reader
 * are one plugin and already share it), read fresh at each [BookshelfFragment.load]. Changing
 * the rung triggers exactly ONE full relayout — the shelf's normal draw-once discipline; there
 * is no live preview to animate, because on e-ink an animated preview is a strobe.
 *
 * Reachable from BOTH doors, deliberately (the OPDS lesson, verbatim from the ＋ comment: a
 * setting a person wants is a setting they should not have to know the one true door to):
 *  • the Bookshelf's own rail — "Shelf: Cozy", where you are when the shelf looks wrong;
 *  • the reader's wrench (🔧), under its own "Bookshelf" section beside Books/Reading —
 *    Michael's "probably unto 'reading' in the wrench in the book reader".
 */
enum class ShelfDensity(
    val key: String,
    val label: String,
    /** Covers per grid row. */
    val columns: Int,
    /** Cover art height, dp. */
    val coverDp: Int,
    /** Tile padding, dp. */
    val padDp: Int,
    /** Title line under the cover, sp. */
    val titleSp: Float
) {
    /** Dense scan — five to a row, for finding a book you already know by its spine. */
    COMPACT("compact", "Compact", 5, 112, 4, 11f),

    /** The shelf as it has always drawn — these are the original hard-coded numbers. */
    COZY("cozy", "Cozy", 4, 150, 6, 12f),

    /** Big faces — three to a row, covers legible at arm's length. */
    LARGE("large", "Large", 3, 210, 10, 14f);

    companion object {
        private const val PREFS = "ledger_reader_prefs"
        private const val KEY = "shelf_density"

        /** The rung as set, COZY (the historical shape) until someone chooses otherwise. */
        fun current(context: Context): ShelfDensity {
            val k = context.getSharedPreferences(PREFS, 0).getString(KEY, COZY.key)
            return entries.firstOrNull { it.key == k } ?: COZY
        }

        fun set(context: Context, density: ShelfDensity) {
            context.getSharedPreferences(PREFS, 0).edit().putString(KEY, density.key).apply()
        }
    }
}
