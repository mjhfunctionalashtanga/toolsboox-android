// PANEL DETECTION for comics — recursive XY-cut over gutters.
//
// The algorithm is the classic gutter analysis that guided-reading features use (mihon-ds's
// "Guided Reading" was read as the REFERENCE for the approach and its failure modes — Apache 2.0,
// recorded in reference_ledger_upstream_references; the implementation here is ours, in JS,
// because BOTH readers are foliate in a WebView and a detector written once serves the Boox and
// the iPad — see DESIGN-comics-panel-view-2026-08-24.md §5).
//
// PURE FUNCTIONS over an ImageData-like {data, width, height} — no DOM, no canvas, no reader
// coupling — so the detector runs in node against synthetic pages (scripts/test-comic-panels.mjs)
// without opening a book.
//
// THE RULE THAT MAKES FAILURE SURVIVABLE: when detection yields ≤1 panel or implausibly many,
// detectPanels returns null and the page shows WHOLE. Bleeds, splashes, overlapping panels and
// dark backgrounds all land there by design — a guided view that strands the reader inside a
// mis-detected block is worse than no guided view.

// --- tuning, in one place -------------------------------------------------------------------
//
// RETUNED 2026-08-28 (field: "the wrong boxes problem is the main offense"; harness:
// scripts/harness-comic-fixtures.mjs). The old GUTTER_CONTENT_MAX = 4% was the killer: a row
// that crosses a panel's thin side BORDERS carries ~0.5% content, so interior rows of any
// outlined panel with sparse art (sky, bubble whitespace) read as gutters — the cut sliced
// through panels, over-segmented past MAX_PANELS, and every real page fell back. A TRUE
// gutter is near-EMPTY; the threshold now says so, with the content delta derived from the
// page's own border noise instead of a fixed 38.
const GUTTER_CONTENT_MAX = 0.0035 // a gutter line is near-empty (outline crossings are ~0.5%)
const MIN_GUTTER_FRAC = 0.004     // a gutter run must span ≥0.4% of the long side (dense manga
                                  // rules 8px gutters on a 1200px page; the strict emptiness
                                  // test above is what makes short runs trustworthy now)
const MIN_PANEL_FRAC = 0.05       // a panel must be ≥5% of page width AND height
const MAX_PANELS = 12             // more than this is garbage, not a page of panels
const CONTENT_DELTA_MIN = 24      // adaptive delta floor …
const CONTENT_DELTA_MAX = 64      // … and ceiling (see contentDelta)
const MAX_DEPTH = 4               // XY-cut recursion depth — panels-in-panels stop here
const CC_GAP_FRAC = 0.02          // component boxes closer than this merge (borderless art)
const COVERAGE_OK = 0.97     // the set explains ≈ all inked content — walk it as-is
const COVERAGE_RESCUE = 0.80 // 80–97%: the uncovered content rides as a FINAL rest step
const REST_MIN_AREA = 0.02   // a rest step must be a panel's worth of page, not gutter specks
const MIN_COVERAGE = 0.22         // panels must explain ≥22% of the page or it isn't a grid

// Luminance of an RGBA pixel.
const lum = (d, i) => 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2]

// The page's own background, read off its border — comics gutters match the page ground
// (usually white, sometimes black), never a colour we can assume.
export const estimateBackground = ({ data, width, height }) => {
    const samples = []
    const step = Math.max(1, (width >> 6))
    for (let x = 0; x < width; x += step) {
        samples.push(lum(data, (x) * 4))
        samples.push(lum(data, ((height - 1) * width + x) * 4))
    }
    const vstep = Math.max(1, (height >> 6))
    for (let y = 0; y < height; y += vstep) {
        samples.push(lum(data, (y * width) * 4))
        samples.push(lum(data, (y * width + width - 1) * 4))
    }
    samples.sort((a, b) => a - b)
    return samples[samples.length >> 1]   // median: a border logo can't drag it
}

