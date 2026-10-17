# Comics in the bookshelf — examined, 2026-08-24

**Nothing is built by this document.** Michael asked for local CBZ panel-by-panel first, and for
the design pass to happen before the build. This is that pass.

> "I want to add comic books/cbz to the bookshelf using https://github.com/mis0suppe/mihon-ds for
> panel by panel use for mihon. I've been using mihon on Android, but would be interested in adding
> its features into the bookshelf." … "local cbz panel by panel first, do the design pass"

---

## 1. THE FINDING: CBZ probably already reads. Panel view is the actual work.

Before designing anything, what is already true on both forks:

| piece | state | evidence |
|---|---|---|
| CBZ listed as a book kind | ✅ | `BookshelfSource.kinds` includes `cbz`, `cbr` |
| CBZ accepted by the importer | ✅ iOS · ❓ Android | iOS `importTypes` names `cbz`; Android uses a bare `OpenDocument()` contract — **verify what MIME filter it passes** |
| CBZ **opens in the reader** | ✅ **already routed** | `view.js` → `isZip(file)` → `isCBZ(file)` → `makeComicBook(loader, file)`. Both forks vendor `comic-book.js` |
| page-per-screen rendering | ✅ | `comic-book.js` sets `book.rendition = { layout: 'pre-paginated' }` |
| **panel-by-panel** | ❌ **absent** | pre-paginated means ONE IMAGE PER SCREEN. There is no panel concept anywhere in the vendored code |
| CBR (RAR) | ❌ **broken, and listed** | `isZip` is the gate; a `.cbr` is a RAR archive and will fail it. The shelf offers a format the reader cannot open |

**So "add CBZ to the bookshelf" is mostly a verification job, and the feature he actually wants is
panel-by-panel.** The first hour of the build should be spent confirming a real `.cbz` opens on the
B6 and on the iPad — because if it does, the work below starts from a working reader rather than
from a port.

**CBR is a separate, smaller decision:** either drop it from `kinds` (honest — a listed format that
cannot open is worse than an absent one) or add a RAR reader (a real dependency). Recommend
dropping it until asked for; nothing in his libraries is known to be CBR.

---

## 2. WHAT MIHON-DS ACTUALLY GIVES US

`mis0suppe/mihon-ds` is a fork of Mihon for DUAL-SCREEN devices (AYN Thor, AYANEO Flip DS), and it
carries exactly the feature named: *"Guided Reading: Detects panels in paged manga and comics for
panel-by-panel navigation with dual-screen context."*

- **License: Apache 2.0.** Liftable with attribution — the same footing as `inkit` in the fast-ink
  work, and this repo already keeps `reference_ledger_upstream_references` for exactly this. Any
  lifted algorithm gets its provenance recorded there.
- **The dual-screen half is irrelevant to us** and should not be ported. What we want is the panel
  DETECTOR and the reading-order walk, not its second-display context.
- **Kotlin, on Android.** Portable to the Boox fork directly. NOT portable to iOS — the iPad's
  reader is JS in a WebView, so the detector has to exist twice or live in the shared JS. **See §5.**

---

## 3. PANEL DETECTION — how it works, and where it fails

The classic approach, and almost certainly what a guided-reading feature does: **gutter analysis /
recursive XY-cut.**

1. Downscale the page (detection does not need full resolution — this is also the speed budget).
2. Binarize against the page's own background (comics gutters are near-uniform, usually white).
3. Find long runs of near-uniform ROWS → horizontal cuts; same for COLUMNS → vertical cuts.
4. Recurse into each resulting block until blocks stop splitting or get too small.
5. Order the leaves by reading direction.

**Where it fails, and these are not edge cases in comics:**
- **Bleeds** — art running to the page edge with no gutter: the page reads as one panel. Acceptable.
- **Splash pages** — genuinely one panel. Correct answer, looks like failure.
- **Overlapping / angled panels** — common in modern comics; XY-cut cannot separate them and will
  merge them into one block. Acceptable, if the fallback is right.
- **Dark or textured backgrounds** — the gutter assumption breaks entirely; detection returns
  garbage (one panel, or fifty).

**The rule that makes all of that survivable: NEVER TRAP THE READER.** When detection yields ≤1
panel, or implausibly many (say >12 on one page), the page shows WHOLE and the reader pages
normally. A guided view that strands you inside a mis-detected block is worse than no guided view,
because the way out is not obvious mid-read.

And a manual override on the page: **"show the whole page"** — always one tap away, always.

---

## 4. READING ORDER — the setting that cannot be guessed

Manga reads RIGHT-TO-LEFT; western comics LEFT-TO-RIGHT. Get it wrong and the book is not
mildly off, it is unreadable — panels arrive in the wrong sequence and dialogue reverses.

- **Per book, stored**, not global: he reads both.
- **Defaulted, not inferred.** A guess from filename or metadata will be wrong often enough to be
  worse than a default plus one visible toggle. (Mihon knows because its sources tell it; a loose
  CBZ on disk carries nothing reliable.)
