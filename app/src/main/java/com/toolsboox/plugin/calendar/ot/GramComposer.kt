package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.da.Stroke
import com.toolsboox.da.StrokePoint
import com.toolsboox.da.TextElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Writes a GRAPH onto a note page as ordinary ledger objects.
 *
 * The point, in Michael's words, is that saving a map should be "more than an image": a PNG through
 * the share sheet leaves the ledger holding a picture of its own thinking, which it cannot search,
 * cannot walk, and cannot be written on. So nothing here invents a new kind of page or a new wire
 * field. A composed page is exactly what a hand-made page is —
 *
 *   • each node is a GRAM ([ImageElement]) carrying `sourceLink`/`sourceLabel`, so the provenance
 *     is LIVE: tapping a node on the saved map jumps to the thing the node was about, the same way
 *     tapping any other gram does. That is the whole reason to save a map as objects at all.
 *   • each node also gets a TEXT BOX ([TextElement]) under it, so the page reads as text to search,
 *     to the web view, and to Ask — and so every label stays editable by hand.
 *   • each edge is a CONNECTOR ([Stroke]) in the page's ink. Ink, not a new element type, because
 *     then the connectors are erasable, movable and mergeable by machinery that already exists.
 *
 * Everything above is existing wire (WIRE-MEDIA-BY-REFERENCE.md unchanged, no `-v3` bump, no new
 * keys), which is what makes this safe to land on one fork ahead of the other: an older reader sees
 * a page of grams, text and ink, because that is all it is.
 *
 * Two layouts, because the two callers want two different-feeling pages:
 *
 *   • [Layout.GRID] — the Map's. A tidy lattice of connected grams, orderly enough to read the
 *     shape of the thinking off it at a glance.
 *   • [Layout.COLLAGE] — the research assistant's. Scattered, tilted, overlapping: "cliparty",
 *     a jots page someone pinned things to rather than a diagram someone drew.
 *
 * Both are deterministic. The scatter is derived from a hash of each node's ref rather than from a
 * random source, so re-composing the same graph gives the same page instead of a new arrangement
 * every time — a map you recognise is a map you can return to.
 */
object GramComposer {

    /**
     * One thing on the page.
     *
     * [ref] is IDENTITY — what edges point at. [link] is PROVENANCE — the `ledger://` ref or
     * http(s) URL the finished gram carries as its `sourceLink`, so the node stays a door back to
     * its origin. They are separate on purpose: a map drawn from an outline or a persona has nodes
     * with perfectly good identities and no addresses at all, and writing a synthetic id into
     * `sourceLink` would put a door on the page that opens onto nothing.
     *
     * [image] is optional: a node with no picture of its own gets a drawn card instead, which is
     * what keeps a text-only graph from composing into a page of empty boxes.
     */
    data class Node(
        val ref: String,
        val label: String,
        val link: String = "",
        val sourceLabel: String = "",
        val image: Bitmap? = null,
        /** A short word or glyph for the drawn card — what KIND of thing this is. */
        val kind: String = "",
        /** Optional second line under the label on the page: a quote, a date, a citation. */
        val note: String = "",
    )

    /** A connector to draw. [label] is written at the midpoint when present. */
    data class Edge(val from: String, val to: String, val label: String = "")

    enum class Layout { GRID, COLLAGE }

