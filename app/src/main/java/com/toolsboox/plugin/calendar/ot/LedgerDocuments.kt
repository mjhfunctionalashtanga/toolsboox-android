package com.toolsboox.plugin.calendar.ot

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    /** The date a never-renamed document is titled by. ISO, matching every other date the app shows. */
    fun dateTitle(date: LocalDate): String = date.toString()

    /**
     * Which document surface a note-page key belongs to, or null for the surfaces that have no
     * documents to list (the plain day, the ritual stations, the numeric notes, and grid/sketch —
     * those last two are sub-pageable but hold exactly ONE implicit document per day, so a chip over
     * them would promise siblings that cannot exist; their ‹ N › pager already lists their pages).
     */
    fun surfaceOf(key: String?): String? {
        val base = key?.substringBefore('#') ?: return null
        return when {
            PickingsStore.isPickings(base) -> PICKINGS
            SynthPageStore.isSynth(base) -> SYNTHESIZE
            WritePageStore.isWrite(base) -> WRITE
            else -> null
        }
    }

    /** The glyph the hub, the chip and the directory all wear for this surface — one vocabulary. */
    fun glyph(surface: String): String = when (surface) {
        WRITE -> "✍"
        SYNTHESIZE -> "🔬"
        PICKINGS -> "❝"
        else -> "⌗"
    }

    /** The SURFACE's name — what the hub row and the folder call it. */
    fun label(surface: String): String = when (surface) {
        WRITE -> "Write"
        SYNTHESIZE -> "Synthesize"
        PICKINGS -> "Pickings"
        else -> "Text Notes"
    }

    /** What ONE of this surface's documents is called. "New Write…" is a door; "New writing…" is
     *  a thing you are about to make, which is what the button actually does. */
    fun noun(surface: String): String = when (surface) {
        WRITE -> "writing"
        SYNTHESIZE -> "synthesis"
        PICKINGS -> "pickings"
        else -> "text note"
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
        WritePageStore.isWrite(base) || base == SynthPageStore.DEFAULT_KEY

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

    /** The day file for [date], newest format first — the same Documents root every reader uses. */
    private fun dayFile(context: Context, date: LocalDate): File? {
        val root = ledgerRoot(context) ?: return null
        val y = date.format(DateTimeFormatter.ofPattern("yyyy"))
        val m = date.format(DateTimeFormatter.ofPattern("MM"))
        val d = date.format(DateTimeFormatter.ofPattern("dd"))
        val dir = File(root, "calendar/$y/$m")
        return listOf(File(dir, "day-$y-$m-$d-v2.json"), File(dir, "day-$y-$m-$d.json"))
            .firstOrNull { it.exists() }
    }

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
            PICKINGS -> PickingsStore.sync(context, date)
        }
    }

    /** Whether this surface's store can hold a name for [key] — what makes "Rename" honest. */
    fun canRename(surface: String, key: String): Boolean = when (surface) {
        // Write can name its daily page too: its index scopes DEFAULT_KEY entries by date.
        WRITE -> true
        // The other two can only name what they minted; their daily/default page is the date.
        SYNTHESIZE -> key != SynthPageStore.DEFAULT_KEY
        PICKINGS -> true
        else -> false
    }

    /** Create a document on [surface], named [name] (blank = let the store choose), homed on [date]. */
    fun create(context: Context, surface: String, name: String, date: LocalDate): LedgerDocument? = when (surface) {
        WRITE -> WritePageStore.add(context, name, date)
            .let { LedgerDocument(it.key, it.name, it.date, true) { 1 } }
        SYNTHESIZE -> SynthPageStore.add(context, name, date)
            .let { LedgerDocument(it.key, it.name, it.date, true) { 1 } }
        PICKINGS -> PickingsStore.add(context, date, name)
            .let { LedgerDocument(it.key, it.name, date, true) { 1 } }
        else -> null
    }

    /** Rename a document. [date] is the document's own date — it disambiguates Write's daily pages. */
    fun rename(context: Context, surface: String, key: String, name: String, date: LocalDate) {
        when (surface) {
            WRITE -> WritePageStore.rename(context, key, name, date)
            SYNTHESIZE -> SynthPageStore.rename(context, key, name)
            PICKINGS -> PickingsStore.rename(context, date, key, name)
        }
    }
}
