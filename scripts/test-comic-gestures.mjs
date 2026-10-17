// Tests for the comic gesture decisions — plain numbers, no DOM, no book.
//   node scripts/test-comic-gestures.mjs
// Pins the 2026-10-11 gesture pass (Michael: "I like a single finger slide in a comic"): the tap
// zones answer the reader's setting, a drag is classified once (pan · scroll · swipe), a swipe
// turns on distance OR speed with no time limit, a second finger pinches only when it really
// pinches, and a zoomed tap slides one window → one row → the page turn. The iPad runs the same
// file (scripts/check-comic-gestures-twin.sh), so these vectors pin both apps; the last block is
// the iPad's 10-11 review fixes, taken back word for word (its suite carries the same vectors).
import { G, tapZone, classifyDrag, swipeSide, overscrollTurn, pinchCommits, panStep, makeVelocity }
    from '../app/src/main/assets/foliate/comic-gestures.js'

let failures = 0
const check = (name, cond, extra = '') => {
    if (cond) console.log(`  ok  ${name}`)
    else { failures++; console.error(`FAIL  ${name}  ${extra}`) }
}

// ---- tap zones: a 1000×800 window --------------------------------------------------------
check('sides: left strip', tapZone(100, 400, 1000, 800, 'sides') === 'left')
check('sides: right strip', tapZone(900, 50, 1000, 800, 'sides') === 'right')
check('sides: centre', tapZone(500, 400, 1000, 800, 'sides') === 'center')
check('sides: 28% boundary is centre', tapZone(281, 400, 1000, 800, 'sides') === 'center')
check('corners: top-left is centre', tapZone(100, 100, 1000, 800, 'corners') === 'center')
check('corners: bottom-left turns', tapZone(100, 700, 1000, 800, 'corners') === 'left')
check('corners: bottom-centre is centre', tapZone(500, 790, 1000, 800, 'corners') === 'center')
check('off: an edge tap is centre', tapZone(10, 790, 1000, 800, 'off') === 'center')
check('no setting pushed = sides (the iPad today)', tapZone(10, 10, 1000, 800) === 'left')

// ---- classifying a drag ------------------------------------------------------------------
const fit = { x: 0, maxX: 0, y: 0, maxY: 0 }
check('fit, nothing to scroll: horizontal drag swipes',
    classifyDrag({ dx: -40, dy: 5, paginated: true, zoom: 'none', scroller: fit }) === 'swipe')
const tall = { x: 0, maxX: 0, y: 300, maxY: 1200 }
check('fit-width tall page: vertical drag scrolls',
    classifyDrag({ dx: 3, dy: -40, paginated: true, zoom: 'none', scroller: tall }) === 'scroll')
check('fit-width tall page: horizontal drag swipes (no sideways room)',
    classifyDrag({ dx: -40, dy: 3, paginated: true, zoom: 'none', scroller: tall }) === 'swipe')
const crop = { x: 200, maxX: 600 }
check('zoomed crop mid-page: drag pans',
    classifyDrag({ dx: -40, dy: 0, paginated: true, zoom: 'crop', crop }) === 'pan')
check('zoomed crop at the right edge: leftward drag hands off to a swipe',
    classifyDrag({ dx: -40, dy: 0, paginated: true, zoom: 'crop', crop: { x: 600, maxX: 600 } }) === 'swipe')
check('zoomed crop at the right edge: rightward drag still pans back',
    classifyDrag({ dx: 40, dy: 0, paginated: true, zoom: 'crop', crop: { x: 600, maxX: 600 } }) === 'pan')
check('zoomed crop at the left edge: vertical drag pans (never a swipe)',
    classifyDrag({ dx: 2, dy: 40, paginated: true, zoom: 'crop', crop: { x: 0, maxX: 600 } }) === 'pan')
