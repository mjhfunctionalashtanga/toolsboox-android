package com.toolsboox.plugin.calendar.ot

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * One document on a making surface, as the directory and the chip see it.
 *
 * [key] is the note-page key its content rides under; [title] is what to show (the explicit name if
 * it has one, else the date it was started — see [LedgerDocuments]); [subPageCount] is how many
 * pages it holds, which is 1 for the single-page surfaces and the real count for the multi-page
 * ones; [named] says which of the two the title came from, so a caller can offer "Rename" honestly.
 *
 * [subPageCount] is LAZY on purpose, and this is not a micro-optimisation. Counting the pages of a
 * document that lives on some other day means reading that day's file, and the day chip rebuilds
 * this whole list on every single page turn — so an eager count would put one disk read per named
 * document on the path of turning a page, on e-ink. The chip only ever needs the titles and how
 * many documents there are; the directory is the only caller that asks for counts, and it asks once
 * when you open it.
 */
class LedgerDocument(
    val key: String,
    val title: String,
    val date: LocalDate,
    val named: Boolean,
    pageCount: () -> Int,
) {
    val subPageCount: Int by lazy(LazyThreadSafetyMode.NONE, pageCount)
}

/**
 * The ONE query behind every "which page of this am I on, and what else is there" listing.
 *
 * Michael's model, in his words: "Write and Synthesize share a shape: nameable, multi-page
 * documents, where the name defaults to the start date if the user never renames it. So the 'page
 * list' for those two is really the same query. Pickings and Text note are adjacent but not
 * identical — still date-titled at creation, but single-page rather than multi-page, so there's no
 * sub-page drill-down to build."
 *
 * So this is deliberately ONE function over four surfaces rather than four bespoke listings. Before
 * it, each surface answered the question its own way and only Pickings answered it at all from the
 * page you were standing on — which is how the Pickings picker ended up reading `LocalDate.now()`
 * and describing today's boards no matter which day you had open. A shared query can only have that
 * bug once, and it doesn't have it: the date is always the caller's.
 *
 * Two scopes, and the difference is the surfaces' own nature rather than a design choice:
 *  • Multi-page (Write, Synthesize) — a piece of writing is a TOPIC you return to over weeks, so its
 *    documents are listed globally, each remembering the day it was started on. Plus the implicit
 *    daily document for the date you're on.
 *  • Single-page (Pickings, Text Notes) — the day's own boards/notes, listed for that date only.
 */
object LedgerDocuments {
    const val WRITE = "write"
    const val SYNTHESIZE = "synthesize"
    const val PICKINGS = "pickings"
    const val TEXT_NOTES = "textnote"

    /**
     * The two ruled surfaces, promoted. Michael: "I think grid and jot should be savable."
     *
     * A surface id here is the PAGE KEY FAMILY, not the label — which is why Jot's is "sketch". The
     * id is what [surfaceOf] hands back for a key and what [LedgerTitleInk] files a face under, so
     * making it agree with the keys already on disk is the difference between an addition and a
     * migration. What the surface is CALLED is [label]'s job, and it says "Jot Notes".
     *
     * Gram Picks is deliberately NOT here. It is sub-pageable like these two and it looked like the
     * third member of the set, but it is one inbox you sort out of rather than a thing you keep and
     * name — Michael named grid and jot, and only those.
     */
    const val GRID = "grid"
    const val JOT = "sketch"

