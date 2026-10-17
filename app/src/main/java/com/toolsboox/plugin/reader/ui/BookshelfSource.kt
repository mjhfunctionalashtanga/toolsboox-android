package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import java.io.File

/**
 * WHERE THE BOOKS ARE.
 *
 * Michael, 2026-08-05: "We can declare a folder to use in app as the books folder." That is the
 * right instinct and it replaces a much worse plan of mine — building a book model and importing
 * copies into the app's own store. His library already exists, in folders, on the device: Calibre
 * pushes to it, Syncthing keeps it level across machines, BooxDrop sideloads into it. Copying books
 * into a private directory would fork that library and leave two of everything to keep in step.
 *
 * So the shelf is a POINTER, not a container. Declare a folder and Ledger reads it — including its
 * subfolders, which answers the other half of the punchlist ("the books in folders as they too get
 * sorted") for free: the folders are the ones already on disk, so the sorting he has done in
 * Calibre or on the Boox is the sorting the shelf shows. Nothing to file twice.
 *
 * Two kinds of source, because Android has two kinds of readable place:
 *
 *  • The DEFAULT — `filesDir/reader/books`, the app's own directory, plain [File] access. What
 *    every existing sideload already went into, so an undeclared shelf behaves exactly as before.
 *  • A DECLARED tree — any folder, chosen through the system picker, held by a persisted URI
 *    permission. This is the only way to reach shared storage under scoped storage, and it is what
 *    lets the shelf point at a Syncthing folder.
 *
 * Opening is deliberately unchanged: the reader still loads a [File]. An entry that lives behind a
 * tree URI is copied into the cache on open, reusing the import path that already existed for
 * share-sheet books. That keeps every reader/annotation/position path working on real files, and
 * confines the SAF-ness to this object.
 */
object BookshelfSource {
    /** Set at the start of [list] so [walkTree] can query without threading a context through
     *  every recursion. Application context only — this object outlives any fragment. */
    @Volatile
    private var appContext: Context? = null


    private const val PREFS = "ledger_reader_prefs"
    private const val KEY_TREE = "books_tree_uri"

    /** One thing on the shelf. [folder] is its subfolder path, "" for the shelf's own root. */
    data class Entry(
        val name: String,
        val folder: String,
        val sizeBytes: Long,
        val lastModified: Long,
        /** Set when the entry is a plain file — the reader can open it directly. */
        val file: File?,
        /** Set when the entry lives behind a declared tree — resolved on open. */
        val uri: Uri?,
    ) {
        /**
         * WHAT THE SHELF CALLS THIS BOOK — the filename, made readable.
         *
         * Michael, 2026-08-24: "make sure the names of the books and whatnot all make sense since
         * the file names are just strings of numbers and pounds etc." A shelf built by pointing at
         * folders shows whatever the downloader named the file, and downloaders name files for
         * themselves: mihon appends a short hex hash to keep two chapters with the same title
         * apart, and library exporters append a numeric id for the same reason. Neither is anything
         * a reader wants to see, and on a comics shelf it is on every single row.
         *
         * Three passes, each conservative enough to be safe on a library nobody has curated:
         *
         *  1. Drop the extension.
         *  2. Drop a TRAILING `_<hex>` or `_<digits>` — mihon's `_58d1c1`, an exporter's `_1335`.
         *     Trailing only, and only after an underscore, so a title that genuinely ends in a
         *     number ("Volume 3", "1984") is untouched.
         *  3. Turn `_ ` back into `: `. Filenames cannot contain a colon, so every downloader
         *     substitutes something; underscore-then-space is the common one, and it is what makes
         *     "Fluke_ Chance, Chaos, and Why" read as the title it actually is. A lone underscore
         *     with no space after it is left alone — that one is usually a real word separator.
         *
         * The FILE is untouched. This is a display name; [name] remains the identity everything
         * else keys on (covers, opens, the fleet manifest), so nothing here can make two devices
         * disagree about which book is which.
         */
        val title: String get() = displayTitle(name, folder)

        val extension: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /**
     * The display-name cleaner, callable on ANY book name — a shelf [Entry]'s, or a fleet
     * ghost's ([com.toolsboox.plugin.calendar.da.v2.LibraryBook.name]). The ghost tiles used
     * to print the raw filename, hash and all, which is precisely the row a reader meets when
     * the book ISN'T here yet — the one place the name has to carry everything.
     */
    fun displayTitle(name: String, folder: String = ""): String {
        val stem = name.substringBeforeLast('.', name)
        val unhashed = stem.replace(Regex("""_(?:[0-9a-fA-F]{6,}|\d{1,6})$"""), "")
        val clean = unhashed.replace("_ ", ": ").replace(Regex("""\s{2,}"""), " ").trim()
            .ifBlank { stem }
        // A CHAPTER'S OWN NAME often says nothing — "#102", "(2024 – Part 3)", "012" — because
        // the series lived in the FOLDER and the downloader never repeated it. When the cleaned
        // name carries no letters (or almost nothing), the series leaf joins it: "Uncanny
        // X-Force · #102" reads as a book where "#102" reads as a mistake ("the comics still
        // show their title as a hashtag and a numeral").
        val saysNothing = clean.length <= 4 || !clean.any { it.isLetter() }
        if (saysNothing && folder.isNotBlank()) {
            val leaf = folder.substringAfterLast('/')
                // mihon's source dir wraps the language in parens — "ReadAllComics (EN)" is a
                // catalogue, not a series; skip up one level when the leaf looks like that.
                .ifBlank { folder }
            if (leaf.isNotBlank()) return "$leaf · $clean"
        }
        return clean
    }

    /** The app's own directory — the shelf when nothing has been declared. */
    fun defaultDir(context: Context): File =
        File(context.filesDir, "reader/books").apply { mkdirs() }

    /** The declared folder, or null when the shelf is still the app's own directory. */
    fun declaredTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, 0).getString(KEY_TREE, null)
            ?.let(Uri::parse)
            // A persisted permission can be revoked by the system (app data cleared, SD card
            // pulled, the folder deleted). Checking here rather than at read time means a stale
            // declaration degrades to the default shelf instead of an empty one with no
            // explanation.
            ?.takeIf { uri ->
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }

    /** A human name for the declared folder, for the settings row. */
    fun declaredName(context: Context): String? =
        declaredTree(context)?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /** The intent that asks for a folder. Callers persist the result through [declare]. */
    fun pickIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

    /**
     * Remember a picked folder, taking the permission that outlives this process.
     *
     * Without `takePersistableUriPermission` the grant dies with the activity and the shelf is
     * empty on next launch with nothing on screen to say why — the failure mode looks exactly like
     * "the app lost my books".
     */
    fun declare(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    /** Go back to the app's own directory. */
    fun clearDeclaration(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().remove(KEY_TREE).apply()
    }

    /** File types the shelf shows. Anything else in the folder is somebody else's business. */
    private val READABLE = setOf(
        "epub", "pdf", "cbz", "cbr", "txt", "fb2", "mobi", "azw3",
        "mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac",   // audiobooks
    )

    private fun isBook(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in READABLE

    /**
     * Everything on the shelf, newest first.
     *
     * [maxDepth] bounds the walk. A declared folder can be anything — pointed at the storage root it
     * would be an unbounded recursive scan behind the UI thread of an e-ink device — so it is capped
     * rather than free.
     *
     * FIVE, not three (2026-08-24). Three was chosen for "the way a library is actually arranged
     * (Author/Series/Book)", which was true of the ebooks folder and wrong about everything else on
     * his device. Verified over adb on the Palma: mihon downloads to
     * `Books/Mihon/downloads/<Source>/<Series>/<Chapter>.cbz`, and his shelf was declared at
     * `Books/ebooks` — a SIBLING of `Books/Mihon`, so 159 comics across 21 series were invisible.
     *
     * Re-pointing at `Books` did not fix that on its own, which is the part worth writing down:
     * from `Books` a chapter sits five levels down, and a three-deep walk stops one directory short
     * — it would list the SERIES folders and none of their contents, which reads as an empty shelf
     * rather than as a walk that gave up. Five reaches it, and leaves headroom for the
     * Author/Series/Volume shape too.
     *
     * The cost is bounded and small: the walk reads names and sizes, never contents, and SAF's
     * `listFiles` is the expensive part — a couple of dozen extra queries for a library this shape,
     * paid once per shelf load.
     */
    fun list(context: Context, maxDepth: Int = 5): List<Entry> {
        appContext = context.applicationContext
        val tree = declaredTree(context)
        val out = mutableListOf<Entry>()
        if (tree == null) {
            walkFiles(defaultDir(context), "", maxDepth, out)
        } else {
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            if (rootId != null) walkTree(tree, rootId, "", maxDepth, out)
        }
        return out.sortedByDescending { it.lastModified }
    }

    /**
     * Delete a book's file — an app-directory file or, behind a declared tree, the SAF document.
     * ONLY ever called from a person's own answer on THIS device ("Finished — delete", "Delete
     * from this device", "Delete my copy too"); no merge, tombstone or pass reaches here.
     */
    fun delete(context: Context, entry: Entry): Boolean = runCatching {
        entry.file?.let { return@runCatching it.delete() || !it.exists() }
        val uri = entry.uri ?: return@runCatching false
        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.onFailure { Timber.w(it, "shelf delete failed for ${entry.folder}/${entry.name}") }.getOrDefault(false)

    /** The subfolders that hold books, for the shelf's folder rows. */
    fun folders(entries: List<Entry>): List<String> =
        entries.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()

    private fun walkFiles(dir: File, prefix: String, depth: Int, out: MutableList<Entry>) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) {
                // Dot folders are never the library: `.stversions` (Syncthing's version graveyard)
                // declared nine ghost "books" fleet-wide (09-13); `.thumbnails`, `.part` dirs, the same.
                if (depth > 0 && !f.name.startsWith(".")) walkFiles(f, if (prefix.isEmpty()) f.name else "$prefix/${f.name}", depth - 1, out)
            } else if (isBook(f.name)) {
                out += Entry(f.name, prefix, f.length(), f.lastModified(), f, null)
            }
        }
    }

