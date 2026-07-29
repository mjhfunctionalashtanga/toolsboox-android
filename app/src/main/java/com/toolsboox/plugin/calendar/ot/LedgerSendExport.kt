package com.toolsboox.plugin.calendar.ot

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.toolsboox.ot.ModalScale
import com.toolsboox.plugin.calendar.nw.LedgerCommunityBridge
import com.toolsboox.plugin.calendar.nw.LedgerEssay
import com.toolsboox.plugin.calendar.nw.LedgerWebBridge
import com.toolsboox.plugin.calendar.nw.WPPublish
import com.toolsboox.ui.plugin.ScreenFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The ONE Send / Export sheet, shared by every making surface — Write, Synthesize, Pickings, Notes
 * and Text Notes. The iPad grew one `SendExportSheet` across the same five; this is its counterpart,
 * and the reason it is one object rather than a method on each fragment is the reason it was one
 * sheet there: seven destinations implemented five times is seven bugs waiting to be fixed in five
 * places, and Write's "Share essay…" had already drifted into offering three of them and no more.
 *
 * Nothing here is new plumbing. The share sheet, the WordPress bridge, the essay email route and
 * the community bridge were all already in the app and are called as they stand; what is new is
 * that all five surfaces can now reach all seven, plus the linked PDF ([LedgerLinkedPdf]), which
 * had no equivalent on Android at all.
 *
 * **WordPress publish is not the same button as WordPress draft**, and the difference is not
 * cosmetic: publishing is what the POSSE cron watches, so a published page is syndicated onward to
 * Bluesky within the hour. That is why it asks before it does it — of the seven, it is the only one
 * that puts something in front of other people without a further step.
 */
object LedgerSendExport {

    /**
     * What is being sent. [bitmap] and [text] are LAZY: rendering a page costs real time on e-ink,
     * and five of the seven destinations never need the picture (or never need the words), so the
     * sheet must be able to open without paying for either.
     */
    class Payload(
        val title: String,
        val text: () -> String,
        val bitmap: () -> Bitmap?,
    )

    fun show(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        val boards = LedgerWebBridge.config(ctx)
        val community = LedgerCommunityBridge.config(ctx)

        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows.add("📝  Plain text" to { sharePlainText(fragment, payload) })
        rows.add("🖼  PNG" to { sharePng(fragment, payload) })
        rows.add("🔗  Linked PDF" to { shareLinkedPdf(fragment, payload) })

        // The three site destinations need creds. Offering a row that can only ever say "not
        // configured" is how a menu teaches you to distrust it, so they appear only when they work.
        val mailSite = when {
            boards.ready -> boards
            community.ready -> LedgerWebBridge.Config(community.site, community.user, community.pass, 0)
            else -> null
        }
        if (mailSite != null) rows.add("✉  Email…" to { promptEmail(fragment, payload, mailSite) })
        if (WPPublish.configured(ctx)) {
            rows.add("📄  WordPress draft" to { sendToWordPress(fragment, payload, publish = false) })
            rows.add("🌐  WordPress publish…" to { confirmPublish(fragment, payload) })
        }
        if (community.ready) rows.add("👥  Community space…" to { pickSpace(fragment, payload) })

        fragment.showModal(
            AlertDialog.Builder(ModalScale.wrap(ctx))
                .setTitle("Send / Export")
                .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    /* -----------------------------------------------------------------------------------
     * Off the device
     * --------------------------------------------------------------------------------- */

    private fun sharePlainText(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            // IO and not Default: on a handwritten page the text is produced by the vision model,
            // so "give me the words" is a network call however plain the destination is.
            val text = withContext(Dispatchers.IO) { payload.text() }
            if (text.isBlank()) { toast(ctx, "Nothing typed or recognised on this page"); return@launch }
            runCatching {
                ctx.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, payload.title)
                        .putExtra(Intent.EXTRA_TEXT, text),
                    "Send text"
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }.onFailure { toast(ctx, "Nothing on this device can take text") }
        }
    }

