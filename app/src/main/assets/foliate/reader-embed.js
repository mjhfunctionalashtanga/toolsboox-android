import { Overlayer } from './overlayer.js'
import { detectPanels } from './comic-panels.js'
import { G, tapZone, classifyDrag, swipeSide, overscrollTurn, pinchCommits, panStep, makeVelocity }
    from './comic-gestures.js'

let view = null
let currentDoc = null      // the section document currently on screen
let lastVisibleRange = null // where the eye is: foliate's visible range at the last relocate
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

// THE MARK STYLES — Readest's set, drawn for e-ink (Michael, 2026-08-23: the reader "needs more
// highlight and underline options like in the readest that it forks").
//
// EVERY ONE IS DRIVEN BY LUMINANCE, NOT HUE, which is the whole reason there is a table here
// rather than a colour picker. On a grayscale Boox a 0.3-opacity yellow band reads as white — the
// mark is drawn and invisible — so a "colour" choice would offer four ways to mark nothing.
// A highlight therefore always carries its solid underline; the other three ARE solid strokes.
// Colour still rides along for the iPad, where it is real, and costs the Boox nothing.
const MARK_STYLES = {
    highlight: (rects, o) => einkHighlight(rects, o),
    underline: (rects) => Overlayer.underline(rects, { color: '#000000', width: 3 }),
    squiggly:  (rects) => Overlayer.squiggly(rects, { color: '#000000', width: 3 }),
    strike:    (rects) => Overlayer.strikethrough(rects, { color: '#000000', width: 3 }),
}
const styleFor = name => MARK_STYLES[name] || MARK_STYLES.highlight


// --- COMICS: panel-by-panel -----------------------------------------------------------------
//
// The detector lives in comic-panels.js (pure, tested in node); everything here is the WALK and
// the CROP. Two rules from DESIGN-comics-panel-view-2026-08-24:
//
//  • A PANEL STEP IS A CROP OF THE ALREADY-DRAWN PAGE — one CSS transform on the same bitmap,
//    never a reload. On e-ink that is the difference between a quiet redraw per panel and a
//    full refresh per panel (§6).
//  • NEVER TRAP THE READER. Detection returning null (splash, bleed, dark ground, garbage)
//    means the page shows WHOLE and paging is ordinary; a TAP toggles the whole page back any
//    time — always one tap away.
//
// RTL is a stored per-book setting handed in by the app (setComicPrefs), never guessed (§4).
const comic = {
    isComic: false,     // the app says so, off the file extension — a loose zip of images is not
    rtl: false,         // manga reads right-to-left WITHIN a page; per book, stored
    panelMode: false,   // guided reading on/off; per book, stored
    panels: null,       // ordered rects for the CURRENT page (natural-pixel space), null = whole
    idx: -1,            // which panel is on screen; -1 = none
    whole: false,       // the one-tap "show the whole page" override
    img: null,          // the current page's <img> — the bitmap every crop transforms
    docIndex: -1,
    pendingEntry: null, // 'last' after a backward page turn: enter the previous page at its END
    restorePanel: -1,   // reopen where the walk stood (the app remembers per book)
    cache: new Map(),   // page index → rects|null; cleared when RTL flips (order changes)
    manualRect: null,   // the reader's own zoom box (hold-then-drag) — outranks the detector
    panningLive: false, // a pan drag is in flight — defer the host re-centre (layout reads per move thrash e-ink)
    spreadPref: 'none', // the comic's spread, remembered so an open can apply it PRE-first-paint
    zoomPref: 'fit-page',
    // 'auto' resolves to fit-width in landscape, fit-page in portrait (his 09-03 selection).
    effZoom() { return this.zoomPref === 'auto'
        ? (window.innerWidth > window.innerHeight ? 'fit-width' : 'fit-page')
        : this.zoomPref },
    lastTapAt: 0,       // the last CENTRE tap, for the double-tap (zone taps never pair)
    lastTapEndAt: 0,    // the last tap of any kind — a hold right after one is not a capture
    tapZones: 'sides',  // the reader's tap-to-turn setting: 'sides' · 'corners' · 'off' (app pushes)
    xform: { s: 1, tx: 0, ty: 0 },   // the transform on screen, for mapping a lasso back to image px
    pages: [],          // EVERY visible page of the spread: {doc, index, img, panels} in spine order
    spreadZoom: false,  // a double-tap took the whole-spread zoom (numeric outer, host scrolls)
    // 💬 THE BALLOON WALK (Seeneva lift, 2026-08-30): rects come from the app's import-time
    // index, not from any per-page-turn inference. Balloon mode IS the panel walk fed different
    // rects — same steps, same crop, same capture, same fallback law (no balloons = whole page).
    balloonMode: false, // per book, stored by the app beside panelMode (mutually exclusive there)
    balloonIndex: null, // spine index → [[x,y,w,h]…] normalized 0–1, from window.setBalloonIndex
    balloonText: null,  // spine index → ["words", …] parallel to balloonIndex (slice 2, 2026-10-11)
}

// The page's balloons as crop rects: normalized → natural px, padded 6% for breathing room,
// reading-ordered by a row-tolerant sort (Seeneva's PageObjectHelper idea: proportional row
// clustering, then across the row — RTL flips the across).
const balloonsFor = (pg) => {
    const rows = comic.balloonIndex?.[String(pg.index)]
    if (!rows || !rows.length || !pg.img) return null
    const W = pg.img.naturalWidth, H = pg.img.naturalHeight
    if (!W || !H) return null
    // Each balloon carries its WORDS through the reading-order sort (Michael 2026-10-11): the
    // index stores them parallel to the rects, and order is never stored — it's decided here.
    // `ri` is the row's place in the index, so a later push can refresh the words in place.
    const words = comic.balloonText?.[String(pg.index)]
    const rects = rows.map((r, ri) => {
        const pad = 0.06
        const w = r[2] * W, h = r[3] * H
        const px = Math.max(0, r[0] * W - w * pad / 2), py = Math.max(0, r[1] * H - h * pad / 2)
        return { x: px, y: py,
                 w: Math.min(W - px, w * (1 + pad)), h: Math.min(H - py, h * (1 + pad)),
                 text: words?.[ri] ?? '', ri }
    })
    const tol = Math.max(1, H * 0.05)
    rects.sort((a, b) => {
        const ra = Math.round(a.y / tol), rb = Math.round(b.y / tol)
        if (ra !== rb) return ra - rb
        return comic.rtl ? b.x - a.x : a.x - b.x
    })
    return rects
}

// One question for every gate: is A walk on — panels or balloons? The two share every verb.
const walkOn = () => comic.panelMode || comic.balloonMode

// The pages actually ON SCREEN — both halves of a two-page spread, one page otherwise.
// fixed-layout's getContents() already filters to visible frames; spine order IS reading
// order (RTL books are placed visually right-to-left by fixed-layout, and each page's own
// panels come RTL-ordered from the detector), so index-ascending is the walk's order.
const visibleComicPages = () => {
    try {
        if (settings.flow === 'scrolled') return []
        const cs = view?.renderer?.getContents?.() || []
        return cs
            .filter(c => c?.doc && c.index != null)
            .map(c => ({ doc: c.doc, index: c.index, img: c.doc.querySelector('img'), panels: null }))
            .filter(pg => pg.img)
            .sort((a, b) => a.index - b.index)
    } catch (e) { return [] }
}

const panelsFor = async (index, img) => {
    if (comic.cache.has(index)) return comic.cache.get(index)
    const W = img.naturalWidth, H = img.naturalHeight
    if (!W || !H) return null
    // Detection does not need full resolution — this is also the speed budget (§3).
    const scale = Math.min(1, 480 / W)
    const w = Math.max(1, Math.round(W * scale)), h = Math.max(1, Math.round(H * scale))
    let rects = null
    try {
        const canvas = document.createElement('canvas')
        canvas.width = w; canvas.height = h
        const ctx = canvas.getContext('2d', { willReadFrequently: true })
        ctx.drawImage(img, 0, 0, w, h)
        rects = detectPanels(ctx.getImageData(0, 0, w, h), { rtl: comic.rtl })
        // Remember WHY a page has no walk ('incomplete' = the coverage guarantee refused a
        // partial verdict) so the why-whole toast can say the honest sentence.
        if (!rects) (comic.reasons ??= new Map()).set(index, detectPanels.lastReason || 'none')
        if (rects) {
            const inv = 1 / scale
            rects = rects.map(r => ({ x: r.x * inv, y: r.y * inv, w: r.w * inv, h: r.h * inv }))
        }
    } catch (e) { rects = null }
    comic.cache.set(index, rects)
    return rects
}

// The crop: scale + translate the page's own <img> so the panel fills the page box, centred.
// The doc's coordinate space IS the image's natural size (comic-book.js emits no viewport meta,
// so fixed-layout falls back to the image dimensions) — the outer fit-to-screen scaling is
// fixed-layout's and is untouched.
const applyPanelCrop = () => {
    if (!comic.img && !(comic.pages && comic.pages.length)) return
    // The reader's own box outranks the detector — it exists precisely for the pages where
    // detection fell back (overlaps, bleeds, dark ground) or cut wrong.
    const chosen = comic.manualRect
        || (!comic.whole && comic.panels && comic.idx >= 0 ? comic.panels[comic.idx] : null)
    // THE CROP KNOWS ITS PAGE (-43): a spread's walk carries each panel's owning page, and the
    // crop transforms THAT page's bitmap while every other visible page rests plain — so a
    // step from the left page's last panel to the right page's first is two transforms, still
    // no re-render. Single page: owner is the page, exactly as before.
    const ownerImg = (chosen && chosen.pg && chosen.pg.img) || comic.img
    const everyImg = (comic.pages && comic.pages.length)
        ? comic.pages.map(pg => pg.img) : (comic.img ? [comic.img] : [])
    for (const other of everyImg) {
        if (other === ownerImg && chosen) continue
        try {
            other.style.transform = ''
            other.ownerDocument.documentElement.style.overflow = ''
        } catch (e) {}
    }
    if (!chosen) {
        comic.xform = { s: 1, tx: 0, ty: 0 }
        // A live crop may have raised the OUTER zoom (below); a cleared crop hands the page
        // back to the reader's own preference — unless a whole-spread double-tap zoom is the
        // thing on screen, which is the reader's own posture, not ours to reset.
        try {
            const rEl = view?.renderer
            if (rEl && !comic.spreadZoom && rEl.getAttribute('zoom') !== comic.effZoom())
                rEl.setAttribute('zoom', comic.effZoom())
        } catch (e) {}
        return
    }
    const img = ownerImg
    if (!img) return
    const doc = img.ownerDocument
    // A crop's owner becomes the active page: the pan that slides the crop window, the
    // capture, and the state post all speak about the page the reader is actually inside.
    comic.img = img
    if (chosen.pg) comic.docIndex = chosen.pg.index
    const W = img.naturalWidth, H = img.naturalHeight
    // Breathing margin around the art — 1.5% of the page's long side (field, 08-28: panels
    // should not clip at the exact gutter; the old 1% read tight on dense pages).
    const pad = Math.round(Math.max(W, H) * 0.015)
    const padded = (q) => {
        const px = Math.max(0, q.x - pad), py = Math.max(0, q.y - pad)
        return { rx: px, ry: py, rw: Math.min(W - px, q.w + pad * 2), rh: Math.min(H - py, q.h + pad * 2) }
    }
    const { rx, ry, rw, rh } = padded(chosen)
    const sFill = Math.min(W / rw, H / rh)
    let s = sFill
    // THE CROP FILLS THE VIEWPORT, AND THE WALK RENDERS ONCE (field, 08-28, both sides of it:
    // "only shows a smaller portion of the canvas" AND "step panels doesn't work very well").
    // The in-page transform can never exceed the page's own box — the iframe clips at W×H —
    // so the OUTER zoom is what makes a crop meet the live viewport (-41). But -41 computed
    // that outer PER PANEL, so every step re-rendered through the attribute door: a flash and
    // a stall per panel on e-ink, against the canon's "panel step = CSS-transform crop, never
    // a reload". The outer is ONE VALUE PER PAGE now — the largest any of its panels needs —
    // so the attribute (epsilon-guarded) holds still across the whole walk and each step is a
    // pure in-page transform; every panel takes the in-page scale that meets the viewport
    // INSIDE that outer, capped at the page-box fill. A DRAWN box stays exact-per-crop — one
    // deliberate act, not a sequence — and rotation still recomputes via the -35 refit door.
    try {
        const rEl = view?.renderer
        const vw = rEl?.clientWidth || 0, vh = rEl?.clientHeight || 0
        if (rEl && vw > 0 && vh > 0) {
            // The FLOOR is the fit of everything on screen — on a spread, both pages side by
            // side (ΣW × maxH), so the outer raise never squeezes below the spread's own fit.
            // The per-panel want is unchanged by the neighbour: a crop meets the viewport when
            // ITS page's box does, wherever the other half of the spread stands.
            const pgs = (comic.pages && comic.pages.length)
                ? comic.pages : [{ img }]
            let sumW = 0, maxH = 0
            for (const pg of pgs) {
                sumW += pg.img.naturalWidth
                maxH = Math.max(maxH, pg.img.naturalHeight)
            }
            const fit = Math.min(vw / (sumW || W), vh / (maxH || H))
            const padOf = (pw, ph) => Math.round(Math.max(pw, ph) * 0.015)
            const wantFor = (q) => {
                const pw = (q.pg && q.pg.img) ? q.pg.img.naturalWidth : W
                const ph = (q.pg && q.pg.img) ? q.pg.img.naturalHeight : H
                const qp = padOf(pw, ph)
                const qx = Math.max(0, q.x - qp), qy = Math.max(0, q.y - qp)
                const qw = Math.min(pw - qx, q.w + qp * 2), qh = Math.min(ph - qy, q.h + qp * 2)
                return Math.min(vw / qw, vh / qh) / Math.min(pw / qw, ph / qh)
            }
            let want
            // PLAIN ZOOM RIDES TO FULL WIDTH (field, 08-30: in landscape fit-page "it doesn't
            // zoom into the full width — it stays with a crop"). A double-tap/pinch region is
            // the reader's own view, and the -40 law applies to it: fill the WIDTH, overflow
            // pans (the crop-window drag). Walk crops keep fit-whole — a panel or balloon must
            // be entirely on screen; that is the walk's contract, not a zoom preference.
            if (comic.manualRect) want = (vw / rw) / sFill
            else {
                want = 0
                for (const q of comic.panels) want = Math.max(want, wantFor(q))
            }
            const outer = Math.min(Math.max(want, fit), fit * 6)
            s = comic.manualRect
                ? Math.min(sFill, (vw / rw) / outer)
                : Math.min(sFill, Math.min(vw / rw, vh / rh) / outer)
            const cur = parseFloat(rEl.getAttribute('zoom'))
            if (!(isFinite(cur) && Math.abs(cur - outer) < 0.01))
                rEl.setAttribute('zoom', String(outer))
        }
    } catch (e) {}
    const tx = (W / s - rw) / 2 - rx
    const ty = (H / s - rh) / 2 - ry
    doc.documentElement.style.overflow = 'hidden'
    img.style.transformOrigin = '0 0'
    img.style.transform = `scale(${s}) translate(${tx}px, ${ty}px)`
    comic.xform = { s, tx, ty }
    // …and the HOST re-centres: a half-claimed native pan (or our own) may have nudged the
    // page box off-centre, and a crop centred in a box that isn't centred reads as a crop
    // that isn't. Deferred while a pan drag is live — scrollWidth/clientWidth are layout
    // reads, and paying them per pointermove is its own jank on e-ink.
    if (!comic.panningLive) try {
        const r = view?.renderer
        if (r) {
            // Centre the OWNING page's box. On a single page that is the whole span (the old
            // midpoint); on a spread it slides the active half under the eye, so a right-page
            // panel doesn't sit half off-screen behind the left page.
            const frame = doc.defaultView?.frameElement
            const box = frame?.parentElement
            if (box && comic.pages && comic.pages.length > 1) {
                const bRect = box.getBoundingClientRect()
                const rRect = r.getBoundingClientRect()
                r.scrollLeft = Math.max(0, r.scrollLeft
                    + (bRect.left + bRect.width / 2) - (rRect.left + rRect.width / 2))
                r.scrollTop = Math.max(0, r.scrollTop
                    + (bRect.top + bRect.height / 2) - (rRect.top + rRect.height / 2))
            } else {
                r.scrollLeft = Math.max(0, (r.scrollWidth - r.clientWidth) / 2)
                r.scrollTop = Math.max(0, (r.scrollHeight - r.clientHeight) / 2)
            }
        }
    } catch (e) {}
}

