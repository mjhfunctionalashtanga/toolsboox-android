package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.CardTreatment
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.ui.plugin.ScreenFragment
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * THE ONE DESTINATION VOCABULARY — iOS's `GramDestinations`, on Android.
 *
 * Every place a gram can be sent, in reading order: Gram Picks (the inbox, first, because a picked
 * gram's home is the page you sort from — Michael: "when I pick a gram, I do want to eventually
 * place it, so it should go on Gram Picks"), today's notes, the day's Pickings boards, the All
 * Stars bands, Synthesize. One list feeds every chooser, and one memory — the same
 * `last_gram_destination` preference [FeedNoteGram's photo default] has written since it landed,
 * with the band riding alongside in its own key so a value that was a bare page key for a year
 * stays readable as a bare page key (the iOS side keeps these exact names for the same reason).
 *
 * The remembered destination is honoured only while it still EXISTS on the day being placed onto:
 * a named board from another day is not in today's list, and offering it would place a gram onto a
 * page nothing can reach. When the memory can't be honoured, the fallback is Gram Picks — the
 * inbox, which is always there and always right to sort from later.
 */
object GramDestinations {

    /** One place a gram can go: a page key, and — for the All Stars bands — which band. */
    data class Destination(val glyph: String, val name: String, val key: String, val kind: String = "")

    /** Today's handwritten Notes page — page "0", where feed notes have always landed. */
    const val NOTES_PAGE = "0"

    // The preference names are load-bearing: FeedNoteGram's photo-gram default has been writing
    // "last_gram_destination" into "ledger_gram_prefs" since it landed, and iOS reads/writes the
    // same pair — renaming either would silently forget where the last gram went on every device
    // that upgrades.
    private const val PREFS = "ledger_gram_prefs"
    private const val KEY_LAST = "last_gram_destination"
    private const val KEY_LAST_KIND = "last_gram_destination_kind"

    /** Every making surface a gram can be sent to, on [date], in reading order. */
    fun all(context: android.content.Context, date: LocalDate = LocalDate.now()): List<Destination> {
        val out = mutableListOf(
            Destination("◈", "Gram Picks", CalendarDayPageNotes.GRAM_PICKS),
            Destination("📝", "Today's notes", NOTES_PAGE),
        )
        for (b in PickingsStore.list(context, date)) {
            val name = if (b.key == PickingsStore.DEFAULT_KEY) "Today's Pickings"
            else b.name.ifBlank { "Pickings" }
            out.add(Destination("❝", name, b.key))
        }
        for (p in CalendarDayPageIntake.kinds) {
            out.add(Destination("★", "All Stars · ${p.title}", CalendarDayPageIntake.INTAKE_PAGE, p.kindKey))
        }
        out.add(Destination("🔬", "Synthesize", "synthesize"))
        return out
    }

    /** Where the last gram went, if that place still exists on [date]; Gram Picks otherwise. */
    fun last(context: android.content.Context, date: LocalDate = LocalDate.now()): Destination {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_LAST, NOTES_PAGE) ?: NOTES_PAGE
        val kind = prefs.getString(KEY_LAST_KIND, "") ?: ""
        val list = all(context, date)
        return list.firstOrNull { it.key == key && it.kind == kind } ?: list[0]
    }

    /** Record a choice — called by the chooser, so the next capture learns from the last one. */
    fun remember(context: android.content.Context, d: Destination) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST, d.key).putString(KEY_LAST_KIND, d.kind).apply()
    }

    /** The chooser's order: the remembered destination first, then everything else in reading order. */
    fun ordered(context: android.content.Context, date: LocalDate = LocalDate.now()): List<Destination> {
        val first = last(context, date)
        return listOf(first) + all(context, date).filter { it.key != first.key || it.kind != first.kind }
    }

    /** A destination named plainly, for a toast or a menu label. Falls back through the board
     *  registry so a gram placed on a board named on another day still says where it landed. */
    fun name(
        context: android.content.Context, key: String, kind: String = "",
        date: LocalDate = LocalDate.now()
    ): String {
        all(context, date).firstOrNull { it.key == key && it.kind == kind }?.let { return it.name }
        if (PickingsStore.isPickings(key)) return PickingsStore.nameOf(context, date, key)
        return "today's notes"
    }

    /**
     * The one chooser every "send this gram to…" runs through: the remembered destination first,
     * then the whole vocabulary, then "＋ New pickings…". Choosing is what teaches the memory —
     * the destination is already remembered by the time [onPick] runs.
     *
     * [fragment] is optional so MainActivity-hosted flows could reuse the list; when present, the
     * new-board name dialog is guarded (a name being typed is work — a stray touch outside must
     * not throw it away).
     */
    fun choose(
        context: android.content.Context,
        date: LocalDate = LocalDate.now(),
        title: String = "Send this gram to",
        fragment: ScreenFragment? = null,
        onPick: (Destination) -> Unit
    ) {
        val choices = ordered(context, date)
        val labels = (choices.map { "${it.glyph}  ${it.name}" } + "＋  New pickings…").toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle(title)
            .setItems(labels) { _, which ->
                if (which < choices.size) {
                    val d = choices[which]
                    remember(context, d)
                    onPick(d)
                } else {
                    val input = android.widget.EditText(context).apply { hint = "Pickings name"; setSingleLine() }
                    val pad = (16 * context.resources.displayMetrics.density).toInt()
                    val box = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, 0); addView(input)
                    }
                    val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
                        .setTitle("New pickings").setView(box)
                        .setPositiveButton("Create") { _, _ ->
                            val page = PickingsStore.add(context, date, input.text.toString().trim())
                            val d = Destination("❝", page.name, page.key)
                            remember(context, d)
                            onPick(d)
                        }.setNegativeButton(android.R.string.cancel, null).create()
                    if (fragment != null) fragment.showGuardedModal(dialog) else dialog.show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}

