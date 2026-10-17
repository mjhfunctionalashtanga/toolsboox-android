package com.toolsboox.ot

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import timber.log.Timber
import java.io.File

/**
 * The diagnostics room's back office: the log ring, the folder, the flush, the report.
 *
 * One folder, `filesDir/diagnostics/`: `log.txt` (the ring, written on background and on
 * "Copy/Share report") and `crash-<time>.txt` files ([CrashRecorder]). Settings › Diagnostics
 * reads all of it; nothing here is shown unasked except the one quiet toast after a crash
 * ([noticeCrashOnce]).
 */
object Diagnostics {

    const val DIR = "diagnostics"
    const val LOG_FILE = "log.txt"
    private const val PREFS = "ledger_diagnostics"
    private const val KEY_ACKNOWLEDGED = "crash_acknowledged"

    val ring = DiagnosticsLog()

    private var installed = false

    /** Plant the ring's tree, arm the crash recorder, flush when the app leaves the screen. */
    fun install(app: Application) {
        if (installed) return
        installed = true
        Timber.plant(DiagnosticsTree(ring))
        CrashRecorder.install(app)
        ring.add(DiagnosticsLog.formatLine(System.currentTimeMillis(), DiagnosticsLog.INFO, "Diagnostics",
            "launch · ${appLine(app)} · ${deviceLine()}", null))

        // "Went to the background" on Android is two signals, and either alone misses cases:
        // TRIM_MEMORY_UI_HIDDEN fires when the last window is hidden; the activity count covers
        // the process being stopped without that callback. Both flush; the write is idempotent.
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flush(app)
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() { flush(app) }
        })
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                if (started == 0) flush(app)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Write the ring to `log.txt`. Off the main thread — it is called on the way out. */
    fun flush(context: Context) {
        val appContext = context.applicationContext
        val text = ring.dump()
        Thread { runCatching { File(dir(appContext), LOG_FILE).writeText(text) } }.apply { isDaemon = true }.start()
    }

    // ── The lines every report starts with ──────────────────────────────────────────────

    fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("?")

    fun appLine(context: Context): String {
        val flavour = com.toolsboox.BuildConfig.FLAVOR.ifEmpty { "standard" }
        val type = com.toolsboox.BuildConfig.BUILD_TYPE
        return "Ledgable ${appVersion(context)} $flavour/$type"
    }

    fun deviceLine(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        return "$model · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    // ── The report ─────────────────────────────────────────────────────────────────────

    /** (time, first line) of the newest crash, or null. */
    fun lastCrashSummary(context: Context): Pair<String, String>? =
        CrashRecorder.lastCrash(context)?.let { DiagnosticsLog.summaryOf(it) }

    /** The whole thing: one-line summary, the last crash, the ring (or the flushed file). */
    fun report(context: Context): String {
        val crash = CrashRecorder.lastCrash(context)
        val live = ring.tail()
        val lines = if (live.isNotEmpty()) live
        else runCatching { File(dir(context), LOG_FILE).readLines() }.getOrDefault(emptyList())
        return DiagnosticsLog.formatReport(
            appLine = appLine(context),
            deviceLine = deviceLine(),
            crashSummary = crash?.let { DiagnosticsLog.summaryOf(it) },
            crashText = crash,
            logLines = lines,
        )
    }

    /** Crash files, log file, ring, and the toast's memory — all gone. */
    fun clear(context: Context) {
        CrashRecorder.clear(context)
        runCatching { File(dir(context), LOG_FILE).delete() }
        ring.clear()
        prefs(context).edit().remove(KEY_ACKNOWLEDGED).apply()
    }

    // ── The one quiet notice ───────────────────────────────────────────────────────────

    /**
     * The newest crash file the owner has not been told about, or null. Acknowledging is by
     * file name, so the same crash is never announced twice and a new one is announced once.
     */
    fun unacknowledgedCrash(context: Context): File? {
        val newest = CrashRecorder.newestCrashFile(context) ?: return null
        return if (prefs(context).getString(KEY_ACKNOWLEDGED, null) == newest.name) null else newest
    }

    fun acknowledge(context: Context, crash: File) {
        prefs(context).edit().putString(KEY_ACKNOWLEDGED, crash.name).apply()
    }

    /**
     * On launch: one toast if the last run ended in a crash, never a dialog, never twice.
     * Returns true if it spoke.
     */
    fun noticeCrashOnce(activity: Activity): Boolean {
        val crash = unacknowledgedCrash(activity) ?: return false
        acknowledge(activity, crash)
        android.widget.Toast.makeText(activity,
            "Ledgable crashed last time — Settings › Diagnostics has the report",
            android.widget.Toast.LENGTH_LONG).show()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