const postPanelState = () => { updatePageRead(); return post('panelStep', {
    page: comic.docIndex,
    panel: comic.panels && comic.idx >= 0 ? comic.idx : -1,
    count: comic.panels ? comic.panels.length : 0,
    whole: comic.whole,
    // A zoom (a double-tap's, a drawn one, a spread's) — with page, panel and whole, what tells the
    // app a STEP (a turn to full screen's peek) from a re-lay that re-posts the same place
    // (2026-10-12, iOS's twin; ReaderFullScreen.walkMove).
    zoomed: !!(comic.manualRect || comic.spreadZoom),
    // WHY a page shows whole, so the app can say so instead of silence (field, 08-30:
    // "doesn't zoom" — the fallback law firing invisibly reads as broken).
    mode: comic.balloonMode ? 'balloon' : (comic.panelMode ? 'panel' : 'off'),
    indexed: !!comic.balloonIndex,
    // The walk's balloons' words in READING order (slice 2) — what read-aloud will speak.
    texts: comic.balloonMode && comic.panels ? comic.panels.map(q => q.text || '') : undefined,
    reason: (!comic.balloonMode && (!comic.panels || comic.whole))
        ? (comic.reasons?.get(comic.docIndex) ?? null) : null,
}) }

// Step the walk. TRUE = handled here; FALSE = let the ordinary page turn happen (which is also
// the fallback for a page with no detected panels — normal paging, exactly as designed).
const comicStep = (forward) => {
    if (!comic.isComic || !walkOn()) return false
    if (settings.flow === 'scrolled') return false   // vertical scroll IS the walk there
    if (!comic.panels) {
        // No walk on THIS page (splash / fallback) — but the turn's direction still decides
        // where the NEXT page's walk enters: stepping backward off a splash must land on the
        // previous page's LAST panel, not its first.
        comic.pendingEntry = forward ? null : 'last'
        return false
    }
    comic.whole = false   // stepping resumes the walk from wherever it stood
    comic.manualRect = null
    const n = comic.idx + (forward ? 1 : -1)
    if (n < 0) { comic.pendingEntry = 'last'; return false }        // off the front → prev page, last panel
    if (n >= comic.panels.length) { comic.pendingEntry = null; return false }  // off the end → next page
    comic.idx = n
    applyPanelCrop()
    postPanelState()
    return true
}

// A comic page rendered: wire its touch, and — ONLY IF IT IS THE PAGE ON SCREEN — hand the
// walk to it. fixed-layout PRERENDERS neighbour pages, and their 'load' events arrive after
// the visible page's; trusting load-order left the walk standing on a page that was off
// screen (docIndex 1 while the screen showed 0), which is a walk that visibly does nothing.
const comicPageLoaded = async (doc, index) => {
    if (!comic.isComic) return
    // ONE INVOCATION WINS (field, 08-30: "Panel by panel doesn't zoom nor does balloon" — the
    // rig reproduced a FROZEN crop). The old stale-guard compared page identity, but a MODE
    // change re-invokes on the SAME page: the older run, parked at its detection await, resumed
    // and stomped the newer run's crop with stale rects. Every open does this on device — the
    // prefs push and the balloon-index push land back-to-back. A sequence token guards every
    // await instead: only the newest invocation may write.
    const seq = (comic.loadSeq = (comic.loadSeq || 0) + 1)
    // A page landing clears any touch state that has gone silent (a lift the digitiser
    // swallowed) — never a live drag (Michael, 2026-10-11 pass; the COMIC-GESTURES block).
    resetComicGestures(false)
    attachComicTouch(doc)   // every rendered page gets its touch, shown or prerendered
    updatePageRead()
    applyComicTouchAction(doc)
    let visIdx = index
    try { const i = view?.renderer?.index; if (i != null && i >= 0) visIdx = i } catch (e) {}
    // THE SPREAD IS THE PAGE (field, 08-28: "it zooms in on only one page which is weird when
    // in landscape on two pages"). The old gate kept only renderer.index's page, so the second
    // half of a two-page spread was invisible to the walk and to every zoom. Every VISIBLE
    // page joins now; a prerendered (hidden) neighbour still stays out.
    const vis = visibleComicPages()
    const mine = vis.find(pg => pg.index === index)
    if (!mine && index !== visIdx) return   // a prerendered neighbour — not the walk's page
    const primary = vis.find(pg => pg.index === visIdx) || mine
        || { doc, index, img: doc.querySelector('img'), panels: null }
    const img = primary.img
    comic.img = img; comic.docIndex = primary.index
    comic.pages = vis.length ? vis : (img ? [primary] : [])
    comic.panels = null; comic.idx = -1; comic.whole = false; comic.manualRect = null
    comic.spreadZoom = false
    if (!img) return
    if (!walkOn() || settings.flow === 'scrolled') { applyPanelCrop(); return }
    // NOT img.decode() — in this WebView it never settles for an image inside foliate's
    // iframe, and the walk suspended on that await forever (panels never detected, crop never
    // applied: "panel by panel doesn't change things", found by tracing in the Chrome harness).
    // Wait for completeness the boring way, with a timeout so the walk can never hang.
    for (const pg of comic.pages) {
        if (!pg.img.complete || !pg.img.naturalWidth) {
            await new Promise(res => {
                pg.img.addEventListener('load', res, { once: true })
                pg.img.addEventListener('error', res, { once: true })
                setTimeout(res, 3000)
            })
        }
    }
    if (seq !== comic.loadSeq || comic.img !== img) return   // superseded or moved on
    // Detect per page, then WALK THE SPREAD FLAT: left page's panels then right's (spine
    // order), each rect carrying its owning page — the step and the crop never care which
    // half of the spread a panel lives on.
    const flat = []
    for (const pg of comic.pages) {
        // Balloon mode reads the app's cached index (no inference here, ever); the panel walk
        // keeps its own in-page detector. Either way, null = whole page — the fallback law.
        pg.panels = comic.balloonMode ? balloonsFor(pg) : await panelsFor(pg.index, pg.img)
        if (pg.panels) for (const q of pg.panels) flat.push({ x: q.x, y: q.y, w: q.w, h: q.h, pg, text: q.text, ri: q.ri })
    }
    if (seq !== comic.loadSeq || comic.img !== img) return
    comic.panels = flat.length ? flat : null
    if (comic.panels) {
        let start = 0
        if (comic.pendingEntry === 'last') start = comic.panels.length - 1
        else if (comic.restorePanel >= 0 && comic.restorePanel < comic.panels.length) start = comic.restorePanel
        comic.idx = start
    }
    comic.pendingEntry = null
    comic.restorePanel = -1
    applyPanelCrop()
    postPanelState()
}

// ZOOM THE WHOLE THING (field, 08-28: "when my comics are in two page mode or landscape and
// I zoom, it zooms in on only one page … I should be able to zoom the whole thing"). On a
// spread, an in-page crop can only ever magnify ONE page's bitmap inside one iframe — so the
// whole-spread zoom rides the OUTER rail instead: a numeric zoom on the renderer (both pages
// scale together, exactly like the ladder) with the host scrolled so the tapped point stays
// under the eye. Toggles: the second double-tap hands back the reader's zoom preference.
const spreadTapZoom = (doc, cx, cy) => {
    try {
        const r = view?.renderer
        if (!r) return
        if (comic.spreadZoom) {
            comic.spreadZoom = false
            r.setAttribute('zoom', comic.effZoom())
            return
        }
        const img = doc.querySelector('img')
        const frame = doc.defaultView?.frameElement
        if (!img || !frame) return
        const fRect = frame.getBoundingClientRect()
        const rRect = r.getBoundingClientRect()
        const eff = fRect.width / img.naturalWidth   // effective outer scale, factor included
        if (!(eff > 0)) return
        const factor = parseFloat(r.getAttribute('scale-factor')) || 1
        const hostX = fRect.left - rRect.left + cx * eff
        const hostY = fRect.top - rRect.top + cy * eff
        comic.spreadZoom = true
        r.setAttribute('zoom', String((eff * 2) / factor))
        // ANCHOR BY THE FRAME, NEVER BY SCALED SCROLL ARITHMETIC (field, 08-30: a lasso on
        // a wide page "takes me to another page much further in the comic"). scrollLeft deep
        // in a book is pages wide; multiplying it by the zoom factor assumes the whole
        // scroller scales from its origin — it does not have to, and when it doesn't the
        // product is a page-fling. The attribute door renders synchronously (-35), so the
        // owning frame can be re-measured NOW and the target point placed absolutely.
        const cxN = cx / 1, cyN = cy / 1   // doc-client coords ARE natural page coords
        const fNew = frame.getBoundingClientRect()
        const rNew = r.getBoundingClientRect()
        const effNew = fNew.width / img.naturalWidth
        r.scrollLeft = Math.max(0, r.scrollLeft + (fNew.left - rNew.left) + cxN * effNew - rNew.width / 2)
        r.scrollTop = Math.max(0, r.scrollTop + (fNew.top - rNew.top) + cyN * effNew - rNew.height / 2)
    } catch (e) {}
}

// >>> COMIC-GESTURES >>> ─────────────────────────────────────────────────────────────────────
// A SHARED BLOCK — MEANT TO BE (Michael, 2026-10-11, the comics gesture pass): everything from
// here to the closing sentinel is to be byte-identical in the iPad's
// Resources-foliate-js/reader-embed.js, as is comic-gestures.js, which holds the pure decisions
// this wiring makes. NOT YET: the iPad's slice 1 (ebfee6df) is its own implementation. Its port
// replaces that with this block (the iPad's back-edge band stays outside the sentinels), and
// scripts/check-comic-gestures-twin.sh fails until it lands. After that, edit both repos or
// neither. Platform differences live OUTSIDE the sentinels — the app answers the 'tap' message,
// and pushes its tap-zone setting.

// THE COMIC LAYER OWNS EVERY GESTURE. Sharing custody with the browser was the bug factory:
// touch-action pan-x/pan-y handed one-finger drags to native panning, which had nothing to pan
// inside the iframe — drags died ("trouble scrolling the comics at all"), the HOST got nudged
// off-centre by half-claimed gestures (the lasso zoom "not centred anymore"), and swallowed
// pointer-ups re-armed the hold timer (the annotation menu back on a double-tap). So:
// touch-action none on comic docs, always — and PAN IS OURS: a plain drag scrolls the host
// renderer directly. One finger, deterministic, e-ink-friendly.
const applyComicTouchAction = (doc) => {
    try {
        doc.documentElement.style.touchAction = 'none'
        doc.querySelectorAll('img').forEach(i => { i.style.touchAction = 'none' })
    } catch (e) {}
}