    // ── THE FIVE TEMPLATES ────────────────────────────────────────────────────────────────────
    //
    // Michael: "Notes have template options: Pickings, Jots, Lines, Grid, Text. Each can be saved
    // etc hence the directory." And on Write: "write was really just another surface that was
    // exactly just like lined notes" — so Write IS the Lines template, not a sixth thing.
    //
    // THE WHOLE OF THIS IS PRESENTATION OVER STORAGE THAT ALREADY EXISTS. Each of the five already
    // had a store, an index directory and a Directory entry; what they did not have was one word
    // for what they are to each other. A template is not a new concept sitting beside the surfaces
    // — it IS a surface, seen as a property of the page rather than as a door of its own:
    //
    //   Pickings → PickingsStore   · pickings-index · keys "pickings" / "pickings-<millis>"
    //   Jots     → JotPageStore    · sketch-index   · keys "sketch"   / "sketch-<millis>"
    //   Lines    → WritePageStore  · write-index    · keys "write"    / "write-<millis>"
    //   Grid     → GridPageStore   · grid-index     · keys "grid"     / "grid-<millis>"
    //   Text     → TextNotesStore  · text-notes     · note ids "tn-<millis>-<rand>"
    //
    // So NOTHING here changes storage: no wire change, no day-JSON key change, no migration, no
    // iOS coordination. The ids in this file are the PAGE KEY FAMILIES they have always been —
    // which is why the Jots template's id is still "sketch". [label] is where the template's NAME
    // lives, and that is the only layer this vocabulary moved.
    //
    // Synthesize is deliberately NOT a template. It is a retired surface whose pages must still
    // open ([RetiredSurfaceReachabilityTest]); it is not something you make a new note as.

    /**
     * The five, in Michael's order — Pickings · Jots · Lines · Grid · Text.
     *
     * ONE list, read by the chooser, the Directory's kind ordering and everything else that
     * enumerates them, so a second copy can never put them in a second order.
     */
    val TEMPLATES = listOf(PICKINGS, JOT, WRITE, GRID, TEXT_NOTES)

    /** Whether a surface id is one of the five note templates (as against Synthesize, or null). */
    fun isTemplate(surface: String?): Boolean = surface != null && surface in TEMPLATES

    /**
     * Which TEMPLATE a note-page key is written on, or null when the key belongs to no template.
     *
     * [surfaceOf] with the retired surface filtered out: a synthesis page resolves to a document
     * surface (so it still lists, names and opens) but it is not a template you can start a note
     * as, and a "Template · Synthesize" row would offer a door that isn't there.
     *
     * Text is absent from [surfaceOf] by nature — a text note's key is a note id its own fragment
     * owns, not a day-page key — so it can never be answered here either. That is correct: the
     * question this asks is "what template is the PAGE I am standing on", and Text notes are not
     * pages you stand on.
     */
    fun templateOf(key: String?): String? = surfaceOf(key)?.takeIf { isTemplate(it) }

    /** The date a never-renamed document is titled by. ISO, matching every other date the app shows. */
    fun dateTitle(date: LocalDate): String = date.toString()

    /**
     * Which document surface a note-page key belongs to, or null for the surfaces that have no
     * documents to list (the plain day, the ritual stations, the numeric notes, and Gram Picks —
     * that last one is sub-pageable but is an inbox holding exactly ONE implicit page run per day,
     * so a chip over it would promise siblings that cannot exist; its ‹ N › pager already lists its
     * pages).
     *
     * Grid and Jot used to be in that list with Gram Picks, and this is where their promotion
     * begins: once a key resolves to a surface here, the day chip, the ‹ N › menu's document rows,
     * the naming/untitle/delete verbs, the handwritten title and the directory all follow, because
     * every one of them is written against a surface rather than against a key.
     */
    fun surfaceOf(key: String?): String? {
        val base = key?.substringBefore('#') ?: return null
        return when {
            PickingsStore.isPickings(base) -> PICKINGS
            SynthPageStore.isSynth(base) -> SYNTHESIZE
            WritePageStore.isWrite(base) -> WRITE
            GridPageStore.isMine(base) -> GRID
            JotPageStore.isMine(base) -> JOT
            else -> null
        }
    }

    /** The glyph the hub, the chip and the directory all wear for this surface — one vocabulary.
     *  Grid and Jot keep the ones the hub's Notes folder and the section pill already give them
     *  (📈 and ⌱); inventing a second glyph for a surface at the moment it gains a directory would
     *  be the directory renaming a door he already knows by sight. Text Notes wears Ⓣ (Michael:
     *  "a uniform Ⓣ") — its old ⌗ read as one more hash beside Tags' #, and two doors wearing
     *  the same mark is the collision this vocabulary exists to prevent. */
    fun glyph(surface: String): String = when (surface) {
        WRITE -> "✍"
        SYNTHESIZE -> "🔬"
        PICKINGS -> "❝"
        GRID -> "📈"
        JOT -> "⌱"
        else -> "Ⓣ"
    }

