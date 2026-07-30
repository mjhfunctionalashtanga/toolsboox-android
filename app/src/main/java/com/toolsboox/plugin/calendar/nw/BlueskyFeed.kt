package com.toolsboox.plugin.calendar.nw

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The Bluesky TIMELINE, read through michaeljoelhall.com — the third leg of the loop.
 *
 * [BlueskyReply] already carried the correspondence both ways: the replies awaiting an answer, and
 * the answers going back. What was missing was the ordinary act of reading — the following-timeline
 * itself — so Bluesky was somewhere he could be answered but not somewhere he could look. This is
 * that half, and it deliberately reuses [BlueskyReply]'s stored base URL and `X-MF-Secret` rather
 * than growing a second pair of settings fields: they are the same server, the same secret and the
 * same auth scheme, and two sets of credentials for one bridge is two things to get out of sync.
 *
 * NO AT PROTOCOL CLIENT HERE EITHER, for exactly the reason [BlueskyReply] gives: the app password
 * lives on the VPS and stays there. The VPS mirrors the timeline into a table on the site and this
 * reads that table over `mjh/v1/bsky-feed`. The device never authenticates to Bluesky at all.
 *
 * TWO THINGS THIS DELIBERATELY DOES NOT MODEL, and both are absences rather than omissions:
 *
 *  • **`liked`.** The server stores the column and always returns false, and there is no route to
 *    set it. A heart in the list would therefore be a control that could not work — the exact class
 *    of thing this app has been removing — so the field is not read and nothing renders it. When a
 *    like route exists this gains a field; it is a route away, not a migration.
 *  • **Anything that parses the cursor.** It is base64 of the full `(created_gmt, id)` keyset, and
 *    the contract says opaque and means it. A client that reconstructed it from a timestamp would
 *    break on the second-level collisions Bluesky produces whenever a thread is posted in a burst —
 *    silently re-serving or skipping those posts. It is carried back verbatim and never inspected.
 */
object BlueskyFeed {

    /** The server clamps `limit` at 100 and defaults to 30. Named here so the client asks for
     *  something the server will actually honour instead of asking for 500 and quietly getting 100. */
    const val MAX_LIMIT = 100

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * One mirrored post.
     *
     * [uri] is the AT URI and the row's natural key — it is what `bsky-feed-seen` takes and what
     * matches a post against the reply queue's `bsky_uri`, so it is the one field nothing may
     * shorten or normalise. [createdAt] arrives SITE-LOCAL with no offset ("2026-07-29 11:47:03"),
     * matching `/bsky-inbox`; the site did the timezone maths so no client has to. [replyTo] is the
     * parent's AT URI when this post is a reply and empty when it is not — on a real timeline that
     * is most of them, which is why the list marks it.
     */
    data class Item(
        val uri: String,
        val cid: String,
        val author: String,
        val handle: String,
        val avatar: String,
        val text: String,
        val createdAt: String,
        val images: List<String>,
        val replyTo: String,
        val url: String,
        val seen: Boolean,
    ) {
        /** True when this post is a reply to something. */
        val isReply: Boolean get() = replyTo.isNotBlank()
    }

    /** One page of the timeline. [cursor] is empty when this was the last page — the contract
     *  guarantees that, so a caller stops on a blank check rather than comparing counts (which is
     *  unreliable the moment the server's pruner removes a row between two requests). */
    data class Page(val items: List<Item>, val cursor: String)

