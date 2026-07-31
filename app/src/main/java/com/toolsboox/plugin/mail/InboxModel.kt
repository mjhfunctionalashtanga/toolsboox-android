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
    /**
     * The sender's ORIGINAL markup, "" when they sent plain text only.
     *
     * Defaulted, and written to JSON only when present, so a starred-mail file written by an older
     * build still reads and an older build still reads one written by this. [body] stays the
     * stripped text — search, snippets and the corpus all want that — and this is what the reader
     * shows when you ask for the letter as it was sent.
     */
    val html: String = "",
    /**
     * Who it went TO. Blank on everything that ARRIVED — an inbox row's recipient is you, and
     * printing that on every line would be noise. It is filled in on the synthetic rows written for
     * mail we SENT ([recordSent], [recordComposed]), because a Sent list that can tell you what you
     * wrote but not who you wrote it to is half a list.
     *
     * Defaulted and written only when present, exactly as [html] is: a sent.json written by an
     * older build still reads here, and an older build still reads one written by this. The key
     * names match iOS `InboxMessage.toName` / `.toEmail` so the two forks describe a sent row the
     * same way.
     */
    val toName: String = "",
    val toEmail: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("account", account)
        .put("fromName", fromName).put("fromEmail", fromEmail)
        .put("subject", subject).put("snippet", snippet).put("body", body)
        .put("date", date).put("truncated", truncated)
        .apply { uid?.let { put("uid", it) } }
        .apply { if (html.isNotBlank()) put("html", html) }
        .apply { if (toName.isNotBlank()) put("toName", toName) }
        .apply { if (toEmail.isNotBlank()) put("toEmail", toEmail) }

    /**
     * The same row with the two BULK fields left out — everything the list needs to draw a line and
     * nothing that costs megabytes. This is the on-disk shape of the download cache's index
     * (`files/mail/inbox.json`); the body and the markup live in a per-message sidecar and are read
     * only when a letter is actually opened. See the note on [InboxStore.warm] for why the split.
     *
     * Deliberately a SUBSET of [toJson]'s keys rather than a new format: [fromJson] reads one of
     * these unchanged (a missing `body`/`html` simply decodes to ""), so nothing that already parses
     * a starred/replied/sent pile needs to learn anything to parse this — including a build that
     * predates the cache entirely and would only ever meet these bytes by accident.
     */
    fun toEnvelopeJson(): JSONObject = toJson().apply { remove("body"); remove("html") }

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
            o.optString("html", ""),                        // pre-html files simply have none
            o.optString("toName", ""),                      // pre-recipient sent rows have no "to"
            o.optString("toEmail", ""),
        )
    }
}

