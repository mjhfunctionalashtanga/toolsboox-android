package com.toolsboox.plugin.calendar.ot

import android.content.Context
import com.toolsboox.plugin.calendar.nw.LedgerText
import org.json.JSONArray

/**
 * Ask the model what something should be tagged — a page, a Pickings board, an email, an article.
 *
 * **The design problem is not "what is this about". It is "what do you already call this".**
 *
 * A model asked to tag freely names the same idea `#breathing` today, `#pranayama` tomorrow and
 * `#breath-work` next week, and every one is defensible. Tags are the naming system here, used in
 * place of per-note titles, so a splintered vocabulary doesn't make things untidy — it breaks what
 * tags are FOR. Everything below follows from that:
 *
 * - The existing vocabulary rides in the prompt, most-used first, so the model chooses from a list
 *   far more often than it invents.
 * - New tags are allowed but RATIONED — at most one, only when nothing fits, and enforced in the
 *   parser rather than merely asked for. Banning them would freeze the language on whatever the
 *   first month produced; letting them flow freely is the splintering.
 * - Nothing applies automatically. Suggestions go to a review dialog.
 *
 * The prompt is byte-for-byte the iPad's, so a page tagged on the Boox and the same page tagged on
 * the iPad get the same answer. Two prompts drifting apart would be two vocabularies drifting apart.
 */
object TagSuggest {

    data class Suggestion(val tag: String, val why: String, val isNew: Boolean)

    /** What is being tagged. The same words mean different things depending on what they sit in. */
    sealed class Subject {
        data class Article(val title: String, val source: String) : Subject()
        data class Email(val subject: String, val from: String) : Subject()
        data class Page(val label: String) : Subject()
        data class Board(val name: String) : Subject()
        data class Note(val title: String) : Subject()

        val instruction: String
            get() = when (this) {
                is Article -> "This is an article titled “$title” from $source, saved to the reader's " +
                    "ledger. Tag it for what the reader would later go looking for it BY — its " +
                    "subject, not its genre. Do not tag it 'article' or name its publication."
                is Email -> "This is an email, subject “$subject”, from $from. Tag it by what it is " +
                    "ABOUT or what it concerns in the reader's life. Do not tag it 'email', and do " +
                    "not tag it with the sender's name unless the person IS the subject."
                is Page -> "This is a page from the reader's handwritten planner ($label); the text " +
                    "is OCR'd handwriting and may contain recognition errors — read past them. Tag " +
                    "it for the threads running through it, the way you'd label a page you meant to " +
                    "find again. Ignore dates, times, and to-do scaffolding."
                is Board -> "This is a Pickings board named “$name” — a collection of quotes, " +
                    "clippings and images the reader gathered together. Tag what the COLLECTION is " +
                    "about, the thread that made these belong side by side. Do not tag individual items."
                is Note -> "This is a written note titled “$title”. Tag it for its subject — what the " +
                    "reader would search for to find this thought again."
            }
    }

    private const val VOCABULARY_CAP = 160
    private const val TEXT_CAP = 6000

    /**
     * Suggest tags. Blocking — call from Dispatchers.IO. Empty on any failure: a suggestion that
     * can't be made should be silent, never a blocked save.
     */
    fun suggest(
        context: Context, text: String, subject: Subject,
        provider: String, apiKey: String, model: String
    ): List<Suggestion> {
        val body = text.trim()
        if (body.length < 40) return emptyList()   // too little to be about anything

        val existing = LedgerTags.list(context).take(VOCABULARY_CAP).map { it.tag }
        val known = existing.toSet()
        val raw = LedgerText.complete(
            prompt(subject, existing), body.take(TEXT_CAP), provider, apiKey, model
        ) ?: return emptyList()
        return parse(raw, known)
    }

    private fun prompt(subject: Subject, vocabulary: List<String>): String {
        val vocab = if (vocabulary.isEmpty()) "(The reader has no tags yet — propose 2 to 4 good ones.)"
                    else vocabulary.joinToString(" ") { "#$it" }
        return """
        You tag things for one person's personal ledger. Their tags are how they NAME things — they
        don't title their notes, they tag them — so reusing the word they already use matters more
        than finding a better one.

        ${subject.instruction}

        THEIR EXISTING TAGS, most-used first:
        $vocab

        RULES
        1. Prefer an existing tag. If one is close, use it exactly as written — do not pluralise it,
           hyphenate it, or make it more precise.
        2. Propose AT MOST ONE new tag, and only if nothing existing fits. A new tag must be a word
           they would plausibly use again, not a phrase describing this one item.
        3. Give 2 to 4 tags. Fewer is better than padding. If only one fits, give one.
        4. Tag the SUBJECT, never the form or the source. Not: article, email, note, newsletter,
           post, blog, link, reading, interesting, misc, general, todo, personal.
        5. Lowercase. A single word, or two joined by a hyphen. No spaces, no # symbol, no
           punctuation, nothing over 30 characters.
        6. Do not tag a person's name unless that person is genuinely the subject.

        Answer with ONLY a JSON array, no prose, no code fence:
        [{"tag":"ashtanga","why":"six words on why"}]
        """.trimIndent()
    }

    /** Read the answer defensively — a stray code fence shouldn't cost the whole suggestion. */
    private fun parse(raw: String, known: Set<String>): List<Suggestion> {
        val start = raw.indexOf('['); val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val rows = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return emptyList()

        val out = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()
        var newCount = 0
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            // Through the REAL extractor, so a suggestion can never be a tag the harvest itself
            // would reject — the model doesn't get its own dialect.
            val tag = LedgerTags.extract("#" + o.optString("tag").trim().trimStart('#')).firstOrNull()
                ?: continue
            if (!seen.add(tag)) continue
            val isNew = tag !in known
            // Rule 2, ENFORCED rather than requested. Models comply with "at most one" most of the
            // time; the vocabulary is what's at stake, so most of the time isn't enough.
            if (isNew) { newCount++; if (newCount > 1) continue }
            out.add(Suggestion(tag, o.optString("why").trim(), isNew))
            if (out.size >= 4) break
        }
        return out
    }
}
