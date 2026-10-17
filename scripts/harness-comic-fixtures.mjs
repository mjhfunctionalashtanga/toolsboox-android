// REALISTIC fixture harness for the panel detector — measures hit rates on pages that look
// like real comics, not like unit tests: outlined panels with sparse interior art, borderless
// panels, uneven gutters, scanner noise, dark pages, mild tilt, two-page spreads, splashes.
//
//   node scripts/harness-comic-fixtures.mjs            → scorecard per fixture family
//   node scripts/harness-comic-fixtures.mjs --dump     → also print per-case detail
//
// Scoring: a case PASSES when the detector either (a) finds the expected panel count with every
// found rect overlapping its ground-truth panel by IoU ≥ 0.5 in the expected reading order, or
// (b) for the families whose honest answer is "show whole" (splash, hopeless dark), returns null.
// A WRONG CROP (panels found but boxes/order wrong) is the worst outcome and is counted apart
// from a clean fallback — the fallback IS the feature; a bad crop is the offense.
import { detectPanels } from '../app/src/main/assets/foliate/comic-panels.js'

// Deterministic PRNG — reruns must reproduce (and the repo bans Math.random in workflows).
const mulberry32 = (a) => () => {
    a |= 0; a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}

// --- page painter ---------------------------------------------------------------------------
// Ground + noise; panels as outlined boxes with sparse "art" strokes, or borderless art blobs.
const makePage = (w, h, spec) => {
    const {
        ground = 240, noise = 0, rng = mulberry32(7),
        panels = [], outline = true, outlineInk = 30, artInk = 60,
        artDensity = 0.10, tiltPx = 0, invert = false,
    } = spec
    const data = new Uint8ClampedArray(w * h * 4)
    const put = (x, y, v) => {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        const i = (y * w + x) * 4
        data[i] = data[i + 1] = data[i + 2] = v
        data[i + 3] = 255
    }
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const n = noise ? (rng() - 0.5) * 2 * noise : 0
        put(x, y, Math.max(0, Math.min(255, ground + n)))
    }
    // tilt: shift each row's panel content horizontally by up to tiltPx across the page height
    const rowShift = (y) => tiltPx ? Math.round((y / h) * tiltPx) : 0
    for (const p of panels) {
        if (outline) {
            for (let x = p.x; x < p.x + p.w; x++) {
                put(x + rowShift(p.y), p.y, outlineInk); put(x + rowShift(p.y + 1), p.y + 1, outlineInk)
                put(x + rowShift(p.y + p.h - 1), p.y + p.h - 1, outlineInk)
                put(x + rowShift(p.y + p.h - 2), p.y + p.h - 2, outlineInk)
            }
            for (let y = p.y; y < p.y + p.h; y++) {
                const sh = rowShift(y)
                put(p.x + sh, y, outlineInk); put(p.x + 1 + sh, y, outlineInk)
                put(p.x + p.w - 1 + sh, y, outlineInk); put(p.x + p.w - 2 + sh, y, outlineInk)
            }
        }
        // sparse interior art: short strokes at artDensity coverage
        const strokes = Math.round((p.w * p.h * artDensity) / 24)
        for (let s = 0; s < strokes; s++) {
            const sx = p.x + 3 + Math.floor(rng() * Math.max(1, p.w - 12))
            const sy = p.y + 3 + Math.floor(rng() * Math.max(1, p.h - 6))
            const len = 4 + Math.floor(rng() * 8)
            const horiz = rng() < 0.5
            for (let k = 0; k < len; k++)
                put((horiz ? sx + k : sx) + rowShift(horiz ? sy : sy + k), horiz ? sy : sy + k, artInk)
        }
    }
    if (invert) for (let i = 0; i < w * h; i++) {
        const j = i * 4
        data[j] = 255 - data[j]; data[j + 1] = 255 - data[j + 1]; data[j + 2] = 255 - data[j + 2]
    }
    return { data, width: w, height: h }
}

const iou = (a, b) => {
    const ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x))
    const iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y))
    const inter = ix * iy
    return inter / (a.w * a.h + b.w * b.h - inter)
}

// PASS: every truth panel matched in order by IoU≥0.5. WRONG: non-null but bad. FALLBACK: null.
const judge = (out, truth) => {
    if (!out) return 'fallback'
    if (out.length !== truth.length) return 'wrong'
    for (let i = 0; i < truth.length; i++) if (iou(out[i], truth[i]) < 0.5) return 'wrong'
    return 'pass'
}

// --- fixture families -----------------------------------------------------------------------
const W = 800, H = 1200
const grid = (cols, rows, gx, gy, jitter = 0, rng = mulberry32(3)) => {
    const out = []
    const pw = Math.floor((W - gx * (cols + 1)) / cols)
    const ph = Math.floor((H - gy * (rows + 1)) / rows)
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
        const jx = jitter ? Math.floor((rng() - 0.5) * jitter) : 0
        const jy = jitter ? Math.floor((rng() - 0.5) * jitter) : 0
        out.push({ x: gx + c * (pw + gx) + jx, y: gy + r * (ph + gy) + jy, w: pw, h: ph })
    }
    return out
}

const families = []
const fam = (name, expect, cases) => families.push({ name, expect, cases })

