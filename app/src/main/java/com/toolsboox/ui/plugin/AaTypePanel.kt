package com.toolsboox.ui.plugin

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.toolsboox.R
import com.toolsboox.ot.AaType
import com.toolsboox.ot.ModalScale
import com.toolsboox.ot.ReadingFonts
import com.toolsboox.ot.ReadingType
import com.toolsboox.ot.ReadingType.Profile
import com.toolsboox.ot.ReadingTypes

/**
 * Aa Type — how the words are set, for books and for articles, in one panel that stays open.
 *
 * Michael, 2026-10-11: "switching font styles in readers both book and rss isn't as robust as I
 * want. It should be powerful like Readest and powerful like EinkBro" — and, of this panel, "Aa
 * type panel is good for that." Readest's settings sections and EinkBro's font import, bold boost
 * and forced black-on-white are the ideas; this is our own code (both are GPL/AGPL).
 *
 * The reader's old Aa panel (size, line spacing, theme, spread, ★ star) was a dial that stayed
 * open — "drops you out of text size adjustment after one button push" (08-10) was the bug it
 * fixed — and this is that panel grown, not replaced. ITS FOUR DIALS STILL OPEN THE SHEET, one
 * tap each: Size, Line, a one-row theme strip and the spread (books), so nothing it did costs a
 * tap more than it did — any theme is now one tap, where the old cycle took one to four (review,
 * Michael 2026-10-11, the no-extra-taps rule). Below them, ONE scroll in five sections (Font ·
 * Spacing · Layout · Colour · Advanced); the jump row and the Books / Articles switch sit above
 * the scroll and never scroll away, so no section is more than a tap off and none is behind a
 * tab. Books / Articles says which profile the dials move ([ReadingType.Profile]); it opens on
 * the one the reader is showing.
 *
 * E-INK, and the page you are adjusting stays in sight: no grey dim (a scrim dithers and ghosts
 * on Carta), no window animation, the sheet sits at the bottom and is capped below half the
 * screen, so the top of the page shows each change live (review, Michael 2026-10-11).
 *
 * Every change is saved at once and handed to [onChange]; the host applies it to the page on
 * screen, debounced ([AaType.LIVE_DEBOUNCE_MS]), so a run of stepper taps costs one e-ink
 * refresh. A tap that changes nothing (a dial at its end) saves nothing and refreshes nothing.
 *
 * [toggleSpread]: the book reader's own two-page toggle (it also stands down a comic's panel walk);
 * null = the plain columns switch. [closing]: the one thing that FINISHES (the reader's ★ Star this
 * book), seated as the sheet's own button beside Done — always on screen however far the sheet is
 * scrolled, and it closes the panel, as the star always did. [note]: what the open book won't
 * take (a comic's or a PDF's fixed pages), said at the top rather than left as dead dials.
 */
