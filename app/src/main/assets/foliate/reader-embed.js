import { Overlayer } from './overlayer.js'

let view = null
let currentDoc = null      // the section document currently on screen
let currentIndex = 0
// Last non-empty selection (tracked live, because the edit menu clears the real
// selection before our "Highlight" action runs).
let lastSelText = '', lastSelRange = null, lastSelIndex = 0, lastSelDoc = null
const msg = document.getElementById('msg')

// Signal the early (non-module) watchdog in the HTML that the module is alive.
window.__readerStarted = true

// e-ink-friendly highlight: the standard translucent fill washes out to white on a
// grayscale Boox, so combine the fill with a solid black underline in a single SVG
// group (Overlayer.add appends exactly one element per annotation).
const einkHighlight = (rects, options = {}) => {
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g')
    g.append(Overlayer.highlight(rects, { color: options.color || '#ffd60a' }))
    g.append(Overlayer.underline(rects, { color: '#000000', width: 3 }))
    return g
}

function post(type, payload = {}) {
    window.webkit?.messageHandlers?.reader?.postMessage({ type, ...payload })
}

// Prefer the early on-screen printer set up in the HTML; fall back to local.
function showStatus(text) {
    if (window.__readerShow) { window.__readerShow(text); return }
    if (msg) { msg.style.display = 'flex'; msg.textContent = text }
}

const errText = e => e ? (e.stack || e.message || String(e)) : String(e)

// Surface any failure ON SCREEN so we can see why nothing renders.
window.addEventListener('error', e => { showStatus('JS error: ' + errText(e.error || e.message)); post('error', { message: String(e.message) }) })
window.addEventListener('unhandledrejection', e => { showStatus('Rejected: ' + errText(e.reason)); post('error', { message: String(e.reason) }) })

// Import the engine; if the module graph fails to load, say so visibly.
let engineOK = false
try {
    await import('./view.js')
    engineOK = true
    showStatus('Reader ready — waiting for book…')
    // If the book bytes never arrive, don't sit silently on "waiting".
    setTimeout(() => {
        if (engineOK && !view) {
            showStatus('Engine loaded, but no book arrived from the app.\n(evaluateJavaScript handoff of the file bytes likely failed — often too large.)')
        }
    }, 8000)
} catch (e) {
    showStatus('Failed to load engine (view.js): ' + errText(e))
    post('error', { message: 'engine load: ' + String(e) })
}

const langMap = x => !x ? '' : (typeof x === 'string' ? x : x[Object.keys(x)[0]])
const contributor = c => Array.isArray(c)
    ? c.map(x => typeof x === 'string' ? x : langMap(x?.name)).join(', ')
    : (typeof c === 'string' ? c : langMap(c?.name))

let readingFontFamily = ''   // set by Swift via window.setReadingFont
let fontFaces = ''           // @font-face block (bundled fonts), set by Swift

// Reader customization (Readest ViewSettings parity), set by Swift via applyReaderSettings.
const settings = {
    fontSize: 100,        // %
    fontWeight: 400,
    lineHeight: 1.5,
    paragraphMargin: 1.0, // em
    textIndent: 0,        // em
    wordSpacing: 0,       // px
    letterSpacing: 0,     // px
    justify: true,
    hyphenate: true,
    margin: 48,           // px (page margin)
    maxInlineSize: 720,   // px (column width)
    gap: 6,               // % gap between columns
    columns: 1,           // max-column-count (1 or 2)
    flow: 'paginated',    // or 'scrolled'
    animated: true,       // page-flip animation
    invertImages: false,  // invert images in dark mode
    theme: 'default',     // default | sepia | gray | black
}

const THEME_BG = { sepia: '#f4ecd8', gray: '#c9c9c9', black: '#000000' }
const THEME_FG = { sepia: '#5b4636', gray: '#1a1a1a', black: '#cfcfcf' }

const buildCSS = () => {
    const bg = THEME_BG[settings.theme], fg = THEME_FG[settings.theme]
    return `
    ${fontFaces}
    @namespace epub "http://www.idpf.org/2007/ops";
    html { color-scheme: light dark; font-size: ${settings.fontSize}%; }
    ${bg ? `html, body { background: ${bg} !important; color: ${fg} !important; }` : ''}
    ${readingFontFamily ? `html, body, p, li, blockquote, dd, h1, h2, h3, h4, h5, h6 {
        font-family: ${readingFontFamily} !important; }` : ''}
    body { font-weight: ${settings.fontWeight}; }
    p, li, blockquote, dd {
        line-height: ${settings.lineHeight};
        margin-block: ${settings.paragraphMargin}em;
        text-align: ${settings.justify ? 'justify' : 'start'};
        word-spacing: ${settings.wordSpacing}px;
        letter-spacing: ${settings.letterSpacing}px;
        -webkit-hyphens: ${settings.hyphenate ? 'auto' : 'manual'};
        hyphens: ${settings.hyphenate ? 'auto' : 'manual'}; }
    p { text-indent: ${settings.textIndent}em; }
    pre { white-space: pre-wrap !important; }
    ${settings.invertImages ? `@media (prefers-color-scheme: dark) {
        img, image, picture, svg, video { filter: invert(1) hue-rotate(180deg); } }` : ''}
`
}

