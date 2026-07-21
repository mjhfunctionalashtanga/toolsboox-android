package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/**
 * Every connection in ONE synced sidecar (`connections/connections.json`), id-union merged over
 * the same Ultrabridge WebDAV credential as contacts, clippings and the day tree. Mirrors
 * [ContactStore] and [ClippingsStore]. Wire-compatible with an iOS ConnectionStore.
 *
 * Global rather than per-day on purpose: an edge joins two things that usually live on different
 * days, and asking which day "owns" it has no honest answer.
 */
object ConnectionStore {
    private const val PATH = "connections/connections.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<Connection>>(Types.newParameterizedType(List::class.java, Connection::class.java))

    private fun file(context: Context) =
        File(context.filesDir, "connections").apply { mkdirs() }.let { File(it, "connections.json") }

    fun loadAll(context: Context): MutableList<Connection> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    fun saveAll(context: Context, connections: List<Connection>) {
        runCatching { file(context).writeText(adapter.toJson(connections)) }
            .onFailure { Timber.w(it, "connections save failed") }
    }

    /**
     * Join two things. Idempotent: connecting the same pair the same way twice revives the edge
     * rather than making a second one, because the id is derived from what it joins.
     */
    fun connect(
        context: Context, from: String, to: String,
        kind: String = Connection.ABOUT, note: String = "",
        fromLabel: String = "", toLabel: String = ""
    ): Connection? {
        if (from.isBlank() || to.isBlank() || from == to) return null
        val all = loadAll(context)
        val edge = Connection.of(from, to, kind, note, fromLabel, toLabel)
        val existing = all.firstOrNull { it.id == edge.id }
        val result = if (existing != null) existing.apply {
            deletedAt = 0L
            if (note.isNotBlank()) this.note = note
            // Labels are a snapshot, so a fresh one always beats the stored one.
            if (fromLabel.isNotBlank()) this.fromLabel = fromLabel
            if (toLabel.isNotBlank()) this.toLabel = toLabel
            updated = System.currentTimeMillis()
        } else edge.also { all.add(it) }
        saveAll(context, all)
        sync(context)
        return result
    }

    /** Tombstone an edge. Neither end is touched — that is the point of the edge owning itself. */
    fun disconnect(context: Context, id: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.apply {
            deletedAt = System.currentTimeMillis(); updated = deletedAt
        }
        saveAll(context, all)
        sync(context)
    }

    /** Every live edge touching [uri], from either end — newest first. */
    fun touching(context: Context, uri: String): List<Connection> =
        Connection.touching(loadAll(context), uri).sortedByDescending { it.updated }

    /**
     * The other ends of everything [uri] joins, deduplicated.
     *
     * Note what is deliberately absent: any check that the far end still exists. An edge to
     * something deleted still lists and still describes itself, it just won't open — a rhizome
     * broken at one point carries on along its other lines rather than collapsing.
     */
    fun neighbours(context: Context, uri: String): List<String> =
        touching(context, uri).mapNotNull { it.otherEnd(uri) }.distinct()

    /** Round-trip the whole graph through WebDAV so an edge made on one device reaches the others. */
    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val remote = runCatching { adapter.fromJson(remoteText) }.getOrNull().orEmpty()
                Connection.merge(local, remote)
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
        }
    }
}
