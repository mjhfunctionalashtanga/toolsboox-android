package com.toolsboox.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Simple shapes you can put down.
 *
 * The Clippings library starts empty and only ever fills with things you cut out yourself, so
 * there was never anything basic to stamp — no box to put round a heading, no arrow to join two
 * thoughts, no bracket to gather three lines. Drawing a passable circle freehand on e-ink is
 * a fussy job, and it is not the part anyone wants to be doing.
 *
 * Drawn as paths at request time rather than shipped as assets: they come out crisp at whatever
 * size they are placed, they are a few lines each, and they cost nothing in the APK.
 *
 * Stroked, not filled, and pure black on transparency — these go OVER handwriting, and a filled
 * shape would bury the words underneath. Line weight scales with the shape so a big box and a
 * small one look like the same pen drew them.
 */
object ShapeLibrary {

    /** One offered shape: what it is called and how to draw it. */
    data class Shape(val key: String, val label: String)

    val ALL = listOf(
        Shape("box", "▭  Box"),
        Shape("rounded", "▢  Rounded box"),
        Shape("circle", "◯  Circle"),
        Shape("oval", "⬭  Oval"),
        Shape("triangle", "△  Triangle"),
        Shape("diamond", "◇  Diamond"),
        Shape("star", "☆  Star"),
        Shape("heart", "♡  Heart"),
        Shape("arrow", "→  Arrow"),
        Shape("arrow_bent", "↷  Curved arrow"),
        Shape("line", "—  Line"),
        Shape("brace", "}  Brace"),
        Shape("bracket", "]  Bracket"),
        Shape("banner", "▤  Banner"),
        Shape("cloud", "☁  Thought cloud"),
        Shape("check", "✓  Tick"),
        Shape("cross", "✕  Cross"),
        Shape("burst", "✳  Burst")
    )

    /**
     * Draw [key] at [size] px square, transparent behind.
     *
     * [weight] is a multiplier on the line, so a shape can be drawn heavier when it is meant to
     * be a frame and lighter when it is a mark among words.
     */
    fun bitmap(key: String, size: Int = 480, weight: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        val pad = s * 0.10f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF000000.toInt()
            strokeWidth = (s * 0.022f * weight).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val box = RectF(pad, pad, s - pad, s - pad)

        when (key) {
            "box" -> c.drawRect(box, p)
            "rounded" -> c.drawRoundRect(box, s * 0.10f, s * 0.10f, p)
            "circle" -> c.drawCircle(s / 2f, s / 2f, (s / 2f) - pad, p)
            "oval" -> c.drawOval(RectF(pad, s * 0.24f, s - pad, s * 0.76f), p)
            "triangle" -> c.drawPath(closed(
                s / 2f to pad, s - pad to s - pad, pad to s - pad), p)
            "diamond" -> c.drawPath(closed(
                s / 2f to pad, s - pad to s / 2f, s / 2f to s - pad, pad to s / 2f), p)
            "star" -> c.drawPath(star(s / 2f, s / 2f, (s / 2f) - pad, (s / 2f - pad) * 0.42f), p)
            "heart" -> c.drawPath(heart(s), p)
            "line" -> c.drawLine(pad, s / 2f, s - pad, s / 2f, p)
            "check" -> c.drawPath(open(
                pad to s * 0.55f, s * 0.42f to s - pad * 1.4f, s - pad to pad * 1.4f), p)
            "cross" -> {
                c.drawLine(pad, pad, s - pad, s - pad, p)
                c.drawLine(s - pad, pad, pad, s - pad, p)
            }
            "arrow" -> {
                c.drawLine(pad, s / 2f, s - pad, s / 2f, p)
                c.drawPath(open(
                    s - pad - s * 0.16f to s / 2f - s * 0.14f,
                    s - pad to s / 2f,
                    s - pad - s * 0.16f to s / 2f + s * 0.14f), p)
            }
            "arrow_bent" -> {
                c.drawPath(Path().apply {
                    moveTo(pad, s - pad)
                    cubicTo(pad, s * 0.3f, s * 0.5f, pad, s - pad, pad * 1.6f)
                }, p)
                c.drawPath(open(
                    s - pad - s * 0.18f to pad * 0.9f,
                    s - pad to pad * 1.6f,
                    s - pad - s * 0.16f to s * 0.22f), p)
            }
            "brace" -> c.drawPath(Path().apply {
                val r = s - pad
                moveTo(r, pad)
                cubicTo(s * 0.55f, pad, s * 0.72f, s / 2f, pad, s / 2f)
                cubicTo(s * 0.72f, s / 2f, s * 0.55f, r, r, r)
            }, p)
            "bracket" -> c.drawPath(open(
                s * 0.66f to pad, s - pad to pad, s - pad to s - pad, s * 0.66f to s - pad), p)
            "banner" -> {
                val top = s * 0.30f; val bot = s * 0.70f
                c.drawPath(closed(
                    pad to top, s - pad to top, s - pad to bot, pad to bot), p)
                c.drawPath(open(pad to top, pad + s * 0.10f to s / 2f, pad to bot), p)
                c.drawPath(open(s - pad to top, s - pad - s * 0.10f to s / 2f, s - pad to bot), p)
            }
            "cloud" -> {
                val r = s * 0.15f
                c.drawCircle(s * 0.35f, s * 0.45f, r * 1.35f, p)
                c.drawCircle(s * 0.55f, s * 0.36f, r * 1.6f, p)
                c.drawCircle(s * 0.72f, s * 0.48f, r * 1.2f, p)
                c.drawCircle(s * 0.52f, s * 0.56f, r * 1.5f, p)
                c.drawCircle(s * 0.30f, s * 0.74f, r * 0.34f, p)
                c.drawCircle(s * 0.22f, s * 0.86f, r * 0.22f, p)
            }
            "burst" -> {
                val cx = s / 2f; val cy = s / 2f; val rr = (s / 2f) - pad
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    c.drawLine(
                        cx + (rr * 0.35f * Math.cos(a)).toFloat(),
                        cy + (rr * 0.35f * Math.sin(a)).toFloat(),
                        cx + (rr * Math.cos(a)).toFloat(),
                        cy + (rr * Math.sin(a)).toFloat(), p)
                }
            }
            else -> c.drawRect(box, p)
        }
        return out
    }

    private fun closed(vararg pts: Pair<Float, Float>): Path = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
        close()
    }

    private fun open(vararg pts: Pair<Float, Float>): Path = Path().apply {
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
    }

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + (r * Math.cos(a)).toFloat()
            val y = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun heart(s: Float): Path = Path().apply {
        val cx = s / 2f
        moveTo(cx, s * 0.82f)
        cubicTo(s * 0.08f, s * 0.56f, s * 0.18f, s * 0.14f, cx, s * 0.34f)
        cubicTo(s * 0.82f, s * 0.14f, s * 0.92f, s * 0.56f, cx, s * 0.82f)
        close()
    }
}
