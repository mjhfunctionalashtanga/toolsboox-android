package com.toolsboox.plugin.mail

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

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
    val truncated: Boolean = false,   // oversized on the server; only a bounded slice was fetched
    val uid: Long? = null,            // IMAP UID on the origin server; the handle the on-demand full fetch needs (null = unknown)
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("account", account)
        .put("fromName", fromName).put("fromEmail", fromEmail)
        .put("subject", subject).put("snippet", snippet).put("body", body)
        .put("date", date).put("truncated", truncated)
        .apply { uid?.let { put("uid", it) } }

    companion object {
        fun fromJson(o: JSONObject) = InboxMessage(
            o.optString("id", ""),
            o.optString("account", ""),
            o.optString("fromName", ""),
            o.optString("fromEmail", ""),
            o.optString("subject", ""),
            o.optString("snippet", ""),
            o.optString("body", ""),
            o.optLong("date", 0L),
            o.optBoolean("truncated", false),
            if (o.has("uid")) o.optLong("uid") else null,   // pre-uid starred files stay readable
        )
    }
}

/**
 * The unified inbox's messages and per-message state. Real mail fetched this session by [MailSync]
 * lives in memory (re-fetched on open); star / read / cleared state persists by message id in a
 * plain SharedPreferences (it isn't a secret). A STARRED message's content also persists to a
 * small JSON file (`files/mail/starred.json`, the [ClippingsStore][com.toolsboox.plugin.calendar.ot.ClippingsStore]
 * sidecar shape, but local to this plugin) — starred = kept, so the kept pile must survive both
 * the 25-per-account fetch window and a process restart. Until an account is configured it seeds
 * a few samples so the object model and actions are live on a fresh install. Mirrors iOS InboxStore.
 */
object InboxStore {
    private const val PREFS = "ledger_mail_inbox"
    private const val STAR = "inbox_starred"
    private const val READ = "inbox_read"
    private const val CLEARED = "inbox_cleared"
    private const val REPLIED = "inbox_replied"

    // Backstop cap per id set. The per-refresh prune keeps them near the fetch window's size;
    // string sets carry no order, so eviction past the cap is arbitrary — acceptable for ids
    // whose only cost of loss is a re-surfaced (cleared) or re-bolded (read) row.
    private const val MAX_IDS = 4000

    // Retention for NON-keep-forever mail (everything that isn't starred or replied-to): keep the
    // last 30 days and at most ~500 rows on device, newest-first, dropping the oldest first. The
    // keep-forever pile (starred ∪ replied, bodies persisted) is NEVER subject to either bound.
    private const val RETENTION_DAYS = 30L
    private const val MAX_MESSAGES = 500

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun ids(c: Context, key: String): MutableSet<String> =
        prefs(c).getStringSet(key, emptySet())!!.toMutableSet()   // copy -- the returned set must not be mutated
    private fun setIds(c: Context, key: String, s: Set<String>) {
        val capped: Set<String> = if (s.size > MAX_IDS) s.take(MAX_IDS).toSet() else s
        prefs(c).edit().putStringSet(key, capped).apply()
    }

    fun isStarred(c: Context, id: String) = ids(c, STAR).contains(id)
    fun isRead(c: Context, id: String) = ids(c, READ).contains(id)
    fun isReplied(c: Context, id: String) = ids(c, REPLIED).contains(id)

    fun setStarred(c: Context, m: InboxMessage, v: Boolean) {
        val s = ids(c, STAR); if (v) s.add(m.id) else s.remove(m.id); setIds(c, STAR, s)
        if (v) { val cl = ids(c, CLEARED); if (cl.remove(m.id)) setIds(c, CLEARED, cl) }  // starring rescues from a prior clear
        // Content follows the star: kept whole on disk while starred, dropped when un-starred.
        val kept = loadStarred(c)
        kept.removeAll { it.id == m.id }
        if (v) kept.add(m)
        saveStarred(c, kept)
    }

    fun markRead(c: Context, id: String) { val s = ids(c, READ); s.add(id); setIds(c, READ, s) }

