package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * EXPORT/IMPORT OF THE NAMING AND THE DIRECTORY STRUCTURE — everything that says what a thing IS,
 * in one file you can put somewhere that is not a device and is not a server.
 *
 * Michael: "I want to make sure that the naming and the directory structure get backed up."
 *
 * [SettingsBackup] next door carries CONNECTION SETTINGS only — WebDAV/RSS/bridge addresses,
 * passwords, AI keys, sites, mail accounts — "so a device is set up once and copied to the others".
 * It carries no content, and it never claimed to. Everything that gives a document its identity was
 * therefore in no backup at all: the four name indexes (`write-index/pages.json`,
 * `synth-index/`, `grid-index/`, `sketch-index/`), their headstones (`deleted.json`), the
 * handwritten title manifests and their PNGs (`title-ink.json` + `title-ink/<id>.png`), the Pickings
 * board names, and how the directory itself is arranged.
 *
 * ── SYNC IS NOT BACKUP ────────────────────────────────────────────────────────────────────────
 *
 * All of the above already round-trips over WebDAV, and that is the thing worth being clear-eyed
 * about, because it is exactly the reason nobody noticed the gap. Sync makes every device agree; it
 * does not make anything durable, and it fails in three ways this file answers:
 *
 *  • A deletion PROPAGATES. That is the whole design — [LedgerDocumentTombstones] exists to make
 *    deletions stick across devices. A mistake therefore also sticks, everywhere, immediately.
 *  • A device with Ultrabridge unconfigured sends nothing anywhere. [LedgerSidecarSync] no-ops
 *    without credentials, silently, which is why today's work went into making that state visible
 *    on the sync screen at all. A ledger that has never been configured has never been backed up.
 *  • A server-side loss takes every device with it, since every device is a mirror of it.
 *
 * ── WHAT THIS CANNOT DO, SAID PLAINLY ─────────────────────────────────────────────────────────
 *
 * It carries NAMES AND STRUCTURE. It does not carry the ink of the pages — strokes, dropped grams,
 * images and text ride the day files under each document's key, exactly as the stores' own notes
 * say ("NOTHING here touches page content"). A restore from this file gives back every title, every
 * handwritten title face, every board name and the shape of the directory, over whatever pages the
 * device actually holds. The whole-ledger .zip (long-press Export in settings) is what carries the
 * pages. Both the button label and the hint under it say so, because a file called "backup" that
 * silently isn't one is worse than no button.
 *
 * ── THE PINNED CROSS-PLATFORM SCHEMA ──────────────────────────────────────────────────────────
 *
 * ```
 * { "version": 1, "exportedAt": 1753900000000,
 *   "documents": { "write": {"pages":[{"key","name","date"}], "deleted":[…]},
 *                  "synthesize": {…}, "grid": {…}, "sketch": {…} },
 *   "titleInk": [ {"surface","id","show","gone","at","png"} ],
 *   "boards":   [ {"date","key","name"} ],
 *   "directory": {"sort","spine"} }
 * ```
 *
 * Both forks emit and accept exactly this. Earlier today two agents built the same feature on the
 * two forks and chose incompatible shapes, and a handwritten title written on one device never
 * reached the other; the schema is pinned for that reason and is not a place to be clever.
 *
 * Two consequences worth spelling out:
 *
 *  • `documents.*.pages` and `.deleted` rows are VERBATIM the rows already in those stores' own
 *    files — this reads and writes `pages.json` as JSON objects and never reconstructs them from
 *    typed models, so a field a newer fork adds to a row survives the trip through here rather than
 *    being quietly flattened to the three fields Android happens to know about. (The store itself
 *    will normalise the row away on its next save; forwarding what we can is still strictly better
 *    than dropping it on read.)
 *  • `titleInk.surface` is the store's directory stem — `write`, `synthesize`, `grid`, `sketch` —
 *    which is precisely [LedgerDocuments.WRITE] / [LedgerDocuments.SYNTHESIZE] / [LedgerDocuments.GRID]
 *    / [LedgerDocuments.JOT]. Jot's token is "sketch" and stays "sketch"; see [LedgerDocumentStore]
 *    for why renaming that key would be a migration of Michael's ink.
 *
 * Unknown top-level keys — and unknown surfaces inside `documents` — are captured on import and
 * re-emitted on the next export, the way [SettingsBackup]'s passthrough store already does for
 * unknown sections, so an iOS-only addition is never destroyed by an Android re-export.
 *
 * ── BASE64 PNGs ARE ACCEPTABLE HERE, AND ONLY HERE ────────────────────────────────────────────
 *
 * [LedgerTitleInk] goes out of its way to keep the pixels BESIDE the manifest rather than inside
 * it, and its note explains why at length: `title-ink.json` is a small file that syncs on every
 * rename, and inlining a base64 PNG would turn the most frequently-written file in the fork into a
 * file whose size is set by how ornately Michael writes. That argument is about a LIVE SIDECAR. It
 * does not apply to a backup, which is a one-shot artifact written to a file the user picked, read
 * back at most once, and never synced. Inlining here is what makes the file self-contained — a
 * names backup that referenced PNGs it did not carry would restore a manifest full of ids pointing
 * at nothing, which is worse than not backing the faces up at all.
 *
 * WHAT WAS REJECTED: a .zip with the PNGs as entries beside a manifest, i.e. the shape the
 * whole-ledger backup uses. It is more efficient and it is the wrong artifact — the pinned schema is
 * one JSON document both forks parse, and a zip would make the iPad's reader a zip reader for the
 * sake of a few hundred kilobytes. It also stops the file being something you can open and read.
 *
 * ── HOW BIG THIS ACTUALLY GETS ────────────────────────────────────────────────────────────────
 *
 * Measured rather than assumed, because "base64 PNGs in a JSON" is the kind of decision that is
 * either fine or catastrophic and the difference is a number:
 *
 *  • A name row is ~75 bytes, a headstone id ~25, a board row ~60. A ledger with a thousand named
 *    documents and two years of daily boards is therefore ~150–250 KB of text. Nothing.
 *  • A face is capped by [LedgerTitleInk] at 1200×160. Encoded at that size, a short handwritten
 *    title comes to ~2.8 KB of PNG (~3.8 KB base64) and a title running the full width of the band
 *    ~4.8 KB (~6.4 KB base64). Call it 4–7 KB each.
 *
 * So the file is roughly `250 KB + 6 KB × (however many titles he has written by hand)`: ~500 KB at
 * fifty faces, ~2 MB at three hundred. That is a size a share sheet, a mail attachment and
 * [JSONObject] all handle without complaint, on a device that already zips its entire Documents
 * tree from the button above.
 *
 * Hence NO hard cap on the number of faces. Dropping some of his handwriting out of a backup to
 * hit a size target would be precisely the dishonesty the hint under the button exists to avoid —
 * a backup must either carry a thing or say it doesn't. What there IS: [MAX_FACE_BYTES], which
 * skips a single file so far outside the store's own cap that it can only be damaged or foreign,
 * and an inventory line ([inventory]) carried on the share sheet so the count and the resulting
 * size are visible at the moment the file is sent rather than discovered later.
 *
 * ── MERGE, NEVER REPLACE ──────────────────────────────────────────────────────────────────────
 *
 * [importJson] folds a file in; it never installs it over the top. Restoring a backup onto a device
 * that has since done work must not throw that work away, and the rules are per-store because the
 * clocks are:
 *
 *  • Title-ink rows carry a real per-row clock ([LedgerTitleInk.Face.at]), so they merge
 *    newest-wins, exactly as [LedgerTitleInk.sync] merges a remote manifest. A `gone` headstone
 *    beats an older live row and loses to a face written since the backup.
 *  • Document rows carry NO clock — `pages.json` is `{key,name,date}` and `date` is the day the
 *    document was started, not the day it was last renamed. So the rule is the one the stores'
 *    own sync already uses and the only one that is honest without inventing a timestamp: union by
 *    the store's own merge id, LOCAL WINS. A row the device doesn't have is restored; a row it does
 *    have is left exactly as it is, because the live row is by definition at least as new as a file
 *    written in the past. An import therefore behaves precisely like a sync from a peer that went
 *    quiet on the export date, which is what it is.
 *  • A backed-up HEADSTONE is accepted only when this device has no live row under that id. A
 *    deletion is also work, and the local device's own `deleted.json` is honoured the same way, so
 *    an old backup cannot resurrect something deleted since. But a stale headstone must never kill
 *    a row that is alive here now — [WritePageStore.rename] already lifts a headstone when a
 *    document is re-created under a dead id, and re-importing the old one would undo that.
 */