function applyLayout() {
    if (!view) return
    const r = view.renderer
    r.setAttribute('flow', settings.flow)
    r.setAttribute('margin', settings.margin + 'px')
    r.setAttribute('max-inline-size', settings.maxInlineSize + 'px')
    r.setAttribute('max-column-count', String(settings.columns))
    r.setAttribute('gap', settings.gap + '%')
    if (settings.animated) r.setAttribute('animated', '')
    else r.removeAttribute('animated')
    r.setStyles?.(buildCSS())
}

async function openBook(file) {
    try {
        if (!customElements.get('foliate-view')) {
            showStatus('Reader engine not registered (<foliate-view> undefined) — view.js did not load.')
            post('error', { message: 'foliate-view custom element undefined' })
            return
        }
        showStatus('Opening book…')
        view = document.createElement('foliate-view')
        document.body.append(view)
        // Track the on-screen section doc (for reading the live selection) and
        // paint highlights foliate asks us to draw.
        view.addEventListener('load', e => {
            currentDoc = e.detail.doc; currentIndex = e.detail.index
            const doc = e.detail.doc, idx = e.detail.index
            doc.addEventListener('selectionchange', () => {
                const sel = doc.getSelection?.()
                const s = sel ? sel.toString() : ''
                if (s && s.trim() && sel.rangeCount) {
                    lastSelText = s; lastSelRange = sel.getRangeAt(0).cloneRange()
                    lastSelDoc = doc; lastSelIndex = idx
                }
            })
        })
        view.addEventListener('draw-annotation', e => {
            const { draw, annotation } = e.detail
            // e-ink: a 0.3-opacity yellow fill is invisible on a grayscale Boox screen.
            // Paint the fill AND a solid black underline in one group so the highlight
            // always reads on e-ink (the underline carries it; the band adds context).
            draw(einkHighlight, { color: annotation.color || '#ffd60a' })
        })
        // Tapping an existing highlight → offer to delete it.
        view.addEventListener('show-annotation', e => {
            post('tapAnnotation', { cfi: e.detail.value })
        })
        // Internal links → navigate in-book; external links → open via the app
        // (window.open is blocked in WKWebView). preventDefault stops foliate's own
        // default so we handle both.
        view.addEventListener('link', e => {
            e.preventDefault?.()
            try { view.goTo(e.detail.href) } catch (err) {}
        })
        view.addEventListener('external-link', e => {
            e.preventDefault?.()
            post('openExternal', { href: e.detail.href })
        })
        await view.open(file)
        applyLayout()
        await view.renderer.next()
        if (msg) msg.style.display = 'none'
        const book = view.book
        post('loaded', {
            title: langMap(book.metadata?.title) || 'Untitled',
            author: contributor(book.metadata?.author),
        })
        // Surface the table of contents (flattened with depth for indenting).
        try {
            const toc = []
            const walk = (items, depth) => {
                for (const it of (items || [])) {
                    if (it.label) toc.push({ label: String(it.label).trim(), href: it.href || '', depth })
                    if (it.subitems) walk(it.subitems, depth + 1)
                }
            }
            walk(book.toc, 0)
            post('toc', { items: toc })
        } catch (e) {}
        view.addEventListener('relocate', e => post('relocate', {
            fraction: e.detail.fraction ?? 0,
            cfi: e.detail.cfi || '',
        }))
    } catch (e) {
        showStatus('Open failed: ' + errText(e))
        post('error', { message: String(e) })
    }
}

// Swift → JS: open the book by fetching it from our same-origin scheme.
// foliate's view.open(string) → makeBook(string) → fetch(url) → Blob.
window.openBookURL = (url) => {
    if (!url) { showStatus('App handed over an empty book URL.'); return }
    openBook(url)
}

