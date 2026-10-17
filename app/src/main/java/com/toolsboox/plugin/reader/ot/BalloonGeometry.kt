package com.toolsboox.plugin.reader.ot

/**
 * 💬 How a page's two detectors become ONE list of balloons, each with its words — pure, so
 * BalloonGeometryTest pins it, with the iPad twin's vectors (IC BalloonGeometry.mergeText,
 * BalloonGeometryTests.testTextMergesIntoBalloons) copied verbatim.
 *
 * Michael 2026-10-11, comics slice 2. Before this, one YOLO hit skipped the text pass, and the
 * text the fallback DID read (`blk.text`) was thrown away. Now both run on every page, and the
 * rule is the iPad's, step for step, so one page yields the same balloons on either device:
 *  1. A text LINE counts if it has 2+ characters and a letter — a page number ("12") or a stray
 *     glyph is never a balloon, nor a word in one.
 *  2. Each line joins the YOLO balloon it overlaps most, provided that balloon covers at least
 *     HALF the line's own area. Lines, not ML Kit's blocks (review 2026-10-11: a block that
 *     spans two close balloons, under half in each, became a third, overlapping stop) — and one
 *     by one, before any merging, so two balloons sitting close together never swap words.
 *  3. Leftover lines (a caption, a balloon YOLO missed — the DC pages it learned no shape for)
 *     merge when their grown boxes touch (grow by max(8 px, 0.8 × the shorter line's height))
 *     and each merged group, padded 18% + 6 px across and 35% + 6 px down, is an extra balloon.
 *  4. A balloon's words are its lines top to bottom (then left to right), one per line; a YOLO
 *     balloon with no lines keeps "" (a sound effect, a drawn scream, art).
 * Pixel constants are px of a 1024-px page, scaled by max(w, h) / 1024 (the iPad's rule), so the
 * rule reads the same off any decode size. Output: YOLO's balloons in their order, then the
 * extra ones — `texts` parallel to `rects`.
 *
 * [combine] is the pure merge (and the shared vector); [walkStops] is the indexer's policy over
 * it — the stops a page's walk actually makes (the iPad's BalloonGeometry.walkStops).
 */
internal object BalloonGeometry {

    /** One recognised text LINE, in the decoded page's PIXELS. */
    data class Line(val l: Float, val t: Float, val r: Float, val b: Float, val text: String)

    /** [rects] are page-relative [x, y, w, h] in 0–1; [texts] is parallel to it. */
    data class Balloons(val rects: List<FloatArray>, val texts: List<String>)

    /** The share of a line that a balloon must cover for the line's words to join it. */
    const val ATTACH_MIN = 0.5

    fun combine(yolo: List<FloatArray>, lines: List<Line>, w: Float, h: Float): Balloons {
        if (w <= 0f || h <= 0f) return Balloons(yolo, yolo.map { "" })
        val W = w.toDouble(); val H = h.toDouble()
        val s = maxOf(W, H) / 1024.0
        val kept = lines.filter { ln ->
            val t = ln.text.trim()
            ln.r > ln.l && ln.b > ln.t && t.codePointCount(0, t.length) >= 2 &&
                t.codePoints().anyMatch { Character.isLetter(it) }
        }
        val owned = Array(yolo.size) { mutableListOf<Line>() }
        val loose = mutableListOf<Line>()
        for (ln in kept) {
            val x = ln.l / W; val y = ln.t / H; val lw = (ln.r - ln.l) / W; val lh = (ln.b - ln.t) / H
            var best = -1; var bestArea = 0.0
            for ((i, b) in yolo.withIndex()) {
                if (b.size < 4) continue
                val ix = minOf(x + lw, (b[0] + b[2]).toDouble()) - maxOf(x, b[0].toDouble())
                val iy = minOf(y + lh, (b[1] + b[3]).toDouble()) - maxOf(y, b[1].toDouble())
                val a = if (ix > 0 && iy > 0) ix * iy else 0.0
                if (a > bestArea) { bestArea = a; best = i }
            }
            if (best >= 0 && bestArea >= ATTACH_MIN * lw * lh) owned[best] += ln else loose += ln
        }
        val rects = yolo.toMutableList()
        val texts = owned.map { joined(it) }.toMutableList()
        for (g in mergeLoose(loose, s)) {
            val padX = (g.r - g.l) * 0.18 + 6 * s; val padY = (g.b - g.t) * 0.35 + 6 * s
            val x0 = ((g.l - padX) / W).coerceIn(0.0, 1.0); val y0 = ((g.t - padY) / H).coerceIn(0.0, 1.0)
            val x1 = ((g.r + padX) / W).coerceIn(0.0, 1.0); val y1 = ((g.b + padY) / H).coerceIn(0.0, 1.0)
            if (x1 - x0 < 0.02 || y1 - y0 < 0.01) continue
            rects += floatArrayOf(x0.toFloat(), y0.toFloat(), (x1 - x0).toFloat(), (y1 - y0).toFloat())
            texts += joined(g.lines)
        }
        return Balloons(rects, texts)
    }

    /**
     * THE STOPS A PAGE'S WALK MAKES (the iPad's review, 2026-10-11 — fewer taps, Michael's rule):
     * the lettering adds stops ONLY to a page YOLO found no balloon on — the 09-13 text fallback,
     * for the DC pages YOLO learned no shape for. On a page YOLO did read, lettering outside its
     * balloons — a sound effect, a sign, a credit line — would be one more tap per page, so those
     * pages keep exactly YOLO's stops (in YOLO's order) and the lines inside them become their
     * words. [combine] stays the pure merge; the iPad indexer applies this same policy.
     */
    fun walkStops(yolo: List<FloatArray>, lines: List<Line>, w: Float, h: Float): Balloons {
        val merged = combine(yolo, lines, w, h)
        if (yolo.isEmpty()) return merged
        return Balloons(yolo, merged.texts.take(yolo.size))
    }

    private fun joined(lines: List<Line>): String =
        lines.sortedWith(compareBy<Line>({ it.t }, { it.l })).joinToString("\n") { it.text.trim() }

    private class Group(val l: Double, val t: Double, val r: Double, val b: Double, val lines: List<Line>)

    /** Leftover lines whose grown boxes touch are one balloon — lettering wraps over lines, and a
     *  balloon's lines sit within a line-height of each other. */
    private fun mergeLoose(loose: List<Line>, s: Double): List<Group> {
        val groups = loose.map { Group(it.l.toDouble(), it.t.toDouble(), it.r.toDouble(), it.b.toDouble(), listOf(it)) }
            .toMutableList()
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in groups.indices) for (j in i + 1 until groups.size) {
                val a = groups[i]; val c = groups[j]
                val g = maxOf(8 * s, minOf(a.b - a.t, c.b - c.t) * 0.8)
                if (a.l - g < c.r && c.l - g < a.r && a.t - g < c.b && c.t - g < a.b) {
                    groups[i] = Group(minOf(a.l, c.l), minOf(a.t, c.t), maxOf(a.r, c.r), maxOf(a.b, c.b), a.lines + c.lines)
                    groups.removeAt(j); merged = true; break@outer
                }
            }
        }
        return groups
    }
}