object NamesBackup {

    /** The pinned schema version. Bump only in lockstep with the iPad twin. */
    const val VERSION = 1

    /**
     * One named surface and everything the merge needs to know about it.
     *
     * [dateScoped] is the whole reason this is a table and not a loop over four names. Write, Grid
     * and Jot merge on "key|date" because every day shares the daily key ("write", "grid",
     * "sketch") and a key-only identity would let one day's title swallow every other day's;
     * Synthesize merges on the key alone, its keys being unique. Those are the stores' OWN merge
     * ids — see [WritePageStore.idOf], [LedgerDocumentStore.idOf] and [SynthPageStore.sync] — and a
     * backup that addressed rows differently from the store would produce headstones that silently
     * match nothing and duplicates that silently match everything.
     */
    private data class Surface(val id: String, val dir: String, val dateScoped: Boolean)

    private val surfaces = listOf(
        Surface(LedgerDocuments.WRITE, WritePageStore.DIR, dateScoped = true),
        Surface(LedgerDocuments.SYNTHESIZE, SynthPageStore.DIR, dateScoped = false),
        Surface(LedgerDocuments.GRID, GridPageStore.dir, dateScoped = true),
        Surface(LedgerDocuments.JOT, JotPageStore.dir, dateScoped = true),
    )

