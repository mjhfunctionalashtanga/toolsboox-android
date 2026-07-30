package com.toolsboox.plugin.calendar.nw

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Basic WordPress publishing from inside Ledger, over the core REST API (wp-json/wp/v2) using the
 * ACTIVE site's application password — the same creds the Fluent bridge uses (read through
 * [LedgerWebBridge.config], i.e. the `site`/`user`/`pass` keys in `ledgr_bridge_prefs`). Compose a
 * post, choose its type (any public CPT via rest_base), set categories/tags, attach an image as the
 * featured image, and move it through the lifecycle: draft -> schedule -> publish -> trash.
 *
 * The publishing counterpart to building a CRM campaign or a Boards card from the app. Mirrors iOS
 * `WPPublish.swift`. All calls BLOCK — invoke them from Dispatchers.IO — and swallow failures to an
 * empty/null/false result, matching the other network objects in this package.
 */
object WPPublish {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** A public post type the user can create — posts, pages, and any public CPT. */
    data class PostType(val restBase: String, val name: String, val hierarchical: Boolean)

    /** One taxonomy term (a category or a tag). */
    data class Term(val id: Int, val name: String)

    /** An existing post, for the browser + editing. */
    data class WpPost(
        val id: Int,
        val type: String,          // rest_base of its type
        val title: String,
        val status: String,        // publish | future | draft | pending | private | trash
        val date: String,          // ISO-ish, as WP returns it
        val link: String,
        val content: String,       // raw when available, else rendered
        val featuredMedia: Int,
        val categories: List<Int>,
        val tags: List<Int>,
    )

    /** The compose model. [dateIso] is set only when [status] == "future". */
    data class Draft(
        var type: String = "posts",
        var title: String = "",
        var content: String = "",
        var status: String = "draft",     // draft | publish | future | private
        var dateIso: String? = null,
        var categories: List<Int> = emptyList(),
        var tags: List<Int> = emptyList(),
        var featuredMedia: Int = 0,
    )

    /** Result of a create/update. [error] carries WP's own words when the site refused the save. */
    data class SaveResult(val ok: Boolean, val id: Int?, val link: String?, val error: String? = null)

    // MARK: - Config (active WP site)

    /** The bridge's stored creds double as the WP application password for wp/v2. */
    fun configured(context: Context): Boolean {
        val c = LedgerWebBridge.config(context)
        return c.site.isNotBlank() && c.user.isNotBlank() && c.pass.isNotBlank()
    }

    private fun base(context: Context): String? {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return null
        return c.site.trimEnd('/') + "/wp-json/wp/v2/"
    }

    private fun auth(context: Context): String {
        val c = LedgerWebBridge.config(context)
        return Credentials.basic(c.user, c.pass)
    }

    private fun getResp(context: Context, path: String): okhttp3.Response? {
        val b = base(context) ?: return null
        return try {
            client.newCall(
                Request.Builder().url(b + path).header("Authorization", auth(context)).build()
            ).execute()
        } catch (e: Exception) {
            Timber.w(e, "wp GET %s failed", path); null
        }
    }

    // MARK: - Taxonomy / types

