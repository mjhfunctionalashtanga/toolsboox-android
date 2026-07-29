package com.toolsboox.plugin.calendar.ot

/**
 * Real, tappable `/Link` annotations bolted onto a PDF that Android's own writer produced.
 *
 * WHY THIS FILE EXISTS AT ALL. `android.graphics.pdf.PdfDocument` hands you a [android.graphics.Canvas]
 * and nothing else. There is no annotation API on it — no equivalent of CoreGraphics' `setURL(_:for:)`,
 * which is how the iPad emits its linked PDFs — so a URL drawn onto that canvas is a picture of a URL
 * and is not tappable in any reader. The platform simply cannot emit link annotations, and pretending
 * otherwise (labelling the export "linked PDF" because the text is blue and underlined) would be worse
 * than not offering it: a reader taps, nothing happens, and they conclude the link is dead rather than
 * absent.
 *
 * So the annotations are written here, afterwards, as a PDF **incremental update** — the mechanism the
 * format itself defines for adding to a file you do not want to rewrite. We append: one `/Annot` object
 * per link, a re-issued copy of each page object carrying an `/Annots` array, a fresh cross-reference
 * section listing only those changed objects, and a trailer whose `/Prev` points back at the original
 * table. Nothing in the original bytes is touched, so a failure here can only ever produce a file we
 * throw away.
 *
 * WHAT IT REFUSES TO DO. This understands exactly one cross-reference form: the classic `xref` table
 * with a `trailer` dictionary, which is what Skia (the writer behind `PdfDocument`) emits today. If it
 * meets a cross-reference *stream* instead, or a catalogue it cannot walk, or a page object it cannot
 * parse, it returns null rather than guessing — and the caller ships the plain PDF and says so. A
 * silently-corrupt PDF is a far worse outcome than a PDF with no links in it.
 *
 * Deliberately free of Android types (bytes and [String] only, ISO-8859-1 so one char is one byte and
 * an index is an offset) so the parsing can be exercised in a plain JVM unit test — byte surgery on a
 * container format is precisely the sort of code that looks right and is off by one.
 */
object PdfLinkAnnotations {

    /**
     * One link to make tappable. The rectangle is in PDF **user space** — origin bottom-left, y
     * increasing upwards — which is upside-down from the canvas the page was drawn on. The caller
     * does that flip, because only the caller knows the page height it drew against.
     */
    data class Link(
        val url: String,
        val pageIndex: Int,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
    )

    /** Result of the walk over one PDF: the object offsets we could resolve, plus the trailer facts. */
    private class Xref(
        val offsets: Map<Int, Int>,
        val size: Int,
        val rootRef: String,
        val startXref: Int,
    )

