package com.toolsboox.ot

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import timber.log.Timber

/**
 * Viwoods AiPaper fast-ink backend.
 *
 * Background: true hardware fast-ink (the T1000 AutoDraw / `initWriting` path used by
 * Viwoods' own apps) is NOT reachable from a sideloaded app — `libpaintworker.so`
 * crashes in JNI_OnLoad on an `untrusted_app` SELinux context (it needs `/dev/t1000_spi`,
 * the display compositor and `/dev/input`, all system-only). See jdkruzr's RE notes:
 * github.com/jdkruzr/ViwoodsAppDev. That path needs root or a system/product-app install.
 *
 * What works WITHOUT root: switch the panel to the FAST e-ink waveform via the
 * `ENoteSetting` binder service (direct IBinder.transact from our process), so the app's
 * own SurfaceView stroke posts refresh quickly instead of using the slow GL16 reading
 * waveform. The app still renders the strokes itself (software). The AutoDraw enable
 * transacts are also fired in case a given firmware unit happens to honour them, but the
 * FAST waveform is what produces the visible speed-up.
 *
 * Every reflective/binder call is guarded; inert on non-Viwoods hardware. Gate on
 * [isAvailable] before constructing.
 */
class ViwoodsFastInk {

    private var enote: Any? = null          // android.os.enote.ENoteSetting.getInstance()
    private var appContext: Context? = null

    var attached: Boolean = false
        private set

    /**
     * True once [initWriting] has been activated — i.e. the native full-screen T1000 fast-ink
     * is rendering pen strokes in hardware. Only possible on the targetSdk-30 (viwoods) build.
     * When true the app must NOT software-draw the live stroke (the hardware does it).
     */
    var hardwareInk: Boolean = false
        private set

    private var initWritingDone = false

    /** Read a system property via reflection (hidden-API bypass is active). */
    private fun getProp(key: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) { "" }

