package com.toolsboox.plugin.mail.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentMailComposeBinding
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The standalone compose screen — writing an email that ISN'T a reply. Until now the only writing
 * surface in plugin/mail was the inbox's per-message Reply dialog, so "write that email" quick wins
 * had nowhere to land but the inbox; this is the missing seam. To autocompletes from the rolodex
 * (the synced [ContactStore]), From defaults to the inbox's account filter (else the first
 * configured account) with a chooser when there are several, and Send goes out through
 * [MailSync.sendNew] — the exact SMTP path a reply takes, minus the In-Reply-To. Arrives blank or
 * prefilled ([ARG_TO_EMAIL] / [ARG_TO_NAME] / [ARG_SUBJECT]) from a quick win or a contact page.
 * Send and Discard live in the TOP bar (the palm rule), sent pops back with a toast, and a failed
 * send says exactly what the server said.
 */
@AndroidEntryPoint
class MailComposeFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_mail_compose
    private lateinit var binding: FragmentMailComposeBinding

    private var accounts: List<MailAccount> = emptyList()
    private var accountId: String? = null
    private var sending = false

    companion object {
        /** Optional prefill: the recipient's address, their name (for the "Name <addr>" face the
         *  To field shows), and a seeded subject — how a quick win or a contact page arrives with
         *  the letter already addressed. */
        const val ARG_TO_EMAIL = "to_email"
        const val ARG_TO_NAME = "to_name"
        const val ARG_SUBJECT = "subject"

        /** Optional pre-written body — how a Quick Win's "path to victory" arrives with the letter
         *  already drafted, ready to review and send. */
        const val ARG_BODY = "body"
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMailComposeBinding.bind(view)
        val ctx = requireContext()

        binding.mailComposeSend.setOnClickListener { send() }
        binding.mailComposeDiscard.setOnClickListener { discard() }

        // From: the inbox's account filter if one is set (a narrowed inbox and its compose should
        // agree on which door mail goes out), else the first configured account.
        accounts = MailAccountStore.all(ctx)
        val filtered = ctx.getSharedPreferences("ledger_mail_inbox", 0)
            .getString("account_filter", "")!!.ifBlank { null }
        accountId = accounts.firstOrNull { it.id == filtered }?.id ?: accounts.firstOrNull()?.id
        renderFrom()

        // Prefill (a quick win or a contact page arriving with the letter addressed) — only on
        // first build; a rotation must not stomp what's been typed since.
        if (savedInstanceState == null) {
            val toEmail = arguments?.getString(ARG_TO_EMAIL).orEmpty()
            val toName = arguments?.getString(ARG_TO_NAME).orEmpty()
            if (toEmail.isNotBlank())
                binding.mailComposeTo.setText(if (toName.isBlank()) toEmail else "$toName <$toEmail>")
            arguments?.getString(ARG_SUBJECT)?.takeIf { it.isNotBlank() }
                ?.let { binding.mailComposeSubject.setText(it) }
            arguments?.getString(ARG_BODY)?.takeIf { it.isNotBlank() }
                ?.let { binding.mailComposeBody.setText(it) }
        }

        // The rolodex feeds the To field: every contact with an address, shown as "Name <addr>"
        // so a couple of typed letters of either the person or the domain finds them. A plain
        // ArrayAdapter — its default filter substring-matches, which is exactly enough.
        viewLifecycleOwner.lifecycleScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                ContactStore.list(ctx)
                    .filter { it.email.isNotBlank() }
                    .map { if (it.name.isBlank()) it.email else "${it.name} <${it.email}>" }
            }
            if (isAdded && suggestions.isNotEmpty()) {
                binding.mailComposeTo.setAdapter(
                    ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, suggestions)
                )
                binding.mailComposeTo.threshold = 1
            }
        }
    }

    /** The From line: a statement with one account, a ▾ chooser with several, an honest pointer
     *  at Mail settings with none (compose can open before any account exists). */
    private fun renderFrom() {
        val a = accounts.firstOrNull { it.id == accountId }
        binding.mailComposeFrom.text = when {
            accounts.isEmpty() -> "No mail accounts yet — add one with the ⚙ in the Mail screen."
            accounts.size == 1 -> "From: ${a?.display ?: accounts.first().display}"
            else -> "From: ${a?.display ?: "choose an account"}  ▾"
        }
        binding.mailComposeFrom.setOnClickListener(
            if (accounts.size > 1) View.OnClickListener { pickAccount() } else null
        )
    }

    private fun pickAccount() {
        val ctx = requireContext()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Send from")
            .setItems(accounts.map { it.display }.toTypedArray()) { _, i ->
                accountId = accounts[i].id
                renderFrom()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The address out of whatever the To field holds — "Name <addr>" from the autocomplete or a
     *  bare typed address; the angle brackets win when present. */
    private fun recipientAddress(raw: String): String =
        (Regex("<([^>]+)>").find(raw)?.groupValues?.get(1) ?: raw).trim()

    /** Send through [MailSync.sendNew] — the same SMTP path a reply takes. Sent → toast + pop
     *  back; failed → the server's own words, and the draft stays on screen to try again. */
    private fun send() {
        if (sending) return
        val ctx = context ?: return
        val aid = accountId
        if (aid == null) { toast("No mail account to send from — add one with the ⚙ in the Mail screen."); return }
        val to = recipientAddress(binding.mailComposeTo.text.toString())
        if (to.isBlank()) { toast("No recipient address."); return }
        val subject = binding.mailComposeSubject.text.toString().trim()
        val body = binding.mailComposeBody.text.toString()
        if (subject.isBlank() && body.isBlank()) { toast("Nothing to send yet."); return }

        sending = true
        binding.mailComposeSend.text = "Sending…"
        lifecycleScope.launch {
            // Same shape as the inbox's reply: the IO on Dispatchers.IO, the send allowed to
            // outlive a wandering-off user, the UI touched only if we're still attached.
            val err = try { withContext(Dispatchers.IO) { MailSync.sendNew(ctx, aid, to, subject, body) }; null }
            catch (e: Exception) { e.message ?: "Send failed" }
            sending = false
            if (!isAdded) return@launch
            if (err == null) {
                toast("Sent")
                NavHostFragment.findNavController(this@MailComposeFragment).popBackStack()
            } else {
                binding.mailComposeSend.text = "➤ Send"
                toast("Send failed: $err")
            }
        }
    }

    /** ✕: leave. A body with words in it gets one held breath (a confirm) — prefilled To/Subject
     *  alone don't, because nothing hand-typed is at stake yet. */
    private fun discard() {
        if (binding.mailComposeBody.text.toString().isBlank()) {
            NavHostFragment.findNavController(this).popBackStack()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setMessage("Discard this draft?")
            .setPositiveButton("Discard") { _, _ ->
                NavHostFragment.findNavController(this).popBackStack()
            }
            .setNegativeButton("Keep writing", null)
            .show()
    }

    // ScreenFragment abstract members: no spinner here — Send narrates its own state ("Sending…"),
    // the same inline discipline as the inbox.
    override fun showLoading() {}
    override fun hideLoading() {}
}