// Swift → JS: hand over the epub as base64 (fallback path).
window.openBookBase64 = (b64) => {
    try {
        if (!b64) { showStatus('App handed over an empty book payload.'); return }
        const bin = atob(b64)
        const bytes = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
        const file = new File([bytes], 'book.epub', { type: 'application/epub+zip' })
        openBook(file)
    } catch (e) { showStatus('Decoding book bytes failed: ' + errText(e)); post('error', { message: String(e) }) }
}

// Swift can call this to print a message on screen (e.g. when the bytes handoff fails).
window.showReaderError = (text) => showStatus(String(text))

// Swift → JS: highlight the current text selection. Paints it, saves the CFI,
// and posts the text + location back so Swift can log it to Notes & Annotations.
window.highlightSelection = () => {
    try {
        // Prefer the tracked last selection (the menu clears the live one first);
        // fall back to scanning live selections.
        let text = lastSelText, range = lastSelRange, idx = lastSelIndex, doc = lastSelDoc
        if (!text || !range) {
            const candidates = []
            if (currentDoc) candidates.push([currentDoc, currentIndex])
            document.querySelectorAll('iframe').forEach(f => {
                try { if (f.contentDocument) candidates.push([f.contentDocument, currentIndex]) } catch (e) {}
            })
            for (const [d, i] of candidates) {
                const s = d.getSelection && d.getSelection()
                if (s && !s.isCollapsed && s.rangeCount && s.toString().trim()) {
                    text = s.toString(); range = s.getRangeAt(0); idx = i; doc = d; break
                }
            }
        }
        if (!text || !range) { post('highlight', { text: '' }); return }
        const cfi = view?.getCFI(idx, range)
        view?.addAnnotation({ value: cfi, color: '#ffd60a' })
        lastSelText = ''; lastSelRange = null
        try { doc?.getSelection()?.removeAllRanges() } catch (e) {}
        post('highlight', { text, cfi })
    } catch (e) { post('highlight', { text: '', error: String(e) }) }
}

// Swift → JS: jump to a TOC entry (or search-result CFI).
window.goToHref = (href) => { if (href) { try { view?.goTo(href) } catch (e) {} } }

// Swift → JS: readable text of the current section (for Read Aloud). Prefers the
// live section doc; falls back to scanning the iframes foliate renders into.
window.getReaderText = () => {
    try {
        const clean = s => (s || '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim()
        if (currentDoc?.body) { const t = clean(currentDoc.body.innerText); if (t) return t }
        for (const f of document.querySelectorAll('iframe')) {
            try { const t = clean(f.contentDocument?.body?.innerText); if (t) return t } catch (e) {}
        }
        return ''
    } catch (e) { return '' }
}

// Swift → JS: full-text search the book; posts up to 200 {cfi, excerpt} results.
window.searchBook = async (query) => {
    try {
        const q = (query || '').trim()
        if (!q) { post('searchResults', { items: [] }); return }
        const items = []
        for await (const r of view.search({ query: q })) {
            if (r.subitems) {
                for (const s of r.subitems) if (s.cfi) items.push({ cfi: s.cfi, excerpt: (s.excerpt || '').trim() })
            } else if (r.cfi) {
                items.push({ cfi: r.cfi, excerpt: (r.excerpt || '').trim() })
            }
            if (items.length >= 200) break
        }
        try { view.clearSearch() } catch (e) {}
        post('searchResults', { items })
    } catch (e) { post('searchResults', { items: [], error: String(e) }) }
}

// Swift → JS controls.
window.pageLeft = () => view?.goLeft()
window.pageRight = () => view?.goRight()
window.setFlow = (f) => { settings.flow = f; applyLayout() }
window.applyCSS = (css) => view?.renderer.setStyles?.(css)
window.setReadingFont = (family) => { readingFontFamily = family || ''; applyLayout() }
// Swift → JS: install the @font-face block for bundled fonts (served via ledgerfont://).
window.setFontFaces = (css) => { fontFaces = css || ''; applyLayout() }
// Swift → JS: merge reader settings (font size, spacing, margins, width, flow…).
window.applyReaderSettings = (obj) => { Object.assign(settings, obj || {}); applyLayout() }
// Swift → JS: remove a highlight by its CFI.
window.deleteHighlight = (cfi) => { if (cfi) view?.deleteAnnotation({ value: cfi }) }
// Swift → JS: re-apply a stored highlight by CFI (called on load to restore saved marks).
// foliate paints it when its section renders (via the draw-annotation handler above).
window.addStoredHighlight = (cfi) => { if (cfi) { try { view?.addAnnotation({ value: cfi, color: '#ffd60a' }) } catch (e) {} } }
window.goToFraction = (f) => view?.goToFraction(f)

post('ready')
