package com.toolsboox.plugin.mail

import android.content.Context

/**
 * THE CONVERSATION a letter belongs to — "see what you sent, and the thread" (Michael, 2026-10-10:
 * Mail inside Incoming should work like a real email app: you can see what you sent and the whole
 * reply thread). One shared home for the rules every surface asks the same question with: who a
 * reply goes to and out of which door, what it threads onto, whether a letter was answered, and
 * which letters are one exchange.
 *
 * Threading follows the Message-ID chain (References ∪ In-Reply-To) in both directions. Rows from
 * before this build carry no ids at all — the thousands of cached envelopes and every sent row
 * written so far — and a FluentCRM letter never has one of its own (the site's mailer mints it),
 * so for those it falls back to the same subject with the same other person within 90 days. Two
 * letters that BOTH carry their own ids are never joined by subject — a weekly "Update" from one
 * sender is many conversations, not one — with one exception: a reply naming only ids this device
 * never saw, beside a letter you sent (some providers replace the Message-ID on the way out).
 */
object MailThreads {

    private val ANGLE_ID = Regex("<[^<>\\s]+>")

    /** The Message-IDs inside a header value ("<a@b> <c@d>"), in order. Bare tokens are tolerated
     *  (some senders forget the angle brackets) so a sloppy chain still joins. */
    fun ids(header: String): List<String> {
        if (header.isBlank()) return emptyList()
        val angled = ANGLE_ID.findAll(header).map { it.value }.toList()
        return angled.ifEmpty { header.split(Regex("\\s+")).filter { it.contains('@') } }
    }

    /** References with In-Reply-To folded in (critique C2: clients that send only In-Reply-To
     *  still thread). In-Reply-To is the parent, which References conventionally ends with, so an
     *  id it adds goes on the end — the RFC 5322 §3.6.4 shape a reply's References would take. */
    fun mergeRefs(references: String, inReplyTo: String): String {
        val out = LinkedHashSet(ids(references))
        ids(inReplyTo).forEach { out.add(it) }
        return out.joinToString(" ")
    }

    /** A letter's own Message-ID, cleaned to its "<…>" token (2026-10-10 review: a header with a
     *  trailing comment or odd folding never matched, and went out verbatim in In-Reply-To). */
    fun ownId(m: InboxMessage): String? = ids(m.messageId).firstOrNull()

    /** The letter a reply answers — the last id of its chain (References ends with the parent;
     *  In-Reply-To is folded onto the end, see [mergeRefs]). */
    fun parent(m: InboxMessage): String? = ids(m.references).lastOrNull()

    /** What a reply to [m] says it answers: its Message-ID (null = it has none to name). */
    fun inReplyTo(m: InboxMessage): String? = ownId(m)

    /** How long a chain a reply carries: the first id (the conversation's root) and the newest
     *  19 — the trim mail clients make (RFC 5322 §3.6.4 lets a reply's References shorten), so a
     *  long exchange can't grow the header, or the stored row, without end. */
    private const val MAX_REFS = 20

    /** The chain a reply to [m] carries: its References + its Message-ID — the whole chain, so a
     *  deep thread stays one (and the sent row keeps it, which is how "answered" is known). */
    fun replyRefs(m: InboxMessage): String? {
        val chain = (ids(m.references) + listOfNotNull(ownId(m))).distinct()
        val kept = if (chain.size <= MAX_REFS) chain else listOf(chain.first()) + chain.takeLast(MAX_REFS - 1)
        return kept.joinToString(" ").ifBlank { null }
    }

    /** "sent:<account>:<uuid>" → "<account>" (which is "crm:<host>" for a FluentCRM letter). */
    fun sentAccount(id: String): String? =
        if (!InboxStore.isSent(id)) null
        else id.removePrefix("sent:").substringBeforeLast(':', "").ifBlank { null }

    /** The configured account a reply to [m] goes out of: the one it arrived on, or — following up
     *  your OWN letter (2026-10-10, "reply on a Sent letter is broken") — the one it was sent from. */
    fun replyAccountId(m: InboxMessage): String? =
        MailSync.accountId(m.id)
            ?: sentAccount(m.id)?.takeIf { !it.startsWith(MailSync.CRM_PREFIX) && it != "unknown" }

