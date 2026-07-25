package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.toolsboox.plugin.michaelfilter.da.IntakePageData
import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * Persistence + dispatch for the per-day MichaelFilter intake page.
 *
 * Typed panel text is stored as one JSON sidecar per day in
 * filesDir/michaelfilter-intake-pages/intake-YYYY-MM-DD.json (local-only;
 * the server is the system of record once content is dispatched).
 *
 * Dispatch: every URL found in a panel's typed text is enqueued through the
 * shared IntakeQueue with the panel's kind (read|watch|listen|educate).
 * Non-URL text in the Educate Me panel is valid on its own and is sent as a
 * text-only submission. Delivered markers on the data class prevent
 * re-enqueueing unchanged content; the server additionally dedups by URL.
 */
object IntakePageStore {

    private const val TAG = "IntakePageStore"
    private const val DIR_NAME = "michaelfilter-intake-pages"

    /**
     * The four panels in page order: kind key to panel title.
     */
    val PANELS = listOf(
        "read" to "THE READ",
        "watch" to "THE WATCH",
        "listen" to "THE LISTEN",
        "educate" to "EDUCATE ME"
    )

    private val moshi: Moshi = Moshi.Builder().build()

    private fun fileFor(context: Context, date: LocalDate): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, "intake-$date.json")
    }

    /**
     * Load the intake page data of a day (empty data when none saved yet).
     */
    fun load(context: Context, date: LocalDate): IntakePageData {
        val file = fileFor(context, date)
        if (!file.exists()) return IntakePageData()
        return try {
            moshi.adapter(IntakePageData::class.java).fromJson(file.readText(Charsets.UTF_8))
                ?: IntakePageData()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Corrupt intake page file ${file.name}")
            IntakePageData()
        }
    }

    /**
     * Save the intake page data of a day.
     */
    fun save(context: Context, date: LocalDate, data: IntakePageData) {
        try {
            val json = moshi.adapter(IntakePageData::class.java).toJson(data)
            fileFor(context, date).writeText(json, Charsets.UTF_8)
            syncWebDav(context, date)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save intake page for $date")
        }
    }

    private fun remotePath(date: LocalDate) = "intake/intake-$date.json"

    /** Union the non-blank lines of [a] and [b], preserving order (a first) and de-duplicating. */
    private fun unionLines(a: String, b: String): String {
        val seen = LinkedHashSet<String>()
        for (s in (a + "\n" + b).split("\n")) { val t = s.trim(); if (t.isNotEmpty()) seen.add(t) }
        return seen.joinToString("\n")
    }

    /**
     * Merge two intake pages without losing either device's edits: the Read/Watch/Listen link lanes
     * are append-style, so they union by line (matching the URL-dedup dispatch already does); Educate
     * is a free note, so the longer text wins; delivered markers union; structured sections keep the
     * non-blank side. Stops the old push-only clobber where the last device to save wiped the other.
     */
    private fun merge(local: IntakePageData, remote: IntakePageData): IntakePageData {
        val m = IntakePageData()
        m.readTyped = unionLines(local.readTyped, remote.readTyped)
        m.watchTyped = unionLines(local.watchTyped, remote.watchTyped)
        m.listenTyped = unionLines(local.listenTyped, remote.listenTyped)
        m.educateTyped = if (remote.educateTyped.length > local.educateTyped.length) remote.educateTyped else local.educateTyped
        m.deliveredLinkUrls = (local.deliveredLinkUrls + remote.deliveredLinkUrls).distinct().toMutableList()
        m.deliveredEducateNote = local.deliveredEducateNote.ifBlank { remote.deliveredEducateNote }
        for (k in (local.sections.keys + remote.sections.keys)) {
            val ls = local.sections[k] ?: mutableMapOf(); val rs = remote.sections[k] ?: mutableMapOf()
            val merged = mutableMapOf<String, String>()
            for (sk in (ls.keys + rs.keys)) merged[sk] = ls[sk]?.takeIf { it.isNotBlank() } ?: rs[sk].orEmpty()
            m.sections[k] = merged
        }
        // Link metadata unions the same way: every URL either side knows about, local value wins
        // per field when both filled one in (it's the device the save happened on).
        for (u in (local.linkMeta.keys + remote.linkMeta.keys)) {
            val lm = local.linkMeta[u] ?: mutableMapOf(); val rm = remote.linkMeta[u] ?: mutableMapOf()
            val merged = mutableMapOf<String, String>()
            for (mk in (lm.keys + rm.keys)) merged[mk] = lm[mk]?.takeIf { it.isNotBlank() } ?: rm[mk].orEmpty()
            m.linkMeta[u] = merged
        }
        return m
    }

    /**
     * Round-trip the day's intake page through WebDAV: pull the remote copy, merge it with the local
     * one, write the merged result back to disk, and push it. Fire-and-forget; no-op without creds.
     * Lands at <root>/intake/intake-YYYY-MM-DD.json.
     */
    private fun syncWebDav(context: Context, date: LocalDate) {
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background { mergeFromRemote(context, date) }
    }

    /** Blocking pull-merge-push for [date]. Call off the main thread. Used to refresh the Later view
     *  so links filed on another device show up here. */
    fun pullLatest(context: Context, date: LocalDate) = mergeFromRemote(context, date)

    private fun mergeFromRemote(context: Context, date: LocalDate) {
        val adapter = moshi.adapter(IntakePageData::class.java)
        val localText = fileFor(context, date).let { if (it.exists()) it.readText(Charsets.UTF_8) else "" }
        val local = runCatching { adapter.fromJson(localText) }.getOrNull() ?: IntakePageData()
        val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath(date))
        val remote = remoteText?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
        val merged = if (remote == null) local else merge(local, remote)
        val mergedJson = adapter.toJson(merged)
        if (mergedJson != localText) runCatching { fileFor(context, date).writeText(mergedJson, Charsets.UTF_8) }
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(date), mergedJson)
    }

    /**
     * File a shared link into a panel (read|watch|listen|educate): append it to that
     * day's typed content, persist, and dispatch (which enqueues it to the pipeline and
     * makes it show up in the Notes & Annotations log). Used by the share-to-file flow.
     *
     * [excerpt] and [image] are the link's presentation metadata — pass them when the caller
     * already knows the thing (a feed entry being saved carries its own blurb + featured
     * image). When neither is given, a best-effort og: fetch fills them in the background,
     * so a link saved from the open web still renders informative and attractive in the
     * Later list — and a fetch that fails leaves today's plain behavior untouched.
     */
    fun fileLink(context: Context, date: LocalDate, kind: String, url: String, title: String?,
                 excerpt: String? = null, image: String? = null) {
        val data = load(context, date)
        val entry = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, url.trim()).joinToString(" — ")
        val existing = data.typedFor(kind).trim()
        data.setTypedFor(kind, if (existing.isEmpty()) entry else "$existing\n$entry")
        writeMeta(data, url, title, excerpt, image)
        save(context, date, data)
        dispatch(context, date, data)
        publish(context, kind, url, title ?: "")
        cacheArticle(context, url)
        if (excerpt.isNullOrBlank() && image.isNullOrBlank()) fetchLinkMeta(context, date, url)
    }

    /** The saved presentation metadata for a filed [url] on [date]'s page (null when none). */
    fun linkMeta(data: com.toolsboox.plugin.michaelfilter.da.IntakePageData, url: String): Map<String, String>? =
        data.linkMeta[url.trim()]?.takeIf { it.values.any { v -> v.isNotBlank() } }

    /** Attach presentation metadata to an already-filed link (the add-to-Later path writes its
     *  typed line itself and only needs this half). */
    fun rememberLinkMeta(context: Context, date: LocalDate, url: String,
                         title: String?, excerpt: String?, image: String?) {
        if (title.isNullOrBlank() && excerpt.isNullOrBlank() && image.isNullOrBlank()) return
        val data = load(context, date)
        writeMeta(data, url, title, excerpt, image)
        save(context, date, data)
    }

    private fun writeMeta(data: com.toolsboox.plugin.michaelfilter.da.IntakePageData,
                          url: String, title: String?, excerpt: String?, image: String?) {
        if (title.isNullOrBlank() && excerpt.isNullOrBlank() && image.isNullOrBlank()) return
        val meta = data.linkMeta.getOrPut(url.trim()) { mutableMapOf() }
        title?.trim()?.takeIf { it.isNotBlank() }?.let { meta["title"] = it }
        excerpt?.trim()?.takeIf { it.isNotBlank() }?.let { meta["excerpt"] = it.take(200) }
        image?.trim()?.takeIf { it.startsWith("http") }?.let { meta["image"] = it }
    }

    /**
     * Best-effort og:title / og:description / og:image for a link filed with no known entry —
     * one lightweight fetch at save time, background, fail-silent. Whatever the page offers is
     * merged into the day's linkMeta; a page that offers nothing changes nothing.
     */
    private fun fetchLinkMeta(context: Context, date: LocalDate, url: String) {
        if (!url.startsWith("http")) return
        Thread {
            runCatching {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0")
                    .get().build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@Thread
                    // The og: tags live in <head>; the first 64K is plenty and keeps this light.
                    val head = resp.body?.source()?.let { s ->
                        s.request(65536); s.buffer.snapshot().utf8()
                    }.orEmpty()
                    fun og(prop: String): String? =
                        Regex("""<meta[^>]+(?:property|name)=["']og:$prop["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(head)?.groupValues?.get(1)
                            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:$prop["']""", RegexOption.IGNORE_CASE)
                                .find(head)?.groupValues?.get(1)
                    val title = og("title")
                    val desc = og("description")
                    val image = og("image")
                    if (title.isNullOrBlank() && desc.isNullOrBlank() && image.isNullOrBlank()) return@Thread
                    val decoded = fun(s: String?): String? = s?.let {
                        android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    }
                    val data = load(context, date)
                    writeMeta(data, url, decoded(title), decoded(desc), image)
                    save(context, date, data)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------
    // Offline article cache — a Later List link behaves like any other feed
    // entry: a parsed (readable) copy is saved AT FILE TIME so the article
    // opens in the reader with no connection.
    // ------------------------------------------------------------------

    private fun articleCacheDir(context: Context): File =
        File(context.filesDir, "later-article-cache").apply { mkdirs() }

    private fun articleCacheFile(context: Context, url: String): File =
        File(articleCacheDir(context), com.toolsboox.ot.CryptoUtils.md5Hash(url.trim().toByteArray()) + ".html")

    /** The cached readable copy of a filed link, or null when never fetched. */
    fun cachedArticle(context: Context, url: String): String? =
        articleCacheFile(context, url).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** Fetch + readability-strip the page in the background and cache it (idempotent). */
    fun cacheArticle(context: Context, url: String) {
        if (!url.startsWith("http")) return
        val file = articleCacheFile(context, url)
        if (file.exists()) return
        Thread {
            runCatching {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0")
                    .get().build()
                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@Thread
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
                    if (body.isNotBlank()) file.writeText(body)
                }
            }
        }.start()
    }

    // No client-embedded shared secret: the server authorizes ingest on the per-user token and
    // provision on a per-IP rate limit, so nothing sensitive ships in the APK.
    private const val LATER_INGEST_URL = "https://mjh.yoga/wp-json/mjh/v1/later"
    private const val LATER_PROVISION_URL = "https://mjh.yoga/wp-json/mjh/v1/later/provision"

    private fun laterPrefs(context: Context) =
        context.getSharedPreferences("ledger_later_prefs", Context.MODE_PRIVATE)

    fun userToken(context: Context): String? =
        laterPrefs(context).getString("later_feed_token", null)?.takeIf { it.isNotBlank() }

    /** The user's personal subscribable feed URL, or null until provisioned. */
    fun feedUrl(context: Context): String? =
        userToken(context)?.let { "https://mjh.yoga/?mjh_later_feed=1&key=$it" }

    /** Self-serve: mint (once) + store this user's token; returns the feed URL. Blocking — off main. */
    fun provision(context: Context): String? {
        feedUrl(context)?.let { return it }
        val body = org.json.JSONObject().put("label", "Android").toString()
        val req = okhttp3.Request.Builder().url(LATER_PROVISION_URL)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = org.json.JSONObject(resp.body?.string() ?: return null)
                val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
                laterPrefs(context).edit().putString("later_feed_token", token).apply()
                json.optString("feed_url").takeIf { it.isNotBlank() } ?: feedUrl(context)
            }
        }.getOrNull()
    }

    /**
     * Publish the filed item to the user's personal Later List RSS feed on mjh.yoga (any RSS
     * reader subscribes). Auto-provisions on first use; fire-and-forget.
     */
    fun publish(context: Context, kind: String, url: String, title: String) {
        Thread {
            var token = userToken(context)
            if (token == null) { provision(context); token = userToken(context) }
            if (token == null) return@Thread
            val itemId = "android-" + (url + "|" + title).hashCode()
            val body = org.json.JSONObject()
                .put("token", token).put("title", title).put("url", url)
                .put("kind", kind).put("item_id", itemId).toString()
            val req = okhttp3.Request.Builder().url(LATER_INGEST_URL)
                .post(body.toRequestBody("application/json".toMediaType())).build()
            runCatching { okHttp.newCall(req).execute().close() }
        }.start()
    }

    private val okHttp by lazy { okhttp3.OkHttpClient() }

    /**
     * Enqueue all not-yet-delivered typed content through the intake queue.
     * Saves the updated delivered markers and schedules the drain worker when
     * anything new was enqueued.
     *
     * @return the number of newly enqueued submissions
     */
    fun dispatch(context: Context, date: LocalDate, data: IntakePageData): Int {
        var enqueued = 0

        for ((kindKey, _) in PANELS) {
            val typed = data.typedFor(kindKey).trim()
            if (typed.isEmpty()) continue

            val urls = ShareTextParser.extractUrls(typed)

            if (urls.isEmpty()) {
                // Text-only content is valid for Educate Me; the other panels
                // hold their text until a URL shows up in it.
                if (kindKey == "educate" && typed != data.deliveredEducateNote) {
                    val submission = IntakeSubmission(
                        linkUrl = "",
                        linkKind = "educate",
                        pastedBody = typed
                    )
                    if (IntakeQueue.enqueue(context, submission) != null) {
                        data.deliveredEducateNote = typed
                        enqueued++
                    }
                }
                continue
            }

            // Body text = typed text with the URLs stripped out.
            var body = typed
            urls.forEach { body = body.replace(it, "") }
            body = body.trim()

            for (url in urls) {
                if (url in data.deliveredLinkUrls) continue
                val submission = IntakeSubmission(
                    linkUrl = url,
                    linkKind = kindKey,
                    pastedBody = body.takeIf { it.isNotEmpty() }
                )
                if (IntakeQueue.enqueue(context, submission) != null) {
                    data.deliveredLinkUrls.add(url)
                    enqueued++
                }
            }
        }

        if (enqueued > 0) {
            save(context, date, data)
            IntakeQueue.scheduleDrain(context)
            Timber.i("$TAG: Dispatched $enqueued intake submission(s) for $date")
        }

        return enqueued
    }
}
