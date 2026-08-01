package com.toolsboox.plugin.mail.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The mail verbs that more than one surface performs — star-to-todo (with its All Stars gram),
 * un-star, the reply dialog, and the accounts editor — lifted out of [MailInboxFragment] when
 * The Mail became a lens in the feeds pane. Two screens now speak these verbs, and a second
 * private copy of "what starring an email means" is exactly how the standalone inbox and the
 * lens would have drifted apart about it. The standalone screen delegates here; the feeds lens
 * calls here; neither owns the ceremony.
 *
 * Everything takes the calling fragment (for its context, scope and guarded modals) and reports
 * back through a callback rather than repainting anything itself — which list to redraw, and
 * how, is the one thing the two callers legitimately disagree about.
 */
object MailVerbs {

    private fun dp(f: ScreenFragment, v: Int) = (v * f.resources.displayMetrics.density).toInt()

    /**
     * Star → to-do: create a task on today's page (like Kanban's add-card), mark the mail starred
     * (which persists its whole body to the keep pile), and mint its link-face gram onto today's
     * Intake — the same three moves MailInboxFragment.starToTodo has always made, verbatim.
     *
     * [onDone] receives the gram placement: true = placed, false = the dedupe declined it (already
     * on All Stars), null = the placement itself failed. The caller toasts accordingly — a re-star
     * that answers a bare "Starred" reads as a star that did nothing.
     */
    fun starToTodo(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File,
        m: InboxMessage, onDone: (placedGram: Boolean?) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val item = LedgerItem(
            id = "li-" + UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK, text = m.subject, date = Date(), stage = "todo", source = "email"
        )
        fragment.lifecycleScope.launch {
            val placedGram = withContext(Dispatchers.IO) {
                InboxStore.setStarred(ctx, m, true)   // persists the message content; off the main thread
                val today = LocalDate.now()
                // The open day page's per-pen-up save does the same whole-file load→mutate→save;
                // unserialized, one of the two writes silently drops the other's items.
                com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    val day = dayService.load(root, today, null, Locale.getDefault())
                    day.ledgerItems.add(item)
                    dayService.save(root, today, day)
                }
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, item) }
                runCatching { placeMailStarGram(dayService, root, m, today) }.getOrNull()
            }
            onDone(placedGram)
        }
    }

    /** The other direction — cheap on purpose (no disk reads on the un-star branch), so it stays
     *  safe from the main-thread callers both screens have always been. */
    fun unstar(ctx: Context, m: InboxMessage) {
        InboxStore.setStarred(ctx, m, false)
    }

    /**
     * Grams for stars, mail edition — mirrors FeedNoteGram.placeStarGram: starring a message mints
     * a movable link-face gram onto TODAY's Intake page, so the mail sits on the board like a
     * starred article does. The face is rendered by [LinkCardRenderer] wearing the ✉ MAIL chip,
     * the subject as its title, and "sender · date" on the source line (the render's url stays blank —
     * a `mail://` address is no host to print; the address rides sourceLink instead, so tapping
     * the gram can resolve the message and dedupe works).
     *
     * Starring the same message twice must not stack twins: an intake gram already carrying this
     * sourceLink today wins. And unstarring deliberately does NOT remove the gram — once placed,
     * the Intake page is his board, not a mirror of the star state. Call OFF the main thread.
     *
     * @return true when a gram was placed, false when today's intake already had it.
     */
    private fun placeMailStarGram(
        dayService: CalendarDayService, root: File, m: InboxMessage, today: LocalDate
    ): Boolean {
        val mailUri = "mail://${m.id}"
        // Dedupe by sourceLink. Read-before-place is unlocked, but stars arrive at human speed
        // and re-stars route through this same path, so a stale read can't stack twins in practice.
        val day = dayService.load(root, today, null, Locale.getDefault())
        if (day.imageElements.any { it.page == "intake" && it.sourceLink == mailUri }) return false
        val sender = m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }
        val dateLabel = java.time.Instant.ofEpochMilli(m.date)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
            url = "", title = m.subject.ifBlank { "(no subject)" }, kind = "mail",
            sourceName = "$sender · $dateLabel",
            // The letter's own opening lines are its excerpt — the iPad's mail gram passes the
            // same snippet. A subject alone tells you a message arrived; the first sentence tells
            // you what it wants, which is what makes the gram worth arranging on the board.
            excerpt = m.snippet.ifBlank { m.body }.trim().take(400)
        )
        com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
            dayService, root, face, today, pageKey = "intake",
            sourceLink = mailUri, sourceLabel = sender, cardText = m.subject,
            // A starred email belongs to the EMAIL quarter of Star Sort — whose storage key is the
            // legacy "educate". Without this the gram carried NO intakeKind, matched no quarter, and
            // landed at the default spot, i.e. on top of THE READ.
            intakeKind = "educate"
        )
        return true
    }

    /**
     * A plain reply, sent out the account the message arrived on (via SMTP) — the guarded dialog
     * MailInboxFragment always showed, now reachable from any surface holding a letter. The toasts
     * live here with the send; [onSent] fires only on success so the caller can refold its list
     * around the new keep-forever row.
     */
    fun reply(fragment: ScreenFragment, m: InboxMessage, onSent: () -> Unit) {
        val ctx = fragment.requireContext()
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        val input = EditText(ctx).apply {
            hint = "Write a reply…"; setSingleLine(false); minLines = 6; gravity = Gravity.TOP
            setPadding(dp(fragment, 10), dp(fragment, 10), dp(fragment, 10), dp(fragment, 10))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(fragment, 16), dp(fragment, 8), dp(fragment, 16), 0)
            addView(TextView(ctx).apply {
                text = "To ${m.fromName.ifBlank { m.fromEmail }}\nRe: ${m.subject}"
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(fragment, 8))
            })
            addView(input)
        }
        // Guarded: a reply being typed is work — a stray touch outside must not throw it away.
        // Cancel and the back gesture remain the ways out.
        fragment.showGuardedModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("Nothing to send"); return@setPositiveButton }
                fragment.lifecycleScope.launch {
                    val err = try { withContext(Dispatchers.IO) { MailSync.sendReply(ctx, text, m) }; null }
                    catch (e: Exception) { e.message ?: "Send failed" }
                    // A sent reply is a keep-forever event: the mail you answered persists its body and
                    // never prunes, exactly like a star. Recorded off the main thread (it writes a file).
                    // The reply text you wrote also persists to its own sent pile, so your outgoing
                    // words are kept forever and searchable alongside the mail you received.
                    if (err == null) withContext(Dispatchers.IO) {
                        InboxStore.markReplied(ctx.applicationContext, m.id)
                        InboxStore.recordSent(ctx.applicationContext, m, text)
                    }
                    if (!fragment.isAdded) return@launch   // send outlives the fragment; toast needs it attached
                    toast(if (err == null) "Reply sent" else "Send failed: $err")
                    if (err == null) onSent()
                }
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    // Mail settings — the accounts list + per-account editor, unchanged in shape from the
    // standalone screen's. [onChanged] fires after a save (a first/renamed account changes
    // whatever chrome names accounts); [onDeleted] carries the removed id so the caller can
    // un-narrow a list that was scoped to it.

    fun showAccountsList(fragment: ScreenFragment, onChanged: () -> Unit, onDeleted: (String) -> Unit = {}) {
        val ctx = fragment.requireContext()
        val accounts = MailAccountStore.all(ctx)
        val labels = (accounts.map { it.display } + "➕  Add account").toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Mail accounts")
            .setItems(labels) { _, i ->
                if (i < accounts.size) showAccountEditor(fragment, accounts[i], onChanged, onDeleted)
                else showAccountEditor(fragment, MailAccount(), onChanged, onDeleted)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAccountEditor(
        fragment: ScreenFragment, account: MailAccount,
        onChanged: () -> Unit, onDeleted: (String) -> Unit
    ) {
        val ctx = fragment.requireContext()
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        val a = account.copy()
        val exists = MailAccountStore.all(ctx).any { it.id == a.id }

        fun label(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(fragment, 10), 0, dp(fragment, 2))
        }
        fun field(value: String, numeric: Boolean = false) = EditText(ctx).apply {
            setText(value); setSingleLine()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        fun check(text: String, on: Boolean) = CheckBox(ctx).apply { this.text = text; isChecked = on }

        val displayName = field(a.displayName)
        val email = field(a.email).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val imapHost = field(a.imapHost)
        val imapPort = field(a.imapPort.toString(), numeric = true)
        val imapSSL = check("Use SSL/TLS (IMAP)", a.imapSSL)
        val username = field(a.username)
        val password = field(MailAccountStore.password(ctx, a.id)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val smtpHost = field(a.smtpHost)
        val smtpPort = field(a.smtpPort.toString(), numeric = true)
        val smtpSSL = check("Use SSL/TLS (SMTP)", a.smtpSSL)

        val preset = TextView(ctx).apply {
            text = "✨  Fill hosts from provider…"; textSize = 14f; setTextColor(0xFF2F6F96.toInt())
            setPadding(0, dp(fragment, 8), 0, dp(fragment, 2))
            setOnClickListener {
                val names = MailAccount.presets.map { it.first }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                    .setTitle("Provider")
                    .setItems(names) { _, i ->
                        val p = MailAccount.presets[i]
                        imapHost.setText(p.second); smtpHost.setText(p.third)
                        imapPort.setText("993"); smtpPort.setText("465")
                        imapSSL.isChecked = true; smtpSSL.isChecked = true
                    }
                    .show()
            }
        }

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(fragment, 18), dp(fragment, 4), dp(fragment, 18), dp(fragment, 4))
            addView(label("Display name")); addView(displayName)
            addView(label("Email address")); addView(email)
            addView(preset)
            addView(label("Incoming — IMAP host")); addView(imapHost)
            addView(label("IMAP port")); addView(imapPort); addView(imapSSL)
            addView(label("Username (default: the address)")); addView(username)
            addView(label("Password (app-specific if 2FA)")); addView(password)
            addView(label("Outgoing — SMTP host")); addView(smtpHost)
            addView(label("SMTP port (implicit TLS, usually 465)")); addView(smtpPort); addView(smtpSSL)
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle(if (exists) "Edit account" else "New account")
            .setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                a.displayName = displayName.text.toString().trim()
                a.email = email.text.toString().trim()
                a.imapHost = imapHost.text.toString().trim()
                a.imapPort = imapPort.text.toString().toIntOrNull() ?: 993
                a.imapSSL = imapSSL.isChecked
                a.username = username.text.toString().trim()
                a.smtpHost = smtpHost.text.toString().trim()
                a.smtpPort = smtpPort.text.toString().toIntOrNull() ?: 465
                a.smtpSSL = smtpSSL.isChecked
                if (a.email.isBlank() || a.imapHost.isBlank()) { toast("An email address and IMAP host are required"); return@setPositiveButton }
                MailAccountStore.upsert(ctx, a)
                MailAccountStore.setPassword(ctx, a.id, password.text.toString())
                toast("Saved")
                onChanged()
            }
            .setNegativeButton("Cancel", null)
        if (exists) builder.setNeutralButton("Delete") { _, _ ->
            MailAccountStore.delete(ctx, a.id); toast("Account deleted")
            onDeleted(a.id)
        }
        val dialog = builder.create()
        // Guarded: a whole account's settings mid-edit — a stray touch outside must not throw
        // them away. Cancel and the back gesture remain the ways out.
        fragment.showGuardedModal(dialog)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