    /** The site whose FluentCRM a letter went through ("sent:crm:<host>:…"), so its follow-up goes
     *  through the same CRM and lands on the same contact's timeline. */
    fun crmHost(m: InboxMessage): String? =
        sentAccount(m.id)?.takeIf { it.startsWith(MailSync.CRM_PREFIX) }?.removePrefix(MailSync.CRM_PREFIX)

    /** Who a reply goes to: the sender — or, on a letter you sent, the person you sent it to (a
     *  follow-up addressed to yourself was the bug). */
    fun replyAddress(m: InboxMessage): String = if (InboxStore.isSent(m.id)) m.toEmail else m.fromEmail
    fun replyName(m: InboxMessage): String =
        if (InboxStore.isSent(m.id)) m.toName.ifBlank { m.toEmail } else m.fromName.ifBlank { m.fromEmail }

    /** The other person on a letter: the sender of what arrived, the recipient of what you sent. */
    private fun other(m: InboxMessage): String =
        (if (InboxStore.isSent(m.id)) m.toEmail else m.fromEmail).trim().lowercase()

    /**
     * The subject with its reply/forward dressing off — Re:, Fwd:, Fw:, plus AW: / SV: / VS: and
     * counted "Re[2]:" (critique P8). Blank, "(no subject)" and very short subjects come back ""
     * so the fallback never joins strangers on "Hi".
     */
    private val DRESS = Regex("^(re|fwd|fw|aw|sv|vs|wg|tr)(\\[\\d+])?\\s*:\\s*")

    fun subjectKey(subject: String): String {
        var s = subject.trim().lowercase()
        while (true) {
            val stripped = DRESS.replace(s, "").trimStart()
            if (stripped == s) break
            s = stripped
        }
        return if (s.length < 4 || s == "(no subject)") "" else s
    }

    private fun chain(m: InboxMessage): Set<String> = (ids(m.messageId) + ids(m.references)).toSet()

