package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.Contact
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * Global rolodex store: every contact in ONE synced sidecar file (`contacts/contacts.json`),
 * id-union merged (newer `updated` wins per contact; `deletedAt` tombstones survive) over the same
 * Ultrabridge WebDAV credential as the day tree. Mirrors [PickingsStore] but a single global file,
 * not per-day. Wire-compatible with the iOS ContactStore.
 */
object ContactStore {
    private const val PATH = "contacts/contacts.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<Contact>>(Types.newParameterizedType(List::class.java, Contact::class.java))

    private fun file(context: Context) =
        File(context.filesDir, "contacts").apply { mkdirs() }.let { File(it, "contacts.json") }

    /** All records including tombstones (needed for merge). */
    fun loadAll(context: Context): MutableList<Contact> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    /** Live contacts (not tombstoned), name-sorted — what the rolodex shows. */
    fun list(context: Context): List<Contact> =
        loadAll(context).filter { !it.isDeleted }.sortedBy { it.name.lowercase() }

    fun get(context: Context, id: String): Contact? =
        loadAll(context).firstOrNull { it.id == id && !it.isDeleted }

    fun saveAll(context: Context, contacts: List<Contact>) {
        runCatching { file(context).writeText(adapter.toJson(contacts)) }
            .onFailure { Timber.w(it, "contacts save failed") }
    }

    fun upsert(context: Context, contact: Contact) {
        val all = loadAll(context)
        contact.updated = System.currentTimeMillis()
        val i = all.indexOfFirst { it.id == contact.id }
        if (i >= 0) all[i] = contact else all.add(contact)
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
     * Round-trip the whole rolodex through WebDAV so edits on one device reach the others.
     * Union by id — newer `updated` wins per contact, so tombstones (a delete bumps `updated`)
     * propagate too. Fire-and-forget; no-op without Ultrabridge creds.
     */
    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, Contact>()
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
