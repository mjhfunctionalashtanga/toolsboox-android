package com.toolsboox.plugin.calendar.ot

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/**
 * Playing an A/V gram back.
 *
 * Audio plays in place, behind a small dialog that shows the clock ticking — on e-ink the panel
 * stays still and only the counter moves, which is as much animation as this ought to have.
 * Video is handed to the system player, which owns the screen refresh that video needs.
 *
 * Nothing here moves until the card is tapped.
 */
object AvPlayback {

    /** Play [file] (or [url] when the blob never reached this device). */
    fun play(context: Context, kind: String, file: File?, url: String, title: String, durationMs: Int) {
        when {
            kind == "video" -> playVideo(context, file, url)
            kind == "audio" -> playAudio(context, file, url, title, durationMs)
            else -> Unit
        }
    }

    private fun playVideo(context: Context, file: File?, url: String) {
        val uri = localUri(context, file) ?: url.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun playAudio(context: Context, file: File?, url: String, title: String, durationMs: Int) {
        val source = file?.takeIf { it.exists() }?.absolutePath
            ?: url.takeIf { it.isNotBlank() }
            ?: return

        val player = MediaPlayer()
        val label = TextView(context).apply {
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 20f
            text = "0:00 / " + (AvPoster.clock(durationMs).ifBlank { "—" })
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
        }

        var dialog: Dialog? = null
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        val total = AvPoster.clock(durationMs).ifBlank { "—" }

        val tick = object : Runnable {
            override fun run() {
                val at = runCatching { player.currentPosition }.getOrDefault(0)
                label.text = AvPoster.clock(at).ifBlank { "0:00" } + " / " + total
                ui.postDelayed(this, 500)
            }
        }

        fun stop() {
            ui.removeCallbacks(tick)
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }

        try {
            player.setDataSource(source)
            player.prepare()
        } catch (e: Exception) {
            stop()
            return
        }

        player.setOnCompletionListener {
            ui.removeCallbacks(tick)
            label.text = total + " / " + total
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(title.ifBlank { "🎤 Audio gram" })
            .setView(box)
            .setPositiveButton("Done") { d, _ -> d.dismiss() }
            .setOnDismissListener { stop() }
            .create()

        dialog.show()
        runCatching { player.start() }
        ui.post(tick)
    }

    private fun localUri(context: Context, file: File?): Uri? {
        val f = file?.takeIf { it.exists() } ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        }.getOrNull()
    }
}
