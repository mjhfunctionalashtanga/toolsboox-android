package com.toolsboox.plugin.calendar.ot

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.Creator
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.michaelfilter.da.IntakePageData
import kotlin.math.abs

/**
 * The All Stars named note page ("intake"), part of the field-ledger swipe cycle.
 *
 * Five full-width bands stacked down the page — THE READ / THE WATCH / THE LISTEN / THE BOOKS /
 * THE MAIL — each a shelf holding just the grams OF ITS OWN KIND, and each exactly as tall as
 * that shelf. A gram lands here when you star a feed item (★ → All Stars) or file an email; it
 * carries its band in [ImageElement.intakeKind] (the Mail band keeps the legacy "educate" storage
 * key). The grams ARE the content — no typing. Moving a gram graduates it into a Pickings page
 * (where its object accumulates); a checkmark on its corner leaves a link back to that page.
 * Aesthetic matches Pickings: mono headers, thin rules, plenty of white.
 *
 * ALL STARS IS A REGISTER, NOT A SORTING BOARD.
 *
 * This was a 2x2 of quarters you filed things into — "Star Sort". Michael: "Instead of being an
 * inbox, it's a list of everything that has gotten a star that day, not an inbox, a reference."
 * A list is what it now looks like: bands you scan, one per kind. The working surface where grams
 * are actually written on and rearranged is Gram Picks — see [CalendarDayPageNotes.GRAM_PICKS].
 *
 * WHY THE BANDS ARE SIZED TO THEIR CONTENT, NOT FIXED. The fixed five-by-300px layout left the
 * 40px band label about 42px of vertical room when it needs about 61, so every box's bottom rule
 * struck through the NEXT band's name — and a quiet day was four-fifths dashed void. Now each
 * band is exactly as tall as the shelf of cards it holds, an empty band is a compact labeled
 * strip, and the inter-band pitch reserves the label's full height BY CONSTRUCTION, so no box
 * can touch the next name at any content volume.
 *
 * The geometry below mirrors iOS's IntakeLayout constant for constant, because a gram's x/y are
 * wire data and both devices are drawing the same 1404×1872 sheet. This matters past looks: the
 * settle pass ([settleAllStars]) files every card onto its own kind's shelf, and if the two forks
 * settled the same cards to different shelves, every sync would ping-pong positions — each device
 * "correcting" the other's geometry forever. Copied verbatim from IntakePageTemplate.swift:
 *
 * ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 * │ ALL STARS BAND GEOMETRY — CROSS-FORK REFERENCE                                          │
 * │ iOS is canon for these numbers; the Android twin (CalendarDayPageIntake) copies them.   │
 * │                                                                                         │
 * │ Design space: the fixed 1404 × 1872 sheet, shared with the Boox, points 1:1.            │
 * │ Page title:  "ALL STARS · <window>", 40pt mono bold, baseline y = 72, x = 40.           │
 * │ Bands, in reading order (kind → label):                                                 │
 * │     read → THE READ, watch → THE WATCH, listen → THE LISTEN,                            │
 * │     books → THE BOOKS, educate → EMAIL (legacy storage key kept).                       │
 * │     THE BOOKS shipped on iOS first — Android's store knows read/watch/listen/educate    │
 * │     and grows the books band when it copies this table.                                 │
 * │ Band box: x = 40, width = 1324 (right edge 1364), 2pt rule.                             │
 * │ First box top: y = 156.                                                                 │
 * │ Band label: 40pt mono bold, its BOTTOM 14pt above its box top (so it spans roughly      │
 * │     [boxTop−61, boxTop−14] — a 40pt mono line is ~47pt tall).                           │
 * │ Inter-band pitch: nextBoxTop = prevBoxBottom + 74. That 74 is the label's 47, plus      │
 * │     13 clear above it and 14 clear below — the label can NEVER collide with either box. │
 * │ Box inner padding: 16 all round.                                                        │
 * │ Slot grid: 12 columns per row; slot gap 8; slot width = (1324 − 32 − 11·8)/12 ≈ 100.3;  │
 * │     slot height 118; row gap 8. Cards aspect-fit centred in their slot inset by 4.      │
 * │ Band height by count n (rows r = ceil(n/12), capped at 2):                              │
 * │     n = 0  → 56   (compact strip: dashed 1.5pt rule, hint centred at 26pt)              │
 * │     r = 1  → 150  (16 + 118 + 16)                                                       │
 * │     r = 2  → 276  (16 + 118 + 8 + 118 + 16)                                             │
 * │ Overflow (n > 24): extras pile on the last slot (row 2, col 12), each successive card   │
 * │     26pt further LEFT, clamped at the box's inner padding — the pile fans along the     │
 * │     bottom row and never leaves its band.                                               │
 * │ Settle margin: every card is kept inside its band's box inset by 8 (scaled down first   │
 * │     if it cannot fit, then translated in).                                              │
 * │ ✓-corner: 34 × 34, 8pt in from the gram's own top-right.                                │
 * │ Worst case: 156 + 5·276 + 4·74 = 1832 ≤ 1872 — five full bands still fit the sheet.     │
 * └─────────────────────────────────────────────────────────────────────────────────────────┘
 *
 * One label differs from the table: on this fork the educate band has always printed THE MAIL
 * ("The Read, The Watch, The Listen, The Mail" — Michael's own naming), and iOS is adopting THE
 * MAIL too. The label costs a string; the "educate" key underneath it is everyone's filed history
 * and does not move.
 */
