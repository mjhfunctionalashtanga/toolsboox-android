package com.toolsboox.plugin.feeds.ot

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.toolsboox.R
import com.toolsboox.da.Attachment
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.ot.AddToPickWords
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ArticlePickIndex
import com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes
import com.toolsboox.plugin.calendar.ot.DayLocks
import com.toolsboox.plugin.calendar.ot.MarkCard
import com.toolsboox.plugin.calendar.ot.MarkWords
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.ReadingEventEdit
import com.toolsboox.plugin.reader.ui.BookNote
import com.toolsboox.ui.plugin.ScreenFragment
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * AN ARTICLE'S HIGHLIGHTS AND NOTES — one controller for both readers that show an RSS article (the
 * Feeds pane and the standalone Articles reader). Michael 2026-10-12 approved the plan
 * (rss-highlight-notes-design.md, critique applied) and then ruled on it:
 *
 *  - "Yes add card to all picks. It goes in the day it was picked's log." and, looking at the
 *    selection menu, "Not sure we need the highlight and make a pick here. Highlight should make a
 *    gram pick anyway." → ONE TAP on Highlight saves the passage as a mark, draws it, writes its ONE
 *    Log line on today and places its ONE gram pick on today's All Picks ([highlight]). The menu's
 *    "Make pick" is gone; Highlight does both.
 *  - A note on that highlight — typed, handwritten, voice, photo, video — goes onto the SAME Log line
 *    (the one home for what is said about a passage, critique #4) and re-draws the SAME card; edits
 *    never add a card ([addText], [addInk], [addMedia] → [reface]). The line stays on the day it was
 *    highlighted, updated in place (ReadingEventEdit).
 *  - Removing a highlight removes its pick — or asks, naming the board, when the pick was moved onto
 *    one ([remove]).
 *  - "gram picks should highlight back too" → every pick (and old Log highlight) whose source is this
 *    article is drawn on it at paint time ([ArticleMarkSet], [ArticlePickIndex]) — one highlight per
 *    passage — and tapping one opens this same chooser: a note joins THAT pick ([ensureMark] adopts it
 *    on first use; no migration step anyone sees).
 *
 * The page side is assets/feeds/article-marks.js (identical on iOS). Marks are painted from the
 * page's head for the feed's own text and Reader view (no extra e-ink refresh), after load for a
 * live page; the other devices' marks are pulled at most every few minutes and redrawn only when
 * something changed. The head's marks are gathered OFF the main thread when the piece opens
 * ([open]); the page build only takes them if they're ready (review 2026-10-12 #5).
 */
class ArticleHighlights(
    private val fragment: ScreenFragment,
    private val service: CalendarDayService,
    private val root: () -> File,
    private val web: () -> WebView?,
    /** The host's A/V capture (voice, video, take-or-upload photo) — kept for the hosts; the
     *  chooser's media rows go straight to their capture now ([ScreenFragment.captureForPick],
     *  add-to-pick pin §1/§7), so their failures say the pin's words. */
    @Suppress("unused") private val captureAv: (Attachment.Kind, (Attachment) -> Unit) -> Unit,
    /** 🖼 the gallery straight — likewise. */
    @Suppress("unused") private val choosePicture: ((Attachment) -> Unit) -> Unit,
    /** ★ the surface's own star ceremony for the open article; null hides the row. */
    private val star: () -> (() -> Unit)?,
    /** Show the piece in another of its views ("feed" / "reader" / "live") — the "not on this
     *  version" row's one tap (critique #7). Null where the reader has one view. */
    private val switchView: ((String) -> Unit)? = null,
) {

    /** The open article, as the marks name it. */
    data class Article(
        val entryId: Long,
        val url: String,
        val title: String,
        val feedTitle: String,
        /** [BookNote.VIEW_FEED] · [BookNote.VIEW_READER] · [BookNote.VIEW_LIVE]. */
        val view: String,
    ) {
        val clean: String get() = ArticleKey.clean(url)
        val key: String get() = ArticleKey.of(url)
        val httpUrl: String get() = url.takeIf { it.startsWith("http", ignoreCase = true) } ?: ""
    }

    /** A selection as the page script captured it. */
    data class Anchor(
        val exact: String, val prefix: String, val suffix: String, val fraction: Double,
        val overlapId: String, val links: List<String>,
    )

    var article: Article? = null
        private set

    /** What the page last painted, by id — what a tap and the Annotations list resolve against. */
    @Volatile private var shown: List<ArticleMarkSet.Shown> = emptyList()
    @Volatile private var unplaced: Set<String> = emptySet()
    private var pendingJump: String? = null
    private var seededFor: String? = null

    /** The marks gathered for the opening piece, off the main thread ([open]), and the last list
     *  painted — what [headScript] seeds a page with (review 2026-10-12 #5). */
    @Volatile private var prepared: Pair<String, java.util.concurrent.Future<List<ArticleMarkSet.Shown>>>? = null
    @Volatile private var latest: Pair<String, List<ArticleMarkSet.Shown>>? = null

    /** Highlights being saved: id → done. A tap on one waits for its save ([ensureMark]); a second
     *  Highlight of the same passage while the first saves is the same highlight (review #8). */
    private val saving = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()
    private val savingPassages = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val ctx get() = fragment.requireContext()
    private val app get() = fragment.requireContext().applicationContext

    // ── The page ──────────────────────────────────────────────────────────────────────────────

    /** A new piece is opening ([a] null: the pane closed). [jumpTo]: a mark to land on (the Log).
     *  Its marks start gathering now, off the main thread, for the page head. */
    fun open(a: Article?, jumpTo: String? = null) {
        article = a
        shown = emptyList(); unplaced = emptySet(); seededFor = null
        pendingJump = jumpTo
        latest = null
        if (!warmed) { warmed = true; runCatching { ArticlePickIndex.warm(app) } }
        prepared = a?.let { art -> art.key to GATHER.submit(java.util.concurrent.Callable { gatherQuietly(art) }) }
    }

    /** The view changed under the same piece (Reader ⇄ feed text) — marks remember which. */
    fun setView(view: String) { article = article?.copy(view = view) }

    /**
     * The page head's share of the marks: the script and this article's marks as its seed, so a
     * built page (the feed's text, Reader view) draws them at its first parse — no second e-ink
     * refresh (critique #8). It never reads a file here — the page is built on the main thread
     * (review 2026-10-12 #5: a full bucket walk and the first index parse ran right here). The list
     * comes from the gather [open] started (waited on a beat at most) or from the last paint; not
     * ready → no seed, and [onPageFinished] paints instead (one more refresh, only that once).
     */
    fun headScript(): String {
        val a = article ?: return ""
        val list = latest?.takeIf { it.first == a.key }?.second
            ?: prepared?.takeIf { it.first == a.key }?.second?.let { f ->
                runCatching { f.get(HEAD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
            }
            ?: return ""
        shown = list
        seededFor = a.key
        val seed = ArticleMarkSet.paintJson(list).replace("</", "<\\/")
        return "<script>window.__ledgerMarksSeed=$seed;</script><script>${script(app).replace("</script", "<\\/script")}</script>"
    }

    /** onPageFinished: the script is in every page (a live page gets it now); a page that was not
     *  seeded is painted; the other devices' marks and any picks a sync brought are caught up in
     *  the background and redrawn only if something changed; a pending Log jump lands. */
    fun onPageFinished(view: WebView?) {
        val v = view ?: return
        val a = article ?: return
        v.evaluateJavascript(script(app), null)
        // Consumed per load: the next load of this piece (a live page, a re-render) is painted
        // unless its own build seeded it again.
        val seeded = seededFor == a.key
        seededFor = null
        if (seeded) {
            v.evaluateJavascript("window.__ledgerMarksUnplaced||'[]'") { raw -> unplaced = parseIds(raw); jumpIfPending() }
        } else {
            Thread {
                val list = runCatching { gather(a) }.getOrDefault(emptyList())
                ui { if (article?.key == a.key) paint(list) }
            }.apply { isDaemon = true }.start()
        }
        catchUp(a)
    }

    /** Pull this article's bucket (throttled) and repair the pick index; redraw on a change. */
    private fun catchUp(a: Article) {
        ArticleNoteStore.refresh(app, a.key) { regatherAndPaint(a) }
        Thread {
            var changed = false
            runCatching {
                var rounds = 0
                while (rounds < 20 && ArticlePickIndex.repairStale(app, service, 60) > 0) { changed = true; rounds++ }
            }.onFailure { Timber.w(it, "article pick index repair failed") }
            // A note added before its card's day reached this device: re-draw that card now, if
            // the day has come (review 2026-10-12 #6).
            runCatching { retryStaleFaces(a) }.onFailure { Timber.w(it, "article highlights: stale card faces") }
            if (changed) regatherAndPaint(a)
        }.apply { isDaemon = true }.start()
    }

    private fun regatherAndPaint(a: Article) {
        val list = gatherQuietly(a)
        if (sameLook(list, shown)) { if (article?.key == a.key) { shown = list; latest = a.key to list }; return }
        ui { if (article?.key == a.key) paint(list) }
    }

    /** Two lists draw the same page: same ids, same noted look, same passages. */
    private fun sameLook(x: List<ArticleMarkSet.Shown>, y: List<ArticleMarkSet.Shown>) =
        x.map { Triple(it.id, it.noted, it.text) } == y.map { Triple(it.id, it.noted, it.text) }

    private fun gather(a: Article): List<ArticleMarkSet.Shown> =
        ArticleMarkSet.assemble(ArticleNoteStore.listWithTombstones(app, a.key, a.clean), ArticlePickIndex.hits(app, a.key), a.title)

    private fun gatherQuietly(a: Article): List<ArticleMarkSet.Shown> = runCatching { gather(a) }.getOrDefault(emptyList())

    private fun paint(list: List<ArticleMarkSet.Shown>) {
        shown = list
        article?.key?.let { latest = it to list }
        val v = web() ?: return
        v.evaluateJavascript(
            "window.__ledgerMarks?window.__ledgerMarks.paint(${ArticleMarkSet.paintJson(list)}):'[]'"
        ) { raw -> unplaced = parseIds(raw); jumpIfPending() }
    }

    private fun repaint() {
        val a = article ?: return
        Thread {
            val list = runCatching { gather(a) }.getOrDefault(emptyList())
            ui { if (article?.key == a.key) paint(list) }
        }.apply { isDaemon = true }.start()
    }

    private fun jumpIfPending() {
        val id = pendingJump ?: return
        pendingJump = null
        val target = shown.firstOrNull { it.id == id || it.mark?.logId == id || it.line?.lineId == id }?.id ?: id
        web()?.evaluateJavascript("window.__ledgerMarks&&window.__ledgerMarks.jump(${JSONObject.quote(target)})", null)
    }

    private fun parseIds(raw: String?): Set<String> {
        val s = WebSelectionBridge.unquote(raw)
        return runCatching { JSONArray(s).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() } }
            .getOrDefault(emptySet())
    }

    /** The pane's link handler asks first: a tap on a drawn passage. */
    fun handleUrl(url: String): Boolean {
        if (!url.startsWith(SCHEME)) return false
        val id = runCatching { java.net.URLDecoder.decode(url.removePrefix(SCHEME), "UTF-8") }.getOrDefault("")
        shown.firstOrNull { it.id == id }?.let { showChooser(Target.Existing(it)) }
        return true
    }

    /**
     * A tap in a page-turn corner over a drawn passage opens the passage instead of turning
     * (critique #15: the bottom corners covered marks). [x], [y]: view pixels in the WebView.
     */
    fun cornerTap(x: Float, y: Float, turn: () -> Unit) {
        val v = web()
        if (v == null || shown.isEmpty()) { turn(); return }
        @Suppress("DEPRECATION") val scale = v.scale.takeIf { it > 0f } ?: 1f
        v.evaluateJavascript("window.__ledgerMarks?window.__ledgerMarks.hitAt(${x / scale},${y / scale}):''") { raw ->
            val id = WebSelectionBridge.unquote(raw)
            val hit = shown.firstOrNull { it.id == id }
            if (hit != null) showChooser(Target.Existing(hit)) else turn()
        }
    }

    // ── The verbs ─────────────────────────────────────────────────────────────────────────────

    /**
     * HIGHLIGHT — the selection menu's first verb. One tap: the mark, its Log line and its ONE pick,
     * drawn at once. A selection over a passage already drawn opens that one instead of a second
     * (the overlap rule, critique #15).
     */
    fun highlight() {
        capture { anchor ->
            if (anchor == null) { message("Select a passage first."); return@capture }
            existing(anchor.overlapId)?.let { showChooser(Target.Existing(it)); return@capture }
            keep(anchor, CalendarDayPageNotes.GRAM_PICKS) { m -> afterStep(m) }
        }
    }

    /** NOTE — the selection menu's second verb, and the Note seat: the chooser, with the passage in
     *  hand when there is one (an existing highlight's own chooser when it overlaps one). */
    fun note() {
        capture { anchor ->
            val hit = existing(anchor?.overlapId)
            when {
                hit != null -> showChooser(Target.Existing(hit))
                anchor != null -> showChooser(Target.Fresh(anchor))
                else -> showChooser(Target.Whole)
            }
        }
    }

    private fun existing(id: String?): ArticleMarkSet.Shown? =
        id?.takeIf { it.isNotBlank() }?.let { i -> shown.firstOrNull { it.id == i } }

    private fun capture(then: (Anchor?) -> Unit) {
        val v = web() ?: return then(null)
        v.evaluateJavascript("window.__ledgerMarks?window.__ledgerMarks.capture():null") { raw ->
            val s = WebSelectionBridge.unquote(raw)
            val o = runCatching { JSONObject(s) }.getOrNull()
            val anchor = o?.let {
                Anchor(it.optString("exact"), it.optString("prefix"), it.optString("suffix"),
                    it.optDouble("fraction", 0.0), it.optString("overlapId"),
                    it.optJSONArray("links")?.let { l -> (0 until l.length()).map { i -> l.getString(i) } } ?: emptyList())
            }?.takeIf { it.exact.isNotBlank() }
            if (anchor != null) { then(anchor); return@evaluateJavascript }
            // The page script isn't there yet (a live page still loading): the selection bridge's
            // passage, link lines off, normalised as the script would — no context, so the first
            // full match is the one drawn.
            WebSelectionBridge.probe(v) { sel ->
                val text = ArticleMarkSet.norm(ArticlePickIndex.passageOf(sel))
                val links = sel.lines().drop(1).map { it.trim() }.filter { it.contains("http") }
                then(text.takeIf { it.isNotBlank() }?.let { Anchor(it, "", "", 0.0, "", links) })
            }
        }
    }

    /** What a chooser is for: a passage just selected, a highlight already drawn, or the piece. */
    sealed class Target {
        data class Fresh(val anchor: Anchor) : Target()
        data class Existing(val shown: ArticleMarkSet.Shown) : Target()
        object Whole : Target()
    }

    /**
     * THE ONE LADDER (critique #9 — one order on both apps, Michael's ruling folding 🃏 into ❝):
     *   [↪ Open <view>] · [🗒 the note — open] · [✍ the handwriting — open] · ❝ Save highlight · ✍ ·
     *   🎤 · 📷 · ⌨ · ★ · 📝 · 📄 · 🖼 · 🎥 · 🔖 · 🔎 · 🎓 · 📋 Copy · 🗑 Remove
     * ❝ leads whenever a passage is in hand and not yet kept (a bare highlight is a complete
     * annotation). Every keeping row on a fresh passage keeps it first (mark + line + its one card),
     * then runs onto it; on a drawn one it runs onto that highlight — never a second card.
     * [openView]: the "not on this version" row's way to the view it is in (the Annotations list).
     */
    fun showChooser(target: Target, openView: Pair<String, () -> Unit>? = null, addOnly: Boolean = false) {
        val a = article ?: return
        val passage = when (target) {
            is Target.Fresh -> target.anchor.exact
            is Target.Existing -> target.shown.text
            Target.Whole -> ""
        }
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        // Run [act] on the highlight this chooser is about, keeping a fresh passage first.
        fun onMark(act: (BookNote) -> Unit) {
            when (target) {
                is Target.Fresh -> keep(target.anchor, CalendarDayPageNotes.GRAM_PICKS) { m -> ui { act(m) } }
                is Target.Existing -> background { ensureMark(target.shown)?.let { m -> ui { act(m) } } }
                Target.Whole -> wholeNote { m -> ui { act(m) } }
            }
        }
        // ＋ ADD ONLY (add-to-pick pin §2 D2–D4): the Add ladder on this highlight's pick — the
        // canon order with the extras taken out, never ★, never asking for anything.
        if (addOnly) { onMark { m -> AddToPick.showLadder(fragment, tied(m)) }; return }
        openView?.let { (label, go) -> rows += "↪  $label" to go }
        if (target is Target.Existing) {
            // What is said, readable from the chooser's own row (review 2026-10-12, iOS parity): the
            // words when there are words, the handwriting when there is handwriting — a voice- or
            // photo-only note opened an empty text box under "the note on this".
            val s = target.shown
            val words = s.line?.note.orEmpty()
            if (words.isNotBlank()) rows += AddToPickWords.openNote(preview(words, 80)) to { openTyped(s) }
            if (s.line?.ink == true) rows += AddToPickWords.OPEN_INK to { openInk(s) }
        }
        if (target is Target.Fresh) rows += AddToPickWords.SAVE_HIGHLIGHT to {
            keep(target.anchor, CalendarDayPageNotes.GRAM_PICKS) { m -> afterStep(m) }
        }
        rows += AddToPickWords.HANDWRITE to {
            FeedNoteGram.inkNoteDialog(fragment, passage) { ink ->
                if (ink == null) return@inkNoteDialog
                onMark { m -> background { addInk(m, ink) } }
            }
        }
        // Each medium straight to its capture (pin §1): 📷 Take a photo opens the camera — the
        // take-or-upload prompt went, since 🖼 Choose a picture is its own row (one tap saved).
        rows += AddToPickWords.VOICE to {
            fragment.captureForPick(AddToPick.Capture.VOICE) { att -> onMark { m -> background { addMedia(m, att) } } }
        }
        rows += AddToPickWords.TAKE_PHOTO to {
            fragment.captureForPick(AddToPick.Capture.CAMERA) { att -> onMark { m -> background { addMedia(m, att) } } }
        }
        rows += AddToPickWords.TYPE to { typeNote(target, passage, ::onMark) }
        star()?.let { s -> rows += "★  Star it" to { s() } }
        if (passage.isNotBlank()) rows += "📝  Onto today's note" to {
            when (target) {
                // A fresh passage's one card lands on today's note instead of All Picks.
                is Target.Fresh -> keep(target.anchor, com.toolsboox.plugin.calendar.ot.GramDestinations.NOTES_PAGE) { _ -> trip(com.toolsboox.plugin.calendar.ot.GramDestinations.NOTES_PAGE) }
                // A kept one: a copy onto the page you're writing — the explicit "put it here".
                else -> background {
                    // Today's page that won't read says so, and the trip there isn't taken.
                    val refused = placeCopyOnTodaysNote(passage)
                    ui { if (refused != null) message(refused) else trip(com.toolsboox.plugin.calendar.ot.GramDestinations.NOTES_PAGE) }
                }
            }
        }
        if (passage.isNotBlank()) rows += "📄  New note with this" to {
            onMark { _ -> newTextNote(passage) }
        }
        rows += AddToPickWords.CHOOSE_PICTURE to {
            fragment.captureForPick(AddToPick.Capture.GALLERY) { att -> onMark { m -> background { addMedia(m, att) } } }
        }
        rows += AddToPickWords.VIDEO to {
            fragment.captureForPick(AddToPick.Capture.VIDEO) { att -> onMark { m -> background { addMedia(m, att) } } }
        }
        val links = when (target) {
            is Target.Fresh -> target.anchor.links
            else -> Regex("""https?://[^\s"'<>)\]]+""").findAll(passage).map { it.value }.distinct().toList()
        }
        if (links.isNotEmpty()) rows += "🔖  ${links.size} link${if (links.size == 1) "" else "s"} → Later" to {
            val urls = links.map { it.substringAfter(" → ") }
            Thread {
                for (u in urls.take(40)) runCatching {
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(app, LocalDate.now(), "read", u, u)
                }
            }.apply { isDaemon = true }.start()
            message("🔗 ${urls.size} → Later")
        }
        // ✏️ THE STUDIO DOOR (pin §1, restoring what -328 took from the article): the Pick sheet,
        // TIED to this highlight — what you add lands on its line; ❝ re-faces its own pick.
        if (passage.isNotBlank()) rows += AddToPickWords.STUDIO to { onMark { m -> openStudio(m) } }
        rows += "🔎  Ask about this" to {
            com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                fragment = fragment, selection = passage.ifBlank { null },
                title = a.title, link = a.url, sourceLabel = a.feedTitle)
        }
        rows += "🎓  Educate me" to {
            com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
                fragment = fragment, text = passage.ifBlank { a.title },
                title = a.title, link = a.url, sourceLabel = a.feedTitle)
        }
        if (target is Target.Existing) {
            rows += "📋  Copy text" to {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Ledger", passage))
                message("Copied")
            }
            rows += "🗑  Remove highlight" to { remove(target.shown) }
        }
        val b = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
        if (passage.isNotBlank()) {
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()
            b.setCustomTitle(android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(px(20), px(16), px(20), px(4))
                addView(android.widget.TextView(ctx).apply {
                    text = "Highlight"; textSize = 18f; setTextColor(0xFF000000.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(android.widget.TextView(ctx).apply {
                    text = "“$passage”"; textSize = 13f; setTextColor(0xFF444444.toInt())
                    maxHeight = px(120); isVerticalScrollBarEnabled = true
                    movementMethod = android.text.method.ScrollingMovementMethod()
                    setPadding(0, px(4), 0, 0)
                })
            })
        } else b.setTitle("🖍  Note")
        fragment.showModal(b.create())
    }

    private fun typeNote(target: Target, passage: String, onMark: ((BookNote) -> Unit) -> Unit) {
        // Editing: the words already on this highlight's line, prefilled — the same line, in place.
        if (target is Target.Existing) background {
            val m = ensureMark(target.shown) ?: return@background
            val current = lineOf(m)?.second?.note.orEmpty()
            ui {
                FeedNoteGram.typeNoteDialog(fragment, passage, current) { words ->
                    if (words == current) return@typeNoteDialog
                    background { addText(m, words) }
                }
            }
        } else FeedNoteGram.typeNoteDialog(fragment, passage) { words ->
            when {
                // Saved with no words: a fresh passage is still kept (a bare highlight is a
                // complete annotation); a note about nothing is nothing.
                words.isBlank() && target is Target.Fresh -> onMark { m -> afterStep(m) }
                words.isBlank() -> Unit
                else -> onMark { m -> background { addText(m, words) } }
            }
        }
    }

    /** The typed words on a drawn highlight, to read and edit in place. */
    private fun openTyped(s: ArticleMarkSet.Shown) {
        background {
            val m = ensureMark(s) ?: return@background
            val current = lineOf(m)?.second?.note.orEmpty()
            ui {
                FeedNoteGram.typeNoteDialog(fragment, s.text, current) { words ->
                    if (words != current) background { addText(m, words) }
                }
            }
        }
    }

    /** The handwriting on a drawn highlight, shown — and another page of it, if you like. */
    private fun openInk(s: ArticleMarkSet.Shown) {
        background {
            val m = ensureMark(s) ?: return@background
            val ink = lineOf(m)?.second?.attachments
                ?.lastOrNull { it.kind == Attachment.Kind.PHOTO && it.filename.startsWith(INK_PREFIX) }
                ?: return@background ui { message("That handwriting isn't on this device yet.") }
            val bmp = runCatching { FeedNoteGram.decodeLocal(File(LedgerPaths.attachmentsDir(app), ink.filename)) }.getOrNull()
            ui {
                val img = android.widget.ImageView(ctx).apply { setImageBitmap(bmp); adjustViewBounds = true }
                fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle("✍  Handwritten note").setView(img)
                    .setPositiveButton("Write another") { _, _ ->
                        FeedNoteGram.inkNoteDialog(fragment, s.text) { more ->
                            if (more != null) background { addInk(m, more) }
                        }
                    }
                    .setNegativeButton(android.R.string.ok, null).create())
            }
        }
    }

    // ── Keeping ───────────────────────────────────────────────────────────────────────────────

    /**
     * Keep [anchor]: the mark, its Log line on today, and its ONE card on today's [page] (All Picks,
     * or today's note for 📝) — the line and the card in ONE locked save of today. [then] runs on
     * the main thread with the mark, once it is saved.
     *
     * DRAWN FIRST, SAVED AFTER (review 2026-10-12 #8): the save renders a card face and loads and
     * writes today's day file — seconds on a B6 with a heavy day — and the tap looked dead until it
     * finished, so a second tap made a second mark, line and card. The passage is painted at once
     * (the one e-ink refresh the save would have cost anyway; the page is the confirmation), a tap
     * on it waits for the save ([ensureMark]), and Highlight on a passage still saving does nothing.
     * A failed save takes the paint back off.
     *
     * The Log line and the card carry the passage WITH its links (Michael 09-11: "make sure linked
     * text are included in a highlight pick"; review R2 #2 — the one-tap path had dropped them, so
     * Log search missed the link and the iPad offered no 🔖 row). [ArticlePickIndex.passageOf]
     * strips them again for painting and matching.
     */
    private fun keep(anchor: Anchor, page: String, then: (BookNote) -> Unit) {
        val a = article ?: return
        val n = ArticleMarkSet.norm(anchor.exact)
        if (!savingPassages.add(n)) {
            // Already being kept: the same highlight — what was asked of it runs on that one, once
            // its save is in.
            val pending = shown.firstOrNull { it.mark != null && ArticleMarkSet.norm(it.text) == n }?.mark ?: return
            background {
                saving[pending.id]?.await(SAVE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
                ArticleNoteStore.get(app, a.key, pending.id)?.let { m -> ui { then(m) } }
            }
            return
        }
        val today = LocalDate.now()
        val id = "mfhl-${a.entryId.coerceAtLeast(0)}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val pickId = UUID.randomUUID()
        val passage = anchor.exact
        val mark = BookNote(
            id = id, type = BookNote.ANNOTATION,
            cfi = ArticleMarkSet.anchorJson(passage, anchor.prefix, anchor.suffix),
            fraction = anchor.fraction, text = passage,
            url = a.clean, title = a.title, source = a.feedTitle, entryId = a.entryId.coerceAtLeast(0),
            view = a.view, day = today.toString(), pickId = pickId.toString(), logId = id,
            logDay = today.toString(), pickDay = today.toString(),
        )
        val done = java.util.concurrent.CountDownLatch(1)
        saving[id] = done
        paint(shown + ArticleMarkSet.Shown(id, passage, anchor.prefix, anchor.suffix, mark = mark))
        background {
            try {
                val withLinks = withLinks(passage, anchor.links)
                val face = FeedNoteGram.noteCard(withLinks, null, emptyList(), null, FeedNoteGram.footer(a.title, a.feedTitle))
                val attempt = runCatching {
                    PickingsPlacement.place(
                        service, root(), face, today, page,
                        sourceLink = a.httpUrl, sourceLabel = FeedNoteGram.label(a.title, a.feedTitle),
                        cardText = withLinks, sourceFeed = a.feedTitle, elementId = pickId,
                        alsoOnDay = { day ->
                            if (day.readingEvents.none { it.id == id }) day.readingEvents.add(lineFor(a, id, withLinks, today))
                        })
                }.onFailure { Timber.w(it, "article highlight: the card and line weren't saved") }
                if (attempt.isFailure) {
                    ui {
                        // Today's page that won't read says so (day-file guard; the placement's line,
                        // iOS's words — MarkWords); nothing was written over it.
                        message(if (attempt.exceptionOrNull() is CalendarDayService.UnreadableDayException) MarkWords.HIGHLIGHT_REFUSED
                            else "Couldn't save that highlight.")
                        if (article?.key == a.key) paint(shown.filter { it.id != id })
                    }
                    return@background
                }
                ArticleNoteStore.put(app, a.key, mark)
                val list = gatherQuietly(a)
                ui {
                    if (article?.key == a.key) {
                        if (sameLook(list, shown)) { shown = list; latest = a.key to list } else paint(list)
                    }
                    runCatching { fragment.onExternalGramPlaced(page) }
                    then(mark)
                }
            } finally {
                done.countDown(); saving.remove(id); savingPassages.remove(n)
            }
        }
    }

    /** The passage and, under it, one line per link in it ("label → https://…" / a bare URL) — the
     *  shape WebSelectionBridge always stored and [ArticlePickIndex.passageOf] takes back off. */
    private fun withLinks(passage: String, links: List<String>): String =
        if (links.isEmpty()) passage else passage + "\n" + links.joinToString("\n")

    /**
     * A Log line about the piece, on [day]. Stamped NOW when [day] is today, else at noon of [day]
     * (review 2026-10-12 #13: a line made late on an old pick's day — its first note — read as made
     * at this moment, and the Log's row time comes from it).
     */
    private fun lineFor(a: Article, id: String, passage: String, day: LocalDate, note: String? = null) =
        adds.lineFor(a, id, passage, day, note)

    /** A note with no passage — about the whole piece: a NOTE mark (the Annotations list carries it
     *  beside the highlights), its line on today and, with its first words, its one card. */
    private fun wholeNote(then: (BookNote) -> Unit) {
        val a = article ?: return
        background {
            val today = LocalDate.now()
            val id = "mfnote-${a.entryId.coerceAtLeast(0)}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val added = try {
                ReadingEventEdit.addIfAbsent(service, root(), today, lineFor(a, id, "", today))
            } catch (e: CalendarDayService.UnreadableDayException) {
                ui { message(CalendarDayService.refusal(e, MarkWords.LOG_IT) ?: "Couldn't save that note.") }
                return@background
            }
            if (!added) {
                ui { message("Couldn't save that note.") }
                return@background
            }
            val mark = BookNote(id = id, type = BookNote.NOTE, url = a.clean, title = a.title, source = a.feedTitle,
                entryId = a.entryId.coerceAtLeast(0), view = a.view, day = today.toString(), logId = id,
                logDay = today.toString())
            ArticleNoteStore.put(app, a.key, mark)
            then(mark)
        }
    }

    /**
     * The mark behind a drawn highlight — itself, or an old pick / Log line ADOPTED now, on first
     * use, with a deterministic id ([ArticleMarkSet.adoptedId]) so two devices adopting it make one
     * mark. Adoption never places a card (critique #13): an old pick keeps being its card; a
     * cardless old line gets its one card only when a note first arrives ([reface]). Off the main
     * thread. Null when there is no mark to act on — a REMOVED one stays removed (review 2026-10-12
     * #10: the drawn copy came back here and a note saved it live again, onto a dead line).
     */
    private fun ensureMark(s: ArticleMarkSet.Shown): BookNote? {
        saving[s.id]?.await(SAVE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
        val a = article ?: return null
        s.mark?.let { m ->
            val stored = ArticleNoteStore.stored(app, a.key, m.id)
            if (stored?.deletedAt != null) { ui { message("That highlight was removed.") }; return null }
            return stored ?: m
        }
        val id = ArticleMarkSet.adoptedId(s)
        ArticleNoteStore.stored(app, a.key, id)?.let { held ->
            if (held.deletedAt != null) { ui { message("That highlight was removed.") }; return null }
            return held
        }
        // Its card and its line each where the index found them — an old pick and an old line of
        // the same passage can sit days apart ([BookNote.logDay] / [BookNote.pickDay]).
        val day = s.lineDay ?: s.cardDay ?: LocalDate.now()
        val ctxAnchor = anchorFromPage(s.id)
        val mark = BookNote(
            id = id, type = BookNote.ANNOTATION,
            cfi = ArticleMarkSet.anchorJson(s.text, ctxAnchor?.first.orEmpty(), ctxAnchor?.second.orEmpty()),
            text = s.text, url = a.clean, title = a.title, source = a.feedTitle,
            entryId = a.entryId.coerceAtLeast(0), view = a.view, day = day.toString(),
            pickId = s.pick?.elementId.orEmpty(), logId = s.line?.lineId.orEmpty(),
            logDay = s.line?.date?.toString().orEmpty(), pickDay = s.pick?.date?.toString().orEmpty(),
            createdAt = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        ArticleNoteStore.putIfAbsent(app, a.key, mark)
        return ArticleNoteStore.get(app, a.key, id)
    }

    /** The drawn passage's context from the live page, for an adopted mark's anchor (blocking a
     *  beat on the main thread's answer; null if the page can't say). */
    private fun anchorFromPage(id: String): Pair<String, String>? {
        val v = web() ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Pair<String, String>? = null
        ui {
            v.evaluateJavascript(
                "(function(){try{var m=window.__ledgerMarks;if(!m||!m.contextOf)return null;return m.contextOf(${JSONObject.quote(id)});}catch(e){return null}})()"
            ) { raw ->
                runCatching { JSONObject(WebSelectionBridge.unquote(raw)) }.getOrNull()?.let {
                    out = it.optString("prefix") to it.optString("suffix")
                }
                latch.countDown()
            }
        }
        latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    // ── What is added (PickAdds — moved there so every door reaches it: the Pick sheet, the
    // Highlight after-step, the held pick, the Log; add-to-pick pin §3) ────────────────────────

    /** The adds' engine: the line, the one face, its slots — saying what happened on the main thread. */
    internal val adds: PickAdds by lazy { PickAdds(app, service, root) { msg -> ui { message(msg) } } }

    /** The mark's Log line and the day it is on — the one home of what's said about the passage. */
    private fun lineOf(m: BookNote): Pair<LocalDate, ReadingEvent>? = adds.lineOf(m)

    /** [ReadingEventEdit.update] on a Log line with no highlight (the Annotations list's Log notes),
     *  saying why when it didn't: not here yet, or (day-file guard) "Couldn't change the line —
     *  <page> won't read" ([MarkWords.CHANGE_THE_LINE], iOS's words). True when the line was changed. */
    private fun updated(day: LocalDate, logId: String, change: (ReadingEvent) -> ReadingEvent): Boolean {
        val hit = try {
            ReadingEventEdit.update(service, root(), day, logId, change = change)
        } catch (e: CalendarDayService.UnreadableDayException) {
            ui { message(CalendarDayService.refusal(e, MarkWords.CHANGE_THE_LINE) ?: NOT_HERE_YET) }; return false
        }
        if (hit == null) { ui { message(NOT_HERE_YET) }; return false }
        return true
    }

    private fun addText(mark: BookNote, words: String) {
        val a = article ?: return
        adds.addText(a, mark, words)?.let { msg -> ui { message(msg); repaint() } }
    }

    private fun addInk(mark: BookNote, ink: Bitmap) {
        val a = article ?: return
        adds.addInk(a, mark, ink)?.let { msg -> ui { message(msg); repaint() } }
    }

    private fun addMedia(mark: BookNote, att: Attachment) {
        val a = article ?: return
        adds.addMedia(a, mark, att)?.let { msg -> ui { message(msg); repaint() } }
    }

    private fun staleFaces(key: String, markId: String, stale: Boolean) = adds.staleFaces(key, markId, stale)

    private fun retryStaleFaces(a: Article) = adds.retryStaleFaces(a)

    /**
     * 📝 on a highlight already kept: a COPY of the passage onto today's note — the explicit "put it
     * here". It carries no `cardText` (review 2026-10-12 #3): a copy is not a pick OF the passage, so
     * it never highlights back, and removing the highlight never reaches into the note you put it on.
     * Null once the copy is placed; when today's page won't read (day-file guard: nothing is written
     * over it), the words that say so.
     */
    private fun placeCopyOnTodaysNote(passage: String): String? {
        val a = article ?: return null
        val attempt = runCatching {
            PickingsPlacement.place(service, root(),
                FeedNoteGram.noteCard(passage, null, emptyList(), null, FeedNoteGram.footer(a.title, a.feedTitle)),
                LocalDate.now(), com.toolsboox.plugin.calendar.ot.GramDestinations.NOTES_PAGE,
                sourceLink = a.httpUrl, sourceLabel = FeedNoteGram.label(a.title, a.feedTitle),
                cardText = "", sourceFeed = a.feedTitle)
        }.onFailure { Timber.w(it, "article highlight: the copy onto today's note wasn't placed") }
        return CalendarDayService.refusal(attempt.exceptionOrNull(), MarkWords.PUT_ON_TODAYS_NOTE)
    }

    private fun newTextNote(passage: String) {
        val a = article ?: return
        val body = buildString {
            append("“").append(passage).append("”\n\n— ").append(FeedNoteGram.label(a.title, a.feedTitle))
            if (a.httpUrl.isNotBlank()) append("\n").append(a.httpUrl)
        }
        background {
            com.toolsboox.plugin.textnotes.TextNotesStore.addNote(app, LocalDate.now(), a.title.ifBlank { "Highlight" }, body)
            ui {
                runCatching {
                    com.google.android.material.snackbar.Snackbar.make(fragment.requireView(), "Text note made.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction("Open") {
                            runCatching {
                                androidx.navigation.fragment.NavHostFragment.findNavController(fragment)
                                    .navigate(R.id.action_to_text_notes)
                            }
                        }.show()
                }
            }
        }
    }

    // ── Removing ──────────────────────────────────────────────────────────────────────────────

    /** One card a removal will take: its id and where it is (from the index when it knows). */
    private data class CardAt(val id: String, val day: LocalDate, val page: String?) {
        val inAllPicks: Boolean get() = page == null || page.substringBefore('#') == CalendarDayPageNotes.GRAM_PICKS
    }

    /**
     * 🗑 — the mark (tombstoned, so the removal travels), its Log line (tombstoned, so search, the
     * corpus, Your Year and the Journal never show it again — critique #2) and its card. Two taps for
     * a bare highlight on All Picks (critique #14). It asks first when a note rides on it, or when the
     * card was moved onto a board — naming the board (Michael 2026-10-12: "removing the highlight
     * removes its pick (or asks, if the pick was moved onto a board — say which)").
     *
     * EVERY SOURCE OF THE PASSAGE goes (review 2026-10-12 #3, [ArticleMarkSet.sourcesOf]) — the page
     * draws one highlight per passage, and a hidden twin painted it straight back. Where each card is
     * comes from the index, so the question opens without loading a day (review #8).
     */
    private fun remove(s: ArticleMarkSet.Shown) {
        val a = article ?: return
        background {
            saving[s.id]?.await(SAVE_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            val sources = ArticleMarkSet.sourcesOf(s,
                ArticleNoteStore.listWithTombstones(app, a.key, a.clean), ArticlePickIndex.hits(app, a.key))
            val cards = sources.associateWith { cardOf(it) }
            val moved = cards.values.filterNotNull().firstOrNull { !it.inAllPicks }
            val boardName = moved?.let { MarkCard.boardName(app, it.day, it.page.orEmpty()) }
            val noted = sources.any { it.noted }
            ui {
                if (!noted && moved == null) { background { doRemove(a, sources, cards, removeCard = true) }; return@ui }
                val msg = buildString {
                    if (noted) append("Its note comes off with it.")
                    if (moved != null) {
                        if (isNotEmpty()) append("\n\n")
                        append("Its pick was moved onto $boardName.")
                    }
                }
                val b = AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle("Remove this highlight?")
                    .setMessage(msg)
                    .setNegativeButton(android.R.string.cancel, null)
                // iOS's words, Michael's noun (review R2 #5): the pick, not "the card".
                if (moved != null) {
                    b.setPositiveButton("Remove, pick too") { _, _ -> background { doRemove(a, sources, cards, true) } }
                    b.setNeutralButton("Keep the pick") { _, _ -> background { doRemove(a, sources, cards, false) } }
                } else {
                    b.setPositiveButton("Remove") { _, _ -> background { doRemove(a, sources, cards, true) } }
                }
                fragment.showModal(b.create())
            }
        }
    }

    /** A source's card, where the index says it is; a day file is opened only when the index
     *  doesn't know the card (a mark whose pick it hasn't seen yet). */
    private fun cardOf(src: ArticleMarkSet.Shown): CardAt? {
        val pickId = src.mark?.pickId?.ifBlank { null } ?: src.pick?.elementId?.ifBlank { null } ?: return null
        src.pick?.takeIf { it.elementId.equals(pickId, ignoreCase = true) }?.let { return CardAt(pickId, it.date, it.page) }
        val day = src.cardDay ?: LocalDate.now()
        val at = MarkCard.locate(service, root(), day, pickId)
        return CardAt(pickId, at?.day ?: day, at?.page)
    }

    /** Take every source off: each line off ITS day, each card off its own (in one locked save
     *  when they share a day), each mark tombstoned — or, never adopted, adopted-then-tombstoned so
     *  the other device's index can't paint it back from a day file that hasn't heard yet; a kept
     *  card stays claimed by that tombstone and never draws the passage again. */
    private fun doRemove(a: Article, sources: List<ArticleMarkSet.Shown>, cards: Map<ArticleMarkSet.Shown, CardAt?>,
                         removeCard: Boolean) {
        val today = LocalDate.now()
        // A source whose line's or card's page won't read (day-file guard) is left exactly as it is —
        // its mark too, so the highlight still stands where its line does — and the words say which
        // page; the other sources still come off.
        var refused: CalendarDayService.UnreadableDayException? = null
        var refusedDoing = MarkWords.TAKE_THE_LINE_OFF
        for (src in sources) {
            val card = cards[src]
            val lineId = src.mark?.logId?.ifBlank { null } ?: src.line?.lineId
            val lineDay = src.lineDay ?: card?.day ?: today
            var cardDone = !removeCard || card == null
            // THE PICK'S DAY IS READ FIRST (2026-10-16 review, item 8): the line used to come off and
            // only then the pick's day refuse — the mark kept, its line gone, and the next note made
            // a new line under a new id. A pick whose day won't read now leaves the whole source
            // exactly as it is, line included.
            if (!cardDone && card != null) {
                val at = MarkCard.removalDay(service, root(), card.day, card.id)
                val read = service.read(root(), at)
                if (read is CalendarDayService.DayRead.Unreadable) {
                    val e = CalendarDayService.UnreadableDayException(read.file, read.cause)
                    Timber.w(e, "article highlight: a source's pick is on a page that won't read — that source is left as it is")
                    if (refused == null) { refused = e; refusedDoing = if (at == lineDay) MarkWords.TAKE_THE_LINE_OFF else MarkWords.TAKE_THE_PICK_OFF }
                    continue
                }
            }
            // Its voice and video notes were filed into the card day's A/V list to make the pick
            // sound; when the pick goes with the highlight (or there is none), they go too —
            // tombstoned, so they never come back as standalone A/V notes (review 2026-10-16 #8).
            // A pick that stays keeps playing them, so they stay.
            val clips = if (lineId != null && (removeCard || card == null))
                ReadingEventEdit.find(service, root(), lineDay, lineId)?.second?.attachments.orEmpty()
                    .filter { it.kind != Attachment.Kind.PHOTO }.map { it.id }
            else emptyList()
            var lineDone = lineId == null
            try {
                if (lineId != null) {
                    ReadingEventEdit.remove(service, root(), lineDay, lineId, alsoOnDay = { cal ->
                        // The card goes in the same write only when this day file really holds it.
                        if (!cardDone && card != null && MarkCard.dropFrom(cal, card.id)) cardDone = true
                    })
                    lineDone = true
                }
                if (!cardDone && card != null) MarkCard.remove(service, root(), card.day, card.id)
            } catch (e: CalendarDayService.UnreadableDayException) {
                Timber.w(e, "article highlight: a source's page won't read — that source is left as it is")
                if (refused == null) { refused = e; refusedDoing = if (lineDone) MarkWords.TAKE_THE_PICK_OFF else MarkWords.TAKE_THE_LINE_OFF }
                continue
            }
            // After the line and the pick are off: a clip's day that won't read keeps its clip
            // (never written over — [PickAdds.dropClips] skips it); the removal itself stands.
            if (clips.isNotEmpty()) adds.dropClips(card?.day ?: src.mark?.cardDay ?: lineDay, clips)
            val m = src.mark
            if (m != null) ArticleNoteStore.delete(app, a.key, m.id)
            else {
                val id = ArticleMarkSet.adoptedId(src)
                ArticleNoteStore.putIfAbsent(app, a.key, BookNote(id = id, type = BookNote.ANNOTATION, text = src.text,
                    url = a.clean, day = lineDay.toString(), pickId = card?.id.orEmpty(), logId = lineId.orEmpty(),
                    logDay = if (lineId != null) lineDay.toString() else "",
                    pickDay = card?.day?.toString().orEmpty()))
                ArticleNoteStore.delete(app, a.key, id)
            }
            staleFaces(a.key, src.mark?.id ?: ArticleMarkSet.adoptedId(src), false)
        }
        val list = gatherQuietly(a)
        // iOS's verbs (MarkWords): "Couldn't take the line off — …" / "Couldn't take the pick off — …".
        val why = CalendarDayService.refusal(refused, refusedDoing)
        ui {
            if (article?.key == a.key) paint(list)
            runCatching { fragment.onExternalGramPlaced(CalendarDayPageNotes.GRAM_PICKS) }
            message(why ?: if (removeCard || cards.values.all { it == null }) "Highlight removed" else "Highlight removed · its pick stays")
        }
    }

    // ── The Annotations list (the article's Notes seat) ────────────────────────────────────────

    /**
     * Every highlight and note on this piece, in reading order, with its note's first words; a row
     * that isn't on this version of the text says so. Tap a row: land on its passage — or, not on
     * this version, its chooser with the view it IS in as the first row; hold any row: its chooser
     * (review 2026-10-12 R2 #7: a passage in no view could never get a note or come off). Old notes
     * about the whole piece from the Log are listed too (iOS lists them). Rows read the index — no
     * day file is opened to draw the list.
     */
    fun showAnnotations() {
        val a = article ?: return
        background {
            val list = gatherQuietly(a).ifEmpty { shown }
            val marks = ArticleNoteStore.listWithTombstones(app, a.key, a.clean)
            val notes = marks.filter { it.type == BookNote.NOTE && it.deletedAt == null }
            val hits = ArticlePickIndex.hits(app, a.key)
            val claimedLines = marks.map { it.logId }.filter { it.isNotBlank() }.toSet()
            val logNotes = hits.filter { it.lineId.isNotBlank() && it.text.isBlank() && it.noted && it.lineId !in claimedLines }
            class Row(val label: String, val tap: () -> Unit, val hold: (() -> Unit)?)
            val rows = mutableListOf<Row>()
            for (s in list) {
                val off = s.id in unplaced
                val words = s.line?.note.orEmpty()
                val head = "❝  " + preview(s.text, 90)
                // What rides on the line, as the pick's own glyphs (✍ 📷 🎥 🎤 — pin §6).
                val glyphs = s.line?.glyphs.orEmpty()
                val tail = listOfNotNull(
                    words.takeIf { it.isNotBlank() }?.let { "🗒 " + preview(it, 60) },
                    glyphs.ifBlank { null } ?: if (s.line?.ink == true) "✍" else null,
                    if (s.noted && words.isBlank() && glyphs.isBlank() && s.line?.ink != true) "🗒 note" else null,
                    when { s.mark != null -> null; s.pick != null -> "pick"; else -> "Log" },
                    if (off) "not on this version" else null).joinToString(" · ")
                val other = if (off) otherView(s) else null
                rows += Row(if (tail.isBlank()) head else "$head\n      $tail",
                    tap = {
                        if (off) showChooser(Target.Existing(s), other)
                        else web()?.evaluateJavascript("window.__ledgerMarks&&window.__ledgerMarks.jump(${JSONObject.quote(s.id)})", null)
                    },
                    hold = { showChooser(Target.Existing(s), other) })
            }
            for (n in notes) {
                val words = hits.firstOrNull { it.lineId == n.logId }?.note.orEmpty()
                rows += Row("🗒  " + (preview(words, 90).ifBlank { "A note on this piece" }), tap = {
                    background {
                        val current = lineOf(n)?.second?.note.orEmpty()
                        ui {
                            FeedNoteGram.typeNoteDialog(fragment, "", current) { w ->
                                if (w != current) background { addText(n, w) }
                            }
                        }
                    }
                }, hold = null)
            }
            for (l in logNotes) {
                rows += Row("🗒  " + (preview(l.note, 90).ifBlank { "A note on this piece" }) + "\n      Log", tap = {
                    background {
                        val current = ReadingEventEdit.find(service, root(), l.date, l.lineId)?.second?.note.orEmpty()
                        ui {
                            FeedNoteGram.typeNoteDialog(fragment, "", current) { w ->
                                if (w != current) background {
                                    if (updated(l.date, l.lineId) { it.copy(note = w.ifBlank { null }) })
                                        ui { message(if (w.isBlank()) "Note cleared" else "Note saved") }
                                }
                            }
                        }
                    }
                }, hold = null)
            }
            ui {
                val b = AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle("Annotations · ${rows.size}")
                    .setNegativeButton(android.R.string.cancel, null)
                if (rows.isEmpty()) b.setMessage("Nothing highlighted here yet — select a passage, then Highlight.")
                else b.setItems(rows.map { it.label }.toTypedArray()) { _, which -> rows[which].tap() }
                val d = b.create()
                fragment.showModal(d)
                d.listView?.setOnItemLongClickListener { _, _, pos, _ ->
                    val hold = rows.getOrNull(pos)?.hold ?: return@setOnItemLongClickListener false
                    d.dismiss(); hold(); true
                }
            }
        }
    }

    /** "Not on this version": the view it was made in, as a chooser row — null where the reader
     *  has one view (critique #7). */
    private fun otherView(s: ArticleMarkSet.Shown): Pair<String, () -> Unit>? {
        val a = article ?: return null
        val sw = switchView ?: return null
        val made = s.mark?.view?.ifBlank { null }
        val target = made?.takeIf { it != a.view }
            ?: if (a.view == BookNote.VIEW_LIVE) BookNote.VIEW_READER else BookNote.VIEW_LIVE
        val name = when (target) {
            BookNote.VIEW_READER -> "Reader view"
            BookNote.VIEW_FEED -> "the feed's text"
            else -> "the original page"
        }
        return "Open $name — it isn't in this text" to { pendingJump = s.id; sw(target) }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────────────────────

    private fun preview(text: String, max: Int): String =
        text.replace('\n', ' ').trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    /**
     * THE HIGHLIGHT AFTER-STEP (pin §2 D2 — it replaces the once-ever hint): 4 s after every
     * Highlight, `❝ Highlighted — its pick is in All Picks` with `＋ Add`, which opens the Add ladder
     * on this highlight. Ignoring it costs nothing. (FeedNoteGram.offerTrip's Snackbar, adopted.)
     */
    private fun afterStep(m: BookNote) {
        val view = fragment.view ?: return
        runCatching {
            com.google.android.material.snackbar.Snackbar.make(view, AddToPickWords.HIGHLIGHTED,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setDuration(AddToPickWords.AFTER_STEP_MS)
                .setAction(AddToPickWords.ADD_ACTION) { AddToPick.showLadder(fragment, tied(m)) }
                .apply { fragment.snackAnchor?.let { setAnchorView(it) } }
                .show()
        }
    }

    /** This highlight's pick as the ladder's sink — each add onto its line, its card re-faced. */
    private fun tied(m: BookNote): AddToPick.Tied {
        val a = article ?: PickAdds.articleOf(m)
        return AddToPick.Tied(adds, a, m) { msg -> ui { msg?.let { message(it) }; repaint() } }
    }

    /** ✏️ The Pick sheet, tied to [m] (D1): its shapes, its adds, its own pick re-faced. */
    private fun openStudio(m: BookNote) {
        val a = article ?: return
        com.toolsboox.plugin.calendar.ot.GramStudio.show(
            fragment, m.text, source = FeedNoteGram.footer(a.title, a.feedTitle, m.cardDay ?: LocalDate.now()),
            onShare = { cards -> fragment.shareCardBitmaps(cards) },
            tie = tied(m))
    }

    private fun trip(page: String) = FeedNoteGram.offerTrip(fragment, page,
        if (page == CalendarDayPageNotes.GRAM_PICKS) "All Picks" else "today's notes")

    private fun message(text: String) {
        runCatching { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun ui(block: () -> Unit) {
        val act = fragment.activity ?: return
        act.runOnUiThread { if (fragment.isAdded) runCatching { block() }.onFailure { Timber.w(it, "article highlights") } }
    }

    private fun background(block: () -> Unit) {
        Thread { runCatching { block() }.onFailure { Timber.w(it, "article highlights (background)") } }
            .apply { isDaemon = true }.start()
    }

    private var warmed = false

    companion object {
        const val SCHEME = "ledger-mark://"
        /** Handwriting rides the Log line as a PHOTO attachment with this name — the card face puts
         *  it first, and the tapped highlight's "open" shows it. */
        const val INK_PREFIX = "ink-"

        private const val NOT_HERE_YET = "This highlight's day hasn't reached this device yet — try again once it syncs."
        /** How long the page build waits for the marks gathered at open — a beat, then the page
         *  goes up unseeded and is painted after load. */
        private const val HEAD_WAIT_MS = 150L
        /** How long a tap on a passage still saving waits for its save. */
        private const val SAVE_WAIT_S = 10L

        /** One gatherer for every reader: the head's marks, off the main thread. */
        private val GATHER: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "article-marks").apply { isDaemon = true } }

        @Volatile private var js: String? = null

        fun script(context: android.content.Context): String =
            js ?: runCatching { context.assets.open("feeds/article-marks.js").bufferedReader().use { it.readText() } }
                .getOrDefault("").also { js = it }
    }
}
