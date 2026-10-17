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
 * device stays erased); reading events / A/V grams / tasks union by id (reading events and A/V grams
 * honour the element tombstones too, and a reading event's newer `updatedAt` wins); tracker values
 * take the newer side per key. The merge time is stamped fresh so both sides re-store the identical result and stop
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

    /**
     * The Log lines a day file really holds: [lines] less every one whose id is in
     * [deletedElementIds] (any case — iOS writes upper-case ids).
     *
     * The merge below already drops a removed line, but a file can still carry a line BESIDE its own
     * tombstone: a day merged by a build that predates line tombstones (the Boox fleet on -195, iOS
     * before 248) unions the line back and keeps the marker, and the WebDAV pull installs a day this
     * device never had straight from those bytes. So every decode of a day applies this too
     * ([CalendarDayService.load], the Log and corpus slices) — the Log, its search, the corpus (Ask,
     * Missed Connections, the Map), Your Year and the Reply-with-Log picker never show a highlight
     * that was removed (design critique #2, Michael 2026-10-12: removing the highlight removes its
     * line). The same instance comes back when nothing is dead, so the common path allocates nothing.
     */
    fun liveLines(lines: List<ReadingEvent>, deletedElementIds: Collection<String>): List<ReadingEvent> {
        if (lines.isEmpty() || deletedElementIds.isEmpty()) return lines
        val dead = deletedElementIds.mapTo(HashSet(deletedElementIds.size * 2)) { it.lowercase() }
        return if (lines.none { it.id.lowercase() in dead }) lines else lines.filter { it.id.lowercase() !in dead }
    }

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
        //
        // A LOG LINE REMOVED STAYS REMOVED, AN EDITED ONE STAYS EDITED (design critique #2, Michael
        // 2026-10-12 — the article highlight's Log line is edited in place and removed with its
        // highlight). Lines now honour the element tombstones, exactly as the A/V grams below
        // always have (the existing marker, reused — removing a line records its id there; see
        // ReadingEventEdit); and a line that carries [ReadingEvent.updatedAt] keeps its NEWER copy
        // whichever whole day is newer. Lines without it (everything written before) tie at 0 and
        // keep the old first-seen rule, so nothing already merged changes.
        merged.readingEvents = LinkedHashMap<String, ReadingEvent>().apply {
            for (e in newer.readingEvents + older.readingEvents) {
                if (e.id.lowercase() in elementTombstones) continue
                val held = get(e.id)
                if (held == null || (e.updatedAt?.time ?: 0L) > (held.updatedAt?.time ?: 0L)) put(e.id, e)
            }
        }.values.map { unionAttachments(it, newer.readingEvents, older.readingEvents, elementTombstones) }.toMutableList()
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
        // `proposed` is MONOTONIC toward PROMOTED (parity with `done`, the offer-never-mint ruling).
        // Promotion is the ASK — a deliberate one-tap act on the Proposed shelf — so a stale device
        // that still holds the candidate must not slide a task the reader already promoted back onto
        // the shelf, the same "to-dos keep reappearing" failure `done` guards. Once ANY copy of an id
        // is a live task (proposed=false), it stays one. There is no reverse act (nothing re-proposes
        // an item), so this only ever HONORS a promotion, never hides a candidate never promoted.
        val promotedAnywhere = (newer.ledgerItems + older.ledgerItems).filter { !it.proposed }.map { it.id }.toSet()
        merged.ledgerItems = LinkedHashMap<String, LedgerItem>().apply {
            newer.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
            older.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
        }.values.map {
            var r = it
            if (r.id in doneAnywhere && !r.done) r = r.copy(done = true)
            if (r.id in promotedAnywhere && r.proposed) r = r.copy(proposed = false)
            r
        }.toMutableList()

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
     * WHAT RIDES ON A LINE IS POOLED, NOT RACED (add-to-pick pin §3): a line is last-write-wins by
     * `updatedAt`, so a photo added to a highlight on the iPhone and a voice note added on the B6
     * before either synced would lose one of them. The winner keeps its own attachments, in order,
     * then gains every other copy's that it lacks (newer day's copy first) — an attachment taken off
     * the pick ("Remove from this pick…" tombstones it in `deletedElementIds` as [attachmentTombstone])
     * never comes back from either side. The same instance comes back when nothing changes (Swift
     * parity: `CalendarDayMerger.swift`, the same order, the same tombstone rule).
     */
    private fun unionAttachments(
        winner: ReadingEvent, a: List<ReadingEvent>, b: List<ReadingEvent>, dead: Set<String>
    ): ReadingEvent {
        val own = winner.attachments.orEmpty()
        val byId = LinkedHashMap<String, Attachment>()
        own.forEach { if (!isAttachmentTombstoned(it.id, dead)) byId.putIfAbsent(it.id, it) }
        for (copy in a + b) {
            if (copy.id != winner.id || copy === winner) continue
            copy.attachments?.forEach { if (!isAttachmentTombstoned(it.id, dead)) byId.putIfAbsent(it.id, it) }
        }
        val pooled = byId.values.toList()
        if (pooled == own) return winner
        return winner.copy(attachments = if (pooled.isEmpty() && winner.attachments == null) null else pooled.toMutableList())
    }

    /**
     * THE MARKER AN ATTACHMENT TAKEN OFF ITS LINE LEAVES in `deletedElementIds` — `att:<id>`, lower-
     * cased, the one form BOTH apps write (review 2026-10-16 #1: Android wrote the bare id, iOS
     * `att:<id>`, and each merger honoured only its own, so a removal made on one device came back
     * from the other's older copy of the line). Prefixed, so it never names an element, a line or an
     * A/V gram of the same id. iOS `CalendarDayMerger.attachmentTombstone`.
     */
    fun attachmentTombstone(id: String): String = "att:" + id.lowercase()

    /**
     * Is attachment [id] taken off its line? Either form counts — `att:<id>` and the bare id (the
     * first Android builds of "＋ Add to this pick" wrote it, and an A/V gram removed from that day
     * leaves it) — exactly as iOS `isAttachmentTombstoned` reads them, so the two mergers keep and
     * drop the same attachments. [dead] is lower-cased (the merge's element set); ids match
     * case-blind (iOS UUIDs are upper-case).
     */
    fun isAttachmentTombstoned(id: String, dead: Set<String>): Boolean {
        val low = id.lowercase()
        return "att:$low" in dead || low in dead
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
     * ── The iOS half ──────────────────────────────────────────────────────────────────────────
     *
     * `CalendarDayMerger.swift` carries the same rule since 2026-07-31 (guards verbatim, with its
     * own `CalendarDayMergerTests` pinning each one), so deletion is durable across the
     * Android↔iPad pair too, not just between Android devices. Before that landed, the pair
     * converged back to the empty key after one round trip (Android dropped it, the iPad's next
     * merge re-added it empty, and by then no tombstoned stroke survived to prove what happened,
     * so guard 3 correctly declined to drop it a second time) — it CONVERGED rather than
     * ping-ponging, which is why shipping the Android half first was safe.
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
