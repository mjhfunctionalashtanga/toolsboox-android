package com.toolsboox.ot

/**
 * Where a hub row's second-level fold remembers itself: the "MAIN" preference key for a given
 * row label — "📧  Mail" → hub_sub_open_mail, the Boox twin of the iPad sidebar's
 * sidebar_mail_open. Keyed off the DRAWN label because the label is the only identity a
 * directory row has (rows are plain label→action pairs; there is no id to hang state on).
 * But emoji and spacing are styling, not identity — a re-glyphed icon or a respaced label must
 * not orphan the remembered state — so only letters and digits survive into the slug and every
 * run of anything else collapses to a single underscore. A leading "The" is dressing too:
 * "📧  Mail" became "📧  The Mail" (Michael 2026-10-11: "The Mail" everywhere), and his open
 * mailbox fold must not snap shut over an article.
 */
object HubSubFold {

    fun prefKey(label: String): String {
        val slug = label.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), "_")
            .removePrefix("the_")
        return "hub_sub_open_$slug"
    }
}
