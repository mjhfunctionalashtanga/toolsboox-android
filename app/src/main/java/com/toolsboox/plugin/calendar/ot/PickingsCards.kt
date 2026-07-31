package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.RectF
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * WHERE THE CARDS ARE — the occurrence store for placed grams, and the thing the Ledger Directory
 * was missing when it shipped with tags as the only addressable item on a page.
 *
 * The directory's own comment named the gap and the reason for it: a Pickings board's cards "live as
 * image elements inside the day JSON, so listing them means decoding that file — the one thing every
 * other query in this file streams, samples or indexes its way around, and never on the path of
 * opening a menu. When cards get an index of their own they can join this list unchanged." Michael's
 * answer to that was two words — "Fill those gaps." This is the index, and the directory's list is
 * unchanged in shape because of it: same rows, same landing, one more kind of item on them.
 *
 * ── The shape, and why it is [LedgerTags]'s shape ──────────────────────────────────────────────
 *
 * A tag occurrence is (date, pageKey, rect) and the directory jumps by that rect through
 * `toDayNote(date, page, rect)` → `SurfaceFragment.focusOnRect`. A card is the same triple with a
 * name on it, so it is stored as the same triple with a name on it, and the directory navigates both
 * with the identical call. Inventing a second notion of "where is this thing on the page" would have
 * meant two definitions to keep agreeing forever; there is one, and cards borrowed it.
 *
 * Storage follows [PickingsStore] rather than [LedgerTags]: one small JSON file per day under
 * `filesDir/pickings-cards/<date>.json`, so "which days have cards" is answerable from FILENAMES and
 * one day's cards cost one small read. [LedgerTags]' single-blob SharedPreferences map is right for
 * tags — a tag's whole point is that it spans days, so every read wants all of it — and wrong here:
 * cards belong to one board on one day, and nothing ever wants every card in the ledger at once.
 *
 * ── What is NOT here, deliberately ────────────────────────────────────────────────────────────
 *
 *  • The PIXELS. Not one byte of a card's face is copied. The index holds a label and a rectangle;
 *    the face stays the day file's business. An index that duplicated the base64 would double the
 *    problem it exists to route around.
 *  • The DAY JSON. Nothing is added there — iOS reads that file, and a field only Android writes is
 *    a field iOS drops on its next save. A sidecar can be rebuilt from the day; a lost day field
 *    cannot be rebuilt from anything.
 *  • WEBDAV SYNC. [PickingsStore] pushes itself through [com.toolsboox.plugin.calendar.nw.LedgerSidecarSync]
 *    because a board's NAME is authored — lose it and it is gone. A card index is DERIVED: every
 *    device already has the day file this is computed from, so syncing it would ship bytes each
 *    device can regenerate, and invent merge conflicts between two correct answers. Each device
 *    indexes its own copy.
 *
 * ── Freshness, and why it is a file timestamp rather than a flag ──────────────────────────────
 *
 * The index is written from [CalendarDayService.save], which every placement, drag, resize,
 * deletion, graduation and merge-writeback in the app funnels through — so under ordinary use it
 * cannot fall behind. But `CalendarWebDavSyncService.writeLocal` installs a pulled day file straight
 * from bytes, on purpose (parsing tens of megabytes on a Boox to decide whether to keep a download
 * "would cost more than the sync"), and that path can never call a save hook. So freshness is not
 * something this store asserts about itself: it is `sidecar.lastModified() >= dayFile.lastModified()`,
 * two stats, and a day that arrived behind the app's back reads as stale exactly as it should.
 * Stale is a repairable state here, not a wrong answer — see [refreshIfStale] and [backfill].
 */
object PickingsCards {

    private const val DIR = "pickings-cards"

    /** The by-page tally every directory row reads. One file, not one per day: the root directory
     *  lists documents across all of history and wants a count on each row, and reading a year of
     *  per-day sidecars to draw a menu is precisely the cost this whole store exists to avoid. */
    private const val COUNTS = "counts.json"

    /** A page holding more cards than this is a page nobody is going to find one in by scrolling a
     *  list; the cap keeps a pathological day from turning the sidecar into a second day file. */
    private const val MAX_CARDS = 400

    private const val LABEL_MAX = 80

    /**
     * One placed card, as the directory sees it: what to call it, which board page it sits on, and
     * the design-space (1404×1872) box to land on. [id] is the element's own UUID, which makes a
     * rewrite idempotent and gives two identically-labelled cards distinct rows.
     */
    data class Card(
        val page: String,
        val label: String,
        val rect: RectF,
        val kind: String,
        val id: String,
    )

