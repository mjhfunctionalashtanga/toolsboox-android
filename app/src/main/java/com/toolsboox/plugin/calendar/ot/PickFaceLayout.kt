package com.toolsboox.plugin.calendar.ot

/**
 * THE FACE OF A PICK — one layout, int for int on both apps (the add-to-pick pin, 2026-10-16 §4;
 * Michael 10-12: "Let the photo/video/note show on the pick."). iOS twin: LedgerCore
 * `PickFaceLayout.swift`. PURE: no android.graphics — text sizes come from a [Measure] (the real
 * fonts on the device, [FakeMeasure] in the golden), and every number is an Int, so the two apps
 * can be held to one byte-identical golden (add-to-pick.golden, the panel-seats precedent).
 *
 * Top to bottom: the big “ → the passage → the typed note → the handwriting → the picture band →
 * the chips → the footer. Words stack DOWN from the top; the band and the chips stack UP from the
 * footer, so spare room falls between the words and the picture. The big “ is drawn in every
 * version, so the look never changes when something is added.
 *
 * Landscape (1280×720) puts a visual in the RIGHT column (x 768..1280, full height, full bleed); the
 * words keep their column (x 64..704, the whole 1152 when there is no visual).
 *
 * THE FIT LADDER — a fixed shape never changes size because of an add. Stage by stage, the first
 * stage at which the passage fits at or above its floor wins (each stage keeps every reduction
 * before it):
 *   0 nothing given · 1 the passage steps down · 2 the band shrinks to its minimum · 3 the
 *   handwriting shrinks to its minimum · 4 the note steps down · 5 the band folds into a chip in the
 *   chip row (📷 / ▶ 0:41, still tappable) · 6 the note ends in "…" (the whole note is on the Log).
 * At every stage the passage takes the LARGEST ladder size that fits; from stage 4 the note takes
 * the largest ladder size that lets the passage fit at its floor, then the passage takes the
 * largest size that fits beside it; stage 6 keeps the note at its floor and cuts it so the passage
 * fits at its floor. Adds never cut the passage: a passage that overflows ALONE at its floor keeps
 * today's behaviour (the card grows, to 2600). Free: as tall as it needs, 560–2600; past 2600 the
 * same ladder runs at H = 2600.
 */
object PickFaceLayout {

    enum class Shape(val key: String, val w: Int, val h: Int) {
        FREE("free", 1080, 0), SQUARE("square", 1080, 1080), PORTRAIT("portrait", 1080, 1350),
        LANDSCAPE("landscape", 1280, 720), STORY("story", 1080, 1920);

        companion object {
            fun of(key: String?): Shape = entries.firstOrNull { it.key == key } ?: FREE
        }
    }

    enum class Font { SERIF, SERIF_BOLD, SERIF_ITALIC, SANS_BOLD, SANS_ITALIC }

    /** How long text is on this device (px, ints). */
    interface Measure {
        /** The height of [text] wrapped to [width] at [size], [spacing] added per line. */
        fun height(text: String, size: Int, font: Font, width: Int, spacing: Int): Int
        /** The width of [text] on one line. */
        fun width(text: String, size: Int, font: Font): Int
        /** One line's height at [size] (a pill's text). */
        fun lineHeight(size: Int, font: Font): Int
    }

    /** The golden's measurer (pin §8) — ints only, the same answer on both apps. A char is a
     *  Unicode scalar (code point), which is what Swift's `unicodeScalars.count` counts. */
    object FakeMeasure : Measure {
        fun chars(text: String): Int = text.codePointCount(0, text.length)
        override fun height(text: String, size: Int, font: Font, width: Int, spacing: Int): Int {
            if (text.isEmpty() || width <= 0) return 0
            val charW = (size + 1) / 2
            val lines = (chars(text) * charW + width - 1) / width
            return lines * ((size * 5 + 2) / 4 + spacing)
        }
        override fun width(text: String, size: Int, font: Font): Int = chars(text) * ((size + 1) / 2)
        override fun lineHeight(size: Int, font: Font): Int = (size * 5 + 2) / 4
    }

    /** What a slot holds — the wire's `faceSlots[].k`. */
    enum class Kind(val key: String) {
        PHOTO("photo"), VIDEO("video"), VOICE("voice"), INK("ink");

