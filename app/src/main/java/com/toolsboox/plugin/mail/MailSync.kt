package com.toolsboox.plugin.mail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import timber.log.Timber

/**
 * Bridges the IMAP/SMTP clients to the unified inbox and the configured accounts. [refresh] pulls
 * recent mail from every account into [InboxStore]; [sendReply] posts a reply from the account the
 * message arrived on. The inbox id encodes its origin -- acct:<accountId>:uid:<uid> -- so a reply
 * always goes out the same door it came in. A direct port of iOS App/Mail/MailSync.swift.
 */
object MailSync {
    fun accountId(messageId: String): String? {
        // A 🚫 Junk letter ("junk:<accountId>:uid:<uid>", [JunkMail.idFor]) came through the same
        // account — its reply goes out of it, and an account's own lists find it by it.
        if (JunkMail.isJunk(messageId)) return JunkMail.accountId(messageId)
        if (!messageId.startsWith("acct:")) return null
        val parts = messageId.removePrefix("acct:").split(":", limit = 2)
        return parts.firstOrNull()
    }

    /** Pull the most recent messages from every configured account into the inbox. Returns a short
     *  human summary of anything that went wrong (null = clean), so the UI can surface it gently.
     *  Suspends; each account's fetch runs on Dispatchers.IO inside [ImapClient]. */
    suspend fun refresh(context: Context, limit: Int = 25): String? {
        val accounts = MailAccountStore.all(context)
        if (accounts.isEmpty()) {
            // Deliberately NOT `setFetched(emptyList())` any more. That call made sense while the
            // store was a session window — it cleared the screen. Now it would be an instruction to
            // forget every message on the device because the account list is momentarily empty
            // (mid-edit, or a password re-entry). [InboxStore.messages] already shows the seeded
            // samples while no account is configured, so nothing is on screen that shouldn't be.
            return "No mail accounts yet -- add one with the gear on The Mail's rail."
        }
        val all = ArrayList<InboxMessage>()
        val errors = ArrayList<String>()
        val okAccounts = HashSet<String>()
        for (a in accounts) {
            val pass = MailAccountStore.password(context, a.id)
            if (a.imapHost.isBlank() || pass.isBlank()) {
                errors.add("${a.email.ifBlank { "account" }}: needs an IMAP host and password")
                continue
            }
            try {
                val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
                val fetched = client.fetchRecent(a.loginName, pass, limit)
                // UIDVALIDITY changed (mailbox rebuilt/restored) ⇒ every stored uid for this account
                // names a different letter now: drop the account's cached window (kept piles hold
                // their own bodies and stay) before the fresh one folds in.
                val uv = client.lastUidValidity
                val uvPrefs = context.getSharedPreferences("ledger_mail_uidvalidity", 0)
                val prev = uvPrefs.getLong(a.id, 0L)
                if (uv > 0L) {
                    if (prev > 0L && prev != uv) {
                        Timber.w("mail: UIDVALIDITY changed for ${a.email} ($prev → $uv); dropping the cached window")
                        withContext(Dispatchers.IO) { InboxStore.dropAccountWindow(context, a.id) }
                    }
                    uvPrefs.edit().putLong(a.id, uv).apply()
                }
                fetched.forEach { f -> all.add(toInbox(a, f)) }
                okAccounts.add(a.id)
            } catch (e: Exception) {
                errors.add("${a.email.ifBlank { "account" }}: ${e.message ?: "failed"}")
            }
        }
        // The window FOLDS INTO the store; it does not become it. And the pruning that follows is
        // told about the whole store, not just this window — a cleared or read id whose message is
        // still on the device must keep its state, or the first refresh after durability shipped
        // would resurface every message you had swept away. Both calls read and write files, so
        // both are held off the main thread; `refresh` is a suspend function called from the UI's
        // scope, and the IO inside ImapClient never covered these two.
        val merged = withContext(Dispatchers.IO) {
            val merged = InboxStore.setFetched(context, all)
            InboxStore.prune(context, okAccounts, merged.map { it.id }.toSet())
            merged
        }
        Timber.i("mail refresh: ${all.size} in the window, ${merged.size} on the device")
        return if (errors.isEmpty()) null else errors.joinToString("\n")
    }

