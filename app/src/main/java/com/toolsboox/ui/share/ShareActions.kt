package com.toolsboox.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.GramDestinations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE SHARE ACTIONS, ONE COPY (2026-10-10). Everything a share can DO — ⏳ Later, a lane, a link
 * card, a quote card, a pick, a fresh note page, a task card, the shelf — lived as private
 * functions on MainActivity, so only a fully started Ledgable could act on a share. The send-from-
 * any-app card ([ShareCatchActivity], Michael: "highlight … in EinkBro … one tap saves it, and
 * you're back in EinkBro") needs the same verbs without the day page behind it. So the store
 * halves live here; MainActivity keeps thin wrappers (its in-app callers and its full chooser are
 * unchanged) and the card calls these directly.
 *
 * Every suspend function here does its disk/network work on IO and touches no view, so a caller
 * may run it in [appScope] after its own window is gone.
 */
object ShareActions {

    // --- The card → Ledgable handoff (rows that "open Ledgable") -------------------------------

    /**
     * The card's handoff rides a LAUNCHER-SHAPED intent (MAIN + LAUNCHER, the payload in extras),
     * never its own action (review, 2026-10-10): when no Ledgable task exists the card's intent
     * becomes the task's root, and a root that isn't the launcher's makes the next launcher tap
     * stack a second MainActivity, and a restore after process death replay the old share.
     * [EXTRA_CARD_OPEN] names the destination; [EXTRA_CARD_SEND] carries ⋯ More's SEND whole.
     */
    const val EXTRA_CARD_OPEN = "ledger.cardOpen"
    const val EXTRA_CARD_SEND = "ledger.cardSend"
    /** Proof a handoff came from THIS process's card: MainActivity is exported and answers the
     *  launcher's intent, so any app could otherwise forge a CARD_OPEN — an image "on today" read
     *  with Ledgable's own permissions and synced to WebDAV (review, 2026-10-10). */
    const val EXTRA_CARD_TOKEN = "ledger.cardToken"
    val cardToken: String by lazy { java.util.UUID.randomUUID().toString() }
    fun isCardToken(token: String?): Boolean = token != null && token == cardToken
    /** Set on the SEND that ⋯ More re-sends, so MainActivity shows today's full chooser instead
     *  of forwarding the share back to the card (critique C8). */
    const val EXTRA_FROM_CARD = "ledger.fromCard"
    /** Set when MainActivity forwards a share to the card (C8): the card's own referrer is then
     *  Ledgable, so only the carried EXTRA_REFERRER names the real sender. */
    const val EXTRA_FORWARDED = "ledger.forwarded"
    /** The sending app's name ("EinkBro"), carried so the full chooser's cards still say "via". */
    const val EXTRA_VIA = "ledger.via"
    const val EXTRA_DATE = "ledger.date"
    const val EXTRA_KEY = "ledger.key"
    const val EXTRA_TEXT = "ledger.text"
    const val EXTRA_URL = "ledger.url"
    const val EXTRA_IMAGE = "ledger.image"
    const val EXTRA_ASK_PASSAGE = "ledger.askPassage"
    const val EXTRA_ASK_CONTEXT = "ledger.askContext"

    const val OPEN_NOTE = "note"
    const val OPEN_DAY = "day"
    const val OPEN_TEXT_ON_DAY = "textOnDay"
    const val OPEN_IMAGE_ON_DAY = "imageOnDay"
    const val OPEN_ASK = "ask"

    /**
     * Card saves run HERE, not in the card's lifecycleScope (critique C7): the card closes on the
     * tap — "you're still reading" — and a lifecycle-bound save is cancelled at onDestroy, before
     * the pick is ever placed. Process-lifetime, main-dispatched; the work hops to IO itself.
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * The day store for code outside Hilt — [CalendarDayService.standalone], the one factory: the
     * ledger's Moshi and the app context (the media-ref save pass, the widget poke), exactly what
     * the injected copy carries. One per process.
     */
    fun service(context: Context): CalendarDayService = CalendarDayService.standalone(context)

    /**
     * MainActivity's nudge while RESUMED, cleared on pause — [ShareNoteQueue.onEnqueued]'s twin for
     * the day file. "The day page reloads on resume" only holds when it was stopped: in Boox split
     * screen or a floating window Ledgable stays resumed beside EinkBro, holding a day from before
     * the card, and its next pen-up save would write the pick straight back out (review,
     * 2026-10-10). The card calls [dayWritten] after each save that landed; the page re-reads.
     */
    @Volatile
    var onDayWritten: ((String) -> Unit)? = null

    /** A card write to today's day file landed on page [pageKey] — tell a resumed day page. */
    fun dayWritten(pageKey: String) {
        runCatching { onDayWritten?.invoke(pageKey) }
    }

    /** Only our own cached share copy may ride a handoff as "an image for today" — never a path
     *  some other app named (review, 2026-10-10). */
    fun isOwnShareImage(context: Context, uri: String?): Boolean {
        val u = runCatching { Uri.parse(uri ?: return false) }.getOrNull() ?: return false
        return u.scheme == "content" && u.authority == "${context.packageName}.fileprovider" &&
            u.path.orEmpty().startsWith("/share-in/")
    }

    fun documentsRoot(context: Context): File = com.toolsboox.ot.LedgerPaths.documentsRoot(context)

    fun toast(context: Context, message: String) {
        android.widget.Toast.makeText(context.applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // --- Provenance ------------------------------------------------------------------------------

    /** "Oct 10, 2026 · Title · via EinkBro" — [com.toolsboox.plugin.feeds.ot.FeedNoteGram]'s
     *  date·source footer, with the sending app standing where the feed stands. */
    fun footer(title: String?, via: String?): String {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        return listOfNotNull(date, title?.trim()?.takeIf { it.isNotEmpty() }, via?.let { "via $it" })
            .joinToString(" · ")
    }

    /** The pick's source label: "Title · via EinkBro", or [fallback] when nothing is known. */
    fun sourceLabel(title: String?, via: String?, fallback: String): String =
        listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, via?.let { "via $it" })
            .joinToString(" · ").ifBlank { fallback }

    /**
     * The quote card's FACE. The old share card cut the passage at 600 characters and said nothing
     * (audit: "It cuts the text at 600 characters without saying so"). The face may still shorten —
     * a card is a picture, and past ~2,000 characters the renderer's 30px floor runs out of room —
     * but it says so with a visible "…", and [cardText] always carries every word, so search,
     * Synthesize and the iPad read the whole passage.
     */
    fun cardFace(passage: String, max: Int = 2000): String {
        val t = passage.trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val atWord = cut.lastIndexOf(' ').takeIf { it > max * 3 / 4 } ?: max
        return cut.take(atWord).trimEnd() + " …"
    }

    /** The passage as a quote card — the in-app ❝ Save highlight face (date·source footer). */
    suspend fun textCard(passage: String, footer: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            com.toolsboox.plugin.calendar.ot.QuoteCardRenderer.render(cardFace(passage), footer, null, 1080, 0)
        }.onFailure { Timber.w(it, "share: quote card failed") }.getOrNull()
    }

    // --- Links -----------------------------------------------------------------------------------

    /**
     * ⏳ Later — the local Instapaper, and it NEVER ASKS A QUESTION: the lane comes from the URL by
     * the one table every filing path reads, and the write is [IntakePageStore.fileLink] (og: face,
     * offline copy, personal feed, cross-device merge). Returns the lane's name for the toast.
     */
    suspend fun saveToLater(context: Context, url: String, title: String?, excerpt: String?): String {
        val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
        withContext(Dispatchers.IO) {
            runCatching {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                    context.applicationContext, LocalDate.now(), kind, url, title,
                    excerpt = excerpt?.takeIf { it.isNotBlank() })
            }.onFailure { Timber.w(it, "Later save failed") }
        }
        return com.toolsboox.plugin.feeds.nw.LaterFeed.LANES.firstOrNull { it.first == kind }?.second ?: "Later List"
    }

    /** "File to a lane…" — the explicit-lane room (read / watch / listen / educate). */
    fun fileToLane(context: Context, kind: String, url: String, title: String?) {
        com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
            .fileLink(context.applicationContext, LocalDate.now(), kind, url, title)
    }

    /** The shared link's card face — the pick doors' renderer, kind inferred from the host. */
    suspend fun renderLinkCard(url: String, title: String, excerpt: String): Bitmap? = withContext(Dispatchers.IO) {
        val kind = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser.inferKind(url)
        runCatching {
            com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(url, title, kind, excerpt = excerpt)
        }.getOrNull()
    }

    /**
     * A link that IS a picture (EinkBro's hold-an-image → "Share link" sends the image's address):
     * the path ends in an image extension, or the query asks for one (`?format=jpg`). Deliberately
     * no network probe — asking the server would hold every page share's card for a round trip, or
     * shift its rows under the pen when the answer came back.
     */
    fun isImageLink(url: String?): Boolean {
        val u = runCatching { Uri.parse(url ?: return false) }.getOrNull() ?: return false
        val path = u.path.orEmpty().lowercase()
        if (Regex("""\.(jpe?g|png|webp|gif|avif|bmp)$""").containsMatchIn(path)) return true
        val format = runCatching { u.getQueryParameter("format") }.getOrNull()?.lowercase().orEmpty()
        return format in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }

    // --- Placing ---------------------------------------------------------------------------------

    /** All Picks, resolved off the main thread — a capture always lands in the inbox (the inbox
     *  rule: memory orders the chooser, it never routes a capture). */
    suspend fun allPicks(context: Context): GramDestinations.Destination =
        withContext(Dispatchers.IO) { GramDestinations.inbox(context.applicationContext) }

    /** Place [bitmap] as a gram on [destination] (null = today's day page itself). True on success. */
    suspend fun placeGram(
        context: Context, bitmap: Bitmap, destination: GramDestinations.Destination?,
        sourceLink: String, sourceLabel: String, cardText: String = "", sourceFeed: String = "",
        service: CalendarDayService = service(context),
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                service, documentsRoot(context), bitmap,
                // A note target carries its own day (the 08-28 ruling's cross-day reach);
                // everything else stays today's placement.
                destination?.date ?: LocalDate.now(), destination?.key ?: "default",
                sourceLink = sourceLink, sourceLabel = sourceLabel,
                cardText = cardText, sourceFeed = sourceFeed,
                intakeKind = destination?.kind ?: ""
            )
        }.onFailure { Timber.w(it, "share: place failed") }.isSuccess
    }

    /**
     * A FRESH grid note page under today with [bitmap] placed on it (Michael, 09-13: "save it on to
     * a new note page") — the store half; opening the page is the caller's. Null when no page.
     */
    suspend fun placeOnNewNotePage(
        context: Context, bitmap: Bitmap, sourceLink: String, sourceLabel: String,
        cardText: String = "", sourceFeed: String = "", service: CalendarDayService = service(context),
        noteName: String? = null,
    ): com.toolsboox.plugin.calendar.ot.LedgerDocuments.NewNote? = withContext(Dispatchers.IO) {
        val docs = com.toolsboox.plugin.calendar.ot.LedgerDocuments
        val app = context.applicationContext
        val n = docs.startNote(app, docs.GRID, (noteName ?: sourceLabel).take(48).ifBlank { "Shared" }, LocalDate.now())
            ?: return@withContext null
        com.toolsboox.plugin.calendar.ot.DayLocks.withDay(n.date) {
            runCatching {
                com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                    service, documentsRoot(app), bitmap, n.date, n.key,
                    sourceLink = sourceLink, sourceLabel = sourceLabel,
                    cardText = cardText, sourceFeed = sourceFeed)
            }.onFailure { Timber.w(it, "share → new note page: place failed") }
        }
        n
    }

    /**
     * [bitmap] as a task card's ink face — unfiled, today (Michael, 09-13: "save it as a task").
     * The day page's "Pin to board" door minus the board question: same LedgerItem shape, same
     * day-lock discipline, same push to the task sync. True when it landed in the day file.
     */
    suspend fun pinBitmapAsTask(
        context: Context, bitmap: Bitmap, label: String, service: CalendarDayService = service(context),
    ): Boolean = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val today = LocalDate.now()
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val item = com.toolsboox.plugin.calendar.da.v2.LedgerItem(
            id = "li-" + java.util.UUID.randomUUID().toString().lowercase(),
            kind = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Kind.TASK,
            text = label.ifBlank { "Shared" },
            date = java.util.Date(),
            crop = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP),
            display = com.toolsboox.plugin.calendar.da.v2.LedgerItem.Display.INK,
            source = "gram", stage = "todo", board = ""
        )
        val root = documentsRoot(ctx)
        var saved = false
        com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
            // A today-file that won't read is never saved over (saved stays false → "couldn't").
            val day = service.loadForWrite(
                root, today, com.toolsboox.plugin.calendar.ot.PagePrefs.settingHour(ctx), Locale.getDefault()
            ) ?: return@withDay
            day.ledgerItems.add(item)
            service.save(root, today, day)
            saved = true
        }
        if (saved) runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, item) }
        saved
    }

    /**
     * Land a shared PDF on the bookshelf, wherever the shelf is (BookshelfSource follows a declared
     * folder). Streamed, temp-then-rename inside writeInto — a half-copied PDF must never sit on the
     * shelf looking like a book. Named by the sender's display name, extension pinned. Pushed on
     * add, like the shelf's own picker. Returns the shelf name, or null.
     */
    suspend fun importPdf(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = runCatching {
            var display: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) display = c.getString(idx)
            }
            val safe = (display?.ifBlank { null } ?: "Shared.pdf")
                .replace(Regex("""[/\\:*?"<>|]"""), "-")
                .let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" }
            val ok = com.toolsboox.plugin.reader.ui.BookshelfSource.writeInto(context, safe) { out ->
                context.contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
            }
            if (ok) safe else null
        }.onFailure { Timber.w(it, "shared PDF import failed") }.getOrNull()
        name?.let { com.toolsboox.plugin.reader.ui.LibraryHub.pushAdded(context.applicationContext, "", it) }
        name
    }

    /**
     * Decode a shared image DOWNSAMPLED, never full-size. A 12MP camera share decoded whole is a
     * ~48MB bitmap — enough to OOM a Palma — and whatever lands here gets base64'd into the day
     * JSON by the gram placement path, so oversized pixels become permanent oversized JSON. Bounds
     * first, then a power-of-two sample that brings the longest edge inside 2048px, then a JPEG ~85
     * round-trip: the lossy pass smooths sensor noise, which lets the PNG re-encode compress.
     */
    fun decodeImage(context: Context, uri: Uri): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val maxEdge = 2048
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        val baos = java.io.ByteArrayOutputStream()
        if (!decoded.compress(Bitmap.CompressFormat.JPEG, 85, baos)) return@runCatching decoded
        val settled = android.graphics.BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        if (settled != null && settled !== decoded) decoded.recycle()
        settled ?: decoded
    }.getOrNull()

    /**
     * Copy a shared stream into our cache while the card still HOLDS the sender's read grant — the
     * grant belongs to the card and dies with it, so a row that runs after the card closes, a row
     * that opens Ledgable, and ⋯ More all read this copy instead (critique P4). Blocking; call on IO.
     */
    fun copyToCache(context: Context, uri: Uri): File? = runCatching {
        val dir = File(context.cacheDir, "share-in").apply { mkdirs() }
        val type = context.contentResolver.getType(uri).orEmpty()
        val ext = when {
            type.endsWith("png") -> "png"; type.endsWith("webp") -> "webp"; type.endsWith("gif") -> "gif"
            type == "application/pdf" -> "pdf"; else -> "jpg"
        }
        val out = File(dir, "in-${System.currentTimeMillis()}-${(0..9999).random()}.$ext")
        context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.onFailure { Timber.w(it, "share: copy to cache failed") }.getOrNull()

    /** Our own FileProvider address for a [copyToCache] file — readable by MainActivity, no grant. */
    fun cacheUri(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // --- Text notes ------------------------------------------------------------------------------

    /**
     * 📝 Text note — the passage as a titled note on today's Text Notes, with where it came from.
     * The Text Notes screen reads its day ONCE and writes the whole list back on every autosave, so
     * a note written from the card is QUEUED for MainActivity to announce when it next resumes
     * ([ShareNoteQueue], the TaskDoneQueue pattern) — otherwise the screen's next keystroke would
     * un-write it.
     */
    fun addTextNote(context: Context, passage: String, title: String?, via: String?) {
        val app = context.applicationContext
        val today = LocalDate.now()
        val first = passage.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val noteTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (first.length <= 60) first else first.take(59).trimEnd() + "…"
        val from = sourceLabel(title, via, "")
        val body = if (from.isBlank()) passage.trim() else passage.trim() + "\n\n— " + from
        com.toolsboox.plugin.textnotes.TextNotesStore.addNote(app, today, noteTitle, body)
        ShareNoteQueue.enqueue(app, today)
    }

    // --- After a card write ----------------------------------------------------------------------

    /**
     * A card write in a process that may have no MainActivity: the screen-off receiver that pushes
     * the day mirror lives on MainActivity, so without this a pick saved from EinkBro would not
     * reach the iPad until Ledgable next opened (critique P3). Both barrels, as screen-off fires
     * them: the cheap debounced day mirror, and the WorkManager one-shot that survives the process.
     */
    fun afterCardWrite(context: Context) {
        val app = context.applicationContext
        runCatching { com.toolsboox.plugin.calendar.nw.QuickDayMirror.fire(app, "share-card") }
        runCatching { com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker.syncNow(app) }
    }

    // --- Who sent it -----------------------------------------------------------------------------

    /** Names Android's package list can't tell us without visibility (see `<queries>` in the
     *  manifest) — and stable names where an app's own label is noisy. */
    private val KNOWN_APPS = mapOf(
        "info.plateaukao.einkbro" to "EinkBro",
        "com.android.chrome" to "Chrome",
        "com.onyx.kreader" to "NeoReader",
        "org.koreader.launcher" to "KOReader",
        "com.google.android.gm" to "Gmail",
        "org.mozilla.firefox" to "Firefox",
        "com.amazon.kindle" to "Kindle",
    )

    /** Packages that relay a share without being its source — never "via the chooser". */
    private val RELAYS = setOf(
        "android", "com.android.intentresolver", "com.google.android.intentresolver",
        "com.android.systemui", "com.android.shell",
    )

    /**
     * "EinkBro" for the app that sent this, or null when unknown or ourselves. Never
     * QUERY_ALL_PACKAGES (Play policy; Ledgable is in a closed test): the known table first, then
     * the package's own label when it's visible, then its last name segment.
     */
    fun viaName(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank() || pkg == context.packageName || pkg in RELAYS) return null
        KNOWN_APPS[pkg]?.let { return it }
        val pm = context.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it != pkg }?.let { return it }
        return pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}

