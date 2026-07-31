package com.toolsboox.plugin.feeds.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.toolsboox.ot.InkPadView
import com.toolsboox.plugin.calendar.ot.LedgerTitleInk
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NAMING A DOCUMENT, WITH THE PEN IN YOUR HAND — the one dialog every "name this / rename this /
 * new writing" path in the app now goes through.
 *
 * Michael asked "Can I hand write the title?" This is where that happens, and the shape of it is
 * the whole answer to the awkward part of the question: a title has to be BOTH text and ink.
 *
 *  • You write it on the pad. The ink is kept and becomes the face on page one of the document.
 *  • You tap "Read the handwriting". The recognition lands IN THE FIELD, where you read it and fix
 *    it. Nothing is ever saved from the recogniser directly.
 *  • What is saved as the NAME is what is in the field — typed, corrected, or both. That string is
 *    what [com.toolsboox.plugin.calendar.ot.WritePageStore] indexes, what the directory searches
 *    and sorts by, and what the page header draws.
 *
 * THE FIELD IS NOT OPTIONAL, and this is the same rule [com.toolsboox.plugin.calendar.ui.BlueskyFragment]
 * spells out for a public timeline: "OCR of real handwriting is good, not perfect." The stake here
 * is different but not smaller. A misread title is not a typo you notice — it is a document filed
 * under a name you will never think to search for, discovered a year later when you go looking for
 * something you are certain you wrote. So the recogniser fills the field and the field is what
 * saves, and a pad with ink on it and an empty field REFUSES to save with the reason said out loud
 * rather than filing a title only a person can read.
 *
 * WHERE THIS DIFFERS FROM THE BLUESKY PAD, deliberately: that one APPENDS each recognition to the
 * field, because a 300-grapheme reply is written a pad-full at a time and a second read is the next
 * paragraph. A title is one line, so a second read is a RETRY of the first — appending would give
 * "The Oxford essay The Oxford essay". It replaces, and the pad is never cleared by reading, so a
 * bad read costs a tap and not the handwriting.
 */

/** What the caller gets back: the approved name, and the face he wrote it with (null when he
 *  typed it, or when the surface holds no faces — in which case whatever face is already stored
 *  for the document is left exactly as it is). */
internal fun promptTitle(
    fragment: ScreenFragment,
    surface: String,
    dialogTitle: String,
    fieldHint: String,
    seed: String?,
    saveLabel: String,
    onSave: (name: String, writtenFace: Bitmap?) -> Unit,
) {
    val ctx = fragment.requireContext()
    val density = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()

    val input = EditText(ctx).apply {
        if (!seed.isNullOrBlank()) setText(seed) else hint = fieldHint
        setSingleLine()
        setPadding(px(10), px(8), px(10), px(8))
    }

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(8), px(16), 0)
    }

    // The pen half only exists where a face has somewhere to live. On Pickings and Text Notes this
    // dialog is exactly the typed field it has always been — see [LedgerTitleInk.supports] for why
    // those two are excluded rather than half-supported.
    val pad = if (LedgerTitleInk.supports(surface)) InkPadView(ctx) else null

    if (pad != null) {
        box.addView(TextView(ctx).apply {
            text = "Write the title, read it into the field, then fix anything it got wrong."
            textSize = 13f; setTextColor(0xFF777777.toInt()); setPadding(0, 0, 0, px(4))
        })
        box.addView(InkPadView.penBar(ctx, pad))
        box.addView(FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            setPadding(px(2), px(2), px(2), px(2))
            addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(150)))
        })
    }

    val readBtn = TextView(ctx).apply {
        text = "✍  Read the handwriting"
        textSize = 15f; setTextColor(0xFF2F6F96.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, px(10), px(16), px(6))
        gravity = Gravity.START
    }
    if (pad != null) box.addView(readBtn)
    box.addView(input, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // A PANEL YOU WRITE ON KEEPS ITS BUTTONS AT THE TOP — Michael's rule, stated while watching
    // this very dialog: the writing hand RESTS below the pad, so anything tappable down there gets
    // hit by a palm mid-word. Cancel and Save therefore ride in a row ABOVE everything when a pad
    // is present (the pen bar already lives up there for the same reason). The typed-only variant
    // keeps the platform's bottom buttons: nothing rests on a dialog you only type into.
    val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
        .setTitle(dialogTitle)
        .setView(ScrollView(ctx).apply { addView(box) })
    if (pad == null) {
        dialogBuilder.setPositiveButton(saveLabel, null)     // wired after show(); see below
        dialogBuilder.setNegativeButton(android.R.string.cancel, null)
    }
    val dialog = dialogBuilder.create()

    // The save path sometimes has to REFUSE (see below) — one handler serves both button homes.
    // `saving` closes the double-tap window a platform positive button used to close by
    // dismissing instantly: this handler does real work (crop, PNG, store write) before its
    // dismiss, and a palm bounce on SAVE would otherwise mint the document twice. A REFUSAL
    // resets it, because the user corrects the field and taps again.
    var saving = false
    val trySave = fun() {
        if (saving) return
        saving = true
        val name = input.text.toString().trim()
        val face = pad?.let { croppedFace(it) }
        if (face != null && name.isEmpty()) {
            fragment.showMessage(
                "Read the handwriting into the field first (or type the title) — " +
                    "a title that is only a picture can't be searched for."
            )
            face.recycle()
            saving = false
            return
        }
        onSave(name, face)
        dialog.dismiss()
    }

    if (pad != null) {
        readBtn.setOnClickListener { recogniseTitle(fragment, pad, input) }
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, px(6))
            addView(TextView(ctx).apply {
                text = "CANCEL"
                textSize = 15f; setTextColor(0xFF555555.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, px(6), px(28), px(6))
                setOnClickListener { dialog.dismiss() }
            })
            addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(ctx).apply {
                text = saveLabel.uppercase()
                textSize = 15f; setTextColor(0xFF2F6F96.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setPadding(px(28), px(6), 0, px(6))
                setOnClickListener { trySave() }
            })
        }
        box.addView(actionRow, 0)
    }

    fragment.showModal(dialog)

    // Wired AFTER show() rather than through setPositiveButton's own listener, because that one
    // dismisses the dialog before it runs and this button sometimes has to REFUSE. A title that is
    // only a picture is the one outcome this whole dialog exists to prevent; catching it by closing
    // the dialog and popping a toast would leave him with the ink gone and nothing named.
    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        trySave()
    }
}

