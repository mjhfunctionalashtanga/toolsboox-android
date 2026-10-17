package com.toolsboox.plugin.feeds.ot

import android.webkit.WebView

/**
 * ONE way to read "what did you highlight?" out of a WebView, for every surface that annotates
 * from a selection — the feeds pane (articles AND letters), the standalone Articles reader,
 * and The Net.
 *
 * THE ONYX COLLAPSE RACE, once, here: the device collapses the WebView's selection the moment a
 * toolbar button is touched by the pen — BEFORE the tap handler's probe can run — so a reader
 * who highlighted a passage and then tapped ✍ arrived at the medium chooser with nothing in
 * hand and no "❝ Save highlight" row (field reports 09-02 and 09-06: "it doesn't give me just
 * the option to leave the highlighted text"). [wire] plants a `selectionchange` listener that
 * keeps the LAST non-empty selection with a timestamp; [probe] takes the live selection when
 * there is one and otherwise falls back to the remembered one while it is fresh. Beyond
 * [FRESH_MS] a stale highlight must not haunt a plain note.
 *
 * The pane used to carry this inline for articles only; the letters ran with scripts off and
 * so could never be probed at all, and the other two readers probed live and lost the race.
 * Both halves live here now so a fourth surface gets the same behaviour by calling two lines.
 */
object WebSelectionBridge {

    /** How long a remembered highlight stays usable after the selection itself collapsed. */
    const val FRESH_MS = 20_000L

    /** Idempotent: safe to run on every onPageFinished (a page reload wipes the flag with the
     *  document, so it re-wires itself). */
    /** The links INSIDE a selection, as lines after its text (Michael, 09-11: "make sure linked
     *  text are included in a highlight pick") — "label → href", each once; a selection sitting
     *  inside one link carries that link. Shared by the wire and the probe. */
    private const val LINKS_JS =
        "function __ledgerLinks(){try{var sel=window.getSelection();if(!sel||!sel.rangeCount)return '';" +
        "var r=sel.getRangeAt(0);var frag=r.cloneContents();var as=frag.querySelectorAll?frag.querySelectorAll('a[href]'):[];" +
        "var out=[],seen={};for(var i=0;i<as.length;i++){var h=as[i].href||'';if(!/^https?:/.test(h)||seen[h])continue;seen[h]=1;" +
        "var l=(as[i].textContent||'').trim().replace(/\\s+/g,' ');out.push(l&&l!==h?(l+' \\u2192 '+h):h);}" +
        "if(!out.length){var n=r.commonAncestorContainer;if(n&&n.nodeType===3)n=n.parentElement;" +
        "var a=n&&n.closest?n.closest('a[href]'):null;if(a&&/^https?:/.test(a.href))out.push(a.href);}" +
        "return out.join('\\n');}catch(e){return '';}}"

    private const val WIRE_JS =
        "(function(){if(window.__ledgerSelWired)return;window.__ledgerSelWired=1;$LINKS_JS;" +
        "document.addEventListener('selectionchange',function(){" +
        "var s=window.getSelection?String(window.getSelection()):'';" +
        "if(s&&s.trim()){var l=__ledgerLinks();window.__ledgerSel=l?(s+'\\n'+l):s;window.__ledgerSelAt=Date.now();" +
        // The RANGE rides along (Michael 2026-10-12, the article highlight): article-marks.js reads
        // the passage and its context out of the page text from it, never from the string above.
        "try{window.__ledgerSelRange=window.getSelection().getRangeAt(0).cloneRange();}catch(e){}}});})()"

    /** Current selection first (with its links); else the page's remembered one, if fresh. */
    private const val PROBE_JS =
        "(function(){$LINKS_JS;var s=window.getSelection?String(window.getSelection()):'';" +
        "if(s&&s.trim()){var l=__ledgerLinks();return l?(s+'\\n'+l):s;}" +
        "if(window.__ledgerSel&&Date.now()-(window.__ledgerSelAt||0)<$FRESH_MS)return window.__ledgerSel;" +
        "return '';})()"

    /**
     * Plant the selection memory in the page. Call from `onPageFinished`; needs JS enabled.
     *
     * ALSO FROM DOCUMENT START, once per WebView (Michael, 09-13: "when I highlight and select
     * Make a pick it doesn't immediately give me the highlighted area — it does if I do other
     * things and come back"): a highlight made while the page was still loading had no listener
     * yet, so nothing was remembered when the menu tap collapsed the live selection; only after
     * the page finished — or reloaded — did the memory exist. With the wire registered as a
     * document-start script, every document this view ever loads carries it from its first byte.
     */
    fun wire(view: WebView?) {
        view ?: return
        if (view.getTag(TAG_DOC_START) == null) {
            view.setTag(TAG_DOC_START, true)
            runCatching {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, WIRE_JS, setOf("*"))
            }
        }
        view.evaluateJavascript(WIRE_JS, null)
    }
    private const val TAG_DOC_START = 0x5E1EC7ED

    /** Read the highlight — live, or remembered-and-fresh — and hand it over trimmed (blank
     *  when there is none). The callback runs on the main thread, like evaluateJavascript's. */
    fun probe(view: WebView, onSelection: (String) -> Unit) {
        view.evaluateJavascript(PROBE_JS) { raw -> onSelection(unquote(raw)) }
    }

    /** Decode the JSON string evaluateJavascript hands back (quoted + escaped; "null" when the
     *  script could not run). */
    fun unquote(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return (runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
            ?: raw.removeSurrounding("\"")).trim()
    }
}
