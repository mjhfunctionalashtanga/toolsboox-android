package com.toolsboox.ot

/**
 * ONE PANEL LANGUAGE — the seats every tuck panel wears, in one order, on both apps (Michael
 * 2026-10-11: "The tuck panels are not working with the same design language hierarchy" → plan →
 * "Yes"). The iOS twin is `Sources/LedgerCore/PanelSeats.swift`: same seats, same ids, same
 * captions, same order, and the same golden table (`app/src/test/resources/panel-seats.golden`,
 * byte-identical in both repos — `scripts/panel-parity.sh` diffs them).
 *
 * THE FRAME, top to bottom (panel-design-language spec §1, with its critique applied):
 *  - HEAD   ⛶ Full, pinned above the scroll ([Seat.FULL]; never in the body). On an ink page it
 *           stands down while the bottom pill's corner wears ⛶ (Michael 2026-10-12: "No need to see
 *           the full screen expand button on the tuck if its on the note nib" —
 *           BottomPill.columnWearsFull; iOS's CornerAction.panelHeadWearsFull); the table still
 *           names it, as the runtime decides. In full screen a list or reader leaves a small [⛶]
 *           pill. ANDROID-ONLY EXCEPTION (2026-10-12, "The Fullscreen button/notes nib in the feed
 *           list could be movable too"): iOS floats that pill on every list and it moves (one
 *           shared spot, `pill_spot_full`); Android floats it only on the book reader, where it
 *           moves on the same key, and stands it IN THE TOP ROW of the Feeds room, the Net and the
 *           shelf — beside the row's own controls, over no list row's ★, so it stays put there.
 *           The notes pill moves on both apps.
 *  - FRAME  Today · the DOOR · More — the same on every screen, More always last so it sits at
 *           one height everywhere. The door is Nib — back to your note, captioned "Notes" (Michael
 *           2026-10-12: "In the reader the nib should be called notes") — on everything you read
 *           ([door]); the Ledger's own pages (the ones you write) have none now. HUB AND FEEDS ARE
 *           OFF EVERY PANEL, both apps (Michael 2026-10-12, after the room panes took them off the
 *           Feeds and Mail panels: "Those too."): the Hub is More's FIRST ROW on every panel (the
 *           touch route — it opens the main menu without leaving the page) and the left edge's
 *           swipe ladder ([EDGE_LADDER]: a reader goes back to its list first, a room opens its
 *           pane first, everywhere else the Hub at once); the Feeds door went with it (Feeds is a
 *           Hub row, and the Nib's hold), and the room panes' seats (Feeds, Mail) to the same swipe
 *           — [replacedByLadder] names every seat's new home. ONE EXCEPTION, both apps: an article
 *           or a letter read OUTSIDE its list keeps Hub, and the article its Feeds seat
 *           ([Flags.outsideList]) — from there no swipe reaches the list or its pane.
 *  - HAND   what you do every few seconds here: the turns on things you read, the pens on a page
 *           you write.
 *  - ROOM   the screen's own buttons, in one fixed order by KIND ([Kind]: index · list · reading ·
 *           answer · make · file), only the kinds this screen has.
 *  - FOOT   [Close on an iOS full-screen cover] · Side, last.
 * Seats pack from the top with one fixed gap between blocks (12dp here, 8pt on iOS) — no elastic
 * gap; spare room collects under Side, and a column that outruns the screen scrolls.
 *
 * Captions are one word per meaning, ≤ 8 characters (Michael's "Move back" is the one longer word
 * and keeps it — the hold menu's and iOS's word). Faces are monochrome only ([MONO_GLYPHS]; the
 * drawables are mapped in [PanelFaces]) — colour emoji render in colour on LCD and in grey dither on
 * e-ink, which is the "icons lack design language" Michael saw (2026-10-11).
 *
 * Pure: PanelSeatsTest holds the order, the kinds, the captions and the golden table.
 */
object PanelSeats {

    enum class Platform(val tag: String) { ANDROID("and"), IOS("ios") }

    /** Where a seat stands. The ROOM's kinds run in this order (index < list < reading < answer <
     *  make < file); HEAD/FRAME/TURN/WRITE/WALK/FOOT are blocks of their own. */
    enum class Kind { HEAD, FRAME, TURN, WRITE, WALK, INDEX, LIST, READING, ANSWER, MAKE, FILE, FOOT }

