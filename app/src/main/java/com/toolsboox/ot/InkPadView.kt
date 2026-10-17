package com.toolsboox.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * The small hand-writing pad used wherever ink is COMPOSED inside a dialog — a reply, a chat
 * message, a card comment, a booking note, the ✍ Handwriting annotation. [InkMount] is its
 * counterpart for ink that is SHOWN.
 *
 * A plain [View] with plain [Canvas] drawing for the RECORD of the ink (a SurfaceView composites
 * through its own window, which doesn't survive a dialog) — but the LIVE stroke is the Boox pen
 * engine's: [TouchHelper] raw drawing is opened over this view's own rect, so the nib lays ink
 * on the panel at hardware speed exactly as the notes page does, and the software path only
 * re-bakes the finished stroke (Michael, 09-12: "the surface is much slower" than the notes
 * pages). Where the engine is absent (finger, Viwoods, a failed create) the software path
 * refreshes only the new segment's box in the fast waveform instead of the whole pad.
 *
 * Each stroke carries its own colour, width and nib, so undo and the colour toolbar behave the
 * way they do on a creation page. Beyond freehand ink the pad also takes PLACED TEXT (typed or
 * pasted, set down where the next tap lands) and DRAGGED SHAPES (box/round/line/arrow) — the
 * page surface's eraser/stylus vocabulary, scaled to a pad (Michael: "can that be made to look
 * more like our current eraser/stylus design").
 */
class InkPadView(context: Context) : android.widget.FrameLayout(context) {

    init { setWillNotDraw(false) }

    // ── PEN PAUSES (Michael 2026-10-11, the one compose screen): a chooser, the keyboard or a
    // suggestion list over the pad must get the pen, not the ink engine — the Bigme daemon hands
    // out pen events by the pad's bounds, not by what is on top. Each reason is named so two can
    // overlap; the pen comes back when the last one lets go. A touch while paused draws nothing
    // and tells the host ([onTouchWhilePaused]), so "put the pen on the page" can drop the keyboard.
    private val penPauses = mutableSetOf<String>()
    /** The pad's own pause: the rest of a touch that landed while paused (see [onTouchEvent]). */
    private val PAUSE_GESTURE = "gesture"
    val penPaused: Boolean get() = penPauses.isNotEmpty()
    var onTouchWhilePaused: (() -> Unit)? = null

    /**
     * May the pad hold an ink client right now? The host's lifecycle gate (Michael 2026-10-11:
     * one client, never one bound after the screen paused — the -304 lesson): a pad whose screen
     * is in the background, or whose page is hidden, must not attach. Default: always.
     */
    var inkAllowed: () -> Boolean = { true }

    fun pausePen(reason: String, paused: Boolean) {
        val was = penPauses.isNotEmpty()
        if (paused) penPauses.add(reason) else penPauses.remove(reason)
        val now = penPauses.isNotEmpty()
        if (was == now) return
        // A stroke caught by the pause ends where it is — its UP will never come.
        if (now && current != null) { current = null; rawStroke = false; strokeBox = null; invalidate() }
        runCatching { bigme?.setInputEnabled(!now) }
        syncRawPen()
    }

    /** Let go of the ink engines (the screen paused, or the page went out of sight). */
    fun releaseInk() {
        penPauses.remove(PAUSE_GESTURE)   // a swallowed touch whose UP never came must not outlive it
        closeRawFully()
        detachBigme()
    }

    /** Take them back — only when the pad is on screen with a size and the host allows it. */
    fun reclaimInk() {
        if (!isAttachedToWindow || width <= 0 || height <= 0 || !inkAllowed()) return
        if (raw == null) raw = try { com.onyx.android.sdk.pen.TouchHelper.create(this, rawCallback) } catch (_: Throwable) { null }
        openRaw()
        attachBigmeIfAvailable()
    }

    // ── BIGME (09-13, "fast ink pads on Bigme dialogs"): the pad is a plain View, and on a
    // Bigme the plain path is the slow one — every sample a software invalidate. So on Bigme
    // hardware the pad grows a transparent SurfaceView on top and hands it to the vendor's
    // handwriting daemon (BigmeFastInk, the notes page's own engine): the daemon paints the
    // live line at the panel's speed, its pen events come back as synthesized stylus
    // MotionEvents into the SAME stroke path the software pad keeps, and on pen-up the pad's
    // record is mirrored onto the overlay (syncOverlay) so the baked ink replaces the live line.
    private var bigme: BigmeFastInk? = null
    private var bigmeOverlay: android.view.SurfaceView? = null
    private var bigmeStroke = false
    private var bigmeDispatching = false

    private fun attachBigmeIfAvailable() {
        if (bigme != null || !BigmeFastInk.isAvailable || width <= 0 || height <= 0 || !inkAllowed()) return
        // ONE CLIENT (Michael 2026-10-11): an attach already on its way is the only one — two
        // quick layouts used to add two overlays and bind two clients to one pad.
        if (bigmeOverlay != null) return
        val overlay = android.view.SurfaceView(context).apply {
            setZOrderOnTop(true)
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            isClickable = false; isFocusable = false
        }
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bigmeOverlay = overlay
        overlay.post {
            // A pad put back into a window (a page turned back to) attaches before its fresh
            // overlay is laid out, and the daemon refuses a 0×0 connect — which used to leave
            // that page on the slow software pen for good. Bind once the overlay has its size.
            if (overlay.width > 0 && overlay.height > 0) bindBigme(overlay)
            else overlay.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                    if (v.width <= 0 || v.height <= 0) return
                    v.removeOnLayoutChangeListener(this)
                    v.post { bindBigme(overlay) }
                }
            })
        }
    }

    private fun bindBigme(overlay: android.view.SurfaceView) {
        // The pad may have let go while this waited (a page turn, a pause, a resize): a client
        // bound now would be a second one, or one over nothing.
        if (bigmeOverlay !== overlay || !overlay.isAttachedToWindow || !inkAllowed()) {
            if (bigmeOverlay === overlay) { runCatching { removeView(overlay) }; bigmeOverlay = null }
            return
        }
        val ink = BigmeFastInk()
        val ok = ink.attach(
            overlay,
            segmentWidth = { _, _ -> penWidth.coerceAtLeast(1.5f) },
            liveDrawAllowed = { !eraseMode && pendingText == null && pendingShape == null },
        ) { a, x, y, p, _ -> onBigmePen(a, x, y, p) }
        bigme = if (ok) ink else null
        if (!ok) { removeView(overlay); bigmeOverlay = null }
        if (ok && penPauses.isNotEmpty()) runCatching { ink.setInputEnabled(false) }
        // A pad coming back with writing on it (a page turned back to, a re-sync) gives the
        // fresh client its truth once — the same forced mirror a pen-up makes.
        if (ok && ops.isNotEmpty()) mirrorOntoBigme()
        timber.log.Timber.i("InkPadView Bigme attach: %s", ink.status)
    }

    /**
     * THE PAD CHANGED SIZE UNDER ITS CLIENT (rotation, a row above it appearing). The daemon
     * was connected at the old size and never follows on its own (Michael 2026-10-11 plan, B6
     * risk: "the pad connects once and doesn't re-sync when it changes size"), so its pen would
     * land in the wrong place. Re-bind at the new size the way the notes page does — detach, then
     * attach (both silent seams) — and never under a live stroke.
     */
    private fun resyncBigme() {
        if (current != null) {
            postDelayed({ if (bigme?.matchesDimensions(width, height) == false) resyncBigme() }, 300L)
            return
        }
        detachBigme()
        attachBigmeIfAvailable()
    }

    private fun onBigmePen(action: Int, x: Float, y: Float, pressure: Float) {
        val mAction = when (action) {
            BigmeFastInk.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            BigmeFastInk.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            BigmeFastInk.ACTION_UP, BigmeFastInk.ACTION_LEAVE -> MotionEvent.ACTION_UP
            else -> return
        }
        post {
            val now = android.os.SystemClock.uptimeMillis()
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure.coerceIn(0f, 1f) }
            val ev = MotionEvent.obtain(now, now, mAction, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0)
            bigmeDispatching = true
            try { onTouchEvent(ev) } finally { bigmeDispatching = false; ev.recycle() }
            if (mAction == MotionEvent.ACTION_UP) mirrorOntoBigme()
        }
    }

    /** The pad's record, onto the daemon's overlay — the bake that replaces the live line. */
    private fun mirrorOntoBigme() {
        val ink = bigme ?: return
        if (width <= 0 || height <= 0) return
        runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            onDraw(c)
            ink.syncOverlay(bmp, android.graphics.Matrix(), force = true)
        }
    }

    private fun detachBigme() {
        runCatching { bigme?.detach() }
        bigme = null
        bigmeOverlay?.let { runCatching { removeView(it) } }
        bigmeOverlay = null
    }

    private class Stroke(val path: Path, val color: Int, val strokeWidth: Float,
                         val calligraphy: Boolean = false,
                         val points: MutableList<Pair<Float, Float>> = mutableListOf())

    /** A piece of typed/pasted text set down on the pad — erasable and undoable like a stroke. */
    private class Placed(val text: String, val x: Float, val y: Float, val color: Int)

    /** One drawn thing, in draw order — so undo peels the pad back element by element
     *  regardless of whether the last thing down was ink, a shape or a word. */
    private sealed class Op {
        class Ink(val stroke: Stroke) : Op()
        class Text(val placed: Placed) : Op()
    }

    private val ops = mutableListOf<Op>()
    private var current: Path? = null

    /** The Boox pen engine's live preview over this pad — null off Boox or when create fails. */
    private var raw: com.onyx.android.sdk.pen.TouchHelper? = null
    private var rawOpen = false
    /** True while a stylus stroke is being previewed by the engine: the software path must not
     *  repaint the region mid-stroke (each repaint wipes the overlay ink) — it re-bakes on up. */
    private var rawStroke = false
    /** The box the stroke in progress has touched, padded — the region to refresh. */
    private var strokeBox: android.graphics.RectF? = null

    var penColor: Int = Color.BLACK
        set(v) { field = v; syncRawPen() }

    /** View-px. The [WIDTHS] ladder is in design units (drawn at the Boox's ~2.5 density), so
     *  the default scales like the rest of the pad's geometry — unscaled it was a hairline that
     *  anti-aliased to grey mush on a dense screen. */
    var penWidth: Float = 4f * resources.displayMetrics.density / 2.5f
        set(v) { field = v; syncRawPen() }

    /** Calligraphy nib: strokes lay down a broad 45° edge instead of a round point — the same
     *  ballpoint-vs-calligraphy choice the page pen holds, emulated with offset passes because
     *  a dialog pad has no pressure engine. */
    var calligraphyMode: Boolean = false
        set(v) { field = v; syncRawPen() }

    /** Stroke eraser: while on, touches delete whole strokes (and placed text) they cross
     *  instead of drawing. */
    var eraseMode: Boolean = false
        set(v) { field = v; syncRawPen() }

    /**
     * Optional STATIONERY painted under the ink — ruled lines, a letterhead — drawn identically
     * on screen and into [render], so the surface you write on is the surface the reader
     * receives. Null = the plain pad.
     */
    var stationery: ((Canvas, Int, Int) -> Unit)? = null

    /** Armed text: the next tap sets this string down where the finger lands, then disarms.
     *  Fed by the pen bar's Ⓣ (typed) and ⎘ (clipboard) buttons. */
    var pendingText: String? = null
        set(v) { field = v; syncRawPen() }

    /** Armed shape (one of [SHAPE_BOX], [SHAPE_ROUND], [SHAPE_LINE], [SHAPE_ARROW]) — the next
     *  drag draws it corner-to-corner, live, and commits it as an ordinary erasable stroke. */
    var pendingShape: String? = null
        set(v) { field = v; syncRawPen() }
    private var shapeStartX = 0f
    private var shapeStartY = 0f

    init {
        setBackgroundColor(Color.WHITE)
    }

    private val strokes: List<Stroke> get() = ops.mapNotNull { (it as? Op.Ink)?.stroke }
    private val texts: List<Placed> get() = ops.mapNotNull { (it as? Op.Text)?.placed }

    private fun paintFor(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun textPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = 20f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.SERIF
    }

    private val rawCallback = object : com.onyx.android.sdk.pen.RawInputCallback() {
        override fun onPenActive(touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onPenUpRefresh(refreshRect: android.graphics.RectF) {}
        override fun onBeginRawDrawing(b: Boolean, touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onEndRawDrawing(b: Boolean, touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onRawDrawingTouchPointMoveReceived(touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onRawDrawingTouchPointListReceived(touchPointList: com.onyx.android.sdk.pen.data.TouchPointList) {}
        override fun onBeginRawErasing(b: Boolean, touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(touchPoint: com.onyx.android.sdk.data.note.TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(touchPointList: com.onyx.android.sdk.pen.data.TouchPointList) {}
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The engine is created against THIS view; its limit rect is set once the pad has a size.
        // Off Boox (or when the SDK is inert) create throws or returns a dead helper — either way
        // the software path stands, as it always has.
        if (raw == null) raw = try { com.onyx.android.sdk.pen.TouchHelper.create(this, rawCallback) } catch (_: Throwable) { null }
        if (width > 0 && height > 0) { openRaw(); attachBigmeIfAvailable() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            openRaw()
            val ink = bigme
            if (ink != null && !ink.matchesDimensions(w, h)) resyncBigme() else attachBigmeIfAvailable()
        }
    }

    override fun onDetachedFromWindow() {
        closeRawFully()
        detachBigme()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) closeRawFully()
    }

    /**
     * THE STRANDED STROKE (Michael, 09-03 → 09-13, the Palma: a small ink crescent sitting on
     * top of the rail's ☰, in no day file, surviving page turns and the day page's own GC
     * repaint). A pad's TouchHelper renders the pen's live line into the hardware raw layer; a
     * pad that went away with that helper merely closed — no render-disable, no post-enable,
     * no full repaint — could leave its last mark on the panel above every view that came
     * after. So a pad leaves the way the notes surface leaves: render off, closed, the panel
     * handed back, one full refresh.
     */
    private fun closeRawFully() {
        val th = raw ?: return
        try { th.setRawDrawingEnabled(false) } catch (_: Throwable) {}
        try { th.isRawDrawingRenderEnabled = false } catch (_: Throwable) {}
        try { th.closeRawDrawing() } catch (_: Throwable) {}
        rawOpen = false; raw = null
        try { com.onyx.android.sdk.api.device.epd.EpdController.enablePost(this, 1) } catch (_: Throwable) {}
        try { com.onyx.android.sdk.api.device.epd.EpdController.repaintEveryThing(com.onyx.android.sdk.api.device.epd.UpdateMode.GC) } catch (_: Throwable) {}
    }

    private fun openRaw() {
        val th = raw ?: return
        // The Boox engine keeps the host's gate too (Michael 2026-10-11): a hidden page (Text
        // only) put back into a window must not open a raw region over the typed body.
        if (!inkAllowed()) return
        try {
            if (rawOpen) th.closeRawDrawing()
            th.setLimitRect(android.graphics.Rect(0, 0, width, height), emptyList())
                .setStrokeWidth(penWidth).openRawDrawing()
            // Finger keeps its ordinary dispatch — the pad takes finger ink through onTouchEvent.
            th.enableFingerTouch(true)
            rawOpen = true
            syncRawPen()
        } catch (_: Throwable) { rawOpen = false }
    }

    /** The engine wears the pad's pen — colour, width, nib — and steps aside while a tool that
     *  isn't ink (eraser, placed text, a shape) is armed, so those strokes reach the software path. */
    private fun syncRawPen() {
        val th = raw ?: return
        if (!rawOpen) return
        try {
            th.setStrokeColor(penColor)
            th.setStrokeWidth(penWidth)
            th.setStrokeStyle(if (calligraphyMode) com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN
                              else com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_PENCIL)
            th.setRawDrawingEnabled(!eraseMode && pendingText == null && pendingShape == null && penPauses.isEmpty())
        } catch (_: Throwable) {}
    }

    private fun rawPreviewing(event: MotionEvent): Boolean =
        (rawOpen || bigmeDispatching) && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            !eraseMode && pendingText == null && pendingShape == null

    /** Grow the stroke's box by the segment just added, padded for the nib (and the calligraphy sweep). */
    private fun growBox(x: Float, y: Float) {
        val pad = penWidth * 2f + (if (calligraphyMode) penWidth * 1.8f else 0f) + 4f
        val b = strokeBox ?: android.graphics.RectF(x, y, x, y).also { strokeBox = it }
        b.union(x - pad, y - pad, x + pad, y + pad)
    }

    /** Refresh just [box] on the panel — the fast waveform while the pen moves, a grey pass once
     *  it lifts — instead of the whole pad on every sample. */
    private fun refreshBox(box: android.graphics.RectF, penUp: Boolean) {
        val l = box.left.toInt().coerceAtLeast(0); val t = box.top.toInt().coerceAtLeast(0)
        val r = box.right.toInt().coerceAtMost(width); val btm = box.bottom.toInt().coerceAtMost(height)
        if (r <= l || btm <= t) { invalidate(); return }
        invalidate(l, t, r, btm)
        try {
            com.onyx.android.sdk.api.device.epd.EpdController.invalidate(
                this, l, t, r, btm,
                if (penUp) com.onyx.android.sdk.api.device.epd.UpdateMode.GU
                else com.onyx.android.sdk.api.device.epd.UpdateMode.DU)
        } catch (_: Throwable) {}
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Paused (a chooser, the keyboard, a suggestion list): nothing is drawn; the touch is the
        // host's cue that the hand has come back to the page.
        if (penPauses.isNotEmpty()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // THE TOUCH THAT ASKS FOR THE PEN BACK IS SWALLOWED WHOLE (2026-10-11 review):
                    // the host drops the keyboard on this DOWN and lifts its pause, and the pen
                    // used to come back mid-touch — the daemon / raw engine painting a tail no
                    // stroke recorded, a ghost until the next refresh. Held until this UP.
                    penPauses.add(PAUSE_GESTURE)
                    onTouchWhilePaused?.invoke()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pausePen(PAUSE_GESTURE, false)
            }
            return true
        }
        // With the Bigme daemon attached the pen reaches the pad ONLY through it; the system's
        // own stylus events for the same touch would draw every stroke twice.
        if (bigme != null && !bigmeDispatching && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS &&
            !eraseMode && pendingText == null && pendingShape == null) return true
        if (eraseMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                eraseAt(event.x, event.y)
            }
            return true
        }
        // Armed text: the tap is the placement, not a stroke.
        pendingText?.let { t ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                ops.add(Op.Text(Placed(t, event.x, event.y, penColor)))
                pendingText = null
                invalidate()
            }
            return true
        }
        // Armed shape: the drag stretches it corner-to-corner; up commits it as a stroke.
        pendingShape?.let { kind ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    shapeStartX = event.x; shapeStartY = event.y
                    ops.add(Op.Ink(Stroke(Path(), penColor, penWidth)))
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    (ops.lastOrNull() as? Op.Ink)?.stroke?.let { s ->
                        buildShape(kind, s, shapeStartX, shapeStartY, event.x, event.y)
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) pendingShape = null
                }
            }
            invalidate()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Path().also {
                    it.moveTo(event.x, event.y)
                    ops.add(Op.Ink(Stroke(it, penColor, penWidth, calligraphyMode)
                        .apply { points.add(event.x to event.y) }))
                }
                rawStroke = rawPreviewing(event)
                strokeBox = null; growBox(event.x, event.y)
                if (!rawStroke) refreshBox(strokeBox!!, penUp = false)
            }
            // Every batched sample, smoothed — one point per dispatched event with straight
            // lineTo segments is the "sloppy pen": see [InkSmoothing].
            MotionEvent.ACTION_MOVE -> {
                val s = if (current != null) (ops.lastOrNull() as? Op.Ink)?.stroke else null
                if (s != null) {
                    val before = s.points.size
                    InkSmoothing.appendMotion(event, s.points)
                    InkSmoothing.rebuild(s.points, s.path)
                    val seg = android.graphics.RectF()
                    for (i in (before - 1).coerceAtLeast(0) until s.points.size) {
                        val (x, y) = s.points[i]; growBox(x, y)
                        val pad = penWidth * 2f + (if (s.calligraphy) penWidth * 1.8f else 0f) + 4f
                        if (seg.isEmpty && i == (before - 1).coerceAtLeast(0)) seg.set(x - pad, y - pad, x + pad, y + pad)
                        else seg.union(x - pad, y - pad, x + pad, y + pad)
                    }
                    // Hardware preview: the engine is drawing this stroke; a repaint here would
                    // wipe it. Software: only the segment's box, in the fast waveform.
                    if (!rawStroke && !seg.isEmpty) refreshBox(seg, penUp = false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                // Re-bake: the whole stroke's box is repainted once from the record, replacing
                // the engine's overlay with the pad's own ink (a grey pass, so it settles clean).
                strokeBox?.let { refreshBox(it, penUp = true) } ?: invalidate()
                rawStroke = false; strokeBox = null
            }
        }
        return true
    }

    /** Rebuild [s]'s path as the armed shape between the anchor and the finger, and refresh its
     *  point list so the stroke-eraser can find the shape's outline later. */
    private fun buildShape(kind: String, s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float) {
        s.path.reset(); s.points.clear()
        val l = minOf(x0, x1); val t = minOf(y0, y1)
        val r = maxOf(x0, x1); val b = maxOf(y0, y1)
        when (kind) {
            SHAPE_BOX -> s.path.addRect(l, t, r, b, Path.Direction.CW)
            SHAPE_ROUND -> s.path.addOval(android.graphics.RectF(l, t, r, b), Path.Direction.CW)
            SHAPE_LINE -> { s.path.moveTo(x0, y0); s.path.lineTo(x1, y1) }
            SHAPE_ARROW -> {
                s.path.moveTo(x0, y0); s.path.lineTo(x1, y1)
                // The head: two short barbs off the tip, sized to the line.
                val ang = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                val len = 26f * resources.displayMetrics.density / 2.5f
                for (side in intArrayOf(-1, 1)) {
                    val a = ang + Math.PI - side * Math.PI / 7
                    s.path.moveTo(x1, y1)
                    s.path.lineTo(x1 + (len * Math.cos(a)).toFloat(), y1 + (len * Math.sin(a)).toFloat())
                }
            }
        }
        // Sample the outline for the eraser (a Path can be drawn but not asked where it went).
        val pm = android.graphics.PathMeasure(s.path, false)
        do {
            val step = 12f
            var d = 0f
            val pos = FloatArray(2)
            while (d <= pm.length) {
                if (pm.getPosTan(d, pos, null)) s.points.add(pos[0] to pos[1])
                d += step
            }
        } while (pm.nextContour())
    }

    /** Delete any stroke or placed text passing within a fingertip of (x, y) — whole elements,
     *  like the page's scribble eraser: partial surgery isn't worth it on a note-sized pad. */
    private fun eraseAt(x: Float, y: Float) {
        val r = 24f * resources.displayMetrics.density / 2.5f
        val hit = ops.filter { op ->
            when (op) {
                is Op.Ink -> op.stroke.points.any { (px, py) ->
                    val dx = px - x; val dy = py - y; dx * dx + dy * dy < r * r
                }
                is Op.Text -> {
                    val p = textPaint(op.placed.color)
                    val w = p.measureText(op.placed.text)
                    x >= op.placed.x - r && x <= op.placed.x + w + r &&
                        y >= op.placed.y - p.textSize - r && y <= op.placed.y + r
                }
            }
        }
        if (hit.isNotEmpty()) { ops.removeAll(hit); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        stationery?.invoke(canvas, width, height)
        drawOps(canvas)
    }

    /** Draw every element in the order it was set down, on-screen and in [render] alike. */
    private fun drawOps(canvas: Canvas) {
        for (op in ops) when (op) {
            is Op.Ink -> drawStroke(canvas, op.stroke)
            is Op.Text -> canvas.drawText(op.placed.text, op.placed.x, op.placed.y, textPaint(op.placed.color))
        }
    }

    /** A round-nib stroke is one pass; the calligraphy nib is the same path swept along a 45°
     *  edge — thin passes offset along the nib axis, which is how a broad edge reads without a
     *  pressure engine (thick across the nib, thin along it). */
    private fun drawStroke(canvas: Canvas, s: Stroke) {
        if (!s.calligraphy) {
            canvas.drawPath(s.path, paintFor(s.color, s.strokeWidth))
            return
        }
        val sweep = s.strokeWidth * 1.8f
        val passes = 5
        val thin = paintFor(s.color, (s.strokeWidth * 0.55f).coerceAtLeast(1.5f))
        for (i in 0 until passes) {
            val d = sweep * (i / (passes - 1f) - 0.5f)
            canvas.save()
            canvas.translate(d * 0.7071f, -d * 0.7071f)
            canvas.drawPath(s.path, thin)
            canvas.restore()
        }
    }

    fun clear() {
        ops.clear(); current = null; invalidate()
    }

    fun undo() {
        if (ops.isNotEmpty()) {
            ops.removeAt(ops.size - 1); current = null; invalidate()
        }
    }

    fun isBlank() = ops.isEmpty()

    /**
     * The box everything written actually occupies, in view pixels, padded by half a pen width so a
     * stroke's own thickness isn't sliced off — or null when the pad is empty.
     *
     * Exists for callers that keep the writing rather than just reading it: a title written on a
     * pad three inches tall is one line of ink in the top third of it, and storing the whole pad
     * would store mostly blank paper and then scale the writing down to nothing to make it fit.
     * The iPad twin crops the same way for the same reason (`canvas.drawing.bounds` in
     * `InkTaskStrip`), so a face written on either device lands the same size on the other.
     */
    fun inkBounds(): android.graphics.RectF? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var pad = 0f
        for (s in strokes) {
            for ((x, y) in s.points) {
                if (x < l) l = x; if (y < t) t = y
                if (x > r) r = x; if (y > b) b = y
            }
            if (s.strokeWidth > pad) pad = s.strokeWidth
        }
        for (p in texts) {
            val paint = textPaint(p.color)
            val w = paint.measureText(p.text)
            if (p.x < l) l = p.x; if (p.y - paint.textSize < t) t = p.y - paint.textSize
            if (p.x + w > r) r = p.x + w; if (p.y > b) b = p.y
        }
        if (r < l || b < t) return null
        val air = pad / 2f + 2f
        return android.graphics.RectF(l - air, t - air, r + air, b + air)
    }

    /** The written card as a bitmap, or null when nothing has been written. */
    fun render(): Bitmap? {
        if (ops.isEmpty() || width == 0 || height == 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        stationery?.invoke(c, width, height)
        drawOps(c)
        return bmp
    }

    companion object {
        private val WIDTHS = floatArrayOf(2.5f, 4f, 7f)
        private const val ACCENT = 0xFF2F6F96.toInt()
        private const val RED = "#B00020"
        private const val BLUE = "#1A4E8A"
        private const val GREEN = "#1E6B34"

        const val SHAPE_BOX = "box"
        const val SHAPE_ROUND = "round"
        const val SHAPE_LINE = "line"
        const val SHAPE_ARROW = "arrow"

        /**
         * The pen toolbar that belongs above a pad: undo, the page palette, a fine↔bold cycle,
         * the calligraphy nib, placed text, clipboard paste, dragged shapes, and the stroke
         * eraser — the current eraser/stylus design, scaled to a pad.
         *
         * Lives here rather than in one dialog so every surface that composes ink gets the same
         * controls — the alternative is four toolbars drifting apart the way the four pads did.
         * The row scrolls sideways rather than wrapping: a dialog on a phone is narrower than
         * the full set, and a second row would push the pad below a writing hand.
         */
        fun penBar(context: Context, pad: InkPadView): android.widget.LinearLayout {
            val dp = context.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()

            val bar = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(2), px(2), px(2), px(4))
            }

            // The armed-tool buttons invert while armed (the pressed-state grammar the page
            // tools use), and arming any one disarms the others — one nib in the hand at a time.
            lateinit var refreshArmed: () -> Unit

            // THE PAD REMEMBERS THE PEN — all three legs of it. The "pen is a habit" fix
            // (AnnotationPen, 08-08/08-11) landed on the Edit-in-ink bar but never on THIS bar,
            // the one every reply/annotation pad actually wears — so each pad still opened at
            // medium black ballpoint, and the 🖋 nib in particular reset every single time
            // ("Calligraphy effect seems to disappear", 08-12). The pad now opens holding the
            // pen it was last used with: width, colour, and the calligraphy nib, all from the
            // one shared store, so a pen chosen on any pad is the pen every pad offers next.
            pad.penColor = AnnotationPen.color(context)
            pad.calligraphyMode = AnnotationPen.calligraphy(context)

            // THE PAGE'S OWN GLYPHS (Michael, 09-12: "the glyphs don't match up") — the same
            // drawables the notes page toolbar wears, at bar size: undo, pen, text, paste, eraser.
            fun iconBtn(res: Int, desc: String, onTap: () -> Unit) = android.widget.ImageView(context).apply {
                setImageResource(res); contentDescription = desc
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(px(34), px(34))
                setPadding(px(5), px(5), px(5), px(5))
                setColorFilter(Color.BLACK)
                setOnClickListener { onTap() }
            }

            fun swatch(color: Int): android.widget.TextView {
                lateinit var tv: android.widget.TextView
                tv = android.widget.TextView(context).apply {
                    text = "●"; textSize = 22f; setTextColor(color); setPadding(px(5), 0, px(5), 0)
                    // The active ink is the underlined one — the REMEMBERED ink on open, not
                    // hard-coded black.
                    if (color == pad.penColor) paintFlags = Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener {
                        pad.penColor = color
                        AnnotationPen.setColor(context, color)
                        (parent as? android.widget.LinearLayout)?.let { row ->
                            for (i in 0 until row.childCount) {
                                val c = row.getChildAt(i) as? android.widget.TextView ?: continue
                                if (c.text == "●") c.paintFlags = 0
                            }
                        }
                        tv.paintFlags = Paint.UNDERLINE_TEXT_FLAG
                    }
                }
                return tv
            }

            bar.addView(iconBtn(com.toolsboox.R.drawable.ic_toolbar_undo, "Undo") { pad.undo() })
            bar.addView(swatch(Color.BLACK))
            bar.addView(swatch(Color.parseColor(RED)))
            bar.addView(swatch(Color.parseColor(BLUE)))
            bar.addView(swatch(Color.parseColor(GREEN)))
            bar.addView(android.widget.Space(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
            })

            var widthIdx = AnnotationPen.widthIndex(context).coerceIn(0, WIDTHS.size - 1)
            pad.penWidth = WIDTHS[widthIdx] * dp / 2.5f
            // Design units → view px, the same density/2.5 the pad's other geometry wears.
            bar.addView(iconBtn(com.toolsboox.R.drawable.ic_toolbar_pen, "Pen width") {
                widthIdx = (widthIdx + 1) % WIDTHS.size
                pad.penWidth = WIDTHS[widthIdx] * dp / 2.5f
                AnnotationPen.setWidthIndex(context, widthIdx)
            })

            // 🖋 the calligraphy nib — a mode, not a one-shot, so it stays down until tapped off.
            val calligBtn = android.widget.TextView(context).apply {
                text = " 🖋 "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    pad.calligraphyMode = !pad.calligraphyMode
                    pad.eraseMode = false
                    AnnotationPen.setCalligraphy(context, pad.calligraphyMode)
                    refreshArmed()
                }
            }
            bar.addView(calligBtn)

            // Ⓣ typed text → the next tap places it. The prompt is the page's text-box idea at
            // pad scale; placement by tap keeps the choice with the hand, not a dialog.
            val textBtn = iconBtn(com.toolsboox.R.drawable.ic_toolbar_text, "Text") {
                run {
                    val input = android.widget.EditText(context).apply { hint = "Text — then tap the pad to place it" }
                    androidx.appcompat.app.AlertDialog.Builder(ModalScale.wrap(context))
                        .setTitle("Text")
                        .setView(input)
                        .setPositiveButton(android.R.string.ok) { d, _ ->
                            val t = input.text.toString().trim()
                            if (t.isNotEmpty()) { pad.pendingText = t; pad.pendingShape = null; pad.eraseMode = false }
                            refreshArmed(); d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        // The pen belongs to this prompt while it's up (Michael 2026-10-11:
                        // pen paused under choosers) — its OK sits over the pad's bounds.
                        .setOnDismissListener { pad.pausePen("text", false) }
                        .show()
                    pad.pausePen("text", true)
                }
            }
            bar.addView(textBtn)

            // ⎘ paste — whatever text the clipboard holds, placed by the next tap.
            val pasteBtn = iconBtn(com.toolsboox.R.drawable.ic_toolbar_paste, "Paste") {
                run {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (t.isEmpty()) {
                        android.widget.Toast.makeText(context, "Nothing on the clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        pad.pendingText = t.take(300); pad.pendingShape = null; pad.eraseMode = false
                        android.widget.Toast.makeText(context, "Tap the pad to place it", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    refreshArmed()
                }
            }
            bar.addView(pasteBtn)

            // Shapes: each tap cycles what the next drag draws — ▭ box, ◯ round, ╱ line,
            // ➔ arrow — and the button wears the armed shape's face.
            val shapes = listOf(SHAPE_BOX to "▭", SHAPE_ROUND to "◯", SHAPE_LINE to "╱", SHAPE_ARROW to "➔")
            var shapeIdx = -1
            val shapeBtn = android.widget.TextView(context).apply {
                text = " ▭ "; textSize = 15f; setTextColor(ACCENT); setPadding(px(5), px(2), px(5), px(2))
                setOnClickListener {
                    shapeIdx = if (pad.pendingShape == null) 0 else (shapeIdx + 1) % shapes.size
                    pad.pendingShape = shapes[shapeIdx].first
                    pad.pendingText = null; pad.eraseMode = false
                    text = " ${shapes[shapeIdx].second} "
                    refreshArmed()
                }
            }
            bar.addView(shapeBtn)

            // Stroke eraser: inverted while armed, the pressed-state grammar the page tools use.
            val eraseBtn = iconBtn(com.toolsboox.R.drawable.ic_toolbar_eraser, "Eraser") {
                pad.eraseMode = !pad.eraseMode
                pad.calligraphyMode = false
                pad.pendingText = null; pad.pendingShape = null
                refreshArmed()
            }
            bar.addView(eraseBtn)

            refreshArmed = {
                fun dress(v: View, armed: Boolean) {
                    v.setBackgroundColor(if (armed) 0xFF000000.toInt() else 0x00000000)
                    when (v) {
                        is android.widget.TextView -> v.setTextColor(if (armed) Color.WHITE else ACCENT)
                        is android.widget.ImageView -> v.setColorFilter(if (armed) Color.WHITE else Color.BLACK)
                    }
                }
                dress(calligBtn, pad.calligraphyMode)
                dress(textBtn, pad.pendingText != null)
                dress(pasteBtn, false)
                dress(shapeBtn, pad.pendingShape != null)
                dress(eraseBtn, pad.eraseMode)
                if (pad.pendingShape == null) shapeBtn.text = " ▭ "
            }
            refreshArmed()

            // The full set outgrows a phone dialog; the row scrolls sideways instead of wrapping
            // (a second row would push the pad down under the writing hand). Same outer type as
            // ever, so every existing mount keeps working untouched.
            val scroller = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar, android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            return android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(scroller, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
