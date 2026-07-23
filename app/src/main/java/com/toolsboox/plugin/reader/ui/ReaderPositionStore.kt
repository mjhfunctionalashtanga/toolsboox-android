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
        runCatching {
            // Atomic (temp+rename): positions.json is sync-authoritative — a truncated
            // write would push garbage to every device (the state.json lesson).
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Timber.w(it, "reader positions save failed") }.let {}

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

    /** Pull the remote map, merge per book (newest ts wins), save, and push.
     *  [onMerged] (background thread) receives the merged map once the pull lands — the
     *  reader uses it to RE-jump when another device's position turns out to be newer
     *  than the local spot it already resumed to. */
    fun sync(context: Context, onMerged: ((JSONObject) -> Unit)? = null) {
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
            onMerged?.invoke(merged)
        }
    }
}
