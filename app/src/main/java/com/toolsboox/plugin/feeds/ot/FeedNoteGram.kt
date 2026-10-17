package com.toolsboox.plugin.feeds.ot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.toolsboox.da.Attachment
import com.toolsboox.ot.InkPadView
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.CalendarNavigator
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.AvGrams
import com.toolsboox.plugin.calendar.ot.PickingsPlacement
import com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
import com.toolsboox.ui.plugin.ScreenFragment
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A saved article note IS a gram — you only choose its MEDIUM.
 *
 * The old note dialog treated the gram as an option on the side ("🃏 Pick…"), so most notes
 * ended their lives as a line in the Ledger Log and never became an object you could move,
 * connect, or rework. This flips it: every note saved from the feed reader lands on TODAY's
 * Notes page (page "0") as a placed card, stamped with today's date and where it came from,
 * and the medium chooser is the whole dialog — handwriting, text, audio, or video.
 *
 * With a highlight in hand the card is the quote-style gram (the passage as the quote, your
 * note in the note field); without one your words are the body. Audio and video route through
 * the same capture the rest of the app records with, filed by [AvGrams] so the clip and its
 * poster card follow the established A/V-gram shape. Both reader entry points — the in-pane
 * reader and the standalone article view — come through here, so there is exactly one of it.
 */
object FeedNoteGram {

    /** Where the gram lands: the handwritten Notes page — "dropped on today's notes page". */
    private const val NOTES_PAGE = "0"

    // Card face metrics — the same paper QuoteCardRenderer draws on, for the ink card.
    private const val CARD_W = 1080
    private val paper = Color.rgb(0xFB, 0xF8, 0xF1)
    private val ink = Color.rgb(0x1F, 0x1F, 0x1F)
    private val accent = Color.rgb(0x2A, 0x2A, 0x2A)
    private val muted = Color.rgb(0x6A, 0x6A, 0x6A)