    /**
     * Every seat, with its stable [id] (the golden table's word), its [caption] (≤ 8 characters,
     * the word under the face while Tool labels are on), its [voice] (the hold-toast / screen-reader
     * name when a surface doesn't name it more exactly) and its [kind].
     */
    enum class Seat(val id: String, val caption: String, val voice: String, val kind: Kind) {
        // Head and frame.
        FULL("full", "Full", "Full screen", Kind.HEAD),
        HUB("hub", "Hub", "Hub", Kind.FRAME),
        TODAY("today", "Today", "Today", Kind.FRAME),
        MORE("more", "More", "More", Kind.FRAME),
        // The doors. Frame seat 3; Feeds is also the feeds drawer (an index) on Android's lists.
        FEEDS("feeds", "Feeds", "Feeds", Kind.INDEX),
        // The notes door — back to your note. Captioned and spoken "Notes" (Michael 2026-10-12, on
        // the iPhone 247 article: "In the reader the nib should be called notes"), wherever it
        // stands; the seat keeps its id (the golden table's word) and its nib face.
        NIB("nib", "Notes", "Notes", Kind.INDEX),

        // Turns — the reading hand, in this order.
        PAGE_UP("pageUp", "Page ↑", "Page up", Kind.TURN),
        PAGE_DOWN("pageDown", "Page ↓", "Page down", Kind.TURN),
        PAGE_BACK("pageBack", "Page ‹", "Previous page", Kind.TURN),
        PAGE_FORWARD("pageForward", "Page ›", "Next page", Kind.TURN),
        PREVIOUS("previous", "Previous", "Previous", Kind.TURN),
        NEXT("next", "Next", "Next", Kind.TURN),
        BACK("back", "Back", "Back a page", Kind.TURN),
        REFRESH("refresh", "Refresh", "Refresh", Kind.TURN),

        // Writing — the ink hand.
        INPUT("input", "Input", "Input", Kind.WRITE),
        PEN("pen", "Pen", "Pen", Kind.WRITE),
        ERASER("eraser", "Eraser", "Eraser", Kind.WRITE),
        LASSO("lasso", "Lasso", "Lasso select", Kind.WRITE),
        UNDO("undo", "Undo", "Undo", Kind.WRITE),
        REDO("redo", "Redo", "Redo", Kind.WRITE),
        // Zoom (Michael 2026-10-12: "Add a zoom button with marquee zoom to the tuck panels"): the tap
        // repeats the last choice and the face says which ("We can make the zoom button the last used
        // option for one click zoom in / out adjustments. Or zoom to box :)") — Zoom box (the next
        // drag draws a dashed box and the page zooms to it, then the tool you held comes back), Zoom +
        // or Zoom − (10% about the middle, − stopping at the rest); hold = the three, the pick
        // becoming the tap (MarqueeZoom.Choice). Boxed while the page is magnified. Directly after
        // Redo, in the pens block, on both apps.
        ZOOM("zoom", "Zoom", "Zoom", Kind.WRITE),
        YOUR_DAY("yourDay", "Your day", "Your day", Kind.WRITE),
        BY_HAND("byHand", "By hand", "By hand / Type", Kind.WRITE),

        // The walk (ink pages and the section panes).
        UP("up", "Up", "Up", Kind.WALK),
        DOWN("down", "Down", "Down", Kind.WALK),
        NEW("new", "New", "New page", Kind.WALK),
        BACK_TO("backToPage", "Back to", "Back to the page", Kind.WALK),
        SECTIONS("sections", "Sections", "Sections", Kind.WALK),

        // Room · index: the room's own contents.
        MAIL("mail", "Mail", "Mail drawer", Kind.INDEX),
        BOOK("book", "Book", "This book", Kind.INDEX),
        FOLDERS("folders", "Folders", "Folders", Kind.INDEX),
        ADDRESS("address", "Address", "Where to?", Kind.INDEX),
        PILES("piles", "Piles", "Marks & piles", Kind.INDEX),
        // "Annotations" (Michael 2026-10-12: "Instead of margin, those can be annotations") — the
        // highlights-and-notes list of the open book, and of the open article now. Not "Notes": the
        // notes door has that word ("In the reader the nib should be called notes"), and a reader
        // wears both. Michael's word, so it is the one caption past eight characters besides Move
        // back; spoken the same. iOS names its bookNotes seat "Annotations" too (PARITY).
        NOTES("bookNotes", "Annotations", "Annotations", Kind.INDEX),

        // Room · list.
        SEARCH("search", "Search", "Search", Kind.LIST),
        SHOW("show", "Show", "Show", Kind.LIST),
        COMPOSE("compose", "Compose", "New email", Kind.LIST),
        ADD("add", "Add", "Add", Kind.LIST),
        CONTINUE("continueReading", "Continue", "Continue reading", Kind.LIST),
        ORDER("order", "Order", "Order", Kind.LIST),

