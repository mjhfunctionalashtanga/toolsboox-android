package com.toolsboox.plugin.reader.ui

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * WHEN a book was opened — the history the shelf's period filter reads.
 *
 * Michael, 2026-08-05: "use the almanac nav at the top of the bookshelf and use that to filter
 * books opened on that filtered period at the top, then another listing order."
 *
 * That needs something the shelf did not have: a record of when you READ a thing, as opposed to
 * when the file changed. The old proxy was `File.setLastModified` on open — a hack that worked only
 * because the shelf was app-owned files. It cannot survive the library becoming a pointer: you
 * cannot stamp a file inside a declared tree, and you should not want to. Touching Michael's own
 * Calibre library to record that Ledger looked at something would make every book appear freshly
 * changed to Syncthing, and push a whole library across the mesh for a read.
 *
 * So opens are recorded HERE, keyed by the book's shelf name, and the library is left alone.
 */
object BookOpens {

    private const val PREFS = "ledger_book_opens"

    /** The synced sidecar (hub root): `[{"name": "...", "opens": [ms…]}]` — the 08-11 design's
     *  unbuilt promise ("BookOpens union in the same sidecar so the almanac band agrees
     *  fleet-wide"), built under his 08-27 stats ruling. Merge = per-name union of opens,
     *  newest-first, capped — an open is a fact that happened; facts union. The old
     *  SharedPreferences store stays as the local seed and read-fallback: nothing migrates,
     *  nothing is lost, and an old build keeps writing prefs this build still reads. */
    private const val SYNC_PATH = "reading-opens.json"

    /** Newest-first opens, capped. A shelf history is for finding what you were reading lately —
     *  beyond a few dozen visits to one book the older ones answer no question anyone asks. */
    private const val MAX_PER_BOOK = 40

    /** Record that [name] was opened now — prefs (legacy seed) + the synced file, push queued. */
    fun record(context: Context, name: String, at: Long = System.currentTimeMillis()) {
        if (name.isBlank()) return
        val p = context.getSharedPreferences(PREFS, 0)
        val kept = (listOf(at) + opens(context, name)).distinct().sortedDescending().take(MAX_PER_BOOK)
        p.edit().putString(key(name), kept.joinToString(",")).apply()
        val app = context.applicationContext
        com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.background {
            runCatching {
                val local = readSyncFile(app)
                local[name] = kept
                writeSyncFile(app, local)
                sync(app)
            }
        }
    }

    /** Every recorded open of [name], newest first — the synced fleet record unioned with the
     *  legacy prefs, so the almanac band agrees across devices without a migration. */
    fun opens(context: Context, name: String): List<Long> {
        val local = context.getSharedPreferences(PREFS, 0).getString(key(name), null)
            ?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        val synced = runCatching { readSyncFile(context)[name] }.getOrNull() ?: emptyList()
        return (local + synced).distinct().sortedDescending().take(MAX_PER_BOOK)
    }

    // ── The synced sidecar ────────────────────────────────────────────────────────────────────

    private fun syncFile(context: Context) = java.io.File(context.filesDir, SYNC_PATH)

    // In-memory mirror of the synced file: [opens] runs once per shelf ROW when the period band
    // filters, and a JSON file read per row would put disk behind the shelf render — the thing
    // SharedPreferences' own cache always spared us. Invalidated by every write path.
    @Volatile private var cache: LinkedHashMap<String, List<Long>>? = null

    private fun readSyncFile(context: Context): LinkedHashMap<String, List<Long>> {
        cache?.let { return it }
        val f = syncFile(context)
        val parsed = if (!f.exists()) LinkedHashMap()
        else runCatching { parse(f.readText()) }.getOrNull() ?: LinkedHashMap()
        cache = parsed
        return parsed
    }

    private fun writeSyncFile(context: Context, map: Map<String, List<Long>>) {
        cache = LinkedHashMap(map)
        runCatching { syncFile(context).writeText(render(map)) }
    }

    /** Pull → union-merge → save → push-if-changed; called from [record]'s background hop and
     *  from the library hub's pass. Pure merge in [mergeOpens], pinned by its own test. */
    fun sync(context: Context) {
        val remoteText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.pull(context, SYNC_PATH)
        val remote = if (remoteText.isNullOrBlank()) LinkedHashMap()
        else runCatching { parse(remoteText) }.getOrNull() ?: LinkedHashMap()
        val merged = mergeOpens(readSyncFile(context), remote)
        writeSyncFile(context, merged)
        if (merged != remote) {
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, SYNC_PATH, render(merged))
        }
    }

    /** Per-name union, newest first, capped — an open happened; two devices' facts add. */
    fun mergeOpens(
        a: Map<String, List<Long>>, b: Map<String, List<Long>>
    ): LinkedHashMap<String, List<Long>> {
        val out = LinkedHashMap<String, List<Long>>()
        for (k in a.keys + b.keys) {
            out[k] = ((a[k] ?: emptyList()) + (b[k] ?: emptyList()))
                .distinct().sortedDescending().take(MAX_PER_BOOK)
        }
        return out
    }

    private fun parse(text: String): LinkedHashMap<String, List<Long>> {
        val out = LinkedHashMap<String, List<Long>>()
        val arr = org.json.JSONArray(text)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            if (name.isBlank()) continue
            val opens = o.optJSONArray("opens") ?: continue
            out[name] = (0 until opens.length()).map { opens.getLong(it) }
        }
        return out
    }

    private fun render(map: Map<String, List<Long>>): String {
        val arr = org.json.JSONArray()
        for ((name, opens) in map) {
            arr.put(org.json.JSONObject().put("name", name)
                .put("opens", org.json.JSONArray(opens)))
        }
        return arr.toString()
    }

    /** When [name] was last opened, or null if never. */
    fun lastOpen(context: Context, name: String): Long? = opens(context, name).firstOrNull()

    /**
     * Every book the fleet has recorded an open for, with its opens — the synced rows unioned with
     * the legacy per-book prefs. Read by the year review, which asks "which books were opened in
     * this span" rather than "when was THIS book opened".
     */
    fun all(context: Context): Map<String, List<Long>> {
        val out = LinkedHashMap<String, List<Long>>()
        for ((name, opens) in runCatching { readSyncFile(context) }.getOrDefault(LinkedHashMap())) out[name] = opens
        val p = context.getSharedPreferences(PREFS, 0)
        for (k in p.all.keys) {
            if (!k.startsWith("open:")) continue
            val name = k.removePrefix("open:")
            if (name in out) continue
            out[name] = opens(context, name)
        }
        return out
    }

    /**
     * The books opened between [from] and [to] inclusive, most-recently-opened first.
     *
     * Takes the shelf's own [entries] rather than reading the store's whole keyset, so a book
     * opened once and since deleted from the library cannot haunt the list — the history is a fact
     * about reading, the shelf is the fact about what exists, and the shelf wins.
     */
    fun openedBetween(
        context: Context, entries: List<BookshelfSource.Entry>, from: LocalDate, to: LocalDate,
    ): List<BookshelfSource.Entry> {
        val zone = ZoneId.systemDefault()
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return entries
            .mapNotNull { e ->
                val hit = opens(context, e.name).firstOrNull { it in start until end } ?: return@mapNotNull null
                e to hit
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** The day an epoch-millis open falls on, for grouping. */
    fun dayOf(at: Long): LocalDate =
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

    // Names carry slashes (a subfolder) and spaces; SharedPreferences keys tolerate both, but a
    // prefix keeps the store readable and makes a future migration greppable.
    private fun key(name: String) = "open:$name"
}
