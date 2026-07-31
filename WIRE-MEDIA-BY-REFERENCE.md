# Wire brief — day-JSON media by reference (base64 → `media/<sha256>`)

**Status: wire PINNED 2026-07-31; nothing implemented yet.** This is the brief the 07-31 handoff
called for ("pin the wire first, like the epochs"). It is committed **byte-identically to both
repos** — `toolsboox-correspondence/` and `ledger-ipad-correspondence/` — under the same contract
as `convergence-fixtures/`: any edit lands in both repos in the same sitting.

## Why

Two fields carry base64 image payloads inside the day JSON, and they are the whole bloat problem:

- `imageElements[].data` — every gram, photo, sticker, shape, clipping, AI card, and A/V poster
  frame (Android `da/ImageElement.kt:24`, iOS `Sources/LedgerCore/ImageElement.swift:19`).
- `ledgerItems[].crop` — a pinned gram's face / hand-written task ink (Android
  `da/v2/LedgerItem.kt:34`, iOS `Sources/LedgerCore/LedgerItem.swift:29`).

What that has already cost, in the code's own words: `day-2026-09-11-v2.json` reached 39 MB and
truncated mid-write (unreadable); its 86 MB predecessor OOM-killed the app on every launch because
the Drive background sync fully parses every day file; the pickings picker's memory floor was
~80 MB of UTF-16 base64 held behind an open menu; a one-pixel text-box move re-uploads every photo
in the day. Three slim decoders, a read-only-day guard, truncation guards, and a quarantine path
exist on each platform purely to survive this. `avGrams` never had the problem — they were born
by-reference (`Attachment{id, kind, filename}`, bytes in `attachments/`) — which is the proof the
reference pattern already round-trips through both forks, WebDAV, and Drive.

## The wire (the pinned part)

### New fields

| Model | Field | Type / default | Meaning |
|---|---|---|---|
| `ImageElement` | `dataRef` | `String = ""` | Media-store name of the bytes that used to live in `data` |
| `LedgerItem` | `cropRef` | `String? = null` | Same, for the card face that used to live in `crop` |

Both are plain defaulted fields in the existing additive-tolerance style (string, absent decodes
as empty) — no enum, no structure. Everything else about the element (geometry, provenance,
`gramId`, A/V pointers, `edgeBaked`, …) is untouched.

### The ref format

`<sha256-hex>.<ext>` — 64 lowercase hex chars of the **SHA-256 of the decoded bytes** (not of the
base64 string), dot, extension `png` or `jpg` matching what `LedgerImageCodec` actually encoded.
Example: `9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08.jpg`.

Content-addressing is the point: the same gram placed on ten boards is ONE file; the filename is
its own checksum (a truncated download can never be mistaken for the real thing); files are
immutable by construction, so media sync is pure union with nothing to merge.

### Presence rules