const spread = { x: 500, maxX: 900, y: 0, maxY: 400 }
check('spread zoom mid-scroll: horizontal drag scrolls',
    classifyDrag({ dx: -40, dy: 0, paginated: true, zoom: 'spread', scroller: spread }) === 'scroll')
check('spread zoom at the right end: leftward drag swipes',
    classifyDrag({ dx: -40, dy: 0, paginated: true, zoom: 'spread', scroller: { ...spread, x: 900 } }) === 'swipe')
check('scrolled flow never swipes',
    classifyDrag({ dx: -80, dy: 0, paginated: false, zoom: 'none', scroller: fit }) === 'scroll')
check('scrolled flow with a crop pans',
    classifyDrag({ dx: -80, dy: 0, paginated: false, zoom: 'crop', crop }) === 'pan')

// ---- the swipe verdict: width 1000 ---------------------------------------------------------
check('half the width, slowly: turns (no time limit)',
    swipeSide({ dx: -520, dy: 40, vx: -0.05, width: 1000 }) === 'right')
check('short and fast: a fling turns',
    swipeSide({ dx: 60, dy: 5, vx: 0.9, width: 1000 }) === 'left')
check('short and slow: nothing',
    swipeSide({ dx: 60, dy: 5, vx: 0.1, width: 1000 }) === null)
check('fast but reversing at release: nothing',
    swipeSide({ dx: -60, dy: 5, vx: 0.9, width: 1000 }) === null)
check('under 25px, however fast: nothing',
    swipeSide({ dx: -20, dy: 0, vx: -3, width: 1000 }) === null)
check('too diagonal: nothing',
    swipeSide({ dx: -600, dy: 500, vx: -2, width: 1000 }) === null)
check('finger moved left = the right-hand page (the app flips a manga)',
    swipeSide({ dx: -600, dy: 0, vx: 0, width: 1000 }) === 'right')

// ---- overscroll: height 800, a quarter = 200 ---------------------------------------------
check('250 past the bottom turns forward', overscrollTurn({ over: 250, height: 800 }) === 1)
check('250 past the top turns back', overscrollTurn({ over: -250, height: 800 }) === -1)
check('150 past the bottom keeps the page', overscrollTurn({ over: 150, height: 800 }) === 0)

// ---- pinch commit ------------------------------------------------------------------------
check('both moved, spread +30 on 200: pinch', pinchCommits({ d0: 200, d: 230, moved1: true, moved2: true }))
check('both moved, spread +20 on 200: palm still', !pinchCommits({ d0: 200, d: 220, moved1: true, moved2: true }))
check('8% rule on a wide spread: +40 on 600 is not enough',
    !pinchCommits({ d0: 600, d: 640, moved1: true, moved2: true }))
check('8% rule on a wide spread: +50 on 600 commits',
    pinchCommits({ d0: 600, d: 650, moved1: true, moved2: true }))
check('a stale first pointer never pinches', !pinchCommits({ d0: 200, d: 400, moved1: false, moved2: true }))

// ---- tap-to-pan: a 400×300 window over a 1000×900 page ----------------------------------
const win = { x: 0, y: 0, w: 400, h: 300, W: 1000, H: 900, stepX: 400, stepY: 300 }
let st = panStep(win, 1, 1)
check('forward slides one window across', st && st.x === 400 && st.y === 0, JSON.stringify(st))
st = panStep({ ...win, x: 400 }, 1, 1)
check('the last slide clamps to the edge', st && st.x === 600 && st.y === 0, JSON.stringify(st))
st = panStep({ ...win, x: 600 }, 1, 1)
check('row end: next row, back to the left', st && st.x === 0 && st.y === 300, JSON.stringify(st))
check('page end: null (the caller turns)', panStep({ ...win, x: 600, y: 600 }, 1, 1) === null)
st = panStep({ ...win, x: 0, y: 300 }, -1, -1)
check('backward at a row start: previous row, its right end', st && st.x === 600 && st.y === 0, JSON.stringify(st))
check('backward at the page start: null', panStep({ ...win }, -1, -1) === null)
st = panStep({ ...win, x: 600 }, -1, 1)
check('a manga row runs leftward', st && st.x === 200 && st.y === 0, JSON.stringify(st))
st = panStep({ ...win, x: 0 }, -1, 1)
check('a manga row end: next row starts at the right', st && st.x === 600 && st.y === 300, JSON.stringify(st))

