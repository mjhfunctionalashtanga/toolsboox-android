package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import java.time.LocalDate

/**
 * ❖ SYNTHESIZE THIS LIST → THE PAGE AND ITS PICK, as elements (design §4, §5) — pure, so the live
 * day page and the day file take ONE path and the JVM suite pins it.
 *
 * The page: the reply's stickies, then a card per cited article (ascending [n]), then one Sources
 * card — [SynthLanding]'s one placement (LEFT 90, TOP 140, GAP 24, FOOT 1832; the Sources card's
 * right-hand column starts at [RIGHT_TOP], below the voice header on both apps), ADOPTED with a
 * width, label and link per card, flowing onto `<key>#1`, `#2`… when a page is full. The pick: the ❖ breadcrumb sticky on Gram Picks
 * ("grampicks", All Picks), placed where [PickingsPlacement.place] places a Gram Picks arrival,
 * int for int, its face encoded by [PickingsPlacement.encodeFace].
 *
 * IDEMPOTENT, because a reply that came back before a kill is landed again on the next launch: a
 * page whose reply already stands ([SynthLanding.alreadyLanded]) gets no second set of cards, and a
 * pick whose link already stands on Gram Picks that day is not placed twice.
 */
object SynthListLanding {

    /** A card for the page: its face (PNG/base64) and height/width, what it is and where it goes. */
    data class Card(
        val data: String,
        val aspect: Float,
        val width: Float,
        val label: String,
        val link: String,
        val cardText: String,
        val sourceFeed: String = "",
        val edgeBaked: Boolean = false,
    )

    /** The ❖ breadcrumb for All Picks. [data] is already encoded; [faceW]/[faceH] its pixels. */
    data class Pick(
        val date: LocalDate,
        val data: String,
        val faceW: Int,
        val faceH: Int,
        val link: String,
        val label: String,
        val cardText: String,
    )

    /** Everything one landing writes: the page's [cards] on [date]/[key], and its [pick]. */
    data class Plan(
        val date: LocalDate,
        val key: String,
        val cards: List<Card>,
        /** The reply's chunks, as they stand on the stickies — how a re-landing knows it stood. */
        val replyChunks: List<String>,
        val pick: Pick?,
        /** The reply stickies' label — the voice that wrote them ([SynthVoice.label]; the
         *  Correspondent's on a page born from a list) — how [mint] finds them standing. */
        val replyLabel: String = SynthVoice.label(SynthVoice.CORRESPONDENT),
    )

    /** What [mint] made for one day: the new elements (in order), and where the page's first card went. */
    data class Minted(val elements: List<ImageElement>, val landed: SynthLanded?, val pageDone: Boolean, val pickDone: Boolean)

    /**
     * The page's right-hand column (x 744 of the 1404 canvas; the reply's column is 90–650): where
     * the Sources card stands on a fresh page, at the top — so on any device every article sent is
     * one tap from the page the run opens on, however far the reply flows. Placed last in the one
     * column it landed one to three pages on (review 2026-10-12) — a page turn or two away on the
     * B6, the iPad's ‹ n/N › pager, and further on an iPhone, which reaches a "#n" sub-page only
     * through the page's directory.
     */
    const val RIGHT = 744f

    /**
     * The right-hand column's top — below the voice header on BOTH apps (merge of synth-personas,
     * 2026-10-16). The header sits at the page's top: here in the top margin (y < 61), on iOS in the
     * Synthesize title bar, right-aligned to x 1288 from x 700 over y 112–176 (its tap rect padded to
     * 188) — so a Sources card at (744, [SynthLanding.TOP] 140) covered "❖ Spiral · answering next"
     * on the iPad and took its taps. One [SynthLanding.GAP] under the bar instead: 176 + 24 = 200.
     * The reply's column (90–650) keeps [SynthLanding.TOP]; the header never reaches it.
     */
    const val RIGHT_TOP = 200f

    /** Nothing on [key] yet — no ink, no typing, no card. */
    private fun isBlank(day: CalendarDay, key: String): Boolean =
        day.noteStrokes[key].isNullOrEmpty() && day.textElements.none { it.pageKey == key } &&
            day.imageElements.none { it.page == key }

