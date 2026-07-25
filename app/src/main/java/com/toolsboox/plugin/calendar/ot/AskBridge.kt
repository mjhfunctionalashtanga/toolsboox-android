package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import androidx.core.os.bundleOf
import androidx.navigation.fragment.NavHostFragment
import com.toolsboox.R
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.CardTreatment
import com.toolsboox.ot.LedgerPaths
import com.toolsboox.ot.LedgerUri
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * The universal "send to Ask" bridge.
 *
 * Anything highlighted anywhere in the Ledger — a feed article, a book page, circled ink on the
 * planner — goes to Ask my Ledger KNOWING where it came from: the item's title, its source, and
 * every connection edge already touching it ride along as grounding the model sees (never pasted
 * into the visible question). No selection means "push the whole thing": the item's title becomes
 * the question and the grounding says the whole item was sent.
 *
 * The same provenance can instead be minted as a question gram onto the intake sheet's
 * EDUCATE ME panel (lower-right of the four intake lanes) via [gramToEducateMe] — surfaces offer
 * both as rows of their own existing highlight/media menus, never through an extra chooser.
 */
object AskBridge {

    /**
     * Hilt door to the day service from a plain object. Activity-scoped because Moshi (the
     * service's one dependency) is provided in [com.toolsboox.di.NetworkModule] on the
     * ActivityComponent; every caller hands us a fragment, so the activity is always at hand.
     */
    @EntryPoint
    @InstallIn(ActivityComponent::class)
    interface Services {
        fun calendarDayService(): CalendarDayService
    }

