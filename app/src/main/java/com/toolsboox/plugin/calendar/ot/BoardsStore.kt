package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.Board
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import timber.log.Timber
import java.io.File

/** The named Kanban boards, in one synced sidecar (`boards/boards.json`), id-union merged like
 *  [ContactStore]. Wire-compatible with the iOS BoardsStore. */
object BoardsStore {
    private const val PATH = "boards/boards.json"
    private val adapter = Moshi.Builder().build()
        .adapter<List<Board>>(Types.newParameterizedType(List::class.java, Board::class.java))

    private fun file(context: Context) =
        File(context.filesDir, "boards").apply { mkdirs() }.let { File(it, "boards.json") }

    fun loadAll(context: Context): MutableList<Board> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    fun list(context: Context): List<Board> =
        loadAll(context).filter { !it.isDeleted }.sortedBy { it.created }

    fun saveAll(context: Context, boards: List<Board>) {
        runCatching { file(context).writeText(adapter.toJson(boards)) }.onFailure { Timber.w(it, "boards save failed") }
    }

    fun add(context: Context, name: String): Board {
        val b = Board(name = name)
        val all = loadAll(context); all.add(b); saveAll(context, all); sync(context); return b
    }

    fun rename(context: Context, id: String, name: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.apply { this.name = name; updated = System.currentTimeMillis() }
        saveAll(context, all); sync(context)
    }

    fun delete(context: Context, id: String) {
        val all = loadAll(context)
        all.firstOrNull { it.id == id }?.apply { deletedAt = System.currentTimeMillis(); updated = deletedAt }
        saveAll(context, all); sync(context)
    }

    fun sync(context: Context) {
        LedgerSidecarSync.background {
            val local = loadAll(context)
            val remoteText = LedgerSidecarSync.pull(context, PATH)
            val merged = if (remoteText.isNullOrBlank()) local else {
                val byId = LinkedHashMap<String, Board>()
                for (b in local) byId[b.id] = b
                runCatching {
                    adapter.fromJson(remoteText)?.forEach { r ->
                        val cur = byId[r.id]; if (cur == null || r.updated > cur.updated) byId[r.id] = r
                    }
                }
                byId.values.toMutableList()
            }
            saveAll(context, merged)
            LedgerSidecarSync.push(context, PATH, adapter.toJson(merged))
        }
    }
}
