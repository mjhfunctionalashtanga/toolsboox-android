package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.AudioChapter
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.Pairing
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.Span
import com.toolsboox.plugin.reader.ui.ReadAlongPairing.TextChapter
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * READ-ALONG, the stateful half: the pairing file beside the reading position, and the one
 * session that keeps the narration and the page together.
 *
 * TWO DIRECTIONS, ONE RULE EACH.
 *  • Audio → page: a one-second tick reads the playhead off [AbsPlayback] on the book's global
 *    timeline; when it crosses into the NEXT mapped chapter, the reader turns to that chapter —
 *    but only while the reader is SHOWING the paired book, and not if a hand has turned a page in
 *    the last 20 s (respect the reader). A jump on the transport (⏭ chapter, ±30 s) is the hand
 *    asking, so it turns the page regardless.
 *  • Page → audio: when the reader lands in a NEW section (a chapter jump from the contents, a page
 *    turn across a chapter boundary — never a scroll inside one), the narration seeks to that
 *    section's chapter start, debounced 1.2 s so a run of taps costs one seek. A section the
 *    narration is already inside needs no seek.
 * The two never chase each other: a landing this object caused is stamped programmatic and the
 * reverse rule ignores it.
 *
 * THE FILE: `readalong/<fileKey>.json` in filesDir and at the hub root, next to
 * `reading-state.json`, so a pairing made on the Boox is the pairing the iPad opens with. Newest
 * `updated` wins; unpairing writes a record with an empty item id (a tombstone) so the other
 * devices forget too. Wire shape on [ReadAlongPairing].
 */
object ReadAlong {

    /** How the follower reaches the reader on screen — [ReaderFragment] implements it. */
    interface Reader {
        val bookKey: String?
        val sectionIndex: Int
        fun goToHref(href: String)
        /** Turn to [fraction] of the way through section [index] — the pages inside a chapter
         *  follow the voice (approximate: an audiobook has no word timings). */
        fun goToSectionFraction(index: Int, fraction: Double) {}
    }

    /** Where a listen-along begins (the iOS twin's StartAt): this page, the chapter's first
     *  second, or wherever the audiobook itself was left. */
    enum class StartAt { HERE, CHAPTER_START, AUDIO_RESUME }

    /** What the last [start] did, for the toast: "Chapter 12 · 3:05". */
    @Volatile var lastStartReport: String = ""
    private var lastNudgeAt = 0L
    private var lastNudgeFrac = -1.0

    /** One live listen-along: which book, which audiobook, and its chapter spans. */
    class Session(
        val bookKey: String,
        val pairing: Pairing,
        val item: Audiobookshelf.Item,
        val spans: List<Span>,
    )

    /** A page turned by a hand in the last this-many ms blocks the audio from turning it back. */
    private const val MANUAL_GUARD_MS = 20_000L
    /** Relocates in this window after we navigated are ours, not the hand's. */
    private const val PROGRAMMATIC_MS = 4_000L
    /** A run of chapter taps costs one seek. */
    private const val REVERSE_DEBOUNCE_MS = 1_200L
    /** A playhead jump bigger than this between ticks is a skip, not playback. */
    private const val JUMP_SEC = 5.0
    private const val TICK_MS = 1_000L

    // ── The sidecar ──────────────────────────────────────────────────────────────────────────

    private fun file(context: Context, bookKey: String) =
        File(File(context.filesDir, "readalong").apply { mkdirs() }, ReadAlongPairing.fileKey(bookKey) + ".json")

    /** `updated` of any record, tombstone included (the merge clock; [ReadAlongPairing.fromJson] drops tombstones). */
    private fun stamp(text: String?): Long =
        runCatching { JSONObject(text ?: return 0L).optLong("updated", 0L) }.getOrDefault(0L)

    /** The pairing this device knows for [bookKey], or null (never paired, or unpaired). */
    fun load(context: Context, bookKey: String): Pairing? =
        ReadAlongPairing.fromJson(runCatching { file(context, bookKey).readText() }.getOrNull())

    /** Save locally now and ride to the hub in the background. */
    fun save(context: Context, bookKey: String, pairing: Pairing) {
        @Suppress("NAME_SHADOWING") val pairing = pairing.copy(bookKey = bookKey)   // the way back from Now Playing
        val text = ReadAlongPairing.toJson(pairing)
        runCatching { file(context, bookKey).writeText(text) }.onFailure { Timber.w(it, "readalong save") }
        val app = context.applicationContext
        LedgerSidecarSync.background { LedgerSidecarSync.push(app, ReadAlongPairing.remotePath(bookKey), text) }
    }