/**
 * Run the pad through the vision OCR and put the result in the field.
 *
 * Reuses the app's ONE recognition path — the encrypted Ask-my-Ledger credentials, the OCR model
 * override that the day page's lasso recognition honours, and
 * [com.toolsboox.plugin.calendar.nw.VisionOcr.recognize] — so a title is read by the same model,
 * with the same key and the same prompt, as every other piece of handwriting in the fork. A second
 * recogniser here would be a second thing to keep tuned and a second place for the confabulation
 * brake to be forgotten.
 *
 * EVERY FAILURE SAYS SO AND SAVES NOTHING. No key, no network, an unreadable scrawl, a model that
 * replies with an apology instead of a transcription (VisionOcr's refusal brake catches that one) —
 * each ends with a sentence on screen and the pad untouched, because whatever is on the pad is the
 * only copy of what he wrote and "try again" must not mean "write it again". Typing the title is
 * always available and always was; recognition is a convenience, never the gate.
 */
private fun recogniseTitle(fragment: ScreenFragment, pad: InkPadView, input: EditText) {
    val ctx = fragment.requireContext()
    if (pad.isBlank()) { fragment.showMessage("Nothing written on the pad yet."); return }
    val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
    if (creds == null) {
        fragment.showMessage(
            "No AI key yet — add one in Ask my Ledger settings, or just type the title. " +
                "The handwriting is kept either way."
        )
        return
    }
    val model = com.toolsboox.ui.plugin.OcrModel.override(ctx, creds.first) ?: creds.third
    val bmp = croppedFace(pad) ?: run { fragment.showMessage("Nothing written on the pad yet."); return }
    fragment.showMessage("Reading your handwriting…")
    fragment.lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) {
            com.toolsboox.plugin.calendar.nw.VisionOcr.recognize(bmp, creds.first, creds.second, model)
        }
        bmp.recycle()
        if (!fragment.isAdded) return@launch
        if (text.isNullOrBlank()) {
            fragment.showMessage("Couldn't read that — write it larger and read again, or type it.")
            return@launch
        }
        // One line, whatever the model returned: a title is a title, and a recogniser that decided
        // to describe the page instead would otherwise put a paragraph in a single-line field.
        val cleaned = text.replace(Regex("\\s+"), " ").trim().take(120)
        if (cleaned.isEmpty()) {
            fragment.showMessage("Couldn't read that — write it larger and read again, or type it.")
            return@launch
        }
        input.setText(cleaned)
        input.setSelection(cleaned.length)
    }
}

/**
 * The pad, cropped to what was actually written — or null when nothing was.
 *
 * Cropping matters twice over. On the page the face is fitted into a 420 × 52 slot, so a full pad
 * of mostly blank paper would scale one line of handwriting down to a smear; and the same bitmap
 * goes to the recogniser, which reads a tight crop more reliably than a line adrift in white space.
 */
private fun croppedFace(pad: InkPadView): Bitmap? {
    val full = pad.render() ?: return null
    val bounds = pad.inkBounds() ?: return full
    val l = bounds.left.toInt().coerceIn(0, full.width - 1)
    val t = bounds.top.toInt().coerceIn(0, full.height - 1)
    val r = bounds.right.toInt().coerceIn(l + 1, full.width)
    val b = bounds.bottom.toInt().coerceIn(t + 1, full.height)
    return runCatching {
        val out = Bitmap.createBitmap(full, l, t, r - l, b - t)
        if (out !== full) full.recycle()
        out
    }.getOrDefault(full)
}
