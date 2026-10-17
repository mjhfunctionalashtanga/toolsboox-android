package com.toolsboox.plugin.calendar.ot

import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Box
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Kind
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Piece
import com.toolsboox.plugin.calendar.ot.PickFaceLayout.Shape

/**
 * add-to-pick.golden — the parity proof of "＋ Add to this pick" (pin §8): the words, the ladders,
 * sample records, and every slot of ≥ 20 laid-out faces under the fake measurer. Byte-identical in
 * ledger-ipad (Tests/LedgerCoreTests/Fixtures/) and toolsboox (app/src/test/resources/); either side
 * regenerates it (the panel-seats.golden precedent). Pure.
 */
object AddToPickGolden {

    /** The passage and note of every case: the first N chars of this, repeated. */
    private const val PROSE = "The quiet work of attention is the work of a life. "
    fun prose(n: Int): String = buildString { while (length < n) append(PROSE) }.take(n)

    const val SOURCE = "The Quiet Work · Field Notes"

    /** The cases, every shape: name → the input's pieces (the passage/note lengths, the adds). */
    data class Case(val name: String, val passage: Int, val note: Int = 0, val ink: Piece? = null,
                    val visuals: List<Piece> = emptyList(), val voices: List<Piece> = emptyList(),
                    val author: String = "", val cover: Piece? = null)

    val CASES: List<Case> = listOf(
        Case("nothing", 150),
        Case("photo", 150, visuals = listOf(Piece(Kind.PHOTO, "p1", 4032, 3024))),
        Case("video", 150, visuals = listOf(Piece(Kind.VIDEO, "v1", 1920, 1080, 41))),
        Case("typed+ink", 150, note = 120, ink = Piece(Kind.INK, "i1", 900, 300)),
        Case("photo+video+3voice", 150,
            visuals = listOf(Piece(Kind.PHOTO, "p2", 3024, 4032), Piece(Kind.VIDEO, "v1", 1920, 1080, 41)),
            voices = listOf(Piece(Kind.VOICE, "a3", seconds = 23), Piece(Kind.VOICE, "a2", seconds = 65),
                Piece(Kind.VOICE, "a1", seconds = 7))),
        Case("overflow", 300, note = 160, visuals = listOf(Piece(Kind.PHOTO, "p1", 4032, 3024)),
            voices = listOf(Piece(Kind.VOICE, "a1", seconds = 23))),
    )

    /** One extra: a book card's footer (author + cover). */
    val BOOK = Case("book", 150, author = "Mary Oliver", cover = Piece(Kind.PHOTO, "", 600, 900))

    fun input(shape: Shape, c: Case) = PickFaceLayout.Input(
        shape = shape, passage = prose(c.passage), note = if (c.note > 0) prose(c.note) else "",
        ink = c.ink, visuals = c.visuals, voices = c.voices, source = SOURCE, author = c.author, cover = c.cover)

    // ── Records (pin §3) — canonical JSON (keys sorted, no spaces); each app decodes and re-encodes
    // them unchanged ──
    const val RECORD_FACE = """{"fit":"pullquote","format":"portrait","picture":false}"""
    const val RECORD_SLOTS =
        """[{"a":"0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90","k":"photo","r":[89,407,822,311]},""" +
            """{"a":"5b7e1c40-2d9a-4e6f-8a13-c4d2e9f07b61","k":"voice","r":[89,815,130,47]}]"""
    const val RECORD_LINE =
        """{"attachments":[{"date":1792152000000,"filename":"photo-0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90.jpg",""" +
            """"id":"0f6c2a8e-6b1d-4c39-9d0e-3f2a7b5c1d90","kind":"photo"},""" +
            """{"date":1792152060000,"duration":23,"filename":"voice-5b7e1c40-2d9a-4e6f-8a13-c4d2e9f07b61.m4a",""" +
            """"id":"5b7e1c40-2d9a-4e6f-8a13-c4d2e9f07b61","kind":"audio"},""" +
            """{"date":1792152120000,"duration":41,"filename":"video-9a3d5e17-0c2b-4f88-b6e4-71d0c3a2f5e8.mp4",""" +
            """"id":"9a3d5e17-0c2b-4f88-b6e4-71d0c3a2f5e8","kind":"video"}],""" +
            """"date":1792151940000,"excerpt":"The quiet work of attention is the work of a life.",""" +
            """"id":"mfhl-42-0a1b2c3d","kind":"article","note":"Read this again in the spring.",""" +
            """"source":"Field Notes","starred":false,"title":"The Quiet Work",""" +
            """"updatedAt":1792152120000,"url":"https://example.com/quiet-work"}"""