    /**
     * Every card, one column, below what [key] already holds — [SynthLanding.place]'s loop — except
     * the Sources card on a blank page, which takes the right-hand column's top ([RIGHT], [RIGHT_TOP]) when
     * it fits the column. The cards are added in their given order either way.
     */
    fun place(day: CalendarDay, key: String, cards: List<Card>): SynthLanded {
        var page = key
        var y = (SynthLanding.contentBottom(day, page) + SynthLanding.BELOW).coerceAtLeast(SynthLanding.TOP)
        val side = if (!isBlank(day, key)) -1 else cards.indexOfFirst {
            SynthSources.role(it.label) == SynthSources.Role.Sources &&
                RIGHT_TOP + it.width * it.aspect <= SynthLanding.FOOT
        }
        var first: SynthLanded? = null
        for ((i, c) in cards.withIndex()) {
            val h = c.width * c.aspect
            if (i == side) {
                day.imageElements.add(ImageElement(
                    x = RIGHT, y = RIGHT_TOP, width = c.width, height = h, data = c.data, page = key,
                    cardText = c.cardText, sourceLink = c.link, sourceLabel = c.label, sourceFeed = c.sourceFeed,
                    edgeBaked = c.edgeBaked))
                continue
            }
            if (y + h > SynthLanding.FOOT && y > 200f) {   // page full → the page flows; never over ink
                page = SynthLanding.nextSubPage(day, page)
                y = SynthLanding.TOP
            }
            day.imageElements.add(ImageElement(
                x = SynthLanding.LEFT, y = y, width = c.width, height = h, data = c.data, page = page,
                cardText = c.cardText, sourceLink = c.link, sourceLabel = c.label, sourceFeed = c.sourceFeed,
                edgeBaked = c.edgeBaked))
            if (first == null) first = SynthLanded(page, SynthLanding.LEFT, y, c.width, h, page, 0)
            y += h + SynthLanding.GAP
        }
        val f0 = first
            ?: cards.getOrNull(side)?.let { SynthLanded(key, RIGHT, RIGHT_TOP, it.width, it.width * it.aspect, key, 0) }
            ?: SynthLanded(key, SynthLanding.LEFT, y, SynthLanding.CARD_W, 0f, key, 0)
        return f0.copy(lastKey = page, cards = cards.size)
    }

