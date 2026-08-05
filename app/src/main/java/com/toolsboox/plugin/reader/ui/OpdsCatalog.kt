package com.toolsboox.plugin.reader.ui

import android.content.Context
import android.util.Base64
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * OPDS — browsing a book catalog over the network, and pulling from it into the shelf.
 *
 * Michael's punchlist, 2026-08-04: "Bookshelf still missing OPDS in import." The iPad has had it
 * since `App/OPDS.swift`; this is its Android twin, deliberately built to the same model (Link →
 * Entry → Feed, Basic auth, prefer-EPUB acquisition) so the two forks describe a catalog the same
 * way and a fix to one reads as a fix to the other.
 *
 * The catalog in practice is his own Calibre-Web, which is why Basic auth is the only scheme here:
 * it is what that server speaks, and inventing support for schemes he does not use would be
 * untested code pretending to be a feature.
 *
 * OPDS is Atom with two useful conventions layered on:
 *
 *  • an entry whose link `type` is `application/atom+xml` is a SUB-CATALOG to browse into
 *  • an entry with an `acquisition` rel is a BOOK to download
 *
 * An entry can be both, and neither is guaranteed, so [Entry.isBook] asks whether there is anything
 * to download rather than trusting a shape.
 */
object OpdsCatalog {

    private const val PREFS = "ledger_opds_encrypted_prefs"

    data class Link(val rel: String?, val href: String, val type: String?)

    data class Entry(
        val id: String,
        val title: String,
        val author: String,
        val summary: String,
        val links: List<Link>,
    ) {
        /** A sub-catalog to browse into. */
        val navHref: String?
            get() = links.firstOrNull {
                (it.type ?: "").contains("atom+xml") && !(it.rel ?: "").contains("acquisition")
            }?.href

        /** The best file to fetch — EPUB by preference, then whatever else is offered. */
        val download: Link?
            get() {
                val acq = links.filter { (it.rel ?: "").contains("acquisition") }
                return acq.firstOrNull { (it.type ?: "").contains("epub") } ?: acq.firstOrNull()
            }

        val isBook: Boolean get() = download != null

        /** The filename to save under, with an extension the reader will recognise. */
        fun filename(): String {
            val ext = when {
                (download?.type ?: "").contains("epub") -> "epub"
                (download?.type ?: "").contains("pdf") -> "pdf"
                (download?.type ?: "").contains("zip") -> "cbz"
                else -> download?.href?.substringAfterLast('.', "")?.take(4)?.lowercase()
                    ?.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "epub"
            }
            // Filesystem-safe, and short enough to survive every filesystem in the mesh.
            val stem = (if (author.isBlank()) title else "$author - $title")
                .replace(Regex("""[/\\:*?"<>|]"""), "-")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(120)
            return "$stem.$ext"
        }
    }

    data class Feed(val title: String, val entries: List<Entry>)

    // ── Settings ──────────────────────────────────────────────────────────────────────────────

    data class Config(val url: String, val user: String, val pass: String)

    private fun prefs(context: Context) =
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context, PREFS,
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * The configured catalog, or null when none has been set up.
     *
     * READS THE SETTINGS SYNC FIRST. Michael, on being told Android had no OPDS client: "there is
     * already a client — or was anyway." Both true, and the useful part is what it implies: the
     * catalog has been configured on the iPad for months, and `SettingsBackup` has been carrying
     * `opds.{url,user,pass}` across to these devices the whole time as an iOS-only section it had
     * no home for. So the config is already here. A private store that ignored it would make him
     * type the same catalog in twice.
     *
     * Local values win when present, because they are the ones set on THIS device most recently;
     * the carried section is the fallback that makes a fresh Android install work with no setup.
     */
    fun config(context: Context): Config? = runCatching {
        val p = prefs(context)
        val carried = com.toolsboox.plugin.calendar.ot.SettingsBackup.carriedSection(context, "opds")
        val url = p.getString("opds_url", "")?.trim().orEmpty()
            .ifBlank { carried?.optString("url").orEmpty().trim() }
        if (url.isBlank()) return@runCatching null
        Config(
            url,
            p.getString("opds_user", "").orEmpty().ifBlank { carried?.optString("user").orEmpty() },
            p.getString("opds_pass", "").orEmpty().ifBlank { carried?.optString("pass").orEmpty() },
        )
    }.getOrNull()

    /**
     * Save, to BOTH stores.
     *
     * The carried section too, so a catalog corrected on the Boox survives the next export and
     * reaches the iPad — otherwise Android would quietly become a place where this setting goes
     * to be forgotten, which is worse than not having it.
     */
    fun save(context: Context, url: String, user: String, pass: String) {
        runCatching {
            prefs(context).edit()
                .putString("opds_url", url.trim())
                .putString("opds_user", user.trim())
                .putString("opds_pass", pass)
                .apply()
        }
        runCatching {
            com.toolsboox.plugin.calendar.ot.SettingsBackup.putCarriedSection(
                context, "opds",
                org.json.JSONObject()
                    .put("url", url.trim())
                    .put("user", user.trim())
                    .put("pass", pass)
            )
        }
    }

    // ── Fetching ──────────────────────────────────────────────────────────────────────────────

    /** Resolve a possibly-relative href against the catalog root. */
    fun resolve(base: String, href: String): String =
        runCatching { URI(base).resolve(href).toString() }.getOrDefault(href)