/**
 * The queue between a Text Note written from the card and the Text Notes screen that would
 * otherwise overwrite it — [com.toolsboox.plugin.calendar.ot.TaskDoneQueue]'s queue-plus-nudge,
 * one store over. The card appends the day; MainActivity drains it on resume (and through
 * [onEnqueued] while it's up) and tells the resumed screen to RE-READ, never save.
 */
object ShareNoteQueue {
    private const val PREFS = "share_note_queue"
    private const val KEY = "dates"
    private val lock = Any()

    /** MainActivity's nudge while resumed; cleared on pause. Called off the main thread. */
    @Volatile
    var onEnqueued: (() -> Unit)? = null

    fun enqueue(context: Context, date: LocalDate) {
        synchronized(lock) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val set = p.getStringSet(KEY, emptySet()).orEmpty().toMutableSet().apply { add(date.toString()) }
            // commit, not apply: the card's process may be gone a breath after this returns.
            p.edit().putStringSet(KEY, set).commit()
        }
        runCatching { onEnqueued?.invoke() }
    }

    /** The queued days, removed from the queue. */
    fun drain(context: Context): List<LocalDate> = synchronized(lock) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet(KEY, emptySet()).orEmpty()
        if (set.isEmpty()) return@synchronized emptyList()
        p.edit().remove(KEY).commit()
        set.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
