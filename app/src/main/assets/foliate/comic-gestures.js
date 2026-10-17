// COMIC GESTURES — the pure decisions behind the comic touch grammar.
//
// Michael, 2026-10-11: "I like a single finger slide in a comic." The touch WIRING lives in
// reader-embed.js (attachComicTouch / attachOuterComicPan, between the COMIC-GESTURES sentinels);
// every DECISION that wiring makes — which zone a tap is in, whether a drag is a swipe, a pan or
// a scroll, when a second finger is really a pinch, where a zoomed tap slides the view — is a pure
// function here, so it runs in node against plain numbers (scripts/test-comic-gestures.mjs)
// without a WebView, and so the Boox and the iPad decide exactly alike.
//
// THIS FILE IS MEANT TO BE A TWIN — byte-identical in toolsboox-correspondence
// (app/src/main/assets/foliate/) and ledger-ipad-correspondence (Resources-foliate-js/). The iPad
// adopted Android's copy in its slice-1 review pass (2026-10-11) and decides every comic gesture
// with it, carrying three review fixes the Android copy has to take back for the two to match
// again: TAP_MS 450 (no touch between a tap and a hold does nothing), classifyDrag's axis test
// at SWIPE_AXIS (a diagonal start used to classify as a swipe that swipeSide refused — a dead
// gesture) with a sideways-only scroller still scrolling, and swipeSide's `ms` (the old quick
// drag, QUICK_FRAC inside QUICK_MS, still turns). This header is the only other difference.
// The iPad's reader-embed.js WIRING is still its own (no overscroll turn, no tap-to-pan, its own
// back-edge strip, stale touches caught by pointer id and isPrimary), so
// scripts/check-comic-gestures-twin.sh reports both until they meet; the iPad's
// scripts/test-comic-gestures.mjs pins this file and reports its drift from the Android copy.
//
// Ideas adopted (no code copied; all Apache-2.0):
//  • Subsampling Scale Image View (davemorrissey; mihonapp's fork) — a new first finger resets
//    every gesture flag; a pan that has started never hands off mid-gesture.
//  • DirectionalViewPager (tachiyomiorg's fork of the androidx ViewPager) — a page turns on
//    DISTANCE or on SPEED: 25 dp travelled at 400 dp/s is a fling, however short the drag.
//  • Mihon's pager — a tap at the edge of a zoomed page pans by one window before it turns
//    (panStep; PARKED until Michael says yes — today a side tap always turns, as it always did).
//
// UNITS: every length here is SCREEN px (the top window's CSS px — a dp on Android, a point on
// iOS). The comic iframe is CSS-scaled, so callers convert page px to screen px first
// (screen = page × frame scale); nothing in here knows about page px.

export const G = Object.freeze({
    SLOP: 12,            // a move past this is a drag (the outer pan's field-proven number)
    MOVED: 4,            // "this finger has moved at all" — the pinch commit's both-fingers test
    HOLD_MS: 450,        // a still hold arms the capture menu / the hold-then-drag box
    TAP_MS: 450,         // this long or longer and it was not a tap — the hold's own 450, so no
                         // touch falls between the two and does nothing (iPad review, 2026-10-11:
                         // a 400–449 ms edge tap used to turn through the browser's click)
    DOUBLE_MS: 450,      // e-ink taps are slow; 350 missed real doubles (field)
    STALE_MS: 1000,      // a pointer silent this long is a lift the digitiser swallowed
    HOLD_LIVE_MS: 10000, // …except an ARMED still hold, live this long (a palm can't steal it)
    ZONE_FRAC: 0.28,     // the outer 28% each side turns the page (the iPad's thirds, -05)
    CORNER_BAND: 120,    // 'corners': the bottom band only (Android's native 120dp patch)
    SWIPE_AXIS: 1.5,     // a swipe is mostly horizontal: |dx| ≥ 1.5·|dy|
    SWIPE_FAR: 0.5,      // …and either travels half the width…
    FLING_MIN: 25,       // …or travels 25 px…
    FLING_SPEED: 0.4,    // …at 0.4 px/ms (400 dp/s) in the same direction
    FLING_WINDOW: 100,   // speed is measured over the last 100 ms only
    QUICK_FRAC: 0.16,    // …or the old quick drag, 16% of the width…
    QUICK_MS: 800,       // …inside 800 ms — kept so nothing that turned before stops turning
    OVERSCROLL: 0.25,    // dragging a quarter-screen past the end of a tall page turns it
    PINCH_MIN: 24,       // a second finger becomes a pinch only when the spread changes this much…
    PINCH_FRAC: 0.08,    // …or 8% of the starting spread, whichever is larger
    ROOM: 2,             // scroll room under this is rounding, not room
})

/** Which zone a tap at screen (sx, sy) lands in, in a window w×h, under the reader's tap-zone
 *  setting: 'sides' (full-height outer strips), 'corners' (only the bottom band), 'off'. */
export const tapZone = (sx, sy, w, h, style = 'sides') => {
    if (style === 'off') return 'center'
    if (style === 'corners' && sy < h - G.CORNER_BAND) return 'center'
    if (sx < w * G.ZONE_FRAC) return 'left'
    if (sx > w * (1 - G.ZONE_FRAC)) return 'right'
    return 'center'
}

