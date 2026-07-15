package com.toolsboox.plugin.calendar.ot

import android.graphics.RectF
import com.toolsboox.da.Stroke
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import java.util.Date
import java.util.UUID

/**
 * Turns a day page's handwriting into structured [LedgerItem]s — one per task / event.
 *
 * Two entry points:
 *  - [extractPanel] (auto): cluster the strokes inside a section rect into vertical rows and OCR
 *    each cluster. A gap larger than [ROW_GAP] starts a new item, so tightly-wrapped lines of one
 *    task stay together while a clear gap to the next task splits them. Imperfect by nature —
 *    hence the lasso path below.
 *  - [extractStrokes] (lasso): the caller's lasso selection IS the group, so a multi-line task or
 *    a sprawling event becomes exactly one item — no grouping guesswork.
 *
 * Layer-1 OCR only (on-device ink). A Layer-2 vision pass can later upgrade an item's text.
 */
object LedgerExtractor {

    /** Vertical gap (1872-tall template space) beyond which a new item starts. */
    private const val ROW_GAP = 46f

    private fun bounds(s: Stroke): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in s.strokePoints) { l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y) }
        return RectF(l, t, r, b)
    }

    private fun groupBounds(g: List<Stroke>): RectF {
        val out = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in g) out.union(bounds(s))
        return out
    }

    private fun centroid(s: Stroke): Pair<Float, Float> {
        val n = s.strokePoints.size.coerceAtLeast(1)
        return s.strokePoints.sumOf { it.x.toDouble() }.toFloat() / n to
            s.strokePoints.sumOf { it.y.toDouble() }.toFloat() / n
    }

    private fun strokesInRect(strokes: List<Stroke>, rect: RectF): List<Stroke> =
        strokes.filter { it.strokePoints.isNotEmpty() && centroid(it).let { (x, y) -> rect.contains(x, y) } }

    /** Cluster strokes top-to-bottom; a Y-gap > [ROW_GAP] between clusters splits into a new item. */
    private fun clusterByRow(strokes: List<Stroke>): List<List<Stroke>> {
        if (strokes.isEmpty()) return emptyList()
        val sorted = strokes.sortedBy { bounds(it).top }
        val groups = mutableListOf<MutableList<Stroke>>()
        var curBottom = Float.NEGATIVE_INFINITY
        for (s in sorted) {
            val bd = bounds(s)
            if (groups.isEmpty() || bd.top - curBottom > ROW_GAP) {
                groups.add(mutableListOf(s)); curBottom = bd.bottom
            } else {
                groups.last().add(s); curBottom = maxOf(curBottom, bd.bottom)
            }
        }
        return groups
    }

    /** Auto: extract one item per row-cluster inside [rect]. */
    suspend fun extractPanel(
        strokes: List<Stroke>, rect: RectF, kind: LedgerItem.Kind, source: String
    ): List<LedgerItem> =
        clusterByRow(strokesInRect(strokes, rect)).mapNotNull { group -> itemFrom(group, kind, source) }

    /** Lasso: the given strokes are one item (the selection defines the boundary). */
    suspend fun extractStrokes(
        strokes: List<Stroke>, kind: LedgerItem.Kind, source: String
    ): LedgerItem? = itemFrom(strokes, kind, source)

    private suspend fun itemFrom(group: List<Stroke>, kind: LedgerItem.Kind, source: String): LedgerItem? {
        if (group.isEmpty()) return null
        val text = PanelOcr.recognize(group).trim()
        if (text.isEmpty()) return null
        val b = groupBounds(group)
        val time = if (kind == LedgerItem.Kind.EVENT) leadingTime(text) else null
        return LedgerItem(
            id = "li-${UUID.randomUUID()}",
            kind = kind,
            text = text,
            date = Date(),
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            strokeIds = group.map { it.strokeId.toString() }.toMutableList(),
            display = LedgerItem.Display.TEXT,
            time = time,
            source = source
        )
    }

    /** Pull a leading time like "9", "9am", "09:00" off an event line, if present. */
    private fun leadingTime(text: String): String? =
        Regex("""^\s*(\d{1,2}(:\d{2})?\s*(am|pm|AM|PM)?)""").find(text)?.groupValues?.get(1)?.trim()?.ifBlank { null }
}
