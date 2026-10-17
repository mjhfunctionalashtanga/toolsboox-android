package com.toolsboox.plugin.calendar.ot

/**
 * ❖ SYNTHESIZE THIS LIST / ARTICLE → A PAGE THAT LINKS BACK — the pure half, one design on both apps.
 *
 * Michael, 2026-10-12: "the response should come back with links to those articles within
 * ledgable itself so you can follow up on it from within" → "That goes on both android and ios.
 * And yeah, it can create that page as a new synth page, and add a pick to all picks".
 * (Design: scratchpad synth-list-links-design.md §1, §2, §4, §8, §9.)
 *
 * Everything that decides WORDS or BYTES lives here, with no `android.*` and no JSON library, so the
 * JVM suite and iOS's `SynthSources.swift` read ONE golden file, `synth-links-cases.json`, byte for
 * byte (both suites pin its SHA-256 — the ArticleKey precedent). What it decides:
 *  • the MATERIAL the engine reads — the list as shown, numbered [1]…[n], budgeted to 9000 code
 *    points with every blurb squeezed evenly before any item drops ([material], [articleMaterial]);
 *  • the CITATIONS in the reply — which [n] are real, the reply cleaned of the rest, never a guessed
 *    number ([find], [clean], [strip]);
 *  • the SOURCES CARD — the page's resolver of record: one line per article sent, its link and its
 *    server id riding the line ([sourcesCard], [parseSources], [encodeLink]);
 *  • the TAP ROWS a card offers ([role], [rows]) and every word he reads ([Kind], [reading], …).
 *
 * UNITS ARE CODE POINTS (Kotlin `codePointCount`, Swift `unicodeScalars`), never UTF-16 units or
 * grapheme clusters: an emoji counts the same on both apps, and a cut never splits a surrogate pair.
 * WHITESPACE IS A SPELLED-OUT SET ([isWs]) because Java's `\s` and Swift's `.whitespaces` disagree.
 * The regexes say `[0-9]`, never `\d` (Swift's `\d` takes Arabic-Indic digits; Java's doesn't).
 */
object SynthSources {

    const val BUDGET = 9000
    const val HEADER_RESERVE = 80
    private const val BODY = BUDGET - HEADER_RESERVE
    const val MAX_ITEMS = 40
    const val TITLE_CAP = 160
    const val FEED_CAP = 60
    const val BLURB_CAP = 220
    const val LABEL_CAP = 56
    const val ROW_CAP = 48
    const val NAME_CAP = 60
    const val PICK_CAP = 520
    const val FACE_LINES = 14
    /** Most cited articles that get a card of their own; the rest stay lines on the Sources card. */
    const val MAX_CARDS = 12
    const val UNTITLED = "Untitled"

    /** Appended to the engine's prompt — byte-identical on both apps. */
    const val INSTRUCTION = "Each source in the material is numbered. When a point comes from a source, cite it right " +
        "there as [n], e.g. [3] or [2][5]. Use only the numbers given. Never invent a source, title, " +
        "number or link, and don't end with a list of sources or links: Ledgable adds them."

    /** The Correspondent's sticky label suffix — a reply card on any page ([SynthLanding.place]). */
    const val REPLY_SUFFIX = "The Correspondent"
    const val REPLY_LABEL = "❖ The Correspondent"
    const val SOURCES_PREFIX = "Sources · "

    // ── The words (design §8, §10) ──────────────────────────────────────────────────────────

    const val WRITING_BACK = "❖ Writing back…"
    const val BUSY = "❖ Already writing back…"
    const val GO_TO_IT = "Go to it"
    const val TRY_AGAIN = "Try again"
    const val EMPTY_LIST = "Nothing on this list to synthesize."
    const val NO_ARTICLE_TEXT = "Nothing to synthesize yet — the article's text hasn't loaded."
    const val NO_KEY = "Add your AI key in Ask my Ledger settings"
    const val TIMEOUT = "❖ No reply after 2 minutes. Nothing was made."
    const val EMPTY_REPLY = "❖ The reply came back empty. Nothing was made."
    const val NONE_CITED = " · none cited by number"
    const val ARTICLE_WONT_LOAD = "Can't load this article right now."