    /**
     * The index file name, repeated here rather than reached for.
     *
     * Each store holds it privately, and it is the same literal in all four because it is a WIRE
     * PATH, not an implementation detail: `<dir>/pages.json` is what the iPad reads off the same
     * WebDAV account (see [LedgerDocumentStore]'s "THE DIRECTORY AND KEY NAMES ARE THE WIRE"). A
     * fifth copy of a name that cannot change without breaking the other fork is a smaller cost
     * than four new accessors punched through four stores to read a constant.
     */
    private const val PAGES_FILE = "pages.json"

    /**
     * A single face bigger than this is not a handwritten title.
     *
     * [LedgerTitleInk] caps what it STORES at 1200×160, so anything approaching half a megabyte
     * arrived from somewhere else or is damaged. It is skipped rather than carried — the manifest
     * row still goes, so the id and the show flag survive and the pixels can still arrive by sync —
     * because one corrupt file must not be able to make the backup too large to write or to send.
     */
    private const val MAX_FACE_BYTES = 512 * 1024

    /**
     * Unknown sections from another fork, kept between an import and the next export.
     *
     * A FILE, not [android.content.SharedPreferences], which is where [SettingsBackup] keeps its
     * passthrough: that store holds a handful of short credential strings, whereas this one could
     * be handed an iOS-only section carrying base64 faces. SharedPreferences is an XML map parsed
     * into memory and rewritten whole on every commit; it is the wrong home for a payload whose
     * size is set by another platform's feature list. Not encrypted, also unlike SettingsBackup's,
     * and for the same reason stated in reverse — there are no secrets in here, only names.
     */
    private const val PASSTHROUGH_FILE = "ledger-names-passthrough.json"

    /** Top-level keys this fork models. Everything else rides the passthrough. */
    private val KNOWN_KEYS = setOf("version", "exportedAt", "documents", "titleInk", "boards", "directory")

    // ── Export ────────────────────────────────────────────────────────────────────────────────

    /**
     * A one-line inventory of what an export would contain, for the message shown when one is made.
     *
     * The face count is the number worth surfacing: names are bytes and faces are kilobytes, so it
     * is the only figure that can surprise anyone about the size of the file they just shared.
     * Counts only — no PNG is read to produce it.
     */
    fun inventory(context: Context): String {
        val names = surfaces.sumOf { readRows(context, it.dir).length() }
        val faces = LedgerTitleInk.SURFACES.sumOf { s -> LedgerTitleInk.rows(context, s).count { !it.gone } }
        val boards = PickingsStore.dates(context).sumOf { PickingsStore.listSaved(context, it).size }
        return "$names names · $faces written titles · $boards boards"
    }

    fun exportJson(context: Context): String {
        val root = JSONObject()
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())

        val documents = JSONObject()
        for (s in surfaces) {
            val deleted = JSONArray()
            for (id in LedgerDocumentTombstones.ids(context, s.dir).sorted()) deleted.put(id)
            documents.put(s.id, JSONObject().put("pages", readRows(context, s.dir)).put("deleted", deleted))
        }