    // ── The application context ───────────────────────────────────────────────────────────────
    //
    // The write hook lives in CalendarDayService, which has no Context and must not grow one: it is
    // constructed by hand in the corpus unit test, which runs on a JVM where there is no Context to
    // hand it. So the context arrives once from BaseApplication and the store no-ops without it —
    // which is the right behaviour in that test, not a degraded one.
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    // ── Writing ───────────────────────────────────────────────────────────────────────────────

    private val lock = Any()

    /** The last payload written per date, so a pen-up save that changed only STROKES doesn't rewrite
     *  the card sidecar. Bounded: a session touches a handful of days, and a miss costs one write. */
    private val lastWritten = LinkedHashMap<LocalDate, String>()

    /** The by-page tally, memoized like [LedgerTags.list] and dropped by every writer. */
    @Volatile
    private var countsMemo: Map<String, Int>? = null

    /**
     * The date a day file's base name is for — "day-2026-07-30" or "day-2026-07-30-v2.json" — or
     * null for anything that isn't one. The save hook is handed a base name and the load hook a
     * file name, and this is the one place that decides what either of them means.
     */
    fun dateOf(name: String): LocalDate? {
        val m = Regex("""day-(\d{4})-(\d{2})-(\d{2})""").find(name) ?: return null
        return runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull()
    }

    /**
     * Index [day]'s cards for [date]. Called from the save funnel with the day already in memory, so
     * nothing is decoded and nothing is read — the whole cost is a walk of a list the caller just
     * serialised anyway, and a small write only when the cards actually changed.
     *
     * The sidecar is TOUCHED even when the payload is identical, because freshness is its mtime
     * against the day file's: skipping the touch would leave every unchanged save looking stale and
     * send the directory off to repair an index that was already right.
     */
    fun record(date: LocalDate, day: CalendarDay) {
        record(appContext ?: return, date, day)
    }

    private fun record(context: Context, date: LocalDate, day: CalendarDay) {
        runCatching {
            val cards = cardsOf(day)
            val payload = encode(cards)
            val file = fileFor(context, date)
            synchronized(lock) {
                if (lastWritten[date] == payload && file.exists()) {
                    file.setLastModified(System.currentTimeMillis())
                    return
                }
                file.writeText(payload)
                lastWritten[date] = payload
                while (lastWritten.size > 8) lastWritten.remove(lastWritten.keys.first())
                writeCounts(context, date, cards)
            }
        }.onFailure { Timber.w(it, "pickings card index write failed for $date") }
    }

    /**
     * Rebuild [date]'s index from an already-decoded [day] if the sidecar is behind [dayFile].
     *
     * This is the backfill that costs nothing, and it is why there is no start-up sweep. Every FULL
     * decode of a day file in this app — opening the day page, the Drive sync's inventory pass, the
     * "Bring in a picking" gather that walks 120 days — passes through
     * [CalendarDayService.load], which calls this. The decode already happened for someone else's
     * reasons; the index just rides it. Ordinary use therefore backfills the ledger a day at a time,
     * in the background, without anyone waiting for it and without a single extra file read.
     */
    fun refreshIfStale(date: LocalDate, day: CalendarDay, dayFile: File) {
        val context = appContext ?: return
        val sidecar = fileFor(context, date)
        if (sidecar.exists() && sidecar.lastModified() >= dayFile.lastModified()) return
        record(context, date, day)
    }

    /** Whether [date]'s index can be trusted — see the class note: two stats, no decode, safe to ask
     *  while a menu is opening. A day with no day file at all is vacuously fresh (nothing to index). */
    fun isFresh(context: Context, date: LocalDate): Boolean {
        val dayFile = LedgerPaths.dayFile(context, date) ?: return true
        val sidecar = fileFor(context, date)
        return sidecar.exists() && sidecar.lastModified() >= dayFile.lastModified()
    }

    /**
     * The explicit repair: decode ONE day file and rewrite its index. Returns the number of cards
     * indexed, or -1 if the day could not be read.
     *
     * This is the only path here that opens a day file, and every caller of it is off the main
     * thread and asking about a single named day — never a list, never while a menu is opening. It
     * goes through [CalendarDayService.load] rather than growing a fourth day-file reader: that
     * method already carries this fork's hard-won defences (the `Throwable` catch that keeps an
     * 86 MB day from taking the process with it, the blank/corrupt-file tolerance), and a private
     * decoder here would be a place for those lessons to be un-learned.
     *
     * Idempotent: re-running it on an unchanged day rewrites the same bytes and moves nothing.
     */
    fun backfill(context: Context, service: CalendarDayService, date: LocalDate): Int {
        val dayFile = LedgerPaths.dayFile(context, date) ?: return 0
        val day = runCatching { service.load(dayFile) }.getOrNull() ?: return -1
        val cards = cardsOf(day)
        record(context, date, day)
        return cards.size
    }