    /** One [FetchedMessage] shaped for the unified inbox. Refresh and server search share this so a
     *  searched message is a REAL inbox message — same acct:<id>:uid:<uid> id, same typed uid — and
     *  every flow (open, star-to-todo, reply, load-the-rest) works on it unchanged. */
    private fun toInbox(a: MailAccount, f: FetchedMessage): InboxMessage = InboxMessage(
        id = "acct:${a.id}:uid:${f.uid}", account = a.displayName.ifBlank { a.email },
        fromName = f.fromName, fromEmail = f.fromEmail, subject = f.subject,
        snippet = f.body.replace("\n", " ").take(140),
        body = f.body, date = f.date, truncated = f.truncated, html = f.html,
        attachments = f.attachments.map { InboxAttachment(it.filename, it.mime, it.size) },
        // The typed UID (null when the probe fell back to a random one) -- the id embeds it too,
        // but only this field is safe to fetch by.
        uid = f.uid.toLongOrNull(),
        // THE THREAD IDS (2026-10-10, critique C1): parsed since 09-10 and dropped right here, so
        // no Android reply ever carried In-Reply-To and no letter could join a conversation.
        // In-Reply-To folds into the chain — a client that sends only that still threads.
        // Kept cleaned to its "<…>" token, so every comparison and In-Reply-To uses one spelling.
        messageId = MailThreads.ids(f.messageId).firstOrNull() ?: f.messageId.trim(),
        references = MailThreads.mergeRefs(f.references, f.inReplyTo)
    )

    /**
     * Search the SERVERS — the local filter only sees the ~25-per-account window plus the starred
     * pile; this asks every configured inbox (or just [accountFilter]'s) via IMAP `UID SEARCH`
     * (subject / from / body text) and pulls the newest matches back through the same size-probed
     * bounded fetch a refresh uses. Returns the matches (newest first, at most [cap] across
     * accounts) alongside per-account error lines — one slow or broken account never empties the
     * others' results. Suspends; each account's search runs on Dispatchers.IO inside [ImapClient].
     */
    suspend fun searchServer(
        context: Context, query: String, accountFilter: String? = null, cap: Int = 30
    ): Pair<List<InboxMessage>, List<String>> {
        val accounts = MailAccountStore.all(context)
            .filter { accountFilter == null || it.id == accountFilter }
        val all = ArrayList<InboxMessage>()
        val errors = ArrayList<String>()
        if (accounts.isEmpty()) return all to listOf("No account to search.")
        // Split the cap across accounts (each bounded hit is a round-trip on a slow panel's
        // network) but never so thin an account can't show a real result set.
        val perAccount = (cap / accounts.size).coerceAtLeast(10)
        for (a in accounts) {
            val pass = MailAccountStore.password(context, a.id)
            if (a.imapHost.isBlank() || pass.isBlank()) {
                errors.add("${a.email.ifBlank { "account" }}: needs an IMAP host and password")
                continue
            }
            try {
                val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
                client.search(a.loginName, pass, query, perAccount).forEach { all.add(toInbox(a, it)) }
            } catch (e: Exception) {
                errors.add("${a.email.ifBlank { "account" }}: ${e.message ?: "search failed"}")
            }
        }
        return all.sortedByDescending { it.date }.take(cap) to errors
    }

    /**
     * Read ONE account's Junk folder (Michael 2026-10-11: "individual sents, junks, trash") through
     * [ImapClient.fetchJunk] — read-only, newest [limit] — and keep it in [JunkMail] for the visit.
     * The letters are real [InboxMessage]s shaped by the same [toInbox] a refresh uses, under their
     * own "junk:<id>:uid:<uid>" ids (iOS's spelling) — the uid is the Junk folder's, which is why
     * [fetchFull] and [attachmentBytes] open that folder for them, never INBOX. Never throws: an
     * account with no Junk folder comes back with a null name, a failure with its words.
     * Suspends; the IMAP round-trip runs on Dispatchers.IO inside [ImapClient].
     */
    suspend fun fetchJunk(context: Context, accountId: String, limit: Int = 25): JunkMail.Folder {
        val a = MailAccountStore.all(context).firstOrNull { it.id == accountId }
        val folder = when {
            a == null -> JunkMail.Folder(accountId, null, emptyList(), "That account is gone.")
            a.imapHost.isBlank() || MailAccountStore.password(context, a.id).isBlank() ->
                JunkMail.Folder(accountId, null, emptyList(), "${a.email.ifBlank { "This account" }} needs an IMAP host and password.")
            else -> try {
                val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
                val got = client.fetchJunk(a.loginName, MailAccountStore.password(context, a.id), limit)
                if (got == null) JunkMail.Folder(accountId, null, emptyList(), null)
                else JunkMail.Folder(accountId, got.first, got.second.map { f ->
                    toInbox(a, f).copy(id = JunkMail.idFor(a.id, f.uid))
                }.sortedByDescending { it.date }, null)
            } catch (e: Exception) {
                JunkMail.Folder(accountId, null, emptyList(), e.message ?: "failed")
            }
        }
        JunkMail.put(folder)
        return JunkMail.get(accountId) ?: folder
    }

