package com.toolsboox.plugin.feeds.nw

import android.content.Context
import com.toolsboox.plugin.feeds.da.Chapter
import com.toolsboox.plugin.feeds.da.ChapterList
import com.toolsboox.plugin.feeds.da.FeedEntry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Podcasting 2.0 layer for The Listen and The Watch: chapters and transcripts per entry.
 *
 * Two chapter sources, one shape:
 *  1. `<podcast:chapters>` — the real 2.0 tag. Miniflux's API strips custom namespaces, so we go
 *     back to the FEED's own XML (entry.feedUrl), find this entry's item by enclosure/link/guid,
 *     and fetch the chapters JSON it points at (podcastindex format). Authoritative — wins.
 *  2. Timestamp lines in the description ("0:00 Intro", "1:23:45 - Topic") — no server support
 *     needed, and it's how nearly every YouTube video ships its chapters, so The Watch gets
 *     chapters for free.
 *
 * Everything is defensive (bad XML/JSON/timestamps → no chapters, never a crash), background-
 * friendly (blocking calls, made for Dispatchers.IO), and cached per entry in [FeedCache] —
 * including the empty answer, so we don't re-interrogate a chapterless feed on every open.
 */
object FeedChapters {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---- Description timestamps (source 2 — works offline, covers The Watch) ---------------

    /** "0:00 Intro" / "» 12:34 — The middle part" / "1:23:45 Topic", one per line. */
    private val stampLine = Regex(
        """^[\s\-–—•*·>»\[(]{0,4}(\d{1,2}):(\d{2})(?::(\d{2}))?[\])]?\s*[-–—:.·]*\s+(\S.*)$"""
    )

    /** Whether one description line IS a chapter mark — so The Watch's skin can render the
     *  chapter table as tappable rows and drop the same lines from the prose. */
    fun isStampLine(line: String): Boolean = stampLine.matches(line.trim())

    /**
     * HTML → lines, keeping the LINE STRUCTURE that timestamp chapters live in. (HtmlText.toPlain
     * collapses all whitespace, which is right for blurbs and fatal here.)
     */
    fun htmlToLines(html: String): List<String> {
        var s = html
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("(?i)<(?:br|/p|/li|/div|/h[1-6]|/tr)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
        for ((a, b) in listOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
            "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")) s = s.replace(a, b)
        return s.lines().map { it.trim() }
    }

    /**
     * Chapter list from an entry's description/content. At least two monotonic timestamps make
     * a chapter list — a lone "12:30 meeting tomorrow" is just a sentence, not a table of
     * contents, and monotonicity is what separates chapters from scattered time mentions.
     */
    fun fromDescription(content: String): List<Chapter> = runCatching {
        val out = mutableListOf<Chapter>()
        for (line in htmlToLines(content)) {
            val m = stampLine.find(line) ?: continue
            val g = m.groupValues
            val sec = if (g[3].isNotEmpty()) g[1].toInt() * 3600 + g[2].toInt() * 60 + g[3].toInt()
            else g[1].toInt() * 60 + g[2].toInt()
            val title = g[4].trim().trimEnd('-', '–', '—').trim().take(120)
            if (title.isEmpty()) continue
            if (out.isNotEmpty() && sec < out.last().startSec) {
                // Order broke — a second timestamp block (credits, "clips at 3:45") started.
                // Keep the first coherent run rather than a shuffled mess.
                break
            }
            out += Chapter(sec, title)
            if (out.size >= 300) break
        }
        if (out.size >= 2) out else emptyList()
    }.getOrDefault(emptyList())

    // ---- Quick (synchronous, no network) ---------------------------------------------------

    /**
     * What we can know WITHOUT touching the network: the cached resolution if one exists, else
     * a fresh description parse. Cheap enough for the render path; [resolve] upgrades it in the
     * background and caches. Never caches on its own — a desc-only cache entry would stop
     * [resolve] from ever asking the feed for the real 2.0 tag.
     */
    fun quick(context: Context, entry: FeedEntry): List<Chapter> {
        FeedCache.loadPodMeta(context, entry.id)?.let { return chaptersFrom(it) }
        return fromDescription(entry.content)
    }

    // ---- Full resolve (blocking — Dispatchers.IO) ------------------------------------------