        companion object {
            fun of(key: String?): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    /** One thing on the pick: its attachment id, its pixel size (photos, video frames, the trimmed
     *  ink) and its length in seconds (voice, video). */
    data class Piece(val kind: Kind, val id: String, val w: Int = 0, val h: Int = 0, val seconds: Int = 0)

    data class Input(
        val shape: Shape,
        val passage: String,
        val note: String = "",
        /** The newest handwriting, trimmed to its ink + 16 margin. */
        val ink: Piece? = null,
        /** Your photos and videos, newest first. */
        val visuals: List<Piece> = emptyList(),
        /** Your voice notes, newest first. */
        val voices: List<Piece> = emptyList(),
        /** The selection's own picture — shown only when there is nothing of yours (the caller
         *  passes it only when the face's `picture` is on). */
        val picture: Piece? = null,
        val source: String = "",
        val author: String = "",
        /** A book card's cover (kept off when a band is shown). */
        val cover: Piece? = null,
    )

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
        fun contains(px: Int, py: Int): Boolean = px >= x && px < x + w && py >= y && py < y + h
        override fun toString(): String = "$x,$y,$w,$h"
    }

    data class TextBlock(val text: String, val size: Int, val box: Box, val truncated: Boolean = false)

    /** A picture in the band (or the Landscape column): the ▶ disc and its length pill on a
     *  video, the "+N" pill on the second tile when there are more. */
    data class Tile(
        val piece: Piece, val box: Box,
        val disc: Box? = null, val pill: Box? = null, val pillText: String = "",
        val more: Box? = null, val moreText: String = "",
    )

    /** A chip: 🎤 m:ss, a folded band (📷 / ▶ m:ss), or "+N". [piece] null for "+N". */
    data class Chip(val label: String, val box: Box, val piece: Piece?)

    data class Slot(val kind: Kind, val id: String, val box: Box)

    data class Footer(
        val x: Int, val width: Int,
        val divider: Box?, val cover: Box?,
        val sourceSize: Int, val sourceBaseline: Int,
        val authorSize: Int, val authorBaseline: Int,
    )

    data class Layout(
        val shape: Shape, val w: Int, val h: Int, val pad: Int,
        val markSize: Int, val markX: Int, val markBaseline: Int,
        val passage: TextBlock, val note: TextBlock?, val ink: Box?,
        val tiles: List<Tile>, val chips: List<Chip>, val footer: Footer,
        /** The fit-ladder stage the layout settled at (0 = nothing given). */
        val step: Int,
        /** Even the last stage didn't make it fit (the words run under the bottom stack). */
        val overflow: Boolean,
        val slots: List<Slot>,
        /** The words' width. */
        val textWidth: Int,
        /** Line spacing the passage and the note were measured with. */
        val passageSpacing: Int, val noteSpacing: Int,
        val chipText: Int, val chipRing: Int, val discRing: Int, val pillText: Int,
        val triangle: Box?, val radius: Int,
    )

    /** The ladder of passage sizes from [start] down to [floor] (pin §4: size −= max(1, round 6%)). */
    fun ladder(start: Int, floor: Int): List<Int> {
        val out = mutableListOf(start)
        var s = start
        while (s > floor) {
            s = maxOf(floor, s - maxOf(1, (s * 6 + 50) / 100))
            out += s
        }
        return out
    }

    /** m:ss, the tape counter. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Per-mille of [total] (int, half up) — the wire's `faceSlots[].r`. */
    fun perMille(v: Int, total: Int): Int = if (total <= 0) 0 else ((v.toLong() * 1000 + total / 2) / total).toInt()

    fun perMille(b: Box, w: Int, h: Int): List<Int> =
        listOf(perMille(b.x, w), perMille(b.y, h), perMille(b.w, w), perMille(b.h, h))

    private fun cp(s: String) = s.codePointCount(0, s.length)

