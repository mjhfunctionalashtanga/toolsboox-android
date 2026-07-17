package com.toolsboox.plugin.feeds.nw

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Parsed item from a local RSS 2.0 / Atom feed (Android parity of the iOS RSSParser). */
data class RssItem(
    var title: String = "", var link: String = "", var content: String = "",
    var author: String = "", var date: String = "", var guid: String = ""
)

data class RssFeed(var title: String = "", val items: MutableList<RssItem> = mutableListOf())

/** Minimal RSS 2.0 + Atom parser (XmlPullParser) for Local Feeds — no server needed. */
object RssParser {
    fun parse(input: InputStream): RssFeed? = try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val feed = RssFeed()
        var item: RssItem? = null
        var text = ""
        var inChannelOrFeed = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase() ?: ""
                    text = ""
                    when (name) {
                        "item", "entry" -> item = RssItem()
                        "channel", "feed" -> inChannelOrFeed = true
                        "link" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank() && item != null && item!!.link.isBlank()) item!!.link = href
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
                            "description", "summary" -> if (it.content.isBlank()) it.content = v
                            "encoded", "content" -> it.content = v
                            "creator", "author", "name" -> if (it.author.isBlank()) it.author = v
                            "pubdate", "published", "updated", "date" -> if (it.date.isBlank()) it.date = v
                            "item", "entry" -> { feed.items.add(it); item = null }
                        }
                    } else if (inChannelOrFeed && name == "title" && feed.title.isBlank()) {
                        feed.title = v
                    }
                }
            }
            event = parser.next()
        }
        if (feed.items.isEmpty() && feed.title.isBlank()) null else feed
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
