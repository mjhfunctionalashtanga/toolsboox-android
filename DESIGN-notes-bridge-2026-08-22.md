# DESIGN — The notes bridge: mjh.yoga/notes ↔ Ledgable (Michael, 2026-08-22 late)

> "The pathways from my webdav to dav.mjh.yoga like we used for ultrabridge could
> provide an in route from my notes routings on mjh.yoga/notes and bringing them
> into my device naturally by importing those notes from different devices/
> strokes and we could be able to pull those up from within ledgable — we can
> explore two way sync."

## The model

The pipe already exists: Ledgable syncs `text-notes/` (and the calendar tree)
against dav.mjh.yoga today. The bridge adds a lane on the same pipe:

- **In-route (first):** the notes that flow through mjh.yoga/notes — the ingest
  routings, seed notes, voice-pipeline notes, notes born on other devices — get
  mirrored server-side onto the dav tree in a shape Ledgable already reads.
  Inside Ledgable they are pull-up-able like any note: listed in the directory,
  openable, taggable.
- **Two-way (explore):** edits and additions made in Ledgable flow back up the
  same lane into /notes. Explore, not promised — the merge discipline
  (newer-wins vs union vs tombstones) gets decided by looking at what /notes
  can tolerate, with the calendar-merge canon as the reference.

## Notes

- Server side is a VPS/WP job (the Ultrabridge pattern: a mirror process on the
  pipe's server end), not app-side scraping.
- The Notes IA directory (three doors) is where these surface — a bridged note
  is just a note; no special chrome.
- Strokes born elsewhere (other devices' handwriting) ride the same in-route
  where formats allow; text notes first, ink later.
- Sync canon applies: Depth:1 not infinity (the 403 lesson), merge rules
  explicit, resilient to bad files.

## The field ledger archive (Michael, same thread)

> "I hope that my field ledger archive is included in all of my ledger searching,
> etc. So many good notes pictures etc etc etc and 23 years of it."

Twenty-three years of field ledgers — notes, pictures, the lot — join the
searchable corpus. The bridge's search half: whatever Ledgable search and the
semantic pathways index can reach, the archive is part of it. Scanned pages ride
as images + OCR text (the memo shape, applied backwards in time); the archive
surfaces in search results beside living notes, openable like anything else.

Location(s) to confirm with Michael — known edges: Drive `StarNote/Journal 000…`
scans; the mjh.yoga /journal archive. The full census (formats, where the 23
years physically/digitally live) is the first build step.

## Bridged Boox notes carry their ink (Michael, during the autonomous run)

> "Text notes is good, but when they're reproductions of notes from inside a boox
> device, we should also have pngs of the strokes too like on mjh.yoga/notes."

When a bridged /notes item reproduces a Boox-device note (Notebook OCR posts and
kin), the in-route also carries the stroke PNGs the site shows: the bridge pulls
the post's images onto the dav media lane, and the note's JSON gains an additive
"images" field (Moshi-safe — older builds ignore it) that the app renders beneath
the text. The hand's ink travels with its transcription, both directions of the
memo shape.

## The out-route — Ledger notes → CPT, strokes and all (Michael, considering aloud)

> "Create pages/posts as CPT from notes within Ledger — we have the plumbing.
> Just like we pull notes in from mjh.yoga which is really a backup from Boox…
> and if it's Boox it could send the strokes JSON so I could really have it."

The two-way's second half, and its most powerful form. A Ledger note POSTS itself
to the `social_archive`/notes CPT on the site (WP REST create/update, the pipes the
in-route already proved). Three fidelity tiers, by what the note is:

1. **Text/OCR** → the post body (searchable, readable, the reproduction we have).
2. **Rendered ink** → the stroke PNG as featured/attached media (what /notes shows).
3. **Boox-native strokes JSON** → the RAW stroke data in a post-meta field
   (`_ledger_strokes`, base64 or JSON). THIS is the inversion: the CPT stops being
   a backup-of-a-picture and becomes the CANONICAL VESSEL — the note round-trips to
   full editable ink on any device, the site is the home, the devices are windows.

STATUS: Michael is CONSIDERING — recorded as canon-in-waiting, not greenlit to build.
Open questions for him when it ripens: does posting happen on the export "post" verb
(the dispatch verbs, Notes increment 4) or as a background sync of every note?
one CPT or two (public journal vs private note vessel)? does the strokes-JSON round
trip re-open as LIVE ink (edit-in-place) or as a read-only faithful render first?
The plumbing is all present; the ruling is the missing piece. His call when ready.