    /** What is being synthesized — the picker's words and the count's noun. [id] is the fixture's. */
    enum class Kind(val id: String, val verb: String, val one: String, val many: String) {
        FEEDS("feeds", "Synthesize this list", "article", "articles"),
        MAIL("mail", "Synthesize this list", "letter", "letters"),
        EPISODES("episodes", "Synthesize these episodes", "episode", "episodes"),
        ARTICLE("article", "Synthesize this article", "article", "articles");

        companion object {
            fun of(id: String): Kind = values().firstOrNull { it.id == id } ?: FEEDS
        }
    }

    fun picker(kind: Kind): String = "❖ " + kind.verb

    fun countNoun(kind: Kind, n: Int): String = "$n " + (if (n == 1) kind.one else kind.many)

    /** "❖ Reading 23 articles…", "❖ Reading 31 of 40 articles…", "❖ Reading this article…". */
    fun reading(kind: Kind, sent: Int, shown: Int): String = when {
        kind == Kind.ARTICLE -> "❖ Reading this article…"
        sent < shown -> "❖ Reading $sent of $shown ${kind.many}…"
        else -> "❖ Reading ${countNoun(kind, sent)}…"
    }

    fun ready(name: String): String = "❖ Ready: $name"

    fun couldntReach(reason: String): String = "❖ Couldn't reach the engine: ${reason(reason)}. Nothing was made."

    fun couldntSave(reason: String): String = "❖ Couldn't save the page: ${reason(reason)}."

    fun killed(name: String): String = "❖ $name didn't finish — the app closed mid-run. Run it again from the list."

    /** A reason as a clause: one line, no trailing full stops (the sentence adds its own). */
    fun reason(r: String): String {
        var s = normalize(r)
        while (s.endsWith(".")) s = s.dropLast(1).trimEnd(' ')
        return s
    }

    // ── Code points and whitespace ──────────────────────────────────────────────────────────

