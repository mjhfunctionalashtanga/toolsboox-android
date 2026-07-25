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
        // ORDER MATTERS: the NEWER day's copy of a shared id must win. Strokes have no per-stroke
        // timestamp, and a moved element keeps its old one — so on any conflict/tie the first-listed
        // side prevails. Passing (a, b) here was the "my moved strokes flipped back after sync" bug:
        // the stale side could out-merge the edit it had never seen.
        merged.calendarStrokes = unionStrokeMap(newer.calendarStrokes, older.calendarStrokes, tombstones)
        merged.noteStrokes = unionStrokeMap(newer.noteStrokes, older.noteStrokes, tombstones)
        merged.textElements = unionTextElements(newer.textElements, older.textElements, elementTombstones)
        merged.imageElements = unionImageElements(newer.imageElements, older.imageElements, elementTombstones)

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
        merged.ledgerItems = LinkedHashMap<String, LedgerItem>().apply {
            newer.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
            older.ledgerItems.forEach { if (it.id.lowercase() !in itemTombstones && it.id.lowercase() !in elementTombstones) putIfAbsent(it.id, it) }
        }.values.toMutableList()

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

    /** Union two style/page → stroke-list maps by [Stroke.strokeId], dropping tombstoned ids. */
    private fun unionStrokeMap(
        m1: Map<String, List<Stroke>>, m2: Map<String, List<Stroke>>, deleted: Set<String> = emptySet()
    ): MutableMap<String, List<Stroke>> {
        val out = mutableMapOf<String, List<Stroke>>()
        for (key in (m1.keys + m2.keys)) {
            val byId = LinkedHashMap<UUID, Stroke>()
            (m1[key] ?: emptyList()).forEach { if (it.strokeId.toString() !in deleted) byId[it.strokeId] = it }
            (m2[key] ?: emptyList()).forEach { if (it.strokeId.toString() !in deleted && !byId.containsKey(it.strokeId)) byId[it.strokeId] = it }
            out[key] = byId.values.toList()
        }
        return out
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
