package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * A typed Write page — the essay half of the Write surface.
 *
 * Write has always been ink on a lined template, which is right for drafting and wrong for the last
 * mile: an essay eventually needs a title, tags, and markup you can publish. So a Write sub-page can
 * be either hand or text, and this holds the text side.
 *
 * Deliberately NOT a text note. Notes are the day's loose jottings, a list you add to; a Write draft
 * is one continuous piece at a fixed address — a date and a sub-page — so it can be paged to, tagged
 * as that page, and sent. Sharing the model would have made a Write page's identity "whichever note
 * happens to be at index 2 today", which is not an address you can come back to.
 *
 * The iPad's `WriteDraft`, same file shape and same id, so a draft written on one device opens on
 * the other.
 */
data class WriteDraft(
    /** "<yyyy-MM-dd>/<pageKey>" — the page IS the identity, so a merge can't make two for one page. */
    val id: String,
    val title: String,
    /** Markdown SOURCE, never rendered HTML: the HTML is an export, and storing the export instead
     *  of the source is how a document stops being editable. */
    val markdown: String,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title)
        .put("markdown", markdown).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = WriteDraft(
            o.optString("id"), o.optString("title"),
            o.optString("markdown"), o.optLong("updatedAt")
        )
    }
}

/** Per-day storage for typed Write pages. Mirrors the iPad's `WriteDraftStore` file-for-file. */
object WriteDraftStore {

    private fun dir(context: Context): File =
        File(LedgerPaths.documentsRoot(context), "write-drafts").apply { mkdirs() }

    private fun file(context: Context, date: LocalDate) = File(dir(context), "write-$date.json")

    fun id(date: LocalDate, pageKey: String) = "$date/$pageKey"

    fun load(context: Context, date: LocalDate): List<WriteDraft> {
        val f = file(context, date)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { WriteDraft.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun draft(context: Context, date: LocalDate, pageKey: String): WriteDraft? =
        load(context, date).firstOrNull { it.id == id(date, pageKey) }

    fun save(context: Context, date: LocalDate, drafts: List<WriteDraft>) {
        val arr = JSONArray()
        drafts.forEach { arr.put(it.toJson()) }
        // Write to a temp file and rename: a mid-write kill must not truncate to a file that reads
        // as empty and then syncs the loss outward.
        val f = file(context, date)
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    /** Write one page's draft, replacing whatever was at that address. */
    fun put(context: Context, date: LocalDate, pageKey: String, title: String, markdown: String) {
        val all = load(context, date).toMutableList()
        val key = id(date, pageKey)
        val d = WriteDraft(key, title, markdown, System.currentTimeMillis())
        val i = all.indexOfFirst { it.id == key }
        if (i >= 0) all[i] = d else all.add(d)
        save(context, date, all)
    }
}

/**
 * Markdown → HTML, small and predictable.
 *
 * Deliberately NOT a full CommonMark implementation. It exists so a Write draft can leave as a
 * WordPress post or an email, and those need the same handful of constructs: headings, emphasis,
 * links, lists, quotes, code, paragraphs. A partial converter whose limits are written down beats a
 * dependency whose behaviour has to be discovered.
 *
 * Raw HTML in the source passes through untouched — both get written here, and a converter that
 * escaped a hand-written `<figure>` would be actively in the way.
 */
object MarkdownHtml {

    fun html(markdown: String): String {
        val out = StringBuilder()
        var inCode = false
        var listKind: String? = null
        val para = mutableListOf<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            out.append("<p>").append(inline(para.joinToString(" "))).append("</p>\n")
            para.clear()
        }
        fun closeList() { listKind?.let { out.append("</$it>\n"); listKind = null } }

        for (raw in markdown.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()

            // Fenced code first: everything inside is verbatim.
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flushPara(); closeList()
                out.append(if (inCode) "</code></pre>\n" else "<pre><code>\n")
                inCode = !inCode
                continue
            }
            if (inCode) { out.append(escape(raw)).append("\n"); continue }
            if (line.isEmpty()) { flushPara(); closeList(); continue }

            val h = heading(line)
            if (h != null) {
                flushPara(); closeList()
                out.append("<h${h.first}>").append(inline(h.second)).append("</h${h.first}>\n")
                continue
            }
            if (line.startsWith("> ")) {
                flushPara(); closeList()
                out.append("<blockquote><p>").append(inline(line.drop(2))).append("</p></blockquote>\n")
                continue
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                flushPara()
                if (listKind != "ul") { closeList(); out.append("<ul>\n"); listKind = "ul" }
                out.append("<li>").append(inline(line.drop(2))).append("</li>\n")
                continue
            }
            val ol = orderedItem(line)
            if (ol != null) {
                flushPara()
                if (listKind != "ol") { closeList(); out.append("<ol>\n"); listKind = "ol" }
                out.append("<li>").append(inline(ol)).append("</li>\n")
                continue
            }
            if (line == "---" || line == "***") { flushPara(); closeList(); out.append("<hr>\n"); continue }
            // A line that's already an HTML block stands as itself.
            if (line.startsWith("<")) { flushPara(); closeList(); out.append(raw).append("\n"); continue }

            para.add(line)
        }
        flushPara(); closeList()
        if (inCode) out.append("</code></pre>\n")   // unterminated fence: close it, don't eat the rest
        return out.toString().trim()
    }

    /** `#` repeated 1–6 times THEN A SPACE — the same rule that keeps `#tag` from being a heading. */
    private fun heading(line: String): Pair<Int, String>? {
        val n = line.takeWhile { it == '#' }.length
        if (n !in 1..6) return null
        val rest = line.drop(n)
        if (!rest.startsWith(" ")) return null
        return n to rest.trim()
    }

    private fun orderedItem(line: String): String? {
        val dot = line.indexOf('.')
        if (dot !in 1..3) return null
        if (!line.take(dot).all { it.isDigit() }) return null
        if (line.length <= dot + 1 || line[dot + 1] != ' ') return null
        return line.drop(dot + 2)
    }

    /** Links first: their brackets and parens would otherwise be chewed by the emphasis passes. */
    private fun inline(s: String): String = s
        .replace(Regex("""\[([^\]]+)\]\(([^)\s]+)\)"""), "<a href=\"$2\">$1</a>")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "<strong>$1</strong>")
        .replace(Regex("""(?<![*\w])\*([^*]+)\*(?![*\w])"""), "<em>$1</em>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
