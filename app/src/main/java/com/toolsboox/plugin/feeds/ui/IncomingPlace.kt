package com.toolsboox.plugin.feeds.ui

import android.content.SharedPreferences

/**
 * THE REMEMBERED PLACE in Feeds (Michael 2026-10-11, Q1 YES: "going back to where you were on
 * incoming should be easy"). Feeds remembers the lens, the feed or category picked in the
 * drawer and Unread / Read / All — across the Hub, Sections and restarts — and every way in
 * lands there. ☀ Today is the one-tap reset to plain Unread for the lens you're in. This
 * overrides plan v6 F4 ("lens taps land on Unread"), at his word.
 *
 * FEEDS ONLY since Mail moved out (Michael 2026-10-11: "You can move mail ... out of incoming" ·
 * "Change "Incoming" to "Feeds" with the rss image"). The mailbox and the mail lens live in
 * [MailPlace] now; [load] turns a stored mail lens into your view of everything, for good — a
 * downgrade or a hand-restored prefs file can't bring The Mail back into Feeds. (The file keeps
 * its "ledger_incoming" name: renaming it would forget every place for nothing.)
 *
 * The decisions are pure ([arrive], [today], [isHome], [capture], [goToFeed], [forPiece]) so
 * IncomingPlaceTest can hold them; FeedsFragment only moves fields in and out.
 */
