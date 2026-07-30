package com.toolsboox.plugin.feeds.nw

import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Minimal Miniflux REST client for the in-app Feed Ledger — just what the list + reader
 * need (fetch unread, toggle star, mark read). Token auth via the `X-Auth-Token` header;
 * credentials are held on-device in EncryptedSharedPreferences by the fragment.
 */
class MinifluxClient @Inject constructor() {

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String) : Result<Nothing>()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    /** GET /v1/entries?status=unread — newest first. Blocking; call off the main thread. */
    fun fetchUnread(baseUrl: String, token: String, limit: Int = 50): Result<List<FeedEntry>> =
        fetch(baseUrl, token, "status=unread", limit)

    /** GET /v1/entries?starred=true — the read-later shelf. */
    fun fetchStarred(baseUrl: String, token: String, limit: Int = 50): Result<List<FeedEntry>> =
        fetch(baseUrl, token, "starred=true", limit)

    /** GET /v1/entries?status=read — the read-history (Feed Read / Watched / Listened). */
    fun fetchRead(baseUrl: String, token: String, limit: Int = 100): Result<List<FeedEntry>> =
        fetch(baseUrl, token, "status=read", limit)

    /** GET /v1/entries with no status filter — read AND unread together ("All"). */
    fun fetchEverything(baseUrl: String, token: String, limit: Int = 150): Result<List<FeedEntry>> =
        fetch(baseUrl, token, "globally_visible=true", limit)

    /** Full-text search across every entry on the server (Miniflux `search=`). */
    fun search(baseUrl: String, token: String, query: String, limit: Int = 100): Result<List<FeedEntry>> =
        fetch(baseUrl, token, "search=${java.net.URLEncoder.encode(query, "UTF-8")}", limit)

    /** Entries PUBLISHED inside [afterEpochSec, beforeEpochSec), optionally filtered by
     *  status ("unread"/"read", null = all) — the Ledger Log's opt-in feed window. */
    fun fetchPublishedWindow(
        baseUrl: String, token: String, status: String?,
        afterEpochSec: Long, beforeEpochSec: Long, limit: Int = 200
    ): Result<List<FeedEntry>> {
        val statusFilter = if (status != null) "status=$status" else "globally_visible=true"
        return fetch(baseUrl, token,
            "$statusFilter&published_after=$afterEpochSec&published_before=$beforeEpochSec", limit)
    }

    /**
     * GET /v1/categories/{id}/entries — ONE FOLDER'S OWN LIST, asked of the server directly.
     *
     * The media lenses (📖 The Read / 📺 The Watch / 🎧 The Listen) used to be a client-side sift
     * of whatever [fetchUnread] had already handed back — and that page is 50 rows deep across
     * every feed on the server. Michael's text feeds publish dozens of items a day and his podcasts
     * publish one an episode, so the newest 50 unread rows routinely held no audio at all and The
     * Listen said "(nothing in this lens yet)" while a full folder of episodes sat on the server.
     * A lens has to ASK for its folders, the way the iPad does (`FeedStore.entries(for: .media)`
     * merges `client.entries(categoryID:)` per category) — not hope they float to the top of All.
     *
     * [status] is "unread"/"read", or null for the folder's whole list.
     */
    fun fetchCategory(
        baseUrl: String, token: String, categoryId: Long, status: String?, limit: Int = 100
    ): Result<List<FeedEntry>> =
        fetch(baseUrl, token, if (status != null) "status=$status" else "", limit,
            path = "/v1/categories/$categoryId/entries")

    private fun fetch(
        baseUrl: String, token: String, filter: String, limit: Int, path: String = "/v1/entries"
    ): Result<List<FeedEntry>> {
        if (baseUrl.isBlank() || token.isBlank()) return Result.Err("Add your Miniflux URL and token in Settings.")
        val q = if (filter.isBlank()) "" else "$filter&"
        val url = "${normalize(baseUrl)}$path?${q}order=published_at&direction=desc&limit=$limit"
        return try {
            val req = Request.Builder().url(url).addHeader("X-Auth-Token", token).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Result.Err("Miniflux error ${resp.code}")
                Result.Ok(parseEntries(body))
            }
        } catch (e: Exception) {
            Result.Err("Network error: ${e.message}")
        }
    }

    /** PUT /v1/entries/{id}/bookmark — toggles starred. */
    fun toggleStar(baseUrl: String, token: String, id: Long): Result<Unit> =
        put("${normalize(baseUrl)}/v1/entries/$id/bookmark", token, "")

    /** PUT /v1/entries {entry_ids, status:"read"}. */
    fun markRead(baseUrl: String, token: String, id: Long): Result<Unit> {
        val payload = JSONObject()
            .put("entry_ids", JSONArray().put(id))
            .put("status", "read")
            .toString()
        return put("${normalize(baseUrl)}/v1/entries", token, payload)
    }

    /** PUT /v1/entries {entry_ids, status} — batch mark read/unread (for Clear feed + undo). */
    fun setStatus(baseUrl: String, token: String, ids: List<Long>, status: String): Result<Unit> {
        if (ids.isEmpty()) return Result.Ok(Unit)
        val arr = JSONArray(); for (i in ids) arr.put(i)
        val payload = JSONObject().put("entry_ids", arr).put("status", status).toString()
        return put("${normalize(baseUrl)}/v1/entries", token, payload)
    }

    /** GET /v1/entries/{id}/fetch-content — Miniflux's readability parse of the original page. */
    fun fetchContent(baseUrl: String, token: String, id: Long): Result<String> = try {
        val req = Request.Builder().url("${normalize(baseUrl)}/v1/entries/$id/fetch-content")
            .addHeader("X-Auth-Token", token).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) Result.Err("Miniflux error ${resp.code}")
            else Result.Ok(JSONObject(body).optString("content"))
        }
    } catch (e: Exception) {
        Result.Err("Network error: ${e.message}")
    }

    /** Direct page fetch + crude readability, for entries NOT on the server (id=0 star opens):
     *  strip scripts/styles/nav/chrome, prefer <article>, fall back to <body>. Rough but readable. */
    fun fetchPageReadable(pageUrl: String): Result<String> = try {
        val req = Request.Builder().url(pageUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.Err("Page error ${resp.code}")
            var html = resp.body?.string().orEmpty()
            html = html
                .replace(Regex("(?is)<script.*?</script>"), "")
                .replace(Regex("(?is)<style.*?</style>"), "")
                .replace(Regex("(?is)<nav\\b.*?</nav>"), "")
                .replace(Regex("(?is)<header\\b.*?</header>"), "")
                .replace(Regex("(?is)<footer\\b.*?</footer>"), "")
                .replace(Regex("(?is)<aside\\b.*?</aside>"), "")
            val article = Regex("(?is)<article[^>]*>(.*?)</article>").find(html)?.groupValues?.get(1)
            val body = article
                ?: Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1)
                ?: html
            Result.Ok(body)
        }
    } catch (e: Exception) {
        Result.Err("Network error: ${e.message}")
    }

    // ---- Subscription management (the paste-a-YouTube-link flow) ---------------------------

    /** GET /v1/categories → (id, title) pairs, for landing a new feed in the right lens. */
    fun categories(baseUrl: String, token: String): Result<List<Pair<Long, String>>> = try {
        val req = Request.Builder().url("${normalize(baseUrl)}/v1/categories")
            .addHeader("X-Auth-Token", token).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) Result.Err("Miniflux error ${resp.code}")
            else {
                val arr = JSONArray(body)
                Result.Ok((0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { it.optLong("id") to it.optString("title") }
                })
            }
        }
    } catch (e: Exception) {
        Result.Err("Network error: ${e.message}")
    }

    /** POST /v1/categories {title} → the new category's id. */
    fun createCategory(baseUrl: String, token: String, title: String): Result<Long> =
        post("${normalize(baseUrl)}/v1/categories", token, JSONObject().put("title", title).toString())
            .let { r ->
                when (r) {
                    is Result.Ok -> Result.Ok(JSONObject(r.value).optLong("id"))
                    is Result.Err -> r
                }
            }

    /** POST /v1/feeds {feed_url, category_id} → the new feed's id. This is how a pasted
     *  YouTube link becomes a real server-side subscription, not just a local one. */
    fun createFeed(baseUrl: String, token: String, feedUrl: String, categoryId: Long?): Result<Long> {
        val payload = JSONObject().put("feed_url", feedUrl)
        if (categoryId != null) payload.put("category_id", categoryId)
        return post("${normalize(baseUrl)}/v1/feeds", token, payload.toString()).let { r ->
            when (r) {
                is Result.Ok -> Result.Ok(JSONObject(r.value).optLong("feed_id"))
                is Result.Err -> r
            }
        }
    }

    private fun post(url: String, token: String, payload: String): Result<String> = try {
        val req = Request.Builder().url(url).addHeader("X-Auth-Token", token)
            .post(payload.toRequestBody(json)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) Result.Ok(body) else Result.Err("Miniflux error ${resp.code}")
        }
    } catch (e: Exception) {
        Result.Err("Network error: ${e.message}")
    }

    private fun put(url: String, token: String, payload: String): Result<Unit> = try {
        val req = Request.Builder().url(url).addHeader("X-Auth-Token", token)
            .put(payload.toRequestBody(json)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) Result.Ok(Unit) else Result.Err("Miniflux error ${resp.code}")
        }
    } catch (e: Exception) {
        Result.Err("Network error: ${e.message}")
    }

    /** Parse the {total, entries:[…]} response into [FeedEntry]s. Public for unit tests. */
    fun parseEntries(payload: String): List<FeedEntry> {
        val arr = JSONObject(payload).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<FeedEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val feed = e.optJSONObject("feed")
            // First image enclosure → featured-image fallback for feeds without an inline <img>.
            val encs = e.optJSONArray("enclosures")
            val enclosureImage = encs?.let { arr ->
                (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("mime_type").startsWith("image", true) }
                    ?.optString("url")?.ifBlank { null }
            }
            // First audio enclosure → the real podcast episode file to play.
            val enclosureAudio = encs?.let { arr ->
                (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("mime_type").startsWith("audio", true) }
                    ?.optString("url")?.ifBlank { null }
            }
            // Transcript enclosure (RSS podcast:transcript) when Miniflux happens to surface one —
            // VTT/SRT by mime, or a .vtt/.srt URL. Best-effort (often stripped; see feedUrl note).
            val enclosureTranscript = encs?.let { arr ->
                (0 until arr.length()).asSequence().mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull {
                        val m = it.optString("mime_type").lowercase()
                        val u = it.optString("url").lowercase()
                        m == "text/vtt" || m.contains("subrip") || m == "text/srt" ||
                            u.endsWith(".vtt") || u.endsWith(".srt") ||
                            ((m == "text/html" || m == "text/plain") && u.contains("transcript"))
                    }?.optString("url")?.ifBlank { null }
            }
            out += FeedEntry(
                id = e.optLong("id"),
                title = e.optString("title"),
                feedTitle = feed?.optString("title").orEmpty(),
                url = e.optString("url"),
                author = if (e.isNull("author")) null else e.optString("author").ifBlank { null },
                content = e.optString("content"),
                publishedAt = e.optString("published_at"),
                starred = e.optBoolean("starred", false),
                read = e.optString("status") == "read",
                category = feed?.optJSONObject("category")?.optString("title")?.ifBlank { null },
                enclosureImage = enclosureImage,
                enclosureAudio = enclosureAudio,
                enclosureTranscript = enclosureTranscript,
                // The feed's XML address — the way back to the Podcasting 2.0 tags
                // (chapters/transcripts) the Miniflux API itself never surfaces.
                feedUrl = feed?.optString("feed_url")?.ifBlank { null }
            )
        }
        return out
    }

    private fun normalize(baseUrl: String): String {
        var u = baseUrl.trim().trimEnd('/')
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return u
    }
}
