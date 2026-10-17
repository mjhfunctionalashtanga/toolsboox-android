package com.toolsboox.plugin.reader.ui

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Audiobookshelf as a Library source — Michael's own server, the one Readest also speaks to
 * ("I don't see audiobookshelf from readest built into bookshelf", 09-10).
 *
 * The shape is the OPDS catalog's ([OpdsCatalog]): a config in encrypted prefs that ALSO rides the
 * settings carry so one sign-in reaches the whole fleet; a browser that is a menu of menus
 * (libraries → items → one item); and a hand-off to the one player. What is new is what the
 * server is: audiobooks STREAM (never downloaded — Readest's rule too), the playhead is the
 * server's playback session (`/api/items/:id/play` → `/api/session/:id/sync`) AND the fleet's own
 * `listen-state.json`, so ▶ Continue and the ABS web app agree on where you are. Ebook-only items
 * download onto the shelf like a catalog pull. The API surface mirrors readest's
 * `services/audiobookshelf/client.ts`: login/refresh, libraries, paged items, expanded item,
 * /api/me progress, play/sync/close, cover.
 */
object Audiobookshelf {

    private const val PREFS = "ledger_abs_encrypted_prefs"
    private const val CARRY = "abs"
    const val PAGE = 100

    data class Config(val url: String, val user: String, val pass: String, val token: String)
    data class Library(val id: String, val name: String, val mediaType: String)   // "book" | "podcast"
    data class Track(val index: Int, val startOffset: Double, val duration: Double, val contentUrl: String, val title: String)
    data class Chapter(val start: Double, val end: Double, val title: String)
    data class Episode(val id: String, val title: String, val publishedAt: Long, val duration: Double)
    data class Progress(val itemId: String, val episodeId: String?, val currentTime: Double, val duration: Double, val isFinished: Boolean, val lastUpdate: Long)
    data class Session(val id: String, val currentTime: Double, val tracks: List<Track>)

    /** A library item, minified or expanded — the expanded fields are empty until [item] fills them. */
    data class Item(
        val id: String, val mediaType: String, val title: String, val author: String, val narrator: String,
        val duration: Double, val hasAudio: Boolean, val ebookFormat: String, val numEpisodes: Int,
        val tracks: List<Track> = emptyList(), val chapters: List<Chapter> = emptyList(),
        val episodes: List<Episode> = emptyList(), val ebookIno: String = "",
    ) {
        val isPodcast get() = mediaType == "podcast"
        val hasEbook get() = ebookFormat.isNotBlank() || ebookIno.isNotBlank()
    }

    class AuthException(msg: String) : Exception(msg)

    // ── Config ────────────────────────────────────────────────────────────────────────────────

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    @Volatile private var prefsCache: android.content.SharedPreferences? = null
    private fun prefs(context: Context) = prefsCache ?: androidx.security.crypto.EncryptedSharedPreferences.create(
        context.applicationContext, PREFS,
        androidx.security.crypto.MasterKey.Builder(context.applicationContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    ).also { prefsCache = it }

    /** The server, or null when none is set up. Local values first, the carried section as the fallback. */
    fun config(context: Context): Config? = runCatching {
        val p = prefs(context)
        val carried = com.toolsboox.plugin.calendar.ot.SettingsBackup.carriedSection(context, CARRY)
        val url = p.getString("abs_url", "")?.trim().orEmpty().ifBlank { carried?.optString("url").orEmpty().trim() }
        if (url.isBlank()) return@runCatching null
        Config(
            OpdsCatalog.withScheme(url).trimEnd('/'),
            p.getString("abs_user", "").orEmpty().ifBlank { carried?.optString("user").orEmpty() },
            p.getString("abs_pass", "").orEmpty().ifBlank { carried?.optString("pass").orEmpty() },
            p.getString("abs_token", "").orEmpty().ifBlank { carried?.optString("token").orEmpty() },
        )
    }.getOrNull()

    fun save(context: Context, url: String, user: String, pass: String, token: String = config(context)?.token.orEmpty()) {
        runCatching {
            prefs(context).edit()
                .putString("abs_url", OpdsCatalog.withScheme(url).trimEnd('/'))
                .putString("abs_user", user.trim()).putString("abs_pass", pass).putString("abs_token", token)
                .apply()
        }
        runCatching {
            com.toolsboox.plugin.calendar.ot.SettingsBackup.putCarriedSection(
                context, CARRY,
                JSONObject().put("url", url.trim()).put("user", user.trim()).put("pass", pass).put("token", token)
            )
        }
    }

    fun forget(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
        runCatching { com.toolsboox.plugin.calendar.ot.SettingsBackup.putCarriedSection(context, CARRY, JSONObject()) }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Is there an Audiobookshelf at [base]? `GET /status` answers `{"isInit":…,"language":…}` on
     * every version. A web page (a proxy's login, a Cloudflare challenge, the wrong host) or a
     * connection failure is named in plain words — the field report was a JSON exception.
     */
    private fun probe(base: String): String? = runCatching {
        http.newCall(Request.Builder().url("$base/status").get().build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.isSuccessful && runCatching { JSONObject(text).has("isInit") }.getOrDefault(false)) null
            else if (text.trimStart().startsWith("<")) "That address answers with a web page, not Audiobookshelf — check the host and port (Audiobookshelf's own port is 13378)."
            else "That address answered ${res.code}, not like Audiobookshelf."
        }
    }.getOrElse { e -> "Couldn't reach that address (${e.message ?: e.javaClass.simpleName})." }

    /** POST /login → the access token (ABS ≥ 2.26 short-lived JWT, or the legacy long-lived one). */
    fun login(context: Context, cfg: Config = config(context) ?: throw AuthException("Add your Audiobookshelf server first.")): Config {
        // Find the server first. A bare host got https:// by default; a home server is usually
        // plain http on its port, so when https fails the same address is tried over http and the
        // working scheme is kept.
        var base = cfg.url
        var why = probe(base)
        if (why != null && base.startsWith("https://") && !why.startsWith("That address answered")) {
            val alt = "http://" + base.removePrefix("https://")
            if (probe(alt) == null) { base = alt; why = null; save(context, base, cfg.user, cfg.pass, cfg.token) }
        }
        if (why != null) throw Exception(why)
        val body = JSONObject().put("username", cfg.user).put("password", cfg.pass).toString()
        val req = Request.Builder().url(base + "/login")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull())).build()
        http.newCall(req).execute().use { res ->
            if (res.code == 401 || res.code == 403) throw AuthException("Audiobookshelf didn't accept that username and password.")
            if (!res.isSuccessful) throw Exception("Audiobookshelf answered ${res.code}.")
            val o = parseJson(res.body?.string().orEmpty())
            val user = o.optJSONObject("user") ?: throw Exception("No user in the sign-in answer.")
            val token = user.optString("accessToken").ifBlank { user.optString("token") }
            if (token.isBlank()) throw Exception("No token in the sign-in answer.")
            save(context, base, cfg.user, cfg.pass, token)
            return cfg.copy(url = base, token = token)
        }
    }

    /** An authenticated call; one re-login on 401 (the JWT lapsed). */
    private fun call(context: Context, path: String, method: String = "GET", json: JSONObject? = null): String {
        var cfg = config(context) ?: throw AuthException("Add your Audiobookshelf server first.")
        if (cfg.token.isBlank()) cfg = login(context, cfg)
        fun go(c: Config): okhttp3.Response {
            val b = Request.Builder().url(c.url + path).header("Authorization", "Bearer " + c.token)
            if (method == "GET") b.get()
            else b.method(method, (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaTypeOrNull()))
            return http.newCall(b.build()).execute()
        }
        var res = go(cfg)
        if (res.code == 401) { res.close(); cfg = login(context, cfg); res = go(cfg) }
        res.use {
            if (!it.isSuccessful) throw Exception("Audiobookshelf answered ${it.code} for $path.")
            return it.body?.string().orEmpty()
        }
    }

    /** The server's answer as JSON — or a plain sentence when it wasn't one (a login page, a proxy). */
    private fun parseJson(text: String): JSONObject = runCatching { JSONObject(text) }
        .getOrElse {
            Timber.w("abs: non-JSON answer: ${text.take(160).replace('\n', ' ')}")
            throw Exception(if (text.trimStart().startsWith("<")) "That address answers with a web page, not Audiobookshelf — check the host and port (Audiobookshelf's own port is 13378)."
                            else "That address didn't answer like an Audiobookshelf server.")
        }

    fun libraries(context: Context): List<Library> {
        val arr = parseJson(call(context, "/api/libraries")).optJSONArray("libraries") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Library(o.optString("id"), o.optString("name"), o.optString("mediaType", "book"))
        }.filter { it.mediaType == "book" || it.mediaType == "podcast" }
    }

    /** One page of a library, minified. */
    fun items(context: Context, libraryId: String, page: Int): Pair<List<Item>, Int> {
        val o = parseJson(call(context, "/api/libraries/$libraryId/items?limit=$PAGE&page=$page&sort=media.metadata.title"))
        val arr = o.optJSONArray("results") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseItem) } to o.optInt("total", 0)
    }

    /** GET /api/libraries/:id/search?q= → the server's matches (books and podcasts) across the whole library. */
    fun search(context: Context, libraryId: String, q: String): List<Item> {
        val o = parseJson(call(context, "/api/libraries/$libraryId/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=40"))
        val out = ArrayList<Item>()
        for (key in listOf("book", "podcast")) {
            val arr = o.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optJSONObject("libraryItem")?.let { out.add(parseItem(it)) }
        }
        return out
    }

    /** The whole item: tracks, chapters, episodes, the ebook file. */
    fun item(context: Context, id: String): Item = parseItem(parseJson(call(context, "/api/items/$id?expanded=1")))

    private fun parseItem(o: JSONObject): Item {
        val media = o.optJSONObject("media") ?: JSONObject()
        val meta = media.optJSONObject("metadata") ?: JSONObject()
        val mediaType = o.optString("mediaType", "book")
        val narrators = meta.optJSONArray("narrators")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val tracks = media.optJSONArray("tracks")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parseTrack) } } ?: emptyList()
        val chapters = media.optJSONArray("chapters")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> Chapter(c.optDouble("start", 0.0), c.optDouble("end", 0.0), c.optString("title")) } }
        } ?: emptyList()
        val episodes = media.optJSONArray("episodes")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { e ->
                    Episode(e.optString("id"), e.optString("title"), e.optLong("publishedAt", 0L),
                        e.optJSONObject("audioTrack")?.optDouble("duration", 0.0) ?: e.optDouble("duration", 0.0))
                }
            }?.sortedByDescending { it.publishedAt }
        } ?: emptyList()
        return Item(
            id = o.optString("id"), mediaType = mediaType,
            title = meta.optString("title").ifBlank { "(untitled)" },
            author = meta.optString("authorName").ifBlank { meta.optString("author") },
            narrator = meta.optString("narratorName").ifBlank { narrators?.joinToString(", ").orEmpty() },
            duration = media.optDouble("duration", 0.0),
            hasAudio = media.optInt("numAudioFiles", 0) > 0 || media.optInt("numTracks", 0) > 0 || tracks.isNotEmpty(),
            ebookFormat = media.optString("ebookFormat").ifBlank { media.optJSONObject("ebookFile")?.optString("ebookFormat").orEmpty() },
            numEpisodes = media.optInt("numEpisodes", episodes.size),
            tracks = tracks, chapters = chapters, episodes = episodes,
            ebookIno = media.optJSONObject("ebookFile")?.optString("ino").orEmpty(),
        )
    }

    private fun parseTrack(t: JSONObject) = Track(
        t.optInt("index", 0), t.optDouble("startOffset", 0.0), t.optDouble("duration", 0.0),
        t.optString("contentUrl"), t.optString("title").ifBlank { t.optJSONObject("metadata")?.optString("filename").orEmpty() }
    )

    @Volatile private var progressCache: Pair<Long, Map<String, Progress>>? = null
    /** GET /api/me → mediaProgress, keyed "itemId" or "itemId/episodeId"; memoised for a minute. */
    fun progress(context: Context): Map<String, Progress> {
        progressCache?.let { (at, m) -> if (System.currentTimeMillis() - at < 60_000L) return m }
        val arr = parseJson(call(context, "/api/me")).optJSONArray("mediaProgress") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ep = o.optString("episodeId").ifBlank { null }
            Progress(o.optString("libraryItemId"), ep, o.optDouble("currentTime", 0.0), o.optDouble("duration", 0.0),
                o.optBoolean("isFinished", false), o.optLong("lastUpdate", 0L))
        }.associateBy { if (it.episodeId == null) it.itemId else it.itemId + "/" + it.episodeId }
            .also { progressCache = System.currentTimeMillis() to it }
    }

    /** A path the server answers WITHOUT a token in it — what the synced files remember. */
    fun coverPath(itemId: String) = "/api/items/$itemId/cover"
    /** Resolve a remembered path (or a full URL) to something MediaPlayer can open now. */
    fun resolve(context: Context, pathOrUrl: String): String =
        if (pathOrUrl.startsWith("/")) streamUrl(context, pathOrUrl) else pathOrUrl

    /**
     * A picture address made fetchable now: a remembered path gains the server and token (as
     * [resolve]), and so does a FULL address on this same server whose path is `/api/items/…`
     * with no token — the form the iPhone writes into the synced ListenState (review 10-12: the
     * B6 fetched those bare and drew nothing). Anything else is returned untouched. May read the
     * encrypted config — call off the main thread.
     */
    fun authorizePicture(context: Context, keyOrUrl: String): String {
        if (keyOrUrl.startsWith("/")) return resolve(context, keyOrUrl)
        if (!keyOrUrl.contains("/api/items/")) return keyOrUrl
        val cfg = config(context) ?: return keyOrUrl
        val path = sameServerPath(keyOrUrl, cfg.url) ?: return keyOrUrl
        return streamUrl(context, path)
    }

    /** [url]'s path when it is an unsigned `/api/items/…` address on the server at [base]
     *  (scheme- and host-case-blind — the iPhone may know the server as http where this device
     *  knows https); null otherwise. Pure. */
    fun sameServerPath(url: String, base: String): String? {
        if (url.contains("token=")) return null
        val u = url.substringAfter("://", "")
        val b = base.substringAfter("://", "").trimEnd('/')
        if (u.isEmpty() || b.isEmpty() || u.length <= b.length) return null
        if (!u.regionMatches(0, b, 0, b.length, ignoreCase = true)) return null
        val path = u.substring(b.length)
        return path.takeIf { it.startsWith("/api/items/") }
    }

    /** POST /api/items/:id/play[/:episodeId] → the session (server-side resume point + tracks). */
    fun openSession(context: Context, itemId: String, episodeId: String?): Session {
        val path = if (episodeId.isNullOrBlank()) "/api/items/$itemId/play" else "/api/items/$itemId/play/$episodeId"
        val body = JSONObject()
            .put("deviceInfo", JSONObject().put("clientName", "Ledgable").put("deviceId", LibraryHub.deviceName(context)))
            .put("supportedMimeTypes", JSONArray(listOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/x-m4b")))
            .put("mediaPlayer", "android")
        val o = parseJson(call(context, path, "POST", body))
        val tracks = o.optJSONArray("audioTracks")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::parseTrack) } } ?: emptyList()
        return Session(o.optString("id"), o.optDouble("currentTime", 0.0), tracks)
    }

    fun syncSession(context: Context, sessionId: String, currentTime: Double, timeListened: Double, duration: Double, close: Boolean) {
        val body = JSONObject().put("currentTime", currentTime).put("timeListened", timeListened).put("duration", duration)
        call(context, "/api/session/$sessionId/" + (if (close) "close" else "sync"), "POST", body)
    }

    /** A streamable address for a track — the token rides as a query parameter, MediaPlayer sets no headers. */
    fun streamUrl(context: Context, contentUrl: String): String {
        val cfg = config(context) ?: return contentUrl
        val sep = if (contentUrl.contains('?')) '&' else '?'
        return cfg.url + contentUrl + sep + "token=" + java.net.URLEncoder.encode(cfg.token, "UTF-8")
    }

    fun coverUrl(context: Context, itemId: String): String? {
        val cfg = config(context) ?: return null
        return cfg.url + "/api/items/$itemId/cover?token=" + java.net.URLEncoder.encode(cfg.token, "UTF-8")
    }

    /** Download the item's ebook file onto the shelf; returns the filename written, or null. */
    fun downloadEbook(context: Context, item: Item): String? {
        val cfg = config(context) ?: return null
        val ext = item.ebookFormat.ifBlank { "epub" }.lowercase()
        val stem = (if (item.author.isBlank()) item.title else "${item.author} - ${item.title}")
            .replace(Regex("""[/\\:*?"<>|]"""), "-").replace(Regex("""\s+"""), " ").trim().take(120)
        val name = "$stem.$ext"
        val path = if (item.ebookIno.isNotBlank()) "/api/items/${item.id}/ebook/${item.ebookIno}" else "/api/items/${item.id}/ebook"
        val req = Request.Builder().url(cfg.url + path).header("Authorization", "Bearer " + cfg.token).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) { Timber.w("abs ebook ${res.code}"); return null }
            val input = res.body?.byteStream() ?: return null
            val ok = BookshelfSource.writeInto(context, name) { out -> input.copyTo(out) }
            return if (ok) name else null
        }
    }

    fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return if (s >= 3600) "%dh %02dm".format(s / 3600, (s % 3600) / 60) else "%dm".format(s / 60)
    }
}

