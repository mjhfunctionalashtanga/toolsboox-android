package com.toolsboox.plugin.calendar.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.toolsboox.plugin.calendar.ui.LogItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export the Notes & Annotations log (the reading corpus) to Markdown or CSV and
 * hand it to the Android share sheet. Parity with the iOS `AnnotationExport`.
 */
object LedgerExport {
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun markdown(items: List<LogItem>): String {
        val sb = StringBuilder()
        sb.append("# Highlights & Annotations\n\n")
        sb.append("_${items.size} item${if (items.size == 1) "" else "s"} · exported ${dayFmt.format(Date())}_\n")
        for (i in items) {
            sb.append("\n## ${i.title.ifBlank { "Untitled" }}\n")
            val meta = listOfNotNull(i.origin.label, i.meta.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (meta.isNotBlank()) sb.append("_${meta}_\n")
            if (i.body.isNotBlank()) sb.append("\n> ${i.body.replace("\n", "\n> ")}\n")
            if (!i.url.isNullOrBlank()) sb.append("\n[${i.url}](${i.url})\n")
        }
        return sb.toString()
    }

    fun csv(items: List<LogItem>): String {
        val rows = StringBuilder("date,origin,title,source,body,url\n")
        for (i in items) {
            val cols = listOf(
                dayFmt.format(Date(i.millis)), i.origin.label, i.title, i.meta, i.body, i.url.orEmpty()
            ).joinToString(",") { escape(it) }
            rows.append(cols).append("\n")
        }
        return rows.toString()
    }

    private fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Write [text] to a cache file and open the share sheet. */
    fun share(context: Context, text: String, filename: String, mime: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export highlights").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
