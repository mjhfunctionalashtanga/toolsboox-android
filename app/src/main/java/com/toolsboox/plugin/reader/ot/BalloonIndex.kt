package com.toolsboox.plugin.reader.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * 💬 THE BALLOON INDEX — Seeneva's speech-balloon detector, lifted, run the way Seeneva runs it:
 * IN THE BACKGROUND, CACHED — never at page-turn (the evaluation's law: ~0.5–2s per page on an
 * e-ink CPU is fine as indexing and unacceptable as navigation).
 *
 * Model: `yolo_seeneva.tflite` from the Seeneva comic reader by Sergei Solodovnikov
 * (https://github.com/Seeneva/seeneva-reader-android, GPL-3.0-or-later — license-compatible with
 * this GPLv3 fork; attribution in THIRD-PARTY-NOTICES.md). YOLOv4-tiny, two classes: class 0
 * (SPEECH_BALLOON) is what the walk steps through; class 1 (PANEL) is STORED beside it since
 * slice 2 (Michael 2026-10-11) but not walked — the 2026-08-30 head-to-head showed its miss mode
 * is wrong crops, the one failure the panel walk's own detector never commits.
 *
 * The index is a local cache, not a wire: `filesDir/balloon-index/<md5 of the cbz1 key>.json`,
 * page-keyed by SPINE INDEX with rects normalized 0–1 against the page's own dimensions.
 *
 * THE KEY IS THE BOOK'S CONTENT, NOT ITS NAME (slice 2). It used to be the file name, so two
 * series with a "Chapter 1.cbz" shared one index, and a replaced scan kept the old rects. Now it
 * is [CbzDirectory.cbz1Key] — a hash of the page members' names, CRCs and sizes, read from the
 * zip directory the pass reads anyway — so every device computes the same key for the same
 * chapter, whatever folder it sits in. The reader talks to this object by its READER KEY — the
 * fleet key (`currentReadingId`, folder/name#size), falling back to the file name only when
 * there is none — so two series' "Chapter 1.cbz" never share a running pass (review
 * 2026-10-11). The cbz1 key is worked out on the pass thread, names the file (and, later, the
 * hub path), and is REMEMBERED per fleet key in a tiny `<md5>.alias` file beside the records: a
 * book already indexed finds its record without opening — let alone copying — its archive. (The
 * fleet key carries the file's size, so a re-download that changes the file learns its key anew.)
 *
 * THE SPINE is comic-book.js's: image names, extensions matched case-insensitively, in NATURAL
 * order with case kept ([CbzDirectory.pages] — `page2` before `page10`, `.JPG` pages present;
 * the iPad's order exactly). Drift here would
 * hand balloons to the wrong pages, so both sides change together and BalloonIndexTest and
 * scripts/test-comic-page-order.mjs pin the same vectors.
 *
 * WHY THE PASS IS SHAPED THE WAY IT IS (field, 09-10: "the balloons aren't really working"):
 * the pass runs when a book is OPENED, and a reader opens a book where he left it — page 23 of
 * 48. The old pass started at page 0 at the lowest thread priority, pushed every four pages,
 * and wrote its cache only when the WHOLE book was done; close the book first and every page it
 * had detected was thrown away. On an e-ink CPU that meant the walk never reached the page on
 * screen before the reader gave up, and the next open started from nothing. So now:
 *  - the pass detects THE PAGE ON SCREEN next ([ensure]'s `startAt`, re-read before every page —
 *    the directory gives random access, so a reader who jumps ahead is followed), working
 *    forward, then wrapping to the front;
 *  - every partial push is WRITTEN to the cache (`done: false`), and a later open RESUMES from
 *    it, skipping pages already indexed — no page is ever detected twice;
 *  - the first three pages push one by one (balloons in ~1 page-time, not four), then every four;
 *  - the thread runs a notch below normal, and the menu's "index now" door runs it a notch
 *    above ([ensure] `urgent`, [boost] for a pass already running);
 *  - progress is readable at any time ([progress]) so the Comic menu can SAY "indexing… (3/48
 *    pages)" instead of leaving the reader to guess.
 *
 * THE RECORD, VERSION 3 (slice 2 — the iPad's App/BalloonIndex.swift writes this same shape, so
 * a record from either device reads on the other once the hub carries them):
 * ```
 * { "v":3, "det":"seeneva-yolo@ed0c2a28", "key":"cbz1:…", "alias":"<fleet key>",
 *   "done":true, "updated":<ms>, "count":<balloons>, "files":<archive entries, folders out>,
 *   "pages":  {"0":[[x,y,w,h],…]},       // as before — the walk reads only this
 *   "text":   {"0":["HELLO THERE.",""]},  // parallel to pages[i], one line per printed line
 *   "panels": {"0":[[x,y,w,h],…]},       // YOLO class 1, stored for later
 *   "ocr":    {"0":"mlkit-latin"},        // page → the engine that read its words
 *   "failed": 1,                          // a COUNT, as every reader of it expects
 *   "failedPages": {"7":2},               // page → attempts; a failed page is never in pages
 *   "textFailed":  {"3":1} }              // rects kept, words missing; retried like a failure
 * ```
 * `det` is the YOLO model alone (the MD5 prefix of the asset, pinned by the test): a record is
 * reused only when `v` and `det` match, whatever text engine filled `text` — `ocr` is per page
 * (a record begun on the iPad and finished on the Boox carries both engines) and metadata only.
 * A page that fails is retried on the next open, up to [MAX_ATTEMPTS] times with this detector;
 * it never again impersonates an honest empty page.
 *
 * Threshold: the exported graph bakes its own NMS and score cut — measured 09-10 over 380
 * detections on 25 real pages, the lowest score it ever emits is 0.703. [PROB_MIN] is therefore
 * a belt on a graph that already wears braces; lowering it cannot surface more balloons, and
 * a page the walk shows whole is a page the model found nothing on.
 *
 * CBZ only: a CBR is a RAR archive, and there is no java.util.zip for RAR — foliate reads them
 * through its own unarchiver, but this indexer honestly skips them (the reader works as before;
 * there are simply no balloons to walk — and the menu row says so).
 */
object BalloonIndex {

    // v3 (slice 2, Michael 2026-10-11): the content key, the natural page order, the text and
    // panels, and failed pages kept OUT of `pages`. Every older record is re-indexed once — that
    // also redoes the old pages that a failure had saved as an honest-looking `[]`.
    internal const val VERSION = 3
    /** The YOLO model's identity — the first 8 hex of the asset's MD5 (pinned by BalloonIndexTest). */
    internal const val DET = "seeneva-yolo@ed0c2a28"
    /** Which engine read a page's words (the record's per-page `ocr`) — never a reason to re-index. */
    internal const val OCR = "mlkit-latin"
    /** Tries per failed page with this detector before the pass stops asking. */
    internal const val MAX_ATTEMPTS = 3
    private const val MODEL_ASSET = "yolo_seeneva.tflite"
    internal const val IN_W = 480
    internal const val IN_H = 736
    internal const val PROB_MIN = 0.70f          // Seeneva's own export threshold (see the header)
    internal const val BALLOON_CLASS = 0
    internal const val PANEL_CLASS = 1
    private const val MAX_DECODE_DIM = 1024     // detection needs nowhere near full resolution

    /** Where a running pass stands — `done` of `total` pages detected (resumed pages count). */
    data class Progress(val done: Int, val total: Int)

    /** One running pass per READER KEY. The callbacks are REPLACEABLE: a reader who closes and reopens
     *  the same book while its pass is still cooking gets the pushes, not the fragment that died. */
    private class Pass(var onReady: (String, Int, Int, Boolean) -> Unit, var onFail: ((String) -> Unit)?) {
        @Volatile var thread: Thread? = null
        @Volatile var progress = Progress(0, 0)
        @Volatile var latest: String? = null
    }

    private val passes = java.util.concurrent.ConcurrentHashMap<String, Pass>()

    /** The reader key → that book's cbz1 key: learned when a pass reads the directory, or read
     *  back from the remembered alias ([storedKey]). */
    private val contentKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The reader is leaving: the running pass keeps cooking, but stops holding its views. */
    fun detach(bookKey: String) {
        passes[bookKey]?.let { it.onReady = { _, _, _, _ -> }; it.onFail = null }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "balloon-index").apply { mkdirs() }

    /** The cache file for a key — the cbz1 key now; a v2 cache was named for the book key. */
    private fun cacheFile(ctx: Context, key: String): File {
        val id = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(ctx), "$id.json")
    }

    /** The finished index as the JS-ready JSON string, or null if this book isn't fully indexed. */
    fun cached(ctx: Context, bookKey: String): String? = partial(ctx, bookKey)
        ?.takeIf { runCatching { JSONObject(it).optBoolean("done") }.getOrDefault(false) }

    /** Whatever the cache holds for this book — finished OR the pages a cut-short pass got to.
     *  Answers at once for a book indexed before (its fleet key remembers its cbz1 key); null for
     *  a book no pass has ever read (the key is its content). */
    fun partial(ctx: Context, bookKey: String): String? =
        (contentKeys[bookKey] ?: storedKey(ctx, bookKey)?.also { contentKeys[bookKey] = it })
            ?.let { load(cacheFile(ctx, it))?.toString() }

    /** Where a fleet key's cbz1 key is remembered — `<md5 of the fleet key>.alias`. */
    private fun aliasFile(ctx: Context, alias: String): File {
        val id = MessageDigest.getInstance("MD5").digest(alias.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(ctx), "$id.alias")
    }

    /** The cbz1 key a pass learned for [alias] (a fleet key), if any pass ever read that book. */
    private fun storedKey(ctx: Context, alias: String): String? = runCatching {
        aliasFile(ctx, alias).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.startsWith("cbz1:") }
    }.getOrNull()

    private fun rememberKey(ctx: Context, alias: String, key: String) {
        if (storedKey(ctx, alias) == key) return
        runCatching { aliasFile(ctx, alias).writeText(key) }
    }

    /** A v3 record from this detector that knows anything — detected pages OR failed attempts
     *  (a book whose every page failed keeps its attempt counts instead of retrying forever). */
    internal fun usable(o: JSONObject): Boolean =
        o.optInt("v") == VERSION && o.optString("det") == DET &&
            ((o.optJSONObject("pages")?.length() ?: 0) > 0 || (o.optJSONObject("failedPages")?.length() ?: 0) > 0)

    private fun load(f: File): JSONObject? = runCatching {
        if (!f.exists()) return null
        JSONObject(f.readText()).takeIf { usable(it) }
    }.getOrNull()

    /** True while a pass is running for this book. */
    fun isIndexing(bookKey: String): Boolean = passes.containsKey(bookKey)

    /** The running pass's progress, or null when none runs (or it is still reading the directory). */
    fun progress(bookKey: String): Progress? = passes[bookKey]?.progress?.takeIf { it.total > 0 }

    /** Raise a running pass to the urgent priority (the menu's "index now" door on a book that
     *  is already cooking). No pass → nothing to raise; the caller's [ensure] starts one. */
    fun boost(bookKey: String) {
        runCatching { passes[bookKey]?.thread?.priority = Thread.NORM_PRIORITY + 1 }
    }

    /**
     * How many balloons the index knows on [page] (a spine index): null when that page has not
     * been detected yet — or failed (see [pageFailed]) — or [json] is null; else the count, zero
     * being an honest "none found".
     */
    fun pageBalloons(json: String?, page: Int): Int? {
        if (json == null || page < 0) return null
        return runCatching {
            JSONObject(json).optJSONObject("pages")?.optJSONArray(page.toString())?.length()
        }.getOrNull()
    }

    /** True when detection FAILED on [page] (it will be retried on a later open, up to
     *  [MAX_ATTEMPTS]) — so the reader can be told "failed", not "none found". */
    fun pageFailed(json: String?, page: Int): Boolean {
        if (json == null || page < 0) return false
        return runCatching {
            JSONObject(json).optJSONObject("failedPages")?.has(page.toString()) == true
        }.getOrDefault(false)
    }

    /** True when [page] failed and still has tries left with this detector — the next pass
     *  (the next open, or the menu's "index now" door) retries it. */
    fun pageRetryable(json: String?, page: Int): Boolean {
        if (json == null || page < 0) return false
        return runCatching {
            val n = JSONObject(json).optJSONObject("failedPages")?.optInt(page.toString(), 0) ?: 0
            n in 1 until MAX_ATTEMPTS
        }.getOrDefault(false)
    }

    /**
     * Index [bookKey] if it isn't already, then hand the JSON to [onReady] (off the main thread):
     * at once when cached (finished or partial), per partial push while a pass runs, and once at
     * completion (`freshlyIndexed = true` — only when this pass read pages never tried before, so
     * a quiet retry of a failed page doesn't toast every open). [open] yields a FRESH seekable
     * channel over the archive (see [CbzDirectory.open]). [alias] is the reader's fleet key,
     * written into the record for diagnostics. [startAt] is re-read before every page — the
     * page on screen is always the next one detected. [urgent] runs the pass above normal
     * priority (the menu door). Re-entrant safe: a book already being indexed keeps its one
     * pass, and this caller becomes the pass's listener. [bookKey] is the READER key (the fleet
     * key when there is one); [legacyKey] is the file name a v2 cache was named for, tidied away.
     *
     * Everything that touches the disk — the directory, the key, the cache — happens on the pass
     * thread, never the caller's. A FINISHED book is answered from its remembered key without
     * calling [open] at all: a provider that only streams would otherwise copy the whole CBZ into
     * the cache on every open just to learn what the last open already knew.
     */
    fun ensure(
        ctx: Context, bookKey: String, isCbz: Boolean,
        open: () -> CbzDirectory.Chan?,
        alias: String? = null,
        legacyKey: String? = null,
        startAt: () -> Int = { 0 },
        urgent: Boolean = false,
        /** A failure the READER should hear about (once) — TFLite refusing this device, an
         *  unreadable archive — instead of vanishing into a log (field, 08-30: the walks
         *  "don't zoom" and nothing ever said why). */
        onFail: ((reason: String) -> Unit)? = null,
        onReady: (json: String, pages: Int, balloons: Int, freshlyIndexed: Boolean) -> Unit,
    ) {
        passes[bookKey]?.let { running ->
            // A pass is already cooking this book: adopt it. The newest reader is the one who
            // should hear its pushes, and what it has so far lands now, not four pages later.
            running.onReady = onReady
            running.onFail = onFail
            if (urgent) boost(bookKey)
            running.latest?.let { onReady(it, tally(it).first, tally(it).second, false) }
            return
        }
        if (!isCbz) { onFail?.invoke("CBR archives can't be indexed yet"); return }
        val pass = Pass(onReady, onFail)
        if (passes.putIfAbsent(bookKey, pass) != null) return
        val t = Thread {
            try {
                // Known book: push its record now, before any open (or spool) of the archive —
                // and a finished one with nothing owed needs no pass at all.
                val known = alias?.let { storedKey(ctx, it) }?.let { k -> load(cacheFile(ctx, k))?.let { k to it } }
                if (known != null) {
                    contentKeys[bookKey] = known.first
                    val snapshot = known.second.toString()
                    pass.latest = snapshot
                    val (p, c) = tally(snapshot)
                    pass.onReady(snapshot, p, c, false)
                    if (known.second.optBoolean("done") && retryable(known.second).isEmpty()) return@Thread
                }
                val chan = open()
                if (chan == null) { pass.onFail?.invoke("couldn't open the book"); return@Thread }
                chan.use { runPass(ctx, bookKey, alias, legacyKey, it.channel, pass, startAt) }
            } catch (e: Throwable) {
                Timber.w(e, "balloon indexing failed")
                pass.onFail?.invoke(e.javaClass.simpleName + (e.message?.take(60)?.let { ": $it" } ?: ""))
            } finally {
                passes.remove(bookKey)
            }
        }.apply {
            isDaemon = true
            priority = if (urgent) Thread.NORM_PRIORITY + 1 else Thread.NORM_PRIORITY - 1
        }
        pass.thread = t
        t.start()
    }

    private fun tally(json: String): Pair<Int, Int> = runCatching {
        val o = JSONObject(json)
        (o.optJSONObject("pages")?.length() ?: 0) to o.optInt("count")
    }.getOrDefault(0 to 0)

    /** comic-book.js's spine for these names: image pages (extensions case-insensitive), natural order, case kept. */
    internal fun spineOrder(names: List<String>): Map<String, Int> =
        names.filter { CbzDirectory.isPageName(it) }
            .sortedWith { a, b -> CbzDirectory.naturalCompare(a, b) }
            .withIndex().associate { (i, n) -> n to i }

    /**
     * The order the pass DETECTS in: the page on screen first, forward to the end, then the
     * front of the book — a reader who resumed at page 23 sees page 23's balloons first, and
     * a walk forward never waits on pages already behind it. Pages in [skip] were detected by
     * an earlier, cut-short pass (or this one) and are not detected again.
     */
    internal fun detectionOrder(total: Int, startAt: Int, skip: Set<Int>): List<Int> {
        if (total <= 0) return emptyList()
        val s = startAt.coerceIn(0, total - 1)
        return ((s until total) + (0 until s)).filter { it !in skip }
    }

    /** What an earlier record says about a page's attempts (0 when it says nothing). */
    private fun attempts(o: JSONObject, k: String) = o.optInt(k, 0)

    /**
     * Pages a resumed record still owes: failed pages and pages whose words failed, each under
     * [MAX_ATTEMPTS] tries. Pure, so the retry rule is pinned by the test.
     */
    internal fun retryable(record: JSONObject): Set<Int> {
        val out = mutableSetOf<Int>()
        for (field in listOf("failedPages", "textFailed")) {
            val m = record.optJSONObject(field) ?: continue
            for (k in m.keys()) {
                val i = k.toIntOrNull() ?: continue
                if (m.optInt(k, 0) < MAX_ATTEMPTS) out += i
            }
        }
        return out
    }

    /** Every page has an outcome — detected, or failed (retryable or not). */
    internal fun complete(record: JSONObject, total: Int): Boolean {
        val pages = record.optJSONObject("pages") ?: JSONObject()
        val failed = record.optJSONObject("failedPages") ?: JSONObject()
        return (0 until total).all { pages.has(it.toString()) || failed.has(it.toString()) }
    }

    /** One page's outcome: balloons with their words, panels, and whether the words failed. */
    private class PageResult(val rects: JSONArray, val texts: JSONArray, val panels: JSONArray, val textFailed: Boolean)

    /**
     * The pass, over one open channel: read the directory, work out the content key, push what
     * the cache already holds, then detect what is still owed — the page on screen first, every
     * page caught and COUNTED on its own (field, 08-30: the v1 detector threw on every page and
     * a runCatching read it as "no balloons" — a failure must never impersonate an honest empty
     * page again). ONE detector and ONE text recogniser for the whole pass, closed in `finally`
     * (the old text fallback opened a recogniser per page and never closed it).
     */
    private fun runPass(
        ctx: Context, bookKey: String, alias: String?, legacyKey: String?, ch: java.nio.channels.FileChannel,
        pass: Pass, startAtNow: () -> Int,
    ) {
        val members = CbzDirectory.read(ch)
        val spine = CbzDirectory.pages(members)
        val key = CbzDirectory.cbz1Key(members)
        if (spine.isEmpty() || key == null) { pass.onFail?.invoke("couldn't read the book's pages"); return }
        contentKeys[bookKey] = key
        if (alias != null) rememberKey(ctx, alias, key)
        val total = spine.size
        val files = members.count { !it.rawName.let { r -> r.isNotEmpty() && r.last() == '/'.code.toByte() } }
        val file = cacheFile(ctx, key)
        // The v2 cache was named for the file name; v3 never reads it. Tidy it away.
        if (legacyKey != null) runCatching { cacheFile(ctx, legacyKey).takeIf { it != file && it.exists() }?.delete() }

        val have = load(file)
        val rec = have ?: JSONObject()
        val pages = rec.optJSONObject("pages") ?: JSONObject()
        val text = rec.optJSONObject("text") ?: JSONObject()
        val panels = rec.optJSONObject("panels") ?: JSONObject()
        val ocr = rec.optJSONObject("ocr") ?: JSONObject()
        val failedPages = rec.optJSONObject("failedPages") ?: JSONObject()
        val textFailed = rec.optJSONObject("textFailed") ?: JSONObject()
        // Out-of-range keys (a belt — the key already pins the page list) never survive a resume.
        for (m in listOf(pages, text, panels, ocr, failedPages, textFailed)) {
            m.keys().asSequence().toList().forEach { k -> if ((k.toIntOrNull() ?: -1) !in 0 until total) m.remove(k) }
        }
        val retry = if (have != null) retryable(have) else emptySet()

        fun encode(done: Boolean): String {
            var count = 0
            for (k in pages.keys()) count += pages.optJSONArray(k)?.length() ?: 0
            return JSONObject().put("v", VERSION).put("det", DET)
                .put("key", key).apply { if (alias != null) put("alias", alias) }
                .put("done", done).put("updated", System.currentTimeMillis())
                .put("count", count).put("files", files)
                .put("failed", failedPages.length())
                .put("pages", pages).put("text", text).put("panels", panels).put("ocr", ocr)
                .put("failedPages", failedPages).put("textFailed", textFailed)
                .toString()
        }

        if (have != null) {
            val snapshot = have.toString()
            // Already pushed before the archive was opened (the remembered key)? Don't re-lay twice.
            if (pass.latest != snapshot) {
                pass.latest = snapshot
                val (p, c) = tally(snapshot)
                pass.onReady(snapshot, p, c, false)
            }
            if (complete(have, total) && retry.isEmpty()) return
            // A cut-short pass left its pages behind, or some pages are owed a retry — resume.
        }

        // Settled = nothing more to do this pass: detected (words fine or out of tries), failed
        // for good, or already tried once by this pass. Everything else is owed, the page on
        // screen first.
        val settled = mutableSetOf<Int>()
        for (i in 0 until total) {
            val k = i.toString()
            if ((pages.has(k) || failedPages.has(k)) && i !in retry) settled += i
        }
        var fresh = 0          // pages THIS pass detected — the push cadence counts these
        var firstTime = 0      // pages no earlier pass had tried — only these earn the toast
        var sincePush = 0
        pass.progress = Progress(settled.size, total)
        val model = Detector(ctx)
        // A text recogniser that can't start costs the WORDS, never the balloons: every page then
        // records a text failure (retried like any other) and YOLO's rects still land.
        val reader = runCatching {
            com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        }.onFailure { Timber.w(it, "BalloonIndex: no text recogniser") }.getOrNull()
        try {
            while (true) {
                // Re-read the page on screen before EVERY page: a reader who jumped ahead is followed.
                val next = detectionOrder(total, startAtNow(), settled).firstOrNull() ?: break
                val k = next.toString()
                val known = pages.has(k) || failedPages.has(k) || textFailed.has(k)
                val result = runCatching {
                    // Read INSIDE the per-page catch: one unreadable member fails one page, not the pass.
                    val bytes = CbzDirectory.bytes(ch, spine[next]) ?: error("unreadable page ${spine[next].name}")
                    detectPage(model, reader, bytes)
                }
                result.onSuccess { r ->
                    pages.put(k, r.rects); text.put(k, r.texts); panels.put(k, r.panels)
                    failedPages.remove(k)
                    if (r.textFailed) { textFailed.put(k, attempts(textFailed, k) + 1); ocr.remove(k) }
                    else { textFailed.remove(k); ocr.put(k, OCR) }
                }.onFailure { e ->
                    Timber.w(e, "balloon detect failed on ${spine[next].name}")
                    // A page retried for its WORDS keeps the rects it already had.
                    if (pages.has(k)) textFailed.put(k, attempts(textFailed, k) + 1)
                    else failedPages.put(k, attempts(failedPages, k) + 1)
                }
                settled += next
                fresh++
                if (!known) firstTime++
                pass.progress = Progress(settled.size, total)
                val owed = settled.size < total
                // The first three pages land one by one — the page on screen and its
                // neighbours — then every four (each push is a JS re-lay and a file write).
                // PARTIAL PUSHES RIDE THE PASS (field, 08-30) and are WRITTEN DOWN, so a close
                // mid-pass loses nothing; the directory fixed every page's index before the
                // first detection, so a partial can never hand balloons to the wrong page.
                if ((fresh <= 3 || ++sincePush >= 4) && owed) {
                    sincePush = 0
                    val snapshot = encode(false)
                    pass.latest = snapshot
                    runCatching { file.writeText(snapshot) }
                    val (p, c) = tally(snapshot)
                    pass.onReady(snapshot, p, c, false)
                }
            }
        } finally {
            model.close()
            runCatching { reader?.close() }
        }
        val json = encode(complete(JSONObject().put("pages", pages).put("failedPages", failedPages), total))
        file.writeText(json)
        pass.latest = json
        val (p, c) = tally(json)
        pass.onReady(json, p, c, firstTime > 0)
    }

    /** Both detectors on one page: YOLO's balloons and panels, the page's text read once, the two
     *  combined by [BalloonGeometry.walkStops] — the lines become YOLO's balloons' words, and only
     *  on a page YOLO found nothing on do they become the stops. Throws when the page can't be
     *  decoded, or when YOLO saw nothing AND the text read failed — that page is not known to be
     *  empty. */
    private fun detectPage(
        model: Detector, reader: com.google.mlkit.vision.text.TextRecognizer?, imageBytes: ByteArray,
    ): PageResult {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
        val w0 = opts.outWidth
        val h0 = opts.outHeight
        if (w0 <= 0 || h0 <= 0) error("not a decodable image")
        var sample = 1
        while (maxOf(w0, h0) / (sample * 2) >= MAX_DECODE_DIM) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("decode failed")
        try {
            val (found, panels) = model.run(bmp)
            // THE WORDS ARE THE BALLOON (Michael, 09-13, a Western comic on the Palma: "the comic
            // wasn't able to read any balloons"). Seeneva's detector learned manga; a DC page
            // with irregular balloons and painted colour can read as nothing to it. So the
            // page's TEXT runs on every page now (slice 2): it fills each YOLO balloon's words
            // and, on a page YOLO found nothing on, IS the walk — ML Kit's bundled Latin
            // recogniser, already in the app for the panel OCR, at the same decode the detector
            // used. On a page YOLO did read, stray lettering (a sound effect, a caption) adds no
            // tap (the iPad's review, 2026-10-11: BalloonGeometry.walkStops on both apps).
            val lines = reader?.let { readLines(it, bmp) }
                ?: Result.failure(IllegalStateException("no text recogniser"))
            if (lines.isFailure && found.isEmpty()) throw lines.exceptionOrNull()!!
            val merged = BalloonGeometry.walkStops(found, lines.getOrDefault(emptyList()),
                bmp.width.toFloat(), bmp.height.toFloat())
            val rects = JSONArray()
            for (r in merged.rects) rects.put(rectJson(r))
            val texts = JSONArray()
            for (t in merged.texts) texts.put(t)
            val panelJson = JSONArray()
            for (r in panels) panelJson.put(rectJson(r))
            return PageResult(rects, texts, panelJson, lines.isFailure)
        } finally {
            bmp.recycle()
        }
    }

    private fun rectJson(r: FloatArray) = JSONArray()
        .put(r[0].toDouble()).put(r[1].toDouble()).put(r[2].toDouble()).put(r[3].toDouble())

    /** The page's printed LINES, in bitmap pixels — lines, not blocks, so a block that straddles
     *  two balloons splits between them (the iPad reads lines too). A FAILURE is a failure (a
     *  Result), never an empty page — the 20 s timeout included (audit: the old fallback
     *  swallowed it as "none"). */
    private fun readLines(
        reader: com.google.mlkit.vision.text.TextRecognizer, bmp: Bitmap,
    ): Result<List<BalloonGeometry.Line>> = runCatching {
        val text = com.google.android.gms.tasks.Tasks.await(
            reader.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)),
            20, java.util.concurrent.TimeUnit.SECONDS)
        text.textBlocks.flatMap { it.lines }.mapNotNull { ln ->
            val bb = ln.boundingBox ?: return@mapNotNull null
            BalloonGeometry.Line(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat(), ln.text)
        }
    }.onFailure { Timber.w(it, "BalloonIndex: text read failed") }

    /** The aspect-preserving fit of a w×h page into the 480×736 canvas (top-left, zero-padded). */
    internal fun fitted(w: Int, h: Int): Pair<Int, Int> {
        val r = minOf(IN_W.toFloat() / w, IN_H.toFloat() / h)
        return maxOf(1, Math.round(w * r)) to maxOf(1, Math.round(h * r))
    }

    /**
     * The graph's raw outputs → page-relative balloon rects [x, y, w, h] in 0–1. Pure, so the
     * unit test can pin it against tensors recorded from the reference interpreter on a fixture
     * page. [boxes] rows are the graph's (y0, x0, y1, x1, prob) on the PADDED canvas; [nw]×[nh]
     * is how much of that canvas the page filled (see [fitted]) — dividing by that fraction is
     * what un-pads the coordinates into the page's own 0–1. [wantClass] picks balloons (class 0,
     * the default) or panels (class 1, stored since slice 2).
     */
    internal fun postprocess(
        boxes: Array<FloatArray>, classes: IntArray, count: Int, nw: Int, nh: Int,
        wantClass: Int = BALLOON_CLASS,
    ): JSONArray {
        val out = JSONArray()
        for (r in rects(boxes, classes, count, nw, nh, wantClass)) out.put(rectJson(r))
        return out
    }

    /** [postprocess] as plain rects — what the pass combines with the page's text. */
    internal fun rects(
        boxes: Array<FloatArray>, classes: IntArray, count: Int, nw: Int, nh: Int, wantClass: Int,
    ): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val valid = count.coerceIn(0, minOf(boxes.size, classes.size))
        for (i in 0 until valid) {
            val p = boxes[i][4]
            if (p < PROB_MIN || classes[i] != wantClass) continue
            val x0 = (boxes[i][1] * IN_W / nw).coerceIn(0f, 1f)
            val x1 = (boxes[i][3] * IN_W / nw).coerceIn(0f, 1f)
            val y0 = (boxes[i][0] * IN_H / nh).coerceIn(0f, 1f)
            val y1 = (boxes[i][2] * IN_H / nh).coerceIn(0f, 1f)
            if (x1 <= x0 || y1 <= y0) continue
            out += floatArrayOf(x0, y0, x1 - x0, y1 - y0)
        }
        return out
    }

    /**
     * The interpreter + Seeneva's exact preprocessing: aspect-preserving resize into 480×736,
     * placed top-left, zero-padded (their `copy_top_left`), /255 float RGB, batch slot 0 of 3.
     * Outputs (NMS already baked into the graph): boxes float32 (3,50,5) y/x-min/max+prob,
     * classes INT32 (3,50), valid counts INT32 (3) — found by TYPE and SHAPE, never by position.
     */
    private class Detector(ctx: Context) {
        private val interpreter: org.tensorflow.lite.Interpreter
        private val boxesIdx: Int
        private val classesIdx: Int
        private val countsIdx: Int
        init {
            val fd = ctx.assets.openFd(MODEL_ASSET)
            val mapped = fd.createInputStream().channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = org.tensorflow.lite.Interpreter(
                mapped, org.tensorflow.lite.Interpreter.Options().setNumThreads(2))
            // The -52 lesson, made structural (as the iOS twin already had it): classes and
            // counts are INT32, boxes FLOAT32 — a pass that trusted output ORDER would break the
            // day a re-export shuffled it, and break silently (every page "balloon-less").
            var b = -1; var c = -1; var n = -1
            for (i in 0 until interpreter.outputTensorCount) {
                val t = interpreter.getOutputTensor(i)
                val elems = t.shape().fold(1) { acc, d -> acc * d }
                when {
                    t.dataType() == org.tensorflow.lite.DataType.FLOAT32 && elems == 3 * 50 * 5 -> b = i
                    t.dataType() == org.tensorflow.lite.DataType.INT32 && elems == 3 * 50 -> c = i
                    t.dataType() == org.tensorflow.lite.DataType.INT32 && elems == 3 -> n = i
                }
            }
            if (b < 0 || c < 0 || n < 0) {
                interpreter.close()
                throw IllegalStateException("unexpected model outputs")
            }
            boxesIdx = b; classesIdx = c; countsIdx = n
        }

        // One 12.7 MB direct buffer for the life of the detector, not one per page (a 200-page
        // pass churned 2.5 GB of native memory on a 2 GB tablet — audit 09-11).
        private val inputBuf = java.nio.ByteBuffer.allocateDirect(3 * IN_H * IN_W * 3 * 4)
            .order(java.nio.ByteOrder.nativeOrder())

        /** Balloons (class 0) and panels (class 1) on this page, as page-relative rects. */
        fun run(bmp: Bitmap): Pair<List<FloatArray>, List<FloatArray>> {
            val (nw, nh) = fitted(bmp.width, bmp.height)
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            val input = inputBuf
            input.clear()
            // (No zero-fill pass first: the two loops below write every float of all three
            // slots — 12.7 million single-byte writes a page were pure waste on an e-ink CPU.)
            val px = IntArray(nw * nh)
            scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
            if (scaled !== bmp) scaled.recycle()
            // Slot 0 carries the page; slots 1–2 stay zero (the graph batches three strips, and a
            // zero image detects nothing — verified by the evaluation's reference runs).
            for (y in 0 until IN_H) for (x in 0 until IN_W) {
                if (y < nh && x < nw) {
                    val c = px[y * nw + x]
                    input.putFloat(((c shr 16) and 0xFF) / 255f)
                    input.putFloat(((c shr 8) and 0xFF) / 255f)
                    input.putFloat((c and 0xFF) / 255f)
                } else {
                    input.putFloat(0f); input.putFloat(0f); input.putFloat(0f)
                }
            }
            // Zero-fill batch slots 1 and 2.
            for (i in 0 until 2 * IN_H * IN_W * 3) input.putFloat(0f)
            input.rewind()

            val boxes = Array(3) { Array(50) { FloatArray(5) } }
            val classes = Array(3) { IntArray(50) }
            val counts = IntArray(3)
            interpreter.runForMultipleInputsOutputs(
                arrayOf(input), mapOf(boxesIdx to boxes, classesIdx to classes, countsIdx to counts))
            return rects(boxes[0], classes[0], counts[0], nw, nh, BALLOON_CLASS) to
                rects(boxes[0], classes[0], counts[0], nw, nh, PANEL_CLASS)
        }

        fun close() = runCatching { interpreter.close() }
    }
}
