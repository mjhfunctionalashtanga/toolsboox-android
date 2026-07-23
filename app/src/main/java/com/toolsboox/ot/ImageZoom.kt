package com.toolsboox.ot

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Open a picture full-screen and get close to it: pinch to zoom, drag to pan, double-tap to snap
 * between fitted and 2.5×.
 *
 * This is the zoom that belongs on a page of posts. The text around it is re-laid-out larger by
 * [ReadingSize] rather than magnified, because scaling rasterised text on e-ink only makes it
 * fuzzy — but a photograph or a piece of handwriting has real detail in it, and that wants
 * magnifying.
 */
object ImageZoom {

    private const val MIN = 1.0f
    private const val MAX = 8.0f
    private const val DOUBLE_TAP = 2.5f

    /** Show [bitmap] full-screen, zoomable. [caption] is optional chrome along the bottom. */
    fun show(context: Context, bitmap: Bitmap, caption: String = "") {
        val image = ZoomableImageView(context).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(Color.WHITE)
        }

        val holder = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            addView(image, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        if (caption.isNotBlank()) {
            val dp = context.resources.displayMetrics.density
            holder.addView(TextView(context).apply {
                text = caption
                textSize = 13f
                setTextColor(0xFF444444.toInt())
                setBackgroundColor(Color.WHITE)
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM })
        }

        // Tapping outside dismisses, but a tap ON the picture must not — that's how you pan it.
        Dialog(context, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen).apply {
            setContentView(holder)
            show()
        }.also { d -> image.onSingleTapOutsideImage = { d.dismiss() } }
    }

    /**
     * An ImageView that starts fitted and lets you get closer.
     *
     * Matrix-based rather than view-scaled, so the bitmap is resampled at its real resolution when
     * you zoom in — the whole point of looking closer.
     */
    private class ZoomableImageView(context: Context) : ImageView(context) {

        var onSingleTapOutsideImage: (() -> Unit)? = null

        private val matrixValues = FloatArray(9)
        private val current = Matrix()
        private var fitted = Matrix()
        private var lastTouch = PointF()
        private var dragging = false

        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = clampFactor(detector.scaleFactor)
                current.postScale(factor, factor, detector.focusX, detector.focusY)
                clampPan()
                imageMatrix = current
                return true
            }
        })

        private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleOf(current) > MIN * 1.05f) {
                    current.set(fitted)
                } else {
                    val f = DOUBLE_TAP / scaleOf(current)
                    current.postScale(f, f, e.x, e.y)
                }
                clampPan()
                imageMatrix = current
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Only dismiss when it's sitting fitted — once zoomed in, a stray tap should not
                // throw away the position you worked to get to.
                if (scaleOf(current) <= MIN * 1.05f) onSingleTapOutsideImage?.invoke()
                return true
            }
        })

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fitToView()
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            post { fitToView() }
        }

        private fun fitToView() {
            val d = drawable ?: return
            if (width == 0 || height == 0) return
            val scale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (width - d.intrinsicWidth * scale) / 2f,
                    (height - d.intrinsicHeight * scale) / 2f
                )
            }
            fitted = Matrix(m)
            current.set(m)
            imageMatrix = current
        }

        private fun scaleOf(m: Matrix): Float {
            m.getValues(matrixValues)
            val s = matrixValues[Matrix.MSCALE_X]
            val base = fittedScale()
            return if (base == 0f) 1f else s / base
        }

        private fun fittedScale(): Float {
            fitted.getValues(matrixValues)
            return matrixValues[Matrix.MSCALE_X]
        }

        private fun clampFactor(requested: Float): Float {
            val now = scaleOf(current)
            val wanted = now * requested
            return when {
                wanted < MIN -> MIN / now
                wanted > MAX -> MAX / now
                else -> requested
            }
        }

        /** Keep the picture from being dragged off the screen entirely. */
        private fun clampPan() {
            val d = drawable ?: return
            current.getValues(matrixValues)
            val scale = matrixValues[Matrix.MSCALE_X]
            val w = d.intrinsicWidth * scale
            val h = d.intrinsicHeight * scale
            var dx = 0f
            var dy = 0f
            val tx = matrixValues[Matrix.MTRANS_X]
            val ty = matrixValues[Matrix.MTRANS_Y]

            dx = if (w <= width) (width - w) / 2f - tx else tx.coerceIn(width - w, 0f) - tx
            dy = if (h <= height) (height - h) / 2f - ty else ty.coerceIn(height - h, 0f) - ty

            current.postTranslate(dx, dy)
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouch.set(event.x, event.y)
                    dragging = true
                }
                MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                    current.postTranslate(event.x - lastTouch.x, event.y - lastTouch.y)
                    clampPan()
                    imageMatrix = current
                    lastTouch.set(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    /** Make an already-populated ImageView open its own bitmap full-screen when tapped. */
    fun makeTappable(view: ImageView, caption: String = "") {
        view.setOnClickListener {
            val bmp = (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@setOnClickListener
            show(view.context, bmp, caption)
        }
    }

    /** True when [v] is an ImageView carrying a bitmap — used to wire a whole rendered page. */
    fun isZoomable(v: View): Boolean =
        v is ImageView && (v.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap != null
}
