package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.da.v2.CalendarDay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE SUMMON THAT OUTLIVES ITS PAGE — AND ITS PROCESS.
 *
 * Michael, 2026-10-12, on the B6 (-322): "I just used the synth button on the synthesize page. It
 * says reading. It says writing back. It does not put in it's write back pic." Then: "But you know
 * I want parity :)".
 *
 * The B6 logcat of that session (scratchpad b6-322-logcat.txt, 13:28:42 → 13:30:05) proves the
 * chain. Three summons; each one's reply took 35–75 s; in that window the day page was rebuilt
 * three times (13:29:06, 13:29:50, 13:30:03 — the left-edge ladder, the Hub, a re-layout), so
 * every reply arrived to a DETACHED fragment and took the detached landing — and the detached
 * landing built a bare `CalendarDayService()` whose Moshi is field-injected, so its load threw
 * `lateinit property moshi has not been initialized`, the service swallowed that as "Corrupt day
 * file … treating as unwritten" (CalendarDayService.load), the landing returned without writing,
 * its runCatching logged a warning nobody sees, and the "❖ Written back on …" line was gated on the
 * DEAD fragment's isAdded. Three replies, nothing on the page, not a word on screen. The
 * correspondence sidecar had already recorded every turn (record-before-land), so the ink was
 * marked "read" too.
 *
 * Cited against 7f588917 (-322): CalendarDayFragment.kt:6372 (live only if THIS fragment still
 * isAdded) → :6383 (detached) → :6398 (`CalendarDayService()` bare) → CalendarDayService.kt:40
 * (`lateinit var moshi`, field-injected) → :246 ("Corrupt day file … treating as unwritten") →
 * CalendarDayFragment.kt:6384 (the words gated on the dead fragment) and :6369 (recorded before
 * landing). The sticky turn repeated it at :6659/:6669 with no timeout at all (:6640); the
 * drive-by's detached writer at :6857 had the same bare service. Logcat: b6-322-logcat.txt:37611,
 * :40275, :49343 (the three summons), :39788/:51046/:52738 (the three rebuilds), :52085, :52342,
 * :53062 (the three swallowed loads).
 *
 * What lives here is the part of the fix that is pure and testable on the JVM:
 *  • [PendingSummon] + [SynthPending] — a record written BEFORE the network call (and before the
 *    read, as a "reading" stub) so a summon survives the app being killed, restarted or
 *    reinstalled: on the next foreground it is asked again (or, if the reply had already come
 *    back, simply landed) into the day file, and he is told where it went. Cleared on landing or
 *    on a definite failure — and a definite failure always says so.
 *  • [SynthLanding] — ONE placement for the live page and the day file alike: below everything the
 *    page holds, flowing to the next sub-page rather than ever sitting over ink (the old detached
 *    writer never flowed: on a full page its cards landed past the canvas foot, invisible).
 *  • [SynthWords] — the words, so both apps say the same thing (Michael: parity).
 */
