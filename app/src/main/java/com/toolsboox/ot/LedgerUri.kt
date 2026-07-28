package com.toolsboox.ot

/**
 * One way to name any object in the ledger.
 *
 * Connections need both ends to be sayable in a single string, and the app already half-had a
 * convention for it — `onImageSource` routes `ledger://`, `book://` and `http(s)://` today. This
 * makes that convention explicit and extends it downward to a single element on a page and
 * sideways to the things that live in sidecars (tasks, contacts, clippings).
 *
 * Deliberately string-shaped rather than a sealed type: these are written into JSON, read by iOS,
 * and must survive a reader that has never heard of a scheme it's being handed. An unknown scheme
 * parses fine and simply doesn't resolve.
 *
 * Forms:
 * ```
 * ledger://2026-07-21/intake              a page
 * ledger://2026-07-21/intake#<elementId>  one element on that page
 * task://<itemId>                         a task or event in the ledger
 * contact://<contactId>
 * clipping://<clippingId>
 * book://<path>                           an open book in the reader
 * https://…                               the world outside
 * ```
 */
object LedgerUri {

    const val SCHEME_LEDGER = "ledger"
    const val SCHEME_TASK = "task"
    const val SCHEME_CONTACT = "contact"
    const val SCHEME_CLIPPING = "clipping"
    const val SCHEME_BOOK = "book"

    /** A handwritten `#hashtag`, first-class in the rhizome — `tag://<tag>` (see LedgerTags). */
    const val SCHEME_TAG = "tag"
    /** An email — parity with iOS, which has had this scheme since mail became an object. */
    const val SCHEME_EMAIL = "email"
    /** A support ticket. */
    const val SCHEME_TICKET = "ticket"
    /** A post in the Field Ledger archive, keyed on the WordPress POST ID — slugs get rewritten,
     *  ids don't, and an edge keyed on a slug would quietly detach from its own post. */
    const val SCHEME_JOURNAL = "journal"
    /** A voice or video recording: the one object in the ledger carrying no text at all. */
    const val SCHEME_AVGRAM = "avgram"
    /** A written note, whose Markdown body may already carry #tags. */
    const val SCHEME_NOTE = "note"
    /** Site objects, host-qualified because the same id exists on every site. */
    const val SCHEME_WPPOST = "wppost"
    const val SCHEME_SITEBOARD = "siteboard"
    const val SCHEME_SITETASK = "sitetask"
    const val SCHEME_COMMUNITY = "community"

    /**
     * A parsed address. [body] is everything between the scheme and the fragment; [fragment] is
     * the part after '#', which for a ledger page is an element id.
     */
    data class Ref(val scheme: String, val body: String, val fragment: String? = null) {

        /** For `ledger://` refs: the date part, or null if this isn't a ledger address. */
        val date: String? get() = if (scheme == SCHEME_LEDGER) body.substringBefore('/', "").ifBlank { null } else null

        /** For `ledger://` refs: the page key, defaulting to the day page when none is given. */
        val pageKey: String?
            get() = if (scheme == SCHEME_LEDGER) body.substringAfter('/', "").ifBlank { "default" } else null

        /** True when this names one element rather than a whole page. */
        val isElement: Boolean get() = !fragment.isNullOrBlank()

        val isWeb: Boolean get() = scheme == "http" || scheme == "https"

        /** True for a `tag://` address — the tag's name is the whole [body]. */
        val isTag: Boolean get() = scheme == SCHEME_TAG

        override fun toString(): String =
            "$scheme://$body" + if (fragment.isNullOrBlank()) "" else "#$fragment"
    }

    fun page(date: String, pageKey: String = "default"): String = "$SCHEME_LEDGER://$date/$pageKey"

    fun element(date: String, pageKey: String, elementId: String): String =
        "${page(date, pageKey)}#$elementId"

    fun task(id: String): String = "$SCHEME_TASK://$id"
    fun contact(id: String): String = "$SCHEME_CONTACT://$id"
    fun journal(id: Int): String = "$SCHEME_JOURNAL://$id"
    fun avGram(id: String): String = "$SCHEME_AVGRAM://$id"
    fun note(id: String): String = "$SCHEME_NOTE://$id"
    fun clipping(id: String): String = "$SCHEME_CLIPPING://$id"
    fun book(path: String): String = "$SCHEME_BOOK://$path"

    /**
     * Parse an address. Returns null for anything that isn't `scheme://body` — including the
     * blank string, so callers can pass a raw field through without checking it first.
     *
     * Note the fragment is split off the END, not the first '#': a web URL is allowed to carry
     * its own fragment, and the last one is the one we added.
     */
    fun parse(uri: String?): Ref? {
        val s = uri?.trim().orEmpty()
        if (s.isEmpty()) return null
        val sep = s.indexOf("://")
        if (sep <= 0) return null
        val scheme = s.substring(0, sep).lowercase()
        if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) return null
        val rest = s.substring(sep + 3)
        if (rest.isEmpty()) return null
        // A ledger/task/contact fragment is ours; a web URL's '#' belongs to the page itself and
        // is left in the body, so the link still opens where it was pointing.
        val hash = if (scheme == "http" || scheme == "https") -1 else rest.lastIndexOf('#')
        return if (hash >= 0) Ref(scheme, rest.substring(0, hash), rest.substring(hash + 1))
        else Ref(scheme, rest)
    }

    /** A short human label for an address — what a connection row shows when it has no title. */
    fun describe(uri: String?): String {
        val ref = parse(uri) ?: return uri.orEmpty()
        return when (ref.scheme) {
            SCHEME_LEDGER -> {
                val page = ref.pageKey.takeUnless { it == "default" }
                (ref.date ?: "a day") + if (page != null) " · $page" else ""
            }
            SCHEME_TAG -> "#" + ref.body
            SCHEME_TASK -> "a task"
            SCHEME_CONTACT -> "a contact"
            SCHEME_CLIPPING -> "a clipping"
            SCHEME_BOOK -> ref.body.substringAfterLast('/')
            else -> if (ref.isWeb) ref.body.substringBefore('/') else ref.toString()
        }
    }
}