// PAGE PX → SCREEN PX. fixed-layout CSS-scales each page iframe, so a comic doc's clientX is in
// PAGE pixels, and every threshold measured there drifted with the scan's size — a 16%-of-width
// swipe or a 24px pinch meant one thing on a 1000px page and another on a 3000px one. k is the
// frame's on-screen width over the doc's own width; screen = frame origin + page × k. The top
// document is k = 1 at the origin. Every length the gesture code compares is a screen length.
const comicScreen = (doc) => {
    try {
        const fe = doc?.defaultView?.frameElement
        if (fe) {
            const f = fe.getBoundingClientRect()
            const k = f.width / (doc.documentElement?.clientWidth || f.width || 1)
            if (k > 0 && isFinite(k)) return { k, left: f.left, top: f.top }
        }
    } catch (e) {}
    return { k: 1, left: 0, top: 0 }
}

// The crop on screen, if any: the reader's own box (double-tap, pinch, hold-then-drag), else the
// walk's current panel/balloon. One question, asked by the pan, the classifier and the capture.
const comicCrop = () => comic.manualRect ||
    (walkOn() && !comic.whole && comic.panels && comic.idx >= 0 ? comic.panels[comic.idx] : null)

const comicZoom = () => comicCrop() ? 'crop' : (comic.spreadZoom ? 'spread' : 'none')

// WHAT A DRAG IS, decided once at the first move past slop (classifyDrag): a PAN of the crop, a
// SCROLL of the host, or a SWIPE that turns the page on lift. Zoomed views pan until they are
// against the edge the finger is pushing toward; a page at fit with nothing to scroll swipes.
const comicClassify = (sdx, sdy) => {
    const c = comicCrop()
    const cImg = (c && c.pg && c.pg.img) || comic.img
    let scroller = null
    try {
        const r = view?.renderer
        if (r) scroller = { x: r.scrollLeft, maxX: r.scrollWidth - r.clientWidth,
                            y: r.scrollTop, maxY: r.scrollHeight - r.clientHeight }
    } catch (e) {}
    return classifyDrag({ dx: sdx, dy: sdy, paginated: settings.flow !== 'scrolled', zoom: comicZoom(),
        crop: c && cImg ? { x: c.x, maxX: Math.max(0, cImg.naturalWidth - c.w) } : null, scroller })
}

// THE PAN — one finger. INSIDE A ZOOM the crop window itself slides over the page ("I want to be
// able to scroll after zoom crop"): the rect moves, the bitmap stays, one transform. A walk crop
// becomes a manual view on first pan (stepping the walk clears it and resumes). Screen px in.
const comicPanCropBy = (sdx, sdy) => {
    const cropped = comicCrop()
    if (!cropped || !comic.img) return
    comic.panningLive = true
    const img = comic.img
    const W = img.naturalWidth, H = img.naturalHeight
    const rect = comic.manualRect
        || { x: cropped.x, y: cropped.y, w: cropped.w, h: cropped.h, pg: cropped.pg }
    const k = comicScreen(img.ownerDocument).k * (comic.xform.s > 0 ? comic.xform.s : 1)
    rect.x = Math.min(Math.max(0, rect.x - sdx / k), Math.max(0, W - rect.w))
    rect.y = Math.min(Math.max(0, rect.y - sdy / k), Math.max(0, H - rect.h))
    comic.manualRect = rect
    applyPanelCrop()
}

// THE SCROLL — with no crop active the host scroller moves: the fit-width tall page, the
// whole-spread zoom, the scrolled flow. Screen px in.
const comicScrollBy = (sdx, sdy) => {
    try {
        const r = view?.renderer
        if (r) { r.scrollLeft -= sdx; r.scrollTop -= sdy }
    } catch (e) {}
}

// ONE PAINT PER FRAME (Michael, 2026-10-11 pass; plan §5): a digitiser streams moves faster than
// e-ink can paint them, and every move used to repaint. Deltas accumulate here and land once per
// frame. requestAnimationFrame can stall in a throttled WebView (the -14 lesson), so a 34ms timer
// races it — whichever comes first paints. A lift flushes synchronously before it decides.
const comicPend = { pan: null, scroll: null, queued: false }
const comicFlush = () => {
    comicPend.queued = false
    const p = comicPend.pan, s = comicPend.scroll
    comicPend.pan = comicPend.scroll = null
    if (p) comicPanCropBy(p.x, p.y)
    if (s) comicScrollBy(s.x, s.y)
}
const comicQueue = (which, sdx, sdy) => {
    const cur = comicPend[which] || { x: 0, y: 0 }
    cur.x += sdx; cur.y += sdy
    comicPend[which] = cur
    if (comicPend.queued) return
    comicPend.queued = true
    let done = false
    const run = () => { if (done) return; done = true; comicFlush() }
    try { requestAnimationFrame(run) } catch (e) {}
    setTimeout(run, 34)
}

// A finished pan settles once: the host re-centre was deferred while the drag was live.
const comicSettle = () => {
    if (!comic.panningLive) return
    comic.panningLive = false
    try { applyPanelCrop() } catch (e) {}
}

// A PAGE TURN BY SIDE. A swipe that brings in the right-hand page means exactly what a tap on
// the right zone means, so both speak one message — 'tap' with the spatial side — and the APP
// owns the reading direction: a manga's next page is to the LEFT (ReaderFragment.pageTurn, the
// iPad's onTap). The swipe used to call pageRight itself, which read every manga backwards.
const comicTurnSide = (side) => post('tap', { zone: side })

// TAP-TO-PAN (Mihon's pager, as an idea): while zoomed, a side-zone tap slides the view one
// window in the reading direction, then one row, and only at the end of the page turns it. Only
// the reader's OWN zoom would pan this way; a walk crop keeps stepping the walk on a side tap.
// PARKED (review 2026-10-11; critique #31 — it waits for Michael): it turns one side tap into up
// to four taps to reach the next page, against "no workflow gets more taps", and the iPad
// doesn't do it. So a side tap turns at once while zoomed, exactly as the native strips did.
// Flip COMIC_TAP_PAN on his yes; the code below is kept whole for that day.
const COMIC_TAP_PAN = false
const comicTapPans = () => COMIC_TAP_PAN && !!(comic.manualRect || comic.spreadZoom) && !(walkOn() && comic.panels)

// The crop's visible height in image px — the window a vertical step may slide without skipping.
const comicVisibleImgH = () => {
    try {
        const img = comic.img, doc = img?.ownerDocument
        const fe = doc?.defaultView?.frameElement, r = view?.renderer
        if (!fe || !r) return 0
        const f = fe.getBoundingClientRect(), rr = r.getBoundingClientRect()
        const k = f.height / (doc.documentElement.clientHeight || f.height || 1)
        const h = Math.min(f.bottom, rr.bottom) - Math.max(f.top, rr.top)
        return h > 0 && k > 0 ? h / k / (comic.xform.s > 0 ? comic.xform.s : 1) : 0
    } catch (e) { return 0 }
}

const comicTapPan = (side) => {
    // Forward is the right zone in a western book and the left in a manga; a manga's row also
    // runs right-to-left, so the window slides leftward going forward.
    const fwd = (side === 'right') !== comic.rtl
    const across = comic.rtl ? -1 : 1
    const dirX = fwd ? across : -across, dirY = fwd ? 1 : -1
    try {
        if (comic.manualRect && comic.img) {
            const m = comic.manualRect
            const img = (m.pg && m.pg.img) || comic.img
            const visH = comicVisibleImgH()
            const step = panStep({ x: m.x, y: m.y, w: m.w, h: m.h,
                W: img.naturalWidth, H: img.naturalHeight,
                stepX: m.w, stepY: visH > 0 ? Math.min(m.h, visH) : m.h }, dirX, dirY)
            if (!step) return false
            comic.manualRect = { ...m, x: step.x, y: step.y }
            applyPanelCrop()
            postPanelState()
            return true
        }
        if (comic.spreadZoom) {
            const r = view?.renderer
            if (!r) return false
            const step = panStep({ x: r.scrollLeft, y: r.scrollTop, w: r.clientWidth, h: r.clientHeight,
                W: r.scrollWidth, H: r.scrollHeight, stepX: r.clientWidth, stepY: r.clientHeight }, dirX, dirY)
            if (!step) return false
            r.scrollLeft = step.x; r.scrollTop = step.y
            return true
        }
    } catch (e) {}
    return false
}

// THE TAP ROUTER'S SIDE ZONES (Michael, 2026-10-11): the Boox's native tap strips sat ON TOP of
// the comic and swallowed every drag, pinch and hold that started in them, so the zones live
// here now, measured in screen px against the reader's own tap-zone setting (sides · corners ·
// off). Checked BEFORE hot zoom, as the native strips were: a balloon near the edge never eats a
// page turn. And decided AT TOUCH-DOWN: a press that starts in a side zone is a page turn however
// long it lasts (the strips were click listeners with no long-click — a slow e-ink tap of 400 ms
// or more turned the page, and a hold there was never possible). TRUE = a side tap, spent.
const comicZoneTap = (zone) => {
    if (zone !== 'left' && zone !== 'right') return false
    if (comicTapPans() && comicTapPan(zone)) return true
    comicTurnSide(zone)
    return true
}

// EVERY TOUCH LAYER CAN BE TOLD TO LET GO. An e-ink digitiser swallows lifts (the hold-flag bug
// below); a swallowed lift used to leave activeId set, which turned every later one-finger drag
// into a pinch, and the outer pan refused every touch after it. Each layer registers
// {doc, reset(force) → still live?}: a page load clears what has gone silent (STALE_MS), a
// hidden page clears everything. (Subsampling Scale Image View's rule, as an idea: a new first
// finger resets every flag — that half lives in each pointerdown.)
const comicGestureResets = new Set()
const resetComicGestures = (force) => {
    let live = false
    for (const g of [...comicGestureResets]) {
        if (g.doc !== document && !g.doc.defaultView) { comicGestureResets.delete(g); continue }
        try { if (g.reset(force)) live = true } catch (e) {}
    }
    if (!live) { comicPend.pan = comicPend.scroll = null; comicSettle() }
}
document.addEventListener('visibilitychange', () => resetComicGestures(true))

// ONE FINGER MOVES THE COMIC EVERYWHERE (field, 08-30: "move the comics more easily with
// one finger instead of two"). The page doc already owns one-finger pan (below), but a drag
// that lands OUTSIDE the page iframe — the outer zoom's scroller, the letterbox margins, a
// spread's gutter — fell through to native handling, which wants two fingers on this
// hardware. Comics are not ink surfaces: there is no palm to reject, so the TOP document
// gets the same grammar — one pointer owns down-to-up, a drag past slop pans, scrolls or
// swipes exactly as it would on the art, a clean tap speaks the same side zones, and a drag
// suppresses the click it would otherwise fire. Attached once; inert unless a comic is open.
let outerPanAttached = false
const attachOuterComicPan = () => {
    if (outerPanAttached) return
    outerPanAttached = true
    let id = null, idType = '', sx = 0, sy = 0, lx = 0, ly = 0, t0 = 0, seenAt = 0
    let panned = false, kind = null
    const vel = makeVelocity()
    const reset = () => { id = null; panned = false; kind = null; vel.reset() }
    comicGestureResets.add({ doc: document, reset: (force) => {
        if (id === null) return false
        if (!force && Date.now() - seenAt <= G.STALE_MS) return true
        reset(); return false
    } })
    document.addEventListener('pointerdown', e => {
        if (!comic.isComic) return
        if (id !== null) {
            // A NEW FIRST FINGER STARTS CLEAN — and so does any touch after a silent second.
            // Anything else out here is a second contact: a palm, never a gesture.
            const fresh = e.isPrimary && e.pointerType === idType
            if (!fresh && Date.now() - seenAt <= G.STALE_MS) return
            reset()
        }
        id = e.pointerId; idType = e.pointerType
        sx = lx = e.clientX; sy = ly = e.clientY; t0 = seenAt = Date.now()
        panned = false; kind = null
        vel.reset(); vel.add(t0, e.clientX)
        try { document.documentElement.setPointerCapture(e.pointerId) } catch (err) {}
    })
    document.addEventListener('pointermove', e => {
        if (id === null || e.pointerId !== id || !comic.isComic) return
        // A PEN HOVERING over the glass with no button down has LIFTED — its pointer-up was
        // swallowed, and its hover moves (same pointerId) would otherwise keep the gesture fresh
        // forever, so the stale-touch resets never fire. Treat the first one as the lift.
        if (e.pointerType === 'pen' && e.buttons === 0) { onOuterUp(e); return }
        seenAt = Date.now()
        vel.add(seenAt, e.clientX)
        if (!panned) {
            if (Math.abs(e.clientX - sx) <= G.SLOP && Math.abs(e.clientY - sy) <= G.SLOP) return
            panned = true
            kind = comicClassify(e.clientX - sx, e.clientY - sy)
        }
        if (kind === 'pan') comicQueue('pan', e.clientX - lx, e.clientY - ly)
        else if (kind === 'scroll') comicQueue('scroll', e.clientX - lx, e.clientY - ly)
        lx = e.clientX; ly = e.clientY
    })
    const onOuterUp = e => {
        if (id === null || e.pointerId !== id) return
        const wasPan = panned, was = kind
        id = null
        comicFlush()
        const now = Date.now()
        if (wasPan) {
            // A pan is not a tap: swallow the click this drag would fire, once, in capture.
            document.addEventListener('click',
                c => { c.stopPropagation(); c.preventDefault() }, { capture: true, once: true })
            if (was === 'swipe') {
                vel.add(now, e.clientX)
                const side = swipeSide({ dx: e.clientX - sx, dy: e.clientY - sy,
                    vx: vel.vx(now), width: window.innerWidth || 1, ms: now - t0 })
                if (side) { comicSettle(); comicTurnSide(side); return }
            }
            comicSettle()
            return
        }
        // A TAP IN THE MARGIN — the letterbox around a page, a spread's gutter — speaks the same
        // zones as a tap on the art. With the native strips gone it is the only thing that can.
        // A press that STARTED in a side zone turns however long it lasted (comicZoneTap's rule).
        const w = window.innerWidth || 1, h = window.innerHeight || 1
        const zoneDown = tapZone(sx, sy, w, h, comic.tapZones)
        if (zoneDown === 'center' && now - t0 >= G.TAP_MS) return
        comic.lastTapEndAt = now
        const zone = zoneDown !== 'center' ? zoneDown : tapZone(e.clientX, e.clientY, w, h, comic.tapZones)
        comic.lastTapAt = 0
        if (comicZoneTap(zone)) return
        if (comicZoom() === 'none' && !(walkOn() && comic.panels)) post('tap', { zone: 'center', fx: e.clientX / w })
    }
    document.addEventListener('pointerup', onOuterUp)
    const cancel = e => {
        if (id === null || e.pointerId !== id) return
        if (e.type === 'lostpointercapture' && e.target !== document.documentElement) return
        reset()
        comicPend.pan = comicPend.scroll = null
        comicSettle()
    }
    document.addEventListener('pointercancel', cancel)
    // Losing OUR capture is a cancel, not a silence. Two look-alikes are ignored: the hand-off
    // from the browser's implicit touch capture (fired at the original target, not the root),
    // and the release after every normal lift (the gesture is already over by then).
    document.addEventListener('lostpointercapture', cancel)
}
attachOuterComicPan()