        // Room · reading.
        STAR("star", "Star", "Star this", Kind.READING),
        BOOKMARK("bookmark", "Bookmark", "Bookmark this page", Kind.READING),
        NOTE("note", "Note", "Note on this", Kind.READING),
        FIND("find", "Find", "Find in this piece", Kind.READING),
        READER("reader", "Reader", "Reader view", Kind.READING),
        // A letter's dress — As sent / Reader (iOS adds Plain text); the caption names the mode at
        // runtime (critique #12: the article's on/off Reader glyph would lose the third way).
        READ_AS("readAs", "Read as", "Read as", Kind.READING),
        TYPE("type", "Type", "Aa Type", Kind.READING),
        LISTEN("listen", "Listen", "Read aloud", Kind.READING),
        LINE_BACK("lineBack", "‹ Line", "Back a sentence", Kind.READING),
        LINE_FORWARD("lineForward", "Line ›", "On a sentence", Kind.READING),
        MARK("mark", "Mark", "Highlight", Kind.READING),
        ANNOTATE("annotate", "Annotate", "Annotate", Kind.READING),
        PICTURES("pictures", "Pictures", "Show pictures", Kind.READING),

        // Room · answer.
        REPLY("reply", "Reply", "Reply", Kind.ANSWER),
        DELETE("delete", "Delete", "Delete", Kind.ANSWER),
        RESTORE("moveBack", "Move back", "Move back", Kind.ANSWER),

        // Room · make.
        SYNTH("synth", "Synth", "Synthesize", Kind.MAKE),

        // Room · file.
        LATER("later", "Later", "Save for later", Kind.FILE),
        TAG("tag", "Tag", "Tag", Kind.FILE),
        PICK("pick", "Pick", "Send to All Picks", Kind.FILE),
        TO_SYNTH("toSynth", "To synth", "Assign to synthesis", Kind.FILE),
        THREAD("thread", "Thread", "All our letters", Kind.FILE),
        LINKS("links", "Links", "Links → Later", Kind.FILE),
        RHIZOME("rhizome", "Rhizome", "Rhizome", Kind.FILE),
        SWEEP("sweep", "Sweep", "Sweep", Kind.FILE),
        ACCOUNTS("accounts", "Accounts", "Accounts", Kind.FILE),

        // Foot.
        CLOSE("close", "Close", "Close", Kind.FOOT),
        SIDE("side", "Side", "Other side", Kind.FOOT);