    /** Unpair: a tombstone (empty item id, fresh stamp) locally and on the hub. */
    fun forget(context: Context, bookKey: String) {
        val text = JSONObject().put("absItemId", "").put("updated", System.currentTimeMillis()).toString()
        runCatching { file(context, bookKey).writeText(text) }
        val app = context.applicationContext
        LedgerSidecarSync.background { LedgerSidecarSync.push(app, ReadAlongPairing.remotePath(bookKey), text) }
        if (session?.bookKey == bookKey) stop()
    }

    /**
     * Pull the hub's copy; the newer record (pairing or tombstone) wins and is saved. [onMerged]
     * (main thread) gets the pairing that stands afterwards — the reader relabels its button when
     * another device paired or unpaired this book.
     */
    fun pull(context: Context, bookKey: String, onMerged: (Pairing?) -> Unit) {
        val app = context.applicationContext
        LedgerSidecarSync.background {
            val local = runCatching { file(app, bookKey).readText() }.getOrNull()
            val remote = LedgerSidecarSync.pull(app, ReadAlongPairing.remotePath(bookKey))
            val winner = when {
                remote.isNullOrBlank() -> local
                stamp(remote) > stamp(local) -> remote.also { runCatching { file(app, bookKey).writeText(it) } }
                else -> local
            }
            // Our copy is newer than the hub's (or the hub had none): put it there.
            if (!local.isNullOrBlank() && winner === local && stamp(local) > stamp(remote)) {
                LedgerSidecarSync.push(app, ReadAlongPairing.remotePath(bookKey), local)
            }
            val p = ReadAlongPairing.fromJson(winner)
            main.post { onMerged(p) }
        }
    }

    // ── Building a pairing ───────────────────────────────────────────────────────────────────

    /** The item's chapters on its global timeline; a book with no chapter marks gets one per file. */
    fun audioChapters(item: Audiobookshelf.Item): List<AudioChapter> =
        if (item.chapters.isNotEmpty()) item.chapters.mapIndexed { i, c -> AudioChapter(i, c.start, c.end, c.title) }
        else item.tracks.sortedBy { it.startOffset }.mapIndexed { i, t ->
            AudioChapter(i, t.startOffset, t.startOffset + t.duration, t.title.ifBlank { "Part ${i + 1}" })
        }

