// Tests for the panel detector — synthetic pages, no DOM, no book.
//   node scripts/test-comic-panels.mjs
// Pins the fallback rules (≤1 → null, dark ground → null unless gutters match it) and the
// reading order (rows top-to-bottom, RTL flips within a row) — the two behaviours a build
// must not regress without noticing.
import { detectPanels, orderPanels, estimateBackground } from '../app/src/main/assets/foliate/comic-panels.js'

let failures = 0
const check = (name, cond, extra = '') => {
    if (cond) console.log(`  ok  ${name}`)
    else { failures++; console.error(`FAIL  ${name}  ${extra}`) }
}

// A synthetic page: white ground, black-ish filled rects for panels.
const page = (w, h, rects, { ground = 245, ink = 20 } = {}) => {
    const data = new Uint8ClampedArray(w * h * 4)
    for (let i = 0; i < w * h; i++) {
        data[i * 4] = data[i * 4 + 1] = data[i * 4 + 2] = ground
        data[i * 4 + 3] = 255
    }
    for (const r of rects) {
        for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) {
            const i = (y * w + x) * 4
            data[i] = data[i + 1] = data[i + 2] = ink
        }
    }
    return { data, width: w, height: h }
}

const within = (a, b, tol) =>
    Math.abs(a.x - b.x) <= tol && Math.abs(a.y - b.y) <= tol &&
    Math.abs(a.w - b.w) <= tol && Math.abs(a.h - b.h) <= tol

// --- 2×2 grid, clean gutters → four panels in reading order -------------------------------
{
    const rects = [
        { x: 20, y: 20, w: 170, h: 250 }, { x: 210, y: 20, w: 170, h: 250 },
        { x: 20, y: 300, w: 170, h: 250 }, { x: 210, y: 300, w: 170, h: 250 },
    ]
    const out = detectPanels(page(400, 570, rects))
    check('2x2 grid finds 4 panels', out?.length === 4, `got ${out?.length}`)
    if (out?.length === 4) {
        check('2x2 reading order is TL,TR,BL,BR',
            out[0].x < out[1].x && out[0].y === out[1].y && out[2].y > out[0].y && out[2].x < out[3].x,
            JSON.stringify(out))
        check('2x2 rects trim to content', within(out[0], rects[0], 3), JSON.stringify(out[0]))
    }
    const rtl = detectPanels(page(400, 570, rects), { rtl: true })
    check('2x2 RTL flips within rows', rtl[0].x > rtl[1].x && rtl[2].x > rtl[3].x, JSON.stringify(rtl))
}

// --- three horizontal strips → three panels ------------------------------------------------
{
    const rects = [
        { x: 15, y: 15, w: 370, h: 160 },
        { x: 15, y: 205, w: 370, h: 160 },
        { x: 15, y: 395, w: 370, h: 160 },
    ]
    const out = detectPanels(page(400, 570, rects))
    check('3 strips find 3 panels', out?.length === 3, `got ${out?.length}`)
    if (out?.length === 3) check('strips ordered top to bottom', out[0].y < out[1].y && out[1].y < out[2].y)
}

// --- splash page (one full-bleed panel) → null: show it whole ------------------------------
{
    const out = detectPanels(page(400, 570, [{ x: 0, y: 0, w: 400, h: 570 }]))
    check('splash page falls back to whole page', out === null, `got ${out?.length}`)
}

// --- blank page → null ---------------------------------------------------------------------
{
    const out = detectPanels(page(400, 570, []))
    check('blank page falls back to whole page', out === null, `got ${out?.length}`)
}

// --- dark background with dark gutters: bg estimate follows the page's own ground ----------
{
    const rects = [
        { x: 20, y: 20, w: 170, h: 530 }, { x: 210, y: 20, w: 170, h: 530 },
    ]
    const out = detectPanels(page(400, 570, rects, { ground: 15, ink: 235 }))
    check('black-ground page still splits on its own gutters', out?.length === 2, `got ${out?.length}`)
    const bg = estimateBackground(page(400, 570, [], { ground: 15 }))
    check('background is read from the page, not assumed white', Math.abs(bg - 15) < 3, `got ${bg}`)
}

