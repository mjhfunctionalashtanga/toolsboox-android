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
     *
     * `"educate"` is a LEGACY STORAGE KEY, not a description — and the fourth quarter is EMAIL.
     * [com.toolsboox.plugin.calendar.ot.CalendarDayPageIntake] has drawn it as `IntakePanel(
     * "educate", "EMAIL", …)` since starred mail started landing there, and the Later List's fourth
     * lane says "📧 Email" for the same reason (see
     * [com.toolsboox.plugin.feeds.nw.LaterFeed.LANES]). Ask's "Educate me" lookups land in the same
     * lane, which is where the key's name came from, but mail is the bulk of it now. When the
     * storage key and the DRAWN label disagree, the drawn label is the one that was learned — so
     * the title here is corrected to match the page rather than left as the third different word
     * for one quarter. (The key itself never moves: it is on disk in every intake day file, on both
     * forks, and renaming it would orphan every link ever filed there.)
     */
    val PANELS = listOf(
        "read" to "THE READ",
        "watch" to "THE WATCH",
        "listen" to "THE LISTEN",
        "educate" to "EMAIL"
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

    /**
     * Union the non-blank lines of [a] and [b], preserving order (a first) and de-duplicating —
     * minus anything [unfileLink] has taken off the list.
     *
     * The union is what stops two devices clobbering each other's filings, and it is also what
     * would resurrect a deleted link: the remote copy still holds the line the delete just removed,
     * so a plain union hands it straight back. [removed] is the tombstone set, and a line naming a
     * tombstoned URL is dropped from BOTH sides — which also means the merged page pushed back up
     * no longer carries it, so the deletion propagates to the other devices instead of fighting
     * them. Nothing else is filtered: a tombstone is only ever written by an explicit delete.
     */
    private fun unionLines(a: String, b: String, removed: Set<String>): String {
        val seen = LinkedHashSet<String>()
        for (s in (a + "\n" + b).split("\n")) {
            val t = s.trim()
            if (t.isEmpty()) continue
            if (removed.any { t.contains(it) }) continue
            seen.add(t)
        }
        return seen.joinToString("\n")
    }

    /**
     * Merge two intake pages without losing either device's edits: the Read/Watch/Listen link lanes
     * are append-style, so they union by line (matching the URL-dedup dispatch already does); Educate
     * is a free note, so the longer text wins; delivered markers union; structured sections keep the
     * non-blank side. Stops the old push-only clobber where the last device to save wiped the other.
     */
    private fun merge(context: Context, local: IntakePageData, remote: IntakePageData): IntakePageData {
        val m = IntakePageData()
        val removed = LaterRemovals.all(context)
        m.readTyped = unionLines(local.readTyped, remote.readTyped, removed)
        m.watchTyped = unionLines(local.watchTyped, remote.watchTyped, removed)
        m.listenTyped = unionLines(local.listenTyped, remote.listenTyped, removed)
        // Educate is the odd lane: longer-wins, because it is a free note as well as a link lane.
        // A delete inside it still has to stick, so the winning side is line-filtered afterwards
        // rather than unioned — the note keeps its shape, the deleted link doesn't come back.
        val educate = if (remote.educateTyped.length > local.educateTyped.length) remote.educateTyped else local.educateTyped
        m.educateTyped =
            if (removed.isEmpty()) educate
            else educate.split("\n").filterNot { l -> removed.any { l.contains(it) } }.joinToString("\n")
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

    /**
     * ASK THE SERVER WHICH DAYS EXIST, then fetch the ones this device hasn't got.
     *
     * The Later List reads a 120-day window of LOCAL intake files while the refresh sweep pulled a
     * 15-day window of remote ones, and that asymmetry is invisible until it isn't: a device that
     * has never filed a link of its own — a fresh Boox beside an iPhone and an iPad that do the
     * filing — has no local files at all, so it can only ever show the last fortnight, and shows an
     * EMPTY LIST if he happened not to file anything in it. The list calls itself "the WHOLE
     * backlog, a finite feed to clear" in its own comment; a fortnight cannot serve a four-month
     * read, and no choice of two numbers ever makes a guess correct.
     *
     * So stop guessing. `intake/` is a flat collection of `intake-YYYY-MM-DD.json`, and the WebDAV
     * service can list it in one round trip (`propfind` walks with Depth:1 where the server refuses
     * Depth:infinity, which stock Apache does — see its own comment). The listing IS the answer to
     * "which days are there", and it costs one request to get instead of a hundred and twenty
     * speculative GETs for days that mostly do not exist.
     *
     * A day is fetched when this device has no copy, OR when the server's copy is NEWER than ours.
     * "Missing" alone would not have been enough: the old short sweep wrote a file for every day it
     * looked at, including the empty ones, so a device could hold a zero-link page for a day the
     * others have since filled — present on disk, skipped forever, and invisible in the list. The
     * modification time is already in the listing, so this costs nothing extra to ask. (It trusts
     * two clocks to roughly agree. They are both his; a skew re-fetches a page or defers it until
     * the next real change, neither of which loses anything.)
     *
     * Days that land here are pulled WITHOUT pushing back — this device is catching up on what the
     * others wrote, and echoing an untouched page at the server is a PUT that says nothing.
     *
     * @return true when at least one day file arrived or changed — the caller redraws on that, and
     *         on nothing else.
     */
    fun pullMissingDays(context: Context, window: Long = 120L): Boolean {
        val svc = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.service(context) ?: return false
        // null means the LISTING failed, which is not the same as "the server has nothing" — the
        // caller keeps whatever it has rather than concluding the backlog is empty. (Same
        // distinction CalendarWebDavSyncService draws, and for the same reason.)
        val listing = runCatching { svc.propfind("intake/") }.getOrNull() ?: return false
        val oldest = LocalDate.now().minusDays(window)
        var landed = false
        for (entry in listing) {
            val name = entry.remotePath.substringAfterLast('/')
            val date = runCatching {
                LocalDate.parse(name.removePrefix("intake-").removeSuffix(".json"))
            }.getOrNull() ?: continue
            if (date.isBefore(oldest)) continue
            val local = fileFor(context, date)
            if (local.exists() && entry.lastModified in 1..local.lastModified()) continue
            val before = runCatching { local.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            mergeFromRemote(context, date, push = false)
            val after = runCatching { local.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            if (after != before) landed = true
        }
        return landed
    }

    private fun mergeFromRemote(context: Context, date: LocalDate, push: Boolean = true) {
        val adapter = moshi.adapter(IntakePageData::class.java)
        val localText = fileFor(context, date).let { if (it.exists()) it.readText(Charsets.UTF_8) else "" }
        val local = runCatching { adapter.fromJson(localText) }.getOrNull() ?: IntakePageData()
        val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, remotePath(date))
        val remote = remoteText?.let { runCatching { adapter.fromJson(it) }.getOrNull() }
        val merged = if (remote == null) local else merge(context, local, remote)
        val mergedJson = adapter.toJson(merged)
        // Nothing there and nothing here: don't mint an empty day file. It would satisfy the
        // "already on disk" check in [pullMissingDays] forever after, so a day that later gained a
        // link on another device would never be fetched again.
        if (remote == null && localText.isEmpty()) return
        if (mergedJson != localText) runCatching { fileFor(context, date).writeText(mergedJson, Charsets.UTF_8) }
        if (push) com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(date), mergedJson)
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
        // Filing a link is an explicit act, so it OUTRANKS an old deletion of the same URL: without
        // this, a link he removed months ago could never be filed again — the merge would quietly
        // drop it on the next sync and the row would just never appear.
        LaterRemovals.forget(context, url)
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

    /**
     * Take a filed link back off the Later List.
     *
     * A later-list entry is a LINE inside an intake day page — that is the whole storage model — so
     * removing one means rewriting that day's lane without it. Matched on the line's URL when it
     * has one and on the whole line when it doesn't, because the title half is RECONSTRUCTED when
     * the row is built ([com.toolsboox.plugin.feeds.nw.LaterFeed] strips the URL out of the line and
     * falls back to the saved meta title or the host), so the title is not reliably what is on disk.
     * The URL is.
     *
     * Michael, 07-30: "I would like to be able to star items from the later list tho and delete
     * them." Deleting drops the link's meta and its star with it — a link you removed should not
     * come back wearing its old face if you file it again a year later.
     *
     * AND IT LEAVES A TOMBSTONE, which the iPad twin does not need and this fork does. Over there a
     * delete rewrites the file and the union-merge only re-adds the line on the next cross-device
     * pull. Here [save] kicks [syncWebDav] on every write, so the pull-merge-push would run seconds
     * later, union the deleted line back out of the remote copy and put the row on screen again
     * before the list had finished redrawing — a delete that undoes itself is worse than no delete.
     * [LaterRemovals] is that record, and [merge] consults it; re-filing the same URL clears it (see
     * [fileLink]), so a deliberate second filing is never eaten by an old deletion.
     *
     * @return true when a line actually went; false when it had already gone (a stale tap on a list
     *         that has since been reloaded), which should be silent rather than an error.
     */
    fun unfileLink(context: Context, date: LocalDate, kind: String, url: String?, title: String): Boolean {
        val data = load(context, date)
        val lines = data.typedFor(kind).split("\n")
        val link = url?.trim()?.takeIf { it.isNotEmpty() }
        val kept = lines.filter { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> true
                link != null -> !line.contains(link)
                else -> line != title
            }
        }
        if (kept.size == lines.size) return false
        data.setTypedFor(kind, kept.joinToString("\n").trim())
        if (link != null) {
            data.linkMeta.remove(link)
            LaterRemovals.remember(context, link)
            LaterStars.set(context, link, starred = false)
        }
        save(context, date, data)
        return true
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

/**
 * WHICH FILED LINKS ARE STARRED.
 *
 * The Later List shipped without a star on both forks, and the reason was honest: a later-list
 * entry is a LINE inside an intake day page, so there was nowhere to put the flag, and the rule
 * here is that a gesture which would write nowhere isn't offered at all. Michael's answer was to
 * make the place exist — "I would like to be able to star items from the later list tho and delete
 * them."
 *
 * A SIDECAR rather than a new field on the line, and that is the load-bearing decision: the line's
 * format is shared with the iPad, which reads these same day files off WebDAV and would not know
 * what to do with a decorated entry. Whatever either fork writes into that lane, the other has to
 * be able to read as "title — url" and nothing else. So the flag lives beside the file, never in it.
 *
 * Keyed on the URL, so a link filed twice on different days is one thing with one star — which is
 * what the row builder's dedup already assumes. Local-only, like the iPad's `later-stars.json`: a
 * star here is triage inside a list you keep on the device you are keeping it on, and syncing it
 * would mean a third sidecar on the wire for a flag no other surface reads.
 */
object LaterStars {

    private fun prefs(context: Context) =
        context.getSharedPreferences("ledger_later_prefs", Context.MODE_PRIVATE)

    private const val KEY = "starred_links"

    /** Defensive copy: [android.content.SharedPreferences.getStringSet] hands back the live set and
     *  explicitly forbids mutating it. */
    private fun all(context: Context): MutableSet<String> =
        HashSet(prefs(context).getStringSet(KEY, emptySet()) ?: emptySet())

    fun isStarred(context: Context, link: String): Boolean {
        val key = link.trim()
        return key.isNotEmpty() && all(context).contains(key)
    }

    fun set(context: Context, link: String, starred: Boolean) {
        val key = link.trim()
        if (key.isEmpty()) return
        val s = all(context)
        if (starred) s.add(key) else s.remove(key)
        prefs(context).edit().putStringSet(KEY, s).apply()
    }
}

/**
 * WHICH FILED LINKS HAVE BEEN DELETED — the tombstones that make a delete stick.
 *
 * See [IntakePageStore.unfileLink] for why this exists at all: every save on this fork pushes the
 * day's page through a pull-merge-push, and the merge unions lines, so a line removed locally comes
 * straight back out of the remote copy. A tombstone is the only thing that can tell "this line is
 * gone on purpose" apart from "this line hasn't reached this device yet", which is exactly what a
 * union cannot distinguish on its own.
 *
 * Deliberately NOT capped or expired. The set holds one URL string per link he has ever deleted,
 * which after years of use is kilobytes, and the failure mode of dropping an old tombstone is the
 * worst one this whole feature has: a link he threw away reappearing at the top of the list with no
 * explanation. Re-filing the URL is the intended way out (see [IntakePageStore.fileLink]) — a
 * deliberate act, undoing a deliberate act.
 */
object LaterRemovals {

    private fun prefs(context: Context) =
        context.getSharedPreferences("ledger_later_prefs", Context.MODE_PRIVATE)

    private const val KEY = "removed_links"

    fun all(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY, emptySet()) ?: emptySet())

    fun remember(context: Context, link: String) {
        val key = link.trim()
        if (key.isEmpty()) return
        val s = HashSet(all(context)); s.add(key)
        prefs(context).edit().putStringSet(KEY, s).apply()
    }

    fun forget(context: Context, link: String) {
        val key = link.trim()
        if (key.isEmpty()) return
        val s = HashSet(all(context))
        if (s.remove(key)) prefs(context).edit().putStringSet(KEY, s).apply()
    }
}