    /** Public post types the user can create — posts, pages, and any public CPT (by rest_base). */
    fun postTypes(context: Context): List<PostType> {
        val resp = getResp(context, "types?context=edit") ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val obj = try { JSONObject(r.body?.string() ?: return emptyList()) } catch (e: Exception) { return emptyList() }
            val out = mutableListOf<PostType>()
            val skip = setOf(
                "attachment", "nav_menu_item", "wp_block", "wp_template",
                "wp_template_part", "wp_navigation", "wp_font_family", "wp_font_face", "wp_global_styles"
            )
            for (slug in obj.keys()) {
                if (slug in skip) continue
                val t = obj.optJSONObject(slug) ?: continue
                val rest = t.optString("rest_base", "")
                if (rest.isBlank()) continue
                out.add(PostType(rest, t.optString("name", slug), t.optBoolean("hierarchical", false)))
            }
            out.sortedBy { it.name.lowercase() }
        }
    }

    /** [taxonomy] is a rest_base: "categories" | "tags". */
    fun terms(context: Context, taxonomy: String): List<Term> {
        val resp = getResp(context, "$taxonomy?per_page=100&_fields=id,name&orderby=name&order=asc") ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = try { JSONArray(r.body?.string() ?: return emptyList()) } catch (e: Exception) { return emptyList() }
            (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                Term(o.optInt("id", 0), o.optString("name", ""))
            }.filter { it.id > 0 }
        }
    }

    /**
     * The one term with this exact SLUG, or null.
     *
     * Distinct from filtering [terms] by name, and the difference is not pedantry: server-side
     * taxonomy queries (`mjh_synd_sources()` on michaeljoelhall.com is the live example) match a
     * post_tag by slug, and a term's slug is not its name — "Essay" can perfectly well carry the
     * slug `essay-2` if `essay` was taken and later deleted. Matching what the server matches is
     * the only way a client can be sure the tag it attached is the tag that will be looked for.
     *
     * It is also one request rather than a page of a hundred: [terms] is capped at `per_page=100`
     * and ordered by name, so on a site whose tag list outgrows that cap a name scan starts
     * silently missing terms that exist — and a miss here reads as "the tag didn't stick".
     */
    fun termBySlug(context: Context, taxonomy: String, slug: String): Term? {
        val q = java.net.URLEncoder.encode(slug, "UTF-8")
        val resp = getResp(context, "$taxonomy?slug=$q&_fields=id,name&per_page=1") ?: return null
        return resp.use { r ->
            if (!r.isSuccessful) return null
            val arr = try { JSONArray(r.body?.string() ?: return null) } catch (e: Exception) { return null }
            val o = arr.optJSONObject(0) ?: return null
            val id = o.optInt("id", 0)
            if (id > 0) Term(id, o.optString("name", slug)) else null
        }
    }

    /** Create a term inline; returns it (with its new id) or null. */
    fun createTerm(context: Context, taxonomy: String, name: String): Term? {
        val b = base(context) ?: return null
        val body = JSONObject().put("name", name).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return try {
            client.newCall(
                Request.Builder().url(b + taxonomy).post(body)
                    .header("Authorization", auth(context)).build()
            ).execute().use { r ->
                val o = try { JSONObject(r.body?.string() ?: return null) } catch (e: Exception) { return null }
                val id = o.optInt("id", 0)
                if (id > 0) return Term(id, o.optString("name", name))
                // `term_exists` is a REFUSAL that carries the answer: WP puts the existing term's
                // id in the error's data. Discarding it made "create the tag if it isn't there"
                // fail in exactly the case where the tag was there — a lookup that missed it, or
                // two devices creating it at once — which is the case it most needed to survive.
                o.optJSONObject("data")?.optInt("term_id", 0)?.takeIf { it > 0 }
                    ?.let { Term(it, name) }
            }
        } catch (e: Exception) {
            Timber.w(e, "wp createTerm failed"); null
        }
    }

    // MARK: - Media (an image -> featured image)

    /** Upload a PNG to the media library; returns its id for `featured_media`, or null. */
    fun uploadMedia(context: Context, png: ByteArray, filename: String): Int? {
        val b = base(context) ?: return null
        val safe = filename.ifBlank { "gram" }.replace(Regex("[^A-Za-z0-9_-]"), "-").take(48).ifBlank { "gram" }
        val body = png.toRequestBody("image/png".toMediaType())
        return try {
            client.newCall(
                Request.Builder().url(b + "media").post(body)
                    .header("Authorization", auth(context))
                    .header("Content-Disposition", "attachment; filename=\"$safe.png\"")
                    .build()
            ).execute().use { r ->
                val o = try { JSONObject(r.body?.string() ?: return null) } catch (e: Exception) { return null }
                o.optInt("id", 0).takeIf { it > 0 }
            }
        } catch (e: Exception) {
            Timber.w(e, "wp uploadMedia failed"); null
        }
    }

    /** A media-library upload the caller wants the ADDRESS of, not just an id. */
    data class MediaUpload(val id: Int, val url: String)

    /**
     * Upload a PNG to the media library and keep WP's `source_url` — the asset-export path, where
     * the point is a URL you can paste into Canva/a post/anywhere. Same wire as [uploadMedia]
     * (raw POST /media, Content-Disposition filename, app-password Basic auth); null on failure.
     */
    fun uploadMediaAsset(context: Context, png: ByteArray, filename: String): MediaUpload? {
        val b = base(context) ?: return null
        val safe = filename.removeSuffix(".png").ifBlank { "gram" }
            .replace(Regex("[^A-Za-z0-9_-]"), "-").take(64).ifBlank { "gram" }
        val body = png.toRequestBody("image/png".toMediaType())
        return try {
            client.newCall(
                Request.Builder().url(b + "media").post(body)
                    .header("Authorization", auth(context))
                    .header("Content-Disposition", "attachment; filename=\"$safe.png\"")
                    .build()
            ).execute().use { r ->
                val o = try { JSONObject(r.body?.string() ?: return null) } catch (e: Exception) { return null }
                val id = o.optInt("id", 0)
                if (id <= 0) return null
                MediaUpload(id, o.optString("source_url", ""))
            }
        } catch (e: Exception) {
            Timber.w(e, "wp uploadMediaAsset failed"); null
        }
    }

    // MARK: - Lifecycle

    /** Create ([id] == null) or update ([id] != null) a post. */
    fun save(context: Context, draft: Draft, id: Int? = null): SaveResult {
        val b = base(context) ?: return SaveResult(false, null, null)
        val json = JSONObject()
            .put("title", draft.title)
            .put("content", draft.content)
            .put("status", draft.status)
            .put("categories", JSONArray(draft.categories))
            .put("tags", JSONArray(draft.tags))
        if (draft.featuredMedia > 0) json.put("featured_media", draft.featuredMedia)
        if (draft.status == "future" && !draft.dateIso.isNullOrBlank()) json.put("date", draft.dateIso)
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val path = if (id != null) "${draft.type}/$id" else draft.type
        return try {
            client.newCall(
                Request.Builder().url(b + path).post(body)
                    .header("Authorization", auth(context)).build()
            ).execute().use { r ->
                val text = r.body?.string() ?: ""
                val o = try { JSONObject(text) } catch (e: Exception) { null }
                SaveResult(
                    r.isSuccessful, o?.optInt("id", 0)?.takeIf { it > 0 }, o?.optString("link", null),
                    if (r.isSuccessful) null else wpErrorMessage(o),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "wp save failed"); SaveResult(false, null, null)
        }
    }

    /**
     * WP error bodies say exactly what went wrong (`{"code":…,"message":"You cannot edit this post
     * because it is in the Trash…"}`); parsing and discarding that left every failure looking like a
     * bad password. Pull the human sentence out so a toast can say the real reason. Null when the
     * body isn't a WP error shape.
     */
    fun wpErrorMessage(o: JSONObject?): String? =
        o?.optString("message", "")?.takeIf { it.isNotBlank() }
            // WP renders these with entities and the odd tag; flatten to a plain sentence.
            ?.let { decodeHtml(it).replace(Regex("<[^>]+>"), "").trim() }?.takeIf { it.isNotBlank() }

    /** Recent posts of a [type] (rest_base), filtered by [statuses]. Empty on any failure. */
    fun list(context: Context, type: String, statuses: List<String>, search: String = ""): List<WpPost> {
        var path = "$type?context=edit&per_page=30&orderby=date&order=desc"
        if (statuses.isNotEmpty()) path += "&status=${statuses.joinToString(",")}"
        if (search.isNotBlank()) {
            val q = java.net.URLEncoder.encode(search, "UTF-8")
            path += "&search=$q"
        }
        val resp = getResp(context, path) ?: return emptyList()
        return resp.use { r ->
            if (!r.isSuccessful) return emptyList()
            val arr = try { JSONArray(r.body?.string() ?: return emptyList()) } catch (e: Exception) { return emptyList() }
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parsePost(it, type) } }
        }
    }

    /** A single post by id (for opening the editor). Null on any failure. */
    fun getPost(context: Context, type: String, id: Int): WpPost? {
        val resp = getResp(context, "$type/$id?context=edit") ?: return null
        return resp.use { r ->
            if (!r.isSuccessful) return null
            val o = try { JSONObject(r.body?.string() ?: return null) } catch (e: Exception) { return null }
            parsePost(o, type)
        }
    }

    private fun parsePost(p: JSONObject, type: String): WpPost? {
        val id = p.optInt("id", 0)
        if (id <= 0) return null
        val title = decodeHtml(p.optJSONObject("title")?.optString("rendered", "") ?: "")
        val c = p.optJSONObject("content")
        val content = (c?.optString("raw", "") ?: "").ifBlank { c?.optString("rendered", "") ?: "" }
        fun ints(key: String): List<Int> {
            val a = p.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.optInt(it, 0) }.filter { it > 0 }
        }
        return WpPost(
            id, type, title,
            p.optString("status", ""), p.optString("date", ""), p.optString("link", ""),
            content, p.optInt("featured_media", 0), ints("categories"), ints("tags"),
        )
    }

    /** Trash a post (WordPress soft-deletes to the trash by default). Null on success; on refusal,
     *  WP's own message (falling back to a generic line) so the browser can show the real reason. */
    fun trash(context: Context, type: String, id: Int): String? {
        val b = base(context) ?: return "No active site configured"
        return try {
            client.newCall(
                Request.Builder().url(b + "$type/$id").delete()
                    .header("Authorization", auth(context)).build()
            ).execute().use { r ->
                if (r.isSuccessful) null
                else {
                    val o = try { JSONObject(r.body?.string() ?: "") } catch (e: Exception) { null }
                    wpErrorMessage(o) ?: "Couldn't trash (HTTP ${r.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "wp trash failed"); e.message ?: "Couldn't reach the site"
        }
    }

    /** A handful of the entities WP double-encodes in `title.rendered`, so titles read cleanly. */
    private fun decodeHtml(s: String): String {
        var out = s
        val map = listOf(
            "&amp;" to "&", "&#038;" to "&", "&#8217;" to "’", "&#8216;" to "‘",
            "&#8220;" to "“", "&#8221;" to "”", "&lt;" to "<", "&gt;" to ">",
            "&hellip;" to "…", "&#8211;" to "–", "&#8212;" to "—", "&quot;" to "\"",
            "&#039;" to "'", "&nbsp;" to " ",
        )
        for ((a, b) in map) out = out.replace(a, b)
        return out
    }

    /** Local ISO-8601 with offset, e.g. 2026-07-22T15:00:00-04:00 — what WP's `date` accepts. */
    fun isoLocal(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        return fmt.format(java.util.Date(millis))
    }

    /**
     * The other half of the [isoLocal] round trip: WP's `date` comes back site-local with NO offset
     * ("yyyy-MM-dd'T'HH:mm:ss"), and the schedule picker displays in the device zone — so parse it
     * as device-local. When the zones match (the normal case) an untouched post re-saves to the same
     * wall-clock time instead of silently re-timing to whatever the picker defaulted to.
     */
    fun parseWpDate(date: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.isLenient = false
        fmt.parse(date.take(19))?.time
    } catch (e: Exception) {
        null
    }
}