    /**
     * The surface's name — which, for the five, is now its TEMPLATE NAME.
     *
     * Michael's five words exactly: Pickings, Jots, Lines, Grid, Text. The old names were the names
     * of five separate doors ("Write", "Grid Notes", "Jot Notes", "Text Notes"), and keeping them
     * beside a chooser that offers Lines and Jots would leave the app speaking two vocabularies for
     * one thing — precisely the drift this trio of functions exists to prevent. So the rename lands
     * HERE, once, and the hub, the day chip, the ‹ N › menu, the Directory's kind rows and every
     * "All …" door follow it for free.
     *
     * "Lines" is Write's new name and nothing else about Write moved: its store is still
     * [WritePageStore], its keys are still "write" / "write-<millis>", and every piece he has ever
     * written opens exactly where it did. Retire the surface, never the data — and here not even
     * the surface, only the word.
     *
     * Synthesize keeps its own name because it is not a template: it is a retired surface whose
     * pages must still list and open.
     */
    fun label(surface: String): String = when (surface) {
        WRITE -> "Lines"
        SYNTHESIZE -> "Synthesize"
        PICKINGS -> "Pickings"
        GRID -> "Grid"
        JOT -> "Jots"
        else -> "Text"
    }

    /**
     * What ONE document of this surface is called — and for the five templates it is "note",
     * because that is what Michael's sentence says they are: "NOTES have template options".
     *
     * It used to be five different words ("writing", "grid", "jot", "pickings", "text note"), one
     * per surface, which was right while they were five surfaces: you made a writing, or a jot.
     * Under one Notes door they are one kind of thing made five ways, so the menus read "＋ New
     * note…", "✎ Name this note…", "🗑 Delete this note…" whichever template you are standing on,
     * and the template's own name is said by [label] where it matters. A row that said "New jot…"
     * on a page the chooser calls Jots would be the two vocabularies again, one line apart.
     *
     * Synthesize keeps "synthesis" — it is not one of the five, and its documents are not notes.
     */
    fun noun(surface: String): String = when (surface) {
        SYNTHESIZE -> "synthesis"
        else -> "note"
    }

    /**
     * The parameterised name index behind a ruled surface, or null for the surfaces whose stores are
     * hand-written ([WritePageStore], [SynthPageStore], [PickingsStore]).
     *
     * This is the ONE place the two new surfaces are told apart from each other. Everything else
     * about them — how they list, sync, rename, delete and title — is identical, so anywhere else
     * that switched on GRID versus JOT would be a second place for them to drift.
     */
    private fun storeOf(surface: String): LedgerDocumentStore? = when (surface) {
        GRID -> GridPageStore
        JOT -> JotPageStore
        else -> null
    }

    /** The key of a document's [sub]-th page — the convention CalendarDayFragment pages by. */
    fun subPageKey(base: String, sub: Int): String = if (sub == 0) base else "$base#$sub"

