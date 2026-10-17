package com.toolsboox.plugin.reader.nw

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TRACKERS THAT KNOW DC AND MARVEL (Michael, 09-13: "DO find DC or Marvel titles"). The manga
 * trackers Suwayomi ships (AniList, MAL…) have no Batman; these three do:
 *
 *  • KOMGA — his own comics server (comics.mjh.yoga). A series bound here gets REAL read
 *    progress: each finished chapter marks the matching Komga book read, and every Komga client
 *    (Mihon, Panels, the web) sees it. Search = Komga's series search.
 *  • COMIC VINE — the comics database (every DC/Marvel volume, with issue counts and covers).
 *    Identity and the issue count come from there; the read record is Ledgable's own (Comic
 *    Vine's API has no user lists). Needs a free API key.
 *  • METRON — the open comics metadata database (metron.cloud). Same shape as Comic Vine;
 *    needs a free account.
 *
 * Each is an EXTENSION: configured under Bookshelf › wrench › Trackers, offered on a comic's
 * "Track this series…" only when configured. [TrackerStore] holds the binding and the last
 * chapter read; the reader pushes on every finished chapter.
 */
object ComicTrackers {
    const val KOMGA = "komga"; const val COMICVINE = "comicvine"; const val METRON = "metron"; const val SUWAYOMI = "suwayomi"

    data class Provider(val key: String, val name: String)
    data class Hit(val provider: String, val remoteId: String, val title: String, val detail: String, val url: String, val total: Int)

    private const val PREFS = "ledger_tracker_prefs"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Configuration ────────────────────────────────────────────────────────────────────────

    data class Komga(val base: String, val user: String, val pass: String)
    fun komga(ctx: Context): Komga? {
        val p = prefs(ctx)
        val url = p.getString("komga_url", "").orEmpty().trim()
        if (url.isBlank()) return null
        val base = (if (url.startsWith("http")) url else "https://$url").trimEnd('/')
        return Komga(base, p.getString("komga_user", "").orEmpty(), p.getString("komga_pass", "").orEmpty())
    }
    fun setKomga(ctx: Context, url: String, user: String, pass: String) =
        prefs(ctx).edit().putString("komga_url", url.trim()).putString("komga_user", user.trim()).putString("komga_pass", pass).apply()

    fun comicVineKey(ctx: Context): String = prefs(ctx).getString("comicvine_key", "").orEmpty().trim()
    fun setComicVineKey(ctx: Context, key: String) = prefs(ctx).edit().putString("comicvine_key", key.trim()).apply()

    data class Metron(val user: String, val pass: String)
    fun metron(ctx: Context): Metron? {
        val p = prefs(ctx)
        val u = p.getString("metron_user", "").orEmpty().trim()
        return if (u.isBlank()) null else Metron(u, p.getString("metron_pass", "").orEmpty())
    }
    fun setMetron(ctx: Context, user: String, pass: String) =
        prefs(ctx).edit().putString("metron_user", user.trim()).putString("metron_pass", pass).apply()

    fun providers(ctx: Context): List<Provider> = listOfNotNull(
        komga(ctx)?.let { Provider(KOMGA, "Komga") },
        comicVineKey(ctx).takeIf { it.isNotBlank() }?.let { Provider(COMICVINE, "Comic Vine") },
        metron(ctx)?.let { Provider(METRON, "Metron") },
    )

    fun providerName(key: String) = when (key) { KOMGA -> "Komga"; COMICVINE -> "Comic Vine"; METRON -> "Metron"; else -> key }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────

    class TrackerException(message: String) : Exception(message)

