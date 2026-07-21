package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.da.v2.Connection
import com.toolsboox.plugin.calendar.da.v2.LedgerItem
import java.time.LocalDate
import java.time.ZoneId

/**
 * The connections the app has always had, said in the one way it now has of saying them.
 *
 * Before edges existed, relating two things meant putting a pointer field on one of them —
 * `contactId` on a task, `sourceLink` on a gram, `sourceUrl` on a text box. Those are real
 * connections; they were just phrased so that only one end could be asked.
 *
 * Nothing here rewrites or removes those fields. They keep working exactly as they did, and iOS
 * keeps reading them. This only says the same fact a second way, so the general question can be
 * answered too — the trick [com.toolsboox.da.ImageElement]'s A/V fields used: add, never migrate.
 *
 * Adoption is idempotent, because a [Connection]'s identity comes from what it joins. Running it
 * twice, on two devices, in either order, converges on the same one edge.
 */
object LegacyEdges {

    /**
     * Say a gram's pointers as edges.
     *
     * `sourceLink` is already an address — a `ledger://`, `book://` or `http(s)://` — which is
     * exactly why the scheme convention was worth making explicit rather than inventing a new one.
     */
    fun adopt(context: Context, element: com.toolsboox.da.ImageElement, date: LocalDate): String {
        val me = LedgerUri.element(date.toString(), element.page, element.elementId.toString())
        element.sourceLink.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, it, Connection.SOURCE, element.sourceLabel)
        }
        element.contactId?.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, LedgerUri.contact(it), Connection.ASSIGNED)
        }
        ConnectionStore.connect(context, me, LedgerUri.page(date.toString(), element.page), Connection.PLACED)
        return me
    }

    /** Say a text box's pointers as edges — for a dropped link, the link it has always carried. */
    fun adopt(context: Context, element: com.toolsboox.da.TextElement, date: LocalDate): String {
        val me = LedgerUri.element(date.toString(), element.pageKey, element.elementId.toString())
        element.sourceUrl?.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, it, Connection.SOURCE)
        }
        element.contactId?.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, LedgerUri.contact(it), Connection.ASSIGNED)
        }
        ConnectionStore.connect(context, me, LedgerUri.page(date.toString(), element.pageKey), Connection.PLACED)
        return me
    }

    /**
     * Say a task's existing pointers as edges. Safe to call every time the item is opened.
     *
     * Returns the task's own address, so a caller can adopt and then navigate in one breath.
     */
    fun adopt(context: Context, item: LedgerItem, sourceDay: LocalDate?): String {
        val me = LedgerUri.task(item.id)
        // Its own words, so anything pointing at this task can name it without a disk scan.
        val mine = item.text.take(60)

        // Who it's for.
        item.contactId?.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, LedgerUri.contact(it), Connection.ASSIGNED,
                fromLabel = mine)
        }

        // Where it came from. A task extracted from ink belongs to the page it was written on,
        // which is the connection most worth having and the one nothing recorded.
        val day = sourceDay ?: runCatching {
            item.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
        if (day != null) {
            val page = item.source?.takeIf { it.isNotBlank() && it != "auto" && it != "lasso" && it != "lasso-ai" }
                ?: "default"
            ConnectionStore.connect(context, me, LedgerUri.page(day.toString(), page), Connection.SOURCE,
                fromLabel = mine)
        }

        // What board it sits on, when it sits on one.
        item.board?.takeIf { it.isNotBlank() }?.let {
            ConnectionStore.connect(context, me, LedgerUri.page(
                (day ?: LocalDate.now()).toString(), it), Connection.PLACED, fromLabel = mine)
        }
        return me
    }
}
