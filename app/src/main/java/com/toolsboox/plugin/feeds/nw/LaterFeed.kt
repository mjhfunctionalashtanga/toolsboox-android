package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.FeedEntry
import com.toolsboox.plugin.michaelfilter.nw.IntakePageStore
import com.toolsboox.plugin.michaelfilter.nw.LaterStars
import java.time.LocalDate

/**
 * The Later List as a feed SOURCE: everything filed for later — links shared in from other apps,
 * links held down in the reader or a book, Ask's "Educate me" lookups, starred mail — dressed as
 * [FeedEntry] rows so every reading surface renders them without knowing what an intake page is.
 *
 * Michael: "I'd love later list to appear in feeds like a feed instead of this, instead under
 * '🎧 The Listen' like '🔖 Later List'." On the iPad that sentence retired a bespoke sheet; here it
 * mostly ratifies what this fork already did — the Later List has been `mode = "later"` in the
 * feed list since it arrived, for the same reason `FeedsFragment.loadBlueskyFeed` gives at length
 * for the timeline: reading a saved-links list is READING, and it wants the
 * list → in-pane reader, the volume rocker, the tap zones, the search field with its scopes, the
 * text tier and the thumbnail sizes — every one of which lives in that fragment and in nothing else
 * the app has. A second list surface for one source would be all of it again, worse, and then two
 * places where "open this" means something slightly different.
 *
 * What this file adds is the part that WAS still bespoke: the rows were synthesized inline in the
 * fragment with no name of their own, no way back from a row to the line it came from, and no lane
 * axis. A source that can be starred and deleted needs all three, so it becomes a thing with a name
 * — the same move [LocalFeedStore] and [AskFeedStore] made for their corpora.
 *
 * Nothing here touches the main thread's budget lightly: [rows] walks 120 intake day files and
 * reads a cached article copy per link, which is why every caller runs it on `Dispatchers.IO`.
 */
object LaterFeed {

    /**
     * The synthetic id band.
     *
     * Rows must NEVER collide with a real Miniflux entry (small positive ints), a [LocalFeedStore]
     * id (-1000 .. -1e9), a Pickings board (-1.1e9) or a Bluesky post (-1.3e9 and down) — a
     * synthetic id that reaches the server addresses somebody ELSE's entry. This band is the 99
     * million ids below -1.2e9, which leaves the Bluesky base a clear million of headroom.
     */
    const val ID_BASE = -1_200_000_000L
    private const val ID_SPAN = 99_000_000L

    fun isLater(id: Long): Boolean = id <= ID_BASE && id > ID_BASE - ID_SPAN

    /**
     * A stable entry id for a filed link.
     *
     * FNV-1a over the row's identity rather than the counting-down seed the other synthetic sources
     * use, and the difference matters here alone: those rows are read and left, while these are
     * starred and deleted. A positional id shifts every time the list gains or loses an item, so a
     * menu opened before a background cross-device merge landed would carry an id that now names a
     * DIFFERENT link — and the one thing a delete may never do is remove the row below the one you
     * chose. Hashing the content means a stale id simply isn't in [handles] any more and the delete
     * reports nothing removed, which is the failure you want.
     *
     * (`String.hashCode` would have been the obvious shortcut and is only 32 bits — birthday
     * collisions start biting in the thousands of links, which is the size this list actually
     * reaches.)
     */
    fun entryId(key: String): Long {
        var h = -0x340d631b7bdddcdbL          // 0xcbf29ce484222325, the FNV-1a offset basis
        for (b in key.toByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L               // FNV prime; Long multiplication wraps, as intended
        }
        // Sign bit off before the modulo, not `abs`: `abs(Long.MIN_VALUE)` is still Long.MIN_VALUE,
        // and one hash in 2^64 landing outside the band is one row that can never be deleted.
        return ID_BASE - (h and Long.MAX_VALUE) % ID_SPAN
    }

