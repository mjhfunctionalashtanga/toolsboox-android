package com.toolsboox.ui.share

import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView

/**
 * LEDGABLE NEVER OFFERS ITSELF IN ITS OWN SELECTION MENUS (2026-10-10). Once Ledgable answers
 * PROCESS_TEXT — the door EinkBro's highlight menu lists — Android adds "Ledgable" to every text
 * selection menu on the device, ours included: the reader's web views beside ❝ Save highlight, the
 * LetterPad's fields, the 🧵 letter text. Tapping it there would open the card over our own ink
 * surface, which detaches the B6 daemon and flashes the wrong page on return (critique C9). So our
 * menus hide it — the item is HIDDEN, not removed: text-processing items all share item id 0, and
 * a removeItem(0) could take someone else's item.
 */
object OwnProcessText {

    /** Hide every item in [menu] that would start Ledgable's own text door. True when one went. */
    fun strip(menu: Menu?, context: Context): Boolean {
        menu ?: return false
        val label = runCatching {
            context.getString(com.toolsboox.R.string.ledger_share_target_title)
        }.getOrDefault("Ledgable")
        var hid = false
        for (i in 0 until menu.size()) {
            val item: MenuItem = menu.getItem(i)
            if (!item.isVisible) continue
            val intent = item.intent
            val ours = if (intent != null) {
                intent.action == Intent.ACTION_PROCESS_TEXT &&
                    intent.component?.packageName == context.packageName
            } else {
                // A newer WebView keeps its own intent table and hands the menu a bare item; the
                // label is then the only mark, and no one else's item is called "Ledgable".
                item.title?.toString() == label
            }
            if (ours) { item.isVisible = false; hid = true }
        }
        return hid
    }

    /**
     * For a TextView whose selection menu lives in a DIALOG's window — MainActivity's
     * onActionModeStarted only hears its own window. The custom callback's prepare runs after the
     * framework has filled the menu, so it sees the text-processing items. A view that already
     * has its own callback is left alone.
     */
    fun guard(view: TextView) {
        if (view.customSelectionActionModeCallback != null) return
        view.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                strip(menu, view.context)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    /**
     * [guard] every field you can select text in under [root] — a dialog's whole tree, once it
     * is showing. ScreenFragment.showModal calls it, so every routed dialog (rename, title,
     * search, the new-pickings name) is covered in ONE place instead of field by field (review,
     * 2026-10-10: dialogs still listed "Ledgable", and a tap there opened the chooser over them).
     */
    fun guardTree(root: android.view.View?) {
        when (root) {
            is TextView -> if (root is android.widget.EditText || root.isTextSelectable) guard(root)
            is android.view.ViewGroup -> for (i in 0 until root.childCount) guardTree(root.getChildAt(i))
        }
    }
}
