package com.toolsboox.plugin.mail.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.databinding.FragmentMailComposeBinding
import com.toolsboox.ot.InkPadView
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.calendar.ot.ContactStore
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.InboxStore
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.plugin.mail.MailAccountStore
import com.toolsboox.plugin.mail.MailSync
import com.toolsboox.plugin.mail.MailThreads
import com.toolsboox.plugin.mail.SmtpClient
import com.toolsboox.ui.main.MainActivity
import com.toolsboox.ui.plugin.ScreenFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

/**
 * THE ONE COMPOSE SCREEN (Michael 2026-10-11, Q2: "one compose screen with pen and keyboard
 * together, one ✉ Compose button, ↩ opening straight into it, and From pinned only when you hold
 * it"). Until now ✎ Compose and ✍ Letter opened two different screens, each missing half the job:
 * only this one had contact suggestions and 📎, only the letter pad ([LetterPad], a full-screen
 * dialog) took ink, and they picked different From accounts. Now one page does both — the pen
 * writes on the pad, a finger types the body, no mode switch.
 *
 * Top to bottom, every tappable thing above the pad (the palm rule): the title · 📎 · 🃏 Draft on
 * pickings (replies) · 📝 Onto today's note · the big filled ➤ Send · ✕; From (tap = this letter,
 * HOLD = pin the default)
 * · Stationery (per account; Text only = no pad); To (the rolodex autocompletes); Subject; the
 * typed body; the pages row and pen bar; then the pad to the bottom edge, on the account's
 * stationery. Send mails the typed words with the written pages attached as PNGs (and any 📎
 * files), threads onto the letter it answers, goes out the door that letter came in, and files
 * itself in Mail ▸ Sent with its ink. Arrives blank or prefilled ([ARG_TO_EMAIL] / [ARG_TO_NAME] /
 * [ARG_SUBJECT] / [ARG_BODY] / an attachment) from a quick win, a contact page, a note page, a
 * forward, a mailto: link — or as a reply ([ARG_REPLY_TO], [open]).
 *
 * THE ONE FROM RULE: the letter's own account (a reply), then the Mail view's account when opened
 * from The Mail ([ARG_FROM_ACCOUNT] — passed in, never read from the old `account_filter`
 * setting), then the default you pinned by holding From, then the first account.
 *
 * B6 INK (the plan's one real risk): one client — only the page on show is in the window, and it
 * lets go whenever the screen pauses or the page is hidden; silent seams (BigmeFastInk's own);
 * the keyboard never resizes the pad (the app is edge-to-edge, so ADJUST_RESIZE moves nothing by
 * itself, the frame never pads for the keyboard, and a pad that changes size for any other reason
 * re-binds); the pen
 * pauses under the From and stationery choosers, every dialog, the keyboard and the To
 * suggestions — their taps would otherwise land in the daemon, which goes by bounds, not by what
 * is on top.
 */
@AndroidEntryPoint
class MailComposeFragment @Inject constructor() : ScreenFragment() {

    override val view = R.layout.fragment_mail_compose
    private lateinit var binding: FragmentMailComposeBinding

    @Inject
    lateinit var calendarDayService: CalendarDayService

    private var accounts: List<MailAccount> = emptyList()
    /** The From: an account id, or a site's FluentCRM door ("crm:<id>"). Chosen once by the one
     *  From rule, then moved only by the From chooser — a trip away and back keeps the choice. */
    private var accountId: String? = null
    private var fromResolved = false
    private var sending = false

    /** This letter's stationery — follows the From; [LetterPad.TEXT] is no pad at all. */
    private var kind = LetterPad.RULED

    /** THE PAGES (09-07: "why not all the handwriting pages?"). Held by the fragment, not the
     *  view, so a trip away (🃏's "Go to it") and back keeps the ink. Only the page on show is in
     *  the window, so only it can hold an ink client. */
    private val pads = mutableListOf<InkPadView>()
    private var pageIndex = 0
    private val pad: InkPadView get() = pads[pageIndex]

    /** Open dialogs over the page ([onModalShown]); the pen is paused while any is up. */
    private var modalDepth = 0
    /** The keyboard's height, from [MainActivity.imeInset]. */
    private var imeBottom = 0
    /** Set when a touch on the pad asked the keyboard down: its pause stands aside until the
     *  keyboard's height next changes, so a device that reports a stale height can never leave
     *  the pen paused for good. */
    private var keyboardIgnored = false
    private var savedSoftInput: Int? = null
    private val imeListener: (Int) -> Unit = { h -> onKeyboard(h) }

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

