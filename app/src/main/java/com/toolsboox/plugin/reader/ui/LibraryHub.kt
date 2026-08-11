package com.toolsboox.plugin.reader.ui

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.toolsboox.plugin.calendar.da.v2.LibraryBook
import com.toolsboox.plugin.calendar.nw.LedgerSidecarSync
import com.toolsboox.plugin.calendar.nw.UltrabridgeWebDavService
import okhttp3.MediaType.Companion.toMediaType
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * THE LIBRARY HUB — the library as a fleet property, not a device property.
 *
 * Build B of the 2026-08-11 Listen/Library design. The hub (the same WebDAV root the day sync and
 * every sidecar already ride) grows two things:
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
 * design step E), and NOTHING here ever deletes inside a declared tree: not a tombstone, not a
 * confirmation, nothing — the tree belongs to whatever put the books there. A fetch of an
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
    private const val PREFS = "ledger_reader_prefs"
    private const val KEY_WIFI_ONLY = "library_wifi_only"
    private const val KEY_CEILING_MB = "library_size_ceiling_mb"
    private const val KEY_CARRY_PREFIX = "library_carry:"
    private const val KEY_ACKED_TOMBSTONES = "library_tombstones_acked"

    private val OCTET = "application/octet-stream".toMediaType()

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

    /** Books the fleet has that this shelf doesn't — the ghost cards. Reads only the cache. */
    fun offered(context: Context, shelf: List<BookshelfSource.Entry>): List<LibraryBook> {
        val localIds = shelf.mapTo(HashSet()) { idFor(it) }
        return cachedManifest(context).filter { !it.isDeleted && it.id !in localIds }
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
                val svc = LedgerSidecarSync.service(app) ?: return@execute
                val entry = BookshelfSource.list(app)
                    .firstOrNull { it.folder == folder && it.name == name } ?: return@execute
                if (!uploadBook(app, svc, entry)) return@execute
                val record = LibraryBook(
                    id = idFor(entry), name = entry.name, folder = entry.folder,
                    size = entry.sizeBytes, hash = sha1Of(app, entry).orEmpty(),
                    addedBy = deviceName(app), added = System.currentTimeMillis(), deletedAt = 0L
                )
                manifestRoundTrip(app) { byId -> byId[record.id] = record }
            }.onFailure { Timber.w(it, "LibraryHub: pushAdded failed for $folder/$name") }
        }
    }

    /**
     * The bytes, up — `.part` then MOVE, so the name a fetch asks for either doesn't exist yet or
     * is whole. A server without MOVE (405/501) gets a direct PUT instead: same landing, briefly
     * less atomic, and the orphaned `.part` is swept.
     */
    private fun uploadBook(
        context: Context, svc: UltrabridgeWebDavService, entry: BookshelfSource.Entry
    ): Boolean {
        // Upload wants a File; a declared-tree entry materialises through the same read cache the
        // reader already uses (keyed name+size, so this costs nothing on a re-push).
        val file = BookshelfSource.materialise(context, entry) ?: return false

        // MKCOL the ancestry every time — cheap (405 = exists), and the 409-on-missing-parent
        // lesson from LedgerSidecarSync.push is not one to relearn per feature.
        var prefix = "books"
        svc.ensureDirectory(prefix)
        for (segment in entry.folder.split('/').filter { it.isNotBlank() }) {
            prefix = "$prefix/$segment"
            svc.ensureDirectory(prefix)
        }

        val finalPath = "$prefix/${entry.name}"
        val partPath = "$prefix/.${entry.name}.part"
        if (!svc.upload(file, partPath, OCTET)) return false
        if (svc.move(partPath, finalPath)) return true
        // No MOVE on this server: PUT the real name directly, sweep our temp.
        val direct = svc.upload(file, finalPath, OCTET)
        runCatching { svc.delete(partPath) }
        return direct
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
        val remoteText = LedgerSidecarSync.pull(context, MANIFEST_PATH)
        val remote: List<LibraryBook> =
            if (remoteText.isNullOrBlank()) emptyList()
            else runCatching { adapter.fromJson(remoteText) }.getOrNull() ?: emptyList()
        val byId = LinkedHashMap<String, LibraryBook>()
        for (b in LibraryBook.merge(cachedManifest(context), remote)) byId[b.id] = b
        mutate?.invoke(byId)
        val merged = byId.values.toList()
        saveCache(context, merged)
        if (merged != remote) {
            LedgerSidecarSync.push(context, MANIFEST_PATH, adapter.toJson(merged))
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
        val svc = LedgerSidecarSync.service(context) ?: return
        val shelf = runCatching { BookshelfSource.list(context) }.getOrDefault(emptyList())
        val localIds = shelf.mapTo(HashSet()) { idFor(it) }

        // One pull for the whole pass.
        val byId = LinkedHashMap<String, LibraryBook>()
        for (b in manifestRoundTrip(context)) byId[b.id] = b

        // UP: local books the manifest has never heard of. Two grades, per the ruled seam:
        // an app-directory book carries its bytes; a declared-tree book contributes its entry
        // only (Syncthing still owns those bytes until step E). A book the manifest knows but
        // has TOMBSTONED is deliberately not re-added here — resurrecting a fleet-wide delete
        // because one device still holds a copy would make deletes impossible; the copy is the
        // person's to keep, the record is the fleet's.
        var changed = false
        val now = System.currentTimeMillis()
        for (entry in shelf) {
            val id = idFor(entry)
            if (byId.containsKey(id)) continue
            // `file != null` means an app-directory (or plain-File) book — bytes ride up. A
            // uri-backed entry IS the declared tree, and the scan carries its entry only.
            val bytesCarried = if (entry.file != null) uploadBook(context, svc, entry)
            else true   // entry-only: the seam
            if (!bytesCarried) continue   // quiet failure — the next pass retries this add whole
            byId[id] = LibraryBook(
                id = id, name = entry.name, folder = entry.folder, size = entry.sizeBytes,
                hash = sha1Of(context, entry).orEmpty(),
                addedBy = deviceName(context), added = now, deletedAt = 0L
            )
            changed = true
        }

        // DOWN: manifest books this shelf lacks. LibraryPolicy judges each; only FETCH moves
        // bytes, everything else stays offered and the Bookshelf draws the ghost.
        val wifiOnly = wifiOnly(context)
        val unmetered = unmetered(context)
        val ceiling = sizeCeilingBytes(context)
        for (book in byId.values) {
            if (book.isDeleted || book.id in localIds) continue
            val decision = LibraryPolicy.decide(
                book.size, carry(context, book.folder), wifiOnly, unmetered, ceiling
            )
            if (decision == LibraryPolicy.Decision.FETCH) {
                // Quiet on failure: an entry-only book (bytes not on the hub yet) or a dropped
                // network leaves the ghost standing, which is the truthful render.
                if (!fetchBlocking(context, svc, book)) {
                    Timber.i("LibraryHub: ${book.id} offered — fetch didn't land this pass")
                }
            }
        }

        if (changed) {
            val merged = byId.values.toList()
            saveCache(context, merged)
            LedgerSidecarSync.push(context, MANIFEST_PATH, adapter.toJson(merged))
        }
    }

    // ── Fetch ─────────────────────────────────────────────────────────────────────────────────

    /** A tapped ghost card. Runs on the hub thread; [onDone] (main thread) reports the landing.
     *  A tap bypasses policy on purpose — the person asking IS the policy. */
    fun fetch(context: Context, book: LibraryBook, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        executor.execute {
            val ok = runCatching {
                val svc = LedgerSidecarSync.service(app) ?: return@runCatching false
                fetchBlocking(app, svc, book)
            }.getOrDefault(false)
            main.post { onDone(ok) }
        }
    }

    /**
     * Stream the book down INTO the shelf, verifying as it lands: bytes are counted and sha1'd
     * on the way through, and a mismatch throws BEFORE [BookshelfSource.writeInto]'s rename —
     * so a truncated or corrupted transfer never exists under the book's real name. The manifest
     * already told us what the file should be; that is a stronger check than any header.
     */
    private fun fetchBlocking(
        context: Context, svc: UltrabridgeWebDavService, book: LibraryBook
    ): Boolean = svc.downloadTo(book.remotePath) { input ->
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
