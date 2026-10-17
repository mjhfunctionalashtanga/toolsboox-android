package com.toolsboox.plugin.calendar.ot

/**
 * THE DAY'S DATED NOTE, AS AN ORDER OVER KEYS THAT ALREADY EXIST.
 *
 * Michael's ruling (DESIGN-notes-ia-2026-08-22): "the basic note for each day is a dated note,
 * with whatever templates a person wants to use per page." The day file already IS that note —
 * every page kind lives in one [com.toolsboox.plugin.calendar.da.v2.CalendarDay.noteStrokes] map —
 * so the unification is PRESENTATION ONLY: the ‹ N › pager walks the existing keys in one stable
 * order. Nothing here mints, moves or renames a key; rekeying the stroke map would invalidate the
 * byte-identical Android/iOS convergence fixtures, and walking achieves the same experience.
 *
 * The order, pinned by [DatedNoteOrderTest]:
 *   1. numeric pages ("0", "1", …), ascending;
 *   2. the standing daily [DOODLE] — every day's freeform page (DESIGN-homepages-2026-08-22:
 *      "the daily doodle is right there waiting"), seated right after the numeric run;
 *   3. the day's template families in [LedgerDocuments.TEMPLATES] order (Text has no day page,
 *      so it never appears), each base followed by its sub-pages ascending.
 *
 * MEMBERSHIP IS THE DAY'S OWN PAGES ONLY: the daily bases exactly ("write", not
 * "write-<millis>") — a named document is a NAMED note, not a page of the dated one — and none
 * of the ritual stations (intake, gratitude, selfexec, grampicks, synthesize), which keep their
 * own doors.
 */
object DatedNote {

    /**
     * The standing daily Doodle's page key. MINTED LAZILY, the way every note page is: the key
     * holds no ink until the first stroke saves under it, so "every day mints its doodle" costs
     * nothing on disk — the day page's notes band simply always OFFERS it, and opening a page
     * that has no strokes yet is already how new pages begin.
     */
    const val DOODLE = "doodle"

    /** A minted document key's epoch-millis tail is this long or longer; a sub-page's is not. */
    private const val MILLIS_DIGITS = 10

    /**
     * A key split into its daily base and sub-page number, or null when it isn't a dated-note key.
     *
     * The two forks SPELL sub-pages differently on the wire — Kotlin writes `write#1`, Swift's
     * `LedgerDocumentStore.pageKey` writes `write-1` — and a SYNCED day holds whichever spelling
     * authored the ink, so membership must read both or the iPad's pages vanish from the Boox
     * pager. Swift's `DatedNote.split` is the model this ports.
     *
     * A dash tail of [MILLIS_DIGITS] digits or more is a MINTED document key
     * (`write-1785354515034`, the store's epoch-millis convention): a named document is a NAMED
     * note, never a page of the dated one.
     */
    fun split(key: String): Pair<String, Int>? {
        // TWO BOOKS PER DAY (Michael, 2026-08-27, the disambiguation ruling): the dated note's
        // pages are its NUMERIC run and the standing daily Doodle — nothing else. The family
        // daily pads (write/grid/sketch/pickings and their sub-pages) used to ride this walk
        // (the 08-22 fold-in), and that is exactly the confusion he named: a second book's
        // pages reading as pages of the dated note. They are their own documents now — their
        // own pager (the sub-page branch already carries them), their own ▤, export, tags —
        // reachable through the Notebook, the per-family shelves and the stations. Their ink
        // renders exactly as before; only WALK MEMBERSHIP narrows.
        val hash = key.indexOf('#')
        if (hash >= 0) {
            val base = key.substring(0, hash)
            if (base.toIntOrNull() == null) return null   // the Doodle has no sub-pages either
            return base to (key.substring(hash + 1).toIntOrNull() ?: 0)
        }
        if (key.toIntOrNull() != null || key == DOODLE) return key to 0
        val dash = key.lastIndexOf('-')
        if (dash >= 0) {
            val base = key.substring(0, dash)
            val tail = key.substring(dash + 1)
            if (base.toIntOrNull() == null) return null
            if (tail.isEmpty() || tail.length >= MILLIS_DIGITS || !tail.all { it.isDigit() }) return null
            return base to (tail.toIntOrNull() ?: return null)
        }
        return null
    }

    /** Whether [key] is a page of the day's dated note: numeric, the daily [DOODLE], or a daily
     *  family base with an optional sub-page tail (EITHER fork's spelling). */
    fun isDatedPage(key: String): Boolean = split(key) != null

    /**
     * The dated note's page walk: the member keys among [keys] plus [current] (the page you are
     * standing on always lists, inked or not), in the stable order above.
     */
    fun order(keys: Collection<String>, current: String?): List<String> {
        val members = (keys.asSequence() + listOfNotNull(current).asSequence())
            .distinct()
            .mapNotNull { key -> split(key)?.let { Triple(key, it.first, it.second) } }
            .toList()

        // Numeric run first, the daily Doodle after it — the whole dated book (the 08-27 ruling).
        fun rank(base: String): Int = if (base.toIntOrNull() != null) 0 else 1
        fun inner(base: String): Int = base.toIntOrNull() ?: 0

        return members
            .sortedWith(compareBy({ rank(it.second) }, { inner(it.second) }, { it.third }))
            .map { it.first }
    }
}
