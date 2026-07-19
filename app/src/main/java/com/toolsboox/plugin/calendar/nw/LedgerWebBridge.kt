package com.toolsboox.plugin.calendar.nw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Pushes a Boards card to the FluentBoards web board through the ledgr-fb-bridge WP plugin
 * (namespace ledgr/v1). The card's ink face (base64 `crop`) uploads as the task's cover image —
 * the web board shows the handwriting — and the text rides as the title. `note_uuid` = the
 * LedgerItem id, so re-pushing never duplicates (the bridge is idempotent on it). Mirrors iOS
 * `LedgerBridge` in LedgerItemsView.swift, including the To do/Doing/Done → remote-stage-by-position map.
 */
object LedgerWebBridge {

    @Volatile private var cachedPrefs: android.content.SharedPreferences? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class Config(val site: String, val user: String, val pass: String, val boardId: Int) {
        val ready: Boolean get() = site.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && boardId > 0
    }

    private fun prefs(context: Context): android.content.SharedPreferences = cachedPrefs ?: EncryptedSharedPreferences.create(
        context, "ledgr_bridge_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).also { cachedPrefs = it }

    fun config(context: Context): Config = try {
        val p = prefs(context)
        Config(
            p.getString("site", "") ?: "",
            p.getString("user", "") ?: "",
            p.getString("pass", "") ?: "",
            p.getInt("boardId", 0)
        )
    } catch (e: Exception) {
        Config("", "", "", 0)
    }

    fun saveConfig(context: Context, c: Config) {
        try {
            prefs(context).edit()
                .putString("site", c.site.trim().trimEnd('/'))
                .putString("user", c.user.trim())
                .putString("pass", c.pass.trim())
                .putInt("boardId", c.boardId)
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "bridge config save failed")
        }
    }

    private var cachedStages: Pair<Int, List<Long>>? = null

    /** Remote stage ids ordered by position (0 = To do, 1 = Doing, 2 = Done). */
    private fun stageIds(c: Config): List<Long> {
        cachedStages?.let { if (it.first == c.boardId) return it.second }
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/${c.boardId}/compact")
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val obj = JSONObject(resp.body?.string() ?: return emptyList())
                val stages = obj.optJSONArray("stages") ?: return emptyList()
                val list = (0 until stages.length())
                    .map { stages.getJSONObject(it) }
                    .sortedBy { it.optDouble("position", 0.0) }
                    .map { it.getLong("id") }
                cachedStages = c.boardId to list
                list
            }
        } catch (e: Exception) {
            Timber.w(e, "bridge stages fetch failed")
            emptyList()
        }
    }

    /** Push one card. Returns a short human status for a toast. Call from Dispatchers.IO. */
    fun pushCard(context: Context, item: LedgerItem): String {
        val c = config(context)
        if (!c.ready) return "Web bridge not configured"
        val stages = stageIds(c)
        if (stages.isEmpty()) return "Couldn't reach the web board"
        val col = if (item.done) 2 else if (item.stage == "doing") 1 else 0
        val stageId = stages[minOf(col, stages.size - 1)]

        val png: ByteArray? = inkPng(item) ?: renderTextCard(item.text)

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("board_id", c.boardId.toString())
            .addFormDataPart("stage_id", stageId.toString())
            .addFormDataPart("note_uuid", item.id)
            .apply {
                if (item.text.isNotBlank()) addFormDataPart("ocr_title", item.text)
                if (png != null) addFormDataPart("png", "card.png", png.toRequestBody("image/png".toMediaType()))
            }
            .build()

        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/card")
                .post(body)
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful && text.contains("task_id")) {
                    if (JSONObject(text).optBoolean("existing")) "Already on the web board" else "→ web board"
                } else {
                    val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Push failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "bridge push failed for ${item.id}")
            "Network error"
        }
    }

    /** The card's ink face: the base64 PNG in `crop` (an old OCR filename simply fails to decode). */
    private fun inkPng(item: LedgerItem): ByteArray? {
        val b64 = item.crop ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) bytes else null
        } catch (e: Exception) { null }
    }

    /** A clean rendered text card for cards with no ink, so the web cover still reads like Ledger. */
    private fun renderTextCard(text: String): ByteArray? {
        if (text.isBlank()) return null
        val w = 900; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
        var y = 96f
        for (line in wrap(text, paint, w - 96f)) {
            canvas.drawText(line, 48f, y, paint)
            y += 56f
            if (y > h - 24f) break
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) line = candidate
            else { if (line.isNotEmpty()) lines.add(line); line = word }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }
}

