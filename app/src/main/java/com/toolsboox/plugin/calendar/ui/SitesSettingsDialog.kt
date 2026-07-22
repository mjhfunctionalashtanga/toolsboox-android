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
 * with one set ACTIVE (the site the Fluent actions target). Reached from the Community/Boards section
 * of Calendar settings. Deliberately dialog-based and plain: no new navigation graph entry, no fancy
 * list widget — one tap opens the list, one tap opens a site, big rows and buttons for e-ink.
 *
 * Mirrors the iPad `SitesSettingsView` / `SiteEditor` in App/LedgerSites.swift. Activating a site
 * write-throughs its creds into the single-cred bridge keys, so Boards/Community/Correspondence/
 * Booking all re-point to it with no other change.
 */
object SitesSettingsDialog {

    /** Open the list of sites. [onChanged] fires whenever the active site or the list changes. */
    fun show(context: Context, onChanged: () -> Unit = {}) {
        val ctx = ModalScale.wrap(context)
        val sites = SiteStore.all(context)
        val activeId = SiteStore.activeId(context)

        val labels = sites.map { s ->
            val mark = if (s.id == activeId) "●  " else "○  "   // ● active / ○ inactive
            val sub = if (s.url.isNotBlank()) "\n     ${s.url}" else ""
            "$mark${s.display}$sub"
        }.toMutableList()
        labels.add("+  Add site")

        AlertDialog.Builder(ctx)
            .setTitle("Sites")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < sites.size) editor(context, sites[which], onChanged)
                else editor(context, LedgerSite(), onChanged)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Edit (or create) one site: URL, login, application password, default board, portal slugs. */
    private fun editor(context: Context, site: LedgerSite, onChanged: () -> Unit) {
        val ctx = ModalScale.wrap(context)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val existing = SiteStore.all(context).any { it.id == site.id }

        fun field(label: String, value: String, inputType: Int): Pair<View, EditText> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, px(10), 0, 0)
                addView(TextView(ctx).apply {
                    text = label; textSize = 12f; setTextColor(0xFF8A8A8A.toInt()); letterSpacing = 0.06f
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
        val (nameV, nameE) = field("NAME", site.name, InputType.TYPE_CLASS_TEXT)
        val (urlV, urlE) = field("URL (https://…)", site.url, InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT)
        val (userV, userE) = field("WORDPRESS USERNAME", site.username, InputType.TYPE_CLASS_TEXT)
        val (passV, passE) = field(
            "APPLICATION PASSWORD", SiteStore.password(context, site.id),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val (boardV, boardE) = field("DEFAULT BOARD ID (optional)", site.boardId, InputType.TYPE_CLASS_NUMBER)
        val (portalV, portalE) = field("COMMUNITY PORTAL SLUG", site.portalPath, InputType.TYPE_CLASS_TEXT)
        val (boardsV, boardsE) = field("BOARDS PORTAL SLUG", site.boardsPath, InputType.TYPE_CLASS_TEXT)
        val (bookingV, bookingE) = field("BOOKING PAGE SLUG", site.bookingPath, InputType.TYPE_CLASS_TEXT)
        val (supportV, supportE) = field("SUPPORT PORTAL SLUG", site.supportPath, InputType.TYPE_CLASS_TEXT)
        val (shopV, shopE) = field("SHOP SLUG", site.shopPath, InputType.TYPE_CLASS_TEXT)
        val (crmV, crmE) = field("CRM PAGE SLUG (optional)", site.crmPath, InputType.TYPE_CLASS_TEXT)
        listOf(nameV, urlV, userV, passV, boardV, portalV, boardsV, bookingV, supportV, shopV, crmV)
            .forEach { col.addView(it) }

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
                AlertDialog.Builder(ctx)
                    .setMessage("Delete “${site.display}”?")
                    .setPositiveButton("Delete") { _, _ ->
                        SiteStore.delete(context, site.id)
                        Toast.makeText(context, "Site deleted", Toast.LENGTH_SHORT).show()
                        onChanged(); show(context, onChanged)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else null
        deleteBtn?.let { col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) }) }

        val scroll = ScrollView(ctx).apply { addView(col) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing) "Edit site" else "New site")
            .setView(scroll)
            // Save persists the site + password (and activates the very first site you add).
            .setPositiveButton("Save", null)
            // "Use" persists then makes this the active site — the one actions target.
            .setNeutralButton("Use this site", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val s = collect()
                if (s.url.isBlank()) { Toast.makeText(context, "A site needs a URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val firstEver = SiteStore.all(context).isEmpty()
                SiteStore.upsert(context, s); savePass(s.id)
                if (firstEver) SiteStore.activate(context, s.id)   // the first site you add becomes active
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                onChanged(); dialog.dismiss(); show(context, onChanged)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val s = collect()
                if (s.url.isBlank()) { Toast.makeText(context, "A site needs a URL", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                SiteStore.upsert(context, s); savePass(s.id)
                SiteStore.activate(context, s.id)
                Toast.makeText(context, "Now using ${s.display}", Toast.LENGTH_SHORT).show()
                onChanged(); dialog.dismiss(); show(context, onChanged)
            }
        }
        dialog.show()
    }
}