/**
 * The hand-off to the one player, and the session the server keeps while it plays.
 *
 * One TRACK at a time (a book is often several files): the fleet-wide listen id is
 * `abs:<item>:<track>` so `listen-state.json` resumes inside the track like any episode, the
 * player's up-next runs the next file when one ends, and the ABS session hears the GLOBAL
 * position (track offset + playhead) every heartbeat — so the web app, Readest and this shelf all
 * agree. The server's resume point is written into listen-state BEFORE play when it is newer than
 * the fleet's, which is how a book started on the phone's ABS app picks up here without a seek.
 */
object AbsPlayback {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The player is main-thread-only (its listeners summon the transport card) — audit 09-11. */
    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    private var sessionId: String? = null
    private var itemId: String? = null
    private var lastGlobal: Double = 0.0
    private var lastSyncAt: Long = 0L
    // What is on the player now, so read-along can ask WHERE on the book's global timeline the
    // playhead stands and move it there — across files (a book is often several).
    private var currentItem: Audiobookshelf.Item? = null
    private var currentEpisodeId: String? = null
    private var currentTracks: List<Audiobookshelf.Track> = emptyList()

    /** The item playing (or paused) on the one player, when it is an Audiobookshelf item; else null. */
    fun playingItemId(): String? {
        val id = itemId ?: return null
        val cap = com.toolsboox.ui.plugin.LedgerPlayer.capture ?: return null
        return if (com.toolsboox.ui.plugin.LedgerPlayer.isMediaSource && cap.url.startsWith("abs:$id:")) id else null
    }