    /** Fetch a truncated message WHOLE from the account it arrived on -- the tap on the truncation
     *  note. Returns the completed message and lands it in [InboxStore] (the fetch window, and the
     *  starred pile if it's kept there), so every surface sees the full body. Throws
     *  [MailMessageTooLarge] when even the on-demand ceiling can't hold it. A 🚫 Junk letter is
     *  fetched from the account's Junk folder (read-only) and lands in [JunkMail] and the keep
     *  piles only — never the fetch window, so it can't surface in the Inbox (as on iOS). */
    suspend fun fetchFull(context: Context, m: InboxMessage): InboxMessage {
        val aid = accountId(m.id)
            ?: throw MailException("This message isn't tied to a configured account.")
        val uid = m.uid
            ?: throw MailException("This message carries no server id to fetch by.")
        val a = MailAccountStore.all(context).firstOrNull { it.id == aid }
            ?: throw MailException("The account this message arrived on is gone.")
        val pass = MailAccountStore.password(context, a.id)
        if (a.imapHost.isBlank() || pass.isBlank()) throw MailException("Add an IMAP host and password for ${a.email}.")
        val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
        val junk = JunkMail.isJunk(m.id)
        val f = client.fetchFull(a.loginName, pass, uid, junk)     // suspends; IO within
        val full = m.copy(
            snippet = f.body.replace("\n", " ").take(140),
            // The full fetch carries the markup too — loading the rest of a designed letter and
            // getting only its stripped text back would be a downgrade halfway through reading.
            // And the ATTACHMENT ROSTER: the bounded slice usually couldn't see the parts (over-
            // bound mail IS usually attachments), so the rest arriving is when the chips appear.
            body = f.body, truncated = false, html = f.html,
            attachments = f.attachments.map { InboxAttachment(it.filename, it.mime, it.size) },
            // The cheapest backfill there is (2026-10-10 review): a letter cached before ids were
            // kept gets them from the whole fetch, so a reply from it threads — and replace()
            // carries them into the kept piles too.
            messageId = m.messageId.ifBlank { MailThreads.ids(f.messageId).firstOrNull() ?: f.messageId.trim() },
            references = m.references.ifBlank { MailThreads.mergeRefs(f.references, f.inReplyTo) }
        )
        if (junk) { JunkMail.replace(full); InboxStore.replaceKept(context, full) }
        else InboxStore.replace(context, full)
        return full
    }

    /**
     * One attachment's bytes, carved from the message fetched WHOLE — the tap-to-view path.
     * The refresh never pulls attachment bodies; only this does, and only for the one message.
     * Cached by the caller; throws with honest words on the same failures [fetchFull] names.
     */
    suspend fun attachmentBytes(context: Context, m: InboxMessage, filename: String): Pair<ByteArray, String> {
        val aid = accountId(m.id)
            ?: throw MailException("This message isn't tied to a configured account.")
        val uid = m.uid
            ?: throw MailException("This message carries no server id to fetch by.")
        val a = MailAccountStore.all(context).firstOrNull { it.id == aid }
            ?: throw MailException("The account this message arrived on is gone.")
        val pass = MailAccountStore.password(context, a.id)
        if (a.imapHost.isBlank() || pass.isBlank()) throw MailException("Add an IMAP host and password for ${a.email}.")
        val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
        val raw = client.fetchRaw(a.loginName, pass, uid, JunkMail.isJunk(m.id))
        return Mime.partBytes(raw, filename)
            ?: throw MailException("That attachment isn't in the message any more.")
    }

    /** Reply to a message out the account it arrived on — or, following up a letter you SENT
     *  (2026-10-10: "reply on a Sent letter is broken"), out the account it went from, to the
     *  person it went to. Returns the reply's own Message-ID, for the sent row to keep. A
     *  FluentCRM letter's follow-up goes through the CRM instead (see MailVerbs.sendReplyText). */
    suspend fun sendReply(
        context: Context, body: String, m: InboxMessage,
        attachments: List<SmtpClient.Attachment> = emptyList(),
    ): String {
        val aid = MailThreads.replyAccountId(m)
            ?: throw MailException("This message isn't tied to a configured account, so there's nowhere to send from.")
        val to = MailThreads.replyAddress(m)
        if (to.isBlank()) throw MailException("This message has no reply address.")
        val subject = if (m.subject.lowercase().startsWith("re:")) m.subject else "Re: ${m.subject}"
        // `record = false`: the reply flow writes its own, richer sent row (with the original in
        // hand, so it can carry who it went to and the Re: thread it belongs to) once this returns
        // — see MailVerbs.reply. Letting sendNew record as well would file the same
        // reply twice, once with a recipient and once without.
        return sendNew(context, aid, to, subject, body, record = false, attachments = attachments,
                inReplyTo = MailThreads.inReplyTo(m), references = MailThreads.replyRefs(m))
    }

