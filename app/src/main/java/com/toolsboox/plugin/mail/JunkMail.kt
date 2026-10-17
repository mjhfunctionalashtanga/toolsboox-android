package com.toolsboox.plugin.mail

import java.util.concurrent.ConcurrentHashMap

/**
 * 🚫 JUNK, PER ACCOUNT (Michael 2026-10-11: "Mail accounts also have individual sents, junks,
 * trash. That can be a drop down under each account"). What the account's own Junk / Spam folder
 * holds on its server, read through [ImapClient.fetchJunk] and kept HERE, in memory, for the
 * visit — never folded into [InboxStore]'s fetch window: a junk letter is not mail you were sent
 * to read, and folding it in would put it in 📥 Inbox, Unread and the widgets.
 *
 * Read-only by construction. The IMAP client has no MOVE or COPY verb, so there is no "Not junk"
 * here (nor on iOS); the letters can be read, starred (a local keep), replied to, picked and tagged
 * like any other — but not deleted. "Load the rest" and attachments work: their uid is the Junk
 * folder's, and [MailSync.fetchFull] / [MailSync.attachmentBytes] open that folder read-only for
 * them ([ImapClient.fetchFull] with `junk`), never INBOX.
 *
 * Ids: "junk:<accountId>:uid:<uid>" — iOS's spelling (`MailSync.fetchJunk`), so a pick or a
 * mail:// link made from a Junk letter names it the same way on both apps. The "junk:" prefix keeps
 * it apart from the INBOX letter with the same uid number ("acct:<accountId>:uid:<uid>"), and
 * [MailOpenResolver] never lets one stand in for the other.
 */
object JunkMail {

    /** One account's Junk as last read: the server's folder name (null = the account has none),
     *  its newest letters, and the error the read met (null = clean). */
    data class Folder(
        val accountId: String,
        val name: String?,
        val messages: List<InboxMessage>,
        val error: String?,
        val readAt: Long = System.currentTimeMillis(),
    )

    private const val PREFIX = "junk:"
    private val folders = ConcurrentHashMap<String, Folder>()

    fun idFor(accountId: String, uid: String) = "$PREFIX$accountId:uid:$uid"

    fun isJunk(id: String) = id.startsWith(PREFIX)

    /** The account a Junk id was listed from, or null for any other id. */
    fun accountId(id: String): String? =
        if (!isJunk(id)) null else id.removePrefix(PREFIX).substringBefore(':').ifBlank { null }

    /** The last read of [accountId]'s Junk this session, or null before the first. */
    fun get(accountId: String): Folder? = folders[accountId]

    /** Every letter read out of any Junk this session — a ↩ chip back to one can find it. */
    fun all(): List<InboxMessage> = folders.values.flatMap { it.messages }

    /** Keep a read. A failed read keeps the letters the last good one found (shown with its error). */
    fun put(f: Folder) {
        val prev = folders[f.accountId]
        folders[f.accountId] = if (f.error != null && prev != null) f.copy(name = prev.name, messages = prev.messages) else f
    }

    /** A letter whose rest was just loaded takes its listed copy's place. */
    fun replace(m: InboxMessage) {
        val acct = accountId(m.id) ?: return
        folders.computeIfPresent(acct) { _, f -> f.copy(messages = f.messages.map { if (it.id == m.id) m else it }) }
    }

    /** An account removed: forget its Junk. */
    fun forget(accountId: String) { folders.remove(accountId) }
}
