package com.toolsboox.plugin.calendar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.toolsboox.R
import com.toolsboox.ot.Diagnostics
import com.toolsboox.ot.ModalScale
import java.io.File

/**
 * Settings › Diagnostics — send a crash or a misbehaviour with evidence instead of a description.
 *
 * What it shows: the build, the device, "Last crash: <time> · <cause>" (or that there is none),
 * and how much log is kept. What it does: Copy report (to the clipboard, summary line first),
 * Share report… (the same text as a file, through the system share sheet), Clear.
 *
 * It is a dialog rather than a fragment for the same reason Sites is: it is a small room off
 * Settings, and the system back is the way out.
 */
object DiagnosticsDialog {

    fun show(context: Context, present: (AlertDialog) -> Unit = { it.show() }) {
        val ctx = ModalScale.wrap(context)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_diagnostics, null)

        val appLabel = view.findViewById<TextView>(R.id.diagnostics_app)
        val deviceLabel = view.findViewById<TextView>(R.id.diagnostics_device)
        val crashLabel = view.findViewById<TextView>(R.id.diagnostics_last_crash)
        val countLabel = view.findViewById<TextView>(R.id.diagnostics_log_count)

        fun render() {
            appLabel.text = Diagnostics.appLine(context)
            deviceLabel.text = Diagnostics.deviceLine()
            val crash = Diagnostics.lastCrashSummary(context)
            crashLabel.text = if (crash != null) "Last crash: ${crash.first} · ${crash.second}" else "No crashes recorded"
            val n = Diagnostics.ring.size()
            countLabel.text = when (n) {
                0 -> "Nothing in the recent log yet — warnings and mail/sync/feeds lines land here as they happen"
                1 -> "1 recent log line kept on this device"
                else -> "$n recent log lines kept on this device"
            }
        }
        render()

        view.findViewById<Button>(R.id.diagnostics_copy).setOnClickListener {
            val report = Diagnostics.report(context)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Ledgable report", report))
            Toast.makeText(context, "Report copied — paste it into a message", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.diagnostics_share).setOnClickListener {
            shareReport(context)
        }

        view.findViewById<Button>(R.id.diagnostics_clear).setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle("Clear the crash record and the recent log?")
                .setPositiveButton("Clear") { _, _ ->
                    Diagnostics.clear(context)
                    render()
                    Toast.makeText(context, "Cleared — the next crash or warning starts a fresh report", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Keep", null)
                .show()
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Diagnostics")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()
        present(dialog)
    }

    /** The report as a text file through the system share sheet, the summary line as its subject. */
    private fun shareReport(context: Context) {
        val report = Diagnostics.report(context)
        val summary = report.lineSequence().firstOrNull() ?: "Ledgable report"
        val file = runCatching {
            // cache/exports is a path the FileProvider already grants (the highlight exports use
            // it), so the report needs no new manifest entry to leave the device.
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val name = "ledgable-report-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm")) + ".txt"
            File(dir, name).apply { writeText(report) }
        }.getOrNull()
        if (file == null) {
            Toast.makeText(context, "Couldn't write the report file — Copy report still works", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, summary)
            putExtra(Intent.EXTRA_TEXT, summary)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "Share the report"))
        }.onFailure {
            Toast.makeText(context, "Nothing on this device can take a file — Copy report still works", Toast.LENGTH_LONG).show()
        }
    }
}