/**
 * Drops a rendered gram (bitmap) onto a pickings board — the cross-surface "Add to Pickings" path.
 * Writes an [ImageElement] (inline base64 PNG, same shape as on-canvas image inserts) onto the
 * chosen board's page key in the day JSON, so the card is there when the board is opened.
 */
object PickingsPlacement {
    /** The long edge a placed card is kept at. Public because callers that DECODE a face before
     *  handing it over should decode no bigger than this — a full-resolution decode that this
     *  method then scales down is a peak allocation nobody ever wanted (see the 86 MB day file). */
    const val MAX_DIM = 1200
    private const val CANVAS_W = 1404f
    private const val CANVAS_H = 1872f

    /**
     * What makes a placed card an A/V gram rather than a plain one: the bitmap is its poster
     * frame, and this says what to play when it's tapped. [attachmentId] points at the blob in
     * the day's `avGrams`; [url] is the remote copy for devices that never held it.
     */
    data class MediaRef(
        val kind: String,
        val attachmentId: String = "",
        val url: String = "",
        val durationMs: Int = 0,
        val title: String = "",
        val date: String = ""
    )

    fun place(
        service: CalendarDayService, root: File, bitmap: Bitmap, date: LocalDate, pageKey: String,
        sourceLink: String = "", sourceLabel: String = "", media: MediaRef? = null,
        treatment: Boolean = true, cardText: String = "", sourceFeed: String = "",
        intakeKind: String = ""
    ) {
        val longest = maxOf(bitmap.width, bitmap.height)
        val fitted = if (longest > MAX_DIM) {
            val r = MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * r).toInt().coerceAtLeast(1), (bitmap.height * r).toInt().coerceAtLeast(1), true)
        } else bitmap
        // Give it a paper ground and tape it down. A crop arrives as bare ink on transparency,
        // which reads as marks lying on the page rather than as a card someone placed; the
        // margin and the edge are what make it an object. Baked after the fit so the tape is
        // proportioned to the size it will actually be seen at, and before the measurement
        // below so the frame is inside the card's own aspect ratio.
        val scaled = if (treatment) CardTreatment.card(fitted) else fitted
        val baos = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        // All Stars sizes its own arrivals: the register's twelve-slot shelf IS the half-size
        // Michael asked for ("grams added to all stars should be 1/2 the size they currently are
        // when added so they don't have to overlap") — [CalendarDayPageIntake.arrivalFrame] fits
        // the card into the next slot of its band, and the page zooms for reading anyway. Every
        // other board keeps the natural card width; existing elements are untouched — this only
        // sizes what lands from now on, and a landed card is still yours to resize like any other.
        val isIntakeArrival = pageKey == CalendarDayPageIntake.INTAKE_PAGE && intakeKind.isNotBlank()
        val w = (CANVAS_W * 0.42f).coerceAtMost(scaled.width.toFloat())
        val h = w * scaled.height / scaled.width
        // Load→mutate→save under the day lock: placements arrive from raw threads (placeAsync)
        // and race the open day page's per-pen-up save; interleaved writers drop each other's items.
        DayLocks.withDay(date) {
            val day = service.load(root, date, null, Locale.getDefault())
            val count = day.imageElements.count { it.page == pageKey }        // grid-stagger new cards
            // ALL STARS lands each arrival inside the band for its kind, so the register organises
            // itself without anyone filing anything — and the card is an ordinary element from that
            // moment on, free to be dragged around its band (the settle pass keeps it on its own
            // kind's shelf). Every other page keeps the plain three-across stagger.
            //
            // Resolved from the DAY, not from a per-page count: the register's bands are sized to
            // their content, so where the next slot sits depends on how full every band above it
            // is — geometry only the whole day file can answer.
            val bandSlot = if (isIntakeArrival)
                CalendarDayPageIntake.arrivalFrame(intakeKind, day, w, h)
            else null
            // Every board is the standard full-height page now (the daily cover band is gone), so
            // picked cards start at the normal top for all of them. A band slot carries the fitted
            // SIZE as well as the spot — the register aspect-fits each arrival into its half-cell
            // so its rows stay rows — so an All Stars arrival takes the whole rect.
            val yBase = 120f
            val placedW = bandSlot?.width() ?: w
            val placedH = bandSlot?.height() ?: h
            val x = bandSlot?.left
                ?: (60f + (count % 3) * (w + 30f)).coerceIn(0f, (CANVAS_W - w).coerceAtLeast(0f))
            val y = bandSlot?.top
                ?: (yBase + (count / 3) * (h + 30f)).coerceIn(0f, (CANVAS_H - h).coerceAtLeast(0f))
            day.imageElements.add(ImageElement(
                x = x, y = y, width = placedW, height = placedH, data = base64, page = pageKey,
                sourceLink = sourceLink, sourceLabel = sourceLabel,
                mediaKind = media?.kind ?: "",
                attachmentId = media?.attachmentId ?: "",
                mediaUrl = media?.url ?: "",
                durationMs = media?.durationMs ?: 0,
                mediaTitle = media?.title ?: "",
                mediaDate = media?.date ?: "",
                cardText = cardText, sourceFeed = sourceFeed, intakeKind = intakeKind,
                // The one-decoration contract: this card already wears its CardTreatment in its own
                // pixels, so a render-time edge (iOS GramEdge) must stand down for it.
                edgeBaked = treatment))
            service.save(root, date, day)
        }
    }

    /** Chooser: the full destination vocabulary — then place [bitmap] off the main thread. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmap: Bitmap,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = ""
    ) = chooseAndPlace(fragment, service, root, listOf(bitmap), date, sourceLink, sourceLabel, media, cardText, sourceFeed)

    /** Same chooser for SEVERAL cards (a gram series) — one destination pick, all placed. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = ""
    ) {
        if (bitmaps.isEmpty()) return
        val ctx = fragment.requireContext()
        // The whole vocabulary, remembered-last-first, through the one funnel — iOS's
        // GramDestinations, so a gram can go anywhere a gram can live and the chooser teaches the
        // memory every quick-capture path reads. Gram Picks leads whenever there is no memory to
        // honour: you no longer have to decide what a gram is FOR at the moment you grab it, which
        // is the moment you know least.
        GramDestinations.choose(ctx, date, "Send this gram to", fragment) { d ->
            placeAsync(
                fragment, service, root, bitmaps, date, d.key, d.name,
                sourceLink, sourceLabel, media, cardText, sourceFeed, intakeKind = d.kind
            )
        }
    }

    private fun placeAsync(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate, key: String, name: String, sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = "", intakeKind: String = ""
    ) {
        Thread {
            for (b in bitmaps) runCatching { place(service, root, b, date, key, sourceLink, sourceLabel, media, cardText = cardText, sourceFeed = sourceFeed, intakeKind = intakeKind) }
            val what = if (bitmaps.size > 1) "${bitmaps.size} grams" else "Placed"
            // Offer the trip rather than taking it. You were mid-something on the page you
            // circled from, and the usual next move is to put another thing on the same board —
            // so staying is the right default and going is one tap.
            runCatching {
                fragment.requireActivity().runOnUiThread {
                    com.google.android.material.snackbar.Snackbar.make(
                        fragment.requireView(), "$what on $name.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).setAction("Go to it") {
                        com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(fragment, date, key)
                    }.show()
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
