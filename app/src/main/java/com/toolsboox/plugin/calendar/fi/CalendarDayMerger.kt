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

        val tombstones = LinkedHashSet<String>().apply {
            addAll(a.deletedStrokeIds); addAll(b.deletedStrokeIds)
        }
        val elementTombstones = LinkedHashSet<String>().apply {
            addAll(a.deletedElementIds); addAll(b.deletedElementIds)
        }

        val merged = newer.deepCopy()
        merged.deletedStrokeIds = tombstones.toMutableList()
        merged.deletedElementIds = elementTombstones.toMutableList()
        merged.calendarStrokes = unionStrokeMap(a.calendarStrokes, b.calendarStrokes, tombstones)
        merged.noteStrokes = unionStrokeMap(a.noteStrokes, b.noteStrokes, tombstones)
        merged.textElements = unionTextElements(a.textElements, b.textElements, elementTombstones)
        merged.imageElements = unionImageElements(a.imageElements, b.imageElements, elementTombstones)

        merged.readingEvents = LinkedHashMap<String, ReadingEvent>().apply {
            a.readingEvents.forEach { put(it.id, it) }
            b.readingEvents.forEach { put(it.id, it) }
        }.values.toMutableList()
        merged.avGrams = LinkedHashMap<String, Attachment>().apply {
            a.avGrams.forEach { put(it.id, it) }
            b.avGrams.forEach { put(it.id, it) }
        }.values.toMutableList()
        // Tasks/events union by id, but honour tombstones (shared with elements) so a
        // deleted item stays deleted instead of resurrecting from the other device's copy.
        merged.ledgerItems = LinkedHashMap<String, LedgerItem>().apply {
            a.ledgerItems.forEach { if (it.id !in elementTombstones) put(it.id, it) }
            b.ledgerItems.forEach { if (it.id !in elementTombstones) put(it.id, it) }
        }.values.toMutableList()

        val values = LinkedHashMap<String, Map<String, Float?>>()
        values.putAll(older.calendarValues)
        values.putAll(newer.calendarValues)
        merged.calendarValues = values

        merged.created = a.created ?: b.created
        merged.updated = Date()
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
