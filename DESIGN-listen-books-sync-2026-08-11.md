# PROPOSAL — The Listen and the Library sync across every Ledger

2026-08-11. Ruled in conversation by Michael the same night; this document is the build spec.
Rulings incorporated: Syncthing retires (it cannot run on iPad) · Google Drive is the SECOND
BACKEND for users without WebDAV, behind the same seam, not a fallback for Michael · a book added
ANYWHERE propagates EVERYWHERE (auto-download or nudge) · one OPDS pull feeds the whole fleet.

## The spine

**The library is a fleet property, not a device property.** The sync hub (Michael: dav.mjh.yoga;
users: WebDAV or Google Drive, chosen in Settings) grows:

```
books/                          the library files, folder structure preserved
books-index.json                manifest: name·folder·size·hash·addedBy·added per book
reading-state.json              per book (folder-qualified name+size key): locator·percent·updated
listen-state.json               per episode (id/url): position·duration·done·updated
```

## The pieces

1. **Backend seam.** One sync logic, two transports — the seam the DAY sync already has (WebDAV +
   Drive merge both exist). Book/listen sync is built backend-agnostic from day one; every future
   sidecar inherits the posture.
2. **Propagation.** Any add (import, OPDS download, share-in) uploads the file + updates the
   manifest. Every device compares manifest vs local shelf on its sync tick: auto-download when
   policy allows (wifi · size ceiling · the device's carry selection), else a shelf nudge —
   "<title> arrived — fetch?". Deletes are manifest tombstones (id-union, the house merge rule);
   a delete never reaches into a device's local files without its own confirmation.
3. **Carry selection.** Per device, per folder: "carry" (auto-fetch) vs "visible" (fetch on open —
   the OPDS pattern against our own hub). A Palma does not want 20GB of PDFs.
4. **No master — the hub is the truth and the Mac is just another ledger.** (Ruled 2026-08-11,
   replacing Calibre-as-master, which contradicted the product half: a Google-backend user has no
   Calibre, so the hub had to stand alone anyway.) The Mac joins the fleet as a device with a
   carry-everything selection — a mirror folder the hub keeps current. Calibre demotes to an
   OPTIONAL tool that reads the mirror (cataloging, conversion); adds made through it ride up
   like adds from any device. No one-way cron pretending not to be two-way sync.
5. **Reading position.** Both forks read through foliate — locators are portable. newest-wins per
   book; BookOpens union in the same sidecar so the almanac band agrees fleet-wide.
6. **Listen playback.** Subscriptions already sync (Miniflux IS the subscription store). The
   sidecar carries position/done per episode: newer-stamp-wins, done monotonic — the calendar
   merge canon applied to audio.
7. **Syncthing retires for books** once the hub proves itself on all six devices (keep it running
   in parallel one week; the shelf pointer just re-aims at the hub-managed folder).

## Sequence

A) Sidecars first (listen-state, reading-state) — small, immediately felt, no file moving.
B) The hub + manifest + propagation on Android; Calibre cron.
C) iOS twin (WebDAV client + bookmark shelf already in place from parity step 5).
D) Google backend behind the seam. E) Syncthing retirement.

Wire rule as ever: new FILES beside the existing tree, no day-JSON changes, id-union + tombstones.
