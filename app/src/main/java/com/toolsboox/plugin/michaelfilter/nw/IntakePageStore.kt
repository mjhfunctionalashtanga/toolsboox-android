package com.toolsboox.plugin.michaelfilter.nw

import android.content.Context
import com.squareup.moshi.Moshi
import com.toolsboox.plugin.michaelfilter.da.IntakePageData
import com.toolsboox.plugin.michaelfilter.da.IntakeSubmission
import com.toolsboox.plugin.michaelfilter.ot.ShareTextParser
import timber.log.Timber
import java.io.File
import java.time.LocalDate

/**
 * Persistence + dispatch for the per-day MichaelFilter intake page.
 *
 * Typed panel text is stored as one JSON sidecar per day in
 * filesDir/michaelfilter-intake-pages/intake-YYYY-MM-DD.json (local-only;
 * the server is the system of record once content is dispatched).
 *
 * Dispatch: every URL found in a panel's typed text is enqueued through the
 * shared IntakeQueue with the panel's kind (read|watch|listen|educate).
 * Non-URL text in the Educate Me panel is valid on its own and is sent as a
 * text-only submission. Delivered markers on the data class prevent
 * re-enqueueing unchanged content; the server additionally dedups by URL.
 */
object IntakePageStore {

    private const val TAG = "IntakePageStore"
    private const val DIR_NAME = "michaelfilter-intake-pages"

    /**
     * The four panels in page order: kind key to panel title.
     */
    val PANELS = listOf(
        "read" to "THE READ",
        "watch" to "THE WATCH",
        "listen" to "THE LISTEN",
        "educate" to "EDUCATE ME"
    )

    private val moshi: Moshi = Moshi.Builder().build()

    private fun fileFor(context: Context, date: LocalDate): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, "intake-$date.json")
    }

    /**
     * Load the intake page data of a day (empty data when none saved yet).
     */
    fun load(context: Context, date: LocalDate): IntakePageData {
        val file = fileFor(context, date)
        if (!file.exists()) return IntakePageData()
        return try {
            moshi.adapter(IntakePageData::class.java).fromJson(file.readText(Charsets.UTF_8))
                ?: IntakePageData()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Corrupt intake page file ${file.name}")
            IntakePageData()
        }
    }

    /**
     * Save the intake page data of a day.
     */
    fun save(context: Context, date: LocalDate, data: IntakePageData) {
        try {
            val json = moshi.adapter(IntakePageData::class.java).toJson(data)
            fileFor(context, date).writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save intake page for $date")
        }
    }

    /**
     * File a shared link into a panel (read|watch|listen|educate): append it to that
     * day's typed content, persist, and dispatch (which enqueues it to the pipeline and
     * makes it show up in the Notes & Annotations log). Used by the share-to-file flow.
     */
    fun fileLink(context: Context, date: LocalDate, kind: String, url: String, title: String?) {
        val data = load(context, date)
        val entry = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, url.trim()).joinToString(" — ")
        val existing = data.typedFor(kind).trim()
        data.setTypedFor(kind, if (existing.isEmpty()) entry else "$existing\n$entry")
        save(context, date, data)
        dispatch(context, date, data)
    }

    /**
     * Enqueue all not-yet-delivered typed content through the intake queue.
     * Saves the updated delivered markers and schedules the drain worker when
     * anything new was enqueued.
     *
     * @return the number of newly enqueued submissions
     */
    fun dispatch(context: Context, date: LocalDate, data: IntakePageData): Int {
        var enqueued = 0

        for ((kindKey, _) in PANELS) {
            val typed = data.typedFor(kindKey).trim()
            if (typed.isEmpty()) continue

            val urls = ShareTextParser.extractUrls(typed)

            if (urls.isEmpty()) {
                // Text-only content is valid for Educate Me; the other panels
                // hold their text until a URL shows up in it.
                if (kindKey == "educate" && typed != data.deliveredEducateNote) {
                    val submission = IntakeSubmission(
                        linkUrl = "",
                        linkKind = "educate",
                        pastedBody = typed
                    )
                    if (IntakeQueue.enqueue(context, submission) != null) {
                        data.deliveredEducateNote = typed
                        enqueued++
                    }
                }
                continue
            }

            // Body text = typed text with the URLs stripped out.
            var body = typed
            urls.forEach { body = body.replace(it, "") }
            body = body.trim()

            for (url in urls) {
                if (url in data.deliveredLinkUrls) continue
                val submission = IntakeSubmission(
                    linkUrl = url,
                    linkKind = kindKey,
                    pastedBody = body.takeIf { it.isNotEmpty() }
                )
                if (IntakeQueue.enqueue(context, submission) != null) {
                    data.deliveredLinkUrls.add(url)
                    enqueued++
                }
            }
        }

        if (enqueued > 0) {
            save(context, date, data)
            IntakeQueue.scheduleDrain(context)
            Timber.i("$TAG: Dispatched $enqueued intake submission(s) for $date")
        }

        return enqueued
    }
}