    /**
     * Compose [nodes] and [edges] onto a NEW note of [template], and return the note.
     *
     * The note is minted through [LedgerDocuments.startNote] — the same seam the Notes door uses —
     * so a composed page is indistinguishable from one you started by hand, appears in the
     * directory, and can be renamed, untitled or forgotten like any other.
     *
     * Returns null when the template is not one of the page templates, or when there is nothing to
     * compose. Callers show their own message; composing an empty page would just be litter.
     */
    fun compose(
        context: Context,
        service: CalendarDayService,
        root: File,
        template: String,
        title: String,
        nodes: List<Node>,
        edges: List<Edge> = emptyList(),
        layout: Layout = Layout.GRID,
        date: LocalDate = LocalDate.now(),
    ): LedgerDocuments.NewNote? {
        if (nodes.isEmpty()) return null
        // Text notes have no canvas to place anything on — composing into one would silently drop
        // every gram and connector. A caller asking for that has made a mistake worth failing on.
        if (template == LedgerDocuments.TEXT_NOTES) return null
        val note = LedgerDocuments.startNote(context, template, title, date) ?: return null

        val placed = placements(nodes, layout)
        val byRef = placed.associateBy { it.node.ref }

        // Load → mutate → save under the day lock, exactly like gram placement: a compose is a
        // multi-element write and the open page saves on every pen-up, so an unlocked writer here
        // would interleave with it and lose objects.
        DayLocks.withDay(date) {
            val day = service.load(root, date, null, Locale.getDefault())
            val stamp = System.currentTimeMillis()

            // Connectors go down FIRST so the grams sit on top of them — a line that crosses over
            // a card reads as a scribble, a line that runs under it reads as an attachment.
            val ink = day.noteStrokes[note.key].orEmpty().toMutableList()
            for (edge in edges) {
                val a = byRef[edge.from] ?: continue
                val b = byRef[edge.to] ?: continue
                ink += connector(a, b, stamp)
                if (edge.label.isNotBlank()) {
                    day.textElements += TextElement(
                        x = (a.cx + b.cx) / 2f - 90f, y = (a.cy + b.cy) / 2f - 16f,
                        width = 180f, height = 34f, text = edge.label,
                        fontSize = 19f, pageKey = note.key, timestamp = stamp,
                    )
                }
            }
            day.noteStrokes[note.key] = ink

            for ((i, p) in placed.withIndex()) {
                val bmp = p.node.image ?: card(p.node, p.w.toInt(), p.h.toInt(), layout)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                day.imageElements += ImageElement(
                    elementId = UUID.randomUUID(), timestamp = stamp,
                    x = p.x, y = p.y, width = p.w, height = p.h,
                    // Inline base64 on the way in; the day's save path externalizes anything over
                    // the 64 KiB threshold into `media/` (LedgerMedia.externalizeDay). Drawn cards
                    // are a few KB and stay inline, which is what we want for something that is
                    // regenerable — no reason to grow the media store with rendered labels.
                    data = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP),
                    page = note.key,
                    // The provenance, and the reason any of this is worth doing — blank for a
                    // node that never had an address.
                    sourceLink = p.node.link,
                    sourceLabel = p.node.sourceLabel.ifBlank { p.node.label },
                    rotation = p.rotation,
                    z = i,
                )
                day.textElements += TextElement(
                    x = p.x, y = p.y + p.h + 6f, width = p.w, height = if (p.node.note.isBlank()) 40f else 72f,
                    text = if (p.node.note.isBlank()) p.node.label else "${p.node.label}\n${p.node.note}",
                    fontSize = 22f, pageKey = note.key, timestamp = stamp,
                    // Web links stay intact here even though the on-canvas text soft-wraps them.
                    sourceUrl = p.node.link.takeIf { it.startsWith("http") },
                )
            }

            day.updated = java.util.Date()
            service.save(root, date, day)
        }
        return note
    }

    // ── Layout ────────────────────────────────────────────────────────────────────────────────

    private data class Spot(
        val node: Node, val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float,
    ) {
        val cx: Float get() = x + w / 2f
        val cy: Float get() = y + h / 2f
    }

    private fun placements(nodes: List<Node>, layout: Layout): List<Spot> = when (layout) {
        Layout.GRID -> nodes.take(COLS * ROWS).mapIndexed { i, n ->
            val col = i % COLS
            val row = i / COLS
            Spot(
                n,
                x = MARGIN + col * (TILE_W + GUTTER),
                y = TOP + row * (TILE_H + LABEL_H + GUTTER),
                w = TILE_W, h = TILE_H, rotation = 0f,
            )
        }

        // Scatter: walk the same lattice, then knock everything off its square. Starting from the
        // grid is what keeps a collage from piling up in one corner — the jitter is disorder added
        // to an even spread, not disorder standing in for one.
        Layout.COLLAGE -> nodes.take(COLS * ROWS).mapIndexed { i, n ->
            val col = i % COLS
            val row = i / COLS
            val j = jitter(n.ref)
            val scale = 0.82f + (j[2] * 0.30f)
            val w = TILE_W * scale
            val h = TILE_H * scale
            Spot(
                n,
                x = (MARGIN + col * (TILE_W + GUTTER) + (j[0] - 0.5f) * GUTTER * 2.4f)
                    .coerceIn(MARGIN / 2f, CANVAS_W - w - MARGIN / 2f),
                y = (TOP + row * (TILE_H + LABEL_H + GUTTER) + (j[1] - 0.5f) * GUTTER * 2.0f)
                    .coerceIn(TOP / 2f, CANVAS_H - h - LABEL_H - MARGIN / 2f),
                w = w, h = h,
                rotation = (j[3] - 0.5f) * 2f * MAX_TILT,
            )
        }
    }

    /** Four stable pseudo-randoms in 0..1 from a ref, so a graph always composes the same way. */
    private fun jitter(ref: String): FloatArray {
        var h = ref.hashCode().toLong() and 0xffffffffL
        return FloatArray(4) {
            h = (h * 6364136223846793005L + 1442695040888963407L) ushr 1
            ((h % 1000L).toFloat() / 1000f)
        }
    }

    // ── Connectors ────────────────────────────────────────────────────────────────────────────

    /**
     * A line from the edge of one card to the edge of the other, bowed slightly off-straight.
     *
     * The bow is not decoration. A dead-straight run between two boxes reads as a printed diagram;
     * a slight arc reads as something drawn on the page, which is what the rest of this surface is.
     * It also separates the two connectors of a mutual pair instead of overdrawing them.
     */
    private fun connector(a: Spot, b: Spot, stamp: Long): Stroke {
        val (x1, y1) = edgePoint(a, b.cx, b.cy)
        val (x2, y2) = edgePoint(b, a.cx, a.cy)
        val mx = (x1 + x2) / 2f
        val my = (y1 + y2) / 2f
        // Perpendicular offset, proportional to length and capped so long runs don't balloon.
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val bow = (len * 0.06f).coerceAtMost(34f)
        val bx = mx + (-dy / len) * bow
        val by = my + (dx / len) * bow

        val pts = ArrayList<StrokePoint>(CONNECTOR_POINTS)
        for (i in 0 until CONNECTOR_POINTS) {
            val t = i.toFloat() / (CONNECTOR_POINTS - 1)
            val u = 1f - t
            // Quadratic Bézier through the bowed control point.
            pts += StrokePoint(
                x = u * u * x1 + 2 * u * t * bx + t * t * x2,
                y = u * u * y1 + 2 * u * t * by + t * t * y2,
                p = 0.6f, t = stamp,
            )
        }
        return Stroke(
            strokeId = UUID.randomUUID(), timestamp = stamp, strokePoints = pts,
            strokeWidth = 2.5f, inkStyle = Stroke.STYLE_NORMAL,
        )
    }

    /** Where the line from ([tx],[ty]) meets this card's border, so connectors touch edges not centres. */
    private fun edgePoint(s: Spot, tx: Float, ty: Float): Pair<Float, Float> {
        val dx = tx - s.cx
        val dy = ty - s.cy
        if (dx == 0f && dy == 0f) return s.cx to s.cy
        val sx = if (dx == 0f) Float.MAX_VALUE else (s.w / 2f) / kotlin.math.abs(dx)
        val sy = if (dy == 0f) Float.MAX_VALUE else (s.h / 2f) / kotlin.math.abs(dy)
        val k = minOf(sx, sy)
        return (s.cx + dx * k) to (s.cy + dy * k)
    }

    // ── The drawn card ────────────────────────────────────────────────────────────────────────

    /**
     * A node with no picture, drawn as one.
     *
     * This is the "cliparty" part: a bordered card with a big kind-glyph and the label set large,
     * so a graph of bare refs still composes into a page of OBJECTS you can see and move, rather
     * than a page of text that happens to be positioned. On a collage the border is heavier and
     * the corner is dog-eared, because a collage wants things that look pinned on.
     */
    private fun card(node: Node, w: Int, h: Int, layout: Layout): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(8), h.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (layout == Layout.COLLAGE) 6f else 3f
            color = Color.BLACK
        }
        val inset = border.strokeWidth
        val r = RectF(inset, inset, w - inset, h - inset)
        c.drawRoundRect(r, 14f, 14f, border)

        val glyph = node.kind.ifBlank { glyphFor(node.link) }
        val gp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = h * 0.30f
            textAlign = Paint.Align.CENTER
        }
        c.drawText(glyph, w / 2f, h * 0.42f, gp)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (h * 0.115f).coerceAtMost(34f)
            textAlign = Paint.Align.CENTER
        }
        // Two lines, ellipsized — the full label also lands in the text box under the card, so
        // nothing is lost by keeping the face of the card readable.
        val lines = wrap(node.label, tp, w - 28f, 2)
        var y = h * 0.66f
        for (line in lines) {
            c.drawText(line, w / 2f, y, tp)
            y += tp.textSize * 1.25f
        }
        return bmp
    }

    /** What kind of thing a ref points at, as one mark. */
    private fun glyphFor(ref: String): String = when {
        ref.startsWith("ledger://") -> "❝"
        ref.startsWith("http") -> "⌘"
        else -> "⁂"
    }

    private fun wrap(text: String, paint: TextPaint, width: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) out += line.toString()
                line = StringBuilder(word)
                if (out.size == maxLines) break
            }
        }
        if (out.size < maxLines && line.isNotEmpty()) out += line.toString()
        if (out.size == maxLines) {
            val last = out[maxLines - 1]
            if (paint.measureText(last) > width || words.joinToString(" ") != out.joinToString(" ")) {
                var t = last
                while (t.isNotEmpty() && paint.measureText("$t…") > width) t = t.dropLast(1)
                out[maxLines - 1] = if (t == last) last else "$t…"
            }
        }
        return out
    }

    // Page geometry — the standard Boox page the rest of the calendar surfaces draw into.
    private const val CANVAS_W = 1404f
    private const val CANVAS_H = 1872f
    private const val MARGIN = 60f
    private const val TOP = 150f          // room for the note's own title band
    private const val GUTTER = 42f
    private const val COLS = 3
    private const val ROWS = 4
    private const val TILE_W = 380f
    private const val TILE_H = 280f
    private const val LABEL_H = 62f
    private const val MAX_TILT = 7f       // degrees, collage only
    private const val CONNECTOR_POINTS = 24
}
