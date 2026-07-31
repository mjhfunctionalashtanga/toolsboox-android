package com.toolsboox.plugin.calendar.ot

import android.content.Context
import org.json.JSONArray
import timber.log.Timber
import java.io.File

/**
 * The headstones for deleted document index entries — one small file beside each naming index.
 *
 * ── WHY THIS HAS TO EXIST ─────────────────────────────────────────────────────────────────────
 *
 * [WritePageStore.sync] and [SynthPageStore.sync] merge by UNION with the local side winning:
 * whatever the server holds is folded in unless this device already has an entry with the same id.
 * That rule is right for names — a rename you just made is never undone by a stale copy still
 * sitting on the server — and it makes deletion impossible, because "this device no longer has that
 * entry" is indistinguishable from "this device has not seen it yet". Delete a document, and the
 * next background sync reads the entry back off the server and re-saves it. The title returns,
 * pointing at pages that are gone.
 *
 * So an absence cannot express a deletion; only a record can. This is that record: a set of merge
 * ids the merge subtracts, kept beside the index it belongs to and synced the same way.
 *
 * ── WHY IT IS A SEPARATE FILE ─────────────────────────────────────────────────────────────────
 *
 * `pages.json` is a WIRE FORMAT — iOS decodes it as a bare JSON array of `{key,name,date}`
 * (`LedgerDocumentStore.list`), and Michael's iPad reads the same file from the same WebDAV path.
 * A tombstone smuggled into that array as a fourth field would decode on iOS as an ordinary entry
 * with an empty name, which the iPad would then show as an untitled document: the deletion would
 * appear on the Boox as a removal and on the iPad as a phantom. So the array stays exactly what both
 * forks already agree it is, and the tombstones live at `<dir>/deleted.json` — a path iOS does not
 * read and is not harmed by.
 *
 * The honest limit of that, stated plainly: this makes deletion durable BETWEEN ANDROID DEVICES —
 * which is the common case, three Boox forks sharing one codebase — and leaves the iPad holding its
 * own copy of the title until iOS grows the same file. It cannot resurrect the INK (that is
 * tombstoned in the day file itself, in fields both forks already honour); at worst the iPad keeps
 * showing a name over a document with no pages left. A title without pages is a smaller wrong than
 * pages without a title, which is the trade this whole feature is arranged around.
 *
 * ── THE ID ───────────────────────────────────────────────────────────────────────────────────
 *
 * Whatever the store's own merge keys on, and nothing else. Write merges on "key|date" (every day
 * shares the key "write", so a key-only tombstone would delete every day's title at once);
 * Synthesize merges on the key alone (its keys are unique). Passing the store's own `idOf` in keeps
 * the two from ever drifting apart — a tombstone that doesn't match the merge id is a tombstone that
 * silently does nothing.
 */
object LedgerDocumentTombstones {

    private const val FILE = "deleted.json"

    /** The remote path, alongside the index it belongs to. */
    fun remotePath(dir: String) = "$dir/$FILE"

    private fun file(context: Context, dir: String) =
        File(context.filesDir, dir).apply { mkdirs() }.let { File(it, FILE) }

    /** Every id this device knows to be deleted. Empty on any problem — a tombstone store that
     *  cannot be read must fail towards KEEPING data, never towards hiding it. */
    fun ids(context: Context, dir: String): Set<String> {
        val f = file(context, dir)
        if (!f.exists()) return emptySet()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
    }

    /** Sorted on write so the file is byte-stable and an unchanged set doesn't read as a change. */
    fun save(context: Context, dir: String, ids: Set<String>) {
        runCatching {
            val arr = JSONArray()
            for (id in ids.sorted()) arr.put(id)
            file(context, dir).writeText(arr.toString())
        }.onFailure { Timber.w(it, "document tombstones save failed for $dir") }
    }

    /** Record a deletion. Idempotent. */
    fun add(context: Context, dir: String, id: String) {
        if (id.isBlank()) return
        save(context, dir, ids(context, dir) + id)
    }

    /** Un-record one — what naming a document again after deleting it has to do, or the merge would
     *  keep subtracting the entry the user just recreated. Only reachable for keys that can be
     *  re-created under the same id, which in practice means Write's date-scoped daily page. */
    fun forget(context: Context, dir: String, id: String) {
        val current = ids(context, dir)
        if (id !in current) return
        save(context, dir, current - id)
    }

    /** Fold the other devices' headstones in with ours and push the union back. Deletions only ever
     *  accumulate, so a union is the whole merge — there is no conflict two devices can have about a
     *  document they both agree is gone. */
    fun merge(context: Context, dir: String, remoteText: String?): Set<String> {
        val local = ids(context, dir)
        val remote = if (remoteText.isNullOrBlank()) emptySet() else runCatching {
            val arr = JSONArray(remoteText)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
        val union = local + remote
        if (union != local) save(context, dir, union)
        return union
    }

    /** The payload to push back. */
    fun encode(ids: Set<String>): String {
        val arr = JSONArray()
        for (id in ids.sorted()) arr.put(id)
        return arr.toString()
    }
}
