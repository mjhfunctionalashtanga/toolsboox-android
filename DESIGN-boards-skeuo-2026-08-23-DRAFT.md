# DRAFT — Boards, the throwback (a proposal sheet, NOT canon)

2026-08-23, prepared for Michael's ruling. From his 08-19 review, verbatim:

> "the to-do boards UX could get an overhaul — nice visuals, throwback CLASSIC style,
> embossed / skeuomorphism."
>
> "Build Schedule automation in a to-do board — I want to know more about this."

Two sentences, deliberately not yet a build. Last night's design-tokens lesson stands:
visual values happen as a real design conversation, or not at all. This sheet exists so
that conversation starts from the app's own vocabulary and his own words, with concrete
options to point at — one ruling per section, then it becomes canon and a build.

## What the app already knows how to do (no invention required)

The skeuomorphic voice is already IN the codebase, field-proven and liked:

- **InkMount / CardTreatment** (`ot/CardTreatment.kt`): white mat, hard near-black
  hairline rule, opaque white tape over the top corners, baked into the bitmap.
  "The same cute stuff" he asked to carry through Garden objects (07-21 ruling).
- **LedgerFonts** (`ot/LedgerFonts.kt`): the app-wide face applied to every dialog tree.
- **The e-ink constraints as style**: 16-grey Kaleido panel, no color reliance, no
  shadows-as-alpha — embossing on e-ink is drawn (highlight line above, shadow line
  below), not blurred. The eink-underline and contrast rulings apply.

So "throwback classic embossed" can mean: boards drawn like PHYSICAL BOARDS — index
cards on a cork/felt field, taped or pinned, lanes as labeled wooden rails — using the
exact mount-and-tape treatment cards already wear elsewhere. Zero new design language;
the existing one, applied to the one surface that never got it.

## The three doors today (from the 08-10 inventory, still true)

- `KanbanFragment` (Boards · Local) and `LedgerItemsFragment` (Tasks & Events): two
  renderings of one walk over the day files.
- `SiteBoardsFragment`: the genuinely different one (remote FluentBoards).
- The 08-10 proposal (`DESIGN-boards-tasks-2026-08-10.md`) and the 08-10 rethink
  ruling already cover the STRUCTURAL half (merge the local pair, the "Waiting On"
  lane, carry-over off the page). This sheet is only the VISUAL half on top.

## Options to rule on (pick, cross out, or redraw)

1. **The card.** Task = an index card wearing the InkMount treatment (mat, hairline,
   tape). A handwritten task shows its INK on the card face (the item already keeps
   strokeIds); a typed one shows typed text in the Ledger face. Done = the card gets
   a drawn ✓ stamp… or moves to a "Done" spike/tray. **Which?**
2. **The lane.** Columns as labeled rails ("To do / Doing / Waiting on / Done") with
   embossed lane headers — drawn double-line emboss, no grey washes. Or: no lanes at
   all on the small screens, one spike per stage, fanned. **Rails or spikes?**
3. **The field.** Cork? Felt? Plain paper? On 16-grey e-ink a cork texture is noise
   risk — the honest options are a paper field with pinholes/tape shadows, or nothing.
   **Field texture: paper-with-pins, or bare?**
4. **Move = the hand.** Dragging a card should feel like unpinning and re-pinning
   (lift: tape corners release; drop: pin sound-less stamp). Hold-to-act canon applies
   — no drag modes. **Confirm hold-drag is the move verb?**
5. **Scope.** Local boards first (KanbanFragment), SiteBoards (FluentBoards) wears the
   same dress after? Or both at once? **Local-first proposed.**

## "Schedule automation in a to-do board — I want to know more about this"

Read as a question to US. The honest answer back, for his ruling:

- What exists: FluentBooking due-cards already flow into SiteBoards; the VPS faucet is
  now offer-never-mint (proposed shelf); schedule blocks live in day JSON.
- What "schedule automation in a board" COULD mean (options, not recommendations):
  (a) a board lane that mirrors today's schedule blocks as cards (render-only, the
  pane-not-page rule); (b) a recurring-task template board ("every Monday these five
  cards appear" — Tasks.org RRULE machinery, GPL-liftable, July ruling); (c) the
  FluentBoards-style stage automations ("card enters Doing → start a timer") done
  LOCALLY on LedgerItem.stage.
- **Ask him which of a/b/c (or none) he actually meant before designing further.**

## What this sheet is not

Not a build order. Nothing ships from here until each numbered question carries his
answer. The 08-10 structural proposal remains separately pending its own rulings.