    /**
     * Send a fresh message from a chosen account (used by the standalone composer and the reply above).
     *
     * [record] files the sent message in the keep-forever pile once SMTP has accepted it — on by
     * default, because everything that reaches here IS a message you sent, and that pile is the only
     * copy of it this device will ever have (nothing is APPENDed to the server's Sent folder; this
     * client speaks only INBOX). The composer used to send and then only toast "Sent", so a letter
     * written here vanished the instant the server took it. The reply path passes false and records
     * its own row.
     *
     * Returns the letter's Message-ID (2026-10-10, gap #4): minted HERE rather than inside the SMTP
     * client, which used to make one up and throw it away — the sent row keeps it, so a reply to
     * your letter (and the server's Sent copy, later) can find its way back to it.
     */
    suspend fun sendNew(
        context: Context, accountId: String, to: String, subject: String, body: String,
        record: Boolean = true, attachments: List<SmtpClient.Attachment> = emptyList(),
        inReplyTo: String? = null, references: String? = null,
        /** Who it went to, by name, for the sent row (the composer's "Name <addr>"). */
        toName: String = "",
    ): String {
        val a = MailAccountStore.all(context).firstOrNull { it.id == accountId }
            ?: throw MailException("No such account.")
        val pass = MailAccountStore.password(context, a.id)
        if (a.smtpHost.isBlank() || pass.isBlank()) throw MailException("Add an SMTP host and password for ${a.email}.")
        if (to.isBlank()) throw MailException("No recipient address.")
        val smtp = SmtpClient(a.smtpHost, a.smtpPort.coerceIn(1, 65535), a.smtpSSL)
        val messageId = SmtpClient.newMessageId(a.email)
        smtp.send(
            a.loginName, pass,
            SmtpClient.Outgoing(
                fromEmail = a.email, fromName = a.displayName, toEmail = to,
                subject = subject.ifBlank { "(no subject)" }, body = body,
                attachments = attachments, inReplyTo = inReplyTo, references = references,
                messageId = messageId
            )
        )
        // Past the throwing send: it went. Only NOW is there anything to keep — a failed send must
        // leave no sent row behind, or the pile starts lying about what actually left the device.
        // And the keeping can't throw past here (2026-10-10 review): a letter the server took must
        // never come back to the writer as "Send failed" — that invites the duplicate.
        if (record) runCatching {
            InboxStore.recordComposed(
                context, accountId = a.id, accountLabel = a.display,
                fromName = a.displayName, fromEmail = a.email,
                to = to, subject = subject, body = body,
                messageId = messageId, references = references ?: inReplyTo ?: "", toName = toName
            )
        }.onFailure { Timber.w(it, "mail: sent, but the sent row couldn't be kept") }
        return messageId
    }

    // ---- The FluentCRM path ----------------------------------------------------------------

    /** What the bridge said after a CRM send — where it came from, and the contact page it landed on. */
    data class CrmSendResult(val fromEmail: String, val fromName: String, val profileUrl: String, val campaignId: Int)

    /** A site whose FluentCRM the app can send through: configured, with an app password. The From
     *  id is "crm:<siteId>". */
    data class CrmSite(val id: String, val host: String, val site: String, val user: String, val pass: String) {
        val fromId: String get() = CRM_PREFIX + id
    }
    const val CRM_PREFIX = "crm:"

    /** EVERY configured site with a password (Michael, 09-07: "make sure this works for Ashtanga
     *  Tech, The Yoga Club, MJH.yoga, michaeljoelhall.com") — one "via FluentCRM · host" row each. */
    fun crmSites(context: Context): List<CrmSite> =
        com.toolsboox.plugin.calendar.nw.SiteStore.all(context).mapNotNull { s ->
            val pass = com.toolsboox.plugin.calendar.nw.SiteStore.password(context, s.id)
            if (s.url.isBlank() || s.username.isBlank() || pass.isBlank()) return@mapNotNull null
            val site = s.url.trimEnd('/')
            CrmSite(s.id, runCatching { java.net.URI(site).host }.getOrNull() ?: s.display, site, s.username, pass)
        }
    fun crmSite(context: Context, fromId: String?): CrmSite? {
        if (fromId == null || !fromId.startsWith(CRM_PREFIX)) return null
        val id = fromId.removePrefix(CRM_PREFIX)
        return crmSites(context).firstOrNull { it.id == id }
    }
    /** The first reachable site — for callers that only need "is there one". */
    fun crmSite(context: Context): Pair<String, com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config>? =
        crmSites(context).firstOrNull()?.let { it.host to com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(it.site, it.user, it.pass, 0) }

