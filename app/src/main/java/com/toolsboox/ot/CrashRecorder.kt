package com.toolsboox.ot

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/**
 * The field loop's black box: writes every uncaught exception to a file before the
 * process dies, so a crash on a device with no adb (the B6, a tester's HiBreak Plus) is
 * readable afterwards — Settings › Diagnostics shows it, copies it, shares it.
 *
 * Each crash is its own file, `diagnostics/crash-<time>.txt`, carrying the build, the
 * device, the thread, the full trace and the last 200 lines of the log ring — the
 * evidence, not the description. The ten newest are kept.
 *
 * The previous handler (Firebase's, then the system's) still runs: this records,
 * it does not swallow.
 */
object CrashRecorder {

    private const val KEEP = 10
    private const val PREFS = "ledger_diagnostics"
    private const val KEY_EXIT_SEEN = "exit_seen_millis"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        // The exits the JVM handler never sees — ANRs and native crashes — are on the system's
        // ledger since Android 11. Read the ones newer than the last look and file them too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread { runCatching { sweepSystemExits(appContext) } }.apply { isDaemon = true }.start()
        }
    }

    private fun record(context: Context, thread: String, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val text = DiagnosticsLog.formatCrash(
            epochMillis = now,
            firstLine = throwable.toString(),
            appLine = Diagnostics.appLine(context),
            deviceLine = Diagnostics.deviceLine(),
            thread = thread,
            stack = DiagnosticsLog.stackText(throwable),
            logTail = Diagnostics.ring.tail(DiagnosticsLog.CRASH_TAIL),
        )
        write(context, DiagnosticsLog.crashFileName(now), text)
    }

    private fun write(context: Context, name: String, text: String) {
        val dir = Diagnostics.dir(context)
        File(dir, name).writeText(text)
        prune(dir)
    }

    private fun prune(dir: File) {
        crashFiles(dir).drop(KEEP).forEach { runCatching { it.delete() } }
    }

    /** Crash files, newest first (the name is the time, so the name is the order). */
    fun crashFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }

    fun newestCrashFile(context: Context): File? = crashFiles(Diagnostics.dir(context)).firstOrNull()

    /** The last recorded crash, or null if none has happened since [clear]. */
    fun lastCrash(context: Context): String? =
        newestCrashFile(context)?.let { f -> runCatching { f.readText() }.getOrNull() }

    fun clear(context: Context) {
        crashFiles(Diagnostics.dir(context)).forEach { runCatching { it.delete() } }
    }

    /**
     * ANRs and native crashes from the system's exit ledger, filed as crash files. Runs once
     * per launch on a background thread; remembers the newest timestamp it has filed so the
     * same exit is never written twice. JVM crashes are skipped here — the handler above
     * already wrote those with a real trace.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun sweepSystemExits(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_EXIT_SEEN, 0L)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        var newest = seen
        for (exit in exits) {
            if (exit.timestamp <= seen) continue
            newest = maxOf(newest, exit.timestamp)
            val kind = when (exit.reason) {
                ApplicationExitInfo.REASON_ANR -> "ANR — the app stopped answering"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed for memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for excessive resource use"
                else -> null
            } ?: continue
            val trace = runCatching {
                exit.traceInputStream?.bufferedReader()?.useLines { it.take(200).joinToString("\n") }
            }.getOrNull()
            val text = DiagnosticsLog.formatCrash(
                epochMillis = exit.timestamp,
                firstLine = "$kind: ${exit.description ?: "(no description from the system)"}",
                appLine = Diagnostics.appLine(context),
                deviceLine = Diagnostics.deviceLine(),
                thread = "process (pid ${exit.pid}, importance ${exit.importance})",
                stack = trace ?: "(the system kept no trace for this exit)",
                logTail = readLogFileTail(context),
            )
            write(context, DiagnosticsLog.crashFileName(exit.timestamp), text)
        }
        if (newest != seen) prefs.edit().putLong(KEY_EXIT_SEEN, newest).apply()
    }

    /** The log the previous run flushed on its way to the background, if it got that far. */
    private fun readLogFileTail(context: Context): List<String> =
        runCatching {
            File(Diagnostics.dir(context), Diagnostics.LOG_FILE).readLines().takeLast(DiagnosticsLog.CRASH_TAIL)
        }.getOrDefault(emptyList())
}
