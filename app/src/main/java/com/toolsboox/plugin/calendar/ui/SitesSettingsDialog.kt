package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.nw.LedgerSite
import com.toolsboox.plugin.calendar.nw.SiteStore

/**
 * The Sites settings surface — every WordPress site the app knows, each editable in its own dialog,
 * with one set ACTIVE — the DEFAULT the Fluent surfaces fall back to. Since the per-surface
 * affinity pass, "Use this site" means "point everything that hasn't chosen its own site here":
 * Messages/Correspondence/Site Boards default to ashtanga.tech, Bookings/Events/Roster to
 * theyoga.club, Publish/Posts to wherever you last published, and each surface's 🌐 chip can
 * re-point just itself ([com.toolsboox.plugin.calendar.nw.SiteRouting]). Reached from the Community/Boards section
 * of Calendar settings. Deliberately dialog-based and plain: no new navigation graph entry, no fancy
 * list widget — one tap opens the list, one tap opens a site, big rows and buttons for e-ink.
 *
 * Mirrors the iPad `SitesSettingsView` / `SiteEditor` in App/LedgerSites.swift. Activating a site
 * write-throughs its creds into the single-cred bridge keys, so Boards/Community/Correspondence/
 * Booking all re-point to it with no other change.
 */
object SitesSettingsDialog {

    /** Open the list of sites. [onChanged] fires whenever the active site or the list changes.
     *  [present] is how every dialog in this flow reaches the screen — a fragment caller MUST
     *  pass its showModal, or the Onyx raw ink session keeps the stylus and taps never arrive
     *  ("touching 'sites' popup with stylus freezes the popup", 08-17 page — the same
     *  guarded-dialog class as the Synthesize picker). */
    fun show(context: Context, present: (AlertDialog) -> Unit = { it.show() }, onChanged: () -> Unit = {}) {
        val ctx = ModalScale.wrap(context)
        val sites = SiteStore.all(context)
        val activeId = SiteStore.activeId(context)

        val labels = sites.map { s ->
            val mark = if (s.id == activeId) "●  " else "○  "   // ● active / ○ inactive
            val sub = if (s.url.isNotBlank()) "\n     ${s.url}" else ""
            "$mark${s.display}$sub"
        }.toMutableList()
        labels.add("+  Add site")
        // ledgable.cc/pair prints a pairing string; pasting it here does the whole editor in one go.
        labels.add("⧉  Paste a pairing string")
        // THE WAY BACK. `seedKnownSites` runs once behind a flag, so a deleted site can never
        // return on its own — see [SiteStore.reseedKnownSites]. Non-destructive by construction:
        // it only adds a URL the list does not already hold, so pressing it twice does nothing the
        // first press didn't.
        labels.add("↺  Restore my known sites")

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Sites")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < sites.size -> editor(context, sites[which], present, onChanged)
                    which == sites.size -> editor(context, LedgerSite(), present, onChanged)
                    which == sites.size + 1 -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val added = com.toolsboox.plugin.calendar.nw.PairLink.apply(context, text)
                        Toast.makeText(context,
                            if (added != null) "Site added: $added (active)"
                            else "The clipboard doesn't hold a pairing string — copy it from ledgable.cc/pair first",
                            Toast.LENGTH_LONG).show()
                        onChanged(); show(context, present, onChanged)
                    }
                    else -> {
                        val before = SiteStore.all(context).size
                        SiteStore.reseedKnownSites(context)
                        val added = SiteStore.all(context).size - before
                        Toast.makeText(context,
                            if (added > 0) "Restored $added site(s)" else "Nothing to restore — they're all here",
                            Toast.LENGTH_SHORT).show()
                        onChanged(); show(context, present, onChanged)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .create()
        present(dialog)
    }

