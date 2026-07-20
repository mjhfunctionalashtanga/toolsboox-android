package com.toolsboox.plugin.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.toolsboox.plugin.calendar.nw.LedgerBooking
import com.toolsboox.plugin.calendar.nw.SiteBookingDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.toolsboox.ot.InkPadView

/**
 * One booking, opened from wherever it was surfaced — the timeline, the day page, the almanac.
 *
 * Deliberately a sheet and not a screen: a booking is a dated object like any other, and the four
 * things you'd actually do to one with a pen in your hand are **keep it, cancel it, move it, and
 * write a note on it**. Authoring availability, building event types and editing booking forms
 * stay on the web, where a keyboard already lives. Refusing that scope is the point — it's what
 * keeps the Fluent surfaces from turning Ledger into an admin console.
 *
 * Times arrive UTC ('yyyy-MM-dd HH:mm:ss', as FluentBooking stores them) and are drawn in the
 * device's own zone; anything sent back is converted to UTC on the way out.
 */
object BookingSheet {

    private val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** UTC wire string → device-local date/time. Null when the server sent nothing usable. */
    private fun local(utc: String?): LocalDateTime? = utc?.let {
        runCatching {
            LocalDateTime.parse(it.trim(), WIRE)
                .atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
        }.getOrNull()
    }