    fun text(): String = buildString {
        appendLine("# add-to-pick.golden — \"＋ Add to this pick\" (Michael 2026-10-12: \"Let the photo/video/note show on the pick.\").")
        appendLine("# Byte-identical in ledger-ipad (Tests/LedgerCoreTests/Fixtures/) and toolsboox (app/src/test/resources/);")
        appendLine("# regenerate from AddToPickGolden on either side.")
        appendLine("# Fake measurer: charW=(size+1)/2 · lines=(chars·charW+width-1)/width · height=lines·((size·5+2)/4+spacing)")
        appendLine("#   · one line=(size·5+2)/4 · a char is a Unicode scalar.")
        appendLine("# Rects are x,y,w,h in face px; slots are per-mille of the face (int, half up).")
        appendLine("[words]")
        AddToPickWords.golden().forEach { appendLine(it) }
        appendLine("[ladders]")
        appendLine("passage 60: " + PickFaceLayout.ladder(60, 30).joinToString(" "))
        appendLine("passage 48: " + PickFaceLayout.ladder(48, 30).joinToString(" "))
        appendLine("landscape passage 40: " + PickFaceLayout.ladder(40, 20).joinToString(" "))
        appendLine("landscape passage 32: " + PickFaceLayout.ladder(32, 20).joinToString(" "))
        appendLine("note 40: " + PickFaceLayout.ladder(40, 30).joinToString(" "))
        appendLine("landscape note 27: " + PickFaceLayout.ladder(27, 20).joinToString(" "))
        appendLine("[records]")
        appendLine("face: $RECORD_FACE")
        appendLine("faceSlots: $RECORD_SLOTS")
        appendLine("line: $RECORD_LINE")
        appendLine("[cases]")
        appendLine("# passage / note = the first N chars of \"$PROSE\" repeated · source \"$SOURCE\"")
        for (c in CASES + BOOK) appendLine("# ${c.name}: ${describe(c)}")
        appendLine("[layouts]")
        for (shape in Shape.entries) for (c in CASES) appendCase(this, shape, c)
        appendCase(this, Shape.FREE, BOOK)
    }

    private fun describe(c: Case): String = listOfNotNull(
        "passage ${c.passage}",
        c.note.takeIf { it > 0 }?.let { "note $it" },
        c.ink?.let { "ink ${it.id} ${it.w}x${it.h}" },
        c.visuals.takeIf { it.isNotEmpty() }?.joinToString(", ") { piece(it) },
        c.voices.takeIf { it.isNotEmpty() }?.joinToString(", ") { "voice ${it.id} ${it.seconds}s" },
        c.author.takeIf { it.isNotBlank() }?.let { "author \"$it\"" },
        c.cover?.let { "cover ${it.w}x${it.h}" },
    ).joinToString(" · ")

    private fun piece(p: Piece) = "${p.kind.key} ${p.id} ${p.w}x${p.h}" + (if (p.kind == Kind.VIDEO) " ${p.seconds}s" else "")

    private fun r(b: Box) = "[$b]"

    private fun appendCase(sb: StringBuilder, shape: Shape, c: Case) {
        val l = PickFaceLayout.layout(input(shape, c), PickFaceLayout.FakeMeasure)
        sb.appendLine("${shape.key}·${c.name}: ${l.w}x${l.h} step=${l.step}" + (if (l.overflow) " overflow" else "") +
            " mark=${l.markSize}@${l.markBaseline} passage=${l.passage.size} ${r(l.passage.box)}")
        l.note?.let { sb.appendLine("  note=${it.size} ${r(it.box)}" + (if (it.truncated) " cut" else "")) }
        l.ink?.let { sb.appendLine("  ink ${r(it)}") }
        for (t in l.tiles) {
            sb.append("  tile ${t.piece.kind.key} ${t.piece.id.ifBlank { "-" }} ${r(t.box)}")
            t.disc?.let { sb.append(" disc ${r(it)}") }
            t.pill?.let { sb.append(" pill \"${t.pillText}\" ${r(it)}") }
            t.more?.let { sb.append(" more \"${t.moreText}\" ${r(it)}") }
            sb.appendLine()
        }
        for (ch in l.chips) sb.appendLine("  chip \"${ch.label}\" ${r(ch.box)}")
        val f = l.footer
        sb.appendLine("  footer x=${f.x} w=${f.width}" +
            (f.divider?.let { " divider ${r(it)}" } ?: "") + (f.cover?.let { " cover ${r(it)}" } ?: "") +
            " source=${f.sourceSize}@${f.sourceBaseline}" +
            (if (f.authorSize > 0) " author=${f.authorSize}@${f.authorBaseline}" else ""))
        if (l.slots.isNotEmpty()) sb.appendLine("  slots " + l.slots.joinToString(" · ") {
            "${it.kind.key} ${it.id} " + PickFaceLayout.perMille(it.box, l.w, l.h).joinToString(",", "[", "]")
        })
    }
}