        companion object {
            fun byId(id: String): Seat? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * One kind of screen. [ledger] = a page of the Ledger's own (you write on it): its door is
     * Feeds. Everything else is something you read: its door is Nib. [platforms] = the apps that
     * have this screen as one dress (Android's typed pages, section panes and Listen are their own
     * surfaces — S3.6 — not these).
     */
    enum class Dress(val id: String, val ledger: Boolean, val platforms: Set<Platform> = Platform.entries.toSet()) {
        DAY("day", ledger = true),
        SECTION("section", ledger = true, platforms = setOf(Platform.IOS)),
        TYPED_PAGE("typedPage", ledger = true, platforms = setOf(Platform.IOS)),
        FEED_LIST("feedList", ledger = false),
        MAIL_LIST("mailList", ledger = false),
        ARTICLE("article", ledger = false),
        LETTER("letter", ledger = false),
        LISTEN("listen", ledger = false, platforms = setOf(Platform.IOS)),
        NET("net", ledger = false),
        SHELF("shelf", ledger = false),
        BOOK("book", ledger = false);

        /**
         * THE FEEDS PANE'S RESERVED SEAT (Michael 2026-10-11): Feeds directly under Hub on the
         * Feeds list and the article. The swipe ladder has it now (Michael 2026-10-12) — worn only
         * by an article read outside its list ([Flags.outsideList]), where no swipe reaches the
         * pane. On Android the pane is the Feeds drawer (the directory of feeds beside the list).
         */
        val reservesFeedsPane: Boolean get() = this == FEED_LIST || this == ARTICLE

        /** A reader that lives inside a list — the article in Feeds, the letter in The Mail — and
         *  so can also be read OUTSIDE it ([Flags.outsideList]). */
        val readsInList: Boolean get() = this == ARTICLE || this == LETTER

        /**
         * THE ROOM PANES' DRESSES (Michael 2026-10-12, on the iOS Feeds pane: "Swiping in rss to
         * bring up the main pane should bring the rss pane first. Same with emails getting an RSS
         * like pane for accounts. First swipe back email stuff second swipe back main pane. I think
         * that you can get rid of the button for hub and rss on the tuck because of it"): the Feeds
         * list, the article, The Mail's list and a letter. The edge-swipe ladder ([EDGE_LADDER])
         * opens their room's pane first (the Feeds pane, the Mail pane), then the Hub — so their
         * pane seats (Feeds, Mail) went with Hub; a reader of the four goes back to its list first.
         */
        val roomPane: Boolean get() = this == FEED_LIST || this == ARTICLE || this == MAIL_LIST || this == LETTER

        /** A dress only one app has — the golden table prefixes its line with that app's tag. */
        val onePlatform: Platform? get() = platforms.singleOrNull()
    }

    /** What a screen is showing right now — only the flags that add or drop a seat. */
    data class Flags(
        /** iOS: a piece is being read aloud (‹ Line · Line › join). */
        val readingAloud: Boolean = false,
        /** iOS letter: pictures are off (Pictures shows). */
        val picturesOff: Boolean = false,
        /** Mail list: Sweep means something here (never over the kept pile, Sent or Trash). */
        val canSweep: Boolean = false,
        /** Letter: the open letter is in Trash — Delete's seat becomes Move back. */
        val trash: Boolean = false,
        /** Letter: read out of Junk — read-only, no Delete seat. */
        val junk: Boolean = false,
        /** Letter: ink is on over it — Delete stands aside so a stray stroke can't reach it (iOS;
         *  Android letters take no ink yet, so this never sets there). */
        val annotating: Boolean = false,
        /** Lists: Show only where the view can cycle (Unread / Read / All). */
        val showScope: Boolean = true,
        /** Article: Star hides on Bluesky and Later sources. */
        val starable: Boolean = true,
        /** Article: Later hides on a Later-source piece. */
        val laterable: Boolean = true,
        /** Letter: Reply shows where the letter can be answered. */
        val canReply: Boolean = true,
        /** iOS day page: [New] while a new page can be made here. */
        val newPage: Boolean = false,
        /** iOS day page: the Your day seat. */
        val yourDay: Boolean = false,
        /** iOS typed Write / day: the By hand ⇄ Type seat. */
        val typed: Boolean = false,
        /**
         * AN ARTICLE OR A LETTER READ OUTSIDE ITS OWN LIST (iOS review 2026-10-12; Android parity
         * 2026-10-12): iOS's saved-article cover, Listen sheet, Search, Missed rhizomes, 🧵 sheets
         * and Correspondence; Android's standalone article (FeedArticleFragment — a gram's source,
         * a link out of the Ledger). The left edge there goes BACK to whatever opened the piece,
         * never to the list or its pane, so the dress keeps Hub (one tap, not More › Hub) and the
         * article its Feeds seat (the pane, otherwise four taps off). Same rule on both apps.
         */
        val outsideList: Boolean = false,
    )

    /** One screen's column under ⛶: four blocks, packed from the top, one gap between each. */
    data class Layout(val frame: List<Seat>, val hand: List<Seat>, val room: List<Seat>, val foot: List<Seat>) {
        val blocks: List<List<Seat>> get() = listOf(frame, hand, room, foot)
        /** The body in column order (⛶ excluded — it is the head). */
        val all: List<Seat> get() = frame + hand + room + foot
    }

    /**
     * The apps whose left edge runs the swipe ladder — and so every panel on them wears no Hub or
     * Feeds seat (Michael 2026-10-12: "Those too." → "Make sure we can get to hub with back swipe in
     * the reader or touch or something as needed" → plan → "Awesome"). Both, now.
     *  - iOS: an in-app flick from the left edge (`EdgeBack`).
     *  - ANDROID: Back from the left edge ([EdgeLadder]). On the B6 (gesture navigation, a 56px back
     *    band down both edges) a flick in from the left edge is system Back where nothing claims
     *    the edge: the manifest's enableOnBackInvokedCallback and activity-ktx 1.11 hand an
     *    enabled OnBackPressedCallback the swipe's edge (handleOnBackStarted →
     *    BackEventCompat.swipeEdge). Every ink page claims the edge for the pen, and there the
     *    in-app [LeftEdgeCatcher] (a finger flick) hands the same callbacks the same Back
     *    (Michael 2026-10-12, the B6: "The b6 doesn't have the left right working for bring up the
     *    hub it seems rn"). A Back that starts at the LEFT edge walks room pane → Hub →
     *    back — the Hub's close and the step back are one swipe (Michael 2026-10-12: "Then two
     *    swipes back instead of three") — a reader: back to its list first; the right edge stays
     *    plain Back. The
     *    system still recognises the swipe, so e-ink and stray strokes don't come into it.
     *    Where Back carries no edge — 3-button navigation, Android 11–13 (the Palma, the older
     *    Boox), or a B6 whose Android 14 reports EDGE_NONE — every Back is plain Back and the
     *    touch route is the one: More › Hub, first row on every panel, and the room's drawer as
     *    More's second row in Feeds and The Mail (Michael approved touch as the fallback).
     */
    val EDGE_LADDER: Set<Platform> = setOf(Platform.ANDROID, Platform.IOS)

    /** The door seat: Nib on anything you read; none on the Ledger's own pages — their Feeds door
     *  left with Hub (Michael 2026-10-12: "Those too."). */
    fun door(dress: Dress): Seat? = if (dress.ledger) null else Seat.NIB

    /** The frame every dress opens with: Today · door · More. A reader outside its list
     *  ([Flags.outsideList]) keeps Hub at its head, and the article the Feeds pane's seat directly
     *  under it: Hub · Feeds · Today · Nib · More. */
    fun frame(dress: Dress, flags: Flags = Flags()): List<Seat> {
        val outside = flags.outsideList && dress.readsInList
        return listOfNotNull(Seat.HUB.takeIf { outside }, Seat.FEEDS.takeIf { outside && dress.reservesFeedsPane },
            Seat.TODAY, door(dress), Seat.MORE)
    }

    /** The orders the room keeps inside a kind (spec §1 C). */
    val INDEX_ORDER = listOf(Seat.FEEDS, Seat.MAIL, Seat.BOOK, Seat.NOTES, Seat.FOLDERS, Seat.ADDRESS, Seat.PILES, Seat.NIB)
    val LIST_ORDER = listOf(Seat.SEARCH, Seat.SHOW, Seat.COMPOSE, Seat.ADD, Seat.CONTINUE, Seat.ORDER)
    val READING_ORDER = listOf(Seat.STAR, Seat.BOOKMARK, Seat.NOTE, Seat.FIND, Seat.READER, Seat.READ_AS, Seat.TYPE,
        Seat.LISTEN, Seat.LINE_BACK, Seat.LINE_FORWARD, Seat.MARK, Seat.ANNOTATE, Seat.PICTURES)
    val ANSWER_ORDER = listOf(Seat.REPLY, Seat.DELETE, Seat.RESTORE)
    val FILE_ORDER = listOf(Seat.LATER, Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS,
        Seat.RHIZOME, Seat.SWEEP, Seat.ACCOUNTS)
    val TURN_ORDER = listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PAGE_BACK, Seat.PAGE_FORWARD,
        Seat.PREVIOUS, Seat.NEXT, Seat.BACK, Seat.REFRESH)
    val ROOM_KINDS = listOf(Kind.INDEX, Kind.LIST, Kind.READING, Kind.ANSWER, Kind.MAKE, Kind.FILE)