- The toggle belongs in the reader's own menu, beside the mark styles, and it re-lays the current
  page immediately so the effect is visible where the choice is made.

---

## 5. THE TWO-FORK PROBLEM, and the answer

The Boox reader is a WebView over foliate-js. The iPad reader is a WebView over foliate-js. **The
detector should therefore be JAVASCRIPT, living beside `comic-book.js`, and shared** — not Kotlin
lifted from mihon-ds and then written a second time in Swift.

So mihon-ds is read as a REFERENCE for the algorithm and its parameters, and the implementation is
ours, in the one language both readers already run. That is the same call the fast-ink work made
about `inkit`: the reference is the facts, not the file.

This also keeps panel state where reading state already lives — one page index plus a panel index,
both in the existing per-book position record.

---

## 6. THE E-INK COST, which decides whether this is worth building for the B6

Panel-by-panel means **a full-screen image change per tap**. On the Boox that is a refresh per
panel — where a whole page used to be one refresh, a six-panel page is now six. Combined with the
B6's speed, guided reading could feel *worse* than paging.

Two things make it survivable, and they should be built in from the start rather than added after
he reports it:
- **Panel steps are a CROP OF AN ALREADY-DRAWN PAGE**, not a new page load. The page bitmap is
  decoded once; a panel step is a transform onto the same bitmap. Android already has exactly this
  mechanism — `focusOnRect` zooms and centres a design-space rect and is used by the tag-jump.
- **Use the quiet refresh for panel steps**, and the full refresh only on page turns. The codebase
  already draws this distinction for pen strokes.

**Honest possibility worth stating before building: guided reading may be an iPad feature that the
Boox merely tolerates.** Comics are usually colour and often dense; the iPad is where they will
actually be read. That is not a reason to skip the Boox — the code is shared — but it IS a reason
to field-verify on the B6 early rather than at the end.

---

## 7. THE ORDER TO BUILD IT

1. **Verify a real CBZ opens** on both devices, and fix the Android importer's filter if it does not
   accept the extension. Decide CBR: drop from `kinds`, or accept the dependency.
2. **The detector, in JS**, beside `comic-book.js`: page image → ordered list of panel rects, with
   the ≤1 / >12 fallback to whole-page. Pure function, no reader coupling — it can be tested against
   sample pages without opening a book.
3. **The walk**: next/prev step through panels, then to the next page. Reading-order setting, per
   book. "Show whole page" always available.
4. **The crop, not a reload** — and the quiet refresh on the Boox.
5. **Position**: panel index rides the existing per-book reading position.
6. Field-verify on the B6 with a real comic BEFORE the iPad polish; the e-ink answer decides how
   much further this goes.

---

## 8. DEFERRED, DELIBERATELY: mihon as a SOURCE client

Mihon's centre of gravity is not the reader — it is **extensions that fetch chapters from online
catalogues**, plus a downloader and a chapter-tracking library. Tachimanga (the iOS app he named)
is the same shape.

That is a different project from "read a CBZ I already have", and it carries questions this pass
did not touch: where catalogues come from, what is legal to ship, how a chapter library relates to
the bookshelf's existing book records, and how any of it syncs.

**And he may already have the library half:** `reference_komga_comics_server` records a Komga
comics server in his world. A Komga OPDS feed may reach the shelf through the OPDS catalogue support
this reader ALREADY has (`opds.js` is vendored, and there is a "Book catalog" OPDS door in the
reader today) — which would answer "get comics onto the shelf" without any of mihon's machinery.

**That is the first thing to check if he wants the library half — before anyone ports anything.**

---

# ADDENDUM — "don't download twice", 2026-08-24

His constraint, which arrived after the pass above and changes the plan for the better:

> "I've got komga installed, but I'm still using the different mihon repositories tbh — i want to
> be able to track and read my books just like in mihon if thats possible within ledgable."
> … "I don't want to download the books twice between mihon and ledgable, but I do want it to work
> and track with mihon."

## The shape this gives the problem

Ledger should NOT fetch, download or store comics. **Mihon already did that**, on the same Android
device, with his repositories and his logins. Ledger's job is to READ what is already on disk and
to leave a trace mihon can see.

That deletes most of §8's deferred project. No extension host, no downloader, no catalogue.

## THE READING HALF MAY BE A SETTINGS CHANGE

**Both forks already have "point at a folder".** Android: `addBook` → "🗂 Point at a folder…" →
`BookshelfSource.pickIntent()`. iOS: the folder importer + `DeclaredBooksFolder`. The shelf walks a
declared tree and lists what it finds; `cbz` is already a recognised kind; `view.js` already routes
a CBZ to `comic-book.js`.

**So the whole reading half may be: point Ledger's shelf at mihon's download folder.** No copy, no
second download, no port.

Three things to VERIFY on the device before believing it (all cheap, none built yet):

1. **Mihon must be saving chapters as CBZ.** It has a "Save as CBZ archive" download setting. With
   it OFF, a chapter is a FOLDER OF LOOSE IMAGES, which foliate cannot open as a book — the shelf
   would list a directory it cannot read. If he does not want to change that setting, the fallback
   is teaching the shelf to treat an image folder as a comic, which is real work but small and
   entirely on our side (`comic-book.js` already takes a loader, not a file).
