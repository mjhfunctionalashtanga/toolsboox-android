package com.toolsboox.plugin.calendar.ot

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * PICK HARVEST — a link in, a filled Pickings page out.
 *
 * Michael's spec, 2026-08-03: "Pick Harvest could have the ai grab three notes and up to three
 * quotes and two images filling up the picking template from user given link."
 *
 * The shape is the point. A summary of an article is a thing you read once and never open again; a
 * Pickings board of it is material you can arrange, connect, quote from and reply to — cards that
 * behave like everything else you gathered by hand. So the output is not an answer about the piece,
 * it is the piece BROKEN INTO PARTS you can use.
 *
 * THE INTEGRITY RULE, and it is the reason this is worth building carefully: a quote is verbatim or
 * it does not exist. Paraphrasing something and presenting it inside quotation marks is the same
 * failure as inventing a citation — worse here, because these cards will be arranged next to
 * Michael's own words and later quoted from a page that no longer remembers which was which. Fewer
 * quotes is fine. Fabricated ones are not.
 *
 * Everything carries `sourceLink` back to the URL, so any card can be walked back, cited, or
 * replied to months later.
 */
object PickHarvest {

    /**
     * What the model is asked for.
     *
     * Deliberately in the house voice ([com.toolsboox.plugin.chat.nw.PersonaStore]'s prompts):
     * second person, tied to his ledger, naming what it must NOT become, and with permission to
     * come back with less rather than pad.
     */
    const val PROMPT = """You are harvesting one piece for Michael's ledger — turning something he found into material he can use.

Read what you are given and return three things, in this exact shape and nothing else:

NOTES
- <an observation worth keeping, in your own words>
- <another>
- <a third>

QUOTES
- <a sentence copied EXACTLY from the text>
- <another, if there is one worth keeping>

Rules that matter more than completeness:

The NOTES are observations, not a summary. He can read the piece; three notes that restate it are three wasted cards. Each one should be usable on its own, months later, by someone who never read the original — the thing you would tell him about it, not what it says.

The QUOTES are VERBATIM or they do not exist. Copy the characters. Never tidy, never trim to fit, never paraphrase and present it as a quote. Two real quotes beat three where one has been smoothed. If nothing in it is worth quoting exactly, return no quotes at all — that is a real answer about a piece of writing.

No markdown beyond the two headings and the dashes. No preamble, no closing remark, no emojis."""

    /** One harvested piece: what the page will be built from. */
    data class Harvest(val notes: List<String>, val quotes: List<String>)

    /**
     * Pull NOTES and QUOTES out of the model's answer.
     *
     * Forgiving about the heading's exact spelling and strict about nothing else — the two lists
     * are the whole contract, and a model that wrote "Notes:" instead of "NOTES" has still done
     * what was asked.
     */
    fun parse(answer: String): Harvest {
        val notes = mutableListOf<String>()
        val quotes = mutableListOf<String>()
        var into: MutableList<String>? = null
        for (raw in answer.lines()) {
            val line = raw.trim()
            val head = line.trimEnd(':').uppercase()
            when {
                head == "NOTES" -> { into = notes; continue }
                head == "QUOTES" -> { into = quotes; continue }
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                val v = line.drop(2).trim().trim('"', '“', '”')
                if (v.isNotBlank()) into?.add(v)
            }
        }
        // His numbers, as a ceiling rather than a target: "three notes and up to three quotes".
        return Harvest(notes.take(3), quotes.take(3))
    }

    /**
     * The URL a gram's `sourceLink` can actually be harvested from, or null.
     *
     * A `sourceLink` is provenance, not necessarily an address on the web: grams also carry
     * `ledger://<date>/<page>` refs back to other pages and `mail://` refs to letters, and neither
     * of those can be fetched and read the way an article can. Only http(s) qualifies. Split out
     * as a pure function so the gram hold menu can decide honestly — "harvest the source" versus
     * "harvest what the gram itself holds" — with the same test the harvest itself will apply,
     * instead of the row promising a link flow that [run] would then quietly fail to deliver.
     */
    fun harvestableLink(sourceLink: String): String? {
        val link = sourceLink.trim()
        return if (link.startsWith("http://") || link.startsWith("https://")) link else null
    }

