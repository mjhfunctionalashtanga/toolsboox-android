package com.toolsboox.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pictures you can put down — the pictorial sibling of [ShapeLibrary].
 *
 * "Clippings" was a misunderstanding. It shipped as an empty shelf whose only door was "save a
 * gram to it", so the only way to fill it was to put your own material in — which made it a second,
 * invisible Pickings. What was wanted was reusable clip art: a leaf, a sun, a book, a banner, the
 * sort of thing you stamp beside a heading without having to draw it on e-ink with a stylus.
 *
 * Every piece is a PATH described in a UNIT SQUARE and scaled at draw time — the same coordinates
 * the iPad's `ClipArtPalette` uses, which is what lets both devices draw the identical set from one
 * description rather than two sets of assets drifting apart. Nothing ships in the APK.
 *
 * Stroked, never filled, pure black on transparency, round caps and joins: these go over
 * handwriting and beside words, and a filled sticker would bury what's underneath. Line weight
 * scales with the size so a big one and a small one look like the same pen drew them.
 */
object ClipArtLibrary {

    /** One piece: its key, the label the picker shows, and which drawer it lives in. */
    data class Art(val key: String, val label: String, val group: String)

    val ALL = listOf(
        Art("leaf", "Leaf", "Nature"),
        Art("sprout", "Sprout", "Nature"),
        Art("tree", "Tree", "Nature"),
        Art("flower", "Flower", "Nature"),
        Art("sun", "Sun", "Nature"),
        Art("moon", "Moon", "Nature"),
        Art("cloud", "Cloud", "Nature"),
        Art("wave", "Wave", "Nature"),
        Art("mountain", "Mountain", "Nature"),
        Art("lotus", "Lotus", "Practice"),
        Art("spiral", "Spiral", "Practice"),
        Art("candle", "Candle", "Practice"),
        Art("bowl", "Bowl", "Practice"),
        Art("book", "Book", "Study"),
        Art("bookmark", "Bookmark", "Study"),
        Art("quill", "Quill", "Study"),
        Art("bulb", "Idea", "Study"),
        Art("key", "Key", "Study"),
        Art("arrow", "Arrow", "Marks"),
        Art("star", "Star", "Marks"),
        Art("banner", "Banner", "Marks"),
        Art("bubble", "Speech", "Marks"),
        Art("heart", "Heart", "Marks"),
        Art("bracket", "Bracket", "Marks")
    )

    val GROUPS = listOf("Nature", "Practice", "Study", "Marks")

    /** Aspect ratio (w/h) each piece wants — most are square, a few read as bands. */
    private fun aspect(key: String): Float = when (key) {
        "banner", "wave" -> 2.1f
        "bubble", "arrow" -> 1.5f
        "bookmark" -> 0.6f
        else -> 1f
    }