2. **Folder access.** Mihon's default download location under `Android/data/...` is not reachable by
   another app on Android 11+ through SAF. If his mihon writes to a user-chosen directory (common,
   and often chosen for exactly this reason) it is pickable and this works. If not, the answer is to
   point MIHON at an ordinary folder, not to fight the sandbox.
3. **What a "book" should be.** One CBZ per chapter means the shelf fills with chapters, not series
   — a hundred rows called "Chapter 12". The shelf groups by folder already; whether a series should
   appear as ONE shelf entry with chapters inside is a real design question and the first thing that
   will feel wrong.

## THE TRACKING HALF — the tracker IS the sync point

"Track with mihon" is the hard half, and there is one honest answer and one tempting wrong one.

**WRONG: write into mihon's database.** Two apps mutating one SQLite file, one of which we do not
control and which updates on its own schedule. Corruption is not the worst case; silent divergence
is, and it would surface as lost reading progress in a library he has curated for years.

**RIGHT: both apps report to the SAME EXTERNAL TRACKER.** Mihon already syncs progress to
AniList / MyAnimeList / Kitsu / Shikimori — that is what he means by "when I download from Mihon
with a tracker it all works". If Ledger reports chapter progress to the same tracker account, the
two converge THROUGH it, and neither app has to know the other exists. The tracker is already the
agreed-upon truth between his devices; it can be the agreed-upon truth between his apps.

That is a contained build — an OAuth login and a "set progress" call per service — and it is
strictly additive: nothing in mihon changes, and if Ledger never writes, nothing breaks.

**Order stands, and he proposed it himself:** reading first, tracking second. Reading may cost a
folder pick and a verification pass; tracking is a genuine feature with a login and an API per
service. Do not start the second until the first is being used.

## Revised order

1. Verify the folder route with a real mihon download (the three checks above). This is where the
   plan is proved or corrected, and it costs an evening at most.
2. Decide the series-vs-chapter shelf question — it is the one that will feel wrong immediately.
3. Panel-by-panel per the pass above (unchanged; that work stands whatever the library route is).
4. Tracker reporting, one service first — whichever he actually uses.


---

# VERIFIED ON THE DEVICE — Palma 2 Pro over adb, 2026-08-24

He plugged in the Palma with mihon installed. All three unknowns from the addendum are answered,
and every one of them lands the easy way.

| check | answer |
|---|---|
| package | `app.mihon` — plus extension APKs installed alongside it (ReadAllComics, Read Comics Online, batcave, comicskingdom, **and `...extension.all.komga`** — his Komga is already a mihon SOURCE) |
| download location | **`/storage/emulated/0/Books/Mihon/downloads/`** — ORDINARY SHARED STORAGE, not `Android/data`. Pickable by SAF; no sandbox fight |
| saved as CBZ? | **YES.** Real `.cbz` files on disk, e.g. `(2024 – Part 1)_58d1c1.cbz` at ~280 MB. The "Save as CBZ archive" setting is already on |
| layout | `downloads/<Source (LANG)>/<Series>/<Chapter>.cbz` — exactly **three levels** |
| how much | **159 chapter CBZs across 21 series** |

## What this means

`BookshelfSource.list` walks a declared tree to **`maxDepth = 3`**, and its `isBook` extension list
already contains `cbz`. Mihon's layout is three levels. **So pointing the declared tree at
`Books/Mihon/downloads` should surface all 159 chapters on the shelf with no code at all.**

The reading half is therefore a FOLDER PICK, exactly as the addendum guessed. Do that first,
literally by hand on the device, before writing a line — if 159 rows appear and one opens, the
build starts from a working reader.

**And note where it already lives: `/storage/emulated/0/Books/`.** If his Ledger shelf is already
declared at `Books`, the comics may be visible RIGHT NOW, three levels down.

## The one thing that will feel wrong, now with numbers

159 rows named like `(2024 – Part 7)_54b37f.cbz` under 21 series. The hash suffix is mihon's, the
parenthesised year is the chapter's own name, and neither reads as a book. So the series-vs-chapter
question from the addendum is not theoretical — it is 159 rows of noise on first open.

The shelf already has the material to fix it: `Entry.folder` carries the path prefix
(`ReadAllComics (EN)/Uncanny X-Force…`), and `folders()` already groups by it. The design work is
deciding that a FOLDER of CBZs is one shelf entry whose "pages" are its chapters — which is the
same shape the dated note already uses for its pages, and worth stealing rather than inventing.

Cleaning the displayed name (drop the `_<hash>`) is cosmetic and cheap; do it in the same pass.

## Still unknown

**Which tracker he uses.** Mihon keeps that in its own database, which is not readable from here,
and the backup file (`mihon_from_minic.tachibk`) is his to open rather than mine to parse. One
question to him, whenever tracking comes up — and it is the second build, not this one.
