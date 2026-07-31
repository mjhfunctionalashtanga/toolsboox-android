package com.toolsboox.plugin.calendar.fi

import com.toolsboox.da.Attachment
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import java.util.Date
import java.util.UUID

/**
 * Conflict-free merge of two versions of the same calendar day — the single source of truth used by
 * BOTH sync back-ends (Google Drive and WebDAV/Ultrabridge), so a day edited on two devices converges
 * to the same result no matter which transport ran.
 *
 * Ink/text/images union by stable id with erase tombstones honoured (a stroke/element erased on either
 * device stays erased); reading events / A/V grams / tasks union by id; tracker values take the newer
 * side per key. The merge time is stamped fresh so both sides re-store the identical result and stop
 * diverging.
 *
 * Known limitation (same as before): a union can't tell "never had this stroke" from "erased it with no
 * tombstone", so an erase recorded without a tombstone can resurrect. For an append-mostly ledger this
 * is the right trade against losing a whole page to last-write-wins.
 *
 * ── The empty page key that used to come back ─────────────────────────────────────────────────
 *
 * [unionStrokeMap] unioned the KEY SET before it filtered the strokes, so merging with a device that
 * had not yet seen a page deletion put the page key back with an empty stroke list. `9c1d807c` wrote
 * that down as a residue rather than fixing it, and was right about the symptom at the time: "That is
 * harmless and invisible — every reader that lists pages filters empty stroke lists out, and the
 * document's index entry is gone, so nothing lists it — but a later scan will see the key."
 *
 * It stopped being invisible when Grid and Jot became savable documents (`29e0767b`) and their pages
 * joined the directory. `CalendarDayFragment.knownNotePageKeys` — which feeds every document's ‹ N ›
 * page count — reads `noteStrokes.keys` WITHOUT filtering empties, deliberately, because a page you
 * have paged to and not yet written on is a real page. A resurrected key is indistinguishable from
 * one of those, so a deleted page now comes back as a phantom page in the pager: no ink, but a page.
 * That is a key which exists and should not, so [unionStrokeMap] now declines to re-add it — see the
 * rule and its four guards there.
 */
object CalendarDayMerger {

