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

    /** One place a gram can go: a page key, and — for the All Stars bands — which band.
     *  [date] names the day the page lives on when it is NOT the placement caller's own day —
     *  a named note from last Tuesday is a real target now (the 08-28 ruling); null keeps the
     *  caller's date, which is every pre-ruling call unchanged. */
    data class Destination(
        val glyph: String, val name: String, val key: String, val kind: String = "",
        val date: LocalDate? = null,
    )

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
            Destination("◈", "All Picks", CalendarDayPageNotes.GRAM_PICKS),
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
        out.add(Destination("❖", "Synthesize", "synthesize"))
        return out
    }

    /**
     * WHERE A CAPTURED GRAM LANDS. Always the Gram Picks inbox, and deliberately not negotiable.
     *
     * Michael: "the real trick is making sure that grams get added to Gram Picks instead of being
     * put on today's note or today's picking first." He is describing two ways this used to go
     * wrong, and both were real. The remembered default fell back to today's NOTES page when
     * nothing had been chosen yet, so a fresh capture landed on the writing page; and once the
     * chooser recorded a Pickings board, every quiet capture afterwards drifted there too. Either
     * way the inbox stayed empty and the sorting he built it for happened at capture time, one
     * gram at a time, which is exactly the cost the inbox exists to avoid.
     *
     * So memory ORDERS THE CHOOSER and never ROUTES A CAPTURE. Placing something deliberately is a
     * decision about that gram; it is not a standing instruction about every gram to come.
     */
    fun inbox(context: android.content.Context, date: LocalDate = LocalDate.now()): Destination =
        all(context, date).firstOrNull { it.key == CalendarDayPageNotes.GRAM_PICKS }
            ?: Destination("◈", "All Picks", CalendarDayPageNotes.GRAM_PICKS)

    /**
     * Where the last gram was deliberately placed, if that place still exists on [date] — used to
     * ORDER the chooser, never to route a capture (see [inbox]). Falls back to the inbox rather
     * than to today's notes: with nothing remembered, the honest first offer is the pile you sort.
     */
    fun last(context: android.content.Context, date: LocalDate = LocalDate.now()): Destination {
        // THE RULING (Michael, 08-28): "things don't get sent to 'pickings' anymore — when you
        // save a pick it goes to All Picks AND it can be sent to a note." A remembered board or
        // band coerces to the inbox — the pref keys stay (iOS reads them; a downgrade still
        // reads them), the coercion is display-only, like the folded quick-note slots.
        return inbox(context, date)
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
     * The one chooser every "send this pick to…" runs through: the remembered destination first,
     * then the whole vocabulary, then "＋ New pickings…". Choosing is what teaches the memory —
     * the destination is already remembered by the time [onPick] runs.
     *
     * [fragment] is optional so MainActivity-hosted flows could reuse the list; when present, the
     * chooser itself goes through [ScreenFragment.showModal] (over an ink-capable surface an
     * unrouted popover leaves the hardware pen armed underneath and the stylus's taps go to the
     * ink — the freeze), and the new-board name dialog is guarded (a name being typed is work —
     * a stray touch outside must not throw it away).
     */
    fun choose(
        context: android.content.Context,
        date: LocalDate = LocalDate.now(),
        title: String = "Send this pick to",
        fragment: ScreenFragment? = null,
        /** Backed out with no destination (Cancel, back, a tap outside — at any step), once. A Pick
         *  sheet's adds that were on their way onto this card are kept by it (merge-addpick #4). */
        onNone: (() -> Unit)? = null,
        onPick: (Destination) -> Unit
    ) {
        if (onNone != null) {
            val once = java.util.concurrent.atomic.AtomicBoolean(false)
            val chosen: (Destination) -> Unit = { d -> if (once.compareAndSet(false, true)) onPick(d) }
            val none: () -> Unit = { if (once.compareAndSet(false, true)) onNone() }
            return chooseSteps(context, date, title, fragment, none, chosen)
        }
        chooseSteps(context, date, title, fragment, null, onPick)
    }

    /** Show [dialog] (through the fragment's modal door when there is one) and run [onGone] when it
     *  closes without [moved] having been set — the step was backed out of. */
    private fun showStep(fragment: ScreenFragment?, dialog: androidx.appcompat.app.AlertDialog,
                         moved: java.util.concurrent.atomic.AtomicBoolean, onGone: (() -> Unit)?) {
        val gone = { if (!moved.get()) onGone?.invoke() }
        when {
            fragment != null && onGone != null -> fragment.showModal(dialog) { gone() }
            fragment != null -> fragment.showModal(dialog)
            else -> { if (onGone != null) dialog.setOnDismissListener { gone() }; dialog.show() }
        }
    }

    private fun chooseSteps(
        context: android.content.Context,
        date: LocalDate,
        title: String,
        fragment: ScreenFragment?,
        onNone: (() -> Unit)?,
        onPick: (Destination) -> Unit
    ) {
        val moved = java.util.concurrent.atomic.AtomicBoolean(false)
        // TWO ACTS, NOT A DIRECTORY (Michael, 08-28): a saved pick goes to ALL PICKS, and it can
        // be SENT TO A NOTE — a board IS a note wearing pickings paper (boards-are-paper), so one
        // note picker covers today's note, boards, and named notes. The All Stars bands and
        // Synthesize left this chooser: the register is the starred rail's landing, not a filing
        // cabinet, and Synthesize has its own verbs.
        val rows = arrayOf("\u25c8  All Picks", "\u2712  To a note\u2026")
        val chooser = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle(title)
            .setItems(rows) { _, which ->
                moved.set(true)
                if (which == 0) {
                    val d = inbox(context, date)
                    remember(context, d)
                    onPick(d)
                } else chooseNote(context, date, fragment, onNone, onPick)
            }.setNegativeButton(android.R.string.cancel, null).create()
        showStep(fragment, chooser, moved, onNone)
    }

    /**
     * The note picker — every note a pick can land on, recents-first: today's note, today's
     * boards, then named notes across time (the Notebook's families; the global ink indexes plus
     * the board registry's filename walk — never a content scan), capped so the dialog stays a
     * dialog. "\uff0b New board\u2026" keeps the old mint. Choosing a note is a decision about
     * THIS pick — it deliberately teaches no memory ([inbox]'s rule: memory never routes).
     */
    private fun chooseNote(
        context: android.content.Context,
        date: LocalDate,
        fragment: ScreenFragment?,
        onNone: (() -> Unit)?,
        onPick: (Destination) -> Unit
    ) {
        val moved = java.util.concurrent.atomic.AtomicBoolean(false)
        val targets = mutableListOf(Destination("\u2712", "Today's note", NOTES_PAGE, date = date))
        for (b in PickingsStore.list(context, date)) {
            val name = if (b.key == PickingsStore.DEFAULT_KEY) "Today's Pickings"
            else b.name.ifBlank { "Pickings" }
            targets.add(Destination("\u275d", name, b.key, date = date))
        }
        val docs = LedgerDocuments
        val named = mutableListOf<Destination>()
        for (surface in listOf(docs.WRITE, docs.GRID, docs.JOT)) {
            runCatching { docs.forSurface(context, surface, date) }.getOrDefault(emptyList())
                .filter { it.named }
                .forEach { named.add(Destination("\u2712", it.title, it.key, date = it.date)) }
        }
        runCatching {
            PickingsStore.dates(context).sortedDescending().take(30).forEach { d ->
                if (d == date) return@forEach
                PickingsStore.listSaved(context, d).filter { it.name.isNotBlank() }.forEach {
                    named.add(Destination("\u275d", it.name, it.key, date = d))
                }
            }
        }
        targets.addAll(named.sortedByDescending { it.date }.take(12))
        val labels = (targets.map { "${it.glyph}  ${it.name}" } + "\uff0b  New pickings note\u2026").toTypedArray()
        val picker = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
            .setTitle("To a note")
            .setItems(labels) { _, which ->
                moved.set(true)
                if (which < targets.size) onPick(targets[which])
                else {
                    val created = java.util.concurrent.atomic.AtomicBoolean(false)
                    val input = android.widget.EditText(context).apply { hint = "Name (blank = today's)"; setSingleLine() }
                    // The share card shows this with no fragment (no showModal): guard it here (C9).
                    com.toolsboox.ui.share.OwnProcessText.guard(input)
                    val pad = (16 * context.resources.displayMetrics.density).toInt()
                    val box = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(pad, pad / 2, pad, 0); addView(input)
                    }
                    val dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(context))
                        .setTitle("New pickings note").setView(box)
                        .setPositiveButton("Create") { _, _ ->
                            created.set(true)
                            val page = PickingsStore.add(context, date, input.text.toString().trim())
                            onPick(Destination("\u275d", page.name, page.key, date = date))
                        }.setNegativeButton(android.R.string.cancel, null).create()
                    // A name being typed is work: an outside touch never closes it (showGuardedModal's rule).
                    if (fragment != null) dialog.setCanceledOnTouchOutside(false)
                    showStep(fragment, dialog, created, onNone)
                }
            }.setNegativeButton(android.R.string.cancel, null).create()
        showStep(fragment, picker, moved, onNone)
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

    /**
     * A laid-out face's slots as the STORED image sees them (add-to-pick pin §3: per-mille of the
     * face the element shows): through the fit to [MAX_DIM] and, with [treatment], the card's paper
     * and tape margins ([CardTreatment.cardInsets]) — so a tap maps the same on both apps whatever
     * each bakes around its face.
     */
    fun storedSlots(layout: PickFaceLayout.Layout, treatment: Boolean): List<com.toolsboox.da.FaceSlot> {
        val longest = maxOf(layout.w, layout.h)
        val r = if (longest > MAX_DIM) MAX_DIM.toFloat() / longest else 1f
        val fw = if (longest > MAX_DIM) (layout.w * r).toInt().coerceAtLeast(1) else layout.w
        val fh = if (longest > MAX_DIM) (layout.h * r).toInt().coerceAtLeast(1) else layout.h
        val sx = fw.toFloat() / layout.w; val sy = fh.toFloat() / layout.h
        val (side, overhang) = if (treatment) CardTreatment.cardInsets(fw) else 0f to 0f
        val outW = if (treatment) (fw + side * 2 + overhang * 2).toInt() else fw
        val outH = if (treatment) (fh + side * 2 + overhang).toInt() else fh
        val off = overhang + side
        fun pm(v: Float, total: Int) = if (total <= 0) 0 else Math.round(v * 1000f / total).coerceIn(0, 1000)
        return layout.slots.map { s ->
            val x = off + s.box.x * sx; val y = off + s.box.y * sy
            com.toolsboox.da.FaceSlot(s.kind.key, s.id,
                listOf(pm(x, outW), pm(y, outH), pm(s.box.w * sx, outW), pm(s.box.h * sy, outH)))
        }
    }

    /** A face fitted to [MAX_DIM], dressed ([CardTreatment]) and encoded — what a placed card
     *  stores — with its pixel size. Shared by [place] and [reface], so a card re-drawn in place
     *  wears exactly what a new one would. */
    fun encodeFace(bitmap: Bitmap, treatment: Boolean = true): Triple<String, Int, Int> {
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
        return Triple(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP), scaled.width, scaled.height)
    }

    /**
     * RE-DRAW A PLACED CARD IN PLACE — same element, same id, same spot, same width; a new face (and
     * its new height), a fresh timestamp so the day merge keeps this copy (the element union is
     * newer-timestamp-wins). Michael 2026-10-12: a note added to an article highlight "goes onto the
     * SAME pick/card — never a second card; edits never add cards". [media] makes it an A/V card
     * (a voice or video note on the highlight). False when the card is not on [date] any more
     * (removed, tombstoned) or [date] isn't on this device — and then nothing is added back. THROWS
     * [CalendarDayService.UnreadableDayException] when [date]'s page is there but won't read
     * ([CalendarDayService.loadToEdit]): the card may well be on it, so that is not "gone".
     */
    fun reface(
        service: CalendarDayService, root: File, date: LocalDate, elementId: String, face: Bitmap,
        media: MediaRef? = null, cardText: String? = null,
        /** The new face's tappable places ([storedSlots]); empty clears them, null leaves them. */
        faceSlots: List<com.toolsboox.da.FaceSlot>? = null,
        /** More to write on the card's day in the SAME locked save — the highlight's clips onto that
         *  day's A/V list (merge-addpick review #8). Runs only when the card is there. */
        alsoOnDay: ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)? = null,
    ): Boolean = DayLocks.withDay(date) {
        val day = service.loadToEdit(root, date) ?: return@withDay false
        val e = day.imageElements.firstOrNull { it.elementId.toString().equals(elementId, ignoreCase = true) }
            ?: return@withDay false
        val (base64, w, h) = encodeFace(face, treatment = true)
        e.data = base64
        e.dataRef = ""
        e.edgeBaked = true
        e.height = e.width * h / w.coerceAtLeast(1)
        e.timestamp = System.currentTimeMillis()
        if (cardText != null) e.cardText = cardText
        if (faceSlots != null) e.faceSlots = faceSlots.ifEmpty { null }
        if (media != null) {
            e.mediaKind = media.kind; e.attachmentId = media.attachmentId; e.mediaUrl = media.url
            e.durationMs = media.durationMs; e.mediaTitle = media.title; e.mediaDate = media.date
        }
        alsoOnDay?.invoke(day)
        service.save(root, date, day)
        true
    }

    /**
     * [reface] on the first of [days] that holds the card — its own day first, then the days a
     * clock that crossed midnight may have put it on ([ReadingEventEdit.candidateDays]). The day it
     * was re-drawn on, or null when none of them holds it. A neighbour that won't read is skipped;
     * when the card was on none of them and the FIRST day (the card's own) won't read, that day's
     * [CalendarDayService.UnreadableDayException] is thrown — the card is most likely there, so
     * that is a refusal for the caller to say, never "the card is gone".
     */
    fun refaceNear(
        service: CalendarDayService, root: File, days: List<LocalDate>, elementId: String, face: Bitmap,
        media: MediaRef? = null, cardText: String? = null,
        /** The new face's tappable places, as [reface] takes them (add-to-pick pin §3). */
        faceSlots: List<com.toolsboox.da.FaceSlot>? = null,
        /** More to write on the day the card is found on, in its save ([reface]). */
        alsoOnDay: ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)? = null,
    ): LocalDate? {
        var refused: CalendarDayService.UnreadableDayException? = null
        for ((i, d) in days.withIndex()) {
            try {
                if (reface(service, root, d, elementId, face, media, cardText, faceSlots, alsoOnDay)) return d
            } catch (e: CalendarDayService.UnreadableDayException) {
                if (i == 0) refused = e
            }
        }
        refused?.let { throw it }
        return null
    }

    fun place(
        service: CalendarDayService, root: File, bitmap: Bitmap, date: LocalDate, pageKey: String,
        sourceLink: String = "", sourceLabel: String = "", media: MediaRef? = null,
        treatment: Boolean = true, cardText: String = "", sourceFeed: String = "",
        intakeKind: String = "",
        /** The card's id, when the caller must point at it (an article highlight's one pick). */
        elementId: java.util.UUID? = null,
        /** More to write on the SAME day in the SAME locked save (the highlight's Log line). */
        alsoOnDay: ((com.toolsboox.plugin.calendar.da.v2.CalendarDay) -> Unit)? = null,
        /** Only onto [date] as it already stands when [date] isn't today ([ReadingEventEdit.loadForWrite]
         *  — an article highlight's card placed late on its own old day must never mint that day
         *  fresh, review 2026-10-12 #2). Throws when it can't, so a caller's runCatching sees it. */
        pastDayMustExist: Boolean = false,
        /** The face's tappable places ([storedSlots]) — add-to-pick pin §3. */
        faceSlots: List<com.toolsboox.da.FaceSlot>? = null,
    ) {
        val (base64, faceW, faceH) = encodeFace(bitmap, treatment)
        // All Stars sizes its own arrivals: the register's twelve-slot shelf IS the half-size
        // Michael asked for ("grams added to all stars should be 1/2 the size they currently are
        // when added so they don't have to overlap") — [CalendarDayPageIntake.arrivalFrame] fits
        // the card into the next slot of its band, and the page zooms for reading anyway. Every
        // other board keeps the natural card width; existing elements are untouched — this only
        // sizes what lands from now on, and a landed card is still yours to resize like any other.
        val isIntakeArrival = pageKey == CalendarDayPageIntake.INTAKE_PAGE && intakeKind.isNotBlank()
        // Gram Picks sizes its own arrivals, the way All Stars does ("grams are too big when
        // placed on gram picks", 08-15). At the boards' 0.42 width the three-across stagger
        // physically overflows the page — column three starts at x=1300 on a 1404 canvas — so
        // cards coerced back and piled up. 0.29 is the width at which three columns and their
        // margins actually fit (2·60 edge + 2·30 gap + 3·w ≤ 1404). Boards keep the natural
        // card width; existing elements are untouched; a landed card is still yours to resize.
        val gramPicksArrival = pageKey.substringBefore('#') == CalendarDayPageNotes.GRAM_PICKS
        val w = (CANVAS_W * (if (gramPicksArrival) 0.29f else 0.42f)).coerceAtMost(faceW.toFloat())
        val h = w * faceH / faceW
        // Load→mutate→save under the day lock: placements arrive from raw threads (placeAsync)
        // and race the open day page's per-pen-up save; interleaved writers drop each other's items.
        DayLocks.withDay(date) {
            // A day file that won't read throws UnreadableDayException here: every caller runs this
            // inside a runCatching whose failure branch says "couldn't" — never a fresh day + this
            // one card saved over the real file. A past day that isn't on this device is not minted
            // for a late card (pastDayMustExist) and throws too.
            val day = if (pastDayMustExist) ReadingEventEdit.loadForWriteOrThrow(service, root, date)
                ?: throw IllegalStateException("$date isn't on this device — not placed")
            else service.loadForWriteOrThrow(root, date, null, Locale.getDefault())
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
                elementId = elementId ?: java.util.UUID.randomUUID(),
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
                edgeBaked = treatment,
                faceSlots = faceSlots?.ifEmpty { null }))
            alsoOnDay?.invoke(day)
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
        GramDestinations.choose(ctx, date, "Send this pick to", fragment) { d ->
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
            val what = if (bitmaps.size > 1) "${bitmaps.size} picks" else "Placed"
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
                    }.apply {
                        // Kept off an ink pad (the compose screen's 🃏, 2026-10-11): above the
                        // anchor, never in the palm zone where a pen tap is a stroke.
                        fragment.snackAnchor?.let { setAnchorView(it) }
                    }.show()
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