    fun serverHost(context: Context): String =
        Audiobookshelf.config(context)?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }.orEmpty()

    /** Map [item] onto [toc] with Readest's rule and hand back the pairing (not yet saved). */
    fun pair(context: Context, bookTitle: String, toc: List<TextChapter>, item: Audiobookshelf.Item): Pairing {
        val audio = audioChapters(item)
        val map = ReadAlongPairing.match(toc, audio)
        return Pairing(
            absItemId = item.id, absServerHost = serverHost(context), bookTitle = bookTitle,
            audioTitle = item.title, audioChapterCount = audio.size, chapterMap = map,
            updated = System.currentTimeMillis(),
        )
    }

    /**
     * Every audiobook on the server that is a BOOK with audio, ranked for [bookTitle]/[author] —
     * the picker's list. Walks each book library page by page (capped), blocking; call off main.
     */
    fun candidates(context: Context, bookTitle: String, author: String): List<Audiobookshelf.Item> {
        val libs = Audiobookshelf.libraries(context).filter { it.mediaType == "book" }
        val seen = HashSet<String>()
        // THE SERVER'S OWN ANSWER FIRST (Michael, 09-11: "it brings up all sorts of other things, but
        // nothing that should be the book"): the title asked of every book library, its hits leading;
        // the page walk fills in behind — NOT filtered by the thin listing's audio flag (often absent
        // there; the full item decides at pair time).
        val lead = ArrayList<Audiobookshelf.Item>()
        val q = bookTitle.trim().take(80)
        if (q.isNotBlank()) for (lib in libs) {
            runCatching { Audiobookshelf.search(context, lib.id, q) }.getOrDefault(emptyList())
                .filter { !it.isPodcast && seen.add(it.id) }.forEach { lead += it }
        }
        val rest = ArrayList<Audiobookshelf.Item>()
        for (lib in libs) {
            var page = 0
            while (page < 20) {
                val (items, total) = Audiobookshelf.items(context, lib.id, page)
                items.filter { !it.isPodcast && seen.add(it.id) }.forEach { rest += it }
                page++
                if (page * Audiobookshelf.PAGE >= total || items.isEmpty()) break
            }
        }
        return rank(lead, bookTitle, author) + rank(rest, bookTitle, author)
    }

    /** [items] best-match-first for a title (and author, as a tie-breaker). */
    fun rank(items: List<Audiobookshelf.Item>, bookTitle: String, author: String): List<Audiobookshelf.Item> {
        val t = ReadAlongPairing.normalizeTitle(bookTitle)
        val a = author.lowercase().trim()
        // Score ONCE per item, then sort — `sortedByDescending` calls the selector on every
        // comparison, which put the fuzzy similarity inside an n·log n loop (the iOS twin's
        // "crazy slowdown", 09-11). Substring hits lead, the fuzzy rank fills in behind.
        fun score(it: Audiobookshelf.Item): Double {
            val k = ReadAlongPairing.normalizeTitle(it.title)
            val s = if (t.isBlank()) 0.0 else ReadAlongPairing.similarity(t, k)
            val contains = if (t.isNotBlank() && k.contains(t)) 0.3 else 0.0
            val by = if (a.isNotBlank() && it.author.lowercase().contains(a)) 0.2 else 0.0
            return s + contains + by
        }
        return items.map { it to score(it) }.sortedByDescending { it.second }.map { it.first }
    }

    // ── The session ──────────────────────────────────────────────────────────────────────────

    private val main = Handler(Looper.getMainLooper())
    var session: Session? = null; private set
    private var reader: Reader? = null

    private var lastPos: Double? = null
    private var lastSpan: Span? = null
    private var lastManualNavAt = 0L
    private var programmaticUntil = 0L
    private var lastSectionIndex = -1
    private var pendingReverse: Runnable? = null

    /** The reader on screen (attach in onResume, detach in onPause). */
    fun attach(r: Reader) { reader = r; lastSectionIndex = r.sectionIndex }
    fun detach(r: Reader) { if (reader === r) reader = null }

    /** Is THIS book's narration on the player right now (playing or paused)? */
    fun isActive(bookKey: String): Boolean {
        val s = session ?: return false
        return s.bookKey == bookKey && AbsPlayback.playingItemId() == s.item.id
    }

    /**
     * Start listening along: fetch the whole item, place the playhead where the reader stands
     * ([sectionIndex], [fraction] of the way through it — Readest's approximate timing), play.
     * Blocking (network); call off the main thread. Throws with a sentence for the user.
     */
    fun start(context: Context, bookKey: String, pairing: Pairing, sectionIndex: Int, fraction: Double,
              at: StartAt = StartAt.HERE): Session {
        val app = context.applicationContext
        val item = Audiobookshelf.item(app, pairing.absItemId)
        val audio = audioChapters(item)
        val spans = ReadAlongPairing.spans(pairing.chapterMap, audio, item.duration)
        if (spans.isEmpty()) throw Exception("No chapters are paired yet — Re-pair… to fix the mapping.")
        val startAt = when (at) {
            StartAt.HERE -> ReadAlongPairing.startSeconds(spans, sectionIndex, fraction) ?: spans.first().start
            StartAt.CHAPTER_START -> ReadAlongPairing.sectionRange(spans, sectionIndex)?.first ?: spans.first().start
            StartAt.AUDIO_RESUME -> runCatching { Audiobookshelf.progress(app)[item.id] }.getOrNull()
                ?.takeIf { !it.isFinished && it.currentTime > 0 }?.currentTime ?: spans.first().start
        }
        // The landing, SAID (the iOS twin, 09-11): the audio chapter and the second it starts at,
        // and whether this page's section had a paired chapter of its own.
        val landed = ReadAlongPairing.spanAt(spans, startAt)
        val chapterTitle = landed?.let { sp -> audio.firstOrNull { it.index == sp.mapping.audioChapterIndex }?.title } ?: "?"
        val own = spans.any { it.mapping.sectionIndex == sectionIndex }
        lastStartReport = "$chapterTitle · ${Audiobookshelf.clock(startAt)}" +
            (if (at == StartAt.HERE && !own) " (this section has no paired chapter — nearest before it)" else "")
        timber.log.Timber.i("readalong start %s section=%d frac=%.2f → %.0fs chapter=%s own=%s", at, sectionIndex, fraction, startAt, chapterTitle, own)
        val s = Session(bookKey, pairing, item, spans)
        main.post {
            session = s
            lastPos = null
            lastSpan = ReadAlongPairing.spanAt(spans, startAt)
            lastManualNavAt = 0L
            programmaticUntil = 0L
            main.removeCallbacks(tick); main.postDelayed(tick, TICK_MS)
        }
        AbsPlayback.play(app, item, startAt = startAt)
        return s
    }

    /** The pairing whose audiobook is [absItemId], if this device knows one — Now Playing's way back
     *  to the book. Scans the small pairing folder. */
    fun forAbsItem(context: Context, absItemId: String): Pairing? =
        File(context.filesDir, "readalong").listFiles { f -> f.name.endsWith(".json") }
            ?.asSequence()
            ?.mapNotNull { f -> ReadAlongPairing.fromJson(runCatching { f.readText() }.getOrNull()) }
            ?.firstOrNull { it.absItemId == absItemId }

    /**
     * The audiobook is ALREADY playing (from the shelf or Now Playing): follow it from here without
     * touching the playhead — the reader opened onto a book whose narration is running. Blocking
     * (fetches the item); call off the main thread.
     */
    fun adopt(context: Context, bookKey: String, pairing: Pairing) {
        if (session?.bookKey == bookKey) return
        val app = context.applicationContext
        if (AbsPlayback.playingItemId() != pairing.absItemId) return
        val item = runCatching { Audiobookshelf.item(app, pairing.absItemId) }.getOrNull() ?: return
        val spans = ReadAlongPairing.spans(pairing.chapterMap, audioChapters(item), item.duration)
        if (spans.isEmpty()) return
        val s = Session(bookKey, pairing, item, spans)
        main.post {
            if (AbsPlayback.playingItemId() != item.id) return@post
            session = s
            lastPos = null
            lastSpan = AbsPlayback.globalPosition()?.let { ReadAlongPairing.spanAt(spans, it) }
            lastManualNavAt = 0L
            programmaticUntil = 0L
            main.removeCallbacks(tick); main.postDelayed(tick, TICK_MS)
        }
    }

    /** Stop following (the audio keeps playing on the one player if it was). */
    fun stop() {
        session = null
        main.removeCallbacks(tick)
        pendingReverse?.let { main.removeCallbacks(it) }; pendingReverse = null
    }

    // Audio → page.
    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            val pos = AbsPlayback.globalPosition()
            if (pos == null) {
                // The player moved on to something else: the session is over.
                if (AbsPlayback.playingItemId() != s.item.id && com.toolsboox.ui.plugin.LedgerPlayer.isActive) { stop(); return }
                main.postDelayed(this, TICK_MS); return
            }
            val jumped = lastPos?.let { kotlin.math.abs(pos - it) > JUMP_SEC } ?: false
            lastPos = pos
            val span = ReadAlongPairing.spanAt(s.spans, pos)
            val r = reader
            val now = System.currentTimeMillis()
            val showing = r != null && r.bookKey == s.bookKey
            val handFree = now - lastManualNavAt > MANUAL_GUARD_MS
            if (span != null && span.mapping != lastSpan?.mapping) {
                lastSpan = span
                val needsTurn = r != null && (span.mapping.sectionIndex != r.sectionIndex || span.mapping.tocHref.contains('#'))
                if (showing && needsTurn && (handFree || jumped)) {
                    programmaticUntil = now + PROGRAMMATIC_MS
                    lastSectionIndex = span.mapping.sectionIndex
                    lastNudgeFrac = -1.0
                    r!!.goToHref(span.mapping.tocHref)
                }
            } else if (span != null && showing && handFree && span.mapping.sectionIndex >= 0 &&
                span.mapping.sectionIndex == r!!.sectionIndex && now - lastNudgeAt > 3_000L) {
                // INSIDE the chapter the pages follow too (Michael, 09-11): how far the voice is
                // through the section's stretch of recording is how far through the section the
                // page should be. Every few seconds, only with the hand off the page; the reader
                // decides whether that's a new page at all.
                val range = ReadAlongPairing.sectionRange(s.spans, span.mapping.sectionIndex)
                if (range != null && range.second > range.first) {
                    val frac = ((pos - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0)
                    if (kotlin.math.abs(frac - lastNudgeFrac) > 0.01) {
                        lastNudgeAt = now; lastNudgeFrac = frac
                        programmaticUntil = now + PROGRAMMATIC_MS
                        r.goToSectionFraction(span.mapping.sectionIndex, frac)
                    }
                }
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Page → audio. The reader's relocate handler calls this on every relocate; only a change of
     * SECTION does anything, and only when the landing was the hand's.
     */
    fun onRelocate(context: Context, bookKey: String?, sectionIndex: Int, manual: Boolean) {
        val s = session ?: return
        if (bookKey == null || bookKey != s.bookKey || sectionIndex < 0) return
        val now = System.currentTimeMillis()
        val changed = sectionIndex != lastSectionIndex
        lastSectionIndex = sectionIndex
        if (!manual || now < programmaticUntil) return   // a restore's landing, or our own
        lastManualNavAt = now
        if (!changed) return                         // a page inside the same section
        pendingReverse?.let { main.removeCallbacks(it) }
        val app = context.applicationContext
        val r = Runnable {
            pendingReverse = null
            val live = session ?: return@Runnable
            if (live !== s) return@Runnable
            val range = ReadAlongPairing.sectionRange(live.spans, sectionIndex) ?: return@Runnable
            val pos = AbsPlayback.globalPosition() ?: return@Runnable
            if (pos >= range.first && pos < range.second) return@Runnable   // already narrating this section
            lastSpan = ReadAlongPairing.spanAt(live.spans, range.first)
            lastPos = range.first
            Thread { runCatching { AbsPlayback.seekGlobal(app, range.first) }.onFailure { Timber.w(it, "readalong seek") } }.start()
        }
        pendingReverse = r
        main.postDelayed(r, REVERSE_DEBOUNCE_MS)
    }
}
