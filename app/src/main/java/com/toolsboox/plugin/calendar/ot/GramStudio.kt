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
     * Open the studio for [text]. Buttons appear for whichever actions are provided:
     * Share (always), "❝ Pickings" ([onPickings]) and "Here" ([onHere]); each receives
     * the rendered card(s) — one bitmap, or several when the Series fit is chosen.
     */
    fun show(
        fragment: ScreenFragment,
        text: String,
        source: String? = null,
        onShare: (List<Bitmap>) -> Unit,
        onPickings: ((List<Bitmap>) -> Unit)? = null,
        onHere: ((List<Bitmap>) -> Unit)? = null,
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ctx = fragment.requireContext()
        val fmt = arrayOf(QuoteCardRenderer.Format.SQUARE)
        val fit = arrayOf("shrink")   // shrink | pull | series
        val isLong = t.length > LONG_THRESHOLD

        fun renderAll(): List<Bitmap> {
            val texts = when (fit[0]) {
                "pull" -> listOf(pullQuote(t))
                "series" -> seriesChunks(t)
                else -> listOf(t)
            }
            val n = texts.size
            return texts.mapIndexed { i, part ->
                val src = if (n > 1) listOfNotNull(source?.takeIf { it.isNotBlank() }, "${i + 1}/$n")
                    .joinToString(" · ") else source
                QuoteCardRenderer.render(part, src, null, fmt[0].w, fmt[0].h)
            }
        }
        fun preview(): Bitmap = renderAll().first()

        val previewView = android.widget.ImageView(ctx).apply { adjustViewBounds = true; setImageBitmap(preview()) }
        val hint = android.widget.TextView(ctx).apply {
            textSize = 12f; setTextColor(0xFF666666.toInt()); visibility = android.view.View.GONE
        }
        fun refreshHint() {
            if (!isLong) return
            hint.visibility = android.view.View.VISIBLE
            hint.text = when (fit[0]) {
                "pull" -> "Opening lines on the gram — the full text stays a tap away via its source"
                "series" -> "${seriesChunks(t).size} cards — the whole passage, one thought per card"
                else -> "Whole passage shrunk to fit — small type"
            }
        }

        val formatRow = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        for (f in QuoteCardRenderer.Format.values()) {
            formatRow.addView(android.widget.Button(ctx).also {
                it.text = f.label; it.isAllCaps = false; it.textSize = 12f
                it.setOnClickListener { fmt[0] = f; previewView.setImageBitmap(preview()) }
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val padPx = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(formatRow)
        }
        if (isLong) {
            val fitRow = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for ((key, label) in listOf("shrink" to "Shrink", "pull" to "Pull-quote", "series" to "Series")) {
                fitRow.addView(android.widget.Button(ctx).also {
                    it.text = label; it.isAllCaps = false; it.textSize = 12f
                    it.setOnClickListener { fit[0] = key; previewView.setImageBitmap(preview()); refreshHint() }
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(fitRow)
            container.addView(hint)
            refreshHint()
        }
        container.addView(previewView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (ctx.resources.displayMetrics.heightPixels * 0.40f).toInt()
        ).apply { topMargin = padPx })

        val pickBtn = if (onPickings != null) android.widget.Button(ctx).also {
            it.text = "❝  Add to Pickings"; it.isAllCaps = false
            container.addView(it)
        } else null

        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Gram")
            .setView(android.widget.ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("Share") { _, _ -> onShare(renderAll()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (onHere != null) builder.setNeutralButton("Here") { _, _ -> onHere(renderAll()) }
        val dialog = builder.create()
        pickBtn?.setOnClickListener { dialog.dismiss(); onPickings?.invoke(renderAll()) }
        // Pause the Onyx pen while the studio is up, or stylus taps freeze the surface.
        fragment.showModal(dialog)
    }
}
