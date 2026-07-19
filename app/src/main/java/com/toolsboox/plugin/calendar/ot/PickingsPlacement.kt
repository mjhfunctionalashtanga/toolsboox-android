package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import com.toolsboox.ui.plugin.ScreenFragment
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Drops a rendered gram (bitmap) onto a pickings board — the cross-surface "Add to Pickings" path.
 * Writes an [ImageElement] (inline base64 PNG, same shape as on-canvas image inserts) onto the
 * chosen board's page key in the day JSON, so the card is there when the board is opened.
 */
object PickingsPlacement {
    private const val MAX_DIM = 1200
    private const val CANVAS_W = 1404f
    private const val CANVAS_H = 1872f

    fun place(
        service: CalendarDayService, root: File, bitmap: Bitmap, date: LocalDate, pageKey: String,
        sourceLink: String = "", sourceLabel: String = ""
    ) {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_DIM) {
            val r = MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * r).toInt().coerceAtLeast(1), (bitmap.height * r).toInt().coerceAtLeast(1), true)
        } else bitmap
        val baos = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val w = (CANVAS_W * 0.42f).coerceAtMost(scaled.width.toFloat())
        val h = w * scaled.height / scaled.width
        val day = service.load(root, date, null, Locale.getDefault())
        val count = day.imageElements.count { it.page == pageKey }        // grid-stagger new cards
        val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (CANVAS_W - w).coerceAtLeast(0f))
        val y = (120f + (count / 3) * (h + 30f)).coerceIn(0f, (CANVAS_H - h).coerceAtLeast(0f))
        day.imageElements.add(ImageElement(
            x = x, y = y, width = w, height = h, data = base64, page = pageKey,
            sourceLink = sourceLink, sourceLabel = sourceLabel))
        service.save(root, date, day)
    }

    /** Chooser: today's board · new board · a saved board — then place [bitmap] off the main thread. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmap: Bitmap,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = ""
    ) = chooseAndPlace(fragment, service, root, listOf(bitmap), date, sourceLink, sourceLabel)

    /** Same chooser for SEVERAL cards (a gram series) — one board pick, all placed. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = ""
    ) {
        val bitmap = bitmaps.firstOrNull() ?: return
        val ctx = fragment.requireContext()
        val saved = PickingsStore.list(ctx, date).filter { it.key != PickingsStore.DEFAULT_KEY }
        val labels = (listOf("❝  Today's Pickings", "＋  New pickings…") + saved.map { "❝  ${it.name}" }).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Add to Pickings")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> placeAsync(fragment, service, root, bitmaps, date, PickingsStore.DEFAULT_KEY, "today's Pickings", sourceLink, sourceLabel)
                    1 -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                        val box = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
                        }
                        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("New pickings").setView(box)
                            .setPositiveButton("Create") { _, _ ->
                                val page = PickingsStore.add(ctx, date, input.text.toString().trim())
                                placeAsync(fragment, service, root, bitmaps, date, page.key, page.name, sourceLink, sourceLabel)
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    else -> {
                        val board = saved[which - 2]
                        placeAsync(fragment, service, root, bitmaps, date, board.key, board.name, sourceLink, sourceLabel)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun placeAsync(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate, key: String, name: String, sourceLink: String = "", sourceLabel: String = ""
    ) {
        Thread {
            for (b in bitmaps) runCatching { place(service, root, b, date, key, sourceLink, sourceLabel) }
            val what = if (bitmaps.size > 1) "${bitmaps.size} cards" else "Placed"
            runCatching { fragment.requireActivity().runOnUiThread { fragment.showMessage("$what on $name.") } }
        }.apply { isDaemon = true }.start()
    }
}
