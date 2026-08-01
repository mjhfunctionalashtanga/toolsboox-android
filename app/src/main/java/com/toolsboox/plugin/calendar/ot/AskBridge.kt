package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.graphics.Bitmap
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
     * Open Ask my Ledger HOLDING [selection] (or the whole item when null/blank), grounded with
     * provenance + connections, and wait there for a prompt.
     *
     * This used to make the passage BE the question and auto-ask it, which produced the one
     * exchange nobody wants: a highlighted sentence pasted into the box, and an answer to the
     * question "what do you make of this?" that nobody had actually asked. Michael, deciding what
     * the bridge is for: "We can send a highlight or a selected set of items to the ask ai and give
     * it a prompt or use a preset." So the passage now arrives AS a passage — visible in the chat's
     * banner, riding `ask_context` — and the question is the next thing chosen. One tap on a preset
     * chip and it is asked; the old behaviour is roughly the first chip ("What is this?").
     *
     * `initial_query` is deliberately no longer set. It still exists and still auto-asks, because
     * some callers genuinely know the question (ReadingLog's search box, a lasso'd term) — but a
     * highlight is a SUBJECT, not a question, and the bridge's callers are all sending subjects.
     * Every surface that already carried an "Ask about this" row got the new behaviour for free
     * without learning anything about presets.
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
        // What was sent, for the human: the highlight, or the item's title when the whole thing
        // went. The model reads the same text through the grounding; this copy is what the banner
        // shows, so you can see WHICH passage you are about to ask about while you pick the prompt.
        val passage = selection?.trim().takeUnless { it.isNullOrEmpty() } ?: title.trim()
        if (passage.isEmpty()) return
        // The connection graph is one small cached JSON, but its first read is still disk — build
        // the grounding off the main thread, then navigate in one hop. Single redraw either way.
        Thread {
            val grounding = runCatching {
                buildGrounding(appCtx, selection, title, link, sourceLabel)
            }.getOrDefault("")
            runCatching {
                fragment.requireActivity().runOnUiThread {
                    if (!fragment.isAdded) return@runOnUiThread
                    runCatching {
                        NavHostFragment.findNavController(fragment).navigate(
                            R.id.action_to_ledger_chat,
                            bundleOf(
                                "ask_passage" to passage,
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
                    // Tell the surface, THEN say it landed. [DayLocks] in placeOnEducatePanel keeps
                    // this write from interleaving with the open day page's pen-up save, but a lock
                    // only orders the two writes — it cannot tell the page that the day it has been
                    // holding since it loaded is now short a gram. 🎓 is offered from ScreenFragment
                    // itself, so this fires from every surface in the app, the day page included:
                    // without the re-read the question-gram is written and then written over.
                    if (ok && fragment.isAdded) runCatching { fragment.onExternalGramPlaced("intake") }
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
            // "Sent", not "highlighted", and 2000 characters rather than 300. Both were right when
            // the only caller was a reader selection — a sentence or two off a page. The Send /
            // Export sheet now pushes a whole made page through this same door (a Write page, a
            // Synthesize, a text note, a handwritten sheet via OCR), and for that the old line said
            // something untrue about a page nobody highlighted, and 300 characters of an 800-word
            // note is a teaser rather than provenance.
            sb.append("· The reader sent: “").append(sel.take(2000))
            if (sel.length > 2000) sb.append('…')
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
     * Drop a rendered question gram inside the intake sheet's mail band.
     *
     * Geometry comes from the SAME [CalendarDayPageIntake.arrivalFrame] every star lands
     * through, so there is no second copy of the layout to drift: All Stars' bands are sized to
     * their content, and where the next slot sits only the whole day file can answer — which is
     * why the frame is resolved INSIDE the lock, against the day as it stands at that moment.
     *
     * Mirrors [PickingsPlacement.place]'s element construction (CardTreatment ground, inline
     * base64 PNG, edgeBaked) under the same [DayLocks] so background placements never race the
     * open day page's per-pen-up save. PickingsPlacement itself doesn't take a raw day, hence the
     * local twin rather than a call.
     */
    private fun placeOnEducatePanel(
        service: CalendarDayService, root: File, bitmap: Bitmap,
        sourceLink: String, sourceLabel: String, cardText: String
    ) {
        val treated = CardTreatment.card(bitmap)
        val baos = ByteArrayOutputStream()
        treated.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val today = LocalDate.now()
        DayLocks.withDay(today) {
            val day = service.load(root, today, null, Locale.getDefault())
            val frame = CalendarDayPageIntake.arrivalFrame(
                "educate", day, treated.width.toFloat(), treated.height.toFloat()
            ) ?: return@withDay
            day.imageElements.add(
                ImageElement(
                    x = frame.left, y = frame.top, width = frame.width(), height = frame.height(),
                    data = base64, page = "intake",
                    sourceLink = sourceLink, sourceLabel = sourceLabel, cardText = cardText,
                    // The mail band's legacy storage key, so an Educate-me question-gram slots into it.
                    intakeKind = "educate",
                    // Wears its CardTreatment in its own pixels — render-time edges stand down.
                    edgeBaked = true
                )
            )
            service.save(root, today, day)
        }
    }
}