// TAP toggles the whole page (double-tap: out of a zoom, or IN on a spot); a side-zone tap — or
// a press of any length that starts there — turns the page; a HOLD held still for 450ms then RELEASED is
// the capture menu; HOLD-THEN-DRAG draws the zoom box (Michael 09-13: "the zoom box stays as
// hold-then-drag" — the old ⬚ mode and its one-shot arm are gone, on both apps); a plain drag
// PANS, SCROLLS or SWIPES (Michael, 2026-10-11: "I like a single finger slide in a comic"). The
// pointer listeners live on the section doc, so they cost nothing on prose books.
const attachComicTouch = (doc) => {
    if (doc.__comicTouch) return
    doc.__comicTouch = true
    let t0 = 0, x0 = 0, y0 = 0, lastX = 0, lastY = 0, seenAt = 0
    let timer = null, armed = false, moved = false, lasso = null
    let zoneDown = 'center'               // the tap zone the touch STARTED in (comicZoneTap's rule)
    // What the drag IS, once decided: 'pan' · 'scroll' · 'swipe' (comicClassify), or 'box' — a
    // drag that began after the hold armed draws the zoom box. Never changes mid-gesture.
    let kind = null
    let scr = { k: 1, left: 0, top: 0 }   // page px → screen px, measured at touch-down
    let scroll0 = null                    // the host scroll at the first move — the overscroll measure
    let hostS = null                      // the finger in HOST px at the last scroll move (see 'scroll')
    const vel = makeVelocity()
    // ONE POINTER OWNS THE GESTURE. A second contact — a resting palm, a second finger, or a
    // stylus HOVER (which this hardware delivers as pointermoves with no touch at all) —
    // interleaved into the same closure state and made the pan teleport between contact
    // points ("the scrolling zoom is super broken"). Everything below answers exactly one
    // pointerId from down to up; every other pointer is somebody's palm.
    let activeId = null, activeType = ''
    // PINCH — in a COMIC the second finger is a command, not a palm (field, 08-30: "be able
    // to zoom from an already double-clicked zoom"). A pinch COMPOSES from wherever the
    // view stands: a double-tap's manualRect scales on, a walk crop converts to a manual
    // view first (the pan's own precedent), the spread's numeric outer zoom multiplies,
    // and a plain fit page zooms in from the pinch centre. Ending either finger ends the
    // whole gesture — no tap, no hold can fire off a pinch's release.
    //
    // …BUT ONLY A REAL PINCH (Michael, 2026-10-11 pass). A second contact is a CANDIDATE until
    // both fingers have moved and their spread has changed by max(24px, 8%) (pinchCommits);
    // until then it is a palm and the first finger keeps panning, swiping, holding. A stale
    // first pointer — a lift the digitiser swallowed — never moves, so it can never pinch.
    let pinch = null, pinchSpent = false, cand = null
    const clearTimer = () => { if (timer) { clearTimeout(timer); timer = null } }
    const removeLasso = () => { if (lasso) { try { lasso.remove() } catch (e) {} lasso = null } }
    const ensureLasso = () => {
        if (lasso) return lasso
        // THE LASSO — drawn in page units so the stroke survives the outer scale.
        lasso = doc.createElement('div')
        const stroke = Math.max(3, Math.round((comic.img?.naturalWidth || 1000) * 0.004))
        lasso.style.cssText = 'position:fixed;border:' + stroke + 'px dashed #000;' +
            'background:rgba(0,0,0,0.05);z-index:2147483647;pointer-events:none'
        doc.body.appendChild(lasso)
        return lasso
    }
    // An ARMED still hold (or one about to arm) is a live pointer however silent it has been: a
    // digitiser that reports a resting finger with no jitter sends no moves at all, and a palm
    // landing then must not take the touch over and tap the page on its lift (review 2026-10-11).
    // Capped, so a hold whose lift was swallowed can't hold every later touch hostage.
    const holdLive = (now) => timer !== null || (armed && !moved && now - t0 <= G.HOLD_LIVE_MS)
    const hardReset = () => {
        activeId = null; activeType = ''; pinch = null; pinchSpent = false; cand = null
        clearTimer(); removeLasso(); armed = false; moved = false; kind = null; scroll0 = null; hostS = null
        vel.reset()
    }
    comicGestureResets.add({ doc, reset: (force) => {
        if (activeId === null && pinch === null && cand === null) return false
        // A still hold is legitimately silent — it is waiting to become the capture menu.
        if (!force && (Date.now() - seenAt <= G.STALE_MS || holdLive(Date.now()))) return true
        hardReset(); return false
    } })
    const beginPinch = (e) => {
        clearTimer(); removeLasso(); armed = false; moved = true; kind = null
        const cx = (lastX + e.clientX) / 2, cy = (lastY + e.clientY) / 2
        const d0 = Math.max(12, Math.hypot(e.clientX - lastX, e.clientY - lastY))
        const img = comic.img
        const W = img?.naturalWidth || 0, H = img?.naturalHeight || 0
        const base = { id2: e.pointerId, d0, ax: e.clientX, ay: e.clientY }
        try {
            const r = view?.renderer
            if (comic.spreadZoom && r) {
                const rRect = r.getBoundingClientRect()
                const frame = comic.img?.ownerDocument?.defaultView?.frameElement || null
                const fRect = frame?.getBoundingClientRect()
                const effNow = fRect && comic.img ? fRect.width / comic.img.naturalWidth : 0
                pinch = { ...base, mode: 'spread', z0: parseFloat(r.getAttribute('zoom')) || 1,
                    frame, hostX: cx - rRect.left, hostY: cy - rRect.top,
                    // The pinch centre in the OWNING page's natural coords — the frame anchor.
                    cxN: fRect && effNow > 0 ? (cx - fRect.left) / effNow : 0,
                    cyN: fRect && effNow > 0 ? (cy - fRect.top) / effNow : 0 }
                return
            }
        } catch (err) {}
        if (!W || !H) { pinch = { ...base, mode: 'none' }; return }
        const { s: sc, tx, ty } = comic.xform
        const px = cx / (sc || 1) - tx, py = cy / (sc || 1) - ty
        const cropped = walkOn() && !comic.whole && comic.panels && comic.idx >= 0
            ? comic.panels[comic.idx] : null
        const r0 = comic.manualRect
            ? { ...comic.manualRect }
            : cropped ? { x: cropped.x, y: cropped.y, w: cropped.w, h: cropped.h, pg: cropped.pg }
            : { x: 0, y: 0, w: W, h: H }
        pinch = { ...base, mode: 'rect', rect0: r0, px, py, W, H }
    }
    const movePinch = (e) => {
        if (e.pointerId === pinch.id2) { pinch.ax = e.clientX; pinch.ay = e.clientY }
        else { lastX = e.clientX; lastY = e.clientY }
        const d = Math.max(12, Math.hypot(pinch.ax - lastX, pinch.ay - lastY))
        const ratio = d / pinch.d0
        if (pinch.mode === 'spread') {
            try {
                const r = view?.renderer
                if (!r) return
                const z = Math.min(8, Math.max(0.05, pinch.z0 * ratio))
                r.setAttribute('zoom', String(z))
                // Frame-anchored (see spreadTapZoom) — the pinched point stays under the fingers.
                if (pinch.frame && comic.img) {
                    const fNew = pinch.frame.getBoundingClientRect()
                    const rNew = r.getBoundingClientRect()
                    const effNew = fNew.width / comic.img.naturalWidth
                    r.scrollLeft = Math.max(0, r.scrollLeft + (fNew.left - rNew.left) + pinch.cxN * effNew - pinch.hostX)
                    r.scrollTop = Math.max(0, r.scrollTop + (fNew.top - rNew.top) + pinch.cyN * effNew - pinch.hostY)
                }
            } catch (err) {}
            return
        }
        if (pinch.mode !== 'rect') return
        const { rect0, px, py, W, H } = pinch
        const minW = W * 0.06, minH = H * 0.06
        const w = Math.min(W, Math.max(minW, rect0.w / ratio))
        const h = Math.min(H, Math.max(minH, rect0.h / ratio))
        const fx = rect0.w > 0 ? (px - rect0.x) / rect0.w : 0.5
        const fy = rect0.h > 0 ? (py - rect0.y) / rect0.h : 0.5
        const rect = {
            x: Math.min(Math.max(0, px - fx * w), Math.max(0, W - w)),
            y: Math.min(Math.max(0, py - fy * h), Math.max(0, H - h)),
            w, h, pg: rect0.pg,
        }
        // Pinched back out to (almost) the whole page = the zoom is over, rest at fit.
        if (w >= W * 0.98 && h >= H * 0.98) comic.manualRect = null
        else comic.manualRect = rect
        applyPanelCrop()
    }
    // The candidate commits on whichever finger's move tips it over; ax/ay = the first finger NOW.
    const pinchReady = (ax, ay) => pinchCommits({
        d0: cand.d0 * scr.k, d: Math.hypot(cand.x - ax, cand.y - ay) * scr.k,
        moved1: cand.moved1, moved2: cand.moved2 })
    doc.addEventListener('pointerdown', e => {
        const now = Date.now()
        if (activeId !== null) {
            // A NEW FIRST FINGER STARTS CLEAN, and so does any touch after the old pointer went
            // silent: either way the old gesture's lift was swallowed. (A pen landing while a
            // finger is down is not a new first finger — each pointer type has its own primary.)
            const fresh = e.isPrimary && e.pointerType === activeType
            if (!fresh && (now - seenAt <= G.STALE_MS || holdLive(now))) {
                if (comic.isComic && pinch === null && cand === null && e.pointerId !== activeId &&
                    e.pointerType === activeType) {
                    cand = { id2: e.pointerId, x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY,
                             ax: lastX, ay: lastY, moved1: false, moved2: false,
                             d0: Math.hypot(e.clientX - lastX, e.clientY - lastY) }
                    try { doc.documentElement.setPointerCapture(e.pointerId) } catch (err) {}
                }
                return
            }
            hardReset()
        }
        activeId = e.pointerId; activeType = e.pointerType
        t0 = seenAt = now; x0 = lastX = e.clientX; y0 = lastY = e.clientY
        moved = false; armed = false; kind = null; scroll0 = null; hostS = null; pinchSpent = false
        scr = comicScreen(doc)
        vel.reset(); vel.add(now, e.clientX * scr.k)
        clearTimer(); removeLasso()
        // Pointer capture keeps the whole gesture in this doc even when the finger strays
        // outside the frame, and its loss is a cancel we can hear (below).
        try { doc.documentElement.setPointerCapture(e.pointerId) } catch (err) {}
        // A PRESS IN A SIDE ZONE IS A PAGE TURN, however long (comicZoneTap): no hold timer, so
        // a slow e-ink tap never opens the capture menu there. Drags from it still pan or swipe.
        zoneDown = tapZone(scr.left + e.clientX * scr.k, scr.top + e.clientY * scr.k,
            window.innerWidth || 1, window.innerHeight || 1, comic.tapZones)
        if (zoneDown !== 'center') return
        // A HOLD RIGHT AFTER A TAP IS A DOUBLE-TAP-AND-HOLD, not the capture menu (Mihon's
        // long-tap rule, as an idea) — the menu that kept arriving on a double-tap.
        if (now - (comic.lastTapEndAt || 0) < G.DOUBLE_MS) return
        // The hold timer: a still hold is the capture menu; a drag that starts first wins the
        // touch for pan, scroll or swipe; a drag that starts AFTER it draws the zoom box.
        const mine = e.pointerId
        timer = setTimeout(() => {
            timer = null
            if (moved || activeId !== mine) return
            armed = true
            // THE HOLD SAYS SO (2026-10-11, the e-ink false-arm worry): a small dashed square
            // lands under the finger the moment the hold arms — release for the capture menu,
            // or drag it out into the zoom box. One small repaint, and no guessing.
            const half = 18 / (scr.k || 1)   // ~18 screen px either side of the finger
            const sq = ensureLasso()
            sq.style.left = (x0 - half) + 'px'
            sq.style.top = (y0 - half) + 'px'
            sq.style.width = sq.style.height = (half * 2) + 'px'
        }, G.HOLD_MS)
    })
    doc.addEventListener('pointermove', e => {
        // A PEN HOVERING with no button down has LIFTED — its pointer-up was swallowed, and its
        // hover moves (same pointerId) would keep the gesture fresh so no stale reset ever fires.
        if (e.pointerType === 'pen' && e.buttons === 0 && e.pointerId === activeId) { onUp(e); return }
        if (pinch !== null && (e.pointerId === activeId || e.pointerId === pinch.id2)) {
            seenAt = Date.now(); movePinch(e); return
        }
        if (cand && e.pointerId === cand.id2) {
            // A second finger moving is a hand on the glass: the gesture is alive (a page load
            // must not drop a two-finger hold just because the first finger is the still one).
            seenAt = Date.now()
            cand.x = e.clientX; cand.y = e.clientY
            if (Math.hypot(e.clientX - cand.sx, e.clientY - cand.sy) * scr.k > G.MOVED) cand.moved2 = true
            if (pinchReady(lastX, lastY)) { comicFlush(); cand = null; beginPinch(e) }
            return
        }
        if (activeId === null || e.pointerId !== activeId) return   // hover streams and palms
        const now = Date.now()
        seenAt = now
        if (cand) {
            if (Math.hypot(e.clientX - cand.ax, e.clientY - cand.ay) * scr.k > G.MOVED) cand.moved1 = true
            if (pinchReady(e.clientX, e.clientY)) {
                comicFlush()
                lastX = e.clientX; lastY = e.clientY
                const c = cand; cand = null
                beginPinch({ pointerId: c.id2, clientX: c.x, clientY: c.y })
                return
            }
        }
        vel.add(now, e.clientX * scr.k)
        const sdx = (e.clientX - x0) * scr.k, sdy = (e.clientY - y0) * scr.k
        if (!moved && (Math.abs(sdx) > G.SLOP || Math.abs(sdy) > G.SLOP)) {
            moved = true
            if (armed) kind = 'box'
            else {
                clearTimer()   // a drag is not a brewing hold
                kind = comicClassify(sdx, sdy)
                if (kind === 'scroll') try {
                    const r = view?.renderer
                    if (r) scroll0 = { y: r.scrollTop, maxY: Math.max(0, r.scrollHeight - r.clientHeight) }
                } catch (err) {}
            }
        }
        if (!moved) return
        if (kind === 'box') {
            const box = ensureLasso()
            box.style.left = Math.min(x0, e.clientX) + 'px'
            box.style.top = Math.min(y0, e.clientY) + 'px'
            box.style.width = Math.abs(e.clientX - x0) + 'px'
            box.style.height = Math.abs(e.clientY - y0) + 'px'
        } else if (kind === 'pan') {
            comicQueue('pan', (e.clientX - lastX) * scr.k, (e.clientY - lastY) * scr.k)
        } else if (kind === 'scroll') {
            // In HOST px, measured NOW (the iPad's 10-11 finding; on the B6 a 339px drag moved the
            // comic 154–187px): scrolling the host carries this frame under the finger, so doc-space
            // deltas half-cancelled every other move and the page trailed the finger at half speed.
            // The frame's origin + clientY × k is where the finger is on the glass, scroll or not.
            const m = comicScreen(doc)
            const hx = m.left + e.clientX * m.k, hy = m.top + e.clientY * m.k
            if (hostS) comicQueue('scroll', hx - hostS.x, hy - hostS.y)
            else comicQueue('scroll', (e.clientX - lastX) * scr.k, (e.clientY - lastY) * scr.k)
            hostS = { x: hx, y: hy }
        }
        // 'swipe' moves nothing while the finger travels — the one repaint is the turn itself.
        lastX = e.clientX; lastY = e.clientY
    })
    const onUp = e => {
        if (cand && e.pointerId === cand.id2) { cand = null; return }   // the palm lifted
        if (pinch !== null && (e.pointerId === activeId || e.pointerId === pinch.id2)) {
            // Either finger ending ends the pinch AND the gesture — a release can't tap.
            pinch = null; pinchSpent = true; seenAt = Date.now()
            if (e.pointerId === activeId) { activeId = null; pinchSpent = false; comicSettle(); postPanelState() }
            else { kind = comicCrop() ? 'pan' : 'scroll'; hostS = null }   // the finger left behind keeps moving the view
            return
        }
        if (activeId === null || e.pointerId !== activeId) return
        activeId = null; cand = null
        clearTimer()
        comicFlush()
        const boxed = armed && moved && !!lasso
        removeLasso()
        if (pinchSpent) { pinchSpent = false; comicSettle(); postPanelState(); return }
        const now = Date.now()
        vel.add(now, e.clientX * scr.k)
        const sdx = (e.clientX - x0) * scr.k, sdy = (e.clientY - y0) * scr.k
        // SWIPE TURNS THE PAGE (Michael, 09-13, on the Palma: "swipe to change pages… not fixed";
        // 2026-10-11: "I like a single finger slide in a comic"). DISTANCE OR SPEED, no time
        // limit (DirectionalViewPager's rule, as an idea): half the width, or 25px at 400px/s —
        // and the old rule, 16% of the page within 800ms, still turns too (`ms`, the iPad's
        // 10-11 review), so nothing that turned before stops turning.
        if (kind === 'swipe') {
            const side = swipeSide({ dx: sdx, dy: sdy, vx: vel.vx(now), width: window.innerWidth || 1,
                ms: now - t0 })
            if (side) { comicSettle(); comicTurnSide(side); return }
        }
        // PAST THE END OF A TALL PAGE: a vertical scroll dragged a quarter-screen beyond the
        // bottom turns forward, beyond the top turns back. Paginated and unzoomed only (the
        // classifier never scrolls a crop, and the scrolled flow never says 'scroll' for a turn).
        if (kind === 'scroll' && scroll0 && settings.flow !== 'scrolled' && comicZoom() === 'none' &&
            Math.abs(sdy) > Math.abs(sdx)) {
            const want = scroll0.y - sdy
            const over = want > scroll0.maxY ? want - scroll0.maxY : Math.min(0, want)
            const turn = overscrollTurn({ over, height: window.innerHeight || 1 })
            if (turn) { comicSettle(); (turn > 0 ? window.pageRight : window.pageLeft)?.(); return }
        }
        comicSettle()   // settle + re-centre once
        if (boxed) { armed = false; commitLassoZoom(doc, x0, y0, e.clientX, e.clientY); return }
        // Only a MEASURED still-hold captures: the flag alone is not enough, because an e-ink
        // digitiser can swallow a tap's pointer-up and leave it armed for the NEXT touch — which
        // is exactly how the annotation menu kept returning on a double-tap. The duration check
        // is per-touch truth; a quick tap can never wear the hold's flag.
        if (armed && !moved && now - t0 >= G.HOLD_MS) { armed = false; comicCapture(); return }
        armed = false
        if (moved) return
        // A press that started in a side zone is a tap whatever its length (see pointerdown).
        const side = zoneDown !== 'center'
        if (!side && now - t0 >= G.TAP_MS) return
        comic.lastTapEndAt = now
        // SIDE ZONES FIRST — an outer tap turns at once, never waiting on a double-tap: double-tap
        // zoom pairs only CENTRE taps, so e-ink page turns pay no 450ms (and a fast pair of edge
        // taps is two turns, not a turn and then a zoom of the new page).
        const zone = side ? zoneDown : tapZone(scr.left + e.clientX * scr.k, scr.top + e.clientY * scr.k,
            window.innerWidth || 1, window.innerHeight || 1, comic.tapZones)
        if (comicZoneTap(zone)) { comic.lastTapAt = 0; return }
        const doubleTap = now - comic.lastTapAt < G.DOUBLE_MS   // e-ink taps are slower; 350 missed real doubles
        comic.lastTapAt = now
        if (comic.manualRect) {
            if (doubleTap) {
                comic.manualRect = null
                applyPanelCrop()
                postPanelState()
            }
            return
        }
        // HOT ZOOM (ComicsNow-inspired, see handoff): in the balloon walk a plain tap on a
        // bubble zooms THAT bubble — smallest-box-wins when bubbles overlap — and the walk
        // continues from it. A tap that hits no bubble keeps the ordinary whole-page toggle.
        if (comic.balloonMode && comic.panels && !comic.whole) {
            const { s: sc, tx, ty } = comic.xform
            const px = e.clientX / (sc || 1) - tx, py = e.clientY / (sc || 1) - ty
            const pgOf = (comic.pages || []).find(pg => pg.doc === doc)
            let hit = -1, hitArea = Infinity
            comic.panels.forEach((r, i) => {
                if (pgOf && r.pg && r.pg !== pgOf) return
                if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) {
                    const a = r.w * r.h
                    if (a < hitArea) { hitArea = a; hit = i }
                }
            })
            if (hit >= 0 && hit !== comic.idx) {
                comic.idx = hit
                comic.manualRect = null
                applyPanelCrop()
                postPanelState()
                return
            }
        }
        if (walkOn() && comic.panels) {
            comic.whole = !comic.whole
            applyPanelCrop()
            postPanelState()
            return
        }
        // Plain reading, whole page on screen: DOUBLE-TAP ZOOMS IN where you tapped.
        if (doubleTap && comic.isComic && comic.img) {
            // A SPREAD zooms whole — both pages together on the outer rail (see spreadTapZoom).
            if ((comic.pages?.length || 0) > 1 && settings.flow !== 'scrolled') {
                spreadTapZoom(doc, e.clientX, e.clientY)
                postPanelState()
                return
            }
            const img = comic.img
            const W = img.naturalWidth, H = img.naturalHeight
            const { s: sc, tx, ty } = comic.xform
            const px = e.clientX / sc - tx, py = e.clientY / sc - ty
            const rw = W * 0.55, rh = H * 0.55
            const rx = Math.min(Math.max(0, px - rw / 2), Math.max(0, W - rw))
            const ry = Math.min(Math.max(0, py - rh / 2), Math.max(0, H - rh))
            comic.manualRect = { x: rx, y: ry, w: Math.min(rw, W), h: Math.min(rh, H) }
            comic.whole = false
            applyPanelCrop()
            postPanelState()
            return
        }
        // A single centre tap at fit belongs to the app: the iPad shows/hides its bars; the Boox
        // keeps its chrome on the rail and lets it pass.
        if (!comic.spreadZoom) post('tap', { zone: 'center',
            fx: (scr.left + e.clientX * scr.k) / (window.innerWidth || 1) })
    }
    doc.addEventListener('pointerup', onUp)
    const cancel = e => {
        if (e.type === 'lostpointercapture' && e.target !== doc.documentElement) return
        if (cand && e.pointerId === cand.id2) { cand = null; return }
        if (pinch !== null && (e.pointerId === activeId || e.pointerId === pinch.id2)) {
            pinch = null; pinchSpent = true
            if (e.pointerId === activeId) { activeId = null; pinchSpent = false; comicSettle() }
            return
        }
        if (activeId === null || e.pointerId !== activeId) return
        hardReset()
        comicPend.pan = comicPend.scroll = null
        comicSettle()
    }
    doc.addEventListener('pointercancel', cancel)
    // Losing OUR capture is a cancel (see the outer layer for the two look-alikes it ignores).
    doc.addEventListener('lostpointercapture', cancel)
    // Belt: with touch-action none the browser should never claim the gesture; refuse it anyway.
    doc.addEventListener('touchmove', e => { try { e.preventDefault() } catch (err) {} }, { passive: false })
    try {
        doc.addEventListener('contextmenu', e => e.preventDefault())
        const st = doc.documentElement.style
        st.webkitUserSelect = 'none'
        st.webkitTouchCallout = 'none'
        doc.querySelectorAll('img').forEach(i => {
            i.draggable = false
            i.style.webkitUserDrag = 'none'
            i.style.webkitUserSelect = 'none'
        })
    } catch (e) {}
}