    private fun sharePng(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) { pngOf(payload) }
            if (bytes == null) { toast(ctx, "This page is empty"); return@launch }
            val file = File(exportsDir(ctx), safeName(payload.title) + ".png")
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            shareFile(ctx, file, "image/png", "Send page")
        }
    }

    /**
     * The linked PDF. Runs the vision model once, for two things at once — the page's text (which
     * is where the URLs come from) and where each URL sits — and then reports back what the export
     * actually turned out to be, because [LedgerLinkedPdf] is allowed to fail to annotate and the
     * user must not be told "linked" when it did.
     */
    private fun shareLinkedPdf(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        toast(ctx, "Building the PDF…")
        fragment.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) { payload.bitmap() }
            if (bmp == null) { toast(ctx, "This page is empty"); return@launch }
            val result = withContext(Dispatchers.IO) {
                val text = payload.text()
                val urls = LedgerLinkedPdf.urlsIn(text).toMutableList()
                var boxes: Map<String, android.graphics.RectF> = emptyMap()
                val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(ctx)
                if (creds != null) {
                    val model = com.toolsboox.ui.plugin.OcrModel.override(ctx, creds.first) ?: creds.third
                    boxes = com.toolsboox.plugin.calendar.nw.VisionOcr
                        .recognizeUrlBoxes(bmp, creds.first, creds.second, model)
                    // The model reads addresses off the page that the typed text never held —
                    // a URL written by hand exists nowhere else — so its finds join the list.
                    for (u in boxes.keys) if (u !in urls) urls.add(u)
                }
                LedgerLinkedPdf.render(
                    ctx, payload.title, bmp, urls, boxes,
                    File(exportsDir(ctx), safeName(payload.title) + ".pdf")
                )
            }
            bmp.recycle()
            toast(
                ctx,
                when {
                    result.annotated -> "PDF · ${result.linkCount} tappable link(s)"
                    result.linkCount > 0 -> "PDF · links listed on the index page (not tappable)"
                    else -> "PDF · no links found on this page"
                }
            )
            shareFile(ctx, result.file, "application/pdf", "Send PDF")
        }
    }

    /* -----------------------------------------------------------------------------------
     * Onto a site
     * --------------------------------------------------------------------------------- */

    private fun promptEmail(fragment: ScreenFragment, payload: Payload, site: LedgerWebBridge.Config) {
        val ctx = fragment.requireContext()
        val toIn = EditText(ctx).apply {
            hint = "to@example.com"; setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(toIn)
        }
        fragment.showModal(
            AlertDialog.Builder(ModalScale.wrap(ctx))
                .setTitle("Email this page")
                .setView(box)
                .setPositiveButton("Send") { _, _ ->
                    val to = toIn.text.toString().trim()
                    if (to.isBlank()) return@setPositiveButton
                    toast(ctx, "Sending…")
                    fragment.lifecycleScope.launch {
                        // The site's essay route composes the letter: the page image inline, the
                        // recognised/typed text and its links below it as real text. Sending it
                        // from here rather than from a local SMTP client is what keeps the mail
                        // looking the same whichever device it left from.
                        val status = withContext(Dispatchers.IO) {
                            LedgerEssay.send(
                                site.site, site.user, site.pass, "email",
                                payload.title, "", payload.text(), to, pngOf(payload)
                            )
                        }
                        toast(ctx, status)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    /** Publishing syndicates onward, so it asks first. Draft does not, so it does not. */
    private fun confirmPublish(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.showModal(
            AlertDialog.Builder(ModalScale.wrap(ctx))
                .setTitle("Publish “${payload.title.take(40)}”?")
                .setMessage(
                    "It goes live on the site immediately, and the POSSE cron syndicates it to " +
                        "Bluesky from there. Save it as a draft instead if you want to read it once more first."
                )
                .setPositiveButton("Publish") { _, _ -> sendToWordPress(fragment, payload, publish = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    private fun sendToWordPress(fragment: ScreenFragment, payload: Payload, publish: Boolean) {
        val ctx = fragment.requireContext()
        toast(ctx, if (publish) "Publishing…" else "Saving draft…")
        fragment.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val png = pngOf(payload)
                // The page image becomes the featured image — the same role it plays when a gram
                // is published from the Publish screen, so a post made here looks like the others.
                val media = png?.let { WPPublish.uploadMedia(ctx, it, payload.title) } ?: 0
                val text = payload.text()
                WPPublish.save(ctx, WPPublish.Draft(
                    title = payload.title.ifBlank { "Untitled" },
                    content = htmlBody(text),
                    status = if (publish) "publish" else "draft",
                    featuredMedia = media,
                ))
            }
            toast(ctx, when {
                result.ok && publish -> "Published"
                result.ok -> "Draft created"
                else -> result.error ?: "WordPress refused it"
            })
        }
    }

    /**
     * Paragraphs, with bare URLs turned into real anchors. Handwriting recognised off a page
     * arrives as plain text — a URL in it is a string, and pasted into a post it stays a string
     * unless something makes it a link. The same job the PDF's annotations do, in the medium that
     * has an easier answer for it.
     */
    private fun htmlBody(text: String): String {
        if (text.isBlank()) return ""
        val linked = Regex("""https?://\S+""").replace(text) { m ->
            val raw = m.value
            val trail = raw.takeLastWhile { it in ".,)]>;\"'" }
            val url = raw.dropLast(trail.length)
            "<a href=\"$url\">$url</a>$trail"
        }
        return linked.split(Regex("\n{2,}")).filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + it.trim().replace("\n", "<br />") + "</p>" }
    }

    private fun pickSpace(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            val spaces = withContext(Dispatchers.IO) { LedgerCommunityBridge.spaces(ctx) }
            if (spaces.isEmpty()) { toast(ctx, "Couldn't load spaces"); return@launch }
            val labels = spaces.map { (if (it.privacy == "public") "🌐  " else "🔒  ") + it.title }.toTypedArray()
            fragment.showModal(
                AlertDialog.Builder(ModalScale.wrap(ctx))
                    .setTitle("Post to space")
                    .setItems(labels) { _, which ->
                        val space = spaces[which]
                        toast(ctx, "Posting…")
                        fragment.lifecycleScope.launch {
                            val status = withContext(Dispatchers.IO) {
                                val png = pngOf(payload) ?: return@withContext "This page is empty"
                                val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
                                LedgerCommunityBridge.postGram(
                                    ctx, b64, payload.title,
                                    "send-" + java.util.UUID.randomUUID().toString().lowercase(), space.id
                                )
                            }
                            toast(ctx, status)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
            )
        }
    }

    /* -----------------------------------------------------------------------------------
     * Shared plumbing
     * --------------------------------------------------------------------------------- */

    private fun pngOf(payload: Payload): ByteArray? {
        val bmp = payload.bitmap() ?: return null
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    /** `exports/` is a declared FileProvider path and is swept by [com.toolsboox.ot.CacheJanitor],
     *  so nothing written here accumulates on a device that is short of space to begin with. */
    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').take(48).ifBlank { "ledger-page" }

    private fun shareFile(context: Context, file: File, mime: String, chooser: String) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                chooser
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }.onFailure { toast(context, "Share failed") }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