/**
 * Community half of the bridge (FluentCommunity on a possibly-different site, e.g. ashtanga.tech).
 * Grams post into a space you pick; the handwriting PNG IS the post. Idempotent on note_uuid.
 * Mirrors iOS `CommunityBridge`.
 */
object LedgerCommunityBridge {

    @Volatile private var cachedPrefs: android.content.SharedPreferences? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class Config(val site: String, val user: String, val pass: String) {
        val ready: Boolean get() = site.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    }

    data class Space(val id: Long, val title: String, val privacy: String)

    private fun prefs(context: Context): android.content.SharedPreferences = cachedPrefs ?: EncryptedSharedPreferences.create(
        context, "ledgr_bridge_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).also { cachedPrefs = it }

    fun config(context: Context): Config = try {
        val p = prefs(context)
        Config(p.getString("communitySite", "") ?: "", p.getString("communityUser", "") ?: "", p.getString("communityPass", "") ?: "")
    } catch (e: Exception) { Config("", "", "") }

    fun saveConfig(context: Context, c: Config) {
        try {
            prefs(context).edit()
                .putString("communitySite", c.site.trim().trimEnd('/'))
                .putString("communityUser", c.user.trim())
                .putString("communityPass", c.pass.trim())
                .apply()
        } catch (e: Exception) { Timber.w(e, "community config save failed") }
    }

    /** The site's spaces, or empty on any failure. Call from Dispatchers.IO. */
    fun spaces(context: Context): List<Space> {
        val c = config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/community/spaces")
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("spaces") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    Space(it.getLong("id"), it.optString("title", "Untitled"), it.optString("privacy", ""))
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "community spaces fetch failed")
            emptyList()
        }
    }

    /**
     * Post a gram (base64 PNG) into [spaceId]. Optional [provenance] renders a "↩ in reply to…"
     * block leading the gram (with [provUrl] as a source link) — the user edits it before sharing.
     * Returns a short toast status. Dispatchers.IO.
     */
    fun postGram(
        context: Context, pngBase64: String, title: String, noteUuid: String, spaceId: Long,
        provenance: String? = null, provUrl: String? = null,
    ): String {
        val c = config(context)
        if (!c.ready) return "Community bridge not configured"
        val png = try {
            android.util.Base64.decode(pngBase64, android.util.Base64.DEFAULT)
        } catch (e: Exception) { return "Bad image data" }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("space_id", spaceId.toString())
            .addFormDataPart("note_uuid", noteUuid)
            .apply {
                if (title.isNotBlank()) addFormDataPart("title", title)
                if (!provenance.isNullOrBlank()) addFormDataPart("provenance", provenance)
                if (!provUrl.isNullOrBlank()) addFormDataPart("prov_url", provUrl)
            }
            .addFormDataPart("png", "gram.png", png.toRequestBody("image/png".toMediaType()))
            .build()

        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/community/gram")
                .post(body)
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful && text.contains("feed_id")) {
                    if (JSONObject(text).optBoolean("existing")) "Already posted" else "Posted to the space"
                } else {
                    val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Post failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "community gram post failed")
            "Network error"
        }
    }
}

/** One reply in the correspondence inbox (community comment / board-card comment). */
data class LedgerReply(
    val id: String,
    val source: String,      // "community" | "boards"
    val thread: String,
    val threadId: Long,
    val author: String,
    val excerpt: String,
    val createdAt: String,
    val threadUrl: String = "",
)