    /**
     * The text faces a panel may wear (spec §2): monochrome in every font on both screens. Any other
     * text face — a colour emoji above all — is a drawable's job ([PanelFaces]).
     */
    val MONO_GLYPHS = setOf("«", "»", "‹", "›", "←", "✕", "◈", "❖", "@", "✉", "↥", "⟳")

    /** A VALUE face — the player's speed ("1.25×") or the sleep timer's time left ("12m", "end")
     *  drawn AS the face while Tool labels are off, so the state never lives in a hidden caption
     *  alone (review 2026-10-11, F6). Plain letters, digits, '.', ':' and '×': black in any font. */
    private val VALUE_FACE = Regex("[0-9A-Za-z.:×]{1,6}")

    /** [glyph] may stand as a panel face: a whitelisted text glyph or a value face. */
    fun isMonoGlyph(glyph: String): Boolean = glyph in MONO_GLYPHS || VALUE_FACE.matches(glyph)

    /** The seats whose faces are pencils — no column wears two (spec §3 rules). */
    val PENCIL_FAMILY = setOf(Seat.NIB, Seat.PEN)

    /**
     * [dress]'s column on [platform] with [flags], or null where that app has no such dress.
     * Spec §4 "After" columns, critique applied: More is seat 4 everywhere; no Refresh while
     * reading; Synth before Later; Delete after Reply; Move back stands in Delete's seat in Trash;
     * The Mail's room seat is "Mail", not "List".
     */
    fun layout(dress: Dress, platform: Platform, flags: Flags = Flags()): Layout? {
        if (platform !in dress.platforms) return null
        val ios = platform == Platform.IOS
        val and = platform == Platform.ANDROID
        val f = flags
        val frame = frame(dress, f)
        val readingTurns = listOf(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.PREVIOUS, Seat.NEXT)
        return when (dress) {
            // Ink pages (the day page, its stations and notes; Android's almanac pages wear it too).
            Dress.DAY -> Layout(frame,
                hand = listOfNotNull(Seat.INPUT.takeIf { ios }, Seat.PEN, Seat.ERASER, Seat.LASSO,
                    Seat.UNDO, Seat.REDO, Seat.ZOOM, Seat.SYNTH,
                    Seat.YOUR_DAY.takeIf { ios && f.yourDay }, Seat.BY_HAND.takeIf { ios && f.typed }),
                room = listOfNotNull(Seat.UP, Seat.DOWN, Seat.NEW.takeIf { ios && f.newPage }, Seat.SECTIONS),
                foot = listOf(Seat.SIDE))
            // iOS Desk and Garden panes: the walk, the way back to the page, Sections, then the Nib.
            Dress.SECTION -> Layout(frame,
                hand = emptyList(),
                room = listOf(Seat.UP, Seat.DOWN, Seat.BACK_TO, Seat.SECTIONS, Seat.NIB),
                foot = listOf(Seat.SIDE))
            // iOS typed planner pages.
            Dress.TYPED_PAGE -> Layout(frame,
                hand = listOfNotNull(Seat.BY_HAND.takeIf { f.typed }),
                room = listOf(Seat.UP, Seat.DOWN, Seat.SECTIONS),
                foot = listOf(Seat.SIDE))
            // The Feeds list (Android: FeedsFragment's Feeds room). Feeds rides the frame, under Hub.
            // Order is iOS's until Android's store learns to sort.
            Dress.FEED_LIST -> Layout(frame,
                hand = readingTurns + Seat.REFRESH,
                room = listOfNotNull(Seat.SEARCH, Seat.SHOW.takeIf { f.showScope },
                    Seat.ORDER.takeIf { ios }, Seat.SYNTH),
                foot = listOf(Seat.SIDE))
            // The Mail drawer's seat left with Hub (the swipe's first rung; More's second row).
            Dress.MAIL_LIST -> Layout(frame,
                hand = readingTurns + Seat.REFRESH,
                room = listOfNotNull(Seat.SEARCH, Seat.SHOW.takeIf { f.showScope },
                    Seat.COMPOSE, Seat.ORDER.takeIf { ios }, Seat.SYNTH,
                    Seat.SWEEP.takeIf { f.canSweep }, Seat.ACCOUNTS),
                foot = listOf(Seat.SIDE))
            // The article. Annotate (ink over the piece) is iOS's: Android's Note seat is the same
            // gram chooser the standalone article's "Annotate" opened. Annotations — every highlight
            // and note on this piece, the approved plan's list (Michael 2026-10-12) — leads the room
            // (an index seat, the kind order's first).
            Dress.ARTICLE -> Layout(frame,
                hand = readingTurns,
                room = listOfNotNull(Seat.NOTES, Seat.STAR.takeIf { f.starable }, Seat.NOTE,
                    Seat.FIND, Seat.READER, Seat.TYPE, Seat.LISTEN,
                    Seat.LINE_BACK.takeIf { ios && f.readingAloud }, Seat.LINE_FORWARD.takeIf { ios && f.readingAloud },
                    Seat.ANNOTATE.takeIf { ios }, Seat.SYNTH, Seat.LATER.takeIf { f.laterable }),
                foot = listOf(Seat.SIDE))
            Dress.LETTER -> Layout(frame,
                hand = readingTurns,
                room = listOfNotNull(Seat.STAR, Seat.NOTE, Seat.FIND, Seat.READ_AS,
                    Seat.LISTEN, Seat.ANNOTATE.takeIf { ios }, Seat.PICTURES.takeIf { ios && f.picturesOff },
                    Seat.REPLY.takeIf { f.canReply },
                    when { f.junk || f.annotating -> null; f.trash -> Seat.RESTORE; else -> Seat.DELETE },
                    Seat.TAG, Seat.PICK, Seat.TO_SYNTH, Seat.THREAD, Seat.LINKS, Seat.RHIZOME),
                foot = listOf(Seat.SIDE))
            // iOS Listen sheet.
            Dress.LISTEN -> Layout(frame,
                hand = listOf(Seat.REFRESH),
                room = listOf(Seat.SYNTH),
                foot = listOf(Seat.CLOSE, Seat.SIDE))
            // The Net (Michael 2026-10-11, field report: "Side changed on tuck not available. Icons
            // lack design language."). Android keeps ← Back in its header; Listen on the Net is
            // Android's alone until iOS grows it.
            Dress.NET -> Layout(frame,
                hand = listOfNotNull(Seat.PAGE_UP, Seat.PAGE_DOWN, Seat.BACK.takeIf { ios }, Seat.REFRESH),
                room = listOfNotNull(Seat.ADDRESS, Seat.PILES, Seat.BOOKMARK, Seat.LISTEN.takeIf { and },
                    Seat.ANNOTATE, Seat.LATER),
                foot = listOfNotNull(Seat.CLOSE.takeIf { ios }, Seat.SIDE))
            Dress.SHELF -> Layout(frame,
                hand = listOf(Seat.PAGE_UP, Seat.PAGE_DOWN),
                room = listOf(Seat.FOLDERS, Seat.SEARCH, Seat.ADD, Seat.CONTINUE),
                foot = listOfNotNull(Seat.CLOSE.takeIf { ios }, Seat.SIDE))
            // The book reader. Annotate joins Android after Mark once "Ink on books" lands there.
            Dress.BOOK -> Layout(frame,
                hand = listOf(Seat.PAGE_BACK, Seat.PAGE_FORWARD),
                room = listOfNotNull(Seat.BOOK, Seat.NOTES, Seat.FIND, Seat.TYPE, Seat.LISTEN, Seat.MARK,
                    Seat.ANNOTATE.takeIf { ios }),
                foot = listOfNotNull(Seat.CLOSE.takeIf { ios }, Seat.SIDE))
        }
    }