    /**
     * One page, newest first. Blocking — call from Dispatchers.IO. An empty page (rather than an
     * exception) on any failure, matching every other network object in this package: a timeline
     * that cannot be reached is a timeline with nothing in it, and the list surface says so.
     */
    fun page(context: Context, limit: Int = MAX_LIMIT, cursor: String? = null): Page {
        val c = BlueskyReply.config(context)
        if (!c.ready) return Page(emptyList(), "")
        val url = StringBuilder("${c.base}/bsky-feed?limit=${limit.coerceIn(1, MAX_LIMIT)}")
        // Percent-encoded even though the server's own encoder only ever emits URL-safe base64:
        // the value is opaque, and treating an opaque token as if we knew its alphabet is how a
        // client breaks the day the server changes an encoding it never promised to keep.
        if (!cursor.isNullOrBlank()) {
            url.append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        }
        return try {
            val req = Request.Builder().url(url.toString())
                .header("X-MF-Secret", c.secret)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("bsky feed %d: %s", resp.code, resp.body?.string()?.take(200).orEmpty())
                    return Page(emptyList(), "")
                }
                val root = JSONObject(resp.body?.string() ?: return Page(emptyList(), ""))
                val arr = root.optJSONArray("items") ?: JSONArray()
                val items = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val uri = o.optString("uri", "")
                    // A row with no AT URI cannot be marked seen and cannot be matched to the reply
                    // queue, so it is not a post this app can do anything with.
                    if (uri.isBlank()) return@mapNotNull null
                    val imgs = o.optJSONArray("images")
                    Item(
                        uri = uri,
                        cid = o.optString("cid", ""),
                        author = o.optString("author", ""),
                        handle = o.optString("handle", ""),
                        avatar = o.optString("avatar", ""),
                        text = o.optString("text", ""),
                        createdAt = o.optString("created_at", ""),
                        images = if (imgs == null) emptyList() else (0 until imgs.length())
                            .map { imgs.optString(it, "") }.filter { it.startsWith("http") },
                        replyTo = o.optString("reply_to", ""),
                        url = o.optString("url", ""),
                        seen = o.optBoolean("seen", false),
                    )
                }
                Page(items, root.optString("cursor", ""))
            }
        } catch (e: Exception) {
            Timber.w(e, "bsky feed fetch failed")
            Page(emptyList(), "")
        }
    }

    /**
     * The timeline as one list, following the cursor until it runs out or [max] is reached.
     *
     * Paged here rather than in the list surface because the surface has no pagination idiom to
     * borrow: every other source in the Feed Ledger (Later walks 120 days, Pickings walks 60) loads
     * its whole list at once and the reader pages within it. [max] is a real ceiling, not a
     * formality — the server caps the mirror at 400 rows, and a timeline is a thing you skim the
     * top of rather than scroll to the bottom of.
     */
    fun recent(context: Context, max: Int = 200): List<Item> {
        val out = ArrayList<Item>(max.coerceAtMost(400))
        var cursor: String? = null
        val seenCursors = HashSet<String>()
        while (out.size < max) {
            val page = page(context, MAX_LIMIT, cursor)
            if (page.items.isEmpty()) break
            out.addAll(page.items)
            // A cursor that repeats means the server is serving the same page again — a bug we do
            // not want to meet as an infinite loop on a device with no way to interrupt it.
            if (page.cursor.isBlank() || !seenCursors.add(page.cursor)) break
            cursor = page.cursor
        }
        return if (out.size > max) out.subList(0, max).toList() else out
    }

    /**
     * Mark posts read. Batched by contract: a request per row as the list scrolls would be one
     * cellular round trip per post, which on a Boox is both slow and a battery cost. Idempotent
     * server-side, so a retry after a dropped connection re-sends the whole batch safely.
     *
     * Blocking — call from Dispatchers.IO. Returns whether the site accepted the batch; a caller
     * that gets false should keep the URIs and try again rather than dropping them, because a
     * silently-lost batch means those posts come back unread on the next refresh.
     */
    fun markSeen(context: Context, uris: Collection<String>): Boolean {
        val c = BlueskyReply.config(context)
        val clean = uris.filter { it.isNotBlank() }.distinct()
        if (!c.ready || clean.isEmpty()) return false
        return try {
            val body = JSONObject().put("uris", JSONArray(clean)).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url("${c.base}/bsky-feed-seen").post(body)
                .header("X-MF-Secret", c.secret)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.w(e, "bsky feed seen failed")
            false
        }
    }
}