    private fun open(cfg: Config, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (cfg.user.isNotBlank() || cfg.pass.isNotBlank()) {
            val token = Base64.encodeToString(
                "${cfg.user}:${cfg.pass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        conn.setRequestProperty("Accept", "application/atom+xml, */*")
        return conn
    }

    /** Thrown with a sentence worth showing — a catalog failure is almost always a sign-in. */
    class OpdsException(message: String) : Exception(message)

    /**
     * Fetch and parse a feed. [href] null means the catalog root.
     *
     * Blocking — call it off the main thread. 401 is named specifically because it is the failure
     * that actually happens (a wrong password, or a Calibre-Web that wants a login), and "sign in"
     * is a fixable instruction where "HTTP 401" is not.
     */
    fun feed(context: Context, href: String? = null): Feed {
        val cfg = config(context) ?: throw OpdsException("No catalog set up yet.")
        val url = if (href.isNullOrBlank()) cfg.url else resolve(cfg.url, href)
        val conn = open(cfg, url)
        try {
            when (conn.responseCode) {
                200 -> Unit
                401, 403 -> throw OpdsException("Sign in to the catalog — check the username and password.")
                404 -> throw OpdsException("The catalog had nothing at that address.")
                else -> throw OpdsException("The catalog answered ${conn.responseCode}.")
            }
            return conn.inputStream.use { parse(it) }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Open [url] with the catalog's auth and hand the body to [body]. Blocking.
     *
     * A stream rather than a ByteArray: books run to tens of megabytes and this device has little
     * to spare, so the bytes go straight from the socket to the shelf without ever being whole in
     * memory.
     */
    fun stream(context: Context, url: String, body: (InputStream) -> Unit) {
        val cfg = config(context) ?: throw OpdsException("No catalog set up yet.")
        val conn = open(cfg, url)
        try {
            when (conn.responseCode) {
                200 -> Unit
                401, 403 -> throw OpdsException("Sign in to the catalog — check the username and password.")
                else -> throw OpdsException("The catalog answered ${conn.responseCode}.")
            }
            conn.inputStream.use(body)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Download an entry's file into [dest]. Blocking. */
    fun download(context: Context, entry: Entry, dest: java.io.File): Boolean {
        val cfg = config(context) ?: return false
        val link = entry.download ?: return false
        val conn = open(cfg, resolve(cfg.url, link.href))
        return try {
            if (conn.responseCode != 200) return false
            // Temp-then-rename, so an interrupted download never leaves a truncated book on the
            // shelf looking like a real one — the shelf reads by extension and would list it.
            val tmp = java.io.File(dest.parentFile, ".${dest.name}.part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Atom, walked with SAX.
     *
     * SAX rather than the pull parser on purpose: `XmlPullParserFactory` is an unimplemented stub
     * in Android's unit-test jar, so a pull-parsed catalog could only ever be tested on a device —
     * and this parser's whole risk is other people's XML, which is exactly what you want to test
     * cheaply and often. SAX is real on both sides.
     *
     * Namespace-agnostic by design: real catalogs differ about prefixes and about whether they
     * declare Dublin Core, and matching on the local name is both simpler and more forgiving than
     * being right about every server's XML hygiene.
     */
    fun parse(input: InputStream): Feed {
        var feedTitle = ""
        var seenFeedTitle = false
        val entries = mutableListOf<Entry>()

        var inEntry = false
        var inAuthor = false
        var id = ""; var title = ""; var author = ""; var summary = ""
        var links = mutableListOf<Link>()
        val text = StringBuilder()

        val handler = object : DefaultHandler() {
            private fun local(qName: String, localName: String) =
                localName.ifBlank { qName.substringAfterLast(':') }

            override fun startElement(uri: String?, localName: String, qName: String, a: Attributes) {
                text.setLength(0)
                when (local(qName, localName)) {
                    "entry" -> {
                        inEntry = true
                        id = ""; title = ""; author = ""; summary = ""; links = mutableListOf()
                    }
                    "author" -> inAuthor = true
                    "link" -> if (inEntry) {
                        val href = a.getValue("href")
                        if (!href.isNullOrBlank()) {
                            links += Link(a.getValue("rel"), href, a.getValue("type"))
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String, qName: String) {
                val v = text.toString().trim()
                when (local(qName, localName)) {
                    "title" ->
                        if (inEntry) title = v
                        // The FIRST title outside an entry is the feed's own; later ones are
                        // whatever a server chose to nest, and must not overwrite it.
                        else if (!seenFeedTitle) { feedTitle = v; seenFeedTitle = true }
                    "id" -> if (inEntry) id = v
                    "name" -> if (inEntry && inAuthor) author = v
                    "author" -> inAuthor = false
                    "summary", "content" -> if (inEntry && summary.isBlank()) summary = stripTags(v)
                    "entry" -> {
                        inEntry = false
                        // A titleless entry is a row with nothing to read — dropped rather than
                        // shown blank, which is how a catalog ends up looking broken.
                        if (title.isNotBlank()) {
                            entries += Entry(id.ifBlank { title }, title, author, summary, links.toList())
                        }
                    }
                }
                text.setLength(0)
            }
        }

        SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Entity expansion is the standard XXE vector, and a catalog is a remote document from
            // a server that may not be the one you think. Nothing in OPDS needs doctypes.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newSAXParser().parse(input, handler)

        return Feed(feedTitle, entries)
    }

    /** Calibre-Web puts HTML in `content`; the shelf shows one plain line. */
    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").replace(Regex("""\s+"""), " ").trim()
}
