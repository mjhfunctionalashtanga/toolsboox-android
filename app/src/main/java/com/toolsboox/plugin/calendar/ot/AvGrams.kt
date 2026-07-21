package com.toolsboox.plugin.calendar.ot

import com.toolsboox.da.Attachment
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Filing a recording into the ledger: onto the day, then onto the board.
 *
 * Everywhere a recording can be made — the page's hold-menu, a reply, the floating pen button —
 * ends here, so a clip is filed the same way no matter which door it came through.
 */
object AvGrams {

    /**
     * Save [att] to [date]'s `avGrams` and drop a card carrying its poster onto the day's board.
     *
     * Blocking; call from IO. Returns whether a card was actually placed — a recording with no
     * readable poster is still saved to the day, it just doesn't get a picture.
     */
    fun file(
        service: CalendarDayService,
        root: File,
        blob: File,
        att: Attachment,
        date: LocalDate = LocalDate.now(),
        title: String = "",
        pageKey: String = PickingsStore.DEFAULT_KEY
    ): Boolean {
        if (!blob.exists()) return false

        val durationMs = att.duration?.let { (it * 1000).toInt() }?.takeIf { it > 0 }
            ?: AvPoster.durationMs(blob)

        val isVideo = att.kind == Attachment.Kind.VIDEO
        val name = title.ifBlank {
            (if (isVideo) "🎥 Video gram · " else "🎤 Audio gram · ") + date
        }

        // The day first: even if the poster fails, the recording must not be left as a loose
        // blob with nothing in the ledger pointing at it.
        runCatching {
            val day = service.load(root, date, null, Locale.getDefault())
            if (day.avGrams.none { it.id == att.id }) {
                day.avGrams.add(att)
                service.save(root, date, day)
            }
        }

        val poster = AvPoster.poster(blob, att.kind, durationMs, name) ?: return false

        return try {
            PickingsPlacement.place(
                service, root, poster, date, pageKey,
                sourceLabel = (if (isVideo) "🎥 Video gram · " else "🎤 Audio gram · ") + date,
                media = PickingsPlacement.MediaRef(
                    kind = if (isVideo) "video" else "audio",
                    attachmentId = att.id,
                    durationMs = durationMs,
                    title = name
                )
            )
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { poster.recycle() }
        }
    }
}