    private fun http(url: String, method: String = "GET", basic: Pair<String, String>? = null, body: String? = null, ua: String? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000; conn.readTimeout = 30_000
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json")
        ua?.let { conn.setRequestProperty("User-Agent", it) }
        basic?.let { (u, p) ->
            conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$u:$p".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) throw TrackerException("That tracker wants a sign-in — check its details under Trackers.")
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) throw TrackerException("The tracker answered $code.")
        return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── Search ───────────────────────────────────────────────────────────────────────────────

    fun search(ctx: Context, provider: String, query: String): List<Hit> = when (provider) {
        KOMGA -> {
            val k = komga(ctx) ?: throw TrackerException("Komga isn't set up.")
            val arr = http("${k.base}/api/v1/series?search=${enc(query)}&size=20", basic = k.user to k.pass).optJSONArray("content") ?: org.json.JSONArray()
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i); val meta = s.optJSONObject("metadata") ?: JSONObject()
                Hit(KOMGA, s.optString("id"), s.optString("name"),
                    listOf(meta.optString("publisher"), "${s.optInt("booksCount")} books").filter { it.isNotBlank() }.joinToString(" · "),
                    "${k.base}/series/${s.optString("id")}", s.optInt("booksCount"))
            }
        }
        COMICVINE -> {
            val key = comicVineKey(ctx).ifBlank { throw TrackerException("Comic Vine needs an API key.") }
            val arr = http("https://comicvine.gamespot.com/api/search/?api_key=$key&format=json&resources=volume&limit=20&field_list=id,name,start_year,publisher,count_of_issues,site_detail_url&query=${enc(query)}",
                ua = "Ledgable/1.0 (comic tracker)").optJSONArray("results") ?: org.json.JSONArray()
            (0 until arr.length()).map { i ->
                val v = arr.getJSONObject(i)
                Hit(COMICVINE, v.optString("id"), v.optString("name"),
                    listOf(v.optJSONObject("publisher")?.optString("name").orEmpty(), v.optString("start_year"), "${v.optInt("count_of_issues")} issues").filter { it.isNotBlank() }.joinToString(" · "),
                    v.optString("site_detail_url"), v.optInt("count_of_issues"))
            }
        }
        METRON -> {
            val m = metron(ctx) ?: throw TrackerException("Metron needs a sign-in.")
            val arr = http("https://metron.cloud/api/series/?name=${enc(query)}", basic = m.user to m.pass, ua = "Ledgable/1.0 (comic tracker)").optJSONArray("results") ?: org.json.JSONArray()
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                Hit(METRON, s.optString("id"), s.optString("series").ifBlank { s.optString("name") },
                    listOf(s.optString("year_began"), "${s.optInt("issue_count")} issues").filter { it.isNotBlank() && it != "0" }.joinToString(" · "),
                    "https://metron.cloud/series/${s.optString("id")}", s.optInt("issue_count"))
            }
        }
        else -> throw TrackerException("Unknown tracker.")
    }

    // ── Progress ─────────────────────────────────────────────────────────────────────────────

    /**
     * A finished chapter. Komga: the matching book (by number) is marked read server-side.
     * Comic Vine / Metron: the record is local. Returns the status line to show.
     */
    fun progress(ctx: Context, folder: String, bound: TrackerBridge.Bound, chapter: Float, chapterName: String): String {
        val n = if (chapter % 1f == 0f) chapter.toInt().toString() else chapter.toString()
        if (bound.provider == KOMGA) {
            val k = komga(ctx) ?: throw TrackerException("Komga isn't set up.")
            val books = http("${k.base}/api/v1/series/${bound.remoteId}/books?size=1000&sort=metadata.numberSort", basic = k.user to k.pass).optJSONArray("content") ?: org.json.JSONArray()
            var bookId: String? = null
            for (i in 0 until books.length()) {
                val b = books.getJSONObject(i)
                val num = b.optJSONObject("metadata")?.optString("number")?.toFloatOrNull() ?: b.optDouble("number", -1.0).toFloat()
                if (num == chapter) { bookId = b.optString("id"); break }
            }
            if (bookId == null && chapterName.isNotBlank()) for (i in 0 until books.length()) {
                val b = books.getJSONObject(i)
                if (b.optString("name").substringBeforeLast('.').equals(chapterName.substringBeforeLast('.'), true)) { bookId = b.optString("id"); break }
            }
            if (bookId != null) http("${k.base}/api/v1/books/$bookId/read-progress", method = "PATCH", basic = k.user to k.pass, body = "{\"completed\":true}")
            TrackerStore.recordProgress(ctx, folder, chapter)
            return if (bookId != null) "📈 Komga · ${bound.title} · #$n read" else "📈 Komga · ${bound.title} · #$n (no matching book on the server)"
        }
        TrackerStore.recordProgress(ctx, folder, chapter)
        val total = if (bound.total > 0) "/${bound.total}" else ""
        return "📈 ${providerName(bound.provider)} · ${bound.title} · #$n$total"
    }
}
