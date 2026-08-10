package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The all-tasks walk, remembered between opens.
 *
 * Boards & Tasks reads every `day-*-v2.json` under `calendar/` to answer "what is open" — a walk
 * that grows with the ledger and used to be paid in full on every load() and every onResume(),
 * even though on any given open almost none of those files have changed since the last one. This
 * is the same disease the iOS DayLite slim decode cured once already, at the next layer up: the
 * decode got cheap, but a thousand cheap decodes is still a walk.
 *
 * The cure is the obvious one: remember what each file decoded to, keyed by the file itself.
 *
 * Invalidation is BY THE FILE'S OWN TIMESTAMP, not by anyone remembering to call an invalidate.
 * Every write the app makes to a day file — a stage move, a done-toggle, an edit, a sync merge
 * landing from the other device — rewrites the file and bumps its mtime, so a changed file simply
 * misses and re-decodes on the next walk. There is no cache-clearing call to forget to make, which
 * is the whole reason this is a map keyed by (mtime, length) rather than an object with a
 * lifecycle. Length rides along as a free second witness for filesystems whose mtime granularity
 * is coarser than two writes in the same second.
 *
 * One honesty note for the reader who mutates: the cached [LedgerItem]s are the same live
 * instances across walks, exactly as if the caller had held onto its previous decode. That is
 * safe here for the same reason it always was — every mutation a surface makes is followed by a
 * save of the item's own day, and the save bumps the mtime that retires this entry.
 */
object TaskWalkCache {

    private data class Entry(val lastModified: Long, val length: Long, val items: List<LedgerItem>)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The file's ledger items, decoded at most once per on-disk version of the file. */
    fun items(file: File, decode: (File) -> List<LedgerItem>): List<LedgerItem> {
        val key = file.absolutePath
        val mtime = file.lastModified()
        val length = file.length()
        entries[key]?.let { if (it.lastModified == mtime && it.length == length) return it.items }
        val decoded = decode(file)
        entries[key] = Entry(mtime, length, decoded)
        return decoded
    }
}
