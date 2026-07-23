package com.toolsboox.ui.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import timber.log.Timber

/**
 * Process-wide read-aloud player. Unlike a per-fragment [LedgerTts], this outlives fragment
 * navigation — so audio keeps playing when you leave the article/book — and a single control modal
 * (play/pause, skip forward/back, featured image + info) drives it from any screen.
 */
object LedgerPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var tts: LedgerTts? = null

    var title: String? = null; private set
    var subtitle: String? = null; private set
    var image: Bitmap? = null; private set

    /** Real-audio backend (podcasts, voice memos). Null when idle or when TTS is the source. */
    private var media: android.media.MediaPlayer? = null

    /** The currently-open modal listens here so its controls track the real playback state. */
    var onChange: (() -> Unit)? = null

    /** File extensions the player treats as audiobooks/audio (vs e-books that open in the reader). */
    fun isAudioFile(name: String): Boolean =
        listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".wav", ".flac")
            .any { name.lowercase().endsWith(it) }

    val isActive: Boolean get() = tts?.isActive == true || media != null
    /** Unified "audible right now" — TTS speaking OR the audio track running. Drives the ▶/⏸ glyph. */
    val isPlaying: Boolean get() = tts?.isSpeaking == true || media?.isPlaying == true
    val isSpeaking: Boolean get() = tts?.isSpeaking == true
    val isPaused: Boolean get() = tts?.isPaused == true

    private fun engine(context: Context): LedgerTts =
        tts ?: LedgerTts(context.applicationContext).also { e ->
            e.onStateChange = { main.post { onChange?.invoke() } }
            tts = e
        }

    /** Start reading [text] aloud (TTS backend), tagging it with metadata for the control modal. */
    fun start(context: Context, title: String?, subtitle: String?, imageUrl: String?, text: String) {
        stopMedia()
        this.title = title?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        this.image = null
        loadImage(imageUrl)
        engine(context).apply { speak(text); setRate(speed) }   // carry the chosen speed to the new read
        onChange?.invoke()
    }

    /** Play a real audio file/stream (podcast enclosure, voice memo) through the same transport. */
    fun startAudio(context: Context, title: String?, subtitle: String?, imageUrl: String?, source: String) {
        tts?.stop(); stopMedia()
        this.title = title?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        this.image = null
        loadImage(imageUrl)
        runCatching {
            media = android.media.MediaPlayer().apply {
                setDataSource(source)
                setOnPreparedListener { mp ->
                    runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }  // carry chosen speed
                    mp.start(); main.post { onChange?.invoke() }
                }
                setOnCompletionListener { stopMedia(); main.post { onChange?.invoke() } }
                setOnErrorListener { _, _, _ -> stopMedia(); main.post { onChange?.invoke() }; true }
                prepareAsync()
            }
        }.onFailure { Timber.w(it, "audio start failed"); stopMedia() }
        onChange?.invoke()
    }

    /** Play/pause toggle — dispatches to whichever backend is active. */
    fun toggle() {
        media?.let { m -> runCatching { if (m.isPlaying) m.pause() else m.start() }; onChange?.invoke(); return }
        val e = tts ?: return
        when {
            e.isPaused -> e.resume()
            e.isSpeaking -> e.pause()
        }
        onChange?.invoke()
    }

    /** Playback speed / speech rate, cycled from the transport. */
    val speeds = listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f)
    var speed: Float = 1.0f
        private set

    /** Cycle to the next speed and apply it to whichever engine is live; returns the new speed. */
    fun cycleSpeed(): Float {
        val next = speeds[(speeds.indexOf(speed).let { if (it < 0) 1 else it } + 1) % speeds.size]
        setSpeed(next)
        return next
    }

    fun setSpeed(s: Float) {
        speed = s
        media?.let { m ->
            runCatching {
                val wasPlaying = m.isPlaying
                m.playbackParams = m.playbackParams.setSpeed(s)   // may auto-start on some devices
                if (!wasPlaying) m.pause()
            }
        }
        tts?.setRate(s)
        onChange?.invoke()
    }

    /** Skip forward: audio by [seconds] (default 30s); TTS jumps one chunk forward. */
    fun skipForward(seconds: Int = 30) {
        media?.let { seekBy(it, seconds * 1000); return }
        tts?.skip(1); onChange?.invoke()
    }

    /** Skip back: audio by [seconds] (default 30s); TTS jumps one chunk back. */
    fun skipBack(seconds: Int = 30) {
        media?.let { seekBy(it, -seconds * 1000); return }
        tts?.skip(-1); onChange?.invoke()
    }

    private fun seekBy(m: android.media.MediaPlayer, delta: Int) {
        runCatching { m.seekTo((m.currentPosition + delta).coerceIn(0, m.duration.coerceAtLeast(0))) }
        onChange?.invoke()
    }

    fun stop() {
        tts?.stop()
        stopMedia()
        title = null; subtitle = null; image = null
        onChange?.invoke()
    }

    private fun stopMedia() {
        media?.let { runCatching { it.stop() }; runCatching { it.release() } }
        media = null
    }

    private fun loadImage(url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            val bmp = runCatching {
                java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.onFailure { Timber.w(it, "player image load failed") }.getOrNull()
            if (bmp != null) main.post { image = bmp; onChange?.invoke() }
        }.apply { isDaemon = true }.start()
    }

    // ---- Control modal ---------------------------------------------------------------------

    /** Show the transport modal (featured image + title/source + ⏮ ⏯ ⏭ ⏹). Reachable from the
     *  Ledger directory's "▶ Now Playing" entry, so it works from any surface. */
    fun showModal(context: Context) {
        val dm = context.resources.displayMetrics
        val pad = (20 * dm.density).toInt()

        val art = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            image?.let { setImageBitmap(it) }
            visibility = if (image != null) View.VISIBLE else View.GONE
        }
        val titleView = TextView(context).apply {
            text = title ?: "Now playing"
            textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subView = TextView(context).apply {
            text = subtitle ?: ""
            textSize = 14f; gravity = Gravity.CENTER
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        fun controlButton(glyph: String) = Button(context).apply {
            text = glyph; textSize = 17f; isAllCaps = false
            setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
        }
        // Row 1: ⏮30  ⏪10  ⏯  ⏩10  ⏭30
        val back30 = controlButton("⏮30")
        val back10 = controlButton("⏪10")
        val playPause = controlButton(if (isPlaying) "⏸" else "▶")
        val fwd10 = controlButton("10⏩")
        val fwd30 = controlButton("30⏭")
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(back30, lp); addView(back10, LinearLayout.LayoutParams(lp))
            addView(playPause, LinearLayout.LayoutParams(lp))
            addView(fwd10, LinearLayout.LayoutParams(lp)); addView(fwd30, LinearLayout.LayoutParams(lp))
        }
        // Row 2: speed · stop
        val speedBtn = controlButton("${speed}×")
        val stopBtn = controlButton("⏹ Stop")
        val controls2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(speedBtn, lp); addView(stopBtn, LinearLayout.LayoutParams(lp))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(art, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.32f).toInt()))
            addView(titleView); addView(subView)
            addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad })
            addView(controls2, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }

        val dialog = AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        // Keep the modal in sync with real playback while it's open.
        val refresh = {
            playPause.text = if (isPlaying) "⏸" else "▶"
            speedBtn.text = "${speed}×"
            image?.let { art.setImageBitmap(it); art.visibility = View.VISIBLE }
        }
        back30.setOnClickListener { skipBack(30); refresh() }
        back10.setOnClickListener { skipBack(10); refresh() }
        playPause.setOnClickListener { toggle(); refresh() }
        fwd10.setOnClickListener { skipForward(10); refresh() }
        fwd30.setOnClickListener { skipForward(30); refresh() }
        speedBtn.setOnClickListener { cycleSpeed(); refresh() }
        stopBtn.setOnClickListener { stop(); dialog.dismiss() }

        onChange = { main.post { refresh() } }
        dialog.setOnDismissListener { onChange = null }
        dialog.show()
    }
}