data class IncomingPlace(
    /** The view or corpus: feed (Unread) · read · both (All) · later · local · smart · asklog · bsky
     *  (⭐ Starred is a moment, never a place — see [PLACE_MODES]). */
    val mode: String = FEED,
    /** The lens: null (everything) · read · watch · listen. */
    val kind: String? = null,
    /** The standing Unread / Read / All choice — what a lens door lands on. Set only by a
     *  deliberate choice (👁, ☀ Today), never inferred from whatever view is on screen. */
    val view: String = FEED,
    /** The Later List lane, when [mode] is later. */
    val laterLane: String? = null,
    /** The drawer pick: a category, a feed, and the lens they were picked under. */
    val category: String? = null,
    val feed: String? = null,
    val pickedLens: String? = null,
    /** The saved smart feed, when [mode] is smart. */
    val smartId: String? = null,
    /** The local subscription, when [mode] is local (null = all local feeds). */
    val localSubId: Long? = null,
) {
    private val hasPick: Boolean get() = category != null || feed != null

    private fun withoutPick() = copy(category = null, feed = null, pickedLens = null)

    /**
     * What a door asked for — the one-shot FeedSelection channel, read before it is consumed.
     * A null [mode] or [mode] == "feed" means "this lens, at your Unread / Read / All". A Later
     * door names its lane or means the whole list. (Mail doors go to The Mail's room — [MailPlace].)
     */
    data class Door(
        val mode: String?,
        val kind: String?,
        val laterLane: String? = null,
    )

    companion object {
        const val PREFS = "ledger_incoming"
        const val FEED = "feed"
        /** The retired mail lens's token — only ever read, to retire it ([load]). */
        private const val MAIL = "mail"

        /** The three views 👁 cycles. */
        val VIEWS = setOf("feed", "read", "both")
        /** Where a place can stand — anything else (a past date's edition, a transient search,
         *  retired modes) is a moment, not a place, and leaves the remembered place as it was.
         *  ⭐ STARRED IS A MOMENT (Michael 2026-10-11 named lens, feed, Unread / Read / All and
         *  mailbox — not Starred): kept as a place, one tap on Hub ⭐ made every later way in and
         *  every cold start open Starred, the "stuck on Starred" report again. */
        val PLACE_MODES = VIEWS + setOf("later", "local", "smart", "asklog", "bsky")
        /** Views a drawer pick survives in — the same set FeedsFragment.dropStalePick keeps. */
        private val PICKABLE = setOf("feed", "read", "both", "edition", "stars")

        /**
         * Where a way in lands. No door (the plain 📰 Feeds door, a cold start, a trip's
         * return): the whole place. A door names the lens; the rest of the place follows it there
         * — your Unread / Read / All — and your feed or category only when the
         * door asks for your view of the lens it was picked in (a door to ⭐ Starred or a Later
         * lane asked for that list, not for a narrowing it can't show). The Hub's 🔖 Later List
         * row is the whole list; its fold's rows name a lane.
         */
        fun arrive(place: IncomingPlace, door: Door?): IncomingPlace {
            if (door == null) return place
            val mode = door.mode?.takeUnless { it == FEED } ?: place.view
            val lane = if (mode == "later") door.laterLane else null
            val kind = door.kind?.takeUnless { it == MAIL }
            val asksForView = door.mode == null || door.mode == FEED
            val keepPick = place.hasPick && asksForView && kind == place.pickedLens && mode in PICKABLE
            val p = place.copy(
                mode = mode, kind = kind, laterLane = lane,
                smartId = place.smartId.takeIf { mode == "smart" },
                localSubId = place.localSubId.takeIf { mode == "local" && place.mode == "local" },
            )
            return if (keepPick) p else p.withoutPick()
        }

        /**
         * ☀ Today: plain Unread for the lens you're in — the pick, Read/All and Starred all let
         * go; the lens stays. (Today means "today's live feed" for every corpus, so the Later
         * List, Local, Smart and the rest land on Unread too.)
         */
        fun today(place: IncomingPlace): IncomingPlace = place.withoutPick().copy(
            mode = FEED, view = FEED, laterLane = null, smartId = null, localSubId = null,
        )

        /** Already at plain Unread for this lens — the second ☀ tap opens the directory. */
        fun isHome(place: IncomingPlace): Boolean = place.mode == FEED && !place.hasPick

        /**
         * The place worth keeping from what's on screen, or null for a moment that isn't one (a
         * transient list search's smart feed, ⭐ Starred, a mode with no door) — the last place
         * then stands. A past date's edition keeps its lens at the standing view: the date isn't
         * remembered. The standing view is carried as it is: what's on screen (a drawer lens
         * tap's All, say) is the place's view, not your Unread / Read / All.
         */
        fun capture(now: IncomingPlace): IncomingPlace? {
            if (now.mode == "smart" && now.smartId.isNullOrBlank()) return null
            val mode = if (now.mode == "edition") now.view else now.mode
            if (mode !in PLACE_MODES) return null
            if (now.kind == MAIL) return null
            val pickOk = now.hasPick && now.kind == now.pickedLens && mode in PICKABLE
            val p = now.copy(
                mode = mode,
                view = now.view.takeIf { it in VIEWS } ?: FEED,
                laterLane = now.laterLane.takeIf { mode == "later" },
                smartId = now.smartId.takeIf { mode == "smart" },
                localSubId = now.localSubId.takeIf { mode == "local" },
            )
            return if (pickOk) p else p.withoutPick()
        }

        /**
         * A gram's "Go to feed" names a feed and no lens: everything, at the standing view,
         * narrowed to that feed — the remembered lens could hide it (a Read feed under The Listen).
         */
        fun goToFeed(place: IncomingPlace, feed: String): IncomingPlace = place.copy(
            mode = place.view, kind = null, laterLane = null, smartId = null, localSubId = null,
            category = null, feed = feed, pickedLens = null,
        )

        /** A piece asked for without a door (Go to source, the Log, ▶ Continue, the ↩ chip) opens
         *  over the remembered place — a stored mail lens (from before Mail had its own room) gives
         *  way to your view of everything, since Feeds reads articles, not letters. */
        fun forPiece(place: IncomingPlace): IncomingPlace =
            if (place.kind == MAIL) place.withoutPick().copy(mode = place.view, kind = null) else place

        /** The stored place — a mail lens left by an older build (or a restored prefs file) lands
         *  as your view of everything: The Mail has its own room now and never stands in Feeds. */
        fun load(p: SharedPreferences): IncomingPlace {
            val view = p.getString("view", null)?.takeIf { it in VIEWS } ?: FEED
            val wasMail = p.getString("kind", null) == MAIL
            return IncomingPlace(
                mode = if (wasMail) view else p.getString("mode", null)?.takeIf { it in PLACE_MODES } ?: FEED,
                kind = p.getString("kind", null)?.takeUnless { wasMail },
                view = view,
                laterLane = p.getString("later_lane", null).takeUnless { wasMail },
                category = p.getString("category", null).takeUnless { wasMail },
                feed = p.getString("feed", null).takeUnless { wasMail },
                pickedLens = p.getString("picked_lens", null).takeUnless { wasMail },
                smartId = p.getString("smart_id", null).takeUnless { wasMail },
                localSubId = if (!wasMail && p.contains("local_sub")) p.getLong("local_sub", 0L) else null,
            )
        }

        fun save(p: SharedPreferences, place: IncomingPlace) {
            p.edit()
                .putString("mode", place.mode)
                .putString("kind", place.kind)
                .putString("view", place.view)
                .putString("later_lane", place.laterLane)
                .putString("category", place.category)
                .putString("feed", place.feed)
                .putString("picked_lens", place.pickedLens)
                .putString("smart_id", place.smartId)
                .apply {
                    if (place.localSubId != null) putLong("local_sub", place.localSubId) else remove("local_sub")
                }
                .apply()
        }
    }
}
