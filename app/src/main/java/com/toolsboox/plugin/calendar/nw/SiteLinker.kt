package com.toolsboox.plugin.calendar.nw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * THE SITE LINKER — "the AI portion of the app should know my WordPress well enough" (Michael,
 * 09-13): given a named thing from a page (a posture, an exercise, a drill, a concept), find the
 * page for it on HIS sites and read that page's card (Open Graph title / description / picture),
 * so a note that lists "Marichyasana C · hip hinge · kapalabhati" becomes one movable link gram
 * per item, each wearing the site's own featured image and tapping back to the page.
 *
 * Search is WordPress core's public `/wp-json/wp/v2/search` on every configured site (no
 * credentials needed for what is public; the study guides and posture pages are). The best hit
 * across sites wins by a plain title score — exact name, then name-as-prefix, then word overlap —
 * and anything below the floor is reported as "no page for X" rather than guessed at.
 */
object SiteLinker {
    data class Hit(val url: String, val title: String, val siteName: String, val score: Int,
                   /** From the site's own index when it has them — saves the Open Graph fetch. */
                   val image: String? = null, val excerpt: String = "")
    data class Meta(val title: String?, val description: String?, val image: String?)

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()

    /**
     * THE CATALOGUE — every page of every public post type on his sites (and the admin-only ones his
     * app password can read: mjh.yoga's `note`), as title + excerpt, cached a day per site. The
     * types come from `/wp-json/wp/v2/types` so a new CPT joins without a code change; the
     * builder's own furniture (Kadence, Fluent, menus, media) is left out.
     */
    data class Entry(val url: String, val title: String, val excerpt: String, val type: String, val siteName: String) {
        val text: String get() = (title + (if (excerpt.isBlank()) "" else " — " + excerpt)).take(320)
    }

    private val SKIP_TYPES = listOf("kadence", "fcom", "fct-", "fcrm", "acf-", "kb_icon", "mwai", "nav_menu", "menu-items",
        "media", "blocks", "template", "navigation", "font", "global-styles", "wp_", "attachment", "fluent-products",
        "fluent_player", "googlesitekit", "fma_", "oembed", "user_request", "custom_css", "customize")

    private fun catalogDir(context: android.content.Context) = java.io.File(context.filesDir, "site-catalog").apply { mkdirs() }

    /** The REST bases worth indexing on [site]. */
    private fun restBases(base: String, auth: String?): List<String> {
        val body = get("$base/wp-json/wp/v2/types", auth) ?: return listOf("posts", "pages")
        return runCatching {
            val o = org.json.JSONObject(body)
            o.keys().asSequence().mapNotNull { key ->
                if (SKIP_TYPES.any { key.contains(it) }) return@mapNotNull null
                val t = o.getJSONObject(key)
                val rest = t.optString("rest_base").ifBlank { key }
                if (SKIP_TYPES.any { rest.contains(it) }) null else rest
            }.toList().ifEmpty { listOf("posts", "pages") }
        }.getOrDefault(listOf("posts", "pages"))
    }