data class PendingSummon(
    /** A fresh id per summon — the log trail's handle (`synth[<first 8>]`). */
    val id: String,
    /** The day file the page lives in (yyyy-MM-dd). */
    val date: String,
    /** The page summoned from — the landing's key (sub-pages included: "synthesize#1"). */
    val pageKey: String,
    /** delta · selection · card · sticky — what was read. */
    val mode: String,
    val startedAt: Long,
    val stage: String = SynthPending.READING,
    /** The page's name as he knows it ("Synthesize", a topic's title) — for the words. */
    val pageName: String = "",
    /** The full request the correspondent is asked with; blank while still reading. */
    val content: String = "",
    /** The ink turn as the transcript sidecar records it once the reply lands. */
    val recordText: String = "",
    /** The stroke ids the watermark advances over once the reply lands. */
    val seen: List<String> = emptyList(),
    val attempts: Int = 0,
    /** The reply, once it came back — a kill between reply and landing must not cost a re-ask. */
    val answer: String = "",
    /** Words still owed to him (stage [SynthPending.NOTICE]): said on the next foreground. */
    val notice: String = "",
    /** The voice that answers — RESOLVED at summon time (the full persona name; "" = the
     *  Correspondent). A resumed summon asks and lands in this voice, never the page's current one:
     *  the question was put to it ([SynthVoice], Michael 2026-10-12). */
    val persona: String = "",
    /** The page's voice when it wasn't on this device — the Correspondent answered in its place,
     *  and the landing says so ([SynthWords.voiceMissing]). On the record (review of b29cabbc), so
     *  the words survive a re-ask and a restart; "" (and left off the wire) otherwise. */
    val missing: String = "",
    /** The transcript tail as it rides [content], and how many turns it was built from — so a voice
     *  that changes at ask time (gone since the summon) is asked with the speakers relabelled for
     *  IT, not for the voice the tail was built for. Left off the wire when empty. */
    val tail: String = "",
    val tailTurns: Int = 0,
    /** ❖ Synthesize this list / article (mode "list" / "article", [com.toolsboox.plugin.calendar.ui.SynthListRun]):
     *  what was sent, as JSON — the page's cards are made from it when the reply lands, even after a kill. */
    val request: String = "",
    /** A summon on a page that carries a Sources card: that card's resolver lines
     *  ([SynthSources.sourcesCard]) — the reply is cleaned against them and a newly cited [n] gets its card. */
    val sources: String = "",
    /** ❖ Synthesize this list / article: the day its All Picks pick goes on (yyyy-MM-dd), fixed the
     *  first time the reply goes to land — a landing resumed on a later day finds the pick there
     *  instead of placing a second one. Blank until then. */
    val pickDate: String = "",
)

object SynthPending {

    // Stages. READING → ASKING → REPLIED → (landed: the record is gone). NOTICE = done, but the
    // app was in the background when it finished, so the words wait for the next foreground.
    const val READING = "reading"
    const val ASKING = "asking"
    const val REPLIED = "replied"
    const val NOTICE = "notice"

    /** The wall-clock bound on one ask — both apps (Michael's ruling: a timed-out summon says so;
     *  iOS had none before 2026-10-12). */
    const val REPLY_TIMEOUT_MS = 120_000L

    /** Asks per summon, the first included: one re-ask after a kill, then it is a definite failure. */
    const val MAX_ATTEMPTS = 2

    /** A summon older than this is not re-asked on resume — the page has moved on; say so instead. */
    const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    /** The share of [REPLY_TIMEOUT_MS] the site-reference lookup may take before the ask goes
     *  without it — both apps (iOS SynthCorrespondent.referencesBudget). On the B6 that lookup is
     *  the step that ran out of memory (-322 logcat :51882); it must never eat the reply's time. */
    const val REFERENCES_BUDGET_MS = 25_000L

    /** Why an ask came back with no reply. */
    enum class Failure {
        /** No response at all: the connection dropped (the screen slept, Wi-Fi went down) or
         *  never came up (no route / DNS on wake). */
        LOST_CONNECTION,
        /** Nothing within the bound ([REPLY_TIMEOUT_MS], or the socket's own read timeout). */
        TIMEOUT,
        /** A response came back with no usable reply — an HTTP error, a blank or unreadable body. */
        REFUSED,
    }

    /**
     * THE ONE RE-ASK RULE, both apps (2026-10-12 review, item 9 — iOS re-asked any summon the app
     * had ever left, so a genuine timeout after he came back ran to 240 s; Android never re-asked,
     * even when the B6 slept through the ask). Asked again — once, within [MAX_ATTEMPTS] — when:
     *  • the connection was LOST, wherever he was; or
     *  • it failed while the app was AWAY ([away]: not in front when the failure came) — a timeout
     *    the sleep caused is not his answer.
     * A refusal is always definite; a timeout while he was on the app is a definite "No reply".
     * The re-ask goes at once when he is on the app; otherwise the record is left on disk exactly as
     * a kill leaves it, and the next foreground pass asks it again.
     */
    fun reasks(failure: Failure, away: Boolean, attempts: Int): Boolean =
        attempts < MAX_ATTEMPTS && when (failure) {
            Failure.LOST_CONNECTION -> true
            Failure.TIMEOUT -> away
            Failure.REFUSED -> false
        }

