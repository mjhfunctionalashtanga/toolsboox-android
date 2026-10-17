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
import com.toolsboox.plugin.mail.MailThreads
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
 * accounts editor, and the knowledge-graph moves (tag, assign-to-synthesis) — lifted out
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
        // OFFER-NEVER-MINT (Michael's ruling): the star still records the whole letter and mints its
        // Intake gram, but the to-do it drafts is now a PROPOSED candidate, not a live task. Same
        // content, same "email" provenance — only [proposed] changes, so it lands on the Tasks
        // surface's Proposed shelf and becomes a task only when the reader taps Promote.
        val item = LedgerItem(
            id = "li-" + UUID.randomUUID().toString().lowercase(),
            kind = LedgerItem.Kind.TASK, text = m.subject, date = Date(), stage = "todo", source = "email"
        ).apply { proposed = true }
        fragment.lifecycleScope.launch {
            val placedGram = withContext(Dispatchers.IO) {
                InboxStore.setStarred(ctx, m, true)   // persists the message content; off the main thread
                val today = LocalDate.now()
                // The open day page's per-pen-up save does the same whole-file load→mutate→save;
                // unserialized, one of the two writes silently drops the other's items.
                val wrote = com.toolsboox.plugin.calendar.ot.DayLocks.withDay(today) {
                    // A today-file that won't read is never saved over: no task, no gram, and the
                    // caller hears null ("the placement failed").
                    val day = dayService.loadForWrite(root, today, null, Locale.getDefault())
                        ?: return@withDay false
                    day.ledgerItems.add(item)
                    dayService.save(root, today, day)
                    true
                }
                if (!wrote) return@withContext null
                // A proposed candidate is NOT pushed to the live task mirror (CalDAV) — pushing it
                // there would MINT the very task the shelf exists to hold back. Promotion does the
                // push. The day JSON still syncs, carrying [proposed] so every device shelves it too.
                if (!item.proposed)
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
        // Read-only dedupe peek; the placement below is the writer (and refuses an unreadable day).
        val day = dayService.loadForDisplay(root, today, null, Locale.getDefault())
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
     * The ↩ on a letter. It used to fork — "⚡ Quick reply" (a guarded dialog), later "⌨ Type the
     * reply · ✍ Handwrite the reply · 🃏 Draft on a pickings note first…" (Michael, 08-23: "it
     * would be better to have a real writing or typing opportunity there"; 08-24: the blank note
     * leads, in both hands). Since 2026-10-11 the two hands are ONE compose screen and the board
     * door sits in its top bar, so ↩ simply opens it, addressed and threaded.
     */
    @Suppress("UNUSED_PARAMETER")
    fun replyDoors(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File,
        m: InboxMessage, fromAccount: String? = null, onSent: () -> Unit
    ) {
        // ↩ OPENS STRAIGHT IN (Michael 2026-10-11, Q2). The fork used to ask "⌨ Type · ✍
        // Handwrite · 🃏 Draft on a pickings note first…" — but typing and handwriting are one
        // screen now (pen on the pad, finger on the body, no mode), and 🃏 Draft on pickings
        // rides that screen's top bar. So the reply is one tap, and the hold menu's ↩ two. The
        // caller's page rebuilds on the way back (its list, the letter's "↩ You replied"), which
        // is what [onSent] used to do; [dayService]/[root] stay for the callers' sake.
        // [fromAccount]: the Mail view's account, the From rule's second step — it answers when
        // the letter's own account no longer resolves (a removed account, a server-search letter).
        MailComposeFragment.open(fragment, replyTo = m, fromAccount = fromAccount)
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
        val sender = MailThreads.replyName(m).ifBlank { "Unknown sender" }
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
    /**
     * The blank note, BY HAND, right now — a pad addressed to the sender: draw, add an optional
     * typed line, Send. The handwriting rides as a PNG beside the text (the text threads,
     * quotes and searches; the picture is the hand — the 2026-08-04 Rebecca rule). No board, no
     * gather, no second surface: the pad IS the reply.
     */
    fun handwriteReply(
        fragment: ScreenFragment, m: InboxMessage, onSent: () -> Unit,
        dayService: CalendarDayService? = null, root: File? = null,
    ) {
        // THE LETTER PAD (09-07): the half-height dialog pad retired for a whole-screen page with
        // a big SEND, an editable To/Subject, the From account, and stationery per account — see
        // [LetterPad]. The sent letter files itself with its ink — in Mail ▸ Sent, and in the
        // conversation under the letter it answers; the 📝 door puts it onto today's note only
        // when asked.
        LetterPad.open(fragment, replyTo = m, onSent = onSent, dayService = dayService, root = root)
    }

    /**
     * ⌨ The typed reply, in the full composer (2026-10-10 review, iOS parity — "⌨ Type the reply"
     * is the composer there in every place it appears): From over every account and each site's
     * FluentCRM, 📎, To and Subject editable — Michael, 08-23: "it would be better to have a real
     * writing or typing opportunity there". Still one tap. The composer is told which letter it
     * answers, so the reply threads, goes out the door that letter came in (or went out of) and
     * marks it replied; the reading pane comes back on return (FeedsFragment's cameBackTo), its
     * thread strip already holding the reply.
     */
    fun typeReply(fragment: ScreenFragment, m: InboxMessage) {
        // The one compose screen's own door (Michael 2026-10-11): To, "Re:", the thread and the
        // door it answers through, the letter handed over in memory for a server-search letter.
        MailComposeFragment.open(fragment, replyTo = m)
    }

    /** The small typed-reply box. No door opens it since 2026-10-10 — ⌨ is the full composer
     *  ([typeReply]) everywhere, as on iOS — but it stays until Michael says it can go. */
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
                text = "To ${MailThreads.replyName(m)}\nRe: ${m.subject}"
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
    fun sendReplyText(
        fragment: ScreenFragment, m: InboxMessage, text: String, onSent: () -> Unit = {},
        /** The handwriting, when the reply was written by hand — sent alongside the recognised text. */
        attachments: List<com.toolsboox.plugin.mail.SmtpClient.Attachment> = emptyList(),
        /** The same handwriting, kept on the sent row so Correspondence shows it (see recordSent). */
        inkPng: ByteArray? = null,
    ) {
        val ctx = fragment.requireContext()
        // A follow-up to a letter that went THROUGH a site's FluentCRM goes back through it
        // (2026-10-10, "reply on a Sent letter is broken"): same contact, same timeline.
        val crmHost = MailThreads.crmHost(m)
        fragment.lifecycleScope.launch {
            val err = try {
                withContext(Dispatchers.IO) {
                    if (crmHost != null) {
                        val crm = MailSync.crmSiteByHost(ctx, crmHost)
                            ?: throw com.toolsboox.plugin.mail.MailException("$crmHost isn't set up to send any more.")
                        val to = MailThreads.replyAddress(m)
                        val name = MailThreads.replyName(m).takeIf { it != to } ?: ""
                        val subject = if (m.subject.lowercase().startsWith("re:")) m.subject else "Re: ${m.subject}"
                        val refs = MailThreads.replyRefs(m)
                        // The hand rides as the bridge's ink pages (the Write piece hands it over
                        // as an attachment instead of inkPng).
                        val ink = listOfNotNull(inkPng).ifEmpty {
                            attachments.filter { it.mimeType.startsWith("image/") }.map { it.bytes }
                        }
                        val r = MailSync.sendViaCrm(ctx, crm, to, name, subject, text, ink,
                            inReplyTo = MailThreads.inReplyTo(m), references = refs)
                        // Only the send is in the try: past it the letter went, whatever the keeping does.
                        afterSent {
                            val row = InboxStore.recordLetter(ctx.applicationContext, accountId = "crm:${crm.host}",
                                accountLabel = "FluentCRM · ${crm.host}", fromName = r.fromName, fromEmail = r.fromEmail,
                                toName = name, toEmail = to, subject = subject, text = text,
                                inkPages = ink, references = refs.orEmpty())
                            InboxStore.noteCrmCampaign(ctx.applicationContext, row.id, crm.host, r.campaignId)
                        }
                    } else {
                        val mid = MailSync.sendReply(ctx, text, m, attachments)
                        // A sent reply is a keep-forever event: the mail you answered persists its
                        // body and never prunes, exactly like a star. The reply text you wrote also
                        // persists to its own sent pile — with its Message-ID and the chain it
                        // answers, so it threads under the letter — kept forever and searchable.
                        // (Your own letter needs no REPLIED mark: it's already kept forever.)
                        afterSent {
                            if (!InboxStore.isSent(m.id)) InboxStore.markReplied(ctx.applicationContext, m.id)
                            InboxStore.recordSent(ctx.applicationContext, m, text, inkPng, messageId = mid)
                        }
                    }
                }
                null
            } catch (e: Exception) { e.message ?: "Send failed" }
            if (!fragment.isAdded) return@launch   // send outlives the fragment; toast needs it attached
            Toast.makeText(ctx, if (err == null) sentWords(reply = true, viaCrm = crmHost != null) else "Send failed: $err",
                Toast.LENGTH_SHORT).show()
            if (err == null) onSent()
        }
    }

    /** What every send says (2026-10-10 review: the pad, the typed reply and the composer said
     *  three different things) — where the letter really is, in the words of the doors that reach
     *  it (the Mail drawer's ➤ Sent). The same words as iOS's LetterPadView. */
    fun sentWords(reply: Boolean, viaCrm: Boolean = false): String = when {
        viaCrm -> "Sent via FluentCRM — noted on their profile"
        reply -> "Sent — it's in the conversation and Mail ▸ Sent"
        else -> "Sent — it's in Mail ▸ Sent"
    }

    /** The keeping after a send that went — the sent row, the replied mark, the CRM campaign note.
     *  Logged, never thrown (2026-10-10 review): the server already has the letter, and "Send
     *  failed" now would invite the writer to send it twice. */
    inline fun afterSent(block: () -> Unit) {
        runCatching(block).onFailure { timber.log.Timber.w(it, "mail: sent, but the keeping failed") }
    }

    // The knowledge-graph moves — how an email joins the Ledger like any other object. All three
    // rode the standalone screen's letter dialog; they moved here when that screen retired so the
    // verbs outlive the door they were first hung on.

    /** File the letter under #tags. The uri keeps the `email://` spelling the screen's Tag row
     *  always wrote — tags already stuck to letters under that name, and renaming the address
     *  would orphan every one of them. But the REST of the letter's life — grams, rhizome,
     *  reply drafts, the day-page router — speaks `mail://`, so the same letter was two
     *  strangers in the connection graph. One idempotent edge joins the two nodes whenever the
     *  tag door opens: healed without a migration (DESIGN-notes-room-2026-08-27 §3.3's
     *  precondition for tag-driven routing). */
    fun tag(fragment: ScreenFragment, m: InboxMessage) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                    ctx.applicationContext, "email://${m.id}", "mail://${m.id}",
                    fromLabel = m.subject.take(60), toLabel = m.subject.take(60))
            }
        }
        com.toolsboox.ot.TagPicker.show(
            ctx, "email://${m.id}", m.subject,
            showModal = { fragment.showModal(it) })
    }

    /**
     * ✍ Send to All Picks — the missing inbound door (DESIGN-notes-room-2026-08-27 §3.4),
     * re-aimed at the inbox per THE RULING (08-28, [PickingsPlacement.inbox]): "when you save a
     * pick it goes to All Picks AND it can be sent to a note." The letter's link-face lands as a
     * pick in ALL PICKS — the one pile you sort from — never straight onto the writing page;
     * from there it goes wherever the sorting takes it. An edge joins `mail://` to the picks
     * page in the rhizome. Dedupe by sourceLink — bringing the same letter twice is a re-tap,
     * not a twin.
     * @return via [onDone]: true placed, false already in All Picks, null the placement failed.
     */
    fun intoAllPicks(
        fragment: ScreenFragment, dayService: CalendarDayService, root: File,
        m: InboxMessage, onDone: (placed: Boolean?) -> Unit
    ) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            val placed = withContext(Dispatchers.IO) {
                runCatching {
                    val today = LocalDate.now()
                    val mailUri = "mail://${m.id}"
                    val picksPage = com.toolsboox.plugin.calendar.ot.CalendarDayPageNotes.GRAM_PICKS
                    // Read-only dedupe peek; the placement below is the writer (it throws on a day
                    // that won't read → null → "couldn't").
                    val day = dayService.loadForDisplay(root, today, null, Locale.getDefault())
                    if (day.imageElements.any { it.page.substringBefore('#') == picksPage && it.sourceLink == mailUri })
                        return@runCatching false
                    val sender = m.fromName.ifBlank { m.fromEmail }.ifBlank { "Unknown sender" }
                    val dateLabel = java.time.Instant.ofEpochMilli(m.date)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
                    val face = com.toolsboox.plugin.calendar.ot.LinkCardRenderer.render(
                        url = "", title = m.subject.ifBlank { "(no subject)" }, kind = "mail",
                        sourceName = "$sender \u00b7 $dateLabel",
                        excerpt = m.snippet.ifBlank { m.body }.trim().take(400)
                    )
                    com.toolsboox.plugin.calendar.ot.PickingsPlacement.place(
                        dayService, root, face, today, pageKey = picksPage,
                        sourceLink = mailUri, sourceLabel = sender, cardText = m.subject
                    )
                    com.toolsboox.plugin.calendar.ot.ConnectionStore.connect(
                        ctx.applicationContext, mailUri, "ledger://$today/$picksPage",
                        fromLabel = m.subject.take(60), toLabel = "All Picks")
                    true
                }.getOrNull()
            }
            onDone(placed)
        }
    }

    /**
     * 🧵 The correspondence — in and out JOINED, per correspondent (DESIGN-notes-room-2026-08-27
     * §3.2, first cut). The kept piles already hold both halves — every starred/replied letter
     * FROM them, every letter you SENT them — but as two lists; this reads them as one thread,
     * newest first, ➤ marking your own letters. A row's tap shows that letter whole. The pane-
     * level correspondent view builds on this later; the JOIN is the piece that was missing.
     */
    fun correspondenceWith(
        fragment: ScreenFragment, m: InboxMessage,
        /** Unused since the one compose screen (2026-10-11) carries its own 📝; kept for callers. */
        @Suppress("UNUSED_PARAMETER") dayService: CalendarDayService? = null,
        @Suppress("UNUSED_PARAMETER") root: File? = null,
    ) {
        val ctx = fragment.requireContext()
        val who = (if (InboxStore.isSent(m.id)) m.toEmail else m.fromEmail).ifBlank { m.fromEmail }
        if (who.isBlank()) { Toast.makeText(ctx, "No address on this letter", Toast.LENGTH_SHORT).show(); return }
        fragment.lifecycleScope.launch {
            val thread = withContext(Dispatchers.IO) {
                InboxStore.messages(ctx).filter {
                    it.fromEmail.equals(who, ignoreCase = true) || it.toEmail.equals(who, ignoreCase = true)
                }.sortedByDescending { it.date }
            }
            if (!fragment.isAdded) return@launch
            if (thread.isEmpty()) { Toast.makeText(ctx, "Nothing kept with $who yet", Toast.LENGTH_SHORT).show(); return@launch }
            val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            val labels = thread.map { t ->
                val d = java.time.Instant.ofEpochMilli(t.date)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(fmt)
                (if (InboxStore.isSent(t.id)) "\u27a4 " else "") + "$d \u00b7 " +
                    t.subject.ifBlank { "(no subject)" }.take(64)
            }.toTypedArray()
            val name = m.fromName.takeIf { !InboxStore.isSent(m.id) }?.ifBlank { null } ?: who
            // ✍ THE SEND DOOR LEADS (Michael, 09-02: "something's up with correspondence. I
            // guess I need a proper email send surface"). The room could SHOW an exchange and
            // never continue one — every row opened a read-only dialog whose only verb was
            // Close. The composer (To prefilled, 📎 and all) is one row away now, above the
            // thread, because writing back is what a correspondence is FOR.
            val rows = arrayOf("\u270d  Write to $name\u2026") + labels
            fragment.showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("\ud83e\uddf5 $name")
                .setItems(rows) { _, i ->
                    if (i == 0) {
                        androidx.navigation.fragment.NavHostFragment.findNavController(fragment).navigate(
                            com.toolsboox.R.id.action_to_mail_compose,
                            androidx.core.os.bundleOf(
                                MailComposeFragment.ARG_TO_EMAIL to who,
                                MailComposeFragment.ARG_TO_NAME to name,
                            ))
                        return@setItems
                    }
                    val which = i - 1
                    val t = InboxStore.hydrate(ctx, thread[which])
                    val body = android.widget.TextView(ctx).apply {
                        text = t.body.ifBlank { t.snippet }
                        textSize = 15f
                        val pad = dp(fragment, 16)
                        setPadding(pad, pad / 2, pad, pad)
                        setTextIsSelectable(true)
                        com.toolsboox.ui.share.OwnProcessText.guard(this)   // no "Ledgable" in our own menu (C9)
                    }
                    val scroll = android.widget.ScrollView(ctx).apply { addView(body) }
                    fragment.showModal(androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                        .setTitle(t.subject.ifBlank { "(no subject)" })
                        .setView(scroll)
                        // Reply, right on the letter — the same composer, and threaded for real
                        // (2026-10-10): told WHICH letter it answers, so the reply carries
                        // In-Reply-To/References, goes out the door that letter came in (or went
                        // out of), and marks it replied — same one tap. ONE ↩ (Michael 2026-10-11,
                        // Q2): the one compose screen types and takes the pen, so "⌨ Type" and
                        // "✍ Handwrite" here opened the same page and folded into the rail's ↩.
                        .setPositiveButton("\u21a9  Reply") { _, _ -> typeReply(fragment, t) }
                        .setNegativeButton("Close", null)
                        .create())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create())
        }
    }

    // (openRhizome — a letter's 🕸 seat — left with Rhizome's exit from mail, Michael 2026-10-11:
    // "Rhizome still doesn't sound useful" → "Ok". Rhizome stays on tasks, contacts, the Map,
    // picks and Missed Rhizomes; the letter's seat is ✉ Read / Unread now.)

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
                        val day = dayService.loadForWriteOrThrow(root, today, null, Locale.getDefault())
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
        var dialog: androidx.appcompat.app.AlertDialog? = null

        // Built as a column rather than setItems, so the fetch cadence can sit UNDER the
        // accounts: it is the clock mail arrives on, and a person setting up mail should not
        // have to know it is kept in the feeds' wrench. Same 52dp/17sp rows as the Settings
        // screen (EinkControls), one per account, then the door to a new one.
        fun row(text: String, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text; textSize = 17f; setTextColor(0xFF000000.toInt())
            minHeight = dp(fragment, 52); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { dialog?.dismiss(); onTap() }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(fragment, 18), dp(fragment, 4), dp(fragment, 18), dp(fragment, 4))
            accounts.forEach { a -> addView(row(a.display) { showAccountEditor(fragment, a, onChanged, onDeleted) }) }
            addView(row("➕  Add account") { showAccountEditor(fragment, MailAccount(), onChanged, onDeleted) })
            // The one worker fetches mail and articles alike; the block persists on pick, so
            // this list dialog needs no Save. The same block, same words, in the feed settings.
            addView(com.toolsboox.plugin.feeds.ui.FetchCadence.view(ctx))
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Mail accounts")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
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
            text = t; textSize = 14f; setTextColor(0xFF444444.toInt()); setPadding(0, dp(fragment, 10), 0, dp(fragment, 2))
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
            addView(label("IMAP port (implicit TLS, usually 993)")); addView(imapPort); addView(imapSSL)
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
            // Guarded (settings sweep, 09-10): a whole account, its password and its kept mail's
            // door — said before it happens, like the Sites delete.
            androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
                .setTitle("Remove ${a.email}?")
                .setMessage("This device forgets the account and its password. Mail already fetched stays until it ages out; starred letters stay.")
                .setPositiveButton("Remove") { _, _ ->
                    MailAccountStore.delete(ctx, a.id); toast("Account removed")
                    onDeleted(a.id)
                }
                .setNegativeButton("Keep", null)
                .show()
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
