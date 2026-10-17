package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE NET'S BOOKMARKS — a KEPT list (Michael, 09-03: "the net browser needs to be able to keep
 * its bookmarks list"), which on this fleet means a SYNCED sidecar (`web-bookmarks.json` at the
 * hub root), id-union merged by URL with tombstones — the ListenState / LocalFeed pattern. A
 * bookmark saved on the Boox is on the iPad; survives a reinstall; ordered by title.
 */
object WebBookmarkStore {
    private const val PREFS = "browser_room"
    private const val KEY = "bookmarks_v2"
    private const val SYNC_PATH = "web-bookmarks.json"

    data class Mark(val url: String, val title: String, val updated: Long, val deletedAt: Long) {
        val isDeleted: Boolean get() = deletedAt > 0L
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readAll(ctx: Context): List<Mark> {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Mark(it.getString("url"), it.optString("title"),
                it.optLong("updated", 0L), it.optLong("deletedAt", 0L))
        }
    }

    private fun writeAll(ctx: Context, marks: List<Mark>) {
        val arr = JSONArray()
        marks.forEach {
            arr.put(JSONObject().put("url", it.url).put("title", it.title)
                .put("updated", it.updated).put("deletedAt", it.deletedAt))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Live bookmarks (tombstones filtered). */
    fun all(ctx: Context): List<Mark> = readAll(ctx).filter { !it.isDeleted }

    /** Add or remove [url]; remove is a tombstone so the delete syncs. */
    fun set(ctx: Context, title: String, url: String, keep: Boolean) {
        val now = System.currentTimeMillis()
        val rest = readAll(ctx).filterNot { it.url == url }
        val next = if (keep) rest + Mark(url, title, now, 0L)
        else rest + Mark(url, title, now, now)
        writeAll(ctx, next)
        sync(ctx)
    }

    /** Pull, union BY URL (newest updated wins, tombstones survive), save, push. */
    fun sync(ctx: Context, onMerged: ((List<Mark>) -> Unit)? = null) {
        LedgerSidecarSync.background {
            val local = readAll(ctx)
            val remoteText = LedgerSidecarSync.pull(ctx, SYNC_PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val rArr = runCatching { JSONArray(remoteText) }.getOrDefault(JSONArray())
                val remote = (0 until rArr.length()).map { rArr.getJSONObject(it) }.map {
                    Mark(it.getString("url"), it.optString("title"),
                        it.optLong("updated", 0L), it.optLong("deletedAt", 0L))
                }
                (local + remote).groupBy { it.url }.map { (_, v) -> v.maxByOrNull { it.updated }!! }
            }
            writeAll(ctx, merged)
            val out = JSONArray()
            merged.forEach {
                out.put(JSONObject().put("url", it.url).put("title", it.title)
                    .put("updated", it.updated).put("deletedAt", it.deletedAt))
            }
            LedgerSidecarSync.push(ctx, SYNC_PATH, out.toString())
            onMerged?.invoke(merged.filter { !it.isDeleted })
        }
    }
}