    /**
     * A bounded sweep: repair up to [limit] of the given [dates] that are stale, newest first, and
     * return how many days were actually read.
     *
     * Bounded on purpose and bounded by COUNT rather than by time, because the cost that matters
     * here is per-file and wildly uneven — one 40 MB day is worth thirty ordinary ones. The caller
     * picks the limit and runs this on IO; nothing in this store ever starts a sweep by itself.
     */
    fun backfillMissing(
        context: Context,
        service: CalendarDayService,
        dates: List<LocalDate>,
        limit: Int,
    ): Int {
        var done = 0
        for (date in dates.sortedDescending()) {
            if (done >= limit) break
            if (isFresh(context, date)) continue
            backfill(context, service, date)
            done++
        }
        return done
    }

    /** How many of [dates] have no trustworthy index — what a "repair N days" offer counts. */
    fun staleCount(context: Context, dates: List<LocalDate>): Int = dates.count { !isFresh(context, it) }

    // ── Reading ───────────────────────────────────────────────────────────────────────────────

    /**
     * The cards inside one document on one day — its base page AND its sub-pages, so a card on page
     * three of a writing is still a card in that writing, exactly as [LedgerTags] treats a tag.
     * Reading order (page, then down the page, then across) so the list reads the way the board
     * looks rather than the way the JSON happened to be ordered.
     */
    fun cards(context: Context, date: LocalDate, base: String): List<Card> =
        readCards(context, date)
            .filter { it.page.substringBefore('#') == base }
            .sortedWith(compareBy({ it.page }, { it.rect.top }, { it.rect.left }))

    /** Every card on [date], whatever page it is on. */
    fun cards(context: Context, date: LocalDate): List<Card> = readCards(context, date)

    /**
     * The by-document card counts the directory rows wear, keyed "date|basePage" — the same key
     * `directoryMarkCounts` uses for tags, so the two badges are computed and read the same way.
     * One small file, memoized, dropped by every write.
     */
    fun counts(context: Context): Map<String, Int> {
        countsMemo?.let { return it }
        synchronized(lock) {
            countsMemo?.let { return it }
            val out = readCounts(context)
            countsMemo = out
            return out
        }
    }

    /**
     * The cards inside a day that is ALREADY DECODED — the same rule [record] indexes by, offered to
     * the one caller that legitimately has a [CalendarDay] in its hand and no sidecar to read.
     *
     * The "Bring in a picking" gather needs this. It lists from the index, but the index is repaired
     * lazily (see [refreshIfStale]) and so cannot be assumed complete: a day that has never been
     * opened since this store existed has no sidecar, and the picker must not pretend that day holds
     * nothing. So it decodes those days itself — and when it does, it must count their cards by the
     * SAME rule the sidecar would have recorded, or the picker's answer would change depending on
     * whether a day happened to have been indexed yet. One rule, two sources.
     *
     * (The decode also repairs the sidecar on its way past, because it goes through
     * [CalendarDayService.load]. So a day is answered the expensive way at most once.)
     */
    fun cardsIn(day: CalendarDay): List<Card> = cardsOf(day)

    /** Every day this store holds an index for, newest first — filenames only, nothing opened.
     *  The [PickingsStore.dates] move, for the same reason: a directory has to know which days are
     *  worth asking about before it knows which day you want. */
    fun dates(context: Context): List<LocalDate> =
        File(context.filesDir, DIR).listFiles()
            ?.mapNotNull { f ->
                if (!f.name.endsWith(".json")) null
                else runCatching { LocalDate.parse(f.name.removeSuffix(".json")) }.getOrNull()
            }
            ?.sortedDescending()
            ?: emptyList()

    /**
     * The glyph a card wears in a list. An A/V gram says what it will do when you land on it —
     * the poster frame is only half of what that card is — and a text card is a quote, which is
     * what the Pickings surface's own ❝ has always meant.
     */
    fun glyphFor(card: Card): String = when (card.kind) {
        "video" -> "▶"
        "audio" -> "♪"
        else -> if (card.label.isBlank()) "▣" else "❝"
    }

