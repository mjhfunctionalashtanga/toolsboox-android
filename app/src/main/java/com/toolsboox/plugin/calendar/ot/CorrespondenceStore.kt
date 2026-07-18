package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.CorrespondenceEntry
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * Global correspondence store: every logged interaction in ONE synced sidecar file
 * (`correspondence/correspondence.json`), id-union merged (newer `updated` wins per entry;
 * `deletedAt` tombstones survive) over the same Ultrabridge WebDAV credential as the day tree.
 * Mirrors [ContactStore] exactly — a single global file, not per-day. Wire-compatible with the iOS
 * CorrespondenceStore.
 */
object CorrespondenceStore {
    private const val PATH = "correspondence/correspondence.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<CorrespondenceEntry>>(
            Types.newParameterizedType(List::class.java, CorrespondenceEntry::class.java)
        )

    private fun file(context: Context) =
        File(context.filesDir, "correspondence").apply { mkdirs() }.let { File(it, "correspondence.json") }

    /** All records including tombstones (needed for merge). */
    fun loadAll(context: Context): MutableList<CorrespondenceEntry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    /** Live entries for one contact, newest first — what a contact's history view shows. */
    fun forContact(context: Context, contactId: String): List<CorrespondenceEntry> =
        loadAll(context).filter { !it.isDeleted && it.contactId == contactId }.sortedByDescending { it.at }

    /** Live-entry count per contact id — for the rolodex "📜 N" badge. */
    fun countsByContact(context: Context): Map<String, Int> =
        loadAll(context).filter { !it.isDeleted }.groupingBy { it.contactId }.eachCount()

    fun saveAll(context: Context, entries: List<CorrespondenceEntry>) {
        runCatching { file(context).writeText(adapter.toJson(entries)) }
            .onFailure { Timber.w(it, "correspondence save failed") }
    }

    fun upsert(context: Context, entry: CorrespondenceEntry) {
        val all = loadAll(context)
        entry.updated = System.currentTimeMillis()
        val i = all.indexOfFirst { it.id == entry.id }
        if (i >= 0) all[i] = entry else all.add(entry)
        saveAll(context, all)
        sync(context)
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.apply {
            deletedAt = System.currentTimeMillis(); updated = deletedAt
        }
        saveAll(context, all)
        sync(context)
    }

    /**
     * Tombstone a contact's whole thread — called when the [Contact] itself is deleted so its
     * correspondence doesn't dangle. Every touched entry bumps `updated`, so the deletes propagate
     * through the same id-union merge.
     */
    fun deleteForContact(context: Context, contactId: String) {
        val all = loadAll(context)
        val now = System.currentTimeMillis()
        var touched = false
        all.forEach {
            if (it.contactId == contactId && !it.isDeleted) {
                it.deletedAt = now; it.updated = now; touched = true
            }
        }
        if (touched) {
            saveAll(context, all)
            sync(context)
        }
    }

    /**
     * Round-trip the whole log through WebDAV so edits on one device reach the others. Union by id —
     * newer `updated` wins per entry, so tombstones (a delete bumps `updated`) propagate too.
     * Fire-and-forget; no-op without Ultrabridge creds.
     */
    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, CorrespondenceEntry>()
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
