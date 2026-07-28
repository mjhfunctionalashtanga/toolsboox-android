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
 * [blockRemote] is the privacy half and runs on both.
 */
object MailReaderHtml {

    /** Scripts never run in mail. Stripped from every reading before anything else happens. */
    private val SCRIPT = Regex("""<script\b.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HEAD_OPEN = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)

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

    /** The message as its sender built it, fitted to the screen. */
    fun asSent(raw: String): String = inject(SCRIPT.replace(raw, ""), """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html { -webkit-text-size-adjust: 100%; }
          body { margin: 0; }
          /* Designed mail is built for a ~600px desktop column. Keep the design; just never let it
             push the page sideways — a sideways page on e-ink is a page you can't read. */
          html, body { overflow-x: hidden; }
          img, video, table, td, th { max-width: 100% !important; }
          img, video { height: auto !important; }
        </style>
    """.trimIndent())

    /**
     * The same letter, reflowed for e-ink: one column, pure black on white, generous line height,
     * every sender colour and background overridden.
     *
     * Overriding colour is the whole point rather than a side effect. Marketing mail leans on pale
     * greys and tinted panels that a backlit LCD renders as "subtle" and an e-ink panel renders as
     * illegible, so Reader takes the words and drops the palette.
     */
    fun reader(raw: String): String = inject(SCRIPT.replace(raw, ""), """
        <meta name="viewport" content="width=device-width, initial-scale=1">
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
    """.trimIndent())

    /** Put [head] inside the document's head, or wrap a bare fragment in a document that has one. */
    private fun inject(html: String, head: String): String {
        val m = HEAD_OPEN.find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + head + html.substring(m.range.last + 1)
        else "<!doctype html><html><head>$head</head><body>$html</body></html>"
    }
}