// 1. Clean grids at several gutter widths — the floor the old tests already stood on.
fam('clean-grid', 'panels', [8, 14, 22].flatMap(g =>
    [[2, 2], [2, 3], [3, 3]].map(([c, r]) => {
        const truth = grid(c, r, g * 2, g)
        return { name: `grid${c}x${r}-g${g}`, truth, img: makePage(W, H, { panels: truth, rng: mulberry32(c * r * g) }) }
    })))

// 2. Uneven gutters + jittered panel edges (hand-ruled layouts).
fam('uneven-gutters', 'panels', [0, 1, 2].map(seed => {
    const rng = mulberry32(seed + 11)
    const truth = grid(2, 3, 26, 16, 10, rng)
    return { name: `uneven-${seed}`, truth, img: makePage(W, H, { panels: truth, rng, noise: 4 }) }
}))

// 3. Scanner noise on the gutters (JPEG/paper grain), increasing amplitude.
fam('noisy-scan', 'panels', [6, 12, 18].map(noise => {
    const truth = grid(2, 2, 30, 20)
    return { name: `noise-${noise}`, truth, img: makePage(W, H, { panels: truth, noise, rng: mulberry32(noise) }) }
}))

// 4. Borderless panels — art only, no outlines. The classic XY-cut blind spot when art is sparse.
fam('borderless', 'panels', [0.08, 0.16, 0.28].map(d => {
    const truth = grid(2, 2, 34, 24)
    return { name: `borderless-${d}`, truth, img: makePage(W, H, { panels: truth, outline: false, artDensity: d, rng: mulberry32(Math.round(d * 100)) }) }
}))

// 5. Dark pages — night scene: dark ground, lighter art, gutters match the dark ground.
fam('dark-page', 'panels', [1, 2].map(seed => {
    const truth = grid(2, 2, 30, 20)
    return { name: `dark-${seed}`, truth, img: makePage(W, H, { panels: truth, ground: 25, outlineInk: 210, artInk: 180, noise: 5, rng: mulberry32(seed * 7) }) }
}))

// 6. Mild tilt (scanned at ~0.7°): gutters survive, content shifts a little across the page.
fam('tilted', 'panels', [8, 14].map(t => {
    const truth = grid(2, 3, 28, 18)
    return { name: `tilt-${t}px`, truth, img: makePage(W, H, { panels: truth, tiltPx: t, noise: 3, rng: mulberry32(t) }) }
}))

// 7. Two-page spread: wide image, spine gutter in the middle, 2 panels a side.
fam('spread', 'panels', [1].map(() => {
    const truth = [
        { x: 30, y: 40, w: 340, h: 500 }, { x: 30, y: 580, w: 340, h: 500 },
        { x: 430, y: 40, w: 340, h: 500 }, { x: 430, y: 580, w: 340, h: 500 },
    ]
    // reading order for a spread: left page top→bottom then right page — rows cluster handles it
    const ordered = [truth[0], truth[2], truth[1], truth[3]]
    return { name: 'spread-2x2', truth: ordered, img: makePage(800, 1120, { panels: truth, rng: mulberry32(5) }) }
}))

// 8. Honest-fallback family: splash page (one full-bleed art) and a hopeless dark blob page.
fam('splash-whole', 'null', [
    { name: 'splash', truth: [], img: makePage(W, H, { panels: [{ x: 10, y: 10, w: W - 20, h: H - 20 }], artDensity: 0.5, rng: mulberry32(9) }) },
    { name: 'mush', truth: [], img: makePage(W, H, { panels: [{ x: 0, y: 0, w: W, h: H }], outline: false, artDensity: 0.9, rng: mulberry32(10) }) },
])

// --- run ------------------------------------------------------------------------------------
const dump = process.argv.includes('--dump')
let totPass = 0, totWrong = 0, totFall = 0, totN = 0
console.log('family                 pass  fallback  WRONG   n')
for (const f of families) {
    let pass = 0, wrong = 0, fall = 0
    for (const c of f.cases) {
        const out = detectPanels(c.img)
        const verdict = f.expect === 'null'
            ? (out === null ? 'pass' : 'wrong')
            : judge(out, c.truth)
        if (verdict === 'pass') pass++
        else if (verdict === 'wrong') wrong++
        else fall++
        if (dump) console.log(`   ${verdict.padEnd(8)} ${f.name}/${c.name}  got=${out ? out.length : 'null'} want=${f.expect === 'null' ? 'null' : c.truth.length}`)
    }
    totPass += pass; totWrong += wrong; totFall += fall; totN += f.cases.length
    console.log(`${f.name.padEnd(22)} ${String(pass).padStart(4)}  ${String(fall).padStart(8)}  ${String(wrong).padStart(5)}  ${String(f.cases.length).padStart(3)}`)
}
console.log('—'.repeat(50))
console.log(`TOTAL                  ${String(totPass).padStart(4)}  ${String(totFall).padStart(8)}  ${String(totWrong).padStart(5)}  ${String(totN).padStart(3)}`)
console.log(`hit rate ${(100 * totPass / totN).toFixed(1)}%   wrong-crop rate ${(100 * totWrong / totN).toFixed(1)}%`)
