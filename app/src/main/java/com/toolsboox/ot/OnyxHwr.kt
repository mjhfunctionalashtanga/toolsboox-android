package com.toolsboox.ot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import com.toolsboox.da.Stroke
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ❖ THE ON-DEVICE RUNG — the Boox firmware's own MyScript engine, spoken to over the HWR
 * service in `com.onyx.android.ksync` (verified present on the Go 6, 09-02, alongside the
 * whole /system/lib64/libMyScript* family). Sits in the OCR ladder between the vision model
 * and PanelOcr: no network, no API charge, and the same recognizer the Notes app trains
 * against his handwriting daily.
 *
 * Interface facts (component, transaction order, parcel layout, protobuf field numbers) were
 * learned from the Aragonite project's research (github.com/jdkruzr/aragonite) and Onyx's own
 * wire; the code is an independent implementation — see THIRD-PARTY-NOTICES.md.
 *
 * DEGRADES BY DESIGN: on Bigme and Viwoods (no ksync), or any bind/recognition failure, every
 * entry returns null and the ladder simply falls through to PanelOcr. No caller needs to know
 * which vendor it is standing on. All entry points BLOCK — call from Dispatchers.IO, exactly
 * as PanelOcr is called.
 */
object OnyxHwr {

    private const val PKG = "com.onyx.android.ksync"
    private const val SVC = "com.onyx.android.ksync.service.KHwrService"
    private const val BIND_TIMEOUT_MS = 2_500L
    private const val RECOGNIZE_TIMEOUT_MS = 10_000L