    /** The track the player is on, read off the listen id (`abs:<item>:<track>`) so an up-next hop counts. */
    private fun currentTrack(): Audiobookshelf.Track? {
        val cap = com.toolsboox.ui.plugin.LedgerPlayer.capture ?: return null
        val idx = cap.url.substringAfterLast(':').toIntOrNull() ?: return null
        return currentTracks.firstOrNull { it.index == idx }
    }

    /** The playhead on the item's GLOBAL timeline (seconds), or null when the player is on something else. */
    fun globalPosition(): Double? {
        if (playingItemId() == null) return null
        val t = currentTrack() ?: return null
        return t.startOffset + com.toolsboox.ui.plugin.LedgerPlayer.positionMs / 1000.0
    }

    /**
     * Move the playhead to [seconds] on the global timeline: a seek inside the current file, or the
     * file that holds it started there (read-along's reverse direction — the reader turned to a
     * chapter; the narration follows). Blocking when it has to start a file; call off the main thread.
     */
    fun seekGlobal(context: Context, seconds: Double) {
        val item = currentItem ?: return
        if (playingItemId() == null) return
        val tracks = currentTracks.ifEmpty { return }
        val idx = tracks.indexOfLast { it.startOffset <= seconds + 0.5 }.coerceAtLeast(0)
        val target = tracks[idx]
        val cur = currentTrack()
        val within = (seconds - target.startOffset).coerceAtLeast(0.0)
        if (cur != null && cur.index == target.index) {
            com.toolsboox.ui.plugin.LedgerPlayer.seekTo((within * 1000).toInt())
        } else {
            startTrack(context.applicationContext, item, currentEpisodeId, tracks, idx, within, serverUpdated = Long.MAX_VALUE)
        }
    }

