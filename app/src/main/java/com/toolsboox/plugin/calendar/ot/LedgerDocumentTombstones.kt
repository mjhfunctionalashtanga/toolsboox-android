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
    private const val EPOCHS = "epochs.json"

    /**
     * ── WHY EPOCHS EXIST (2026-07-31) ─────────────────────────────────────────────────────────
     *
     * A bare id set cannot express revival. `forget` removed the id locally, but the merge below
     * is a union, so the very next sync pulled the remote headstone back in and re-buried the row
     * the user had just recreated — deterministically, forever, on every device. Rename-after-
     * delete IS a conflict two devices can have about a deletion, and a union has no way to lose.
     *
     * So each id carries two clocks in `<dir>/epochs.json` — `{"<id>":{"d":millis,"r":millis}}`,
     * sorted keys — d for the last deletion, r for the last revival, merged per id by MAX of each.
     * An id is DEAD iff d > r; a tie goes to the revival, because a phantom name over a document
     * with no pages is a smaller wrong than killing the name of a document that exists.
     *
     * `deleted.json` stays exactly what it always was — the sorted array of currently-dead ids —
     * DERIVED from the epochs on every save, so an old build (either fork) keeps reading the same
     * wire it always did. A legacy id found in a `deleted.json` with no epochs entry is treated as
     * {d: 1, r: 0}: dead, but at the dawn of time, so any real revival outranks it. That is also
     * how timestampless sources (the names backup) must record deletions — [addLegacy], never
     * [add] — or an old backup could out-shout a revival that happened after it was taken.
     */
    private const val LEGACY_DELETED_AT = 1L

    /** The remote path, alongside the index it belongs to. */
    fun remotePath(dir: String) = "$dir/$FILE"

    /** The epochs file's remote path, beside [remotePath]. */
    fun epochsRemotePath(dir: String) = "$dir/$EPOCHS"

    private fun file(context: Context, dir: String) =
        File(context.filesDir, dir).apply { mkdirs() }.let { File(it, FILE) }

    private fun epochsFile(context: Context, dir: String) =
        File(context.filesDir, dir).apply { mkdirs() }.let { File(it, EPOCHS) }

    /** Last-deleted / last-revived, millis. Dead iff d > r. */
    data class Epoch(val d: Long, val r: Long)

    // ── The pure per-id rules ─────────────────────────────────────────────────────────────────
    // Context-free and network-free so the cross-fork convergence golden tests (JVM, and the iOS
    // twin `LedgerCore.TombstoneEpochs`) can pin them against `convergence-fixtures/` at the repo
    // root. [epochs]/[ids]/[save]/[roundTrip] apply exactly these — change them only in lockstep
    // with the iOS twin and the fixtures.

    /** Per-id MAX of each clock — a deletion and a revival each win exactly the arguments they
     *  are newer for. Blank ids are skipped, matching both forks' readers. */
    fun mergeEpochs(ours: Map<String, Epoch>, theirs: Map<String, Epoch>): Map<String, Epoch> {
        val out = LinkedHashMap(ours)
        for ((id, t) in theirs) {
            if (id.isBlank()) continue
            val o = out[id]
            out[id] = if (o == null) t else Epoch(maxOf(o.d, t.d), maxOf(o.r, t.r))
        }
        return out
    }

    /** Fold a legacy `deleted.json` id array in: an id lands as {d: 1, r: 0} ONLY when the store
     *  has never heard of it — a known id already has real clocks, and legacy knowledge must
     *  never outrank them. */
    fun foldLegacy(epochs: Map<String, Epoch>, legacyIds: Iterable<String>): Map<String, Epoch> {
        val out = LinkedHashMap(epochs)
        for (id in legacyIds) {
            if (id.isNotBlank() && id !in out) out[id] = Epoch(LEGACY_DELETED_AT, 0L)
        }
        return out
    }

    /** The dead subset — every id whose deletion clock outranks its revival clock. */
    fun deadIds(epochs: Map<String, Epoch>): Set<String> =
        epochs.filterValues { it.d > it.r }.keys

    /** Every id's clocks — legacy `deleted.json` entries folded in as {d:1, r:0}. Empty on any
     *  problem: a tombstone store that cannot be read must fail towards KEEPING data. */
    fun epochs(context: Context, dir: String): Map<String, Epoch> {
        val out = LinkedHashMap<String, Epoch>()
        val ef = epochsFile(context, dir)
        if (ef.exists()) runCatching {
            val obj = org.json.JSONObject(ef.readText())
            for (id in obj.keys()) {
                val e = obj.optJSONObject(id) ?: continue
                if (id.isNotBlank()) out[id] = Epoch(e.optLong("d"), e.optLong("r"))
            }
        }
        var result: Map<String, Epoch> = out
        val f = file(context, dir)
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            result = foldLegacy(result, (0 until arr.length()).map { arr.optString(it) })
        }
        return result
    }

    /** Every id this device knows to be deleted — the d > r subset of [epochs]. */
    fun ids(context: Context, dir: String): Set<String> =
        deadIds(epochs(context, dir))

    /** Both files, atomically derived from one truth: epochs first, then `deleted.json` as the
     *  dead subset old builds still read. Sorted on write so an unchanged set isn't a change. */
    fun save(context: Context, dir: String, epochs: Map<String, Epoch>) {
        runCatching {
            val obj = org.json.JSONObject()
            for (id in epochs.keys.sorted()) {
                val e = epochs.getValue(id)
                obj.put(id, org.json.JSONObject().put("d", e.d).put("r", e.r))
            }
            epochsFile(context, dir).writeText(obj.toString())
            val arr = JSONArray()
            for (id in deadIds(epochs).sorted()) arr.put(id)
            file(context, dir).writeText(arr.toString())
        }.onFailure { Timber.w(it, "document tombstones save failed for $dir") }
    }

    /** Record a deletion the user made NOW. Idempotent within a millisecond, which is enough. */
    fun add(context: Context, dir: String, id: String) {
        if (id.isBlank()) return
        val all = epochs(context, dir).toMutableMap()
        all[id] = Epoch(System.currentTimeMillis(), all[id]?.r ?: 0L)
        save(context, dir, all)
    }

    /** Record a deletion from a TIMESTAMPLESS source (a names backup). Only lands on an id this
     *  store has never heard of — a known id already has real clocks, and legacy knowledge must
     *  never outrank them. */
    fun addLegacy(context: Context, dir: String, id: String) {
        if (id.isBlank()) return
        val all = epochs(context, dir).toMutableMap()
        if (id in all) return
        all[id] = Epoch(LEGACY_DELETED_AT, 0L)
        save(context, dir, all)
    }

    /** Un-record one — what naming a document again after deleting it has to do. Stamps a revival
     *  clock rather than erasing the record: an erased record loses the merge to every remote copy
     *  of the deletion, which was the bug — the recreated title vanished on the next sync, forever.
     *  Only reachable for keys that can be re-created under the same id, which in practice means
     *  the date-scoped daily pages. */
    fun forget(context: Context, dir: String, id: String) {
        val all = epochs(context, dir).toMutableMap()
        val e = all[id] ?: return
        if (e.d <= e.r) return   // already alive
        all[id] = Epoch(e.d, System.currentTimeMillis())
        save(context, dir, all)
    }

    /**
     * The whole round trip: pull both remote files, merge per id by MAX of each clock, save, push
     * both back. Returns the dead set the caller's row merge is allowed to subtract.
     *
     * The legacy `deleted.json` is still read from the remote (an old build may have written it
     * moments ago) and still pushed (an old build will read it moments from now) — but it can only
     * ever contribute dawn-of-time deletions, so a revival recorded by any current build survives
     * every old build's re-pushed union.
     */
    fun roundTrip(context: Context, dir: String): Set<String> {
        val local = epochs(context, dir)
        var merged: Map<String, Epoch> = local

        val remoteEpochsText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
            .pull(context, epochsRemotePath(dir))
        if (!remoteEpochsText.isNullOrBlank()) runCatching {
            val obj = org.json.JSONObject(remoteEpochsText)
            val theirs = LinkedHashMap<String, Epoch>()
            for (id in obj.keys()) {
                val e = obj.optJSONObject(id) ?: continue
                if (id.isNotBlank()) theirs[id] = Epoch(e.optLong("d"), e.optLong("r"))
            }
            merged = mergeEpochs(merged, theirs)
        }
        val remoteDeletedText = com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
            .pull(context, remotePath(dir))
        if (!remoteDeletedText.isNullOrBlank()) runCatching {
            val arr = JSONArray(remoteDeletedText)
            merged = foldLegacy(merged, (0 until arr.length()).map { arr.optString(it) })
        }

        if (merged != local) save(context, dir, merged)
        val dead = deadIds(merged)
        // An empty store is not pushed — absence reads as empty on every build, and this runs on
        // every Directory open; see the iOS twin's note on the same guard.
        if (merged.isNotEmpty()) {
            val obj = org.json.JSONObject()
            for (id in merged.keys.sorted()) {
                val e = merged.getValue(id)
                obj.put(id, org.json.JSONObject().put("d", e.d).put("r", e.r))
            }
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, epochsRemotePath(dir), obj.toString())
            val arr = JSONArray()
            for (id in dead.sorted()) arr.put(id)
            com.toolsboox.plugin.calendar.nw.LedgerSidecarSync.push(context, remotePath(dir), arr.toString())
        }
        return dead
    }
}
