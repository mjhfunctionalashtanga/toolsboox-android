package com.toolsboox.plugin.mail

import android.content.Context

/** One message in the unified inbox. Mirrors iOS Inbox.swift InboxMessage. */
data class InboxMessage(
    val id: String,
    val account: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val snippet: String,
    val body: String,
    val date: Long,
)

/**
 * The unified inbox's messages and per-message state. Real mail fetched this session by [MailSync]
 * lives in memory (re-fetched on open); star / read / cleared state persists by message id in a
 * plain SharedPreferences (it isn't a secret). Until an account is configured it seeds a few
 * samples so the object model and actions are live on a fresh install. Mirrors iOS InboxStore.
 */
object InboxStore {
    private const val PREFS = "ledger_mail_inbox"
    private const val STAR = "inbox_starred"
    private const val READ = "inbox_read"
    private const val CLEARED = "inbox_cleared"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ids(c: Context, key: String): MutableSet<String> =
        prefs(c).getStringSet(key, emptySet())!!.toMutableSet()   // copy -- the returned set must not be mutated
    private fun setIds(c: Context, key: String, s: Set<String>) = prefs(c).edit().putStringSet(key, s).apply()

    fun isStarred(c: Context, id: String) = ids(c, STAR).contains(id)
    fun isRead(c: Context, id: String) = ids(c, READ).contains(id)

    fun setStarred(c: Context, id: String, v: Boolean) {
        val s = ids(c, STAR); if (v) s.add(id) else s.remove(id); setIds(c, STAR, s)
        if (v) { val cl = ids(c, CLEARED); if (cl.remove(id)) setIds(c, CLEARED, cl) }  // starring rescues from a prior clear
    }

    fun markRead(c: Context, id: String) { val s = ids(c, READ); s.add(id); setIds(c, READ, s) }

    @Volatile private var fetched: List<InboxMessage> = emptyList()

    /** Real mail fetched this session by [MailSync] (in-memory; re-fetched on open). */
    fun setFetched(m: List<InboxMessage>) { fetched = m }

    /** True once at least one email account is configured -- past the seeded-samples phase. */
    fun hasAccounts(c: Context) = MailAccountStore.all(c).isNotEmpty()

    /** Messages, newest first, minus anything cleared away. Real mail when accounts are configured;
     *  the seeded samples only while there are none. */
    fun messages(c: Context): List<InboxMessage> {
        val cleared = ids(c, CLEARED)
        val base = if (hasAccounts(c)) fetched else seeds()
        return base.filter { !cleared.contains(it.id) }.sortedByDescending { it.date }
    }

    /** Sweep the given (already-triaged) messages out of the inbox -- the "clear" pass. The caller
     *  decides WHAT to sweep (what's on screen, minus stars); this just records it. */
    fun clear(c: Context, toClear: Collection<String>) {
        val cl = ids(c, CLEARED); cl.addAll(toClear); setIds(c, CLEARED, cl)
    }

    /** Bring a swept set back -- the Clear snackbar's Undo. */
    fun restore(c: Context, toRestore: Collection<String>) {
        val cl = ids(c, CLEARED); cl.removeAll(toRestore.toSet()); setIds(c, CLEARED, cl)
    }

    private fun seeds(): List<InboxMessage> {
        val now = System.currentTimeMillis()
        return listOf(
            InboxMessage(
                "sample-1", "gmail", "A Student", "student@example.com",
                "Question about the Mysore schedule",
                "Hi Michael, I wanted to ask about the times next month...",
                "Hi Michael,\n\nI wanted to ask about the Mysore schedule for next month -- are the early slots still open?\n\nThanks!",
                now - 3_600_000
            ),
            InboxMessage(
                "sample-2", "gmail", "Studio Booking", "booking@example.com",
                "New booking request",
                "You have a new booking request for a private session...",
                "You have a new booking request for a private session this Saturday at 9am.",
                now - 7_200_000
            ),
            InboxMessage(
                "sample-3", "work", "Newsletter", "news@example.com",
                "This week in Ashtanga",
                "The latest posts and practice notes for this week...",
                "The latest posts and practice notes for this week are up on the site.",
                now - 86_400_000
            ),
        )
    }
}