    /**
     * The documents of [surface] as seen from [date], in listing order.
     *
     * [knownPageKeys] is the caller's escape from re-reading a day file it already holds: the day
     * fragment passes the keys of the day it has loaded (noteStrokes + image/text page keys) and the
     * sub-page counts come straight off them. Callers that have no day open (the hub) pass nothing
     * and pay for a bounded scan of the day file instead — see [subPageCountFromFile].
     */
    fun forSurface(
        context: Context,
        surface: String,
        date: LocalDate,
        knownPageKeys: Set<String> = emptySet(),
    ): List<LedgerDocument> = when (surface) {
        WRITE -> {
            // The daily document leads. It is implicit — no index entry until you name it — which is
            // exactly what keeps every day already written under the bare "write" key intact: the
            // store gained a title for it, not custody of it.
            val dailyName = WritePageStore.nameOf(context, WritePageStore.DEFAULT_KEY, date)
            listOf(
                LedgerDocument(
                    WritePageStore.DEFAULT_KEY, dailyName ?: dateTitle(date), date, dailyName != null
                ) { subPageCount(context, WritePageStore.DEFAULT_KEY, date, knownPageKeys) }
            ) + WritePageStore.documents(context).map { p ->
                LedgerDocument(p.key, p.name.ifBlank { dateTitle(p.date) }, p.date, p.name.isNotBlank()) {
                    subPageCount(context, p.key, p.date, if (p.date == date) knownPageKeys else emptySet())
                }
            }
        }

        SYNTHESIZE -> {
            // The daily synthesis is not in SynthPageStore by design (its index holds only the named
            // topic pages), so it is supplied here the same way Write's daily document is. Unlike
            // Write's, it cannot be renamed: SynthPageStore keys its entries by key alone and every
            // day's daily page shares the key "synthesize", so an entry for one day's would stand in
            // for all of them. Michael has real topic data in that index; widening its key is a
            // migration, not an addition, so it isn't done here.
            listOf(
                LedgerDocument(SynthPageStore.DEFAULT_KEY, dateTitle(date), date, false) {
                    subPageCount(context, SynthPageStore.DEFAULT_KEY, date, knownPageKeys)
                }
            ) + SynthPageStore.list(context).map { p ->
                LedgerDocument(p.key, p.name.ifBlank { dateTitle(p.date) }, p.date, p.name.isNotBlank()) {
                    subPageCount(context, p.key, p.date, if (p.date == date) knownPageKeys else emptySet())
                }
            }
        }

        // Grid and Jot are Write's shape exactly — an implicit daily pad plus the named ones you
        // kept — so they are listed by the same three lines rather than by a branch of their own.
        // The only thing that differs between them is which index is asked, which is [storeOf].
        GRID, JOT -> {
            val store = storeOf(surface)!!
            val dailyName = store.nameOf(context, store.defaultKey, date)
            listOf(
                LedgerDocument(
                    store.defaultKey, dailyName ?: dateTitle(date), date, dailyName != null
                ) { subPageCount(context, store.defaultKey, date, knownPageKeys) }
            ) + store.documents(context).map { p ->
                LedgerDocument(p.key, p.name.ifBlank { dateTitle(p.date) }, p.date, p.name.isNotBlank()) {
                    subPageCount(context, p.key, p.date, if (p.date == date) knownPageKeys else emptySet())
                }
            }
        }

        // Boards are of the day and single-page: no drill-down to build, so the count is always 1.
        // PickingsStore.list already guarantees the default board leads the list even on a day whose
        // index file was never written.
        PICKINGS -> PickingsStore.list(context, date).map {
            LedgerDocument(it.key, it.name.ifBlank { dateTitle(date) }, date, it.name.isNotBlank()) { 1 }
        }

        // Text Notes are date-anchored, titled, single-page, and live in their own fragment rather
        // than on a day-page surface — so they are readable through this query (their "key" is the
        // note id, which only TextNotesFragment can act on) but nothing here navigates to them.
        TEXT_NOTES -> com.toolsboox.plugin.textnotes.TextNotesStore.load(context, date).map {
            LedgerDocument(it.id, it.title.ifBlank { dateTitle(date) }, date, it.title.isNotBlank()) { 1 }
        }

        else -> emptyList()
    }

    /**
     * Every date on which [surface] has documents the stores KNOW about, newest first.
     *
     * [forSurface] answers "what is on this day"; this answers "which days are there at all", which
     * is what a directory needs before it can offer a year → month → day spine. The two scopes of
     * the model show through here as they do everywhere else: the multi-page surfaces keep one
     * global index, so their dates come straight off it; the per-day surfaces keep one small file
     * per day, so their dates come off the FILENAMES — no file is opened to build this list.
     *
     * "Know about" is doing real work in that sentence. A day whose Write page was written on but
     * never named appears in no index and is not here; the by-date spine finds it anyway, because
     * its skeleton comes from [dayDates] and the day's implicit documents come from [forSurface]
     * once you open that day. Splitting it that way is what keeps the directory's cost proportional
     * to what you are looking at rather than to how long you have owned the device.
     */
    fun dates(context: Context, surface: String): List<LocalDate> = when (surface) {
        WRITE -> WritePageStore.list(context).map { it.date }.distinct().sortedDescending()
        SYNTHESIZE -> SynthPageStore.list(context).map { it.date }.distinct().sortedDescending()
        GRID, JOT -> storeOf(surface)!!.list(context).map { it.date }.distinct().sortedDescending()
        PICKINGS -> PickingsStore.dates(context)
        TEXT_NOTES -> com.toolsboox.plugin.textnotes.TextNotesStore.dates(context)
        else -> emptyList()
    }