    /** Edit (or create) one site: URL, login, application password, default board, portal slugs. */
    private fun editor(
        context: Context, site: LedgerSite,
        present: (AlertDialog) -> Unit = { it.show() }, onChanged: () -> Unit,
    ) {
        val ctx = ModalScale.wrap(context)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val existing = SiteStore.all(context).any { it.id == site.id }

        fun field(label: String, value: String, inputType: Int): Pair<View, EditText> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(10), 0, 0)
                addView(TextView(ctx).apply {
                    text = label; textSize = 12f; setTextColor(0xFF444444.toInt()); letterSpacing = 0.06f
                })
            }
            val edit = EditText(ctx).apply {
                setText(value); this.inputType = inputType; setSingleLine(inputType != InputType.TYPE_NULL)
            }
            box.addView(edit)
            return box to edit
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(4), px(20), px(4))
        }
        // Sentence-case labels (no shouting on e-ink) with the developer word "slug" dropped in favour
        // of "page", matching iOS App/LedgerSites.swift. A footer below explains the portal fields.
        val (nameV, nameE) = field("Name", site.name, InputType.TYPE_CLASS_TEXT)
        val (urlV, urlE) = field("URL (https://…)", site.url, InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT)
        val (userV, userE) = field("WordPress username", site.username, InputType.TYPE_CLASS_TEXT)
        val (passV, passE) = field(
            "Application password", SiteStore.password(context, site.id),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val (boardV, boardE) = field("Default board ID (optional)", site.boardId, InputType.TYPE_CLASS_NUMBER)
        val (portalV, portalE) = field("Community portal", site.portalPath, InputType.TYPE_CLASS_TEXT)
        val (boardsV, boardsE) = field("Boards portal", site.boardsPath, InputType.TYPE_CLASS_TEXT)
        val (bookingV, bookingE) = field("Booking page", site.bookingPath, InputType.TYPE_CLASS_TEXT)
        val (supportV, supportE) = field("Support portal", site.supportPath, InputType.TYPE_CLASS_TEXT)
        val (shopV, shopE) = field("Shop page", site.shopPath, InputType.TYPE_CLASS_TEXT)
        val (crmV, crmE) = field("CRM page (optional)", site.crmPath, InputType.TYPE_CLASS_TEXT)
        listOf(nameV, urlV, userV, passV, boardV, portalV, boardsV, bookingV, supportV, shopV, crmV)
            .forEach { col.addView(it) }

        // Footer help — the terms above ("application password", the portal paths) are unexplained
        // otherwise. iOS shows the same guidance under the form (LedgerSites.swift:211-213).
        col.addView(TextView(ctx).apply {
            text = "The portal fields are the path each front-facing page lives at on your site " +
                "(e.g. “portal”, “booking”). Leave blank to use the defaults. Create an application " +
                "password in WordPress → Users → Profile → Application Passwords."
            textSize = 12f; setTextColor(0xFF444444.toInt()); setLineSpacing(0f, 1.2f)
            setPadding(0, px(14), 0, px(2))
        })

        // Persist the inputs back onto a fresh copy of the site (id preserved).
        fun collect(): LedgerSite = site.copy(
            name = nameE.text.toString().trim(),
            url = urlE.text.toString().trim(),
            username = userE.text.toString().trim(),
            boardId = boardE.text.toString().trim(),
            portalPath = portalE.text.toString().trim(),
            boardsPath = boardsE.text.toString().trim(),
            bookingPath = bookingE.text.toString().trim(),
            supportPath = supportE.text.toString().trim(),
            shopPath = shopE.text.toString().trim(),
            crmPath = crmE.text.toString().trim(),
        )

        // WordPress application passwords display in spaced groups ("abcd efgh …") but the real secret
        // has no spaces — strip them so a paste of either form works.
        fun savePass(id: String) = SiteStore.setPassword(context, id, passE.text.toString().replace(" ", ""))

        val deleteBtn = if (existing) Button(ctx).apply {
            text = "Delete site"
            setOnClickListener {
                present(AlertDialog.Builder(ctx)
                    .setMessage("Delete “${site.display}”?")
                    .setPositiveButton("Delete") { _, _ ->
                        SiteStore.delete(context, site.id)
                        Toast.makeText(context, "Site deleted", Toast.LENGTH_SHORT).show()
                        onChanged(); show(context, present, onChanged)
                    }
                    .setNegativeButton("Cancel", null)
                    .create())
            }
        } else null
        deleteBtn?.let { col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) }) }

        val scroll = ScrollView(ctx).apply { addView(col) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing) "Edit site" else "New site")
            .setView(scroll)
            // Save persists the site + password (and activates the very first site you add).
            .setPositiveButton("Save", null)
            // "Use" persists then makes this the active site — the global DEFAULT surfaces
            // without their own pick fall back to.
            .setNeutralButton("Use this site", null)
            .setNegativeButton("Cancel", null)
            .create()

        // A whole site's credentials mid-edit are work — a stray touch outside must not throw
        // them away. Cancel and the back gesture remain the ways out. (Set directly: this object
        // only has a Context, so it can't route through ScreenFragment.showGuardedModal.) Set
        // BEFORE presenting: after `show()` it is too late to matter.
        dialog.setCanceledOnTouchOutside(false)

        // THE BUTTONS ARE WIRED AFTER THE DIALOG IS SHOWN, NOT IN AN OnShowListener.
        //
        // This is the whole of Michael's "adding a site doesn't work" (08-31). These handlers used
        // to be attached inside `dialog.setOnShowListener { … }` — and `present` is
        // `ScreenFragment.showModal`, whose first act is `dialog.setOnShowListener { onModalShown() }`.
        // A dialog holds ONE show-listener: the modal bookkeeping replaced this one, the click
        // handlers were never attached, and both buttons fell back to AlertDialog's default —
        // dismiss and do nothing. You typed a site, pressed Save, the dialog closed, and nothing
        // was written, on every build since the day `present` started routing through showModal.
        //
        // `getButton` returns null before the dialog is shown, which is why the listener existed at
        // all; presenting FIRST and wiring after is the version that survives a host which needs
        // the show-listener for itself. `LedgerItemsFragment` documents the same trap from the
        // other side ("which showGuardedModal would replace") and dodged it; this dialog did not.
        present(dialog)
        run {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = collect()
                if (s.url.isBlank()) { Toast.makeText(context, "A site needs a URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val firstEver = SiteStore.all(context).isEmpty()
                savePass(s.id)                                   // password first — upsert re-activates with it
                // A SAVE THAT DIDN'T SAVE MUST NOT SAY "Saved". The store's write is guarded and
                // reports now (it used to be a bare `apply()` whose failure surfaced on another
                // thread long after this dialog closed) — so the toast tells the truth, and the
                // dialog STAYS OPEN on failure with the typing still in it.
                if (!SiteStore.upsert(context, s)) {
                    Toast.makeText(context, "Couldn't save that site — nothing was written.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (firstEver) SiteStore.activate(context, s.id)   // the first site you add becomes active
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                onChanged(); dialog.dismiss(); show(context, present, onChanged)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val s = collect()
                if (s.url.isBlank()) { Toast.makeText(context, "A site needs a URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                savePass(s.id)
                if (!SiteStore.upsert(context, s)) {
                    Toast.makeText(context, "Couldn't save that site — nothing was written.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                SiteStore.activate(context, s.id)
                Toast.makeText(context, "Now using ${s.display}", Toast.LENGTH_SHORT).show()
                onChanged(); dialog.dismiss(); show(context, present, onChanged)
            }
        }
    }
}