    /** U+0009–000D, 0020, 00A0, 2000–200A, 2028, 2029, 202F, 205F, 3000 — nothing else. */
    fun isWs(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0xA0 || cp in 0x2000..0x200A ||
            cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    fun cpLen(s: String): Int = s.codePointCount(0, s.length)

    private fun cpTake(s: String, n: Int): String =
        if (n <= 0) "" else if (cpLen(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** At most [n] code points; a cut one ends in "…" (which counts). */
    fun cap(s: String, n: Int): String =
        if (n <= 0) "" else if (cpLen(s) <= n) s else cpTake(s, n - 1) + "…"

    /** Runs of [isWs] → one space, then trimmed. */
    fun normalize(s: String): String {
        val out = StringBuilder(s.length)
        var pending = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isWs(cp)) pending = true
            else {
                if (pending && out.isNotEmpty()) out.append(' ')
                pending = false
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** Trim [isWs] from both ends, nothing collapsed inside. */
    fun wtrim(s: String): String {
        var i = 0
        var j = s.length
        while (i < j) { val cp = s.codePointAt(i); if (!isWs(cp)) break; i += Character.charCount(cp) }
        while (j > i) { val cp = s.codePointBefore(j); if (!isWs(cp)) break; j -= Character.charCount(cp) }
        return s.substring(i, j)
    }

    private fun angle(s: String) = s.replace("<", "‹").replace(">", "›")

    // ── The items ───────────────────────────────────────────────────────────────────────────

    /**
     * One row as the list shows it. Feeds: {title, feed, blurb, link, entryId}; mail: {subject, who,
     * snippet, `mail://<id>`, 0}; an episode: {episode, show, description, entry url, id}. [entryId]
     * is a SERVER id (Miniflux on Android) or 0 — never a hash, never negative ([prep] zeroes those).
     */
    data class Item(
        val title: String = "",
        val feed: String = "",
        val blurb: String = "",
        val link: String = "",
        val entryId: Long = 0,
    )

    /** Normalized, capped, `<>` turned to `‹›` in title and feed, the link trimmed and otherwise
     *  untouched — the bytes both apps write. */
    fun prep(it: Item): Item {
        val title = cap(angle(normalize(it.title)), TITLE_CAP).ifEmpty { UNTITLED }
        return Item(
            title = title,
            feed = cap(angle(normalize(it.feed)), FEED_CAP),
            blurb = cap(normalize(it.blurb), BLURB_CAP),
            link = wtrim(it.link),
            entryId = if (it.entryId > 0) it.entryId else 0,
        )
    }

    /** "[n] Title — Feed" (no " — feed" when the feed is blank). */
    fun line(n: Int, title: String, feed: String): String =
        "[$n] $title" + (if (feed.isNotEmpty()) " — $feed" else "")

    private fun bodyLen(items: List<Item>, blurbs: List<String>): Int {
        var total = 0
        items.forEachIndexed { i, it -> total += cpLen(line(i + 1, it.title, it.feed)) }
        for (b in blurbs) if (b.isNotEmpty()) total += cpLen(b) + 1
        total += 2 * maxOf(0, items.size - 1)
        return total
    }

    /** The material: [text] for the engine, how many were [sent] of the [shown], and the sent items
     *  as prepared (n = index + 1) — what the page's cards and Sources card are made from. */
    data class Material(val text: String, val sent: Int, val shown: Int, val items: List<Item>)

    /**
     * The first [MAX_ITEMS] rows, numbered, LF-joined, no trailing newline:
     *
     *     SOURCES — Feeds · 23 of 40
     *     [1] Why the count matters — Ashtanga Notes
     *     The count is the practice's metronome…
     *
     *     [2] …
     *
     * Over [BUDGET] (80 reserved for the header) every blurb is capped evenly first; still over,
     * items drop from the end. Only what is sent is numbered.
     */
    fun material(header: String, rows: List<Item>): Material {
        var items = rows.take(MAX_ITEMS).map(::prep)
        val shown = items.size
        if (shown == 0) return Material("", 0, 0, emptyList())
        var blurbs = items.map { it.blurb }
        if (bodyLen(items, blurbs) > BODY) {
            val n = items.size
            var fixed = 0
            items.forEachIndexed { i, it -> fixed += cpLen(line(i + 1, it.title, it.feed)) }
            fixed += 2 * (n - 1) + blurbs.count { it.isNotEmpty() }
            val each = maxOf(0, Math.floorDiv(BODY - fixed, n))
            blurbs = blurbs.map { cap(it, each) }
        }
        while (items.size > 1 && bodyLen(items, blurbs) > BODY) {
            items = items.dropLast(1)
            blurbs = blurbs.dropLast(1)
        }
        val sent = items.size
        val blocks = items.mapIndexed { i, it ->
            line(i + 1, it.title, it.feed) + (if (blurbs[i].isNotEmpty()) "\n" + blurbs[i] else "")
        }
        val text = "SOURCES — $header · $sent of $shown\n" + blocks.joinToString("\n\n")
        return Material(text, sent, shown, items.mapIndexed { i, it -> it.copy(blurb = blurbs[i]) })
    }

    /** One piece as its single source [1]: `SOURCES — This article · 1 of 1` (or `Selection`), the
     *  [1] line, a blank line, then its text (one line, whitespace collapsed) to the budget. */
    fun articleMaterial(label: String, title: String, feed: String, text: String): String {
        val it = prep(Item(title = title, feed = feed))
        val l1 = line(1, it.title, it.feed)
        val body = cap(normalize(text), BODY - cpLen(l1) - 2)
        return "SOURCES — $label · 1 of 1\n$l1\n\n$body"
    }

    // ── Citations (design §2) ───────────────────────────────────────────────────────────────

    private val MARK = Regex("\\[([0-9]{1,2}(?: ?[,–-] ?[0-9]{1,2})*)\\]")
    private val TOKEN = Regex("[0-9]+|[,–-]")

    /** A mark's numbers: a range a–b expands only when a < b and b − a ≤ 9; repeats dropped. */
    private fun expand(body: String): List<Int> {
        val nums = mutableListOf<Int>()
        val seps = mutableListOf<String>()
        for (m in TOKEN.findAll(body)) {
            val t = m.value
            if (t[0] in '0'..'9') nums.add(t.toInt()) else seps.add(t)
        }
        if (nums.isEmpty()) return emptyList()
        val out = mutableListOf(nums[0])
        for (i in 1 until nums.size) {
            val a = nums[i - 1]
            val b = nums[i]
            if (seps.getOrNull(i - 1) != "," && a < b && b - a <= 9) for (x in a + 1..b) out.add(x)
            else out.add(b)
        }
        return out.distinct()
    }

    /** The valid numbers (1…[sent]) in first-appearance order, without repeats. */
    fun find(text: String, sent: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        for (m in MARK.findAll(text)) for (x in expand(m.groupValues[1])) if (x in 1..sent) seen.add(x)
        return seen.toList()
    }

    private fun rewrite(text: String, sent: Int, stripAll: Boolean): String {
        val out = StringBuilder(text.length)
        var last = 0
        for (m in MARK.findAll(text)) {
            out.append(text, last, m.range.first)
            last = m.range.last + 1
            val nums = expand(m.groupValues[1])
            val valid = if (stripAll) emptyList() else nums.filter { it in 1..sent }
            when {
                !stripAll && valid.size == nums.size -> out.append(m.value)
                valid.isNotEmpty() -> out.append("[").append(valid.joinToString(", ")).append("]")
                // None valid: the mark goes, with ONE space before it.
                out.endsWith(" ") -> out.setLength(out.length - 1)
            }
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** Per mark: all valid → byte for byte; some → "[a, b]"; none → gone with one preceding space.
     *  An unknown number never becomes a card, a row or a link. */
    fun clean(text: String, sent: Int): String = rewrite(text, sent, false)

    /** Every numeric mark gone — the pick's face. */
    fun strip(text: String): String = rewrite(text, 0, true)

    /** The pick's face text: "❖ " + the reply without marks, trimmed, to [PICK_CAP]. */
    fun pickText(reply: String): String = "❖ " + cpTake(wtrim(strip(reply)), PICK_CAP)

    // ── The Sources card (design §4) ────────────────────────────────────────────────────────

    /** `% space < > LF` → `%25 %20 %3C %3E %0A` — only those, so any link survives the line. */
    fun encodeLink(s: String): String =
        s.replace("%", "%25").replace(" ", "%20").replace("<", "%3C").replace(">", "%3E").replace("\n", "%0A")

    /** Only the five escapes [encodeLink] writes (upper-case hex) are read back. */
    fun decodeLink(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val k = if (i + 3 <= s.length) s.substring(i, i + 3) else ""
            val c = when (k) { "%25" -> "%"; "%20" -> " "; "%3C" -> "<"; "%3E" -> ">"; "%0A" -> "\n"; else -> null }
            if (c != null) { out.append(c); i += 3 } else { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    data class SourcesCard(val face: String, val cardText: String)

    /**
     * The face: `Sources · <scope> · <sent> of <shown>` (+ ` · none cited by number`), up to
     * [FACE_LINES] `[n] Title — Feed` lines, then `+ <k> more — tap for all`. The cardText — the
     * resolver of record — one line per item sent: `[n] <title> — <feed> <<link>> #<id>`.
     */
    fun sourcesCard(scope: String, items: List<Item>, shown: Int, noneCited: Boolean): SourcesCard {
        val its = items.map(::prep)
        val head = "$SOURCES_PREFIX$scope · ${its.size} of $shown" + (if (noneCited) NONE_CITED else "")
        val lines = its.mapIndexed { i, it -> line(i + 1, it.title, it.feed) }
        val face = mutableListOf(head)
        // Each face line at most ROW_CAP (review 2026-10-12: real 60–90-character headlines wrapped
        // the card past the canvas foot, its last lines and "tap for all" cut off); cardText keeps
        // every line whole.
        face.addAll(lines.take(FACE_LINES).map { cap(it, ROW_CAP) })
        if (lines.size > FACE_LINES) face.add("+ ${lines.size - FACE_LINES} more — tap for all")
        val record = its.mapIndexed { i, it ->
            val sb = StringBuilder(line(i + 1, it.title, it.feed))
            if (it.link.isNotEmpty()) {
                sb.append(" <").append(encodeLink(it.link)).append(">")
                if (it.entryId > 0) sb.append(" #").append(it.entryId)
            }
            sb.toString()
        }
        return SourcesCard(face.joinToString("\n"), record.joinToString("\n"))
    }

    /** One resolvable source: its number, its "Title — Feed" text, its link and its server id. */
    data class Source(val n: Int, val text: String, val link: String, val entryId: Long)

    // DOTALL + \z: Python's `.` takes everything but LF and its `$` is the true end here (lines are
    // split on LF) — Java's `.` and `$` would stop at \r, U+0085, U+2028 and U+2029.
    private val PARSE = Regex("(?s)^\\[([0-9]{1,2})\\] (.+?)(?: <([^<>]*)>(?: #([0-9]{1,19}))?)?\\z")

    fun parseSources(cardText: String): List<Source> = cardText.split("\n").mapNotNull { raw ->
        val m = PARSE.find(raw) ?: return@mapNotNull null
        val link = m.groups[3]?.value?.let(::decodeLink) ?: ""
        val id = m.groups[4]?.value?.toLongOrNull() ?: 0L
        Source(m.groupValues[1].toInt(), m.groupValues[2], link, id)
    }

    // ── Cards, roles and rows ───────────────────────────────────────────────────────────────

    /**
     * The engine's name without its leading glyph (review 2026-10-12: the built-ins are
     * "❖  Synthesize", "?  3 Questions", "✎  Writing Prompt", and the pick, the door and the Log row
     * already wear their own ❖ — "❖ ❖ Synthesize · Starred"): normalized, then every leading code
     * point that is not a letter (L*) or a decimal digit (Nd) dropped. Nothing left → the
     * normalized name as it was. Swift: generalCategory L* / Nd.
     */
    fun engineWord(engine: String): String {
        val e = normalize(engine)
        var i = 0
        while (i < e.length) {
            val cp = e.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) break
            i += Character.charCount(cp)
        }
        return if (i < e.length) e.substring(i) else e
    }

    /** `<engine> · <scope>` ([engineWord]: the engine's glyph left off), at most [NAME_CAP]. */
    fun pageName(engine: String, scope: String): String = cap(engineWord(engine) + " · " + normalize(scope), NAME_CAP)

    /** A cited article's own card: label `[n] Title` ([LABEL_CAP]); cardText `[n] Title — Feed\nblurb`. */
    data class SourceCardText(val label: String, val cardText: String)

    fun sourceCard(n: Int, item: Item): SourceCardText {
        val it = prep(item)
        val l = line(n, it.title, it.feed)
        return SourceCardText(cap("[$n] ${it.title}", LABEL_CAP), l + (if (it.blurb.isNotEmpty()) "\n" + it.blurb else ""))
    }

    /** What a card on a Synthesize page IS, by its label. */
    sealed class Role {
        object Reply : Role()
        object Sources : Role()
        /** A cited article's own card, `[n] Title`. */
        data class Card(val n: Int) : Role()
        object Other : Role()
    }

    private val SOURCE_LABEL = Regex("^\\[([0-9]{1,2})\\] ")

    fun role(label: String): Role {
        if (label.endsWith(REPLY_SUFFIX)) return Role.Reply
        if (label.startsWith(SOURCES_PREFIX)) return Role.Sources
        SOURCE_LABEL.find(label)?.let { return Role.Card(it.groupValues[1].toInt()) }
        return Role.Other
    }

    /**
     * [role] for a card in hand (its link and page known): ANY voice's reply on its own conversation
     * ([SynthVoice.isReply]) is a [Role.Reply] — a follow-up "❖ Spiral" card that cites [n] offers
     * its ↗ rows like a "❖ The Correspondent" one. The label alone can't tell "❖ Spiral" from a
     * "❖ <page>" breadcrumb (synth-personas pin §I), so every caller that holds the element asks this.
     */
    fun role(label: String, sourceLink: String, page: String): Role =
        if (SynthVoice.isReply(label, sourceLink, page)) Role.Reply else role(label)

    /** One `↗ [n] Title — Feed` row ([ROW_CAP]) and what it opens. */
    data class Row(val label: String, val source: Source)

    fun rowLabel(s: Source): String = cap("↗ [${s.n}] ${s.text}", ROW_CAP)

    /**
     * The rows a card offers: the Sources card all of them; a source card its own; a reply the
     * ones it cites that the page can resolve (in first-appearance order); anything else none.
     */
    fun rows(role: Role, cardText: String, sources: List<Source>): List<Row> {
        val byN = LinkedHashMap<Int, Source>()
        for (s in sources) byN[s.n] = s
        val top = sources.maxOfOrNull { it.n } ?: 0
        val picked: List<Int> = when (role) {
            Role.Sources -> sources.map { it.n }
            Role.Reply -> find(cardText, top).filter { it in byN }
            is Role.Card -> if (role.n in byN) listOf(role.n) else emptyList()
            Role.Other -> emptyList()
        }
        return picked.mapNotNull { n -> byN[n]?.let { Row(rowLabel(it), it) } }
    }

    // ── The conversation's seed and its follow-ups ──────────────────────────────────────────

    /** The first turn the transcript sidecar records — so ❖ Synth on the page continues as the
     *  Correspondent knowing what was read. */
    fun seedTurn(kind: Kind, scope: String, items: List<Item>): String {
        val its = items.map(::prep)
        val head = "❖ ${kind.verb} — ${normalize(scope)} · ${countNoun(kind, its.size)}"
        return (listOf(head) + its.mapIndexed { i, it -> line(i + 1, it.title, it.feed) }).joinToString("\n")
    }

    /** What a later ❖ on the page appends: the numbered lines the page can resolve + [INSTRUCTION]. */
    fun followUpBlock(cardText: String): String =
        "SOURCES — This page\n" + parseSources(cardText).joinToString("\n") { "[${it.n}] ${it.text}" } +
            "\n\n" + INSTRUCTION

    // ── The Log's one row (design §5; iOS SynthSources.logFoldKey, rule for rule) ────────────

    /** `ledger://<yyyy-MM-dd>/<key>` — the pick's link, the reply stickies' link, the door. */
    fun pageLink(iso: String, key: String): String = "ledger://$iso/$key"

    /** A new Synthesize document's key, chosen at ask time (`synthesize-<ms>`). */
    fun pageKey(millis: Long): String = "synthesize-$millis"

    /** A named Synthesize document's key (`synthesize-<digits>`), sub-page tail ignored. */
    fun isSynthesizeDocument(key: String): Boolean {
        val base = key.substringBefore('#')
        if (!base.startsWith("synthesize-")) return false
        val tail = base.removePrefix("synthesize-")
        return tail.isNotEmpty() && tail.all { it in '0'..'9' }
    }

    /** The Synthesize document a card folds into on the Log: a card ON a `synthesize-*` page, or any
     *  card (the All Picks pick) whose link names one. Null otherwise. */
    fun logFoldKey(page: String, sourceLink: String): String? {
        val base = page.substringBefore('#')
        if (isSynthesizeDocument(base)) return base
        if (!sourceLink.startsWith("ledger://")) return null
        // Swift's split drops empty pieces; so do these.
        val parts = sourceLink.removePrefix("ledger://").split("/", limit = 2).filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0].split("-").count { it.isNotEmpty() } != 3) return null
        val key = parts[1].substringBefore('#')
        return if (isSynthesizeDocument(key)) key else null
    }
}