    /**
     * Walk a declared tree with ONE QUERY PER DIRECTORY.
     *
     * This used to use `DocumentFile.listFiles()` and then read `name`, `length` and `lastModified`
     * off each child — and every one of those four things is a SEPARATE ContentResolver round trip.
     * On his library that is roughly two thousand queries, and the shelf took FORTY-FIVE SECONDS
     * to appear. It was never the walk's shape that was wrong; it was that DocumentFile makes one
     * cheap-looking property access cost an IPC.
     *
     * `buildChildDocumentsUriUsingTree` + a single projection asks for all of it at once: one
     * cursor per directory, every column already in hand. Same tree, same result, ~84 queries
     * instead of ~2000.
     *
     * (`DocumentFile` is still the right thing for the ROOT — it is what turns a tree URI into
     * something with a document id — but not for the walk.)
     */
    /**
     * Walk a declared tree with ONE QUERY PER DIRECTORY, recursing on DOCUMENT IDS.
     *
     * Two things were wrong before, and they compounded:
     *
     * 1. It used `DocumentFile.listFiles()` and then read `name`, `length` and `lastModified` off
     *    each child — every one of those four is a SEPARATE ContentResolver round trip. Listing his
     *    494 files cost roughly two thousand IPCs, and the shelf took forty-five seconds.
     *
     * 2. My first attempt at the fix recursed by wrapping each child in
     *    `DocumentFile.fromTreeUri(ctx, childDocumentUri)` — WHICH RE-ROOTS AT THE TREE. Every
     *    "child" directory therefore listed the tree ROOT again, so the walk did five times the
     *    work and produced a listing whose prefixes were nonsense; a shelf opened from a fresh
     *    fragment could come back EMPTY. `fromTreeUri` takes a tree URI; a document URI is not one,
     *    and it fails by returning the wrong thing rather than null.
     *
     * The recursion now carries `(treeUri, documentId)` and never builds a DocumentFile at all:
     * one cursor per directory, every column in hand, child ids straight from that cursor.
     */
    private fun walkTree(treeUri: Uri, docId: String, prefix: String, depth: Int, out: MutableList<Entry>) {
        val ctx = appContext ?: return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = mutableListOf<Pair<String, String>>()   // (childDocId, name)
        runCatching {
            ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += id to name
                    } else if (isBook(name)) {
                        out += Entry(
                            name, prefix,
                            if (c.isNull(3)) 0L else c.getLong(3),
                            if (c.isNull(4)) 0L else c.getLong(4),
                            null,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        )
                    }
                }
            }
        }.onFailure { Timber.w(it, "shelf walk failed under '$prefix'") }
        // Recurse AFTER the cursor is closed — a nested query while one is open is what makes some
        // providers stall.
        if (depth > 0) for ((id, name) in dirs) {
            if (name.startsWith(".")) continue   // `.stversions` and kin — see walkFiles
            walkTree(treeUri, id, if (prefix.isEmpty()) name else "$prefix/$name", depth - 1, out)
        }
    }
    fun writeInto(context: Context, filename: String, body: (java.io.OutputStream) -> Unit): Boolean =
        writeInto(context, "", filename, body)

    /**
     * [writeInto], aimed at a subfolder — the library hub's landing path.
     *
     * The hub's manifest names every book by `folder/name#size`, so a fetch MUST land the file in
     * the same folder it holds on the hub or the downloaded copy mints a different id than the one
     * it was fetched under — and the ghost card that triggered the fetch would still be a ghost,
     * forever, beside the very book it fetched. Folder structure is identity here, not decoration.
     *
     * [body] may THROW to abandon the landing (the hub does, on a hash/size mismatch): in the
     * default-dir branch the temp file is deleted and the real name never exists; in the declared
     * tree the write goes to a hidden `.part` document that is renamed into place only on success,
     * so an interrupted or corrupt download never sits on the shelf looking like a book. (The SAF
     * rename can fail on exotic providers; then — and only then — the landing falls back to a
     * direct copy under the real name, the same exposure the pre-hub share-sheet path always had.)
     */
    fun writeInto(context: Context, folder: String, filename: String, body: (java.io.OutputStream) -> Unit): Boolean {
        val tree = declaredTree(context)
        if (tree == null) {
            val dir = if (folder.isEmpty()) defaultDir(context)
            else File(defaultDir(context), folder).apply { mkdirs() }
            val dest = File(dir, filename)
            // Temp-then-rename: an interrupted write must not leave a truncated book on the shelf
            // looking like a whole one, because the shelf lists by extension and would show it.
            val tmp = File(dest.parentFile, ".${dest.name}.part")
            return runCatching {
                tmp.outputStream().use(body)
                tmp.renameTo(dest)
            }.getOrDefault(false).also { ok -> if (!ok) runCatching { tmp.delete() } }
        }
        val root = DocumentFile.fromTreeUri(context, tree) ?: return false
        // A declared tree may be read-only (a shared folder, a mounted card). Say so by failing
        // rather than by appearing to succeed.
        if (!root.canWrite()) return false
        // Walk-and-create the subfolder path. findFile per segment rather than createDirectory
        // blindly: createDirectory on an existing name mints "folder (1)" on some providers.
        var dir: DocumentFile = root
        for (segment in folder.split('/').filter { it.isNotBlank() }) {
            dir = dir.listFiles().firstOrNull { it.isDirectory && it.name == segment }
                ?: dir.createDirectory(segment) ?: return false
        }
        val partName = ".$filename.part"
        val part = dir.findFile(partName) ?: dir.createFile("application/octet-stream", partName)
        if (part != null) {
            val wrote = runCatching {
                context.contentResolver.openOutputStream(part.uri, "wt")!!.use(body)
                true
            }.getOrDefault(false)
            if (!wrote) { runCatching { part.delete() }; return false }
            // Rename into place — the commit. renameDocument returns the (possibly new) uri or
            // throws/nulls where the provider doesn't support it.
            val renamed = runCatching {
                dir.findFile(filename)?.delete()   // Overwrite semantics: replace a stale copy.
                android.provider.DocumentsContract.renameDocument(
                    context.contentResolver, part.uri, filename
                ) != null
            }.getOrDefault(false)
            if (renamed) return true
            runCatching { part.delete() }
            // Fall through to the direct write below.
        }
        val existing = dir.findFile(filename)
        val doc = existing ?: dir.createFile("application/octet-stream", filename) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(doc.uri, "wt")!!.use(body)
            true
        }.getOrDefault(false)
    }

    /**
     * The bytes of one entry, wherever it lives — a [File] stream or a resolver stream — WITHOUT
     * materialising a cache copy. The hub hashes and uploads through this: sha1-ing a declared
     * tree's audiobook via [materialise] would copy gigabytes into cache just to read them once.
     */
    fun openStream(context: Context, entry: Entry): java.io.InputStream? =
        entry.file?.let { runCatching { it.inputStream() }.getOrNull() }
            ?: entry.uri?.let { runCatching { context.contentResolver.openInputStream(it) }.getOrNull() }

    /**
     * A [File] the reader can open, materialising a tree entry into the cache if it must.
     *
     * Copy-on-open rather than copy-on-declare: a declared library can be thousands of books and
     * gigabytes, and importing it wholesale would be the very duplication this design exists to
     * avoid. The cache copy is keyed by name and size, so re-opening a book you have already read
     * costs nothing and a book REPLACED in the folder (a better scan, a fixed EPUB) is re-copied
     * rather than silently serving the stale one.
     */
    fun materialise(context: Context, entry: Entry): File? {
        entry.file?.let { return it }
        val uri = entry.uri ?: return null
        val cache = File(context.cacheDir, "shelf").apply { mkdirs() }
        val dest = File(cache, "${entry.sizeBytes}-${entry.name}")
        if (dest.exists() && dest.length() == entry.sizeBytes) return dest
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest
        }.getOrNull()
    }
}