    /** The summon on [pageKey] of [date] that is still on its way, if any — one summon per page at a
     *  time (the -322 field log: three taps in ~80 s, each asking about the same unread ink, each
     *  of which would now land). [carried] = the records this process is carrying. */
    fun carrying(carried: Collection<PendingSummon>, date: String, pageKey: String): PendingSummon? =
        carried.firstOrNull { it.date == date && it.pageKey == pageKey && it.stage != NOTICE }

    /** What resuming a record should do. */
    enum class Action {
        /** This process is still carrying it — leave it alone. */
        WAIT,
        /** The request is known and no reply came back: ask again. */
        ASK,
        /** The reply came back but never landed: land it, no re-ask. */
        LAND,
        /** Killed before the read finished — nothing to ask with: say so, clear. */
        CUT_OFF,
        /** Asked [MAX_ATTEMPTS] times without a reply: say so, clear. */
        GIVE_UP,
        /** Too old to re-ask: say so, clear. */
        EXPIRED,
        /** Already finished; only the words are owed. */
        SAY,
    }

    /** The resume decision — pure, so the ladder above is pinned by tests. */
    fun actionFor(rec: PendingSummon, inFlight: Set<String>, now: Long): Action = when {
        rec.id in inFlight -> Action.WAIT
        rec.stage == NOTICE -> Action.SAY
        rec.stage == REPLIED && rec.answer.isNotBlank() -> Action.LAND
        now - rec.startedAt > MAX_AGE_MS -> Action.EXPIRED
        rec.content.isBlank() -> Action.CUT_OFF
        rec.attempts >= MAX_ATTEMPTS -> Action.GIVE_UP
        else -> Action.ASK
    }

    // ── The wire ────────────────────────────────────────────────────────────────────────────

    fun toJson(r: PendingSummon): JSONObject = JSONObject()
        .put("id", r.id).put("date", r.date).put("key", r.pageKey).put("mode", r.mode)
        .put("startedAt", r.startedAt).put("stage", r.stage).put("pageName", r.pageName)
        .put("content", r.content).put("record", r.recordText)
        .put("seen", JSONArray(r.seen)).put("attempts", r.attempts)
        .put("answer", r.answer).put("notice", r.notice).put("persona", r.persona)
        .put("request", r.request).put("sources", r.sources).put("pickDate", r.pickDate)
        .apply {
            if (r.missing.isNotEmpty()) put("missing", r.missing)
            if (r.tail.isNotEmpty() && r.tailTurns > 0) put("tail", r.tail).put("tailTurns", r.tailTurns)
        }

