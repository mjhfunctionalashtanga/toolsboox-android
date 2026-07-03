package com.toolsboox.plugin.michaelfilter.ot

import java.net.URI

/**
 * Parses shared text (ACTION_SEND EXTRA_TEXT / EXTRA_SUBJECT) into the
 * MichaelFilter intake fields, and infers the content kind from the URL host.
 */
object ShareTextParser {

    /**
     * Result of parsing a shared payload.
     */
    data class ParsedShare(
        val url: String?,
        val title: String?,
        val kind: String,
        val leftoverText: String?
    )

    private val URL_REGEX = Regex("https?://\\S+")

    /**
     * Parse shared text. EXTRA_TEXT often carries "Title\nURL" or just the URL;
     * EXTRA_SUBJECT sometimes has the title.
     *
     * @param text the EXTRA_TEXT payload
     * @param subject the EXTRA_SUBJECT payload
     * @return the parsed share
     */
    fun parse(text: String?, subject: String?): ParsedShare {
        val raw = text?.trim().orEmpty()
        val url = URL_REGEX.find(raw)?.value?.trimEnd('.', ',', ')', ']', '>', ';')

        val leftover = (if (url != null) raw.replace(url, "") else raw).trim()

        val subjectTitle = subject?.trim()?.takeIf { it.isNotEmpty() }
        val leftoverFirstLine = leftover.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }

        // A short leftover line is treated as the title; a long leftover is pasted body text.
        val leftoverIsTitleSized = leftoverFirstLine != null && leftover.length <= 200 &&
            !leftover.contains('\n')

        val title = subjectTitle ?: if (leftoverIsTitleSized) leftoverFirstLine else null

        val leftoverText = when {
            leftover.isEmpty() -> null
            title != null && leftover == title -> null
            else -> leftover
        }

        return ParsedShare(url, title, inferKind(url), leftoverText)
    }

    /**
     * Infer the intake kind (read|listen|watch) from the URL host.
     *
     * @param url the URL
     * @return the kind
     */
    fun inferKind(url: String?): String {
        if (url == null) return "read"
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return "read"
        return when {
            host.contains("youtube.com") || host.contains("youtu.be") ||
                host.contains("vimeo.com") -> "watch"

            host.contains("spotify.com") || host.contains("podcasts.apple.com") ||
                host.contains("podcasts.google.com") || host.contains("overcast.fm") ||
                host.contains("pocketcasts.com") || host.contains("pca.st") -> "listen"

            else -> "read"
        }
    }
}
