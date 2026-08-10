# PROPOSAL — Boards & Tasks rethink, and the today page goes quiet

2026-08-10. A proposal, not canon — Michael rules on it first. Prompted by:

> "Boards and Tasks needs a rethinking/overhaul as does the auto created or text add ons to the
> [today] page about tasks. They aren't making the workflow better."

Grounded in a full code inventory (this session) and the standing rulings: *surfaces that guess
are the dead ones* (2026-08-03), *eliminate a decision where there's no loss* (07-24), the
produce-or-hold test, and "the real work will be to use Ask to create the flows, using the
boards/Task feature."

---

## What is actually happening (the inventory's short version)

**Two things write on the today page without being asked, and neither has an off switch:**

1. **Task carry-over** (`LedgerTaskCarryOver`, fired from `CalendarDayPresenter` on every open of
   today). Yesterday's undone tasks are COPIED into today — new `LedgerItem`s *plus typed text
   boxes physically stamped into the Tasks column* (or ink strokes redrawn). A separate `reflow`
   pass then re-arranges those boxes on every open. The text box and its task record are linked
   **by matching words only** — the fragility the code comments admit throughout that file.

2. **The Quick Wins panel** (`CalendarDayPage`), drawn onto today's template: up to six generated
   "⚡ task" rows + reasons occupying the bottom-right third of the page. Render-only, but
   automatic, every day, unasked — and Quick Wins was already ruled "overgrown" and a
   surface-that-guesses on 2026-08-03.

Also found: `LedgerExtractor`'s "Extract tasks & events" menu item (pref-gated, default off, one
reader in the whole app, redundant with the lasso flow) — dead weight.

**What already works the right way and is the model:** the lasso → OCR → **preview dialog with
editable date** → commit flow (`confirmLedgerItem`), and photo OCR's "nothing files without a
glance." Recognition proposes; the hand accepts.

**Boards/Tasks is three doors over one dataset.** `KanbanFragment` (Boards · Local) and
`LedgerItemsFragment` (Tasks & Events) are two renderings of the same walk over every day file —
the directory already routes one "☑ Boards & Tasks" row to whichever you used last, and the seam
comment says merging them deletes the router and nothing else changes. `SiteBoardsFragment` is the
genuinely different one (remote FluentBoards). Frictions: the board-picker menu holds eight
unrelated things behind hand-computed indices; every open re-walks the whole calendar directory
with no cache; "stage" has no lane for work waiting on someone else.

---

## The proposal

### 1. The today page is paper again *(the complaint, answered)*

- **Carry-over stops writing on the page.** An undone task doesn't need to be re-inscribed every
  morning — it needs to still be *open*. Carried-forward stays a fact about the TASK (it shows in
  the Tasks/Board view under today, marked "· since Aug 8"), not a stamp on the page. The typed
  text-box stamping, the ink redrawing, and the whole `reflow` repair pass retire. The
  words-matching fragility retires with them.
- **The Tasks panel may DRAW what's open, never write it.** Light render-only rows (the way the
  template draws its ruled lines) listing still-open tasks — tap opens the task, pen-check marks
  done. Nothing persisted onto the page; the ink layer stays exactly what Michael wrote. *(Ruling
  wanted: drawn ghost rows, or nothing at all and the panel is purely his ink?)*
- **The Quick Wins panel comes off the today page.** The bottom-right third returns to Notes.
  Quick Wins' fate was already sealed on 08-03 — absorbed into Ask's occasioned prep, not a
  standing guess on the day's paper.
- **`LedgerExtractor` + its pref retire.** The lasso flow is the one blessed reader.
- Everything with an explicit verb (lasso-confirm, ＋Task, star-to-todo, Ask's `add_task`,
  Path-to-Victory's confirmed execute) stays — those are asked-for.

### 2. One Boards door *(merge what the code already admits is merged)*

- **Kanban + Tasks & Events become ONE surface** with a List ⇄ Columns view toggle on the rail.
  Same cards, same verbs (done, edit, ink face, rhizome, assign, board, delete) in both renderings.
  The `TASKS_MODE_PREFS` router and one whole fragment delete.
- **Local / Site stays the RSS-style toggle** inside that one door. Site Boards' tactile surface
  remains the renderer for Site; the existing deep-links and due-card folding survive unchanged.
- **A "Waiting" lane joins the stages** (`stage="waiting"` — a new VALUE, not a new wire key; an
  older reader folds unknown stages into To do, which is honest degradation). It's the one
  portable idea from the kanban research: the lane for work that sits on someone else —
  Correspondence's natural landing.
- **The board picker unbundles**: switching boards is one menu; bridge creds, web-push, and
  include-site settings move to where settings live.
- **The all-tasks walk gets a cache** (mtime-keyed, the DayLite pattern that fixed "insanely
  slow" before) so opening Boards stops re-reading the year.

### 3. Ask assembles the flows *(the real work — sequenced after 1 & 2)*

Michael's own spec from the 08-03 notes: the occasion is the day, the trigger is the schedule.
"You teach a private at 4 — here's the client's CRM info, the follow-up you owed them, your last
session notes, the article you starred." Ask reads the schedule + CRM + notes + the Read and
**proposes a card stack onto the board — proposals you accept**, in the confirmLedgerItem grammar.
Quick Wins' machinery (the corpus math is fine; the surface was wrong) feeds this instead of the
day page. Smallest proof: tomorrow's schedule, one private client, one prep stack.

---

## Sequence

1. **Build A — the quiet page**: retire carry-over stamping + reflow + day-page Quick Wins panel
   + LedgerExtractor; carried-open tasks visible in the Tasks view (and drawn rows if ruled in).
   Small, immediately felt, removes the complaint.
2. **Build B — the one door**: fragment merge, view toggle, Waiting lane, picker unbundle, cache.
3. **Build C — occasioned prep**: the Ask flow, proposals-to-board.
4. iOS follows each build behind the Android proof, folded into the parity queue.

**Rulings wanted from Michael:** ① ghost rows or bare paper in the Tasks panel; ② confirm Quick
Wins leaves the today page; ③ green-light Build A now, or the whole arc.