// App → JS: the reader's tap-to-turn setting, so the side zones above answer it exactly as the
// native strips they replace did — 'sides' (full-height outer strips) · 'corners' (the bottom
// band only) · 'off'. Never re-lays the page: a setting about taps must not move the walk.
window.setComicTapZones = (style) => {
    comic.tapZones = (style === 'off' || style === 'corners') ? style : 'sides'
}
// <<< COMIC-GESTURES <<< ─────────────────────────────────────────────────────────────────────

// The drawn box, mapped back through whatever transform is on screen, becomes the view.
// The box belongs to the DOC it was drawn in (on a spread, either page can host the drag);
// a box that runs past that page's edge toward its neighbour is a cross-gutter ask, and an
// in-page crop cannot span two iframes — so that box rides the OUTER rail: numeric zoom so
// the drawn region fills the viewport, host scrolled to it, both pages moving together.
const commitLassoZoom = (doc, ax, ay, bx, by) => {
    const pageOf = (d) => (comic.pages || []).find(pg => pg.doc === d)
    const owner = pageOf(doc)
    const img = (owner && owner.img) || comic.img
    if (!img) return
    const sameDocAsXform = img === comic.img
    const { s, tx, ty } = sameDocAsXform ? comic.xform : { s: 1, tx: 0, ty: 0 }
    const toImg = (vx, vy) => [vx / s - tx, vy / s - ty]
    const [x1, y1] = toImg(Math.min(ax, bx), Math.min(ay, by))
    const [x2, y2] = toImg(Math.max(ax, bx), Math.max(ay, by))
    const W = img.naturalWidth, H = img.naturalHeight
    const spanning = (comic.pages?.length || 0) > 1 && (x1 < -W * 0.02 || x2 > W * 1.02)
    if (spanning) {
        try {
            const r = view?.renderer
            const frame = doc.defaultView?.frameElement
            if (!r || !frame) return
            const fRect = frame.getBoundingClientRect()
            const rRect = r.getBoundingClientRect()
            const eff = fRect.width / W
            if (!(eff > 0)) return
            const hx1 = fRect.left - rRect.left + Math.min(ax, bx) * eff
            const hx2 = fRect.left - rRect.left + Math.max(ax, bx) * eff
            const hy1 = fRect.top - rRect.top + Math.min(ay, by) * eff
            const hy2 = fRect.top - rRect.top + Math.max(ay, by) * eff
            const bw = hx2 - hx1, bh = hy2 - hy1
            if (bw < rRect.width * 0.04 || bh < rRect.height * 0.04) return
            const factor = parseFloat(r.getAttribute('scale-factor')) || 1
            const grow = Math.min(rRect.width / bw, rRect.height / bh)
            const f = Math.min(grow, 6)
            comic.spreadZoom = true
            r.setAttribute('zoom', String((eff * f) / factor))
            // Frame-anchored (see spreadTapZoom): the box centre in the OWNING page's natural
            // coords, placed absolutely against the freshly measured frame — no scaled-scroll
            // arithmetic, no page-fling on wide pages deep in the book.
            const cxN = (x1 + x2) / 2, cyN = (y1 + y2) / 2
            const fNew = frame.getBoundingClientRect()
            const rNew = r.getBoundingClientRect()
            const effNew = fNew.width / W
            r.scrollLeft = Math.max(0, r.scrollLeft + (fNew.left - rNew.left) + cxN * effNew - rNew.width / 2)
            r.scrollTop = Math.max(0, r.scrollTop + (fNew.top - rNew.top) + cyN * effNew - rNew.height / 2)
        } catch (e) {}
        return
    }
    const rx = Math.max(0, x1), ry = Math.max(0, y1)
    const r = { x: rx, y: ry, w: Math.min(W, x2) - rx, h: Math.min(H, y2) - ry, pg: owner }
    if (r.w < W * 0.04 || r.h < H * 0.04) return   // a twitch, not a box
    comic.manualRect = r
    comic.whole = false
    applyPanelCrop()
    postPanelState()
}