/** One post ("cute card") in a community space — repliable in the Correspondence view. */
data class LedgerPost(
    val id: Long,
    val title: String,
    val excerpt: String,
    val author: String,
    val createdAt: String,
    val commentsCount: Int,
    val url: String,
    val reactionsCount: Int = 0,
    val liked: Boolean = false,
)

/** Correspondence fetch + ink reply — the Boox half of the Correspondence page. */
object LedgerCorrespondence {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Replies to your posts + cards, newest first. Call from Dispatchers.IO. */
    fun fetch(context: Context): List<LedgerReply> {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/correspondence?limit=100")
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("items") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull {
                    val excerpt = it.optString("excerpt", "")
                    if (excerpt.isBlank()) return@mapNotNull null
                    LedgerReply(
                        it.optString("id", ""), it.optString("source", "community"),
                        it.optString("thread", ""), it.optLong("thread_id", 0),
                        it.optString("author", "?"), excerpt, it.optString("created_at", ""),
                        it.optString("thread_url", "")
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "correspondence fetch failed")
            emptyList()
        }
    }

    /** A space's posts (the cute cards shared in), newest first. Call from Dispatchers.IO. */
    fun fetchSpaceFeed(context: Context, spaceId: Long, limit: Int = 50): List<LedgerPost> {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/community/feed?space=$spaceId&limit=$limit")
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("items") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    LedgerPost(
                        it.optLong("id", 0),
                        it.optString("title", "").takeIf { t -> t != "null" } ?: "",
                        it.optString("excerpt", ""),
                        it.optString("author", "?"),
                        it.optString("created_at", ""),
                        it.optInt("comments_count", 0),
                        it.optString("url", ""),
                        it.optInt("reactions_count", 0),
                        it.optBoolean("liked", false),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "space feed fetch failed")
            emptyList()
        }
    }

    /** Toggle a like on a community post. Returns (liked, count) or null on failure. Dispatchers.IO. */
    fun reactPost(context: Context, feedId: Long): Pair<Boolean, Int>? {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("feed_id", feedId.toString())
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/community/react")
                .post(body)
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body?.string() ?: return null)
                o.optBoolean("liked", false) to o.optInt("reactions_count", 0)
            }
        } catch (e: Exception) {
            Timber.w(e, "react failed")
            null
        }
    }

    /** Post handwriting as your comment on a community thread. Call from Dispatchers.IO. */
    fun postInkReply(context: Context, feedId: Long, png: ByteArray): String {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return "Community bridge not configured"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("feed_id", feedId.toString())
            .addFormDataPart("note_uuid", "inkreply-" + java.util.UUID.randomUUID().toString().lowercase())
            .addFormDataPart("png", "reply.png", png.toRequestBody("image/png".toMediaType()))
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/community/comment")
                .post(body)
                .header("Authorization", Credentials.basic(c.user, c.pass))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful && text.contains("comment_id")) "Reply posted"
                else {
                    val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Reply failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "ink reply failed")
            "Network error"
        }
    }
}

/** One board in the site-boards browser. */
data class SiteBoard(
    val id: Int,
    val title: String,
    val color: String?,
    val taskCount: Int,
    val stageCount: Int,
)

/** One column of a board. */
data class SiteStage(val id: Long, val title: String, val position: Double)

/** One card on a board (the compact form the browser lays out). */
data class SiteTask(
    val id: Long,
    val title: String,
    val stageId: Long,
    val position: Double,
    val priority: String?,
    val status: String?,
    val dueAt: String?,
    val coverUrl: String?,
    val isLedgr: Boolean,
)

/** A whole board: its columns and its cards. */
data class SiteBoardCompact(val stages: List<SiteStage>, val tasks: List<SiteTask>)

/** A person on a card. */
data class SiteAssignee(val id: Int, val name: String, val email: String, val avatar: String?)

/** A label on a card. */
data class SiteLabel(val id: Long, val title: String, val color: String?)

/** A comment on a card. */
data class SiteComment(val id: Long, val author: String, val excerpt: String, val createdAt: String)

