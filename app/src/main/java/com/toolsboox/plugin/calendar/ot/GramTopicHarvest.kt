package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.da.ImageElement
import com.toolsboox.plugin.calendar.fi.CalendarDayService
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * 🌾 THE TOPIC HARVEST — a pickings page around a topic, BY GRAMS.
 *
 * Michael's ruling (08-23): "On Gram Picks, selecting a gram should allow me to do a pick
 * harvest making a pickings page around a topic by grams" — and, closing the loop on the Notes
 * IA model, "pickings is just a template within notes, and the name of the pickings-templated
 * note would just be the topic name."
 *
 * So this is the ❦ link harvest's sibling with the OTHER material: ❦ harvests a source's WORDS
 * into quote cards; 🌾 harvests the ledger's own GRAMS into a topic's company. The selected
 * gram names the topic and always makes the page; the AI reads the recent gram pool's labels —
 * never their pixels — and picks the companions; [GramComposer] lays them onto a fresh named
 * pickings note whose name IS the topic, indistinguishable from a board he started by hand.
 */
object GramTopicHarvest {

    /** How far back the candidate pool reaches. Three weeks is the working memory of the gram
     *  world — past that a gram has either graduated to a board or gone quiet. */
    private const val POOL_DAYS = 21L

    /** The most candidates the picker is shown. The listing is labels, not pixels, so this is
     *  a prompt-size guard, not a coverage promise — newest first, so what falls off is oldest. */
    private const val POOL_CAP = 80

    private fun prompt(topic: String) = """
        You are picking grams (saved cards: articles, quotes, images, notes) for a topic board.
        Topic: "$topic"

        Below is a numbered list of candidate grams, each described by its own labels. Reply with
        ONLY the numbers of the candidates that genuinely belong with this topic, comma-separated
        (for example: 2, 7, 15). If none belong, reply NONE. Do not explain.
    """.trimIndent()

    /** A gram's one-line self-description — every label it carries, pixels never read. */
    private fun describe(el: ImageElement): String = listOf(
        el.mediaTitle, el.sourceLabel, el.cardText.take(120), el.sourceFeed
    ).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "(untitled pick)" }

    /** A gram's identity for dedup across the days it was carried to. */
    private fun identity(el: ImageElement): String = el.gramId ?: el.elementId.toString()

    /** The recent gram pool: real (non-decorative) picture-bearing elements off the last
     *  [POOL_DAYS] of day files ending at [today], newest first, one entry per gram identity.
     *  Read-only — internal so the JVM suite can walk a fixture day through it. */
    internal fun gather(
        service: CalendarDayService, root: File, excludeIdentity: String,
        today: LocalDate = LocalDate.now(),
    ): List<ImageElement> {
        val out = LinkedHashMap<String, ImageElement>()
        for (back in 0 until POOL_DAYS) {
            // Read-only pool: a day that won't read (logged at ERROR) has no grams to offer.
            val day = runCatching {
                service.loadOrNull(root, today.minusDays(back), Locale.getDefault())
            }.getOrNull() ?: continue
            for (el in day.imageElements) {
                if (el.decorative) continue
                if (el.data.isBlank() && el.dataRef.isBlank()) continue
                val id = identity(el)
                if (id == excludeIdentity) continue
                out.putIfAbsent(id, el)
                if (out.size >= POOL_CAP) return out.values.toList()
            }
        }
        return out.values.toList()
    }

    /**
     * Run the harvest: [seed] names the page and always joins it; the picker chooses its company
     * from the pool. Blocking — IO. Returns the minted note, or null when the AI key is absent
     * or the compose found nothing to place (the seed alone still composes: a topic can start
     * as one gram).
     */
    fun run(
        context: Context,
        service: CalendarDayService,
        root: File,
        topic: String,
        seed: ImageElement,
        date: LocalDate = LocalDate.now(),
    ): PickHarvest.Outcome {
        val creds = com.toolsboox.plugin.chat.nw.AiCreds.get(context)
            ?: return PickHarvest.Outcome.Failed("No AI key yet — add one in Ask my Ledger settings.")
        val (provider, key, model) = creds

        val pool = gather(service, root, identity(seed))
        val chosen = mutableListOf(seed)
        if (pool.isNotEmpty()) {
            val listing = pool.mapIndexed { i, el -> "$i. ${describe(el)}" }
                .joinToString("\n").take(20_000)
            val answer = (com.toolsboox.plugin.chat.nw.LedgerChatService()
                .run(provider, key, model, prompt(topic), listing)
                    as? com.toolsboox.plugin.chat.nw.LedgerChatService.Result.Ok)?.answer.orEmpty()
            // "2, 7, 15" (or NONE). Anything unparseable reads as no picks — the seed still lands.
            Regex("""\d+""").findAll(answer)
                .mapNotNull { it.value.toIntOrNull() }
                .distinct()
                .mapNotNullTo(chosen) { pool.getOrNull(it) }
        }

        val nodes = chosen.distinctBy(::identity).map { el ->
            GramComposer.Node(
                ref = "gram:${identity(el)}",
                label = el.mediaTitle.ifBlank { el.sourceLabel }.ifBlank { el.cardText.take(60) },
                link = el.sourceLink,
                sourceLabel = el.sourceLabel.ifBlank { el.sourceFeed },
                image = com.toolsboox.ot.LedgerMedia.resolveBitmap(context, el.data, el.dataRef),
                kind = "◈",
            )
        }
        // The note's name IS the topic — no prefix, no ceremony (the ruling's own words).
        //
        // The failure carries the exception's own message now. This one swallowed into Timber, and
        // Timber is unreadable on the B6 — which is how "the harvest came back empty" ended up
        // meaning "something threw and nobody will ever know what".
        val attempt = runCatching {
            GramComposer.compose(
                context, service, root,
                LedgerDocuments.PICKINGS, topic.trim().ifBlank { "Picks" },
                nodes, emptyList(), GramComposer.Layout.GRID, date,
            )
        }.onFailure { Timber.w(it, "topic harvest compose failed") }
        val note = attempt.getOrNull()
        return when {
            note != null -> PickHarvest.Outcome.Ok(note)
            attempt.isFailure -> PickHarvest.Outcome.Failed(
                (attempt.exceptionOrNull() as? com.toolsboox.plugin.calendar.fi.CalendarDayService.UnreadableDayException)
                    ?.let { "Couldn't write the board — ${it.whichPage} won't read, so no board was made." }
                    ?: "Couldn't write the board: ${attempt.exceptionOrNull()?.message ?: "unknown error"}")
            // compose() returns null without throwing when the note could not be minted at all.
            else -> PickHarvest.Outcome.Failed("Couldn't start a Pickings board for that topic.")
        }
    }
}
