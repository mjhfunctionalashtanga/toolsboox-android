package com.toolsboox

import com.toolsboox.plugin.calendar.ot.SynthSources
import com.toolsboox.plugin.calendar.ot.SynthSources.Item
import com.toolsboox.plugin.calendar.ot.SynthSources.Kind
import com.toolsboox.plugin.calendar.ot.SynthSources.Role
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * ❖ SYNTHESIZE THIS LIST → A PAGE THAT LINKS BACK, the pure half (Michael, 2026-10-12: "the response
 * should come back with links to those articles within ledgable itself" → "That goes on both android
 * and ios").
 *
 * Every case lives in `synth-links-cases.json`, written by the scratchpad's Python reference
 * (slinks-ref/gen_fixture.py) and read BYTE FOR BYTE by iOS's SynthSourcesTests too — the pinned
 * SHA-256 below is the same constant in both suites, so a fixture edited on one side alone fails
 * here before the two apps can write different bytes onto one page.
 */
class SynthSourcesTest {

    private val raw = javaClass.getResourceAsStream("/synth-links-cases.json")!!.readBytes()
    private val cases = JSONObject(String(raw, Charsets.UTF_8))

    // ── The fixture's generators (its "generators" note): the same expansion iOS does ─────────

    private fun str(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is JSONObject -> v.getString("unit").repeat(v.getInt("times"))
        else -> v.toString()
    }

    private fun item(o: JSONObject) = Item(
        title = str(o.opt("title")), feed = str(o.opt("feed")), blurb = str(o.opt("blurb")),
        link = str(o.opt("link")), entryId = o.optLong("entryId", 0),
    )

    private fun items(v: Any): List<Item> = when (v) {
        is JSONArray -> (0 until v.length()).map { item(v.getJSONObject(it)) }
        is JSONObject -> {
            val base = v.optLong("entryIdBase", 0)
            (1..v.getInt("repeat")).map { n ->
                fun f(k: String) = str(v.opt(k)).replace("{n}", n.toString())
                Item(f("title"), f("feed"), f("blurb"), f("link"), if (base > 0) base + n else 0)
            }
        }
        else -> error("items: $v")
    }

    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    private fun objs(name: String) = cases.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun roleName(r: Role) = when (r) {
        Role.Reply -> "reply"
        Role.Sources -> "sources"
        is Role.Card -> "source:${r.n}"
        Role.Other -> "other"
    }

    private fun roleOf(name: String): Role = when {
        name == "reply" -> Role.Reply
        name == "sources" -> Role.Sources
        name.startsWith("source:") -> Role.Card(name.removePrefix("source:").toInt())
        else -> Role.Other
    }

    // ── The pin ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the fixture is the one both apps read`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        assertEquals(FIXTURE_SHA256, sha)
    }

    @Test
    fun `constants and the instruction match the fixture`() {
        val c = cases.getJSONObject("constants")
        assertEquals(c.getInt("budget"), SynthSources.BUDGET)
        assertEquals(c.getInt("headerReserve"), SynthSources.HEADER_RESERVE)
        assertEquals(c.getInt("maxItems"), SynthSources.MAX_ITEMS)
        assertEquals(c.getInt("titleCap"), SynthSources.TITLE_CAP)
        assertEquals(c.getInt("feedCap"), SynthSources.FEED_CAP)
        assertEquals(c.getInt("blurbCap"), SynthSources.BLURB_CAP)
        assertEquals(c.getInt("labelCap"), SynthSources.LABEL_CAP)
        assertEquals(c.getInt("rowCap"), SynthSources.ROW_CAP)
        assertEquals(c.getInt("nameCap"), SynthSources.NAME_CAP)
        assertEquals(c.getInt("pickCap"), SynthSources.PICK_CAP)
        assertEquals(c.getInt("faceLines"), SynthSources.FACE_LINES)
        assertEquals(c.getString("untitled"), SynthSources.UNTITLED)
        assertEquals(c.getString("instruction"), SynthSources.INSTRUCTION)
    }

    // ── §1 The material ─────────────────────────────────────────────────────────────────────

    @Test
    fun `normalize collapses the spelled-out whitespace set and nothing else`() {
        for (c in objs("normalize")) assertEquals(c.getString("in"), c.getString("out"), SynthSources.normalize(c.getString("in")))
    }

    @Test
    fun `material - numbering, caps, the budget squeeze and the dropped tail`() {
        for (c in objs("material")) {
            val m = SynthSources.material(c.getString("header"), items(c.get("items")))
            val name = c.getString("name")
            assertEquals(name, c.getInt("sent"), m.sent)
            assertEquals(name, c.getInt("shown"), m.shown)
            assertEquals(name, c.getString("text"), m.text)
            assertTrue(name, SynthSources.cpLen(m.text) <= SynthSources.BUDGET)
            assertEquals(name, m.sent, m.items.size)
        }
    }

    @Test
    fun `an article is one source, its text to the budget`() {
        for (c in objs("articleMaterial")) assertEquals(
            c.getString("out"),
            SynthSources.articleMaterial(c.getString("label"), c.getString("title"), c.getString("feed"), str(c.get("text"))))
    }

    // ── §2 Citations ────────────────────────────────────────────────────────────────────────

    @Test
    fun `find - only the numbers sent, first appearance, no repeats`() {
        for (c in objs("find")) assertEquals(c.getString("text"), ints(c.getJSONArray("out")),
            SynthSources.find(c.getString("text"), c.getInt("sent")))
    }

    @Test
    fun `clean - valid marks byte for byte, partial marks rewritten, invented ones gone`() {
        for (c in objs("clean")) assertEquals(c.getString("text"), c.getString("out"),
            SynthSources.clean(c.getString("text"), c.getInt("sent")))
    }

    @Test
    fun `strip and the pick's face`() {
        for (c in objs("strip")) assertEquals(c.getString("out"), SynthSources.strip(c.getString("in")))
        for (c in objs("pickText")) assertEquals(c.getString("out"), SynthSources.pickText(str(c.get("in"))))
    }

    // ── §4 The Sources card, links, names, roles, rows ──────────────────────────────────────

    @Test
    fun `the Sources card's face and its resolver of record`() {
        for (c in objs("sourcesCard")) {
            val sc = SynthSources.sourcesCard(c.getString("scope"), items(c.get("items")), c.getInt("shown"), c.getBoolean("noneCited"))
            assertEquals(c.getString("face"), sc.face)
            assertEquals(c.getString("cardText"), sc.cardText)
        }
    }

    @Test
    fun `parseSources reads every line back - links decoded, ids only when they fit`() {
        for (c in objs("parseSources")) {
            val want = c.getJSONArray("out")
            val got = SynthSources.parseSources(c.getString("in"))
            assertEquals(c.getString("in"), want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                assertEquals(w.getInt("n"), got[i].n)
                assertEquals(w.getString("text"), got[i].text)
                assertEquals(w.getString("link"), got[i].link)
                assertEquals(w.getLong("entryId"), got[i].entryId)
            }
        }
    }

    @Test
    fun `links round-trip through the five escapes and only those`() {
        for (c in objs("links")) {
            assertEquals(c.getString("encoded"), SynthSources.encodeLink(c.getString("raw")))
            assertEquals(c.getString("raw"), SynthSources.decodeLink(c.getString("encoded")))
        }
        for (c in objs("decodeLink")) assertEquals(c.getString("out"), SynthSources.decodeLink(c.getString("in")))
    }

    @Test
    fun `the page's name`() {
        for (c in objs("pageName")) assertEquals(c.getString("out"),
            SynthSources.pageName(c.getString("engine"), str(c.get("scope"))))
    }

    @Test
    fun `a card's role by its label`() {
        for (c in objs("role")) assertEquals(c.getString("label"), c.getString("out"), roleName(SynthSources.role(c.getString("label"))))
    }

    @Test
    fun `rows - a reply's cited and resolvable, a card's own, the Sources card all`() {
        val r = cases.getJSONObject("rows")
        val src = SynthSources.parseSources(r.getString("sources"))
        val arr = r.getJSONArray("cases")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            assertEquals(c.getString("role") + " " + c.getString("text"), strings(c.getJSONArray("out")),
                SynthSources.rows(roleOf(c.getString("role")), c.getString("text"), src).map { it.label })
        }
        // [3][3][9] → two rows, never a row for [4], which the page can't resolve.
        val twice = SynthSources.rows(Role.Reply, "Twice [3][3][9].", src)
        assertEquals(listOf(3, 9), twice.map { it.source.n })
        assertEquals("https://x.example/3", twice[0].source.link)
    }

    @Test
    fun `a cited article's own card`() {
        for (c in objs("sourceCard")) {
            val sc = SynthSources.sourceCard(c.getInt("n"), item(c.getJSONObject("item")))
            val out = c.getJSONObject("out")
            assertEquals(out.getString("label"), sc.label)
            assertEquals(out.getString("cardText"), sc.cardText)
        }
    }

    @Test
    fun `the seed turn and the follow-up block`() {
        for (c in objs("seedTurn")) assertEquals(c.getString("out"),
            SynthSources.seedTurn(Kind.of(c.getString("kind")), c.getString("scope"), items(c.get("items"))))
        for (c in objs("followUpBlock")) assertEquals(c.getString("out"), SynthSources.followUpBlock(c.getString("in")))
    }

    // ── §8 / §10 The words ──────────────────────────────────────────────────────────────────

    @Test
    fun `every word he reads`() {
        val w = cases.getJSONObject("words")
        val picker = w.getJSONObject("picker")
        for (k in Kind.values()) assertEquals(picker.getString(k.id), SynthSources.picker(k))
        val reading = w.getJSONArray("reading")
        for (i in 0 until reading.length()) {
            val c = reading.getJSONObject(i)
            assertEquals(c.getString("out"), SynthSources.reading(Kind.of(c.getString("kind")), c.getInt("sent"), c.getInt("shown")))
        }
        assertEquals(w.getString("writingBack"), SynthSources.WRITING_BACK)
        assertEquals(w.getString("busy"), SynthSources.BUSY)
        assertEquals(w.getString("goToIt"), SynthSources.GO_TO_IT)
        assertEquals(w.getString("tryAgain"), SynthSources.TRY_AGAIN)
        assertEquals(w.getString("emptyList"), SynthSources.EMPTY_LIST)
        assertEquals(w.getString("noArticleText"), SynthSources.NO_ARTICLE_TEXT)
        assertEquals(w.getString("noKey"), SynthSources.NO_KEY)
        assertEquals(w.getString("timeout"), SynthSources.TIMEOUT)
        assertEquals(w.getString("emptyReply"), SynthSources.EMPTY_REPLY)
        assertEquals(w.getString("noneCited"), SynthSources.NONE_CITED)
        assertEquals(w.getString("articleWontLoad"), SynthSources.ARTICLE_WONT_LOAD)
        // The writeback's own line is the same one ❖ Synth says on a page.
        assertEquals(com.toolsboox.plugin.calendar.ot.SynthWords.WRITING_BACK, SynthSources.WRITING_BACK)
        fun pairs(name: String, f: (String) -> String) {
            val a = w.getJSONArray(name)
            for (i in 0 until a.length()) a.getJSONObject(i).let { assertEquals(it.getString("out"), f(it.getString("in"))) }
        }
        pairs("ready", SynthSources::ready)
        pairs("couldntReach", SynthSources::couldntReach)
        pairs("couldntSave", SynthSources::couldntSave)
        pairs("killed", SynthSources::killed)
    }

    // ── Beyond the fixture: what the page relies on ─────────────────────────────────────────

    @Test
    fun `a reply sticky's label reads as a reply, so threading and alreadyLanded hold`() {
        assertEquals(Role.Reply, SynthSources.role(SynthSources.REPLY_LABEL))
        // The Sources card's label is its prefix + the scope.
        assertEquals(Role.Sources, SynthSources.role(SynthSources.SOURCES_PREFIX + "Mail · Inbox"))
    }

    @Test
    fun `every sent item's line parses back to its link and id - the card is the resolver`() {
        val list = (1..40).map { Item("T<$it>  x", "F $it", "", "https://e.example/p $it?q=%41#f", if (it % 2 == 0) 9_000_000_000L + it else 0) }
        val m = SynthSources.material("Feeds", list)
        val card = SynthSources.sourcesCard("Feeds", m.items, m.shown, false)
        val parsed = SynthSources.parseSources(card.cardText)
        assertEquals(m.sent, parsed.size)
        parsed.forEachIndexed { i, s ->
            assertEquals(i + 1, s.n)
            assertEquals(m.items[i].link, s.link)
            assertEquals(m.items[i].entryId, s.entryId)
        }
    }

    companion object {
        /** sha256(app/src/test/resources/synth-links-cases.json) — the same constant in iOS's
         *  SynthSourcesTests. Regenerate both with slinks-ref/gen_fixture.py, never by hand. */
        const val FIXTURE_SHA256 = "a26502171328d20df727d0597a1dba8877be5adf636ff4524bd944d33630de10"
    }
}
