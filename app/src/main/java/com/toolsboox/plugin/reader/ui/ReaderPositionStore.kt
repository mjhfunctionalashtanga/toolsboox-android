package com.toolsboox.plugin.reader.ui

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Where you left off in each book, portable across devices. Keyed by book NAME (not the device-local
 * file path, which differs per device) so a book synced onto two Boox resumes at the same spot.
 * Stored as filesDir/reader/positions.json ({ bookName: { cfi, ts } }) and round-tripped through
 * WebDAV — merged per book by timestamp (newest read wins).
 */
object ReaderPositionStore {
    private const val REMOTE = "reader/positions.json"
    private fun file(context: Context) =
        File(File(context.filesDir, "reader").apply { mkdirs() }, "positions.json")

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject())

    private fun write(context: Context, obj: JSONObject) =
        runCatching { file(context).writeText(obj.toString()) }
            .onFailure { Timber.w(it, "reader positions save failed") }.let {}

    /** The saved CFI for [book], or null. */
    fun get(context: Context, book: String): String? =
        read(context).optJSONObject(book)?.optString("cfi")?.takeIf { it.isNotBlank() }

    /** Record the current [cfi] for [book] (with a timestamp) and push the merged map. */
    fun set(context: Context, book: String, cfi: String) {
        if (book.isBlank() || cfi.isBlank()) return
        val obj = read(context)
        obj.put(book, JSONObject().put("cfi", cfi).put("ts", System.currentTimeMillis()))
        write(context, obj)
        sync(context)
    }

    /** Pull the remote map, merge per book (newest ts wins), save, and push. Fire-and-forget. */
    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = read(context)
            val remoteText = LedgerSidecarSync.pull(context, REMOTE)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { JSONObject(remoteText) }.getOrDefault(JSONObject())
                val out = JSONObject()
                for (key in (local.keys().asSequence() + remote.keys().asSequence()).toSet()) {
                    val l = local.optJSONObject(key); val r = remote.optJSONObject(key)
                    out.put(key, when {
                        l == null -> r
                        r == null -> l
                        r.optLong("ts") > l.optLong("ts") -> r
                        else -> l
                    })
                }
                out
            }
            write(context, merged)
            LedgerSidecarSync.push(context, REMOTE, merged.toString())
        }
    }
}
