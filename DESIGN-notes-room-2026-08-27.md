# The Notes room reads classic — 2026-08-27

Michael, same day as the filing pass shipped, looking at it: "I don't need all that jots
graphs sketches pickings. … Notes are just a sheet of grams with handwriting. … the ux
makes it easy to write on each today page, create a note for today, a note for a week, a
note for a month when using the note button. The ability to name a note, export it, etc,
is just classic notes behavior." Plus: "Make sure that Notes has doors for publishing to
wordpress, bluesky, email, and fluent." Plus: iOS parity, and after the organizer and
notes are corrected, a combined in/out flow for correspondence (emails now, texts later)
— notes tagged and emailed or tasked.

This doc keeps the 08-22 IA ruling and the 08-27 filing doc as law and finishes them in
the direction he just pointed: **the list is a classic notes list, the note button mints
today/week/month, name and export are one hold away, and the publish doors are four.**

## 0. THE SENTENCE THAT DECIDES EVERYTHING

**Surfaces are just backgrounds. Grams and picks are atoms. A pickings board is a notes
page wearing the pickings template. A note is a sheet of grams with handwriting. Today
pages, week pages, and almanacs are surfaces for grams too.**

Consequence: the Notebook list may never speak template words. "Lines", "Grid", "Jots",
"Pickings" are paper, and paper is not a fact about a note any more than "college-ruled"
is a fact worth printing on a notebook's spine. A row is: **title (or a humane date) ·
when · pages · tags.** Nothing else.

## 1. WHAT THE MAP FOUND (2026-08-27 code audit, builds -19/-20/-21)

- Every document row's detail line LEADS with the template word and wears the template
  glyph (`showNotebook`, LedgerDirectory.kt:2041; vocabulary from
  `LedgerDocuments.glyph/label`). The paper leaks back into kind on every row.
- Today double-files: `forSurface(today)` contributes the implicit unnamed daily pads
  (Write/Grid/Jot + default board + untitled texts) while the same day also gets its 🗓
  dated row.
- "All notes" means three different places; six doors still open the OLD kind-first root
  (`showLedgerRootDirectory`) — including the dated-note menu's own "🗂 Saved notes…".
- The dated-note menu has FIVE doors against the three-door ruling (its own comment
  cites the ruling).
- Tapping a text note in the Notebook opens a blank page (tn- key sent to the day
  navigator instead of `TextNotesFragment.open`). Broken since -21.
- The dated trunk is 60 days deep and >20KB only; the list truncates at 80 rows with no
  unroll. "All notes" is not all notes.
- Pins are `shown.filter(...)` — a pinned row outside the query/lookback silently
  vanishes; pins are device-local.
- No rename and no export from the list; hold = Pin/Pages only.
- Named notes are second-class at the export door (no page picker, no multi-page verbs).
- Week/month notes do not exist in the Notes system — BUT the dormant toolsboox
  surfaces (`CalendarWeek/CalendarMonth.noteStrokes`, `toWeekNote/toMonthNote`, synced
  `week-YYYY-WW`/`month-YYYY-MM` files) already store, render, page, and sync. Promoting
  them is wiring, not storage.
- The pen button contradicts the quick-jump canon (tap = remembered configurable action;
  hold = destination tray, not the family sheet).
- iOS build 76 has NONE of -19/-20/-21 (no Notebook, no dated rows, no pins; sidebar is
  still kind-first with seven doors).

## 2. THE ROOM, REDRAWN

### 2a. The list (the Notebook, corrected)

```
📌 Punchlist                      Mon · 3 pages
📌 The Fluke essay                Aug 25 · 3 pages
─────
🗓 Today                          2 pages · #media
   Facts and Feels                Aug 25 · 3 pages
🗓 Week of Aug 25                 4 pages
   Reading board                  Aug 25 · ❝ 12
🗓 Yesterday                      3 pages
   Groceries                      Aug 22
```

- **No template words, no template glyphs.** 🗓 marks period notes (day/week/month);
  every other row is just its title. Text notes keep Ⓣ only if field-testing says the
  distinction earns its ink; default is OFF.