    /** Device-local date/time → UTC wire string. */
    private fun wire(dt: LocalDateTime): String =
        dt.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).format(WIRE)

    /** "Tue 28 Jul · 10:30–14:00" — the one line that says when. */
    private fun whenLine(startUtc: String?, endUtc: String?): String {
        val s = local(startUtc) ?: return "Time unknown"
        val e = local(endUtc)
        return DAY.format(s) + "  ·  " + CLOCK.format(s) + (e?.let { "–" + CLOCK.format(it) } ?: "")
    }

    /**
     * Load a booking and show it. Safe to call from any fragment: everything network runs on IO
     * and every failure lands as a quiet line in the sheet rather than a crash.
     */
    fun open(fragment: Fragment, bookingId: Long) {
        val ctx = fragment.requireContext()
        // Swap content INTO a container — setView() after show() doesn't reliably replace
        // the view (same reason SiteBoardsFragment.openDetail does it this way).
        val container = FrameLayout(ctx)
        container.addView(TextView(ctx).apply {
            text = "Loading…"; setPadding(px(ctx, 24), px(ctx, 24), px(ctx, 24), px(ctx, 24))
        })
        val dialog = AlertDialog.Builder(ctx).setView(container).create()
        dialog.show()

        fragment.lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) { LedgerBooking.booking(ctx, bookingId) }
            if (!fragment.isAdded) return@launch
            container.removeAllViews()
            if (detail == null) {
                container.addView(TextView(ctx).apply {
                    text = "Couldn't load the booking."
                    setPadding(px(ctx, 24), px(ctx, 24), px(ctx, 24), px(ctx, 24))
                })
            } else {
                container.addView(detailView(fragment, detail, dialog))
            }
        }
    }

    private fun detailView(fragment: Fragment, d: SiteBookingDetail, dialog: AlertDialog): View {
        val ctx = fragment.requireContext()
        val b = d.booking
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(ctx, 22), px(ctx, 20), px(ctx, 22), px(ctx, 18))
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t; setTextColor(Color.parseColor("#888888")); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
            setPadding(0, px(ctx, 14), 0, px(ctx, 3))
        }
        fun body(t: String) = TextView(ctx).apply { text = t; setTextColor(Color.BLACK); textSize = 15f }

        col.addView(TextView(ctx).apply {
            text = b.title; setTextColor(Color.BLACK); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(ctx).apply {
            text = whenLine(b.startTime, b.endTime)
            setTextColor(Color.parseColor("#333333")); textSize = 15f
            setPadding(0, px(ctx, 5), 0, 0)
        })

        val meta = buildList {
            // "Happening now" / "Starting soon" is worth more than the status word when it's true.
            when (b.ongoing) {
                "happening_now" -> add("● happening now")
                "starting_soon" -> add("● starting soon")
                "recently_happened" -> add("just finished")
                else -> add(d.statusLabel.ifBlank { b.status })
            }
            if (d.guestCount > 1) add("${d.guestCount} people")
            if (b.isLedgr) add("from Ledger")
        }
        col.addView(TextView(ctx).apply {
            text = meta.joinToString("   ·   ")
            setTextColor(Color.parseColor("#666666")); textSize = 13f
            setPadding(0, px(ctx, 4), 0, 0)
        })

        col.addView(label("WHO"))
        col.addView(body(b.person + (b.email?.let { "\n$it" } ?: "") + (d.phone?.let { "\n$it" } ?: "")))

        d.location?.takeIf { it.isNotBlank() }?.let {
            col.addView(label("WHERE"))
            col.addView(body(it))
        }

        d.message?.takeIf { it.isNotBlank() }?.let {
            col.addView(label("WHAT THEY SAID"))
            col.addView(body(it))
        }

        if (d.fields.isNotEmpty()) {
            col.addView(label("FORM"))
            col.addView(body(d.fields.joinToString("\n") { "${it.label}: ${it.value}" }))
        }

        d.cancelReason?.takeIf { it.isNotBlank() }?.let {
            col.addView(label("CANCELLED BECAUSE"))
            col.addView(body(it))
        }

        d.internalNote?.takeIf { it.isNotBlank() }?.let {
            col.addView(label("YOUR NOTES"))
            col.addView(body(it))
        }

        if (d.activities.isNotEmpty()) {
            col.addView(label("TRAIL"))
            for (a in d.activities.take(8)) {
                col.addView(TextView(ctx).apply {
                    text = "•  ${a.title}" + a.createdAt.take(10).takeIf { it.isNotBlank() }?.let { "   ·   $it" }.orEmpty()
                    setTextColor(Color.parseColor("#333333")); textSize = 14f
                    setPadding(0, px(ctx, 4), 0, px(ctx, 1))
                })
            }
        }

        // The four gestures. Cancel and Move are hidden once a booking is settled — there is
        // nothing to do to a cancelled booking but read it and write on it.
        val settled = b.status in setOf("cancelled", "rejected")
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(ctx, 18), 0, 0)
        }
        fun actionButton(text: String, onTap: () -> Unit) = Button(ctx).apply {
            this.text = text; isAllCaps = false; textSize = 13f
            setPadding(px(ctx, 12), 0, px(ctx, 12), 0); minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(ctx, 6) }
            setOnClickListener { onTap() }
        }
        if (b.canMove) actions.addView(actionButton("Move…") { promptMove(fragment, d, dialog) })
        if (!settled) actions.addView(actionButton("Cancel…") { promptCancel(fragment, d, dialog) })
        actions.addView(actionButton("Note…") { promptNote(fragment, d, dialog) })
        col.addView(actions)

        // Say why the button isn't there, once, quietly — rather than leaving a gap.
        if (!settled && !b.canMove) {
            col.addView(TextView(ctx).apply {
                text = "A class booking moves on the web — seats and hosts travel with it."
                setTextColor(Color.parseColor("#888888")); textSize = 12f
                setPadding(px(ctx, 2), px(ctx, 8), 0, 0)
            })
        }

        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, px(ctx, 10), 0, 0)
            addView(Button(ctx).apply {
                text = "Close"; isAllCaps = false
                setOnClickListener { dialog.dismiss() }
            })
        })

        scroll.addView(col)
        return scroll
    }

    /* ---------------------------------------------------------------
     * Cancel — FluentBooking sends the attendee's email itself.
     * ------------------------------------------------------------- */

    private fun promptCancel(fragment: Fragment, d: SiteBookingDetail, parent: AlertDialog) {
        val ctx = fragment.requireContext()
        val input = EditText(ctx).apply {
            hint = "Why? (they'll see this)"
            setPadding(px(ctx, 14), px(ctx, 12), px(ctx, 14), px(ctx, 12))
            minLines = 2
        }
        AlertDialog.Builder(ctx)
            .setTitle("Cancel · ${d.booking.title.take(32)}")
            .setMessage("${d.booking.person} will be emailed.")
            .setView(input)
            .setPositiveButton("Cancel booking") { _, _ ->
                val reason = input.text.toString().trim().ifBlank { null }
                fragment.lifecycleScope.launch {
                    val status = withContext(Dispatchers.IO) {
                        LedgerBooking.cancel(ctx, d.booking.id, reason)
                    }
                    if (!fragment.isAdded) return@launch
                    toast(ctx, status)
                    if (status == "Cancelled") { parent.dismiss(); open(fragment, d.booking.id) }
                }
            }
            .setNegativeButton("Keep it", null)
            .show()
    }

    /* ---------------------------------------------------------------
     * Move — pick from the event's own open times, so a move can't
     * land somewhere the calendar wouldn't have offered anyway.
     * ------------------------------------------------------------- */

    private fun promptMove(fragment: Fragment, d: SiteBookingDetail, parent: AlertDialog) {
        val ctx = fragment.requireContext()
        val from = local(d.booking.startTime)?.toLocalDate()?.toString()
            ?: LocalDateTime.now().toLocalDate().toString()

        fragment.lifecycleScope.launch {
            val slots = withContext(Dispatchers.IO) {
                LedgerBooking.slots(ctx, d.booking.eventId, from, 21)
            }
            if (!fragment.isAdded) return@launch
            if (slots.isEmpty()) {
                toast(ctx, "No open times in the next three weeks")
                return@launch
            }
            // Drop the time it's already at — moving to now is a no-op the server rejects anyway.
            val options = slots.filter { it != d.booking.startTime }.take(60)
            if (options.isEmpty()) {
                toast(ctx, "Nothing open but the time it's already at")
                return@launch
            }
            val labels = options.map { utc ->
                local(utc)?.let { DAY.format(it) + "   " + CLOCK.format(it) } ?: utc
            }.toTypedArray()

            AlertDialog.Builder(ctx)
                .setTitle("Move to…")
                .setItems(labels) { _, which ->
                    val target = options[which]
                    fragment.lifecycleScope.launch {
                        val status = withContext(Dispatchers.IO) {
                            LedgerBooking.reschedule(ctx, d.booking.id, target, null)
                        }
                        if (!fragment.isAdded) return@launch
                        toast(ctx, status)
                        if (status == "Moved") { parent.dismiss(); open(fragment, d.booking.id) }
                    }
                }
                .setNegativeButton("Leave it", null)
                .show()
        }
    }

    /* ---------------------------------------------------------------
     * Note — in your own hand. Lands on the booking AND, when the
     * attendee is a known contact, on the person in the CRM.
     * ------------------------------------------------------------- */

    private fun promptNote(fragment: Fragment, d: SiteBookingDetail, parent: AlertDialog) {
        val ctx = fragment.requireContext()
        val ink = InkPadView(ctx)
        val input = EditText(ctx).apply {
            hint = "…or type a note"
            setPadding(px(ctx, 14), px(ctx, 12), px(ctx, 14), px(ctx, 12)); minLines = 1
        }
        // Actions at the TOP — a writing hand covers bottom buttons — and a bold frame
        // around the pen area so it reads against white on e-ink.
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, 0, 0, px(ctx, 6))
        }
        val inkFrame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(px(ctx, 2), px(ctx, 2), px(ctx, 2), px(ctx, 2))
            addView(ink, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(ctx, 300)))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(ctx, 12), px(ctx, 6), px(ctx, 12), 0)
            addView(actionRow)
            // The same pen toolbar the reply pad has — undo, three inks, fine↔bold.
            addView(InkPadView.penBar(ctx, ink))
            addView(inkFrame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(ctx, 8) })
            addView(TextView(ctx).apply {
                text = "Kept on the booking — and on the person, if you know them."
                setTextColor(Color.parseColor("#888888")); textSize = 12f
                setPadding(px(ctx, 2), px(ctx, 10), 0, px(ctx, 2))
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Note · ${d.booking.person.take(32)}")
            .setView(box)
            .create()

        fun actionBtn(text: String, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text; textSize = 16f; setTextColor(Color.parseColor("#2F6F96"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(ctx, 16), px(ctx, 4), px(ctx, 16), px(ctx, 4))
            setOnClickListener { onTap() }
        }
        actionRow.addView(actionBtn("Clear") { ink.clear(); input.text?.clear() })
        actionRow.addView(actionBtn("Cancel") { dialog.dismiss() })
        actionRow.addView(actionBtn("Save") {
            val text = input.text.toString().trim().ifBlank { null }
            val png = ink.render()?.let { bmp ->
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
                baos.toByteArray()
            }
            if (text == null && png == null) { toast(ctx, "Nothing to send"); return@actionBtn }
            fragment.lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) {
                    LedgerBooking.note(ctx, d.booking.id, text, png)
                }
                if (!fragment.isAdded) return@launch
                toast(ctx, status)
                if (status == "Noted") { dialog.dismiss(); parent.dismiss(); open(fragment, d.booking.id) }
            }
        })

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /* ---------------------------------------------------------------
     * Small shared bits
     * ------------------------------------------------------------- */

    private fun px(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /**
     * A plain pen surface for a dialog. (SiteBoardsFragment, CorrespondenceFragment and
     * MessagesFragment each carry a private copy of this — they could collapse onto this one
     * later; not doing it here to keep this change off those three files.)
     */
}
