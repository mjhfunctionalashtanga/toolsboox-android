package com.toolsboox.plugin.reader.ui

import androidx.lifecycle.lifecycleScope
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Audiobookshelf sign-in — one dialog, two doors (Settings › Books and the shelf's menu). */
object AbsSettingsDialog {
    /** The server, and who you are to it — saved, then signed in, then the browser. */
    fun show(fragment: ScreenFragment, onSignedIn: () -> Unit) {
        val ctx = fragment.requireContext()
        val cfg = Audiobookshelf.config(ctx)
        val dp = ctx.resources.displayMetrics.density
        val urlIn = android.widget.EditText(ctx).apply { hint = "Server address (audiobooks.example.com)"; setSingleLine(); setText(cfg?.url.orEmpty()) }
        val userIn = android.widget.EditText(ctx).apply { hint = "Username"; setSingleLine(); setText(cfg?.user.orEmpty()) }
        val passIn = android.widget.EditText(ctx).apply {
            hint = "Password"; setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(cfg?.pass.orEmpty())
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
            addView(urlIn); addView(userIn); addView(passIn)
            if (cfg?.token?.isNotBlank() == true) addView(android.widget.TextView(ctx).apply { text = "Signed in as ${cfg.user}"; setPadding(0, (8 * dp).toInt(), 0, 0) })
        }
        val b = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Audiobookshelf")
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Sign in") { _, _ ->
                Audiobookshelf.save(ctx, urlIn.text.toString(), userIn.text.toString(), passIn.text.toString(), token = "")
                fragment.showMessage("Signing in…")
                fragment.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Audiobookshelf.login(ctx) } }
                    if (!fragment.isAdded) return@launch
                    r.onSuccess { onSignedIn() }.onFailure { fragment.showMessage(it.message ?: "Couldn't sign in.") }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (cfg != null) b.setNeutralButton("Forget") { _, _ -> Audiobookshelf.forget(ctx); fragment.showMessage("Audiobookshelf forgotten.") }
        fragment.showModal(b.create())
    }
}