    /** Tolerant read (opt*): a row without an id, date or key is dropped, never fatal. */
    fun fromJson(o: JSONObject): PendingSummon? {
        val id = o.optString("id"); val date = o.optString("date"); val key = o.optString("key")
        if (id.isBlank() || date.isBlank() || key.isBlank()) return null
        val seen = mutableListOf<String>()
        o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(seen::add) }
        return PendingSummon(
            id = id, date = date, pageKey = key, mode = o.optString("mode"),
            startedAt = o.optLong("startedAt"), stage = o.optString("stage", READING),
            pageName = o.optString("pageName"), content = o.optString("content"),
            recordText = o.optString("record"), seen = seen, attempts = o.optInt("attempts"),
            answer = o.optString("answer"), notice = o.optString("notice"),
            persona = o.optString("persona"),
            missing = o.optString("missing"),
            tail = o.optString("tail"), tailTurns = o.optInt("tailTurns"),
            request = o.optString("request"), sources = o.optString("sources"),
            pickDate = o.optString("pickDate"),
        )
    }

    fun parse(text: String): MutableList<PendingSummon> {
        if (text.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun serialize(list: List<PendingSummon>): String =
        JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    /** Replace by id, or append. */
    fun upsert(list: List<PendingSummon>, rec: PendingSummon): List<PendingSummon> {
        val out = list.filterNot { it.id == rec.id }.toMutableList()
        out.add(rec)
        return out
    }

    fun without(list: List<PendingSummon>, id: String): List<PendingSummon> = list.filterNot { it.id == id }

    // ── The file ────────────────────────────────────────────────────────────────────────────
    //
    // `correspondence/pending.json`, beside the transcript sidecar ([SynthCorrespondence]) — the
    // device's own bookkeeping, never synced (a summon is this device's network round-trip).

    private val lock = Any()

    fun file(context: Context): File =
        File(File(context.filesDir, "correspondence").apply { mkdirs() }, "pending.json")

    fun all(context: Context): List<PendingSummon> = all(file(context))
    fun put(context: Context, rec: PendingSummon) = put(file(context), rec)
    fun remove(context: Context, id: String) = remove(file(context), id)
    fun get(context: Context, id: String): PendingSummon? = all(context).firstOrNull { it.id == id }

    // The File doors — the same store, reachable from a JVM test.

    fun all(f: File): List<PendingSummon> = synchronized(lock) {
        if (!f.exists()) emptyList() else parse(runCatching { f.readText() }.getOrDefault(""))
    }

    fun put(f: File, rec: PendingSummon) = synchronized(lock) { write(f, upsert(all(f), rec)) }

    fun remove(f: File, id: String) = synchronized(lock) { write(f, without(all(f), id)) }

    private fun write(f: File, list: List<PendingSummon>) {
        runCatching {
            if (list.isEmpty()) { f.delete(); return }
            // Write-then-rename: a kill mid-write must not corrupt the very record that exists to
            // survive kills.
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(serialize(list))
            if (!tmp.renameTo(f)) { f.writeText(serialize(list)); tmp.delete() }
        }
    }

    // ── The reply as cards ──────────────────────────────────────────────────────────────────

    /** Split a reply on paragraphs, packed to card-sized chunks: one thought per face, never a wall.
     *  (Moved here from CalendarDayFragment.synthReplyChunks unchanged — shared by every landing.) */
    fun replyChunks(answer: String): List<String> {
        val chunks = mutableListOf<String>()
        var cur = StringBuilder()
        for (para in answer.split(Regex("\\n\\s*\\n"))) {
            val p = para.trim().ifBlank { continue }
            if (cur.isNotEmpty() && cur.length + p.length > 520) { chunks.add(cur.toString()); cur = StringBuilder() }
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(p)
            while (cur.length > 620) {   // a single monster paragraph still splits
                val cut = cur.lastIndexOf(". ", 560).let { if (it < 200) 560 else it + 1 }
                chunks.add(cur.substring(0, cut).trim()); cur = StringBuilder(cur.substring(cut).trim())
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        if (chunks.isEmpty()) chunks.add(answer.trim())
        return chunks
    }
}

/** One rendered reply face: the chunk, its PNG as base64, and height/width of the bitmap. */
data class SynthFace(val text: String, val data: String, val aspect: Float)

/** Where a reply landed: the FIRST card's page and rect (what the reveal brings into view), the
 *  LAST card's page (a reply that flowed says "continued on page N"), and the card count.
 *  [already] = it was standing before this landing (a resumed summon's first try beat the kill) —
 *  nothing was placed, and the log trail says so rather than "landed". */
data class SynthLanded(
    val firstKey: String, val x: Float, val y: Float, val w: Float, val h: Float,
    val lastKey: String, val cards: Int, val already: Boolean = false,
)

/**
 * THE ONE PLACEMENT — live page and day file alike. Ink is sacred: the reply sits below everything
 * the page holds (strokes AND elements) and flows to a fresh sub-page when the page is full; it
 * never shrinks over ink and never lands past the canvas foot.
 */
object SynthLanding {
    /** A sticky is stout and modest, not a full-width card. */
    const val CARD_W = 560f
    const val LEFT = 90f
    const val TOP = 140f
    const val GAP = 24f
    const val BELOW = 40f
    /** The last y a card may run to before the reply flows (canvas 1872 tall). */
    const val FOOT = 1832f

    fun contentBottom(day: CalendarDay, key: String): Float {
        var bottom = 100f
        day.noteStrokes[key]?.forEach { s -> s.strokePoints.forEach { p -> if (p.y > bottom) bottom = p.y } }
        day.textElements.filter { it.pageKey == key }.forEach { if (it.y + it.height > bottom) bottom = it.y + it.height }
        day.imageElements.filter { it.page == key }.forEach { if (it.y + it.height > bottom) bottom = it.y + it.height }
        return bottom
    }

    /** The next unused sub-page of [of]'s run: "<base>#<max+1>". */
    fun nextSubPage(day: CalendarDay, of: String): String {
        val base = of.substringBefore('#')
        val subs = (day.noteStrokes.keys + day.textElements.map { it.pageKey } + day.imageElements.map { it.page })
            .filter { it == base || it.startsWith("$base#") }
            .mapNotNull { it.substringAfter('#', "0").toIntOrNull() }
        return "$base#${(subs.maxOrNull() ?: 0) + 1}"
    }

    /**
     * Append [faces] to [day] below [pageKey]'s content (mutates [day.imageElements]); [sourceLink]
     * is the turn's page (`ledger://<date>/<key>`) so a travelled card navigates home. [label] is the
     * voice that wrote it ([SynthVoice.label]: "❖ The Correspondent", "❖ Spiral").
     */
    fun place(
        day: CalendarDay, pageKey: String, faces: List<SynthFace>, sourceLink: String,
        label: String = SynthVoice.label(SynthVoice.CORRESPONDENT),
    ): SynthLanded {
        var key = pageKey
        var y = (contentBottom(day, key) + BELOW).coerceAtLeast(TOP)
        var first: SynthLanded? = null
        for (f in faces) {
            val h = CARD_W * f.aspect
            if (y + h > FOOT && y > 200f) {   // page full → the reply flows; it never shrinks over ink
                key = nextSubPage(day, key)
                y = TOP
            }
            day.imageElements.add(ImageElement(
                x = LEFT, y = y, width = CARD_W, height = h, data = f.data, page = key,
                cardText = f.text, sourceLink = sourceLink, sourceLabel = label))
            if (first == null) first = SynthLanded(key, LEFT, y, CARD_W, h, key, 0)
            y += h + GAP
        }
        val f0 = first ?: SynthLanded(pageKey, LEFT, y, CARD_W, 0f, pageKey, 0)
        return f0.copy(lastKey = key, cards = faces.size)
    }

    /**
     * A FRESH page's reply — the drive-by's minted topic, whose only content is the [opening] card
     * (the circled material). The same [place] as every landing: under the opening card, flowing to
     * "<key>#1" when the page is full (a long drive-by reply used to stack past the foot, unseen).
     * Returns the reply cards to add and where they went.
     */
    fun placeUnder(
        date: LocalDate, opening: ImageElement, faces: List<SynthFace>, sourceLink: String,
        label: String = SynthVoice.label(SynthVoice.CORRESPONDENT),
    ): Pair<List<ImageElement>, SynthLanded> {
        val scratch = CalendarDay(year = date.year, month = date.monthValue, day = date.dayOfMonth,
            startHour = null, imageElements = mutableListOf(opening))
        val landed = place(scratch, opening.page, faces, sourceLink, label)
        return scratch.imageElements.drop(1) to landed
    }

    /**
     * The reply is ALREADY standing on [pageKey]'s run (its first face's words on a card wearing
     * [label] — the record's voice, so a resumed Spiral reply is found as Spiral) — a resumed summon
     * whose landing beat the kill but whose record didn't get cleared. Landing it again would double
     * the card; this answers where it already is instead.
     */
    fun alreadyLanded(
        day: CalendarDay, pageKey: String, faces: List<SynthFace>,
        label: String = SynthVoice.label(SynthVoice.CORRESPONDENT),
    ): SynthLanded? {
        val first = faces.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: return null
        val base = pageKey.substringBefore('#')
        val mine = day.imageElements.filter {
            (it.page == base || it.page.startsWith("$base#")) && it.sourceLabel == label
        }
        // Every face, not just the first — a short reply ("Yes.") can repeat across turns.
        val texts = mine.map { it.cardText }.toSet()
        if (faces.any { it.text !in texts }) return null
        val head = mine.firstOrNull { it.cardText == first } ?: return null
        val lastText = faces.last().text
        val tail = mine.lastOrNull { it.cardText == lastText } ?: head
        return SynthLanded(head.page, head.x, head.y, head.width, head.height, tail.page, faces.size, already = true)
    }

    /**
     * THE DETACHED LANDING — no page standing on the day, so the reply goes straight into the day
     * file: load → [place] → save under the day lock, read-guarded: null when the day isn't on this
     * device (it didn't land, and the caller says so), and [com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException]
     * when it is there but won't read — never written over, and the caller keeps the reply OWED
     * ([SynthWords.replyKept]): a reply is never dropped because its page won't read.
     *
     * The -322 field bug (Michael, 2026-10-12) was a bare `CalendarDayService()` here: its
     * field-injected Moshi was never set, every load threw "lateinit property moshi has not been
     * initialized", the service called that a corrupt day, and every detached reply vanished. The
     * service now takes its Moshi in the constructor (there is no bare one to build); callers pass
     * [com.toolsboox.ui.share.ShareActions.service], i.e. CalendarDayService.standalone.
     */
    fun landInDayFile(
        service: com.toolsboox.plugin.calendar.fi.CalendarDayService, root: File,
        date: LocalDate, pageKey: String, faces: List<SynthFace>, resumed: Boolean = false,
        label: String = SynthVoice.label(SynthVoice.CORRESPONDENT),
    ): SynthLanded? = DayLocks.withDay(date) {
        // The editor's door (day-file guard): null when the day isn't on this device; a file that
        // is there but won't read THROWS UnreadableDayException — the caller keeps the reply owed
        // and says which page won't read, and nothing is written over it.
        val day = service.loadToEdit(root, date) ?: return@withDay null
        // A RESUMED landing whose first try beat the kill is already standing — don't double it.
        if (resumed) alreadyLanded(day, pageKey, faces, label)?.let { return@withDay it }
        val landed = place(day, pageKey, faces, "ledger://$date/$pageKey", label)
        service.save(root, date, day)
        landed
    }
}

/**
 * THE WORDS — one set, both apps (Michael, 2026-10-12: "you know I want parity"). iOS mirrors
 * these strings exactly (LedgerCore's SynthWords).
 */
object SynthWords {
    const val READING_INK = "❖ Reading the ink…"
    const val READING_CARD = "❖ Reading the card…"
    const val READING_STICKY = "❖ Reading the sticky…"
    const val READING_CARDS = "❖ Reading the cards…"
    const val WRITING_BACK = "❖ Writing back…"
    const val NO_REPLY = "❖ No reply came back — try the summon again."

    // The rest of the summon's words (2026-10-12 review, item 16) — each was a literal in one app
    // and a different literal in the other. iOS's line wins where iOS already had a ❖ line.

    /** Summoned with no ink the correspondent hasn't read (a first summon on a blank page too). */
    const val NOTHING_NEW = "❖ Write something first — there's no new ink to read."
    /** The ink came back from every reader blank. */
    const val INK_UNREADABLE = "❖ That ink didn't come back readable — try underlining it."
    const val CARD_UNREADABLE = "❖ The card holds nothing readable."
    const val CARDS_UNREADABLE = "❖ The cards hold nothing readable."
    /** The sticky turn: the face itself wouldn't decode / nothing of his on it beyond the thread. */
    const val STICKY_UNREADABLE = "❖ The sticky's face couldn't be read."
    const val NOTHING_NEW_ON_STICKY = "❖ Nothing new on the card — write on it first."
    /** A card that already fronts a conversation page is opened, never re-minted. */
    const val CONVERSATION_CONTINUES = "❖ The conversation continues on its own page."
    /** A drive-by whose ask failed — and so minted nothing (empty-mint hygiene). */
    const val DRIVEBY_NO_REPLY = "❖ No reply came back — nothing was made; try again."

    /** The Settings room that holds the AI key, as each app names it (iOS: "AI"). */
    const val AI_KEY_ROOM_ANDROID = "Ask my Ledger"

    /** Several owed lines at once (the next foreground after a kill or a sleep) — one message,
     *  one line each, rather than each replacing the last (iOS joins the same way). */
    fun joined(lines: List<String>): String = lines.filter { it.isNotBlank() }.joinToString("\n")

    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** "Oct 11", or "Dec 31, 2025" for another year than [today]'s. */
    fun day(date: LocalDate, today: LocalDate): String =
        (if (date.year == today.year) DAY else DAY_YEAR).format(date)

    /** Summoned again on a page whose reply is still on its way: one summon per page at a time
     *  (the -322 field log: three taps in ~80 s, each asking about the same ink). */
    fun stillWriting(place: String): String = "❖ Still writing back on $place…"

    /** No AI key: "❖ No AI key set — Settings › Ask my Ledger." (iOS: "… Settings › AI."). */
    fun noKey(room: String): String = "❖ No AI key set — Settings › $room."

    /** The drive-by landed on the page he is standing on. "Open", not tap/hold — each app opens a
     *  sticky with its own gesture, and the line must be true on both. */
    fun driveByHere(name: String): String = "❖ “$name” — the reply is on your page; open the sticky to continue."

    /** The drive-by landed in the day file — the page he circled on isn't the one in front of him.
     *  [day] is [SynthWords.day]'s "Oct 11" form, never an ISO date. */
    fun driveByElsewhere(name: String, day: String): String = "❖ “$name” — the sticky is on $day."

    /** "Synthesize" for the daily page, a topic's own title, else the key as a word. */
    fun pageName(base: String, title: String?): String = when {
        !title.isNullOrBlank() -> title.trim()
        base == SynthPageStore.DEFAULT_KEY || base.startsWith("synthesize-") -> "Synthesize"
        else -> base.replaceFirstChar { it.uppercase() }
    }

    /** "Synthesize", "Synthesize · page 2", "Synthesize · Oct 11" — where a landing went, from the
     *  page he is looking at's point of view. Sub-page "#n" reads as page n+1, the pager's number.
     *  A named topic ("synthesize-…") is reached by its name, not its home day, so it never carries
     *  a date. */
    fun place(name: String, key: String, date: LocalDate, today: LocalDate): String {
        val sub = key.substringAfter('#', "").toIntOrNull() ?: 0
        val named = key.substringBefore('#').startsWith("synthesize-")
        val sb = StringBuilder(name)
        if (sub > 0) sb.append(" · page ").append(sub + 1)
        if (!named && date != today) sb.append(" · ").append(day(date, today))
        return sb.toString()
    }

    /** "❖ Written back on Synthesize." / "… — continued on page 3." */
    fun writtenBack(place: String, continuedOnPage: Int? = null): String =
        if (continuedOnPage == null) "❖ Written back on $place."
        else "❖ Written back on $place — continued on page $continuedOnPage."

    // ── The voice's words (Michael, 2026-10-12: Synth with a persona). [persona] is the voice's
    //    name, full or short ("" = the Correspondent, whose lines are today's, byte for byte). ──

    private fun voiced(persona: String): String? =
        if (SynthVoice.isCorrespondent(persona)) null else SynthVoice.shortName(persona)

    /** "❖ Writing back…" / "❖ Writing back as Spiral…" */
    fun writingBack(persona: String): String =
        voiced(persona)?.let { "❖ Writing back as $it…" } ?: WRITING_BACK

    /** Today's "❖ Written back on …" for the Correspondent; "❖ Spiral wrote back on Synthesize."
     *  (and "… — continued on page 3.") for a persona. */
    fun writtenBack(place: String, continuedOnPage: Int?, persona: String): String {
        val v = voiced(persona) ?: return writtenBack(place, continuedOnPage)
        return if (continuedOnPage == null) "❖ $v wrote back on $place."
        else "❖ $v wrote back on $place — continued on page $continuedOnPage."
    }

    /** The page's voice isn't on this device, so the Correspondent answered instead. */
    fun voiceMissing(persona: String, place: String): String =
        "❖ ${SynthVoice.shortName(persona)} isn't on this device — the Correspondent wrote back on $place."

    /** The header's pick: "❖ Spiral answers next." / "❖ The Correspondent answers next." */
    fun answersNext(persona: String): String = "❖ ${SynthVoice.shortName(SynthVoice.normalize(persona))} answers next."

    /** A card that fronts a conversation opened it, in a chosen voice: "❖ The conversation
     *  continues on its own page — Spiral answers next." (the Correspondent: today's line). */
    fun conversationContinues(persona: String): String =
        voiced(persona)?.let { "❖ The conversation continues on its own page — $it answers next." } ?: CONVERSATION_CONTINUES

    /** The page number a flowed reply continued on, or null when it stayed on its first page. */
    fun continuedOn(landed: SynthLanded): Int? =
        if (landed.lastKey == landed.firstKey) null
        else (landed.lastKey.substringAfter('#', "").toIntOrNull() ?: 0) + 1

    /** A summon that finished away from the page names where; one on the page keeps the plain line. */
    fun noReply(place: String?): String =
        if (place.isNullOrBlank()) NO_REPLY else "❖ No reply came back for $place — try the summon again."

    fun couldNotWrite(place: String): String =
        "❖ The reply came back but couldn't be written on $place — try the summon again."

    /**
     * The reply came back, but the page it lands on is there and WON'T READ (the day-file guard):
     * nothing is written over that page, and the reply is KEPT — its record stays a reply to land,
     * landed on a later foreground once the page reads. [whichPage] is
     * [com.toolsboox.plugin.calendar.fi.CalendarDayService.pageName]'s words ("today's page", "the
     * Oct 3 page"); the "Couldn't … — <page> won't read" shape every refused write says.
     */
    fun replyKept(place: String, whichPage: String): String =
        "❖ Couldn't write back on $place — $whichPage won't read. The reply is kept; it lands once the page reads."

    /**
     * The reply stands, but the cards for the numbers it newly cites (a page made from a list) were
     * refused because the page won't read: the record stays a reply to land, and the next pass finds
     * the reply standing and adds only the missing cards. iOS's `citedCardsKept`, string for string.
     */
    fun citedCardsKept(whichPage: String): String =
        "❖ Couldn't add the cited cards — $whichPage won't read. They're kept and land once it does."

    /**
     * A drive-by circled on a page that won't read — refused BEFORE anything is asked, so no reply
     * is ever left with nowhere to land (a drive-by keeps no record to land later), and nothing is
     * minted: the guard's own refusal. iOS's `driveByRefused`, string for string.
     */
    fun driveByRefused(whichPage: String): String = "❖ Couldn't write back — $whichPage won't read"

    /**
     * A kept reply's line, said at most once per [windowMs] (iOS's DayPageNotice: "a page that
     * won't read refuses every pen-up, and one line says it") — every return to the app lands every
     * kept reply again, and the same refusal back to back is noise. Process memory only.
     */
    class Repeats(private val windowMs: Long = KEPT_REPEAT_MS) {
        private var last: Pair<String, Long>? = null

        /** True when [line] should be said now (and remembers it); false within the window. */
        @Synchronized
        fun shouldSay(line: String, now: Long): Boolean {
            val l = last
            if (l != null && l.first == line && now - l.second in 0 until windowMs) return false
            last = line to now
            return true
        }
    }

    /** iOS's window for a repeated refusal line (DayFileGuard.swift's Presenter: 15 s). */
    const val KEPT_REPEAT_MS = 15_000L

    fun cutOff(place: String): String =
        "❖ The summon on $place stopped before it read the page — try it again."
}
