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
        for (a in accounts) {
            val pass = MailAccountStore.password(context, a.id)
            if (a.imapHost.isBlank() || pass.isBlank()) {
                errors.add("${a.email.ifBlank { "account" }}: needs an IMAP host and password")
                continue
            }
            try {
                val client = ImapClient(a.imapHost, a.imapPort.coerceIn(1, 65535), a.imapSSL)
                val fetched = client.fetchRecent(a.loginName, pass, limit)
                val label = a.displayName.ifBlank { a.email }
                fetched.forEach { f ->
                    all.add(
                        InboxMessage(
                            id = "acct:${a.id}:uid:${f.uid}", account = label,
                            fromName = f.fromName, fromEmail = f.fromEmail, subject = f.subject,
                            snippet = f.body.replace("\n", " ").take(140),
                            body = f.body, date = f.date
                        )
                    )
                }
            } catch (e: Exception) {
                errors.add("${a.email.ifBlank { "account" }}: ${e.message ?: "failed"}")
            }
        }
        InboxStore.setFetched(all)
        return if (errors.isEmpty()) null else errors.joinToString("\n")
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
