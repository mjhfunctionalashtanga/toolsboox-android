package com.toolsboox.plugin.feeds.ui

/**
 * THE INCOMING DOORS — one list, one order, one name per door, read by every screen that offers
 * a way into what arrives: the Hub's 📰 Feeds folder, the Feeds drawer, the day page's Sections,
 * The Net's 🔖 launcher and the Doors widget (IncomingDoorsTest holds the widget to it).
 *
 * 📧 THE MAIL HAS ITS OWN ROOM (Michael 2026-10-11: "You can move mail ... out of incoming" ·
 * "Mail can have its own accordion then"): the Hub folds it on its own, right above Feeds ("Today,
 * Daily, Mail, Feeds, Desk, …"), and the Feeds drawer keeps only a one-tap "📧 The Mail ↗" door
 * where its fold was. The door stays in this list — the one source of its glyph and name, and its
 * seat in Sections and on the Doors widget, which still go straight to it.
 *
 * 🦋 BLUESKY'S HUB ROW IS ON THE DESK (Michael 2026-10-11: "Can Bluesky move to desk?") — the
 * replies waiting on an answer and the pad, beside Correspondence. The Hub's Feeds fold no longer
 * carries it; the Feeds drawer still does, as its timeline lens (the mirror read like a feed), and
 * the Desk row takes its glyph and name from here.
 *
 * Michael 2026-10-11: "the shared order everywhere" — ➕ Save for later · 🌐 The Net · 📧 The Mail
 * · 🦋 Bluesky · 📖 Read · 📺 Watch · 🎧 Listen · 🔖 Later List · ⭐ Starred · 💬 Asks & Answers ·
 * # Smart · 📡 Local, and "The Mail" everywhere. Each screen used to type its own list, so his
 * 09-13 order ("+ for later just before The Net, the net above The Mail") only ever reached the
 * Hub: the drawer kept Mail first with The Net down in SOURCES, Sections had no The Net, and the
 * widget put Listen before Watch.
 *
 * The rule a screen keeps: it may show FEWER doors, never a different order and never a different
 * name. A screen names which doors it carries ([rows], [inOrder]); the order is only ever this
 * enum's. Screen-specific extras (the Hub's ▶ Continue under 🎧, the drawer's 🕘 The Log, the
 * drawer's saved searches riding under its search field per 09-03) are not doors and keep their
 * seats beside the door they belong to.
 */
object IncomingDoors {

    /**
     * A door: its glyph, its one name, and the shorter name a 4-across widget tile can hold
     * ([tile] — the media lenses drop their article there and nowhere else; The Mail keeps its).
     */
    enum class Door(val glyph: String, val title: String, val tile: String = title) {
        SAVE_FOR_LATER("➕", "Save for later"),
        THE_NET("🌐", "The Net"),
        THE_MAIL("📧", "The Mail"),
        BLUESKY("🦋", "Bluesky"),
        READ("📖", "The Read", "Read"),
        WATCH("📺", "The Watch", "Watch"),
        LISTEN("🎧", "The Listen", "Listen"),
        LATER("🔖", "Later List"),
        STARRED("⭐", "Starred"),
        ASKS("💬", "Asks & Answers"),
        SMART("#", "Smart Feeds"),
        LOCAL("📡", "Local Feeds");

        /** The row grammar every directory draws: glyph, two spaces, name. */
        val label: String get() = "$glyph  $title"
    }

    /** The shared order. Declaration order of [Door] IS the order; nothing re-sorts it. */
    val ORDER: List<Door> = Door.entries

    /** [doors] in the shared order, whatever order the caller named them in. */
    fun inOrder(doors: Collection<Door>): List<Door> = ORDER.filter { it in doors }

    /**
     * Label → action rows for a launcher, in the shared order. [after] seats a screen's extra rows
     * directly beneath the door they belong to (the Hub's ▶ Continue rides under 🎧 The Listen).
     */
    fun rows(
        actions: Map<Door, () -> Unit>,
        after: Map<Door, List<Pair<String, () -> Unit>>> = emptyMap(),
    ): List<Pair<String, () -> Unit>> =
        inOrder(actions.keys).flatMap { d ->
            listOf(d.label to actions.getValue(d)) + after[d].orEmpty()
        }
}