// ---- release speed -----------------------------------------------------------------------
const v = makeVelocity()
v.add(0, 0); v.add(40, 10); v.add(80, 40); v.add(120, 80)
check('speed over the last 100ms', Math.abs(v.vx(120) - (80 - 10) / 80) < 1e-9, String(v.vx(120)))
check('a pause before lifting is not a fling', v.vx(400) === 0)

check('constants: the double-tap window stays field-tuned at 450', G.DOUBLE_MS === 450 && G.HOLD_MS === 450)

// ═══ THE iPAD REVIEW FIXES (2026-10-11), taken back so both copies are one file ═══════════════

// A diagonal start on a fit-width tall page used to classify as a swipe that swipeSide then
// refused (|dx| < 1.5·|dy|): no turn and no scroll for the whole gesture. Now it scrolls.
check('diagonal start on a tall page scrolls (was a dead swipe)',
    classifyDrag({ dx: -40, dy: -35, paginated: true, zoom: 'none', scroller: tall }) === 'scroll')
check('…and on a page with nothing to scroll it is still a (refused) swipe — nothing to move',
    classifyDrag({ dx: -40, dy: -35, paginated: true, zoom: 'none', scroller: fit }) === 'swipe')
check('a sideways-only scroller (spread zoom, no vertical room) scrolls a vertical drag',
    classifyDrag({ dx: 2, dy: 40, paginated: true, zoom: 'none', scroller: { x: 100, maxX: 400, y: 0, maxY: 0 } }) === 'scroll')
check('rounding is not room: a host 1px wider than the screen still swipes',
    classifyDrag({ dx: -40, dy: 0, paginated: true, zoom: 'none', scroller: { x: 0, maxX: 1, y: 0, maxY: 0 } }) === 'swipe')
check('a diagonal drag inside a zoom pans (never a swipe)',
    classifyDrag({ dx: -40, dy: -35, paginated: true, zoom: 'crop', crop: { x: 600, maxX: 600 } }) === 'pan')

// The old quick drag (16% of the width inside 800 ms) still turns, so nothing that turned before
// the 10-11 pass stops turning. Both reader-embed.js callers pass `ms` (finger-down time); a
// caller that passes none gets no quick drag.
check('quick drag: 200 of 1000 in 300 ms turns', swipeSide({ dx: -200, dy: 0, vx: -0.1, width: 1000, ms: 300 }) === 'right')
check('quick drag: the same 200 over 900 ms does not', swipeSide({ dx: -200, dy: 0, vx: -0.1, width: 1000, ms: 900 }) === null)
check('quick drag: under 16% never', swipeSide({ dx: -150, dy: 0, vx: -0.1, width: 1000, ms: 200 }) === null)
check('no ms given: the quick drag is off', swipeSide({ dx: -200, dy: 0, vx: -0.1, width: 1000 }) === null)
check('quick drag still answers the axis test', swipeSide({ dx: -200, dy: 180, vx: -0.1, width: 1000, ms: 300 }) === null)

// No touch falls between a tap and a hold: a tap is anything shorter than the hold's 450 ms (a
// 400–449 ms centre tap used to be neither a tap nor a capture).
check('TAP_MS meets HOLD_MS', G.TAP_MS === G.HOLD_MS && G.TAP_MS === 450)

console.log(failures ? `COMIC GESTURES: ${failures} FAILED` : 'COMIC GESTURES: all good')
if (failures) process.exitCode = 1