- **Humane dates**: Today · Yesterday · weekday inside 7 days · "Aug 25" inside the
  year · ISO beyond. Both as dated-row titles and as row details.
- **No double-filing**: the named half of the list is named-only (`doc.named`); today's
  unnamed ink IS the 🗓 Today row.
- **Row hold** = the classic contract: 📌 Pin · ✎ Name · ⇪ Export · ▤ Pages · 🗑 Delete
  (delete only where the store already allows forget; dated rows never delete).
- **Pins stand on their own**: the pinned block is computed from the pin ids directly
  (resolving each id to a row even outside the lookback/query), so a pin never silently
  vanishes. Sidecar sync of notebook-pins.json rides the write-index pattern (follow-up
  as planned).
- **All time, not 60 days**: dated rows enumerate by listing `calendar/YYYY/MM/` dirs
  (filenames only, same never-parse law) with the >20KB heuristic unchanged; the list
  pages by 40 with an "…and N more" unroll instead of the silent 80 cap.
- **Search** stops matching template words (detail no longer contains them; nothing else
  changes).
- Fix the text-note tap: `openDocument` grows the TEXT_NOTES branch →
  `TextNotesFragment.open(fragment, date, key)`.

### 2b. One door

Every lambda that today opens `showLedgerRootDirectory` under a notes name — the
dated-note menu's "Saved notes…", the named-note menus' "All notes & documents…", the
family sheet's "All notes…", the hub subfolds — retargets to `showNotebook`. The
kind-first root survives ONLY behind the hub's standalone "🗂 Directory" row (it lists
Gram Picks and the whole ledger; that is its job). The per-family shelves stay reachable
from there — behind the fold for the moments the kind IS the question — but no Notes
door leads with them. "Notes & Tags" retires from the Notes folder (Phase 4 of the
filing doc, field-verdict delivered today); # Tags and 🔗 Links fold into the Notebook
as header filter chips.

### 2c. The note button (quick-jump, finished to canon + periods)

- **Tap = today's dated note.** Always. The remembered-action pref retires; the two-tap
  day-page toggle stays (second tap while standing on the note flips to the day page —
  that pairing is the organizer↔notes shuttle he asked to keep flawless).
- **Hold = the family sheet**, now the period mint:

```
📅 Today's note
🗓 This week's note
🗓 This month's note
＋ New named note…
Ⓣ Text note
🗂 All notes…
```

