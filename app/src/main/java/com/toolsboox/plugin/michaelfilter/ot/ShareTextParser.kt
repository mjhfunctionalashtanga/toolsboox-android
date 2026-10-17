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
     * Extract every URL in a block of text (trailing punctuation trimmed).
     *
     * @param text the text
     * @return the distinct URLs in order of appearance
     */
    fun extractUrls(text: String): List<String> {
        return URL_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '>', ';') }
            .distinct()
            .toList()
    }

    /** Video hosts → 📺 The Watch. The Boox's copy of the iPad's FeedNoteGram.kind(for:) table. */
    private val WATCH_HOSTS = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "twitch.tv", "dailymotion.com", "rumble.com"
    )

    /** Podcast/audio hosts → 🎧 The Listen. */
    private val LISTEN_HOSTS = listOf(
        "spotify.com", "podcasts.apple.com", "podcasts.google.com", "overcast.fm",
        "pocketcasts.com", "pca.st", "soundcloud.com", "castro.fm", "podbean.com", "castbox.fm"
    )

    /** A direct media file says what it is louder than its host does. */
    private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac")
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv")

    /**
     * Infer the intake kind (read|listen|watch) from the URL — the lane a ⏳ Later save lands in
     * without ever asking, so a saved item knows whether it reads, plays, or watches. Host table
     * first (the known video/podcast services), then the file extension (a shared enclosure or a
     * direct media link carries no telling host), then a "/podcast" path segment for episode
     * pages on ordinary sites. Everything else reads.
     *
     * @param url the URL
     * @return the kind
     */
    fun inferKind(url: String?): String {
        if (url == null) return "read"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "read"
        val host = uri.host?.lowercase() ?: return "read"
        val path = uri.path?.lowercase().orEmpty()
        return when {
            WATCH_HOSTS.any { host.contains(it) } -> "watch"
            LISTEN_HOSTS.any { host.contains(it) } -> "listen"
            AUDIO_EXTENSIONS.any { path.endsWith(it) } -> "listen"
            VIDEO_EXTENSIONS.any { path.endsWith(it) } -> "watch"
            path.contains("/podcast") -> "listen"
            else -> "read"
        }
    }
}