class CalendarDayPageIntake : Creator {

    companion object {

        const val INTAKE_PAGE = "intake"

        // The canon numbers, straight from the table above.
        private const val left = 40f
        private const val right = 1364f
        private const val bandFirstTop = 156f
        private const val pageTitleBaseline = 72f
        // The vertical room between one band's box and the next band's box — the whole reason
        // this layout exists. The 40px label lives entirely inside this pitch (47px of text, 13
        // above, 14 below), so a box bottom striking through the next label is impossible by
        // construction.
        private const val labelPitch = 74f
        private const val labelGap = 14f
        private const val cellPad = 16f   // inner padding of a band before its shelf
        private const val slotCols = 12
        private const val slotGap = 8f
        private const val slotH = 118f
        // A band grows a second row when its first fills, and stops there: two full rows of
        // twelve is the most height a band may claim, because five bands at that maximum are what
        // the 1872px sheet was budgeted against. Past 24 cards the extras pile on the last slot.
        private const val maxRows = 2
        // An empty band is a compact strip — a labeled row of the register awaiting its first
        // star, not a void the size of a full shelf.
        private const val emptyBandHeight = 56f
        // Every card is kept inside its band's box inset by this (see [settledFrame]).
        private const val settleInset = 8f
        // How far each overflow card in the pile peeks out from under the one on top of it.
        private const val STACK_PEEK = 26f

        private val slotW = (right - left - 2 * cellPad - (slotCols - 1) * slotGap) / slotCols

        // High-quality bitmap paint for gram thumbnails — filtered + dithered so a card downscaled
        // into its slot stays smooth and legible, and holds up when the page is zoomed to read it.
        private val gramPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true; isDither = true }

