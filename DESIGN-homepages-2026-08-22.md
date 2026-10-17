# DESIGN — The new homepages (Michael's ruling, 2026-08-22 late)

> "Get rid of All Stars, and replace it with a schedule, notes, tasks, and doodle
> or something similar."

The home set of the Ledger changes. All Stars retires as the landing station; in
its place, four honest surfaces — the things a person actually reaches for:

- **📅 Schedule** — the day's time (the day page's calendar face).
- **✒ Notes** — today's dated note (the Notes IA model; tap = today, hold = the
  family sheet).
- **☑ Tasks** — the day's tasks.
- **🖊 Doodle** — a freeform ink page, no ceremony, dot-grid or blank: the page
  you open just to move the pen.

"Or something similar" — the four names above are the working shape; Michael holds
final cut. A handwritten outline for the new homepages exists a little further
back in his notes — consult it when building if he flips it open or names the
date; his typed sentence governs meanwhile.

## Notes

- All Stars ("intake") follows the Self Executive rule: renders forever where it
  exists, no longer the front door. The intake PIPELINE (share-to-Ledger, link
  filing) is untouched — only the homepage seat changes.
- This composes with the Notes IA three doors and the quick-jump hold — Doodle is
  in spirit a one-tap "new Dots page of today's note".
- Chrome canon applies: no modes, hold-to-act, the four homepages are peers.

## The today page as the living index (Michael, moments later)

> "That way you can see the day's notes and click on them immediately from the
> today page. The daily doodle is right there waiting for me. Then, we can have
> all of the grams placed on Gram Picks and then AI can be used to pick harvest."

Three consequences:

1. **The today page SHOWS the day's notes** — visible, one tap from ink. Not a
   door to a list; the list itself, on the face of the day.
2. **The daily Doodle is a standing page, already waiting** — not a create
   button. Every day mints its doodle the way it mints its dated note; opening
   it is one tap, blank and ready.