/** The full task behind a card — the deep-detail view. */
data class SiteTaskDetail(
    val id: Long,
    val boardId: Int,
    val title: String,
    val description: String,
    val stageId: Long,
    val priority: String?,
    val status: String?,
    val dueAt: String?,
    val coverUrl: String?,
    val isLedgr: Boolean,
    val assignees: List<SiteAssignee>,
    val labels: List<SiteLabel>,
    val comments: List<SiteComment>,
    val crmName: String?,
    val crmEmail: String?,
)

/**
 * The site-boards browser client: reads every FluentBoards board on the site, renders one as
 * tactile columns, and writes card moves + comments back through the ledgr-fb-bridge plugin.
 * Reuses [LedgerWebBridge]'s stored site/user/pass creds (the same "Community & Boards" settings).
 * All calls run on Dispatchers.IO and swallow failures to an empty/null result, e-ink-quietly.
 */
object LedgerBoards {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun auth(c: LedgerWebBridge.Config) = Credentials.basic(c.user, c.pass)

    /** Every board the signed-in user can see, alphabetical. Empty on any failure. */
    fun boards(context: Context): List<SiteBoard> {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank() || c.user.isBlank() || c.pass.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/boards")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("boards") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    SiteBoard(
                        it.optInt("id", 0),
                        it.optString("title", "Untitled"),
                        it.optString("color", "").takeIf { s -> s.isNotBlank() && s != "null" },
                        it.optInt("task_count", 0),
                        it.optInt("stage_count", 0),
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "boards list fetch failed")
            emptyList()
        }
    }

    /** A board's columns and cards. Null on any failure. */
    fun compactBoard(context: Context, boardId: Int): SiteBoardCompact? {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/compact")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string() ?: return null)
                val stagesArr = obj.optJSONArray("stages") ?: return null
                val stages = (0 until stagesArr.length()).map { stagesArr.getJSONObject(it) }.map {
                    SiteStage(it.getLong("id"), it.optString("title", ""), it.optDouble("position", 0.0))
                }.sortedBy { it.position }
                val tasksArr = obj.optJSONArray("tasks")
                val tasks = if (tasksArr == null) emptyList() else
                    (0 until tasksArr.length()).map { tasksArr.getJSONObject(it) }.map {
                        SiteTask(
                            it.getLong("id"),
                            it.optString("title", ""),
                            it.optLong("stage_id", 0),
                            it.optDouble("position", 0.0),
                            it.optString("priority", "").takeIf { s -> s.isNotBlank() && s != "null" },
                            it.optString("status", "").takeIf { s -> s.isNotBlank() && s != "null" },
                            it.optString("due_at", "").takeIf { s -> s.isNotBlank() && s != "null" },
                            it.optString("cover_url", "").takeIf { s -> s.isNotBlank() && s != "null" },
                            it.optBoolean("is_ledgr", false),
                        )
                    }.sortedBy { it.position }
                SiteBoardCompact(stages, tasks)
            }
        } catch (e: Exception) {
            Timber.w(e, "compact board fetch failed")
            null
        }
    }

    /** Move a card to a stage (append to the end). Returns a short toast status. */
    fun moveTask(context: Context, boardId: Int, taskId: Long, newStageId: Long): String {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return "Bridge not configured"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("new_stage_id", newStageId.toString())
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/task/$taskId/move")
                .post(body)
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful && text.contains("stage_id")) "Moved"
                else {
                    val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Move failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "task move failed")
            "Network error"
        }
    }

    /** The full task behind a card. Null on any failure. */
    fun taskDetail(context: Context, boardId: Int, taskId: Long): SiteTaskDetail? {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/task/$taskId")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body?.string() ?: return null)
                val assignees = o.optJSONArray("assignees").let { arr ->
                    if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                        SiteAssignee(it.optInt("id", 0), it.optString("name", "?"), it.optString("email", ""),
                            it.optString("avatar", "").takeIf { s -> s.isNotBlank() && s != "null" })
                    }
                }
                val labels = o.optJSONArray("labels").let { arr ->
                    if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                        SiteLabel(it.optLong("id", 0), it.optString("title", ""),
                            it.optString("color", "").takeIf { s -> s.isNotBlank() && s != "null" })
                    }
                }
                val comments = o.optJSONArray("comments").let { arr ->
                    if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                        SiteComment(it.optLong("id", 0), it.optString("author", "?"),
                            it.optString("excerpt", ""), it.optString("created_at", ""))
                    }
                }
                val crm = o.optJSONObject("crm")
                SiteTaskDetail(
                    o.optLong("id", 0), o.optInt("board_id", boardId),
                    o.optString("title", ""), o.optString("description", ""),
                    o.optLong("stage_id", 0),
                    o.optString("priority", "").takeIf { s -> s.isNotBlank() && s != "null" },
                    o.optString("status", "").takeIf { s -> s.isNotBlank() && s != "null" },
                    o.optString("due_at", "").takeIf { s -> s.isNotBlank() && s != "null" },
                    o.optString("cover_url", "").takeIf { s -> s.isNotBlank() && s != "null" },
                    o.optBoolean("is_ledgr", false),
                    assignees, labels, comments,
                    crm?.optString("name", "")?.takeIf { it.isNotBlank() },
                    crm?.optString("email", "")?.takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "task detail fetch failed")
            null
        }
    }

    /**
     * Land replies on your shared items as cards on [boardId]'s first column — the Correspondence
     * Inbox. Idempotent server-side, so re-running only adds the new ones. Returns (created, skipped)
     * or null on failure.
     */
    fun syncInbox(context: Context, boardId: Int): Pair<Int, Int>? {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return null
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("board_id", boardId.toString())
            .addFormDataPart("limit", "50")
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/correspondence/to-board")
                .post(body)
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body?.string() ?: return null)
                o.optInt("created", 0) to o.optInt("skipped", 0)
            }
        } catch (e: Exception) {
            Timber.w(e, "inbox sync failed")
            null
        }
    }

    /** The board's people — the roster a card can be assigned to. Empty on failure. */
    fun members(context: Context, boardId: Int): List<SiteAssignee> {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/members")
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONObject(resp.body?.string() ?: return emptyList()).optJSONArray("members") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    SiteAssignee(it.optInt("id", 0), it.optString("name", "?"), it.optString("email", ""),
                        it.optString("avatar", "").takeIf { s -> s.isNotBlank() && s != "null" })
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "board members fetch failed")
            emptyList()
        }
    }

    /**
     * Edit a card: any of assignees (full desired roster, csv of ids), dueAt (yyyy-MM-dd or "" to
     * clear), priority (low|medium|high|normal). Pass null to leave a field unchanged. Toast status.
     */
    fun updateTask(
        context: Context, boardId: Int, taskId: Long,
        assignees: List<Int>? = null, dueAt: String? = null, priority: String? = null,
    ): String {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return "Bridge not configured"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply {
                if (assignees != null) addFormDataPart("assignees", assignees.joinToString(","))
                if (dueAt != null) addFormDataPart("due_at", dueAt)
                if (priority != null) addFormDataPart("priority", priority)
            }
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/task/$taskId/update")
                .post(body)
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                val t = resp.body?.string() ?: ""
                if (resp.isSuccessful && t.contains("task_id")) "Saved"
                else {
                    val msg = try { JSONObject(t).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Save failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "task update failed")
            "Network error"
        }
    }

    /** Move a card to a stage at a precise 1-based index (0 = append). Returns toast status. */
    fun moveTaskAt(context: Context, boardId: Int, taskId: Long, newStageId: Long, newIndex: Int): String {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return "Bridge not configured"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("new_stage_id", newStageId.toString())
            .addFormDataPart("new_index", newIndex.toString())
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/task/$taskId/move")
                .post(body)
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.isSuccessful && text.contains("stage_id")) "Moved"
                else {
                    val msg = try { JSONObject(text).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Move failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "task move failed")
            "Network error"
        }
    }

    /** Add a comment to a card — typed text and/or a handwriting PNG. Returns toast status. */
    fun commentTask(context: Context, boardId: Int, taskId: Long, text: String?, png: ByteArray?): String {
        val c = LedgerWebBridge.config(context)
        if (c.site.isBlank()) return "Bridge not configured"
        if (text.isNullOrBlank() && png == null) return "Nothing to send"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply {
                if (!text.isNullOrBlank()) addFormDataPart("text", text)
                if (png != null) addFormDataPart("png", "reply.png", png.toRequestBody("image/png".toMediaType()))
            }
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/board/$boardId/task/$taskId/comment")
                .post(body)
                .header("Authorization", auth(c))
                .build()
            client.newCall(req).execute().use { resp ->
                val t = resp.body?.string() ?: ""
                if (resp.isSuccessful && t.contains("comment_id")) "Reply posted"
                else {
                    val msg = try { JSONObject(t).optString("message") } catch (e: Exception) { "" }
                    if (msg.isNotBlank()) msg else "Reply failed (${resp.code})"
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "task comment failed")
            "Network error"
        }
    }
}

