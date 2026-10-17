package com.toolsboox.plugin.reader.ui

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.LibraryBook
import com.toolsboox.plugin.calendar.nw.HubTransport
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import com.toolsboox.plugin.calendar.nw.putBytesEnsuringFolders
import com.toolsboox.plugin.calendar.nw.putFileCommitted
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * THE LIBRARY HUB — the library as a fleet property, not a device property.
 *
 * Build B of the 2026-08-11 Listen/Library design. The hub (the same remote root every sidecar
 * already rides — WebDAV or, since step D, Google Drive, whichever [LedgerSidecarSync.transport]
 * says the person chose) grows two things:
 *
 * ```
 * books/               the library files, folder structure preserved
 * books-index.json     manifest: one [LibraryBook] per book, id-union + tombstones
 * ```
 *
 * and this object is the Android side of the contract:
 *
 *  • PUSH ON ADD — a book that lands on this shelf (import, OPDS pull, share-in) rides up:
 *    bytes to `books/<folder>/<name>` (uploaded to a `.part` name and MOVEd into place, so no
 *    device can ever fetch half a book), then its record into the manifest. Failure is quiet;
 *    the next sync pass notices the book missing from the manifest and retries the whole add.
 *  • PULL ON TICK — [syncPassBlocking] rides the [com.toolsboox.plugin.calendar.nw.UltrabridgeSyncWorker]
 *    cadence: manifest vs shelf, [LibraryPolicy] decides per missing book, policy-allowed books
 *    download, the rest stay OFFERED and render as ghost cards on the Bookshelf.
 *  • DELETES ARE TOMBSTONES — an in-app delete of an app-directory book marks the manifest, and
 *    that is ALL it does anywhere else: a tombstone never deletes another device's file. The
 *    shelf on the other devices asks "removed from the library — keep your copy?" and honours
 *    either answer. The bytes under `books/` also stay — the hub is the truth, and a truth you
 *    can change your mind about is worth the disk.
 *
 * THE DECLARED-TREE SEAM, documented as ruled: a device whose shelf points at a declared folder
 * (the Boox aimed at a Syncthing tree) participates READ-ONLY. Its pre-existing books get
 * manifest ENTRIES pushed up on the scan — so the fleet sees them on every shelf — but the scan
 * does not bulk-upload their bytes (a 20GB tree is Syncthing's to carry until Syncthing retires,
 * design step E), and NOTHING here ever deletes inside a declared tree BY ITSELF: not a
 * tombstone, not a merge, not a pass. Since 09-13 (Michael: "we need to figure out how to delete
 * things too, like when I finish a comic book… same with finished books") the PERSON can: a
 * "Finished — delete everywhere" or "Delete from this device" answered on this device deletes
 * the file wherever it sits ([deleteEverywhere], [deleteHere]); the fleet still only ever gets
 * the question. A fetch of an
 * entry-only book quietly fails and the ghost card simply stays until the bytes exist on the hub
 * (an add that flows THROUGH the app uploads bytes even into a declared tree, because the add
 * moment holds them anyway). When Syncthing retires, the scan's entry-only branch is the one line
 * that changes.
 *
 * Threading: every network-touching call runs on ONE private single thread ("library-hub"), for
 * the same reason [LedgerSidecarSync] runs its stores on one — the manifest is a whole-file
 * read-modify-write and parallel passes lose writes. It is a SEPARATE thread from the sidecar
 * executor on purpose: a 100MB book transfer parked on the sidecar queue would hold every page
 * turn's reading-position push hostage behind it.
 *
 * The nudge stays render-only: ghost cards are drawn by [BookshelfFragment] and fetched by a tap
 * there or by the worker pass here. NO fetch runs in widget processes — a widget that downloaded
 * books would be a surface that guesses, with a data plan.
 */
object LibraryHub {

    private const val MANIFEST_PATH = "books-index.json"

    /**
     * WHERE THE LIBRARY LIVES — its own choice, apart from where the day sync goes (Michael,
     * 09-13: "what about using Google Drive?" — the VPS holding the WebDAV hub has 3.7 GB free
     * against a 20 GB declared library, while the planner JSON that feeds the VPS pipeline must
     * keep riding WebDAV). "auto" = whatever the sync backend is (yesterday's behaviour);
     * "drive" / "webdav" pin the library alone. The iOS twin is Settings › Books › Library over.
     */
    const val LIBRARY_BACKEND_KEY = "library_backend"   // auto | webdav | drive
    fun libraryBackend(context: Context): String = prefs(context).getString(LIBRARY_BACKEND_KEY, "auto") ?: "auto"
    fun setLibraryBackend(context: Context, v: String) = prefs(context).edit().putString(LIBRARY_BACKEND_KEY, v).apply()

    /** The hub's transport for THIS device — the library's own choice, else the sync backend's. */
    fun transport(context: Context): HubTransport? = when (libraryBackend(context)) {
        "drive" -> com.toolsboox.plugin.calendar.nw.DriveHubTransport.create(context)
        "webdav" -> LedgerSidecarSync.service(context)?.let { com.toolsboox.plugin.calendar.nw.WebDavHubTransport(it) }
        else -> LedgerSidecarSync.transport(context)
    }
    private fun manifestPull(context: Context): String? =
        if (libraryBackend(context) == "auto") LedgerSidecarSync.pull(context, MANIFEST_PATH)
        else transport(context)?.get(MANIFEST_PATH)?.toString(Charsets.UTF_8)
    private fun manifestPush(context: Context, text: String) {
        if (libraryBackend(context) == "auto") LedgerSidecarSync.push(context, MANIFEST_PATH, text)
        else transport(context)?.putBytesEnsuringFolders(MANIFEST_PATH, text.toByteArray(Charsets.UTF_8))
    }
    private const val PREFS = "ledger_reader_prefs"
    private const val KEY_WIFI_ONLY = "library_wifi_only"
    private const val KEY_CEILING_MB = "library_size_ceiling_mb"
    private const val KEY_CARRY_PREFIX = "library_carry:"
    /** Whether DECLARED-TREE bytes ride up to the hub: "off" (entry-only, the historical seam),
     *  "small" (≤ the size ceiling), "all". Default off — Syncthing owns those bytes until the
     *  person says otherwise. His ask, 2026-08-24: comics offline between devices WITHOUT
     *  Syncthing, "this might need a settings gate for download size". This is that gate. */
    private const val KEY_CARRY_DECLARED = "library_carry_declared"
    private const val KEY_ACKED_TOMBSTONES = "library_tombstones_acked"
    /** Books this device's person deleted HERE ONLY — never offered back as a ghost, never
     *  re-fetched by policy; a tap on the shelf's own fetch door un-dismisses. */
    private const val KEY_DISMISSED_HERE = "library_dismissed_here"

    private val adapter = Moshi.Builder().build()
        .adapter<List<LibraryBook>>(Types.newParameterizedType(List::class.java, LibraryBook::class.java))

    /** One thread, strict FIFO — the serialization IS the correctness mechanism (see class doc). */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "library-hub").apply { isDaemon = true }
    }

    // ── The local manifest cache ──────────────────────────────────────────────────────────────

    private fun cacheFile(context: Context) = File(context.filesDir, MANIFEST_PATH)

    /** The manifest as this device last knew it. Instant, local — what the ghost cards render
     *  from, so an offline shelf still shows the fleet's library as of the last reach. */
    fun cachedManifest(context: Context): MutableList<LibraryBook> {
        val f = cacheFile(context)
        if (!f.exists()) return mutableListOf()
        return runCatching { adapter.fromJson(f.readText())?.toMutableList() }.getOrNull() ?: mutableListOf()
    }

    private fun saveCache(context: Context, books: List<LibraryBook>) {
        runCatching { cacheFile(context).writeText(adapter.toJson(books)) }
            .onFailure { Timber.w(it, "library manifest cache save failed") }
    }

    // ── Identity ──────────────────────────────────────────────────────────────────────────────

    fun idFor(entry: BookshelfSource.Entry): String =
        LibraryBook.keyFor(entry.folder, entry.name, entry.sizeBytes)

    /**
     * Who this device is to the manifest's `addedBy`. The name a person gave their device in
     * Android's own settings when there is one (Settings → About → Device name lands in
     * Settings.Global under "device_name"), else the model — "Boox Go 6" beats "unknown" and a
     * chosen name beats both. Display-grade only; nothing merges on it.
     */
    fun deviceName(context: Context): String =
        runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (android.os.Build.MODEL ?: "unknown")

    /** sha1 of [entry]'s bytes, lowercase hex — streamed, never whole in memory. Null when the
     *  bytes can't be read (a yanked card, a revoked grant); the record then carries "" and the
     *  fetch side skips the hash check for it. */
    fun sha1Of(context: Context, entry: BookshelfSource.Entry): String? =
        BookshelfSource.openStream(context, entry)?.use { input ->
            runCatching {
                val md = MessageDigest.getInstance("SHA-1")
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }

    // ── Policy facts (the judgment itself lives in [LibraryPolicy], pure) ─────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun wifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, LibraryPolicy.DEFAULT_WIFI_ONLY)

    fun setWifiOnly(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, on).apply()

    fun sizeCeilingBytes(context: Context): Long =
        prefs(context).getInt(KEY_CEILING_MB, 100).toLong() * 1024 * 1024

    // DEFAULT "all" since 09-13 — Syncthing's retirement (Michael: "sync files… between all my
    // devices — I don't wish to rely on Syncthing"): a declared tree's bytes ride up to the hub
    // on the tick, wifi-only as ever, so the hub — not Syncthing — carries the fleet's books.
    // The Bookshelf menu still offers Off / Small (≤ the ceiling) / All for anyone who wants less.
    fun carryDeclared(context: Context): String =
        prefs(context).getString(KEY_CARRY_DECLARED, "all") ?: "all"

    fun setCarryDeclared(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_CARRY_DECLARED, mode).apply()

    /** Should THIS declared-tree entry's bytes ride up this pass? The gate plus the same
     *  network courtesy the fetch side keeps: wifi-only means uploads too. */
    private fun shouldCarryDeclared(context: Context, entry: BookshelfSource.Entry): Boolean {
        val mode = carryDeclared(context)
        if (mode == "off") return false
        if (wifiOnly(context) && !unmetered(context)) return false
        return mode == "all" || entry.sizeBytes <= sizeCeilingBytes(context)
    }

    fun carry(context: Context, folder: String): LibraryPolicy.Carry =
        LibraryPolicy.Carry.of(prefs(context).getString(KEY_CARRY_PREFIX + folder, null))

    fun setCarry(context: Context, folder: String, carry: LibraryPolicy.Carry) =
        prefs(context).edit().putString(KEY_CARRY_PREFIX + folder, carry.wire).apply()

    /** "Is this network one the wifi-only toggle means?" Unmetered is the honest reading of
     *  "wifi" — a metered hotspot named WiFi is exactly what the toggle exists to protect. */
    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        cm is android.net.ConnectivityManager && !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    // ── What the shelf asks ───────────────────────────────────────────────────────────────────

    /**
     * Books the fleet has that this shelf doesn't — the ghost cards. Reads only the cache.
     *
     * MATCHED BY FILE, NOT BY PLACE. The manifest id is FOLDER-QUALIFIED (`ReadingState.keyFor`:
     * folder + name + "#" + size), which is right for a stable library and wrong the moment a
     * shelf is re-pointed: changing the declared tree from `Books/ebooks` to `Books` moves every
     * book's folder, so every id this device itself published stops matching the same file on
     * disk. Michael saw the result as "it's got cloud comics? weird" — comics he owns, offered
     * back to him from the fleet, because they had moved half an inch in the path.
     *
     * So a manifest row is also dropped when a local file has the same NAME AND SIZE anywhere on
     * the shelf. Name+size is what the file IS; the folder is only where it currently sits. A
     * genuine second copy of a book in another folder was already indistinguishable from the first
     * under this scheme, so nothing is lost by ignoring the folder here.
     */
    fun offered(context: Context, shelf: List<BookshelfSource.Entry>): List<LibraryBook> {
        val localIds = shelf.mapTo(HashSet()) { idFor(it) }
        val localFiles = shelf.mapTo(HashSet()) { "${it.name}#${it.sizeBytes}" }
        val dismissed = dismissedHere(context)
        return cachedManifest(context).filter {
            !it.isDeleted && it.id !in localIds && it.id !in dismissed && "${it.name}#${it.size}" !in localFiles
        }
    }

    fun dismissedHere(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISMISSED_HERE, emptySet()) ?: emptySet()

    private fun setDismissedHere(context: Context, ids: Set<String>) =
        prefs(context).edit().putStringSet(KEY_DISMISSED_HERE, ids).apply()

    /**
     * "Delete from this device" — the file goes, the library keeps the book, and this shelf
     * stops offering it back (the fleet still holds it; a tap on any ghost door un-dismisses).
     */
    fun deleteHere(context: Context, entry: BookshelfSource.Entry): Boolean {
        val id = idFor(entry)
        val gone = BookshelfSource.delete(context, entry)
        if (gone) setDismissedHere(context, dismissedHere(context) + id)
        return gone
    }

    /**
     * "Finished — delete everywhere": the file here, the bytes on the hub (this record's path and
     * every live twin's — same hash, the re-pointed-tree double), and a tombstone for each, which
     * on every other shelf is the "removed — keep your copy?" QUESTION, never an action. The
     * local delete happens first and synchronously (the caller re-lists the shelf); the hub side
     * rides the library-hub thread, quiet on failure — the next pass re-tombstones nothing, so a
     * dropped network simply leaves bytes on the hub under a tombstoned record (the truth you can
     * change your mind about).
     */
    fun deleteEverywhere(context: Context, entry: BookshelfSource.Entry, onDone: ((Boolean) -> Unit)? = null): Boolean {
        val app = context.applicationContext
        val id = idFor(entry)
        val gone = BookshelfSource.delete(app, entry)
        if (!gone) return false
        ackKeep(app, id)   // this device answered; the tombstone must not come back as a question here
        executor.execute {
            val ok = runCatching {
                val now = System.currentTimeMillis()
                val paths = mutableListOf<String>()
                manifestRoundTrip(app) { byId ->
                    val self = byId[id]
                    val hash = self?.hash.orEmpty()
                    val twins = byId.values.filter {
                        !it.isDeleted && (it.id == id || (hash.isNotBlank() && it.hash == hash))
                    }
                    if (twins.isEmpty() && self == null) byId[id] = LibraryBook(id = id, deletedAt = now, added = 0L)
                    for (t in twins) {
                        byId[t.id] = t.copy(deletedAt = now)
                        if (t.bytesAt > 0L) paths += t.remotePath
                    }
                }
                val svc = transport(app)
                for (path in paths) runCatching { svc?.delete(path) }
                    .onFailure { Timber.w(it, "LibraryHub: hub delete failed for $path") }
                true
            }.onFailure { Timber.w(it, "LibraryHub: deleteEverywhere failed for $id") }.getOrDefault(false)
            onDone?.let { cb -> android.os.Handler(android.os.Looper.getMainLooper()).post { cb(ok) } }
        }
        return true
    }

    /** Books tombstoned in the manifest that this device still holds and hasn't been asked about
     *  — the "removed from the library — keep your copy?" rows. */
    fun removedButLocal(
        context: Context, shelf: List<BookshelfSource.Entry>
    ): Map<String, LibraryBook> {
        val acked = prefs(context).getStringSet(KEY_ACKED_TOMBSTONES, emptySet()) ?: emptySet()
        val dead = cachedManifest(context).filter { it.isDeleted && it.id !in acked }
            .associateBy { it.id }
        return shelf.mapNotNull { e -> dead[idFor(e)]?.let { idFor(e) to it } }.toMap()
    }

    /** "Keep your copy" — remember the answer so the question isn't asked on every open. The ack
     *  is local by design: each device's person answers for that device's file. */
    fun ackKeep(context: Context, id: String) {
        val cur = prefs(context).getStringSet(KEY_ACKED_TOMBSTONES, emptySet()) ?: emptySet()
        prefs(context).edit().putStringSet(KEY_ACKED_TOMBSTONES, cur + id).apply()
    }

    // ── Push on add ───────────────────────────────────────────────────────────────────────────

    /**
     * A book just landed on this shelf through the app — ride it up. Fire-and-forget from the UI;
     * quiet on failure (the sync pass is the retry, see class doc). [folder] "" for the shelf
     * root, which is where every current add lands.
     */
    fun pushAdded(context: Context, folder: String, name: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val svc = transport(app) ?: return@execute
                val entry = BookshelfSource.list(app)
                    .firstOrNull { it.folder == folder && it.name == name } ?: return@execute
                if (!uploadBook(app, svc, entry)) return@execute
                val now = System.currentTimeMillis()
                val record = LibraryBook(
                    id = idFor(entry), name = entry.name, folder = entry.folder,
                    size = entry.sizeBytes, hash = sha1Of(app, entry).orEmpty(),
                    addedBy = deviceName(app), added = now, deletedAt = 0L,
                    bytesAt = now   // the upload above just proved them
                )
                manifestRoundTrip(app) { byId -> byId[record.id] = record }
                // An add through the app is the ruling's opening move — "downloaded to one
                // device, downloaded to all": the want is what makes the rest of the fleet pull.
                BookWantsStore.want(app, record.id, deviceName(app))
            }.onFailure { Timber.w(it, "LibraryHub: pushAdded failed for $folder/$name") }
        }
    }

    /**
     * The bytes, up — `.part` then rename, so the name a fetch asks for either doesn't exist yet
     * or is whole. A remote without rename gets a direct put instead: same landing, briefly less
     * atomic, and the orphaned `.part` is swept. The sequence itself lives on the seam
     * ([putFileCommitted]) — written once, proven transport-blind by the fake-transport test —
     * and this method keeps only what is the LIBRARY's to know: which folder ancestry to ensure.
     */
    private fun uploadBook(
        context: Context, svc: HubTransport, entry: BookshelfSource.Entry
    ): Boolean {
        // Upload wants a File; a declared-tree entry materialises through the same read cache the
        // reader already uses (keyed name+size, so this costs nothing on a re-push).
        // Note whether the cache copy pre-existed: a carry-declared pass over a comics folder
        // must not leave gigabytes behind in cache/shelf (the 141 MB cleanup, at 20× scale) —
        // a copy minted just for this upload is deleted after it.
        val cached = entry.uri != null &&
            java.io.File(java.io.File(context.cacheDir, "shelf"), "${entry.sizeBytes}-${entry.name}").exists()
        val file = BookshelfSource.materialise(context, entry) ?: return false

        // Ensure the ancestry every time — cheap (MKCOL answers 405 for "exists"; Drive answers
        // from a folder-id cache), and the 409-on-missing-parent lesson from
        // LedgerSidecarSync.push is not one to relearn per feature.
        var prefix = "books"
        svc.ensureFolder(prefix)
        for (segment in entry.folder.split('/').filter { it.isNotBlank() }) {
            prefix = "$prefix/$segment"
            svc.ensureFolder(prefix)
        }

        val ok = svc.putFileCommitted(prefix, entry.name, file)
        if (entry.uri != null && !cached) runCatching { file.delete() }
        return ok
    }

    // ── Deletes ───────────────────────────────────────────────────────────────────────────────

    /**
     * An in-app delete of an APP-DIRECTORY book: tombstone the manifest so the delete travels.
     * The caller has already deleted the local file (with its own guarded confirmation) — this
     * records the fact for the fleet, where it becomes a question on every other shelf, never an
     * action. Declared-tree books never route here: the shelf refuses to delete inside a declared
     * tree at all, so there is nothing to record.
     */
    fun recordDelete(context: Context, id: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                manifestRoundTrip(app) { byId ->
                    val now = System.currentTimeMillis()
                    val cur = byId[id]
                    if (cur != null) byId[id] = cur.copy(deletedAt = now)
                    else byId[id] = LibraryBook(id = id, deletedAt = now, added = 0L)
                }
            }.onFailure { Timber.w(it, "LibraryHub: recordDelete failed for $id") }
        }
    }

    // ── The manifest round trip ───────────────────────────────────────────────────────────────

    /**
     * Pull → merge → mutate → save → push-if-changed. Runs ON the library-hub thread (callers
     * are already there). Push only when the merged result differs from what the server had:
     * the manifest is read far more often than it changes, and an unconditional push per shelf
     * open would churn the hub's version history for nothing.
     */
    private fun manifestRoundTrip(
        context: Context, mutate: ((MutableMap<String, LibraryBook>) -> Unit)? = null
    ): List<LibraryBook> {
        val remoteText = manifestPull(context)
        val remote: List<LibraryBook> =
            if (remoteText.isNullOrBlank()) emptyList()
            else runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
        val byId = LinkedHashMap<String, LibraryBook>()
        for (b in LibraryBook.merge(cachedManifest(context), remote)) byId[b.id] = b
        mutate?.invoke(byId)
        val merged = byId.values.toList()
        saveCache(context, merged)
        if (merged != remote) {
            manifestPush(context, adapter.toJson(merged))
        }
        return merged
    }

    /**
     * Cheap read-side refresh for the shelf: pull + merge the manifest, no book bytes moved.
     * [onChanged] fires (on the library-hub thread) only when the merge actually learned
     * something, so a caller can re-render without looping on its own refresh.
     */
    fun refreshManifest(context: Context, onChanged: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val before = cachedManifest(app)
                val after = manifestRoundTrip(app)
                if (after != before) onChanged?.invoke()
            }.onFailure { Timber.w(it, "LibraryHub: manifest refresh failed") }
        }
    }

    // ── Pull on tick ──────────────────────────────────────────────────────────────────────────

    /**
     * The full pass, blocking until done — called from [UltrabridgeSyncWorker]'s coroutine so
     * WorkManager keeps the process alive while books move. Serialized through the same executor
     * as everything else here; a worker pass and a tapped fetch cannot interleave.
     */
    fun syncPassBlocking(context: Context) {
        executor.submit { runCatching { syncPass(context.applicationContext) }
            .onFailure { Timber.w(it, "LibraryHub: sync pass failed") } }.get()
    }

    private fun syncPass(context: Context) {
        val svc = transport(context) ?: return
        val shelf = runCatching { BookshelfSource.list(context) }.getOrDefault(emptyList())
        val localIds = shelf.mapTo(HashSet()) { idFor(it) }

        // One pull for the whole pass.
        val byId = LinkedHashMap<String, LibraryBook>()
        for (b in manifestRoundTrip(context)) byId[b.id] = b

        // THE WANTS — the fleet's "this should be everywhere" list (his 08-27 ruling), merged
        // once per pass. Both loops below consult it: UP answers a want with bytes this device
        // holds, DOWN fetches a want this device lacks.
        val wanted = runCatching { BookWantsStore.sync(context) }.getOrDefault(emptyList())
            .filter { !it.isDeleted }.mapTo(HashSet()) { it.id }

        // The opens sidecar rides the same tick — the shelf's period band converges fleet-wide
        // without its own worker (the 08-11 promise, kept under the 08-27 stats ruling).
        runCatching { BookOpens.sync(context) }
        runCatching { com.toolsboox.plugin.calendar.ot.ReadingSessionStore.sync(context) }

        // UP: local books the manifest has never heard of. Two grades, per the ruled seam:
        // an app-directory book carries its bytes; a declared-tree book contributes its entry
        // only (Syncthing still owns those bytes until step E). A book the manifest knows but
        // has TOMBSTONED is deliberately not re-added here — resurrecting a fleet-wide delete
        // because one device still holds a copy would make deletes impossible; the copy is the
        // person's to keep, the record is the fleet's.
        var changed = false
        val now = System.currentTimeMillis()
        val wifiOnly = wifiOnly(context)
        val unmetered = unmetered(context)
        for (entry in shelf) {
            val id = idFor(entry)
            val known = byId[id]
            if (known != null) {
                // THE WANT ANSWERED FROM THE HOLDING SIDE (his 08-27 ruling). The old
                // `containsKey → continue` meant an entry-only record could never gain bytes —
                // the book the fleet asked for stayed a dead ghost forever. A live want for a
                // book this shelf HOLDS whose bytes are unconfirmed rides up now, regardless of
                // the carry-declared gate: a request is not a mirror, and the asker is the
                // policy. Wifi courtesy still applies — this is the hourly worker, not a tap.
                if (id in wanted && known.bytesAt == 0L && !known.isDeleted &&
                    !(wifiOnly && !unmetered)
                ) {
                    if (uploadBook(context, svc, entry)) {
                        byId[id] = known.copy(bytesAt = System.currentTimeMillis())
                        changed = true
                    }
                }
                continue
            }
            // `file != null` means an app-directory (or plain-File) book — bytes ride up. A
            // uri-backed entry IS the declared tree; historically entry-only (Syncthing's bytes),
            // now gated: with the carry-declared setting on, its bytes ride up too — which is
            // what lets a mihon comic downloaded on the Palma be FETCHED on the B6 with no
            // Syncthing anywhere in the story. The manifest entry is written either way, so an
            // ungated book still ghosts honestly.
            var uploads = entry.file != null || shouldCarryDeclared(context, entry) ||
                (id in wanted && !(wifiOnly && !unmetered))
            val hash = sha1Of(context, entry).orEmpty()
            // ONE UPLOAD PER FILE, NOT PER PATH (09-13, "why is that 20 gigs?"): the Palma's
            // declared tree was re-pointed once, so every Mihon comic stands in the manifest
            // twice — `downloads/…` and `Mihon/downloads/…`, same hash — and 20 GB declared is
            // 12 GB of files. A declared copy whose hash the hub already holds under another
            // live record rides its ENTRY up and not its bytes; the fetch side reads those bytes
            // through the donor ([bytesDonor]). App-directory books still carry themselves.
            if (uploads && entry.file == null && hash.isNotBlank() &&
                byId.values.any { !it.isDeleted && it.id != id && it.hash == hash && it.bytesAt > 0L }
            ) uploads = false
            val bytesCarried = if (uploads) uploadBook(context, svc, entry) else true
            if (!bytesCarried) continue   // quiet failure — the next pass retries this add whole
            byId[id] = LibraryBook(
                id = id, name = entry.name, folder = entry.folder, size = entry.sizeBytes,
                hash = hash,
                addedBy = deviceName(context), added = now, deletedAt = 0L,
                bytesAt = if (uploads) now else 0L
            )
            changed = true
        }

        // DOWN: manifest books this shelf lacks. LibraryPolicy judges each; only FETCH moves
        // bytes, everything else stays offered and the Bookshelf draws the ghost.
        val ceiling = sizeCeilingBytes(context)
        // READ-AHEAD (the Mihon lift's downloads half, 09-02): the next two chapters of any
        // series this device is mid-read in fetch themselves — the ▶ Continue walk already
        // opens the next sibling; this makes sure it is already here when it does. Same facts
        // as the Continue tracker (position clock + name-ordered siblings), judged here so an
        // on-open folder still reads ahead in the one series being actively read. The wifi
        // courtesy and the size ceiling both hold — a 280MB chapter still waits for its tap.
        val readAhead: Set<String> = runCatching {
            val touched = ReaderPositionStore.lastReadTimes(context)
            val out = mutableSetOf<String>()
            for ((_, sibs0) in byId.values.filter { !it.isDeleted }.groupBy { it.folder }) {
                val sibs = sibs0.sortedBy { it.name.lowercase() }
                val here = sibs.filter { touched.containsKey(it.name) }
                    .maxByOrNull { touched[it.name] ?: 0L } ?: continue
                val i = sibs.indexOfFirst { it.id == here.id }
                if (i < 0) continue
                sibs.subList((i + 1).coerceAtMost(sibs.size), (i + 3).coerceAtMost(sibs.size))
                    .forEach { out += it.id }
            }
            out
        }.getOrDefault(emptySet())
        val dismissed = dismissedHere(context)
        for (book in byId.values.toList()) {
            if (book.isDeleted || book.id in localIds || book.id in dismissed) continue
            // A WANTED book bypasses policy — carry mode and the size ceiling both — exactly as
            // a tapped ghost does: the person asked, and the want is that ask made fleet-visible
            // (his 08-27 ruling; the 280 MB chapter is precisely the book this is for). The
            // wifi courtesy is the one gate kept: this is the hourly worker spending data, not
            // a person watching a progress line.
            val wantedHere = book.id in wanted && !(wifiOnly && !unmetered)
            val aheadHere = book.id in readAhead && !(wifiOnly && !unmetered) && book.size <= ceiling
            val decision = LibraryPolicy.decide(
                book.size, carry(context, book.folder), wifiOnly, unmetered, ceiling
            )
            if (wantedHere || aheadHere || decision == LibraryPolicy.Decision.FETCH) {
                // Quiet on failure: an entry-only book (bytes not on the hub yet) or a dropped
                // network leaves the ghost standing, which is the truthful render.
                if (fetchBlocking(context, svc, book, from = bytesDonor(book, byId.values)?.remotePath ?: book.remotePath)) {
                    // The fetch just proved the hub holds whole bytes — confirm it for the
                    // fleet, which is what stops another device's answering upload.
                    if (book.bytesAt == 0L) {
                        byId[book.id] = book.copy(bytesAt = System.currentTimeMillis())
                        changed = true
                    }
                } else {
                    Timber.i("LibraryHub: ${book.id} offered — fetch didn't land this pass")
                }
            }
        }

        if (changed) {
            val merged = byId.values.toList()
            saveCache(context, merged)
            manifestPush(context, adapter.toJson(merged))
        }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────────────────────

    /** A tapped ghost card. Runs on the hub thread; [onDone] (main thread) reports the landing.
     *  A tap bypasses policy on purpose — the person asking IS the policy. */
    fun fetch(context: Context, book: LibraryBook, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (book.id in dismissedHere(app)) setDismissedHere(app, dismissedHere(app) - book.id)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        executor.execute {
            val ok = runCatching {
                val svc = transport(app) ?: return@runCatching false
                val from = if (book.bytesAt > 0L) book.remotePath
                    else bytesDonor(book, cachedManifest(app))?.remotePath ?: book.remotePath
                fetchBlocking(app, svc, book, from = from)
            }.getOrDefault(false)
            // A landed tap proved the hub's bytes — confirm for the fleet (stops answering
            // uploads); a needed record may predate the field, so 0 → now is the only write.
            if (ok && book.bytesAt == 0L) runCatching {
                manifestRoundTrip(app) { byId ->
                    byId[book.id]?.takeIf { it.bytesAt == 0L }?.let {
                        byId[book.id] = it.copy(bytesAt = System.currentTimeMillis())
                    }
                }
            }
            main.post { onDone(ok) }
        }
    }

    /**
     * Stream the book down INTO the shelf, verifying as it lands: bytes are counted and sha1'd
     * on the way through, and a mismatch throws BEFORE [BookshelfSource.writeInto]'s rename —
     * so a truncated or corrupted transfer never exists under the book's real name. The manifest
     * already told us what the file should be; that is a stronger check than any header.
     */
    /**
     * The live record whose bytes the hub holds for the SAME file as [book] — same non-blank
     * hash, confirmed bytes — when [book] itself is entry-only. The second path of a re-pointed
     * declared tree reads through the first; nothing is uploaded twice. Null = no such twin.
     */
    fun bytesDonor(book: LibraryBook, all: Collection<LibraryBook>): LibraryBook? =
        if (book.bytesAt > 0L || book.hash.isBlank()) null
        else all.firstOrNull { !it.isDeleted && it.id != book.id && it.hash == book.hash && it.bytesAt > 0L }

    private fun fetchBlocking(
        context: Context, svc: HubTransport, book: LibraryBook, from: String = book.remotePath
    ): Boolean = svc.getStream(from) { input ->
        val landed = BookshelfSource.writeInto(context, book.folder, book.name) { out ->
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                out.write(buf, 0, n)
                copied += n
            }
            if (copied != book.size) {
                throw IOException("short/long landing: $copied of ${book.size} bytes")
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (book.hash.isNotBlank() && hex != book.hash) {
                throw IOException("hash mismatch for ${book.id}")
            }
        }
        if (!landed) throw IOException("shelf write failed for ${book.id}")
    }
}