    /** Mark a message replied-to — a keep-forever event that MIRRORS starring: the id joins the
     *  REPLIED set, is rescued from any prior clear, and the whole body is persisted to its own kept
     *  pile (`files/mail/replied.json`) so the mail you answered outlives the fetch window and a
     *  restart, and stays searchable forever. The full message is resolved from the live window (you
     *  reply to something you can see) or the starred pile; if neither has it the id is still recorded
     *  (keep-more), and the next refresh that carries it in the window can back-fill the body. */
    fun markReplied(c: Context, id: String) {
        val s = ids(c, REPLIED); s.add(id); setIds(c, REPLIED, s)
        val cl = ids(c, CLEARED); if (cl.remove(id)) setIds(c, CLEARED, cl)   // replying rescues from a prior clear
        val msg = fetched.firstOrNull { it.id == id } ?: loadStarred(c).firstOrNull { it.id == id }
        if (msg != null) {
            val kept = loadReplied(c)
            if (kept.none { it.id == id }) { kept.add(msg); saveReplied(c, kept) }
        }
    }

    /** Swap one message's content in place (the on-demand full fetch landing): the in-memory
     *  fetch window, and — if the message is starred — the kept pile on disk, so the whole body
     *  survives the window and a restart exactly as the truncated one did. */
    fun replace(c: Context, m: InboxMessage) {
        fetched = fetched.map { if (it.id == m.id) m else it }
        val kept = loadStarred(c)
        val i = kept.indexOfFirst { it.id == m.id }
        if (i >= 0) { kept[i] = m; saveStarred(c, kept) }
        val rep = loadReplied(c)
        val j = rep.indexOfFirst { it.id == m.id }
        if (j >= 0) { rep[j] = m; saveReplied(c, rep) }
    }

    @Volatile private var fetched: List<InboxMessage> = emptyList()

    /** Real mail fetched this session by [MailSync] (in-memory; re-fetched on open). */
    fun setFetched(m: List<InboxMessage>) { fetched = m }

    /** True once at least one email account is configured -- past the seeded-samples phase. */
    fun hasAccounts(c: Context) = MailAccountStore.all(c).isNotEmpty()

    /** Messages, newest first, minus anything cleared away. Real mail when accounts are configured
     *  (the fetch window UNION the persisted starred pile, fresh fetch winning on overlap — so a
     *  starred mail outlives the window and shows before the first refresh); the seeded samples
     *  only while there are none. */
    fun messages(c: Context): List<InboxMessage> {
        val cleared = ids(c, CLEARED)
        if (!hasAccounts(c)) return seeds().filter { !cleared.contains(it.id) }.sortedByDescending { it.date }
        val live = fetched
        val liveIds = live.map { it.id }.toSet()
        // Keep-forever pile = starred ∪ replied, deduped by id, full bodies. These survive the window,
        // a restart, and BOTH retention bounds below.
        val keptForever = keepForeverPile(c)
        val keptIds = keptForever.map { it.id }.toSet()
        val base = live + keptForever.filter { it.id !in liveIds }
        val visible = base.filter { !cleared.contains(it.id) }.sortedByDescending { it.date }

        // Retention, applied ONLY to the non-keep-forever remainder: last 30 days, then a ~500 cap
        // (keep-forever rows already shown always count first, so the cap only trims the rest). The
        // kept-forever rows themselves are never dropped, even if they alone exceed the cap.
        val kf = visible.filter { it.id in keptIds }
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        var rest = visible.filter { it.id !in keptIds && it.date >= cutoff }   // drop non-kept older than 30d
        val room = (MAX_MESSAGES - kf.size).coerceAtLeast(0)
        if (rest.size > room) rest = rest.take(room)                          // newest-first: drops the oldest non-kept
        return (kf + rest).sortedByDescending { it.date }
    }

