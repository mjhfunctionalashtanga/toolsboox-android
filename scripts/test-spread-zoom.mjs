// Spread-zoom algebra checks (-43). These MIRROR the formulas in reader-embed.js
// (applyPanelCrop's outer/want/s and spreadTapZoom's anchor math) — the embed module is
// browser-bound (it boots a view at import), so the math is restated here and any edit to
// the embed's formulas must be mirrored. Worked at the field device class: Tab8 landscape.
const eq = (a, b, eps, msg) => {
    if (Math.abs(a - b) > eps) { console.error(`FAIL ${msg}: ${a} vs ${b}`); process.exitCode = 1 }
    else console.log(`ok  ${msg}`)
}

// ---- the spread: two 1000×1500 pages, viewport 1872×1404 ----
const vw = 1872, vh = 1404
const pages = [{ W: 1000, H: 1500 }, { W: 1000, H: 1500 }]
const sumW = pages.reduce((a, p) => a + p.W, 0)
const maxH = Math.max(...pages.map(p => p.H))
const fit = Math.min(vw / sumW, vh / maxH)
eq(fit, 0.936, 1e-9, 'spread fit = min(vw/ΣW, vh/maxH)')

// a panel on the left page
const padOf = (pw, ph) => Math.round(Math.max(pw, ph) * 0.015)
const wantFor = (q, pw, ph) => {
    const qp = padOf(pw, ph)
    const qx = Math.max(0, q.x - qp), qy = Math.max(0, q.y - qp)
    const qw = Math.min(pw - qx, q.w + qp * 2), qh = Math.min(ph - qy, q.h + qp * 2)
    return { want: Math.min(vw / qw, vh / qh) / Math.min(pw / qw, ph / qh), qw, qh }
}
const q = { x: 100, y: 200, w: 800, h: 600 }
const { want, qw, qh } = wantFor(q, 1000, 1500)
const outer = Math.min(Math.max(want, fit), fit * 6)
const sFill = Math.min(1000 / qw, 1500 / qh)
const s = Math.min(sFill, Math.min(vw / qw, vh / qh) / outer)
// the crop must MEET THE VIEWPORT on its binding axis — the whole point of the raise
const cropW = qw * s * outer, cropH = qh * s * outer
eq(Math.max(cropW / vw, cropH / vh), 1, 0.01, 'spread panel crop meets the viewport')
// …and the outer never dips below the spread's own fit
if (outer < fit - 1e-9) { console.error('FAIL outer under spread fit'); process.exitCode = 1 }
else console.log('ok  outer ≥ spread fit')

// ---- single page reduces to the -42 formula exactly ----
const one = [{ W: 1000, H: 1500 }]
const fit1 = Math.min(vw / one[0].W, vh / one[0].H)
eq(fit1, Math.min(vw / 1000, vh / 1500), 1e-12, 'single page fit unchanged')

// ---- spreadTapZoom anchor: the tapped content point lands at the viewport centre ----
const f = 2
const scrollLeft = 120, hostX = 700
const contentX = scrollLeft + hostX
const newScroll = Math.max(0, contentX * f - vw / 2)
eq(contentX * f - newScroll, vw / 2, 1e-9, 'tap point centres after ×2')
console.log(process.exitCode ? 'SPREAD ZOOM ALGEBRA: FAIL' : 'SPREAD ZOOM ALGEBRA: all good')


// ── FRAME-ANCHORED OUTER ZOOM (field, 08-30: lasso on a wide page flung pages forward) ──
// The old law scaled raw scrollLeft by the zoom factor — true only at the scroller origin.
// The new law places the target point against the freshly measured frame and is invariant
// to how deep in the book the reader stands.
{
    const t = (name, cond) => { if (!cond) { console.error('ANCHOR FAIL: ' + name); process.exit(1) } }
    const anchor = (scroll, frameLeftRel, cxN, effNew, viewportW) =>
        Math.max(0, scroll + frameLeftRel + cxN * effNew - viewportW / 2)
    // same page geometry, two reading depths — the anchored result tracks the frame, which
    // moves with the scroll, so the TARGETED point lands centred either way
    const eff = 0.9, W = 2000, cxN = 1500, vw = 1872
    const shallow = anchor(0,   300, cxN, eff, vw)
    const deep    = anchor(50000, 300, cxN, eff, vw)
    t('anchor is scroll + frame-relative placement', Math.abs((deep - shallow) - 50000) < 0.001)
    // the OLD math at depth: (scroll + host)*f — diverges by (f-1)*scroll ≈ pages of fling
    const f = 3, host = 800
    const oldMath = (50000 + host) * f - vw / 2
    t('old math flings at depth (the bug this pins)', oldMath - deep > 40000)
    console.log('FRAME ANCHOR: all good')
}
