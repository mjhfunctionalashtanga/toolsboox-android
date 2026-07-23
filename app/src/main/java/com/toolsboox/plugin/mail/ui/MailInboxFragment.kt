package com.toolsboox.plugin.mail.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentMailInboxBinding
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * The unified inbox: messages from every email place in one list, where an email is a first-class
 * object. Star one and it becomes a to-do (a real [LedgerItem] on today's page, exactly as the
 * Kanban "add card" does); open it to read it and reply (out the account it arrived on, via SMTP).
 * The gear opens Mail settings -- the accounts list and a per-account editor whose password is kept
 * in EncryptedSharedPreferences. Mirrors iOS UnifiedInboxView; matches CorrespondenceFragment's
 * conventions (programmatic rows in a ScrollView, ModalScale-wrapped dialogs, Dispatchers.IO work).
 */
@AndroidEntryPoint
class MailInboxFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_mail_inbox
    private lateinit var binding: FragmentMailInboxBinding

    @Inject
    lateinit var calendarDayService: CalendarDayService

    // Starred (your to-dos) is the default view -- the kept pile. Toggle to see everything that has
    // come in, star what matters, then Clear sweeps the rest.
    private var onlyStarred = true
    private var messages: List<InboxMessage> = emptyList()
    private var refreshing = false

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMailInboxBinding.bind(view)
        binding.mailClose.setOnClickListener { NavHostFragment.findNavController(this).popBackStack() }
        binding.mailRefresh.setOnClickListener { refresh() }
        binding.mailSettings.setOnClickListener { showAccountsList() }
        binding.mailToggle.setOnClickListener { onlyStarred = !onlyStarred; updateToggle(); render() }
        updateToggle()

        val ctx = requireContext()
        messages = InboxStore.messages(ctx)
        render()
        if (InboxStore.hasAccounts(ctx)) refresh()
    }

    private fun updateToggle() { binding.mailToggle.text = if (onlyStarred) "★ To-dos" else "✉ All" }

    private fun shown(): List<InboxMessage> {
        val ctx = requireContext()
        return if (onlyStarred) messages.filter { InboxStore.isStarred(ctx, it.id) } else messages
    }

    /** Pull mail from the configured accounts, then reload the list. Surfaces the first problem gently. */
    private fun refresh() {
        if (refreshing) return
        val ctx = context ?: return
        if (!InboxStore.hasAccounts(ctx)) { render(); return }
        refreshing = true
        binding.mailStatus.text = "Fetching…"
        binding.mailStatus.visibility = View.VISIBLE
        lifecycleScope.launch {
            val problem = MailSync.refresh(ctx)          // suspends; fetch runs on Dispatchers.IO within
            if (!isAdded) return@launch
            messages = InboxStore.messages(ctx)
            refreshing = false
            binding.mailStatus.visibility = View.GONE
            render()
            if (problem != null) toast(problem.split("\n").first())
        }
    }

    private fun render() {
        val ctx = context ?: return
        val container = binding.mailContainer
        container.removeAllViews()

        val list = shown()
        if (list.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = if (onlyStarred)
                    "No starred mail yet. Open a message and star it to make a to-do, or switch to All."
                else if (InboxStore.hasAccounts(ctx)) "Inbox empty."
                else "No accounts yet. Tap the gear to add one — until then a few samples show here."
                textSize = 15f; setTextColor(0xFF444444.toInt()); setPadding(dp(8), dp(24), dp(8), 0)
            })
            return
        }

        for (m in list) container.addView(row(m))

        // Clear is for the triage view: sweep out everything you didn't star.
        if (!onlyStarred) {
            container.addView(TextView(ctx).apply {
                text = "🌀  Clear un-starred"
                textSize = 15f; setTextColor(0xFFB00020.toInt()); setPadding(dp(6), dp(16), dp(6), dp(8))
                setOnClickListener {
                    InboxStore.clearUnstarred(ctx)
                    messages = InboxStore.messages(ctx)
                    render()
                    toast("Cleared — starred mail kept")
                }
            })
        }
    }

    private fun row(m: InboxMessage): View {
        val ctx = requireContext()
        val unread = !InboxStore.isRead(ctx, m.id)
        val starred = InboxStore.isStarred(ctx, m.id)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(if (unread) 0xFFF3F3F3.toInt() else 0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(6)) }
        }

        // Star toggle on the left -- tap it to make (or unmake) a to-do without opening the message.
        val star = TextView(ctx).apply {
            text = if (starred) "★" else "☆"
            textSize = 20f; setTextColor(if (starred) 0xFFE0A500.toInt() else 0xFF999999.toInt())
            setPadding(0, 0, dp(12), 0); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { if (starred) unstar(m) else starToTodo(m) }
        }
        card.addView(star)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(ctx).apply {
            text = m.fromName.ifBlank { m.fromEmail }
            textSize = 14f; setTextColor(0xFF000000.toInt())
            if (unread) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(ctx).apply {
            text = rel(m.date); textSize = 11f; setTextColor(0xFF888888.toInt())
        })
        col.addView(head)
        col.addView(TextView(ctx).apply {
            text = m.subject; textSize = 14f; setTextColor(0xFF000000.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            if (unread) setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(ctx).apply {
            text = m.snippet; textSize = 12f; setTextColor(0xFF666666.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(col)

        card.setOnClickListener { InboxStore.markRead(ctx, m.id); openMessage(m) }
        return card
    }

    /** An opened message: its body, and the moves that make it an assignable object. */
    private fun openMessage(m: InboxMessage) {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        col.addView(TextView(ctx).apply {
            text = m.subject; textSize = 17f; setTextColor(0xFF000000.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(2), 0, dp(4))
        })
        col.addView(TextView(ctx).apply {
            text = listOf(m.fromName, m.fromEmail).filter { it.isNotBlank() }.joinToString("  ·  ")
            textSize = 12f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(8))
        })
        col.addView(TextView(ctx).apply {
            text = m.body; textSize = 15f; setTextColor(0xFF000000.toInt()); setTextIsSelectable(true)
        })

        val canReply = MailSync.accountId(m.id) != null
        val builder = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Close", null)
            .setNeutralButton(if (InboxStore.isStarred(ctx, m.id)) "Un-star" else "★ To-do") { _, _ ->
                if (InboxStore.isStarred(ctx, m.id)) unstar(m) else starToTodo(m)
            }
        if (canReply) builder.setNegativeButton("Reply…") { _, _ -> showReply(m) }
        val dialog = builder.create()
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** A plain reply, sent out the account the message arrived on (via SMTP). */
    private fun showReply(m: InboxMessage) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Write a reply…"; setSingleLine(false); minLines = 6; gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(ctx).apply {
                text = "To ${m.fromName.ifBlank { m.fromEmail }}\nRe: ${m.subject}"
                textSize = 13f; setTextColor(0xFF666666.toInt()); setPadding(0, 0, 0, dp(8))
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Reply")
            .setView(box)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("Nothing to send"); return@setPositiveButton }
                lifecycleScope.launch {
                    val err = try { withContext(Dispatchers.IO) { MailSync.sendReply(ctx, text, m) }; null }
                    catch (e: Exception) { e.message ?: "Send failed" }
                    toast(if (err == null) "Reply sent" else "Send failed: $err")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Star -> to-do: create a task on today's page (like Kanban's add-card), and mark the mail starred.
    private fun starToTodo(m: InboxMessage) {
        val ctx = requireContext()
        InboxStore.setStarred(ctx, m.id, true)
        val item = LedgerItem(
            id = "li-" + UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK, text = m.subject, date = Date(), stage = "todo", source = "email"
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val root = documentsRoot()
                val today = LocalDate.now()
                val day = calendarDayService.load(root, today, null, Locale.getDefault())
                day.ledgerItems.add(item)
                calendarDayService.save(root, today, day)
                runCatching { com.toolsboox.plugin.calendar.nw.LedgerTaskSync.pushTask(ctx, item) }
            }
            if (!isAdded) return@launch
            toast("Starred — added to your to-dos")
            messages = InboxStore.messages(ctx)
            render()
        }
    }

    private fun unstar(m: InboxMessage) {
        val ctx = requireContext()
        InboxStore.setStarred(ctx, m.id, false)
        messages = InboxStore.messages(ctx)
        render()
    }

    // Mail settings -- accounts list + per-account editor.

    private fun showAccountsList() {
        val ctx = requireContext()
        val accounts = MailAccountStore.all(ctx)
        val labels = (accounts.map { it.display } + "➕  Add account").toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Mail accounts")
            .setItems(labels) { _, i ->
                if (i < accounts.size) showAccountEditor(accounts[i]) else showAccountEditor(MailAccount())
            }
            .setNegativeButton("Close", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showAccountEditor(account: MailAccount) {
        val ctx = requireContext()
        val a = account.copy()
        val exists = MailAccountStore.all(ctx).any { it.id == a.id }

        fun label(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(10), 0, dp(2))
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
            setPadding(0, dp(8), 0, dp(2))
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
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(4))
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
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (exists) builder.setNeutralButton("Delete") { _, _ ->
            MailAccountStore.delete(ctx, a.id); toast("Account deleted")
            messages = InboxStore.messages(ctx); render()
        }
        val dialog = builder.create()
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rel(millis: Long): String =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()

    // ScreenFragment abstract members: this screen manages its own inline status, so these are no-ops
    // (the same stubs CorrespondenceFragment uses).
    override fun showLoading() {}
    override fun hideLoading() {}
}