        /**
         * An optional PNG to send with the letter, base64 — how a NOTE PAGE arrives as an email:
         * its words in the body, its picture attached.
         *
         * Kept for single-page senders; MULTI-PAGE stitches arrive as [ARG_ATTACH_PATH] instead —
         * a four-page 1404×1872 stitch base64'd into the arg bundle can pass the binder's ~1MB
         * transaction ceiling and TransactionTooLargeException kills the navigation.
         */
        const val ARG_ATTACH_PNG = "attach_png"
        /** An optional attachment as a CACHE FILE path; read once at send, deleted after. */
        const val ARG_ATTACH_PATH = "attach_path"
        /** What that attachment is called in the letter. */
        const val ARG_ATTACH_NAME = "attach_name"
        /** The inbox id of the letter this one ANSWERS (2026-10-10, the 🧵 Reply): the send threads
         *  onto it (In-Reply-To/References), goes out the door it came in — or, for your own sent
         *  letter, the door it went out of — and marks it replied. */
        const val ARG_REPLY_TO = "reply_to"
        /** The Mail view's account when the screen opens from The Mail (Michael 2026-10-11, the
         *  one From rule's second step) — handed in by the door, never read from a setting. */
        const val ARG_FROM_ACCOUNT = "from_account"

        /** The letter [ARG_REPLY_TO] names, handed over in memory by the door that opened this —
         *  taken only when the ids match, then cleared. A letter from a server search was never
         *  filed in the store, and without this its reply lost its thread and its door. */
        @Volatile var replyHandoff: InboxMessage? = null