    fun listenId(itemId: String, track: Int, episodeId: String? = null) =
        if (episodeId.isNullOrBlank()) "abs:$itemId:$track" else "abs:$itemId:ep:$episodeId"

    /** ▶ Continue for a remembered track: the listen id names the item (and episode); the item is
     *  fetched again and played through the real path — session, chapters, up-next — not as a bare
     *  stream with a stale token (09-11: "trying to continue the audiobook doesn't seem to work").
     *  Blocking (network); call off the main thread. */
    fun resume(context: Context, listenId: String) {
        val parts = listenId.removePrefix("abs:").split(":")
        val itemId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: throw Exception("That track has no home on the server.")
        val episode = if (parts.size >= 3 && parts[1] == "ep") parts[2] else null
        val item = Audiobookshelf.item(context.applicationContext, itemId)
        play(context.applicationContext, item, episode)
    }

    /**
     * Play an audiobook (episodeId null) or one podcast episode, from where the server and the fleet
     * agree it stopped — or, with [startAt] (seconds on the global timeline), from exactly there:
     * read-along starts the narration where the READER is standing, whatever anyone's playhead says.
     */
    fun play(context: Context, item: Audiobookshelf.Item, episodeId: String? = null, startOver: Boolean = false, startAt: Double? = null) {
        val appCtx = context.applicationContext
        val session = Audiobookshelf.openSession(appCtx, item.id, episodeId)
        val tracks = session.tracks.ifEmpty { item.tracks }.sortedBy { it.startOffset }
        if (tracks.isEmpty()) throw Exception("The server offered nothing to play.")
        closeQuietly(appCtx)
        sessionId = session.id; itemId = item.id; lastSyncAt = System.currentTimeMillis()
        currentItem = item; currentEpisodeId = episodeId; currentTracks = tracks
        val global = startAt?.coerceAtLeast(0.0) ?: if (startOver) 0.0 else session.currentTime
        // When the server last heard from anyone — the fleet's own record wins only if it is newer.
        // An explicit start point outranks both.
        val serverUpdated = if (startOver || startAt != null) Long.MAX_VALUE else runCatching {
            Audiobookshelf.progress(appCtx)[if (episodeId.isNullOrBlank()) item.id else item.id + "/" + episodeId]?.lastUpdate
        }.getOrNull() ?: 0L
        val idx = tracks.indexOfLast { it.startOffset <= global + 0.5 }.coerceAtLeast(0)
        // The player is main-thread-only (its listeners summon the transport card); the network
        // above ran on the caller's IO thread, the hand-off runs on main (audit 09-11).
        onMain { startTrack(appCtx, item, episodeId, tracks, idx, within = (global - tracks[idx].startOffset).coerceAtLeast(0.0), serverUpdated = serverUpdated) }
    }

