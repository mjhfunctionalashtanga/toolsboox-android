package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
        val title: String get() = name.substringBeforeLast('.', name)
        val extension: String get() = name.substringAfterLast('.', "").lowercase()
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
     * [maxDepth] bounds the walk. A declared folder can be anything — pointed at the storage root
     * it would be an unbounded recursive scan behind the UI thread of an e-ink device — and three
     * levels is deep enough for the way a library is actually arranged (Author/Series/Book).
     */
    fun list(context: Context, maxDepth: Int = 3): List<Entry> {
        val tree = declaredTree(context)
        val out = mutableListOf<Entry>()
        if (tree == null) {
            walkFiles(defaultDir(context), "", maxDepth, out)
        } else {
            val root = DocumentFile.fromTreeUri(context, tree)
            if (root != null) walkTree(root, "", maxDepth, out)
        }
        return out.sortedByDescending { it.lastModified }
    }

    /** The subfolders that hold books, for the shelf's folder rows. */
    fun folders(entries: List<Entry>): List<String> =
        entries.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()

    private fun walkFiles(dir: File, prefix: String, depth: Int, out: MutableList<Entry>) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) {
                if (depth > 0) walkFiles(f, if (prefix.isEmpty()) f.name else "$prefix/${f.name}", depth - 1, out)
            } else if (isBook(f.name)) {
                out += Entry(f.name, prefix, f.length(), f.lastModified(), f, null)
            }
        }
    }

    private fun walkTree(dir: DocumentFile, prefix: String, depth: Int, out: MutableList<Entry>) {
        for (d in dir.listFiles()) {
            val name = d.name ?: continue
            if (d.isDirectory) {
                if (depth > 0) walkTree(d, if (prefix.isEmpty()) name else "$prefix/$name", depth - 1, out)
            } else if (isBook(name)) {
                out += Entry(name, prefix, d.length(), d.lastModified(), null, d.uri)
            }
        }
    }

    /**
     * Write a new book ONTO THE SHELF, wherever the shelf currently is.
     *
     * This has to follow the declaration or the feature is a lie: download a book while a Syncthing
     * folder is declared, write it into app storage, and it does not appear on the shelf you are
     * looking at. The book would exist and be invisible, which is the worst of the three possible
     * outcomes.
     *
     * Returns whether it landed. Streams rather than buffering — a book is tens of megabytes and
     * this runs on a device with little to spare.
     */
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
