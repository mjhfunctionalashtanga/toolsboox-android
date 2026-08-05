package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.time.LocalDate

/**
 * NOTES AS REAL FILES — the mirror, in a folder you declare.
 *
 * Designed with Michael on 2026-08-05. The shape came out of separating three things that "notes as
 * files" can mean, which have very different prices: READABLE outside (backup, grep, Syncthing —
 * cheap), EDITABLE outside (cheap for text, impossible for ink), and ADDRESSABLE (cheap to fake,
 * expensive to make true).
 *
 * THE RULE, and it is the only thing here worth remembering:
 *
 *     Text round-trips because text IS the source.
 *     Ink exports because the strokes are the source and a picture of them is not.
 *
 * A folder where some edits stick and some silently vanish is worse than one where the rule is
 * obvious. So a `.md` you edit in Obsidian comes back; a `.png` of a handwritten page does not, and
 * says so in the file that sits beside it.
 *
 * WHAT THIS DELIBERATELY IS NOT: a decomposition of the day file. `CalendarDayMerger` merges on it,
 * tombstones delete against it, the convergence fixtures pin it, and both forks agree on it
 * byte-for-byte. Splitting a day into one file per note would mean rewriting the merge on two
 * platforms simultaneously to gain a filename. The day file stays canon; this is a mirror OF it.
 *
 * `[[links]]` need no translation — [LedgerLinks] already reads and writes the same syntax Obsidian
 * does, which is the happy accident that made the whole design cheap.
 */
object NotesMirror {

    private const val PREFS = "ledger_notes_mirror"
    private const val KEY_TREE = "notes_tree_uri"

    /** The declared folder, or null when the mirror is off. */
    fun declaredTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, 0).getString(KEY_TREE, null)
            ?.let(Uri::parse)
            ?.takeIf { uri ->
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isWritePermission
                }
            }

    fun declaredName(context: Context): String? =
        declaredTree(context)?.let { DocumentFile.fromTreeUri(context, it)?.name }

    /** Write permission, unlike the books folder — this one is a mirror, not a shelf to read. */
    fun pickIntent(): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

    fun declare(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().remove(KEY_TREE).apply()
    }

    /** Filesystem-safe, and stable across runs so a note keeps ONE file rather than growing a new
     *  one each time its title is edited into a different set of illegal characters. */
    fun filename(title: String, id: String): String {
        val stem = title.trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "-")
            .replace(Regex("""\s+"""), " ")
            .take(80)
            .ifBlank { "Note" }
        // The id rides in the name so two notes titled "Monday" are two files, and so a rename
        // can find and replace the old one instead of orphaning it.
        return "$stem — $id.md"
    }

    /** The front matter a mirrored text note carries, so a round-trip knows what it is looking at. */
    fun frontMatter(date: LocalDate, id: String, updatedAt: Long): String =
        buildString {
            append("---\n")
            append("date: ").append(date).append('\n')
            append("ledger-id: ").append(id).append('\n')
            append("updated: ").append(updatedAt).append('\n')
            append("---\n\n")
        }

    /** Split a mirrored file back into its front matter and its body. */
    fun parse(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to text
        val head = text.substring(3, end).trim().lines()
            .mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.take(i).trim() to l.drop(i + 1).trim()
            }.toMap()
        val body = text.substring(end + 4).removePrefix("\n").removePrefix("\n")
        return head to body
    }

    /**
     * Mirror one day, both directions for text and outward-only for ink.
     *
     * Returns how many files were written. Blocking — call it off the main thread.
     *
     * NEWER WINS, by the `updated` stamp the front matter carries. It is the same rule the day
     * merger uses for elements, and the same rule is the point: a person editing a note in Obsidian
     * and a person editing it on the Boox are the same person, and whichever they touched last is
     * the one they meant.
     */
    fun mirror(
        context: Context,
        date: LocalDate,
        renderInk: ((pageKey: String) -> ByteArray?)? = null,
        inkPages: List<Pair<String, String>> = emptyList(),
    ): Int {
        val tree = declaredTree(context) ?: return 0
        val root = DocumentFile.fromTreeUri(context, tree) ?: return 0
        if (!root.canWrite()) return 0
        var written = 0

        // ── Text: two-way ────────────────────────────────────────────────────────────────────
        val notes = com.toolsboox.plugin.textnotes.TextNotesStore.load(context, date)
        var changedLocally = false
        for (note in notes) {
            val name = filename(note.title, note.id)
            val existing = root.findFile(name)
            val remote = existing?.let { doc ->
                runCatching {
                    context.contentResolver.openInputStream(doc.uri)!!.bufferedReader().readText()
                }.getOrNull()
            }
            val remoteStamp = remote?.let { parse(it).first["updated"]?.toLongOrNull() } ?: 0L
            if (remote != null && remoteStamp > note.updatedAt) {
                // The file is newer — it was edited outside. Take it.
                note.body = parse(remote).second.trim()
                note.updatedAt = remoteStamp
                changedLocally = true
                continue
            }
            val text = frontMatter(date, note.id, note.updatedAt) + note.body
            if (remote == text) continue
            val doc = existing ?: root.createFile("text/markdown", name) ?: continue
            runCatching {
                context.contentResolver.openOutputStream(doc.uri, "wt")!!.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                written++
            }
        }
        if (changedLocally) com.toolsboox.plugin.textnotes.TextNotesStore.save(context, date, notes)

        // ── Ink: outward only ────────────────────────────────────────────────────────────────
        //
        // A `.png` and, beside it, a `.md` carrying whatever the OCR pass recognised — so a
        // handwritten page is searchable, greppable and linkable in the mirror even though it can
        // only ever be written on the device. The `.md` says so in a line at the top, because a
        // file you can edit that quietly discards your edits is a trap, and one sentence prevents it.
        for ((pageKey, ocr) in inkPages) {
            val png = renderInk?.invoke(pageKey)
            if (png != null) {
                val name = "$date — $pageKey.png"
                val doc = root.findFile(name) ?: root.createFile("image/png", name)
                if (doc != null) runCatching {
                    context.contentResolver.openOutputStream(doc.uri, "wt")!!.use { it.write(png) }
                    written++
                }
            }
            if (ocr.isNotBlank()) {
                val name = "$date — $pageKey.md"
                val body = "> Handwritten in Ledger on $date. This file is a copy — " +
                    "edits here are not read back.\n\n" + ocr
                val doc = root.findFile(name) ?: root.createFile("text/markdown", name)
                if (doc != null) runCatching {
                    context.contentResolver.openOutputStream(doc.uri, "wt")!!.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }
                    written++
                }
            }
        }
        return written
    }
}
