package com.toolsboox.ui.plugin

/**
 * A one-tap "return anchor". When you leave an open feed article or a book to jot a note on the Day
 * page, we remember where you were so the Day page can offer an immediate jump back to the exact
 * article / book you were reading. Set by the source surface (via [ReturnAnchorProvider]); consumed
 * when you tap Back on the Day page.
 */
object LedgerReturn {
    /** Nav action to return through (e.g. R.id.action_to_feeds / R.id.action_to_reader). 0 = none. */
    var navActionId: Int = 0
        private set

    /** Chip label — the article or book title. */
    var label: String? = null
        private set

    /** What the ↩ chip arms just before it navigates — the source's own "open this again" (an
     *  article to reopen, a letter in its mailbox). Held HERE, not in the destination's one-shot
     *  channel, so only the chip turns it into a way in: set at leave time, those one-shots used
     *  to hijack the next unrelated arrival (Michael 2026-10-11 review — a gram's Go to feed
     *  landing in The Mail with yesterday's letter springing open). */
    var onReturn: (() -> Unit)? = null
        private set

    val isSet: Boolean get() = navActionId != 0

    fun set(navActionId: Int, label: String?, onReturn: (() -> Unit)? = null) {
        this.navActionId = navActionId
        this.label = label?.takeIf { it.isNotBlank() }
        this.onReturn = onReturn
    }

    fun clear() { navActionId = 0; label = null; onReturn = null }
}

/**
 * Implemented by reader surfaces (feed article, book) so the shared Ledger directory can ask them to
 * stash a [LedgerReturn] anchor right before jumping to the Day page — without the directory needing
 * to know each surface's internals.
 */
interface ReturnAnchorProvider {
    /** Record a return anchor for the currently-open content, or clear it if nothing is open. */
    fun prepareReturnAnchor()
}