    /** Every entry across [sites] — from the day-old cache when it has one, else fetched. */
    fun catalogue(context: android.content.Context, sites: List<LedgerSite>, maxPagesPerType: Int = 6): List<Entry> {
        val out = mutableListOf<Entry>()
        for (site in sites) {
            val base = site.url.trim().trimEnd('/')
            if (!base.startsWith("http")) continue
            val name = site.name.ifBlank { base.removePrefix("https://") }
            val cache = java.io.File(catalogDir(context), base.hashCode().toUInt().toString(16) + ".json")
            val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 24L * 3600_000
            if (fresh) {
                runCatching {
                    val arr = org.json.JSONArray(cache.readText())
                    for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                        out += Entry(o.getString("url"), o.getString("title"), o.optString("excerpt"), o.optString("type"), name) }
                }.onSuccess { continue }
            }
            val pass = SiteStore.password(context, site.id)
            val auth = if (site.username.isNotBlank() && pass.isNotBlank()) okhttp3.Credentials.basic(site.username.trim(), pass) else null
            val mine = mutableListOf<Entry>()
            for (rest in restBases(base, auth)) {
                for (page in 1..maxPagesPerType) {
                    val body = get("$base/wp-json/wp/v2/$rest?per_page=100&page=$page&_fields=id,link,title,excerpt", auth) ?: break
                    val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: break
                    if (arr.length() == 0) break
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val link = o.optString("link"); if (link.isBlank()) continue
                        val title = strip(o.optJSONObject("title")?.optString("rendered") ?: o.optString("title"))
                        val excerpt = strip(o.optJSONObject("excerpt")?.optString("rendered") ?: "").take(300)
                        if (title.isBlank()) continue
                        mine += Entry(link, title, excerpt, rest, name)
                    }
                    if (arr.length() < 100) break
                }
            }
            if (mine.isNotEmpty()) runCatching {
                val arr = org.json.JSONArray()
                mine.forEach { e -> arr.put(org.json.JSONObject().put("url", e.url).put("title", e.title).put("excerpt", e.excerpt).put("type", e.type)) }
                cache.writeText(arr.toString())
            }
            out += mine
        }
        return out
    }

    private fun strip(html: String): String =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()

    // MARK: — the site's OWN semantic search (AI Engine + Pinecone, through the ledgr-semantic plugin)

    /**
     * THE LADDER (Michael, 09-13: "I use Pinecone embeddings on my AI Engine… this will work if
     * plugged into a user's WordPress and their AI Engine, but it needs to be configurable — not
     * all users will have all of the data"): a site is asked whether it can answer semantically
     * (`/wp-json/ledgr/v1/semantic/status`, public, remembered a day); when it can, ITS index
     * answers — the same vectors its chatbot uses — and only sites that can't fall to the app's
     * own catalogue embedding, then to title search. Nothing here is configured by hand: the
     * probe is the configuration.
     */
    private fun semanticAvailable(context: android.content.Context, site: LedgerSite): Boolean {
        val base = site.url.trim().trimEnd('/')
        if (!base.startsWith("http")) return false
        val prefs = context.getSharedPreferences("ledgr_semantic_status", 0)
        val key = base.hashCode().toUInt().toString(16)
        val at = prefs.getLong("$key.at", 0L)
        if (System.currentTimeMillis() - at < 24L * 3600_000) return prefs.getBoolean("$key.ok", false)
        val ok = runCatching {
            val body = get("$base/wp-json/ledgr/v1/semantic/status") ?: return@runCatching false
            org.json.JSONObject(body).optBoolean("available", false)
        }.getOrDefault(false)
        prefs.edit().putBoolean("$key.ok", ok).putLong("$key.at", System.currentTimeMillis()).apply()
        return ok
    }

    /** The site's index for [q]: its top [n] pages with the index's own scores (0–1). */
    private fun semanticSearch(context: android.content.Context, site: LedgerSite, q: String, n: Int): List<Hit> {
        val base = site.url.trim().trimEnd('/')
        val pass = SiteStore.password(context, site.id)
        val auth = if (site.username.isNotBlank() && pass.isNotBlank()) okhttp3.Credentials.basic(site.username.trim(), pass) else null
        val body = get("$base/wp-json/ledgr/v1/semantic?q=${URLEncoder.encode(q, "UTF-8")}&n=$n", auth) ?: return emptyList()
        val name = site.name.ifBlank { base.removePrefix("https://") }
        return runCatching {
            val arr = org.json.JSONObject(body).getJSONArray("results")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Hit(o.getString("url"), o.optString("title"), name, (o.optDouble("score", 0.0) * 100).toInt(),
                    image = o.optString("image").takeIf { it.startsWith("http") }, excerpt = o.optString("excerpt"))
            }
        }.getOrDefault(emptyList())
    }

    /** The sites that answer semantically themselves, and the rest. */
    private fun split(context: android.content.Context, sites: List<LedgerSite>): Pair<List<LedgerSite>, List<LedgerSite>> =
        sites.partition { semanticAvailable(context, it) }

    /**
     * SEMANTIC FIRST (Michael, 09-13: "can you do it semantically for topics, too? every
     * study-guide, functional-ashtanga… CPT"): the item and every catalogue entry are embedded
     * (the Ask key's OpenAI embeddings, cached by text), the closest entry wins with a title
     * bonus, and only when there is no key does it fall back to the title search below.
     */
    fun find(context: android.content.Context, item: String, sites: List<LedgerSite>): Hit? {
        val (semantic, rest) = split(context, sites)
        if (semantic.isNotEmpty()) {
            val q = norm(item)
            val best = semantic.flatMap { runCatching { semanticSearch(context, it, item, 3) }.getOrDefault(emptyList()) }
                .map { h -> h to (h.score + (if (norm(h.title).contains(q)) 6 else 0)) }
                .maxByOrNull { it.second }
            // The index's cosine for a page ABOUT the thing runs ~0.80+ (ada-002); a title that
            // carries the name lowers the bar a little.
            if (best != null && best.second >= 80) return best.first
        }
        if (rest.isEmpty()) return null
        val cat = catalogue(context, rest)
        if (cat.isNotEmpty()) {
            val vecs = com.toolsboox.plugin.calendar.ot.SemanticRoots.vectorsFor(context, listOf(item) + cat.map { it.text })
            val qv = vecs[item]
            if (qv != null) {
                val q = norm(item)
                val best = cat.mapNotNull { e ->
                    val v = vecs[e.text] ?: return@mapNotNull null
                    val c = com.toolsboox.plugin.calendar.ot.SemanticRoots.cosine(qv, v)
                    val t = norm(e.title)
                    val bonus = when { t == q -> 0.30; t.startsWith(q) || t.endsWith(q) -> 0.18; t.contains(q) -> 0.12; else -> 0.0 }
                    Triple(e, c + bonus, c)
                }.maxByOrNull { it.second }
                if (best != null && (best.second >= 0.62 || best.third >= 0.55))
                    return Hit(best.first.url, best.first.title, best.first.siteName, (best.second * 10).toInt())
                // A real catalogue with nothing close is an answer too — don't fall through to the
                // title search and land on something merely lexical.
                return null
            }
            // No embedding key: the catalogue by title score, no network per item.
            val q = norm(item)
            val hit = cat.map { e -> Hit(e.url, e.title, e.siteName, score(q, norm(e.title), e.type)) }
                .maxByOrNull { it.score }?.takeIf { it.score >= 3 }
            if (hit != null) return hit
        }
        return find(item, rest)
    }

    /** The [n] pages closest in meaning to a TOPIC — the study guides, the functional-ashtanga
     *  pieces, the posts — across every site. Empty without an embedding key. */
    fun topic(context: android.content.Context, topic: String, sites: List<LedgerSite>, n: Int = 10, minScore: Double = 0.42): List<Hit> {
        val (semantic, rest) = split(context, sites)
        // The sites' own indexes first (their scores lead), then the app's catalogue for the rest.
        val server = semantic.flatMap { runCatching { semanticSearch(context, it, topic, n) }.getOrDefault(emptyList()) }
            .filter { it.score >= 74 }.sortedByDescending { it.score }
        val cat = if (rest.isEmpty()) emptyList() else catalogue(context, rest)
        val local = if (cat.isEmpty()) emptyList() else run {
            val vecs = com.toolsboox.plugin.calendar.ot.SemanticRoots.vectorsFor(context, listOf(topic) + cat.map { it.text })
            val qv = vecs[topic] ?: return@run emptyList()
            cat.mapNotNull { e ->
                val v = vecs[e.text] ?: return@mapNotNull null
                val c = com.toolsboox.plugin.calendar.ot.SemanticRoots.cosine(qv, v)
                if (c >= minScore) Hit(e.url, e.title, e.siteName, (c * 100).toInt()) else null
            }.sortedByDescending { it.score }
        }
        return (server + local).distinctBy { it.url }.take(n)
    }

    /** The best page for [item] across [sites] by TITLE SEARCH — the pre-semantic path, kept as the
     *  fallback when there is no catalogue at all. */
    fun find(item: String, sites: List<LedgerSite>): Hit? {
        val q = norm(item)
        if (q.isBlank()) return null
        val hits = mutableListOf<Hit>()
        for (site in sites) {
            val base = site.url.trim().trimEnd('/')
            if (!base.startsWith("http")) continue
            val body = get("$base/wp-json/wp/v2/search?search=${URLEncoder.encode(item, "UTF-8")}&per_page=8") ?: continue
            runCatching {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = android.text.Html.fromHtml(o.optString("title"), android.text.Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim()
                    val link = o.optString("url")
                    if (link.isBlank() || title.isBlank()) continue
                    hits += Hit(link, title, site.name.ifBlank { base.removePrefix("https://") },
                        score(q, norm(title), o.optString("subtype")))
                }
            }
        }
        return hits.maxByOrNull { it.score }?.takeIf { it.score >= 3 }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun score(q: String, t: String, subtype: String): Int {
        var s = 0
        if (t == q) s += 8
        else if (t.startsWith(q) || t.endsWith(q)) s += 5
        else if (t.contains(q)) s += 4
        val qt = q.split(' ').filter { it.length > 2 }
        val tt = t.split(' ').toSet()
        s += qt.count { it in tt } * 2
        if (subtype.contains("posture") || subtype.contains("asana") || subtype.contains("guide") || subtype == "page") s += 1
        return s
    }

    /** The page's Open Graph card — title, description, image — from its first 64KB. */
    fun meta(url: String): Meta {
        val head = runCatching {
            client.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.source()?.let { s -> s.request(65536); s.buffer.snapshot().utf8() }.orEmpty()
                }
        }.getOrDefault("")
        fun og(prop: String): String? =
            Regex("""<meta[^>]+(?:property|name)=["']og:$prop["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:$prop["']""", RegexOption.IGNORE_CASE)
                    .find(head)?.groupValues?.get(1)
        fun clean(s: String?) = s?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }?.takeIf { it.isNotBlank() }
        return Meta(clean(og("title")), clean(og("description")), og("image")?.takeIf { it.startsWith("http") })
    }

    /** A remote picture decoded at card size, never full resolution. */
    fun bitmap(url: String, maxDim: Int = 900): Bitmap? = runCatching {
        val bytes = client.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.bytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun get(url: String, auth: String? = null): String? = runCatching {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (auth != null) b.header("Authorization", auth)
        client.newCall(b.get().build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /**
     * REFERENCE PAGES for any prompt — the forty pages closest in meaning to [material], as a
     * block an engine or the chat may cite by exact URL (every cited URL becomes a gram). The one
     * the day page's synth uses, now shared with Ask my Ledger (Michael, 09-13: "read a note or
     * chat with me and then put ten poses on the page"). Empty without sites or an index.
     */
    fun referenceBlock(context: android.content.Context, material: String): String {
        val sites = SiteStore.all(context).filter { it.url.trim().startsWith("http") }
        if (sites.isEmpty() || material.isBlank()) return ""
        val hits = runCatching { topic(context, material.take(1500), sites, n = 40, minScore = 0.30) }.getOrDefault(emptyList())
        if (hits.isEmpty()) return ""
        return "\n\nREFERENCE PAGES ON MY SITES — when the answer touches one of these, cite it by its exact URL " +
            "on its own line (each cited URL becomes a link gram on a page); never invent a URL:\n" +
            hits.joinToString("\n") { "- ${it.title} — ${it.url}" }
    }

    /** The URLs in [text] that point at one of the configured sites, each once, in order. */
    fun citedSiteUrls(context: android.content.Context, text: String): List<String> {
        val hosts = SiteStore.all(context).mapNotNull { runCatching { java.net.URI(it.url.trim()).host?.removePrefix("www.") }.getOrNull() }.filter { it.isNotBlank() }
        if (hosts.isEmpty()) return emptyList()
        return Regex("""https?://[^\s)\]>"'<]+""").findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '*') }.distinct()
            .filter { u -> runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull()?.let { h -> h in hosts } == true }.toList()
    }

    /** The engine that reads a page for the things worth linking. */
    const val ITEMS_PROMPT = "You are reading one page of a yoga teacher's notes (typed lines, handwriting read as text, " +
        "and picks). List every NAMED yoga posture (asana), exercise, drill, technique, sequence or concept that appears, " +
        "one per line, using the canonical name (Sanskrit transliteration for postures, e.g. \"Marichyasana C\", " +
        "\"Utthita Trikonasana\"); no numbering, no commentary, no duplicates, nothing that is not a name. Output only the list."

    /** The engine that sorts a page's picks into sections. */
    /** The POSTER: a topic and the pages found for it → a title, one lead sentence, and 2–5 named
     *  sections each with a one-line lead and the pages that belong under it (numbered). */
    const val POSTER_PROMPT = "You are composing a one-page poster (an infographic) for a yoga teacher from a TOPIC and " +
        "a numbered list of pages from his own sites (title · excerpt). Reply with JSON only, no prose: " +
        "{\"title\":\"a poster title, 2-6 words\",\"intro\":\"one sentence saying what this page is\"," +
        "\"sections\":[{\"title\":\"short section name\",\"lead\":\"one plain sentence, under 18 words\",\"items\":[0,3]}]} — " +
        "2 to 5 sections, every index at most once, drop a page only if it truly doesn't fit, order the sections the way a reader would walk them."

    const val GROUP_PROMPT = "These are the picks on one page of a notebook, numbered. Group them into 2 to 6 named sections " +
        "by theme (for a yoga page: by body region, by series, by kind — postures / drills / readings — whatever fits). " +
        "Reply with JSON only, no prose: {\"sections\":[{\"title\":\"...\",\"items\":[0,3]}]} — short titles, every index " +
        "at most once, and leave out nothing that fits somewhere."
}
