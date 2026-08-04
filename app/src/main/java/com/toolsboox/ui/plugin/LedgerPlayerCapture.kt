package com.toolsboox.ui.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.squareup.moshi.Moshi
import com.toolsboox.ot.DateJsonAdapter
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.ot.LocaleJsonAdapter
import com.toolsboox.ot.ModalScale
import com.toolsboox.ot.UUIDJsonAdapter
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake
import com.toolsboox.plugin.calendar.ot.GramDestinations
import com.toolsboox.plugin.calendar.ot.LinkCardRenderer
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 * The moment is part of the capture. Both paths append the playhead to the episode URL as a
 * `#t=<seconds>` media fragment (the W3C form podcast apps and transcripts key on), so the gram's
 * source link carries not just WHAT he was hearing but WHERE in it — the timestamp he asked for,
 * checkable against the transcript later.
 *
 * The player outlives every fragment (that is its whole point), so nothing here may lean on one:
 * the day service is built the way the sync workers build theirs (same Moshi adapters as
 * NetworkModule.provideMoshi), the documents root comes from [LedgerPaths], and feedback is a
 * plain Toast rather than a fragment-anchored Snackbar.
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
                val day = service.load(root, today, null, Locale.getDefault())
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
     * 📝 — a note on the playing episode, seeded with the moment: the composer opens with
     * "@ 23:41 — " already typed (mm:ss; h:mm:ss past the hour) and the cursor after it.
     *
     * The playhead is read at BUTTON PRESS, not at save: he types slowly on glass, playback does
     * not pause, and a timestamp taken at save would mark wherever the episode had wandered to by
     * then — the moment he reacted to is the one the button went down on, so that is the one the
     * note keeps, in its seed line and in the source link's `#t=`.
     *
     * Each save is its OWN gram — the star's dedupe deliberately does not apply here, so the
     * third note on an episode lands beside the first two ([PickingsPlacement.place] never
     * dedupes; only the star path checks for twins). The note rides the remembered-destination
     * funnel like every other quick capture: [GramDestinations.inbox], the Gram Picks pile
     * whenever the memory can't be honoured today.
     */
    fun annotateNow(context: Context) {
        val cap = LedgerPlayer.capture ?: return
        val atMs = LedgerPlayer.positionMs
        val stamp = clock(atMs)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val input = EditText(context).apply {
            setText("@ $stamp — ")
            setSelection(text.length)
            setSingleLine(false); minLines = 3; gravity = android.view.Gravity.TOP
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0); addView(input)
        }
        val dialog = AlertDialog.Builder(ModalScale.wrap(context))
            .setTitle(cap.title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isBlank()) return@setPositiveButton
                place(context.applicationContext, cap, n, atMs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // A note being typed is work — a stray touch outside must not throw it away. The reader's
        // composer gets this from showGuardedModal; there is no fragment here, so the same guard
        // is set directly. Cancel and the back gesture remain the ways out.
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    /** Render the note's quote-card face and place it at the remembered destination, off the main
     *  thread — provenance carried the same way the feed note carries it, plus the `#t=` moment. */
    private fun place(appCtx: Context, cap: LedgerPlayer.Capture, note: String, atMs: Int) {
        Thread {
            val service = dayService(appCtx)
            val root = LedgerPaths.documentsRoot(appCtx)
            val dest = GramDestinations.inbox(appCtx)
            val face = QuoteCardRenderer.render(note, footer(cap), null, CARD_W, 0)
            runCatching {
                PickingsPlacement.place(
                    service, root, face, LocalDate.now(), dest.key,
                    sourceLink = timedLink(cap.url.substringBefore("#"), atMs),
                    sourceLabel = cap.title, cardText = note,
                    sourceFeed = cap.feedTitle, intakeKind = dest.kind
                )
            }.onSuccess { toast(appCtx, "Gram on ${dest.name}.") }
                .onFailure { Timber.w(it, "player annotation failed") }
        }.apply { isDaemon = true }.start()
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
     * A day service with its two injected fields filled by hand. The player outlives every
     * fragment that could have lent its Hilt-injected one, so this builds the service the way the
     * sync workers do — the same three Moshi adapters as NetworkModule.provideMoshi, the
     * application context for the widget poke.
     */
    private fun dayService(appCtx: Context): CalendarDayService =
        CalendarDayService().apply {
            moshi = Moshi.Builder()
                .add(LocaleJsonAdapter()).add(DateJsonAdapter()).add(UUIDJsonAdapter()).build()
            appContext = appCtx.applicationContext
        }

    /** Feedback from any thread, anchored to nothing — the player has no fragment to Snackbar on. */
    private fun toast(appCtx: Context, msg: String) {
        main.post {
            runCatching { Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
