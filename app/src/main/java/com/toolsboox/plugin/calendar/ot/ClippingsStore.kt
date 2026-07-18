package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.Clipping
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * The Clippings library: every saved clip-art asset in ONE synced sidecar file
 * (`clippings/clippings.json`), id-union merged over the same Ultrabridge WebDAV credential as
 * contacts + the day tree. Mirrors [ContactStore]. Wire-compatible with the iOS ClippingsStore.
 */
object ClippingsStore {
    private const val PATH = "clippings/clippings.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<Clipping>>(Types.newParameterizedType(List::class.java, Clipping::class.java))

    private fun file(context: Context) =
        File(context.filesDir, "clippings").apply { mkdirs() }.let { File(it, "clippings.json") }

    fun loadAll(context: Context): MutableList<Clipping> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    /** Live clippings (not tombstoned), newest first — what the picker shows. */
    fun list(context: Context): List<Clipping> =
        loadAll(context).filter { !it.isDeleted }.sortedByDescending { it.created }

    fun saveAll(context: Context, clippings: List<Clipping>) {
        runCatching { file(context).writeText(adapter.toJson(clippings)) }
            .onFailure { Timber.w(it, "clippings save failed") }
    }

    fun add(context: Context, data: String, label: String = "") {
        if (data.isBlank()) return
        val all = loadAll(context)
        all.add(Clipping(data = data, label = label))
        saveAll(context, all)
        sync(context)
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.apply { deletedAt = System.currentTimeMillis(); updated = deletedAt }
        saveAll(context, all)
        sync(context)
    }

    /** Round-trip the whole library through WebDAV so a clip saved on one device reaches the others. */
    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, Clipping>()
                for (c in local) byId[c.id] = c
                runCatching {
                    adapter.fromJson(remoteText)?.forEach { r ->
                        val cur = byId[r.id]
                        if (cur == null || r.updated > cur.updated) byId[r.id] = r
                    }
                }
                byId.values.toMutableList()
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
        }
    }
}
