package com.toolsboox.ui.plugin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.toolsboox.R
import com.toolsboox.da.Attachment
import com.toolsboox.ot.InkPadView
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.ot.ModalScale
import com.toolsboox.ot.VoiceRecorder
import com.toolsboox.plugin.calendar.da.v2.ReadingEvent
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.AvGrams
import com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake
import com.toolsboox.plugin.calendar.ot.DayLocks
import com.toolsboox.plugin.calendar.ot.GramDestinations
import com.toolsboox.plugin.calendar.ot.LinkCardRenderer
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ★ and 📝 for the thing that is PLAYING — capture from the transport itself.
 *
 * The article star and the note dialog both live on the reader pane, and a podcast never opens
 * that pane: tapping a Listen entry starts [LedgerPlayer] and the list stays put, so the episode
 * you were reacting to had no star and no note anywhere on screen (Michael: "it pops up the
 * independent player on the lesson... so it's hard to get a podcast to intake"). This object is
 * those two captures for the player's own surfaces — the transport modal and the drawer's inline
 * Now Playing card — working from [LedgerPlayer.capture], the episode identity the play call
 * handed over.
 *
 * The moment is part of the capture. Every path appends the playhead to the episode URL as a
 * `#t=<seconds>` media fragment (the W3C form podcast apps and transcripts key on), so the gram's
 * source link carries not just WHAT he was hearing but WHERE in it — the timestamp he asked for,
 * checkable against the transcript later.
 *
 * 📝 IS THE WHOLE CANON (09-10: "every annotation surface has 'Save highlight' with the
 * handwriting, audio capture, etc."). It used to be a typed note and nothing else; now it is the
 * same seven-row chooser every reading surface wears — ❝ Save this moment · ✍ Handwrite · 🎤 Voice ·
 * 📷 Photo · ⌨ Type · ★ Star · 📝 Onto today's note — with the moment ("@ 23:41") standing where a
 * highlighted passage stands. Each keep files a [ReadingEvent] onto today's day (title, feed, the
 * `#t=` link, the moment as the excerpt) so it appears in the reading log and syncs across the
 * fleet, and places its gram the way the feed note does.
 *
 * The player outlives every fragment (that is its whole point), so nothing here may lean on one:
 * the day service comes from CalendarDayService.standalone (the ledger's Moshi, the app context),
 * the documents root comes from [LedgerPaths], and feedback is a
 * plain Toast rather than a fragment-anchored Snackbar. The camera and the microphone permission
 * need an Activity's launchers — the transport borrows [com.toolsboox.ui.main.MainActivity]'s
 * one-shot doors when the context it was handed resolves to one.
 */
object LedgerPlayerCapture {

    /** The star face's canvas width — the same paper the feed star's link card renders at. */
    private const val CARD_W = 1080

    private val main = Handler(Looper.getMainLooper())

    /**
     * ★ — the playing episode onto All Stars, exactly the shape the article star mints: the
     * link-card face (kind chip · art · title · excerpt · feed), placed into the Listen band
     * (`intakeKind = "listen"`), deduped by source link, answered with the same toasts.
     *
     * The playhead is read at BUTTON PRESS and rides the source link as `#t=`. Dedupe compares
     * with the fragment stripped: a re-star ten minutes later is still the same episode, and the
     * register keeps the first star's card — once placed, the board is his (arranged, annotated),
     * not a mirror of the star state, same as [com.toolsboox.plugin.feeds.ot.FeedNoteGram.placeStarGram].
     */
    fun starNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val atMs = LedgerPlayer.positionMs
        // The transport already loaded the episode art for its own display — reuse it as the
        // card's thumb rather than fetching twice. Read here, on the main thread, before the work
        // moves off it.
        val art = LedgerPlayer.image
        val appCtx = context.applicationContext
        Thread {
            val service = dayService(appCtx)
            val root = LedgerPaths.documentsRoot(appCtx)
            val today = LocalDate.now()
            val base = cap.url.substringBefore("#")
            val already = runCatching {
                // Read-only dedupe peek; place() below is the writer (and refuses an unreadable day).
                val day = service.loadForDisplay(root, today, null, Locale.getDefault())
                day.imageElements.any {
                    it.page == CalendarDayPageIntake.INTAKE_PAGE &&
                        it.sourceLink.substringBefore("#") == base
                }
            }.getOrDefault(false)
            if (already) {
                // Say so — a silent no-op reads as a dead star (the feed star's own rule).
                toast(appCtx, "★ already on All Stars")
                return@Thread
            }
            // File the link's face alongside, the way the feed star does, so the SAME link picked
            // up later (shared, re-rendered off the Later list) arrives already dressed.
            if (cap.excerpt.isNotBlank() || !cap.imageUrl.isNullOrBlank()) runCatching {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.rememberLinkMeta(
                    appCtx, today, base, cap.title, cap.excerpt, cap.imageUrl)
            }
            val face = LinkCardRenderer.render(
                base, cap.title, "listen", thumb = art,
                sourceName = cap.feedTitle, excerpt = cap.excerpt, W = CARD_W)
            val host = runCatching { java.net.URI(base).host?.removePrefix("www.") }.getOrNull().orEmpty()
            runCatching {
                PickingsPlacement.place(
                    service, root, face, today, CalendarDayPageIntake.INTAKE_PAGE,
                    sourceLink = timedLink(base, atMs),
                    sourceLabel = cap.feedTitle.ifBlank { host },
                    cardText = cap.title, sourceFeed = cap.feedTitle,
                    intakeKind = "listen"
                )
            }.onSuccess { toast(appCtx, "★ → All Stars") }
                .onFailure { Timber.w(it, "player star failed") }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 📝 — the canon chooser for the playing episode, seeded with the moment.
     *
     * The playhead is read at BUTTON PRESS, not at save: he types slowly on glass, playback does
     * not pause, and a timestamp taken at save would mark wherever the episode had wandered to by
     * then — the moment he reacted to is the one the button went down on, so that is the one
     * every row keeps, as the excerpt of its ReadingEvent and in the source link's `#t=`.
     *
     * Each save is its OWN gram — the star's dedupe deliberately does not apply here, so the
     * third note on an episode lands beside the first two ([PickingsPlacement.place] never
     * dedupes; only the star path checks for twins). Grams ride the remembered-destination
     * funnel like every other quick capture: [GramDestinations.inbox], the Gram Picks pile
     * whenever the memory can't be honoured today — except 📝 Onto today's note, which names
     * the page you are writing on.
     */
    fun annotateNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val atMs = LedgerPlayer.positionMs
        val stamp = clock(atMs)
        val moment = "@ $stamp"
        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows += "❝  Save this moment" to { keepMoment(context, cap, atMs, moment) }
        rows += "✍  Handwrite a note" to { inkNote(context, cap, atMs, moment) }
        rows += "🎤  Record a voice note" to { voiceNote(context, cap, atMs, moment) }
        rows += "📷  Photo" to { photoNote(context, cap, atMs, moment) }
        rows += "⌨  Type a note" to { typeNote(context, cap, atMs, moment) }
        rows += "★  Star it" to { starNow(context) }
        rows += "📝  Onto today's note" to { ontoTodaysNote(context, cap, atMs, moment) }
        AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle("$moment · ${cap.title}")
            .setItems(rows.map { it.first }.toTypedArray()) { _, i -> rows[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- The player's own pick doors (Michael, 09-19: "provide the annotation pick options on
    // the player") — the row's verbs, working from the capture the play call handed over. -------

    /** 🃏 — the playing episode as a PICK at the moment: the link-card face the star mints, placed
     *  at the remembered destination and never deduped — a pick is a new act each time, the way
     *  every other pick is ([PickingsPlacement.place] never dedupes; only ★ checks for twins). */
    fun pickNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val atMs = LedgerPlayer.positionMs
        val art = LedgerPlayer.image
        val appCtx = context.applicationContext
        Thread {
            val base = cap.url.substringBefore("#")
            if (cap.excerpt.isNotBlank() || !cap.imageUrl.isNullOrBlank()) runCatching {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.rememberLinkMeta(
                    appCtx, LocalDate.now(), base, cap.title, cap.excerpt, cap.imageUrl)
            }
            val face = LinkCardRenderer.render(
                base, cap.title, "listen", thumb = art,
                sourceName = cap.feedTitle, excerpt = cap.excerpt, W = CARD_W)
            logMoment(appCtx, cap, atMs, "@ ${clock(atMs)}", note = null, attachment = null)
            placeFace(appCtx, cap, atMs, face, cardText = cap.title)
        }.apply { isDaemon = true }.start()
    }

    /** ⏳ — the episode onto the Later list's Listen lane through the one write path, and its
     *  audio kept on this device (Later means having it when later comes). */
    fun laterNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val appCtx = context.applicationContext
        Thread {
            runCatching {
                com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                    appCtx, LocalDate.now(), "listen", cap.url.substringBefore("#"), cap.title,
                    excerpt = cap.excerpt.ifBlank { null }, image = cap.imageUrl)
                if (cap.enclosureUrl.startsWith("http")) {
                    // The ask, with the enclosure aboard (fileLink recorded it off the page URL).
                    com.toolsboox.plugin.calendar.ot.ListenStateStore.setWantOffline(
                        appCtx, cap.url.substringBefore("#"), true, audioUrl = cap.enclosureUrl,
                        title = cap.title, show = cap.feedTitle, artworkUrl = cap.imageUrl?.takeIf { it.startsWith("http") }.orEmpty())
                    com.toolsboox.plugin.feeds.nw.LaterMedia.undismiss(appCtx, cap.enclosureUrl)
                    com.toolsboox.plugin.feeds.nw.LaterMedia.rememberAsk(appCtx, cap.enclosureUrl,
                        cap.url.substringBefore("#"), cap.title, cap.feedTitle, cap.imageUrl.orEmpty())
                    com.toolsboox.plugin.feeds.nw.LaterMedia.download(appCtx, cap.enclosureUrl, null,
                        com.toolsboox.plugin.feeds.nw.LaterMedia.REASON_ASKED)
                }
            }.onSuccess { toast(appCtx, "⏳ Later · Listen · ${cap.title.take(50)}") }
                .onFailure { Timber.w(it, "player later failed"); toast(appCtx, "Couldn't file it for later") }
        }.apply { isDaemon = true }.start()
    }

    /** ❝ — a highlight at the playhead. Seeded with the transcript's words around the moment when
     *  the episode has one (the flattened transcript carries no clocks, so the window is
     *  proportional — playhead over duration — and offered to EDIT, never saved unseen); with no
     *  transcript the moment itself is the passage, as ❝ Save this moment already does. */
    fun highlightNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val atMs = LedgerPlayer.positionMs
        val moment = "@ ${clock(atMs)}"
        val seed = transcriptWindow(atMs)
        if (seed == null) { keepMoment(context, cap, atMs, moment); return }
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val input = EditText(context).apply {
            setText(seed); setSelection(0)
            setSingleLine(false); minLines = 4; gravity = android.view.Gravity.TOP
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0); addView(input)
        }
        val appCtx = context.applicationContext
        val dialog = AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle("❝ $moment · ${cap.title}")
            .setView(box)
            .setPositiveButton("Keep") { _, _ ->
                val q = input.text.toString().trim().ifBlank { moment }
                Thread {
                    logMoment(appCtx, cap, atMs, moment, note = q.takeIf { it != moment }, attachment = null)
                    placeFace(appCtx, cap, atMs, QuoteCardRenderer.render(q, footer(cap), null, CARD_W, 0),
                        cardText = q)
                }.apply { isDaemon = true }.start()
            }
            .setNeutralButton("Just the moment") { _, _ -> keepMoment(context, cap, atMs, moment) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    /** ~50 words of the transcript around the playhead's proportional position, or null. */
    private fun transcriptWindow(atMs: Int): String? {
        val text = LedgerPlayer.transcriptProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        val dur = LedgerPlayer.durationMs
        if (dur <= 0) return null
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 20) return null
        val at = ((atMs.toDouble() / dur) * words.size).toInt().coerceIn(0, words.size - 1)
        val from = (at - 25).coerceAtLeast(0)
        val to = (at + 25).coerceAtMost(words.size)
        return words.subList(from, to).joinToString(" ")
    }

    /** The doors as ONE menu — what the folded chip's hold and the title's hold open, so the
     *  transport never has to be unfolded to keep something. */
    fun doorsMenu(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val rows = listOf<Pair<String, () -> Unit>>(
            "★  Star" to { starNow(context) },
            "🃏  Make pick" to { pickNow(context) },
            "❝  Highlight" to { highlightNow(context) },
            "⏳  Save for later" to { laterNow(context) },
            "📝  Note at this moment" to { annotateNow(context) }
        )
        AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle("@ ${clock(LedgerPlayer.positionMs)} · ${cap.title}")
            .setItems(rows.map { it.first }.toTypedArray()) { _, i -> rows[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- ❝ Save this moment ----------------------------------------------------------------

    /** The bare moment kept in one tap: its Log line, and the quote card ("@ 23:41" over the
     *  episode footer) at the remembered destination — a highlight with the clock as the passage. */
    private fun keepMoment(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val appCtx = context.applicationContext
        Thread {
            logMoment(appCtx, cap, atMs, moment, note = null, attachment = null)
            placeFace(appCtx, cap, atMs, QuoteCardRenderer.render(moment, footer(cap), null, CARD_W, 0),
                cardText = moment)
        }.apply { isDaemon = true }.start()
    }

    // --- ⌨ Type a note -------------------------------------------------------------------------

    /** The typed note, opened with "@ 23:41 — " already there and the cursor after it. */
    private fun typeNote(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val input = EditText(context).apply {
            setText("$moment — ")
            setSelection(text.length)
            setSingleLine(false); minLines = 3; gravity = android.view.Gravity.TOP
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0); addView(input)
        }
        val appCtx = context.applicationContext
        val dialog = AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle(cap.title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isBlank()) return@setPositiveButton
                Thread {
                    logMoment(appCtx, cap, atMs, moment, note = n, attachment = null)
                    placeFace(appCtx, cap, atMs, QuoteCardRenderer.render(n, footer(cap), null, CARD_W, 0),
                        cardText = n)
                }.apply { isDaemon = true }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // A note being typed is work — a stray touch outside must not throw it away. The reader's
        // composer gets this from showGuardedModal; there is no fragment here, so the same guard
        // is set directly. Cancel and the back gesture remain the ways out.
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    // --- ✍ Handwrite a note ---------------------------------------------------------------------

    /** The shared ink pad, buttons on TOP (the rule every handwriting panel follows: the writing
     *  hand rests below the pad). The ink lands as a PHOTO attachment on the moment's ReadingEvent
     *  and as its own gram — the same two homes the feed's handwritten note has. */
    private fun inkNote(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val appCtx = context.applicationContext
        val pad = InkPadView(context)
        val frame = FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt()); setPadding(px(2), px(2), px(2), px(2))
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(340)))
        }
        lateinit var dialog: AlertDialog
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(6))
            addView(TextView(context).apply {
                text = "$moment · ${cap.title}"
                textSize = 16f; setTextColor(0xFF000000.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "  Cancel  "; textSize = 14f; setTextColor(0xFF555555.toInt())
                setOnClickListener { dialog.dismiss() }
            })
            addView(TextView(context).apply {
                text = "  SAVE  "; textSize = 15f
                setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF000000.toInt())
                setPadding(px(12), px(6), px(12), px(6))
                setOnClickListener {
                    isEnabled = false   // first tap wins — the encode is slow enough to palm-bounce
                    val written = pad.render()
                    dialog.dismiss()
                    if (written == null) return@setOnClickListener
                    Thread {
                        val dest = File(LedgerPaths.attachmentsDir(appCtx), "photo-${UUID.randomUUID()}.png")
                        val ok = runCatching {
                            dest.outputStream().use { written.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.isSuccess
                        val att = if (ok) Attachment(UUID.randomUUID().toString(), Attachment.Kind.PHOTO,
                            dest.name, null, Date()) else null
                        logMoment(appCtx, cap, atMs, moment, note = null, attachment = att)
                        placeFace(appCtx, cap, atMs, written, cardText = moment)
                    }.apply { isDaemon = true }.start()
                }
            })
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), px(10))
            addView(topBar)
            addView(InkPadView.penBar(context, pad)); addView(frame)
        }
        dialog = AlertDialog.Builder(ModalScale.wrap(context)).setView(box).create()
        // The pad is work in progress — a palm outside the dialog must not cost the ink.
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    // --- 🎤 Record a voice note ---------------------------------------------------------------

    /** The shared recorder; the clip files through [AvGrams] onto the remembered page (poster
     *  card + the clip in the day's avGrams) and rides the moment's ReadingEvent as its audio. */
    private fun voiceNote(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val activity = context.activity() as? com.toolsboox.ui.main.MainActivity
        if (activity == null) {
            toast(context.applicationContext, "Recording needs the app in front — open it and try again.")
            return
        }
        val appCtx = context.applicationContext
        activity.withMicrophone {
            VoiceRecorder.record(
                context = activity,
                out = File(LedgerPaths.attachmentsDir(appCtx), "voice-${UUID.randomUUID()}.m4a"),
                recordingLabel = { c -> activity.getString(R.string.reader_capture_recording, c) },
                stopLabel = activity.getString(R.string.reader_capture_stop),
                onSaved = { file, seconds ->
                    val att = Attachment(UUID.randomUUID().toString(), Attachment.Kind.AUDIO,
                        file.name, seconds, Date())
                    Thread {
                        logMoment(appCtx, cap, atMs, moment, note = null, attachment = att)
                        val service = dayService(appCtx)
                        val root = LedgerPaths.documentsRoot(appCtx)
                        val dest = GramDestinations.inbox(appCtx)
                        val placed = runCatching {
                            AvGrams.file(
                                service, root, file, att,
                                title = "$moment · ${cap.title}",
                                pageKey = dest.key,
                                sourceLink = timedLink(cap.url.substringBefore("#"), atMs),
                                sourceLabel = cap.title
                            )
                        }.getOrDefault(false)
                        toast(appCtx, if (placed) "🎤 Gram on ${dest.name}." else "Voice note saved")
                    }.apply { isDaemon = true }.start()
                }
            )
        }
    }

    // --- 📷 Photo ------------------------------------------------------------------------------

    /** One photo through the activity's camera door; it becomes the moment's PHOTO attachment and
     *  a photo gram at the remembered destination. */
    private fun photoNote(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val activity = context.activity() as? com.toolsboox.ui.main.MainActivity
        if (activity == null) {
            toast(context.applicationContext, "The camera needs the app in front — open it and try again.")
            return
        }
        val appCtx = context.applicationContext
        activity.capturePhotoThen { shot ->
            Thread {
                val dest = File(LedgerPaths.attachmentsDir(appCtx), "photo-${UUID.randomUUID()}.jpg")
                val copied = runCatching { shot.copyTo(dest, overwrite = true); shot.delete() }.isSuccess
                val att = if (copied) Attachment(UUID.randomUUID().toString(), Attachment.Kind.PHOTO,
                    dest.name, null, Date()) else null
                logMoment(appCtx, cap, atMs, moment, note = null, attachment = att)
                val bmp = decodeSampled(if (copied) dest else shot)
                if (bmp == null) { toast(appCtx, "Couldn't read that picture."); return@Thread }
                placeFace(appCtx, cap, atMs, bmp, cardText = moment)
            }.apply { isDaemon = true }.start()
        }
    }

    // --- 📝 Onto today's note ---------------------------------------------------------------------

    /** The moment's quote card straight onto TODAY'S NOTES PAGE (page "0"), whatever the remembered
     *  destination is — the explicit "put it on the page I'm writing". */
    private fun ontoTodaysNote(context: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String) {
        val appCtx = context.applicationContext
        Thread {
            logMoment(appCtx, cap, atMs, moment, note = null, attachment = null)
            placeFace(appCtx, cap, atMs, QuoteCardRenderer.render(moment, footer(cap), null, CARD_W, 0),
                cardText = moment,
                destination = GramDestinations.Destination("📝", "Today's notes", GramDestinations.NOTES_PAGE))
        }.apply { isDaemon = true }.start()
    }

    // --- Filing ----------------------------------------------------------------------------------

    /**
     * The moment's line in the reading log: an ARTICLE-kind [ReadingEvent] (the episode is a feed
     * entry) titled with the episode, sourced to its feed, linked with the `#t=` moment, the
     * moment itself as the excerpt, whatever was added as its note or attachment. This is what
     * makes a podcast keep appear in the Ledger Log and cross the fleet with the day file. Call
     * OFF the main thread.
     */
    private fun logMoment(
        appCtx: Context, cap: LedgerPlayer.Capture, atMs: Int, moment: String,
        note: String?, attachment: Attachment?
    ) {
        runCatching {
            val service = dayService(appCtx)
            val root = LedgerPaths.documentsRoot(appCtx)
            val today = LocalDate.now()
            DayLocks.withDay(today) {
                // Throws (→ logged below) rather than save over a today-file that won't read.
                val day = service.loadForWriteOrThrow(root, today, null, Locale.getDefault())
                day.readingEvents.add(
                    ReadingEvent(
                        id = "listen-${UUID.randomUUID().toString().take(8)}",
                        kind = ReadingEvent.Kind.ARTICLE,
                        date = Date(),
                        title = cap.title,
                        source = cap.feedTitle.ifBlank { null },
                        url = timedLink(cap.url.substringBefore("#"), atMs).ifBlank { null },
                        excerpt = moment,
                        note = note?.ifBlank { null },
                        attachments = attachment?.let { mutableListOf(it) }
                    )
                )
                service.save(root, today, day)
            }
        }.onFailure { Timber.w(it, "player moment log failed") }
    }

    /** Place a face at [destination] (the remembered one when null), provenance carried the same
     *  way the feed note carries it, plus the `#t=` moment. Call OFF the main thread. */
    private fun placeFace(
        appCtx: Context, cap: LedgerPlayer.Capture, atMs: Int, face: Bitmap,
        cardText: String, destination: GramDestinations.Destination? = null
    ) {
        val service = dayService(appCtx)
        val root = LedgerPaths.documentsRoot(appCtx)
        val dest = destination ?: GramDestinations.inbox(appCtx)
        runCatching {
            PickingsPlacement.place(
                service, root, face, LocalDate.now(), dest.key,
                sourceLink = timedLink(cap.url.substringBefore("#"), atMs),
                sourceLabel = cap.title, cardText = cardText,
                sourceFeed = cap.feedTitle, intakeKind = dest.kind
            )
        }.onSuccess { toast(appCtx, "Gram on ${dest.name}.") }
            .onFailure { Timber.w(it, "player annotation failed") }
    }

    /** Decode a camera shot downsampled so the long edge is ≤1024 px (the media-bloat rule). */
    private fun decodeSampled(file: File): Bitmap? = runCatching {
        if (!file.exists()) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** The Activity under a themed/wrapped context, or null (a Service's context, say). */
    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** The episode URL with the moment aboard: `<url>#t=<seconds>` — the W3C media-fragment form,
     *  integer seconds, which is what transcript tooling and podcast apps key timestamps on. */
    private fun timedLink(base: String, atMs: Int): String =
        if (base.isBlank()) base else "$base#t=${(atMs / 1000).coerceAtLeast(0)}"

    /** mm:ss, or h:mm:ss past the hour — the Now Playing card's own clock shape. */
    private fun clock(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /** "date · title · feed" — the same footer line the feed note stamps on its cards. */
    private fun footer(cap: LedgerPlayer.Capture): String {
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        return (listOf(date, cap.title, cap.feedTitle)).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * The day service for the player, which outlives every fragment that could have lent its
     * Hilt-injected one — [CalendarDayService.standalone], the one factory (the ledger's Moshi, the
     * application context for the widget poke).
     */
    private fun dayService(appCtx: Context): CalendarDayService = CalendarDayService.standalone(appCtx)

    /** Feedback from any thread, anchored to nothing — the player has no fragment to Snackbar on. */
    private fun toast(appCtx: Context, msg: String) {
        main.post {
            runCatching { Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