    /**
     * Harvest [url] onto a new Pickings page. Blocking — call it off the main thread.
     *
     * Returns the note, or null when there was nothing worth landing. Null rather than an empty
     * page: a Pickings board with nothing on it is litter in the directory, and the caller can say
     * "nothing came back" far more usefully than the page can.
     */
    fun run(
        context: Context,
        service: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
        url: String,
        title: String = "",
        date: LocalDate = LocalDate.now(),
    ): LedgerDocuments.NewNote? {
        // Creds first, before a fetch is spent on a harvest that cannot run anyway.
        com.toolsboox.plugin.chat.nw.AiCreds.get(context) ?: return null
        // The page's own text is what gets harvested — handing the model a bare URL would invite
        // it to answer from memory of the site, which is exactly the fabrication this guards.
        val text = runCatching { com.toolsboox.ot.HtmlText.toPlain(fetch(url)) }.getOrNull()
        if (text.isNullOrBlank()) return null
        return runText(
            context, service, root, text,
            sourceLink = url,
            title = title.ifBlank { url.substringAfter("://").substringBefore('/') },
            date = date,
        )
    }

    /**
     * Harvest [text] that is already in hand onto a new Pickings page. Blocking — call it off the
     * main thread.
     *
     * This is the link flow's second half, split out (1.06.58) so a GRAM can be harvested: "if I
     * don't have the link handy, I can have the gram so that it can then auto-populate." A gram
     * with no fetchable URL still often carries words — the exact text its card was rendered from,
     * or what OCR read off its face — and those words get exactly the treatment a fetched article
     * gets: same prompt, same verbatim-or-nothing parse, same board shape. The only difference is
     * who supplied the text, which is why the split sits here and not in the caller.
     *
     * [sourceLink] is whatever provenance the caller genuinely has — the fetched URL on the link
     * path, a `ledger://` ref for a card grammed off another page, or "" when there is none.
     * Blank is honest: [GramComposer] leaves `sourceLink` empty for a node with no address, so
     * the finished cards never wear a door that opens onto nothing.
     */
    fun runText(
        context: Context,
        service: com.toolsboox.plugin.calendar.fi.CalendarDayService,
        root: File,
        text: String,
        sourceLink: String = "",
        title: String = "",
        date: LocalDate = LocalDate.now(),
    ): LedgerDocuments.NewNote? {
        if (text.isBlank()) return null
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(context) ?: return null
        val (provider, key, model) = creds
        val result = com.toolsboox.plugin.chat.nw.LedgerChatService()
            .run(provider, key, model, PROMPT, text.take(24_000))
        val answer = (result as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer
            ?: return null
        val harvest = parse(answer)
        if (harvest.notes.isEmpty() && harvest.quotes.isEmpty()) return null

        val name = title.ifBlank { "Gram" }
        // A ref is IDENTITY (what edges point at) and must be distinct per node even when there is
        // no URL to hang it off — so a link-less harvest anchors its refs to the name instead.
        // The LINK stays [sourceLink] verbatim, blank included; see the contract above.
        val refBase = sourceLink.ifBlank { "harvest:$name" }
        val nodes = mutableListOf<GramComposer.Node>()
        // Quotes lead. They are the piece's own words and the thing he is most likely to use;
        // the notes are the reading of it, and a reading sits under what it read.
        for ((i, q) in harvest.quotes.withIndex()) {
            nodes += GramComposer.Node(
                ref = "$refBase#quote$i", label = q, link = sourceLink, sourceLabel = name, kind = "❝",
            )
        }
        for ((i, n) in harvest.notes.withIndex()) {
            nodes += GramComposer.Node(
                ref = "$refBase#note$i", label = n, link = sourceLink, sourceLabel = name, kind = "✎",
            )
        }
        return GramComposer.compose(
            context, service, root,
            LedgerDocuments.PICKINGS, "Harvest · $name",
            nodes, emptyList(), GramComposer.Layout.GRID, date,
        )
    }

    private fun fetch(url: String): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            // Some publishers serve a stub to unknown agents; a plain identity gets the article.
            setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Ledger/1.0)")
        }
        return try {
            if (conn.responseCode != 200) "" else conn.inputStream.bufferedReader().readText()
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