    fun merge(a: CalendarDay, b: CalendarDay): CalendarDay {
        val newer = if ((a.updated?.time ?: 0L) >= (b.updated?.time ?: 0L)) a else b
        val older = if (newer === a) b else a

        // Tombstones compare case-insensitively: Android UUID.toString() is lowercase but
        // iOS uuidString is UPPERCASE, and a case-mismatched tombstone silently resurrects
        // the erased stroke. Normalize once here; the union helpers lowercase their side.
        val tombstones = LinkedHashSet<String>().apply {
            a.deletedStrokeIds.forEach { add(it.lowercase()) }
            b.deletedStrokeIds.forEach { add(it.lowercase()) }
        }
        val elementTombstones = LinkedHashSet<String>().apply {
            a.deletedElementIds.forEach { add(it.lowercase()) }
            b.deletedElementIds.forEach { add(it.lowercase()) }
        }
        // Dedicated item tombstones (wire name shared with iOS). Item deletions recorded by
        // pre-split builds live in deletedElementIds, so the ledgerItems union below honours
        // BOTH sets — a tombstoned id never survives as a live item, from either side.
        val itemTombstones = LinkedHashSet<String>().apply {
            a.deletedItemIds.forEach { add(it.lowercase()) }
            b.deletedItemIds.forEach { add(it.lowercase()) }
        }

        val merged = newer.deepCopy()
        // Sorted so the output JSON is byte-deterministic across platforms (Swift parity —
        // an unordered set re-ordered every save, which read as a change and re-pushed).
        merged.deletedStrokeIds = tombstones.sorted().toMutableList()
        merged.deletedElementIds = elementTombstones.sorted().toMutableList()
        merged.deletedItemIds = itemTombstones.sorted().toMutableList()
        // ORDER MATTERS in every one of the four unions below: the NEWER day's copy of a shared id
        // must win. Strokes have no per-stroke timestamp, and a moved element keeps its old one — so
        // on any conflict/tie the first-listed side prevails. Passing (a, b) here was the "my moved
        // strokes flipped back after sync" bug: the stale side could out-merge the edit it had never
        // seen. (The four lines themselves are now in a different order than they were — elements
        // first — for the reason given below. Which SIDE goes first is what the bug was about; which
        // FIELD is computed first is nothing to it.)
        merged.textElements = unionTextElements(newer.textElements, older.textElements, elementTombstones)
        merged.imageElements = unionImageElements(newer.imageElements, older.imageElements, elementTombstones)

        // The two page-key facts the stroke union needs and cannot work out for itself, gathered from
        // the element lists it does not see — which is why the elements are unioned above rather than
        // below now.
        //
        // LIVE pages are pages something still stands on. A page whose ink is gone but which still
        // carries a card or a text box is a page, and no amount of tombstone evidence may take its key
        // away. This is the guard that keeps the rule below from ever being clever at the ledger's
        // expense.
        val livePages = LinkedHashSet<String>().apply {
            merged.textElements.forEach { if (it.pageKey.isNotBlank()) add(it.pageKey) }
            merged.imageElements.forEach { if (it.page.isNotBlank()) add(it.page) }
        }
        // ERASED pages are pages an element was tombstoned OFF. This is the other half of the
        // deletion evidence: a Pickings board or an intake page can hold cards and no ink at all, so
        // "every stroke under this key was tombstoned" would find nothing to go on there — the whole
        // page was elements. LedgerDocumentPages.erase tombstones those elements as it clears the
        // page, and this is where that record is read.
        val erasedPages = LinkedHashSet<String>().apply {
            (a.textElements + b.textElements).forEach {
                if (it.elementId.toString().lowercase() in elementTombstones && it.pageKey.isNotBlank()) add(it.pageKey)
            }
            (a.imageElements + b.imageElements).forEach {
                if (it.elementId.toString().lowercase() in elementTombstones && it.page.isNotBlank()) add(it.page)
            }
        }

        // calendarStrokes is keyed by the calendar sheet's own style keys, not by page keys, so it
        // gets no element evidence — only the stroke half of the rule can apply there, which is
        // exactly right: nothing on the calendar sheet is a "page" anyone can be stranded on.
        merged.calendarStrokes = unionStrokeMap(newer.calendarStrokes, older.calendarStrokes, tombstones)
        merged.noteStrokes = unionStrokeMap(
            newer.noteStrokes, older.noteStrokes, tombstones, livePages, erasedPages
        )

        // Newer side first, first-seen wins — same winner AND order as the Swift merger,
        // so both platforms produce byte-identical output from the same two inputs.
        merged.readingEvents = LinkedHashMap<String, ReadingEvent>().apply {
            newer.readingEvents.forEach { putIfAbsent(it.id, it) }
            older.readingEvents.forEach { putIfAbsent(it.id, it) }
        }.values.toMutableList()
        // A/V grams honour element tombstones (Swift parity) — a deleted gram stays deleted.
        merged.avGrams = LinkedHashMap<String, Attachment>().apply {
            newer.avGrams.forEach { if (it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
            older.avGrams.forEach { if (it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
        }.values.toMutableList()
        // Tasks/events union by id, but honour tombstones — the dedicated item list plus the
        // element list (where pre-split builds recorded item deletions) — so a deleted item
        // stays deleted instead of resurrecting from the other device's copy.
        //
        // `done` is MONOTONIC (parity with iOS `unionLedgerItems`): once a task is checked off on
        // ANY device it stays checked. Newer-wins-wholesale otherwise re-opened completed tasks
        // whenever the other device's day merely got a newer timestamp (opening it is enough) — the
        // "to-dos keep reappearing" bug. `done` has no per-item clock to arbitrate by, so OR it
        // across both copies of an id; both platforms apply this identically so convergence holds.
        val doneAnywhere = (newer.ledgerItems + older.ledgerItems).filter { it.done }.map { it.id }.toSet()
        merged.ledgerItems = LinkedHashMap<String, LedgerItem>().apply {
            newer.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
            older.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
        }.values.map { if (it.id in doneAnywhere && !it.done) it.copy(done = true) else it }.toMutableList()

        val values = LinkedHashMap<String, Map<String, Float?>>()
        values.putAll(older.calendarValues)
        values.putAll(newer.calendarValues)
        merged.calendarValues = values

        merged.created = a.created ?: b.created
        // updated = max of the two sides, NOT Date(): a fresh stamp made every merge output
        // look newer than both inputs, so two-device pairs re-merged and re-uploaded forever.
        // With max(), merging is idempotent — identical content converges and sync goes quiet.
        merged.updated = Date(maxOf(a.updated?.time ?: 0L, b.updated?.time ?: 0L))
        return merged
    }

    /**
     * Union two style/page → stroke-list maps by [Stroke.strokeId], dropping tombstoned ids — and
     * declining to re-add a page key the tombstones say was DELETED.
     *
     * ── The rule, and why it is this narrow ───────────────────────────────────────────────────
     *
     * A key is left out only when all four of these hold:
     *
     *  1. It is on ONE SIDE ONLY. This is the load-bearing guard, and it is what tells a page
     *     DELETION from an ordinary erase. Deleting a document runs `day.noteStrokes.remove(key)` —
     *     the key goes. Rubbing every stroke off a page you still want leaves the key sitting there
     *     with an empty list, on both sides, and the page keeps its place in the ‹ N › pager. Two
     *     gestures, two different marks on disk, and only one of them is a deletion.
     *  2. It has no surviving strokes after tombstoning.
     *  3. The tombstones EXPLAIN that emptiness — either every stroke it had on the one side it was
     *     on was tombstoned away, or an element that stood on it was ([erasedPages]). A key that
     *     was already empty with nothing tombstoned is a page someone paged to and has not written
     *     on yet, which is a real page, and it is kept.
     *  4. Nothing live still stands on it ([livePages]).
     *
     * Anything narrower would not fix the bug; anything wider starts eating blank pages. What was
     * REJECTED, explicitly: dropping any one-sided key that merges out empty. That is one line
     * shorter and it deletes page five of a five-page writing the moment the other device has not
     * seen you page to it — losing a page you can still reach is a far worse failure than a page key
     * that lingers, and the whole delete design (`9c1d807c`) is arranged around never stranding one.
     *
     * ── The iOS half, stated rather than assumed ──────────────────────────────────────────────
     *
     * `CalendarDayMerger.swift` unions its key set the same way and needs the same change; until it
     * has it, an Android↔iPad pair converges back to the empty key after one round trip (Android
     * drops it, the iPad's next merge re-adds it empty, and by then no tombstoned stroke survives to
     * prove what happened, so guard 3 correctly declines to drop it a second time). It CONVERGES
     * rather than ping-ponging, which is what mattered: both forks still stop re-uploading. This is
     * the same honest limit [com.toolsboox.plugin.calendar.ot.LedgerDocumentTombstones] already
     * records for titles — deletion is durable between Android devices now, and complete when iOS
     * grows the matching rule.
     */
    private fun unionStrokeMap(
        m1: Map<String, List<Stroke>>, m2: Map<String, List<Stroke>>, deleted: Set<String> = emptySet(),
        livePages: Set<String> = emptySet(), erasedPages: Set<String> = emptySet()
    ): MutableMap<String, List<Stroke>> {
        val out = mutableMapOf<String, List<Stroke>>()
        for (key in (m1.keys + m2.keys)) {
            val byId = LinkedHashMap<UUID, Stroke>()
            (m1[key] ?: emptyList()).forEach { if (it.strokeId.toString() !in deleted) byId[it.strokeId] = it }
            (m2[key] ?: emptyList()).forEach { if (it.strokeId.toString() !in deleted && !byId.containsKey(it.strokeId)) byId[it.strokeId] = it }
            if (byId.isEmpty() && isTombstonedPage(key, m1, m2, deleted, livePages, erasedPages)) continue
            out[key] = byId.values.toList()
        }
        return out
    }

    /** The four guards of the deletion rule, in the order they are cheapest to refuse on. */
    private fun isTombstonedPage(
        key: String, m1: Map<String, List<Stroke>>, m2: Map<String, List<Stroke>>,
        deleted: Set<String>, livePages: Set<String>, erasedPages: Set<String>
    ): Boolean {
        if (key in livePages) return false
        val onBothSides = m1.containsKey(key) && m2.containsKey(key)
        if (onBothSides) return false
        val had = m1[key] ?: m2[key] ?: return false
        // Strokes present but every one of them tombstoned, or the page's elements were tombstoned
        // off it. Either is the other device saying "I have not seen this deletion yet".
        return (had.isNotEmpty() && had.all { it.strokeId.toString() in deleted }) || key in erasedPages
    }

    /** Union text boxes by [TextElement.elementId] (later timestamp wins), dropping tombstoned ids. */
    private fun unionTextElements(l1: List<TextElement>, l2: List<TextElement>, deleted: Set<String> = emptySet()): MutableList<TextElement> {
        val byId = LinkedHashMap<UUID, TextElement>()
        (l1 + l2).forEach { e ->
            if (e.elementId.toString() in deleted) return@forEach
            val prev = byId[e.elementId]
            if (prev == null || e.timestamp > prev.timestamp) byId[e.elementId] = e
        }
        return byId.values.toMutableList()
    }

    /** Union images by [ImageElement.elementId] (later timestamp wins), dropping tombstoned ids. */
    private fun unionImageElements(l1: List<ImageElement>, l2: List<ImageElement>, deleted: Set<String> = emptySet()): MutableList<ImageElement> {
        val byId = LinkedHashMap<UUID, ImageElement>()
        (l1 + l2).forEach { e ->
            if (e.elementId.toString() in deleted) return@forEach
            val prev = byId[e.elementId]
            if (prev == null || e.timestamp > prev.timestamp) byId[e.elementId] = e
        }
        return byId.values.toMutableList()
    }
}