    /**
     * The whole ladder, cached: cache → feed XML (`podcast:chapters` JSON, `podcast:transcript`
     * pointer) → description timestamps. The result is written back to [FeedCache] — including
     * an empty list — EXCEPT when the feed fetch itself failed, so a dead network tonight
     * doesn't get remembered as "no chapters" forever.
     */
    fun resolve(context: Context, entry: FeedEntry): ChapterList {
        FeedCache.loadPodMeta(context, entry.id)?.let {
            return ChapterList(entry.id, chaptersFrom(it), it.optString("source").ifBlank { "desc" })
        }

        val meta = JSONObject()
        var feedFetchFailed = false
        var chapters: List<Chapter> = emptyList()
        var source = "desc"

        // 1. The feed's own XML — the only place the 2.0 tags live (Miniflux won't carry them).
        val feedUrl = entry.feedUrl?.takeIf { it.startsWith("http") }
        if (feedUrl != null) {
            val lookup = runCatching { matchItem(feedUrl, entry) }
                .getOrDefault(ItemLookup(reachable = false, item = null))
            if (!lookup.reachable) feedFetchFailed = true
            val item = lookup.item
            if (item != null) {
                if (item.chaptersUrl.isNotBlank()) meta.put("chaptersUrl", item.chaptersUrl)
                if (item.transcriptUrl.isNotBlank()) {
                    meta.put("transcriptUrl", item.transcriptUrl)
                    meta.put("transcriptType", item.transcriptType)
                }
                if (item.chaptersUrl.isNotBlank()) {
                    val fetched = runCatching { fetchChaptersJson(item.chaptersUrl) }.getOrDefault(emptyList())
                    if (fetched.isNotEmpty()) { chapters = fetched; source = "tag" }
                }
            }
        }

        // 2. Description timestamps — the everywhere fallback (and The Watch's main course).
        if (chapters.isEmpty()) chapters = fromDescription(entry.content)

        meta.put("source", source)
        meta.put("chapters", JSONArray().also { arr ->
            chapters.forEach { arr.put(JSONObject().put("start", it.startSec).put("title", it.title)) }
        })
        if (!feedFetchFailed) FeedCache.savePodMeta(context, entry.id, meta)
        return ChapterList(entry.id, chapters, source)
    }

    /** The Watch's door: chapters for an entry, full ladder, cached. Blocking — call on IO.
     *  The article view renders these as tappable rows (embed reload with ?start=SECONDS). */
    fun forEntry(context: Context, entry: FeedEntry): List<Chapter> = resolve(context, entry).chapters

    // ---- Transcript ------------------------------------------------------------------------

    /**
     * The entry's transcript as plain "[m:ss] line" text: resolve the `podcast:transcript`
     * pointer (if the feed has one), fetch it lazily, normalise SRT/VTT/JSON/plain, cache.
     * Null when the feed offers none or the fetch fails — never a crash.
     */
    fun transcript(context: Context, entry: FeedEntry): String? {
        FeedCache.loadTranscript(context, entry.id)?.let { return it }
        resolve(context, entry)   // makes sure the pod meta (with any transcript pointer) exists
        val meta = FeedCache.loadPodMeta(context, entry.id) ?: return null
        val url = meta.optString("transcriptUrl").takeIf { it.startsWith("http") } ?: return null
        val raw = runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull() ?: return null
        val text = runCatching { normalizeTranscript(raw) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        FeedCache.saveTranscript(context, entry.id, text)
        return text
    }

    /** SRT / VTT / podcastindex-JSON / plain-anything → "[m:ss] line" text. */
    fun normalizeTranscript(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("{") || t.startsWith("[") -> jsonTranscript(t)
            t.startsWith("WEBVTT") || t.contains("-->") -> cueTranscript(t)
            t.startsWith("<") -> htmlToLines(t).filter { it.isNotBlank() }.joinToString("\n")
            else -> t
        }
    }

