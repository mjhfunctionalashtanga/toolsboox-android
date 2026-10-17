package com.toolsboox.plugin.feeds.ui

import android.content.SharedPreferences

/**
 * THE MAIL'S REMEMBERED PLACE — its own, now that The Mail is its own room (Michael 2026-10-11:
 * "You can move mail ... out of incoming" · "Mail can have its own accordion then"). The Mail
 * remembers the mailbox you were in and your Unread / Read / All, across the Hub, Sections and
 * restarts; ☀ Today is the one-tap reset to every account's Unread. Feeds keeps its own place
 * ([IncomingPlace]), so 👁 in one room no longer moves the other.
 *
 * Kept in its own prefs file ([PREFS], "ledger_mail_place"). The mailbox was a field of the
 * shared Incoming place until this split; [split] moves it across ONCE, idempotently, and only
 * rewrites the Incoming place when that place was The Mail (critique 1: an RSS place — its feed,
 * category, Later lane, smart or local feed — is never touched, only the dead `mailbox` key goes).
 *
 * Pure decisions ([arrive], [today], [isHome], [capture], [split]) so MailPlaceTest holds them;
 * FeedsFragment only moves fields in and out.
 */
data class MailPlace(
    /** The view: feed (Unread) · read · both (All) · stars (⭐ Starred — a moment, never stored). */
    val mode: String = FEED,
    /** The standing Unread / Read / All — what a door with no view lands on. Moved only by 👁, ☀. */
    val view: String = FEED,
    /** The mailbox: null = 📥 Inbox (all accounts), an account id (that account's Inbox), or one
     *  account's ➤ Sent / 🚫 Junk / 🗑 Trash ([of]) — those piles are visits, stood in and never
     *  stored (see [capture]). The all-accounts [SENT] and [TRASH] tokens still resolve. */
    val mailbox: String? = null,
) {
    /**
     * What a door asked for. A null [mode] (or "feed") means "at your Unread / Read / All"; a null
     * [mailbox] means "the one you were in" ([ALL_ACCOUNTS] names every account). [letter]: the
     * door opens a letter, whose row must be on screen — every account.
     */
    data class Door(val mode: String? = null, val mailbox: String? = null, val letter: Boolean = false)

    /**
     * THE MAILBOXES, one list, one name per row, read by the Hub's Mail fold and the Mail room's
     * drawer alike (the IncomingDoors rule: a screen may show fewer, never reorder or rename).
     * Michael 2026-10-11: "Inbox then starred then @gmail is the preferred order in mail" and
     * "Mail accounts also have individual sents, junks, trash. That can be a drop down under each
     * account." So the fold leads with [TOP] (📥 Inbox — every account — and ⭐ Starred), then one
     * row per account whose own disclosure holds [PER_ACCOUNT].
     */
    enum class Box(val glyph: String, val title: String) {
        INBOX("📥", "Inbox"), STARRED("⭐", "Starred"), SENT("➤", "Sent"), JUNK("🚫", "Junk"), TRASH("🗑", "Trash");

        /** Glyph, two spaces, name — an every-account 📥 Inbox, ➤ Sent or 🗑 Trash says "all
         *  accounts" once there is more than one; an account's own rows pass nothing and read plain.
         *  (🚫 Junk, as on iOS's `MailFolder.junk` — ⚠ already means "something went wrong" here.) */
        fun label(accounts: Int = 1): String = "$glyph  ${words(accounts)}"

        /** The row's words without its glyph — what the room panes draw beside a monochrome face
         *  (Michael 2026-10-12: no colour emoji in the panes; the Hub keeps [label]). Not `name`:
         *  that is the enum's own ("SENT"), one dropped pair of brackets away (review 2026-10-12).
         *  iOS `MailFolder.everyAccountTitle`. */
        fun words(accounts: Int = 1): String =
            title + if (this in EVERY_ACCOUNT && accounts >= 2) " · all accounts" else ""

        companion object {
            /** The fold's head, before the accounts: 📥 Inbox (every account), ⭐ Starred. */
            val TOP = listOf(INBOX, STARRED)
            /** Under each account's disclosure: its Inbox, Sent, Junk, Trash. */
            val PER_ACCOUNT = listOf(INBOX, SENT, JUNK, TRASH)
            /** The piles that also exist across every account — 📥 Inbox at the fold's head, and
             *  ➤ Sent and 🗑 Trash at the foot of The Mail's own drawer (review 2026-10-11): the
             *  letters sent through a site's FluentCRM ("sent:crm:<host>"), replies whose account
             *  wasn't known, an account since removed, and the sample letters belong to no account,
             *  so no account's own Sent or Trash can list them — iOS keeps the same pair under its
             *  ✉️ All strip for the same reason. No every-account Junk: Junk is read per server. */
            val EVERY_ACCOUNT = listOf(INBOX, SENT, TRASH)
        }
    }

    /**
     * Each account's row name in the fold and the drawer: "@  <its display name>" — and, when two
     * accounts share that name (both "Michael Joel Hall", say: the display name is the From name),
     * "@  <name> · <address>", then a count if even that collides (review 2026-10-11). The rows
     * are told apart BY this label — the Hub's per-account drop-downs are keyed by it, and so is
     * each drop-down's remembered open/closed state — so two equal labels let the second account's
     * Sent, Junk and Trash stand in for the first's, and both carets open and close together.
     */
    object AccountLabel {
        /** [accounts] as (id, display name, address), in their order → id to label. */
        fun all(accounts: List<Triple<String, String, String>>): Map<String, String> {
            val byName = accounts.groupingBy { it.second }.eachCount()
            val first = accounts.map { (id, display, email) ->
                id to ("@  $display" + if ((byName[display] ?: 0) > 1 && email.isNotBlank() && email != display) " · $email" else "")
            }
            val seen = HashMap<String, Int>()
            return first.associate { (id, label) ->
                val n = (seen[label] ?: 0) + 1
                seen[label] = n
                id to if (n == 1) label else "$label ($n)"
            }
        }

        /** An account's row words in a room pane: the label without its "@  " — the pane draws the
         *  @ as the row's face (Michael 2026-10-12). The fold's state stays filed under [all]'s
         *  full label. */
        fun plain(label: String): String = label.removePrefix("@  ")
    }

    /** Where a mailbox token points: which [Box], and whose ([account] null = every account). */
    data class Where(val box: Box, val account: String?)

    companion object {
        const val PREFS = "ledger_mail_place"
        const val FEED = "feed"
        /** A door's "every account" — the Mail widget's kept pile, the 📥 Inbox row — where a null
         *  mailbox would mean "the one you were in". Never stored. */
        const val ALL_ACCOUNTS = "__all"
        /** Every account's outgoing pile — the same reserved string the iPad and the retired
         *  screen used. The Hub's fold lists each account's own ➤ Sent since 2026-10-11 (Michael:
         *  "Mail accounts also have individual sents, junks, trash"); this one stays at the foot of
         *  The Mail's drawer ([Box.EVERY_ACCOUNT]) for the letters no account owns. A visit. */
        const val SENT = "__sent"
        /** 🗑 Trash: the letters you cleared on this device (🧹 Sweep, 🗑 Delete), still held here,
         *  each one a ⤴ Move back away from the Inbox. Nothing was ever deleted on a server.
         *  A VISIT, never a remembered place (2026-10-11 review, as on iOS's `PlaceMemory.remembers`):
         *  you go there to fetch a letter back, and every plain door reopening the Trash would be
         *  the "stuck on Starred" trap all over again. Every account's Trash; each account's own
         *  is [of] (TRASH, id). */
        const val TRASH = "__trash"
        /** One account's piles: "__sent:<id>", "__junk:<id>", "__trash:<id>" (Michael
         *  2026-10-11: "individual sents, junks, trash … a drop down under each account"). */
        private const val SENT_OF = "__sent:"
        private const val JUNK_OF = "__junk:"
        private const val TRASH_OF = "__trash:"
        /** The three views 👁 cycles — the only modes a place stands in. */
        val VIEWS = setOf("feed", "read", "both")

        /** The token for [box] of [account] (null = every account): an account's Inbox is its id;
         *  ⭐ Starred is a view, never a mailbox (null); there is no every-account Junk. */
        fun of(box: Box, account: String?): String? = when (box) {
            Box.INBOX -> account
            Box.STARRED -> null
            Box.SENT -> if (account == null) SENT else SENT_OF + account
            Box.JUNK -> account?.let { JUNK_OF + it }
            Box.TRASH -> if (account == null) TRASH else TRASH_OF + account
        }

        /** Read a mailbox token back: which box, whose. */
        fun where(mailbox: String?): Where = when {
            mailbox == null || mailbox == ALL_ACCOUNTS -> Where(Box.INBOX, null)
            mailbox == SENT -> Where(Box.SENT, null)
            mailbox == TRASH -> Where(Box.TRASH, null)
            mailbox.startsWith(SENT_OF) -> Where(Box.SENT, mailbox.removePrefix(SENT_OF).ifBlank { null })
            mailbox.startsWith(JUNK_OF) -> Where(Box.JUNK, mailbox.removePrefix(JUNK_OF).ifBlank { null })
            mailbox.startsWith(TRASH_OF) -> Where(Box.TRASH, mailbox.removePrefix(TRASH_OF).ifBlank { null })
            else -> Where(Box.INBOX, mailbox)
        }

        /** The account a mailbox narrows to — its Inbox or one of its piles — or null (every account). */
        fun accountOf(mailbox: String?): String? = where(mailbox).account

        /** The piles the view axis stands aside for — Unread and Starred mean nothing over Sent,
         *  Junk or Trash, any account's or every account's. */
        fun isPile(mailbox: String?) = where(mailbox).box != Box.INBOX

        /** Where a way in lands — no door: the whole place; a door: its view and mailbox, the rest
         *  of the place where it was. ⭐ Starred is every account's kept pile, never Sent's. */
        fun arrive(place: MailPlace, door: Door?): MailPlace {
            if (door == null) return place
            val mode = door.mode?.takeUnless { it == FEED } ?: place.view
            val box = when {
                door.mailbox == ALL_ACCOUNTS -> null
                door.mailbox != null -> door.mailbox
                door.letter -> null
                mode == "stars" && isPile(place.mailbox) -> null
                else -> place.mailbox
            }
            return place.copy(mode = mode, mailbox = box)
        }

        /** ☀ Today: every account's Unread — the mailbox, Read/All and Starred all let go. */
        fun today(@Suppress("UNUSED_PARAMETER") place: MailPlace): MailPlace = MailPlace()

        /** Already at every account's Unread — the second ☀ tap opens the Hub. */
        fun isHome(place: MailPlace): Boolean = place.mode == FEED && place.mailbox == null

        /** The place worth keeping from what's on screen, or null for a moment (⭐ Starred, a mode
         *  the room never stands in) or a visit — every pile: an account's ➤ Sent, 🚫 Junk and
         *  🗑 Trash, as the Trash already was (Michael 2026-10-11) — the last place then stands. */
        fun capture(now: MailPlace): MailPlace? {
            if (now.mode !in VIEWS) return null
            if (isPile(now.mailbox)) return null
            return now.copy(view = now.view.takeIf { it in VIEWS } ?: FEED)
        }

        /**
         * The mailbox the room can honestly stand in, given the accounts there are now:
         *  - an account's Inbox, or its Sent / Junk / Trash, while the account exists — even the
         *    only one: since 2026-10-11 every account has its own row (Michael: "individual sents,
         *    junks, trash … a drop down under each account"), so a list narrowed to the lone
         *    account is a row he picked, marked and named on the mailbox line, not the unmarked
         *    look-alike of 📥 Inbox the 2+ rule was there to stop;
         *  - every account's Sent / Trash as asked;
         *  - else 📥 Inbox.
         */
        fun standing(mailbox: String?, accountIds: Collection<String>): String? {
            if (mailbox == null) return null
            val w = where(mailbox)
            return when {
                w.account == null -> mailbox.takeIf { w.box != Box.JUNK }
                w.account in accountIds -> mailbox
                else -> null
            }
        }

        fun load(p: SharedPreferences): MailPlace = MailPlace(
            mode = p.getString("mode", null)?.takeIf { it in VIEWS } ?: FEED,
            view = p.getString("view", null)?.takeIf { it in VIEWS } ?: FEED,
            // A pile stored before it became a visit (the first cut's Trash; every-account Sent,
            // a place until its row moved under each account on 2026-10-11) reads as 📥.
            mailbox = p.getString("mailbox", null)?.takeUnless { it == ALL_ACCOUNTS || isPile(it) },
        )

        fun save(p: SharedPreferences, place: MailPlace) {
            p.edit()
                .putString("mode", place.mode)
                .putString("view", place.view)
                .putString("mailbox", place.mailbox)
                .apply()
        }

        /** The guard: set once the split has run on this device. */
        const val SPLIT_FLAG = "split_v1"

        /**
         * The one-time split, as data — what the Mail place starts as, and what the Incoming place
         * keeps. Safe to run twice: a second run over an already-split Incoming place finds no
         * mailbox and no mail lens, so it hands back nothing to rewrite.
         *  - The Mail gets the mailbox and the standing view; its mode too, when The Mail was where
         *    you last were.
         *  - Incoming drops `mailbox` always; when its place WAS The Mail it becomes your view of
         *    everything (kind gone, mode = view, the long-dropped pick fields cleared). An RSS place
         *    keeps every other field exactly as it was.
         */
        data class Split(val mail: MailPlace, val incomingPuts: Map<String, String?>, val incomingRemoves: Set<String>)

        fun split(incoming: Map<String, *>): Split {
            val view = (incoming["view"] as? String)?.takeIf { it in VIEWS } ?: FEED
            val wasMail = incoming["kind"] == "mail"
            val mode = (incoming["mode"] as? String)?.takeIf { wasMail && it in VIEWS } ?: view
            val box = (incoming["mailbox"] as? String)?.takeUnless { it.isBlank() || it == ALL_ACCOUNTS }
            val puts = if (wasMail) linkedMapOf<String, String?>(
                "kind" to null, "mode" to view, "category" to null, "feed" to null, "picked_lens" to null,
            ) else emptyMap()
            val removes = if ("mailbox" in incoming) setOf("mailbox") else emptySet()
            return Split(MailPlace(mode = mode, view = view, mailbox = box), puts, removes)
        }

        /** Run [split] once per device, before either room reads or writes its place. */
        fun migrateOnce(incoming: SharedPreferences, mail: SharedPreferences) {
            if (mail.getBoolean(SPLIT_FLAG, false)) return
            val s = split(incoming.all)
            // The Mail's place only if it has none yet (a downgrade-and-back must not stomp it).
            if (!mail.contains("view")) save(mail, s.mail)
            if (s.incomingPuts.isNotEmpty() || s.incomingRemoves.isNotEmpty()) {
                val e = incoming.edit()
                for ((k, v) in s.incomingPuts) e.putString(k, v)
                for (k in s.incomingRemoves) e.remove(k)
                e.apply()
            }
            mail.edit().putBoolean(SPLIT_FLAG, true).apply()
        }
    }
}
