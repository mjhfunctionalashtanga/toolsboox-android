package com.toolsboox.plugin.calendar.ot

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.michaelfilter.da.IntakePageData

/**
 * The MichaelFilter Intake named note page ("intake"), part of the field-ledger
 * swipe cycle right after the gratitude page.
 *
 * Four panels — THE READ / THE WATCH / THE LISTEN / EDUCATE ME — each with a
 * ruled handwriting zone (ink saves under noteStrokes["intake"] like any named
 * page) and a dashed tap-to-type strip at the bottom for cut-and-paste. Typed
 * text renders in place and is dispatched to the mjh.yoga intake endpoint via
 * IntakePageStore. Aesthetic matches the Pickings page (mono headers, thin
 * rules, dashed boxes, plenty of white).
 */
class CalendarDayPageIntake : Creator {

    companion object {

        private const val left = 40f
        private const val right = 1364f
        private const val gap = 36f
        private const val colMidR = (left + right) / 2f - gap / 2f  // 666
        private const val colMidL = (left + right) / 2f + gap / 2f  // 702
        private const val lineSpacing = 60f

        private const val row1Top = 60f
        private const val row1Bottom = 900f
        private const val row2Top = 985f
        private const val row2Bottom = 1825f

        // Height of the tap-to-type strip at the bottom of each panel.
        private const val typedStripHeight = 190f

        /**
         * A panel of the intake page.
         */
        data class IntakePanel(val kindKey: String, val title: String, val rect: RectF) {
            val typedRect: RectF
                get() = RectF(rect.left, rect.bottom - typedStripHeight, rect.right, rect.bottom)
        }

        /**
         * The four panels: READ (top-left), WATCH (top-right),
         * LISTEN (bottom-left), EDUCATE ME (bottom-right).
         */
        val panels = listOf(
            IntakePanel("read", "THE READ", RectF(left, row1Top, colMidR, row1Bottom)),
            IntakePanel("watch", "THE WATCH", RectF(colMidL, row1Top, right, row1Bottom)),
            IntakePanel("listen", "THE LISTEN", RectF(left, row2Top, colMidR, row2Bottom)),
            IntakePanel("educate", "EDUCATE ME", RectF(colMidL, row2Top, right, row2Bottom))
        )

        /**
         * Hit-test a canvas-space point against the tap-to-type strips.
         *
         * @param x canvas x
         * @param y canvas y
         * @return the panel kind key, or null when the point is not on a strip
         */
        fun typedZoneAt(x: Float, y: Float): String? {
            val pad = 20f
            return panels.firstOrNull {
                val r = it.typedRect
                x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad
            }?.kindKey
        }

        /**
         * Draw the intake page template with the typed panel texts in place.
         *
         * @param canvas the canvas
         * @param intakeData the typed content of the day
         */
        fun drawPage(canvas: Canvas, intakeData: IntakePageData) {
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val monoPlain = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)

            val headerPaint = TextPaint().apply {
                color = Color.BLACK; textAlign = Paint.Align.LEFT; textSize = 40f; typeface = monoBold; isAntiAlias = true
            }
            val linePaint = Paint().apply {
                color = Color.argb(140, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val panelBorder = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val dashedBorder = Paint().apply {
                color = Color.argb(100, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
            }
            val hintPaint = TextPaint().apply {
                color = Color.argb(110, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 26f
                typeface = monoPlain; isAntiAlias = true
            }
            val typedPaint = TextPaint().apply {
                color = Color.BLACK; textSize = 26f; typeface = monoPlain; isAntiAlias = true
            }

            for (panel in panels) {
                val r = panel.rect
                val t = panel.typedRect

                // Panel title above the box, pickings-style.
                canvas.drawText(panel.title, r.left, r.top - 14f, headerPaint)

                // Panel border + ruled handwriting zone above the typed strip.
                canvas.drawRect(r, panelBorder)
                var y = r.top + lineSpacing
                while (y < t.top - 12f) {
                    canvas.drawLine(r.left + 14f, y, r.right - 14f, y, linePaint)
                    y += lineSpacing
                }

                // Tap-to-type strip (dashed, like the pickings image boxes).
                canvas.drawRect(t, dashedBorder)

                val typed = intakeData.typedFor(panel.kindKey).trim()
                if (typed.isEmpty()) {
                    canvas.drawText(
                        "TAP TO TYPE / PASTE",
                        (t.left + t.right) / 2f, (t.top + t.bottom) / 2f + 9f, hintPaint
                    )
                } else {
                    // Render the typed text in place, wrapped and clipped to the strip.
                    val pad = 14f
                    val width = (t.width() - 2 * pad).toInt().coerceAtLeast(50)
                    val layout = StaticLayout.Builder
                        .obtain(typed, 0, typed.length, typedPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                    canvas.save()
                    canvas.clipRect(t.left + pad, t.top + pad, t.right - pad, t.bottom - pad)
                    canvas.translate(t.left + pad, t.top + pad)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
        }
    }
}