// The content threshold, from the page itself: the border samples' spread (MAD) says how noisy
// this scan's ground is, and the delta sits well above that noise — a clean digital page keeps
// the tight floor, a grainy scan raises its own bar instead of reading grain as art.
export const contentDelta = ({ data, width, height }, bg) => {
    const devs = []
    const step = Math.max(1, (width >> 6))
    for (let x = 0; x < width; x += step) {
        devs.push(Math.abs(lum(data, x * 4) - bg))
        devs.push(Math.abs(lum(data, ((height - 1) * width + x) * 4) - bg))
    }
    devs.sort((a, b) => a - b)
    const mad = devs[devs.length >> 1]
    return Math.max(CONTENT_DELTA_MIN, Math.min(CONTENT_DELTA_MAX, Math.round(mad * 6 + 16)))
}

// content[x + y*width] = 1 where the pixel differs from the background enough to be art.
const contentMask = (img, bg, delta) => {
    const { data, width, height } = img
    const mask = new Uint8Array(width * height)
    for (let y = 0; y < height; y++) {
        const row = y * width
        for (let x = 0; x < width; x++) {
            if (Math.abs(lum(data, (row + x) * 4) - bg) > delta) mask[row + x] = 1
        }
    }
    return mask
}

// Runs of near-empty rows (axis='y') or columns (axis='x') inside rect — the gutters.
// Returns cut positions (run centres), excluding runs that touch the rect's edges
// (those are margins, not gutters between panels).
const gutterCuts = (mask, width, rect, axis, laneFrac = null) => {
    const { x, y, w, h } = rect
    const long = axis === 'y' ? h : w
    const lane = axis === 'y' ? w : h
    const minRun = Math.max(3, Math.round(long * MIN_GUTTER_FRAC))
    const empty = new Uint8Array(long)
    for (let i = 0; i < long; i++) {
        let content = 0
        if (axis === 'y') {
            const row = (y + i) * width
            for (let j = 0; j < lane; j++) content += mask[row + x + j]
        } else {
            for (let j = 0; j < lane; j++) content += mask[(y + j) * width + x + i]
        }
        // Near-EMPTY, not merely sparse: the tight fraction (plus a 2px speck allowance for
        // dust on scans) rejects lines that cross panel borders or art, which is what kept
        // cuts out of panel interiors — the wrong-boxes offense.
        empty[i] = content <= Math.max(2, lane * (laneFrac ?? GUTTER_CONTENT_MAX)) ? 1 : 0
    }
    const cuts = []
    let runStart = -1
    for (let i = 0; i <= long; i++) {
        const e = i < long ? empty[i] : 0
        if (e && runStart < 0) runStart = i
        if (!e && runStart >= 0) {
            const runLen = i - runStart
            const touchesEdge = runStart === 0 || i === long
            if (!touchesEdge && runLen >= minRun) cuts.push(runStart + (runLen >> 1))
            runStart = -1
        }
    }
    return cuts
}

// Shrink rect to its content bounding box (so a crop doesn't carry half a gutter).
const trimToContent = (mask, width, rect) => {
    const { x, y, w, h } = rect
    let minX = x + w, maxX = x - 1, minY = y + h, maxY = y - 1
    for (let yy = y; yy < y + h; yy++) {
        const row = yy * width
        for (let xx = x; xx < x + w; xx++) {
            if (mask[row + xx]) {
                if (xx < minX) minX = xx
                if (xx > maxX) maxX = xx
                if (yy < minY) minY = yy
                if (yy > maxY) maxY = yy
            }
        }
    }
    if (maxX < minX || maxY < minY) return null   // an empty block is not a panel
    return { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 }
}

const splitAt = (rect, cuts, axis) => {
    const out = []
    let start = 0
    const edges = [...cuts, axis === 'y' ? rect.h : rect.w]
    for (const c of edges) {
        if (c - start > 0) out.push(axis === 'y'
            ? { x: rect.x, y: rect.y + start, w: rect.w, h: c - start }
            : { x: rect.x + start, y: rect.y, w: c - start, h: rect.h })
        start = c
    }
    return out
}

