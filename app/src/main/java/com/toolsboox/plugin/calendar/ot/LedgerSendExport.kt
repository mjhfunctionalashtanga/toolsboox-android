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
 * Bluesky. That is why it asks before it does it — of the seven, it is the only one that puts
 * something in front of other people without a further step.
 *
 * TWO THINGS EVERY EXPORT NOW CARRIES that it did not before:
 *
 *  1. **Provenance.** The PNG and the PDF used to leave bare — see [LedgerProvenance] for why that
 *     made them unfindable a fortnight later. Every picture that leaves here is stamped, and the
 *     PDF gets a visible block plus a real `/Info` dictionary.
 *  2. **Syndication, by default, on the publish path.** The live server contract is that a
 *     published post on michaeljoelhall.com enters the queue when it matches `mjh_synd_sources()`,
 *     which for a regular post means the `essay` tag, and takes its image from the featured image.
 *     Publish already set the featured image; what was missing was the tag, so "publish" and
 *     "syndicate" were two different acts that looked like one. They are one act now, with an
 *     explicit opt-out — and the words say *within half an hour*, never "posts to Bluesky", because
 *     the cron runs every thirty minutes and a promise the cron cannot keep is a lie the UI told.
 */
object LedgerSendExport {

    /**
     * What is being sent. [bitmap] and [text] are LAZY: rendering a page costs real time on e-ink,
     * and five of the seven destinations never need the picture (or never need the words), so the
     * sheet must be able to open without paying for either.
     *
     * [surface], [date] and [pageKey] are what make the export self-describing. They are the page's
     * address in the Ledger, not decoration: [pageKey] plus [date] is the identity a published URL
     * is remembered under ([LedgerProvenance.keyFor]), which is why it is the raw note-page key and
     * not the title — a document can be renamed and must not thereby lose the address it is already
     * published at.
     */
    class Payload(
        val title: String,
        val text: () -> String,
        val bitmap: () -> Bitmap?,
        val surface: String = "Ledger",
        val date: java.time.LocalDate? = null,
        val pageKey: String? = null,
    )

