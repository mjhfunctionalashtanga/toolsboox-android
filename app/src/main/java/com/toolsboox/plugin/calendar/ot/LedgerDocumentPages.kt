package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * Where one document's pages ACTUALLY ARE, as against where the index thinks they are.
 *
 * [byDate] is the page keys ("write-1753900000000", "write-1753900000000#1", …) grouped by the day
 * file each one was found in. Grouped rather than flattened because the destructive path has to open
 * one day file per group, under that day's lock, and a flat list would lose the only thing that says
 * how many files are about to be written.
 */
data class DocumentPages(val base: String, val byDate: Map<LocalDate, List<String>>) {
    /** How many pages the confirmation says. Counted off the day files, never off the index. */
    val pageCount: Int = byDate.values.sumOf { it.size }

    /** Newest day first, which is the order the erase walks and the order a message reads best in. */
    val dates: List<LocalDate> = byDate.keys.sortedDescending()

    val isEmpty: Boolean = pageCount == 0
}

/** What an erase actually did: pages removed, day files rewritten, and the days it REFUSED to touch. */
data class PagesErased(val pages: Int, val days: Int, val unreadable: List<LocalDate>)

/**
 * The two halves of deleting a piece of writing that the naming work deliberately left undone —
 * FINDING its pages, and CLEARING them — kept apart from the naming stores because they are a
 * different kind of thing entirely.
 *
 * The agent that built naming declined to add Delete, and its reasoning was right and is worth
 * keeping: "A text note IS its record, so deleting the entry deletes the writing. A Write document's
 * ink lives in the day JSON under its key and the index holds only its title — so 'delete' would
 * either drop the title and strand the pages under a key nothing can reach again, or reach into the
 * day file and destroy work. That is a decision about what deletion MEANS here, not a missing
 * button, and it is Michael's." He has made it: two verbs, never one.
 *
 * This file is the half that reaches into the day file. [LedgerDocuments.untitle] is the other verb
 * and touches nothing here at all.
 *
 * ── WHY A DOCUMENT NEEDS FINDING IN THE FIRST PLACE ───────────────────────────────────────────
 *
 * A named document has a HOME DATE and every listing in the app navigates it there:
 * [LedgerDocuments.forSurface] counts its pages against its own date, the directory opens it at its
 * own date, the ‹ N › pager extends it on the day you are standing on. So the model says a document
 * lives on one day.
 *
 * The app does not enforce that, and two ordinary gestures break it: the ‹ N › menu's "📅 Go to a
 * date…" carries the CURRENT page key to another day, and the centre pill's tap-to-today does the
 * same. Both land you on "write-1753900000000" in a different day file, where you can write. That
 * ink is real, it is on disk, and today nothing lists it — the document's own listing only ever asks
 * its home date.
 *
 * A delete that only cleared the home date would therefore leave those pages behind with their index
 * entry gone: existing, unlisted, unreachable. That is the exact failure the "never strand" rule
 * names, so the scan below goes and finds them.
 *
 * ── HOW THE SCAN STAYS CHEAP ──────────────────────────────────────────────────────────────────
 *
 * A day file is megabytes of inline base64 and a device may hold years of them, so "read every day
 * file" is not an answer — it is the cost that every other query in this codebase streams, samples or
 * indexes its way around. Two things make it bounded:
 *
 *  • A MINTED KEY IS A CLOCK. Documents are minted "write-<epoch millis>" (Android and iOS agree on
 *    this to the character). A day file whose mtime is OLDER than that timestamp has not been
 *    written since the document was created, so it cannot possibly contain the key. One stat per
 *    day file, no reads, and in practice it leaves a handful of candidates — the document's own days.
 *  • THE CANDIDATES ARE STREAMED, NOT DECODED. Each survivor is read in 64 KB chunks looking only
 *    for the quoted key, carrying an overlap so a key straddling a chunk boundary still matches —
 *    the same technique [LedgerDocuments.subPageCountFromFile] uses, and for the same reason.
 *
 * The mtime filter is a heuristic and is named as one. It can only be wrong if a day file's
 * modification time has been rolled BACKWARDS behind the document's creation while still containing
 * it — a restore that preserved timestamps, essentially. Two cheap indexes are unioned in to cover
 * the likely shapes of that (the card index knows which days hold cards on this base; the tag store
 * knows which days hold its #tags), and the home date is always included. A day missed by all four
 * keeps its ink, which is the safe direction to be wrong in: the pages survive, and the document's
 * absence from the index is what a re-scan on the next delete would notice.
 *
 * ── WHAT THE ERASE WILL NOT DO ────────────────────────────────────────────────────────────────
 *
 * It will not write a day file it could not READ. [CalendarDayService.load]'s File overload returns
 * null for a blank, corrupt or too-large-to-read day — the 86 MB day that killed the process on
 * launch is why that defence exists — and the date-based overload answers the same condition with a
 * FRESH EMPTY DAY. Saving that empty day over a file whose real content merely could not be parsed
 * would be the single most destructive thing in this file, so only the null-returning overload is
 * used and a null skips the day, loudly, in [PagesErased.unreadable].
 */