const xyCut = (mask, width, rect, depth, out, laneFrac = null) => {
    if (depth <= 0) { out.push(rect); return }
    const hCuts = gutterCuts(mask, width, rect, 'y', laneFrac)
    if (hCuts.length) {
        for (const band of splitAt(rect, hCuts, 'y')) xyCut(mask, width, band, depth - 1, out, laneFrac)
        return
    }
    const vCuts = gutterCuts(mask, width, rect, 'x', laneFrac)
    if (vCuts.length) {
        for (const col of splitAt(rect, vCuts, 'x')) xyCut(mask, width, col, depth - 1, out, laneFrac)
        return
    }
    out.push(rect)
}

// Reading order: cluster into rows by vertical overlap, rows top-to-bottom, and within a row
// left-to-right — or right-to-left for manga. RTL is a per-book SETTING, never a guess
// (a loose CBZ carries nothing reliable to guess from — design §4).
export const orderPanels = (rects, rtl = false) => {
    const rows = []
    for (const r of [...rects].sort((a, b) => a.y - b.y)) {
        const row = rows.find(row => {
            const overlap = Math.min(row.bottom, r.y + r.h) - Math.max(row.top, r.y)
            return overlap > 0.3 * Math.min(row.bottom - row.top, r.h)
        })
        if (row) {
            row.items.push(r)
            row.top = Math.min(row.top, r.y)
            row.bottom = Math.max(row.bottom, r.y + r.h)
        } else rows.push({ top: r.y, bottom: r.y + r.h, items: [r] })
    }
    rows.sort((a, b) => a.top - b.top)
    const out = []
    for (const row of rows) {
        row.items.sort((a, b) => rtl ? (b.x + b.w) - (a.x + a.w) : a.x - b.x)
        out.push(...row.items)
    }
    return out
}

/**
 * The detector. {data,width,height} → ordered panel rects in the SAME pixel space, or NULL
 * meaning "show the page whole" — the fallback IS the feature.
 */
