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

    /**
     * What the playing episode IS, for capture — the provenance the article star already carries
     * (title, feed, the entry's own URL, art, blurb), handed in by whoever started playback.
     * Without it the transport knew only what to DISPLAY (title/subtitle/art bitmap), so a
     * playing podcast could not be starred or annotated from the player at all: the Listen flow
     * never opens the article pane where those buttons live, which is exactly the gap Michael
     * hit. Null for tracks with no capturable source (TTS read-aloud, audiobook files) — the
     * ★/📝 affordances only appear when this is set.
     */
    data class Capture(
        val title: String,
        val feedTitle: String,
        val url: String,
        val imageUrl: String?,
        val excerpt: String = ""
    )

    var capture: Capture? = null; private set

    /** Monotonic playback-session token: bumped on every start/stop so background lookups
     *  (chapter/transcript resolves racing the network) can tell "still my track" from
     *  "the listener moved on" and lose quietly. */
    var session: Long = 0L; private set

    /** Chapters for the CURRENT item (Podcasting 2.0 tag or description timestamps — one shape,
     *  see FeedChapters). Set by whoever started playback once resolved; empty = plain track. */
    var chapters: List<com.toolsboox.plugin.feeds.da.Chapter> = emptyList(); private set

    /** Hand the current item its chapter list (main thread). The transport listeners re-render,
     *  which is how the Now Playing card's chapter row appears the moment the resolve lands. */
    fun setChapters(list: List<com.toolsboox.plugin.feeds.da.Chapter>) {
        chapters = list
        notifyChange()
    }

    /** Lazy transcript hook for the current item — Ask/search reach the episode's words through
     *  here without the player holding megabytes of text. Blocking; call off the main thread. */
    var transcriptProvider: (() -> String?)? = null

    /** Index of the chapter the playhead is inside (-1 when chapterless or before the first). */
    fun currentChapterIndex(): Int {
        if (chapters.isEmpty()) return -1
        val pos = positionMs / 1000
        var idx = -1
        for (i in chapters.indices) if (chapters[i].startSec <= pos) idx = i else break
        return idx
    }

    /** The playing chapter's title, for the transport's "where am I" line. */
    val currentChapterTitle: String?
        get() = chapters.getOrNull(currentChapterIndex())?.title

    /** Jump to the next chapter start (audio only — chapters are clock-addressed). */
    fun chapterNext() {
        val i = currentChapterIndex()
        chapters.getOrNull(i + 1)?.let { seekTo(it.startSec * 1000) }
    }

    /** Back one chapter, radio-style: well into a chapter returns to ITS start; near its start
     *  (≤3s, i.e. "I just got here") goes to the previous one. */
    fun chapterPrev() {
        val i = currentChapterIndex()
        val cur = chapters.getOrNull(i) ?: return
        val intoMs = positionMs - cur.startSec * 1000
        val target = if (intoMs > 3000) cur else chapters.getOrNull(i - 1) ?: cur
        seekTo(target.startSec * 1000)
    }

    /** Real-audio backend (podcasts, voice memos). Null when idle or when TTS is the source. */
    private var media: android.media.MediaPlayer? = null

    /** Whoever is showing transport controls listens here so they track the real playback state.
     *  A list, not a single slot: the drawer's inline Now Playing card and the modal can both be
     *  alive at once, and neither should silently unhook the other. */
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChange() { listeners.toList().forEach { it() } }

    /** File extensions the player treats as audiobooks/audio (vs e-books that open in the reader). */
    fun isAudioFile(name: String): Boolean =
        listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".wav", ".flac")
            .any { name.lowercase().endsWith(it) }

    val isActive: Boolean get() = tts?.isActive == true || media != null
    /** Unified "audible right now" — TTS speaking OR the audio track running. Drives the ▶/⏸ glyph. */
    val isPlaying: Boolean get() = tts?.isSpeaking == true || media?.isPlaying == true
    val isSpeaking: Boolean get() = tts?.isSpeaking == true
    val isPaused: Boolean get() = tts?.isPaused == true

    /** True while the real-audio backend (podcast/voice memo) owns the transport; false = TTS. */
    val isMediaSource: Boolean get() = media != null

    // Progress, for an inline transport that shows WHERE you are, not just that something plays.
    // Audio reports milliseconds; TTS has no clock, so it reports chunk (≈paragraph) counts —
    // callers render whichever pair is meaningful. MediaPlayer throws if queried before prepare,
    // hence the runCatching guards.
    val positionMs: Int get() = media?.let { m -> runCatching { m.currentPosition }.getOrDefault(0) } ?: 0
    val durationMs: Int get() = media?.let { m -> runCatching { m.duration }.getOrDefault(0) } ?: 0
    val ttsChunkIndex: Int get() = tts?.chunkIndex ?: 0
    val ttsChunkCount: Int get() = tts?.chunkCount ?: 0

    /** Absolute seek (audio only — TTS moves in chunks via [skipForward]/[skipBack]). */
    fun seekTo(ms: Int) {
        media?.let { m ->
            runCatching { m.seekTo(ms.coerceIn(0, m.duration.coerceAtLeast(0))) }
            notifyChange()
        }
    }

    private fun engine(context: Context): LedgerTts =
        tts ?: LedgerTts(context.applicationContext).also { e ->
            e.onStateChange = { main.post { notifyChange() } }
            tts = e
        }

    /** Start reading [text] aloud (TTS backend), tagging it with metadata for the control modal. */
    fun start(context: Context, title: String?, subtitle: String?, imageUrl: String?, text: String) {
        stopMedia()
        newSession()
        this.title = title?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        this.image = null
        loadImage(imageUrl)
        engine(context).apply { speak(text); setRate(speed) }   // carry the chosen speed to the new read
        notifyChange()
    }

    /** Play a real audio file/stream (podcast enclosure, voice memo) through the same transport.
     *  [capture] is the episode's capturable identity — pass it and the transport grows its ★ and
     *  📝 buttons; leave it null (voice memos, audiobook files) and the transport stays plain. */
    fun startAudio(
        context: Context, title: String?, subtitle: String?, imageUrl: String?, source: String,
        capture: Capture? = null
    ) {
        tts?.stop(); stopMedia()
        newSession()
        this.capture = capture
        this.title = title?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        this.image = null
        loadImage(imageUrl)
        runCatching {
            media = android.media.MediaPlayer().apply {
                setDataSource(source)
                setOnPreparedListener { mp ->
                    runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }  // carry chosen speed
                    mp.start(); main.post { notifyChange() }
                }
                setOnCompletionListener { stopMedia(); main.post { notifyChange() } }
                setOnErrorListener { _, _, _ -> stopMedia(); main.post { notifyChange() }; true }
                prepareAsync()
            }
        }.onFailure { Timber.w(it, "audio start failed"); stopMedia() }
        notifyChange()
    }

    /** Play/pause toggle — dispatches to whichever backend is active. */
    fun toggle() {
        media?.let { m -> runCatching { if (m.isPlaying) m.pause() else m.start() }; notifyChange(); return }
        val e = tts ?: return
        when {
            e.isPaused -> e.resume()
            e.isSpeaking -> e.pause()
        }
        notifyChange()
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
        notifyChange()
    }

    /** Skip forward: audio by [seconds] (default 30s); TTS jumps one chunk forward. */
    fun skipForward(seconds: Int = 30) {
        media?.let { seekBy(it, seconds * 1000); return }
        tts?.skip(1); notifyChange()
    }

    /** Skip back: audio by [seconds] (default 30s); TTS jumps one chunk back. */
    fun skipBack(seconds: Int = 30) {
        media?.let { seekBy(it, -seconds * 1000); return }
        tts?.skip(-1); notifyChange()
    }

    private fun seekBy(m: android.media.MediaPlayer, delta: Int) {
        runCatching { m.seekTo((m.currentPosition + delta).coerceIn(0, m.duration.coerceAtLeast(0))) }
        notifyChange()
    }

    fun stop() {
        tts?.stop()
        stopMedia()
        newSession()
        title = null; subtitle = null; image = null
        notifyChange()
    }

    /** New track (or silence): whatever chapter/transcript state belonged to the last one is
     *  stale now, and any in-flight resolve for it must find a different session number. */
    private fun newSession() {
        session++
        chapters = emptyList()
        transcriptProvider = null
        capture = null
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
            if (bmp != null) main.post { image = bmp; notifyChange() }
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
        // Row 2: speed · ★ · 📝 · stop. The ★ and 📝 live ON the player because for a playing
        // podcast the player IS the surface — the Listen flow never opens the article pane where
        // the reader's star/note buttons sit, so capture has to be reachable from the transport
        // itself: listening → one tap → captured → keep listening. They only appear when the
        // track carries a capturable identity ([capture]); TTS read-aloud stays speed·stop.
        val speedBtn = controlButton("${speed}×")
        val starBtn = controlButton("★")
        val noteBtn = controlButton("📝")
        val stopBtn = controlButton("⏹ Stop")
        val controls2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(speedBtn, lp)
            if (capture != null) {
                addView(starBtn, LinearLayout.LayoutParams(lp))
                addView(noteBtn, LinearLayout.LayoutParams(lp))
            }
            addView(stopBtn, LinearLayout.LayoutParams(lp))
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
        starBtn.setOnClickListener { LedgerPlayerCapture.starNow(context) }
        noteBtn.setOnClickListener { LedgerPlayerCapture.annotateNow(context) }
        stopBtn.setOnClickListener { stop(); dialog.dismiss() }

        val listener = { main.post { refresh() }; Unit }
        addListener(listener)
        dialog.setOnDismissListener { removeListener(listener) }
        dialog.show()
    }
}