- Week/month notes v1 = the dormant surfaces, promoted: `toWeekNote(today)` /
  `toMonthNote(today)` open them; they join the Notebook as 🗓 rows ("Week of Aug 25",
  "August") via the same file-size listing over `week-*`/`month-*` files; the per-page
  ▤ template picker works there (NotePageTemplates keys are `key|date`-shaped and the
  period surfaces have their own keys — record against the period file's identity).
  Naming/tagging/exporting period notes rides later phases; v1 is: mintable from the
  button, listed in the Notebook, ink syncs (it already does).

### 2d. Name + export, classic

- **Name**: ✎ on the row hold (promptRename machinery exists for every named store);
  dated rows use the existing date-scoped WRITE-entry naming. One ceremony, reachable
  without opening the note.
- **Export**: the ⇪ door — page picker → verb sheet — becomes reachable from (1) the
  row hold in the Notebook, (2) the dated-note menu (already), (3) the named-note menu
  (new — wire `showPagePicker` + the verb rows into `showNoteSubPageJump`).
- **The verb sheet's publish half is exactly four doors**, per Michael today:
  **✉ Email** (the user's own SMTP composer — the bridge-mail route retires from this
  sheet; one email rail, one sent record) · **📰 WordPress** (draft or publish) ·
  **🦋 Bluesky** · **🌱 Fluent** (community space). Every dispatch carries OCR text +
  page image(s), per the 08-22 ruling. File verbs (Add to a note · PNG · PDF) and ✅
  Task stay as they are.
- Exports of a named note carry its NAME (subject/title/filenames), not "Note — date".
- Multi-page PNG/PDF outputs get the LedgerProvenance stamp the single-page sheet
  already writes.
- The dated-note menu's "🗂 Saved notes…" door opens the NOTEBOOK now, not the old root.
  Its "💾 Save page(s) as a named note…" and "✉ Send page(s) as an email…" doors STAY —
  they were his own 08-24 asks ("I need a clear way to handle saving a page/pages as a
  named note", "an easy send-as-email option"), which outrank this doc's three-door
  tidiness. Amended in-implementation, 08-27.

### 2e. What dies

- Template words and glyphs in the Notebook.
- The five-door dated-note menu; the second "All notes" meanings.
- "Notes & Tags" as a door (the level ladder survives inside the Almanac).
- The bridge-email verb on the export sheet.
- The remembered-action pen button (the tray's other destinations — Text, photo, voice,
  Gram Picks — live on in the hold sheet and capture chrome).

## 3. CORRESPONDENCE — the in/out flow (design now, build after notes verify)

The mail map found the rails; what's missing is the JOIN and the ROUTE:

1. **One out-rail**: everything outbound converges on `MailSync.sendNew` /
   `MailVerbs.sendReplyText` (local SMTP, sent pile recorded). The export sheet's ✉
   already moves there in 2d.
2. **The correspondent view**: one thread joining in and out per correspondent, built
   over InboxStore's kept + sent piles (they exist; they're just two lists today).
3. **Tag-then-route**: `MailVerbs` grows the routing verbs beside star-to-todo — a
   tagged note/letter can be **✉ emailed** (compose prefilled with the note's
   OCR + image, per 2d's gather) or **✅ tasked** (LedgerItem, proposed-first,
   LedgerTaskSync push on promote). Precondition: heal the `email://` vs `mail://`
   identity split so a letter is one node in the rhizome.
4. **Email → note page**: a letter gains "✍ Onto today's note" beside star-to-todo —
   the missing inbound door (letter face as gram + link, onto the dated note).
5. **A handwritten reply is a note**: the ephemeral reply pad mints a real page (the
   Write-reply seam already does this; the pad path joins it), so sent letters are
   findable in the Notebook.
6. **Texts**: no on-device rail exists (Shalagram/Nudge are server-side). The lens
   grammar (mail is a source + lens over the pane) is the join point when a texts
   source arrives. Not in this pass.

## 4. iOS PARITY (the twin list, in ship order)

Build 76 lacks all of -19/-20/-21. The twin plan, against the same stores (no wire
changes — the Notebook is a pointer; ConvergenceGoldenTests stay green):

1. The Notebook, born correct: flat list per §2a (never ship the template-word version
   iOS never had), one door from the sidebar (kind rows collapse), dated rows by
   day-file size, pins (notebook-pins.json, same id scheme, sidecar ride when Android's
   does).
2. The note button/quick-jump per §2c incl. week/month (the period stores are in the
   shared wire already).
3. Export door parity: add-to-a-new-note verb, the four publish doors, named-note page
   picker.
4. Mail reply as blank note + stationery (-12/-13 twin) — folds into §3.5.

## 5. PHASES (Android, ship-today sized)

1. **-22 The list reads classic**: §2a complete (labels, humane dates, double-filing,
   hold verbs Pin/Name/Export*/Pages, pins stand alone, all-time dated rows, unroll,
   text-note tap fix). *Export on the hold may land as navigate-to-note-and-open-door
   if extraction fights back; the door itself is Phase 3's.
2. **-23 One door + the button**: §2b retargets + §2c (tap/hold canon, family sheet
   with week/month mints, period surfaces promoted into the Notebook).
3. **-24 Export classic**: §2d (three-door menu, named-note picker, four publish doors,
   one email rail, names on exports, provenance).
4. Field-verify on the Boox; then §3 correspondence increments and §4 iOS twin as the
   next arcs.

## 6. WHAT THIS DOES NOT TOUCH

Stores, keys, day JSON, sync, fixtures — nothing moves (the one addition:
NotePageTemplates rows for period-surface pages, an additive sidecar already tolerant of
unknown keys). Older builds see nothing new. RetiredSurfaceReachabilityTest stays green.