    /** What to show when the card carries no words of its own — a photograph, a bare crop. Never
     *  blank: a row you can't read is a row you can't choose. */
    fun labelFor(card: Card): String = card.label.ifBlank {
        when (card.kind) {
            "video" -> "Video"
            "audio" -> "Audio"
            else -> "Picture"
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, date: LocalDate) = File(dir(context), "$date.json")

    /**
     * The label ladder, which is not a new one.
     *
     * `mediaTitle` first because an A/V gram's title is the only thing the poster frame can't say;
     * then the same `cardText → sourceLabel` fall the link grams, the widget renderer, the "Bring in
     * a picking" gather and the graduate-a-gram namer all use, so a card is called the same thing
     * wherever it is named. `sourceFeed` catches the clipping that arrived with a publication and no
     * words; below that the card genuinely has no name and [labelFor] says so at read time rather
     * than storing a made-up one.
     */
    private fun labelOf(e: ImageElement): String {
        val raw = e.mediaTitle.ifBlank { e.cardText }.ifBlank { e.sourceLabel }.ifBlank { e.sourceFeed }
        return raw.replace(Regex("\\s+"), " ").trim().take(LABEL_MAX)
    }

    /**
     * The day's image elements as index rows.
     *
     * `decorative` is skipped for the reason [ImageElement] gives for the flag existing: a sticker
     * "carries no provenance and never joins the rhizome/Map — an edge to a decorative flourish is
     * noise". A row in a jump-to list is the same kind of noise. Elements with no page key are
     * skipped too: a card that belongs to no page is a card no jump can land on.
     */
    private fun cardsOf(day: CalendarDay): List<Card> =
        day.imageElements.asSequence()
            .filter { !it.decorative && it.page.isNotBlank() }
            .sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
            .take(MAX_CARDS)
            .map {
                Card(
                    page = it.page,
                    label = labelOf(it),
                    rect = RectF(it.x, it.y, it.x + it.width, it.y + it.height),
                    kind = it.mediaKind,
                    id = it.elementId.toString(),
                )
            }
            .toList()

    /** Rects are rounded to whole design-space pixels. The landing zooms a 1404×1872 page to a box
     *  of hundreds of pixels; a fractional edge is invisible there and costs bytes on every card. */
    private fun encode(cards: List<Card>): String {
        val arr = JSONArray()
        for (c in cards) {
            arr.put(
                JSONObject()
                    .put("page", c.page)
                    .put("label", c.label)
                    .put("x", Math.round(c.rect.left))
                    .put("y", Math.round(c.rect.top))
                    .put("w", Math.round(c.rect.width()))
                    .put("h", Math.round(c.rect.height()))
                    .put("kind", c.kind)
                    .put("id", c.id)
            )
        }
        return JSONObject().put("cards", arr).toString()
    }

    private fun readCards(context: Context, date: LocalDate): List<Card> {
        val f = fileFor(context, date)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("cards") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val x = o.optDouble("x", 0.0).toFloat()
                val y = o.optDouble("y", 0.0).toFloat()
                Card(
                    page = o.optString("page"),
                    label = o.optString("label"),
                    rect = RectF(x, y, x + o.optDouble("w", 0.0).toFloat(), y + o.optDouble("h", 0.0).toFloat()),
                    kind = o.optString("kind"),
                    id = o.optString("id"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Replace [date]'s slice of the tally and drop the memo. Under [lock] with the sidecar write,
     *  so two days saving at once can't each write the other's stale copy of the map back. */
    private fun writeCounts(context: Context, date: LocalDate, cards: List<Card>) {
        runCatching {
            val prefix = "$date|"
            val out = JSONObject()
            for ((k, v) in readCounts(context)) if (!k.startsWith(prefix)) out.put(k, v)
            for ((base, n) in cards.groupingBy { it.page.substringBefore('#') }.eachCount()) {
                out.put("$prefix$base", n)
            }
            File(dir(context), COUNTS).writeText(out.toString())
        }.onFailure { Timber.w(it, "pickings card counts write failed for $date") }
        countsMemo = null
    }

    private fun readCounts(context: Context): Map<String, Int> {
        val f = File(dir(context), COUNTS)
        if (!f.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            val out = HashMap<String, Int>(o.length())
            for (k in o.keys()) out[k] = o.optInt(k, 0)
            out
        }.getOrDefault(emptyMap())
    }
}