    /**
     * Draw [key] at [width] px wide (height follows its aspect), transparent behind.
     *
     * [weight] multiplies the line, so a piece meant as a frame can be drawn heavier than one
     * meant as a mark among words.
     */
    fun bitmap(key: String, width: Int = 420, weight: Float = 1f): Bitmap {
        val w = width.coerceAtLeast(24)
        val h = (w / aspect(key)).toInt().coerceAtLeast(24)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val inset = w * 0.04f
        val box = RectF(inset, inset, w - inset, h - inset)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF000000.toInt()
            strokeWidth = (minOf(w, h) / 34f * weight).coerceAtLeast(2.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(pathFor(key, box), paint)
        return out
    }

    /** The unit-square description, mapped into [b]. Mirrors ClipArtPalette.unitPath on iOS. */
    private fun pathFor(key: String, b: RectF): Path {
        val p = Path()
        fun x(u: Float) = b.left + u * b.width()
        fun y(v: Float) = b.top + v * b.height()
        fun m(u: Float, v: Float) = p.moveTo(x(u), y(v))
        fun l(u: Float, v: Float) = p.lineTo(x(u), y(v))
        fun q(u: Float, v: Float, cu: Float, cv: Float) = p.quadTo(x(cu), y(cv), x(u), y(v))
        fun circle(cu: Float, cv: Float, r: Float) =
            p.addOval(RectF(x(cu - r), y(cv - r), x(cu + r), y(cv + r)), Path.Direction.CW)

        when (key) {
            "leaf" -> {
                m(0.5f, 0.94f); q(0.5f, 0.06f, 0.04f, 0.56f); q(0.5f, 0.94f, 0.96f, 0.56f)
                m(0.5f, 0.88f); l(0.5f, 0.14f)
                for (i in 0 until 3) {
                    val yy = 0.30f + i * 0.18f
                    m(0.5f, yy); q(0.24f, yy - 0.06f, 0.37f, yy + 0.03f)
                    m(0.5f, yy + 0.09f); q(0.76f, yy + 0.03f, 0.63f, yy + 0.12f)
                }
            }
            "sprout" -> {
                m(0.5f, 0.95f); l(0.5f, 0.42f)
                m(0.5f, 0.60f); q(0.14f, 0.36f, 0.24f, 0.62f); q(0.5f, 0.60f, 0.30f, 0.34f)
                m(0.5f, 0.48f); q(0.86f, 0.24f, 0.76f, 0.50f); q(0.5f, 0.48f, 0.70f, 0.22f)
            }
            "tree" -> {
                m(0.5f, 0.95f); l(0.5f, 0.55f)
                m(0.5f, 0.74f); l(0.30f, 0.60f)
                m(0.5f, 0.68f); l(0.70f, 0.54f)
                circle(0.5f, 0.36f, 0.28f)
            }
            "flower" -> {
                for (i in 0 until 6) {
                    val a = i * PI / 3
                    circle((0.5 + cos(a) * 0.26).toFloat(), (0.5 + sin(a) * 0.26).toFloat(), 0.16f)
                }
                circle(0.5f, 0.5f, 0.11f)
            }
            "sun" -> {
                circle(0.5f, 0.5f, 0.24f)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    m((0.5 + cos(a) * 0.32).toFloat(), (0.5 + sin(a) * 0.32).toFloat())
                    l((0.5 + cos(a) * 0.46).toFloat(), (0.5 + sin(a) * 0.46).toFloat())
                }
            }
            "moon" -> { m(0.62f, 0.08f); q(0.62f, 0.92f, 0.10f, 0.50f); q(0.62f, 0.08f, 0.52f, 0.50f) }
            "cloud" -> {
                m(0.14f, 0.66f); q(0.30f, 0.40f, 0.14f, 0.46f)
                q(0.52f, 0.26f, 0.34f, 0.26f); q(0.74f, 0.40f, 0.72f, 0.24f)
                q(0.86f, 0.66f, 0.92f, 0.48f); l(0.14f, 0.66f)
            }
            "wave" -> for (row in 0 until 2) {
                val yy = 0.40f + row * 0.22f
                m(0.06f, yy)
                q(0.30f, yy, 0.18f, yy - 0.13f); q(0.54f, yy, 0.42f, yy + 0.13f)
                q(0.78f, yy, 0.66f, yy - 0.13f); q(0.94f, yy, 0.86f, yy + 0.13f)
            }
            "mountain" -> {
                m(0.04f, 0.82f); l(0.36f, 0.24f); l(0.56f, 0.56f); l(0.68f, 0.42f); l(0.96f, 0.82f); l(0.04f, 0.82f)
                m(0.25f, 0.44f); l(0.36f, 0.30f); l(0.46f, 0.44f); l(0.40f, 0.40f); l(0.34f, 0.45f); l(0.29f, 0.41f)
            }
            "lotus" -> {
                m(0.5f, 0.80f); q(0.5f, 0.16f, 0.28f, 0.44f); q(0.5f, 0.80f, 0.72f, 0.44f)
                m(0.5f, 0.80f); q(0.10f, 0.50f, 0.16f, 0.74f); q(0.5f, 0.80f, 0.34f, 0.56f)
                m(0.5f, 0.80f); q(0.90f, 0.50f, 0.84f, 0.74f); q(0.5f, 0.80f, 0.66f, 0.56f)
                m(0.16f, 0.80f); q(0.5f, 0.94f, 0.84f, 0.80f)
            }
            "spiral" -> {
                val steps = 160
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val a = t * 3.0 * 2 * PI
                    val r = 0.06 + t * 0.38
                    val ux = (0.5 + cos(a) * r).toFloat()
                    val uy = (0.5 + sin(a) * r).toFloat()
                    if (i == 0) m(ux, uy) else l(ux, uy)
                }
            }
            "candle" -> {
                m(0.34f, 0.92f); l(0.34f, 0.44f); l(0.66f, 0.44f); l(0.66f, 0.92f); l(0.34f, 0.92f)
                m(0.28f, 0.92f); l(0.72f, 0.92f)
                m(0.5f, 0.44f); l(0.5f, 0.36f)
                m(0.5f, 0.36f); q(0.5f, 0.06f, 0.34f, 0.24f); q(0.5f, 0.36f, 0.66f, 0.24f)
            }
            "bowl" -> {
                m(0.14f, 0.46f); q(0.5f, 0.94f, 0.86f, 0.46f)
                p.addOval(RectF(x(0.14f), y(0.38f), x(0.86f), y(0.54f)), Path.Direction.CW)
                m(0.06f, 0.30f); q(0.14f, 0.22f, 0.10f, 0.26f)
                m(0.94f, 0.30f); q(0.86f, 0.22f, 0.90f, 0.26f)
            }
            "book" -> {
                m(0.5f, 0.26f); q(0.08f, 0.22f, 0.28f, 0.16f)
                l(0.08f, 0.76f); q(0.5f, 0.80f, 0.28f, 0.70f)
                m(0.5f, 0.26f); q(0.92f, 0.22f, 0.72f, 0.16f)
                l(0.92f, 0.76f); q(0.5f, 0.80f, 0.72f, 0.70f)
                m(0.5f, 0.26f); l(0.5f, 0.80f)
            }
            "bookmark" -> { m(0.22f, 0.10f); l(0.78f, 0.10f); l(0.78f, 0.90f); l(0.5f, 0.68f); l(0.22f, 0.90f); l(0.22f, 0.10f) }
            "quill" -> {
                m(0.16f, 0.90f); l(0.62f, 0.34f)
                m(0.62f, 0.34f); q(0.86f, 0.10f, 0.58f, 0.12f); q(0.62f, 0.34f, 0.84f, 0.38f)
                for (i in 0 until 3) {
                    val t = 0.20f + i * 0.13f
                    m(0.62f + t * 0.30f, 0.34f - t * 0.30f); l(0.62f + t * 0.10f, 0.34f - t * 0.52f)
                }
            }
            "bulb" -> {
                circle(0.5f, 0.40f, 0.26f)
                m(0.36f, 0.60f); l(0.36f, 0.74f); l(0.64f, 0.74f); l(0.64f, 0.60f)
                m(0.38f, 0.80f); l(0.62f, 0.80f)
                m(0.42f, 0.86f); l(0.58f, 0.86f)
                m(0.42f, 0.44f); q(0.5f, 0.30f, 0.5f, 0.46f); q(0.58f, 0.44f, 0.5f, 0.30f)
            }
            "key" -> {
                circle(0.28f, 0.38f, 0.17f); circle(0.28f, 0.38f, 0.07f)
                m(0.40f, 0.50f); l(0.86f, 0.80f)
                m(0.68f, 0.68f); l(0.62f, 0.80f)
                m(0.78f, 0.74f); l(0.72f, 0.86f)
            }
            "arrow" -> { m(0.06f, 0.50f); l(0.82f, 0.50f); m(0.62f, 0.28f); l(0.90f, 0.50f); l(0.62f, 0.72f) }
            "star" -> {
                for (i in 0 until 5) {
                    val outer = -PI / 2 + i * 2 * PI / 5
                    val inner = outer + PI / 5
                    val ox = (0.5 + cos(outer) * 0.44).toFloat()
                    val oy = (0.5 + sin(outer) * 0.44).toFloat()
                    val ix = (0.5 + cos(inner) * 0.18).toFloat()
                    val iy = (0.5 + sin(inner) * 0.18).toFloat()
                    if (i == 0) m(ox, oy) else l(ox, oy)
                    l(ix, iy)
                }
                p.close()
            }
            "banner" -> {
                m(0.06f, 0.30f); l(0.94f, 0.30f); l(0.94f, 0.70f); l(0.06f, 0.70f); l(0.06f, 0.30f)
                m(0.06f, 0.30f); l(0.00f, 0.36f); l(0.06f, 0.42f)
                m(0.94f, 0.58f); l(1.00f, 0.64f); l(0.94f, 0.70f)
            }
            "bubble" -> {
                p.addRoundRect(RectF(x(0.06f), y(0.12f), x(0.94f), y(0.72f)),
                    b.width() * 0.12f, b.width() * 0.12f, Path.Direction.CW)
                m(0.28f, 0.72f); l(0.24f, 0.94f); l(0.46f, 0.72f)
            }
            "heart" -> {
                m(0.5f, 0.88f)
                q(0.04f, 0.44f, 0.10f, 0.72f); q(0.5f, 0.24f, 0.20f, 0.14f)
                q(0.90f, 0.44f, 0.80f, 0.14f); q(0.5f, 0.88f, 0.96f, 0.72f)
            }
            "bracket" -> {
                m(0.30f, 0.08f); q(0.14f, 0.50f, 0.06f, 0.20f); q(0.30f, 0.92f, 0.06f, 0.80f)
                m(0.70f, 0.08f); q(0.86f, 0.50f, 0.94f, 0.20f); q(0.70f, 0.92f, 0.94f, 0.80f)
            }
        }
        return p
    }
}
