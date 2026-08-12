package com.toolsboox.plugin.mail

/**
 * Resolve a pending mail-open request (the id out of a `mail://<id>` gram link, or a widget
 * row's deep link) against the ids the local inbox store actually holds.
 *
 * WHY EXACT MATCH ISN'T ENOUGH — Michael's 08-12 punchlist: "When I visit All Stars and select
 * an 'Email' it does not load the email. It gets to the email feed however." The All Stars tap
 * was already carrying the id through [com.toolsboox.plugin.feeds.ui.FeedSelection.pendingMailOpenId]
 * (the same one-shot door the Mail List widget rides), and the lens was consuming it after its
 * warm load — so "gets to the feed, not to the letter" means the id LOOKUP came back empty and
 * the consumer fell through to the plain lens, silently, exactly as its comment said it would.
 *
 * The lookup misses for a structural reason, not a data loss: an inbox id is spelled
 * `acct:<accountRowId>:uid:<imapUid>`, and `<accountRowId>` is the DEVICE-LOCAL row id the
 * account got when it was configured on that device. The iPad and this Android fork configure
 * the SAME mailbox under DIFFERENT row ids — so a gram starred on the iPad (or any day file
 * that synced across) names a letter this device provably holds, under an account prefix this
 * device has never heard of. `onImageSource`'s mail arm has a note accepting that miss
 * ("the pending id simply won't match a letter this device's accounts don't hold"); the
 * punchlist says the miss is not acceptable when the letter IS held.
 *
 * So the resolution is two-step, strictly ordered:
 *  1. EXACT id match — same device, same account row: always right, always first.
 *  2. UID-TAIL match — same `:uid:<n>` tail under a different account prefix. The IMAP UID is
 *     the server's own name for the message within its mailbox, so when exactly ONE held id
 *     carries that tail, it is the same letter arriving under the other device's spelling.
 *     When SEVERAL match (two accounts can each own a uid 4711), we refuse to guess — opening
 *     someone's tax letter because a newsletter shared its uid number is worse than falling
 *     through to the lens — and when NONE match, the letter genuinely isn't here.
 *
 * Pure Kotlin on purpose: the store hands in its id list, this answers with the id to open
 * (or null), and the whole contract is unit-testable without a Context.
 */
object MailOpenResolver {

    /**
     * The held id to open for [pendingId], or null when the store holds no unambiguous match.
     *
     * @param pendingId the id a gram/widget asked to open (with any `mail://` prefix already
     *   stripped by the router)
     * @param heldIds every message id the inbox store currently holds
     */
    fun resolve(pendingId: String, heldIds: List<String>): String? {
        if (pendingId.isBlank()) return null
        heldIds.firstOrNull { it == pendingId }?.let { return it }
        val tail = uidTail(pendingId) ?: return null
        val matches = heldIds.filter { uidTail(it) == tail }
        return if (matches.size == 1) matches[0] else null
    }

    /**
     * The `:uid:<digits>` tail of an inbox id, or null when the id carries none (a `sent:` row,
     * a seed, a malformed link). Digits-only, so a stray colon inside a subject that leaked into
     * an id can never pass as a uid.
     */
    private fun uidTail(id: String): String? {
        val at = id.lastIndexOf(":uid:")
        if (at < 0) return null
        val tail = id.substring(at + ":uid:".length)
        return tail.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    }
}