/**
 * What a one-finger drag IS, decided once, at the first move past slop, never changed after.
 * "Across" is the swipe's own axis test (|dx| ≥ 1.5·|dy|): a diagonal start on a tall page used
 * to classify as a swipe that swipeSide then refused, so the whole gesture did nothing (iPad
 * review, 2026-10-11). Now it scrolls, or pans, whichever the page has room for.
 *   dx, dy     the move so far (screen px)
 *   paginated  false in the scrolled (webtoon) flow — there a drag only ever scrolls or pans
 *   zoom       'crop' (a drawn/double-tap/pinch box or a walk crop) · 'spread' (the outer
 *              numeric zoom) · 'none' (the page at fit)
 *   crop       {x, maxX} of the crop window in image px (zoom === 'crop')
 *   scroller   {x, maxX, y, maxY} of the host scroller in screen px
 * → 'pan' (slide the crop) · 'scroll' (scroll the host) · 'swipe' (a page turn on lift)
 *
 * A finger moving LEFT asks for what lies to the RIGHT. A zoomed view that is already against
 * that edge has nothing left to show, so the drag becomes a swipe; anything else pans.
 */
export const classifyDrag = ({ dx, dy, paginated, zoom, crop, scroller }) => {
    const across = Math.abs(dx) >= Math.abs(dy) * G.SWIPE_AXIS
    const wantsRight = dx < 0
    if (zoom === 'crop') {
        if (!paginated || !across || !crop) return 'pan'
        const atEdge = wantsRight ? crop.x >= crop.maxX - 0.5 : crop.x <= 0.5
        return atEdge ? 'swipe' : 'pan'
    }
    if (!paginated) return 'scroll'
    const s = scroller || { x: 0, maxX: 0, y: 0, maxY: 0 }
    if (across) {
        if (!(s.maxX > G.ROOM)) return 'swipe'
        const atEdge = wantsRight ? s.x >= s.maxX - 0.5 : s.x <= 0.5
        return atEdge ? 'swipe' : 'scroll'
    }
    return (s.maxY > G.ROOM || s.maxX > G.ROOM) ? 'scroll' : 'swipe'
}

/**
 * The page turn a lifted swipe stands for, as the SPATIAL side it brings in — 'right' when the
 * finger travelled left (exactly what tapping the right zone means), 'left' the other way, or
 * null when it is not a swipe. The app owns the reading direction: a manga flips the side.
 *   dx, dy   total travel (screen px) · vx  release speed (px/ms, signed) · width  window width
 *   ms       (optional) how long the finger was down — the old quick drag (QUICK_FRAC inside
 *            QUICK_MS) still turns, so a drag that turned before the 10-11 pass still does
 * No total-time limit on the slide: a slow, long drag turns the page as surely as a flick.
 */
export const swipeSide = ({ dx, dy, vx, width, ms }) => {
    const ax = Math.abs(dx)
    if (!(ax > 0) || ax < Math.abs(dy) * G.SWIPE_AXIS) return null
    const far = ax >= width * G.SWIPE_FAR
    const fling = ax >= G.FLING_MIN && Math.abs(vx) >= G.FLING_SPEED && Math.sign(vx) === Math.sign(dx)
    const quick = ms >= 0 && ms < G.QUICK_MS && ax >= width * G.QUICK_FRAC
    if (!far && !fling && !quick) return null
    return dx < 0 ? 'right' : 'left'
}

/** A scroll dragged past the end of a tall page: +1 = past the bottom (forward), -1 = past the
 *  top (back), 0 = keep the page. `over` is the distance past the end (screen px; negative =
 *  past the start), `height` the window height. */
export const overscrollTurn = ({ over, height }) => {
    if (!(height > 0)) return 0
    if (over >= height * G.OVERSCROLL) return 1
    if (-over >= height * G.OVERSCROLL) return -1
    return 0
}

/** A second finger is a palm until BOTH fingers have moved and the spread between them has
 *  changed by max(24 px, 8%). A stale first pointer (a lift the digitiser swallowed) never
 *  moves, so it can never pinch. Distances in screen px. */
export const pinchCommits = ({ d0, d, moved1, moved2 }) =>
    !!(moved1 && moved2) && Math.abs(d - d0) >= Math.max(G.PINCH_MIN, d0 * G.PINCH_FRAC)

/**
 * One tap-to-pan step of a zoomed window over its page: slide one window ACROSS (dirX = +1
 * rightward, -1 leftward); at the end of the row, one window DOWN (dirY = +1) or UP (-1), to the
 * start of the next row; at the end of the page, null — the caller turns the page.
 *   x, y, w, h  the window · W, H  the page · stepX, stepY  how far one step slides
 * Every value in one unit (image px for a crop, screen px for the host scroller).
 */
export const panStep = ({ x, y, w, h, W, H, stepX, stepY }, dirX, dirY) => {
    const maxX = Math.max(0, W - w), maxY = Math.max(0, H - h)
    const clamp = (v, hi) => Math.min(Math.max(0, v), hi)
    const nx = clamp(x + dirX * stepX, maxX)
    if (Math.abs(nx - x) > 0.5) return { x: nx, y }
    const ny = clamp(y + dirY * stepY, maxY)
    if (Math.abs(ny - y) > 0.5) return { x: dirX > 0 ? 0 : maxX, y: ny }
    return null
}

/** Release speed from the last FLING_WINDOW ms of samples. A finger that paused before lifting
 *  has no samples in the window and reads 0 — a pause is not a fling. */
export const makeVelocity = () => {
    const pts = []
    return {
        reset() { pts.length = 0 },
        add(t, x) {
            pts.push({ t, x })
            while (pts.length > 2 && pts[0].t < t - G.FLING_WINDOW * 2) pts.shift()
        },
        vx(now) {
            const w = pts.filter(p => p.t >= now - G.FLING_WINDOW)
            if (w.length < 2) return 0
            const a = w[0], b = w[w.length - 1]
            const dt = b.t - a.t
            return dt >= 1 ? (b.x - a.x) / dt : 0
        },
    }
}