    /**
     * The four lanes a filed link can be in, in intake-page order, labelled with the SAME media
     * grammar the feed drawer speaks (📖 / 📺 / 🎧 — the emoji [FeedEntry.kind] reads a category by).
     *
     * `"educate"` is a LEGACY STORAGE KEY and the lane is EMAIL.
     * [com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake] draws that quarter as
     * `IntakePanel("educate", "EMAIL", …)`, starred mail is filed into it, and Ask's "Educate me"
     * lookups land there too — which is where the key's name came from, but mail is the bulk of it
     * now. The iPad briefly labelled this lens "Educate me" on the reasoning that a lens called
     * Email sitting a few rows under the real Mail folder was category confusion, and that was the
     * wrong call: EMAIL is what every other surface calls this quarter, including this one. A lens
     * sharing a word with the Mail folder is a smaller problem than one surface naming a quarter
     * something no other surface does. When the storage key and the drawn label disagree, the DRAWN
     * label is the one that was learned.
     */
    val LANES: List<Triple<String, String, String>> = listOf(
        Triple("read", "📖 The Read", "The Read"),
        Triple("watch", "📺 The Watch", "The Watch"),
        Triple("listen", "🎧 The Listen", "The Listen"),
        Triple("educate", "📧 Email", "Email"),
    )

    /** The name the list wears when narrowed to one lane; a lane says which list it belongs to
     *  first, because "The Read" alone is indistinguishable from the RSS lens of that name sitting
     *  three rows above it in the same drawer. */
    fun title(lane: String?): String =
        LANES.firstOrNull { it.first == lane }?.let { "Later · ${it.third}" } ?: "Later List"

    /** Where a row came from, so it can be taken off the list again. */
    data class Filed(val date: LocalDate, val kind: String, val url: String, val title: String)

    /**
     * WHICH LINE EACH ROW CAME FROM.
     *
     * A synthesized row knows its title and URL but not the day file or the lane it was read out of,
     * and [IntakePageStore.unfileLink] needs both — an intake page is one file per day and the lanes
     * are separate fields inside it. Rebuilt WHOLE on every [rows] call rather than merged, so a
     * link removed on another device stops being deletable here too, and so it can never disagree
     * with what is on screen. Same shape and same reason as the fragment's `bskyUriById`: the row's
     * id is a hash and cannot be walked backwards.
     *
     * `@Volatile` because [rows] runs on IO and the menus read this on the main thread; the map is
     * replaced wholesale, never mutated in place, so a reader either sees the old load or the new
     * one and never a half-built one.
     */
    @Volatile
    var handles: Map<Long, Filed> = emptyMap()
        private set

    fun handle(id: Long): Filed? = handles[id]