    /**
     * Every day that has a day file on disk, newest first — filenames only, nothing decoded.
     *
     * This is the by-date spine's skeleton and the closest honest answer to "which days did I make
     * anything on". Reading each day to ask what it holds is what [NotesTagsFragment] does for a
     * WINDOW of days and is the right cost there; doing it for all of history while a directory
     * opens is not, so the directory shows the day and reads it only when you choose it.
     */
    fun dayDates(context: Context): List<LocalDate> {
        val root = ledgerRoot(context) ?: return emptyList()
        val calendar = File(root, "calendar")
        if (!calendar.exists()) return emptyList()
        val pattern = Regex("""^day-(\d{4})-(\d{2})-(\d{2})""")
        val out = sortedSetOf<LocalDate>(compareByDescending { it })
        runCatching {
            calendar.walkTopDown().maxDepth(3).forEach { f ->
                if (!f.isFile || !f.name.endsWith(".json")) return@forEach
                val m = pattern.find(f.name) ?: return@forEach
                runCatching {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull()?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    /** The one document [key] names, as seen from [date] — the chip's "where am I". */
    fun documentFor(
        context: Context,
        key: String?,
        date: LocalDate,
        knownPageKeys: Set<String> = emptySet(),
    ): LedgerDocument? {
        val surface = surfaceOf(key) ?: return null
        val base = key?.substringBefore('#') ?: return null
        return forSurface(context, surface, date, knownPageKeys).firstOrNull { it.key == base }
    }

    /**
     * Whether this particular document carries a "<base>#<n>" sub-page series — the same set
     * CalendarDayFragment.isSubPageableBase pages. Note that a NAMED synthesis does not: the topic
     * pages have never had a sub-page run, and this must agree with the fragment or the directory
     * would offer pages the pager can't reach. Asking here also keeps [subPageCount] from paying
     * for a day-file scan whose answer can only ever be 1.
     */
    private fun hasSubPageSeries(base: String): Boolean =
        WritePageStore.isWrite(base) || base == SynthPageStore.DEFAULT_KEY ||
            GridPageStore.isMine(base) || JotPageStore.isMine(base)

    /**
     * How many pages a document holds. Single-page documents are 1 by definition; a multi-page
     * document's count is one past its highest sub-index, because the series is dense from 0 by the
     * way the pager extends it.
     */
    fun subPageCount(context: Context, base: String, date: LocalDate, knownPageKeys: Set<String>): Int {
        if (!hasSubPageSeries(base)) return 1
        if (knownPageKeys.isNotEmpty()) {
            val max = knownPageKeys
                .filter { it.substringBefore('#') == base }
                .maxOfOrNull { it.substringAfter('#', "").toIntOrNull() ?: 0 }
            if (max != null) return max + 1
            // The caller HAS the day and this base isn't in it: the document is one blank page.
            return 1
        }
        return subPageCountFromFile(context, base, date)
    }

    /**
     * The count for callers with no day loaded, read off the day file without decoding it.
     *
     * A day file is JSON with the strokes and the base64 image payloads inline — megabytes, of which
     * the page keys are a few dozen bytes. Decoding it (or even reading it whole) to answer "how
     * many pages" would put a multi-megabyte parse on the path of opening a menu, on e-ink. So this
     * streams the file in chunks and looks only for the key pattern, carrying a small overlap so a
     * key straddling a chunk boundary still matches.
     *
     * A false high count would show a page that opens blank, which is the surface's normal answer to
     * a page never written; a false low count is impossible, since the page you are standing on is
     * always merged in by the caller.
     */
    private fun subPageCountFromFile(context: Context, base: String, date: LocalDate): Int {
        val file = dayFile(context, date) ?: return 1
        val pattern = Regex("\"" + Regex.escape(base) + "#(\\d+)\"")
        var max = 0
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(64 * 1024)
                var carry = ""
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    val window = carry + String(buffer, 0, read)
                    for (m in pattern.findAll(window)) {
                        val n = m.groupValues[1].toIntOrNull() ?: continue
                        if (n > max) max = n
                    }
                    // 64 chars is far longer than any key, so nothing can be cut in half unseen.
                    carry = window.takeLast(64)
                }
            }
        }
        return max + 1
    }

    /** The day file for [date], newest format first — the same Documents root every reader uses.
     *  The convention now lives in [com.toolsboox.ot.LedgerPaths] beside the root it hangs off,
     *  since the card index needs the identical answer and a second copy of a path layout is a
     *  second thing to keep in step. Wrapped, because that version asserts the root exists and this
     *  caller has always answered "no day file" rather than throwing on a device without one. */
    private fun dayFile(context: Context, date: LocalDate): File? =
        runCatching { com.toolsboox.ot.LedgerPaths.dayFile(context, date) }.getOrNull()

    private fun ledgerRoot(context: Context): File? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
        else File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
            "toolsBoox"
        )