    // ── The golden table ──────────────────────────────────────────────────────────────────────

    /** The flag sets the golden table pins, per dress: (label, flags). The bare dress first. Labels
     *  are iOS's word for word (`PanelSeats.goldenCases`), so the two files can be byte-identical. */
    val GOLDEN_CASES: List<Triple<Dress, String, Flags>> = listOf(
        Triple(Dress.DAY, "", Flags()),
        Triple(Dress.DAY, "notes+yourDay+typed", Flags(newPage = true, yourDay = true, typed = true)),
        Triple(Dress.SECTION, "", Flags()),
        Triple(Dress.TYPED_PAGE, "", Flags()),
        Triple(Dress.TYPED_PAGE, "typed", Flags(typed = true)),
        Triple(Dress.FEED_LIST, "", Flags()),
        Triple(Dress.FEED_LIST, "noScope", Flags(showScope = false)),
        Triple(Dress.MAIL_LIST, "", Flags()),
        Triple(Dress.MAIL_LIST, "sweep", Flags(canSweep = true)),
        Triple(Dress.ARTICLE, "", Flags()),
        Triple(Dress.ARTICLE, "aloud", Flags(readingAloud = true)),
        Triple(Dress.ARTICLE, "later-source", Flags(starable = false, laterable = false)),
        Triple(Dress.ARTICLE, "outside", Flags(outsideList = true)),
        Triple(Dress.LETTER, "", Flags()),
        Triple(Dress.LETTER, "picturesOff", Flags(picturesOff = true)),
        Triple(Dress.LETTER, "trash", Flags(trash = true)),
        Triple(Dress.LETTER, "annotating", Flags(annotating = true)),
        Triple(Dress.LETTER, "noReply", Flags(canReply = false)),
        Triple(Dress.LETTER, "outside", Flags(outsideList = true)),
        Triple(Dress.LISTEN, "", Flags()),
        Triple(Dress.NET, "", Flags()),
        Triple(Dress.SHELF, "", Flags()),
        Triple(Dress.BOOK, "", Flags()),
    )

