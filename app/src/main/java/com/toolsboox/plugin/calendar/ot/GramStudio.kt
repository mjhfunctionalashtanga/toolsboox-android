package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import com.toolsboox.ui.plugin.ScreenFragment

/**
 * The shared gram studio — turn ANY text (lasso OCR, article selection, shared text,
 * log entries) into gram(s): pick a format (square/portrait/landscape/story) and,
 * when the text is long, a fit strategy. One dialog everywhere, so every text→gram
 * path gets sizes instead of a silent fixed square.
 *
 * Long-text strategy (mirrors iOS GramStudioView):
 *  - SHRINK: whole passage, smaller type (readable to ~400 chars).
 *  - PULL-QUOTE: opening sentences + ellipsis — the gram is the hook; the full text
 *    stays one tap away through the gram's source link (rhizome, not truncation).
 *  - SERIES: sentence-packed cards labeled 1/n · 2/n — the whole passage, one
 *    thought per card.
 */
object GramStudio {

    private const val LONG_THRESHOLD = 420

    /** Past 420 chars the studio offers Pull-quote and Series — counted in CODE POINTS, a char as
     *  both apps count it (iOS's Unicode scalars; review 2026-10-16 B#19: this counted UTF-16 units). */
    fun isLong(text: String): Boolean = text.trim().let { it.codePointCount(0, it.length) } > LONG_THRESHOLD

    /** Opening sentences up to ~[cap] chars, closed with an ellipsis. */
    fun pullQuote(text: String, cap: Int = 240): String {
        val out = StringBuilder()
        for (sentence in text.split('.')) {
            val t = sentence.trim()
            if (t.isEmpty()) continue
            if (out.length + t.length > cap && out.isNotEmpty()) break
            out.append(t).append(". ")
            if (out.length > cap) break
        }
        return out.toString().trim() + " …"
    }

    /** Sentence-packed chunks ≤ ~[cap] chars for the card series. */
    fun seriesChunks(text: String, cap: Int = 380): List<String> {
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (sentence in text.split('.')) {
            val t = sentence.trim()
            if (t.isEmpty()) continue
            if (cur.length + t.length > cap && cur.isNotEmpty()) { chunks.add(cur.toString().trim()); cur.clear() }
            cur.append(t).append(". ")
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString().trim())
        return chunks.ifEmpty { listOf(text) }
    }