// What the reader is LOOKING AT, as a JPEG data URL — the panel when cropped, the page when
// whole. Capped at 2048px on the long side: full art for a gram or a saved image without
// pushing tens of megabytes of base64 through the bridge.
const comicCapture = () => {
    try {
        const img = comic.img
        if (!img) return
        const W = img.naturalWidth, H = img.naturalHeight
        const cropped = walkOn() && !comic.whole && comic.panels && comic.idx >= 0
        let sx = 0, sy = 0, sw = W, sh = H
        const r = comic.manualRect || (cropped ? comic.panels[comic.idx] : null)
        if (r) { sx = r.x; sy = r.y; sw = r.w; sh = r.h }
        const scale = Math.min(1, 2048 / Math.max(sw, sh))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(sw * scale))
        canvas.height = Math.max(1, Math.round(sh * scale))
        canvas.getContext('2d').drawImage(img, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height)
        post('comicCapture', {
            dataUrl: canvas.toDataURL('image/jpeg', 0.9),
            page: comic.docIndex + 1,
            pages: view?.book?.sections?.length ?? 0,
            panel: cropped ? comic.idx + 1 : 0,
            panelCount: comic.panels ? comic.panels.length : 0,
        })
    } catch (e) { post('error', { message: 'comic capture: ' + String(e) }) }
}

// App → JS: capture what is on screen NOW (panel, drawn box, or page) — the menu door to the
// same capture the long-hold reaches, so the feature exists even where a device's gesture
// stack eats the hold.
window.comicCaptureNow = () => comicCapture()

// LEDGER PATCH — ROTATION REFIT. fixed-layout's only resize trigger is a ResizeObserver, and
// observer delivery is unreliable here (the -14 lesson: IntersectionObserver — "nothing in
// throttled WebViews"; the desktop harness reproduced the same silence for ResizeObserver).
// So a rotation while ZOOMED kept the pre-rotation fit — "still using a smaller device's
// measurements … doesn't hit full width" (Michael, field, 08-27). A page TURN re-renders and
// self-heals; a zoomed page turns no pages, so the stale fit persisted exactly there.
//
// The deterministic door, proven in the harness: setting the renderer's 'zoom' attribute to
// its CURRENT value runs attributeChangedCallback → #render(), which reads the live rect.
// Reflowable books carry no 'zoom' attribute and are untouched (the paginator has its own
// resize path). The comic crop re-applies after — its transform lives in image-natural px
// and survives, but the host re-centre must be recomputed for the new geometry.
let refitTimer = 0
const refitViewport = () => {
    const r = view?.renderer
    if (!r?.getAttribute) return
    const z = r.getAttribute('zoom')
    if (z == null) return
    // On a comic, re-resolve 'auto' for the NEW orientation — a rotation flips fit-page↔fit-width,
    // so re-setting the stale value would keep the old fit. Reflowable/manual-zoom pass z through.
    const next = (comic.isComic && (comic.zoomPref === 'auto')) ? comic.effZoom() : z
    try { r.setAttribute('zoom', next) } catch (e) {}
    if (comic.isComic) setTimeout(() => { try { applyPanelCrop() } catch (e) {} }, 80)
}
window.addEventListener('resize', () => {
    // Rotation resizes in bursts (bars settle, insets land); refit once, after the last.
    clearTimeout(refitTimer)
    refitTimer = setTimeout(refitViewport, 160)
})
// The app's belt: onConfigurationChanged calls this directly, for the WebView that
// relaid out without firing a window resize.
window.refitViewport = refitViewport

// App → JS: the per-book comic settings. Re-lays the CURRENT page immediately, so the effect
// of a toggle is visible where the choice is made (§4).
window.setComicPrefs = (obj) => {
    const o = obj || {}
    const rtlChanged = ('rtl' in o) && !!o.rtl !== comic.rtl
    if ('isComic' in o) comic.isComic = !!o.isComic
    if ('rtl' in o) comic.rtl = !!o.rtl
    if ('panelMode' in o) comic.panelMode = !!o.panelMode
    if ('balloonMode' in o) comic.balloonMode = !!o.balloonMode
    if ('restorePanel' in o) comic.restorePanel = o.restorePanel | 0
    if (rtlChanged) comic.cache.clear()
    // The whole-page walk's own controls — fixed-layout already speaks both of these:
    // zoom ('fit-page' | 'fit-width' | a factor) and flow ('scrolled' = the vertical,
    // webtoon-shaped read). Per book, stored by the app like the rest.
    if ('flow' in o) { settings.flow = o.flow; applyLayout() }
    if ('zoom' in o) {
        comic.zoomPref = String(o.zoom)
        try { view?.renderer?.setAttribute('zoom', comic.effZoom()) } catch (e) {}
    }
    // The COMIC's page count per screen is fixed-layout's own 'spread' attribute — the epub
    // columns toggle never reached it. comic-book.js declares no spread, and the fallback
    // built spreads on a wide viewport: HIS B6 IN LANDSCAPE DREW THREE PAGES, and a panel
    // crop landing on one iframe of three read as "panel by panel doesn't change things".
    // 'none' = one page per screen, the e-ink default; 'both' = the chosen two-page spread.
    // …and never while scrolled: a spread pass across #root's children used to stamp the
    // scroll container hidden (the vertical-scroll white screen). The spread re-applies when
    // the flow returns to paginated — the prefs always ride together.
    if ('spread' in o) {
        comic.spreadPref = String(o.spread)
        if (settings.flow !== 'scrolled') {
            try { view?.renderer?.setAttribute('spread', comic.spreadPref) } catch (e) {}
        }
    }
    if (comic.isComic) relayVisiblePage()
}

// A programmatic re-lay (prefs, balloon index) must speak about the page ON SCREEN — resolved
// live from the renderer, never from whichever doc happened to load last.
const relayVisiblePage = () => {
    try {
        const vi = view?.renderer?.index
        const c = [...(view?.renderer?.getContents?.() || [])].find(x => x.index === vi)
        if (c?.doc) { comicPageLoaded(c.doc, c.index); return }
    } catch (e) {}
    if (currentDoc) comicPageLoaded(currentDoc, currentIndex)
}

// App → JS: the balloon index for the OPEN book ({pages: {"<spineIdx>": [[x,y,w,h]…]}}, normalized
// 0–1). Arrives on open (cached or partial), per partial push while the pass cooks, and once more
// when it finishes; the current page re-lays so freshly indexed balloons appear where the reader
// is standing.
//
// …BUT A PUSH MUST NEVER MOVE A WALK THAT IS ALREADY WALKING (field, 09-10). A partial lands every
// few pages of indexing, and a re-lay re-enters the page at its FIRST balloon — so a reader three
// balloons into a page was snapped back to the first one every time the indexer finished another
// batch, which reads as the walk fighting him. Re-lay only when the pages ON SCREEN have something
// new to say (rects appeared, or changed); an identical entry leaves the walk exactly where it is.
window.setBalloonIndex = (obj) => {
    const prev = comic.balloonIndex
    const next = (obj && obj.pages) || null
    comic.balloonIndex = next
    comic.balloonText = (obj && obj.text) || null
    if (!comic.isComic || !comic.balloonMode) return   // stored; the mode toggle re-lays
    if (comic.panels && prev && next) {
        const key = (idx, pg) => JSON.stringify(idx?.[String(pg.index)] ?? null)
        const same = (comic.pages || []).length &&
            (comic.pages || []).every(pg => key(prev, pg) === key(next, pg))
        if (same) {
            // Same rects: the walk stays exactly where it is — only the words may be newer.
            for (const q of comic.panels)
                if (q.pg && q.ri != null) q.text = comic.balloonText?.[String(q.pg.index)]?.[q.ri] ?? ''
            return
        }
    }
    relayVisiblePage()
}

function post(type, payload = {}) {
    window.webkit?.messageHandlers?.reader?.postMessage({ type, ...payload })
}

// When a finger last left the page (a page doc or the margins around it) — what tells a swipe's
// landing in the next chapter from a contents jump, both 'navigation' to foliate (openBook's
// 'turned'; 2026-10-12, iOS's twin). The app's own sheets and panel are never the page.
let fingerLiftedAt = 0
const noteFingerLifted = () => { fingerLiftedAt = Date.now() }
document.addEventListener('touchend', noteFingerLifted, { passive: true, capture: true })

// Prefer the early on-screen printer set up in the HTML; fall back to local.
// 📖 THE PAGE READ (field, 08-30: "there's not really a way to see comic progress/page").
// A quiet "12 / 48" in the corner of the TOP document — never inside the page iframe, so it
// costs no page redraw. Guarded writes only (the -42 rule: touch the DOM when the text
// changes, never per event), monospace so successive values repaint the same cells (the
// e-ink instant-paging glyph rule), and ABSENT while zoomed-in reading — chrome must not
// sit over art the reader deliberately magnified.
//
// FULL SCREEN TAKES IT TOO (Michael, 2026-10-12: "IT cant block the comics"; review, iOS's twin):
// the app says when its controls are away (setPageReadShown) and the count goes with them, back
// with the peek.
let pageReadEl = null, pageReadText = '', pageReadShown = true
const updatePageRead = () => {
    const total = (() => { try { return view?.book?.sections?.length || 0 } catch (e) { return 0 } })()
    const zoomedIn = !!(comic.manualRect || comic.spreadZoom ||
        (walkOn() && !comic.whole && comic.panels))
    const want = (!pageReadShown || !comic.isComic || !total || zoomedIn)
        ? '' : `${Math.min(total, (comic.docIndex ?? 0) + 1)} / ${total}`
    if (want === pageReadText) return
    pageReadText = want
    if (!want) { if (pageReadEl) pageReadEl.style.display = 'none'; return }
    if (!pageReadEl) {
        pageReadEl = document.createElement('div')
        pageReadEl.style.cssText = 'position:fixed;right:8px;bottom:6px;z-index:2147483646;' +
            'font:12px ui-monospace,monospace;color:#444;background:rgba(255,255,255,0.78);' +
            'padding:1px 6px;border-radius:8px;pointer-events:none'
        document.body.appendChild(pageReadEl)
    }
    pageReadEl.style.display = ''
    pageReadEl.textContent = want
}

function showStatus(text) {
    if (window.__readerShow) { window.__readerShow(text); return }
    // Write-on-change only: the overlay covers the whole screen, and on an e-ink WebView a
    // same-value style write still forces the layer to redraw — a visible flash for nothing.
    if (msg && (msg.style.display !== 'flex' || msg.textContent !== text)) {
        msg.style.display = 'flex'; msg.textContent = text
    }
}

const errText = e => e ? (e.stack || e.message || String(e)) : String(e)

// A page that renders clears any stale status — the overlay's ONE hide used to be the one-shot
// success line after view.open(), so the first later error pinned it open for the whole session
// (visible as ghost text under the prose before #msg got its opaque z-indexed style).
function hideStatus() {
    if (window.__readerHide) { window.__readerHide(); return }
    // Guarded for the same reason as showStatus: hideStatus runs on EVERY relocate (the
    // standing hide), i.e. every page turn — an unguarded same-value write was a full-screen
    // layer redraw per turn (field, 08-28: "the status bar flashing on page changes").
    if (msg && msg.style.display !== 'none') msg.style.display = 'none'
}

// ResizeObserver loop reports are benign per spec (the browser retries next frame) and the
// paginator's own comments flag spread-mode relayout as oscillation-prone at threshold widths —
// exactly the two-page case. Nothing a reader can act on; don't paint it, don't post it.
const benign = m => typeof m === 'string' && m.includes('ResizeObserver loop')

// Surface any real failure ON SCREEN so we can see why nothing renders.
window.addEventListener('error', e => {
    if (benign(e.message)) return
    showStatus('JS error: ' + errText(e.error || e.message)); post('error', { message: String(e.message) })
})
window.addEventListener('unhandledrejection', e => {
    if (benign(String(e.reason))) return
    showStatus('Rejected: ' + errText(e.reason)); post('error', { message: String(e.reason) })
})

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
    animated: false,      // page-flip animation off — it ghosts on e-ink
    invertImages: false,  // invert images in dark mode
    theme: 'default',     // default | sepia | gray | black | contrast
    // Aa Type (Michael, 2026-10-11) — our own override branches, Readest's idea, not its code:
    fg: '',               // custom text colour '#rrggbb' ('' = the theme's)
    bg: '',               // custom page colour
    overrideFont: false,  // the book's own face/size/weight give way
    overrideLayout: false,// the book's own spacing/alignment give way
    overrideColor: false, // the book's own colours give way
    customCss: '',        // appended last
}