    /** The keep-forever pile: persisted starred and replied bodies, unioned and deduped by id. */
    private fun keepForeverPile(c: Context): List<InboxMessage> {
        val out = loadStarred(c)
        val seen = out.map { it.id }.toMutableSet()
        for (m in loadReplied(c)) if (seen.add(m.id)) out.add(m)
        return out
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

    /**
     * Drop cleared/read ids that fell out of the fetch window — they can't resurface, so the
     * sets would otherwise grow forever. Only ids from [okAccounts] (accounts that just fetched
     * cleanly) are eligible: an account whose fetch failed keeps ALL its state, or its cleared
     * mail would come back on the next good refresh. Starred ids stay as long as the star store
     * holds their content (or the id is still in the window — covers stars from before content
     * persisted); an unbacked star that left the window has nothing left to show and goes too.
     */
    fun prune(c: Context, okAccounts: Set<String>, liveIds: Set<String>) {
        if (okAccounts.isEmpty()) return
        // Keep-forever = every id whose body is persisted (starred OR replied). Neither the id sets
        // below nor the piles ever drop one of these while its content is on disk.
        val kept = keepForeverPile(c).map { it.id }.toSet()
        fun keep(id: String): Boolean {
            if (id in liveIds || id in kept) return true
            val acct = MailSync.accountId(id)
            return acct != null && acct !in okAccounts
        }
        for (key in listOf(CLEARED, READ)) {
            val s = ids(c, key)
            if (s.retainAll { keep(it) }) setIds(c, key, s)
        }
        // STAR and REPLIED id sets follow the same rule as before: an id stays while backed by
        // persisted content or still in the window; an unbacked one that left a cleanly-fetched
        // account's window has nothing left to show and goes. Content in the piles is untouched.
        val stars = ids(c, STAR)
        if (stars.retainAll { keep(it) }) setIds(c, STAR, stars)
        val replied = ids(c, REPLIED)
        if (replied.retainAll { keep(it) }) setIds(c, REPLIED, replied)
    }

    // The starred-content store: a plain JSON array in the app's files dir. Local by design —
    // star/read/cleared ids don't sync either, and the day-page to-do a star creates DOES.

    private fun starFile(c: Context): File =
        File(c.filesDir, "mail").apply { mkdirs() }.let { File(it, "starred.json") }

    // messages() runs on every render; the file is read once and served from memory after.
    @Volatile private var starredCache: List<InboxMessage>? = null

    private fun loadStarred(c: Context): MutableList<InboxMessage> {
        starredCache?.let { return it.toMutableList() }
        val f = starFile(c)
        val list = if (!f.exists()) mutableListOf() else try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { InboxMessage.fromJson(arr.getJSONObject(it)) }
                .filter { it.id.isNotBlank() }.toMutableList()
        } catch (e: Exception) {
            Timber.w(e, "starred mail read failed")
            mutableListOf<InboxMessage>()
        }
        starredCache = list.toList()
        return list
    }

    private fun saveStarred(c: Context, list: List<InboxMessage>) {
        starredCache = list.toList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            starFile(c).writeText(arr.toString())
        } catch (e: Exception) {
            Timber.w(e, "starred mail save failed")
        }
    }

    // The replied-content store: exact mirror of the starred pile above, a separate JSON array so a
    // message can be starred, replied-to, or both, and its body is kept forever either way.

    private fun repliedFile(c: Context): File =
        File(c.filesDir, "mail").apply { mkdirs() }.let { File(it, "replied.json") }

    @Volatile private var repliedCache: List<InboxMessage>? = null

    private fun loadReplied(c: Context): MutableList<InboxMessage> {
        repliedCache?.let { return it.toMutableList() }
        val f = repliedFile(c)
        val list = if (!f.exists()) mutableListOf() else try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { InboxMessage.fromJson(arr.getJSONObject(it)) }
                .filter { it.id.isNotBlank() }.toMutableList()
        } catch (e: Exception) {
            Timber.w(e, "replied mail read failed")
            mutableListOf<InboxMessage>()
        }
        repliedCache = list.toList()
        return list
    }

    private fun saveReplied(c: Context, list: List<InboxMessage>) {
        repliedCache = list.toList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            repliedFile(c).writeText(arr.toString())
        } catch (e: Exception) {
            Timber.w(e, "replied mail save failed")
        }
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