        val ink = JSONArray()
        for (surface in LedgerTitleInk.SURFACES) {
            for (row in LedgerTitleInk.rows(context, surface)) {
                val o = JSONObject()
                    .put("surface", surface).put("id", row.id)
                    .put("show", row.show).put("gone", row.gone).put("at", row.at)
                // A headstone has no pixels by definition, and reading a file for a row that says the
                // face is gone would either fail or resurrect it into the backup.
                if (!row.gone) {
                    val bytes = LedgerTitleInk.bytes(context, surface, row.id)
                    if (bytes != null && bytes.size <= MAX_FACE_BYTES)
                        o.put("png", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    else if (bytes != null)
                        Timber.w("names backup: face ${row.id} is ${bytes.size} bytes, omitted")
                }
                ink.put(o)
            }
        }
        root.put("titleInk", ink)

        // Pickings boards. [PickingsStore.listSaved] rather than `list`, because `list` INVENTS the
        // default board for any day you ask about — backing that up would write one fabricated row
        // per day in the ledger's history and restore a "Pickings" board onto days nobody touched.
        val boards = JSONArray()
        for (date in PickingsStore.dates(context)) {
            for (p in PickingsStore.listSaved(context, date)) {
                if (p.key.isBlank()) continue
                boards.put(JSONObject().put("date", date.toString()).put("key", p.key).put("name", p.name))
            }
        }
        root.put("boards", boards)

        val (sort, spine) = com.toolsboox.plugin.feeds.ui.ledgerDirectoryState(context)
        root.put("directory", JSONObject().put("sort", sort).put("spine", spine))

        // Merge back whatever another fork sent us and we have no home for. Live values win and are
        // never overwritten; the passthrough only fills what this device does not model at all —
        // the same rule, for the same reason, as [SettingsBackup.exportJson]'s passthrough merge.
        val carried = readPassthrough(context)
        val carriedDocs = carried.optJSONObject("documents")
        if (carriedDocs != null) {
            val keys = carriedDocs.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!documents.has(k)) documents.put(k, carriedDocs.get(k))
            }
        }
        root.put("documents", documents)
        val keys = carried.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "documents" && !root.has(k)) root.put(k, carried.get(k))
        }

        // Compact, unlike [SettingsBackup]'s indented output. A settings file is a page of key/value
        // pairs somebody may well read to check a hostname; this one is thousands of rows and lines
        // of base64 nobody reads by eye, and indenting it spends kilobytes on nothing.
        return root.toString()
    }

    // ── Import ────────────────────────────────────────────────────────────────────────────────

    /** What a restore actually did — the honest report the toast shows, rather than "Imported". */
    data class Restored(
        val names: Int, val headstones: Int, val faces: Int, val boards: Int, val directory: Boolean
    ) {
        fun isEmpty() = names == 0 && headstones == 0 && faces == 0 && boards == 0 && !directory

        /**
         * Deliberately says what was ADDED, not what was in the file. A merge that found the device
         * already up to date has restored nothing, and reporting the file's row count instead would
         * turn "nothing to do" into a false claim that hundreds of names had just come back.
         */
        fun summary(): String {
            if (isEmpty()) return "Already up to date — nothing to restore"
            val parts = mutableListOf<String>()
            if (names > 0) parts += "$names names"
            if (faces > 0) parts += "$faces written titles"
            if (boards > 0) parts += "$boards boards"
            if (headstones > 0) parts += "$headstones deletions"
            if (directory) parts += "directory arrangement"
            return "Restored ${parts.joinToString(", ")}"
        }
    }

    fun importJson(context: Context, json: String): Restored {
        val root = JSONObject(json)
        var names = 0
        var headstones = 0
        var faces = 0
        var boards = 0

        val documents = root.optJSONObject("documents")
        if (documents != null) for (s in surfaces) {
            val sec = documents.optJSONObject(s.id) ?: continue
            val result = runCatching { mergeDocuments(context, s, sec) }
                .onFailure { Timber.w(it, "names import: ${s.id} documents failed") }
                .getOrDefault(0 to 0)
            names += result.first
            headstones += result.second
        }

        // Title ink. Each surface is synced ONCE, after all of its rows have landed — see
        // [LedgerTitleInk.restore] for why the per-row call deliberately doesn't sync.
        val touched = LinkedHashSet<String>()
        root.optJSONArray("titleInk")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.optJSONObject(i) ?: return@runCatching
                    val surface = o.optString("surface")
                    val id = o.optString("id")
                    if (id.isBlank() || !LedgerTitleInk.supports(surface)) return@runCatching
                    val row = LedgerTitleInk.Face(
                        id = id,
                        show = o.optBoolean("show", true),
                        gone = o.optBoolean("gone", false),
                        at = o.optLong("at", 0L),
                    )
                    val b64 = o.optString("png", "")
                    val png = if (b64.isBlank()) null
                    else runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    if (LedgerTitleInk.restore(context, surface, row, png)) {
                        if (!row.gone) faces++
                        touched.add(surface)
                    }
                }.onFailure { Timber.w(it, "names import: title ink row failed") }
            }
        }
        for (surface in touched) LedgerTitleInk.sync(context, surface)

        root.optJSONArray("boards")?.let { boards += mergeBoards(context, it) }

        val directory = root.optJSONObject("directory")
        if (directory != null) {
            com.toolsboox.plugin.feeds.ui.applyLedgerDirectoryState(
                context,
                directory.optString("sort").takeIf { it.isNotBlank() },
                directory.optString("spine").takeIf { it.isNotBlank() }
            )
        }

        capturePassthrough(context, root)
        return Restored(names, headstones, faces, boards, directory != null)
    }

    /**
     * Fold one surface's index in. Returns (rows restored, headstones accepted).
     *
     * The order of the three rules below is the substance of "merge, not replace", and each one is a
     * way the naive version loses data:
     *
     *  1. A backup row whose id is ALREADY LIVE here is skipped. The local row is at least as new.
     *  2. A backup row whose id is in this device's own `deleted.json` is skipped — it was deleted
     *     here after the backup was written, and re-adding it is the resurrection
     *     [LedgerDocumentTombstones] was built to stop. (Undoing a deletion has a path already:
     *     name the document again, which lifts the headstone. See [WritePageStore.rename].)
     *  3. A backup HEADSTONE is accepted only when nothing live sits under that id here. A row that
     *     exists now is newer news than a file's record that it once didn't.
     */
    private fun mergeDocuments(context: Context, s: Surface, sec: JSONObject): Pair<Int, Int> {
        val local = readRows(context, s.dir)
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until local.length()) {
            val o = local.optJSONObject(i) ?: continue
            val id = mergeId(s, o) ?: continue
            byId[id] = o
        }
        val live = HashSet(byId.keys)
        val dead = LedgerDocumentTombstones.ids(context, s.dir)

        var restored = 0
        val incoming = sec.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val id = mergeId(s, o) ?: continue
            if (id in live || id in dead) continue
            byId[id] = o
            restored++
        }

        var buried = 0
        val stones = sec.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until stones.length()) {
            val id = stones.optString(i)
            if (id.isBlank() || id in dead) continue
            if (id in live) {
                Timber.i("names import: headstone $id ignored, ${s.id} has a live row for it")
                continue
            }
            // addLegacy, never add: a backup's headstones carry no clocks, so they land at the
            // dawn of time — a revival recorded on any device after this backup was taken
            // outranks them, instead of the backup re-burying the recreated document.
            LedgerDocumentTombstones.addLegacy(context, s.dir, id)
            byId.remove(id)   // only ever removes a row this same file just handed us
            buried++
        }

        if (restored > 0 || buried > 0) {
            writeRows(context, s.dir, byId.values.toList())
            syncOf(s, context)
        }
        return restored to buried
    }

    /**
     * Board names, per day, union by key with the local name winning — [PickingsStore.sync]'s rule,
     * because a board index has no clock either and the argument is identical to the document one.
     */
    private fun mergeBoards(context: Context, arr: JSONArray): Int {
        val wanted = LinkedHashMap<LocalDate, MutableList<PickingPage>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            if (key.isBlank()) continue
            val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: continue
            wanted.getOrPut(date) { mutableListOf() }.add(PickingPage(key, o.optString("name")))
        }
        var restored = 0
        for ((date, rows) in wanted) {
            runCatching {
                val local = PickingsStore.listSaved(context, date).toMutableList()
                var changed = false
                for (r in rows) {
                    if (local.any { it.key == r.key }) continue
                    local.add(r); changed = true; restored++
                }
                if (changed) {
                    PickingsStore.save(context, date, local)
                    PickingsStore.sync(context, date)
                }
            }.onFailure { Timber.w(it, "names import: boards for $date failed") }
        }
        return restored
    }

    // ── Files ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Merge id for a raw row — the store's own, per [Surface.dateScoped].
     *
     * A parseable date is demanded even for Synthesize, whose id ignores it, because every one of
     * these stores drops a row with an unparseable date on read ([SynthPageStore.list] and its
     * three siblings all `mapNotNull` on exactly that). Writing such a row back would put a line in
     * the index that the store deletes the next time it saves — a restore that appears to work and
     * silently doesn't. Better to refuse it here, where a log line says so.
     */
    private fun mergeId(s: Surface, row: JSONObject): String? {
        val key = row.optString("key").takeIf { it.isNotBlank() } ?: return null
        val date = runCatching { LocalDate.parse(row.optString("date")) }.getOrNull() ?: return null
        return if (s.dateScoped) "$key|$date" else key
    }

    private fun pagesFile(context: Context, dir: String) =
        File(context.filesDir, dir).apply { mkdirs() }.let { File(it, PAGES_FILE) }

    /** The index exactly as it sits on disk. Empty on any problem: a backup that silently exported
     *  nothing is bad, but a backup that refused to be made at all because one index is unreadable
     *  would lose the other three as well. */
    private fun readRows(context: Context, dir: String): JSONArray {
        val f = pagesFile(context, dir)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }
            .onFailure { Timber.w(it, "names backup: $dir/$PAGES_FILE unreadable") }
            .getOrDefault(JSONArray())
    }

    private fun writeRows(context: Context, dir: String, rows: List<JSONObject>) {
        runCatching {
            val arr = JSONArray()
            for (r in rows) arr.put(r)
            pagesFile(context, dir).writeText(arr.toString())
        }.onFailure { Timber.w(it, "names import: $dir/$PAGES_FILE write failed") }
    }

    /** Push the merged index at the other devices, through the store that owns it — so the headstone
     *  round trip and the tombstone subtraction happen exactly once, in the code that already does
     *  them, rather than being a fifth copy here. */
    private fun syncOf(s: Surface, context: Context) = when (s.id) {
        LedgerDocuments.WRITE -> WritePageStore.sync(context)
        LedgerDocuments.SYNTHESIZE -> SynthPageStore.sync(context)
        LedgerDocuments.GRID -> GridPageStore.sync(context)
        else -> JotPageStore.sync(context)
    }

    // ── Passthrough ───────────────────────────────────────────────────────────────────────────

    /**
     * Keep every top-level key this fork does not model, plus any surface inside `documents` it does
     * not have a store for, so the next export re-emits them intact.
     *
     * Same contract as [SettingsBackup]'s passthrough and for the same reason: two forks add
     * features at different times, and the fork that lands second must not be able to destroy the
     * other's data simply by exporting. Imported keys win over carried ones, and a key absent from
     * this file leaves the carried copy standing — a partial backup must not erase a section an
     * earlier import captured.
     */
    private fun capturePassthrough(context: Context, root: JSONObject) {
        val carried = readPassthrough(context)
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in KNOWN_KEYS) continue
            carried.put(k, root.get(k))
        }
        root.optJSONObject("documents")?.let { docs ->
            val known = surfaces.map { it.id }.toSet()
            val leftover = carried.optJSONObject("documents") ?: JSONObject()
            val dk = docs.keys()
            while (dk.hasNext()) {
                val k = dk.next()
                if (k in known) continue
                leftover.put(k, docs.get(k))
            }
            if (leftover.length() > 0) carried.put("documents", leftover)
        }
        writePassthrough(context, carried)
    }

    private fun readPassthrough(context: Context): JSONObject =
        runCatching { JSONObject(File(context.filesDir, PASSTHROUGH_FILE).readText()) }
            .getOrDefault(JSONObject())

    private fun writePassthrough(context: Context, obj: JSONObject) {
        if (obj.length() == 0) return
        runCatching { File(context.filesDir, PASSTHROUGH_FILE).writeText(obj.toString()) }
            .onFailure { Timber.w(it, "names passthrough write failed") }
    }
}
