package com.toolsboox.plugin.calendar.ot

import android.graphics.Bitmap
import android.util.Base64
import com.toolsboox.da.ImageElement
import com.toolsboox.ot.CardTreatment
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

    /**
     * What makes a placed card an A/V gram rather than a plain one: the bitmap is its poster
     * frame, and this says what to play when it's tapped. [attachmentId] points at the blob in
     * the day's `avGrams`; [url] is the remote copy for devices that never held it.
     */
    data class MediaRef(
        val kind: String,
        val attachmentId: String = "",
        val url: String = "",
        val durationMs: Int = 0,
        val title: String = "",
        val date: String = ""
    )

    fun place(
        service: CalendarDayService, root: File, bitmap: Bitmap, date: LocalDate, pageKey: String,
        sourceLink: String = "", sourceLabel: String = "", media: MediaRef? = null,
        treatment: Boolean = true, cardText: String = "", sourceFeed: String = ""
    ) {
        val longest = maxOf(bitmap.width, bitmap.height)
        val fitted = if (longest > MAX_DIM) {
            val r = MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * r).toInt().coerceAtLeast(1), (bitmap.height * r).toInt().coerceAtLeast(1), true)
        } else bitmap
        // Give it a paper ground and tape it down. A crop arrives as bare ink on transparency,
        // which reads as marks lying on the page rather than as a card someone placed; the
        // margin and the edge are what make it an object. Baked after the fit so the tape is
        // proportioned to the size it will actually be seen at, and before the measurement
        // below so the frame is inside the card's own aspect ratio.
        val scaled = if (treatment) CardTreatment.card(fitted) else fitted
        val baos = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val w = (CANVAS_W * 0.42f).coerceAtMost(scaled.width.toFloat())
        val h = w * scaled.height / scaled.width
        // Load→mutate→save under the day lock: placements arrive from raw threads (placeAsync)
        // and race the open day page's per-pen-up save; interleaved writers drop each other's items.
        DayLocks.withDay(date) {
            val day = service.load(root, date, null, Locale.getDefault())
            val count = day.imageElements.count { it.page == pageKey }        // grid-stagger new cards
            // The daily board wears its cover band up top; a card staggered behind the band would sit
            // under the recent-board tiles and steal their taps. Start the grid below the band there.
            val yBase = if (pageKey == PickingsStore.DEFAULT_KEY) PickingsCover.CONTENT_TOP + 20f else 120f
            val x = (60f + (count % 3) * (w + 30f)).coerceIn(0f, (CANVAS_W - w).coerceAtLeast(0f))
            val y = (yBase + (count / 3) * (h + 30f)).coerceIn(0f, (CANVAS_H - h).coerceAtLeast(0f))
            day.imageElements.add(ImageElement(
                x = x, y = y, width = w, height = h, data = base64, page = pageKey,
                sourceLink = sourceLink, sourceLabel = sourceLabel,
                mediaKind = media?.kind ?: "",
                attachmentId = media?.attachmentId ?: "",
                mediaUrl = media?.url ?: "",
                durationMs = media?.durationMs ?: 0,
                mediaTitle = media?.title ?: "",
                mediaDate = media?.date ?: "",
                cardText = cardText, sourceFeed = sourceFeed,
                // The one-decoration contract: this card already wears its CardTreatment in its own
                // pixels, so a render-time edge (iOS GramEdge) must stand down for it.
                edgeBaked = treatment))
            service.save(root, date, day)
        }
    }

    /** Chooser: today's board · new board · a saved board — then place [bitmap] off the main thread. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmap: Bitmap,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = ""
    ) = chooseAndPlace(fragment, service, root, listOf(bitmap), date, sourceLink, sourceLabel, media, cardText, sourceFeed)

    /** Same chooser for SEVERAL cards (a gram series) — one board pick, all placed. */
    fun chooseAndPlace(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate = LocalDate.now(), sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = ""
    ) {
        val bitmap = bitmaps.firstOrNull() ?: return
        val ctx = fragment.requireContext()
        val saved = PickingsStore.list(ctx, date).filter { it.key != PickingsStore.DEFAULT_KEY }
        val labels = (listOf("❝  Today's Pickings", "＋  New pickings…") + saved.map { "❝  ${it.name}" }).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx))
            .setTitle("Add to Pickings")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> placeAsync(fragment, service, root, bitmaps, date, PickingsStore.DEFAULT_KEY, "today's Pickings", sourceLink, sourceLabel, media, cardText, sourceFeed)
                    1 -> {
                        val input = android.widget.EditText(ctx).apply { hint = "Pickings name"; setSingleLine() }
                        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                        val box = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(input)
                        }
                        androidx.appcompat.app.AlertDialog.Builder(com.toolsboox.ot.ModalScale.wrap(ctx)).setTitle("New pickings").setView(box)
                            .setPositiveButton("Create") { _, _ ->
                                val page = PickingsStore.add(ctx, date, input.text.toString().trim())
                                placeAsync(fragment, service, root, bitmaps, date, page.key, page.name, sourceLink, sourceLabel, media, cardText, sourceFeed)
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    else -> {
                        val board = saved[which - 2]
                        placeAsync(fragment, service, root, bitmaps, date, board.key, board.name, sourceLink, sourceLabel, media, cardText, sourceFeed)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun placeAsync(
        fragment: ScreenFragment, service: CalendarDayService, root: File, bitmaps: List<Bitmap>,
        date: LocalDate, key: String, name: String, sourceLink: String = "", sourceLabel: String = "",
        media: MediaRef? = null, cardText: String = "", sourceFeed: String = ""
    ) {
        Thread {
            for (b in bitmaps) runCatching { place(service, root, b, date, key, sourceLink, sourceLabel, media, cardText = cardText, sourceFeed = sourceFeed) }
            val what = if (bitmaps.size > 1) "${bitmaps.size} grams" else "Placed"
            // Offer the trip rather than taking it. You were mid-something on the page you
            // circled from, and the usual next move is to put another thing on the same board —
            // so staying is the right default and going is one tap.
            runCatching {
                fragment.requireActivity().runOnUiThread {
                    com.google.android.material.snackbar.Snackbar.make(
                        fragment.requireView(), "$what on $name.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).setAction("Go to it") {
                        com.toolsboox.plugin.calendar.CalendarNavigator.toDayNote(fragment, date, key)
                    }.show()
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
