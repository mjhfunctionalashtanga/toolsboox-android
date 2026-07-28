package com.toolsboox.plugin.mail

import android.content.Context

/**
 * Bridges the IMAP/SMTP clients to the unified inbox and the configured accounts. [refresh] pulls
 * recent mail from every account into [InboxStore]; [sendReply] posts a reply from the account the
 * message arrived on. The inbox id encodes its origin -- acct:<accountId>:uid:<uid> -- so a reply
 * always goes out the same door it came in. A direct port of iOS App/Mail/MailSync.swift.
 */
object MailSync {
    fun accountId(messageId: String): String? {
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
            InboxStore.setFetched(emptyList())
            return "No mail accounts yet -- add one with the gear in the Mail screen."
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
                fetched.forEach { f -> all.add(toInbox(a, f)) }
                okAccounts.add(a.id)
            } catch (e: Exception) {
                errors.add("${a.email.ifBlank { "account" }}: ${e.message ?: "failed"}")
            }
        }
        InboxStore.setFetched(all)
        // With the window in hand, drop the cleared/read ids that can never resurface — only for
        // accounts that just fetched cleanly (a failed account keeps all its state).
        InboxStore.prune(context, okAccounts, all.map { it.id }.toSet())
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
        // The typed UID (null when the probe fell back to a random one) -- the id embeds it too,
        // but only this field is safe to fetch by.
        uid = f.uid.toLongOrNull()
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

    /** Fetch a truncated message WHOLE from the account it arrived on -- the tap on the truncation
     *  note. Returns the completed message and lands it in [InboxStore] (the fetch window, and the
     *  starred pile if it's kept there), so every surface sees the full body. Throws
     *  [MailMessageTooLarge] when even the on-demand ceiling can't hold it. */
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
        val f = client.fetchFull(a.loginName, pass, uid)     // suspends; IO within
        val full = m.copy(
            snippet = f.body.replace("\n", " ").take(140),
            // The full fetch carries the markup too — loading the rest of a designed letter and
            // getting only its stripped text back would be a downgrade halfway through reading.
            body = f.body, truncated = false, html = f.html
        )
        InboxStore.replace(context, full)
        return full
    }

    /** Reply to a message out the account it arrived on. */
    suspend fun sendReply(context: Context, body: String, m: InboxMessage) {
        val aid = accountId(m.id)
            ?: throw MailException("This message isn't tied to a configured account, so there's nowhere to send from.")
        if (m.fromEmail.isBlank()) throw MailException("This message has no reply address.")
        val subject = if (m.subject.lowercase().startsWith("re:")) m.subject else "Re: ${m.subject}"
        sendNew(context, aid, m.fromEmail, subject, body)
    }

    /** Send a fresh message from a chosen account (used by the reply above; ready for a composer). */
    suspend fun sendNew(context: Context, accountId: String, to: String, subject: String, body: String) {
        val a = MailAccountStore.all(context).firstOrNull { it.id == accountId }
            ?: throw MailException("No such account.")
        val pass = MailAccountStore.password(context, a.id)
        if (a.smtpHost.isBlank() || pass.isBlank()) throw MailException("Add an SMTP host and password for ${a.email}.")
        if (to.isBlank()) throw MailException("No recipient address.")
        val smtp = SmtpClient(a.smtpHost, a.smtpPort.coerceIn(1, 65535), a.smtpSSL)
        smtp.send(
            a.loginName, pass,
            SmtpClient.Outgoing(
                fromEmail = a.email, fromName = a.displayName, toEmail = to,
                subject = subject.ifBlank { "(no subject)" }, body = body
            )
        )
    }
}
