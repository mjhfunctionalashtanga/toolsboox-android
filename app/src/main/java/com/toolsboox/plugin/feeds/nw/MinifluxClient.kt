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

    private fun fetch(baseUrl: String, token: String, filter: String, limit: Int): Result<List<FeedEntry>> {
        if (baseUrl.isBlank() || token.isBlank()) return Result.Err("Add your Miniflux URL and token in Settings.")
        val url = "${normalize(baseUrl)}/v1/entries?$filter&order=published_at&direction=desc&limit=$limit"
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
            out += FeedEntry(
                id = e.optLong("id"),
                title = e.optString("title"),
                feedTitle = feed?.optString("title").orEmpty(),
                url = e.optString("url"),
                author = if (e.isNull("author")) null else e.optString("author").ifBlank { null },
                content = e.optString("content"),
                publishedAt = e.optString("published_at"),
                starred = e.optBoolean("starred", false),
                category = feed?.optJSONObject("category")?.optString("title")?.ifBlank { null }
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