    /**
     * Return [pdf] with [links] added as annotations, or null when this file cannot be annotated
     * safely. An empty [links] list is also null — there is nothing to add and no reason to rewrite.
     */
    fun inject(pdf: ByteArray, links: List<Link>): ByteArray? {
        if (links.isEmpty()) return null
        val s = String(pdf, Charsets.ISO_8859_1)
        val xref = readXref(s) ?: return null
        val pages = pageObjects(s, xref) ?: return null
        if (pages.isEmpty()) return null

        val byPage = links.groupBy { it.pageIndex }.filterKeys { it in pages.indices }
        if (byPage.isEmpty()) return null

        val out = StringBuilder(s)
        // The appended section must start on its own line; a writer that ended without a newline
        // after %%EOF would otherwise glue our first object onto it.
        if (!out.endsWith("\n")) out.append('\n')

        var nextObj = xref.size
        val updated = LinkedHashMap<Int, Int>()   // object number → byte offset of "N 0 obj"

        for ((pageIndex, pageLinks) in byPage) {
            val pageObjNum = pages[pageIndex]
            val pageOffset = xref.offsets[pageObjNum] ?: return null
            val body = dictOf(s, pageOffset, pageObjNum) ?: return null
            // A page that already carries annotations is not ours to rewrite: merging into an
            // existing /Annots array (which may be an indirect reference to an array object) is a
            // second parser's worth of work for a case Skia never produces.
            if (body.contains("/Annots")) return null

            val refs = StringBuilder()
            for (link in pageLinks) {
                val annotNum = nextObj++
                updated[annotNum] = out.length
                out.append(annotNum).append(" 0 obj\n").append(annotDict(link)).append("\nendobj\n")
                refs.append(annotNum).append(" 0 R ")
            }
            updated[pageObjNum] = out.length
            out.append(pageObjNum).append(" 0 obj\n<< ").append(body.trim())
                .append(" /Annots [ ").append(refs.toString().trim()).append(" ] >>\nendobj\n")
        }

        val xrefOffset = out.length
        out.append(xrefSection(updated))
        out.append("trailer\n<< /Size ").append(nextObj)
            .append(" /Root ").append(xref.rootRef)
            .append(" /Prev ").append(xref.startXref)
            .append(" >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n")

        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** `<< /Type /Annot /Subtype /Link … >>` for one link. `/Border [0 0 0]` keeps readers from
     *  drawing their own box over handwriting; `/F 4` is the Print flag, so the link survives being
     *  printed to another PDF. */
    private fun annotDict(link: Link): String = buildString {
        append("<< /Type /Annot /Subtype /Link /Border [0 0 0] /F 4 /Rect [ ")
        append(fmt(link.left)).append(' ').append(fmt(link.bottom)).append(' ')
        append(fmt(link.right)).append(' ').append(fmt(link.top))
        append(" ] /A << /S /URI /URI (").append(escape(link.url)).append(") >> >>")
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    /** PDF literal strings end at an unbalanced `)`, so the three structural characters have to be
     *  escaped; anything outside printable ASCII is dropped rather than guessed at, since a URL that
     *  needed it should have arrived percent-encoded. */
    private fun escape(url: String): String = buildString {
        for (c in url) when {
            c == '\\' -> append("\\\\")
            c == '(' -> append("\\(")
            c == ')' -> append("\\)")
            c.code in 32..126 -> append(c)
        }
    }

    /** The incremental section: contiguous runs of object numbers, each as its own subsection. */
    private fun xrefSection(updated: Map<Int, Int>): String = buildString {
        append("xref\n")
        val nums = updated.keys.sorted()
        var i = 0
        while (i < nums.size) {
            var j = i
            while (j + 1 < nums.size && nums[j + 1] == nums[j] + 1) j++
            append(nums[i]).append(' ').append(j - i + 1).append('\n')
            for (k in i..j) {
                // Exactly 20 bytes per entry, trailing space included — readers index into this
                // table arithmetically, so a short line shifts every entry after it.
                append(String.format(java.util.Locale.US, "%010d 00000 n \n", updated[nums[k]]))
            }
            i = j + 1
        }
    }

    /* -----------------------------------------------------------------------------------
     * Reading the original
     * --------------------------------------------------------------------------------- */

    /** Walk the cross-reference chain from the last `startxref`. Null on anything unexpected. */
    private fun readXref(s: String): Xref? {
        val sx = s.lastIndexOf("startxref")
        if (sx < 0) return null
        val startOffset = Regex("""startxref\s+(\d+)""").find(s, sx)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null

        val offsets = HashMap<Int, Int>()
        var size = 0
        var root = ""
        var at: Int? = startOffset
        val seen = HashSet<Int>()
        while (at != null && at in s.indices && seen.add(at)) {
            // A cross-reference STREAM starts "N 0 obj << /Type /XRef" — the compressed form this
            // deliberately does not attempt. Bail so the caller ships the plain file.
            if (!s.startsWith("xref", at)) return null
            var p = at + 4
            while (p < s.length && (s[p] == '\r' || s[p] == '\n' || s[p] == ' ')) p++
            while (p < s.length && s[p].isDigit()) {
                val header = Regex("""(\d+)\s+(\d+)""").find(s, p) ?: return null
                if (header.range.first != p) return null
                val first = header.groupValues[1].toIntOrNull() ?: return null
                val count = header.groupValues[2].toIntOrNull() ?: return null
                p = header.range.last + 1
                while (p < s.length && (s[p] == '\r' || s[p] == '\n' || s[p] == ' ')) p++
                for (k in 0 until count) {
                    // Entries are fixed-width by specification: ten offset digits, a space, five
                    // generation digits, a space, the n/f type, then a two-byte EOL — twenty bytes
                    // exactly. Validated rather than assumed, because a writer that used a shorter
                    // line would make every later offset in this table silently wrong, and wrong
                    // offsets are how you get a plausible-looking PDF that no reader will open.
                    if (p + 20 > s.length) return null
                    if (s[p + 10] != ' ' || s[p + 16] != ' ') return null
                    val kind = s[p + 17]
                    if (kind != 'n' && kind != 'f') return null
                    val off = s.substring(p, p + 10).toIntOrNull() ?: return null
                    // First section wins: the chain is walked newest-first, so an older section
                    // must not overwrite an object a newer one has already redefined.
                    if (kind == 'n' && !offsets.containsKey(first + k)) offsets[first + k] = off
                    p += 20
                }
                while (p < s.length && (s[p] == '\r' || s[p] == '\n' || s[p] == ' ')) p++
            }
            val trailerAt = s.indexOf("trailer", p)
            if (trailerAt < 0) return null
            val trailer = s.substring(trailerAt, minOf(s.length, trailerAt + 2000))
            if (size == 0) size = Regex("""/Size\s+(\d+)""").find(trailer)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (root.isEmpty()) root = Regex("""/Root\s+(\d+\s+\d+\s+R)""").find(trailer)?.groupValues?.get(1).orEmpty()
            at = Regex("""/Prev\s+(\d+)""").find(trailer)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (size <= 0 || root.isEmpty() || offsets.isEmpty()) return null
        return Xref(offsets, size, root, startOffset)
    }

    /** The page objects in document order, walked catalogue → `/Pages` → `/Kids`. Null if the tree
     *  is anything other than the flat one Skia writes (a nested `/Pages` node, a missing kid). */
    private fun pageObjects(s: String, xref: Xref): List<Int>? {
        val rootNum = Regex("""(\d+)\s+\d+\s+R""").find(xref.rootRef)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val catalog = dictOf(s, xref.offsets[rootNum] ?: return null, rootNum) ?: return null
        val pagesNum = Regex("""/Pages\s+(\d+)\s+\d+\s+R""").find(catalog)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val pagesDict = dictOf(s, xref.offsets[pagesNum] ?: return null, pagesNum) ?: return null
        val kidsAt = pagesDict.indexOf("/Kids")
        if (kidsAt < 0) return null
        val open = pagesDict.indexOf('[', kidsAt)
        val close = pagesDict.indexOf(']', open + 1)
        if (open < 0 || close < 0) return null
        val kids = Regex("""(\d+)\s+\d+\s+R""").findAll(pagesDict.substring(open, close))
            .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        if (kids.isEmpty()) return null
        // Every kid must actually be a leaf page; a `/Pages` node among them would mean the flat
        // assumption is wrong and the ORDER we hand back would be wrong with it.
        for (k in kids) {
            val d = dictOf(s, xref.offsets[k] ?: return null, k) ?: return null
            if (!Regex("""/Type\s*/Page\b""").containsMatchIn(d)) return null
        }
        return kids
    }

    /**
     * The inside of object [num]'s top-level dictionary (without the enclosing `<<` `>>`), read from
     * [offset]. Null when the bytes there are not that object, or the dictionary does not close.
     *
     * The brace scan has to know about strings, because `(a >> b)` inside a literal string is text and
     * not a dictionary terminator — the classic way a naive scanner truncates a dictionary.
     */
    private fun dictOf(s: String, offset: Int, num: Int): String? {
        if (offset < 0 || offset >= s.length) return null
        val header = Regex("""^(\d+)\s+(\d+)\s+obj""").find(s.substring(offset, minOf(s.length, offset + 40)))
            ?: return null
        if (header.groupValues[1].toIntOrNull() != num) return null
        var i = offset + header.range.last + 1
        while (i < s.length && s[i].isWhitespace()) i++
        if (i + 1 >= s.length || s[i] != '<' || s[i + 1] != '<') return null
        val start = i + 2
        var depth = 1
        i = start
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> {
                    // Literal string: balanced parens, backslash escapes anything.
                    var d = 1
                    i++
                    while (i < s.length && d > 0) {
                        when (s[i]) {
                            '\\' -> i++
                            '(' -> d++
                            ')' -> d--
                        }
                        i++
                    }
                    continue
                }
                c == '<' && i + 1 < s.length && s[i + 1] == '<' -> { depth++; i += 2; continue }
                c == '>' && i + 1 < s.length && s[i + 1] == '>' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i)
                    i += 2
                    continue
                }
            }
            i++
        }
        return null
    }
}
