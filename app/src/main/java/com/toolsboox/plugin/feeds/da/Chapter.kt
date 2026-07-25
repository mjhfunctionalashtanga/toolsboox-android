package com.toolsboox.plugin.feeds.da

/**
 * One chapter mark inside an episode — a second offset and a name. The SAME shape whether it
 * came from a Podcasting 2.0 `<podcast:chapters>` JSON document or from timestamp lines parsed
 * out of the episode description ("0:00 Intro" / "1:23:45 - Topic"), so the player, the Now
 * Playing card and The Watch's article skin all speak one chapter language.
 */
data class Chapter(val startSec: Int, val title: String) {
    /** m:ss, or h:mm:ss past the hour — the same clock face the transport uses. */
    val clock: String
        get() = if (startSec >= 3600) "%d:%02d:%02d".format(startSec / 3600, (startSec % 3600) / 60, startSec % 60)
        else "%d:%02d".format(startSec / 60, startSec % 60)
}

/**
 * The chapters resolved for one feed entry, tagged with where they came from: "tag" for the
 * Podcasting 2.0 chapters document (authoritative — wins when both exist), "desc" for the
 * description-timestamp parse (covers most of The Watch for free). An empty list is a real
 * answer too: "we looked, there are none" — cached so we don't re-look every open.
 */
data class ChapterList(val entryId: Long, val chapters: List<Chapter>, val source: String = "desc")
