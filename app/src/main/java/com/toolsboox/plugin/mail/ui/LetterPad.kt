package com.toolsboox.plugin.mail.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.plugin.mail.InboxMessage
import com.toolsboox.plugin.mail.MailAccount
import com.toolsboox.ui.plugin.ScreenFragment
import java.io.File

/**
 * THE LETTER PAD — a handwritten letter written on a whole screen, not in a dialog's half-height
 * box (Michael, 09-07: "I wish I could make the pad I'm offered on a reply larger for writing, or
 * a simple send to a notes page / correspondence with a big send button, subject line, etc. Like
 * the email export from notes almost is but more obvious, and could use a stationery template per
 * email account for the PNG").
 *
 * So: the screen IS the page. Buttons ride the TOP (the handwriting-panel rule — the writing hand
 * rests below), with one big filled SEND. Under them the letter's lines — From (the account:
 * tap to switch for this letter, HOLD to pin as the default — Michael 2026-10-11), To, Subject (editable; "Re: …" prefilled on a reply)
 * — then the pen bar, then the pad to the bottom edge. The stationery is chosen PER ACCOUNT and
 * remembered per account: Ruled · Grid · Plain · Letterhead. It is painted under the ink on
 * screen and into the PNG, so the reader receives a letter on your stationery, not a scribble.
 *
 * Where it goes: SEND mails the PNG (plus any typed line as the body) and files the letter in
 * Correspondence with the ink on the sent row (the 09-07 ruling: a reply is a letter you sent,
 * not a pick). 📝 puts the same page onto today's Notes as a pick WITHOUT sending, for when the
 * letter is to yourself — an explicit act, never automatic.
 *
 * Since Michael 2026-10-11 (Q2, one compose screen) the page itself lives in
 * [MailComposeFragment], beside the typed composer it used to compete with; this object keeps the
 * stationery (kinds, the per-account memory, the painter) and the [open] door its callers use.
 */
object LetterPad {

    const val RULED = "ruled"
    const val GRID = "grid"
    const val PLAIN = "plain"
    const val LETTERHEAD = "letterhead"
    /** TEXT ONLY (Michael, 09-19: "a text only email option would be good along with the different
     *  templates"): no pad, no paper — a typed body under the same From · To · Subject, sent as an
     *  ordinary text email (no picture attached) so it reads and quotes like any other. Remembered
     *  per account like the stationery kinds. */
    const val TEXT = "text"
    internal val KINDS = listOf(RULED to "Ruled", GRID to "Grid", PLAIN to "Plain", LETTERHEAD to "Letterhead", TEXT to "Text only")

    private const val PREFS = "mail_prefs"

    fun stationeryFor(ctx: Context, accountId: String?): String =
        ctx.getSharedPreferences(PREFS, 0).getString("stationery_${accountId ?: "none"}", RULED) ?: RULED

    fun setStationery(ctx: Context, accountId: String?, kind: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("stationery_${accountId ?: "none"}", kind).apply()
    }

    internal fun label(kind: String) = KINDS.firstOrNull { it.first == kind }?.second ?: "Ruled"

    /**
     * The stationery painter: paper, the letterhead (the account's name and address on the
     * Letterhead kind; To · Re · the date on all of them), then the field — rules, a grid, or
     * nothing. House palette (LinkCardRenderer's paper/ink/muted/rule).
     */
    fun painter(
        ctx: Context, kind: String, account: MailAccount?, toLine: String, reLine: String,
    ): (Canvas, Int, Int) -> Unit {
        val density = ctx.resources.displayMetrics.density
        val stamp = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date())
        return { c, w, h ->
            val pxf = density
            val paper = Paint().apply { color = 0xFFFBF8F1.toInt() }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paper)
            val serif = Typeface.SERIF
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F1F1F.toInt(); typeface = serif; textSize = 15f * pxf }
            val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6A6A6A.toInt(); typeface = serif; textSize = 12f * pxf }
            val rule = Paint().apply { color = 0xFFC8C8C8.toInt(); strokeWidth = 1f * pxf }
            val heavy = Paint().apply { color = 0xFF1F1F1F.toInt(); strokeWidth = 1.5f * pxf }
            val margin = 14f * pxf
            var y = 20f * pxf
            if (kind == LETTERHEAD && account != null) {
                // The account's name large, its address under — the letterhead a printer would set.
                val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF1F1F1F.toInt(); typeface = Typeface.create(serif, Typeface.BOLD); textSize = 22f * pxf
                }
                c.drawText(account.display, margin, y + 6f * pxf, name)
                if (account.email.isNotBlank()) c.drawText(account.email, margin, y + 22f * pxf, muted)
                y += 40f * pxf
            }
            c.drawText(toLine, margin, y, ink)
            val dateW = muted.measureText(stamp)
            c.drawText(stamp, w - margin - dateW, y, muted)
            c.drawText(reLine, margin, y + 16f * pxf, muted)
            c.drawLine(margin, y + 24f * pxf, w - margin, y + 24f * pxf, heavy)
            val top = y + 58f * pxf
            when (kind) {
                RULED, LETTERHEAD -> {
                    var yy = top
                    while (yy < h - 10f * pxf) { c.drawLine(margin, yy, w - margin, yy, rule); yy += 34f * pxf }
                }
                GRID -> {
                    val step = 24f * pxf
                    var yy = top
                    while (yy < h - 10f * pxf) { c.drawLine(margin, yy, w - margin, yy, rule); yy += step }
                    var xx = margin
                    while (xx < w - margin) { c.drawLine(xx, top, xx, h - 10f * pxf, rule); xx += step }
                }
                else -> {}
            }
        }
    }

    /**
     * Open the letter — now THE ONE COMPOSE SCREEN (Michael 2026-10-11, Q2: "one compose screen
     * with pen and keyboard together"). The pad that lived here as a full-screen dialog moved
     * into [MailComposeFragment] whole — pages, pen bar, stationery per account, 📝 onto today's
     * note, SEND threaded and filed with its ink — beside the typed body, contacts, 📎 and the one
     * From rule. This hands off, so every door that opened the pad keeps working unchanged.
     *
     * [replyTo] answers that letter (To, "Re:", its own door out); null = a fresh letter, to
     * [toEmail]/[toName] when it starts from a person. [onSent], [dayService] and [root] are kept
     * for the callers' sake: the screen is a trip now, and the caller's own page rebuilds on the
     * way back (its list, the letter's "↩ You replied"), and it reaches today's note itself.
     */
    @Suppress("UNUSED_PARAMETER")
    fun open(
        fragment: ScreenFragment, replyTo: InboxMessage?, onSent: () -> Unit = {},
        dayService: CalendarDayService? = null, root: File? = null,
        /** A FRESH letter's recipient, when it starts from a person (the rolodex) — blank otherwise. */
        toEmail: String = "", toName: String = "",
    ) {
        MailComposeFragment.open(fragment, replyTo = replyTo, toEmail = toEmail, toName = toName)
    }
}
