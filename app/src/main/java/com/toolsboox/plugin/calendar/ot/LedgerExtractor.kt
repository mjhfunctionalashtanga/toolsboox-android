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

    // The panel auto-extract (whole Tasks/Schedule sections clustered by row and OCR'd into
    // items, source="auto") retired 2026-08-10 with its settings toggle: it guessed, the lasso
    // flow asks — and the lasso "Create task/event" path below is the one blessed reader.

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

    /**
     * Not writing → keep it out of the OCR (and out of the row-clustering so it can't bridge rows):
     * a highlight/underline (long, flat, thin horizontal line — on e-ink a highlight is a black
     * underline), or a lasso/big circle (large closed loop returning near its start).
     */
    private fun isNoise(s: Stroke): Boolean {
        val b = bounds(s)
        val w = b.width(); val h = b.height()
        if (w > 80f && h < 22f && w > h * 6f) return true                 // highlight / underline
        if (w > 280f && h > 220f) {                                       // large loop…
            val pts = s.strokePoints
            if (pts.size >= 2) {
                val d = kotlin.math.hypot((pts.first().x - pts.last().x).toDouble(), (pts.first().y - pts.last().y).toDouble())
                if (d < 80.0) return true                                 // …that closes ≈ a lasso circle
            }
        }
        return false
    }

    private fun writingOnly(strokes: List<Stroke>): List<Stroke> =
        strokes.filter { it.strokePoints.isNotEmpty() && !isNoise(it) }

    private fun strokesInRect(strokes: List<Stroke>, rect: RectF): List<Stroke> =
        writingOnly(strokes).filter { centroid(it).let { (x, y) -> rect.contains(x, y) } }

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

    /**
     * Lasso: the given strokes are one item (the selection defines the boundary). [date] is the
     * DUE date — the page's own day, so a task written on a future page is due that day.
     */
    suspend fun extractStrokes(
        strokes: List<Stroke>, kind: LedgerItem.Kind, source: String, date: Date
    ): LedgerItem? = itemFrom(writingOnly(strokes), kind, source, date)

    /** Bounding rect of [strokes] (for rendering a vision crop); empty rect if none. */
    fun boundsOf(strokes: List<Stroke>): RectF =
        if (strokes.isEmpty()) RectF() else groupBounds(strokes)

    /** Build an item from a selection + already-recognized [text] (the Layer-2 vision path). [date] = due date. */
    fun itemWithText(strokes: List<Stroke>, kind: LedgerItem.Kind, text: String, source: String, date: Date): LedgerItem? {
        if (strokes.isEmpty() || text.isBlank()) return null
        val b = groupBounds(strokes)
        return LedgerItem(
            id = "li-${UUID.randomUUID()}", kind = kind, text = text.trim(), date = date,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            strokeIds = strokes.map { it.strokeId.toString() }.toMutableList(),
            display = LedgerItem.Display.TEXT,
            time = if (kind == LedgerItem.Kind.EVENT) leadingTime(text) else null,
            confidence = 0.99f, source = source
        )
    }

    private suspend fun itemFrom(group: List<Stroke>, kind: LedgerItem.Kind, source: String, date: Date): LedgerItem? {
        if (group.isEmpty()) return null
        val text = PanelOcr.recognize(group).trim()
        if (text.isEmpty()) return null
        val b = groupBounds(group)
        val time = if (kind == LedgerItem.Kind.EVENT) leadingTime(text) else null
        return LedgerItem(
            id = "li-${UUID.randomUUID()}",
            kind = kind,
            text = text,
            date = date,
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
