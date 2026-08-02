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
 * The letter ceremonies — star-to-todo (with its All Stars gram), un-star, the reply dialog, the
 * accounts editor, and the knowledge-graph moves (tag, rhizome, assign-to-synthesis) — lifted out
 * of the standalone Mail screen when The Mail became a lens in the feeds pane, and the whole of
 * what survived it when Michael retired that screen. They stay gathered here rather than folding
 * into the lens because they are what a LETTER can do, not what a screen does: the feeds lens
 * speaks them today, and any surface that ever holds a letter calls here rather than growing a
 * second private copy of what starring an email means.
 *
 * Everything takes the calling fragment (for its context, scope and guarded modals) and reports
 * back through a callback rather than repainting anything itself — which list to redraw, and
 * how, is the one thing callers legitimately disagree about.
 */
object MailVerbs {

    private fun dp(f: ScreenFragment, v: Int) = (v * f.resources.displayMetrics.density).toInt()

    /**
     * Star → to-do: create a task on today's page (like Kanban's add-card), mark the mail starred
     * (which persists its whole body to the keep pile), and mint its link-face gram onto today's
     * Intake — the same three moves the Mail screen's star always made, verbatim, kept whole
     * through that screen's retirement.
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
     * The ↩ on a letter is TWO doors now, and this is the fork. "⚡ Quick reply" is the guarded
     * dialog below, unchanged — right for the two-sentence answer typed on the spot. "✍ Draft the
     * reply…" is for the answer that deserves composing: it mints the letter's reply gram onto a
     * board through the one destination funnel, so the answer can be gathered on Pickings,
     * sketched on Synthesize, and written in Write before anything is sent. Michael: "Reply
     * popping up a quicky reply is OK, but it would be better to have a real writing or typing
     * opportunity there."
     */
    fun replyDoors(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File,
        m: InboxMessage, onSent: () -> Unit
    ) {
        val labels = arrayOf("⚡  Quick reply", "✍  Draft the reply…")
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(fragment.requireContext()))
            .setTitle("Reply to ${m.fromName.ifBlank { m.fromEmail }}")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> reply(fragment, m, onSent)
                    1 -> draftReplyGram(fragment, dayService, root, m)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Mint the REPLY GRAM — the letter's link-card face wearing the reply mark (↩ REPLY chip,
     * "Re: <subject>" title, "to <sender>" on the source line, the letter's opening words as the
     * excerpt, since they are what the answer answers) — and send it through [GramDestinations]'
     * chooser, so it can land on Gram Picks, a Pickings board, All Stars or Synthesize, wherever
     * the gathering happens. The placement snackbar names the landing and offers the trip.
     *
     * The card itself stays an ordinary mail gram on the wire — `sourceLink = mail://<id>`, no new
     * day-JSON fields, so the iPad reads it as a letter card and loses nothing. The reply INTENT
     * (and later the Write piece answering it) rides the [MailReplyDrafts] sidecar keyed by that
     * same address; it is what makes the gram's hold menu grow "✍ Write the reply" wherever the
     * card travels.
     */
    fun draftReplyGram(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File, m: InboxMessage
    ) {
        val ctx = fragment.requireContext()
        val sender = m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }
        val subject = m.subject.trim().ifBlank { "(no subject)" }
        val reSubject = if (subject.lowercase().startsWith("re:")) subject else "Re: $subject"
        // Recorded before the destination is even chosen: the intent is "I owe this letter an
        // answer", and that became true the moment he picked the door. A small local write plus a
        // backgrounded sync — the same bargain the tag picker's contacts read makes on a tap.
        com.toolsboox.plugin.mail.MailReplyDrafts.note(ctx, m)
        val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
            url = "", title = reSubject, kind = "reply", sourceName = "to $sender",
            excerpt = m.snippet.ifBlank { m.body }.trim().take(400)
        )
        com.toolsboox.plugin.calendar.ot.PickingsPlacement.chooseAndPlace(
            fragment, dayService, root, face,
            sourceLink = "mail://${m.id}", sourceLabel = sender, cardText = reSubject
        )
    }

    /**
     * The reply gram's own verb, offered by the hold menus wherever the card sits: null for every
     * ordinary gram, an item for a card whose `mail://` address has a recorded reply intent. One
     * lookup against an in-memory cache — a hold menu must stay cheap to open.
     */
    fun writeReplyItem(fragment: ScreenFragment, sourceLink: String): com.toolsboox.ot.LedgerContextMenu.Item? {
        if (!sourceLink.startsWith("mail://")) return null
        val intent = com.toolsboox.plugin.mail.MailReplyDrafts
            .forMail(fragment.requireContext(), sourceLink) ?: return null
        return com.toolsboox.ot.LedgerContextMenu.Item("✍  Write the reply") {
            openReplyWrite(fragment, intent)
        }
    }

    /**
     * Open the Write piece FOR this letter — minting it on first use, reopening it ever after.
     *
     * The piece is a named Write document through [WritePageStore] (the same machinery every named
     * document rides — registry entry, cross-device name sync, renameable, deletable), titled
     * "Re: <subject>" so the hub row says what it answers. The link back to the letter is the
     * intent's [MailReplyDrafts.linkWrite] — recorded BEFORE navigating, so the send door is
     * already on the piece when it opens. A piece deleted from the hub simply stops being found
     * here, and the next "Write the reply" mints a fresh one: the registry is the truth about
     * what exists, this store only remembers which existing piece answers which letter.
     */
    fun openReplyWrite(fragment: ScreenFragment, intent: com.toolsboox.plugin.mail.MailReplyDrafts.Intent) {
        val ctx = fragment.requireContext()
        val existing = intent.writeKey.takeIf { it.isNotBlank() }?.let { key ->
            com.toolsboox.plugin.calendar.ot.WritePageStore.list(ctx).firstOrNull { it.key == key }
        }
        val page = existing ?: com.toolsboox.plugin.calendar.ot.WritePageStore
            .add(ctx, intent.reSubject)
            .also { com.toolsboox.plugin.mail.MailReplyDrafts.linkWrite(ctx, intent.mailId, it.key, it.date) }
        com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(fragment, page.date, page.key)
    }

    /**
     * A plain reply, sent out the account the message arrived on (via SMTP) — the guarded dialog
     * the Mail screen always showed, reachable from any surface holding a letter. The toasts
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
                sendReplyText(fragment, m, text, onSent)
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    /**
     * The one reply SEND — the dialog above and the Write piece's "Send as reply" door both come
     * through here, so a reply means the same thing however it was written: out the account the
     * letter arrived on ([MailSync.sendReply], which threads the `Re:` subject), then the
     * keep-forever bookkeeping, then the toast. [onSent] fires only on success.
     */
    fun sendReplyText(fragment: ScreenFragment, m: InboxMessage, text: String, onSent: () -> Unit = {}) {
        val ctx = fragment.requireContext()
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
            Toast.makeText(ctx, if (err == null) "Reply sent" else "Send failed: $err", Toast.LENGTH_SHORT).show()
            if (err == null) onSent()
        }
    }

    // The knowledge-graph moves — how an email joins the Ledger like any other object. All three
    // rode the standalone screen's letter dialog; they moved here when that screen retired so the
    // verbs outlive the door they were first hung on.

    /** File the letter under #tags. The uri keeps the `email://` spelling the screen's Tag row
     *  always wrote — tags already stuck to letters under that name, and renaming the address
     *  would orphan every one of them. */
    fun tag(fragment: ScreenFragment, m: InboxMessage) {
        com.toolsboox.ot.TagPicker.show(
            fragment.requireContext(), "email://${m.id}", m.subject,
            showModal = { fragment.showModal(it) })
    }

    /**
     * Join the message into the connection graph and open its rhizome — the same move every other
     * Ledger object makes (mirrors QuickWinsFragment.openRhizome / DailyPileFragment.rhizome). The
     * message rides a stable `mail://<id>` URI, so any edge drawn on the rhizome surface sticks.
     */
    fun openRhizome(fragment: ScreenFragment, m: InboxMessage) {
        androidx.navigation.fragment.NavHostFragment.findNavController(fragment).navigate(
            com.toolsboox.R.id.action_to_ledger_rhizome,
            androidx.core.os.bundleOf(
                com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment.ARG_URI to "mail://${m.id}",
                com.toolsboox.plugin.calendar.ui.LedgerRhizomeFragment.ARG_LABEL
                    to m.subject.ifBlank { m.fromName }.take(60)
            )
        )
    }

    /**
     * File the message onto today's synthesis pile (its subject + a body snippet), drop a
     * provenance edge from its `mail://<id>` URI to today's Synthesize page, AND place a card on
     * the page itself. Filing used to write only the bank, which is a list behind a control you
     * have to know to open — so "Assign to synthesis" reported success and the Synthesize page
     * stayed blank. Michael: "Send to Synthesis from email is the correct move — but when used,
     * nothing appears in Synthesis." It didn't, because nothing was ever put there. A card on the
     * grid is what "in Synthesis" means; the bank is the reservoir, not the destination.
     *
     * [onDone] reports whether anything was filed (false = the letter had no words to file); the
     * caller says so its own way. IO runs off the main thread, and the card placement takes the
     * per-day lock — a background whole-file load→mutate→save that races the open day page's
     * per-pen-up save.
     */
    fun assignToSynthesis(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File,
        m: InboxMessage, onDone: (filed: Boolean) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val uri = "mail://${m.id}"
        val label = m.subject.ifBlank { m.fromName.ifBlank { m.fromEmail } }.take(60)
        val snippet = m.body.trim().replace(Regex("\\s+"), " ").take(280)
        val line = listOf(m.subject.trim(), snippet).filter { it.isNotBlank() }.joinToString(" — ")
        if (line.isBlank()) { onDone(false); return }
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val today = LocalDate.now()
                // The idea BANK — syncs across devices, and feeds the "Ideas → grid" picker.
                com.toolsboox.plugin.calendar.ot.SynthesisIdeaStore.add(ctx, today, listOf(line), "note", label)
                com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                    ctx, uri, com.toolsboox.ot.LedgerUri.page(today.toString(), "synthesize"),
                    kind = com.toolsboox.plugin.calendar.da.v2.Connection.PLACED,
                    fromLabel = label, toLabel = "Synthesize · $today"
                )
                // Same card + same staggered placement as CalendarDayFragment.addIdeaCards, so a
                // filed message is indistinguishable from one dropped via the Ideas picker.
                runCatching {
                    com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                        val day = dayService.load(root, today, null, Locale.getDefault())
                        val pageKey = "synthesize"
                        val bmp = com.toolsboox.plugin.calendar.ot.QuoteCardRenderer
                            .render(line, "— $label", null, 1080, 0)
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        val w = 1404f * 0.42f
                        val h = w * bmp.height / bmp.width
                        val count = day.imageElements.count { it.page == pageKey }
                        val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (1404f - w).coerceAtLeast(0f))
                        val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (1872f - h).coerceAtLeast(0f))
                        day.imageElements.add(com.toolsboox.da.ImageElement(
                            x = x, y = y, width = w, height = h, data = base64, page = pageKey,
                            sourceLabel = "— $label", cardText = line
                        ))
                        dayService.save(root, today, day)
                    }
                }.onFailure { timber.log.Timber.w(it, "failed to place the synthesis card") }
            }
            onDone(true)
        }
    }

    // Mail settings — the accounts list + per-account editor, unchanged in shape from the
    // retired screen's. [onChanged] fires after a save (a first/renamed account changes
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
