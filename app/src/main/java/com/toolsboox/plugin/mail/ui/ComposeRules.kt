package com.toolsboox.plugin.mail.ui

/**
 * The one compose screen's decisions, pure so ComposeRulesTest can hold them (Michael 2026-10-11,
 * Q2). [MailComposeFragment] only gathers the inputs.
 */
object ComposeRules {

    /**
     * THE ONE FROM RULE: a reply to a letter that went THROUGH a site's FluentCRM goes back
     * through the same one; else the letter's own account (a reply goes out the door it came in,
     * or your own letter's follow-up the door it went out of); else the Mail view's account when
     * compose opened from The Mail; else the default pinned by HOLDING From; else the first
     * account. Only doors that still exist count ([known]) — a removed account falls through.
     * The old pad and the old composer each had their own order, and the pad pinned whatever was
     * picked for one letter; this is the only order now, and only a hold pins.
     */
    fun from(
        replyCrm: String?, replyAccount: String?, viewAccount: String?, pinned: String?,
        firstAccount: String?, known: (String) -> Boolean,
    ): String? =
        listOf(replyCrm, replyAccount, viewAccount, pinned)
            .firstOrNull { !it.isNullOrBlank() && known(it) }
            ?: firstAccount

    /** "Re: …" once, never "Re: Re: …"; a blank subject stays blank. */
    fun replySubject(subject: String): String =
        if (subject.lowercase().startsWith("re:") || subject.isBlank()) subject else "Re: $subject"
}
