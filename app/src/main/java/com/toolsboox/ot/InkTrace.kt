package com.toolsboox.ot

import android.content.Context
import java.io.File

/**
 * File-based ink trace for the B6 — the readable record adb logcat cannot be.
 *
 * The 09-03 lasso postmortem's first rule: never touch the daemon without a trace.
 * `adb logcat` streaming drops constantly on this USB, which is what forced a day of
 * guessing; this writes the daemon call/commit sequence to a plain file on the device
 * instead, pullable in one shot:
 *
 *   adb pull /sdcard/Android/data/com.toolsboox.mjh.debug/files/inktrace.log
 *
 * Volume discipline: DOWN/UP but never MOVE; commits and mirrors, not per-segment
 * draws. Rotates at 512KB to a single .1 file so it can run for days. All writes on
 * one background thread; [log] is a cheap enqueue and a no-op until [init] has run
 * (only the Bigme path initializes it — every other device never pays).
 */
object InkTrace {

    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var file: File? = null

    private val thread by lazy { android.os.HandlerThread("InkTrace").apply { start() } }
    private val handler by lazy { android.os.Handler(thread.looper) }

    // Formatted ONLY on the handler thread — SimpleDateFormat is not thread-safe, and log()
    // is called from the daemon binder thread and main thread simultaneously (09-04 sweep).
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun init(context: Context) {
        if (file != null) return
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return
        file = File(dir, "inktrace.log")
        log("trace", "=== session start ${android.os.Build.MODEL} " +
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("?") + " ===")
    }

    /** Append one line: `HH:mm:ss.SSS [tag] msg`. Safe from any thread; no-op before init. */
    fun log(tag: String, msg: String) {
        val f = file ?: return
        val atMs = System.currentTimeMillis()
        handler.post {
            val line = "${timeFormat.format(java.util.Date(atMs))} [$tag] $msg\n"
            runCatching {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "inktrace.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line)
            }
        }
    }

    /** The whole current trace, newest file last — the Settings instruments door reads this. */
    fun dump(): String {
        val f = file ?: return "trace not initialized"
        val old = File(f.parentFile, "inktrace.1.log")
        return buildString {
            if (old.exists()) append(old.readText())
            if (f.exists()) append(f.readText())
        }.ifEmpty { "trace empty" }
    }
}
