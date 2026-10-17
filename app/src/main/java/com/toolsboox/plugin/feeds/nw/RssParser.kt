package com.toolsboox.plugin.feeds.nw

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Parsed item from a local RSS 2.0 / Atom feed (Android parity of the iOS RSSParser).
 *  Beyond the reading basics it now carries the podcast layer: the enclosure (episode file +
 *  art), and the Podcasting 2.0 sidecars — `<podcast:chapters>` / `<podcast:transcript>` URLs —
 *  which Miniflux's API never exposes, so the raw feed XML is the only place to get them. */
data class RssItem(
    var title: String = "", var link: String = "", var content: String = "",
    var author: String = "", var date: String = "", var guid: String = "",
    var enclosureAudio: String = "", var enclosureImage: String = "",
    var chaptersUrl: String = "", var transcriptUrl: String = "", var transcriptType: String = ""
)

data class RssFeed(
    var title: String = "", val items: MutableList<RssItem> = mutableListOf(),
    /** The SHOW's own picture — channel-level `itunes:image href`, else `<image><url>`, else an
     *  Atom `<logo>`. (Before 10-12 the channel half was dropped: every image check needed an
     *  open item, so a podcast whose episodes carry no art of their own had no picture at all.) */
    var imageUrl: String = ""
)

/** Minimal RSS 2.0 + Atom parser (XmlPullParser) for Local Feeds — no server needed. */
object RssParser {
    fun parse(input: InputStream): RssFeed? = parseFeed(input, headOnly = false)

    /**
     * Just the show's cover, read from the HEAD of the feed: the channel's art always sits above
     * the first `<item>`, so the parse stops there and the caller closes the stream — a podcast
     * feed can run to megabytes of back catalogue, and the cover is in the first few kilobytes.
     * Null when the head names no picture (or the XML is broken).
     */
    fun channelImage(input: InputStream): String? =
        parseFeed(input, headOnly = true)?.imageUrl?.takeIf { it.startsWith("http") }

    private fun parseFeed(input: InputStream, headOnly: Boolean): RssFeed? = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val feed = RssFeed()
        var item: RssItem? = null
        var text = ""
        var inChannelOrFeed = false
        // The channel's art, by preference: itunes:image (square, full size — what podcast apps
        // draw), then the RSS 2.0 <image><url> (often a small banner), then an Atom <logo>.
        var itunesArt = ""
        var rssImageArt = ""
        var logoArt = ""
        var inChannelImage = false
        var event = parser.eventType
        loop@ while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase() ?: ""
                    text = ""
                    when (name) {
                        "item", "entry" -> { if (headOnly) break@loop; item = RssItem() }
                        "channel", "feed" -> inChannelOrFeed = true
                        "image" -> if (item == null) inChannelImage = true
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank() && item != null && item!!.link.isBlank()) item!!.link = href
                        }
                        // The podcast layer rides on attributes, not text, so it's all START_TAG
                        // work. Namespace processing is off, so names arrive prefixed
                        // ("podcast:chapters"); endsWith also catches nonstandard prefixes.
                        "enclosure" -> item?.let {
                            val u = parser.getAttributeValue(null, "url").orEmpty()
                            val t = parser.getAttributeValue(null, "type").orEmpty().lowercase()
                            when {
                                t.startsWith("audio") && it.enclosureAudio.isBlank() -> it.enclosureAudio = u
                                t.startsWith("image") && it.enclosureImage.isBlank() -> it.enclosureImage = u
                            }
                        }
                        // YouTube feeds carry the video art as media:thumbnail — the enclosure
                        // slot The Watch's rows and skin lean on.
                        "media:thumbnail", "itunes:image" -> {
                            val u = parser.getAttributeValue(null, "url")
                                ?: parser.getAttributeValue(null, "href")
                            val cur = item
                            if (cur != null) {
                                if (!u.isNullOrBlank() && cur.enclosureImage.isBlank()) cur.enclosureImage = u
                            } else if (name == "itunes:image" && !u.isNullOrBlank() && itunesArt.isBlank()) {
                                itunesArt = u.trim()
                            }
                        }
                        else -> when {
                            name.endsWith(":chapters") -> item?.let {
                                parser.getAttributeValue(null, "url")?.takeIf { u -> u.isNotBlank() }
                                    ?.let { u -> if (it.chaptersUrl.isBlank()) it.chaptersUrl = u }
                            }
                            name.endsWith(":transcript") -> item?.let {
                                val u = parser.getAttributeValue(null, "url").orEmpty()
                                // Prefer a text-shaped transcript (SRT/VTT/plain) when the feed
                                // offers several; otherwise first one wins.
                                val t = parser.getAttributeValue(null, "type").orEmpty()
                                fun textish(ty: String) = ty.contains("srt", true) ||
                                    ty.contains("vtt", true) || ty.startsWith("text", true)
                                val preferable = it.transcriptUrl.isBlank() ||
                                    (!textish(it.transcriptType) && textish(t))
                                if (u.isNotBlank() && preferable) { it.transcriptUrl = u; it.transcriptType = t }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> text += parser.text ?: ""
                XmlPullParser.CDSECT -> text += parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase() ?: ""
                    val v = text.trim()
                    val it = item
                    if (it != null) {
                        when (name) {
                            "title" -> it.title = v
                            "link" -> if (v.isNotBlank() && it.link.isBlank()) it.link = v
                            "guid", "id" -> it.guid = v
                            // media:description is where YouTube feeds keep the video text —
                            // the raw material for description-timestamp chapters.
                            "description", "summary", "media:description" -> if (it.content.isBlank()) it.content = v
                            "encoded", "content" -> it.content = v
                            "creator", "author", "name" -> if (it.author.isBlank()) it.author = v
                            "pubdate", "published", "updated", "date" -> if (it.date.isBlank()) it.date = v
                            "item", "entry" -> { feed.items.add(it); item = null }
                        }
                    } else if (inChannelImage) {
                        when (name) {
                            "url" -> if (rssImageArt.isBlank()) rssImageArt = v
                            "image" -> inChannelImage = false
                        }
                    } else if (inChannelOrFeed && name == "logo" && logoArt.isBlank()) {
                        logoArt = v
                    } else if (inChannelOrFeed && name == "title" && feed.title.isBlank()) {
                        feed.title = v
                    }
                }
            }
            event = parser.next()
        }
        feed.imageUrl = listOf(itunesArt, rssImageArt, logoArt).firstOrNull { it.startsWith("http") }.orEmpty()
        if (feed.items.isEmpty() && feed.title.isBlank() && feed.imageUrl.isBlank()) null else feed
    } catch (e: Exception) {
        null
    }

    /** Every feed URL (xmlUrl) in an OPML document. */
    fun opmlUrls(input: InputStream): List<String> = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val urls = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.lowercase() == "outline") {
                (parser.getAttributeValue(null, "xmlUrl") ?: parser.getAttributeValue(null, "xmlurl"))
                    ?.trim()?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }
            event = parser.next()
        }
        urls
    } catch (e: Exception) {
        emptyList()
    }
}
