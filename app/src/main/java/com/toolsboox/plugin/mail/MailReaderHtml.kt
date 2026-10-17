package com.toolsboox.plugin.mail

/**
 * Shaping a message's markup for the two web readings — the Boox mirror of the iPad's
 * `MailReaderHTML` in UnifiedInboxView.swift.
 *
 * [asSent] keeps the letter as its sender built it and only stops it pushing the page sideways.
 * [reader] throws the design away and reflows the words into a plain, high-contrast column, which
 * is what e-ink actually wants: a marketing email's pale grey on off-white dithers into mud on a
 * screen with no backlight and sixteen greys.
 *
 * [blockRemote] is the privacy half and runs on both; [disarm] is the other half of it.
 *
 * A LETTER RUNS NO CODE — but the pane's WebView now reads letters with JavaScript ON, because
 * that is the only way to read the passage you highlighted (09-06: "when I highlight and click
 * the pin… in the email reader, it doesn't give me just the option to leave the highlighted
 * text" — the selection probe is JavaScript, and with scripts off it could never run). So the
 * no-code promise moves from the WebView setting into the markup: [disarm] strips everything
 * executable out of the message before either reading shapes it, and the document loads on no
 * origin with no file or content access. What is left cannot run, whatever the setting says.
 */
object MailReaderHtml {