    /**
     * The golden table, in iOS's format (`PanelSeats.golden()`): one line per case,
     * `dress[·flags]: full | frame | hand | room | foot`, seat ids space-separated (`-` for an
     * empty block); a seat only one app wears is tagged `and:` or `ios:`, and a dress only one app
     * has is tagged on its name instead. Both repos render it from their own layout code and hold
     * it equal to the checked-in file; scripts/panel-parity.sh diffs the two files.
     */
    fun goldenTable(): String = buildString {
        appendLine("# panel-seats.golden — the tuck panel's one design language (Michael, 2026-10-11).")
        appendLine("# dress[·flags]: head | frame | hand | room | foot. ios:/and: = that platform only.")
        appendLine("# Byte-identical in ledger-ipad and toolsboox; regenerate from PanelSeats on either side.")
        for ((dress, label, flags) in GOLDEN_CASES) appendLine(goldenLine(dress, label, flags))
    }

    fun goldenLine(dress: Dress, label: String, flags: Flags): String {
        val one = dress.onePlatform
        val blocks: List<String> = if (one != null) {
            // A one-app dress: its own column, untagged — the tag is on the dress's name — except
            // Close, which is iOS's alone wherever it stands (iOS tags its slot the same way).
            val l = layout(dress, one, flags)
            (0 until 4).map { b ->
                l?.blocks?.get(b).orEmpty().joinToString(" ") { if (it == Seat.CLOSE) "ios:close" else it.id }.ifEmpty { "-" }
            }
        } else {
            val a = layout(dress, Platform.ANDROID, flags)
            val i = layout(dress, Platform.IOS, flags)
            (0 until 4).map { b ->
                tagged(a?.blocks?.get(b).orEmpty(), i?.blocks?.get(b).orEmpty()).joinToString(" ").ifEmpty { "-" }
            }
        }
        val name = (one?.let { it.tag + ":" } ?: "") + dress.id + (if (label.isEmpty()) "" else "·$label")
        return "$name: ${Seat.FULL.id} | " + blocks.joinToString(" | ")
    }

