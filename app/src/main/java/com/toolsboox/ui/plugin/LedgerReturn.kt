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

    val isSet: Boolean get() = navActionId != 0

    fun set(navActionId: Int, label: String?) {
        this.navActionId = navActionId
        this.label = label?.takeIf { it.isNotBlank() }
    }

    fun clear() { navActionId = 0; label = null }
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
