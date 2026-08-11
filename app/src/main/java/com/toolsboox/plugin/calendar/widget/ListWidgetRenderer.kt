package com.toolsboox.plugin.calendar.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailAccountStore
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The home-screen LIST widgets — Mail, Feed, and the Daily Pile. Where [WidgetRenderer] crops
 * regions out of the real day page, these draw their own crisp e-ink list: a grey section-bar
 * header (same fill and white bold text as the day page's column headers), then alternating-shaded
 * rows, ellipsized to the row. Everything is greyscale and legible at widget dpi — no colour is
 * load-bearing, matching the day-page renderer's conventions.
 *
 * All three source their data from disk / SharedPreferences that the app already wrote; nothing
 * here touches the network. A widget process is short-lived and has no logged-in session, so a
 * fetch would only ever fail — the rule is read what's cached, never go and get it.
 */
object ListWidgetRenderer {

    // Draw wide, scale down. 1000 across keeps a comfortable line length; the shared
    // [WidgetBitmapBudget] keeps the pushed bitmap inside the RemoteViews ceiling (the fixed
    // 768px long-side cap it replaces is what made every list widget soft — see that object's
    // comment for the arithmetic).
    private const val CW = 1000f
    private const val HEADER_H = 88f
    private const val ROW_H = 84f
    private const val PAD = 26f
    private const val MAX_ROWS = 14
    // The task widget shows fewer, deliberately: ~6 undone is a glance; a scroll's worth is a page.
    private const val MAX_TASK_ROWS = 6

    /** One row of a list widget: an optional marker (emoji kind-glyph or ★), a bold primary line,
     *  an optional small secondary line, and an optional tiny thumbnail (grams). */
    private data class Row(
        val primary: String,
        val secondary: String? = null,
        val marker: String? = null,
        val thumb: Bitmap? = null,
        /** Grey, non-bold — for meta rows like "+N more" that aren't content. */
        val muted: Boolean = false
    )

    // ---- Public entry points (one per provider) ----

    /** Action mail: STARRED first, then the rest of the inbox, newest within each band. Reads the
     *  same [InboxStore.messages] the inbox screen does — in a widget process nothing has fetched,
     *  so this resolves to the keep piles plus the download cache, union the seeded samples on a
     *  fresh install. No IMAP, no fetch. */
    fun renderMail(context: Context, widthDp: Int, heightDp: Int): Bitmap {
        // The widget process has its own memory, so the cache has to be warmed here too — without
        // this the widget would only ever show the keep piles while the app itself shows a year of
        // mail. A widget draw already runs on a background thread (goAsync + Thread in
        // CalendarWidgetProvider), which is the one precondition warm has.
        runCatching { InboxStore.warm(context) }
        val messages = runCatching { InboxStore.messages(context) }
            .getOrDefault(emptyList())
            // Mail you SENT is keep-forever, so it comes back from messages() alongside the rest —
            // and on a widget headed "Mail" it read as arriving mail from yourself. A glance widget
            // answers "what wants me", and a letter you already wrote does not.
            .filter { !InboxStore.isSent(it.id) }
        val (starred, rest) = messages.partition { runCatching { InboxStore.isStarred(context, it.id) }.getOrDefault(false) }
        val unread = messages.count { !InboxStore.isRead(context, it.id) }
        val ordered = starred + rest
        val rows = ordered.take(MAX_ROWS).map { m ->
            val sender = m.fromName.ifBlank { m.fromEmail.substringBefore('@') }.ifBlank { "Unknown" }
            Row(
                primary = m.subject.ifBlank { "(no subject)" },
                secondary = "$sender · ${relativeAge(m.date)}",
                marker = when {
                    InboxStore.isStarred(context, m.id) -> "★"
                    !InboxStore.isRead(context, m.id) -> "●"
                    else -> "·"
                }
            )
        }
        // Whose inbox this is: the account name joins the header when there's exactly one account
        // (with several, a per-account label would misdescribe a unified list).
        val accounts = runCatching { MailAccountStore.all(context) }.getOrDefault(emptyList())
        val title = if (accounts.size == 1) "✉ Mail · ${accounts[0].display}" else "✉ Mail"
        val countLabel = buildList {
            if (unread > 0) add("$unread unread")
            if (starred.isNotEmpty()) add("${starred.size} ★")
        }.joinToString(" · ").ifBlank { null }
        return draw(context, title, countLabel, rows, "Inbox clear", widthDp, heightDp)
    }

    /** RSS headlines the app already surfaced. Reads the freshest cached entry list off disk
     *  (files/feed-cache/list-*.json, written by the Feed screen); nothing is fetched. */
    fun renderFeed(context: Context, widthDp: Int, heightDp: Int): Bitmap {
        val entries = loadCachedFeed(context)
        val unread = entries.count { !it.read }
        // Unread first (the point of the glance), newest within; fall back to newest overall.
        val ordered = entries.sortedWith(compareBy({ it.read }, { -parseEpoch(it.publishedAt) }))
        val rows = ordered.take(MAX_ROWS).map { e ->
            val source = e.feedTitle.ifBlank { e.categoryLabel ?: "Feed" }
            Row(
                primary = e.title.ifBlank { "(untitled)" },
                secondary = "$source · ${relativeAgeIso(e.publishedAt)}",
                marker = if (e.read) "·" else "●"
            )
        }
        return draw(context, "⧉ Feed", unread.takeIf { it > 0 }?.let { "$it new" }, rows,
            "Feed all caught up", widthDp, heightDp)
    }

    /** Today's open tasks as checkbox rows — the day's ledgerItems read straight off the day JSON
     *  (text + done only), first six undone, a muted "+N more" when they overflow, and the next
     *  timed calendar event on top when there is one still ahead. Display only: a widget can't be
     *  checked, it can only be glanced at; a tap opens the day page where the ink lives. */
    fun renderTasks(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap {
        val day = WidgetRenderer.loadCalendarDay(context, date)
        val dead = (day?.deletedItemIds.orEmpty() + day?.deletedElementIds.orEmpty()).toSet()
        val tasks = day?.ledgerItems.orEmpty()
            .filter { it.kind == LedgerItem.Kind.TASK && it.text.isNotBlank() && it.id !in dead }
        val undone = tasks.filter { !it.done && it.stage != "done" }

        val rows = mutableListOf<Row>()

        // The next timed event, from the same system-calendar read the day-page renderer does
        // (already permission-guarded there). One row, on top — it's the time-critical line.
        val now = System.currentTimeMillis()
        runCatching { WidgetRenderer.loadCalendarEvents(context, date) }.getOrDefault(emptyList())
            .filter { !it.allDay && it.endDate > now }
            .minByOrNull { it.startDate }
            ?.let { ev ->
                val time = android.text.format.DateFormat.getTimeFormat(context)
                    .format(java.util.Date(ev.startDate))
                rows.add(Row(primary = ev.title.ifBlank { "(untitled)" }, secondary = time, marker = "📆"))
            }

        undone.take(MAX_TASK_ROWS).forEach { rows.add(Row(primary = it.text, marker = "☐")) }
        if (undone.size > MAX_TASK_ROWS) {
            rows.add(Row(primary = "+${undone.size - MAX_TASK_ROWS} more", muted = true))
        }

        // Still open from the days before — carry-over no longer copies these into today's
        // file (2026-08-10), so the widget asks the question itself: undone tasks off the
        // lookback window's day files, slim-decoded, word-deduped against today's. Muted, with
        // the day they were written, the same reading as the page's ghost rows.
        if (undone.size < MAX_TASK_ROWS) {
            val said = HashSet<String>()
            tasks.forEach { said.add(com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe.key(it.text)) }
            // The service's Moshi is normally field-injected; a widget process wires it by hand
            // (the same adapters WidgetRenderer's own loader builds).
            val svc = com.toolsboox.plugin.calendar.fi.CalendarDayService().apply {
                moshi = com.squareup.moshi.Moshi.Builder()
                    .add(com.toolsboox.ot.LocaleJsonAdapter())
                    .add(com.toolsboox.ot.DateJsonAdapter())
                    .add(com.toolsboox.ot.UUIDJsonAdapter())
                    .build()
            }
            val root = com.toolsboox.ot.LedgerPaths.documentsRoot(context)
            val sinceFmt = DateTimeFormatter.ofPattern("MMM d")
            var d = date.minusDays(1)
            val floor = date.minusDays(30)
            var slots = MAX_TASK_ROWS - undone.size
            while (!d.isBefore(floor) && slots > 0) {
                val y = "%04d".format(d.year); val m = "%02d".format(d.monthValue); val dd = "%02d".format(d.dayOfMonth)
                val f = File(File(root, "calendar/$y/$m"), "day-$y-$m-$dd-v2.json")
                if (f.exists()) {
                    for (it in runCatching { svc.loadLedgerItems(f) }.getOrNull().orEmpty()) {
                        if (it.kind != LedgerItem.Kind.TASK || it.done || it.stage == "done" || it.text.isBlank()) continue
                        if (!said.add(com.toolsboox.plugin.calendar.ot.LedgerTaskDedupe.key(it.text))) continue
                        rows.add(Row(primary = it.text, secondary = "since ${d.format(sinceFmt)}", marker = "·", muted = true))
                        if (--slots <= 0) break
                    }
                }
                d = d.minusDays(1)
            }
        }

        val dateLabel = date.format(DateTimeFormatter.ofPattern("EEE · MMM d"))
        val doneCount = tasks.size - undone.size
        val empty = if (doneCount > 0) "All $doneCount done" else "No open tasks"
        return draw(context, "⚡ Tasks", dateLabel, rows, empty, widthDp, heightDp)
    }

    /** All Stars at a glance: today's starred count per band — READ / WATCH / LISTEN / BOOKS /
     *  EMAIL — as one row of five cells. Counts, not grams: decoding a band's worth of base64
     *  cards in the widget process is exactly the weight this surface exists to avoid, and the
     *  count is the glanceable truth. Same filter as the Stars page's own gramsFor (page ==
     *  "intake", kind key per band, non-decorative; EMAIL keeps the legacy "educate" key). */
    fun renderStars(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap {
        val day = WidgetRenderer.loadCalendarDay(context, date)
        val bands = listOf("read" to "READ", "watch" to "WATCH", "listen" to "LISTEN",
            "books" to "BOOKS", "educate" to "EMAIL")
        val grams = day?.imageElements.orEmpty()
            // A star's face may be inline (`data`) or in the media store (`dataRef`) — either
            // way it counts; the widget never decodes it.
            .filter { it.page == "intake" && !it.decorative && (it.data.isNotBlank() || it.dataRef.isNotBlank()) }
        val counts = bands.map { (key, _) -> grams.count { it.intakeKind == key } }
        val total = counts.sum()

        val bandH = 250f
        val canvasH = HEADER_H + bandH
        val bitmap = Bitmap.createBitmap(CW.toInt(), canvasH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, CW, canvasH, fillWhite)

        canvas.drawRect(0f, 0f, CW, HEADER_H, fillGrey80)
        canvas.drawText("★ All Stars", PAD, HEADER_H - 28f, textWhite)
        if (total > 0) canvas.drawText("$total today", CW - PAD, HEADER_H - 28f, textWhiteRight)
        canvas.drawLine(0f, HEADER_H, CW, HEADER_H, lineBlack)

        val cellW = CW / bands.size
        for (i in bands.indices) {
            val left = i * cellW
            if (i % 2 == 1) canvas.drawRect(left, HEADER_H, left + cellW, canvasH, fillGrey20)
            val cx = left + cellW / 2f
            canvas.drawText(counts[i].toString(), cx, HEADER_H + 155f,
                if (counts[i] > 0) textStarCount else textStarCountZero)
            canvas.drawText(bands[i].second, cx, canvasH - 26f, textStarBand)
            if (i > 0) canvas.drawLine(left, HEADER_H, left, canvasH, lineGrey50)
        }
        canvas.drawLine(0f, canvasH - 1f, CW, canvasH - 1f, lineBlack)

        return scaleToWidget(context, bitmap, widthDp, heightDp)
    }

    /** Today's gathered pile — the same read-side gather the Daily Pile screen does (tasks/events
     *  from the day's ledgerItems, birthdays from the rolodex, grams from imageElements, which now
     *  include starred-mail grams). Honours the same pick-cooldown / dismissal / hand-set order the
     *  screen stores in SharedPreferences, re-read here rather than reached through its private
     *  stores. Grams show their tiny thumbnail. */
    fun renderPile(context: Context, date: LocalDate, widthDp: Int, heightDp: Int): Bitmap {
        val pieces = gatherPile(context, date)
        val rows = pieces.take(MAX_ROWS).map { p ->
            Row(primary = p.title, marker = p.glyph, thumb = p.thumb)
        }
        return draw(context, "Today's Pile", pieces.size.takeIf { it > 0 }?.toString(), rows,
            "Nothing gathered yet", widthDp, heightDp)
    }

    // ---- Daily Pile gather (read-side reproduction of DailyPileFragment.gather) ----

    private data class PilePiece(val id: String, val glyph: String, val title: String, val thumb: Bitmap?)

    private fun gatherPile(context: Context, date: LocalDate): List<PilePiece> {
        val out = mutableListOf<PilePiece>()
        val day = WidgetRenderer.loadCalendarDay(context, date)

        val dead = (day?.deletedItemIds.orEmpty() + day?.deletedElementIds.orEmpty()).toSet()
        for (item in day?.ledgerItems.orEmpty()) {
            if (item.text.isBlank() || item.id in dead) continue
            val glyph = if (item.kind == LedgerItem.Kind.EVENT) "📆" else "🃏"
            out.add(PilePiece("task-${item.id}", glyph, item.text, null))
        }
        for (c in runCatching { ContactStore.list(context) }.getOrNull().orEmpty()) {
            if (birthdayMatches(c.birthday, date.monthValue, date.dayOfMonth)) {
                out.add(PilePiece("bday-${date.monthValue}-${date.dayOfMonth}-${c.id}",
                    "🎂", "${c.name} · ${c.birthday}", null))
            }
        }
        for (img in day?.imageElements.orEmpty()) {
            if (img.data.isBlank() && img.dataRef.isBlank()) continue
            val label = img.sourceLabel.ifBlank { img.cardText.ifBlank { "Gram" } }
            out.add(PilePiece("gram-${img.elementId}", "🖼", label.take(48), decodeThumb(context, img)))
        }

        // The screen's own filters, re-read from the same SharedPreferences (formats mirror
        // DailyPileFragment's private PilePickStore / PileDismissStore / PileOrderStore).
        val alive = out.filter { !isCooling(context, it.id) && !isDismissed(context, date, it.id) }
        return arrange(context, date, alive)
    }

    private fun isCooling(context: Context, id: String): Boolean {
        val cooldown = 3L * 86_400_000L
        val t = context.getSharedPreferences("daily_pile_picks", 0).getLong(id, 0L)
        return t > 0L && System.currentTimeMillis() - t < cooldown
    }

    private fun isDismissed(context: Context, date: LocalDate, id: String): Boolean =
        context.getSharedPreferences("daily_pile_dismissals", 0).getString(date.toString(), null)
            ?.split("\n")?.contains(id) == true

    private fun arrange(context: Context, date: LocalDate, pieces: List<PilePiece>): List<PilePiece> {
        val saved = context.getSharedPreferences("daily_pile_order", 0).getString(date.toString(), null)
            ?: return pieces
        val rank = saved.split("\n").withIndex().associate { (i, id) -> id to i }
        val (known, fresh) = pieces.partition { it.id in rank }
        return known.sortedBy { rank[it.id]!! } + fresh
    }

    /** Freeform birthday string matched to a month/day — copied from DailyPileFragment so the
     *  widget's pile matches the screen's exactly. */
    private fun birthdayMatches(bday: String, month: Int, day: Int): Boolean {
        val s = bday.lowercase().trim()
        if (s.isEmpty()) return false
        val months = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
        val nums = s.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
        val mk = months.entries.firstOrNull { s.contains(it.key) }?.value
        if (mk != null) return mk == month && nums.contains(day)
        if (nums.size >= 2) return nums[0] == month && nums[1] == day
        return false
    }

    /** Decode a gram's face — inline base64 or a media-store `dataRef` — to a small thumbnail,
     *  through the shared bounded decode so a full-res card never inflates in the widget
     *  process. Failures just drop the thumbnail. */
    private fun decodeThumb(context: Context, img: com.toolsboox.da.ImageElement): Bitmap? =
        com.toolsboox.ot.LedgerMedia.resolveThumb(context, img.data, img.dataRef, 96)

    // ---- Feed cache read ----

    /** Load the freshest cached feed list. The Feed screen writes list-<mode>_<kind>.json (default
     *  "feed_all"); the widget can't know which view was last open, so it takes the most recently
     *  written list file. Pure disk read — [com.toolsboox.plugin.feeds.nw.FeedCache] never fetches. */
    private fun loadCachedFeed(context: Context): List<com.toolsboox.plugin.feeds.da.FeedEntry> {
        val dir = File(context.filesDir, "feed-cache")
        val lists = dir.listFiles { f -> f.name.startsWith("list-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val freshest = lists.firstOrNull() ?: return emptyList()
        val key = freshest.name.removePrefix("list-").removeSuffix(".json")
        return runCatching { com.toolsboox.plugin.feeds.nw.FeedCache.loadEntries(context, key) }.getOrDefault(emptyList())
    }

    // ---- Relative age ----

    private fun relativeAge(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return humanise(Duration.ofMillis(System.currentTimeMillis() - epochMs))
    }

    private fun relativeAgeIso(iso: String): String {
        val epoch = parseEpoch(iso)
        return if (epoch <= 0L) "" else relativeAge(epoch)
    }

    private fun parseEpoch(iso: String): Long = runCatching {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.recoverCatching {
        ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun humanise(d: Duration): String {
        val mins = d.toMinutes()
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            mins < 60 * 24 -> "${mins / 60}h"
            mins < 60 * 24 * 7 -> "${mins / (60 * 24)}d"
            else -> "${mins / (60 * 24 * 7)}w"
        }
    }

    /**
     * ✍ Blog widget: a DOOR, not a window — posting is a live WordPress session and nothing about
     * it is cached on disk, so the honest face is the labelled door itself rather than a stale
     * list pretending to be fresh. Same header/empty-row grammar as the data widgets, so the door
     * still looks like it belongs to the set.
     */
    fun renderPublish(context: Context, widthDp: Int, heightDp: Int): Bitmap =
        draw(context, "✍ Blog · Schedule", null, emptyList(),
            "Tap to write or schedule a post.", widthDp, heightDp)

    /** 🎟 Event Attendees widget: the same door shape — the roster is live FluentBooking data a
     *  widget process must never fetch, and there is no on-disk copy to show. */
    fun renderRoster(context: Context, widthDp: Int, heightDp: Int): Bitmap =
        draw(context, "🎟 Event Attendees", null, emptyList(),
            "Tap to open today's attendees.", widthDp, heightDp)

    // ---- Drawing ----

    private fun draw(
        context: Context, title: String, countLabel: String?, rows: List<Row>,
        emptyText: String, widthDp: Int, heightDp: Int
    ): Bitmap {
        val bodyRows = if (rows.isEmpty()) 1 else rows.size
        val canvasH = HEADER_H + bodyRows * ROW_H
        val bitmap = Bitmap.createBitmap(CW.toInt(), canvasH.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, CW, canvasH, fillWhite)

        // Section-bar header (day-page column-header style: grey fill, white bold label).
        canvas.drawRect(0f, 0f, CW, HEADER_H, fillGrey80)
        canvas.drawText(title, PAD, HEADER_H - 28f, textWhite)
        if (countLabel != null) canvas.drawText(countLabel, CW - PAD, HEADER_H - 28f, textWhiteRight)
        canvas.drawLine(0f, HEADER_H, CW, HEADER_H, lineBlack)

        if (rows.isEmpty()) {
            canvas.drawText(emptyText, PAD, HEADER_H + ROW_H * 0.6f, textEmpty)
        } else {
            for ((i, row) in rows.withIndex()) {
                val top = HEADER_H + i * ROW_H
                if (i % 2 == 1) canvas.drawRect(0f, top, CW, top + ROW_H, fillGrey20)

                var x = PAD
                if (row.thumb != null) {
                    val size = ROW_H - 20f
                    val dst = Rect(x.toInt(), (top + 10f).toInt(), (x + size).toInt(), (top + 10f + size).toInt())
                    canvas.drawBitmap(row.thumb, null, dst, thumbPaint)
                    canvas.drawRect(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(), lineGrey50)
                    x += size + 18f
                } else if (row.marker != null) {
                    canvas.drawText(row.marker, x, top + 46f, textMarker)
                    x += 44f
                }

                val textW = CW - x - PAD
                if (row.secondary != null) {
                    canvas.drawText(TextUtils.ellipsize(row.primary, textPrimary, textW, TextUtils.TruncateAt.END).toString(),
                        x, top + 38f, textPrimary)
                    canvas.drawText(TextUtils.ellipsize(row.secondary, textSecondary, textW, TextUtils.TruncateAt.END).toString(),
                        x, top + 68f, textSecondary)
                } else {
                    // Single-line row (the pile / tasks): centre it vertically in the band.
                    val paint = if (row.muted) textEmpty else textPrimary
                    canvas.drawText(TextUtils.ellipsize(row.primary, paint, textW, TextUtils.TruncateAt.END).toString(),
                        x, top + ROW_H * 0.62f, paint)
                }
                if (i > 0) canvas.drawLine(0f, top, CW, top, lineGrey50)
            }
        }
        canvas.drawLine(0f, canvasH - 1f, CW, canvasH - 1f, lineBlack)

        return scaleToWidget(context, bitmap, widthDp, heightDp)
    }

    /** Same fit-then-cap scale the day-page renderer uses, so a list widget downsizes to the home
     *  screen identically and never overshoots the RemoteViews bitmap ceiling. The fit is to the
     *  widget's REAL pixel box (its dp bounds × density) and the cap is [WidgetBitmapBudget]'s
     *  computed one — the widget ships at its native resolution and the launcher never has to
     *  stretch it back up. */
    private fun scaleToWidget(context: Context, src: Bitmap, widthDp: Int, heightDp: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
        val heightPx = (heightDp * density).roundToInt().coerceAtLeast(1)

        val fitScale = min(widthPx / src.width.toFloat(), heightPx / src.height.toFloat())
        val rawW = (src.width * fitScale).roundToInt()
        val rawH = (src.height * fitScale).roundToInt()
        val capScale = WidgetBitmapBudget.capScale(context, rawW, rawH)
        val outW = maxOf(1, (rawW * capScale).roundToInt())
        val outH = maxOf(1, (rawH * capScale).roundToInt())

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, outW, outH), scalePaint)
        src.recycle()
        return out
    }

    // ---- Paints (mirroring WidgetRenderer's greyscale conventions) ----

    private val fillWhite = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val fillGrey20 = Paint().apply { color = Color.argb(20, 128, 128, 128); style = Paint.Style.FILL }
    private val fillGrey80 = Paint().apply { color = Color.argb(204, 128, 128, 128); style = Paint.Style.FILL }
    private val lineBlack = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
    private val lineGrey50 = Paint().apply { color = Color.argb(128, 128, 128, 128); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val scalePaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val thumbPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

    private val textWhite = TextPaint().apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
    private val textWhiteRight = TextPaint().apply {
        color = Color.WHITE; textSize = 36f; textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD
    }
    private val textPrimary = TextPaint().apply { color = Color.BLACK; textSize = 36f; typeface = Typeface.DEFAULT_BOLD }
    private val textSecondary = TextPaint().apply { color = 0x99000000.toInt(); textSize = 27f; typeface = Typeface.DEFAULT }
    private val textMarker = TextPaint().apply { color = Color.BLACK; textSize = 34f; typeface = Typeface.DEFAULT }
    private val textEmpty = TextPaint().apply { color = 0x99000000.toInt(); textSize = 34f; typeface = Typeface.DEFAULT }

    // All Stars cells — mono, per the Stars page's own aesthetic ("mono headers, thin rules").
    private val textStarCount = TextPaint().apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 110f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textStarCountZero = TextPaint().apply {
        color = 0x55000000; textAlign = Paint.Align.CENTER; textSize = 110f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textStarBand = TextPaint().apply {
        color = 0x99000000.toInt(); textAlign = Paint.Align.CENTER; textSize = 30f
        typeface = Typeface.MONOSPACE
    }
}