    /** Pull the surface's registry from the other devices before listing it. */
    fun sync(context: Context, surface: String, date: LocalDate) {
        when (surface) {
            WRITE -> WritePageStore.sync(context)
            SYNTHESIZE -> SynthPageStore.sync(context)
            GRID, JOT -> storeOf(surface)!!.sync(context)
            PICKINGS -> PickingsStore.sync(context, date)
        }
    }

    /** Whether this surface's store can hold a name for [key] — what makes "Rename" honest. */
    fun canRename(surface: String, key: String): Boolean = when (surface) {
        // Write can name its daily page too: its index scopes DEFAULT_KEY entries by date.
        WRITE -> true
        // Grid and Jot for the same reason — [LedgerDocumentStore] is Write's rule parameterised,
        // so "today's grid" is nameable exactly as "today's writing" is.
        GRID, JOT -> true
        // The other two can only name what they minted; their daily/default page is the date.
        SYNTHESIZE -> key != SynthPageStore.DEFAULT_KEY
        PICKINGS -> true
        else -> false
    }

    /**
     * Create a document on [surface], named [name] (blank = let the store choose), homed on [date].
     *
     * TEXT IS NOT HERE, and deliberately. Every caller of this follows it with a navigation to
     * `doc.key` as a DAY-PAGE key; a text note's key is a note id that only TextNotesFragment can
     * act on, so returning one would hand the caller an address that resolves to a blank page named
     * "tn-1785…". The Notes chooser routes Text to its own fragment instead — one branch, in the
     * one place that offers the five, rather than a lie in the shared return type.
     */
    fun create(context: Context, surface: String, name: String, date: LocalDate): LedgerDocument? = when (surface) {
        WRITE -> WritePageStore.add(context, name, date)
            .let { LedgerDocument(it.key, it.name, it.date, true) { 1 } }
        SYNTHESIZE -> SynthPageStore.add(context, name, date)
            .let { LedgerDocument(it.key, it.name, it.date, true) { 1 } }
        GRID, JOT -> storeOf(surface)!!.add(context, name, date)
            .let { LedgerDocument(it.key, it.name, it.date, true) { 1 } }
        PICKINGS -> PickingsStore.add(context, date, name)
            .let { LedgerDocument(it.key, it.name, date, true) { 1 } }
        else -> null
    }