// 'contrast' is EinkBro's "force black text on a white page": the colours AND the override.
const THEME_BG = { sepia: '#f4ecd8', gray: '#c9c9c9', black: '#000000', contrast: '#ffffff' }
const THEME_FG = { sepia: '#5b4636', gray: '#1a1a1a', black: '#cfcfcf', contrast: '#000000' }
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/
const colorOr = (c, d) => (typeof c === 'string' && HEX_COLOR.test(c)) ? c : d
// WCAG luminance / contrast — the same arithmetic as ReadingType.kt (luminance, contrastRatio,
// isDark), so a book and an article pick the same ink for the same page.
const luminance = (hex) => {
    const lin = (i) => {
        const c = parseInt(hex.substr(1 + 2 * i, 2), 16) / 255
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(0) + 0.7152 * lin(1) + 0.0722 * lin(2)
}
const contrastRatio = (a, b) => {
    const la = luminance(a), lb = luminance(b)
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}
const isDarkHex = (hex) => luminance(hex) < 0.18

// The override branches (Aa Type › Advanced). Each one only ADDS !important rules after the
// ordinary ones, so with every switch off the CSS is what it always was.
const overrideFontCSS = (s) => `
    body { font-size: 1rem !important; font-weight: ${s.fontWeight} !important; }
    p, li, blockquote, dd, dt { font-size: 1rem !important; }
    body *:not(strong):not(b):not(h1):not(h2):not(h3):not(h4):not(h5):not(h6):not(th) {
        font-weight: inherit !important; }
    ${readingFontFamily ? `body *:not(pre):not(code):not(kbd):not(samp):not(tt) {
        font-family: ${readingFontFamily} !important; }
    ${CODE_KEEPS_ITS_FACE}` : ''}`
// Inside code (a highlighter's spans) the face is inherited from the code, not forced: as
// specific as the rule above (one type + five :not) and after it. ReadingType.kt has the twin.
const NOT_CODE = ':not(pre):not(code):not(kbd):not(samp):not(tt)'
const CODE_KEEPS_ITS_FACE = ['pre', 'code', 'kbd', 'samp', 'tt'].map(t => `${t} ${NOT_CODE}`).join(', ') +
    ' { font-family: inherit !important; }'
const overrideLayoutCSS = (s) => `
    body { margin-inline: 0 !important; padding-inline: 0 !important; }
    body * { line-height: ${s.lineHeight} !important; }`
const overrideColorCSS = (fg, bg) => `
    html, body { background: ${bg} !important; color: ${fg} !important; }
    body * { color: ${fg} !important; background-color: transparent !important;
        border-color: ${fg} !important; }`

const buildCSS = () => {
    const s = settings
    // A custom colour wins over the theme's; each lands on its own, so a custom text colour on
    // the default theme leaves the book's page colour alone. (Aa Type, Michael 2026-10-11.)
    // High contrast is black on white ALWAYS: the custom colours give way to it.
    const contrast = s.theme === 'contrast'
    const bg = contrast ? THEME_BG.contrast : colorOr(s.bg, THEME_BG[s.theme])
    const customFg = !contrast && HEX_COLOR.test(s.fg || '')
    let fg = contrast ? THEME_FG.contrast : colorOr(s.fg, THEME_FG[s.theme])
    const darkPage = !!bg && isDarkHex(bg)
    // A page with no ink that reads on it gets the ink that does — a custom dark page on the
    // default theme (no theme ink: the book's own, usually black), or a custom light page under
    // Black's light ink. A custom text colour is the reader's own choice and stays.
    if (!customFg && bg && (!fg || contrastRatio(fg, bg) < 4.5)) fg = darkPage ? THEME_FG.black : '#000000'
    // A dark page forces the colours too: a book's own dark ink (p { color: #333 }) on black is
    // no text at all — always true of the Black theme, and easy to reach with a custom page
    // colour. Light pages keep the book's colours unless asked. (Review, Michael 2026-10-11.)
    const forceColor = s.overrideColor || contrast || darkPage
    // Under the layout override every spacing rule carries !important, so the book's own give way.
    const lay = s.overrideLayout ? ' !important' : ''
    // @namespace must come before every other rule but @charset/@import, so the faces follow it
    // (ahead of it, the @namespace was silently dropped). Aa Type, Michael 2026-10-11.
    return `
    @namespace epub "http://www.idpf.org/2007/ops";
    ${fontFaces}
    html { color-scheme: light dark; font-size: ${s.fontSize}%; }
    ${bg ? `html, body { background: ${bg} !important; }` : ''}
    ${fg ? `html, body { color: ${fg} !important; }` : ''}
    ${readingFontFamily ? `html, body, p, li, blockquote, dd, h1, h2, h3, h4, h5, h6 {
        font-family: ${readingFontFamily} !important; }` : ''}
    body { font-weight: ${s.fontWeight}; }
    p, li, blockquote, dd {
        line-height: ${s.lineHeight}${lay};
        margin-block: ${s.paragraphMargin}em${lay};
        text-align: ${s.justify ? 'justify' : 'start'}${lay};
        word-spacing: ${s.wordSpacing}px${lay};
        letter-spacing: ${s.letterSpacing}px${lay};
        -webkit-hyphens: ${s.hyphenate ? 'auto' : 'manual'}${lay};
        hyphens: ${s.hyphenate ? 'auto' : 'manual'}${lay}; }
    p { text-indent: ${s.textIndent}em${lay}; }
    pre { white-space: pre-wrap !important; }
    ${s.overrideFont ? overrideFontCSS(s) : ''}
    ${s.overrideLayout ? overrideLayoutCSS(s) : ''}
    ${forceColor ? overrideColorCSS(fg || '#000000', bg || '#ffffff') : ''}
    ${s.invertImages ? `@media (prefers-color-scheme: dark) {
        img, image, picture, svg, video { filter: invert(1) hue-rotate(180deg); } }` : ''}
    ${typeof s.customCss === 'string' ? s.customCss : ''}
`
}

// TWO PAGES MUST BE TWO PAGES (Aa Type review, Michael 2026-10-11). The paginator counts
// ceil(width / max-inline-size) columns, capped at max-column-count — so once Width could be
// "No limit" (4096) or any rung at or past the screen's, "Two pages" drew ONE. With two columns
// asked for, the cap is at most half the width, but never under the 720 every spread had before
// Aa Type: a spread appears exactly where it always did (a portrait B6 stays one page).
const SPREAD_MIN_INLINE = 720
const inlineSizeFor = () => {
    const s = settings
    if (!view || view.isFixedLayout || s.columns < 2 || s.flow === 'scrolled') return s.maxInlineSize
    let w = 0
    try { w = Math.floor(view.renderer.getBoundingClientRect().width) } catch (e) {}
    if (!w) return s.maxInlineSize
    return Math.min(s.maxInlineSize, Math.max(Math.floor(w / s.columns), SPREAD_MIN_INLINE))
}
// A PDF or a fixed-layout EPUB keeps its pages: Paged/Scrolled is for flowing text, and before
// Aa Type nothing ever scrolled one. A comic's flow is its own (setComicPrefs), so it passes.
const flowFor = () => (view?.isFixedLayout && !comic.isComic) ? 'paginated' : settings.flow
// Rotation changes the width the spread is measured against — re-measure once it settles, and
// touch the attribute only when the answer changed (a re-set re-lays: one refresh for nothing).
const refitSpread = () => {
    const r = view?.renderer
    if (!r?.setAttribute || view.isFixedLayout) return
    const next = inlineSizeFor() + 'px'
    if (r.getAttribute('max-inline-size') !== next) r.setAttribute('max-inline-size', next)
}
let spreadTimer = 0
window.addEventListener('resize', () => {
    clearTimeout(spreadTimer)
    spreadTimer = setTimeout(refitSpread, 160)
})
{
    // The app's onConfigurationChanged belt reaches the spread too.
    const refitFixed = window.refitViewport
    window.refitViewport = () => { try { refitFixed?.() } catch (e) {} refitSpread() }
}

function applyLayout() {
    if (!view) return
    const r = view.renderer
    r.setAttribute('flow', flowFor())
    r.setAttribute('margin', settings.margin + 'px')
    r.setAttribute('max-inline-size', inlineSizeFor() + 'px')
    r.setAttribute('max-column-count', String(settings.columns))
    r.setAttribute('gap', settings.gap + '%')
    if (settings.animated) r.setAttribute('animated', '')
    else r.removeAttribute('animated')
    r.setStyles?.(buildCSS())
}

// THE CENTRE TAP OF A PROSE BOOK OR A PDF (Michael 2026-10-12: full screen in a reader leaves
// nothing on the page, and the tap that brings the reader's controls back brings the ⛶ back). The
// app's native strips own the turn zones (by the reader's tap-zone setting), so only the CENTRE
// speaks — the same zones the comic's own layer uses (comic-gestures.js tapZone under
// comic.tapZones, which the app pushes for every book): 'sides' the middle 44%, 'corners'
// everything above the bottom band but its corners, 'off' the whole page. Measured in the
// wrapper's viewport (a paginated iframe is as wide as its spread). A comic routes its own taps
// (the COMIC-GESTURES block); links, a selection (and the tap that puts one down) and a tap on a
// mark keep their own meaning.
const bindCentreTap = (doc) => {
    if (doc.__centreTapBound) return
    doc.__centreTapBound = true
    doc.addEventListener('touchend', noteFingerLifted, { passive: true, capture: true })
    // THE TAP THAT PUTS A SELECTION DOWN (review 2026-10-12): Chromium collapses the selection on
    // the tap's mousedown, before 'click' — so whether one was up is read when the finger lands,
    // and a collapse just before the click (any route) still counts.
    const liveSel = () => {
        const sel = doc.getSelection?.()
        return !!(sel && !sel.isCollapsed && sel.toString().trim())
    }
    let selAtDown = false, hadSel = false, selGoneAt = 0
    doc.addEventListener('pointerdown', () => { selAtDown = liveSel() }, true)
    doc.addEventListener('selectionchange', () => {
        const live = liveSel()
        if (hadSel && !live) selGoneAt = Date.now()
        hadSel = live
    })
    doc.addEventListener('click', e => {
        if (comic.isComic) return
        if (e.target?.closest?.('a[href]')) return
        if (selAtDown || liveSel() || Date.now() - selGoneAt < 600) return
        try {
            const c = [...(view?.renderer?.getContents?.() || [])].find(x => x.doc === doc)
            const hit = c?.overlayer?.hitTest?.(e)
            if (hit && hit[0]) return
        } catch (err) {}
        const now = Date.now()
        if (now - (window.__lastCentreTapTs || 0) < 350) return
        window.__lastCentreTapTs = now
        const w = window.innerWidth || document.documentElement.clientWidth || 1
        const h = window.innerHeight || document.documentElement.clientHeight || 1
        const fe = e.view && e.view.frameElement
        const fr = fe ? fe.getBoundingClientRect() : null
        // A PDF's page frame is scaled to fit: its own px are not the screen's.
        const k = fr && fe.offsetWidth > 0 ? fr.width / fe.offsetWidth : 1
        const x = (e.clientX ?? (w / 2)) * k + (fr ? fr.left : 0)
        const y = (e.clientY ?? (h / 2)) * k + (fr ? fr.top : 0)
        // fx: where across the screen — the app enters full screen only from iOS's middle 44%
        // ('center' here is the tap-zone setting's: under 'corners' all but the bottom band).
        if (tapZone(x, y, w, h, comic.tapZones) === 'center') post('tap', { zone: 'center', fx: x / w })
    }, false)
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
            // ONLY THE VISIBLE PAGE IS "CURRENT" (field, 08-30; the -05 lesson reborn):
            // prerendered neighbours' load events fire LAST, and letting them win left
            // currentDoc pointing off-screen — so every re-lay (mode toggle, balloon index)
            // handed the prerender gate a neighbour and bailed: "doesn't zoom", both walks.
            let visNow = e.detail.index
            try { const i = view?.renderer?.index; if (i != null && i >= 0) visNow = i } catch (err) {}
            if (e.detail.index === visNow || currentDoc == null) {
                currentDoc = e.detail.doc; currentIndex = e.detail.index
            }
            const doc = e.detail.doc, idx = e.detail.index
            comicPageLoaded(doc, idx)
            doc.addEventListener('selectionchange', () => {
                const sel = doc.getSelection?.()
                const s = sel ? sel.toString() : ''
                if (s && s.trim() && sel.rangeCount) {
                    lastSelText = s; lastSelRange = sel.getRangeAt(0).cloneRange()
                    lastSelDoc = doc; lastSelIndex = idx
                }
            })
            bindCentreTap(doc)
        })
        view.addEventListener('draw-annotation', e => {
            const { draw, annotation } = e.detail
            // e-ink: a 0.3-opacity yellow fill is invisible on a grayscale Boox screen, so the
            // highlight style paints the fill AND a solid black underline in one group (the
            // underline carries it; the band adds context). The other styles are solid strokes
            // already. An unknown style falls back to highlight rather than drawing nothing —
            // a mark written by a newer build must never vanish on an older one.
            draw(styleFor(annotation.style), { color: annotation.color || '#ffd60a' })
        })
        // Tapping an existing highlight → open its menu (copy / note / remove). Include the
        // highlighted text (from the annotation's range) so the app can offer a Copy action.
        view.addEventListener('show-annotation', e => {
            let text = ''
            try { text = e.detail.range ? e.detail.range.toString() : '' } catch (err) {}
            post('tapAnnotation', { cfi: e.detail.value, text })
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
        // A HAND'S TURN (Michael, 2026-10-12: full screen leaves nothing on the page — what the
        // centre tap brought back goes again when you turn the page; review 2026-10-12, iOS's twin).
        // The app hears every turn it asks for itself; this is foliate's own — a swipe in a text book
        // or a PDF, a scroll in scrolled flow. The view's 'relocate' drops the paginator's reason, so
        // it is read here, off the renderer: 'snap' (a swipe), 'page' (next/prev) or 'scroll'
        // (scrolled flow), and only when the place moved — a swipe let go short, or a tap that moved
        // a pixel, snaps back with a 'snap' too. A re-lay ('anchor': Aa, a turn of the device) and a
        // jump ('navigation') are never a turn — except a swipe off a chapter's last page, which
        // lands through the paginator's #goTo as 'navigation': a relocate into ANOTHER section
        // within a second of a finger leaving the page counts (the contents and the app's own jumps
        // are tapped in the app, never on the page).
        try {
            let turnAt = null, turnIndex = null
            view.renderer?.addEventListener('relocate', e => {
                const d = e.detail || {}
                const at = `${d.index}:${d.fraction}`
                const moved = turnAt !== null && at !== turnAt
                const crossed = turnIndex !== null && d.index !== turnIndex
                    && Date.now() - fingerLiftedAt < 1000
                turnAt = at
                turnIndex = d.index
                if (moved && (d.reason === 'snap' || d.reason === 'page' || d.reason === 'scroll' || crossed))
                    post('turned', { reason: d.reason })
            })
        } catch (e) {}
        applyLayout()
        // A COMIC'S LAYOUT ATTRS GO DOWN BEFORE THE FIRST PAINT. The app pushes its prefs
        // ahead of the open now; applying spread/zoom here — after open() built the book,
        // before next() lays the first page — means the first render IS the right render,
        // instead of a re-render racing it (the blank-page-on-open).
        if (comic.isComic) {
            try {
                view.renderer.setAttribute('spread', comic.spreadPref)
                view.renderer.setAttribute('zoom', comic.effZoom())
            } catch (e) {}
        }
        await view.renderer.next()
        if (msg) msg.style.display = 'none'
        const book = view.book
        post('loaded', {
            title: langMap(book.metadata?.title) || 'Untitled',
            author: contributor(book.metadata?.author),
        })
        // Cover art → a data URL (for the book-gram card). Best-effort; foliate exposes getCover().
        try {
            const blob = await book.getCover?.()
            if (blob) {
                const fr = new FileReader()
                fr.onload = () => post('cover', { dataUrl: String(fr.result || '') })
                fr.readAsDataURL(blob)
            }
        } catch (e) {}
        // Surface the table of contents (flattened with depth for indenting).
        try {
            const toc = []
            const walk = (items, depth) => {
                for (const it of (items || [])) {
                    if (it.label) {
                        // The spine index the href lands in — how read-along pairs an audiobook
                        // chapter with a SECTION of the book (ReadAlongPairing.TextChapter).
                        let index = -1
                        try { index = book.resolveHref?.(it.href)?.index ?? -1 } catch (err) {}
                        toc.push({ label: String(it.label).trim(), href: it.href || '', depth, index })
                    }
                    if (it.subitems) walk(it.subitems, depth + 1)
                }
            }
            walk(book.toc, 0)
            post('toc', { items: toc })
        } catch (e) {}
        // The chapter label rides along because the app groups marks by it: there is no CFI
        // comparator on the Kotlin side, so where the reader IS at the moment of a mark is the
        // only chance to record which section it belongs to.
        view.addEventListener('relocate', e => {
            // Where the page IS — the first visible text of the page (paginated) or the first
            // visible line (scrolled). Read Aloud starts here; see getReaderTextFromHere.
            if (e.detail?.range) lastVisibleRange = e.detail.range
            // A relocate means a page just laid out and rendered — whatever the status overlay
            // was reporting is over. This is the standing hide (the post-open hide below only
            // ever ran once per book, so a mid-session error used to stick until the book closed).
            hideStatus()
            // THE WALK FOLLOWS THE RELOCATE, not just the 'load' event: fixed-layout CACHES its
            // page iframes, so turning BACK to an already-rendered page fires no load at all —
            // the walk kept standing on the page you left, stepping a doc that was off screen.
            if (comic.isComic) {
                try {
                    const idx = view?.renderer?.index
                    if (idx != null && idx >= 0 && idx !== comic.docIndex) {
                        const c = view.renderer.getContents?.()?.find(x => x.index === idx)
                        if (c?.doc) comicPageLoaded(c.doc, idx)
                    }
                } catch (err) {}
            }
            // `index` = the spine index on screen — the app starts a balloon pass there and
            // counts that page's balloons for the Comic menu. progress.js's section.current
            // is the section the relocate is about; the renderer's own index is the fallback.
            let sectionIndex = -1
            try { sectionIndex = e.detail.section?.current ?? view?.renderer?.index ?? -1 } catch (err) {}
            // How far into THIS section the page is (0–1), from the paginator's page count —
            // read-along's "approximate" timing starts the narration that far into the chapter.
            let sectionFraction = 0
            try {
                // From the book fraction and the sections' sizes first (progress.js's own
                // arithmetic, inverted) — the same answer in paginated and scrolled flow.
                const f = e.detail.fraction
                const sizes = (book?.sections ?? []).map(s => Number(s?.size) || 0)
                const total = sizes.reduce((a, b) => a + b, 0)
                if (typeof f === 'number' && total > 0 && sectionIndex >= 0 && sizes[sectionIndex] > 0) {
                    const before = sizes.slice(0, sectionIndex).reduce((a, b) => a + b, 0) / total
                    sectionFraction = Math.min(1, Math.max(0, (f - before) / (sizes[sectionIndex] / total)))
                } else {
                    const r = view?.renderer, pages = r?.pages ?? 0
                    if (pages > 0) sectionFraction = Math.min(1, Math.max(0, (r.page + 0.5) / pages))
                }
            } catch (err) {}
            try { lastReloc = { index: sectionIndex, sectionFraction, pages: view?.renderer?.pages ?? 0 } } catch (err) {}
            // A comic whose page order moved (comic-book.js comicPositions) names its pages in
            // its CFIs; `legacyCfi` is the same page's pre-slice-2 CFI, so a bookmark saved
            // before the update still matches this page (Michael 2026-10-11, slice 2 review).
            let legacyCfi = ''
            try { legacyCfi = view?.book?.sections?.[sectionIndex]?.legacyCfi || '' } catch (err) {}
            post('relocate', {
                fraction: e.detail.fraction ?? 0,
                cfi: e.detail.cfi || '',
                legacyCfi,
                chapter: String(e.detail.tocItem?.label || '').trim(),
                index: sectionIndex,
                sectionFraction,
            })
        })
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
window.markSelection = (style) => {
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
        const st = MARK_STYLES[style] ? style : 'highlight'
        view?.addAnnotation({ value: cfi, color: '#ffd60a', style: st })
        lastSelText = ''; lastSelRange = null
        try { doc?.getSelection()?.removeAllRanges() } catch (e) {}
        // The style rides back so the app can persist it — a mark restored without its style
        // would silently become a highlight on the next open.
        post('highlight', { text, cfi, style: st })
    } catch (e) { post('highlight', { text: '', error: String(e) }) }
}

// The old name, kept: it is called from the pill, the tuck panel and the selection menu, and a
// rename across three call sites buys nothing. Plain highlight, as it always was.
window.highlightSelection = () => window.markSelection('highlight')

// Swift → JS: jump to a TOC entry (or search-result CFI).
window.goToHref = (href) => { if (href) { try { view?.goTo(href) } catch (e) {} } }

// Swift → JS: resume at a saved reading position (CFI) on open.
window.goToCfi = (cfi) => { if (cfi) { try { view?.goTo(cfi) } catch (e) {} } }

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

// Kotlin/Swift → JS: the section's text FROM HERE to its end — from the selection when there is
// one, else from the first visible text on the page. Read Aloud starts where the eye (or the
// highlight) is, not at the section's first line (Michael, 09-06: "it starts playing the
// beginning of the book instead of the highlight or page"). Falls back to getReaderText when
// there is nothing to anchor on (a comic, a page that never relocated).
window.getReaderTextFromHere = () => {
    try {
        const clean = s => (s || '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim()
        let doc = currentDoc?.body ? currentDoc : null
        if (!doc) for (const f of document.querySelectorAll('iframe')) {
            try { if (f.contentDocument?.body) { doc = f.contentDocument; break } } catch (e) {}
        }
        if (!doc) return window.getReaderText()
        let start = null
        const sel = doc.getSelection && doc.getSelection()
        if (sel && !sel.isCollapsed && sel.rangeCount && sel.toString().trim()) start = sel.getRangeAt(0)
        else if (lastVisibleRange?.startContainer?.ownerDocument === doc) start = lastVisibleRange
        if (!start) return window.getReaderText()
        const r = doc.createRange()
        r.setStart(start.startContainer, start.startOffset)
        r.setEnd(doc.body, doc.body.childNodes.length)
        // innerText (not Range.toString) so paragraph breaks survive — the sentence splitter
        // downstream needs the whitespace between blocks. innerText needs layout, hence the
        // offscreen scratch node, removed the moment it has been read.
        const tmp = doc.createElement('div')
        tmp.style.cssText = 'position:absolute;left:-100000px;top:0;width:800px;visibility:hidden'
        tmp.appendChild(r.cloneContents())
        doc.body.appendChild(tmp)
        const t = clean(tmp.innerText)
        tmp.remove()
        return t || window.getReaderText()
    } catch (e) { return window.getReaderText() }
}

// Swift → JS: the current text selection, if any (else ''). Lets Synthesize/Write use an explicit
// highlighted boundary as their intake instead of the whole section. Scans the rendered iframes,
// same as the highlight path.
window.getReaderSelectionText = () => {
    try {
        const clean = s => (s || '').replace(/[ \t]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim()
        const docs = []
        if (currentDoc) docs.push(currentDoc)
        for (const f of document.querySelectorAll('iframe')) {
            try { if (f.contentDocument) docs.push(f.contentDocument) } catch (e) {}
        }
        for (const d of docs) {
            const s = d.getSelection && d.getSelection()
            if (s && !s.isCollapsed && s.rangeCount && s.toString().trim()) return clean(s.toString())
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

// App → JS: the reader's controls are on screen (normal reading, or full screen's peek) — the
// comic's "12 / 48" shows only with them (2026-10-12, iOS's twin).
window.setPageReadShown = (on) => { pageReadShown = !!on; updatePageRead() }
// Swift → JS controls. In a comic's panel mode the walk gets first refusal: panels within the
// page, then the ordinary page turn — next/prev step through panels, then to the next page.
window.pageLeft = () => { if (!comicStep(false)) view?.goLeft() }
window.pageRight = () => { if (!comicStep(true)) view?.goRight() }
window.setFlow = (f) => { settings.flow = f; applyLayout() }
window.applyCSS = (css) => view?.renderer.setStyles?.(css)
window.setReadingFont = (family) => { readingFontFamily = family || ''; applyLayout() }
// Swift → JS: install the @font-face block for bundled fonts (served via ledgerfont://).
window.setFontFaces = (css) => { fontFaces = css || ''; applyLayout() }
// Swift → JS: merge reader settings (font size, spacing, margins, width, flow…).
window.applyReaderSettings = (obj) => { Object.assign(settings, obj || {}); applyLayout() }
// Aa Type (Michael, 2026-10-11): settings, the @font-face block and the family in ONE pass, so the
// page lays out once — three calls were three re-lays, three e-ink refreshes for one change.
window.applyReaderType = (obj, faces, family) => {
    primeReaderType(obj, faces, family)
    applyLayout()
}
// Aa Type, BEFORE the book (Michael, 2026-10-11): the same state, but no lay-out — openBook's own
// applyLayout (after open(), before the first page is drawn) picks it up, so the first paint is
// the right paint and the 'loaded' push has nothing left to change (no second render).
const primeReaderType = (obj, faces, family) => {
    Object.assign(settings, obj || {})
    fontFaces = faces || ''
    readingFontFamily = family || ''
    return true
}
window.primeReaderType = primeReaderType
// Swift → JS: remove a highlight by its CFI.
window.deleteHighlight = (cfi) => { if (cfi) view?.deleteAnnotation({ value: cfi }) }
// Swift → JS: re-apply a stored highlight by CFI (called on load to restore saved marks).
// foliate paints it when its section renders (via the draw-annotation handler above).
window.addStoredHighlight = (cfi, style) => {
    if (!cfi) return
    try { view?.addAnnotation({ value: cfi, color: '#ffd60a', style: MARK_STYLES[style] ? style : 'highlight' }) } catch (e) {}
}
window.goToFraction = (f) => view?.goToFraction(f)
// Read-along's within-chapter nudge: the page `f` of the way through section `index`. Turns only
// when that is really another page (the paginator's page count, else a 5% step).
let lastReloc = { index: -1, sectionFraction: 0, pages: 0 }
window.goToSectionFraction = (index, f) => {
    try {
        if (!view || index < 0) return
        if (lastReloc.index === index) {
            const step = lastReloc.pages > 0 ? 1 / lastReloc.pages : 0.05
            if (Math.abs(f - lastReloc.sectionFraction) < step * 0.6) return
        }
        const sizes = (book?.sections ?? []).map(s => Number(s?.size) || 0)
        const total = sizes.reduce((a, b) => a + b, 0)
        if (!(total > 0) || !(sizes[index] > 0)) return
        const before = sizes.slice(0, index).reduce((a, b) => a + b, 0) / total
        view.goToFraction(Math.min(0.9999, Math.max(0, before + f * (sizes[index] / total))))
    } catch (err) {}
}

post('ready')