/** One chat thread — a group (space) chat or a 1:1 DM. */
data class ChatThread(
    val id: Long,
    val title: String,
    val isGroup: Boolean,
    val messageCount: Int,
    val updatedAt: String,
)

/** One chat message. [text] is the plain body; [imageUrl] is a handwriting/media image if any. */
data class ChatMessage(
    val id: Long,
    val author: String,
    val text: String,
    val createdAt: String,
    val mine: Boolean,
    val imageUrl: String? = null,
)

/**
 * The Ledger's window into FluentCommunity messaging (the live `fluent-messaging` plugin) — group
 * space chats + 1:1 DMs. Talks straight to the `fluent-community/v2/chat` REST routes with the same
 * community bridge creds (PortalPolicy just needs a logged-in member). Text is a pure passthrough;
 * polling `/new` keeps a thread fresh without sockets (ideal for e-ink). All calls Dispatchers.IO.
 */
object LedgerChat {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private const val JSON = "application/json; charset=utf-8"
    private fun base(c: LedgerCommunityBridge.Config) = "${c.site}/wp-json/fluent-community/v2/chat"
    private fun auth(c: LedgerCommunityBridge.Config) = Credentials.basic(c.user, c.pass)

    /** Strip the server's chat HTML to plain text for e-ink rendering. */
    private fun plain(html: String): String =
        html.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&#039;", "'").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ").trim()