- `data` **remains a required key on the wire** — both decoders hard-require it (iOS
  `ImageElement.swift:179` throws if absent; Android's constructor has no default). An
  externalized element carries `"data": ""` plus a non-empty `dataRef`.
- Writers never emit both a non-empty `data` and a non-empty `dataRef` on one element.
- Reader resolution order, both forks and every downstream consumer:
  1. `data` non-empty → use it (a build that wrote inline meant it; also all pre-migration files).
  2. else `dataRef` non-empty → resolve from the media store; if the file is local, render it;
     if not, render a placeholder and fetch on demand. A missing blob is **never** a reason to
     drop the element, clear the ref, or refuse the day.
  3. else → the element has no face (same as an empty `data` today).
- `crop` keeps its existing dual meaning and its existing resolution order grows one head:
  `cropRef` → try-base64-decode(`crop`) → `crop`-as-attachments-filename (the Android OCR
  legacy). Do not "fix" the dual-typed `crop`; the try-decode fallback stays forever.

### The media store

- **Local:** `<documentsRoot>/media/` on Android (sibling of `attachments/`, via `LedgerPaths`),
  `Documents/media/` on iOS. Writes are temp-file-then-atomic-move, like every hardened store.
- **Remote (WebDAV):** `media/` at the sync root — a sibling of `calendar/` and `attachments/`.
  Upload with the MKCOL-ancestors-on-failure pattern (`9c4e702e`'s lesson: stock Apache 409s a
  PUT into a missing collection and the sidecar indexes silently never synced).
- **Remote (Drive):** folder `media`, app property `{type: media}` — the exact `attachments/`
  pattern in `CalendarGoogleDriveSyncPresenter` / `GoogleDriveSyncProvider`.
- **Sync rule:** push local files the remote lacks, pull remote files the local tree lacks —
  the existing attachment blob pattern. Names are immutable and self-verifying; verify the
  SHA-256 on download and discard mismatches (this replaces the Content-Length guard for media).
- **Ordering:** when a save produced new media, push the blobs **before** the day JSON that
  references them, so no reader anywhere sees a ref whose bytes don't exist remotely yet.

### Writing rules (Phase W only — see phasing)

- **Threshold:** decoded payloads **> 64 KiB go by reference; ≤ 64 KiB may stay inline.** Small
  stickers and shapes cost nothing inline and skip a fetch round-trip on e-ink; photographs and
  card faces are what bloat. One number, both forks, pinned here.
- **New placements** write bytes to the media store and set the ref at creation.
- **Lazy migration on save:** any day being saved anyway externalizes each over-threshold inline
  payload: write the file, set `dataRef`/`cropRef`, set `data`/`crop` to `""`. Rules:
  - `elementId` and the element's `timestamp` are **preserved** — the pixels are identical, and a
    minted timestamp would make the migrated copy "win" merges it has no business winning.
  - Before clearing `data`, backfill `gramId = md5(data)` if `gramId` is empty — that is exactly
    the match key `contentKey`/"where used" would have derived, and it must survive the base64
    leaving the file. (`gramId` stays md5-of-base64 lineage; the media NAME is sha256-of-bytes.
    Two different jobs; do not unify them.)
  - No sweep, no bulk pass: a day untouched forever stays inline forever, and that is fine —
    read-both is permanent.
- **Transforms** (crop, flip, frame, pen-edit) produce new bytes → new hash → new file → update
  the ref. The superseded file is left in place for GC.
- **GC:** local mark-sweep only — a media file referenced by no local day/board and untouched
  for 30 days may be deleted **locally**. Nothing deletes from the remote in v1; the remote is
  the archive (and the future cold tier).

## Phasing — and why it is strict

Both forks **drop unknown JSON fields on re-save** (Swift `CodingKeys` and Moshi generated
adapters alike; `PickingsCards.kt`'s own comment: "a field only Android writes is a field iOS
drops on its next save"). So a pre-migration build that opens and re-saves a ref-bearing day
**silently deletes the `dataRef` — and the image with it, everywhere, via sync**. The
forward-schema protection in the WebDAV merge doesn't help: a ref-bearing v2 file *parses fine*
on an old build. That is the entire reason for the two phases, and for not bumping the filename
to `-v3` (which old Android loaders would simply not open, forking the day).

- **Phase R — read, resolve, sync. No writer emits a ref.** Both forks: decode the new fields,
  carry them through re-save, resolve refs everywhere `data`/`crop` is consumed today (canvas,
  thumbs, kanban, widgets' renderers, PDF export, correspondence, `LedgerWebBridge`/`VisionOcr`
  at their network seams), media-store dirs + WebDAV/Drive media sync, fetch-on-demand for a
  missing blob. The VPS processor is updated in this phase too. Fixtures land (below). Ships to
  **every syncing device** (iPhone, iPad, Boox Go 6, Palma) and TestFlight.
- **Phase W — emit.** A build-time constant (`EMIT_MEDIA_REFS`), flipped only once every device
  in the fleet runs a Phase-R build, turns on ref-writing + lazy migration. If any device must
  roll back past Phase R, flip it off first.

The Watch is unaffected: its pickings/tasks wires (`{"v":1,…}`) carry no images by design.

## The third reader — `process-ledger.py`

The VPS processor composes `imageElements[].data` into page renders and forwards
pickings/gratitude images (`ie.get("data")` at ~741/869/1098–1168). Phase R change: when `data`
is empty and `dataRef` is set, read the bytes from `LEDGER_MEDIA_DIR` (env-overridable, default
sibling of the day tree: `/var/lib/docker/volumes/webdav_webdav_data/_data/data/media`) and
base64 them at the seam; same for `cropRef`. A missing blob logs and skips that image — it must
not error the day (the `processed[name]` success-path contract stays). The devices' WebDAV media
sync is what lands the blobs in that tree; nothing new server-side.

## Merger and fixtures

**`CalendarDayMerger` does not change.** Refs are strings inside elements and ride the existing
rules: `imageElements` id-union with newer-`timestamp`-wins wholesale, `ledgerItems` id-union
first-seen-wins with monotonic `done`. Because migration preserves timestamps, an
inline-vs-ref pair of the same element converges on whichever copy really is newer, and both
faces are the same pixels either way.

**Fixture arms to add — same sitting, both repos, byte-identical** (`convergence-fixtures/`):

1. An element carrying `dataRef` only (`"data": ""`) that survives merge untouched.
2. The same `elementId` inline on one side, ref'd (equal pixels, same timestamp) on the other —
   converges deterministically on the newer *file*'s copy.
3. The same `elementId` where the ref'd copy has the **newer timestamp** (a transform after
   migration) — ref wins wholesale.
4. A `ledgerItems` row with `cropRef` set and `crop: null` — carried first-seen, `done` still
   monotonic.
5. Both suites also assert the new fields survive a decode→encode round trip (the CodecTests /
   RoundTripTest pattern), which is what actually guards against the dropped-field hazard.

## Out of scope (recorded so they aren't re-litigated)

- **Sidecar base64** — `Clipping.data`, `Contact.avatarData`, `BookNote.image`, template
  backgrounds: same disease, small doses, separate pass; clippings should adopt the same media
  store when touched (placement already reuses the original base64 → refs make dedupe automatic).
- **Cold-tier eviction / on-demand-only pulls** — the payoff that motivated content-addressing
  (evict cold media locally, keep it remote, re-fetch on touch). Follow-on; v1 mirrors fully.
- **Remote GC and an R2 mirror** — nothing deletes remotely in v1; `mediaUrl` already exists on
  the element for the A/V R2 story and stays orthogonal.
- **Byte-identity across forks** — unchanged; convergence stays content-level (audit item 11).

## Invariants (test against these)

1. `data` is always present as a key; `""` + non-empty `dataRef` = externalized.
2. Writers never emit both faces; readers prefer inline, then ref, then nothing.
3. Media files are immutable, named by SHA-256 of their bytes, verified on download.
4. Blobs are pushed before the day that references them.
5. Migration never changes `elementId`, never changes element `timestamp`, always backfills
   `gramId` before clearing `data`.
6. A missing blob renders a placeholder — never a dropped element, cleared ref, or failed sync.
7. `crop`'s try-base64-then-filename fallback is permanent; `cropRef` just goes first.
8. No writer emits a ref until every reader in the fleet (both forks + the VPS processor)
   resolves them — the epochs discipline, applied to pixels.