    /**
     * THE FLUENTCRM PATH (Michael, 09-07: "send outgoing emails through my FluentCRM to contacts for
     * better tracking… in FluentCRM there is a place from the CRM profile to send that user a message
     * and it would note that on their account"). The letter goes THROUGH the site: the bridge
     * (`ledgr/v1/crm/send-email`, mu-plugin ledgr-crm-send.php on ATS + TYC) finds or creates the
     * contact and sends a one-off custom-email campaign to them — the CRM profile's own "Send Email"
     * — so opens and clicks are tracked and the letter is noted on the contact's timeline. The
     * handwriting rides as a PNG the site uploads and embeds by URL. Blocking; call on Dispatchers.IO.
     */
    private val crmHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Ask each site how its letters fared (`ledgr/v1/crm/campaign-status`) and cache the answers.
     *  Blocking; call off the main thread. Quiet on every failure — the row just keeps its last word. */
    fun refreshCrmStates(context: Context) {
        val pending = InboxStore.crmPending(context)
        if (pending.isEmpty()) return
        val sites = crmSites(context)
        val client = crmHttp
        for ((host, ids) in pending) {
            val site = sites.firstOrNull { it.host == host } ?: continue
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("${site.site}/wp-json/ledgr/v1/crm/campaign-status?ids=" + ids.take(200).joinToString(","))
                    .header("Authorization", okhttp3.Credentials.basic(site.user, site.pass)).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val arr = org.json.JSONObject(resp.body?.string().orEmpty()).optJSONArray("campaigns") ?: return@use
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val state = when {
                            o.optInt("clicks", 0) > 0 -> "clicked"
                            o.optBoolean("opened", false) -> "opened"
                            o.optString("status") == "sent" -> "sent"
                            else -> "pending"
                        }
                        InboxStore.setCrmState(context, host, o.optInt("campaign_id"), state)
                    }
                }
            }.onFailure { Timber.w(it, "crm status $host") }
        }
    }

    /** The CRM site a FluentCRM letter went through, by the host its sent row names. */
    fun crmSiteByHost(context: Context, host: String?): CrmSite? =
        host?.let { h -> crmSites(context).firstOrNull { it.host == h } }

    fun sendViaCrm(context: Context, crm: CrmSite, to: String, name: String, subject: String, body: String,
                   inkPages: List<ByteArray>, inReplyTo: String? = null, references: String? = null): CrmSendResult {
        val host = crm.host
        val c = com.toolsboox.plugin.calendar.nw.LedgerWebBridge.Config(crm.site, crm.user, crm.pass, 0)
        val payload = org.json.JSONObject()
            .put("email", to).put("name", name).put("subject", subject).put("body_text", body)
        // The thread a CRM reply answers (2026-10-10). Today's bridge (ledgr-crm-send.php, a
        // FluentCRM custom-email campaign) doesn't set mail headers, so it ignores these and the
        // recipient's client sees a new conversation; they ride along so a bridge that learns to
        // set In-Reply-To/References needs no app change. The sent row keeps the chain either
        // way, which is what clears "Letters to answer" and draws the thread here.
        inReplyTo?.takeIf { it.isNotBlank() }?.let { payload.put("in_reply_to", it) }
        references?.takeIf { it.isNotBlank() }?.let { payload.put("references", it) }
        if (inkPages.isNotEmpty()) {
            payload.put("ink_pngs", org.json.JSONArray(inkPages.map { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }))
            payload.put("ink_alt", "handwritten letter")
        }
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        val req = okhttp3.Request.Builder()
            .url("${c.site}/wp-json/ledgr/v1/crm/send-email")
            .header("Authorization", okhttp3.Credentials.basic(c.user, c.pass))
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), payload.toString()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            val ok = json?.optBoolean("ok", false) == true || json?.optInt("ok", 0) == 1
            if (!resp.isSuccessful || !ok) {
                throw MailException(json?.optString("message")?.ifBlank { null }
                    ?: "The site refused the letter (HTTP ${resp.code}).")
            }
            return CrmSendResult(
                fromEmail = json!!.optString("from_email", ""),
                fromName = json.optString("from_name", host),
                profileUrl = json.optString("profile_url", ""),
                campaignId = json.optInt("campaign_id", 0))
        }
    }
}

