package com.toolsboox.ot

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import timber.log.Timber
import java.io.File

/**
 * Recording a voice memo: the microphone, the dialog with the clock ticking, and the tidy-up.
 *
 * Lives on its own rather than inside a fragment so the floating pen button can record without a
 * second copy of the machinery — the same reason [InkPadView] moved out of its four dialogs.
 *
 * One recording at a time, process-wide. That's not a limitation to work around: two microphones
 * open at once is a bug, not a feature.
 */
object VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt: Long = 0L
    private var dialog: AlertDialog? = null
    private var onDone: ((File, Double) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    val isRecording: Boolean get() = recorder != null

    /**
     * Record to [out], showing a dialog that counts up. [onSaved] gets the file and its length in
     * seconds; [onDiscarded] fires when it was cancelled or too short to keep.
     *
     * The caller is responsible for having RECORD_AUDIO already — this is the microphone, not the
     * permission dance, which belongs to whoever owns the screen.
     */
    fun record(
        context: Context,
        out: File,
        recordingLabel: (String) -> String,
        stopLabel: String,
        onSaved: (File, Double) -> Unit,
        onDiscarded: () -> Unit = {}
    ) {
        if (!start(context, out, onSaved)) {
            onDiscarded()
            return
        }

        val label = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(40, 48, 40, 24)
            text = recordingLabel("0:00")
        }

        dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setView(label)
            .setPositiveButton(stopLabel) { _, _ -> stop(save = true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> stop(save = false, onDiscarded = onDiscarded) }
            .setCancelable(false)
            .show()

        val tick = object : Runnable {
            override fun run() {
                if (recorder == null) return
                val s = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                label.text = recordingLabel("%d:%02d".format(s / 60, s % 60))
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(tick, 500)
    }

    /**
     * Chromeless start — for surfaces that own their own recording chrome (Notebot's flat
     * recording bar in the chat). Same microphone, same one-at-a-time rule, no dialog: the
     * caller shows elapsed time itself and calls [stop] to finish (save=true) or discard.
     * Returns false when the recorder couldn't start, so the caller can say so instead of
     * showing a bar over silence.
     *
     * VOICE-STATE NOTE (e-ink canon): recording state stays FLAT — a counting label, never a
     * waveform or level meter. A moving meter on e-ink is a strobing grey rectangle that costs
     * refreshes and communicates nothing the counter doesn't.
     */
    fun start(context: Context, out: File, onSaved: (File, Double) -> Unit): Boolean {
        if (isRecording) {
            // Someone is already at the microphone; finish that one rather than racing it.
            stop(save = true)
        }

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            Timber.w(e, "voice recording failed to start")
            runCatching { rec.release() }
            return false
        }

        recorder = rec
        file = out
        startedAt = SystemClock.elapsedRealtime()
        onDone = onSaved
        return true
    }

    /** Elapsed seconds of the current recording — for callers drawing their own counting bar. */
    val elapsedSeconds: Int
        get() = if (recorder == null) 0 else ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    /**
     * Finish. [save] false throws the clip away.
     *
     * Call this from whatever owns the screen when it goes away: leaving a recorder running past
     * the screen holds the microphone, keeps growing the file, and leaks the dialog's window.
     * Saving on the way out keeps the memo rather than silently binning it.
     */
    fun stop(save: Boolean, onDiscarded: () -> Unit = {}) {
        if (recorder == null) return

        handler.removeCallbacksAndMessages(null)
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val out = file
        val done = onDone

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file = null
        onDone = null
        runCatching { dialog?.dismiss() }
        dialog = null

        // Under half a second is a mis-tap, not a memo.
        if (save && out != null && out.exists() && seconds >= 0.5) {
            done?.invoke(out, seconds)
        } else {
            out?.delete()
            onDiscarded()
        }
    }
}
