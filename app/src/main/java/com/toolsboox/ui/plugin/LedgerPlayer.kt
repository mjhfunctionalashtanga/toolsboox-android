package com.toolsboox.ui.plugin

import android.content.Context
import android.graphics.Bitmap
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
        val excerpt: String = "",
        /** The audio ENCLOSURE (not [url], which is the episode page) — recorded on the
         *  playhead so ▶ Continue can resume on any device without the feed list. */
        val enclosureUrl: String = ""
    )

    var capture: Capture? = null; private set
    /** The BOOK being read aloud (its shelf name) — the transport title's way back to the reader
     *  (09-13: "…and that be the case in reader for books and rss"). Letters and articles ride
     *  [capture] (a letter's url is its mail:// address). */
    var readingBook: String? = null

    // ---- Listen state (the synced playhead) ------------------------------------------------

    /** The episode's fleet-wide name in `listen-state.json` — [Capture.url], the same identity the
     *  star files. Null for tracks with no capturable identity (TTS read-aloud, voice memos,
     *  audiobook files): a voice memo has no cross-device name worth syncing, and audiobooks join
     *  when the library manifest gives them one. */
    private var listenId: String? = null

    /** For the state writes that happen after the starting fragment is gone (stop from another
     *  surface, the 15s heartbeat) — always the application context, set at [startAudio]. */
    private var appContext: Context? = null

    /** How often the playhead is noted to local disk while playing. Local only — the round trips
     *  ride on pause/stop/finish, the moments another device might realistically be next to play. */
    private const val HEARTBEAT_MS = 15_000L

    /** A cross-device re-seek only fires when it would MOVE the listener — a jump smaller than
     *  this is the same sentence again, not worth the audio hiccup. */
    private const val RESEEK_SLOP_MS = 5_000L

    private val heartbeat = object : Runnable {
        override fun run() {
            if (media == null) return
            if (media?.isPlaying == true) recordListen(sync = false)
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** Note the current playhead into [ListenStateStore] (no-op for untracked tracks). The
     *  position/duration are read on the caller's thread while [media] is still alive; the file
     *  write rides the sidecar executor so the heartbeat never does IO on main. */
    /** Whoever started the track may ride the heartbeat: (positionMs, durationMs, final) — `final`
     *  on stop. Audiobookshelf's session sync lives here; cleared with the session. */
    var progressHook: ((Long, Long, Boolean) -> Unit)? = null
    /** True only inside [stop] — a pause also records with sync=true, and Audiobookshelf must not
     *  close its session on a pause (audit 09-11). */
    @Volatile private var stopping = false

    private fun recordListen(sync: Boolean) {
        val ctx = appContext ?: return
        val id = listenId ?: return
        val m = media ?: return
        val pos = runCatching { m.currentPosition }.getOrDefault(0).toLong()
        val dur = runCatching { m.duration }.getOrDefault(0).toLong()
        if (pos > 0L) progressHook?.let { h -> runCatching { h(pos, dur, stopping) } }
        if (pos <= 0L) return
        val enc = capture?.enclosureUrl.orEmpty()
        val cap = capture
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            com.toolsboox.plugin.calendar.ot.ListenStateStore.record(ctx, id, pos, dur, sync, enc,
                title = cap?.title.orEmpty(), show = cap?.feedTitle.orEmpty(), artworkUrl = cap?.imageUrl.orEmpty())
        }
    }

    /** Monotonic playback-session token: bumped on every start/stop so background lookups
     *  (chapter/transcript resolves racing the network) can tell "still my track" from
     *  "the listener moved on" and lose quietly. */
    var session: Long = 0L; private set

    // ---- Sleep timer (the AntennaPod lift, 09-02) --------------------------------------------

    /** Minutes until sleep; 0 = off, -1 = end of episode (finish this one, then stop). */
    val sleepModes = listOf(0, 15, 30, 45, -1)
    var sleepMode: Int = 0; private set
    private var sleepAt: Long = 0L
    private val sleepRunnable = Runnable {
        // Fell due: pause (never stop — the playhead and the card stay, exactly where a woken
        // listener wants to resume from) and disarm.
        if (media?.isPlaying == true) toggle()
        sleepMode = 0; sleepAt = 0L
        notifyChange()
    }

    /** Cycle Off → 15m → 30m → 45m → end of episode → Off; returns the new label. */
    fun cycleSleep(): String {
        val next = sleepModes[(sleepModes.indexOf(sleepMode) + 1) % sleepModes.size]
        sleepMode = next
        main.removeCallbacks(sleepRunnable)
        sleepAt = if (next > 0) System.currentTimeMillis() + next * 60_000L else 0L
        if (next > 0) main.postDelayed(sleepRunnable, next * 60_000L)
        notifyChange()
        return sleepLabel() ?: "Off"
    }

    /** The chip's face: null when off, "14m" counting down, "end" for end-of-episode. */
    fun sleepLabel(): String? = when {
        sleepMode == -1 -> "end"
        sleepMode > 0 && sleepAt > 0L ->
            "${(((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L)}m"
        else -> null
    }

    // ---- Per-show tuning (the Podcast Republic lift, 09-02) -----------------------------------

    /** A show you always play at ×1.5; a show whose first 45s are always the same jingle.
     *  Keyed by feed title in `ledger_player` prefs as JSON — small, local, per-device. */
    data class ShowTuning(val speed: Float?, val introSec: Int)

    private fun tuningMap(ctx: Context): org.json.JSONObject =
        runCatching { org.json.JSONObject(ctx.getSharedPreferences("ledger_player", 0)
            .getString("show_tuning", "{}").orEmpty()) }.getOrDefault(org.json.JSONObject())

    fun showTuning(ctx: Context, feed: String): ShowTuning? {
        val o = tuningMap(ctx).optJSONObject(feed) ?: return null
        val sp = o.optDouble("speed", 0.0).toFloat().takeIf { it > 0f }
        val intro = o.optInt("introSec", 0)
        return if (sp == null && intro <= 0) null else ShowTuning(sp, intro)
    }

    fun setShowTuning(ctx: Context, feed: String, speed: Float?, introSec: Int) {
        val map = tuningMap(ctx)
        if (speed == null && introSec <= 0) map.remove(feed)
        else map.put(feed, org.json.JSONObject().apply {
            if (speed != null) put("speed", speed.toDouble())
            if (introSec > 0) put("introSec", introSec)
        })
        ctx.getSharedPreferences("ledger_player", 0).edit()
            .putString("show_tuning", map.toString()).apply()
    }

    // ---- Up next (the lens IS the queue) ------------------------------------------------------

    /** What plays when this track runs off the end — armed by whoever started playback with the
     *  next unheard episode of the same lens, AntennaPod's queue without a queue screen. Cleared
     *  on every manual start/stop ([newSession]); an end-of-episode sleep timer outranks it. */
    var upNextTitle: String? = null; private set
    private var upNextRunner: (() -> Unit)? = null

    fun setUpNext(title: String?, run: (() -> Unit)?) {
        upNextTitle = title?.takeIf { it.isNotBlank() && run != null }
        upNextRunner = run
        notifyChange()
    }

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
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChange() { listeners.toList().forEach { it() } }

    /** File extensions the player treats as audiobooks/audio (vs e-books that open in the reader). */
    fun isAudioFile(name: String): Boolean =
        listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".wav", ".flac")
            .any { name.lowercase().endsWith(it) }

    val isActive: Boolean get() = tts?.isActive == true || media != null

    /** Whether the live backend is the speech engine (the transport's TTS-only chrome keys off this). */
    val isTtsBacked: Boolean get() = media == null && tts?.isActive == true
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
    /** Minutes of speech left, estimated from the characters still to be spoken at the current
     *  rate (~900 chars/min at 1× is where Android's engines land for English prose). */
    val ttsMinutesLeft: Int get() {
        val chars = tts?.remainingChars ?: return 0
        return kotlin.math.ceil(chars / (900.0 * speed.coerceAtLeast(0.5f))).toInt()
    }

    /** TTS seek by chunk (the bar's tap/drag on read-aloud; audio seeks by [seekTo]). */
    fun ttsSeekTo(chunk: Int) {
        tts?.seekTo(chunk); notifyChange()
    }

    /** Absolute seek (audio only — TTS moves in chunks via [skipForward]/[skipBack]). */
    fun seekTo(ms: Int) {
        media?.let { m ->
            runCatching { m.seekTo(ms.coerceIn(0, m.duration.coerceAtLeast(0))) }
            notifyChange()
        }
    }

    private fun engine(context: Context): LedgerTts =
        tts ?: LedgerTts(context.applicationContext).also { e ->
            e.onSentence = { s -> onFollowSentence?.invoke(s) }
            e.onStateChange = { main.post { notifyChange() } }
            tts = e
        }

    /** Start reading [text] aloud (TTS backend), tagging it with metadata for the control modal. */
    fun start(context: Context, title: String?, subtitle: String?, imageUrl: String?, text: String,
              capture: Capture? = null) {
        stopMedia()
        newSession()
        // The article being read aloud IS a capturable source (09-13: "when I click on the title
        // of the now playing item I want it to take me to the rss feed and show the read along
        // highlight") — the title's door back to the piece needs its URL.
        this.capture = capture
        this.title = title?.takeIf { it.isNotBlank() }
        this.subtitle = subtitle?.takeIf { it.isNotBlank() }
        this.image = null
        loadImage(context, imageUrl)
        engine(context).apply { speak(text); setRate(speed) }   // carry the chosen speed to the new read
        // The lifecycle anchor, for TTS too (Michael's ruling, 08-30, reversing -55's choice:
        // "the reader TTS doesn't bring up a podcast-like control but should"): read-aloud gets
        // the same foreground service + MediaSession as real audio — notification transport,
        // hardware keys, survival across backgrounding. toggle() already speaks TTS.
        appContext = context.applicationContext
        appContext?.let { LedgerPlayerService.start(it) }
        notifyChange()
    }

    /** Follow-along relay — the engine's sentence callback, surfaced for whichever reading
     *  surface is showing the text (the reader wires it while its page is the one being read). */
    var onFollowSentence: ((String) -> Unit)? = null

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
        appContext = context.applicationContext
        if (!speedLoaded) {
            speedLoaded = true
            speed = context.getSharedPreferences("ledger_player", 0).getFloat("speed", 1.0f)
        }
        listenId = capture?.url?.takeIf { it.isNotBlank() }
        loadImage(context, imageUrl)
        // Resume in two phases, the reader's own restore pattern (restoreReadingPosition):
        // start INSTANTLY from the local spot, then re-seek once the sidecar pull merges if
        // another device's playhead turns out to be newer. The heartbeat deliberately does not
        // start until that pull lands — a heartbeat stamped before the merge would out-merge the
        // other device's genuinely newer position with this device's stale one (the exact
        // silent-revert the reader's restore already had and fixed).
        val local = listenId?.let {
            com.toolsboox.plugin.calendar.ot.ListenStateStore.get(context, it)
        }
        // Started with no picture, but the fleet already named one (another device played it and
        // wrote its cover into the synced record): the transport draws that (10-12).
        if (imageUrl.isNullOrBlank()) adoptArt(local?.artworkUrl)
        val resumeMs = com.toolsboox.plugin.calendar.da.v2.ListenState.resumePointMs(local)
        runCatching {
            media = android.media.MediaPlayer().apply {
                // A backgrounded e-ink device sleeps its CPU with a vengeance; the partial wake
                // lock (held only while playing — MediaPlayer manages it) plus the foreground
                // service below are what let an episode outlive the screen and the app both.
                setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(source)
                setOnPreparedListener { mp ->
                    // The show's own tuning outranks the global speed for this session — and an
                    // intro skip fires only on a FRESH start (a resume already cleared the jingle).
                    val tuning = capture?.feedTitle?.takeIf { it.isNotBlank() }
                        ?.let { showTuning(context, it) }
                    runCatching { mp.playbackParams = mp.playbackParams.setSpeed(tuning?.speed ?: speed) }
                    if (resumeMs <= 0 && (tuning?.introSec ?: 0) > 0)
                        runCatching { mp.seekTo(tuning!!.introSec * 1000) }
                    // Jump back to where the listener left off — but only when the jump is worth
                    // making (>10s in, not into the outro of a finished episode): resumePointMs
                    // answers 0 for both of those, and a finished episode restarts from the top.
                    if (resumeMs > 0) runCatching { mp.seekTo(resumeMs.toInt()) }
                    mp.start()
                    // The lifecycle anchor: foreground service + MediaSession, for exactly as
                    // long as real audio runs (Michael, 08-30: "leaving the app and coming back
                    // can stop the playback"). TTS read-aloud deliberately stays unanchored —
                    // it reads the page you are on.
                    appContext?.let { LedgerPlayerService.start(it) }
                    main.post { notifyChange() }
                }
                setOnCompletionListener { mp ->
                    // Ran off the end: heard, whatever the last heartbeat said. Record it at the
                    // full duration (position == duration crosses the >95% done line) and push —
                    // "done" is the one fact every device wants promptly, it is what the Listen
                    // lens's unheard filters read. Captured from [mp] directly because stopMedia()
                    // is about to release the player.
                    val ctx = appContext; val id = listenId; val capSnapshot = capture
                    val dur = runCatching { mp.duration }.getOrDefault(0).toLong()
                    if (ctx != null && id != null && dur > 0) {
                        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
                            com.toolsboox.plugin.calendar.ot.ListenStateStore.record(ctx, id, dur, dur, sync = true,
                            audioUrl = capSnapshot?.enclosureUrl.orEmpty(),
                            title = capSnapshot?.title.orEmpty(), show = capSnapshot?.feedTitle.orEmpty(),
                            artworkUrl = capSnapshot?.imageUrl.orEmpty())
                        }
                    }
                    stopMedia()
                    // The lens is the queue: run straight into the next unheard episode —
                    // unless the sleep timer was set to "end of episode", which outranks it.
                    val advance = upNextRunner
                    if (sleepMode == -1) {
                        sleepMode = 0; sleepAt = 0L
                    } else if (advance != null) {
                        main.post { advance() }
                    }
                    main.post { notifyChange() }
                }
                setOnErrorListener { _, _, _ -> stopMedia(); main.post { notifyChange() }; true }
                prepareAsync()
            }
        }.onFailure { Timber.w(it, "audio start failed"); stopMedia() }
        if (listenId != null) {
            val startedSession = session
            val resumedUpdated = local?.updated ?: 0L
            val id = listenId!!
            com.toolsboox.plugin.calendar.ot.ListenStateStore.sync(context.applicationContext) { merged ->
                val remote = merged.firstOrNull { it.id == id && !it.isDeleted }
                main.post {
                    // The listener may have moved on while the pull was in flight — a stale
                    // session's answer must lose quietly (the [session] contract).
                    if (session != startedSession || media == null) return@post
                    // Re-seek only when the MERGE was won by a record we did not resume from —
                    // when the newest copy is our own, the difference between it and the playhead
                    // is just the seconds listened since, and "correcting" that would yank the
                    // listener backwards.
                    if (remote != null && remote.updated > resumedUpdated) {
                        val target = com.toolsboox.plugin.calendar.da.v2.ListenState.resumePointMs(remote)
                        if (target > 0 && kotlin.math.abs(target - positionMs) > RESEEK_SLOP_MS) {
                            seekTo(target.toInt())
                        }
                    }
                    main.removeCallbacks(heartbeat)
                    main.postDelayed(heartbeat, HEARTBEAT_MS)
                }
            }
        }
        notifyChange()
    }

    /** Play/pause toggle — dispatches to whichever backend is active. */
    fun toggle() {
        media?.let { m ->
            val pausing = runCatching { m.isPlaying }.getOrDefault(false)
            runCatching { if (m.isPlaying) m.pause() else m.start() }
            // Pause is a punctuation mark: the moment after it, another device is as likely as
            // this one to press play next, so the playhead rides to the hub now rather than
            // waiting out the local-only heartbeat.
            if (pausing) recordListen(sync = true)
            notifyChange(); return
        }
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
    private var speedLoaded = false

    /** Cycle to the next speed and apply it to whichever engine is live; returns the new speed. */
    fun cycleSpeed(): Float {
        val next = speeds[(speeds.indexOf(speed).let { if (it < 0) 1 else it } + 1) % speeds.size]
        setSpeed(next)
        return next
    }

    fun setSpeed(s: Float) {
        speed = s
        // The chosen speed is a fact about the listener, not the session — it survives restarts.
        appContext?.getSharedPreferences("ledger_player", 0)?.edit()?.putFloat("speed", s)?.apply()
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
        // Before the player is released: stop is the strongest punctuation there is, and the
        // whole point of the listen sidecar is that the position at THIS moment reaches the
        // device you pick the episode up on.
        stopping = true
        try { recordListen(sync = true) } finally { stopping = false }
        tts?.stop()
        stopMedia()
        newSession()
        title = null; subtitle = null; image = null
        notifyChange()
    }

    /** New track (or silence): whatever chapter/transcript state belonged to the last one is
     *  stale now, and any in-flight resolve for it must find a different session number. */
    private fun newSession() {
        upNextTitle = null; upNextRunner = null
        readingBook = null
        session++
        chapters = emptyList()
        transcriptProvider = null
        capture = null
        listenId = null
        progressHook = null
    }

    private fun stopMedia() {
        main.removeCallbacks(heartbeat)
        val had = media != null
        media?.let { runCatching { it.stop() }; runCatching { it.release() } }
        media = null
        // The anchor exists exactly as long as the audio does.
        if (had) appContext?.let { LedgerPlayerService.stop(it) }
    }

    /** The transport's picture, through the feed's thumbnail cache (10-12): the network at stage
     *  size, spilled to disk, so the art is there offline next time — it used to be a raw fetch
     *  on every play, so an offline episode played with no picture. A late answer for a track
     *  the listener already left loses quietly (the [session] contract). */
    private fun loadImage(context: Context, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http")) return
        val cache = com.toolsboox.plugin.feeds.ui.FeedThumbCache
        cache.get(url)?.takeIf { maxOf(it.width, it.height) >= cache.STAGE_SIDE * 3 / 4 }?.let { image = it; return }
        val started = session
        cache.load(context, url, diskFirst = false, side = cache.STAGE_SIDE) { bmp ->
            if (bmp != null && session == started) { image = bmp; notifyChange() }
        }
    }

    /**
     * The playing episode's picture, learned after it started (10-12) — the show's cover, found by
     * the play-time feed read when the episode carries no art of its own. Fills a BLANK — or
     * replaces [over], a stand-in the starter used for want of better (an image from the show
     * notes, which the show's cover outranks); any other art the starter handed in stays. The
     * capture is what the heartbeat writes, so from the next beat the synced ListenState names
     * the cover and the other devices draw it.
     */
    fun adoptArt(url: String?, over: String? = null) {
        val u = url?.takeIf { it.startsWith("http") } ?: return
        val cap = capture ?: return
        val had = cap.imageUrl
        val replacing = !had.isNullOrBlank() && had == over && had != u
        if (!had.isNullOrBlank() && !replacing) return
        capture = cap.copy(imageUrl = u)
        if (image == null || replacing) appContext?.let { loadImage(it, u) }
        notifyChange()
    }

    /**
     * Go to the playing episode's own entry — the title's door (see [showModal]).
     *
     * Resolved by URL against the feed cache rather than held as an object, because the transport
     * OUTLIVES the list it was started from: you press play, walk to the day page, and the adapter
     * that had the entry is long gone. The cache is what still knows.
     *
     * Routes through `FeedSelection.pendingInPaneEntry` — the same channel a gram's "Go to source"
     * uses — so the episode lands in the in-pane reader with its paging and its star rather than in
     * a second reader that would have to learn all of that again. A URL the cache no longer holds
     * says so instead of opening something adjacent, which on a transport would be the worst kind
     * of wrong: you would be reading one thing while hearing another.
     */
    private fun openPlayingEntry(fragment: androidx.fragment.app.Fragment, url: String) {
        val ctx = fragment.context ?: return
        val entry = runCatching {
            com.toolsboox.plugin.feeds.nw.FeedCache.loadFreshestEntries(ctx)
                .firstOrNull { it.url.isNotBlank() && it.url == url }
        }.getOrNull()
        if (entry == null) {
            android.widget.Toast.makeText(
                ctx, "That episode has aged out of the feed.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.toolsboox.plugin.feeds.ui.FeedSelection.pendingInPaneEntry = entry
        com.toolsboox.plugin.feeds.ui.FeedSelection.mode = "feed"
        com.toolsboox.plugin.feeds.ui.FeedSelection.kind = entry.kind
        com.toolsboox.plugin.feeds.ui.FeedSelection.filterFeedTitle = null
        runCatching {
            androidx.navigation.fragment.NavHostFragment.findNavController(fragment)
                .navigate(com.toolsboox.R.id.action_to_feeds)
        }
    }

    // ---- Control modal ---------------------------------------------------------------------

    /** Show the transport modal (featured image + title/source + ⏮ ⏯ ⏭ ⏹). Reachable from the
     *  Ledger directory's "▶ Now Playing" entry, so it works from any surface. */
    fun showModal(context: Context, fragment: androidx.fragment.app.Fragment? = null) {
        // ONE PLAYER, ONE FACE (Michael, 08-30: "the one in the RSS feeds is the better
        // version, but it should be in the tuck panel"): the modal is now just a SEAT for
        // [com.toolsboox.plugin.feeds.ui.NowPlayingCard] — the drawer's transport, with its
        // drag-to-seek bar, chapter line, capture chips and e-ink tick discipline — plus the
        // artwork the wider stage has room for. The bespoke five-button UI this replaces is
        // retired; its unique goods (art, the title-door, ★/📝) all ride the card now.
        var modalRef: android.app.AlertDialog? = null
        val card = com.toolsboox.plugin.feeds.ui.NowPlayingCard.build(
            context,
            navHost = fragment,
            onNavigate = { modalRef?.dismiss() },
            showArt = true,
            startUnfolded = true,
        ) { modalRef?.dismiss() }
        val dialog = android.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setView(card)
            .setNegativeButton("Close", null)
            .create()
        modalRef = dialog
        dialog.show()
    }
}
