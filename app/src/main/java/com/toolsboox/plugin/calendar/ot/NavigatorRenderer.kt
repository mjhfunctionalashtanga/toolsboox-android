package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.toolsboox.R
import com.toolsboox.ot.Creator

/**
 * Shared renderer for the navigator strip that sits at the top of every calendar
 * page (Day, Week, Month, Quarter, Year). Clean white background, single bottom
 * underline, Atkinson Hyperlegible font, thin separators between slots.
 *
 * Each navigator builds a list of [Slot]s with one marked [Emphasis.FOCAL]
 * (the granularity currently being viewed). The focal slot renders larger and
 * bolder; the rest are normal or muted depending on relevance.
 */
object NavigatorRenderer {

    enum class Emphasis { FOCAL, NORMAL, MUTED }

    data class Slot(
        val text: String,
        val emphasis: Emphasis = Emphasis.NORMAL,
        val hasPages: Boolean = false,
        val noteCount: Int = 0,
    )

    fun render(context: Context, canvas: Canvas, slots: List<Slot>,
               /** A single glyph for the strip's far-left corner — the margin the retired
                *  header hamburger used to float over, free real estate since the rail took
                *  the ☰ (Michael: "we can use that space now"). The day strip passes the moon
                *  phase; null leaves the corner quiet. */
               cornerBadge: String? = null) {
        canvas.drawRect(0.0f, 0.0f, 1404.0f, 140.4f, Creator.fillWhite)

        if (cornerBadge != null) {
            val badgePaint = TextPaint().apply {
                textAlign = Paint.Align.CENTER; textSize = 58f; isAntiAlias = true
                color = Color.BLACK
            }
            // Centered in the 0–100 gutter left of the ‹ caret (which sits at 120) — the exact
            // footprint the old floating ☰ occupied, so nothing else moves.
            canvas.drawText(cornerBadge, 52f, 98f, badgePaint)
        }

        val atkinsonBold = try {
            ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_bold)
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
        val atkinsonReg = try {
            ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible_regular)
        } catch (_: Exception) {
            Typeface.DEFAULT
        }

        // Honour the reader's chosen font. SYSTEM/null keeps Atkinson, which the strip was designed
        // around; any other choice supplies both the regular face and a bold derived from it.
        val chosen = com.toolsboox.ot.LedgerFonts.typeface(context)
        val boldFace = if (chosen != null) Typeface.create(chosen, Typeface.BOLD) else atkinsonBold
        val regFace = chosen ?: atkinsonReg

        // Slot geometry FIRST, because the type is sized to fit it rather than the other way
        // round. The strip's footprint is fixed by design (1404 wide, no reflow), so the width a
        // label gets is a constant we solve against — not something the dial can negotiate with.
        val slotsLeft = 175f
        val slotsRight = 1264f
        val slotWidth = (slotsRight - slotsLeft) / slots.size
        val slotGap = 12f
        val maxLabelWidth = slotWidth - slotGap * 2f

        // The nav's own size dial — what you ASKED for.
        val requested = com.toolsboox.ot.ModalScale.stripScale(context)

        val focalBase = 92f
        val normalBase = 68f
        val mutedBase = 56f
        fun baseFor(e: Emphasis) = when (e) {
            Emphasis.FOCAL -> focalBase
            Emphasis.NORMAL -> normalBase
            Emphasis.MUTED -> mutedBase
        }

        // …and what actually FITS. The largest step is capped by measurement, not by a constant:
        // shrink the whole strip together until every label clears its slot.
        //
        // The dial used to hand its number straight to the paints, so Expanded sized the type past
        // a box that cannot grow and the labels went to "(…)" — the almanac header must never do
        // that, at any setting. Solving for the fit makes overflow unreachable instead of merely
        // unlikely, and it keeps holding when the numbers underneath change: a different reading
        // font (LedgerFonts lets you pick one), a locale whose short month is four letters, or a
        // strip built with a different number of slots would each invalidate a hardcoded ceiling.
        //
        // UNIFORM, deliberately — one scale for all six slots. Shrinking only the offenders makes
        // a strip of mismatched type where the sizes no longer mean emphasis, which is a worse
        // read than a strip that is honestly one step smaller than you asked for.
        val probe = TextPaint().apply { isAntiAlias = true }
        fun everythingFits(scale: Float): Boolean = slots.all { s ->
            probe.typeface = if (s.emphasis == Emphasis.FOCAL) boldFace else regFace
            probe.textSize = baseFor(s.emphasis) * scale
            probe.measureText(s.text) <= maxLabelWidth
        }
        var sizeScale = requested
        // 0.6 is the floor: below that the strip stops being readable at arm's length, and a label
        // that still doesn't fit there is pathological enough to earn the ellipsis backstop below.
        while (sizeScale > 0.6f && !everythingFits(sizeScale)) sizeScale -= 0.02f

