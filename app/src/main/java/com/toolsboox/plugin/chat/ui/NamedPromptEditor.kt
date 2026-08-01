package com.toolsboox.plugin.chat.ui

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.toolsboox.ot.ModalScale
import com.toolsboox.ui.plugin.ScreenFragment

/**
 * A name and a body of prompt text, saved or thrown away — which is the whole of editing a persona
 * AND the whole of editing a preset.
 *
 * Written as one dialog rather than two near-identical ones because the second copy is where they
 * start to differ by accident: one grows a delete button, the other doesn't; one trims on save, the
 * other keeps trailing newlines. Personas and presets are two different things (a stance vs a
 * question — see [com.toolsboox.plugin.chat.nw.AskPreset]), but they are stored the same way and
 * edited the same way, so they get ONE editor. Michael should not have to learn a second form to do
 * the same job on the second list. What differs between them is only wording and where Save lands,
 * which is what the parameters are. The iPad grew the same single `NamedPromptEditor` over both
 * lists; this is its counterpart.
 *
 * The prompt field is multi-line and stays one — a preset can be a whole workflow ("do this, then
 * that, then save it as a note"), and a single-line field would quietly teach you to write only
 * one-liners. Nothing is truncated on save; only the outer whitespace goes.
 */
object NamedPromptEditor {

    /**
     * @param original the name this opened on — blank for a new one. Callers use it to retire the
     *   old key on a rename (both stores are keyed by name, so a rename without it breeds a second
     *   row), and it is also what Delete acts on, so deleting after typing a new name still removes
     *   the row that was actually opened.
     * @param onSave (name, prompt) — both already trimmed, both guaranteed non-blank.
     * @param onDelete null when there is nothing to delete yet.
     */
    fun show(
        fragment: ScreenFragment,
        title: String,
        nameHint: String,
        promptHint: String,
        footnote: String,
        original: String,
        prompt: String,
        onSave: (String, String) -> Unit,
        onDelete: ((String) -> Unit)? = null,
    ) {
        val ctx = fragment.requireContext()
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val nameEdit = EditText(ctx).apply {
            hint = nameHint
            setText(original)
            setSingleLine()
            setTextColor(0xFF000000.toInt())
        }
        val promptEdit = EditText(ctx).apply {
            hint = promptHint
            setText(prompt)
            // Six lines rather than four: a workflow preset is several steps, and a box that shows
            // one step at a time is a box that argues for one-step presets.
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(0xFF000000.toInt())
        }
        val note = TextView(ctx).apply {
            text = footnote
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            setPadding(0, pad / 3, 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameEdit); addView(promptEdit); addView(note)
        }

        val builder = AlertDialog.Builder(ModalScale.wrap(ctx))
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val n = nameEdit.text.toString().trim()
                // trim() and not take(n): the whole point of the multi-line field is that a preset
                // may run to a paragraph, and a cap applied here would silently eat the tail of a
                // workflow the moment it got long enough to be worth saving.
                val p = promptEdit.text.toString().trim()
                if (n.isNotEmpty() && p.isNotEmpty()) onSave(n, p)
                else fragment.showMessage("Give it a name and a prompt.", fragment.view)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (onDelete != null && original.isNotBlank()) {
            builder.setNeutralButton("Delete") { _, _ -> onDelete(original) }
        }
        // The guarded door: a preset's name and prompt mid-edit are work — a stray touch
        // outside must not throw them away. Cancel and the back gesture remain the ways out.
        fragment.showGuardedModal(builder.create())
    }
}