    /**
     * The filed links as feed rows, newest-filed first, optionally narrowed to one [lane].
     *
     * Blocking and disk-bound (120 day files, one cached-article read per link) — call it on
     * `Dispatchers.IO`.
     */
    fun rows(context: Context, lane: String? = null): List<FeedEntry> {
        val ctx = context.applicationContext
        val out = mutableListOf<FeedEntry>()
        val found = HashMap<Long, Filed>()
        val lanes = LANES.filter { lane == null || it.first == lane }
        for (d in 0L..120L) {
            val date = LocalDate.now().minusDays(d)
            val data = IntakePageStore.load(ctx, date)
            for ((kind, label, _) in lanes) {
                // Reversed: lines are appended oldest-first within a day, so walk them backwards to
                // surface the NEWEST-added link first instead of burying it under earlier saves.
                data.typedFor(kind).lines().reversed().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    val url = com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
                        .extractUrls(line).firstOrNull() ?: line
                    // Presentation metadata saved with the link (entry blurb + image, or the og:
                    // fetch) — what makes the row informative instead of a bare URL.
                    val meta = IntakePageStore.linkMeta(data, url)
                    // A link saved without a title reads as its host ("nytimes.com"), not the raw
                    // URL; stripping the URL also drops the " — " separator leftovers.
                    val title = meta?.get("title")?.takeIf { it.isNotBlank() }
                        ?: line.replace(url, "").trim().trim('—', '-', ' ')
                            .ifBlank {
                                runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }
                                    .getOrNull() ?: url
                            }
                    // Same link re-filed on another day shows once (newest day wins — we walk
                    // newest-first). Keyed on url+title, which is also what the star and the
                    // tombstone are keyed on, so the three agree about what "one link" is.
                    if (out.any { it.url == url && it.title == title }) return@forEach
                    // The offline parsed copy saved at file time IS the entry content — the article
                    // opens in the reader like any other feed entry, no connection needed. Older
                    // items without a copy get one fetched now (background).
                    val cached = IntakePageStore.cachedArticle(ctx, url)
                    if (cached == null) IntakePageStore.cacheArticle(ctx, url)
                    // No offline copy (yet)? The saved excerpt stands in, so the row still says what
                    // the thing is. The image rides the enclosure slot: FeedEntry.imageUrl prefers
                    // an inline <img> from the article, then falls back to it.
                    val excerpt = meta?.get("excerpt")?.takeIf { it.isNotBlank() }
                    val content = cached ?: excerpt?.let { "<p>$it</p>" }.orEmpty()
                    val id = entryId("later|$kind|$url|$title")
                    found[id] = Filed(date, kind, url, title)
                    out += FeedEntry(
                        id = id,
                        title = title,
                        // The "feed" line is the link's HOST — where you got it — which is the only
                        // useful thing that line could say when every row shares one source. (It is
                        // the fallback the meta line uses when a row has no category, and what the
                        // drawer's "this feed" search scope narrows by.) The lane rides in
                        // `category` instead, carrying its media emoji, so the row reads the lens it
                        // was FILED under rather than re-guessing one from the URL.
                        feedTitle = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }
                            .getOrNull() ?: "Later List",
                        url = url, author = null, content = content,
                        // The date it was FILED, which is the only date the store keeps — intake
                        // pages are one file per day. A bare date, which `publishedDate` already
                        // knows to parse for exactly these rows.
                        publishedAt = date.toString(),
                        // From the sidecar. This shipped as a hard `false` with no way to set it, on
                        // the sound reasoning that a filed link has no Miniflux entry behind it and a
                        // gesture writing nowhere is worse than a missing one. Michael's answer was
                        // to give it somewhere to write, so [LaterStars] is that place.
                        starred = LaterStars.isStarred(ctx, url),
                        category = label,
                        enclosureImage = meta?.get("image")?.takeIf { it.startsWith("http") }
                    )
                }
            }
        }
        handles = found
        return out
    }

    /**
     * Take a row off the list for good.
     *
     * @return false when the line had already gone — a stale tap on a list that has since been
     *         reloaded or merged, which should be silent rather than an error.
     */
    fun remove(context: Context, entryId: Long): Boolean {
        val item = handles[entryId] ?: return false
        handles = handles - entryId
        return IntakePageStore.unfileLink(context, item.date, item.kind, item.url, item.title)
    }

    /**
     * Star or unstar a filed link — and DELIBERATELY NOT the full star ceremony.
     *
     * On an article the star is what MAKES the thing: it writes a ReadingEvent into today's day
     * file, mints a link-card gram onto the Intake page's Star Sort quarter, and can fire the
     * configured webhook (see `FeedsFragment.logStar`). None of that happens here, and the reason is
     * that a filed link is ALREADY in the ledger — filing it is what put it there, gram and all.
     * Starring it a second time is triage inside a list you already keep, not a new act of keeping,
     * and running the ceremony would place a duplicate gram on Star Sort for something that is
     * already on Star Sort. The flag stays where the list can read it and nowhere else.
     */
    fun setStar(context: Context, url: String, starred: Boolean) {
        if (url.isBlank()) return
        LaterStars.set(context.applicationContext, url, starred)
    }
}