    /** podcastindex transcript JSON: {"segments":[{"startTime":s,"body":"…"}]}. */
    private fun jsonTranscript(t: String): String {
        val segments = if (t.startsWith("[")) JSONArray(t)
        else JSONObject(t).optJSONArray("segments") ?: return ""
        val sb = StringBuilder()
        var lastStamp = -1
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val body = seg.optString("body").trim()
            if (body.isEmpty()) continue
            val start = seg.optDouble("startTime", -1.0).toInt()
            // Stamp roughly every new moment, not every word-sized segment.
            if (start >= 0 && start != lastStamp) { sb.append("[${clock(start)}] "); lastStamp = start }
            sb.append(body).append('\n')
        }
        return sb.toString().trim()
    }

    /** SRT and VTT share the shape: "HH:MM:SS[.,]mmm --> …" cue lines followed by text. */
    private fun cueTranscript(t: String): String {
        val sb = StringBuilder()
        var pendingStamp: String? = null
        var lastText = ""
        for (rawLine in t.lines()) {
            val line = rawLine.trim()
            when {
                line.contains("-->") -> {
                    val start = line.substringBefore("-->").trim()
                    val sec = parseCueTime(start)
                    pendingStamp = if (sec >= 0) "[${clock(sec)}]" else null
                }
                line.isEmpty() || line == "WEBVTT" || line.matches(Regex("""\d+""")) ||
                    line.startsWith("NOTE") || line.startsWith("STYLE") -> { /* structure, not speech */ }
                else -> {
                    val clean = line.replace(Regex("<[^>]+>"), "").trim()
                    // Rolling captions repeat the previous line — keep each spoken line once.
                    if (clean.isNotEmpty() && clean != lastText) {
                        if (pendingStamp != null) { sb.append(pendingStamp).append(' '); pendingStamp = null }
                        sb.append(clean).append('\n')
                        lastText = clean
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    /** "01:02:03.456" / "02:03,456" / "02:03" → seconds; -1 when unparseable. */
    private fun parseCueTime(s: String): Int = runCatching {
        val core = s.replace(',', '.').substringBefore('.').trim()
        val parts = core.split(':').map { it.toInt() }
        when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> -1
        }
    }.getOrDefault(-1)

    private fun clock(sec: Int): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)

    // ---- Player hookup ---------------------------------------------------------------------

    /**
     * Resolve this entry's chapters + transcript pointer in the background and hand them to
     * [com.toolsboox.ui.plugin.LedgerPlayer] — IF it is still on the same playback session by
     * the time the network comes back (a resolve racing a track change must lose quietly).
     */
    fun attachToPlayer(context: Context, entry: FeedEntry) {
        val appCtx = context.applicationContext
        val player = com.toolsboox.ui.plugin.LedgerPlayer
        val session = player.session
        Thread {
            val list = runCatching { resolve(appCtx, entry) }.getOrNull() ?: return@Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (player.session == session) {
                    player.setChapters(list.chapters)
                    player.transcriptProvider = { runCatching { transcript(appCtx, entry) }.getOrNull() }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- Internals -------------------------------------------------------------------------

    /** [matchItem]'s answer: did we REACH the feed at all (a 404 is reachable, a dead network
     *  isn't — only the latter blocks caching), and this entry's item if we found it. */
    private data class ItemLookup(val reachable: Boolean, val item: RssItem?)

    /** Fetch the feed XML and find THIS entry's item — by audio enclosure, then link, then guid. */
    private fun matchItem(feedUrl: String, entry: FeedEntry): ItemLookup {
        val feed = http.newCall(Request.Builder().url(feedUrl).get().build()).execute().use { r ->
            if (!r.isSuccessful) return ItemLookup(reachable = true, item = null)
            r.body?.byteStream()?.let { RssParser.parse(it) }
        } ?: return ItemLookup(reachable = true, item = null)
        val audio = entry.enclosureAudio.orEmpty()
        val item = feed.items.firstOrNull { audio.isNotBlank() && it.enclosureAudio == audio }
            ?: feed.items.firstOrNull { entry.url.isNotBlank() && it.link == entry.url }
            ?: feed.items.firstOrNull { entry.url.isNotBlank() && it.guid == entry.url }
        return ItemLookup(reachable = true, item = item)
    }

    /** The podcastindex chapters document: {"chapters":[{"startTime":s,"title":"…"}]}. */
    private fun fetchChaptersJson(url: String): List<Chapter> {
        val body = http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        } ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("chapters") ?: return emptyList()
        val out = mutableListOf<Chapter>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val start = c.optDouble("startTime", -1.0)
            if (start < 0) continue
            val title = c.optString("title").trim().take(120)
            out += Chapter(start.toInt(), title.ifBlank { "—" })
            if (out.size >= 500) break
        }
        return out.sortedBy { it.startSec }
    }

    private fun chaptersFrom(meta: JSONObject): List<Chapter> {
        val arr = meta.optJSONArray("chapters") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Chapter(it.optInt("start"), it.optString("title")) }
        }
    }
}