object LedgerDocumentPages {

    /** A minted key's tail is epoch milliseconds — ten digits since 1970, thirteen today. A sub-page
     *  index would have to reach ten billion to be mistaken for one. Same threshold iOS uses in
     *  `LedgerDocumentStore.isDocumentKey`, so both forks tell a document from a page the same way. */
    private const val MINTED_TAIL_MIN = 10

    /**
     * The epoch millis a minted key was created at, or null when [base] is not a minted key (the
     * daily "write"/"synthesize" page, a Pickings board, anything else). Null is the signal to scan
     * the home date only, which for those keys is the whole truth: the daily page's key is shared by
     * every day in history, so "which days hold this key" is not a question with a useful answer.
     */
    private fun mintedAt(base: String): Long? {
        val dash = base.lastIndexOf('-')
        if (dash <= 0) return null
        val tail = base.substring(dash + 1)
        if (tail.length < MINTED_TAIL_MIN || !tail.all { it.isDigit() }) return null
        return tail.toLongOrNull()
    }

    /**
     * Every page of [base] that is actually on disk, grouped by the day it is in.
     *
     * [homeDate] is the document's own date from the index — always scanned, whatever the clock
     * says, because it is the one day the model guarantees.
     */
    fun locate(context: Context, base: String, homeDate: LocalDate): DocumentPages {
        val out = LinkedHashMap<LocalDate, List<String>>()
        for (date in candidates(context, base, homeDate)) {
            val file = LedgerPaths.dayFile(context, date) ?: continue
            val keys = pageKeysIn(file, base)
            if (keys.isNotEmpty()) out[date] = keys
        }
        return DocumentPages(base, out)
    }

    /**
     * The days worth opening, newest first. The home date, plus — for a minted document — every day
     * file that could possibly hold it. See the class note for why each source is here.
     */
    private fun candidates(context: Context, base: String, homeDate: LocalDate): List<LocalDate> {
        val minted = mintedAt(base) ?: return listOf(homeDate)
        val out = sortedSetOf<LocalDate>(compareByDescending { it })
        out.add(homeDate)
        // The clock filter: one stat per day file, nothing opened.
        for (date in LedgerDocuments.dayDates(context)) {
            val f = LedgerPaths.dayFile(context, date) ?: continue
            if (f.lastModified() >= minted) out.add(date)
        }
        // The two derived indexes that already know where this base has been, in case a timestamp lied.
        runCatching {
            for ((key, n) in PickingsCards.counts(context)) {
                if (n <= 0) continue
                val parts = key.split('|')
                if (parts.size < 2 || parts[1] != base) continue
                runCatching { LocalDate.parse(parts[0]) }.getOrNull()?.let { out.add(it) }
            }
        }
        runCatching {
            for (info in LedgerTags.list(context)) {
                for (occ in info.occurrences) {
                    if (occ.second.substringBefore('#') == base) out.add(occ.first)
                }
            }
        }
        return out.toList()
    }