    // ── THE SEAM: MAKING A NOTE WITHOUT A HUMAN ───────────────────────────────────────────────
    //
    // Michael's next slice puts Ask's OUTPUTS into these templates: "text output in text notes …
    // those can bring in grams and outside links, so would go on a grid … Pick Harvest could have
    // the ai grab three notes and up to three quotes and two images filling up the picking
    // template from user given link."
    //
    // So a template cannot be a thing only a finger can choose. [startNote] is the one call that
    // says "make me a note of THIS template and tell me where it went", and it is deliberately in
    // the ot layer with the stores rather than in the picker that currently calls it — a chooser
    // that owned the only way to mint a note would have to be re-entered, headless, by whatever
    // wanted one, and that is the rewrite this exists to avoid.
    //
    // [create] stays exactly as it was for the four day-page templates, because two dozen callers
    // navigate its result as a page key; [startNote] is the wider door that also knows Text.

    /**
     * A note that has just been made: which template it is on, the [key] its content rides under,
     * the [date] it is filed on, and the [name] the store settled on.
     *
     * [key] IS THE ADDRESS, and what it addresses depends on the template — which is why
     * [isTextNote] is on the data rather than left for each caller to rediscover. For the four
     * day-page templates it is a note-page key: content rides the day JSON under it, and
     * `CalendarNavigator.toDayNote(date, key)` opens it. For Text it is a note id inside
     * `text-notes/notes-<date>.json`, which only TextNotesFragment can open. One branch, stated
     * once, rather than a shared type that quietly means two different things.
     */
    data class NewNote(
        val template: String,
        val key: String,
        val date: LocalDate,
        val name: String,
    ) {
        val isTextNote: Boolean get() = template == TEXT_NOTES
    }

    /**
     * Make a note on [template] and hand back where it went. The programmatic half of the Notes
     * door — the picker calls this, and so will anything else that needs a note to exist.
     *
     * [name] blank lets the store choose ("Jot 3", "Pickings 2"), which is what the picker passes:
     * a title is worth asking for when you know what the thing is, and inventing one before there
     * is anything on the page is the friction the Notes door removes. A generator that DOES know —
     * Ask naming a synthesis after the question — passes it here.
     *
     * [body] is TEXT ONLY, and null for everything else on purpose. A text note is its own content,
     * so it can be made complete in one call; the four page templates have no text field to fill —
     * their content is strokes, images and cards written into the day file under the returned
     * [NewNote.key], which is exactly what the gram placement and card paths already do. Passing a
     * body for one of those is a caller error and is ignored rather than silently half-honoured.
     *
     * Returns null only when [template] is not one of the five.
     */
    fun startNote(
        context: Context,
        template: String,
        name: String = "",
        date: LocalDate = LocalDate.now(),
        body: String = "",
    ): NewNote? {
        if (!isTemplate(template)) return null
        if (template == TEXT_NOTES) {
            val note = com.toolsboox.plugin.textnotes.TextNotesStore.addNote(context, date, name, body)
            return NewNote(template, note.id, date, note.title)
        }
        val doc = create(context, template, name, date) ?: return null
        return NewNote(template, doc.key, doc.date, doc.title)
    }

    /** Rename a document. [date] is the document's own date — it disambiguates Write's daily pages. */
    fun rename(context: Context, surface: String, key: String, name: String, date: LocalDate) {
        when (surface) {
            WRITE -> WritePageStore.rename(context, key, name, date)
            SYNTHESIZE -> SynthPageStore.rename(context, key, name)
            GRID, JOT -> storeOf(surface)!!.rename(context, key, name, date)
            PICKINGS -> PickingsStore.rename(context, date, key, name)
        }
    }

    /** The key of the surface's own implicit daily document — where to land after deleting the
     *  document you were standing in, so a delete never leaves you on a page that no longer exists. */
    fun defaultKey(surface: String): String = when (surface) {
        WRITE -> WritePageStore.DEFAULT_KEY
        SYNTHESIZE -> SynthPageStore.DEFAULT_KEY
        GRID, JOT -> storeOf(surface)!!.defaultKey
        PICKINGS -> PickingsStore.DEFAULT_KEY
        else -> "0"
    }

    // ── The two verbs of deletion ─────────────────────────────────────────────────────────────
    //
    // NOT ONE VERB. Michael's instruction, and the reason the naming work left this open: a Write
    // document is two things sitting in two places — a TITLE in the index, and INK in the day JSON
    // under the document's key — and "delete" can honestly mean either. One button would have to
    // pick, silently, and whichever it picked would be wrong half the time.
    //
    //  • [untitle] takes the title off and leaves the writing. It is the small, reversible one.
    //  • [forget] + [LedgerDocumentPages.erase] take the writing too. It is the one that asks first,
    //    with the page count in the question.