    fun show(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        val boards = LedgerWebBridge.config(ctx)
        val community = LedgerCommunityBridge.config(ctx)

        val rows = mutableListOf<Pair<String, () -> Unit>>()
        rows.add("📝  Plain text" to { sharePlainText(fragment, payload) })
        // The text sibling of PNG and Linked PDF: a real `.md` on disk rather than EXTRA_TEXT.
        // "Plain text" hands the words to the chooser as an extra, which most destinations paste
        // into a body with no name attached — fine for a message, useless for filing. A file
        // arrives as the page, named after it, which is the whole point of exporting one. (The
        // iPad's Text Notes has had this and its making surfaces had not; now both have both.)
        rows.add("📄  Markdown file" to { shareMarkdownFile(fragment, payload) })
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

    /**
     * The page written out as a markdown document and handed to the chooser as a file.
     *
     * The title becomes an H1 so the document still says what it is once it is sitting in a folder
     * next to other files — the same shape the iPad writes ([App/LaterListView.swift]'s
     * `currentMarkdown`), so a note exported from either device opens the same way.
     */
    private fun shareMarkdownFile(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            // Same reason as sharePlainText: on a handwritten page these words come from the
            // vision model, so producing them is a network call.
            val text = withContext(Dispatchers.IO) { payload.text() }
            if (text.isBlank()) { toast(ctx, "Nothing typed or recognised on this page"); return@launch }
            val doc = if (payload.title.isBlank()) text else "# ${payload.title}\n\n$text"
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    File(exportsDir(ctx), "${safeName(payload.title)}.md")
                        .apply { writeText(doc) }
                }.getOrNull()
            }
            if (file == null) { toast(ctx, "Couldn't write the markdown file"); return@launch }
            shareFile(ctx, file, "text/markdown", "Send markdown")
        }
    }

    private fun sharePng(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        fragment.lifecycleScope.launch {
            // IO rather than Default despite being a render: the stamp needs the page's links, and
            // getting the words off a handwritten page is a network call (see [sharePlainText]).
            val bytes = withContext(Dispatchers.IO) { pngOf(ctx, payload) }
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
                // The stamp is built from the SAME url list the index page gets, including the ones
                // only the vision model found — so the colophon's sources and its links can never
                // disagree about what is on this page.
                val stamp = provenanceOf(ctx, payload, text)
                    .copy(sourceLinks = urls.take(8))
                LedgerLinkedPdf.render(
                    ctx, payload.title, bmp, urls, boxes,
                    File(exportsDir(ctx), safeName(payload.title) + ".pdf"),
                    provenance = stamp
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
                            // Recognised once, used twice — as the letter's text and as the stamp's
                            // source of links. See [pngOf].
                            val text = payload.text()
                            LedgerEssay.send(
                                site.site, site.user, site.pass, "email",
                                payload.title, "", text, to, pngOf(ctx, payload, text)
                            )
                        }
                        toast(ctx, status)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    /**
     * Publishing puts the page in front of other people, so it asks first. Draft does not, so it
     * does not.
     *
     * The checkbox is CHECKED by default because Michael's own rule is "anything going to WordPress
     * can go to Bluesky when part of a share/publication cycle" — the common case is both, and a
     * default that made him tick a box every time to get the behaviour he asked for would be a
     * default set by the implementation rather than by him. Unticking it publishes without the
     * `essay` tag, which is precisely what keeps the post out of `mjh_synd_sources()` and therefore
     * out of the queue: the opt-out is a real mechanism, not a client-side flag the server ignores.
     *
     * The words are exact about the cron on purpose. It runs every half hour, so the truthful verb
     * is "syndicates", never "posts": telling him it goes to Bluesky and then watching nothing
     * appear for twenty minutes teaches him the app lies about the things he cannot see.
     */
    private fun confirmPublish(fragment: ScreenFragment, payload: Payload) {
        val ctx = fragment.requireContext()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        // Flat, no elevation, black text: a checkbox in a modal on e-ink is read at the same
        // distance as everything else on the panel.
        val syndicate = android.widget.CheckBox(ctx).apply {
            text = "Syndicate to Bluesky"
            isChecked = true
            textSize = 15f
            setTextColor(0xFF000000.toInt())
            elevation = 0f
        }
        val note = android.widget.TextView(ctx).apply {
            text = "Tags it “essay” and uses this page as the post's image, which is what puts it " +
                "in the syndication queue. The queue is a cron that runs every 30 minutes, so it " +
                "reaches Bluesky within the half hour — not straight away."
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            setPadding(0, pad / 3, 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(android.widget.TextView(ctx).apply {
                text = "It goes live on the site straight away. Save it as a draft instead if you " +
                    "want to read it once more first."
                textSize = 14f
                setTextColor(0xFF000000.toInt())
                setPadding(0, 0, 0, pad / 2)
            })
            addView(syndicate)
            addView(note)
        }
        fragment.showModal(
            AlertDialog.Builder(ModalScale.wrap(ctx))
                .setTitle("Publish “${payload.title.take(40)}”?")
                .setView(box)
                .setPositiveButton("Publish") { _, _ ->
                    sendToWordPress(fragment, payload, publish = true, syndicate = syndicate.isChecked)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    /**
     * [syndicate] is only ever honoured on the publish path. A DRAFT tagged `essay` is a trap: the
     * post sits there matching `mjh_synd_sources()` in every respect but its status, and the day it
     * is published from a laptop months later it syndicates a page he had forgotten he wrote. So a
     * draft is saved untagged, whatever the sheet was showing.
     */
    private fun sendToWordPress(
        fragment: ScreenFragment, payload: Payload, publish: Boolean, syndicate: Boolean = false
    ) {
        val ctx = fragment.requireContext()
        toast(ctx, if (publish) "Publishing…" else "Saving draft…")
        fragment.lifecycleScope.launch {
            val wantsSyndication = publish && syndicate
            var tagged = !wantsSyndication
            val result = withContext(Dispatchers.IO) {
                // The words first: they are the post's body AND the stamp's source of links, and
                // recognising a handwritten page twice for one publish is seconds and an API
                // charge for nothing. See [pngOf].
                val text = payload.text()
                val png = pngOf(ctx, payload, text)
                // The page image becomes the featured image — the same role it plays when a gram
                // is published from the Publish screen, so a post made here looks like the others.
                // It is ALSO what `mjh_synd_compose_post` takes the Bluesky post's image from, so
                // on the syndicating path this upload is not decoration: without it the mirrored
                // post goes out as bare text.
                val media = png?.let { WPPublish.uploadMedia(ctx, it, payload.title) } ?: 0
                val tags = if (wantsSyndication) essayTagIds(ctx) else emptyList()
                tagged = !wantsSyndication || tags.isNotEmpty()
                WPPublish.save(ctx, WPPublish.Draft(
                    title = payload.title.ifBlank { "Untitled" },
                    content = htmlBody(text),
                    status = if (publish) "publish" else "draft",
                    tags = tags,
                    featuredMedia = media,
                ))
            }
            // A published page HAS a canonical address now, and every later export of it should
            // carry that address — which is the whole point of remembering it here rather than
            // making him paste it back in by hand.
            if (result.ok && publish) {
                LedgerProvenance.rememberPublished(ctx, payload.date, payload.pageKey, result.link)
            }
            toast(ctx, when {
                // Deliberately three different sentences, because the three outcomes are different
                // facts. The middle one is the honest report of a partial success: the page IS
                // live, and it will NOT syndicate, and saying only "Published" would hide half of
                // what happened until he went looking for a Bluesky post that never came.
                result.ok && wantsSyndication && tagged ->
                    "Published · syndicating to Bluesky within 30 min"
                result.ok && wantsSyndication ->
                    "Published — but the “essay” tag didn't stick, so it won't syndicate"
                result.ok && publish -> "Published (no syndication)"
                result.ok -> "Draft created"
                else -> result.error ?: "WordPress refused it"
            })
        }
    }

    /**
     * The id of the `essay` tag, creating it if this site has never had one. Blocking — call from
     * Dispatchers.IO.
     *
     * The server matches on the TAG, so the tag has to exist as a term and be attached by id: the
     * REST API's `tags` field takes term ids, and posting the string "essay" into it is silently
     * dropped.
     *
     * Looked up BY SLUG, because slug is what `mjh_synd_sources()` matches on — its terms list is
     * `[ 'essay' ]` against `post_tag`, resolved as a slug rather than a term id. Matching by name
     * instead would attach a term called "essay" whose slug had drifted to `essay-2`, and the post
     * would then sit there looking tagged and never enter the queue.
     */
    private fun essayTagIds(context: Context): List<Int> {
        WPPublish.termBySlug(context, "tags", SYNDICATION_TAG)?.let { return listOf(it.id) }
        // Not there: make it. WordPress derives the slug from the name, so a term created as
        // "essay" on a site that has none gets the slug "essay" — the case this is for is a fresh
        // site or one whose tag was deleted, not a rename.
        val created = WPPublish.createTerm(context, "tags", SYNDICATION_TAG)
        return listOfNotNull(created?.id)
    }

    /** The tag SLUG `mjh_synd_sources()` looks for on a regular post from michaeljoelhall.com. A
     *  constant because it is a contract with a live server, not a preference: changing it here
     *  without changing it there silently stops every future publish from syndicating. */
    private const val SYNDICATION_TAG = "essay"

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
                                val png = pngOf(ctx, payload) ?: return@withContext "This page is empty"
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

    /** The page's provenance. Cheap apart from [Payload.text], which the callers here have already
     *  paid for — hence the text parameter rather than calling the lambda a second time and running
     *  a handwritten page through the vision model twice for one export. */
    private fun provenanceOf(context: Context, payload: Payload, text: String): LedgerProvenance.Stamp =
        LedgerProvenance.of(
            context, payload.surface, payload.date, payload.pageKey, payload.title, text
        )

    /**
     * The page as PNG bytes, WITH the provenance band drawn beneath it.
     *
     * Every picture-shaped destination comes through here — the plain PNG share, the email's inline
     * image, the community gram, and the WordPress featured image that `mjh_synd_compose_post`
     * hands on to Bluesky. Stamping in this one place is what makes that true of all of them
     * instead of true of whichever one someone remembered: the picture Michael described going "to
     * email, to bluesky, to wordpress" is literally the same bytes on all three routes.
     *
     * [text] is the page's words when the caller already has them. It is not an optimisation to
     * pass it: on a handwritten page `payload.text()` is a vision-model round trip, and the two
     * callers that need the words for their own sake (the email body, the WordPress post) would
     * otherwise pay for recognition TWICE for one export — once for the body and once for the
     * stamp — which on a page of handwriting is several seconds and a duplicated API charge.
     *
     * Blocking (it reads the page's words for the link list) — call from Dispatchers.IO.
     */
    private fun pngOf(context: Context, payload: Payload, text: String? = null): ByteArray? {
        val bmp = payload.bitmap() ?: return null
        val stamp = runCatching { provenanceOf(context, payload, text ?: payload.text()) }.getOrNull()
        // A failure to build the stamp must not cost him the export. The picture is the thing being
        // sent; the caption is what makes it findable later, and an un-captioned picture beats a
        // toast saying the share failed because the OCR call timed out.
        val stamped = stamp?.let { runCatching { LedgerProvenance.stampPng(bmp, it) }.getOrNull() }
        val toEncode = stamped ?: bmp
        val baos = ByteArrayOutputStream()
        toEncode.compress(Bitmap.CompressFormat.PNG, 100, baos)
        if (stamped != null) stamped.recycle()
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