    /**
     * The page keys of [base] present in one day file — streamed, never decoded.
     *
     * The pattern is the QUOTED key, which is what makes it safe: a page key appears in the day JSON
     * as a `noteStrokes` map key, as an image element's `"page"` and as a text box's `"pageKey"`, all
     * of which open with a quote. A `sourceLink` of "ledger://2026-07-21/write-1753900000000" does
     * NOT match, because the character before the key there is a slash — which is the whole reason
     * the leading quote is in the pattern rather than being tidied away.
     *
     * A page key with an EMPTY stroke list still counts. Merely visiting a page writes its key, so
     * "write-…#4":[] is what page five of a document looks like when nothing has been written on it
     * yet — and it is exactly what the ‹ N › pager already counts as a page. The confirmation and the
     * pager therefore say the same number, which matters more than the number being minimal.
     */
    private fun pageKeysIn(file: File, base: String): List<String> {
        val pattern = Regex("\"" + Regex.escape(base) + "(#\\d+)?\"")
        val found = sortedSetOf<String>(compareBy({ it.substringAfter('#', "").toIntOrNull() ?: 0 }))
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(64 * 1024)
                var carry = ""
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    val window = carry + String(buffer, 0, read)
                    for (m in pattern.findAll(window)) found.add(m.value.trim('"'))
                    // Longer than any key, so nothing can be cut in half unseen.
                    carry = window.takeLast(256)
                }
            }
        }.onFailure { Timber.w(it, "could not scan ${file.name} for $base") }
        return found.toList()
    }

    /**
     * Clear [pages] from the day files they live in, and tombstone everything removed.
     *
     * Runs on a background thread by contract — every caller in this fork does — and one day at a
     * time under [DayLocks], because that is what keeps a delete from interleaving with the open
     * page's per-pen-up save or with a background placement.
     *
     * TOMBSTONES ARE NOT OPTIONAL HERE. [com.toolsboox.plugin.calendar.fi.CalendarDayMerger] unions
     * strokes and elements by id across devices, and its own note says the quiet part: "a union can't
     * tell 'never had this stroke' from 'erased it with no tombstone'". Removing a page's ink without
     * recording the ids would delete it on this Boox and have the iPad's copy hand every stroke back
     * on the next merge — the deletion would appear to work and then silently undo itself. The
     * tombstone fields (`deletedStrokeIds`, `deletedElementIds`) are already shared with iOS, so
     * nothing new goes on the wire; this simply uses them for what they are for.
     *
     * THE RESIDUE THIS ONCE HAD IS GONE, and it is worth saying what it was, because the erase below
     * is the thing that produces the evidence the fix runs on. `CalendarDayMerger.unionStrokeMap`
     * unioned the `noteStrokes` KEY SET before it filtered the strokes, so a merge with a device that
     * had not seen the deletion put the page key back with an EMPTY stroke list — invisible while
     * every page-lister filtered empties out, and no longer invisible now that Grid and Jot are
     * documents whose pages are counted straight off `noteStrokes.keys`. The merger declines to
     * re-add such a key, and it can tell a deleted page from a merely blank one ONLY because this
     * method removes the key outright and tombstones what stood on it. Both marks matter: the missing
     * key is the "one side only" signal, the tombstones are the proof of what happened to it.
     */
    fun erase(context: Context, service: CalendarDayService, pages: DocumentPages): PagesErased {
        val root = runCatching { LedgerPaths.documentsRoot(context) }.getOrNull()
            ?: return PagesErased(0, 0, pages.dates)
        var removed = 0
        var written = 0
        val unreadable = mutableListOf<LocalDate>()

        for (date in pages.dates) {
            val keys = pages.byDate[date]?.toSet() ?: continue
            DayLocks.withDay(date) {
                val file = LedgerPaths.dayFile(context, date)
                if (file == null) return@withDay
                // The null-returning overload, deliberately — see the class note. A day we cannot
                // read is a day we must not write.
                val day = runCatching { service.load(file) }.getOrNull()
                if (day == null) {
                    unreadable.add(date)
                    return@withDay
                }

                val deadStrokes = LinkedHashSet(day.deletedStrokeIds.map { it })
                val deadElements = LinkedHashSet(day.deletedElementIds.map { it })

                var touched = 0
                for (key in keys) {
                    day.noteStrokes.remove(key)?.forEach { deadStrokes.add(it.strokeId.toString()) }
                    touched++
                }

                val images = day.imageElements.filter { it.page in keys }
                day.imageElements.removeAll(images)
                images.forEach { deadElements.add(it.elementId.toString()) }

                val texts = day.textElements.filter { it.pageKey in keys }
                day.textElements.removeAll(texts)
                texts.forEach { deadElements.add(it.elementId.toString()) }

                // A/V grams: the poster frame was one of the images just removed, and the SOUNDING
                // part is an attachment the day carries separately. Left behind it is megabytes of
                // base64 belonging to a card that no longer exists — the heaviest thing in the file
                // pointing at a page that is gone. Dropped only when no surviving element still
                // references it, because a duplicated gram shares one attachment.
                val stillUsed = day.imageElements.mapNotNull { it.attachmentId.takeIf { a -> a.isNotBlank() } }.toSet()
                val orphanIds = images.mapNotNull { it.attachmentId.takeIf { a -> a.isNotBlank() } }
                    .toSet() - stillUsed
                if (orphanIds.isNotEmpty()) {
                    day.avGrams.removeAll { it.id in orphanIds }
                    orphanIds.forEach { deadElements.add(it) }
                }

                day.deletedStrokeIds = deadStrokes.toMutableList()
                day.deletedElementIds = deadElements.toMutableList()

                // THE ONE THROAT. Not a private writer: this save is what re-indexes the day's cards
                // (PickingsCards.record hangs off it), what writes atomically through a temp file,
                // and what the whole fork's day-file discipline is built around.
                runCatching { service.save(root, date, day) }
                    .onSuccess {
                        removed += touched
                        written++
                        // The occurrence stores that keep their own copy of "there is something on
                        // this page". The card index rebuilt itself inside the save; these two did not.
                        runCatching { LedgerTags.forgetPages(context, date, keys) }
                        runCatching { keys.forEach { LedgerProvenance.forgetPublished(context, date, it) } }
                    }
                    .onFailure {
                        Timber.w(it, "could not clear $date while deleting ${pages.base}")
                        unreadable.add(date)
                    }
            }
        }
        return PagesErased(removed, written, unreadable)
    }
}
