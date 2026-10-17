# DESIGN — Notes, simplified (Michael's ruling, 2026-08-22 evening)

The design conversation the handoff was waiting for. Michael, verbatim intent:

> "Dot, Graph, Line are all just templates. Same same Pickings, Self Executive.
> Notes should be simplified more with access to saved notes / the ability to create
> a new named note, or to export a single page or multiple pages from notes.
> Classic, but the basic note for each day is a dated note, with whatever templates
> a person wants to use per page."

## The model

- **One Notes surface.** No parallel kinds. Dot, Graph, Line, Pickings, Self
  Executive — every current page flavor is a TEMPLATE a page wears, not a thing a
  note is.
- **The default note is the dated note.** Open Notes on a day → that day's note,
  no ceremony. Classic.
- **A page picks its template.** Any page of any note can wear any template; the
  note doesn't care. Template choice is per page, remembered per page.
- **Named notes** are minted from the same surface (the existing five-template
  "start new named note" flow folds into this — the template picker IS the page
  template picker).
- **Notes' chrome shrinks to three doors:**
  1. saved notes (the directory of dated + named notes),
  2. new named note,
  3. export — one page or a selected set of pages, from any note.

## What this retires

- Separate Dot/Graph/Line/Pickings/Self-Executive entries as if they were
  destinations. They become rows in the page-template picker.
- Any Notes navigation that isn't one of the three doors.

## Michael's answers (2026-08-22, closing the open points)

1. **Fold it in.** Existing Dot/Graph/Line ink becomes pages of that day's dated
   note, each page keeping its template.
2. **Export = three verbs on a page selection:**
   - **Add to a new note** (export and transplant are the same door — the page-ops
     machinery),
   - **Export as PNG** — multiple pages may become multiple PNGs OR one single
     PNG stitched in page order,
   - **Export as PDF** — the selected pages, in order.

   And beyond files, **export as a dispatch** (Michael, same evening): send the
   selection as an **email**, a **post**, a **Bluesky** share, or a **task** —
   each carrying BOTH the OCR'd text of the page(s) AND the page image(s). The
   rails exist: the intake pipeline (page → server note → post), the gram→Bluesky
   loop, LedgerTaskSync for tasks, the mail path for email. OCR: the in-app
   ML Kit digital-ink recognition and/or the server's Vision pass — whichever the
   rail already speaks.

## Build notes (when green-lit)

- Data already cooperates: pages are keyed (`notePage`), templates render per page,
  named notes exist (LedgerDocuments). This is mostly IA/chrome work plus the
  page-template picker and the export flow — the PAGE OPS machinery the 08-22
  handoff already ruled "we should build it" (task #9 spec) is the export door's
  engine.
- Chrome vocabulary canon applies: no modes, hold-to-act, the pager's ‹ N › stays.

## Build plan (from the code map, 2026-08-22 late)

**The gift in the map: the day file already IS the dated note.** Every page kind —
numeric, write/grid/sketch/pickings families, selfexec, gratitude — lives in ONE
day file (`noteStrokes` keyed by page). So "fold it in" needs NO data migration:
the dated note is a PRESENTATION unification — its pager walks all of the day's
page keys in a defined order, each rendered with its own template. Rekeying the
stroke map would invalidate the byte-identical Android/iOS convergence fixtures;
walking the existing keys achieves the same experience with zero wire risk.

**Vocabulary translation (the brief's words → the code's):** Dot = Jots
(`drawBrainstormPage`), Graph = Grid, Line = Lines/numeric-ruled. Self Executive
is today a station key with no store — it becomes a template by giving it one
(one-line `LedgerDocumentStore("selfexec-index","selfexec","Self Executive")`).

**Template becomes a per-page PROPERTY, not a per-store identity.** Persisted as
an additive `"template"` field on the existing sidecar `pages.json` rows
(`LedgerDocumentStore.list` reads with `optString`, so older builds ignore it —
no day-JSON change, no fixture churn, no iOS wire coordination). The global
two-value `calendarNoteTemplate` pref becomes the seed default for new pages;
`drawPage` takes the per-page value. This dissolves the written refusal in the
old ▤ Template sheet (the "store IS the template" constraint) by architecture,
not by force.

**Export builds on `LedgerSendExport`** (which already speaks email / WP draft /
WP publish / community / PNG / linked-PDF / markdown) — additions: a PAGE
SELECTION model (this page / pick pages), `renderPageBitmap(pageKey)` (today it
renders only the current page), multi-page PNG (separate files or one stitched
tall image via the existing ordered-PDF writer's page walk), multi-page PDF via
`CalendarPdfRenderer.renderMonthToPdf`'s `PageContent` machinery, ADD-TO-NEW-NOTE
via `LedgerDocuments.startNote` + `LedgerDocumentPages` (the read half of page
ops already exists), Bluesky via the gram→Bluesky rail, task via LedgerTaskSync;
dispatch verbs attach the ML Kit / Vision OCR text alongside the image.

**Increments, in order:**
1. Per-page template property + the page-template picker (replaces the refusal
   sheet); Self Executive gets its store.
2. The dated-note pager unification (walk all keys; the three-door chrome).
3. Export: page selection + PNG(s)/stitched/PDF + add-to-new-note.
4. Dispatch verbs (email / post / Bluesky / task) with OCR + image.
5. iOS twin.

## The quick-jump hold (Michael, 2026-08-22 late)

The Notes quick-jump entry follows the chrome canon: **tap = today's dated note**
(the default door, no ceremony); **long-press = the family sheet** —

- 📅 Today's note
- 📝 Text notes
- ⏱ Most recent
- ＋ New named note…
- 🗂 All notes…

("or something like that" — the five rows above are the working shape; Michael
holds final cut.) Applies wherever Notes is one tap away: the Directory's ✒ Notes
row and the quick-note pen action.

## Self Executive, demoted (Michael: "not my favorite")

Self Executive keeps rendering wherever it already exists — history is never
orphaned — but it does NOT get a row in the page-template picker. The picker's
working set: Lines · Grid · Dots (Jots) · Pickings (+ Text as its own kind).
No selfexec store gets built; the station key stays a legacy renderer only.
