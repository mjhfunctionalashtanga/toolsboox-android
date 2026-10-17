#!/usr/bin/env python3
"""THE PANEL FACES (ic_pf_*) — one outline family for every tuck panel seat (review 2026-10-11, F5;
Michael 2026-10-11: "Icons lack design language"). Every face is drawn on the ink tools' own
geometry (Pen, Eraser, Lasso, Undo, Redo, Side, ⛶, Delete): the 256 grid and 16-unit round-capped
strokes (= 1.5 on a 24 grid), outline at rest, a filled face only for an ON state (Star).

  scripts/panel-faces.py [repo] [--write]

Writes app/src/main/res/drawable/ic_pf_*.xml with --write, and always build/panel-faces-sheet.svg —
a contact sheet with the ink faces beside the new ones (open it in any browser or Quick Look) — so a
new face is drawn here, checked on the sheet, and stays in the family. PanelSeatsTest 8c holds it."""
import math, os, re, sys

WT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('--') else '.'
OUT = os.path.join(WT, 'app/src/main/res/drawable')
SHEET = os.path.join(WT, 'build')
os.makedirs(SHEET, exist_ok=True)

def circle(cx, cy, r):
    return f"M{cx-r},{cy}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z"

def rrect(x, y, w, h, r):
    return (f"M{x+r},{y}H{x+w-r}a{r},{r} 0 0,1 {r},{r}V{y+h-r}a{r},{r} 0 0,1 {-r},{r}"
            f"H{x+r}a{r},{r} 0 0,1 {-r},{-r}V{y+r}a{r},{r} 0 0,1 {r},{-r}Z")

def star(cx, cy, ro, ri):
    pts = []
    for i in range(10):
        a = math.radians(-90 + i * 36)
        r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return "M" + "L".join(f"{x:.1f},{y:.1f}" for x, y in pts) + "Z"

def dot(x, y, r=10):
    # A filled dot (no zero-length strokes: every renderer agrees on a filled circle).
    return circle(x, y, r)

# name -> (comment, [ (pathData, mode) ]) ; mode 's' stroke 16, 'f' fill, 'sf' fill+stroke, ('s', w) custom
F = {}
def face(name, comment, *paths):
    F[name] = (comment, list(paths))

S = 's'
face('hub', 'Hub: the Ledger hub — three lines (iOS line.3.horizontal).',
     ("M40,64H216M40,128H216M40,192H216", S))
face('today', 'Today: a sun (iOS sun.max).',
     (circle(128, 128, 44), S),
     ("M128,32V56M128,200V224M32,128H56M200,128H224M60,60L77,77M196,60L179,77M60,196L77,179M196,196L179,179", S))
face('more', 'More: the wrench — settings and rare verbs (iOS wrench).',
     ("M226.8,69A8,8 0 0,0 214,66.1L173.6,103.3L156.4,99.6L152.7,82.4L189.9,42.1A8,8 0 0,0 187,29.2A72,72 0 0,0 94,124.9L33.8,177A32,32 0 0,0 79,222.2L131.1,162A72,72 0 0,0 226.8,69Z", S))
face('feeds', 'Feeds: the door to your reading — the feed mark (iOS RSSGlyph).',
     ("M56,56A144,144 0 0,1 200,200M56,120A80,80 0 0,1 136,200", S),
     (dot(68, 188, 14), 'f'))
face('nib', "Nib: back to your note — a pencil tip, the floating notes button's own pencil and iOS's pencil.tip.",
     ("M48,48H208L128,224Z", S),
     ("M81,120L97,138L112,120L128,138L144,120L159,138L175,120", S),
     ("M108,180H148L128,220Z", 'sf'))
face('up', 'Page ↑ / Up (iOS chevron.up).', ("M56,164L128,92L200,164", S))
face('down', 'Page ↓ / Down (iOS chevron.down).', ("M56,92L128,164L200,92", S))
face('left', 'Page ‹ (iOS chevron.left).', ("M164,56L92,128L164,200", S))
face('right', 'Page › (iOS chevron.right).', ("M92,56L164,128L92,200", S))
face('previous', 'Previous article or letter (iOS chevron.left.2).', ("M128,56L56,128L128,200M200,56L128,128L200,200", S))
face('next', 'Next article or letter (iOS chevron.right.2).', ("M56,56L128,128L56,200M128,56L200,128L128,200", S))
face('refresh', 'Refresh: fetch again (iOS arrow.clockwise).',
     ("M184,104H232V56M188.4,192A88,88 0 1,1 190.2,65.8L232,104", S))