class AaTypePanel(
    private val host: ScreenFragment,
    private val start: Profile,
    private val importLauncher: ActivityResultLauncher<Array<String>>?,
    private val onChange: (Profile) -> Unit,
    private val toggleSpread: (() -> Unit)? = null,
    private val closing: Pair<String, () -> Unit>? = null,
    private val note: String? = null,
) {
    private val ctx = host.requireContext()
    private val density = ctx.resources.displayMetrics.density
    private var profile = start
    private var dialog: AlertDialog? = null
    private var faceList: List<AaType.FaceChoice> = emptyList()
    private val refreshers = mutableListOf<(ReadingType) -> Unit>()
    private val anchors = LinkedHashMap<String, View>()
    /** The switch and the jump row — above the scroll, so they never scroll away. */
    private val head = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(8), px(4), px(8), 0)
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(8), 0, px(8), px(8))
    }
    /** Below half the screen, so the page above shows the change being made. */
    private val maxScrollPx = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()
    // No fling glow, no fade: e-ink draws every frame of either.
    private val scroll = object : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = MeasureSpec.getSize(heightMeasureSpec).let { if (it > 0) minOf(it, maxScrollPx) else maxScrollPx }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }.apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = false
        addView(body)
    }
    private val sheet = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(head)
        addView(scroll)
    }

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        render()
        val b = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(R.string.aa_type)
            .setView(sheet)
            .setNegativeButton("Done", null)
        closing?.let { (label, act) -> b.setNeutralButton(label) { _, _ -> act() } }
        val d = b.create()
        d.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(0)
            setGravity(Gravity.BOTTOM)
        }
        dialog = d
        host.showModal(d)   // never a bare .show() (plan v6 S1.0c, Michael 2026-10-10)
    }

    /** The system picker came back from "Import font…": the new face is chosen at once (that is
     *  why it was imported) and joins the list. */
    fun onImported(result: Result<ReadingFonts.UserFont>) {
        result.onSuccess { f ->
            faceList = loadFaces()
            edit { it.copy(customFontId = f.id) }
            render()
            host.showMessage("Font added · ${f.label}")
        }.onFailure { host.showMessage(it.message ?: "Couldn't add that font") }
    }

    // ---- the sheet -----------------------------------------------------------------------

    private fun render() {
        val y = scroll.scrollY
        head.removeAllViews()
        body.removeAllViews()
        refreshers.clear()
        anchors.clear()
        faceList = loadFaces()
        val book = profile == Profile.BOOK

        head.addView(profileSwitch())
        if (profile != start) {
            head.addView(caption(if (book) "These show the next time you open a book."
                else "These show the next time you open an article."))
        }
        head.addView(jumpRow())

        if (book && note != null) body.addView(caption(note))
        // The old panel's four, first and in its words (Michael 2026-10-11: no extra taps).
        body.addView(stepper("Size", "A−", "A+", AaType::sizeLabel, AaType::stepSize, big = true))
        body.addView(stepper("Line", "☰−", "☰+", AaType::lineLabel, AaType::stepLine))
        body.addView(themeStrip())
        if (book) {
            // The old panel's spread row, word for word: it names what is showing now.
            body.addView(row({ if (it.columns == 2) "📖  Two pages" else "📄  One page" }) { spread() })
        }

        header("Font")
        // The face list (tap the name) carries "Import font…" and the imported faces.
        body.addView(faceRow())
        body.addView(stepper("Weight", "B−", "B+", AaType::weightLabel, AaType::stepWeight))

        header("Spacing")
        body.addView(stepper("Paragraph", "−", "+", { AaType.emLabel(it.paragraph) }, AaType::stepParagraph))
        body.addView(stepper("Indent", "−", "+", { AaType.emLabel(it.indent) }, AaType::stepIndent))
        body.addView(stepper("Words", "−", "+", { AaType.pxLabel(it.word) }, AaType::stepWord))
        body.addView(stepper("Letters", "−", "+", { AaType.pxLabel(it.letter) }, AaType::stepLetter))

        header("Layout")
        body.addView(stepper(if (book) "Margin" else "Sides", "−", "+",
            { AaType.pxLabel(it.marginPx.toDouble()) }, AaType::stepMargin))
        // Tap the width to choose one outright — "No limit" to 640 px was eight taps of −.
        body.addView(stepper("Width", "−", "+", AaType::widthLabel, AaType::stepWidth, onValueTap = { pickWidth() }))
        if (book) {
            body.addView(row({ if (it.flow == "scrolled") "⇣  Scrolled" else "⇢  Paged" }) {
                edit { it.copy(flow = if (it.flow == "scrolled") "paginated" else "scrolled") }
            })
        }
        body.addView(toggle("Justify", { it.justify }) { t, on -> t.copy(justify = on) })
        body.addView(toggle("Hyphenate", { it.hyphenate }) { t, on -> t.copy(hyphenate = on) })

        header("Colour")
        // The strip up top picks the theme; here it is in full words, with the custom colours.
        body.addView(caption("").also { tv ->
            refreshers += { t -> setIfChanged(tv, "Theme · " + AaType.themeLabel(t.theme, profile)) }
        })
        body.addView(row({ colourLabel("Text colour", it.fg, it) }) { pickColour(text = true) })
        body.addView(row({ colourLabel("Page colour", it.bg, it) }) { pickColour(text = false) })

        header("Advanced")
        val whose = if (book) "the book's" else "the page's"
        body.addView(toggle("My face over $whose", { it.overrideFont }) { t, on -> t.copy(overrideFont = on) })
        body.addView(toggle("My spacing over $whose", { it.overrideLayout }) { t, on -> t.copy(overrideLayout = on) })
        body.addView(toggle("My colours over $whose", { it.overrideColor }) { t, on -> t.copy(overrideColor = on) })
        body.addView(row({ "Custom CSS · " + if (it.customCss.isBlank()) "none" else "${it.customCss.length} characters" }) {
            editCss()
        })
        body.addView(row(if (book) "↺  Start books over" else "↺  Start articles over") { confirmReset() })

        refresh(load())
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun profileSwitch() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for ((p, word) in listOf(Profile.BOOK to "Books", Profile.ARTICLE to "Articles")) {
            addView(TextView(ctx).apply {
                text = mark(p == profile) + word
                textSize = 17f
                if (p == profile) setTypeface(typeface, Typeface.BOLD)
                minHeight = px(48)
                gravity = Gravity.CENTER
                setPadding(px(18), px(8), px(18), px(8))
                setBackgroundResource(R.drawable.eink_press_bg)
                setOnClickListener { if (profile != p) { profile = p; render() } }
            })
        }
    }

    /** One tap to any section — scrollTo, never smoothScrollTo: no animation on e-ink. */
    private fun jumpRow() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (name in SECTIONS) {
            addView(TextView(ctx).apply {
                text = name
                textSize = 13f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                minHeight = px(48)
                setPadding(px(2), px(6), px(2), px(6))
                setBackgroundResource(R.drawable.eink_press_bg)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { anchors[name]?.let { scroll.scrollTo(0, it.top) } }
            })
        }
    }

    /**
     * All five themes in one row — one tap to any of them (the old cycle stepped one at a time).
     * The one in force is drawn inverted, white on black: plain on e-ink, no extra glyph to fit.
     */
    private fun themeStrip() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(px(12), px(4), px(4), px(4))
        for (th in ReadingType.THEMES) {
            val chip = TextView(ctx).apply {
                text = AaType.themeChip(th)
                textSize = 14f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                minHeight = px(48)
                setPadding(px(2), px(6), px(2), px(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = px(4) }
                setOnClickListener { pickTheme(th) }
            }
            val plain: ColorStateList = chip.textColors
            val face: Typeface? = chip.typeface   // the app's face — null would drop to the system's
            var shown: Boolean? = null
            refreshers += { t ->
                val on = t.theme == th
                if (shown != on) {
                    shown = on
                    if (on) { chip.setBackgroundColor(Color.BLACK); chip.setTextColor(Color.WHITE) }
                    else { chip.setBackgroundResource(R.drawable.eink_press_bg); chip.setTextColor(plain) }
                    chip.setTypeface(face, if (on) Typeface.BOLD else Typeface.NORMAL)
                }
            }
            addView(chip)
        }
    }

    private fun header(title: String) {
        val v = TextView(ctx).apply {
            text = title.uppercase()
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(20), px(18), px(20), px(4))
        }
        anchors[title] = v
        body.addView(v)
    }

    private fun caption(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setPadding(px(20), px(4), px(20), px(6))
    }

    /** A dial: its name, −, where it is, +. The value is on screen between the steppers — you are
     *  adjusting toward something, and you can only tell you've arrived if you can see it.
     *  [onValueTap]: the value also opens a list to choose from (marked ▾). [big]: the old A−/A+
     *  size, kept for Size. */
    private fun stepper(
        name: String, minus: String, plus: String,
        label: (ReadingType) -> String, step: (ReadingType, Int) -> ReadingType,
        big: Boolean = false, onValueTap: (() -> Unit)? = null,
    ): View {
        val value = TextView(ctx).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            minWidth = px(88)
            minHeight = px(48)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            if (onValueTap != null) {
                setBackgroundResource(R.drawable.eink_press_bg)
                setOnClickListener { onValueTap() }
            }
        }
        val more = if (onValueTap != null) " ▾" else ""
        refreshers += { t -> setIfChanged(value, label(t) + more) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(2), px(4), px(2))
            addView(TextView(ctx).apply {
                text = name
                textSize = 16f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(glyph(minus, big) { edit { step(it, -1) } })
            addView(value)
            addView(glyph(plus, big) { edit { step(it, +1) } })
        }
    }

    /** Face: ‹ walks the list, the name opens it (each face drawn in itself), › walks on. */
    private fun faceRow(): View {
        val name = TextView(ctx).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // ▾ says the name opens the list (every face, the imported ones, "Import font…") — its
        // own view, so a long name gives way with "…" and the ▾ always shows.
        val opener = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = px(48)
            setPadding(px(8), px(6), px(8), px(6))
            setBackgroundResource(R.drawable.eink_press_bg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { pickFace() }
            addView(name)
            addView(TextView(ctx).apply { text = "  ▾"; textSize = 17f })
        }
        refreshers += { t -> setIfChanged(name, faceList.getOrNull(AaType.faceIndex(t, faceList))?.label.orEmpty()) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(2), px(4), px(2))
            addView(TextView(ctx).apply { text = "Face"; textSize = 16f; setPadding(0, 0, px(8), 0) })
            addView(glyph("‹") { edit { AaType.stepFace(it, -1, faceList) } })
            addView(opener)
            addView(glyph("›") { edit { AaType.stepFace(it, +1, faceList) } })
        }
    }

    /** A − / + target: at least 48 dp each way — on e-ink a missed tap costs a refresh. */
    private fun glyph(g: String, big: Boolean = false, onTap: () -> Unit) = TextView(ctx).apply {
        text = g
        textSize = if (big) 26f else 22f
        gravity = Gravity.CENTER
        minWidth = px(48)
        minHeight = px(48)
        setPadding(px(if (big) 22 else 16), px(6), px(if (big) 22 else 16), px(6))
        setBackgroundResource(R.drawable.eink_press_bg)
        setOnClickListener { onTap() }
    }

    /** A row whose words follow the type (a toggle, a radio, a value) — kept by [refresh]. */
    private fun row(text: (ReadingType) -> String, onTap: () -> Unit): TextView =
        row("", onTap).also { tv -> refreshers += { t -> setIfChanged(tv, text(t)) } }

    private fun row(text: String, onTap: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 16f
        minHeight = px(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), px(10), px(20), px(10))
        setBackgroundResource(R.drawable.eink_press_bg)
        setOnClickListener { onTap() }
    }

    private fun toggle(label: String, get: (ReadingType) -> Boolean, set: (ReadingType, Boolean) -> ReadingType) =
        row({ (if (get(it)) "☑  " else "☐  ") + label }) { edit { t -> set(t, !get(t)) } }

    private fun colourLabel(name: String, hex: String, t: ReadingType) = when {
        t.theme == "contrast" -> "$name · set by High contrast"
        hex.isBlank() -> "$name · the theme's"
        else -> "$name · $hex"
    }

    // ---- changes -------------------------------------------------------------------------

    private fun load() = ReadingTypes.load(ctx, profile)

    /** Saves and applies; returns what was saved (null = nothing changed). */
    private fun edit(change: (ReadingType) -> ReadingType): ReadingType? {
        val before = load()
        val after = change(before).sanitized()
        if (after == before) return null   // a dial at its end: nothing saved, nothing re-laid
        ReadingTypes.save(ctx, profile, after)
        refresh(after)
        onChange(profile)
        return after
    }

    private fun refresh(t: ReadingType) = refreshers.forEach { it(t) }

    private fun setIfChanged(tv: TextView, s: String) { if (tv.text.toString() != s) tv.text = s }

    private fun spread() {
        val hook = toggleSpread
        // The reader's own toggle when the panel moves the book it is showing — it applies at
        // once and stands down a comic's panel walk, exactly as the old row did.
        if (hook != null && profile == Profile.BOOK && start == Profile.BOOK) { hook(); refresh(load()) }
        else edit { it.copy(columns = if (it.columns == 2) 1 else 2) }
    }

    /**
     * A theme is its colours: custom colours that would not read on the new page (white text
     * carried onto Default's white page) give way to it, and it says so. Custom colours that
     * still read stay (review, Michael 2026-10-11).
     */
    private fun pickTheme(th: String) {
        var cleared = false
        edit { t ->
            val next = t.copy(theme = th)
            val custom = next.fg.isNotBlank() || next.bg.isNotBlank()
            if (custom && ReadingType.contrastRatio(next.resolvedFg(), next.resolvedBg()) < ReadingType.READABLE) {
                cleared = true
                next.copy(fg = "", bg = "")
            } else next
        }
        if (cleared) host.showMessage("Your own colours don't read on ${AaType.themeChip(th)} — its colours instead")
    }

    private fun loadFaces() = AaType.faces(profile, ReadingFonts.userFonts(ctx).map { it.id to it.label })

    private fun launchImport() {
        val l = importLauncher ?: return
        runCatching { l.launch(ReadingFonts.PICK_TYPES) }
            .onFailure { host.showMessage("No file picker on this device") }
    }

    // ---- sub-pickers ---------------------------------------------------------------------

    private fun pickFace() {
        val cur = AaType.faceIndex(load(), faceList)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
        }
        lateinit var d: AlertDialog
        faceList.forEachIndexed { i, f ->
            val name = TextView(ctx).apply {
                text = mark(i == cur) + f.label
                textSize = 18f
                minHeight = px(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), px(10), px(20), px(10))
                setBackgroundResource(R.drawable.eink_press_bg)
                ReadingFonts.previewTypeface(ctx, f.family, f.customFontId)?.let { typeface = it }
                setOnClickListener { d.dismiss(); edit { f.applyTo(it) } }
                // Hold to act (the reader's canon) still works; the ✎ beside it is the plain door.
                if (f.isImported) setOnLongClickListener { manageFont(f, d); true }
            }
            if (!f.isImported) { list.addView(name); return@forEachIndexed }
            // An imported face renames or goes from its ✎ — a tap, not a hold you'd have to know
            // about (review, Michael 2026-10-11).
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(glyph("✎") { manageFont(f, d) })
            })
        }
        if (importLauncher != null) list.addView(row("＋  Import font…") { d.dismiss(); launchImport() })
        d = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle("Face")
            .setView(ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        host.showModal(d)
    }

    private fun manageFont(f: AaType.FaceChoice, picker: AlertDialog) {
        host.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(f.label)
            .setItems(arrayOf("Rename…", "Remove")) { _, which ->
                picker.dismiss()
                if (which == 0) renameFont(f) else confirmRemove(f)
            }
            .create())
    }

    private fun renameFont(f: AaType.FaceChoice) {
        val input = EditText(ctx).apply {
            setText(f.label)
            setSingleLine()
            setSelection(text.length)
        }
        // Guarded: a name being typed is work — a stray touch outside must not throw it away.
        host.showGuardedModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle("Rename face")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                if (ReadingFonts.rename(ctx, f.customFontId, input.text.toString())) render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun confirmRemove(f: AaType.FaceChoice) {
        host.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle("Remove “${f.label}”?")
            .setMessage("Books and articles set in it go back to their own face.")
            .setPositiveButton("Remove") { _, _ ->
                ReadingFonts.remove(ctx, f.customFontId)
                render()
                // Either profile may have used it; each host re-lays only the one it shows.
                onChange(Profile.BOOK); onChange(Profile.ARTICLE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    /** Width, chosen outright from its rungs. */
    private fun pickWidth() {
        val cur = load().maxWidthPx
        val labels = AaType.WIDTHS.map { w -> mark(w == cur) + if (w <= 0) "No limit" else "$w px" }
        host.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle("Width")
            .setItems(labels.toTypedArray()) { _, which -> edit { it.copy(maxWidthPx = AaType.WIDTHS[which]) } }
            .create())
    }

    private fun pickColour(text: Boolean) {
        val t = load()
        val cur = if (text) t.fg else t.bg
        val presets = if (text) AaType.TEXT_COLOURS else AaType.PAGE_COLOURS
        val onPreset = presets.any { it.first == cur }
        val labels = listOf(mark(cur.isBlank()) + "The theme's own") +
            presets.map { (hex, name) -> mark(hex == cur) + "$name · $hex" } +
            listOf(mark(cur.isNotBlank() && !onPreset) + "Other…" + (if (cur.isNotBlank() && !onPreset) " · $cur" else ""))
        fun set(hex: String) {
            val saved = edit { if (text) it.copy(fg = hex) else it.copy(bg = hex) } ?: return
            // Said, not refused: the page is right there to judge by, and the theme's own is one
            // tap back (review, Michael 2026-10-11: no unreadable pair without a word).
            when {
                saved.theme == "contrast" -> host.showMessage("High contrast keeps black on white — this shows under the other themes")
                ReadingType.contrastRatio(saved.resolvedFg(), saved.resolvedBg()) < ReadingType.READABLE ->
                    host.showMessage("Hard to read on this page — try another, or the theme's own")
            }
        }
        host.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(if (text) "Text colour" else "Page colour")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> set("")
                    labels.lastIndex -> hexPrompt(text, cur) { set(it) }
                    else -> set(presets[which - 1].first)
                }
            }
            .create())
    }

    private fun hexPrompt(text: Boolean, cur: String, done: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(cur)
            hint = "#333333"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        host.showGuardedModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(if (text) "Text colour" else "Page colour")
            .setView(padded(input))
            .setPositiveButton("Use") { _, _ ->
                val hex = AaType.parseHex(input.text.toString())
                if (hex == null) host.showMessage("That isn't a colour like #333333") else done(hex)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun editCss() {
        val input = EditText(ctx).apply {
            setText(load().customCss)
            hint = "p { text-align: left; }"
            typeface = Typeface.MONOSPACE
            textSize = 14f
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        host.showGuardedModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(if (profile == Profile.BOOK) "Custom CSS · books" else "Custom CSS · articles")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ -> edit { it.copy(customCss = input.text.toString()) } }
            .setNeutralButton("Clear") { _, _ -> edit { it.copy(customCss = "") } }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun confirmReset() {
        val book = profile == Profile.BOOK
        host.showModal(AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(if (book) "Start books over?" else "Start articles over?")
            .setMessage("Every Aa Type setting for ${if (book) "books" else "articles"} goes back to how it started. " +
                "Imported fonts stay.")
            .setPositiveButton("Start over") { _, _ ->
                ReadingTypes.reset(ctx, profile)
                render()
                onChange(profile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create())
    }

    private fun padded(v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(8), px(20), 0)
        addView(v)
    }

    private fun mark(on: Boolean) = if (on) "◉  " else "○  "

    private fun px(v: Int) = (v * density).toInt()

    private companion object {
        val SECTIONS = listOf("Font", "Spacing", "Layout", "Colour", "Advanced")
    }
}