    /** Group (space) chats first, then DMs. Empty on failure. */
    fun threads(context: Context): List<ChatThread> {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder().url("${base(c)}/threads")
                .header("Authorization", auth(c)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val o = JSONObject(resp.body?.string() ?: return emptyList())
                fun parse(key: String, group: Boolean): List<ChatThread> {
                    val arr = o.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                        ChatThread(
                            it.optLong("id", 0),
                            it.optString("title", "Chat").takeIf { t -> t.isNotBlank() && t != "null" } ?: "Chat",
                            group,
                            it.optString("message_count", "0").toIntOrNull() ?: 0,
                            it.optString("updated_at", ""),
                        )
                    }
                }
                parse("community_threads", true) + parse("threads", false)
            }
        } catch (e: Exception) {
            Timber.w(e, "chat threads fetch failed")
            emptyList()
        }
    }

    private fun parseMessages(o: JSONObject, myUsername: String): List<ChatMessage> {
        // Messages come back paginated under messages.data (newest-first page); return oldest-first.
        val data = o.optJSONObject("messages")?.optJSONArray("data")
            ?: o.optJSONArray("messages") ?: o.optJSONArray("data") ?: return emptyList()
        val imgRe = Regex("<img[^>]+src=\"([^\"]+)\"")
        val list = (0 until data.length()).map { data.getJSONObject(it) }.map {
            val xp = it.optJSONObject("xprofile")
            val raw = it.optString("text", "")
            ChatMessage(
                it.optLong("id", 0),
                xp?.optString("display_name", "?") ?: "?",
                plain(raw),
                it.optString("created_at", ""),
                xp?.optString("username", "") == myUsername,
                imgRe.find(raw)?.groupValues?.get(1),
            )
        }
        return list.sortedBy { it.id }
    }

    /** A thread's recent messages, oldest-first. Your own are flagged by [myUsername]. Empty on failure. */
    fun messages(context: Context, threadId: Long): List<ChatMessage> {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder().url("${base(c)}/messages/$threadId")
                .header("Authorization", auth(c)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                parseMessages(JSONObject(resp.body?.string() ?: return emptyList()), c.user)
            }
        } catch (e: Exception) {
            Timber.w(e, "chat messages fetch failed")
            emptyList()
        }
    }

    /** Messages newer than [lastId] (polling). Empty when nothing new or on failure. */
    fun newMessages(context: Context, threadId: Long, lastId: Long): List<ChatMessage> {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return emptyList()
        return try {
            val req = Request.Builder().url("${base(c)}/messages/$threadId/new?last_message_id=$lastId")
                .header("Authorization", auth(c)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                parseMessages(JSONObject(resp.body?.string() ?: return emptyList()), c.user)
            }
        } catch (e: Exception) {
            Timber.w(e, "chat poll failed")
            emptyList()
        }
    }

    /** Send a text message. Returns true on success. */
    fun send(context: Context, threadId: Long, text: String): Boolean {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready || text.isBlank()) return false
        return try {
            val payload = JSONObject().put("text", text).toString()
            val req = Request.Builder().url("${base(c)}/messages/$threadId")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .header("Authorization", auth(c)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.w(e, "chat send failed")
            false
        }
    }

    /**
     * Send a handwritten message (+ optional text) via the bridge — it sideloads the PNG and posts
     * it as a chat message, since the chat's own send wants a pre-registered media URL. True on OK.
     */
    fun sendInk(context: Context, threadId: Long, text: String?, png: ByteArray): Boolean {
        val c = LedgerCommunityBridge.config(context)
        if (!c.ready) return false
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("png", "ink.png", png.toRequestBody("image/png".toMediaType()))
            .apply { if (!text.isNullOrBlank()) addFormDataPart("text", text) }
            .build()
        return try {
            val req = Request.Builder()
                .url("${c.site}/wp-json/ledgr/v1/chat/$threadId/ink")
                .post(body)
                .header("Authorization", auth(c)).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Timber.w(e, "chat ink send failed")
            false
        }
    }

    /** Fetch a chat image to a bitmap (for rendering inbound handwriting). Null on failure. */
    fun loadImage(context: Context, url: String): android.graphics.Bitmap? {
        val c = LedgerCommunityBridge.config(context)
        return try {
            val req = Request.Builder().url(url)
                .apply { if (c.ready) header("Authorization", auth(c)) }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Timber.w(e, "chat image load failed")
            null
        }
    }
}