        /**
         * The one door in for the screen's own callers: [replyTo] answers that letter (To, "Re:",
         * its thread and its door out); otherwise a fresh letter to [toEmail]/[toName], sent from
         * [fromAccount] when the opener knows the view's account. Returns false (and says so) when
         * there is nowhere to navigate from.
         */
        fun open(
            fragment: Fragment, replyTo: InboxMessage? = null,
            toEmail: String = "", toName: String = "", fromAccount: String? = null,
        ): Boolean {
            val args = Bundle()
            if (replyTo != null) {
                // Handed over in memory too: a letter from a SERVER search was never filed in the
                // store, and the screen would otherwise lose the thread and the door it answers through.
                replyHandoff = replyTo
                val to = MailThreads.replyAddress(replyTo)
                args.putString(ARG_TO_EMAIL, to)
                args.putString(ARG_TO_NAME, MailThreads.replyName(replyTo).takeIf { it != to }.orEmpty())
                args.putString(ARG_SUBJECT, ComposeRules.replySubject(replyTo.subject))
                args.putString(ARG_REPLY_TO, replyTo.id)
            } else {
                if (toEmail.isNotBlank()) args.putString(ARG_TO_EMAIL, toEmail)
                if (toName.isNotBlank()) args.putString(ARG_TO_NAME, toName)
            }
            fromAccount?.takeIf { it.isNotBlank() }?.let { args.putString(ARG_FROM_ACCOUNT, it) }
            return runCatching {
                NavHostFragment.findNavController(fragment).navigate(R.id.action_to_mail_compose, args)
            }.onFailure {
                fragment.context?.let { c -> Toast.makeText(c, "Couldn't open compose", Toast.LENGTH_SHORT).show() }
            }.isSuccess
        }
    }

    /** The letter being answered, when [ARG_REPLY_TO] named one the store still holds. */
    private var replyTo: InboxMessage? = null

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun isText() = kind == LetterPad.TEXT

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentMailComposeBinding.bind(view)
        val ctx = requireContext()

        binding.mailComposeSend.setOnClickListener { send() }
        binding.mailComposeDiscard.setOnClickListener { discard() }
        // A BACK SWIPE ASKS FIRST (Michael 2026-10-11): system Back takes the ✕'s road — words or
        // ink at stake get "Discard this draft?", an empty letter just leaves. (The letter pad's
        // back swipe used to throw its ink away without a word.)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { discard() }
            })

        accounts = MailAccountStore.all(ctx)
        val handoff = replyHandoff.also { replyHandoff = null }
        replyTo = replyTo ?: arguments?.getString(ARG_REPLY_TO)?.takeIf { it.isNotBlank() }?.let { id ->
            handoff?.takeIf { it.id == id } ?: InboxStore.messages(ctx).firstOrNull { it.id == id }
        }
        if (!fromResolved) {
            accountId = resolveFrom()
            kind = LetterPad.stationeryFor(ctx, accountId)
            fromResolved = true
        }
        binding.mailComposeTitle.text = if (replyTo != null) "Reply" else "Compose"
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

        // 📎 — anything the document providers can hand over rides the letter (Michael, 09-02:
        // "so want to be able to add attachments etc to email").
        binding.mailComposeAttach.setOnClickListener {
            runCatching { attachPicker.launch(arrayOf("*/*")) }
                .onFailure { toast("No file picker available on this device.") }
        }
        renderAttachmentChips()

        // 🃏 DRAFT ON PICKINGS moved here from the ↩ fork (Michael 2026-10-11): ↩ opens this
        // screen straight away, and the board ceremony — the reply gram onto a pickings note, to
        // gather and sketch the answer first — is one tap on the letter it answers.
        binding.mailComposePickings.visibility = if (replyTo != null) View.VISIBLE else View.GONE
        binding.mailComposePickings.setOnClickListener {
            val r = replyTo ?: return@setOnClickListener
            MailVerbs.draftReplyGram(this, calendarDayService, documentsRoot(), r)
        }
        binding.mailComposeNote.setOnClickListener { ontoTodaysNote() }

        // The rolodex feeds the To field: every contact with an address, shown as "Name <addr>"
        // so a couple of typed letters of either the person or the domain finds them. A plain
        // ArrayAdapter — its default filter substring-matches, which is exactly enough. Handwritten
        // letters get the suggestions too now (they had none on the old pad).
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
        // The suggestion list can hang over the pad: the pen pauses while it shows.
        binding.mailComposeTo.setOnDismissListener { applyPenPause() }

        // The stationery wears To and Subject; it repaints as they're typed.
        val restationery = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                refreshStationery()
                binding.mailComposeTo.postDelayed({ if (view.isAttachedToWindow) applyPenPause() }, 250L)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        binding.mailComposeTo.addTextChangedListener(restationery)
        binding.mailComposeSubject.addTextChangedListener(restationery)

        // ---- the pad ----
        if (pads.isEmpty()) pads.add(newPad())
        pageIndex = pageIndex.coerceIn(0, pads.lastIndex)
        // The prefill above typed To and Subject before any page existed: paint the paper now,
        // or a blank letter (no field ever typed into) would open on a bare white pad.
        refreshStationery()
        buildPagesRow()
        binding.mailComposePaper.setOnClickListener { pickStationery() }
        showPage(pageIndex)
        applyKind()
    }

    // ---- From ------------------------------------------------------------------------------

    private fun crmSites() = MailSync.crmSites(requireContext())

    /**
     * THE ONE FROM RULE (Michael 2026-10-11): the letter's own account → the Mail view's account
     * (when opened from The Mail) → the default you pinned by holding From → the first account.
     * A follow-up to a letter that went THROUGH a site's FluentCRM goes back through the same one.
     * The order itself lives in [ComposeRules.from]; this gathers what it decides between.
     */
    private fun resolveFrom(): String? {
        val ctx = requireContext()
        val crms = crmSites()
        val r = replyTo
        return ComposeRules.from(
            replyCrm = r?.let { MailSync.crmSiteByHost(ctx, MailThreads.crmHost(it))?.fromId },
            replyAccount = r?.let { MailThreads.replyAccountId(it) },
            viewAccount = arguments?.getString(ARG_FROM_ACCOUNT),
            pinned = ctx.getSharedPreferences("mail_prefs", 0).getString("default_from", null),
            firstAccount = accounts.firstOrNull()?.id,
            known = { i -> accounts.any { it.id == i } || crms.any { it.fromId == i } },
        )
    }

    /** The From line: a statement with one door, a ▾ chooser with several, an honest pointer at
     *  Mail settings with none (compose can open before any account exists). */
    private fun renderFrom() {
        val a = accounts.firstOrNull { it.id == accountId }
        val crms = crmSites()
        val viaCrm = MailSync.crmSite(requireContext(), accountId)
        val choices = accounts.size + crms.size
        binding.mailComposeFrom.text = when {
            viaCrm != null -> "From: via FluentCRM · ${viaCrm.host} (tracked)  ▾"
            accounts.isEmpty() && crms.isEmpty() -> "No mail accounts yet — add one with the @ on The Mail's rail."
            choices == 1 && a != null -> "From: ${a.display}"
            else -> "From: ${a?.display ?: "choose an account"}  ▾"
        }
        binding.mailComposeFrom.setOnClickListener(
            if (choices > 1) View.OnClickListener { pickAccount() } else null
        )
        // HOLD = pin this door as the default out — the setup lives on the control it sets, and
        // it is the ONLY way the default moves (Michael 2026-10-11: the letter pad used to pin
        // whatever you picked for one letter). A site's FluentCRM pins like an account.
        binding.mailComposeFrom.setOnLongClickListener {
            val cur = accountId ?: return@setOnLongClickListener true
            val name = viaCrm?.let { "via FluentCRM · ${it.host}" } ?: a?.display ?: return@setOnLongClickListener true
            requireContext().getSharedPreferences("mail_prefs", 0).edit()
                .putString("default_from", cur).apply()
            toast("★ $name is now your default send address")
            true
        }
        binding.mailComposePaper.text = "Stationery  ${LetterPad.label(kind)}  ▾"
    }

    private fun pickAccount() {
        // THE FLUENTCRM PATH (09-07): the last rows — through the roster site's CRM, tracked and
        // noted on the contact's profile. See MailSync.sendViaCrm.
        val crms = crmSites()
        val labels = accounts.map { it.display } + crms.map { "via FluentCRM · ${it.host} (tracked)" }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Send from")
            .setItems(labels.toTypedArray()) { _, i ->
                // This letter only — holding the From line is what pins a default.
                accountId = if (i < accounts.size) accounts[i].id else crms[i - accounts.size].fromId
                kind = LetterPad.stationeryFor(requireContext(), accountId)
                renderFrom(); refreshStationery(); applyKind()
            }
            .setNegativeButton("Cancel", null)
            .showModal()
    }

    // ---- Stationery and the pad ------------------------------------------------------------

    private fun pickStationery() {
        val a = accounts.firstOrNull { it.id == accountId }
        val crm = MailSync.crmSite(requireContext(), accountId)
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setTitle("Stationery for ${crm?.let { "via ${it.host}" } ?: a?.display ?: "this letter"}")
            .setItems(LetterPad.KINDS.map { it.second }.toTypedArray()) { _, which ->
                kind = LetterPad.KINDS[which].first
                LetterPad.setStationery(requireContext(), accountId, kind)   // remembered PER ACCOUNT / per site
                renderFrom(); refreshStationery(); applyKind()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showModal()
    }

    private fun newPad() = InkPadView(requireContext()).apply {
        // One client, never one bound while the screen is away or the page is hidden.
        inkAllowed = { this@MailComposeFragment.isResumed && !isText() && getView() != null }
        onTouchWhilePaused = { onPausedPadTouch() }
    }

    /** The account's stationery under every page — To and Subject on its letterhead. */
    private fun refreshStationery() {
        if (getView() == null) return
        val ctx = requireContext()
        val crm = MailSync.crmSite(ctx, accountId)
        val head = crm?.let { MailAccount(displayName = "via ${it.host}", email = "") }
            ?: accounts.firstOrNull { it.id == accountId }
        val raw = binding.mailComposeTo.text.toString().trim()
        val who = raw.substringBefore('<', "").trim().trim('"').trim().ifBlank { raw }
        val toLine = if (who.isBlank()) "To —" else "To $who"
        val re = binding.mailComposeSubject.text.toString().trim().take(64)
        pads.forEachIndexed { i, p ->
            p.stationery = LetterPad.painter(ctx, kind, head, toLine,
                if (i == 0) re else (if (re.isBlank()) "p. ${i + 1}" else "$re · p. ${i + 1}"))
            p.invalidate()
        }
    }

    /**
     * Pad or no pad. Text only hides the pad, the pages and the pen bar and gives the body the
     * page; the pad LETS GO of its ink client when hidden (its daemon would otherwise keep taking
     * the pen over the body by bounds) and takes it back when shown.
     */
    private fun applyKind() {
        val text = isText()
        val padVis = if (text) View.GONE else View.VISIBLE
        binding.mailComposeInkTools.visibility = padVis
        binding.mailComposeInk.visibility = padVis
        binding.mailComposeBody.hint = if (text) "Write…" else "Type here — the pen writes on the page below…"
        if (text) pads.forEach { it.releaseInk() }
        else binding.mailComposeInk.post { if (getView() != null && !isText()) pad.reclaimInk() }
        applyKeyboardRoom()
    }

    private fun buildPagesRow() {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        fun flat(label: String, color: Int) = TextView(ctx).apply {
            text = label; textSize = 15f; setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(px(10), px(6), px(10), px(6))
        }
        val row = binding.mailComposePages
        row.removeAllViews()
        val prev = flat("‹", 0xFF1F1F1F.toInt()).apply { setOnClickListener { showPage(pageIndex - 1) } }
        val label = TextView(ctx).apply { textSize = 13f; setTextColor(0xFF1F1F1F.toInt()); setPadding(px(8), 0, px(8), 0) }
        val next = flat("›", 0xFF1F1F1F.toInt()).apply { setOnClickListener { showPage(pageIndex + 1) } }
        val add = flat("＋ Page", 0xFF1F1F1F.toInt()).apply {
            setOnClickListener { pads.add(newPad()); refreshStationery(); showPage(pads.lastIndex) }
        }
        val drop = flat("🗑", 0xFF555555.toInt()).apply {
            contentDescription = "Remove this blank page"
            setOnClickListener {
                if (pads.size > 1 && pad.isBlank()) { pads.removeAt(pageIndex); showPage(pageIndex) }
            }
        }
        row.addView(prev); row.addView(label); row.addView(next); row.addView(add)
        row.addView(View(ctx), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(drop)
        row.tag = listOf(prev, label, next, drop)
    }

    private fun refreshPages() {
        @Suppress("UNCHECKED_CAST")
        val parts = (binding.mailComposePages.tag as? List<View>) ?: return
        val (prev, label, next, drop) = parts
        (label as TextView).text = "Page ${pageIndex + 1} of ${pads.size}"
        prev.alpha = if (pageIndex == 0) 0.3f else 1f
        next.alpha = if (pageIndex >= pads.lastIndex) 0.3f else 1f
        drop.visibility = if (pads.size > 1 && pad.isBlank()) View.VISIBLE else View.GONE
    }

    /** Put page [i] on show — the one pad in the window, so the one ink client (the page that
     *  leaves lets go of its own as it detaches). The pen bar belongs to the page on show. */
    private fun showPage(i: Int) {
        pageIndex = i.coerceIn(0, pads.lastIndex)
        val frame = binding.mailComposeInk
        frame.removeAllViews()
        (pad.parent as? ViewGroup)?.removeView(pad)
        frame.addView(pad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        binding.mailComposePenBar.removeAllViews()
        binding.mailComposePenBar.addView(InkPadView.penBar(requireContext(), pad))
        applyPenPause()
        refreshPages()
    }

    // ---- The pen's pauses and the keyboard -------------------------------------------------

    /** The pen pauses while a dialog is up, the keyboard is up, or the To suggestions show —
     *  on the page on show only; the others are out of the window. */
    private fun applyPenPause() {
        if (getView() == null || pads.isEmpty()) return
        val modal = modalDepth > 0
        val keyboard = imeBottom > 0 && !keyboardIgnored
        val suggest = binding.mailComposeTo.isPopupShowing
        pads.forEachIndexed { i, p ->
            val on = i == pageIndex
            p.pausePen("modal", on && modal)
            p.pausePen("keyboard", on && keyboard)
            p.pausePen("suggest", on && suggest)
        }
    }

    /** The hand came back to the page while the pen was paused: drop the keyboard and the
     *  suggestions, so the next stroke writes. */
    private fun onPausedPadTouch() {
        if (getView() == null) return
        if (binding.mailComposeTo.isPopupShowing) binding.mailComposeTo.dismissDropDown()
        if (imeBottom > 0) {
            keyboardIgnored = true
            val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
            binding.root.requestFocus()   // no field keeps the keyboard up behind the pen
        }
        applyPenPause()
    }

    private fun onKeyboard(height: Int) {
        if (height != imeBottom) keyboardIgnored = false
        imeBottom = height
        if (getView() == null) return
        applyKeyboardRoom()
        applyPenPause()
    }

    /** THE KEYBOARD STOPS COVERING THE BODY (Michael 2026-10-11) — on Text only, where the body
     *  is the page, the page makes room. Over a pad nothing moves: the body sits above the pad,
     *  and a pad that changed size under the keyboard would strand the B6 pen. */
    private fun applyKeyboardRoom() {
        if (getView() == null) return
        val bottom = if (isText()) imeBottom else 0
        if (binding.root.paddingBottom != bottom) binding.root.setPadding(0, 0, 0, bottom)
    }

    /** 🃏's "Go to it" sits above the pages row and pen bar, off the pad (the palm rule). */
    override val snackAnchor: View?
        get() = if (getView() != null && !isText()) binding.mailComposeInkTools else null

    override fun onModalShown() {
        modalDepth++
        applyPenPause()
    }

    override fun onModalDismissed() {
        modalDepth = (modalDepth - 1).coerceAtLeast(0)
        applyPenPause()
    }

    override fun onResume() {
        super.onResume()
        val main = activity as? MainActivity
        // The pen pill stands aside on this page (bottom corner = the palm zone, over the pad).
        main?.quickNoteSuppressed = true
        // Never PAN for the keyboard: a panned window would carry the pad away from where the
        // daemon thinks it is. ADJUST_RESIZE leaves the pad where it is only because the app
        // draws edge-to-edge (Android 11+) and MainActivity's frame doesn't pad for the keyboard
        // on this screen — the height comes to [onKeyboard] instead, and only Text only makes room.
        requireActivity().window?.let { w ->
            savedSoftInput = w.attributes.softInputMode
            @Suppress("DEPRECATION")
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        main?.imeInsetListener = imeListener
        onKeyboard(main?.imeInset ?: 0)
        if (getView() != null && !isText() && pads.isNotEmpty()) pad.reclaimInk()
    }

    override fun onPause() {
        // One client, never one left bound behind Home, Recents or the file picker (the -304
        // lesson): the page lets go here and takes it back in onResume.
        pads.forEach { it.releaseInk() }
        val main = activity as? MainActivity
        if (main?.imeInsetListener === imeListener) main?.imeInsetListener = null
        main?.quickNoteSuppressed = false
        savedSoftInput?.let { m -> activity?.window?.setSoftInputMode(m) }
        savedSoftInput = null
        super.onPause()
    }

    override fun onDestroyView() {
        // The pages outlive the view (a trip and back); hand them out of the dying frame.
        runCatching { binding.mailComposeInk.removeAllViews() }
        super.onDestroyView()
    }

    // ---- Attachments -----------------------------------------------------------------------

    /** One file the reader chose to ride the letter — held as its URI until send, so a big video
     *  never sits in memory while the letter is still being written. Name and size are read once
     *  at pick time for the chip. */
    private data class PickedAttachment(val uri: android.net.Uri, val name: String, val size: Long)

    private val pickedAttachments = mutableListOf<PickedAttachment>()

    /** The whole letter's attachment budget. SMTP servers commonly refuse around 25 MB encoded;
     *  20 raw keeps the letter under that after base64's ~4/3 growth, and the toast says the rule
     *  instead of letting the server bounce it later with a worse message. */
    private val ATTACH_BUDGET = 20L * 1024 * 1024

    private val attachPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val ctx = context ?: return@registerForActivityResult
        var over = false
        for (uri in uris) {
            // Keep read rights across the send (the grant is otherwise this activity result's).
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            var name = "attachment"; var size = 0L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
            if (pickedAttachments.sumOf { it.size } + size > ATTACH_BUDGET) { over = true; continue }
            if (pickedAttachments.none { it.uri == uri }) {
                pickedAttachments.add(PickedAttachment(uri, name, size))
            }
        }
        if (over) toast("That would put the letter over 20 MB of attachments — the rest were added.")
        renderAttachmentChips()
    }

    /** The chips row: each file's name and an ✕. The list IS the manager — no screen behind it. */
    private fun renderAttachmentChips() {
        val ctx = context ?: return
        if (getView() == null) return
        val chips = binding.mailComposeAttachChips
        chips.removeAllViews()
        binding.mailComposeAttachRow.visibility =
            if (pickedAttachments.isEmpty()) View.GONE else View.VISIBLE
        val dp = ctx.resources.displayMetrics.density
        for (a in pickedAttachments) {
            chips.addView(TextView(ctx).apply {
                text = "${a.name}  ✕"
                textSize = 13f
                setTextColor(android.graphics.Color.BLACK)
                setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((1.2f * dp).toInt(), android.graphics.Color.BLACK)
                    cornerRadius = 4 * dp
                }
                setOnClickListener {
                    pickedAttachments.remove(a)
                    renderAttachmentChips()
                }
                this.layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * dp).toInt() }
            })
        }
    }

    /** The address out of whatever the To field holds — "Name <addr>" from the autocomplete or a
     *  bare typed address; the angle brackets win when present. */
    private fun recipientAddress(raw: String): String =
        (Regex("<([^>]+)>").find(raw)?.groupValues?.get(1) ?: raw).trim()

    // ---- 📝 Onto today's note --------------------------------------------------------------

    /**
     * 📝 The letter onto today's note WITHOUT sending (the letter pad's door, 09-07) — for the
     * letter to yourself; an explicit act, never automatic. The written pages land as picks on
     * your stationery; typed words land in today's Text Notes, titled by the subject (09-19: "could
     * send to a text note"), never as a picture of words.
     */
    private fun ontoTodaysNote() {
        val ctx = requireContext().applicationContext
        val subject = binding.mailComposeSubject.text.toString().trim()
        val raw = binding.mailComposeTo.text.toString().trim()
        val who = raw.substringBefore('<', "").trim().trim('"').trim().ifBlank { raw }
        val words = binding.mailComposeBody.text.toString().trim()
        val bmps = if (isText()) emptyList() else pads.mapNotNull { it.render() }
        if (bmps.isEmpty() && words.isBlank()) { toast("Nothing written yet"); return }
        val service = calendarDayService
        val root = documentsRoot()
        val stamp = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            bmps.forEachIndexed { i, bmp ->
                runCatching {
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        service, root, bmp, LocalDate.now(), pageKey = "0",
                        sourceLink = "letter://$stamp#p${i + 1}",
                        sourceLabel = "Letter · ${who.ifBlank { "—" }}" + (if (bmps.size > 1) " · p. ${i + 1}" else ""),
                        cardText = subject.ifBlank { "Letter" })
                }
            }
            if (words.isNotBlank()) runCatching {
                com.toolsboox.plugin.textnotes.TextNotesStore.addNote(
                    ctx, LocalDate.now(),
                    title = subject.ifBlank { "Letter · ${who.ifBlank { "—" }}" },
                    body = (if (who.isNotBlank()) "To $who\n\n" else "") + words)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                toast(listOfNotNull(
                    bmps.takeIf { it.isNotEmpty() }?.let { "On today's note" + (if (it.size > 1) " · ${it.size} pages" else "") },
                    "in today's Text Notes".takeIf { words.isNotBlank() },
                ).joinToString(" · ").replaceFirstChar { it.uppercase() })
            }
        }
    }

    // ---- Send ------------------------------------------------------------------------------

    /**
     * Send: the typed words as the body, every written page attached as a PNG on your stationery,
     * the 📎 files beside them. Through the account's SMTP — the exact path both old screens took
     * — or a site's FluentCRM (text and handwriting pages; 📎 files don't ride that road). Sent →
     * toast + back; failed → the server's own words, and the letter stays on screen to try again.
     */
    private fun send() {
        if (sending) return
        val ctx = context ?: return
        val aid = accountId
        if (aid == null) { toast("No mail account to send from — add one with the @ on The Mail's rail."); return }
        val to = recipientAddress(binding.mailComposeTo.text.toString())
        if (to.isBlank()) { toast("No recipient address."); return }
        val subject = binding.mailComposeSubject.text.toString().trim()
        val typed = binding.mailComposeBody.text.toString()
        // Text only: no pages rendered, nothing drawn attached.
        val bmps = if (isText()) emptyList() else pads.mapNotNull { it.render() }
        val hasPrefill = !arguments?.getString(ARG_ATTACH_PNG).isNullOrBlank() ||
            !arguments?.getString(ARG_ATTACH_PATH).isNullOrBlank()
        if (subject.isBlank() && typed.isBlank() && bmps.isEmpty() && pickedAttachments.isEmpty() && !hasPrefill) {
            toast("Nothing to send yet."); return
        }
        val body = if (typed.isBlank() && bmps.isNotEmpty()) "(handwritten — see attached)" else typed
        val orig = replyTo
        // The person's name for the Sent row (2026-10-10 review: a reply recorded a bare
        // address): the To field's "Name <addr>", else the letter it answers.
        val rawTo = binding.mailComposeTo.text.toString()
        val toName = rawTo.substringBefore('<', "").trim().trim('"').trim().ifBlank {
            orig?.let { MailThreads.replyName(it) }
                ?.takeIf { MailThreads.replyAddress(orig).equals(to, ignoreCase = true) }
                .orEmpty()
        }

        sending = true
        binding.mailComposeSend.text = "Sending…"
        MailSync.crmSite(ctx, aid)?.let { crm ->
            if (pickedAttachments.isNotEmpty() || hasPrefill)
                toast("FluentCRM carries the words and the written pages — the 📎 files stay behind.")
            lifecycleScope.launch {
                val err = try {
                    withContext(Dispatchers.IO) {
                        val pngs = bmps.map { pngBytes(it) }
                        val refs = orig?.let { MailThreads.replyRefs(it) }
                        val subj = subject.ifBlank { "(no subject)" }
                        val r = MailSync.sendViaCrm(ctx, crm, to, toName, subj, body, pngs,
                            inReplyTo = orig?.let { MailThreads.inReplyTo(it) }, references = refs)
                        val host = crm.host
                        // Past the send it went: the keeping is logged, never "Send failed".
                        MailVerbs.afterSent {
                            if (orig != null && !InboxStore.isSent(orig.id))
                                InboxStore.markReplied(ctx.applicationContext, orig.id)
                            val row = InboxStore.recordLetter(ctx.applicationContext, accountId = "crm:$host",
                                accountLabel = "FluentCRM · $host", fromName = r.fromName, fromEmail = r.fromEmail,
                                toName = toName, toEmail = to, subject = subj, text = body, inkPages = pngs,
                                references = refs.orEmpty())
                            InboxStore.noteCrmCampaign(ctx.applicationContext, row.id, host, r.campaignId)
                        }
                    }
                    null as String?
                } catch (e: Exception) { e.message ?: "Send failed" }
                sentOrNot(err, viaCrm = true)
            }
            return
        }
        lifecycleScope.launch {
            // The IO on Dispatchers.IO, the send allowed to outlive a wandering-off user, the UI
            // touched only if we're still attached. A prefilled page picture rides along as a real
            // attachment (see ARG_ATTACH_PNG).
            val attachBytes = arguments?.getString(ARG_ATTACH_PNG)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
                ?: arguments?.getString(ARG_ATTACH_PATH)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path ->
                        runCatching {
                            java.io.File(path).let { f -> f.readBytes().also { b -> f.delete(); b } }
                        }.getOrNull()
                    }
            val prefill = attachBytes
                ?.let {
                    listOf(SmtpClient.Attachment(
                        filename = arguments?.getString(ARG_ATTACH_NAME).orEmpty().ifBlank { "page.png" },
                        mimeType = "image/png", bytes = it))
                }.orEmpty()
            // The reader's own files, read HERE at send (never held in memory while writing),
            // each with the resolver's mime so a PDF arrives as a PDF and a voice memo plays.
            val picked = withContext(Dispatchers.IO) {
                pickedAttachments.mapNotNull { a ->
                    runCatching {
                        val bytes = ctx.contentResolver.openInputStream(a.uri)?.use { it.readBytes() }
                            ?: return@mapNotNull null
                        SmtpClient.Attachment(
                            filename = a.name,
                            mimeType = ctx.contentResolver.getType(a.uri) ?: "application/octet-stream",
                            bytes = bytes)
                    }.getOrNull()
                }
            }
            if (picked.size < pickedAttachments.size && isAdded) {
                toast("${pickedAttachments.size - picked.size} attachment(s) couldn't be read and were left off.")
            }
            val err = try {
                withContext(Dispatchers.IO) {
                    // The written pages, encoded off the main thread (the PNG encode is slow).
                    val pngs = bmps.map { pngBytes(it) }
                    val ink = pngs.mapIndexed { i, p ->
                        SmtpClient.Attachment(if (pngs.size == 1) "letter.png" else "letter-${i + 1}.png", "image/png", p)
                    }
                    val refs = orig?.let { MailThreads.replyRefs(it) }
                    // Threaded when it answers a letter (the sent row keeps the chain + its own id).
                    // A letter with no pages files itself the way a typed letter always has; one
                    // with pages files its own row so the ink rides it (the letter pad's keeping).
                    val mid = MailSync.sendNew(ctx, aid, to, subject, body, record = pngs.isEmpty(),
                        attachments = prefill + picked + ink,
                        inReplyTo = orig?.let { MailThreads.inReplyTo(it) },
                        references = refs, toName = toName)
                    MailVerbs.afterSent {
                        if (orig != null && !InboxStore.isSent(orig.id))
                            InboxStore.markReplied(ctx.applicationContext, orig.id)
                        if (pngs.isNotEmpty()) {
                            val a = accounts.firstOrNull { it.id == aid }
                            InboxStore.recordLetter(
                                ctx.applicationContext, accountId = aid, accountLabel = a?.display ?: aid,
                                fromName = a?.displayName.orEmpty(), fromEmail = a?.email.orEmpty(),
                                toName = toName, toEmail = to, subject = subject.ifBlank { "(no subject)" },
                                text = body, inkPages = pngs, messageId = mid, references = refs.orEmpty())
                        }
                    }
                }
                null
            } catch (e: Exception) { e.message ?: "Send failed" }
            sentOrNot(err, viaCrm = false)
        }
    }

    private fun pngBytes(b: android.graphics.Bitmap): ByteArray =
        java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun sentOrNot(err: String?, viaCrm: Boolean) {
        sending = false
        if (!isAdded) return
        if (err == null) {
            // Says where it really is: in the conversation under the letter it answers, and in
            // Mail ▸ Sent.
            toast(MailVerbs.sentWords(reply = replyTo != null, viaCrm = viaCrm))
            NavHostFragment.findNavController(this).popBackStack()
        } else {
            if (getView() != null) binding.mailComposeSend.text = "➤ Send"
            toast("Send failed: $err")
        }
    }

    /** ✕ (and a back swipe): leave. Anything you made gets one held breath (a confirm) — words in
     *  the body, ink on any page, a 📎 you picked, or a To / Subject you typed or changed. Fields
     *  exactly as the door prefilled them (a mailto's body too) don't: nothing hand-made is at stake. */
    private fun discard() {
        val args = arguments
        val toEmail = args?.getString(ARG_TO_EMAIL).orEmpty()
        val toName = args?.getString(ARG_TO_NAME).orEmpty()
        val toFace = if (toEmail.isBlank()) "" else if (toName.isBlank()) toEmail else "$toName <$toEmail>"
        val body = binding.mailComposeBody.text.toString().trim()
        val atStake = (body.isNotEmpty() && body != args?.getString(ARG_BODY).orEmpty().trim()) ||
            (!isText() && pads.any { !it.isBlank() }) ||
            pickedAttachments.isNotEmpty() ||
            binding.mailComposeTo.text.toString().trim() != toFace ||
            binding.mailComposeSubject.text.toString().trim() != args?.getString(ARG_SUBJECT).orEmpty().trim()
        if (!atStake) {
            NavHostFragment.findNavController(this).popBackStack()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(requireContext()))
            .setMessage("Discard this draft?")
            .setPositiveButton("Discard") { _, _ ->
                NavHostFragment.findNavController(this).popBackStack()
            }
            .setNegativeButton("Keep writing", null)
            .showModal()
    }

    // ScreenFragment abstract members: no spinner here — Send narrates its own state ("Sending…"),
    // the same inline discipline as the inbox.
    override fun showLoading() {}
    override fun hideLoading() {}
}
