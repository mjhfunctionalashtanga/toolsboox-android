package com.toolsboox.ot

import android.graphics.Rect
import android.os.Build
import android.view.SurfaceView
import timber.log.Timber
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Bigme fast-ink backend (the B6 Pro / HiBreak family), converged on the discipline of
 * the working reference integration (inkit / inksdk) — see DESIGN-bigme-ink-2026-08-22.md.
 *
 * The daemon (`com.xrz.HandwrittenClient` → `/system/bin/handwrittenservice`) binds our
 * SurfaceView, exposes an ION-backed Canvas, and delivers pen events on a binder thread.
 * The rules this class keeps:
 *
 *  - ONE client per surface life; rotation updates it in place.
 *  - The app draws the live stroke into the daemon canvas on the binder thread and posts
 *    NOTHING to the surface while the pen is down.
 *  - The ION buffer is a MIRROR, not a scratch layer: [syncOverlay] blits the page's
 *    ink into it at every commit point over a TRANSPARENT base, then converges the
 *    glass with one flashless GU16 refresh. COMPOSITING IS PER-DEVICE: the HiBreak
 *    Plus keys on white (the reference doc's claim), but the B6 alpha-composites —
 *    white is OPAQUE PAINT there, and a white-based mirror papered over the template,
 *    the nav ball, and the notes nav (field report). Transparent = show-through on
 *    the B6; ink occludes; the mirror still carries the ink across the system
 *    e-ink refresh while everything beneath stays visible.
 *  - Daemon FINGER events are dropped whole — fingers belong to the app's raw touch.
 *  - Stale-listener ghosts are impossible: [dead] is checked first in the proxy.
 *  - The registration flush is filtered by the daemon's own timestamps, not wall clock.
 *
 * Every reflective call is guarded; a failed [attach] leaves the software path in charge
 * (slow but writing). Gate on [isAvailable] before constructing.
 */
class BigmeFastInk {

    /** Where the last attach attempt ended, for the human-readable diagnostics toast —
     *  the B6 isn't on adb, so the person holding the pen is the log reader. */
    @Volatile
    var status: String = "not attached"
        private set

    @Volatile
    var attached: Boolean = false
        private set

    /** The buffer dimensions the daemon connected with. */
    @Volatile
    var attachedWidth: Int = 0
        private set
    @Volatile
    var attachedHeight: Int = 0
        private set

    fun matchesDimensions(w: Int, h: Int): Boolean = attachedWidth == w && attachedHeight == h

    /** -189: the daemon stores its canvas PANEL-SHAPED. In landscape that equals the view
     *  (1366×978 ↔ 1366×978); in portrait it is the view's dims SWAPPED (connect 986×1354 →
     *  getCanvas 1354×986) with a pre-rotation so view-frame drawing lands right — which is
     *  how every portrait mirror before -180 worked. The -180 guard compared view width to
     *  canvas width literally, so in portrait it refused every mirror and scrub (trace 09-04
     *  07:08: 393 "SKIPPED: view=986x1354 canvas=1354x986" — lasso/erase/chips invisible
     *  until a manual refresh). The rotation-storm corruption (law #5) is view≠attach dims;
     *  the canvas only has to be the attach dims in either orientation. */
    fun canvasMatchesAttach(canvas: android.graphics.Canvas): Boolean =
        (canvas.width == attachedWidth && canvas.height == attachedHeight) ||
        (canvas.width == attachedHeight && canvas.height == attachedWidth)

    /** Set at [detach] and checked FIRST in the listener proxy: the daemon has no working
     *  unregister, so a detached client's listener keeps receiving events forever. */
    @Volatile
    private var dead = false

    /** Daemon CLOCK_REALTIME (ns) at connect: events stamped BEFORE this are the daemon's
     *  registration flush (stale queued input replayed at listener registration) and are
     *  dropped. Timestamp-based, so fresh ink milliseconds after attach still lands. */
    @Volatile
    private var attachedAtRealtimeNs = 0L

    /** Uptime ms of the last daemon pen event — the fragment's liveness watchdog reads
     *  this to detect a wedged daemon (attached but delivering nothing). */
    @Volatile
    var lastDaemonEventMs = 0L
        private set

    /** ALL app-initiated daemon calls (the mirror, its GU16 refresh) run here, never on
     *  the main thread: the daemon's inValidate can block for hundreds of ms against a
     *  busy panel — on main that IS the freeze, with or without lock contention. The
     *  binder thread keeps its own live path. Coalesced: only the newest mirror request
     *  survives the queue. */
    private val inkThread = android.os.HandlerThread("BigmeInk").apply { start() }
    private val inkHandler = android.os.Handler(inkThread.looper)

    /** Rotation truth, captured at attach: the delta (in 90° steps) between what the
     *  display says and what the daemon believes. The B6 daemon is rotation-blind —
     *  it keeps feeding portrait-frame coordinates under a landscape view, so the
     *  proxy rotates them itself by this delta. */
    @Volatile
    private var rotationDelta = 0
    @Volatile
    private var daemonFrameW = 0
    @Volatile
    private var daemonFrameH = 0

    /** The daemon's frame vs the view's: 0 = aligned; 90/270 = the daemon is
     *  PANEL-ANCHORED (portrait-native glass under a rotated view — the B6 "sideways
     *  writing" lock). Measured at attach from geometry, not belief; every input
     *  coordinate, live stroke, and mirror blit adapts through it. */
    @Volatile
    private var panelMode = 0

    /** Daemon coords → view coords.
     *
     *  FIELD-CALIBRATED 09-03, twice: the -177 four-corner probe looked like an x-mirror,
     *  but the -178 writing trace disproved it (left-to-right strokes arrived with raw x
     *  INCREASING — the corner probe had been done while the panel was displaying 180° off
     *  from the physical hold, mid rotation-storm). On this firmware the daemon reports pen
     *  input ALREADY IN THE VIEW FRAME in every orientation seen so far: the correct
     *  adaptation is IDENTITY. The old portrait-frame axis-swap (90/270) was itself the
     *  "old flipped strokes bug" — it scattered rotated writing and pushed coords negative
     *  (see inktrace raw= lines, -177). If a future orientation misbehaves, calibrate with
     *  corner taps against the raw= trace BEFORE adding any case here — and distrust the
     *  probe unless the on-screen content provably matched the physical hold. */
    private fun panelToView(x: Float, y: Float): Pair<Float, Float> = Pair(x, y)

    /** One lock for the daemon-canvas work, NEVER held across a blocking wait on the
     *  main thread: the live drawer (binder thread) may sit inside a slow daemon call
     *  mid-EPD-refresh, and a main-thread mirror that WAITED on it froze the app (the
     *  erase-freeze report). The mirror tryLocks and skips instead. */
    private val inkLock = java.util.concurrent.locks.ReentrantLock()
    /** Set at every attach: the first forced mirror drives the whole view to truth (see syncOverlayLocked). */
    @Volatile private var truthWipePending = false
    /** The page's template bitmap (ruling/furniture) — the truth under the ink-only shadow. */
    @Volatile var templateProvider: (() -> android.graphics.Bitmap?)? = null

    /**
     * Ink chrome lying over the surface — the bottom pill and its corner's picker (plan v6 S1.2;
     * Michael 2026-10-11), in VIEW pixels, replaced whole by the main thread. Every daemon write
     * leaves these rects transparent — the mirror, the scrubs (🧹 Clean the screen among them),
     * the seam white and the live line — so the daemon never paints over the chrome; a pen-down
     * inside one paints no live ink. The mirror's and the scrubs' pushes go up AROUND them
     * ([pushAround], review 2026-10-10), never over them.
     */
    @Volatile var chromeCutouts: List<Rect> = emptyList()

    /** A forced push went up with a hole in it: the page under the chrome may have changed (an
     *  undo from the flyout), and the glass there is owed a truth scrub once the chrome is gone. */
    @Volatile private var cutoutDebt = false

    /** Uptime ms of the last ink the daemon carried on this surface (in-bounds pen down, move or
     *  up) — what [msUntilQuiet] measures from. Taps on chrome beside the surface don't count; a
     *  pen tap on the bottom pill over the page does (Michael 2026-10-11: it floats at the foot),
     *  since the daemon sees that pen-down too. Whether its layer stays off for a
     *  muted tap is a field question (inktrace) — until it is answered, ⛶ from the pill waits. */
    @Volatile private var lastInkMs = 0L

    @Volatile
    private var client: Any? = null
    @Volatile
    private var clientClass: Class<*>? = null
    @Volatile
    private var boundView: SurfaceView? = null

    // ── Live-ink state (binder thread, guarded by @Synchronized methods) ──────────
    private var lastX = 0f
    private var lastY = 0f
    private var penIsDown = false
    private var penDownAtMs = 0L
    /** True while the current daemon stroke started out of bounds (toolbar taps arrive
     *  at negative view-local coords) — the whole stroke is discarded, live and capture. */
    private var droppingStroke = false
    private var segmentWidthPx: (Float, Float) -> Float = { _, _ -> 4f }
    private var liveDrawAllowed: () -> Boolean = { true }
    /** Is a DOWN at this view point on a control floating over the page (rail, pills, pager
     *  arrows)? Then the stroke is a TAP, not ink: captured as before (the app forwards the
     *  click) but never painted live. THE CRESCENT BEHIND THE ☰ (09-13/14): every pen tap on
     *  the hamburger left the daemon's down-dot and the tap's tiny drag-arc on the glass —
     *  HANDWRITE-mode ink nothing ever lifted, because the mirror only darkens and no scrub
     *  ever runs under a button. */
    private var liveInkMutedAt: (Float, Float) -> Boolean = { _, _ -> false }
    private var liveMuted = false
    private var downNanos = 0L
    private var firstPaintPending = false

    // Anti-alias must stay OFF on the daemon canvas: the EPD renders discrete grey
    // levels, and AA edge pixels dither differently on each refresh ("train track"
    // ghosts along every stroke).
    private val livePaint = android.graphics.Paint().apply {
        isAntiAlias = false
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    /** The eraser's live trail punches the overlay to TRANSPARENT (the B6's true
     *  show-through — white is opaque paint here), revealing the surface beneath; the
     *  post-erase [syncOverlay] reconciles fully. */
    private val rubberPaint = android.graphics.Paint().apply {
        isAntiAlias = false
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = RUBBER_TRAIL_PX
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    /** EPD commits are batched: segments union into this dirty rect and one inValidate
     *  goes out per vsync. One-rect-per-MOVE floods the daemon and leaves "train track"
     *  artifacts on long strokes. */
    private val accumDirty = Rect(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
    private var lastCommitMs = 0L
    private var firstSegmentOfStroke = false

    companion object {
        private const val CLIENT_CLASS = "com.xrz.HandwrittenClient"
        private const val LISTENER_CLASS = "com.xrz.HandwrittenClient\$InputListener"

        // The daemon's own vocabulary.
        const val ACTION_NEAR = 0
        const val ACTION_DOWN = 1
        const val ACTION_MOVE = 2
        const val ACTION_UP = 3
        const val ACTION_LEAVE = 4
        const val TOOL_PEN = 0
        const val TOOL_RUBBER = 1
        const val TOOL_FINGER = 2
        const val MODE_HANDWRITE = 1029
        const val MODE_RUBBER = 1030
        /** THE ONE FACT ABOUT THE DAEMON'S MEMORY (banked 09-03, relearned 09-14): only OPAQUE
         *  1202 commits enter its commit-state, and it republishes that state — per rect — at the
         *  next attach. Every white+truth scrub (lasso ring, drag trail, 🧹) is therefore ALSO the
         *  picture the daemon will paint over the NEXT page ("I see the new screen, then it
         *  flashes, then the image that has gotten stuck somewhere is shown again"). This flag
         *  says whether any such commit has been made since the state was last driven to plain
         *  white; persisted, because the state outlives the process (not a reboot). */
        private const val PREF_STATE_DIRTY = "bigme_state_dirty"
        @Volatile private var stateDirty = false
        fun markOpaqueCommit(ctx: android.content.Context?) {
            stateDirty = true
            runCatching { ctx?.getSharedPreferences("bigme_ink", 0)?.edit()?.putBoolean(PREF_STATE_DIRTY, true)?.apply() }
        }
        private fun markStateWhite(ctx: android.content.Context?) {
            stateDirty = false
            runCatching { ctx?.getSharedPreferences("bigme_ink", 0)?.edit()?.putBoolean(PREF_STATE_DIRTY, false)?.apply() }
        }
        /** Absent = ASSUME DIRTY: a device that never ran this code has whatever the daemon
         *  accumulated before it (the whole-view 🧹 pages of 09-14) — one white pass costs a
         *  blink, a wrong "clean" costs the old page over the new one again. */
        private fun stateDirty(ctx: android.content.Context?): Boolean =
            stateDirty || runCatching { ctx?.getSharedPreferences("bigme_ink", 0)?.getBoolean(PREF_STATE_DIRTY, true) != false }.getOrDefault(true)

        /** 0xFEFEFE: opaque to the daemon's white-is-transparent key, white on a 16-level panel. */
        const val OPAQUE_WHITE = 0xFFFEFEFE.toInt()

        /** SILENT SEAMS (10-10, experiment 2): connect and disconnect never push to the glass —
         *  Bigme's own xNote has no inValidate at connect, disconnect, page turn or rotation. The
         *  field capture showed our detach push landing 2ms AFTER "layer size after remove: 0", so
         *  the daemon committed its stale panel buffer (fd=11) over the next screen — the stuck
         *  image on every screen change; the attach prime and the seam whites did the same. */
        const val SILENT_SEAMS = true

        const val MODE_GU16 = 132
        const val MODE_GC16 = 4
        /** EINK_NORMAL_RECT_MODE — the erase-capable partial (xNote FlushInfo, first-party):
         *  the ONLY rect mode that can LIGHTEN pixels. GU16 is additive/darken-only, which is
         *  why stale live ink could never be lifted off the glass through it. */
        const val MODE_NORMAL_RECT = 1202

        /** One EPD commit per vsync — the daemon's own cadence. */
        private const val COMMIT_INTERVAL_MS = 16L

        /** Coalescing token for queued mirror passes on the ink thread. */
        private val MIRROR_TOKEN = Any()

        /** Width of the eraser's live trail, in view pixels. */
        private const val RUBBER_TRAIL_PX = 24f

        /** Wall-clock fallback flush window when the daemon sends no timestamps. */
        private const val ATTACH_GRACE_FALLBACK_MS = 150L

        /** Clock-skew slack for the registration-flush filter: the daemon's CLOCK_REALTIME
         *  and the app's wall clock may disagree — without slack, a daemon clock running
         *  behind makes EVERY fresh stroke look pre-attach and the filter eats the ink. */
        private const val FLUSH_SLACK_NS = 5_000_000_000L

        /** A wedged pen-down (daemon lost the UP — modal opened mid-stroke, breakage)
         *  self-heals after this long with no events, so the mirror can't be starved. */
        private const val PEN_DOWN_WEDGE_MS = 2000L
        /** The daemon's layer stays live this long after the last ink (the -295/-296 window: on at
         *  pen-down, off 1.2s idle). Chrome toggles wait it out — never toggle while it is on. */
        const val LAYER_IDLE_MS = 1200L
        /** Minimum quiet gap after a drag-frame 1202 COMPLETES before the next may start. */
        private const val DRAG_SCRUB_GAP_MS = 250L   // -191: 350 → 250 ("could be a little faster"); floor is ~200

        /**
         * The FIRST recipe of the xrz framework, callable with or without the daemon:
         * tell the panel this SurfaceView is a handwriting canvas, so its posts refresh
         * on the handwriting waveform instead of the sluggish app default. Without it
         * the SOFTWARE path's renders (erase results, bakes) lag until something else
         * refreshes the panel — the field report. This is the documented
         * XrzEinkManager.setRefreshModeForSurfaceView(view, EINK_HANDWRITE_MODE) call
         * every stock Bigme ink app makes.
         */
        fun applySurfaceRefreshMode(view: SurfaceView) {
            runCatching {
                val cls = Class.forName("xrz.framework.manager.XrzEinkManager")
                val mgr = cls.getConstructor(android.content.Context::class.java)
                    .newInstance(view.context)
                cls.getMethod("setRefreshModeForSurfaceView",
                    SurfaceView::class.java, Int::class.javaPrimitiveType)
                    .invoke(mgr, view, MODE_HANDWRITE)
                Timber.i("BigmeFastInk: surface refresh mode set to HANDWRITE")
            }.onFailure { Timber.d(it, "setRefreshModeForSurfaceView unavailable") }
        }

        /** Bigme hardware with the vendor client present. */
        val isAvailable: Boolean
            get() = (Build.MANUFACTURER.contains("bigme", true) ||
                Build.BRAND.contains("bigme", true) ||
                runCatching { Class.forName(CLIENT_CLASS); true }.getOrDefault(false)) &&
                runCatching { Class.forName(CLIENT_CLASS); true }.getOrDefault(false)
    }

    /**
     * Re-sync the connected client to a resized/rotated surface IN PLACE — the vendor's
     * path (HandwrittenClient implements SurfaceHolder.Callback; inkit rotates on one
     * client for its whole life). The caller must [syncOverlay] afterwards — the wiped
     * buffer holds nothing until the page is re-mirrored.
     */
    fun updateGeometry(w: Int, h: Int): Boolean {
        val c = client ?: return false
        val cls = clientClass ?: return false
        if (w <= 0 || h <= 0) return false
        return runCatching {
            cls.getMethod("updateLayout").invoke(c)
            cls.getMethod("updateRotation").invoke(c)
            (cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas)
                ?.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            attachedWidth = w; attachedHeight = h
            status = "daemon updated ($w×$h)"
            Timber.i("BigmeFastInk: %s", status)
            true
        }.getOrDefault(false)
    }

    /**
     * Bind [view], connect, and route the daemon's pen events into [onPen] (action, x, y,
     * pressure 0..1, tool — view pixels). [segmentWidth] gives the live line's width per
     * segment (angle, pressure). [liveDrawAllowed] gates live painting by app mode — the
     * lasso, image mode, and menus must not paint black lines.
     */
    fun attach(
        view: SurfaceView,
        segmentWidth: (angleRad: Float, pressure: Float) -> Float = { _, _ -> 4f },
        liveDrawAllowed: () -> Boolean = { true },
        liveInkMutedAt: (x: Float, y: Float) -> Boolean = { _, _ -> false },
        onPen: (Int, Float, Float, Float, Int) -> Unit,
    ): Boolean {
        if (attached) return true
        segmentWidthPx = segmentWidth
        this.liveDrawAllowed = liveDrawAllowed
        this.liveInkMutedAt = liveInkMutedAt
        return try {
            val cls = Class.forName(CLIENT_CLASS)
            val c = cls.getConstructor(android.content.Context::class.java).newInstance(view.context)
            cls.getMethod("bindView", android.view.View::class.java).invoke(c, view)

            val listenerCls = Class.forName(LISTENER_CLASS)
            val proxy = Proxy.newProxyInstance(cls.classLoader, arrayOf(listenerCls)) { _, method: Method, args ->
                if (dead) {
                    if (method.name == "onInputTouch") InkPerf.record("pen.stale_listener", 0)
                    return@newProxyInstance 0
                }
                runCatching {
                    if (method.name == "onInputTouch" && args != null && args.size >= 5) {
                        val handlerStart = System.nanoTime()
                        val action = (args[0] as? Int) ?: return@runCatching
                        val x = (args[1] as? Number)?.toFloat() ?: return@runCatching
                        val y = (args[2] as? Number)?.toFloat() ?: return@runCatching
                        // Pressure arrives as an int 0..4096.
                        val pRaw = (args[3] as? Number)?.toFloat() ?: 4096f
                        val p = if (pRaw > 1f) (pRaw / 4096f).coerceIn(0f, 1f) else pRaw
                        val tool = (args[4] as? Int) ?: TOOL_PEN
                        val daemonNs = if (args.size >= 6) (args[5] as? Number)?.toLong() ?: 0L else 0L

                        // Registration flush: events the daemon stamped BEFORE we attached
                        // are stale queued input replayed at registration — never ink.
                        if (daemonNs in 1 until (attachedAtRealtimeNs - FLUSH_SLACK_NS)) {
                            InkPerf.record("pen.attach_flush", 0)
                            return@runCatching
                        }
                        if (daemonNs == 0L &&
                            android.os.SystemClock.uptimeMillis() - lastDaemonEventMs > 10_000 &&
                            lastDaemonEventMs != 0L
                        ) {
                            // No timestamps on this firmware: nothing to filter by; the
                            // wall fallback below only guards the first events ever.
                        }

                        // Fingers belong to the app's raw MotionEvent pipeline (swipe,
                        // scroll, pinch). Daemon finger events painted ink and doubled
                        // gesture streams — dropped whole, like the reference app.
                        if (tool == TOOL_FINGER) return@runCatching

                        if (daemonNs > 0L) {
                            InkPerf.record("pen.kernel_to_jvm",
                                System.currentTimeMillis() * 1_000_000L - daemonNs)
                        }
                        lastDaemonEventMs = android.os.SystemClock.uptimeMillis()

                        // -179: the daemon reports input already in the view frame;
                        // panelToView is identity (see its doc for the calibration story).
                        val (vx, vy) = panelToView(x, y)
                        if (action == ACTION_DOWN || action == ACTION_UP || action == ACTION_LEAVE) {
                            // Raw daemon coords + the panelMode that mapped them: the flipped-strokes
                            // calibration needs both sides of the transform in the log.
                            InkTrace.log("pen", "a=$action t=$tool raw=${x.toInt()},${y.toInt()} -> ${vx.toInt()},${vy.toInt()} panel=$panelMode view=${boundView?.width}x${boundView?.height}")
                        }
                        // The LIVE mark, on the binder thread — latency is the point.
                        drawLiveSegment(action, vx, vy, p, rubber = tool == TOOL_RUBBER)
                        if (!isDroppingStroke()) onPen(action, vx, vy, p, tool)
                        InkPerf.record("pen.event_handler", System.nanoTime() - handlerStart)
                    }
                }
                0
            }
            cls.getMethod("registerInputListener", listenerCls).invoke(c, proxy)

            val w = if (view.width > 0) view.width else view.holder.surfaceFrame.width()
            val h = if (view.height > 0) view.height else view.holder.surfaceFrame.height()
            // NEVER connect a zero-sized buffer: the daemon accepts it, claims the pen,
            // and inks into nothing ("daemon connected (0×0)" — the dead-stylus report).
            if (w <= 0 || h <= 0) {
                status = "view not laid out yet (${w}×${h})"
                Timber.w("BigmeFastInk: %s", status)
                runCatching { cls.getMethod("unBindView").invoke(c) }
                return false
            }
            val ok = cls.getMethod("connect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(c, w, h) as? Boolean ?: false
            if (!ok) {
                status = "daemon refused connect ($w×$h)"
                Timber.w("BigmeFastInk: %s", status)
                runCatching { cls.getMethod("unBindView").invoke(c) }
                return false
            }

            runCatching { cls.getMethod("updateLayout").invoke(c) }
            runCatching { cls.getMethod("updateRotation").invoke(c) }
            // The ION buffer is server-side state that survives reconnects still holding
            // the previous surface's ink. White = the overlay's transparent: prime it
            // white so nothing stale composites; the first syncOverlay brings the page.
            //
            // Pushed, not just written — the same reason `detach` pushes its clear. Priming the
            // buffer without a frame leaves the stale composite on the glass until whatever
            // happens to invalidate next, and "the first syncOverlay brings the page" is a
            // promise about the buffer, not about what the panel is showing in the meantime.
            // OVERLAY + BLEND FIRST, THEN THE PRIMING PUSH. The push below composites a zeroed
            // (transparent-black) buffer; pushed before blending is on, the B6 shows it as a
            // full-panel BLACK frame — "flashes all black when moving around, like there's a
            // surface hidden behind the surface" (Michael, 09-13). Every page change reattaches,
            // so every page change could flash. With blend on first, zero alpha is show-through.
            // THE OLD PAGE FLASHING UP ON LOAD (Michael, 09-14: "it still flashes the page back
            // up on load before showing the today page leaving a ton of ghosting"). A cold launch
            // never ran detach's clear, so the buffer still holds the LAST SESSION's page, and
            // enabling the overlay composites it onto the glass before the clear below can run.
            // The GU16 priming push then only darkens — the stale black stays as ghosting.
            // So: wipe the buffer BEFORE the overlay is switched on (a write, not a frame — no
            // black-frame risk, blend is not involved). If the canvas isn't reachable yet, the
            // priming push below becomes the erase-capable 1202 instead of the darken-only GU16.
            // A process that died mid-page never ran detach: the state may still be a page from
            // the last session (the cold-launch "old page flashes up"). White it before the
            // overlay can republish it. Traced either way so the field says whether a push
            // before setOverlayEnabled reaches the glass at all.
            if (stateDirty(view.context) && !SILENT_SEAMS) {
                InkTrace.log("daemon", "attach: state dirty from a previous session — whiting before overlay")
                commitWhiteLocked(c, cls, w, h, "attach/pre-overlay")
            }
            val clearedBeforeOverlay = runCatching {
                val cv = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return@runCatching false
                cv.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                true
            }.getOrDefault(false)
            InkTrace.log("daemon", "attach: buffer cleared before overlay = $clearedBeforeOverlay")
            // OVERLAY OFF — Bigme's own notes app never turns it on for a notes page (xNote
            // HandwritingManager: H=false, every connect sends setOverlayEnabled(false); only the
            // system-wide floating note, GlobalNoteView, turns it on). With it on, the panel buffer
            // rides along as an overlay and stale strokes / old screens flash up on refreshes —
            // Michael's "stuck image" (10-10 experiment 1: this switch ALONE, nothing else changed).
            cls.getMethod("setOverlayEnabled", Boolean::class.javaPrimitiveType).invoke(c, false)
            runCatching { cls.getMethod("setBlendEnabled", Boolean::class.javaPrimitiveType).invoke(c, true) }
            runCatching {
                (cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas)
                    ?.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                if (SILENT_SEAMS) InkTrace.log("daemon", "attach: silent (buffer cleared, no push)")
                else pushToGlass(c, cls, Rect(0, 0, w, h), if (clearedBeforeOverlay) MODE_GU16 else MODE_NORMAL_RECT,
                    "attach/prime", w, h)
            }
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, true)

            // Size BEFORE the client is published: a pen DOWN on the binder thread between the two
            // would find a client with no buffer bounds and pushToGlass would drop its dot.
            attachedWidth = w; attachedHeight = h
            truthWipePending = true
            client = c; clientClass = cls; boundView = view
            attached = true
            attachedAtRealtimeNs = System.currentTimeMillis() * 1_000_000L
            lastDaemonEventMs = android.os.SystemClock.uptimeMillis()
            // Rotation truth: what does the daemon believe vs what the display says?
            val dispRot = runCatching {
                @Suppress("DEPRECATION") view.display?.rotation ?: 0
            }.getOrDefault(0)
            val daemonRot = runCatching {
                (cls.getMethod("getCurViewRotation").invoke(c) as? Int) ?: 0
            }.getOrDefault(0)
            // MEASURE the daemon's frame: if its claimed layout is the SWAP of the view's
            // (or the display is rotated while the daemon claims it isn't), it is
            // panel-anchored — adapt, don't argue.
            val daemonLayout = runCatching { cls.getMethod("getViewLayout").invoke(c) as? Rect }.getOrNull()
            val swapped = daemonLayout != null && w != h &&
                daemonLayout.width() == h && daemonLayout.height() == w
            // panelMode is DIAGNOSTIC-ONLY since -179: panelToView is identity (the input
            // arrives in the view frame on this firmware), so nothing consumes this value —
            // it rides the status string and the pen trace so a future orientation bug can
            // be read straight off the log. The pre--179 axis-swap theory it used to drive
            // was itself the "old flipped strokes bug"; do not resurrect it.
            panelMode = when {
                w > h && dispRot == 1 -> 90
                w > h && dispRot == 3 -> 270
                swapped -> 90
                // Upside-down portrait: geometry can't reveal it (nothing swaps), only the
                // display's own rotation can.
                dispRot == 2 -> 180
                else -> 0
            }
            // (The calibration L-probe that used to draw at the daemon's raw origin is GONE —
            // Michael, 08-23: "get everything shifted back so I don't have weird Ls popping
            // up." It did its job in the 1.07.01–.05 rounds: the frame facts it revealed are
            // recorded in DESIGN-bigme-ink-2026-08-22.md, and panelMode above carries them.)
            status = "daemon connected ($w×$h, rot d$daemonRot/v$dispRot, panel=$panelMode)"
            Timber.i("BigmeFastInk: %s on %s", status, Build.MODEL)
            InkTrace.log("daemon", "attach: $status")
            true
        } catch (t: Throwable) {
            status = "attach failed: ${t.javaClass.simpleName}"
            Timber.w(t, "BigmeFastInk attach failed")
            InkTrace.log("daemon", "attach FAILED: ${t.javaClass.simpleName}: ${t.message}")
            client = null; clientClass = null; attached = false
            false
        }
    }

    private fun isDroppingStroke(): Boolean = droppingStroke

    /** Ms until the daemon's layer is quiet again after the last ink on this surface; 0 = now (or
     *  no client). Read on the main thread by the ⛶ toggle (plan v6 S1.2, Michael 2026-10-10). */
    fun msUntilQuiet(): Long {
        if (!attached || lastInkMs == 0L) return 0L
        return (LAYER_IDLE_MS - (android.os.SystemClock.uptimeMillis() - lastInkMs)).coerceAtLeast(0L)
    }

    /** True once if a forced push was cut around chrome since the last ask (see [cutoutDebt]).
     *  Under the lock: the mirror raises it on this thread, a scrub on the main one. */
    private fun takeCutoutDebt(): Boolean {
        inkLock.lock()
        try {
            val owed = cutoutDebt
            cutoutDebt = false
            return owed
        } finally {
            inkLock.unlock()
        }
    }

    /**
     * The main thread just took chrome off the surface (the flyout closed): settle [cutoutDebt]
     * ON THE INK THREAD, behind any mirror already queued or running — that mirror may punch the
     * old holes and raise the debt a moment after a main-thread check would have looked, and a
     * debt raised then was never paid (review 2026-10-10). [onOwed] comes back on the main thread.
     */
    fun settleCutoutDebt(onOwed: () -> Unit) {
        if (client == null) return
        inkHandler.post {
            if (dead) return@post
            if (takeCutoutDebt()) android.os.Handler(android.os.Looper.getMainLooper()).post(onOwed)
        }
    }

    /**
     * Chrome just came over the surface (the flyout opened): punch its rects into the daemon's
     * canvas at once, QUIETLY — a buffer write like the quiet mirror, no push — so ink the daemon
     * still carries from a stroke a moment ago can't reach the glass over the chrome before our
     * next write would have cut it (review 2026-10-10).
     */
    fun holdCutouts() {
        val c = client ?: return
        val cls = clientClass ?: return
        inkHandler.post {
            if (dead) return@post
            inkLock.lock()
            try {
                runCatching {
                    val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas
                        ?: return@runCatching
                    val v = boundView
                    if (v == null || v.width != attachedWidth || v.height != attachedHeight ||
                        !canvasMatchesAttach(canvas)) return@runCatching
                    punchCutouts(canvas)
                }
            } finally {
                inkLock.unlock()
            }
        }
    }

    /** Punch [chromeCutouts] back to transparent on the daemon [canvas] (only those meeting
     *  [within], when given). True when any hole was made. Transparent is never driven, so the
     *  chrome above shows through whatever push follows. */
    private fun punchCutouts(canvas: android.graphics.Canvas, within: Rect? = null): Boolean {
        val cuts = chromeCutouts
        if (cuts.isEmpty()) return false
        var holed = false
        for (r in cuts) {
            if (within != null && !Rect.intersects(r, within)) continue
            canvas.save()
            canvas.clipRect(r)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.restore()
            holed = true
        }
        return holed
    }

    private fun inCutout(x: Float, y: Float): Boolean =
        chromeCutouts.any { it.contains(x.toInt(), y.toInt()) }

    /**
     * One live segment onto the daemon's canvas + a batched EPD refresh. Runs on the
     * binder thread. A DOWN out of the view's bounds (the daemon reports toolbar taps at
     * negative view-local coords) discards the whole stroke.
     */
    private fun drawLiveSegment(action: Int, x: Float, y: Float, p: Float, rubber: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        val v = boundView
        inkLock.lock()
        try {
            drawLiveSegmentLocked(action, x, y, p, rubber, c, cls, v)
        } finally {
            inkLock.unlock()
        }
    }

    private fun drawLiveSegmentLocked(action: Int, x: Float, y: Float, p: Float, rubber: Boolean, c: Any, cls: Class<*>, v: SurfaceView?) {
        when (action) {
            ACTION_DOWN -> {
                droppingStroke = v != null &&
                    (x < 0f || y < 0f || x > v.width.toFloat() || y > v.height.toFloat())
                if (droppingStroke) {
                    InkPerf.record("pen.out_of_bounds", 0)
                    penIsDown = false
                    return
                }
                lastX = x; lastY = y; penIsDown = true
                penDownAtMs = android.os.SystemClock.uptimeMillis()
                lastInkMs = penDownAtMs
                accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
                lastCommitMs = penDownAtMs
                firstSegmentOfStroke = true
                downNanos = System.nanoTime()
                firstPaintPending = true
                // A DOWN on the full-screen chrome (S1.2) is a tap on it, never ink under it.
                liveMuted = inCutout(x, y) || runCatching { liveInkMutedAt(x, y) }.getOrDefault(false)
                if (liveMuted) InkTrace.log("pen", "down on a floating control at ${x.toInt()},${y.toInt()} — no live ink")
                if (!liveMuted && liveDrawAllowed()) runCatching {
                    // A tap must leave a mark NOW: paint the down-dot and refresh it
                    // tightly, or dots only appear at the bake.
                    val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas
                        ?: return
                    val paint = if (rubber) rubberPaint else livePaint
                    if (!rubber) paint.strokeWidth = segmentWidthPx(0f, p).coerceAtLeast(1f)
                    canvas.drawPoint(x, y, paint)
                    val pad = (paint.strokeWidth + 4f).toInt()
                    val dot = Rect(x.toInt() - pad, y.toInt() - pad, x.toInt() + pad, y.toInt() + pad)
                    pushToGlass(c, cls, dot, if (rubber) MODE_RUBBER else MODE_HANDWRITE, "pen/dot")
                }
            }
            ACTION_MOVE -> {
                if (!penIsDown || droppingStroke) return
                lastInkMs = android.os.SystemClock.uptimeMillis()
                if (liveMuted || !liveDrawAllowed()) { lastX = x; lastY = y; return }
                val dx = x - lastX
                val dy = y - lastY
                // A segment no physical pen could produce is not ink — skip and re-anchor.
                if (dx * dx + dy * dy > jumpThresholdSq()) {
                    InkPerf.record("pen.garbage_move", 0)
                    lastX = x; lastY = y
                    return
                }
                runCatching {
                    val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas
                        ?: return
                    val paint = if (rubber) rubberPaint else livePaint
                    if (!rubber) {
                        paint.strokeWidth =
                            segmentWidthPx(kotlin.math.atan2(dy, dx), p).coerceAtLeast(1f)
                    }
                    // A stroke that wanders under the full-screen chrome draws round it (S1.2).
                    val cuts = chromeCutouts
                    if (cuts.isNotEmpty()) { canvas.save(); for (r in cuts) canvas.clipOutRect(r) }
                    canvas.drawLine(lastX, lastY, x, y, paint)
                    if (cuts.isNotEmpty()) canvas.restore()
                    val pad = (paint.strokeWidth + 4f).toInt()
                    accumDirty.union(
                        (minOf(lastX, x).toInt() - pad), (minOf(lastY, y).toInt() - pad),
                        (maxOf(lastX, x).toInt() + pad), (maxOf(lastY, y).toInt() + pad))
                    val now = android.os.SystemClock.uptimeMillis()
                    if (firstSegmentOfStroke || now - lastCommitMs >= COMMIT_INTERVAL_MS) {
                        firstSegmentOfStroke = false
                        commitDirty(cls, c, rubber)
                        lastCommitMs = now
                    }
                }.onFailure { Timber.d(it, "BigmeFastInk live segment failed") }
                lastX = x; lastY = y
            }
            ACTION_UP, ACTION_LEAVE -> {
                penIsDown = false
                val wasDropping = droppingStroke
                droppingStroke = false
                if (!wasDropping) lastInkMs = android.os.SystemClock.uptimeMillis()
                if (!wasDropping) runCatching { commitDirty(cls, c, rubber) }
            }
        }
    }

    /** Push the accumulated dirty rect to the EPD and reset it. */
    private fun commitDirty(cls: Class<*>, c: Any, rubber: Boolean = false) {
        if (accumDirty.left == Int.MAX_VALUE) return
        pushToGlass(c, cls, Rect(accumDirty), if (rubber) MODE_RUBBER else MODE_HANDWRITE, "pen/segment")
        accumDirty.set(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
        if (firstPaintPending) {
            firstPaintPending = false
            InkPerf.record("pen.down_to_first_paint", System.nanoTime() - downNanos)
        }
    }

    /** Drive the whole [w]×[h] view to OPAQUE WHITE with one 1202 — the only payload that is
     *  harmless when the daemon republishes it: everything the app paints afterwards only
     *  darkens. Used at the seams (detach, and attach after a process death) so the stored
     *  state is never a page. */
    private fun commitWhiteLocked(c: Any, cls: Class<*>, w: Int, h: Int, label: String) {
        if (w <= 0 || h <= 0) return
        runCatching {
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas ?: return
            canvas.save(); canvas.clipRect(0, 0, w, h)
            // NOT PURE WHITE. The daemon keys pure white as TRANSPARENT (that is how every clear
            // in this file has always worked), so a white commit is a transparent frame — and
            // transparent frames neither drive the glass nor replace the stored state (law 3).
            // -284..-286 "whited" the store and changed nothing (Michael, 09-16: "every time I
            // change a surface it flashes the old image"). One step below white is opaque to the
            // daemon and still the panel's whitest level.
            canvas.drawColor(OPAQUE_WHITE)
            canvas.restore()
            // The full-screen chrome stays itself through a seam white too (S1.2).
            punchCutouts(canvas, Rect(0, 0, w, h))
            InkTrace.log("daemon", "commit off-white ${w}x$h ($label, 1202)")
            pushToGlass(c, cls, Rect(0, 0, w, h), MODE_NORMAL_RECT, "white/$label", w, h)
            markStateWhite(boundView?.context)
        }.onFailure { InkTrace.log("daemon", "commit WHITE FAILED ($label): ${it.javaClass.simpleName}") }
    }

    /**
     * Mirror the canonical page into the daemon's buffer and converge the glass.
     *
     * TRANSPARENT base + [page] (the app's transparent-ink shadow, mapped by
     * [transform]). The B6 alpha-composites its overlay: transparent is the
     * show-through, WHITE IS OPAQUE PAINT — a white base papered over the template,
     * the nav ball, and the notes nav ("when I write, the background and the nav
     * disappear"). The ink occludes where it exists; everything else stays visible;
     * the buffer still CARRIES the ink across the system e-ink refresh. One flashless
     * full-view GU16 makes buffer and glass converge (the quiet-blit variant left them
     * alternating on repeated refresh presses). Never called mid-stroke — but a wedged
     * penIsDown (daemon lost the UP: modal mid-stroke, breakage) self-heals after
     * [PEN_DOWN_WEDGE_MS] so the mirror can't be starved into staleness.
     *
     * [template]: the template in [page]'s OWN frame, for the first-draw truth — the page-space
     * bitmap when [page] is the page-space shadow, the view-space redraw when [page] is the
     * zoomed resolution's view-sized buffer under an identity [transform] (ZoomClarity; Michael
     * 2026-10-12, 16× with no loss of clarity). Null = [templateProvider], as before.
     */
    fun syncOverlay(page: android.graphics.Bitmap, transform: android.graphics.Matrix, force: Boolean = false,
                    bypassWedge: Boolean = false, template: (() -> android.graphics.Bitmap?)? = null) {
        val c = client ?: return
        val cls = clientClass ?: return
        // The daemon's refresh can block for hundreds of ms — NEVER on the caller's
        // (main) thread. Coalesced: a newer mirror request replaces any queued one.
        val snap = android.graphics.Matrix(transform)
        inkHandler.removeCallbacksAndMessages(MIRROR_TOKEN)
        // Through inkHandler itself: removeCallbacksAndMessages(MIRROR_TOKEN) only drops messages
        // whose target is THAT handler — a fresh Handler made the coalescing a no-op.
        inkHandler.postAtTime({
            if (dead) return@postAtTime
            inkLock.lock()
            try { syncOverlayLocked(page, snap, c, cls, force, bypassWedge, template) } finally { inkLock.unlock() }
        }, MIRROR_TOKEN, android.os.SystemClock.uptimeMillis())
    }

    private fun syncOverlayLocked(page: android.graphics.Bitmap, transform: android.graphics.Matrix, c: Any, cls: Class<*>, force: Boolean, bypassWedge: Boolean = false,
                                  template: (() -> android.graphics.Bitmap?)? = null) {
        // A chip action (delete/cut/task) is a deliberate TAP — it sets penIsDown but has no ink
        // in flight, so the pen-down wedge must not starve its refresh (09-03: it "doesn't show
        // until refresh" when you pick what to do with the selection).
        if (penIsDown && !bypassWedge) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - maxOf(lastDaemonEventMs, penDownAtMs) < PEN_DOWN_WEDGE_MS) {
                InkTrace.log("mirror", "skipped: pen-down wedge")
                return
            }
            penIsDown = false
            droppingStroke = false
            InkPerf.record("pen.heal.wedged_down", 0)
        }
        runCatching {
            val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas
                ?: return
            // Rotation-race guard (law #5): during the B6's v1/v3 recreate storm the view
            // can already be in the NEXT orientation while the daemon canvas still holds the
            // previous one — a blit through a viewMatrix built for the wrong frame is the
            // 09-03 "strange bizarre circles" corruption. The page bitmap is document-space
            // (CANVAS_WIDTH×CANVAS_HEIGHT, orientation-free); it is the VIEW vs the CANVAS
            // that must agree. Skip on mismatch and let a matching sync land after settle.
            val gv = boundView
            if (gv == null || gv.width != attachedWidth || gv.height != attachedHeight ||
                !canvasMatchesAttach(canvas)) {
                InkTrace.log("mirror", "SKIPPED: view=${gv?.width}x${gv?.height} attached=${attachedWidth}x${attachedHeight} canvas=${canvas.width}x${canvas.height}")
                return
            }
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            // FIELD-CALIBRATED (the L-glance: "top-left, arms right and down"): the
            // daemon's DRAWING canvas is view-aligned even when its INPUT is rotated —
            // two frames in one daemon. Only input adapts (panelToView in the proxy);
            // drawing and this mirror stay in the view's frame. Rotating them too was
            // .01's flip-manufacturer.
            canvas.drawBitmap(page, transform, null)
            // Ink under the full-screen chrome (plan v6 S1.2) never covers it: its rects go back
            // to transparent before anything can reach the glass.
            val holed = punchCutouts(canvas)
            // inkit's rule, exactly: pen strokes get a QUIET blit (the buffer is primed,
            // the panel is left alone); only erase and page events force the GU16 —
            // a full-view refresh on every pen-up is what flickered the template and
            // nav away while writing.
            if (!force) return   // quiet blit — unlogged: it fires per bake incl. pinch/pan storms
            if (holed) cutoutDebt = true
            val v = boundView
            val rect = Rect(0, 0, v?.width ?: canvas.width, v?.height ?: canvas.height)
            if (truthWipePending) {
                // FIRST PAGE DRAW AFTER A CONNECT (10-10, experiment 3): the daemon's panel buffer
                // still holds the last session's strokes (silent seams no longer white it), and a
                // GU16 can only darken — "switched out, closed the app and opened it again, and
                // now there is something stuck". Copy xNote: the buffer gets the WHOLE page,
                // opaque (paper + template + ink, the 🧹 truth), and it goes up as 1202, the one
                // mode that lightens. Once per attach; later forced mirrors stay GU16.
                truthWipePending = false
                canvas.drawColor(android.graphics.Color.WHITE)
                (template ?: templateProvider)?.invoke()?.let { canvas.drawBitmap(it, transform, null) }
                canvas.drawBitmap(page, transform, null)
                punchCutouts(canvas)
                InkTrace.log("mirror", "first draw after attach: truth 1202 $rect")
                pushAround(c, cls, rect, MODE_NORMAL_RECT, "mirror/truth")
                markOpaqueCommit(v?.context)
                return@runCatching
            }
            InkTrace.log("mirror", "forced GU16 $rect")
            pushAround(c, cls, rect, MODE_GU16, "mirror")
        }.onFailure { Timber.d(it, "BigmeFastInk syncOverlay failed") }
    }

    /** The largest squared point-to-point jump a real pen stroke can contain, in view
     *  px² — a quarter of the view diagonal between consecutive samples is beyond any
     *  hand. GARBAGE rejection only; never a same-stroke test (a quarter-screen "same
     *  stroke" radius is what welded consecutive strokes into phantom straight lines). */
    fun jumpThresholdSq(): Float {
        val v = boundView
        val diagSq = if (v != null && v.width > 0)
            (v.width * v.width + v.height * v.height).toFloat() else 1_000_000f
        return diagSq * 0.0625f   // (0.25 × diagonal)²
    }

    /**
     * inkit's clearRegion discipline, region-scoped: drive [rect] to its TRUE content and
     * ERASE everything else there — the one way to lift stale live ink (the auto-lasso ring
     * the daemon painted under the pen) off the glass. Transparent overlay pixels are never
     * driven and GU16 only darkens, so this paints opaque WHITE clipped to the rect (the
     * paper), lays the page's real ink over it from [page] (mapped by [transform]), and
     * flushes with MODE_NORMAL_RECT 1202 — the erase-capable partial. The caller detaches
     * right after; detach's full transparent commit supersedes this frame in the daemon's
     * commit-state buffer, so nothing here can ever republish as a ghost.
     *
     * Blocking daemon call — runs inline on the caller's thread by design: it must complete
     * BEFORE the detach that follows it, and it is rect-scoped (tens of ms, a tap moment).
     */
    fun scrubRegion(rect: Rect, page: android.graphics.Bitmap?, transform: android.graphics.Matrix?,
                    template: android.graphics.Bitmap? = null, label: String = "white+truth") {
        val c = client ?: return
        val cls = clientClass ?: return
        inkLock.lock()
        try {
            runCatching {
                val canvas = cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas
                    ?: return@runCatching
                // LAW #5 (09-03): a 1202 commit PERSISTS in the daemon's replay buffer — an
                // orientation-mismatched blit becomes a permanent ghost that survives app
                // reinstall. Refuse to scrub unless the view, the attach-time dims, and the
                // daemon canvas all agree RIGHT NOW; a skipped scrub costs one lingering
                // ring, a wrong one costs a cold power-off.
                val v = boundView
                if (v == null || v.width != attachedWidth || v.height != attachedHeight ||
                    !canvasMatchesAttach(canvas)) {
                    InkTrace.log("daemon", "scrubRegion SKIPPED: dims view=${v?.width}x${v?.height} " +
                        "attached=${attachedWidth}x${attachedHeight} canvas=${canvas.width}x${canvas.height}")
                    return@runCatching
                }
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawColor(android.graphics.Color.WHITE)
                // The shadow bitmap is INK-ONLY — white+ink alone wipes the page TEMPLATE
                // (ruling, furniture) wherever a scrub lands: the "big white square dragged
                // behind the selection". Truth is white + template + ink, in that order.
                if (template != null && transform != null) canvas.drawBitmap(template, transform, null)
                if (page != null && transform != null) canvas.drawBitmap(page, transform, null)
                // The full-screen chrome inside the rect stays the chrome's (S1.2) — 🧹 included.
                if (punchCutouts(canvas, rect)) cutoutDebt = true
                canvas.restore()
                InkTrace.log("daemon", "scrubRegion $rect ($label, 1202)")
                pushAround(c, cls, Rect(rect), MODE_NORMAL_RECT, "scrub/$label")
                markOpaqueCommit(boundView?.context)
            }.onFailure { Timber.d(it, "BigmeFastInk scrubRegion failed") }
        } finally {
            inkLock.unlock()
        }
    }

    // ---- Drag animation (-190) ----------------------------------------------------------
    // -183 animated the drag by firing a 1202 per 120ms software frame, inline on the main
    // thread, fire-and-forget: the panel physically needs ~200-400ms per 1202 partial, so
    // the waveforms piled up into in-box flicker and daemon corruption (-184 field). The
    // frames now go through the ink thread ONE AT A TIME — a frame is scheduled only when
    // no frame is in flight and [DRAG_SCRUB_GAP_MS] has passed since the last one FINISHED.
    // ~3 frames/s: enough to see where to pen up, never a piled waveform. The caller keeps
    // the last DRIVEN footprint so each frame erases exactly what the glass last showed.
    @Volatile private var dragScrubInFlight = false
    @Volatile private var dragScrubStartedMs = 0L
    @Volatile private var lastDragScrubDoneMs = 0L

    /** Schedule one drag frame: drive [rect] to truth on the ink thread. Returns true only if
     *  the frame was accepted (the caller then treats [rect] as the new on-glass footprint). */
    fun scrubRegionForDrag(rect: Rect, page: android.graphics.Bitmap?, transform: android.graphics.Matrix?,
                           template: android.graphics.Bitmap? = null): Boolean {
        if (dead || client == null) return false
        val now = android.os.SystemClock.uptimeMillis()
        // A stuck flag must never freeze the animation for the rest of the session.
        if (dragScrubInFlight && now - dragScrubStartedMs < 2_000L) return false
        if (now - lastDragScrubDoneMs < DRAG_SCRUB_GAP_MS) return false
        dragScrubInFlight = true
        dragScrubStartedMs = now
        val snap = transform?.let { android.graphics.Matrix(it) }
        inkHandler.post {
            try {
                if (!dead) scrubRegion(rect, page, snap, template, label = "drag frame")
            } finally {
                lastDragScrubDoneMs = android.os.SystemClock.uptimeMillis()
                dragScrubInFlight = false
            }
        }
        return true
    }

    /** Pause/resume the daemon's claim on the pen (menus and dialogs need the pen back —
     *  "the daemon dispatches pen events by view bounds, not Z-order"). Pausing also
     *  ends any in-flight live stroke: its UP will never arrive. */
    fun setInputEnabled(enabled: Boolean) {
        val c = client ?: return
        val cls = clientClass ?: return
        if (!enabled) {
            inkLock.lock()
            try { penIsDown = false; droppingStroke = false } finally { inkLock.unlock() }
        }
        InkTrace.log("daemon", "setInputEnabled($enabled)")
        runCatching {
            cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, enabled)
        }.onFailure { Timber.d(it, "BigmeFastInk setInputEnabled failed") }
    }

    /**
     * [pushToGlass], but never over the full-screen chrome (plan v6 S1.2): while [chromeCutouts]
     * meet [rect], the push goes up as the rects AROUND them (two, for the flyout at a corner)
     * instead of one rect with a transparent hole. A hole keeps our buffer off the glass, but the
     * daemon's shared panel buffer under it may still hold an older page, and a push over it could
     * print that over the open flyout — an undo from the flyout forces exactly such a push (review
     * 2026-10-10). With no chrome over the surface it is the one push it always was.
     */
    private fun pushAround(c: Any, cls: Class<*>, rect: Rect, mode: Int, label: String) {
        val cuts = chromeCutouts.filter { Rect.intersects(it, rect) }
        if (cuts.isEmpty()) { pushToGlass(c, cls, rect, mode, label); return }
        val region = android.graphics.Region(rect)
        for (r in cuts) region.op(r, android.graphics.Region.Op.DIFFERENCE)
        val parts = android.graphics.RegionIterator(region)
        val part = Rect()
        while (parts.next(part)) pushToGlass(c, cls, Rect(part), mode, "$label/around")
    }

    /**
     * THE ONLY ROAD TO inValidate (10-05). Every rect the daemon is handed is trimmed to the buffer
     * it was connected with — the VIEW frame, [attachedWidth]×[attachedHeight] (the daemon rotates
     * it to panel coordinates itself) — and an empty one is never sent. The vendor blender reads
     * the client buffer for the whole rect without checking: a 1354×1354 square on a 1354×986
     * buffer SIGSEGV'd handwrittenservice (Blender::blendToFrameBuffer, 10-05 13:50:59) and took
     * the phone's display with it. Detach after a rotation measured the NEW view (1362×978) over
     * the old portrait buffer (986×1354) — the same overrun, waiting. [bw]/[bh] override only
     * where the buffer is being created or whited before [attachedWidth] is set.
     */
    private fun pushToGlass(c: Any, cls: Class<*>, rect: Rect, mode: Int, label: String,
                            bw: Int = attachedWidth, bh: Int = attachedHeight): Boolean {
        if (bw <= 0 || bh <= 0) { InkTrace.log("daemon", "push skipped ($label): no buffer"); return false }
        val r = Rect(rect)
        if (!r.intersect(0, 0, bw, bh) || r.isEmpty) return false
        // Not for pen pushes: an edge stroke clamps every 16ms and would flood the trace.
        if (r != rect && !label.startsWith("pen/")) InkTrace.log("daemon", "push clamped ($label): $rect -> $r (buffer ${bw}x$bh)")
        cls.getMethod("inValidate", Rect::class.java, Int::class.javaPrimitiveType).invoke(c, r, mode)
        return true
    }

    fun detach() {
        dead = true
        val c = client ?: return
        val cls = clientClass ?: return
        InkTrace.log("daemon", "detach (was $attachedWidth×$attachedHeight)")
        runCatching { cls.getMethod("setInputEnabled", Boolean::class.javaPrimitiveType).invoke(c, false) }
        // Best-effort real unregister — the dead switch is the guarantee either way.
        runCatching {
            val m = cls.methods.firstOrNull { it.name == "unregisterInputListener" }
            if (m != null) {
                if (m.parameterTypes.isEmpty()) m.invoke(c) else m.invoke(c, null)
            }
        }
        // Leave the buffer white (= transparent) for whoever binds next — AND PUSH IT.
        //
        // THE OVERLAY BLEED (the second fault the 08-23 portrait-only retreat named, and the one
        // that survived: "its overlay bleeds across surfaces"). Clearing the ION buffer is a write
        // into server-side memory; it is not a frame. Every other draw in this file pairs its
        // canvas work with an `inValidate` — that is what puts pixels on the glass (see
        // `commitDirty` and `syncOverlayLocked`). This one cleared and then unbound, so the LAST
        // COMPOSITED FRAME stayed on the panel: the previous surface's ink, alpha-composited over
        // whatever bound next. Portrait mostly hid it because the daemon stayed attached across
        // page changes; landscape detaches and reattaches constantly, which is why the bleed read
        // as a landscape fault rather than a teardown one.
        // THE STORED STATE MUST NOT BE A PAGE (09-14). If any white+truth scrub ran on this page,
        // the daemon holds that picture and will paint it over whatever attaches next. Drive the
        // view to plain white first — the one payload the next page can only darken.
        // A ROTATION's detach: the view already has the new size, and whether the client still maps
        // the full attached rect onto its buffer is the vendor's business. Don't gamble a whole-buffer
        // 1202 on it — leave the state dirty; the next attach whites it at matching dimensions.
        val bv = boundView
        if (stateDirty(bv?.context) && !SILENT_SEAMS) {
            if (bv != null && (bv.width != attachedWidth || bv.height != attachedHeight))
                InkTrace.log("daemon", "detach: view ${bv.width}x${bv.height} != buffer ${attachedWidth}x$attachedHeight — white left to next attach")
            else commitWhiteLocked(c, cls, attachedWidth, attachedHeight, "detach")
        }
        runCatching {
            val v = boundView
            (cls.getMethod("getCanvas").invoke(c) as? android.graphics.Canvas)
                ?.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            // GU16 is the mode the full-surface mirror uses: one flashless whole-panel converge,
            // which is exactly what "there is nothing here now" should look like.
            val rect = Rect(0, 0, v?.width ?: 0, v?.height ?: 0)
            if (SILENT_SEAMS) InkTrace.log("daemon", "detach: silent (buffer cleared, no push)")
            else if (rect.width() > 0 && rect.height() > 0) pushToGlass(c, cls, rect, MODE_GU16, "detach")
        }
        runCatching { cls.getMethod("unBindView").invoke(c) }
        runCatching { cls.getMethod("disconnect").invoke(c) }
        client = null; clientClass = null; boundView = null
        attached = false
        attachedWidth = 0; attachedHeight = 0
        status = "detached"
        inkHandler.removeCallbacksAndMessages(null)
        inkThread.quitSafely()
    }
}