/**
 * The unified inbox's messages and per-message state.
 *
 * Star / read / cleared state persists by message id in a plain SharedPreferences (it isn't a
 * secret). A STARRED, REPLIED-to or SENT message's content persists whole to its own JSON file
 * (`files/mail/starred.json`, `replied.json`, `sent.json` — the
 * [ClippingsStore][com.toolsboox.plugin.calendar.ot.ClippingsStore] sidecar shape, but local to this
 * plugin): those three piles are keep-forever and must survive both the 25-per-account fetch window
 * and a process restart.
 *
 * Everything ELSE you have downloaded — mail you merely received and read — persists too, in the
 * download cache described at [warm]. Until that landed, the fetch window was session-only memory:
 * close the app and every message that wasn't starred, answered or sent was gone until the next
 * IMAP round-trip, which on a Boox that is often offline meant mail was a VIEW ONTO A SERVER rather
 * than something the ledger held. Michael, 07-30: "Mail should store in app." Until an account is
 * configured it seeds a few samples so the object model and actions are live on a fresh install.
 * Mirrors iOS InboxStore (`App/Inbox.swift`), whose `inbox.json` this is the Android idiom of.
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
    //
    // Raised with MAX_MESSAGES below, and it HAD to be: a cap under the retention count meant the
    // device could hold more mail than it could hold state about, and the eviction is arbitrary —
    // so the id it dropped could have been a STAR, which is keep-forever and the one thing here
    // that is never allowed to be lost. Ids are cheap; keep far more of them than mail.
    private const val MAX_IDS = 20_000

    // Retention for NON-keep-forever mail (everything that isn't starred, replied-to or sent): keep
    // the last year and at most ~5,000 rows on device, newest-first, dropping the oldest first. The
    // keep-forever pile (starred ∪ replied ∪ sent, bodies persisted) is NEVER subject to either.
    //
    // A year and 5,000 rather than the 30 days and 500 this shipped with — Michael, 07-30: "mail
    // should be stored on the device". A month is a triage window, not an archive, and mail you
    // neither starred nor answered is still mail you may need to go back for. The iPad moved first
    // (its commit: "keep a year of it on the device, not a month"); these are the same two numbers.
    private const val RETENTION_DAYS = 365L
    private const val MAX_MESSAGES = 5000

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

    /** True for a synthetic sent row — its id prefix, so the bulk-clear guard can skip it without a
     *  file read. Sent rows are keep-forever (see [recordSent]); this is how the UI keeps them. */
    fun isSent(id: String) = id.startsWith("sent:")

    fun setStarred(c: Context, m: InboxMessage, v: Boolean) {
        val s = ids(c, STAR); if (v) s.add(m.id) else s.remove(m.id); setIds(c, STAR, s)
        if (v) { val cl = ids(c, CLEARED); if (cl.remove(m.id)) setIds(c, CLEARED, cl) }  // starring rescues from a prior clear
        // Content follows the star: kept whole on disk while starred, dropped when un-starred.
        //
        // WHOLE, emphatically. A row can now reach here as an ENVELOPE (starred from the list after
        // a restart, when the download cache supplied it), and filing that into the keep pile would
        // record a kept message with no letter in it — durability that stores nothing. Hydrating
        // first costs one sidecar read, and only on the starring branch: un-starring writes no
        // content at all, so it stays free of disk reads for the main-thread `unstar` caller.
        val kept = loadStarred(c)
        kept.removeAll { it.id == m.id }
        if (v) kept.add(hydrate(c, m))
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
        // Hydrated for the same reason starring is: the store may only hold this message's envelope,
        // and a replied pile full of subject lines with no letters under them would satisfy the
        // bookkeeping while losing exactly what the pile is for. Still filed when the body can't be
        // resolved (an image-only message really has none) — keep-more, as this always did, and the
        // id is exempt from retention either way because [keepForeverIds] reads the id set.
        val msg = (fetched.firstOrNull { it.id == id } ?: loadStarred(c).firstOrNull { it.id == id })
            ?.let { hydrate(c, it) }
        if (msg != null) {
            val kept = loadReplied(c)
            if (kept.none { it.id == id }) { kept.add(msg); saveReplied(c, kept) }
        }
    }

    /** Record an OUTGOING reply into the sent pile — a keep-forever event that MIRRORS the replied
     *  pile, but for the text YOU wrote. A synthetic [InboxMessage] carrying your sending identity
     *  (the account the reply goes out AS: display name, from-address), the `Re:` subject, and the
     *  reply body is appended to its own kept pile (`files/mail/sent.json`), so your own words outlive
     *  the fetch window and a restart and stay searchable forever. Sent rows persist purely via this
     *  pile — they carry no live account window and no id set, so nothing ever prunes them. Call OFF
     *  the main thread (it writes a file). */
    fun recordSent(c: Context, original: InboxMessage, replyText: String) {
        // The from-identity is the account the reply is sent AS — resolved exactly as MailSync.sendReply
        // resolves it (accountId embedded in the original's id → the configured account). If that can't
        // be resolved, fall back to the original's account label and a best-effort self name.
        val aid = MailSync.accountId(original.id)
        val a = if (aid != null) MailAccountStore.all(c).firstOrNull { it.id == aid } else null
        val subject = if (original.subject.lowercase().startsWith("re:")) original.subject else "Re: ${original.subject}"
        record(
            c, accountId = aid ?: "unknown", accountLabel = a?.display ?: original.account,
            fromName = a?.displayName ?: "Me", fromEmail = a?.email ?: "",
            // A reply's recipient is whoever wrote the thing you're answering.
            toName = original.fromName, toEmail = original.fromEmail,
            subject = subject, text = replyText
        )
    }

    /**
     * The same, for a message you COMPOSED rather than replied to.
     *
     * Only replies were recorded before, which meant a letter written from the standalone composer
     * (`MailComposeFragment`) left no trace anywhere on the device the moment SMTP accepted it:
     * nothing in the sent pile, nothing in search, nothing in the corpus —
     * the screen said "Sent" and that was the entire record. That is the same loss the retention
     * work was done to stop, and worse: a sent message is the most deliberate thing in a mailbox
     * and the least reconstructable, and this client never APPENDs to the server's Sent folder, so
     * this pile is the only copy that will ever exist. Called on the SUCCESS path only — see
     * [MailSync.sendNew]. Call OFF the main thread (it writes a file).
     */
    fun recordComposed(
        c: Context, accountId: String, accountLabel: String,
        fromName: String, fromEmail: String, to: String, subject: String, body: String
    ) = record(
        c, accountId = accountId, accountLabel = accountLabel,
        fromName = fromName, fromEmail = fromEmail,
        // A composed letter knows the address it went to and nothing else about the person — the
        // "Name <addr>" the composer offered is already reduced to an address by the time it sends.
        toName = "", toEmail = to,
        subject = subject, text = body
    )

    /** The shared write behind both: mint the synthetic row and append it to the sent pile. */
    private fun record(
        c: Context, accountId: String, accountLabel: String,
        fromName: String, fromEmail: String, toName: String, toEmail: String,
        subject: String, text: String
    ) {
        val msg = InboxMessage(
            id = "sent:$accountId:${java.util.UUID.randomUUID()}",
            account = accountLabel,
            fromName = fromName.ifBlank { fromEmail.ifBlank { "Me" } },
            fromEmail = fromEmail,
            subject = subject.ifBlank { "(no subject)" },
            snippet = text.replace("\n", " ").take(140),
            body = text,
            date = System.currentTimeMillis(),
            toName = toName, toEmail = toEmail
        )
        val kept = loadSent(c)
        kept.add(msg)
        saveSent(c, kept)
    }

    /**
     * Swap one message's content in place (the on-demand full fetch landing): the durable store —
     * memory, its index, and its body sidecar — plus the keep piles that hold it, so the whole body
     * survives a restart exactly as the truncated one did.
     *
     * The sidecar write matters more than it looks: loading the rest of a long letter is the single
     * most expensive thing this client does (up to the 24 MB on-demand ceiling), and without it,
     * reopening that message after a restart would silently pay for it again. Call OFF the main
     * thread — [MailSync.fetchFull], its only caller, already is.
     */
    fun replace(c: Context, m: InboxMessage) {
        val merged: List<InboxMessage>
        synchronized(storeLock) {
            // Insert-or-update, not update-only: this also lands a message opened out of a SERVER
            // search, which never passed through a refresh and so was never in the store. Storing
            // what you went to the trouble of finding is the same instinct as storing what arrived.
            val cur = fetched
            merged = if (cur.any { it.id == m.id }) cur.map { if (it.id == m.id) m else it }
            else (cur + m).sortedByDescending { it.date }
            fetched = merged
        }
        persist(c, merged, fresh = listOf(m), overwriteBodies = true)
        val kept = loadStarred(c)
        val i = kept.indexOfFirst { it.id == m.id }
        if (i >= 0) { kept[i] = m; saveStarred(c, kept) }
        val rep = loadReplied(c)
        val j = rep.indexOfFirst { it.id == m.id }
        if (j >= 0) { rep[j] = m; saveReplied(c, rep) }
    }

    // =============================================================================================
    // The download cache — mail you have merely RECEIVED, kept on the device.
    // =============================================================================================
    //
    // WHAT WAS WRONG. `fetched` used to be exactly what its name said: the ~25-per-account window
    // the last IMAP refresh returned, held in a @Volatile field and lost with the process. The
    // retention numbers above (365 days / 5,000) were therefore describing a bound on something
    // that never lived long enough to reach it — they only ever trimmed the union of a live window
    // and the keep piles. The numbers matched iOS; the storage underneath them did not.
    //
    // WHY IT MATTERS HERE. This is an e-ink device that spends most of its life off a network. A
    // mailbox you can only read while the server answers is not in the ledger, it is a window onto
    // somebody else's computer. Michael, 07-30: "Mail should store in app."
    //
    // THE SHAPE, AND WHY IT IS TWO FILES RATHER THAN ONE.
    //
    //   files/mail/inbox.json          — a JSON array of ENVELOPES: id, account, from, subject,
    //                                    snippet, date, truncated, uid, to. No body, no markup.
    //   files/mail/bodies/<key>.json   — one sidecar per message, holding that message's body and
    //                                    (when the sender sent markup) its html. Read ON OPEN.
    //
    // iOS keeps the whole thing — bodies and inline base64 markup included — in a single inbox.json
    // and guards it with a 512 MB "this is damaged, not big" ceiling. That is defensible on an iPad
    // with `JSONDecoder` and a lot of RAM. It is not defensible here, and copying it would have been
    // the easy wrong answer: `org.json` parses from a String, so reading an N-byte file costs 2N
    // bytes of char array before the parser allocates a single object, and the envelopes are ~350
    // bytes each while a newsletter's html is routinely 100 KB and may reach the 24 MB on-demand
    // ceiling. At 5,000 messages the one-file design is a multi-hundred-megabyte read to draw a
    // list of subjects. This fork has been bitten by precisely that twice — an 86 MB day file, and
    // "Pickings decoded 53 MB on the main thread" — and the lesson both times was to stop carrying
    // the bulk through the index. So: the index is small and always read whole; the bulk is
    // addressed by id and read one letter at a time, which is also the only granularity a reader
    // ever asks for.
    //
    // WHAT IT IS NOT. This is a CACHE, in the sense that losing it costs a re-sync and never costs
    // you a message you marked. The keep-forever piles are the durable record; a damaged cache file
    // is discarded rather than quarantined.

    private const val CACHE_DIR = "mail"
    private const val INBOX_FILE = "inbox.json"
    private const val BODIES_DIR = "bodies"

    /** Past this the index is damaged, not big: 5,000 envelopes are ~2 MB, so this is ~15x headroom. */
    private const val INBOX_MAX_BYTES = 32L * 1024 * 1024

    /** Past this a body sidecar is damaged: [ImapClient]'s on-demand ceiling is 24 MB. */
    private const val BODY_MAX_BYTES = 32L * 1024 * 1024

    /**
     * The durable store, in memory: full messages for whatever this session fetched or opened,
     * envelopes for everything else the cache carried in. `messages()` reads it on the render path,
     * so it is swapped wholesale under [storeLock] rather than mutated in place.
     */
    @Volatile private var fetched: List<InboxMessage> = emptyList()

    /** Whether [warm] has folded the cache in. Guards against a re-read and against a warm landing
     *  after a live fetch and quietly overwriting it. */
    @Volatile private var cacheLoaded = false

    private val storeLock = Any()

    /** Every cache write goes here — never on the caller's thread, and serialized, so two refreshes
     *  can't interleave halfway through rewriting the index. */
    private val storeIo: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ledger-mail-store").apply { priority = Thread.MIN_PRIORITY }
        }

    /**
     * Fold the download cache into memory. CALL THIS OFF THE MAIN THREAD — it is the one expensive
     * read in this file.
     *
     * THE CHEAP START IS NOT NEGOTIABLE. The inbox list currently paints instantly because
     * [messages] touches only memory and the small keep piles, and that property is worth as much
     * as durability is: trading "my mail is gone" for "the screen is white for a second" is not a
     * fix, it is a different complaint. So nothing here runs from [messages], and [messages] never
     * blocks on it — an un-warmed store simply resolves to the keep piles, exactly as it did
     * before this existed. The caller renders first from memory and calls this on a worker, then
     * re-renders if it returns true. (iOS reached the same rule from a launch crash and states it
     * as "no mail disk I/O belongs anywhere the first render can reach".)
     *
     * WORST CASE AT 5,000 MESSAGES: one `File.readText` of ~2 MB and one `JSONArray` parse of 5,000
     * flat objects — tens of milliseconds on a Boox, and zero bodies touched. The bodies, which are
     * the hundreds of megabytes, are never read here at all.
     *
     * Failure-proof by construction, like the keep piles: a missing file is an empty cache, an
     * oversized or unparseable one is DELETED (it's a cache — a re-sync rebuilds it) and treated as
     * empty. Idempotent; safe to call from anywhere off-main, including the widget process.
     *
     * @return true when the cache brought in something memory didn't already have — i.e. when the
     *   caller has a reason to re-render.
     */
    fun warm(c: Context): Boolean {
        if (cacheLoaded) return false
        val loaded = readIndex(c)
        synchronized(storeLock) {
            if (cacheLoaded) return false
            cacheLoaded = true
            if (loaded.isEmpty()) return false
            // A live fetch may have landed while this read was in flight. It wins on every id it
            // holds — it is newer, and it carries bodies this index deliberately does not.
            val have = fetched.map { it.id }.toSet()
            val added = loaded.filter { it.id !in have }
            if (added.isEmpty()) return false
            fetched = fetched + added
        }
        return true
    }

    /**
     * A refresh landed. MERGE it into the store — never replace it.
     *
     * This is the whole point of the exercise and the easiest thing to get catastrophically wrong:
     * a fetch returns the server's most recent ~25 per account, so a `fetched = window` assignment
     * (which is what this used to be) would mean the FIRST refresh after durability shipped threw
     * away every older message the cache had just made durable. The failure would look exactly like
     * the bug this was built to fix, and would be blamed on it.
     *
     * The window wins on any id both sides hold — it is the fresher copy. Retention is applied to
     * the merged result on the way to disk (see [persist]). Call OFF the main thread: it reads the
     * cache if [warm] hasn't yet, and it hands a write to [storeIo].
     *
     * @return the merged store, newest first — the caller wants its ids for [prune].
     */
    fun setFetched(c: Context, window: List<InboxMessage>): List<InboxMessage> {
        if (!cacheLoaded) warm(c)   // merge into the WHOLE store, not into whatever memory happened to hold
        val merged: List<InboxMessage>
        synchronized(storeLock) {
            val byId = LinkedHashMap<String, InboxMessage>(fetched.size + window.size)
            for (m in fetched) if (m.id.isNotBlank()) byId[m.id] = m
            for (m in window) if (m.id.isNotBlank()) byId[m.id] = m   // the window is the fresher copy
            merged = byId.values.sortedByDescending { it.date }
            fetched = merged
            cacheLoaded = true
        }
        persist(c, merged, fresh = window, overwriteBodies = false)
        return merged
    }

    /**
     * The whole letter, for a row that may only be an envelope.
     *
     * [messages] hands back what it has: full messages for anything this session fetched or that a
     * keep pile holds, envelopes for everything the download cache carried in. That is the right
     * trade for a LIST — a list draws sender, subject, snippet and date, and a list that had to
     * read 5,000 bodies to draw itself would be the main-thread stall this design exists to avoid.
     * A READER is the one place that needs the rest, and it needs exactly one message's worth, so
     * this is where the sidecar is opened. CALL OFF THE MAIN THREAD.
     *
     * Returns [m] untouched when it already carries its content (the common case — you usually open
     * something you just fetched), so a warm read costs nothing.
     */
    fun hydrate(c: Context, m: InboxMessage): InboxMessage {
        if (m.body.isNotBlank() || m.html.isNotBlank()) return m
        if (m.id.isBlank()) return m
        // The keep piles are already in memory and hold whole messages — ask them before touching
        // the disk. This also covers a replied-to message whose body was back-filled there.
        keepForeverPile(c).firstOrNull { it.id == m.id && (it.body.isNotBlank() || it.html.isNotBlank()) }
            ?.let { return it }
        val stored = readBody(c, m.id) ?: return m
        return m.copy(body = stored.first, html = stored.second)
    }

    /**
     * Every id that is KEPT FOREVER, computed from the ID SETS — never from what the piles happen
     * to contain.
     *
     * This distinction is the whole bug. [markReplied] deliberately records the id even when it
     * cannot resolve a body to persist ("keep more when in doubt", back-filled by a later refresh),
     * so a message you had ANSWERED could be in the REPLIED id set and in no pile at all. Read
     * keep-forever off pile membership and that message is not exempt — and retention ages out the
     * one letter in the mailbox the reader had already engaged with. iOS shipped that bug and fixed
     * it by making `keepForeverIds()` purely the three id sets; Android's read path was fixed the
     * same way earlier today, and the persistence added since must not quietly reintroduce it,
     * because a message evicted from DISK cannot be back-filled by anything.
     *
     * Sent rows have no id set here (unlike iOS, which keeps one): their membership is the `sent:`
     * prefix, which [isSent] reads without a file, and their bodies live in their own pile that
     * nothing ever prunes. They are folded in for symmetry so a caller can ask one question.
     */
    fun keepForeverIds(c: Context): Set<String> {
        val out = HashSet<String>()
        out.addAll(ids(c, STAR))
        out.addAll(ids(c, REPLIED))
        out.addAll(loadSent(c).map { it.id })
        return out
    }

    // ---- The cache on disk. Every entry point below is best-effort: a failure here costs a
    // ---- re-sync and must never cost the caller an exception.

    private fun mailDir(c: Context): File = File(c.filesDir, CACHE_DIR).apply { mkdirs() }
    private fun indexFile(c: Context): File = File(mailDir(c), INBOX_FILE)
    private fun bodiesDir(c: Context): File = File(mailDir(c), BODIES_DIR)

    /**
     * A message id as a filename. Ids are `acct:<accountId>:uid:<uid>` and `sent:<accountId>:<uuid>`
     * — legal bytes on every filesystem this runs on, but colons are a trap on anything that ever
     * copies these files elsewhere, so they are sanitized away. The `hashCode` suffix is what makes
     * the mapping injective again after that flattening (and after the length clamp), so two
     * different messages can never land on one sidecar.
     */
    private fun bodyKey(id: String): String {
        val safe = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").takeLast(72)
        return "$safe-${Integer.toHexString(id.hashCode())}.json"
    }

    private fun readIndex(c: Context): List<InboxMessage> {
        val f = indexFile(c)
        if (!f.exists()) return emptyList()
        return try {
            // Oversized reads as damaged rather than parsed: a multi-hundred-megabyte readText is
            // an OOM, and the retention cap puts the honest size three orders of magnitude below it.
            if (f.length() > INBOX_MAX_BYTES) { discardCache(c); return emptyList() }
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { InboxMessage.fromJson(arr.getJSONObject(it)) }
                .filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            // It's a CACHE. A corrupt keep pile would be quarantined (it's the only copy of kept
            // mail); this one is deleted, because the server can rebuild it and a file that won't
            // parse would otherwise be re-parsed and re-failed on every single launch.
            Timber.w(e, "mail cache index unreadable; discarding")
            discardCache(c)
            emptyList()
        }
    }

    private fun discardCache(c: Context) {
        runCatching { indexFile(c).delete() }
        runCatching { bodiesDir(c).deleteRecursively() }
    }

    /** A message's stored body and markup, or null when there is no sidecar for it. */
    private fun readBody(c: Context, id: String): Pair<String, String>? {
        val f = File(bodiesDir(c), bodyKey(id))
        if (!f.exists()) return null
        return try {
            if (f.length() > BODY_MAX_BYTES) { f.delete(); return null }
            val o = JSONObject(f.readText())
            o.optString("body", "") to o.optString("html", "")
        } catch (e: Exception) {
            Timber.w(e, "mail body sidecar unreadable")
            runCatching { f.delete() }
            null
        }
    }

    /**
     * One message's bulk. Written only for mail that just arrived (or was just completed by an
     * on-demand full fetch) — the store re-persists its index on every refresh, but re-writing
     * thousands of unchanged bodies to do so would turn a refresh into a flash-wear event.
     *
     * The keys are `body` and `html`, the same two [InboxMessage.toJson] uses, so the sidecar is
     * readable by anything that already understands a pile row.
     *
     * [overwrite] is false for a refresh and true for a full fetch. A refresh returns the same ~25
     * messages per account every time it runs, and their content cannot have changed — an IMAP UID
     * names one immutable message — so rewriting those bodies on every pull would be pure flash
     * wear on a device whose storage is not replaceable. A full fetch is the opposite case: its
     * whole purpose is that the stored body was only a slice.
     */
    private fun writeBody(c: Context, m: InboxMessage, overwrite: Boolean) {
        if (m.body.isBlank() && m.html.isBlank()) return
        if (!overwrite && File(bodiesDir(c), bodyKey(m.id)).exists()) return
        try {
            val dir = bodiesDir(c).apply { mkdirs() }
            val o = JSONObject().put("id", m.id).put("body", m.body)
            if (m.html.isNotBlank()) o.put("html", m.html)
            File(dir, bodyKey(m.id)).writeText(o.toString())
        } catch (e: Exception) {
            Timber.w(e, "mail body sidecar save failed")
        }
    }

    /**
     * Apply retention to the store and write it out. Runs on [storeIo], never on the caller's thread.
     *
     * RETENTION LIVES IN TWO PLACES ON PURPOSE, and they are the same policy: [messages] bounds what
     * is SHOWN, this bounds what is STORED. Without the second the file would grow past anything
     * that could ever be displayed; without the first a stale cache could out-show the policy
     * between refreshes. Both read [RETENTION_DAYS] / [MAX_MESSAGES], and both exempt keep-forever
     * by id — see [keepForeverIds] for why that word "id" is load-bearing.
     *
     * @param fresh the messages that just came off the wire; only their bodies are (re)written.
     * @param overwriteBodies see [writeBody] — false for a refresh, true for a full fetch.
     */
    private fun persist(
        c: Context, merged: List<InboxMessage>, fresh: List<InboxMessage>, overwriteBodies: Boolean
    ) {
        storeIo.execute {
            try {
                val keep = keepForeverIds(c)
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
                val retained = ArrayList<InboxMessage>(merged.size)
                val evicted = ArrayList<String>()
                var nonKeep = 0
                // `merged` arrives newest-first, so "past the cap" drops the OLDEST non-kept mail.
                for (m in merged) {
                    if (m.id in keep || isSent(m.id)) { retained.add(m); continue }   // never trimmed
                    if (m.date < cutoff) { evicted.add(m.id); continue }              // aged out
                    if (nonKeep >= MAX_MESSAGES) { evicted.add(m.id); continue }      // past the cap
                    nonKeep++
                    retained.add(m)
                }
                // Memory follows the same bound, so a long-lived process can't drift above what the
                // file holds. Swapped, not mutated: `messages()` reads this from the render thread.
                synchronized(storeLock) { fetched = retained }

                val arr = JSONArray()
                retained.forEach { arr.put(it.toEnvelopeJson()) }
                indexFile(c).writeText(arr.toString())

                val retainedKeys = HashSet<String>(retained.size)
                retained.forEach { retainedKeys.add(bodyKey(it.id)) }
                for (m in fresh) if (bodyKey(m.id) in retainedKeys) writeBody(c, m, overwriteBodies)
                for (id in evicted) runCatching { File(bodiesDir(c), bodyKey(id)).delete() }
                // Self-healing sweep: a write interrupted mid-way (a kill, a full disk) can leave a
                // sidecar whose envelope never made it, and nothing else would ever collect it.
                bodiesDir(c).list()?.forEach { name ->
                    if (name !in retainedKeys) runCatching { File(bodiesDir(c), name).delete() }
                }
            } catch (e: Exception) {
                Timber.w(e, "mail cache save failed")
            }
        }
    }

    /** True once at least one email account is configured -- past the seeded-samples phase. */
    fun hasAccounts(c: Context) = MailAccountStore.all(c).isNotEmpty()

    /**
     * Messages, newest first, minus anything cleared away. Real mail when accounts are configured
     * (the durable store — the download cache as folded in by [warm], updated by every refresh —
     * UNION the keep piles); the seeded samples only while there are none.
     *
     * MEMORY ONLY. This is called from render paths and from the widget's draw, so it never reads a
     * file it hasn't already got: an un-warmed store resolves to the keep piles and the caller
     * re-renders when [warm] lands. Rows sourced from the cache carry no body — see [hydrate].
     */
    fun messages(c: Context): List<InboxMessage> {
        val cleared = ids(c, CLEARED)
        if (!hasAccounts(c)) return seeds().filter { !cleared.contains(it.id) }.sortedByDescending { it.date }
        val live = fetched
        // Keep-forever pile = starred ∪ replied ∪ sent, deduped by id, full bodies. These survive the
        // window, a restart, and BOTH retention bounds below.
        val keptForever = keepForeverPile(c)
        // The EXEMPTION is by id, not by pile membership. [markReplied] deliberately records the id
        // even when it can't resolve a body to persist (keep-more, back-fill on a later refresh), so
        // a pile-only reading of "kept forever" would have let retention age out the one message the
        // reader had already answered — the exact case the keep-forever rule exists for. The union
        // here mirrors iOS `InboxStore.keepForeverIds()`, which is purely the three id sets.
        val keptIds = keptForever.map { it.id }.toSet() + keepForeverIds(c)

        // Fold the store together with the piles. The store wins in general — it holds whatever the
        // last refresh or full fetch produced — EXCEPT when its copy is only an envelope and the
        // pile has the whole letter. That case is routine now rather than exotic: a starred message
        // read back from the download cache after a restart is an envelope, and handing the reader
        // the envelope over the pile's full body would lose the very content starring persisted.
        val byId = LinkedHashMap<String, InboxMessage>(live.size + keptForever.size)
        for (m in live) if (m.id.isNotBlank()) byId[m.id] = m
        for (m in keptForever) {
            val cur = byId[m.id]
            val curIsEmpty = cur != null && cur.body.isBlank() && cur.html.isBlank()
            if (cur == null || (curIsEmpty && (m.body.isNotBlank() || m.html.isNotBlank()))) byId[m.id] = m
        }
        val visible = byId.values.filter { !cleared.contains(it.id) }.sortedByDescending { it.date }

        // Retention, applied ONLY to the non-keep-forever remainder: the last RETENTION_DAYS, then
        // the MAX_MESSAGES cap (keep-forever rows already shown always count first, so the cap only
        // trims the rest). The kept-forever rows themselves are never dropped, even alone past it.
        val kf = visible.filter { it.id in keptIds }
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        var rest = visible.filter { it.id !in keptIds && it.date >= cutoff }   // drop non-kept past the window
        val room = (MAX_MESSAGES - kf.size).coerceAtLeast(0)
        if (rest.size > room) rest = rest.take(room)                          // newest-first: drops the oldest non-kept
        return (kf + rest).sortedByDescending { it.date }
    }

    /** The keep-forever pile: persisted starred, replied, and sent bodies, unioned and deduped by id. */
    private fun keepForeverPile(c: Context): List<InboxMessage> {
        val out = loadStarred(c)
        val seen = out.map { it.id }.toMutableSet()
        for (m in loadReplied(c)) if (seen.add(m.id)) out.add(m)
        for (m in loadSent(c)) if (seen.add(m.id)) out.add(m)
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
     * Drop cleared/read ids for mail that is no longer on the device — they can't resurface, so the
     * sets would otherwise grow forever. Only ids from [okAccounts] (accounts that just fetched
     * cleanly) are eligible: an account whose fetch failed keeps ALL its state, or its cleared
     * mail would come back on the next good refresh. Starred ids stay as long as the star store
     * holds their content (or the id is still present — covers stars from before content
     * persisted); an unbacked star for a message that is gone has nothing left to show and goes too.
     *
     * [liveIds] USED to be the ~25-per-account fetch window, which is why the sets stayed small.
     * [MailSync.refresh] now passes the whole durable store, and it has to: a message you swept
     * away is still on the device, so forgetting that you swept it would put it back on screen at
     * the next refresh — a durability change silently undoing a triage decision.
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

    // The sent-content store: exact mirror of the starred/replied piles above, a separate JSON array
    // so the OUTGOING reply text you wrote is kept forever and searchable alongside the mail you got.

    private fun sentFile(c: Context): File =
        File(c.filesDir, "mail").apply { mkdirs() }.let { File(it, "sent.json") }

    @Volatile private var sentCache: List<InboxMessage>? = null

    private fun loadSent(c: Context): MutableList<InboxMessage> {
        sentCache?.let { return it.toMutableList() }
        val f = sentFile(c)
        val list = if (!f.exists()) mutableListOf() else try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { InboxMessage.fromJson(arr.getJSONObject(it)) }
                .filter { it.id.isNotBlank() }.toMutableList()
        } catch (e: Exception) {
            Timber.w(e, "sent mail read failed")
            mutableListOf<InboxMessage>()
        }
        sentCache = list.toList()
        return list
    }

    private fun saveSent(c: Context, list: List<InboxMessage>) {
        sentCache = list.toList()
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sentFile(c).writeText(arr.toString())
        } catch (e: Exception) {
            Timber.w(e, "sent mail save failed")
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