face('book', 'Book: this book — contents, marks, search (iOS book).',
     ("M48,208a24,24 0 0,1 24,-24H208V32H72A24,24 0 0,0 48,56Z", S),
     ("M48,208v16H192", S))
face('book_notes', "Notes: this book's notes — a bulleted list in a box (iOS list.bullet.rectangle).",
     (rrect(32, 40, 192, 176, 12), S),
     ("M112,96H184M112,128H184M112,160H184", S),
     (dot(76, 96) + dot(76, 128) + dot(76, 160), 'f'))
face('books', 'Folders: books standing on a shelf (iOS books.vertical).',
     ("M40,40H84V216H40ZM84,72H128V216H84Z", S),
     ("M40,80H84M40,176H84M84,108H128M84,180H128", S),
     ("M148,76L186,66L226,204L188,214Z", S))
face('address', 'Address: where to — the globe (iOS globe).',
     (circle(128, 128, 96), S),
     ("M88,128a40,96 0 1,0 80,0a40,96 0 1,0 -80,0Z", S),
     ("M37.5,96H218.5M37.5,160H218.5", S))
face('piles', 'Piles: marks and piles — two stacked squares (iOS square.stack).',
     (rrect(40, 80, 136, 136, 10), S),
     ("M80,40H206a10,10 0 0,1 10,10V176", S))
face('search', 'Search a collection — the magnifier (iOS magnifyingglass).',
     (circle(112, 112, 72), S), ("M163,163L216,216", S))
face('show', "Show: the view's state — an eye (iOS eye).",
     ("M128,56C56,56 24,128 24,128S56,200 128,200 232,128 232,128 200,56 128,56Z", S),
     (circle(128, 128, 36), S))
face('add', 'Add / New (iOS plus).', ("M40,128H216M128,40V216", S))
face('continue', 'Continue: where your hand left off — play and pause (iOS playpause).',
     ("M40,64V192L120,128Z", S), ("M164,64V192M212,64V192", S))
face('star', 'Star: All Stars (iOS star); filled = starred.', (star(128, 134, 104, 44), S))
face('starred', 'Star, on: this is starred (iOS star.fill).', (star(128, 134, 104, 44), 'sf'))
face('flag', 'Bookmark: remember this page of a book or site — a flag (iOS flag).',
     ("M48,224V48", S),
     ("M48,168C104,120 152,216 208,168V48C152,96 104,0 48,48", S))
face('note', 'Note: a note on this — a page with a turned corner (iOS note.text).',
     ("M152,216H56a16,16 0 0,1 -16,-16V56a16,16 0 0,1 16,-16H200a16,16 0 0,1 16,16V152ZM152,216V152H216", S),
     ("M88,96H168M88,128H136", S))
face('find', 'Find inside this piece — lines under a magnifier (iOS text.magnifyingglass).',
     ("M32,64H192M32,104H120M32,144H88M32,184H88", S),
     (circle(164, 160, 40), S), ("M193,189L224,220", S))
face('reader', 'Reader view: the plain page (the article Reader glyph).',
     (rrect(48, 32, 160, 192, 16), S), ("M88,88H168M88,128H168M88,168H136", S))
face('type', 'Type: text size — a large and a small T (iOS textformat.size).',
     ("M96,56H224M160,56V208M32,120H120M76,120V208", S))
face('listen', 'Listen: read aloud and listen along — the speaker (iOS speaker.wave.2).',
     ("M80,168H32a8,8 0 0,1 -8,-8V96a8,8 0 0,1 8,-8H80L144,40V216Z", S),
     ("M176,100A40,40 0 0,1 176,156M200,76A72,72 0 0,1 200,180", S))
face('mark', 'Mark: highlight a passage — an underlined U (iOS underline).',
     ("M72,40V112a56,56 0 0,0 112,0V40M48,216H208", S))
face('annotate', 'Annotate: ink on this piece — a chisel-tip highlighter (iOS highlighter).',
     ("M148,44L212,108L124,196L60,132Z", S),
     ("M60,132L44,184L72,212L124,196", S),
     ("M32,224H96", S))
face('later', 'Later: the Later list — the ribbon (iOS bookmark).',
     ("M192,224L128,184L64,224V48a8,8 0 0,1 8,-8H184a8,8 0 0,1 8,8Z", S))