    /**
     * Seats a dress wore before the one panel language that now live in its More (rare by rule,
     * and named in the build report — Michael 2026-10-11: "anything moved to More must be rare").
     * Android's article: Refresh while reading is More › Feed › Refresh (1 → 2 taps).
     */
    fun movedToMore(dress: Dress, platform: Platform): List<Seat> = when {
        platform == Platform.ANDROID && dress == Dress.ARTICLE -> listOf(Seat.REFRESH)
        else -> emptyList()
    }

    /**
     * Seats [dress] wore before Michael's 2026-10-12 "Those too." whose work More's first row and
     * the left edge's ladder do now — each with its home, so the nothing-lost test still names one
     * (iOS: `PanelSeats.replacedBySwipe`):
     *  - HUB, every dress: More › Hub (first row, never leaves the page); the left edge — at once
     *    on a room-less screen; after the pane in Feeds and The Mail; after the back to the list
     *    or shelf from a reader. It had no hold.
     *  - FEEDS, the door on the Ledger's own pages (iOS's day, section and typed pages — Android's
     *    day rail never wore it): the Hub's Feeds row, and the Nib's hold (Notes · Text notes ·
     *    Feeds · Today).
     *  - FEEDS, the Feeds pane's seat (the Feeds list and the article): the first left-edge swipe
     *    on the list (from the article: back, then that swipe); on Android also More's second row,
     *    "Feeds drawer", for a Back with no edge. Its hold, The Mail, is the pane's "The Mail ↗".
     *  - MAIL, the Mail pane's seat on Android (The Mail's list and a letter): the same, "Mail
     *    drawer" in More; it had no hold, and the pane's "Feeds ↗" is the way across.
     * A reader outside its list keeps Hub and the article Feeds ([Flags.outsideList]).
     */
    fun replacedByLadder(dress: Dress, platform: Platform, flags: Flags = Flags()): List<Seat> {
        if (platform !in dress.platforms) return emptyList()
        if (flags.outsideList && dress.readsInList) return emptyList()
        return listOfNotNull(Seat.HUB,
            Seat.FEEDS.takeIf { dress.ledger || dress.reservesFeedsPane },
            Seat.MAIL.takeIf { platform == Platform.ANDROID && (dress == Dress.MAIL_LIST || dress == Dress.LETTER) })
    }

    /**
     * Two apps' block merged into one tagged sequence: shared seats bare, Android-only `and:`,
     * iOS-only `ios:`, in column order. The orders agree wherever both apps wear a seat (the test
     * holds that), so a longest-common-subsequence walk lays the extras where they stand.
     */
    fun tagged(android: List<Seat>, ios: List<Seat>): List<String> {
        val n = android.size
        val m = ios.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) for (y in m - 1 downTo 0)
            lcs[x][y] = if (android[x] == ios[y]) lcs[x + 1][y + 1] + 1 else maxOf(lcs[x + 1][y], lcs[x][y + 1])
        val out = ArrayList<String>()
        var x = 0
        var y = 0
        while (x < n || y < m) when {
            x < n && y < m && android[x] == ios[y] -> { out += android[x].id; x++; y++ }
            y >= m || (x < n && lcs[x + 1][y] >= lcs[x][y + 1]) -> { out += "and:" + android[x].id; x++ }
            else -> { out += "ios:" + ios[y].id; y++ }
        }
        return out
    }
}