    /**
     * Open Ask my Ledger about [selection] (or the whole item when null/blank), grounded with
     * provenance + connections.
     *
     * @param fragment the surface asking — used for navigation and context only
     * @param selection the highlight; null/blank = "push the whole thing"
     * @param title article/book/page title
     * @param link source url or LedgerUri string ("" if none)
     * @param sourceLabel feed/site/book name ("" if none)
     */
    fun askFrom(
        fragment: com.toolsboox.ui.plugin.ScreenFragment,
        selection: String?,
        title: String,
        link: String,
        sourceLabel: String
    ) {
        val ctx = fragment.context ?: return
        val appCtx = ctx.applicationContext
        // The connection graph is one small cached JSON, but its first read is still disk — build
        // the grounding off the main thread, then navigate in one hop. Single redraw either way.
        Thread {
            val grounding = runCatching {
                buildGrounding(appCtx, selection, title, link, sourceLabel)
            }.getOrDefault("")
            val question = selection?.trim().takeUnless { it.isNullOrEmpty() } ?: title.trim()
            runCatching {
                fragment.requireActivity().runOnUiThread {
                    if (!fragment.isAdded) return@runOnUiThread
                    runCatching {
                        NavHostFragment.findNavController(fragment).navigate(
                            R.id.action_to_ledger_chat,
                            bundleOf(
                                "initial_query" to question,
                                "ask_context" to grounding
                            )
                        )
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Mint the question/highlight as a quote-face gram onto TODAY's intake sheet, inside the
     * EDUCATE ME panel (lower-right lane). Reuses the panel the sheet already draws — no board is
     * created anywhere. Renders and places off the main thread; toasts "🎓 → Educate Me".
     */
    fun gramToEducateMe(
        fragment: com.toolsboox.ui.plugin.ScreenFragment,
        text: String,
        title: String,
        link: String,
        sourceLabel: String
    ) {
        val ctx = fragment.context ?: return
        val activity = fragment.activity ?: return
        val service = EntryPointAccessors.fromActivity(activity, Services::class.java).calendarDayService()
        val root = LedgerPaths.documentsRoot(ctx)
        val face = text.trim().ifBlank { title.trim() }
        if (face.isBlank()) return
        Thread {
            val ok = runCatching {
                // Quote treatment: the highlight/question is the face, the 🎓 chip rides the
                // source line (the renderers are shared and take no chip of their own).
                val provenance = listOf(sourceLabel.trim(), title.trim())
                    .filter { it.isNotBlank() && it != face }.distinct()
                val bmp = QuoteCardRenderer.render(
                    quote = face,
                    source = "🎓  " + (provenance.firstOrNull() ?: "Educate Me"),
                    author = provenance.getOrNull(1),
                    H = 0
                )
                placeOnEducatePanel(service, root, bmp, link, sourceLabel, face)
            }.isSuccess
            runCatching {
                activity.runOnUiThread {
                    if (fragment.isAdded) fragment.showMessage(
                        if (ok) "🎓 → Educate Me" else "Couldn't place that — try again.",
                        fragment.view
                    )
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- grounding -------------------------------------------------------------------------

    /**
     * The "Where this came from:" preamble the chat injects ahead of its corpus excerpts.
     *
     * Provenance (item / source / what was highlighted) plus every live connection edge touching
     * the item — matched by [link] against edge uris AND against the labels either end was called
     * (a feed highlight is usually connected by its article title, not its url). The store is one
     * cached JSON, so this is a filter over memory after the first read.
     *
     * Deliberately NOT here: rhizome thread terms. Every cheap thread source is out of reach —
     * the Roots page's session cache is private to that fragment, and computing threads fresh
     * means walking every day JSON through the corpus service (seconds, cold) before the chat
     * could even open. The chat's own hybrid retrieval brings the selection's corpus neighbours
     * in anyway, so provenance + edges is the honest cheap version.
     */
    private fun buildGrounding(
        context: Context, selection: String?, title: String, link: String, sourceLabel: String
    ): String {
        val sb = StringBuilder("Where this came from:\n")
        if (title.isNotBlank()) sb.append("· Item: ").append(title.trim()).append('\n')
        val src = listOf(sourceLabel.trim(), link.trim()).filter { it.isNotBlank() }.distinct()
        if (src.isNotEmpty()) sb.append("· Source: ").append(src.joinToString(" · ")).append('\n')
        val sel = selection?.trim().orEmpty()
        if (sel.isEmpty()) {
            sb.append("· The reader sent the whole item to Ask.\n")
        } else {
            sb.append("· The reader highlighted: “").append(sel.take(300))
            if (sel.length > 300) sb.append('…')
            sb.append("”\n")
        }

        val edges = runCatching { ConnectionStore.loadAll(context) }.getOrDefault(mutableListOf())
            .asSequence()
            .filter { !it.isDeleted }
            .filter { e ->
                (link.isNotBlank() && (e.from == link || e.to == link ||
                    e.fromLabel.equals(link, true) || e.toLabel.equals(link, true))) ||
                    (title.isNotBlank() && (e.fromLabel.equals(title, true) || e.toLabel.equals(title, true)))
            }
            .sortedByDescending { it.updated }
            .take(6)
            .toList()
        if (edges.isNotEmpty()) {
            sb.append("Connections already in the reader's Ledger touching this item:\n")
            for (e in edges) {
                val from = e.fromLabel.ifBlank { LedgerUri.describe(e.from) }
                val to = e.toLabel.ifBlank { LedgerUri.describe(e.to) }
                sb.append("· ").append(from).append(" —").append(e.kind).append("→ ").append(to)
                if (e.note.isNotBlank()) sb.append(" (").append(e.note).append(')')
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    // ---- Educate Me panel placement ----------------------------------------------------------

    /**
     * Drop a rendered question gram inside the intake sheet's EDUCATE ME panel.
     *
     * Geometry comes from the SAME [CalendarDayPageIntake.panels] the sheet draws and hit-tests
     * with, so there is no second copy of the layout to drift: the usable area is the "educate"
     * panel rect minus its tap-to-type strip ([CalendarDayPageIntake.Companion.IntakePanel.typedRect]).
     * Grams grid-stagger 2-up inside it (the PickingsPlacement discipline, scaled to a panel),
     * wrapping into a slightly-offset pile after six so a busy day reads as a stack, not a spill.
     *
     * Mirrors [PickingsPlacement.place]'s element construction (CardTreatment ground, inline
     * base64 PNG, edgeBaked) under the same [DayLocks] so background placements never race the
     * open day page's per-pen-up save. PickingsPlacement itself takes no position, hence the
     * local twin rather than a call.
     */
    private fun placeOnEducatePanel(
        service: CalendarDayService, root: File, bitmap: Bitmap,
        sourceLink: String, sourceLabel: String, cardText: String
    ) {
        val panel = CalendarDayPageIntake.panels.first { it.kindKey == "educate" }
        // Usable zone: the panel above its dashed tap-to-type strip.
        val inner = RectF(panel.rect.left, panel.rect.top, panel.rect.right, panel.typedRect.top)
        val treated = CardTreatment.card(bitmap)
        val baos = ByteArrayOutputStream()
        treated.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val margin = 18f
        val cols = 2
        var w = (inner.width() - margin * (cols + 1)) / cols          // ≈ 304 on the 1404 canvas
        var h = w * treated.height / treated.width
        // A long question makes a tall card; keep it inside the panel rather than under the strip.
        val maxH = inner.height() - 2 * margin
        if (h > maxH) { h = maxH; w = h * treated.width / treated.height }
        val slotPitch = (inner.height() - 2 * margin) / 3f            // three rows of grid

        val today = LocalDate.now()
        DayLocks.withDay(today) {
            val day = service.load(root, today, null, Locale.getDefault())
            // Stagger by what is already in the panel, not on the whole page — the intake page
            // holds four lanes and their grams must not count against each other.
            val count = day.imageElements.count {
                it.page == "intake" && inner.contains(it.x + it.width / 2f, it.y + it.height / 2f)
            }
            val col = count % cols
            val row = (count / cols) % 3
            val wrap = (count / (cols * 3)) * 16f                     // 7th onward: offset pile
            val x = (inner.left + margin + col * (w + margin) + wrap)
                .coerceIn(inner.left, (inner.right - w).coerceAtLeast(inner.left))
            val y = (inner.top + margin + row * slotPitch + wrap)
                .coerceIn(inner.top, (inner.bottom - h - 8f).coerceAtLeast(inner.top))
            day.imageElements.add(
                ImageElement(
                    x = x, y = y, width = w, height = h, data = base64, page = "intake",
                    sourceLink = sourceLink, sourceLabel = sourceLabel, cardText = cardText,
                    // Wears its CardTreatment in its own pixels — render-time edges stand down.
                    edgeBaked = true
                )
            )
            service.save(root, today, day)
        }
    }
}