face('reply', 'Reply: answer this letter (iOS arrowshape.turn.up.left).',
     ("M80,152L32,104L80,56M224,200A96,96 0 0,0 128,104H32", S))
face('tag', 'Tag: a label (iOS tag).',
     ("M40,48a8,8 0 0,1 8,-8H128L224,136L136,224L40,128Z", S), (dot(88, 88, 12), 'f'))
face('link', 'Links: a chain link (iOS link).',
     ("M122,70L146,46a40,40 0 0,1 57,57L179,127M134,186L110,210a40,40 0 0,1 -57,-57L77,129M104,152L152,104", S))
# Player
def arrow_ccw():
    return "M72,104H24V56M67.6,192A88,88 0 1,0 65.8,65.8L24,104"
def three(x, y, h):
    # a stroked "3": two right-facing bowls
    r = h / 4
    return f"M{x},{y+r*0.4}A{r},{r} 0 1,1 {x+r},{y+2*r}A{r},{r} 0 1,1 {x},{y+4*r-r*0.4}"
def zero(x, y, w, h):
    rx = w / 2
    return f"M{x},{y+rx}V{y+h-rx}a{rx},{rx} 0 0,0 {w},0V{y+rx}a{rx},{rx} 0 0,0 {-w},0Z"
digits = three(98, 100, 64) + zero(140, 100, 28, 64)
face('back30', 'Back 30: skip back thirty seconds (iOS gobackward.30).',
     (arrow_ccw(), S), (digits, ('s', 12)))
face('fwd30', 'Fwd 30: skip on thirty seconds (iOS goforward.30).',
     ("M184,104H232V56M188.4,192A88,88 0 1,1 190.2,65.8L232,104", S),
     (three(92, 100, 64) + zero(134, 100, 28, 64), ('s', 12)))
face('play', 'Play (iOS play).', ("M72,40V216L208,128Z", S))
face('pause', 'Pause (iOS pause).', (rrect(56, 40, 48, 176, 8) + rrect(152, 40, 48, 176, 8), S))
face('stop', 'Stop (iOS stop).', (rrect(48, 48, 160, 160, 16), S))
face('moon', 'Sleep: the sleep timer — a crescent moon (iOS moon).',
     ("M216.7,152.6A92,92 0 0,1 103.4,39.3A92,92 0 1,0 216.7,152.6Z", S))
face('speed', "Speed: the player's pace — a gauge (iOS gauge).",
     ("M40,188A96,96 0 1,1 216,188", S), ("M128,168L172,104", S), (dot(128, 168, 14), 'f'))
face('star_ep', "Star ep: star the moment in what is playing — a star in a ring (never the reading Star's face).",
     (circle(128, 128, 100), S), (star(128, 134, 56, 24), S))
face('note_ep', "Note ep: a note on the moment in what is playing — a note in a ring (never the reading Note's face).",
     (circle(128, 128, 100), S),
     ("M140,176H92a8,8 0 0,1 -8,-8V88a8,8 0 0,1 8,-8H164a8,8 0 0,1 8,8V144ZM140,176V144H172", S))

# The Zoom seat wears its last choice (Michael 2026-10-12: "We can make the zoom button the last used
# option for one click zoom in / out adjustments. Or zoom to box :)"). Zoom box: a marquee's corners
# and a lens (iOS viewfinder, the box's corners — never ⛶'s four brackets, which Android's Full
# screen already wears); its lens lost its plus, which is Zoom +'s now. ＋ and − are Search's
# magnifier carrying the sign (iOS plus.magnifyingglass / minus.magnifyingglass).
face('zoom', "Zoom box: zoom to a box — a marquee's corners and a lens (iOS viewfinder). Michael 2026-10-12.",
     ("M32,72V32H72M96,32H112M136,32H176V72M32,96V112M32,136V176H72", S),
     (circle(160, 160, 48), S),
     ("M194,194L224,224", S))
face('zoom_in', "Zoom +: one step in, 10% more — the magnifier with a plus (iOS plus.magnifyingglass). Michael 2026-10-12.",
     (circle(112, 112, 72), S), ("M163,163L216,216", S), ("M80,112H144M112,80V144", S))