    /** Belt to [disarm]'s braces: the raw regex pass still runs first, so a fragment jsoup
     *  cannot parse cleanly has its <script> blocks gone before anything else happens. */
    private val SCRIPT = Regex("""<script\b.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** URL schemes that are code, not places. Anything in an href/src/action that starts with
     *  one of these is dropped whole. `ledger:` too (2026-10-10, critique P5): the reader's own
     *  doors (load images, attachments, the thread cards) are ledger:// links the app adds AFTER
     *  this pass — a sender's markup must never be able to press them. */
    private val CODE_SCHEMES = listOf("javascript:", "vbscript:", "data:text/html", "livescript:", "ledger:")

    /**
     * Everything executable out of the letter, the design left standing.
     *
     * Not a Safelist clean — that would throw away the sender's inline styles and tables, which
     * is precisely what As-sent exists to keep. This is a targeted pass over the parsed tree:
     *  - `script`, `noscript`, `iframe`, `frame`, `object`, `embed`, `applet`, `base` go;
     *  - `link` and `meta[http-equiv]` go (a remote stylesheet is a tracking request the image
     *    block never covered; a refresh meta is a navigation the reader did not ask for);
     *  - every `on*` attribute goes, on every element;
     *  - `href`/`src`/`action`/`formaction`/`xlink:href` carrying a code scheme go.
     * Output is the whole document again (head and all) so the readings' `inject` still finds
     * the head it looks for. jsoup normalises a head-less fragment into a full document, which
     * `inject` already tolerated either way.
     */
    fun disarm(raw: String): String {
        val doc = runCatching { org.jsoup.Jsoup.parse(SCRIPT.replace(raw, "")) }.getOrNull()
            ?: return SCRIPT.replace(raw, "")
        doc.select("script, noscript, iframe, frame, frameset, object, embed, applet, base, link, meta[http-equiv]").remove()
        for (el in doc.allElements) {
            val attrs = el.attributes()
            val drop = mutableListOf<String>()
            for (a in attrs) {
                val key = a.key.lowercase()
                if (key.startsWith("on")) { drop += a.key; continue }
                if (key in URL_ATTRS) {
                    val v = a.value.trim().lowercase().replace(Regex("[\\s\\u0000-\\u001f]"), "")
                    if (CODE_SCHEMES.any { v.startsWith(it) }) drop += a.key
                }
            }
            for (k in drop) el.removeAttr(k)
        }
        doc.outputSettings().prettyPrint(false)
        return doc.outerHtml()
    }

    private val URL_ATTRS = setOf("href", "src", "action", "formaction", "xlink:href", "poster", "background")
    // `<head(\s…)?>` — NOT `<head[^>]*>`, which happily matched `<header>` and planted the
    // injected styles inside body content on head-less fragments (the iPad found this the hard
    // way; both forks carried it).
    private val HEAD_OPEN = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)

    private val IMG_SRC = Regex("""(<img[^>]+)\bsrc(\s*=\s*["']https?://)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val IMG_SRCSET = Regex("""(<img[^>]+)\bsrcset(\s*=)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CSS_BG = Regex("""background(-image)?\s*:[^;"']*url\(\s*["']?https?://[^)]*\)""", RegexOption.IGNORE_CASE)
    private val REMOTE_IMG = Regex("""<img[^>]+src\s*=\s*["']https?://""", RegexOption.IGNORE_CASE)
    private val REMOTE_BG = Regex("""background(-image)?\s*:[^;"']*url\(\s*["']?https?://""", RegexOption.IGNORE_CASE)

    /** Does this message pull anything off the network to render? */
    fun hasRemoteRefs(raw: String): Boolean =
        REMOTE_IMG.containsMatchIn(raw) || REMOTE_BG.containsMatchIn(raw)

    /**
     * Defuse every remote fetch, keeping the document otherwise intact.
     *
     * A tracking pixel's whole payload is the REQUEST: rendering the message tells the sender you
     * opened it, when, and roughly from where. The WebView's own `blockNetworkImage` is set too,
     * but the markup is disarmed as well — belt and braces on the one thing that cannot be taken
     * back once it has happened.
     *
     * `src` becomes `data-blocked-src` rather than being deleted, so "load images" is a plain
     * re-render of the untouched original. Inline `data:` images are left alone: they arrived with
     * the letter and cost no request.
     */
    fun blockRemote(html: String): String {
        var s = IMG_SRC.replace(html) { "${it.groupValues[1]}data-blocked-src${it.groupValues[2]}" }
        s = IMG_SRCSET.replace(s) { "${it.groupValues[1]}data-blocked-srcset${it.groupValues[2]}" }
        s = CSS_BG.replace(s, "background-image:none")
        // A blocked image collapses to nothing; a quiet placeholder keeps the page reading as a
        // letter with pictures in it rather than a bag of holes.
        return inject(s, """
            <style>
              img[data-blocked-src] {
                min-height: 18px; border: 1px dashed #999; opacity: .5; border-radius: 4px;
              }
            </style>
        """.trimIndent())
    }

    /**
     * The message as its sender built it, fitted to the screen.
     *
     * The fit rides at the document's END, not the head: injected at head-start the sender's own
     * stylesheet loads after it and out-cascades the caps (equal specificity, later wins) — the
     * "doesn't scale to the window" report on both forks. And the caps cover `body *`, because
     * designed mail's fixed/min-width 600px WRAPPER DIVS are what actually push the page sideways
     * — img/table/td alone let every wrapper sail past.
     */
    fun asSent(raw: String): String = inject(disarm(raw), """
        <meta name="viewport" content="width=device-width, initial-scale=1">
    """.trimIndent()) + """
        <style>
          html { -webkit-text-size-adjust: 100%; }
          body { margin: 0; }
          html, body { overflow-x: hidden; }
          body * { max-width: 100% !important; min-width: 0 !important; }
          img, video { height: auto !important; }
        </style>
    """.trimIndent()

    /**
     * The same letter, reflowed for e-ink: one column, pure black on white, generous line height,
     * every sender colour and background overridden.
     *
     * Overriding colour is the whole point rather than a side effect. Marketing mail leans on pale
     * greys and tinted panels that a backlit LCD renders as "subtle" and an e-ink panel renders as
     * illegible, so Reader takes the words and drops the palette.
     */
    fun reader(raw: String): String = inject(disarm(raw), """
        <meta name="viewport" content="width=device-width, initial-scale=1">
    """.trimIndent()) + """
        <style>
          html { -webkit-text-size-adjust: 100%; }
          body {
            margin: 0 !important; padding: 4px 10px !important;
            background: #fff !important; color: #000 !important;
            font-family: Georgia, serif !important; font-size: 17px !important; line-height: 1.55 !important;
          }
          /* Sender styling off: colours, backgrounds, fixed widths and float columns all go, so
             what's left is the reading order the words were written in. */
          * {
            background: transparent !important; color: #000 !important;
            font-family: inherit !important; max-width: 100% !important;
            float: none !important; position: static !important;
          }
          table, tbody, tr, td, th, div, span { display: block !important; width: auto !important; }
          img, video { max-width: 100% !important; height: auto !important; display: block; margin: 8px 0; }
          a { color: #000 !important; text-decoration: underline !important; }
          h1, h2, h3 { font-size: 19px !important; margin: 14px 0 6px !important; }
          hr { border: 0; border-top: 1px solid #bbb; margin: 14px 0; }
          /* A newsletter's footer is half its bytes and none of its point. */
          [class*="footer"], [id*="footer"], [class*="unsubscribe"] { opacity: .6; }
        </style>
    """.trimIndent()

    /** Put [head] inside the document's head, or wrap a bare fragment in a document that has one. */
    private fun inject(html: String, head: String): String {
        val m = HEAD_OPEN.find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + head + html.substring(m.range.last + 1)
        else "<!doctype html><html><head>$head</head><body>$html</body></html>"
    }
}