// Connected components over a coarse grid of the mask — the borderless-layout fallback.
// XY-cut needs straight through-gutters; art floating on open ground has none. Components
// of content, boxed and merged when nearly touching, recover those pages. Coarse cells
// (~1% of the page a side) keep this O(page) and act as the dilation that holds one
// panel's separate strokes together.
const componentPanels = (mask, width, height) => {
    const cell = Math.max(4, Math.round(Math.max(width, height) * 0.01))
    const gw = Math.ceil(width / cell), gh = Math.ceil(height / cell)
    const occ = new Uint8Array(gw * gh)
    for (let y = 0; y < height; y++) {
        const row = y * width, grow = Math.floor(y / cell) * gw
        for (let x = 0; x < width; x++) if (mask[row + x]) occ[grow + Math.floor(x / cell)] = 1
    }
    const label = new Int32Array(gw * gh).fill(-1)
    const boxes = []
    const stack = []
    for (let i = 0; i < gw * gh; i++) {
        if (!occ[i] || label[i] >= 0) continue
        const id = boxes.length
        boxes.push({ x0: gw, y0: gh, x1: -1, y1: -1 })
        stack.push(i); label[i] = id
        while (stack.length) {
            const j = stack.pop()
            const jx = j % gw, jy = (j / gw) | 0
            const b = boxes[id]
            if (jx < b.x0) b.x0 = jx
            if (jx > b.x1) b.x1 = jx
            if (jy < b.y0) b.y0 = jy
            if (jy > b.y1) b.y1 = jy
            for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
                const nx = jx + dx, ny = jy + dy
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                const n = ny * gw + nx
                if (occ[n] && label[n] < 0) { label[n] = id; stack.push(n) }
            }
        }
    }
    let rects = boxes.map(b => ({
        x: b.x0 * cell, y: b.y0 * cell,
        w: (b.x1 - b.x0 + 1) * cell, h: (b.y1 - b.y0 + 1) * cell,
    }))
    // Merge boxes separated by less than a gutter's worth — one panel's islands of art.
    // The threshold is read off the boxes THEMSELVES when they say something: sort the
    // pairwise separations and look for the largest relative jump — below it are the
    // intra-panel holes sparse art leaves, above it the real gutters. A page whose gaps
    // show no such break keeps the conservative default, and the plausibility bar after
    // us still gets the final word.
    const seps = []
    for (let a = 0; a < rects.length; a++) for (let b = a + 1; b < rects.length; b++) {
        const A = rects[a], B = rects[b]
        const dx = Math.max(0, Math.max(A.x, B.x) - Math.min(A.x + A.w, B.x + B.w))
        const dy = Math.max(0, Math.max(A.y, B.y) - Math.min(A.y + A.h, B.y + B.h))
        seps.push(Math.max(dx, dy))
    }
    seps.sort((a, b) => a - b)
    let gap = Math.max(width, height) * CC_GAP_FRAC
    for (let i = 1; i < seps.length; i++) {
        if (seps[i - 1] > 0 && seps[i] / seps[i - 1] >= 1.6 && seps[i - 1] < Math.max(width, height) * 0.06) {
            gap = (seps[i - 1] + seps[i]) / 2
        }
    }
    let merged = true
    while (merged && rects.length > 1) {
        merged = false
        outer: for (let a = 0; a < rects.length; a++) {
            for (let b = a + 1; b < rects.length; b++) {
                const A = rects[a], B = rects[b]
                const dx = Math.max(0, Math.max(A.x, B.x) - Math.min(A.x + A.w, B.x + B.w))
                const dy = Math.max(0, Math.max(A.y, B.y) - Math.min(A.y + A.h, B.y + B.h))
                if (dx < gap && dy < gap) {
                    const x = Math.min(A.x, B.x), y = Math.min(A.y, B.y)
                    rects[a] = { x, y, w: Math.max(A.x + A.w, B.x + B.w) - x, h: Math.max(A.y + A.h, B.y + B.h) - y }
                    rects.splice(b, 1)
                    merged = true
                    break outer
                }
            }
        }
    }
    return rects
}

// The panels' verdict must EXPLAIN the page before it is allowed to crop it: a plausible
// count, real coverage, and no overlapping boxes — anything less shows the page whole
// (the fallback IS the feature; a wrong crop is the offense).
const plausible = (panels, width, height, fromComponents = false) => {
    if (!panels || panels.length <= 1 || panels.length > MAX_PANELS) return false
    let area = 0
    for (let i = 0; i < panels.length; i++) {
        const a = panels[i]
        area += a.w * a.h
        for (let j = i + 1; j < panels.length; j++) {
            const b = panels[j]
            const ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x))
            const iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y))
            if (ix * iy > 0.15 * Math.min(a.w * a.h, b.w * b.h)) return false
        }
    }
    // The more panels a verdict claims, the more of the page it must explain: a true
    // 9-grid covers most of its page; eleven boxes covering a quarter of one are art
    // fragments wearing a layout's count (harness: sparse borderless at 26% coverage).
    // Component verdicts answer to a higher bar than ruled gutters: XY-cut's cuts are
    // physical near-empty lanes, but a clustering's boxes are inference — and sparse art
    // that merged into two low-coverage blobs is a guess wearing a layout's face.
    const floor = fromComponents ? Math.max(0.35, 0.05 * panels.length) : Math.max(MIN_COVERAGE, 0.05 * panels.length)
    return area >= floor * width * height
}