face('zoom_out', "Zoom −: one step out, 10% less — the magnifier with a minus (iOS minus.magnifyingglass). Michael 2026-10-12.",
     (circle(112, 112, 72), S), ("M163,163L216,216", S), ("M80,112H144", S))

face('sections', "Sections: the day's pages (iOS folder).",
     ("M216,208H40a8,8 0 0,1 -8,-8V56a8,8 0 0,1 8,-8H93.3a8,8 0 0,1 4.8,1.6L128,72H216a8,8 0 0,1 8,8V200a8,8 0 0,1 -8,8Z", S))
face('mail', "Mail: The Mail's drawer — the in-tray (iOS tray), never Compose's envelope.",
     (rrect(32, 40, 192, 176, 12), S), ("M32,152H80L100,180H156L176,152H224", S))
face('pictures', 'Pictures: show the letter\'s pictures (iOS photo).',
     (rrect(32, 48, 192, 160, 12), S), ("M32,176L88,120L152,184M136,168L168,136L224,192", S),
     (dot(168, 96, 14), 'f'))

def vector_xml(name, comment, paths):
    body = []
    for d, mode in paths:
        w = 16
        if isinstance(mode, tuple):
            mode, w = mode
        if mode == 's':
            body.append(f'    <path android:pathData="{d}"\n        android:strokeColor="#FF000000" android:strokeWidth="{w}"\n        android:strokeLineCap="round" android:strokeLineJoin="round"\n        android:fillColor="#00000000"/>')
        elif mode == 'sf':
            body.append(f'    <path android:pathData="{d}"\n        android:fillColor="#FF000000" android:strokeColor="#FF000000" android:strokeWidth="{w}"\n        android:strokeLineCap="round" android:strokeLineJoin="round"/>')
        else:
            body.append(f'    <path android:pathData="{d}" android:fillColor="#FF000000"/>')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment}\n     One panel face family (review 2026-10-11, F5; Michael: "Icons lack design language"): the ink\n'
            '     tools\' geometry — 256 grid, 16-unit round strokes, outline at rest; a filled face only for an\n'
            '     ON state (Star). -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="256" android:viewportHeight="256"\n'
            '    android:tint="#000000">\n' + "\n".join(body) + '\n</vector>\n')

def svg_paths(paths):
    out = []
    for d, mode in paths:
        w = 16
        if isinstance(mode, tuple):
            mode, w = mode
        if mode == 's':
            out.append(f'<path d="{d}" fill="none" stroke="#000000" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>')
        elif mode == 'sf':
            out.append(f'<path d="{d}" fill="#000000" stroke="#000000" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            out.append(f'<path d="{d}" fill="#000000"/>')
    return "".join(out)

if '--write' in sys.argv:
    for name, (c, paths) in F.items():
        open(os.path.join(OUT, f'ic_pf_{name}.xml'), 'w').write(vector_xml(name, c, paths))

# Existing Phosphor ink faces, read from their vector XML (fill paths in 256 grid).
ink = ['ic_toolbar_pen', 'ic_toolbar_eraser', 'ic_toolbar_lasso', 'ic_toolbar_undo', 'ic_toolbar_redo',
       'ic_toolbar_switch_side', 'ic_fullscreen', 'ic_toolbar_trash']
cells = [(n, svg_paths(F[n][1])) for n in F]
for n in ink:
    x = open(os.path.join(OUT, n + '.xml')).read()
    ds = re.findall(r'android:pathData="([^"]+)"', x)
    cells.append((n.replace('ic_toolbar_', 'ink:').replace('ic_', 'ink:'), "".join(f'<path d="{d}" fill="#000000"/>' for d in ds)))
cols = 8
size = 96
pad = 24
lab = 18
rows = (len(cells) + cols - 1) // cols
W = cols * (size + pad) + pad
H = rows * (size + pad + lab) + pad
parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><rect width="{W}" height="{H}" fill="#fff"/>']
for i, (n, p) in enumerate(cells):
    cx = pad + (i % cols) * (size + pad)
    cy = pad + (i // cols) * (size + pad + lab)
    parts.append(f'<g transform="translate({cx},{cy}) scale({size/256})">{p}</g>')
    parts.append(f'<text x="{cx + size/2}" y="{cy + size + 14}" font-size="12" text-anchor="middle" font-family="Arial">{n}</text>')
parts.append('</svg>')
open(os.path.join(SHEET, 'panel-faces-sheet.svg'), 'w').write("".join(parts))
print(len(F), 'faces')
