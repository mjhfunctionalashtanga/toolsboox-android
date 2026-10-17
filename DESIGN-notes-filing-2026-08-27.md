# The notes filing system — a real pass, 2026-08-27

Michael: "there's a lot of double writing and notes organization is so confusing and hard to
nav" · "notes, named or pickings or really all are so obtusely organized" · "Put some real
thought into the notes filing system."

This pass starts from EVIDENCE, keeps the 2026-08-22 ruling as law, and ends in phases small
enough to ship the same day.

## 1. THE EVIDENCE — where his ink actually goes

Three days pulled off dav.mjh.yoga (the live fleet store):

| day | dated-page strokes | named/family strokes |
|---|---|---|
| Aug 25 | 1,822 | 1,258 |
| Aug 26 | 952 | 2 |
| Aug 27 | 867 | 0 |

Named documents across ALL TIME: 14 Writes, 1 Grid, 2 Jots, 0 Synth. Text notes: 21 days'
worth. Pickings: 29 days.

**The dated pages are the notebook.** The named documents are occasional branches. And the
directories list ONLY the branches — the dated notes, where ~70% of the ink lives, appear in
no notes list anywhere. That single inversion is most of "obtusely organized": the system
files what he rarely makes and loses what he makes daily. (His punchlist this week was three
dated pages I had to read out of the raw day file.)

## 2. THE LAW — the 2026-08-22 ruling, still right

"The basic note for each day is a dated note, with whatever templates a person wants to use
per page." One Notes surface; templates are paper, not places; three doors — saved notes
(DATED + NAMED), new named note, export. The template half shipped. The DIRECTORY half was
built family-first, and the dated notes never joined. This pass finishes the ruling rather
than replacing it.

## 3. THE MODEL — what a note IS here

A note = **(date, pages) + optional name + tags + links.** Nothing else.

- **Date** is identity's floor — every note was born on a day; the dated note IS that day.
- **Name** is optional promotion — naming a thing moves it up from "Tuesday" to "the Fluke
  essay". Unnamed things are still real (titled by their date, per the ruling).
- **Tags** (#ink-harvested) and **links** (rhizome) are the associative axes — the
  zettelkasten half. FOLDERS DO NOT EXIST and must not be added: with recency, name-search,
  tags, links and the calendar, a folder tree is a fifth taxonomy that must be maintained by
  hand. The filing system's job is to make the four free axes so good that folders are never
  missed.
- **Template is paper.** Already law.
- **Pinned** is the one flag worth adding: the working set — a punchlist, the piece being
  written — is 2–5 notes that deserve to stand above recency. A pin is a reader's thumb in
  the book, not a folder.

## 4. THE ROOM — one Notebook, four ways in

**🗂 The Notebook** (Notes › All notes, shipped v1 in 1.07.49-19) is THE saved-notes door:

```
📌 Pinned            ← the working set, hold a row to pin/unpin
—
🗓 Aug 27 · Tuesday · 2 pages · #media       ← DATED notes join (phase 1, this pass)
✍ Facts and Feels · Lines · Aug 25 · 3 pages
🗓 Aug 26 · Wednesday · 3 pages
❝ Reading board · Pickings · Aug 25 · ❝ 12
…newest first, one list, searchable
```

Retrieval axes, each already owned by a surface and now all reachable from one room:
- **Recency** — the list itself (the commonest question needs zero navigation)
- **Name** — the search field
- **Tags / Links** — the # and 🔗 indexes (existing rows in the Notes folder)
- **Calendar** — a dated row IS the calendar axis; the Almanac remains the wide view

"Notes & Tags" (the period-windowed list) becomes redundant with dated rows in the Notebook —
retire its door AFTER the Notebook proves itself on-device, not before.

## 5. WHAT DIES (decision elimination)

- The family-first walk as the ONLY path (already demoted to the fold, -19).
- Any future folder feature. Ruled out above, on purpose, in writing.
- "Notes & Tags" as a separate door — candidate for retirement after field-verify.
- The double doors the punchlist named (corner tools, redundant tuck rows) — separate item.

## 6. PHASES

1. **Dated notes join the Notebook** (this pass): local day files enumerated by size
   (>20KB ≈ has content — a 360-byte day is empty; parsing 60 day files would repeat the
   35-second inventory mistake). Row: 🗓 date · weekday · page count · tags. Tap → the day's
   notes page. NOTE: size is a heuristic; a heavy reading-only day can slip in — opening it
   is still correct behavior, so the false positive costs one tap.
2. **📌 Pins** (this pass): local `notebook-pins.json` (key|date rows); hold any Notebook row
   → Pin/unpin (docs keep their Pages… hold as a menu row). Sync follow-up: the
   LedgerDocumentStore sidecar pattern, exactly as write-index rides.
3. **Export door** finishes per the 08-22 ruling (PNG/PDF/dispatch of page selections) —
   already specced there; iOS twin follows.
4. **Retire Notes & Tags** if the field agrees; fold Tags/Links as filter chips INTO the
   Notebook list header rather than sibling doors.
5. **iOS twin** of the Notebook (same indexes sync already).

## 7. WHAT THIS DOES NOT TOUCH

Stores, keys, sync, day JSON — nothing moves. The Notebook remains a POINTER over stores
that already exist (the bookshelf principle). Older builds see nothing new and break nothing.