/** Write → Share: POST /ledgr/v1/essay (dest=draft|email). The handwriting IS the essay. */
object LedgerEssay {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
    }

    fun send(
        site: String, user: String, pass: String,
        dest: String, title: String, tags: String, text: String, to: String?, png: ByteArray?
    ): String {
        if (site.isBlank() || user.isBlank() || pass.isBlank()) return "Bridge not configured"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("dest", dest)
            .addFormDataPart("title", title)
            .apply {
                if (tags.isNotBlank()) addFormDataPart("tags", tags)
                if (text.isNotBlank()) addFormDataPart("text", text)
                if (!to.isNullOrBlank()) addFormDataPart("to", to)
                if (png != null) addFormDataPart("png", "essay.png", png.toRequestBody("image/png".toMediaType()))
            }.build()
        return try {
            val req = Request.Builder()
                .url("${site.trimEnd('/')}/wp-json/ledgr/v1/essay").post(body)
                .header("Authorization", Credentials.basic(user, pass)).build()
            client.newCall(req).execute().use { resp ->
                val t = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful && t.contains("post_id") -> "Draft created"
                    resp.isSuccessful && t.contains("\"sent\":true") -> "Emailed"
                    else -> {
                        val msg = try { JSONObject(t).optString("message") } catch (e: Exception) { "" }
                        if (msg.isNotBlank()) msg else "Failed (${resp.code})"
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "essay send failed"); "Network error"
        }
    }
}