    private fun startTrack(ctx: Context, item: Audiobookshelf.Item, episodeId: String?, tracks: List<Audiobookshelf.Track>, idx: Int, within: Double, serverUpdated: Long) {
        val track = tracks[idx]
        val id = listenId(item.id, track.index, episodeId)
        val many = tracks.size > 1
        val title = if (episodeId != null) item.episodes.firstOrNull { it.id == episodeId }?.title ?: item.title else item.title
        val shownTitle = if (many) "$title · part ${idx + 1}/${tracks.size}" else title
        val stream = Audiobookshelf.streamUrl(ctx, track.contentUrl)
        val cover = Audiobookshelf.coverUrl(ctx, item.id)
        // What the SYNCED files remember is the token-free path — `listen-state.json` rides the
        // fleet and stars copy the enclosure into day files; a JWT there dies within the hour and
        // a legacy token there is a credential on every device (audit 09-11). ▶ Continue and the
        // artwork resolve the path through Audiobookshelf.resolve at play time.
        val streamPath = track.contentUrl
        val coverPath = Audiobookshelf.coverPath(item.id)
        // The server's resume point is written into the fleet's record when the server heard from
        // someone more recently than the fleet did (a book advanced in the ABS app on the phone);
        // otherwise the fleet's own playhead stands and the player resumes from it as usual.
        val local = com.toolsboox.plugin.calendar.ot.ListenStateStore.get(ctx, id)
        if (serverUpdated > (local?.updated ?: 0L)) {
            com.toolsboox.plugin.calendar.ot.ListenStateStore.record(ctx, id, (within * 1000).toLong(), (track.duration * 1000).toLong(),
                sync = false, audioUrl = streamPath, title = shownTitle, show = item.author, artworkUrl = coverPath)
        }
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        lastGlobal = track.startOffset + within
        player.startAudio(
            ctx, shownTitle, listOfNotNull(item.author.ifBlank { null }, item.narrator.ifBlank { null }?.let { "read by $it" }).joinToString(" · "),
            cover, stream,
            com.toolsboox.ui.plugin.LedgerPlayer.Capture(
                title = shownTitle, feedTitle = item.author.ifBlank { "Audiobookshelf" }, url = id,
                imageUrl = coverPath, enclosureUrl = streamPath
            )
        )
        // Chapters inside this file, clock-addressed from ITS start.
        val chapters = item.chapters
            .filter { it.start >= track.startOffset - 0.5 && it.start < track.startOffset + track.duration - 0.5 }
            .map { com.toolsboox.plugin.feeds.da.Chapter(((it.start - track.startOffset).coerceAtLeast(0.0)).toInt(), it.title) }
        if (chapters.isNotEmpty()) player.setChapters(chapters)
        if (idx + 1 < tracks.size) {
            player.setUpNext("$title · part ${idx + 2}/${tracks.size}") {
                onMain { runCatching { startTrack(ctx, item, episodeId, tracks, idx + 1, 0.0, serverUpdated = Long.MAX_VALUE) }
                    .onFailure { Timber.w(it, "abs next track") } }
            }
        }
        val sid = sessionId ?: return
        val startOffset = track.startOffset
        val totalDur = if (episodeId != null) track.duration else item.duration.takeIf { it > 0 } ?: tracks.sumOf { it.duration }
        // Every heartbeat (15s) the global playhead reaches the server; stop closes the session.
        player.progressHook = { posMs, _, final ->
            val global = startOffset + posMs / 1000.0
            val now = System.currentTimeMillis()
            val listened = ((now - lastSyncAt) / 1000.0).coerceIn(0.0, 120.0)
            lastGlobal = global
            if (final || now - lastSyncAt >= 14_000) {
                lastSyncAt = now
                Thread {
                    runCatching { Audiobookshelf.syncSession(ctx, sid, global, listened, totalDur, close = final) }
                        .onFailure { Timber.w(it, "abs sync") }
                }.start()
                if (final) { sessionId = null }
            }
        }
    }

    private fun closeQuietly(ctx: Context) {
        val sid = sessionId ?: return
        sessionId = null
        val g = lastGlobal
        Thread { runCatching { Audiobookshelf.syncSession(ctx, sid, g, 0.0, 0.0, close = true) } }.start()
    }
}