    /**
     * The medium chooser — the note dialog now. [selection] is the captured highlight (may be
     * blank), [captureAv] is the host fragment's protected A/V capture handed in as a lambda
     * (audio/video recording lives on ScreenFragment), and [logEvent] appends the article
     * ReadingEvent so the Ledger Log keeps its line alongside the placed gram.
     */
    fun show(
        fragment: ScreenFragment,
        service: CalendarDayService,
        root: File,
        selection: String,
        articleTitle: String,
        feedTitle: String,
        articleUrl: String,
        captureAv: (Attachment.Kind, (Attachment) -> Unit) -> Unit,
        logEvent: (excerpt: String?, note: String?) -> Unit,
        /** ★ Star it — the surface's own star ceremony (article, letter, page). Null hides the
         *  row: a surfaced garden item is already in the ledger and has nothing to star. */
        star: (() -> Unit)? = null
    ) {
        val ctx = fragment.requireContext()
        val sel = selection.trim()
        // THE CANON (Michael, 09-06 / 09-10: "make sure every annotation surface has 'Save
        // highlight' with the handwriting, audio capture, etc."). Every annotate surface — the
        // feeds pane (articles AND letters), the standalone article, The Net, the garden's
        // picks — offers the SAME seven ways to keep something, in the same order:
        //   ❝ Save highlight · ✍ Handwrite · 🎤 Voice · 📷 Photo · ⌨ Type · ★ Star · 📝 Onto today's note
        // A bare highlight is a complete annotation, so ❝ leads (one tap, no note required).
        // The extras — video, the passage's links, Ask, Educate — follow the seven rather than
        // interleaving with them, so the hand learns one ladder on every surface.
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (sel.isNotBlank()) items.add("❝  Save highlight" to {
            saveHighlight(fragment, service, root, sel, articleTitle, feedTitle, articleUrl, logEvent)
        })
        items.add("✍  Handwrite a note" to {
            inkNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl, logEvent)
        })
        items.add("🎤  Record a voice note" to {
            avNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl,
                Attachment.Kind.AUDIO, captureAv, logEvent)
        })
        items.add("📷  Photo" to {
            photoNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl,
                captureAv, logEvent)
        })
        items.add("⌨  Type a note" to {
            textNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl, logEvent)
        })
        if (star != null) items.add("★  Star it" to { star() })
        if (sel.isNotBlank()) items.add("📝  Onto today's note" to {
            ontoTodaysNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl, logEvent)
        })
        items.add("🎥  Video" to {
            avNote(fragment, service, root, sel, articleTitle, feedTitle, articleUrl,
                Attachment.Kind.VIDEO, captureAv, logEvent)
        })
        // 🔗 Highlight a passage, keep ITS links (09-03: "you'd have to be able to highlight
        // and then select a button to do it… emails, rss, and the net").
        val selLinks = Regex("""https?://[^\s"'<>)\]]+""").findAll(sel).map { it.value }.distinct().toList()
        if (selLinks.isNotEmpty()) items.add("🔖  ${selLinks.size} link${if (selLinks.size == 1) "" else "s"} \u2192 Later" to {
            Thread {
                for (u in selLinks.take(40)) runCatching {
                    com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.fileLink(
                        ctx.applicationContext, java.time.LocalDate.now(), "read", u, u)
                }
            }.apply { isDaemon = true }.start()
            android.widget.Toast.makeText(ctx, "\ud83d\udd17 ${selLinks.size} \u2192 Later", android.widget.Toast.LENGTH_SHORT).show()
        })
        items.add("🔎  Ask about this" to {
            // selection = null means "the whole article"; the bridge goes straight to the Ask
            // chat — this chooser stays THE menu, no layered dialogs behind its rows.
            com.toolsboox.plugin.calendar.ot.AskBridge.askFrom(
                fragment = fragment, selection = sel.ifBlank { null },
                title = articleTitle, link = articleUrl, sourceLabel = feedTitle
            )
        })
        items.add("🎓  Educate me" to {
            // Grams the highlight — or the whole article (its title standing in as the text)
            // when nothing is selected — to the Educate Me panel.
            com.toolsboox.plugin.calendar.ot.AskBridge.gramToEducateMe(
                fragment = fragment, text = sel.ifBlank { articleTitle },
                title = articleTitle, link = articleUrl, sourceLabel = feedTitle
            )
        })
        val b = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
        // The quote preview must NOT ride in via setMessage: AlertDialog shows a message OR an
        // items list, never both (AlertController.setupContent skips installing the ListView
        // whenever a message is set) — which is exactly the bug Michael hit: the pen icon
        // opened "Highlight + note" with the passage, a Cancel, and NO save / note options.
        // The preview lives in a custom title instead, so the option rows always show.
        if (sel.isNotBlank()) {
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()
            b.setCustomTitle(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(16), px(20), px(4))
                addView(android.widget.TextView(ctx).apply {
                    text = "Highlight"; textSize = 18f; setTextColor(0xFF000000.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                // THE WHOLE HIGHLIGHT (Michael, 09-13: "it could not capture all of the
                // highlight" — this preview cut it at 280 chars and four lines, so the dialog
                // itself said the capture was short). It scrolls past six lines instead.
                addView(android.widget.TextView(ctx).apply {
                    text = "“$sel”"; textSize = 13f; setTextColor(0xFF444444.toInt())
                    maxHeight = px(120); isVerticalScrollBarEnabled = true
                    movementMethod = android.text.method.ScrollingMovementMethod()
                    setPadding(0, px(4), 0, 0)
                })
            })
        } else {
            b.setTitle("🖍  Note")
        }
        // Through the modal door, never a bare show(): over an ink-capable surface an unrouted
        // dialog leaves the hardware pen armed, and the stylus that tapped the notes icon then
        // taps a menu that cannot hear it — the popup "freezes". [ScreenFragment.showModal]
        // runs the present hook that pauses the pen; a menu still closes on an outside tap.
        fragment.showModal(b.create())
    }

    /** ❝ Save highlight — the bare passage kept as a gram in ONE tap, no note required: the
     *  quote face with the date·source footer (title · feed stamped, url carried as the gram's
     *  source link), placed on today's Notes page exactly like an annotated one. The passage
     *  still earns its Ledger Log line via [logEvent]. */
    private fun saveHighlight(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        logEvent: (String?, String?) -> Unit
    ) {
        logEvent(sel, null)
        placeAsync(fragment, service, root, articleUrl, articleTitle, feedTitle, cardText = sel) {
            QuoteCardRenderer.render(sel, footer(articleTitle, feedTitle), null, CARD_W, 0)
        }
    }

    /** 📝 Onto today's note — the passage as a quote gram ON TODAY'S NOTES PAGE (page "0"),
     *  whatever the remembered destination is. ❝ keeps the passage where your grams have been
     *  going; this is the explicit "put it on the page I'm writing" — with the same Log line. */
    private fun ontoTodaysNote(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        logEvent: (String?, String?) -> Unit
    ) {
        logEvent(sel, null)
        placeAsync(fragment, service, root, articleUrl, articleTitle, feedTitle, cardText = sel,
            destination = com.toolsboox.plugin.calendar.ot.GramDestinations.Destination(
                "📝", "Today's notes", NOTES_PAGE)) {
            QuoteCardRenderer.render(sel, footer(articleTitle, feedTitle), null, CARD_W, 0)
        }
    }

    /**
     * The garden's pick — a surfaced item (a root's crossing, a sprout, a missed rhizome, a map
     * node) becomes a gram exactly the way a feed highlight does: the item's text stands where
     * the highlight stood (the quote on the card face), and the medium chooser is the whole
     * dialog — handwriting or a typed note ride as the annotation, audio and video record with
     * the item as their source. Saving with nothing added still makes the plain quote card; the
     * pick itself is enough. Nothing extra is logged — a picked item is already in the ledger.
     */
    fun showForItem(
        fragment: ScreenFragment,
        service: CalendarDayService,
        root: File,
        itemText: String,
        originLabel: String,
        sourceUrl: String = "",
        captureAv: (Attachment.Kind, (Attachment) -> Unit) -> Unit
    ) = show(
        fragment, service, root,
        selection = itemText, articleTitle = originLabel, feedTitle = "",
        articleUrl = sourceUrl, captureAv = captureAv, logEvent = { _, _ -> })

    /** "Title · Feed" — the same both-names label the gram studio path uses, so a card from
     *  The Guardian sits with the others from The Guardian. */
    fun label(articleTitle: String, feedTitle: String): String =
        listOf(articleTitle, feedTitle).filter { it.isNotBlank() }.joinToString(" · ")

    /** The footer line: "date · source" — today's date stamped to the note, as asked. */
    fun footer(articleTitle: String, feedTitle: String, day: LocalDate = LocalDate.now()): String {
        val date = day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        return listOf(date, label(articleTitle, feedTitle)).filter { it.isNotBlank() }.joinToString(" · ")
    }

    // --- ⌨ Text -------------------------------------------------------------------------------

    /** Type the note, then it lands on today's Notes page as a card. With a highlight the face
     *  is the quote gram (passage as quote, note in the note field); without, the note is the body. */
    private fun textNote(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        logEvent: (String?, String?) -> Unit
    ) {
        typeNoteDialog(fragment, sel) { n ->
            if (n.isBlank() && sel.isBlank()) return@typeNoteDialog
            logEvent(sel.ifBlank { null }, n.ifBlank { null })
            val body = sel.ifBlank { n }                       // highlight leads; else the note
            val note = if (sel.isNotBlank()) n.ifBlank { null } else null
            placeAsync(fragment, service, root, articleUrl, articleTitle, feedTitle, cardText = body) {
                QuoteCardRenderer.render(body, footer(articleTitle, feedTitle), note, CARD_W, 0)
            }
        }
    }

    /**
     * The typed-note dialog, alone — [onSave] hears the trimmed words (blank allowed). [initial]
     * prefills it: editing the note already on an article highlight (Michael 2026-10-12 — the note
     * joins that highlight's one card; ArticleHighlights) opens with the words you wrote.
     */
    fun typeNoteDialog(
        fragment: ScreenFragment, sel: String, initial: String = "",
        title: String = if (sel.isNotBlank()) "Highlight + note" else "Note",
        onSave: (String) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val input = EditText(ctx).apply {
            hint = "Note"; setSingleLine(false); minLines = 3; gravity = android.view.Gravity.TOP
            setText(initial); setSelection(initial.length)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0); addView(input)
        }
        val b = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (sel.isNotBlank()) b.setMessage("“$sel”")   // the whole passage; the dialog scrolls
        // A note being typed is work — a stray touch outside must not throw it away. Cancel and
        // the back gesture remain the ways out.
        fragment.showGuardedModal(b.create())
    }

    // --- ✍ Handwriting ------------------------------------------------------------------------

    /** Write the note by hand on the shared ink pad, then bake it onto a white card face with
     *  the date·source footer (and the highlighted passage above it, when there is one). */
    private fun inkNote(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        logEvent: (String?, String?) -> Unit
    ) {
        inkNoteDialog(fragment, sel) { written ->
            if (written == null && sel.isBlank()) return@inkNoteDialog
            if (sel.isNotBlank()) logEvent(sel, null)     // the passage still earns its Log line
            placeAsync(fragment, service, root, articleUrl, articleTitle, feedTitle) {
                inkCard(written, sel.ifBlank { null }, footer(articleTitle, feedTitle))
            }
        }
    }

    /**
     * The shared ink pad in its guarded dialog, alone — [onSave] hears the rendered ink (null:
     * nothing written). Save sits at the TOP, out of the writing hand's way.
     */
    fun inkNoteDialog(fragment: ScreenFragment, sel: String, onSave: (Bitmap?) -> Unit) {
        val ctx = fragment.requireContext()
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val pad = InkPadView(ctx)
        // The shared pen toolbar over a bold-framed pad — same composition surface as replies.
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()); setPadding(px(2), px(2), px(2), px(2))
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(340)))
        }
        // Save lives at the TOP: the dialog's own bottom buttons sat exactly where a writing
        // hand rests, so Michael kept striking Save mid-stroke. Top bar: title left, save right.
        lateinit var dialog: AlertDialog
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(6))
            addView(android.widget.TextView(ctx).apply {
                text = if (sel.isNotBlank()) "Highlight + note" else "✍ Handwritten note"
                textSize = 16f; setTextColor(0xFF000000.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.TextView(ctx).apply {
                text = "  Cancel  "; textSize = 14f; setTextColor(0xFF555555.toInt())
                setOnClickListener { dialog.dismiss() }
            })
            addView(android.widget.TextView(ctx).apply {
                text = "  SAVE  "; textSize = 15f
                setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF000000.toInt())
                setPadding(px(12), px(6), px(12), px(6))
                setOnClickListener {
                    isEnabled = false   // first tap wins
                    val written = pad.render()
                    dialog.dismiss()
                    onSave(written)
                }
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), px(10))
            addView(topBar)
            if (sel.isNotBlank()) addView(android.widget.TextView(ctx).apply {
                text = "“$sel”"; textSize = 13f; setTextColor(0xFF444444.toInt())
                maxHeight = px(96); isVerticalScrollBarEnabled = true
                movementMethod = android.text.method.ScrollingMovementMethod()
                setPadding(0, 0, 0, px(6))
            })
            addView(InkPadView.penBar(ctx, pad)); addView(frame)
        }
        dialog = AlertDialog.Builder(ModalScale.wrap(ctx)).setView(box).create()
        // The pad is work in progress — the writing hand lands outside the dialog constantly on
        // a slab, and that touch must not cost the ink. Cancel / SAVE up top and the back
        // gesture remain the ways out.
        fragment.showGuardedModal(dialog)
    }

    /**
     * An article highlight's ONE card face, drawn from everything said about it (Michael
     * 2026-10-12: a note "goes onto the SAME pick/card"): the passage up top in the quote serif,
     * the typed note under it, then the pictures (handwriting first, then photos), a line naming
     * any voice or video note, and the date·source footer — the quote card's siblings, one face.
     */
    fun noteCard(quote: String, note: String?, images: List<Bitmap>, mediaLine: String?, footerText: String): Bitmap {
        if (images.isEmpty()) {
            val words = listOfNotNull(note?.takeIf { it.isNotBlank() }, mediaLine?.takeIf { it.isNotBlank() })
                .joinToString("\n").ifBlank { null }
            return QuoteCardRenderer.render(quote, footerText, words, CARD_W, 0)
        }
        val PAD = CARD_W * 0.089f
        val contentW = (CARD_W - 2 * PAD).toInt()
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.SERIF
            textSize = if (quote.length > 220) 48f else 60f
        }
        val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textSize = 40f
        }
        fun layout(t: String, p: TextPaint) = StaticLayout.Builder.obtain(t, 0, t.length, p, contentW)
            .setLineSpacing(10f, 1f).build()
        val quoteLayout = quote.takeIf { it.isNotBlank() }?.let { layout("“$it”", quotePaint) }
        val words = listOfNotNull(note?.takeIf { it.isNotBlank() }, mediaLine?.takeIf { it.isNotBlank() }).joinToString("\n")
        val noteLayout = words.takeIf { it.isNotBlank() }?.let { layout(it, notePaint) }
        val imgHeights = images.map { contentW.toFloat() * it.height / it.width.coerceAtLeast(1) }
        val footerBand = 120f
        var height = PAD
        if (quoteLayout != null) height += quoteLayout.height + 36f
        if (noteLayout != null) height += noteLayout.height + 30f
        height += imgHeights.sum() + 24f * images.size + footerBand + PAD
        val h = height.toInt().coerceIn(560, 4000)
        val bmp = Bitmap.createBitmap(CARD_W, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)
        var y = PAD
        if (quoteLayout != null) {
            canvas.save(); canvas.translate(PAD, y); quoteLayout.draw(canvas); canvas.restore()
            y += quoteLayout.height + 36f
        }
        if (noteLayout != null) {
            canvas.save(); canvas.translate(PAD, y); noteLayout.draw(canvas); canvas.restore()
            y += noteLayout.height + 30f
        }
        for ((i, img) in images.withIndex()) {
            if (y + imgHeights[i] > h - footerBand - PAD) break
            canvas.drawBitmap(img, null, android.graphics.RectF(PAD, y, PAD + contentW, y + imgHeights[i]),
                Paint(Paint.FILTER_BITMAP_FLAG))
            y += imgHeights[i] + 24f
        }
        drawFooter(canvas, h, footerText)
        return bmp
    }

    /**
     * Bake handwriting onto a card face: the quoted passage (if any) up top in the quote serif,
     * the ink below it, and the same divider + date·source footer QuoteCardRenderer draws — so
     * a handwritten note and a typed one read as siblings on the page.
     */
    private fun inkCard(written: Bitmap?, quote: String?, footerText: String): Bitmap {
        val PAD = CARD_W * 0.089f
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.SERIF
            textSize = if ((quote?.length ?: 0) > 220) 48f else 60f
        }
        val contentW = (CARD_W - 2 * PAD).toInt()
        val quoteLayout = quote?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, quotePaint, contentW)
                .setLineSpacing(12f, 1f).build()
        }
        val inkH = written?.let { contentW.toFloat() * it.height / it.width.coerceAtLeast(1) } ?: 0f
        val footerBand = 120f
        val quoteH = quoteLayout?.height?.toFloat()?.plus(36f) ?: 0f
        val height = (PAD + quoteH + inkH + 48f + footerBand + PAD).toInt().coerceIn(560, 2600)

        val bmp = Bitmap.createBitmap(CARD_W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)
        var y = PAD
        if (quoteLayout != null) {
            canvas.save(); canvas.translate(PAD, y); quoteLayout.draw(canvas); canvas.restore()
            y += quoteLayout.height + 36f
        }
        if (written != null) {
            val dst = android.graphics.RectF(PAD, y, PAD + contentW, y + inkH)
            canvas.drawBitmap(written, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        drawFooter(canvas, height, footerText)
        return bmp
    }

    /** The divider + date·source line, QuoteCardRenderer's footer signature — one drawing of it
     *  shared by the ink and photo faces. */
    private fun drawFooter(canvas: Canvas, height: Int, footerText: String) {
        val PAD = CARD_W * 0.089f
        val footerY = height - PAD
        canvas.drawRect(PAD, footerY - 92f, PAD + 108f, footerY - 84f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val srcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 40f
        }
        var line = footerText
        while (line.isNotEmpty() && srcPaint.measureText("$line…") > CARD_W - 2 * PAD &&
            srcPaint.measureText(line) > CARD_W - 2 * PAD) line = line.dropLast(1)
        canvas.drawText(if (line == footerText) line else "$line…", PAD, footerY - 30f, srcPaint)
    }

    // --- 🎤 / 🎥 Audio & video ----------------------------------------------------------------

    /** Record, then file through [AvGrams] onto today's Notes page — the clip into the day's
     *  `avGrams`, its poster as the card, with the article as its source. */
    private fun avNote(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        kind: Attachment.Kind,
        captureAv: (Attachment.Kind, (Attachment) -> Unit) -> Unit,
        logEvent: (String?, String?) -> Unit
    ) {
        val ctx = fragment.requireContext().applicationContext
        captureAv(kind) { att ->
            // Only once the recording is real: a discarded take shouldn't leave a Log line.
            if (sel.isNotBlank()) logEvent(sel, null)
            Thread {
                val blob = File(com.toolsboox.ot.LedgerPaths.attachmentsDir(ctx), att.filename)
                // A/V grams land in the inbox like every other quick capture. The band-kind
                // dance this used to do is gone with the remembered routing: an All Stars band
                // places by intakeKind through PickingsPlacement rather than AvGrams, which a
                // clip cannot ride — and now nothing tries to send it there in the first place.
                val dest = com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(ctx)
                val placed = runCatching {
                    AvGrams.file(
                        service, root, blob, att,
                        title = label(articleTitle, feedTitle),
                        pageKey = dest.key,
                        sourceLink = articleUrl,
                        sourceLabel = label(articleTitle, feedTitle)
                    )
                }.getOrDefault(false)
                if (placed) offerTrip(fragment, dest.key, dest.name)
            }.apply { isDaemon = true }.start()
        }
    }

    // --- 🖼 Picture note (take or upload) ------------------------------------------------------

    /**
     * 🖼 Take or upload a picture, then place it as a photo card — the image sibling of [avNote].
     *
     * A photo cannot ride [AvGrams]: that path files the blob into `avGrams` and places its
     * POSTER, and a photo's poster IS the photo ([AvPoster] deliberately has no PHOTO face). So
     * the attachment the capture machinery saved becomes a [photoCard] and places exactly like
     * the held-image photo gram — same paper face, same date·source footer, same inbox.
     */
    private fun photoNote(
        fragment: ScreenFragment, service: CalendarDayService, root: File, sel: String,
        articleTitle: String, feedTitle: String, articleUrl: String,
        captureAv: (Attachment.Kind, (Attachment) -> Unit) -> Unit,
        logEvent: (String?, String?) -> Unit
    ) {
        val ctx = fragment.requireContext().applicationContext
        captureAv(Attachment.Kind.PHOTO) { att ->
            // Only once the picture is real: an abandoned camera shouldn't leave a Log line.
            if (sel.isNotBlank()) logEvent(sel, null)
            val foot = footer(articleTitle, feedTitle)
            Thread {
                val blob = File(com.toolsboox.ot.LedgerPaths.attachmentsDir(ctx), att.filename)
                val photo = decodeLocal(blob)
                if (photo == null) {
                    runCatching {
                        fragment.requireActivity().runOnUiThread {
                            runCatching {
                                com.google.android.material.snackbar.Snackbar.make(
                                    fragment.requireView(), "Couldn't read that picture.",
                                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                            }
                        }
                    }
                    return@Thread
                }
                val dest = lastDestination(ctx)
                val ok = runCatching {
                    PickingsPlacement.place(
                        service, root, photoCard(photo, foot), LocalDate.now(), dest.key,
                        sourceLink = articleUrl,
                        sourceLabel = label(articleTitle, feedTitle), sourceFeed = feedTitle,
                        intakeKind = dest.kind
                    )
                }.isSuccess
                if (ok) offerTrip(fragment, dest.key, dest.name)
            }.apply { isDaemon = true }.start()
        }
    }

    /** Decode a local capture, downsampled so the long edge is ≤1024 px — [fetchImage]'s twin for
     *  a file the camera or gallery already landed in the attachments dir. */
    internal fun decodeLocal(file: File): Bitmap? = runCatching {
        if (!file.exists()) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= 1024) bmp else {
            val r = 1024f / longest
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
        }
    }.getOrNull()

    // --- ⁂ Photo gram (hold on a picture) ------------------------------------------------------

    /**
     * Where the last gram went — the remembered default, so "Save as photo pick" is one tap and
     * lands where you've been working. The memory itself moved to
     * [com.toolsboox.plugin.calendar.ot.GramDestinations] (same preference keys, so nothing is
     * forgotten on upgrade): one vocabulary, one memory, shared with every other capture path.
     */
    private fun lastDestination(ctx: android.content.Context) =
        com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(ctx)

    /** The one-tap menu label, naming where the gram will land: "⁂  Save as photo pick → …". */
    fun photoGramLabel(ctx: android.content.Context): String =
        "⁂  Save as photo pick → ${lastDestination(ctx).name}"

    /** Menu for a bare image held in the reader (SRC_IMAGE_TYPE — no link under it). */
    fun showImageMenu(
        fragment: ScreenFragment, service: CalendarDayService, root: File,
        imageUrl: String, articleTitle: String, feedTitle: String, articleUrl: String
    ) {
        val ctx = fragment.requireContext()
        // Same modal door as [show] — this menu answers a stylus HOLD on an image, the one
        // gesture guaranteed to come from the pen the present hook must pause.
        fragment.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(imageUrl)
            .setItems(arrayOf(photoGramLabel(ctx), "⁂  Photo pick to…")) { _, which ->
                when (which) {
                    0 -> savePhotoGram(fragment, service, root, imageUrl, articleTitle, feedTitle, articleUrl)
                    1 -> photoGramTo(fragment, service, root, imageUrl, articleTitle, feedTitle, articleUrl)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** Choose the destination explicitly — the one funnel, and it becomes the remembered
     *  one-tap default (the chooser itself teaches the memory). */
    fun photoGramTo(
        fragment: ScreenFragment, service: CalendarDayService, root: File,
        imageUrl: String, articleTitle: String, feedTitle: String, articleUrl: String
    ) {
        val ctx = fragment.requireContext()
        com.toolsboox.plugin.calendar.ot.GramDestinations.choose(
            ctx, title = "Photo pick to…", fragment = fragment
        ) { dest ->
            savePhotoGram(fragment, service, root, imageUrl, articleTitle, feedTitle, articleUrl, dest)
        }
    }

    /**
     * The photo gram itself: download the held image (IO), downsample to a sane page object
     * (≤1024 long edge — the media-bloat rule), stamp the date·source footer, and place it —
     * CardTreatment gives it the cute taped-down face on the way in, exactly like every other
     * gram this object makes. [destination] defaults to wherever the last gram went.
     */
    fun savePhotoGram(
        fragment: ScreenFragment, service: CalendarDayService, root: File,
        imageUrl: String, articleTitle: String, feedTitle: String, articleUrl: String,
        destination: com.toolsboox.plugin.calendar.ot.GramDestinations.Destination? = null
    ) {
        val ctx = fragment.requireContext()
        val dest = destination ?: lastDestination(ctx)
        val foot = footer(articleTitle, feedTitle)
        Thread {
            val photo = fetchImage(imageUrl)
            if (photo == null) {
                runCatching {
                    fragment.requireActivity().runOnUiThread {
                        runCatching {
                            com.google.android.material.snackbar.Snackbar.make(
                                fragment.requireView(), "Couldn't fetch that image.",
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
                return@Thread
            }
            val ok = runCatching {
                PickingsPlacement.place(
                    service, root, photoCard(photo, foot), dest.date ?: LocalDate.now(), dest.key,
                    sourceLink = articleUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: imageUrl,
                    sourceLabel = label(articleTitle, feedTitle), sourceFeed = feedTitle,
                    intakeKind = dest.kind
                )
            }.isSuccess
            if (ok) offerTrip(fragment, dest.key, dest.name)
        }.apply { isDaemon = true }.start()
    }

    /** Download + decode the image, downsampled so the long edge is ≤1024 px. Null on any failure. */
    internal fun fetchImage(url: String): Bitmap? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) LedgerReader/1.0")
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= 1024) bmp else {
            val r = 1024f / longest
            Bitmap.createScaledBitmap(bmp,
                (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true)
        }
    }.getOrNull()

    /** The photo on the paper ground with the divider + date·source footer — sibling face to the
     *  quote and ink cards; the tape comes from CardTreatment at placement. */
    internal fun photoCard(photo: Bitmap, footerText: String): Bitmap {
        val PAD = CARD_W * 0.089f
        val contentW = (CARD_W - 2 * PAD)
        val photoH = contentW * photo.height / photo.width.coerceAtLeast(1)
        val footerBand = 120f
        val height = (PAD + photoH + 48f + footerBand + PAD).toInt().coerceIn(400, 2600)
        val bmp = Bitmap.createBitmap(CARD_W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(paper)
        canvas.drawBitmap(photo, null,
            android.graphics.RectF(PAD, PAD, PAD + contentW, PAD + photoH), Paint(Paint.FILTER_BITMAP_FLAG))
        drawFooter(canvas, height, footerText)
        return bmp
    }

    // --- ★ Grams for stars --------------------------------------------------------------------

    /** The Intake page key — the read-later ink surface star grams land on. */
    private const val INTAKE_PAGE = "intake"

    /**
     * Grams for stars: a ★ in the feed list ALSO mints a visual gram onto TODAY's Intake page —
     * the entry as a link card (kind chip · title · featured image · feed/site), placed as a
     * movable ImageElement so Michael can arrange the board and write pen notes around it.
     *
     * [thumb] is the row's already-loaded thumbnail when the list has one (no re-download);
     * otherwise [imageUrl] is fetched here, and on any miss the card is text-only — the face
     * degrades gracefully, the gram still lands. Call OFF the main thread (network + render + a
     * day-JSON write live here); placement itself serializes under the per-day lock (DayLocks)
     * inside [PickingsPlacement.place].
     *
     * Starring the same entry twice must not stack twins: an intake card already carrying this
     * sourceLink today wins and nothing is placed. And unstarring deliberately does NOT remove
     * the gram — once placed, the Intake page is his board (arranged, annotated), not a mirror
     * of the star state.
     *
     * @return true when a gram was placed, false when today's intake already had it.
     */
    fun placeStarGram(
        fragment: ScreenFragment, service: CalendarDayService, root: File,
        title: String, feedTitle: String, url: String, kind: String,
        imageUrl: String?, thumb: Bitmap? = null, excerpt: String = ""
    ): Boolean {
        val today = LocalDate.now()
        // Dedupe by sourceLink. Read-before-place is unlocked, but stars arrive at human speed
        // and re-stars route through this same path, so a stale read can't stack twins in practice.
        if (url.isNotBlank()) {
            // Read-only dedupe peek; the placement below is the writer (and refuses an unreadable day).
            val day = service.loadForDisplay(root, today, null, Locale.getDefault())
            if (day.imageElements.any { it.page == INTAKE_PAGE && it.sourceLink == url }) {
                // Say so — a silent no-op reads as a dead star. The gram is already on the
                // register (arranged, annotated), which is exactly why nothing new is placed.
                runCatching {
                    fragment.requireActivity().runOnUiThread {
                        runCatching {
                            android.widget.Toast.makeText(
                                fragment.requireContext(), "★ already on All Stars",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return false
            }
        }
        val photo = thumb ?: imageUrl?.let { fetchImage(it) }
        val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
        // The excerpt comes from the entry the star was struck on — already parsed, already in
        // hand — so the card carries a taste of the piece without a second trip to the network.
        // Filed alongside the rest of the link's face too, so the SAME link picked up later
        // (shared to a page, re-rendered off the Later list) arrives already dressed.
        if (url.isNotBlank() && (excerpt.isNotBlank() || !imageUrl.isNullOrBlank())) runCatching {
            com.toolsboox.plugin.michaelfilter.nw.IntakePageStore.rememberLinkMeta(
                fragment.requireContext().applicationContext, today, url, title, excerpt, imageUrl)
        }
        val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
            url, title, kind, thumb = photo, sourceName = feedTitle, excerpt = excerpt)
        PickingsPlacement.place(
            service, root, face, today, INTAKE_PAGE,
            sourceLink = url, sourceLabel = feedTitle.ifBlank { host },
            cardText = title, sourceFeed = feedTitle,
            // Tag the gram with its quarter (read/watch/listen) so the Intake page can draw it in
            // its own panel; the Email quarter uses the legacy "educate" key.
            intakeKind = kind
        )
        runCatching {
            fragment.requireActivity().runOnUiThread {
                runCatching {
                    android.widget.Toast.makeText(
                        fragment.requireContext(), "★ → All Stars", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        return true
    }

    // --- Placement ----------------------------------------------------------------------------

    /** Render the face off the main thread, place it (CardTreatment baked, edgeBaked, provenance
     *  carried — [PickingsPlacement]'s conventions), then offer the trip. The destination is the
     *  REMEMBERED one — capture stays one tap and the memory does the routing, with Gram Picks
     *  standing in whenever the remembered place no longer exists today. */
    private fun placeAsync(
        fragment: ScreenFragment, service: CalendarDayService, root: File,
        articleUrl: String, articleTitle: String, feedTitle: String,
        cardText: String = "",
        /** A named landing place; null = the remembered one ([GramDestinations.inbox]). */
        destination: com.toolsboox.plugin.calendar.ot.GramDestinations.Destination? = null,
        face: () -> Bitmap
    ) {
        val src = articleUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: ""
        val appCtx = fragment.requireContext().applicationContext
        Thread {
            val dest = destination ?: com.toolsboox.plugin.calendar.ot.GramDestinations.inbox(appCtx)
            val ok = runCatching {
                PickingsPlacement.place(
                    service, root, face(), LocalDate.now(), dest.key,
                    sourceLink = src, sourceLabel = label(articleTitle, feedTitle),
                    cardText = cardText, sourceFeed = feedTitle, intakeKind = dest.kind
                )
            }.isSuccess
            if (ok) {
                // Tell the surface underneath, BEFORE offering the trip: if the reader is a pane on
                // the day page, that page is holding a day without this card in it.
                runCatching {
                    fragment.requireActivity().runOnUiThread {
                        runCatching { fragment.onExternalGramPlaced(dest.key) }
                    }
                }
                offerTrip(fragment, dest.key, dest.name)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Offer the trip rather than taking it — you were mid-article, so staying is the default
     *  and the page the gram landed on is one tap away. */
    internal fun offerTrip(fragment: ScreenFragment, pageKey: String, name: String) {
        runCatching {
            fragment.requireActivity().runOnUiThread {
                runCatching {
                    com.google.android.material.snackbar.Snackbar.make(
                        fragment.requireView(), "Gram on $name.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).setAction("Go to it") {
                        CalendarNavigator.toDayNote(fragment, LocalDate.now(), pageKey)
                    }.show()
                }
            }
        }
    }
}