3. **Gram Picks becomes the automatic gram shelf, and AI picks the harvest** —
   every gram lands on Gram Picks by default; an AI pass (the lasso→LLM /
   Writer's Desk harvest machinery) selects what's worth harvesting into
   Pickings/posts. The human curates the AI's picks, not the raw pile — the
   surfaces-that-guess-vs-surfaces-you-ask thesis, applied to grams.

## Tasks stay in the hand (Michael, same thread)

> "Make sure that tasks are still able to be hand written and not auto created
> into text."

A handwritten task IS the task. Ink-first: writing a task on the page keeps it as
ink — no automatic OCR conversion into a text task. Recognition serves the hand
(search, surfacing, checking off), it does not replace it; conversion to text
happens only on an explicit ask, never as a side effect of writing.

This is also the source-side half of the slop retirement: the 436-task pile is
92% machine-minted, and the auto-minting (the VPS ledger processor's OCR →
task pass) is the faucet. The Tasks homepage shows handwritten tasks as
themselves alongside typed ones; the processor's auto-mint becomes opt-in
per-capture rather than a blanket pass.

## The memo (Michael, same thread)

> "Like a doodle, a memo that can be OCR'd or whatever into a task or event from
> the field or it just exists as a memo. This way it can have the strokes and the
> OCR. Events and tasks can then be worked on into subtasks etc using the full
> power of tasks.org and the idea of FluentBoards staging dynamics but locally."

**The memo is the capture primitive.** A quick handwritten object — doodle-kin —
that holds BOTH its strokes and its OCR text, always together. Its life:

- **It can just BE a memo.** Forever. That is a complete fate.
- **Or it promotes** — on the ask, from the field — into a **task** or an
  **event**, carrying its ink and its text with it.

**Promoted tasks/events get real machinery, locally:**
- **Tasks.org power** (GPL-3.0 — the July ruling: code lifts with attribution):
  subtasks, RRULE recurrence advanced in place, TitleParser-style natural
  language, the VtodoCache read-modify-write discipline for CalDAV survival.
- **FluentBoards staging dynamics, locally**: stages as the working surface —
  the existing LedgerItem.stage / Boards kanban (todo → doing → done) grows into
  real local staging, not a mirror of a server.

Composition: memo → (stays) | (promotes) task/event → subtasks + stages + recur.
The hand writes; the machine serves; promotion is always an ask, never a guess.

## CORRECTION from Michael's own sketch (08-19 notes, read 08-23)

His hand-drawn home-page layout — rendered from the day-19 strokes, labeled
"Home Page / Today" — is a TWO-COLUMN DASHBOARD, not the four-doors-hub + bottom-band
that shipped in 1.07.07:

- **LEFT column, full height: Schedule.**
- **RIGHT rail, stacked bands: Tasks · Memo · Memo** (Memo appears TWICE — multiple
  standing memo slots on the home page itself).

**This settles the Memo question: Memo is a FIRST-CLASS HOME SURFACE** — visible quick
memo slots on the today page (not merely the hidden capture primitive). A memo slot is
where you dump a strokes+text thought in place; it still promotes to task/event on the
ask (the primitive's behavior), but it LIVES on the home page as a slot.

From the same review pages (validating tonight's work in his words):
- "tasks are being premade in a protocol/process/automation somewhere else" → the
  offer-never-mint faucet (in build now).
- "grams on iOS are gorgeous… want that" → the share-in lanes (shipped 1.07.06).
- NEW direction: "the to-do boards UX could get an overhaul — nice visuals, throwback
  CLASSIC style, embossed / skeuomorphism." → a Boards/Tasks visual redesign, his words.
- "Build Schedule automation in a to-do board — I want to know more about this."

REBUILD PENDING his confirm: the today page as Schedule-left / Tasks+Memo(s)-right,
with real memo slots. 1.07.07's band + four doors stay reachable meanwhile.

## The today-page dashboard — FINAL spec (Michael, 08-23, refining his sketch)

Confirmed rebuild of the today page (the 08-19 sketch, refined). One screen, two columns:

- **LEFT column, full height: SCHEDULE** (the day's time — the existing day/schedule face).
- **RIGHT column, TOP HALF — TASKS + MEMO (one zone):**
  - the task list, AND a **Memo strip = the last ~3 lines**: a handwriting capture area.
    What you write there can be promoted to tasks/events (the memo primitive's ask) OR
    just left as a memo. This IS the "offer-never-mint" surface made visible — the memo
    is where task/event candidates are born by hand, on the home page.
- **RIGHT column, BOTTOM HALF — NOTES + DOODLE:**
  - **Notes** = THREE THUMBNAILS of the three most-recent notes (rendered stroke minis,
    tap to open). (This replaces the sketch's second "Memo".)
  - **Doodle** = the open space BELOW the thumbnails — tap/write to doodle (the standing
    daily doodle page, inline).

So the four homepages (Schedule · Notes · Tasks · Doodle) all live ON the today page as
zones, plus Memo as the task/event capture strip. The hub's four door-rows (1.07.07)
remain as the full-surface entries; this is the dashboard that makes the today page the
living index in earnest.

Build note: CalendarDayFragment-heavy — run SOLO (after the faucet build merges) to
avoid a merge collision. Thumbnails reuse renderPageBitmap(pageKey) + a bounded mini
decode; the memo strip reuses the note-capture + LedgerItem promotion path.

## CORRECTION 2 — I over-built; the truth is ONE schedule page (Michael, 08-23)

Michael, plainly: "It was meant to be on a single schedule page. Idk why you created
four new things in the menu and a doodle dot thingie. These were all supposed to be on
the schedule page."

**What was WRONG in 1.07.07 and must be undone:**
- The four hub "door" rows (Schedule · Notes · Tasks · Doodle) added to LedgerDirectory
  — REVERT. He never wanted new menu items.
- Doodle as a dots-template standing note page — WRONG. Doodle is an inline AREA.

**The actual, only design — ONE Schedule page (notePage == null):**
- **LEFT column, full height: Schedule.**
- **RIGHT column, split EXACTLY 50/50 vertically** — deliberate, so the right column
  lifts out as a WIDGET later:
  - **TOP 50%: Tasks + Memo** (memo strip = handwriting capture → promote to task/event
    or leave as memo).
  - **BOTTOM 50%: the 3 recent-note thumbnails + the Doodle area**, where **Doodle is
    sized to the 6-inch device's LANDSCAPE aspect ratio** (wider-than-tall inline area,
    not a page).

Nothing new in any menu. The whole home experience is this one page. The 50/50 split is
load-bearing (widget parity). Supersedes CORRECTION 1 and the FINAL spec above where
they conflict.

## Top-level page-skip order (Michael, 08-23)

The day surface's skip/pager order at the top level: **Today → Gram Picks → Notes.**
(Today = the Schedule dashboard page; then Gram Picks; then Notes — day, to what you're
collecting, to what you're writing.) This is the ‹ › skip on the Schedule face, distinct
from the dated-note pager that walks a day's note pages. Small wiring change in the day
navigator's page order — queue as a follow-up to the dashboard build (same file region;
don't collide with the in-flight dashboard agent).

## CORRECTION 3 — the dashboard IS the page, drawn in, not laid on top (Michael, 08-23)

Field notes on 1.07.09's dashboard:
- "Tasks is meant to be a place to write by hand" — the Tasks zone is a HANDWRITING
  region, not a rendered row-list. You write tasks in it directly (page strokes);
  promotion reads the region.
- "I do not like the rounded edges" — square corners everywhere on the dashboard.
- "The new parts don't pop in/out properly with the side bar — stay consistent with
  the sizings" — the zones must reflow proportionally through sidebar open/close.
- **"They're meant to be part of the page, not laid on top of the page."** — THE key
  architectural correction: the dashboard is DRAWN INTO the schedule page template
  (part of the page bitmap: dividing lines, zone labels, note-mini bitmaps, doodle
  preview), NOT floating Views. Writing happens on the page surface in the Tasks/Memo
  regions (normal strokes); taps use lightweight page-region hit-rects (the
  pickings-cover idiom), not opaque Views.

This subsumes the other three: drawn rectangles = no rounded drawables; part of the
template = scales with the page through sidebar transitions; regions of the page =
write-by-hand is native. The overlay-View approach (1.07.07 band, 1.07.09 dashboard) is
retired in favor of template-drawn zones. Layout unchanged: Schedule left, right column
50/50 (Tasks+Memo / 3-note-thumbnails + landscape Doodle).

## Tasks: OCR to text-on-lines, many sources, and PROVENANCE navigation (Michael, 08-23)

How tasks work on the today page, fully:

- **Display = OCR'd text on lines.** A handwritten task is OCR'd and shown in TEXT form
  on ruled lines in the Tasks region of the today page (not raw ink rows). The ink is
  recognized; the line shows the text.
- **Creation sources (all become tasks):**
  1. **Handwrite in the Memo section** → task (the memo strip's promote).
  2. **Lasso a stroke on ANY page** → "make this a task" (the lasso→createLedgerItem path).
  3. **Turn a rhizome / atom into a task** — a rhizome node/atom can be promoted to a
     task (a "→ task" verb on the atom/node).
- **PROVENANCE + navigation:** every task remembers the exact PAGE it was created on
  (surface + pageKey + date, ideally the mark position), not just the day. **Tapping the
  task's text on the today page navigates to THAT page** — CalendarNavigator.toDayNote
  (date, pageKey), and where a mark position exists, focusOnRect to the spot. "Click the
  text on the lines → takes you to the page the to-do was created on, not just the day."

Implementation notes: LedgerItem needs a provenance ref (page key + date + optional
rect) recorded at creation for all three sources — additive fields, Moshi-safe. The
today-page Tasks region renders OCR text lines with per-line tap → navigate. OCR reuses
the digital-ink recognition already wired for lasso/memo promotion. Sequences AFTER the
"dashboard drawn into the template" rework lands (that build owns the Tasks region;
this adds OCR-text-lines + provenance nav on top).

## Dashboard punch-list — for ONE careful pass (collected 08-23, after the thrash)

Parked build: 1.07.12 on R2 (dashboard-into-template + 8× zoom; before the weather/
grid-jot/portrait-fast-ink batch). The following were reported in rapid succession and
must be fixed TOGETHER, tested, not one-blind-ship-at-a-time:

1. **Weather + moon**: belong ONLY in the Tasks-bottom strip. (a) the fetch must
   actually show on the B6 (verify ipwho.is/open-meteo reachable on device; if blocked,
   another geolocation or a manual location); (b) there is a STRAY moon "hiding in the
   hamburger" — a second weather/moon render somewhere in the drawer/nav — FIND and
   remove it so the moon shows once, in Tasks. (Weather draw is wired in
   CalendarDayPage.drawTasksWeatherMoon; the stray one is elsewhere — hunt every glyph.)
2. **Scroll regression**: "can't scroll anymore" — LOCATION UNCONFIRMED (schedule page
   pan? a list? the zoomed-in page after 8× zoom?). First step of the pass: reproduce/
   locate. Suspects: the dashboard hit-rects (dashHitAt) consuming drag events, the
   MAX_ZOOM 8× pan-clamp, or exclude-view changes.
3. **ALL of LANDSCAPE is broken (one root)**: doodle won't work rotated · the today
   page WON'T ZOOM when rotated · dashboard squished. The dashboard is laid out for a
   PORTRAIT 1404×1872 canvas; landscape fits that portrait page into a wide surface, so
   zoom/pan/layout all misbehave. Landscape needs its OWN layout AND the fast-ink room.
   This is the DAYLIGHT session — one coherent landscape design pass, not scattered
   patches.
4. Confirm portrait-only fast ink (from .13, currently rolled back) is the right interim
   before re-shipping it.

Discipline for the pass: reproduce each before touching; fix together; ONE build; field-
verify with Michael. No blind rapid iteration (the 08-23 late-night lesson).

## Landscape cluster — the coherent pass (added 08-23, after rapid patches)

All ONE root: the dashboard + strokes are positioned for a PORTRAIT 1404×1872 canvas;
landscape re-projects them wrong. Confirmed by exhaustive grep: there is exactly ONE
moon in the codebase (WeatherMoon, the Tasks strip) — the "moon behind the hamburger"
is that strip MISPLACED in landscape, same as "weather line to the right of tasks."
Do NOT chase these as separate bugs.

Open landscape items (need on-device iteration / the focused pass, NOT blind midnight
patches — the 08-23 lesson, re-learned twice):
1. **Strokes render wrong when rotated** ("the rotation bug also happens on the strokes")
   — coordinate mapping / bake position under the landscape width-fill transform. Verify
   with fast ink OFF (portrait-only gate means landscape = software pen): if strokes
   still misplace on the software pen in landscape, it's the transform, not the daemon.
2. **Weather strip misplaced in landscape** — the moon + divider land top-left near the
   hamburger instead of in the Tasks strip. drawTasksWeatherMoon uses portrait rcL/rcR;
   under the landscape transform they project wrong. One fix places both.
3. **Doodle reach in landscape** — addressed 1.07.15-.17 (width-fill + one-finger pan +
   overscroll); FIELD-VERIFY it actually reaches now.
4. Fast-ink in landscape remains the daylight "room" (portrait-only interim holds).

Shipped this run (portrait, working): the whole dashboard, weather/moon in Tasks,
grid/jot fold, deeper zoom, note titles, direct doodle. Landscape is the cluster above.