    /**
     * Whether taking the title off this document means anything — i.e. whether it has one.
     *
     * Same surfaces [canRename] allows, because untitling is a rename to nothing and cannot be
     * offered anywhere a name cannot be held.
     */
    fun canUntitle(surface: String, key: String): Boolean = canRename(surface, key)

    /**
     * Take the title off, KEEP the pages.
     *
     * This is a rename to blank, NOT a removal of the index entry, and that choice is the whole
     * safety of this path. For a MINTED document ("write-1753900000000") the index entry is the only
     * record that the document exists at all — its pages are reachable because that row is in the
     * list. Dropping the row would leave the ink on disk under a key nothing lists: precisely the
     * stranding this feature is not allowed to cause. A blank name costs one row and strands nothing.
     *
     * The stores already read a blank name as "never named" — [WritePageStore.nameOf] returns null
     * for it, and [forSurface] falls back to `name.ifBlank { dateTitle(p.date) }` with `named =
     * false` — so an untitled document is, to every listing in the app, exactly the document it was
     * before anyone named it: present, titled by its date, and one tap from being named again. That
     * is the undo, and it is why this path asks no confirmation.
     *
     * It also survives sync for free. The merge is a union with the LOCAL side winning on a shared
     * id, so a locally-blanked name beats the server's stale copy of the old one. Removing the row
     * would have lost that race — an absence loses a union — which is the same asymmetry
     * [LedgerDocumentTombstones] exists to work around for the destructive path.
     *
     * The handwritten title, if there is one, is NOT touched. That is what the blank-name choice
     * bought: [LedgerTitleInk] files a face against the same row this keeps alive, so untitle →
     * retitle returns the title he wrote rather than asking him to write it again. The page stops
     * DRAWING the face while the document has no name (see [CalendarDayPageNotes]), which is the
     * honest reading of "untitled" without being a deletion of anything.
     */
    fun untitle(context: Context, surface: String, key: String, date: LocalDate) {
        rename(context, surface, key, "", date)
    }

    /**
     * Whether the destructive path is offered on this surface at all.
     *
     * The multi-page document surfaces, which now means Grid and Jot as well: the whole reason the
     * destructive path is theirs is that they are the shape where a document's INK and its TITLE sit
     * in two different places, so both verbs are needed and neither can stand in for the other.
     *
     * Pickings and Text Notes stay excluded on purpose and for different reasons: a board's deletion
     * is really a question about its CARDS and belongs with the board, and Text Notes already has its
     * own Delete in its own fragment, where a note IS its record and the question doesn't arise.
     */
    fun canDelete(surface: String): Boolean =
        surface == WRITE || surface == SYNTHESIZE || surface == GRID || surface == JOT

    /**
     * Drop the index entry outright — the half of deletion that happens after the pages are gone.
     *
     * Only ever called with [LedgerDocumentPages.erase], never alone: an entry removed while its
     * pages survive is the stranding case, and the only reason it is safe here is that by this point
     * there is nothing left to reach.
     */
    fun forget(context: Context, surface: String, key: String, date: LocalDate) {
        // A key the store cannot hold a name for has no entry to drop — the daily Synthesize page is
        // the one that reaches here. Writing a headstone for it would put a permanent subtraction on
        // an id no entry can ever legitimately use, which is a small thing that gets confusing later.
        if (!canRename(surface, key)) return
        when (surface) {
            WRITE -> WritePageStore.delete(context, key, date)
            SYNTHESIZE -> SynthPageStore.delete(context, key)
            GRID, JOT -> storeOf(surface)!!.delete(context, key, date)
        }
        // The written title goes with the document, and ONLY here. [untitle] deliberately leaves it
        // — see that method — so this is the single path on which a face is destroyed, and it is
        // the one that has already erased the pages the face was a title for.
        LedgerTitleInk.forget(context, surface, key, date)
    }
}