        val focalPaint = TextPaint().apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = focalBase * sizeScale
            typeface = boldFace; isAntiAlias = true
        }
        val normalPaint = TextPaint().apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = normalBase * sizeScale
            typeface = regFace; isAntiAlias = true
        }
        val mutedPaint = TextPaint().apply {
            color = Color.argb(180, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = mutedBase * sizeScale
            typeface = regFace; isAntiAlias = true
        }
        val arrowPaint = TextPaint().apply {
            color = Color.argb(180, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 76f * sizeScale
            typeface = boldFace; isAntiAlias = true
        }
        val separator = Paint().apply {
            color = Color.argb(80, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
        }
        // The vibe's accent, kept to the strip's chrome only: the underline stroke and the
        // pages marker. The text stays black — on a colour panel the accent is a quiet signature,
        // on a monochrome Boox it resolves to a gray, and either way it's a solid (no alpha, which
        // dithers into mud on e-ink).
        val accent = com.toolsboox.ot.LedgerTheme.accent(context)
        val underline = Paint().apply {
            color = accent; strokeWidth = 2.0f; style = Paint.Style.STROKE
        }
        val markerFill = Paint().apply {
            color = accent; style = Paint.Style.FILL; isAntiAlias = true
        }

        val rowCenterY = 98f
        // The "<" sits at 170, not 60: the almanac pages float the ▦ hub button over the strip's
        // left edge, and on a narrow panel (Tab Mini C) a 60px caret vanished underneath it —
        // along with most of its tap zone. 150 skims just clear of the button on the Mini C (measured on a screenshot) without exiling the caret to the middle distance.
        canvas.drawText("<", 120f, rowCenterY, arrowPaint)
        canvas.drawText(">", 1344f, rowCenterY, arrowPaint)

        // (Slot geometry and the fitted scale are computed above, before the paints — the type is
        // sized to the slots, not the slots to the type.)

        for ((idx, slot) in slots.withIndex()) {
            val cx = slotsLeft + slotWidth * (idx + 0.5f)
            val paint = when (slot.emphasis) {
                Emphasis.FOCAL -> focalPaint
                Emphasis.NORMAL -> normalPaint
                Emphasis.MUTED -> mutedPaint
            }
            // EVERY label shrinks to fit rather than ellipsize. This started as a focal-only rule
            // ("2026" promoted to focal came out as "…", which filters a year down to three dots),
            // but the same thing happens to the other two the moment the modal-size dial goes to
            // Expanded: `sizeScale` grows the type 25% inside a strip whose 1404 footprint and even
            // slot division never reflow, so "September 2026" turned into "(…)" and the whole top
            // nav read as unreadable. That is the failure ScreenFragment already names — text that
            // grows inside a fixed box only ellipsizes, which reads as the setting doing nothing.
            //
            // Shrinking keeps the words. A long label at Expanded may land back near its Standard
            // size, so the dial looks like it did little THERE — but a slot that still says
            // "September 2026" beats one that says "…", and the short labels around it do grow.
            // Floors stay ordered focal > normal > muted so the hierarchy survives the squeeze.
            val minSize = when (slot.emphasis) {
                Emphasis.FOCAL -> 40f
                Emphasis.NORMAL -> 38f
                Emphasis.MUTED -> 34f
            }
            var drawPaint = paint
            if (paint.measureText(slot.text) > maxLabelWidth) {
                drawPaint = TextPaint(paint)
                while (drawPaint.textSize > minSize && drawPaint.measureText(slot.text) > maxLabelWidth) {
                    drawPaint.textSize -= 4f
                }
            }
            val label =
                if (drawPaint.measureText(slot.text) <= maxLabelWidth) slot.text
                else android.text.TextUtils.ellipsize(
                    slot.text, drawPaint, maxLabelWidth, android.text.TextUtils.TruncateAt.END
                ).toString()
            canvas.drawText(label, cx, rowCenterY, drawPaint)

            if (idx > 0) {
                val sx = slotsLeft + slotWidth * idx
                canvas.drawLine(sx, 30f, sx, 110f, separator)
            }
            if (slot.hasPages) {
                Creator.drawTriangle(canvas, cx - slotWidth * 0.4f, 20f, 14f, markerFill)
            }
            if (slot.noteCount > 0) {
                Creator.notesDots(canvas, cx - slotWidth * 0.4f, 130f, 4.0f, slot.noteCount)
            }
        }

        canvas.drawLine(0.0f, 138.4f, 1404.0f, 138.4f, underline)

        // The underline thickens under the FOCAL slot — the strip's one honest answer to "what
        // am I looking at?". On a calendar page that's the page's own period; on a filtering
        // surface it's the ACTIVE filter, so choosing 2026 moves this bar under the year slot
        // instead of leaving the strip reading like today's date. A strip with no focal slot
        // (a surface anchored but not filtered) draws no bar — nothing is claiming the focus.
        val focalIdx = slots.indexOfFirst { it.emphasis == Emphasis.FOCAL }
        if (focalIdx >= 0) {
            val focalBar = Paint().apply {
                color = accent; strokeWidth = 7.0f; style = Paint.Style.STROKE
            }
            val sx = slotsLeft + slotWidth * focalIdx + slotGap
            val ex = slotsLeft + slotWidth * (focalIdx + 1) - slotGap
            canvas.drawLine(sx, 135.0f, ex, 135.0f, focalBar)
        }
    }

    // Hit-testing geometry — MUST stay in sync with render()'s slot layout above.
    const val SLOTS_LEFT = 175.0f
    const val SLOTS_RIGHT = 1264.0f
    const val ARROW_PREV = -1
    const val ARROW_NEXT = -2

    /**
     * Which slot the x-coordinate (in the 1404-wide navigator space) falls on,
     * matching the even slot layout [render] draws. Returns a 0-based slot index,
     * or [ARROW_PREV] / [ARROW_NEXT] for the `<` / `>` arrow zones. This replaces
     * the old fixed 20-cell ladder hit-testing, which no longer matched the drawn
     * strip (so slots like a quarter page's "year" were untappable).
     */
    fun slotAt(px: Float, slotCount: Int): Int {
        if (slotCount <= 0 || px < SLOTS_LEFT) return ARROW_PREV
        if (px > SLOTS_RIGHT) return ARROW_NEXT
        val w = (SLOTS_RIGHT - SLOTS_LEFT) / slotCount
        return ((px - SLOTS_LEFT) / w).toInt().coerceIn(0, slotCount - 1)
    }
}
