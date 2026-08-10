package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.chat.fi.LedgerCorpusService
import java.io.File
import java.time.LocalDate

/**
 * Schedule-occasioned prep — the smallest honest proof of Michael's 2026-08-03 direction:
 * "on schedule: Class, Private appointment. Assets pulled: client info for the private — CRM
 * info, follow up, relevant training notes…" The occasion is the day, the trigger is the
 * schedule; the surfaces that guessed what mattered are gone, and this is what replaces them —
 * prep hung on a moment that actually happens.
 *
 * Deterministic on purpose: the assembly is gathers over his own material (the day's
 * appointments, the rolodex, open tasks, the corpus), not a model's invention. Ask can dress
 * this later; the pull has to be trustworthy first.
 *
 * Nothing is written here. [assemble] returns PROPOSALS; the surface shows them with their
 * assets visible and only an accepted proposal becomes a card — the confirmLedgerItem grammar,
 * applied to the machine's suggestions.
 */
object SchedulePrep {

    /** One appointment's assembled prep: the occasion, who it's with, and what was pulled. */
    data class Proposal(
        val eventText: String,
        val time: String,
        val contactName: String,
        val contactId: String,
        /** The pulled assets, one line each, provenance included — what accept puts in view. */
        val assets: List<String>,
        /** The card an accept mints (stage todo, source="prep", the contact attached). */
        val cardText: String,
    )

    /** How far back the open-task and corpus pulls reach. */
    private const val LOOKBACK_DAYS = 60L

    /**
     * Assemble prep for [day]'s appointments. An appointment is an EVENT item on the day file
     * whose words name someone in the rolodex — the deliberate bar: prep is for a person you
     * are meeting, and an event that names nobody has nothing to pull. Blocking; call from IO.
     */
    fun assemble(
        ctx: Context,
        dayService: CalendarDayService,
        corpusService: LedgerCorpusService,
        root: File,
        day: LocalDate,
    ): List<Proposal> {
        val dayFile = dayFileOf(root, day) ?: return emptyList()
        val events = runCatching { dayService.loadLedgerItems(dayFile) }.getOrNull().orEmpty()
            .filter { it.kind == LedgerItem.Kind.EVENT && it.text.isNotBlank() }
        if (events.isEmpty()) return emptyList()

        val contacts = runCatching { ContactStore.loadAll(ctx) }.getOrNull().orEmpty()
            .filter { it.name.isNotBlank() }
        if (contacts.isEmpty()) return emptyList()

        val out = ArrayList<Proposal>()
        for (ev in events) {
            // First and full names both match, longest name first so "Greg Cooke" beats "Greg".
            val contact = contacts
                .sortedByDescending { it.name.length }
                .firstOrNull { c ->
                    val full = c.name.trim()
                    val first = full.substringBefore(' ')
                    ev.text.contains(full, ignoreCase = true) ||
                        (first.length >= 3 && ev.text.contains(first, ignoreCase = true))
                } ?: continue

            val assets = ArrayList<String>()
            // CRM: how to reach them, straight off the rolodex card.
            if (contact.email.isNotBlank()) assets.add("✉ ${contact.email}")
            if (contact.phone.isNotBlank()) assets.add("☎ ${contact.phone}")

            // The follow-ups owed: open tasks carrying this contact, or naming them.
            for (open in openTasksFor(dayService, root, day, contact.id, contact.name)) {
                assets.add("☐ owed: ${open.item.text.take(80)} (${open.sourceDay})")
                if (assets.size >= 8) break
            }

            // What the ledger last said about them — the training notes and journal lines.
            val name = contact.name.substringBefore(' ')
            runCatching { corpusService.gather(root, Spiral.SCOPE) }.getOrNull().orEmpty()
                .filter { it.text.contains(name, ignoreCase = true) }
                .sortedByDescending { it.date.time }
                .take(3)
                .forEach { snip ->
                    assets.add("✎ ${snip.text.trim().take(100)} — ${snip.citation}")
                }

            if (assets.isEmpty()) continue
            out.add(Proposal(
                eventText = ev.text.trim().take(80),
                time = ev.time.orEmpty(),
                contactName = contact.name,
                contactId = contact.id,
                assets = assets,
                cardText = "Prep · ${contact.name} — ${ev.text.trim().take(60)}",
            ))
        }
        return out
    }

    /** Undone TASKs from the lookback window that carry [contactId] or say [name]. */
    private fun openTasksFor(
        dayService: CalendarDayService, root: File, day: LocalDate,
        contactId: String, name: String,
    ): List<OpenTasks.Open> {
        val out = ArrayList<OpenTasks.Open>()
        var d = day
        val floor = day.minusDays(LOOKBACK_DAYS)
        val first = name.substringBefore(' ')
        while (!d.isBefore(floor) && out.size < 6) {
            dayFileOf(root, d)?.let { f ->
                runCatching { dayService.loadLedgerItems(f) }.getOrNull().orEmpty()
                    .filter { it.kind == LedgerItem.Kind.TASK && !it.done && it.text.isNotBlank() }
                    .filter {
                        it.contactId == contactId ||
                            (first.length >= 3 && it.text.contains(first, ignoreCase = true))
                    }
                    .forEach { if (out.size < 6) out.add(OpenTasks.Open(it, d)) }
            }
            d = d.minusDays(1)
        }
        return out
    }

    private fun dayFileOf(root: File, d: LocalDate): File? {
        val y = "%04d".format(d.year); val m = "%02d".format(d.monthValue); val dd = "%02d".format(d.dayOfMonth)
        val f = File(File(root, "calendar/$y/$m"), "day-$y-$m-$dd-v2.json")
        return if (f.exists()) f else null
    }
}