    /** Gram Picks' arrival geometry — [PickingsPlacement.place] for a "grampicks" card, int for int:
     *  0.29 of the canvas wide (never wider than the face), three across, from y 120. */
    fun pickElement(day: CalendarDay, pick: Pick): ImageElement {
        val canvasW = 1404f
        val canvasH = 1872f
        val w = (canvasW * 0.29f).coerceAtMost(pick.faceW.toFloat())
        val h = w * pick.faceH / pick.faceW.coerceAtLeast(1)
        val count = day.imageElements.count { it.page == CalendarDayPageNotes.GRAM_PICKS }
        val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (canvasW - w).coerceAtLeast(0f))
        val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (canvasH - h).coerceAtLeast(0f))
        return ImageElement(
            x = x, y = y, width = w, height = h, data = pick.data, page = CalendarDayPageNotes.GRAM_PICKS,
            sourceLink = pick.link, sourceLabel = pick.label, cardText = pick.cardText, edgeBaked = false)
    }

    /** The pick already stands on that day's Gram Picks (dedupe on its link). */
    fun hasPick(day: CalendarDay, link: String): Boolean =
        day.imageElements.any { it.page.substringBefore('#') == CalendarDayPageNotes.GRAM_PICKS && it.sourceLink == link }

    /**
     * What [plan] adds to [day] (the day file of [date]), WITHOUT touching it: the page's cards when
     * [date] is the page's day and its reply isn't standing yet, and the pick when [date] is the
     * pick's day and it isn't there yet. Add [Minted.elements] to the same day and save.
     */
    fun mint(day: CalendarDay, date: LocalDate, plan: Plan): Minted {
        val scratch = day.copy(imageElements = day.imageElements.toMutableList())
        val before = scratch.imageElements.size
        var landed: SynthLanded? = null
        var pageDone = false
        if (date == plan.date) {
            val faces = plan.replyChunks.map { SynthFace(it, "", 0f) }
            landed = SynthLanding.alreadyLanded(scratch, plan.key, faces, plan.replyLabel)
            if (landed == null) landed = place(scratch, plan.key, plan.cards)
            pageDone = true
        }
        var pickDone = false
        val pick = plan.pick
        if (pick != null && date == pick.date) {
            if (!hasPick(scratch, pick.link)) scratch.imageElements.add(pickElement(scratch, pick))
            pickDone = true
        }
        return Minted(scratch.imageElements.drop(before), landed, pageDone, pickDone)
    }

    /** The days a plan writes — the page's, and the pick's when it differs (a run across midnight). */
    fun days(plan: Plan): List<LocalDate> = listOfNotNull(plan.date, plan.pick?.date?.takeIf { it != plan.date })

    /**
     * THE DAY-FILE GUARD FOR A WHOLE PLAN, before anything is written: every day [plan] touches is
     * read, and the first that is there but won't read is THROWN
     * ([com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException]). A landing
     * across midnight used to write the page's day and only then meet the pick's refusal — the
     * page's cards on disk, the page not yet listed (2026-10-16 review, item 5; iOS checks both
     * days first too). A day that isn't there yet is fine: the writer's door mints it.
     */
    fun refuseUnreadable(
        service: com.toolsboox.plugin.calendar.fi.CalendarDayService, root: java.io.File, plan: Plan,
    ) {
        for (d in days(plan)) {
            val r = service.read(root, d)
            if (r is com.toolsboox.plugin.calendar.fi.CalendarDayService.DayRead.Unreadable)
                throw com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException(r.file, r.cause)
        }
    }

    // ── The Log's one row (design §5; iOS SynthListLog.fold) ────────────────────────────────

    /** One synthesis on one day's Log: the page [key] on [date] (where its door opens), the name
     *  its pick gave it ([pickName], the fallback when the index doesn't know the key yet), what it
     *  says, when it began and how many cards it folds. */
    data class LogRow(
        val key: String, val date: LocalDate, val pickName: String, val text: String,
        val millis: Long, val cards: Int,
    )

    /**
     * A day's cards with every `synthesize-*` page's cards — and any card linking to one, the All
     * Picks pick — folded into ONE row per page ([SynthSources.logFoldKey]). Nothing is removed
     * from the day; the Log just stops listing forty cards for one synthesis. [rest] keeps every
     * other card, in order, for its usual row.
     */
    data class LogFold(val rows: List<LogRow>, val rest: List<ImageElement>)

    fun logFold(elements: List<ImageElement>, dayDate: LocalDate?): LogFold {
        // One document per synthesis on both apps: a full page flows onto its own "#n" sub-pages
        // (iOS's "<name> · page N" continuation documents are gone, merge 2026-10-16), and a
        // sub-page's cards fold by their base key — so there is no second document to alias.
        val groups = LinkedHashMap<String, MutableList<ImageElement>>()
        val rest = mutableListOf<ImageElement>()
        for (img in elements) {
            val k = SynthSources.logFoldKey(img.page, img.sourceLink)
            if (k == null) rest.add(img) else groups.getOrPut(k) { mutableListOf() }.add(img)
        }
        val rows = groups.entries.toList().mapNotNull { (k, els) ->
            // The page's day: a card linking to it names it; a card standing on it is this day's.
            val linked = els.firstNotNullOfOrNull { e ->
                e.sourceLink.removePrefix("ledger://").takeIf { e.sourceLink.startsWith("ledger://") }
                    ?.takeIf { it.substringAfter('/', "").substringBefore('#') == k }
                    ?.let { runCatching { LocalDate.parse(it.substringBefore('/')) }.getOrNull() }
            }
            // No day to open it on (never on a dated day file): its cards keep their own rows.
            val date = linked ?: dayDate ?: run { rest.addAll(els); return@mapNotNull null }
            // The pick: a ❖ card OFF the run (the run's own reply stickies wear ❖ too).
            val pick = els.firstOrNull { it.page.substringBefore('#') != k && it.sourceLabel.startsWith("❖") }
            val reply = els.firstOrNull { SynthSources.role(it.sourceLabel, it.sourceLink, it.page) == SynthSources.Role.Reply }
            LogRow(k, date, pick?.sourceLabel?.removePrefix("❖")?.trim().orEmpty(),
                (pick?.cardText ?: reply?.cardText).orEmpty().trim(), els.minOf { it.timestamp }, els.size)
        }
        return LogFold(rows, rest)
    }
}