// A claimed panel that CONTAINS a real gutter is two panels wearing one box — the failure
// that reads worst on device (the walk stops mid-pair). Checked only for small counts, with
// ABSOLUTE emptiness (a run of zero-content lanes wide enough to be ruled): sparse art can
// fake near-empty, but a hand-ruled gutter is truly empty, and only truly empty convicts.
const containsRealGutter = (mask, width, r) => {
    for (const axis of ['x', 'y']) {
        const long = axis === 'x' ? r.w : r.h
        const lane = axis === 'x' ? r.h : r.w
        const minRun = Math.max(6, Math.round(long * 0.02))
        let run = 0
        for (let i = 0; i < long; i++) {
            let content = 0
            if (axis === 'x') { for (let j = 0; j < lane; j++) content += mask[(r.y + j) * width + r.x + i] }
            else { const row = (r.y + i) * width; for (let j = 0; j < lane; j++) content += mask[row + r.x + j] }
            if (content === 0) { run++; if (run >= minRun && i > minRun && i < long - 2) return true }
            else run = 0
        }
    }
    return false
}

// THE COVERAGE GUARANTEE (field, 08-30: "sometimes the panel walk skips panels — hard to
// know when you've missed something good"). A PARTIAL detection is worse than none: whole
// shows everything, 4-of-5 hides the fifth in silence. The winning verdict is audited
// against the content mask — covered inked pixels / total inked pixels:
//   ≥ COVERAGE_OK      → the set explains the page;
//   ≥ COVERAGE_RESCUE  → nearly: the uncovered content's bbox becomes a FINAL walk step,
//                        so the walk still shows 100% of the page across its steps;
//   below              → partial confidence IS low confidence: whole page, reason
//                        'incomplete' (the why-whole toast says so, not "no layout").
export const coverageAudit = (mask, width, height, panels) => {
    let total = 0, covered = 0, uncovered = 0
    let bx0 = width, by0 = height, bx1 = -1, by1 = -1
    const row = new Uint8Array(width)
    for (let y = 0; y < height; y++) {
        row.fill(0)
        for (const p of panels) {
            if (y < p.y || y >= p.y + p.h) continue
            row.fill(1, Math.max(0, p.x), Math.min(width, p.x + p.w))
        }
        const base = y * width
        for (let x = 0; x < width; x++) {
            if (!mask[base + x]) continue
            total++
            if (row[x]) { covered++; continue }
            uncovered++
            if (x < bx0) bx0 = x
            if (x > bx1) bx1 = x
            if (y < by0) by0 = y
            if (y > by1) by1 = y
        }
    }
    if (!total) return { action: 'ok' }
    const ratio = covered / total
    if (ratio >= COVERAGE_OK) return { action: 'ok' }
    const rect = bx1 >= bx0 ? { x: bx0, y: by0, w: bx1 - bx0 + 1, h: by1 - by0 + 1 } : null
    if (ratio >= COVERAGE_RESCUE) {
        // Specks scattered in gutters can dip the ratio without a panel's worth of missed
        // art — a rest step needs real area, or it is chrome pointing at dust.
        if (rect && uncovered >= REST_MIN_AREA * width * height) return { action: 'rest', rect }
        return { action: 'ok' }
    }
    return { action: 'whole' }
}

export const detectPanels = (img, { rtl = false } = {}) => {
    detectPanels.lastReason = null
    if (!img || !img.width || !img.height || img.width < 32 || img.height < 32) return null
    const bg = estimateBackground(img)
    const delta = contentDelta(img, bg)
    const strict = detectPass(img, bg, delta, rtl)
    if (strict) return strict
    // THE RELAXED TIER (field, 08-30: "Panel by panel doesn't zoom" — on REAL scans the strict
    // bars refused so many pages that the walk vanished; 0% wrong crops had eaten the feature).
    // Scan dirt is what defeats the near-empty gutter test: JPEG noise and print bleed lift a
    // true gutter's rows just past the content threshold, and no cut is ever found. The relaxed
    // pass re-reads the page with a NOISE-TOLERANT mask (delta ×1.7 — dirt drops out, ink
    // stays) and judges the cuts under looser count/coverage bars. RULED GUTTERS ONLY: the
    // component clustering stays strict-tier — loosening inference is how wrong crops happen,
    // but a ruled cut on a forgiving mask is still a physical lane. The dust-sparse page still
    // refuses (no lanes at any tolerance) — the fallback law holds at the bottom.
    // Two tolerances, one per dirt kind: a slightly lifted content threshold absorbs light
    // gray wash; the LANE fraction is the real rescue — a gutter row may carry up to 9%
    // speck pixels (print bleed dots) and still read as a gutter, where strict demands
    // near-zero. Cuts through art stay convicted by the acceptance bars below.
    return detectPass(img, bg, delta * 1.3, rtl, {
        maxPanels: 14, minPanelFrac: 0.04, coverageBase: 0.15, components: false,
        laneFrac: 0.09,
    })
}

