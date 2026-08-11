package com.toolsboox.plugin.reader.ui

/**
 * The judgment a device makes about a book it doesn't have: fetch it now, or offer it.
 *
 * Pure on purpose — no Context, no network, no prefs. [LibraryHub] gathers the facts (the
 * toggle, the ceiling, the folder's carry mode, whether the network is metered) and this decides,
 * so the decision is unit-testable without an emulator and reads as one sentence in one place.
 *
 * The defaults are chosen so Michael's fleet just works with nothing configured: every folder
 * carries (a book added anywhere appears everywhere), wifi-only is ON (a Palma on LTE does not
 * silently eat a data plan), and the ceiling is 100MB (an audiobook or a scanned atlas waits to
 * be asked for; an EPUB never hits it). Every "no" here is an OFFER, not a refusal — the book
 * still appears on the shelf as a ghost card, and a tap fetches it regardless of policy, because
 * a person asking is the policy.
 */
object LibraryPolicy {

    /** Prefs defaults, named here beside the logic they feed so they can't drift apart. */
    const val DEFAULT_WIFI_ONLY = true
    const val DEFAULT_SIZE_CEILING_BYTES = 100L * 1024 * 1024

    /** Per-folder carry selection. AUTO fetches new arrivals on the sync tick; ON_OPEN waits for
     *  the tap — the OPDS pattern turned against our own hub, for the device that does not want
     *  20GB of PDFs it will never open. */
    enum class Carry(val wire: String, val label: String) {
        AUTO("auto", "Carry: auto — new books download here"),
        ON_OPEN("on-open", "Carry: on-open — books download when tapped");

        companion object {
            fun of(wire: String?): Carry = entries.firstOrNull { it.wire == wire } ?: AUTO
        }
    }

    /** Why a book is or isn't coming down on its own. Not an error taxonomy — the ghost card can
     *  say "waiting for wifi" instead of implying the sync is broken. */
    enum class Decision {
        /** Policy allows: the sync pass downloads it. */
        FETCH,
        /** Would fetch, but the network is metered and the wifi-only toggle is on. */
        WAIT_FOR_WIFI,
        /** Bigger than the ceiling — offered instead, fetched on tap. */
        TOO_BIG,
        /** The folder is set to on-open — offered by selection, fetched on tap. */
        ON_OPEN,
    }

    /**
     * The one judgment, facts in, sentence out.
     *
     * Order matters and is deliberate: carry is checked FIRST because it is the person's explicit
     * selection ("this folder waits"), then size (a property of the book), then the network (a
     * property of the moment). A 2GB audiobook in an on-open folder on LTE is ON_OPEN, not
     * TOO_BIG — the ghost card should cite the reason the person chose over the ones they didn't.
     */
    fun decide(
        sizeBytes: Long,
        carry: Carry,
        wifiOnly: Boolean,
        unmetered: Boolean,
        ceilingBytes: Long = DEFAULT_SIZE_CEILING_BYTES,
    ): Decision = when {
        carry == Carry.ON_OPEN -> Decision.ON_OPEN
        sizeBytes > ceilingBytes -> Decision.TOO_BIG
        wifiOnly && !unmetered -> Decision.WAIT_FOR_WIFI
        else -> Decision.FETCH
    }
}