    /**
     * THE PICK SHEET (add-to-pick pin §2 D1), in this order on both apps: `＋ Add to this pick` (with
     * what's on the pick as chips under it — tap one to open it) → Format Square/Portrait/Landscape/
     * Story → `Picture on the pick` (when there is one) → Fit (past 420 chars) → the preview, which IS
     * the baked face → the actions.
     *
     * TIED ([tie], a highlight's own pick, opened from a passage): each add goes straight onto the
     * highlight's line; "❝ Add to Pickings" RE-FACES that pick in the chosen shape and remembers it
     * (mark.face) — never a second card; Cancel keeps the adds and leaves the shape alone.
     * UNTIED ([pending], or one of its own): each file is in the attachment store the moment it is
     * made and waits here; what the host places wears it ([AddToPick.Pending.stamp]).
     * Share / Here / the host's own Pickings each receive the rendered card(s) — one bitmap, or
     * several when the Series fit is chosen — with the adds baked into card 1.
     */
    fun show(
        fragment: ScreenFragment,
        text: String,
        source: String? = null,
        onShare: (List<Bitmap>) -> Unit,
        onPickings: ((List<Bitmap>) -> Unit)? = null,
        onHere: ((List<Bitmap>) -> Unit)? = null,
        tie: com.toolsboox.plugin.feeds.ot.AddToPick.Tied? = null,
        pending: com.toolsboox.plugin.feeds.ot.AddToPick.Pending? = null,
        /** The selection's own picture — "Picture on the pick" (shown only when there is one). */
        picture: Bitmap? = null,
        /**
         * UNTIED: what was added in the sheet when it closes with no card placed (Cancel, Share only)
         * — the files are in the store already, so they are filed where they can be found again
         * (review 2026-10-16 #6: they were orphaned). Null: today's A/V list, on disk; a host that
         * holds the open day files them there in memory.
         */
        keepUnplaced: ((List<com.toolsboox.da.Attachment>) -> Unit)? = null,
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ctx = fragment.requireContext()
        val app = ctx.applicationContext
        val dir = com.toolsboox.ot.LedgerPaths.attachmentsDir(app)
        val sinkPending = if (tie == null) (pending ?: com.toolsboox.plugin.feeds.ot.AddToPick.Pending(app, t)) else null
        val sink: com.toolsboox.plugin.feeds.ot.AddToPick.Sink = tie ?: sinkPending!!
        val isLong = isLong(t)
        // TIED: the sheet opens on the shape the pick was designed in (review 2026-10-16 #12 — it
        // always opened on Square, and ❝ then re-designed a Portrait pick as Square). iOS seed().
        val seed = runCatching { tie?.markNow()?.face }.getOrNull()
        val fmt = arrayOf(seed?.format?.let { f -> QuoteCardRenderer.Format.values().firstOrNull { it.name.equals(f, ignoreCase = true) } }
            ?: QuoteCardRenderer.Format.SQUARE)
        val fit = arrayOf(if (!isLong) "shrink" else when (seed?.fit) {   // shrink | pull | series
            com.toolsboox.plugin.reader.ui.MarkFace.FIT_PULLQUOTE -> "pull"
            com.toolsboox.plugin.reader.ui.MarkFace.FIT_SERIES -> "series"
            else -> "shrink"
        })
        val withPicture = booleanArrayOf(picture != null && (seed?.picture ?: true))
        /** ❝ or Here was pressed: what waits in the sheet is on its way onto a card. */
        val placing = booleanArrayOf(false)
        val addsRef = java.util.concurrent.atomic.AtomicReference(PickFace.Adds.NONE)
        val currentRef = java.util.concurrent.atomic.AtomicReference(
            com.toolsboox.plugin.feeds.ot.AddToPick.Current("", emptyList()))

        fun shape(): PickFaceLayout.Shape = PickFaceLayout.Shape.valueOf(fmt[0].name)

        /** Every card, card 1 wearing the adds (a Series' others stay plain — and no picture). */
        fun renderAll(): List<PickFace.Rendered> {
            val texts = when (fit[0]) {
                "pull" -> listOf(pullQuote(t))
                "series" -> seriesChunks(t)
                else -> listOf(t)
            }
            val n = texts.size
            val first = addsRef.get().copy(picture = if (withPicture[0]) picture else null)
            return texts.mapIndexed { i, part ->
                val src = if (n > 1) listOfNotNull(source?.takeIf { it.isNotBlank() }, "${i + 1}/$n")
                    .joinToString(" · ") else source
                PickFace.render(shape(), part, src, if (i == 0) first else PickFace.Adds.NONE)
            }.also { cards -> sinkPending?.layout = cards.firstOrNull()?.layout }
        }

        val previewView = android.widget.ImageView(ctx).apply { adjustViewBounds = true }
        val chipRow = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val hint = android.widget.TextView(ctx).apply {
            textSize = 12f; setTextColor(0xFF666666.toInt()); visibility = android.view.View.GONE
        }
        fun drawChips() {
            chipRow.removeAllViews()
            for ((label, att) in com.toolsboox.plugin.feeds.ot.AddToPick.chips(currentRef.get())) {
                chipRow.addView(android.widget.TextView(ctx).apply {
                    this.text = label; textSize = 15f; setTextColor(0xFF000000.toInt())
                    val pad = (8 * ctx.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                    setOnClickListener { com.toolsboox.plugin.feeds.ot.AddToPick.open(fragment, att) }
                })
            }
            chipRow.visibility = if (chipRow.childCount == 0) android.view.View.GONE else android.view.View.VISIBLE
        }
        /** Re-read what's on the pick (off the main thread), then redraw the face. */
        fun refresh() {
            Thread {
                runCatching {
                    currentRef.set(sink.current())
                    addsRef.set(tie?.adds(dir) ?: sinkPending!!.adds())
                    val face = renderAll().first().bitmap
                    com.toolsboox.plugin.feeds.ot.AddToPick.main {
                        if (fragment.isAdded) { previewView.setImageBitmap(face); drawChips() }
                    }
                }.onFailure { timber.log.Timber.w(it, "pick sheet preview") }
            }.apply { isDaemon = true }.start()
        }
        fun preview() {
            Thread {
                runCatching {
                    val face = renderAll().first().bitmap
                    com.toolsboox.plugin.feeds.ot.AddToPick.main { if (fragment.isAdded) previewView.setImageBitmap(face) }
                }
            }.apply { isDaemon = true }.start()
        }
        tie?.onChange = { refresh() }
        sinkPending?.onChange = { refresh() }

        fun refreshHint() {
            if (!isLong) return
            hint.visibility = android.view.View.VISIBLE
            hint.text = when (fit[0]) {
                "pull" -> "Opening lines on the gram — the full text stays a tap away via its source"
                "series" -> "${seriesChunks(t).size} cards — the whole passage, one thought per card"
                else -> "Whole passage shrunk to fit — small type"
            }
        }

        val padPx = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
        }
        // 1. ＋ Add to this pick — the Add ladder; what's on the pick as chips under it.
        container.addView(android.widget.Button(ctx).apply {
            this.text = com.toolsboox.plugin.calendar.ot.AddToPickWords.ADD; isAllCaps = false
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { com.toolsboox.plugin.feeds.ot.AddToPick.showLadder(fragment, sink) }
        })
        container.addView(chipRow)
        // 2. Format.
        val formatRow = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        for (f in QuoteCardRenderer.Format.values()) {
            formatRow.addView(android.widget.Button(ctx).also {
                it.text = f.label; it.isAllCaps = false; it.textSize = 12f
                it.setOnClickListener { fmt[0] = f; preview() }
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(formatRow)
        // 3. Picture on the pick — only when there is a picture to talk about.
        if (picture != null) container.addView(android.widget.CheckBox(ctx).apply {
            this.text = "Picture on the pick"; isChecked = withPicture[0]
            setOnCheckedChangeListener { _, on -> withPicture[0] = on; preview() }
        })
        // 4. Fit, past 420 chars.
        if (isLong) {
            val fitRow = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for ((key, label) in listOf("shrink" to "Shrink", "pull" to "Pull-quote", "series" to "Series")) {
                fitRow.addView(android.widget.Button(ctx).also {
                    it.text = label; it.isAllCaps = false; it.textSize = 12f
                    it.setOnClickListener { fit[0] = key; preview(); refreshHint() }
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(fitRow)
            container.addView(hint)
            refreshHint()
        }
        // 5. The preview — the baked face itself.
        container.addView(previewView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (ctx.resources.displayMetrics.heightPixels * 0.40f).toInt()
        ).apply { topMargin = padPx })

        // 6. The actions.
        val pickBtn = if (onPickings != null || tie != null) android.widget.Button(ctx).also {
            it.text = "❝  Add to Pickings"; it.isAllCaps = false
            container.addView(it)
        } else null

        /** UNTIED: what waits in the sheet and no card wears — kept where it can be found again
         *  ([keepUnplaced], else today's A/V list). Main thread. */
        fun keepAdds() {
            val p = sinkPending ?: return
            if (p.placed) return
            val held = p.attachments()
            if (held.isEmpty()) return
            if (keepUnplaced != null) keepUnplaced(held) else keepOnToday(app, held)
        }

        /** The rendered cards to [then]; none rendered while placing (❝ / Here): nothing will wear
         *  the adds, so they are kept. */
        fun cards(placingNow: Boolean = false, then: (List<Bitmap>) -> Unit) {
            Thread {
                val out = runCatching { renderAll().map { it.bitmap } }.getOrDefault(emptyList())
                com.toolsboox.plugin.feeds.ot.AddToPick.main {
                    if (out.isNotEmpty()) then(out) else if (placingNow) keepAdds()
                }
            }.apply { isDaemon = true }.start()
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Gram")
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("Share") { _, _ -> cards(then = onShare) }
            // Cancel keeps the adds — on a highlight they are on its line already.
            .setNegativeButton(android.R.string.cancel, null)
        if (onHere != null) builder.setNeutralButton("Here") { _, _ -> placing[0] = true; cards(placingNow = true, then = onHere) }
        val dialog = builder.create()
        /**
         * Closed: an untied sheet's adds that no card is on its way to wear are kept. Once ❝ or Here
         * was pressed the HOST owns them — a placement it refuses or that is cancelled keeps them
         * itself (CalendarDayFragment.keepStudioAdds; merge-addpick review #4). Heard through
         * [ScreenFragment.showModal]'s dismiss door: a listener set here before it was replaced.
         */
        val onClosed: () -> Unit = {
            tie?.onChange = null; sinkPending?.onChange = null
            if (!placing[0]) keepAdds()
        }
        pickBtn?.setOnClickListener {
            placing[0] = true
            dialog.dismiss()
            if (tie != null) {
                val face = com.toolsboox.plugin.reader.ui.MarkFace(
                    format = PickFaceLayout.Shape.valueOf(fmt[0].name).key,
                    fit = when (fit[0]) { "pull" -> com.toolsboox.plugin.reader.ui.MarkFace.FIT_PULLQUOTE
                        "series" -> com.toolsboox.plugin.reader.ui.MarkFace.FIT_SERIES
                        else -> com.toolsboox.plugin.reader.ui.MarkFace.FIT_SHRINK },
                    // No picture to talk about in this sheet: the setting stays as it was (the
                    // other device may draw the selection's picture) — never turned off unasked.
                    picture = if (picture != null) withPicture[0] else (seed?.picture ?: true))
                Thread {
                    val more = if (fit[0] == "series") runCatching { renderAll().drop(1).map { it.bitmap } }.getOrDefault(emptyList()) else emptyList()
                    val msg = tie.engine.designed(tie.article, tie.mark, face, more)
                    com.toolsboox.plugin.feeds.ot.AddToPick.main {
                        if (msg != null) com.toolsboox.plugin.feeds.ot.AddToPick.say(app, msg)
                        runCatching { fragment.onExternalGramPlaced(CalendarDayPageNotes.GRAM_PICKS) }
                    }
                }.apply { isDaemon = true }.start()
            } else cards(placingNow = true) { onPickings?.invoke(it) }
        }
        // Pause the Onyx pen while the studio is up, or stylus taps freeze the surface.
        fragment.showModal(dialog, onClosed)
        refresh()
    }

    /** An untied sheet's adds with no card to wear them: onto today's A/V list (the Log shows them
     *  there), under the day's lock, off the main thread. Today's file that won't read is never
     *  written over (the day-file guard — today through the writer's door): it says so, in the
     *  shared words ("Couldn't write on today — its page won't read"), and the files stay in the
     *  attachment store. */
    fun keepOnToday(app: android.content.Context, atts: List<com.toolsboox.da.Attachment>) {
        Thread {
            runCatching {
                val service = com.toolsboox.ui.share.ShareActions.service(app)
                val root = com.toolsboox.ot.LedgerPaths.documentsRoot(app)
                val day = java.time.LocalDate.now()
                DayLocks.withDay(day) {
                    val cal = ReadingEventEdit.loadForWriteOrThrow(service, root, day) ?: return@withDay
                    val fresh = atts.filter { a -> cal.avGrams.none { it.id == a.id } }
                    if (fresh.isEmpty()) return@withDay
                    cal.avGrams.addAll(fresh)
                    service.save(root, day, cal)
                }
            }.onFailure {
                timber.log.Timber.w(it, "pick sheet: keeping its adds on today")
                if (it is com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException)
                    com.toolsboox.plugin.feeds.ot.AddToPick.say(app, MarkWords.TODAY_REFUSED)
            }
        }.apply { isDaemon = true }.start()
    }
}