    fun layout(input: Input, m: Measure): Layout {
        val L = input.shape == Shape.LANDSCAPE
        fun s(v: Int) = if (L) (v * 2 + 1) / 3 else v
        val shape = input.shape
        val W = shape.w
        val pad = s(96)
        val long = cp(input.passage) > 220
        val markSize = s(if (long) 180 else 240)
        val markBaseline = pad + (markSize * 72 + 50) / 100
        val bodyTop = pad + markSize / 2 + s(24)

        val pLadder = ladder(s(if (long) 48 else 60), s(30))
        val pFloor = pLadder.last()
        val pSpacing = s(12)
        val nLadder = ladder(s(40), s(30))
        val nSpacing = s(8)
        val nGap = s(36)
        val inkGap = s(24)
        val inkMin = s(160); val inkMax = s(360)
        val chipH = s(64); val chipText = s(32); val chipPad = s(22); val chipGap = s(16); val chipAbove = s(24)
        val footerBand = s(120)
        val wordsFooterGap = s(48)
        val wordsStackGap = s(36)

        val visuals = input.visuals.ifEmpty { listOfNotNull(input.picture) }
        val colX = W * 3 / 5
        val textW = when {
            L && visuals.isNotEmpty() -> colX - 2 * pad
            else -> W - 2 * pad
        }
        val note = input.note.trim()
        val passage = input.passage.trim()

        // ── The band (not Landscape: there the column carries the pictures) ──
        val inBand = !L && visuals.isNotEmpty()
        val bandRange = when (shape) {
            Shape.SQUARE -> 240..420
            Shape.PORTRAIT -> 300..560
            Shape.STORY -> 360..900
            else -> 300..720
        }
        val tilesN = minOf(2, visuals.size)
        val tileW = if (tilesN <= 1) textW else (textW - 24) / 2
        fun natural(p: Piece, width: Int, range: IntRange): Int =
            (if (p.w <= 0 || p.h <= 0) range.first else ((width.toLong() * p.h + p.w / 2) / p.w).toInt())
                .coerceIn(range.first, range.last)
        val bandNatural = if (!inBand) 0 else visuals.take(tilesN).maxOf { natural(it, tileW, bandRange) }
        val bandMin = bandRange.first

        // ── The handwriting ──
        val ink = input.ink
        val inkNatural = ink?.let { natural(it, textW, inkMin..inkMax) } ?: 0

        // ── Chips ──
        fun chipLabel(p: Piece): String = when (p.kind) {
            Kind.VOICE -> "🎤 ${clock(p.seconds)}"
            Kind.VIDEO -> "▶ ${clock(p.seconds)}"
            Kind.PHOTO -> "📷"
            Kind.INK -> "✍"
        }
        fun chipList(folded: Boolean): List<Pair<String, Piece?>> {
            val cands = (if (folded) listOf(visuals.first()) else emptyList()) + input.voices
            val shown = cands.take(2)
            val extra = cands.size - shown.size + (if (folded) visuals.size - 1 else 0)
            return shown.map { chipLabel(it) to it } + (if (extra > 0) listOf("+$extra" to null) else emptyList())
        }

        data class St(val p: Int, val n: Int, val band: Int, val inkH: Int, val folded: Boolean,
                      val noteText: String, val truncated: Boolean)

        fun hasBand(st: St) = inBand && !st.folded
        fun hasChips(st: St) = chipList(st.folded).isNotEmpty()
        fun stackH(st: St): Int =
            (if (hasChips(st)) chipAbove + chipH else 0) +
                (if (hasBand(st)) (if (hasChips(st)) chipGap else chipAbove) + st.band else 0)
        fun pH(size: Int) = m.height(passage, size, Font.SERIF, textW, pSpacing)
        fun nH(st: St) = if (st.noteText.isEmpty()) 0 else nGap + m.height(st.noteText, st.n, Font.SERIF_ITALIC, textW, nSpacing)
        fun wordsH(st: St) = pH(st.p) + nH(st) + (if (ink != null) inkGap + st.inkH else 0)
        fun gapBelow(st: St) = if (stackH(st) > 0) wordsStackGap + stackH(st) else wordsFooterGap
        fun needed(st: St) = bodyTop + wordsH(st) + gapBelow(st) + footerBand + pad
        fun fits(st: St, H: Int) = needed(st) <= H

        val base = St(pLadder.first(), nLadder.first(), bandNatural, inkNatural, false, note, false)

        // The height: Free grows to what it needs; a fixed shape keeps its own, unless the passage
        // ALONE overflows at its floor (today's behaviour: the card grows, to 2600).
        val H: Int = when {
            shape == Shape.FREE -> needed(base).coerceIn(560, 2600)
            else -> {
                val alone = bodyTop + pH(pFloor) + wordsFooterGap + footerBand + pad
                if (alone > shape.h) alone.coerceAtMost(2600) else shape.h
            }
        }

        // ── The ladder ──
        fun largestP(st: St): St? = pLadder.firstOrNull { fits(st.copy(p = it), H) }?.let { st.copy(p = it) }
        var step = 0
        var overflow = false
        var chosen: St? = if (fits(base, H)) base else null
        if (chosen == null) {
            for (stage in 1..6) {
                val st = base.copy(
                    band = if (stage >= 2) bandMin else bandNatural,
                    inkH = if (stage >= 3 && ink != null) inkMin else inkNatural,
                    folded = stage >= 5 && inBand,
                )
                val found: St? = when {
                    stage <= 3 -> largestP(st)
                    stage <= 5 -> if (note.isEmpty()) largestP(st) else nLadder.firstOrNull {
                        fits(st.copy(p = pFloor, n = it), H)
                    }?.let { n -> largestP(st.copy(n = n)) }
                    else -> {
                        if (note.isEmpty()) null else {
                            val at = st.copy(p = pFloor, n = nLadder.last())
                            // The longest opening of the note (+ "…") that lets the passage fit at
                            // its floor; nothing of it when even an empty note won't.
                            val chars = note.codePoints().toArray()
                            var best: St? = null
                            var lo = 0; var hi = chars.size - 1
                            while (lo <= hi) {
                                val mid = (lo + hi) / 2
                                val cut = String(chars, 0, mid).trimEnd() + "…"
                                val tryIt = at.copy(noteText = cut, truncated = true)
                                if (fits(tryIt, H)) { best = tryIt; lo = mid + 1 } else hi = mid - 1
                            }
                            best ?: at.copy(noteText = "", truncated = true).takeIf { fits(it, H) }
                        }
                    }
                }
                if (found != null) { chosen = found; step = stage; break }
            }
        }
        val st = chosen ?: run {
            overflow = true; step = 6
            base.copy(p = pFloor, n = nLadder.last(), band = bandMin, inkH = if (ink != null) inkMin else 0,
                folded = inBand, noteText = "", truncated = note.isNotEmpty())
        }

        // ── Positions ──
        var y = bodyTop
        val passageBlock = TextBlock(passage, st.p, Box(pad, y, textW, pH(st.p)))
        y += passageBlock.box.h
        val noteBlock = if (st.noteText.isEmpty()) null else {
            y += nGap
            val h = m.height(st.noteText, st.n, Font.SERIF_ITALIC, textW, nSpacing)
            TextBlock(st.noteText, st.n, Box(pad, y, textW, h), st.truncated).also { y += h }
        }
        val inkBox = ink?.let { p ->
            y += inkGap
            val boxH = st.inkH
            val fit = if (p.w <= 0 || p.h <= 0) Box(pad, y, textW, boxH)
            else if (p.w.toLong() * boxH >= p.h.toLong() * textW)
                Box(pad, y, textW, ((textW.toLong() * p.h + p.w / 2) / p.w).toInt())
            else Box(pad, y, ((boxH.toLong() * p.w + p.h / 2) / p.h).toInt(), boxH)
            y += boxH
            fit
        }

        // Slots read top to bottom: the handwriting, the pictures, the chips.
        val slots = mutableListOf<Slot>()
        val chipSlots = mutableListOf<Slot>()
        if (ink != null && inkBox != null && ink.id.isNotBlank()) slots += Slot(Kind.INK, ink.id, inkBox)

        // The bottom stack, up from the footer.
        val footerTop = H - pad - footerBand
        var cur = footerTop
        val chips = mutableListOf<Chip>()
        val chipDefs = chipList(st.folded)
        if (chipDefs.isNotEmpty()) {
            cur -= chipAbove
            val top = cur - chipH
            var x = pad
            for ((label, piece) in chipDefs) {
                val w = m.width(label, chipText, Font.SANS_BOLD) + 2 * chipPad
                val b = Box(x, top, w, chipH)
                chips += Chip(label, b, piece)
                if (piece != null && piece.id.isNotBlank()) chipSlots += Slot(piece.kind, piece.id, b)
                x += w + chipGap
            }
            cur = top
        }
        val tiles = mutableListOf<Tile>()
        fun tileFor(p: Piece, b: Box, more: Int): Tile {
            var disc: Box? = null; var pill: Box? = null; var pillText = ""
            if (p.kind == Kind.VIDEO) {
                val r = s(144) / 2
                disc = Box(b.x + b.w / 2 - r, b.y + b.h / 2 - r, 2 * r, 2 * r)
                pillText = clock(p.seconds)
                val pw = m.width(pillText, s(30), Font.SANS_BOLD) + 2 * s(12)
                val ph = m.lineHeight(s(30), Font.SANS_BOLD) + 2 * s(6)
                pill = Box(b.right - s(16) - pw, b.bottom - s(16) - ph, pw, ph)
            }
            var moreBox: Box? = null; var moreText = ""
            if (more > 0) {
                moreText = "+$more"
                val pw = m.width(moreText, s(32), Font.SANS_BOLD) + 2 * s(12)
                val ph = m.lineHeight(s(32), Font.SANS_BOLD) + 2 * s(6)
                moreBox = Box(b.right - s(16) - pw, b.y + s(16), pw, ph)
            }
            if (p.id.isNotBlank()) slots += Slot(p.kind, p.id, b)
            return Tile(p, b, disc, pill, pillText, moreBox, moreText)
        }
        val more = (visuals.size - 2).coerceAtLeast(0)
        if (hasBand(st)) {
            cur -= if (chipDefs.isNotEmpty()) chipGap else chipAbove
            val top = cur - st.band
            if (tilesN == 1) tiles += tileFor(visuals[0], Box(pad, top, textW, st.band), 0)
            else {
                tiles += tileFor(visuals[0], Box(pad, top, tileW, st.band), 0)
                tiles += tileFor(visuals[1], Box(pad + tileW + 24, top, tileW, st.band), more)
            }
        }
        if (L && visuals.isNotEmpty()) {
            val colW = W - colX
            if (tilesN == 1) tiles += tileFor(visuals[0], Box(colX, 0, colW, H), 0)
            else {
                val h1 = (H - 8) / 2
                tiles += tileFor(visuals[0], Box(colX, 0, colW, h1), 0)
                tiles += tileFor(visuals[1], Box(colX, h1 + 8, colW, H - h1 - 8), more)
            }
        }

        slots += chipSlots

        // The footer.
        val footerY = H - pad
        val footer = if (L) Footer(pad, textW, null, null, s(40), H - 64, 0, 0)
        else {
            val withAuthor = input.author.isNotBlank()
            val cover = input.cover?.takeIf { tiles.isEmpty() && it.w > 0 && it.h > 0 }?.let { c ->
                val ch = ((150L * c.h + c.w / 2) / c.w).toInt().coerceAtLeast(1)
                Box(pad, footerY - ch, 150, ch)
            }
            val x = if (cover != null) pad + 150 + 28 else pad
            Footer(x, W - x - pad, if (cover == null) Box(pad, footerY - 92, 108, 8) else null, cover,
                40, footerY - (if (withAuthor) 56 else 30),
                if (withAuthor) 36 else 0, if (withAuthor) footerY - 12 else 0)
        }

        val triW = s(52); val triH = s(60)
        return Layout(
            shape = shape, w = W, h = H, pad = pad,
            markSize = markSize, markX = pad, markBaseline = markBaseline,
            passage = passageBlock, note = noteBlock, ink = inkBox,
            tiles = tiles, chips = chips, footer = footer,
            step = step, overflow = overflow, slots = slots, textWidth = textW,
            passageSpacing = pSpacing, noteSpacing = nSpacing,
            chipText = chipText, chipRing = s(3), discRing = s(4), pillText = s(30),
            triangle = Box(s(6), 0, triW, triH), radius = 16,
        )
    }
}
