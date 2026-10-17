package com.toolsboox.plugin.reader.nw

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * COMIC TRACKERS, as extensions of Ledgable (Michael, 09-13: "my mihon style trackers working
 * within the comics as addable extensions"). The trackers themselves are Suwayomi's — the same
 * six Mihon ships (MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi), running on
 * suwayomi.mjh.yoga with Suwayomi's own registered API clients, so Ledgable registers nothing
 * and signs in through the tracker's own OAuth page in The Net. This bridge is the GraphQL
 * client; [TrackerStore] remembers which shelf series is bound to which tracker record.
 *
 * The walk for one series: the shelf folder names the source and the series
 * (`ReadAllComics (EN)/Uncanny X-Force…` — Mihon's own layout) → the matching Suwayomi source
 * is searched for the title → the manga joins Suwayomi's library (a tracker binding needs a
 * manga id) → the tracker is searched → the person picks the entry → bound. From then on every
 * finished chapter in the reader pushes `lastChapterRead`.
 *
 * Server: the OPDS catalog's address when it is a Suwayomi (`…/api/opds/v1.2`), else the one
 * named under Bookshelf › wrench › Trackers › Server. Basic auth, same credentials.
 */
object TrackerBridge {
    data class Server(val base: String, val user: String, val pass: String)
    data class Tracker(val id: Int, val name: String, val loggedIn: Boolean, val authUrl: String, val icon: String)
    data class Hit(val remoteId: String, val title: String, val totalChapters: Int, val summary: String, val url: String)
    /** A series' binding. [provider] = komga | comicvine | metron | suwayomi; [remoteId] is the
     *  provider's id for the series (Suwayomi: the tracker's remote id, with [recordId]/[trackerId]
     *  for its own bookkeeping). [lastChapter] is the last chapter pushed; [total] the provider's count. */
    data class Bound(
        val recordId: Int, val trackerId: Int, val trackerName: String, val mangaId: Int, val title: String, val remoteUrl: String,
        val provider: String = "suwayomi", val remoteId: String = "", val lastChapter: Float = 0f, val total: Int = 0,
    )

    private const val PREFS = "ledger_tracker_prefs"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun server(ctx: Context): Server? {
        val p = prefs(ctx)
        val url = p.getString("tracker_url", "").orEmpty().trim()
        if (url.isNotBlank()) return Server(withScheme(url).trimEnd('/'), p.getString("tracker_user", "").orEmpty(), p.getString("tracker_pass", "").orEmpty())
        val opds = com.toolsboox.plugin.reader.ui.OpdsCatalog.config(ctx) ?: return null
        val u = runCatching { URL(opds.url) }.getOrNull() ?: return null
        if (!opds.url.contains("/api/opds")) return null
        return Server("${u.protocol}://${u.authority}", opds.user, opds.pass)
    }

    fun setServer(ctx: Context, url: String, user: String, pass: String) =
        prefs(ctx).edit().putString("tracker_url", url.trim()).putString("tracker_user", user.trim()).putString("tracker_pass", pass).apply()

    private fun withScheme(u: String) = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"

    /** The tracker whose sign-in page The Net is showing — its callback comes back here. */
    fun setPendingLogin(ctx: Context, trackerId: Int) = prefs(ctx).edit().putInt("tracker_pending_login", trackerId).apply()
    fun pendingLogin(ctx: Context): Int = prefs(ctx).getInt("tracker_pending_login", 0)
    fun clearPendingLogin(ctx: Context) = prefs(ctx).edit().remove("tracker_pending_login").apply()

    /** Is this the tracker's OAuth landing? Suwayomi's clients redirect to `tachiyomi://…-auth`
     *  (AniList, MyAnimeList) or `https://suwayomi.org/tracker-oauth` (Shikimori, Bangumi). */
    fun isOAuthCallback(url: String): Boolean =
        url.startsWith("tachiyomi://") || url.startsWith("https://suwayomi.org/tracker-oauth")

    // ── GraphQL ──────────────────────────────────────────────────────────────────────────────

    class TrackerException(message: String) : Exception(message)

    internal fun gql(srv: Server, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val conn = URL("${srv.base}/api/graphql").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 40_000
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (srv.user.isNotBlank() || srv.pass.isNotBlank()) {
            val token = Base64.encodeToString("${srv.user}:${srv.pass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        conn.outputStream.use { it.write(JSONObject().put("query", query).put("variables", variables).toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 401 || code == 403) throw TrackerException("Suwayomi wants a sign-in — check the server's user and password.")
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) throw TrackerException("Suwayomi answered $code.")
        val obj = JSONObject(text)
        obj.optJSONArray("errors")?.let { if (it.length() > 0) throw TrackerException(it.getJSONObject(0).optString("message", "Suwayomi error")) }
        return obj.optJSONObject("data") ?: JSONObject()
    }

    fun trackers(srv: Server): List<Tracker> {
        val nodes = gql(srv, "{ trackers { nodes { id name isLoggedIn authUrl icon } } }")
            .optJSONObject("trackers")?.optJSONArray("nodes") ?: JSONArray()
        return (0 until nodes.length()).map { i ->
            val t = nodes.getJSONObject(i)
            Tracker(t.optInt("id"), t.optString("name"), t.optBoolean("isLoggedIn"), t.optString("authUrl"), t.optString("icon"))
        }
    }

    fun loginOAuth(srv: Server, trackerId: Int, callbackUrl: String): Boolean =
        gql(srv, "mutation(\$id: Int!, \$cb: String!) { loginTrackerOAuth(input: { trackerId: \$id, callbackUrl: \$cb }) { isLoggedIn } }",
            JSONObject().put("id", trackerId).put("cb", callbackUrl))
            .optJSONObject("loginTrackerOAuth")?.optBoolean("isLoggedIn") == true

    fun loginCredentials(srv: Server, trackerId: Int, user: String, pass: String): Boolean =
        gql(srv, "mutation(\$id: Int!, \$u: String!, \$p: String!) { loginTrackerCredentials(input: { trackerId: \$id, username: \$u, password: \$p }) { isLoggedIn } }",
            JSONObject().put("id", trackerId).put("u", user).put("p", pass))
            .optJSONObject("loginTrackerCredentials")?.optBoolean("isLoggedIn") == true

    fun logout(srv: Server, trackerId: Int) {
        gql(srv, "mutation(\$id: Int!) { logoutTracker(input: { trackerId: \$id }) { isLoggedIn } }", JSONObject().put("id", trackerId))
    }

    /** Mihon's download layout names the source: `ReadAllComics (EN)/<series>` → ("ReadAllComics", "en"). */
    fun sourceOf(folder: String): Pair<String, String>? {
        val top = folder.split("/").firstOrNull()?.trim().orEmpty()
        val m = Regex("""^(.*?)\s*\(([A-Za-z-]+)\)$""").find(top) ?: return null
        return m.groupValues[1].trim() to m.groupValues[2].lowercase()
    }

    fun seriesOf(folder: String): String = folder.split("/").getOrNull(1)?.trim().orEmpty()

    internal fun sourceId(srv: Server, name: String, lang: String): String? {
        val nodes = gql(srv, "{ sources { nodes { id name lang } } }").optJSONObject("sources")?.optJSONArray("nodes") ?: return null
        var loose: String? = null
        for (i in 0 until nodes.length()) {
            val s = nodes.getJSONObject(i)
            val n = s.optString("name"); val l = s.optString("lang")
            if (n.equals(name, true) && l.equals(lang, true)) return s.optString("id")
            if (loose == null && n.replace(" ", "").equals(name.replace(" ", ""), true) && (l.equals(lang, true) || lang == "all")) loose = s.optString("id")
        }
        return loose
    }

    /** The series in Suwayomi's library (added if needed), or null when the source can't find it. */
    fun libraryManga(srv: Server, folder: String): Pair<Int, String>? {
        val (name, lang) = sourceOf(folder) ?: return null
        val series = seriesOf(folder).ifBlank { return null }
        val src = sourceId(srv, name, lang) ?: return null
        val mangas = gql(srv, "mutation(\$src: LongString!, \$q: String!) { fetchSourceManga(input: { source: \$src, type: SEARCH, query: \$q, page: 1 }) { mangas { id title inLibrary } } }",
            JSONObject().put("src", src).put("q", series)).optJSONObject("fetchSourceManga")?.optJSONArray("mangas") ?: return null
        if (mangas.length() == 0) return null
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
        var pick = mangas.getJSONObject(0)
        for (i in 0 until mangas.length()) { val m = mangas.getJSONObject(i); if (norm(m.optString("title")) == norm(series)) { pick = m; break } }
        val id = pick.optInt("id")
        if (!pick.optBoolean("inLibrary")) {
            gql(srv, "mutation(\$id: Int!) { updateManga(input: { id: \$id, patch: { inLibrary: true } }) { manga { id } } }", JSONObject().put("id", id))
        }
        return id to pick.optString("title")
    }

    /**
     * Find a shelf folder's series on the server: through its own source when the folder names
     * one (Mihon's layout), else through the first English sources that answer. Returns the
     * Suwayomi manga id and title, or null.
     */
    fun findSeries(srv: Server, folder: String): Pair<Int, String>? {
        sourceOf(folder)?.let { return libraryManga(srv, folder) }
        val title = folder.substringAfterLast('/').trim().ifBlank { return null }
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
        val sources = SuwayomiSources.sources(srv).filter { it.lang == "en" }.take(4)
        for (src in sources) {
            val hits = runCatching { SuwayomiSources.browse(srv, src.id, "SEARCH", title, 1).first }.getOrDefault(emptyList())
            val pick = hits.firstOrNull { norm(it.title) == norm(title) } ?: continue
            return pick.id to pick.title
        }
        return null
    }

    fun search(srv: Server, trackerId: Int, query: String): List<Hit> {
        val arr = gql(srv, "query(\$id: Int!, \$q: String!) { searchTracker(input: { trackerId: \$id, query: \$q }) { trackSearches { remoteId title totalChapters summary trackingUrl } } }",
            JSONObject().put("id", trackerId).put("q", query)).optJSONObject("searchTracker")?.optJSONArray("trackSearches") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val h = arr.getJSONObject(i)
            Hit(h.optString("remoteId"), h.optString("title"), h.optInt("totalChapters"), h.optString("summary"), h.optString("trackingUrl"))
        }
    }

    fun bind(srv: Server, mangaId: Int, trackerId: Int, remoteId: String): Int? =
        gql(srv, "mutation(\$m: Int!, \$t: Int!, \$r: LongString!) { bindTrack(input: { mangaId: \$m, trackerId: \$t, remoteId: \$r }) { trackRecord { id } } }",
            JSONObject().put("m", mangaId).put("t", trackerId).put("r", remoteId))
            .optJSONObject("bindTrack")?.optJSONObject("trackRecord")?.optInt("id")?.takeIf { it > 0 }

    /** Push progress; never lowers it (a re-read of chapter 3 doesn't undo chapter 12). */
    fun progress(srv: Server, recordId: Int, chapter: Float): Float {
        val cur = gql(srv, "{ trackRecords { nodes { id lastChapterRead } } }").optJSONObject("trackRecords")?.optJSONArray("nodes")
        var last = 0f
        if (cur != null) for (i in 0 until cur.length()) { val r = cur.getJSONObject(i); if (r.optInt("id") == recordId) last = r.optDouble("lastChapterRead", 0.0).toFloat() }
        if (chapter <= last) return last
        gql(srv, "mutation(\$id: Int!, \$c: Float!) { updateTrack(input: { recordId: \$id, lastChapterRead: \$c }) { trackRecord { id } } }",
            JSONObject().put("id", recordId).put("c", chapter.toDouble()))
        return chapter
    }

    fun unbind(srv: Server, recordId: Int) {
        runCatching { gql(srv, "mutation(\$id: Int!) { unbindTrack(input: { recordId: \$id, deleteRemoteTrack: false }) { trackRecord { id } } }", JSONObject().put("id", recordId)) }
            .onFailure { Timber.w(it, "TrackerBridge: unbind failed") }
    }

    /**
     * The chapter a file is: Mihon names chapters "Chapter 12", "Ch. 12.5", "#12", "Part 4",
     * "Vol. 2 Ch. 7"; failing all of those, the file's place among its folder's siblings (title
     * order = chapter order for Mihon downloads).
     */
    fun chapterNumber(name: String, siblingIndex: Int): Float {
        val stem = name.substringBeforeLast('.')
        val pats = listOf(
            Regex("""(?i)\bch(?:apter)?\.?\s*(\d+(?:\.\d+)?)"""),
            Regex("""(?i)#\s*(\d+(?:\.\d+)?)"""),
            Regex("""(?i)\bpart\s*(\d+(?:\.\d+)?)"""),
            Regex("""(?i)\bepisode\s*(\d+(?:\.\d+)?)"""),
            Regex("""(?i)(?:^|\s)(\d+(?:\.\d+)?)\s*$"""),
        )
        for (p in pats) p.find(stem)?.let { return it.groupValues[1].toFloatOrNull() ?: (siblingIndex + 1).toFloat() }
        return (siblingIndex + 1).toFloat()
    }
}

/** Which shelf series (folder) is bound to which tracker record — per device, tiny. */
object TrackerStore {
    private const val PREFS = "ledger_tracker_bindings"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bound(ctx: Context, folder: String): TrackerBridge.Bound? {
        val s = prefs(ctx).getString(folder, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            TrackerBridge.Bound(o.optInt("recordId"), o.optInt("trackerId"), o.optString("trackerName"), o.optInt("mangaId"), o.optString("title"), o.optString("remoteUrl"),
                provider = o.optString("provider", "suwayomi"), remoteId = o.optString("remoteId"),
                lastChapter = o.optDouble("lastChapter", 0.0).toFloat(), total = o.optInt("total"))
        }.getOrNull()
    }

    fun bind(ctx: Context, folder: String, b: TrackerBridge.Bound) {
        prefs(ctx).edit().putString(folder, JSONObject().put("recordId", b.recordId).put("trackerId", b.trackerId)
            .put("trackerName", b.trackerName).put("mangaId", b.mangaId).put("title", b.title).put("remoteUrl", b.remoteUrl)
            .put("provider", b.provider).put("remoteId", b.remoteId).put("lastChapter", b.lastChapter.toDouble()).put("total", b.total).toString()).apply()
    }

    /** The last chapter pushed, kept locally for every provider (the record Comic Vine and Metron can't hold). */
    fun recordProgress(ctx: Context, folder: String, chapter: Float) {
        val b = bound(ctx, folder) ?: return
        if (chapter > b.lastChapter) bind(ctx, folder, b.copy(lastChapter = chapter))
    }

    fun unbind(ctx: Context, folder: String) = prefs(ctx).edit().remove(folder).apply()
}


/**
 * THE SOURCES, INSIDE LEDGABLE (Michael, 09-13: "I really only need the sources"): BatCave,
 * ReadAllComics, Comick, MangaDex, Weeb Central… — the keiyoushi extensions Suwayomi runs
 * server-side — browsed and searched from the Bookshelf, a series added to Suwayomi's library
 * and its chapters queued for download there. Downloads land as CBZ in Komga's library and in
 * Suwayomi's OPDS, which is the catalog the Bookshelf already pulls from — so "download" here
 * is "it'll be on the shelf's catalog shortly", no Mihon in the story.
 */
object SuwayomiSources {
    data class Source(val id: String, val name: String, val lang: String, val supportsLatest: Boolean)
    data class Series(val id: Int, val title: String, val thumb: String, val inLibrary: Boolean)
    data class Chapter(val id: Int, val name: String, val number: Float, val downloaded: Boolean)

    private fun gql(srv: TrackerBridge.Server, q: String, v: JSONObject = JSONObject()): JSONObject = TrackerBridge.gql(srv, q, v)

    fun sources(srv: TrackerBridge.Server): List<Source> {
        val nodes = gql(srv, "{ sources { nodes { id name lang supportsLatest } } }").optJSONObject("sources")?.optJSONArray("nodes") ?: JSONArray()
        return (0 until nodes.length()).map { i -> val s = nodes.getJSONObject(i)
            Source(s.optString("id"), s.optString("name"), s.optString("lang"), s.optBoolean("supportsLatest")) }
            .filter { it.id != "0" }
            .sortedWith(compareBy({ it.lang != "en" }, { it.name.lowercase() }))
    }

    /** [type] = SEARCH | POPULAR | LATEST. */
    fun browse(srv: TrackerBridge.Server, sourceId: String, type: String, query: String, page: Int = 1): Pair<List<Series>, Boolean> {
        val d = gql(srv, "mutation(\$src: LongString!, \$t: FetchSourceMangaType!, \$q: String, \$p: Int!) { fetchSourceManga(input: { source: \$src, type: \$t, query: \$q, page: \$p }) { hasNextPage mangas { id title thumbnailUrl inLibrary } } }",
            JSONObject().put("src", sourceId).put("t", type).put("q", query.ifBlank { JSONObject.NULL }).put("p", page)).optJSONObject("fetchSourceManga") ?: return emptyList<Series>() to false
        val arr = d.optJSONArray("mangas") ?: JSONArray()
        return (0 until arr.length()).map { i -> val m = arr.getJSONObject(i)
            Series(m.optInt("id"), m.optString("title"), m.optString("thumbnailUrl"), m.optBoolean("inLibrary")) } to d.optBoolean("hasNextPage")
    }

    fun addToLibrary(srv: TrackerBridge.Server, id: Int) {
        gql(srv, "mutation(\$id: Int!) { updateManga(input: { id: \$id, patch: { inLibrary: true } }) { manga { id } } }", JSONObject().put("id", id))
    }

    fun chapters(srv: TrackerBridge.Server, mangaId: Int): List<Chapter> {
        val arr = gql(srv, "mutation(\$id: Int!) { fetchChapters(input: { mangaId: \$id }) { chapters { id name chapterNumber isDownloaded sourceOrder } } }",
            JSONObject().put("id", mangaId)).optJSONObject("fetchChapters")?.optJSONArray("chapters") ?: JSONArray()
        return (0 until arr.length()).map { i -> val c = arr.getJSONObject(i)
            Chapter(c.optInt("id"), c.optString("name"), c.optDouble("chapterNumber", 0.0).toFloat(), c.optBoolean("isDownloaded")) }
            .sortedBy { it.number }
    }

    fun download(srv: TrackerBridge.Server, chapterIds: List<Int>) {
        if (chapterIds.isEmpty()) return
        gql(srv, "mutation(\$ids: [Int!]!) { enqueueChapterDownloads(input: { ids: \$ids }) { downloadStatus { state } } }",
            JSONObject().put("ids", JSONArray(chapterIds)))
    }

    /** The series' page in Suwayomi's web UI, for The Net. */
    fun webUrl(srv: TrackerBridge.Server, mangaId: Int) = "${srv.base}/manga/$mangaId"
}