    companion object {
        private const val ENOTE_CLASS = "android.os.enote.ENoteSetting"
        private const val IFACE_TOKEN = "android.os.enote.IENoteSetting"
        private const val SERVICE_NAME = "ENoteSetting"

        const val MODE_FAST = 4
        const val MODE_GL16 = 3
        const val MODE_GC = 17

        // Transaction codes from IENoteSetting.Stub (jdkruzr's decompilation).
        private const val TXN_SET_AUTODRAW_ENABLE = 20
        private const val TXN_SET_AUTODRAW_TOOLTYPE = 21
        private const val TXN_SET_AUTODRAW_PENWIDTH = 23
        private const val TXN_ADD_AUTODRAW_RECT = 24
        private const val TXN_SET_ALL_REGION_UNAUTODRAW = 28
        private const val TXN_STOP_HANDWRITE_INTERCEPT = 29
        private const val TXN_SET_PICTURE_MODE = 13

        // callT1000CmdIIsI(int type, int[] values) lives at transaction 7.
        private const val TXN_T1000_CALL = 7
        private const val T1000_SEND_HANDWRITING_RANGE = 14
        private const val T1000_SET_HANDWRITING_ENABLE = 17

        private var bypassDone = false

        /** Lift hidden-API enforcement process-wide (needed at high targetSdk). Idempotent. */
        private fun ensureHiddenApiBypass() {
            if (bypassDone) return
            bypassDone = true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            try {
                val classClass = Class::class.java
                val forName = classClass.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = classClass.getDeclaredMethod(
                    "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
                )
                val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime = getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", arrayOfNulls<Class<*>>(0)
                ) as java.lang.reflect.Method
                val setExemptions = getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(arrayOf<String>()::class.java)
                ) as java.lang.reflect.Method
                // "L" is the prefix for every Ljava-style signature → exempts everything.
                setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
            } catch (t: Throwable) {
                Timber.w(t, "ViwoodsFastInk hidden-API bypass failed")
            }
        }

        /** True only on Viwoods AiPaper hardware (ENoteSetting present). Cached. */
        val isAvailable: Boolean by lazy {
            ensureHiddenApiBypass()
            try {
                Class.forName(ENOTE_CLASS)
                Timber.i("ViwoodsFastInk available on ${Build.MANUFACTURER}/${Build.MODEL}")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Force-initialise the NoteJNI stub so its static {} block runs `System.loadLibrary("paintworker")`
     * from the APP classloader. This MUST happen before any ENoteSetting call that could load the
     * library from the framework (boot classloader) — otherwise libpaintworker's JNI_OnLoad FindClass
     * for com.iflytek.ainote.handwrite.NoteJNI fails ("NoClassDefFound … NoteJNI" → "检测到异常") and the
     * native pen callbacks never register (overlay enables but paints nothing). No-op off the viwoods
     * flavor (the stub only exists in src/viwoods).
     */
    private fun ensureNoteJniLoaded() {
        try {
            Class.forName("com.iflytek.ainote.handwrite.NoteJNI", true, this.javaClass.classLoader)
            Timber.i("ViwoodsFastInk NoteJNI initialised (libpaintworker loaded from app classloader)")
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk NoteJNI init failed")
        }
    }

    /** Obtain the ENoteSetting singleton and bind the app context. */
    fun attach(context: Context) {
        if (!isAvailable || attached) return
        appContext = context.applicationContext
        ensureNoteJniLoaded()
        try {
            enote = Class.forName(ENOTE_CLASS).getMethod("getInstance").invoke(null)
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk getInstance failed")
            return
        }
        reflect("setApplicationContext", arrayOf(Context::class.java), appContext)
        attached = true
    }

    /**
     * Activate fast ink. [w]x[h] are the full-screen dimensions.
     *
     * When [useInitWriting] is true (the targetSdk-30 viwoods build, which runs in the
     * untrusted_app_30 SELinux domain), `initWriting()` loads libpaintworker.so and the
     * T1000 renders pen strokes natively, full-screen — true instant hardware ink. This is
     * the confirmed working path; it is NEVER called on the standard build (targetSdk 36),
     * where it would crash in JNI_OnLoad.
     *
     * Otherwise we fall back to setting the FAST waveform so the app's own software stroke
     * posts refresh faster than the GL16 reading waveform. Idempotent.
     */
    fun enable(w: Int, h: Int, useInitWriting: Boolean, penMin: Int = 1, penMax: Int = 3) {
        if (enote == null) return
        Timber.i("ViwoodsFastInk.enable ${w}x${h} useInitWriting=$useInitWriting focusmonitor=[${getProp("persist.sys.focusmonitor.config")}]")
        if (useInitWriting && !initWritingDone) {
            initWritingDone = true
            // WiNote's EXACT in-process recipe — derived empirically 2026-07-11 by diffing WiNote's
            // working writing session against a failing sideloaded app on the same freshly-rebooted
            // (clean) WritingProducer BufferQueue:
            //   WiNote (works):  native_is_handwriting_enable:1 → WritingSurface::init
            //                    (mConnectedToCpu:0, NO error) → native_is_overlay_enable:1 — ALL in
            //                    WiNote's own process; system_server never touches the surface.
            //   sideloaded PoC:  additionally drove the AutoDraw binder path (setT1000AutoDrawEnable/
            //                    addAutoDrawRect/setAllRegionUnAutoDraw) + T1000 arm, which made
            //                    system_server's HandWritePolicy.updateAutoDrawRegion spin up its OWN
            //                    WritingSurface that can't lock the producer → lock error:-22 (spammed),
            //                    and produced no ink. mConnectedToCpu:0 was a red herring (WiNote has
            //                    it too). The AutoDraw/T1000 calls were the whole bug.
            // So: run ONLY the in-process JNI calls, exactly like WiNote. No binder AutoDraw, no T1000,
            // no ENoteWriting.setAutoDrawRects. onWritingStart()/onWritingEnd() are driven per stroke
            // (native_is_overlay_enable) from onStrokeStart/onStrokeEnd.
            // Prereq (out-of-band): persist.sys.focusmonitor.config=1 present AT BOOT + targetSdk 30.
            // libpaintworker.so is loaded (with the app classloader) by NoteJNI's static block via
            // [ensureNoteJniLoaded], called from attach() before any ENoteSetting use — that is what
            // lets JNI_OnLoad's FindClass resolve NoteJNI for RegisterNatives.
            reflect("initWriting", arrayOf())
            reflect("setWritingEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), true)
            registerWritingInputListener()
            hardwareInk = true
            Timber.i("ViwoodsFastInk hardware fast-ink (WiNote in-process recipe) applied ${w}x${h}")
        }
        // FAST waveform for the panel. Harmless/self-correcting and shared with the software path.
        // (WiNote manages its own waveform; setting FAST here does not go through the AutoDraw path.)
        transact(TXN_SET_PICTURE_MODE, MODE_FAST)
            ?: reflect("setPictureMode", arrayOf(Int::class.javaPrimitiveType!!), MODE_FAST)
    }

    /**
     * Hand the native writing layer the current page as its background/content bitmap. WITHOUT
     * this the overlay enables but `bufWorker` is null (native has no buffer to composite the fast
     * ink into) → the stroke never paints. WiNote calls the native equivalents
     * (`setmBackgroudBitmap` + `setFastShowContentBitmap`) — derived from its success trace.
     * [bmp] must be the full device-resolution page (e.g. 1440×1920) so the 1-bit fast stroke
     * appears over the real page content. Call on stroke start (and after [enable]).
     */
    fun setPageBitmap(bmp: android.graphics.Bitmap, rotation: Int = 0) {
        if (!hardwareInk) { Timber.w("ViwoodsFastInk.setPageBitmap skipped: hardwareInk=false"); return }
        val types = arrayOf(
            android.graphics.Bitmap::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        )
        val bg = reflect("setWritingJavaBackgroundBitmap", types, bmp, rotation, 0, 0)
        val fg = reflect("setWritingJavaBitmap", types, bmp, rotation, 0, 0)
        Timber.i("ViwoodsFastInk.setPageBitmap ${bmp.width}x${bmp.height} rot=$rotation config=${bmp.config} bg=$bg fg=$fg")
    }

    /**
     * Register a writing input listener so libpaintworker starts its native INPUT worker — the
     * thread that reads the pen digitizer (/dev/input/event6) and draws strokes onto the writing
     * bitmap in hardware. Without it the native side has a canvas (background bitmap) but nothing
     * driving strokes onto it ("inputWorker == NULL && bufWorker == NULL"), so nothing paints.
     * Derived from libpaintworker RE: native_set_input_listener creates the input worker; WiNote
     * calls setWritingInputlistener, the fork never did. The listener itself can be a no-op — the
     * native worker draws directly; the callback is only an app-side notification.
     */
    private fun registerWritingInputListener() {
        try {
            val ln = Class.forName("android.os.enote.ENoteWritingInputListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                ln.classLoader, arrayOf(ln)
            ) { _, method, _ -> if (method.returnType == Void.TYPE) null else defaultReturn(method.returnType) }
            reflect("setWritingInputlistener", arrayOf(ln), proxy)
            Timber.i("ViwoodsFastInk registered writing input listener (native pen intercept)")
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk registerWritingInputListener failed")
        }
    }

    private fun defaultReturn(t: Class<*>): Any? = when (t) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType, Short::class.javaPrimitiveType, Byte::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> ' '
        else -> null
    }

    /** Signal stroke start to the native layer (quality-redraw bookkeeping). Hardware path only. */
    fun onStrokeStart() {
        if (hardwareInk) reflect("onWritingStart", arrayOf())
    }

    /**
     * Re-assert the FAST waveform for the software path. The panel reverts to the GL16
     * reading waveform on full-page redraws (e.g. swiping between the day and notes/journal
     * pages), so FAST set once at surface creation isn't enough — call this on each pen-down
     * so every page writes quickly. No-op on the hardware path (the native layer owns refresh).
     */
    fun reassertFastMode() {
        if (enote == null || hardwareInk) return
        transact(TXN_SET_PICTURE_MODE, MODE_FAST)
            ?: reflect("setPictureMode", arrayOf(Int::class.javaPrimitiveType!!), MODE_FAST)
    }

    /** Signal stroke end — triggers the native quality redraw. Hardware path only. */
    fun onStrokeEnd() {
        if (hardwareInk) reflect("onWritingEnd", arrayOf())
    }

    /** Tear down fast ink and return the panel to the reading (GL16) waveform. Call from onPause. */
    fun disable() {
        if (enote == null) return
        if (hardwareInk) {
            // Mirror WiNote's teardown: disable handwriting + exit the writing surface, in-process.
            // No AutoDraw/handwrite-intercept binder calls (enable() no longer arms them, and they
            // are device-global state owned by the native note apps — we must not touch it).
            reflect("setWritingEnabled", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            reflect("exitWriting", arrayOf())
            hardwareInk = false
            initWritingDone = false
        }
        transact(TXN_SET_PICTURE_MODE, MODE_GL16)
            ?: reflect("setPictureMode", arrayOf(Int::class.javaPrimitiveType!!), MODE_GL16)
    }

    // === reflection on the ENoteSetting wrapper ===

    private fun reflect(name: String, types: Array<Class<*>>, vararg args: Any?): String? {
        val e = enote ?: return null
        return try {
            e.javaClass.getMethod(name, *types).apply { isAccessible = true }.invoke(e, *args)
            "ok"
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk.reflect($name)")
            null
        }
    }

    // === direct IBinder.transact (correct PID) ===

    private fun serviceBinder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, SERVICE_NAME) as? IBinder
    } catch (t: Throwable) {
        Timber.w(t, "ViwoodsFastInk.serviceBinder")
        null
    }

    /** transact with zero or more int args. Returns "ok"/error string, or null if unavailable. */
    private fun transact(code: Int, vararg ints: Int): String? {
        val binder = serviceBinder() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_TOKEN)
            ints.forEach { data.writeInt(it) }
            binder.transact(code, data, reply, 0)
            reply.readException()
            "ok"
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk.transact($code)")
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    /**
     * Push the AutoDraw region straight into libpaintworker.so via the native ENoteWriting
     * singleton (now loaded in-process by initWriting). The binder AutoDraw path can't do this
     * — updateAutoDrawRegion() clears the native rects but never re-sets them — so this is the
     * call that actually makes the T1000 draw. Both portrait and physical/landscape rects.
     */
    private fun setNativeAutoDrawRects(w: Int, h: Int) {
        try {
            val writingClass = Class.forName("android.os.enote.ENoteWriting")
            val writing = writingClass.getMethod("getInstance").invoke(null)
            val rects = arrayListOf(Rect(0, 0, w, h), Rect(0, 0, h, w))
            writingClass.getMethod("setAutoDrawRects", java.util.List::class.java)
                .invoke(writing, rects)
            Timber.i("ViwoodsFastInk setNativeAutoDrawRects OK (${w}x${h})")
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk.setNativeAutoDrawRects")
        }
    }

    /** callT1000CmdIIsI(type, int[]) via transaction 7 — arms the T1000 timing chip directly. */
    private fun callT1000Cmd(type: Int, values: IntArray): String? {
        val binder = serviceBinder() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeInt(type)
            data.writeIntArray(values)
            binder.transact(TXN_T1000_CALL, data, reply, 0)
            reply.readException()
            "ok"
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk.callT1000Cmd($type)")
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun transactRect(code: Int, rect: Rect): String? {
        val binder = serviceBinder() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeInt(1)            // non-null marker (writeTypedObject)
            rect.writeToParcel(data, 0)
            binder.transact(code, data, reply, 0)
            reply.readException()
            "ok"
        } catch (t: Throwable) {
            Timber.w(t, "ViwoodsFastInk.transactRect($code)")
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