        // The ✓-corner target on each gram: tap it to graduate the gram into its own Pickings page.
        // Once graduated it wears the check and the whole gram becomes a link into that board.
        private const val cornerSize = 34f
        private val cornerFill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        private val cornerBorder = Paint().apply {
            color = Color.argb(200, 0, 0, 0); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true
        }
        private val checkPaint = TextPaint().apply {
            color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = cornerSize * 0.82f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); isAntiAlias = true
        }

        /** One kind of star: its storage key and its printed name. This list is the register's
         *  vocabulary; the rectangles come from [panels], which needs to know what the day holds. */
        data class IntakeBand(val kindKey: String, val title: String)

        /**
         * The five kinds of star, in reading order down the page. BOOKS is the one Michael named
         * as missing ("the only Star type missing is books on the page"); THE MAIL keeps its
         * legacy "educate" storage key so every gram already filed there still lands in its band.
         */
        val kinds = listOf(
            IntakeBand("read", "THE READ"),
            IntakeBand("watch", "THE WATCH"),
            IntakeBand("listen", "THE LISTEN"),
            IntakeBand("books", "THE BOOKS"),
            IntakeBand("educate", "THE MAIL")
        )

        /** One band of All Stars with its resolved place on the page and the count that sized it. */
        data class IntakePanel(val kindKey: String, val title: String, val rect: RectF, val count: Int)

        /** A kind the register recognises, or the first band (read) — the same normalisation the
         *  settle pass writes back, so counting and settling can never disagree about membership. */
        private fun normalKind(kind: String): String =
            if (kinds.any { it.kindKey == kind }) kind else kinds.first().kindKey

        /** Whether an element is a star of this page at all — a face may be inline ([ImageElement.data])
         *  or by media ref ([ImageElement.dataRef]); both are stars. */
        private fun isStar(el: ImageElement): Boolean =
            el.page == INTAKE_PAGE && !el.decorative && (el.data.isNotBlank() || el.dataRef.isNotBlank())

        /** The day's tombstoned element ids, lowercased — the authority on what may be drawn,
         *  counted or settled. A deleted star can still be sitting in the file (pruned only on the
         *  next save), and it must not size a shelf it no longer stands on. */
        private fun deadOf(calendarDay: CalendarDay?): HashSet<String> {
            val dead = HashSet<String>()
            calendarDay?.deletedElementIds?.forEach { dead.add(it.lowercase()) }
            calendarDay?.deletedItemIds?.forEach { dead.add(it.lowercase()) }
            return dead
        }

        /**
         * How many live stars each band holds — the ONE count everything derives geometry from,
         * so the drawn band heights, an arrival's landing slot and the settle pass always agree.
         * Unknown kinds count toward read, matching [settleAllStars]'s normalisation.
         */
        fun counts(calendarDay: CalendarDay?): Map<String, Int> {
            val dead = deadOf(calendarDay)
            val c = HashMap<String, Int>()
            for (band in kinds) c[band.kindKey] = 0
            calendarDay?.imageElements?.forEach { el ->
                if (!isStar(el)) return@forEach
                if (el.elementId.toString().lowercase() in dead) return@forEach
                val k = normalKind(el.intakeKind)
                c[k] = (c[k] ?: 0) + 1
            }
            return c
        }

        /** How many rows a band of [count] cards shelves (0 for an empty strip, capped at [maxRows]). */
        private fun rowsFor(count: Int): Int =
            if (count <= 0) 0 else minOf((count + slotCols - 1) / slotCols, maxRows)

        /** A band's box height for [count] cards — the strip, one row, or two. */
        private fun boxHeight(count: Int): Float {
            val r = rowsFor(count)
            if (r == 0) return emptyBandHeight
            return 2 * cellPad + r * slotH + (r - 1) * slotGap
        }

        /**
         * The day's resolved geometry: each band's box, stacked down the page with the label
         * pitch between them. Deterministic from the counts alone, which is what lets this fork
         * compute the identical page iOS computes from the identical day file.
         */
        fun panels(counts: Map<String, Int>): List<IntakePanel> {
            var y = bandFirstTop
            val out = ArrayList<IntakePanel>(kinds.size)
            for (band in kinds) {
                val n = counts[band.kindKey] ?: 0
                val h = boxHeight(n)
                out.add(IntakePanel(band.kindKey, band.title, RectF(left, y, right, y + h), n))
                y += h + labelPitch
            }
            return out
        }

        /** The day's resolved geometry straight from its file. */
        fun panels(calendarDay: CalendarDay?): List<IntakePanel> = panels(counts(calendarDay))

        // The geometry as LAST DRAWN — the register's boxes move when a band above grows, so a
        // hit-test must resolve against the page actually on screen, never a fresh resolution
        // that a not-yet-repainted change could have shifted (the recorded-at-draw discipline
        // [grams] already keeps for the cards).
        @Volatile
        private var drawnPanels: List<IntakePanel> = emptyList()

        /** The band boxes as last drawn — or, before any draw, the all-strips resting geometry. */
        fun panelsNow(): List<IntakePanel> = drawnPanels.ifEmpty { panels(emptyMap()) }

        /** The panel a canvas-space point falls in, or null (used to file a fresh gram by band). */
        fun panelAt(x: Float, y: Float): IntakePanel? = panelsNow().firstOrNull { it.rect.contains(x, y) }

        /**
         * The design-space slot rectangle for the card at [slot] within a band's box: twelve to a
         * row, two rows at most. Past the last slot of the last row the pile begins — each further
         * card sits [STACK_PEEK] to the LEFT of the one before, clamped at the box's inner
         * padding, so the overflow fans along the bottom row and never leaves its band.
         */
        fun slotRect(box: RectF, slot: Int): RectF {
            val capacity = slotCols * maxRows
            val i = slot.coerceAtMost(capacity - 1)
            val col = i % slotCols
            val row = i / slotCols
            var l = box.left + cellPad + col * (slotW + slotGap)
            val t = box.top + cellPad + row * (slotH + slotGap)
            if (slot >= capacity) {
                val shift = minOf((slot - capacity + 1) * STACK_PEEK, l - (box.left + cellPad))
                l -= shift
            }
            return RectF(l, t, l + slotW, t + slotH)
        }

        /**
         * Where a NEW star of [kindKey] should land, given the day as it stands BEFORE the
         * arrival: the next slot in its band's shelf, with the card aspect-fit into it — 1:1 with
         * iOS's IntakeLayout.arrivalFrame, so the same day file draws the same picture on both
         * devices. The geometry is resolved with the arrival already counted, because the band
         * must have grown its row for the card that is landing in it.
         *
         * Stars arrive organised — you never file one — but they are ordinary elements from the
         * moment they land, so this only chooses a starting point. Drag one and it stays where you
         * put it (within its band; [settleAllStars] keeps every card inside its own shelf). The
         * half-size cards Michael asked for ("grams added to all stars should be 1/2 the size
         * they currently are") ARE the slot size now — one card to a slot, no doubling-up — and
         * the page zooms for reading anyway.
         *
         * [cardW]/[cardH] give the card's shape; the returned rect carries the fitted size as
         * well as the position, so the caller places exactly what the band laid out. Returns null
         * for an unknown kind, so a caller with no band falls back to plain placement.
         */
        fun arrivalFrame(kindKey: String, calendarDay: CalendarDay, cardW: Float, cardH: Float): RectF? {
            if (kinds.none { it.kindKey == kindKey }) return null
            val c = counts(calendarDay).toMutableMap()
            val taken = c[kindKey] ?: 0
            c[kindKey] = taken + 1
            val box = panels(c).firstOrNull { it.kindKey == kindKey }?.rect ?: return null
            val slot = slotRect(box, taken)
            slot.inset(4f, 4f)
            return fitted(cardW, cardH, slot)
        }

        /**
         * A card kept inside its own band: scaled down if the shelf cannot hold it at its stored
         * size (natural-width arrivals from the fixed-band era), then translated the shortest
         * distance in. A card already on its shelf comes back untouched, which is what keeps a
         * hand-dragged arrangement yours.
         */
        fun settledFrame(frame: RectF, box: RectF): RectF {
            val home = RectF(box).apply { inset(settleInset, settleInset) }
            val r = RectF(frame)
            if (r.width() > home.width() || r.height() > home.height()) {
                val s = minOf(
                    home.width() / r.width().coerceAtLeast(1f),
                    home.height() / r.height().coerceAtLeast(1f)
                )
                r.right = r.left + r.width() * s
                r.bottom = r.top + r.height() * s
            }
            if (r.left < home.left) r.offset(home.left - r.left, 0f)
            if (r.right > home.right) r.offset(home.right - r.right, 0f)
            if (r.top < home.top) r.offset(0f, home.top - r.top)
            if (r.bottom > home.bottom) r.offset(0f, home.bottom - r.bottom)
            return r
        }

        /**
         * KIND OWNS BAND MEMBERSHIP — the settle pass, mirroring iOS `settleAllStars`.
         *
         * The cost of content-sized bands is that a band's TOP moves when a band above it grows,
         * and these cards are live, draggable elements whose x/y live in the day JSON. So the
         * band a gram is shown in is the band its kind names — never the band that happens to
         * have slid underneath its stored coordinates. On load, this resolves the day's geometry
         * and pulls every card inside its OWN kind's band (scaling a card too big for the shelf,
         * translating one the geometry moved out from under); it never re-files by position,
         * because at load time a position can be stale. Unknown kinds normalise to read FIRST,
         * because the kinds decide the geometry: the band heights resolved below must be computed
         * from the same kinds the cards will settle into.
         *
         * Both forks must run this identically — same numbers, same rules — because a settled
         * x/y is wire data: if the two forks settled the same cards to different shelves, every
         * sync would ping-pong positions. Returns whether anything moved, so the caller knows a
         * save has something to keep.
         */
        fun settleAllStars(calendarDay: CalendarDay): Boolean {
            var changed = false
            val dead = deadOf(calendarDay)
            val known = kinds.map { it.kindKey }.toHashSet()
            for (el in calendarDay.imageElements) {
                if (el.page != INTAKE_PAGE || el.decorative) continue
                if (el.intakeKind !in known) { el.intakeKind = kinds.first().kindKey; changed = true }
            }
            val boxes = panels(counts(calendarDay)).associate { it.kindKey to it.rect }
            for (el in calendarDay.imageElements) {
                if (!isStar(el)) continue
                if (el.elementId.toString().lowercase() in dead) continue
                val box = boxes[el.intakeKind] ?: continue
                val f = RectF(el.x, el.y, el.x + el.width, el.y + el.height)
                val s = settledFrame(f, box)
                if (abs(s.left - f.left) > 0.5f || abs(s.top - f.top) > 0.5f ||
                    abs(s.width() - f.width()) > 0.5f || abs(s.height() - f.height()) > 0.5f
                ) {
                    el.x = s.left; el.y = s.top; el.width = s.width(); el.height = s.height()
                    changed = true
                }
            }
            return changed
        }

        /**
         * Dragging still files, at the moment you drop — iOS `fileAllStarsDrop`'s twin, run when
         * the element layer commits a change. A card whose centre was left inside ANOTHER band's
         * box adopts that band's kind, then settles into it: "Correcting a mis-detected kind
         * still works — drag it across and it stays." The drop is the ONE moment position is
         * evidence of intent (the geometry on screen is exactly the geometry the drop was aimed
         * at — a drag changes no kinds, so re-resolving from the current elements reproduces it);
         * a drop in the gutter between bands adopts nothing and the settle pulls the card home.
         */
        fun fileAllStarsDrop(calendarDay: CalendarDay): Boolean {
            var changed = false
            val dead = deadOf(calendarDay)
            val resolved = panels(counts(calendarDay))
            for (el in calendarDay.imageElements) {
                if (!isStar(el)) continue
                if (el.elementId.toString().lowercase() in dead) continue
                val hit = resolved.firstOrNull {
                    it.rect.contains(el.x + el.width / 2f, el.y + el.height / 2f)
                } ?: continue
                if (hit.kindKey != el.intakeKind) { el.intakeKind = hit.kindKey; changed = true }
            }
            return settleAllStars(calendarDay) || changed
        }

        /** An aspect ratio fit and centred inside a rectangle. */
        private fun fitted(cardW: Float, cardH: Float, rect: RectF): RectF {
            val aspect = (cardW / cardH.coerceAtLeast(1f)).coerceAtLeast(0.01f)
            var w = rect.width()
            var h = w / aspect
            if (h > rect.height()) { h = rect.height(); w = h * aspect }
            val x = rect.centerX() - w / 2f
            val y = rect.centerY() - h / 2f
            return RectF(x, y, x + w, y + h)
        }

        // ---- gram hit-testing ---------------------------------------------------------------

        /** A drawn gram and where it landed, so a tap can move / checkmark / open it — recorded at
         *  draw time, the PickingsCover/DayEventHits discipline. */
        data class IntakeGram(
            val elementId: String, val page: String, val kindKey: String,
            val sourceLink: String, val rect: RectF, val corner: RectF,
            val graduatedTo: String
        )

        @Volatile
        private var grams: List<IntakeGram> = emptyList()

        /** The gram under a canvas-space point, or null. LAST match wins: the list is built in
         *  draw order, so where cards overlap (a full band piles its overflow) the one on top —
         *  drawn last — is the one a tap visibly lands on. */
        fun gramAt(x: Float, y: Float): IntakeGram? = grams.lastOrNull { it.rect.contains(x, y) }

        /** The gram whose ✓-corner a canvas-space point falls in, or null (checked before [gramAt]). */
        fun cornerAt(x: Float, y: Float): IntakeGram? = grams.lastOrNull { it.corner.contains(x, y) }

        /** The ✓-corner box for a gram, hung 8px in from the TOP-RIGHT OF THE GRAM ITSELF — the
         *  card is the thing and the target follows the card, wherever it has been dragged. */
        private fun cornerRect(gram: RectF): RectF = RectF(
            gram.right - cornerSize - 8f, gram.top + 8f, gram.right - 8f, gram.top + 8f + cornerSize
        )

        /** The grams of one kind among [elements], newest first. NOT capped — past a band's 24
         *  slots the band piles them (see [slotRect]), because dropping the overflow would
         *  silently lose something you starred, and a register that omits entries is not a
         *  register. */
        private fun gramsFor(kindKey: String, elements: List<ImageElement>?): List<ImageElement> =
            elements
                ?.filter { isStar(it) && normalKind(it.intakeKind) == kindKey }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()

        /**
         * Draw the All Stars register: five labeled bands, each exactly as tall as the shelf of
         * its own grams.
         *
         * @param canvas the canvas
         * @param intakeData retained for call-site compatibility; unused now that the page is grams
         *        only (typed capture is gone).
         * @param calendarDay the loaded day whose grams size and fill the bands (null — the
         *        notes-preview fallback — draws five empty strips and clears the hit-test list).
         * @param scopedGrams when non-null, past stars to print IN ADDITION to [calendarDay]'s own —
         *   the almanac header's period filter collects these across a week / month / quarter /
         *   year, so the page can answer "everything I starred this month" and not only today.
         *   Null keeps the single-day reading, which costs one already-loaded file.
         * @param scopeLabel what the header says after "ALL STARS ·", e.g. "TODAY" or "WEEK 30".
         */
        @Suppress("UNUSED_PARAMETER")
        fun drawPage(
            canvas: Canvas, intakeData: IntakePageData, calendarDay: CalendarDay? = null,
            scopedGrams: List<ImageElement>? = null, scopeLabel: String = "TODAY",
            // Resolves media-store `dataRef` faces in the printed wider-window record; null
            // (the notes-preview fallback) draws inline faces only.
            context: android.content.Context? = null
        ) {
            // Today's own stars are live elements, so they must not ALSO be printed as part of the
            // wider-window record — identity by timestamp, which is what the day JSON carries.
            val todayStamps = calendarDay?.imageElements
                ?.filter { it.page == INTAKE_PAGE }?.map { it.timestamp }?.toHashSet() ?: HashSet()
            // The tombstone lists are the authority on what may be drawn — the day fragment's own
            // rule, honoured here too: a deleted star can still be sitting in the file (pruned
            // only on the next save), and it must neither hold its band's empty hint off nor keep
            // answering taps from the hit-list after its face has left the screen. [counts]
            // honours the same list, so a dead star doesn't size a shelf either.
            val dead = deadOf(calendarDay)
            canvas.drawRect(0f, 0f, 1404f, 1872f, Creator.fillWhite)

            val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val headerPaint = TextPaint().apply {
                color = Color.BLACK; textAlign = Paint.Align.LEFT; textSize = 40f; typeface = monoBold; isAntiAlias = true
            }
            val panelBorder = Paint().apply {
                color = Color.argb(170, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val cellBorder = Paint().apply {
                color = Color.argb(150, 0, 0, 0); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true
            }
            val dashed = Paint().apply {
                color = Color.argb(90, 0, 0, 0); strokeWidth = 1.5f; style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f); isAntiAlias = true
            }
            val hintPaint = TextPaint().apply {
                color = Color.argb(120, 0, 0, 0); textAlign = Paint.Align.CENTER; textSize = 26f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC); isAntiAlias = true
            }

            // The register names its own window. Without this the page looks identical whether it
            // holds today's stars or the whole month's, which is the one thing a filter must never
            // leave ambiguous.
            canvas.drawText("ALL STARS · $scopeLabel", left, pageTitleBaseline, headerPaint)

            // THE GEOMETRY, resolved once from what the page will actually show. The settle pass
            // and every arrival compute against the day's own counts, so the single-day reading
            // here is EXACTLY the geometry the live cards were filed to. A wider window adds the
            // printed past stars to each band's count so the record has shelf room — a read-only
            // view no settle ever runs against, so nothing about it reaches the wire.
            val liveCounts = counts(calendarDay)
            val pastByKind = HashMap<String, List<ImageElement>>()
            if (scopedGrams != null) {
                for (band in kinds) {
                    pastByKind[band.kindKey] =
                        gramsFor(band.kindKey, scopedGrams).filter { it.timestamp !in todayStamps }
                }
            }
            val combined = kinds.associate { band ->
                band.kindKey to (liveCounts[band.kindKey] ?: 0) + (pastByKind[band.kindKey]?.size ?: 0)
            }
            val resolved = panels(combined)
            drawnPanels = resolved

            // THE BANDS THEMSELVES — title above the box, wholly inside the label pitch (the
            // pitch exists so this text can never collide with a rule), then the box itself. This
            // is the furniture iOS has and this page lost when the painted copies of the grams
            // went (Michael: "Android doesn't have the bands that iOS has for The Read, The
            // Watch, The Listen, The Mail"): without it the register was a bare title over
            // floating cards, and the geometry that files every arrival was invisible. AN EMPTY
            // BAND IS A COMPACT STRIP, NOT A VOID: it still reads as a row of the register —
            // dashed, with its own hint line saying what fills it — but at strip height it costs
            // the page almost nothing. Five of those read as a printed form waiting to be filled
            // in; five bare rectangles read as a mistake. Drawn before the printed wider-window
            // record so past stars sit on the furniture, not under it.
            for (panel in resolved) {
                val r = panel.rect
                canvas.drawText(panel.title, r.left, r.top - labelGap, headerPaint)
                if (panel.count == 0) {
                    canvas.drawRect(r, dashed)
                    canvas.drawText(emptyHint(panel.kindKey), r.centerX(), r.centerY() + 9f, hintPaint)
                } else {
                    canvas.drawRect(r, panelBorder)
                }
            }

            // TODAY'S STARS ARE NOT PAINTED HERE — they are real ImageElements and the element
            // layer draws them, exactly as it does on a Pickings board.
            //
            // They used to be BOTH. CalendarDayFragment hands every element on this page to
            // setImageElements (imgPageKey = "intake"), so each gram was already live and movable at
            // its own x/y — and this loop painted a second copy of it into a band cell. Two copies,
            // in different places. The painted one is the one the eye goes to and it cannot move,
            // because it is part of the template picture: that, and not a missing feature, is what
            // "Star Sort's grams are not selectable/movable" was. Michael: "all stars should be
            // manipulatable just like pickings." They now are, because the photograph of them is
            // gone and only the thing itself remains.
            //
            // The bands stay as printed guides, and [arrivalFrame] places each new arrival inside
            // the one for its kind — so stars still land organised, then behave like any other
            // object.
            //
            // The ONE thing still painted is a WIDER WINDOW: when the almanac filter reaches past
            // today, other days' stars are drawn here as a printed record. They cannot be live
            // elements of this page — they belong to their own days — so a printed record behind
            // today's live objects is the honest rendering, and it needs no mode: your own stars are
            // always the real ones. The record fills the slots AFTER today's — today's live cards
            // were filed to the first ones — oldest painted first so the newest sits on top of any
            // pile.
            val recorded = mutableListOf<IntakeGram>()
            if (scopedGrams != null) {
                for (panel in resolved) {
                    val past = pastByKind[panel.kindKey] ?: continue
                    if (past.isEmpty()) continue
                    val base = liveCounts[panel.kindKey] ?: 0
                    past.asReversed().forEachIndexed { rev, img ->
                        val i = past.size - 1 - rev
                        val slot = slotRect(panel.rect, base + i)
                        slot.inset(4f, 4f)
                        drawGramInCell(canvas, img, slot, context)
                        canvas.drawRect(slot, cellBorder)
                        // Recorded where it was DRAWN — the PickingsCover/DayEventHits discipline.
                        // A printed past star still answers a hold ("Bring in a picking" keeps its
                        // band) and a graduated one wears its ✓ in the record.
                        recorded.add(IntakeGram(
                            elementId = img.elementId.toString().lowercase(),
                            page = img.page, kindKey = panel.kindKey,
                            sourceLink = img.sourceLink, rect = RectF(slot),
                            corner = cornerRect(slot),
                            graduatedTo = img.graduatedTo
                        ))
                        if (img.graduatedTo.isNotBlank()) {
                            drawCorner(canvas, recorded.last().corner, checked = true)
                        }
                    }
                }
            }
            // TODAY'S LIVE STARS are drawn by the element layer, never by this template — but the
            // hit-test list is this template's to keep, and shipping it empty is why the All Stars
            // tap-to-open (1.06.14) and the hold menu's gram half never fired: [gramAt] answered
            // null for every tap that visibly landed on a card. Each live element's own x/y/w/h is
            // its rect (the same numbers the element layer draws it at, re-recorded on every
            // [redrawIntakePage] so a drag re-teaches the map on the next repaint). No ✓ box is
            // painted for a live card — template ink under an opaque element is invisible — and
            // none is needed: a graduated gram routes its whole face to its board on tap.
            calendarDay?.imageElements?.forEach { img ->
                if (!isStar(img)) return@forEach
                if (img.elementId.toString().lowercase() in dead) return@forEach
                val rect = RectF(img.x, img.y, img.x + img.width, img.y + img.height)
                recorded.add(IntakeGram(
                    elementId = img.elementId.toString().lowercase(),
                    page = img.page, kindKey = normalKind(img.intakeKind),
                    sourceLink = img.sourceLink, rect = rect,
                    corner = cornerRect(rect),
                    graduatedTo = img.graduatedTo
                ))
            }
            grams = recorded
        }

        /** The corner target: a small white box, checked with ✓ once the gram has graduated. */
        private fun drawCorner(canvas: Canvas, box: RectF, checked: Boolean) {
            canvas.drawRoundRect(box, 6f, 6f, cornerFill)
            canvas.drawRoundRect(box, 6f, 6f, cornerBorder)
            if (checked) canvas.drawText("✓", box.centerX(), box.centerY() + box.height() * 0.30f, checkPaint)
        }

        private fun emptyHint(kindKey: String): String = when (kindKey) {
            "read" -> "star Reads to fill this"
            "watch" -> "star Watches to fill this"
            "listen" -> "star Listens to fill this"
            "books" -> "star Books to fill this"
            else -> "filed emails land here"
        }

        /**
         * Decode SMALL and center-crop the gram into its slot — bounds first, then an inSampleSize
         * near slot size, so a band's worth of full-page base64 decodes don't drag the page turn.
         * One decode per slot, recycled as soon as it's on the canvas (the PickingsCover rule).
         */
        private fun drawGramInCell(
            canvas: Canvas, img: ImageElement, cell: RectF, context: android.content.Context? = null
        ) {
            runCatching {
                val bytes = if (context != null)
                    com.toolsboox.ot.LedgerMedia.resolveBytes(context, img.data, img.dataRef) ?: return@runCatching
                else
                    Base64.decode(img.data, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                // Decode with 2× headroom over the slot so zooming the page still reads crisply —
                // the cards are intentionally small at 1× (twelve to a row) and meant to be zoomed.
                val target = maxOf(cell.width(), cell.height()) * 2f
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching
                // Center-crop to the slot's aspect so a card reads as a card, not a sliver.
                val cellAspect = cell.width() / cell.height()
                val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                val src = if (bmpAspect > cellAspect) {
                    val w = (bmp.height * cellAspect).toInt().coerceIn(1, bmp.width)
                    val x0 = (bmp.width - w) / 2
                    Rect(x0, 0, x0 + w, bmp.height)
                } else {
                    val h = (bmp.width / cellAspect).toInt().coerceIn(1, bmp.height)
                    val y0 = (bmp.height - h) / 2
                    Rect(0, y0, bmp.width, y0 + h)
                }
                canvas.drawBitmap(bmp, src, cell, gramPaint)
                bmp.recycle()
            }
        }
    }
}
