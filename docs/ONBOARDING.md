# Getting started with Ledger

Ledger works completely offline the moment you install it. Everything below about
servers is optional, and you can add it later.

---

## Sixty seconds

1. **Open Today.** Write on it. That's the whole product — the rest is scaffolding.
2. **Pick your input mode** with the ink button in the top bar: ⃠ nothing draws ·
   ✏️ pen draws · ☝️ finger draws. One at a time, so a resting hand never leaves a mark.
3. **Swipe** left/right with two fingers to change the day, up/down to change the page.
   Pinch to zoom.
4. **Hold a time** on the schedule to write an event right where it belongs.
5. **Tap the ☀️** any time to come back to today; the ✎ beside it jumps to your notes.

## The daily loop

The Daily menu runs in the order a day actually goes:

**Intake → Pickings → Gratitude → Synthesize → Write**

Intake is what came in. Pickings is what you kept. Gratitude is what you noticed.
Synthesize presses it together. Write is what comes out. You don't have to use all five
— but if you're wondering where something goes, that's the order to think in.

## Making things

- **Lasso** anything you wrote → make it a task, an event, a gram, or search your ledger
  for it.
- **Hold an empty spot** on a page → add text, a photo, a clipping, or paste.
- **Hold a gram** → the menu leads with what you want: arrange it, see where it came
  from, share it out, or file it deeper in.
- **📷 Capture** (hold the pen button) → photograph handwriting and keep it as an object;
  extract the text if you want it searchable.

## Reading

Ledger reads RSS and books. You have three ways in, in order of effort:

1. **Local feeds** — paste any feed URL, or import an OPML file. No server, no account.
2. **Miniflux** — if you self-host it, add the URL and a token in Feed settings and your
   whole subscription list arrives, grouped into The Read / The Listen / The Watch.
3. **Books** — import EPUB/PDF/CBZ from Files, or browse an OPDS catalog (Calibre-Web
   and friends).

Anything you file for later is cached offline at the moment you file it.

## Syncing your devices

Ledger stores plain JSON, one file per day. To share it between a Boox and an iPad:

- **WebDAV** (recommended — any server, no OAuth): enter URL, user, password in
  Settings → Sync on both devices, pointed at the same folder.
- **Google Drive**: sign in on both devices.

Both devices merge rather than overwrite: newer edits win per item, deletions are
tombstoned, and neither device can wipe the other's work.

## The networked layer (optional)

The social half of Ledger — Correspondence, Community, Site Boards, Messages — talks to
**your own WordPress**, not to us. You need WordPress with the Fluent plugins
(FluentCommunity / FluentBoards / FluentCRM as you like) and the `ledgr-fb-bridge`
plugin installed. Then in Settings → Community & Boards enter your site URL, your
WordPress username, and an application password.

Without it, everything else still works — those surfaces just stay quiet.

## Settings live in one place

**Settings → Ledger Settings** holds every connection and preference: sync, web bridge,
feeds, bookshelf, AI keys, OCR level, appearance, text sizes.

- **Export settings** writes one JSON file (the same schema on Boox and iPad, so you can
  set up a second device by importing it).
- **Back up ledger** writes your *entire* ledger — every page, contact, board, clipping
  and setting — as a dated folder you keep wherever you like. **Restore** merges it back.

Nothing phones home. If you never configure a server, Ledger never makes a network call
except the ones you ask for.