    @Volatile private var service: IHWRService? = null
    @Volatile private var initialized = false
    @Volatile private var everRefused = false
    private var connectGate = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            Timber.i("OnyxHwr: service connected")
            connectGate.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            initialized = false
            Timber.w("OnyxHwr: service disconnected")
        }
    }

    /** Bind if needed and wait briefly. False = this device has no usable HWR service; the
     *  refusal is remembered so later ladder calls cost one volatile read, not a bind storm. */
    private fun ready(context: Context): Boolean {
        if (service != null) return true
        if (everRefused) return false
        synchronized(this) {
            if (service != null) return true
            if (everRefused) return false
            connectGate = CountDownLatch(1)
            val intent = Intent().apply { component = ComponentName(PKG, SVC) }
            val started = runCatching {
                context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!started) {
                everRefused = true
                Timber.i("OnyxHwr: no HWR service on this device — ladder falls through")
                return false
            }
        }
        val connected = runCatching {
            connectGate.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false) && service != null
        if (!connected) { everRefused = true; Timber.i("OnyxHwr: bind timed out — falling through") }
        return connected
    }

    private fun ensureInitialized(width: Float, height: Float): Boolean {
        val svc = service ?: return false
        if (initialized) return true
        val done = CountDownLatch(1)
        runCatching {
            svc.init(HWRInputArgs().apply {
                lang = "en_US"
                contentType = "Text"
                recognizerType = "MS_ON_SCREEN"
                viewWidth = width
                viewHeight = height
                isTextEnable = true
            }, true, object : HWROutputCallback.Stub() {
                override fun read(args: HWROutputArgs?) {
                    initialized = args?.recognizerActivated == true
                    done.countDown()
                }
            })
        }.onFailure { Timber.w(it, "OnyxHwr: init threw"); return false }
        runCatching { done.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        Timber.i("OnyxHwr: initialized=%s", initialized)
        return initialized
    }

    /**
     * Read [strokes] (design-space, 1404×1872) back as words, or null when this device can't —
     * the ladder's contract. Blocking; Dispatchers.IO.
     */
    fun recognize(context: Context, strokes: List<Stroke>): String? {
        if (strokes.isEmpty()) return null
        if (!ready(context)) return null
        val svc = service ?: return null
        if (!ensureInitialized(1404f, 1872f)) return null

        val pfd = memoryPfd(inkProto(strokes)) ?: return null
        val out = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        try {
            svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                override fun read(args: HWROutputArgs?) {
                    try {
                        // An error rides hwrResult as text; success rides a PFD of JSON.
                        val err = args?.hwrResult
                        if (!err.isNullOrBlank()) {
                            Timber.w("OnyxHwr: engine error: %s", err.take(200))
                        } else args?.pfd?.let { rp ->
                            val json = rp.use { readAll(it) }
                            out.set(labelOf(json))
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "OnyxHwr: result read failed")
                    } finally {
                        done.countDown()
                    }
                }
            })
            val finished = runCatching {
                done.await(RECOGNIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!finished) Timber.w("OnyxHwr: recognition timed out")
        } catch (e: Exception) {
            Timber.w(e, "OnyxHwr: batchRecognize threw")
        } finally {
            runCatching { pfd.close() }
        }
        return out.get()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ── The result JSON: {"result":{"label":"…"}}; errors as {"exception":{…}} ────────────────

    private fun labelOf(json: String): String? = runCatching {
        val o = JSONObject(json)
        if (o.has("exception")) {
            Timber.w("OnyxHwr: exception in result: %s", json.take(200)); null
        } else o.optJSONObject("result")?.optString("label")?.takeIf { it.isNotBlank() }
            ?: o.optString("label").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun readAll(pfd: ParcelFileDescriptor): String =
        java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }

    // ── The input protobuf (HWRInputProto): field numbers are the service's wire ──────────────
    //  1 lang(str) · 2 contentType(str) · 4 recognizerType(str) · 5 viewWidth(f32) ·
    //  6 viewHeight(f32) · 10 recognizeText(bool) · 15 repeated pointer events, each:
    //  1 x(f32) · 2 y(f32) · 3 t(sint64) · 4 pressure(f32) · 5 pointerId(sint32) ·
    //  6 eventType(0 down/1 move/2 up) · 7 pointerType(0 pen)

    private fun inkProto(strokes: List<Stroke>): ByteArray {
        val out = ByteArrayOutputStream()
        str(out, 1, "en_US")
        str(out, 2, "Text")
        str(out, 4, "MS_ON_SCREEN")
        f32(out, 5, 1404f)
        f32(out, 6, 1872f)
        tag(out, 10, 0); varint(out, 1)
        for (s in strokes) {
            val pts = s.strokePoints
            if (pts.isEmpty()) continue
            val epoch = if (s.timestamp > 0) s.timestamp else System.currentTimeMillis()
            for ((i, p) in pts.withIndex()) {
                val event = when (i) { 0 -> 0; pts.size - 1 -> 2; else -> 1 }
                // Stored strokes carry t=0 (the PanelOcr lesson) — synthesize a plausible pace.
                val t = epoch + if (p.t > 0) p.t else i * 10L
                // The wire pressure is 0..1; live capture can hand larger raw units.
                val f = if (p.p > 1f) (p.p / 4096f).coerceIn(0.05f, 1f) else p.p.coerceIn(0.05f, 1f)
                val pe = ByteArrayOutputStream()
                f32(pe, 1, p.x); f32(pe, 2, p.y)
                tag(pe, 3, 0); varint(pe, (t shl 1) xor (t shr 63))          // sint64 zigzag
                f32(pe, 4, f)
                tag(pe, 5, 0); varint(pe, 0)                                  // pointerId 0
                tag(pe, 6, 0); varint(pe, event.toLong())
                tag(pe, 7, 0); varint(pe, 0)                                  // PEN
                tag(out, 15, 2)
                val b = pe.toByteArray(); varint(out, b.size.toLong()); out.write(b)
            }
        }
        return out.toByteArray()
    }

    private fun tag(o: ByteArrayOutputStream, field: Int, wire: Int) =
        varint(o, ((field shl 3) or wire).toLong())

    private fun varint(o: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { o.write(((v.toInt() and 0x7F) or 0x80)); v = v ushr 7 }
        o.write(v.toInt() and 0x7F)
    }

    private fun f32(o: ByteArrayOutputStream, field: Int, value: Float) {
        tag(o, field, 5)
        val bits = java.lang.Float.floatToIntBits(value)
        o.write(bits and 0xFF); o.write((bits shr 8) and 0xFF)
        o.write((bits shr 16) and 0xFF); o.write((bits shr 24) and 0xFF)
    }

    private fun str(o: ByteArrayOutputStream, field: Int, value: String) {
        tag(o, field, 2)
        val b = value.toByteArray(Charsets.UTF_8)
        varint(o, b.size.toLong()); o.write(b)
    }

    /** Bytes → an ashmem-backed PFD, the transport the service reads. MemoryFile's descriptor
     *  is hidden API — the same reflection Onyx's own MemoryFileUtils performs. */
    private fun memoryPfd(data: ByteArray): ParcelFileDescriptor? = runCatching {
        val mem = MemoryFile("ledger_hwr", data.size)
        mem.writeBytes(data, 0, 0, data.size)
        val m = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
        m.isAccessible = true
        val fd = m.invoke(mem) as FileDescriptor
        val pfd = ParcelFileDescriptor.dup(fd)
        mem.close()
        pfd
    }.onFailure { Timber.w(it, "OnyxHwr: ashmem PFD failed") }.getOrNull()
}