const detectPass = (img, bg, delta, rtl, relaxed = null) => {
    const mask = contentMask(img, bg, delta)
    const minFrac = relaxed ? relaxed.minPanelFrac : MIN_PANEL_FRAC
    const minW = img.width * minFrac
    const minH = img.height * minFrac
    const clean = (rects) => rects
        .map(r => trimToContent(mask, img.width, r))
        .filter(r => r && r.w >= minW && r.h >= minH)
    const ok = (panels, fromComponents = false) => relaxed
        ? plausibleRelaxed(panels, img.width, img.height, relaxed)
        : plausible(panels, img.width, img.height, fromComponents)
    const leaves = []
    xyCut(mask, img.width, { x: 0, y: 0, w: img.width, h: img.height }, MAX_DEPTH, leaves,
        relaxed ? relaxed.laneFrac : null)
    let panels = clean(leaves)
    // Straight gutters found nothing believable — try content components (borderless
    // layouts, staggered panels, L-gutters), judged by the same plausibility bar PLUS a
    // tighter count: component boxes come from clustering, not from ruled gutters, and a
    // clustering that claims more than 8 panels is art fragmenting, not a layout — that
    // page shows WHOLE (harness: sparse borderless art shatters into ~11 boxes with no
    // clean gap-break; a wrong walk is the offense, a whole page never is).
    let fromComponents = false
    if (!ok(panels) && (!relaxed || relaxed.components)) {
        const cc = clean(componentPanels(mask, img.width, img.height))
        panels = cc.length <= 8 ? cc : []
        fromComponents = true
    }
    if (!ok(panels, fromComponents)) return null
    if (panels.length <= 3 && panels.some(r => containsRealGutter(mask, img.width, r))) return null
    const ordered = orderPanels(panels, rtl)
    // The guarantee: no verdict ships that silently skips art (see coverageAudit).
    const audit = coverageAudit(mask, img.width, img.height, ordered)
    if (audit.action === 'whole') { detectPanels.lastReason = 'incomplete'; return null }
    if (audit.action === 'rest')
        ordered.push(trimToContent(mask, img.width, audit.rect) || audit.rect)
    detectPanels.lastReason = null
    return ordered
}

// The relaxed acceptance: more panels allowed, a lower coverage floor — but overlap still
// convicts (two boxes sharing art is a wrong crop in waiting), every panel must be a real
// region (≥2% of the page), and at least two panels or it is not a walk.
const plausibleRelaxed = (panels, width, height, o) => {
    if (!panels || panels.length <= 1 || panels.length > o.maxPanels) return false
    let area = 0
    for (let i = 0; i < panels.length; i++) {
        const a = panels[i]
        if (a.w * a.h < 0.02 * width * height) return false
        area += a.w * a.h
        for (let j = i + 1; j < panels.length; j++) {
            const b = panels[j]
            const ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x))
            const iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y))
            if (ix * iy > 0.15 * Math.min(a.w * a.h, b.w * b.h)) return false
        }
    }
    return area >= Math.max(o.coverageBase, 0.04 * panels.length) * width * height
}