    /**
     * The letters in [m]'s conversation, oldest first, [m] included. Grows outward from [m]: a row
     * joins when its Message-ID is in the chain gathered so far, or its own chain names one of
     * those ids (replies, siblings, parents — both directions). The subject fallback joins a row
     * that shares the subject key and the other person and sits within 90 days of a letter the
     * thread holds BY ITS IDS (or [m] itself) — never of one that came in on subject alone, which
     * would chain a weekly subject's old id-less rows into one long false thread (2026-10-10
     * review) — and only when the pair can't be told apart by ids: one side has no Message-ID of
     * its own (old rows, FluentCRM letters), or one is a reply naming only ids this device never
     * saw and the other is a letter you sent.
     */
    fun of(m: InboxMessage, all: List<InboxMessage>): List<InboxMessage> {
        // Rendered on the main thread over up to ~5,000 rows: the cheap tests (the id chain, the
        // other person) run first, and a subject key is worked out only for a row that already
        // shares a person with the thread — and then only once.
        class Member(
            val m: InboxMessage, val who: String, val key: String,
            /** No Message-ID of its own: pre-build rows, and FluentCRM letters (the site's mailer
             *  mints theirs, so the answer cites an id this device never held). */
            val bare: Boolean,
            /** A reply whose chain names nothing on this device — most often an answer to your
             *  letter whose Message-ID the provider replaced on submission (Outlook.com, by
             *  report: "I use a lot of different mailboxes, not just Gmail", Michael 2026-10-10). */
            val orphan: Boolean,
            val sent: Boolean,
            /** [m] itself or joined by ids — the only members the subject fallback measures from. */
            val core: Boolean,
        )
        val pool = all.filter { it.id != m.id && it.id.isNotBlank() }.map { it to chain(it) }
        val onDevice: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
            HashSet<String>().also { s -> ownId(m)?.let { s.add(it) }; pool.forEach { (x, _) -> ownId(x)?.let { s.add(it) } } }
        }
        fun orphan(x: InboxMessage): Boolean =
            !InboxStore.isSent(x.id) && x.references.isNotBlank() && ids(x.references).none { it in onDevice }
        fun bare(x: InboxMessage) = ownId(x) == null
        val members = LinkedHashMap<String, Member>()
        members[m.id] = Member(m, other(m), subjectKey(m.subject), bare(m), orphan(m), InboxStore.isSent(m.id), core = true)
        val known = HashSet(chain(m))
        val windowMs = 90L * 86_400_000L
        val keys = HashMap<String, String>()
        var grew = true
        var passes = 0
        while (grew && passes < 6) {
            grew = false; passes++
            for ((x, xc) in pool) {
                if (x.id in members) continue
                val byId = xc.isNotEmpty() && xc.any { it in known }
                var key = ""
                val bySubject = !byId && run {
                    val who = other(x)
                    if (who.isEmpty()) return@run false
                    val xBare = bare(x)
                    val xSent = InboxStore.isSent(x.id)
                    // Cheap first: the person and the window, against the core only.
                    val near = members.values.filter { y ->
                        y.core && y.who == who && kotlin.math.abs(y.m.date - x.date) <= windowMs
                    }
                    if (near.isEmpty()) return@run false
                    val xOrphan = !xSent && !xBare && orphan(x)
                    val eligible = near.filter { y ->
                        xBare || y.bare || (xOrphan && y.sent) || (y.orphan && xSent)
                    }
                    if (eligible.isEmpty()) return@run false
                    key = keys.getOrPut(x.id) { subjectKey(x.subject) }
                    key.isNotEmpty() && eligible.any { it.key == key }
                }
                if (byId || bySubject) {
                    if (key.isEmpty()) key = keys.getOrPut(x.id) { subjectKey(x.subject) }
                    val xSent = InboxStore.isSent(x.id)
                    members[x.id] = Member(x, other(x), key, bare(x), !xSent && orphan(x), xSent, core = byId)
                    known.addAll(xc)
                    grew = true
                }
            }
        }
        return members.values.map { it.m }.sortedBy { it.date }
    }

    /**
     * The letter "↩ You replied · date" names: your newest reply in [thread] whose chain ends with
     * [m] — and only when no sent row names it (an old row, a reply from before ids were kept),
     * the first letter you sent after [m] that names nothing else, and only if it went before
     * that person's next letter (2026-10-10 review: a later sent row joined on subject used to
     * say "You replied" on a letter that never was).
     */
    fun yourReplyAfter(m: InboxMessage, thread: List<InboxMessage>): InboxMessage? {
        if (InboxStore.isSent(m.id)) return null
        val mine = thread.filter { InboxStore.isSent(it.id) && it.id != m.id && it.date >= m.date }
        ownId(m)?.let { own -> mine.filter { parent(it) == own }.maxByOrNull { it.date }?.let { return it } }
        val theirNext = thread.filter { !InboxStore.isSent(it.id) && it.id != m.id && it.date > m.date }
            .minOfOrNull { it.date }
        return mine.filter { parent(it) == null && (theirNext == null || it.date < theirNext) }.minByOrNull { it.date }
    }

    /**
     * The keys [isAnswered] looks a letter up by, from your sent rows: each row's PARENT (the
     * letter it answered), and every earlier id of its chain paired with the person it went to —
     * so their own earlier letters in that exchange count as answered too, but in a group thread
     * P's letter isn't "answered" by your reply to Q's (2026-10-10 review).
     */
    fun answeredKeys(sent: List<InboxMessage>): Set<String> {
        val out = HashSet<String>()
        for (s in sent) {
            val chain = ids(s.references)
            if (chain.isEmpty()) continue
            out.add(chain.last())
            val to = s.toEmail.trim().lowercase()
            if (to.isNotEmpty()) chain.forEach { out.add("$it|$to") }
        }
        return out
    }

    /** Was [m] answered? Replied from this device (the REPLIED set) OR named by one of your sent
     *  rows (critique C3; see [answeredKeys]) — so the mark lights by itself once Sent comes from
     *  the server too. */
    fun isAnswered(c: Context, m: InboxMessage): Boolean {
        if (InboxStore.isSent(m.id)) return false
        if (InboxStore.isReplied(c, m.id)) return true
        val own = ownId(m) ?: return false
        val keys = InboxStore.answeredKeys(c)
        return own in keys || "$own|${m.fromEmail.trim().lowercase()}" in keys
    }
}
