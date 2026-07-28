package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerPaths
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/** One entry in the Field Ledger archive — 25 years of posts off michaeljoelhall.com. */
data class JournalItem(
    val id: Int,
    /** 20031225 — the date as YYYYMMDD; the join key that gives every entry its place in the ledger. */
    val dateYMD: Int,
    val title: String,
    val permalink: String,
    /** Already-hosted media (R2 / wp-content) — referenced, never copied. */
    val imageUrl: String
) {
    val year get() = dateYMD / 10_000
    val month get() = (dateYMD / 100) % 100
    val day get() = dateYMD % 100
    val dateString: String get() = String.format("%04d-%02d-%02d", year, month, day)
}

/**
 * The archive, on the day it happened.
 *
 * The iPad reads the same index for SEMANTIC search and so must decode its vectors. The Boox wants
 * one much smaller thing — "what did I post on this day" — so this reads the header, SKIPS the
 * vector block entirely, and inflates only the metadata. That's a few hundred KB of JSON instead of
 * a 7 MB float array, which is the difference between practical and not on e-ink.
 *
 * Binary layout, matching the VPS builder and the iPad reader:
 *
 *     "MJHJIDX1"  8 bytes magic
 *     count       u32   number of entries
 *     dim         u32   vector width
 *     compLen     u32   compressed metadata length
 *     rawLen      u32   uncompressed metadata length
 *     vectors     count * dim * 2 bytes (float16) — skipped here
 *     metadata    compLen bytes, RAW deflate, of a JSON array
 *
 * Cached on disk and refreshed weekly. Failure is silent everywhere: no index means no strip, never
 * an error — the archive is an enrichment to a page that works without it.
 */
object JournalCorpus {

    private const val INDEX_URL =
        "https://pub-477a42ee397942aa87e96485ef790f2c.r2.dev/ledger-journal/journal-index.bin"
    private const val PREFS = "ledger_journal"
    private const val LAST_CHECK = "last_check"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    /** An index this size is damaged, not big. */
    private const val MAX_BYTES = 64 * 1024 * 1024

    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val lock = Any()

    @Volatile private var items: List<JournalItem>? = null

    private fun cacheFile(context: Context) =
        File(File(LedgerPaths.documentsRoot(context), "journal").apply { mkdirs() }, "journal-index.bin")

    val isLoaded: Boolean get() = items != null

    /**
     * Everything posted on ONE day, oldest id first.
     *
     * Blocking on first use (it may read and inflate the cached index) — call from Dispatchers.IO.
     */
    fun itemsOn(context: Context, date: LocalDate): List<JournalItem> {
        val ymd = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
        return ensureLoaded(context).filter { it.dateYMD == ymd }.sortedBy { it.id }
    }

    /** The whole archive, loading it once. */
    fun ensureLoaded(context: Context): List<JournalItem> {
        items?.let { return it }
        synchronized(lock) {
            items?.let { return it }
            val f = cacheFile(context)
            val parsed = if (f.exists()) parse(f.readBytes()) else null
            val out = parsed ?: emptyList()
            items = out
            return out
        }
    }

    /**
     * Download the index if it's missing or a week stale. Blocking; call off the main thread.
     * Returns true when the cache changed, so a caller can redraw.
     */
    fun refreshIfStale(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val f = cacheFile(context)
        val last = prefs.getLong(LAST_CHECK, 0L)
        if (f.exists() && System.currentTimeMillis() - last < REFRESH_MS) return false
        return runCatching {
            val resp = client.newCall(Request.Builder().url(INDEX_URL).build()).execute()
            resp.use {
                if (!it.isSuccessful) return false
                val bytes = it.body?.bytes() ?: return false
                if (bytes.size > MAX_BYTES) return false
                // Parse BEFORE replacing the cache: a truncated or changed-format download must not
                // take out a working index.
                val fresh = parse(bytes) ?: return false
                f.writeBytes(bytes)
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
                synchronized(lock) { items = fresh }
                true
            }
        }.getOrDefault(false)
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun parse(data: ByteArray): List<JournalItem>? {
        if (data.size < 24) return null
        if (String(data, 0, 8, Charsets.US_ASCII) != "MJHJIDX1") return null
        val count = u32(data, 8)
        val dim = u32(data, 12)
        val compLen = u32(data, 16)
        val rawLen = u32(data, 20)
        if (count <= 0 || dim <= 0 || compLen <= 0 || rawLen <= 0) return null
        val vecBytes = count.toLong() * dim.toLong() * 2L
        val metaStart = 24L + vecBytes
        if (metaStart + compLen > data.size) return null

        val json = inflate(data, metaStart.toInt(), compLen, rawLen) ?: return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                JournalItem(
                    id = o.optInt("i"),
                    dateYMD = o.optInt("d"),
                    title = o.optString("t"),
                    permalink = o.optString("p"),
                    imageUrl = o.optString("m")
                )
            }
        }.getOrNull()
    }

    /** RAW deflate (no zlib header) — `Inflater(true)`, matching the builder's output. */
    private fun inflate(data: ByteArray, offset: Int, length: Int, expected: Int): String? = runCatching {
        val inf = Inflater(true)
        inf.setInput(data, offset, length)
        val out = ByteArray(expected)
        var written = 0
        while (!inf.finished() && written < expected) {
            val n = inf.inflate(out, written, expected - written)
            if (n == 0) break
            written += n
        }
        inf.end()
        if (written == 0) null else String(out, 0, written, Charsets.UTF_8)
    }.getOrNull()
}