// --- an uncuttable mess (no gutters at all) → null -----------------------------------------
{
    // Noise everywhere: content on every row and column.
    const w = 200, h = 200
    const img = page(w, h, [])
    for (let i = 0; i < w * h; i++) {
        if ((i * 2654435761) % 7 < 3) {   // deterministic scatter
            img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = 10
        }
    }
    const out = detectPanels(img)
    check('textured page with no gutters shows whole', out === null, `got ${out?.length}`)
}

// --- orderPanels: staggered rows cluster by overlap ---------------------------------------
{
    const rects = [
        { x: 200, y: 10, w: 90, h: 100 },   // TR
        { x: 10, y: 20, w: 90, h: 100 },    // TL (slightly lower but same row)
        { x: 10, y: 200, w: 280, h: 100 },  // bottom strip
    ]
    const out = orderPanels(rects)
    check('staggered top row clusters as one row, LTR', out[0].x === 10 && out[1].x === 200 && out[2].y === 200,
        JSON.stringify(out))
}

// --- too many blocks (>12) → null ----------------------------------------------------------
{
    const rects = []
    for (let ry = 0; ry < 4; ry++) for (let rx = 0; rx < 4; rx++)
        rects.push({ x: 8 + rx * 98, y: 8 + ry * 140, w: 80, h: 120 })
    const out = detectPanels(page(400, 570, rects))
    check('16 blocks falls back to whole page', out === null, `got ${out?.length}`)
}

console.log(failures ? `\n${failures} FAILURE(S)` : '\nall good')
process.exit(failures ? 1 : 0)


// ── THE COVERAGE GUARANTEE (field, 08-30: a 4-of-5 detection must never walk silently) ──
import { coverageAudit } from '../app/src/main/assets/foliate/comic-panels.js'
{
    const W = 200, H = 300
    const mask = new Uint8Array(W * H)
    const paint = (x, y, w, h) => { for (let j = y; j < y + h; j++) mask.fill(1, j * W + x, j * W + x + w) }
    // five equal-ish blocks: 2x2 grid + one wide bottom panel
    const blocks = [
        { x: 10, y: 10, w: 85, h: 85 }, { x: 105, y: 10, w: 85, h: 85 },
        { x: 10, y: 105, w: 85, h: 85 }, { x: 105, y: 105, w: 85, h: 85 },
        { x: 10, y: 200, w: 180, h: 90 },
    ]
    blocks.forEach(b => paint(b.x, b.y, b.w, b.h))
    const t = (name, cond) => { if (!cond) { console.error('COVERAGE FAIL: ' + name); process.exit(1) } }
    // all five given → ok
    t('full set is ok', coverageAudit(mask, W, H, blocks).action === 'ok')
    // 4 of 5 (missing the big bottom panel ≈31% of content) → below rescue → whole
    t('missing a third of the art falls back whole',
        coverageAudit(mask, W, H, blocks.slice(0, 4)).action === 'whole')
    // 4 of 5 smaller miss: drop one grid cell (~20% of content) → rest step boxing it
    {
        const given = [blocks[0], blocks[1], blocks[2], blocks[4]]
        const a = coverageAudit(mask, W, H, given)
        t('one missed cell earns a rest step', a.action === 'rest')
        t('the rest step boxes the missed cell',
            a.rect && Math.abs(a.rect.x - blocks[3].x) <= 2 && Math.abs(a.rect.y - blocks[3].y) <= 2 &&
            Math.abs(a.rect.w - blocks[3].w) <= 2 && Math.abs(a.rect.h - blocks[3].h) <= 2)
    }
    // speck dust outside panels (< REST_MIN_AREA) must not conjure a step
    {
        const dusty = new Uint8Array(mask)
        for (let k = 0; k < 40; k++) dusty[(2 + (k % 5)) * W + 2 + k] = 1
        t('specks stay ok', coverageAudit(dusty, W, H, blocks).action === 'ok')
    }
    console.log('COVERAGE GUARANTEE: all good')
}
