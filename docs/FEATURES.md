# Ledger — what it does

A paper planner that happens to be software. You write by hand; everything you write
stays yours, on your own devices and (if you want) your own server. Boox e-ink and
iPad/iPhone run the same Ledger and sync to each other page for page.

---

## The daily loop

**Intake → Pickings → Gratitude → Synthesize → Write.** Five surfaces in the order you
actually move through a day, with a ⚡ "Next step" shortcut mid-flow.

- **Today** — a real day page: schedule on the left, tasks on the right, notes below.
  Overlapping events sit side by side, not stacked. Hold a time and *write* the event
  there: your handwriting sets the title and is kept as the event's picture.
- **Intake / For Later** — links and lookups you filed to read, watch, or listen to.
  Everything is cached for offline reading the moment you file it.
- **Pickings** — boards where quotes, clippings, grams and scraps collect. Multiple
  named boards per day.
- **Gratitude** — three grateful things, the best thing, and a doodle.
- **Synthesize** — a dot-grid whiteboard where the day's material gets pressed through
  an engine (three questions, a writing prompt, an essay outline — or your own).
- **Write** — a paginated ruled sheet for the long-form that comes out the other side.
- **Notes** — free numeric pages (0, 1, 2…), always reopening where you left off.

## Ink

Apple Pencil or Boox stylus, with palm rejection. One input mode at a time — nothing
draws, the pen draws, or your finger draws — so a stray touch is never a stray mark.
Pinch to zoom, two-finger drag to pan, undo/redo, lasso, scribble-to-erase.

**Lasso anything you've written** and turn it into a task, an event, a gram, a lookup,
or a search of your own ledger.

## Grams — the visual unit

A gram is any picture on a page: a photo, a clipping, a rendered quote card, a piece of
your own handwriting. Grams can be moved, resized, cropped, rotated, flipped, inverted,
turned to line art, and drawn on with the pen. Everything they touch keeps **provenance**
— where it came from and where it's been used — so you can always walk back to the source.

Select several at once (writing and pictures together) to move, copy, delete, or send
them somewhere as a group.

## Capture

- **📷 Capture** — photograph handwritten anything (a letter, a recipe, directions on a
  napkin). It lands as a Ledger object, and the text can be read out of it and filed as
  a note or a task — you see the reading before anything files.
- **Share into Ledger** — send a screenshot or a link from any app: it becomes a gram, a
  Later List item, or a task card.
- **A/V Grams** — record audio or video into the day; transcripts join the search index.

## Reading

- **Feed Ledger** — a full RSS reader over Miniflux, or local feeds with no server at
  all. The Read / The Listen / The Watch, a Later List, smart feeds, offline caching,
  and full-text search.
- **Bookshelf** — a real EPUB/PDF/CBZ reader (foliate-js), OPDS catalog browsing,
  highlights, bookmarks, table of contents, and reading settings shared with the feed
  reader so both read the same way.
- **Read aloud** — on-device voices or cloud TTS, with a player that follows you around
  the app.
- Highlight, annotate, or send a whole article straight to a Picking — title, blurb,
  author, site, featured image, and a link home.

## The Log (zettelkasten)

Everything you star, highlight, note, capture or reply to lands in one timeline you can
filter by kind, by star, and by day/week/month/quarter/year. From any entry you can walk
to its source, pull the thread, or drop it into a synthesis basket.

## Ask my Ledger

Chat grounded in **your own corpus** — day pages, notes, pickings, book highlights, feed
articles, A/V transcripts, tasks and events. Hybrid retrieval (embeddings + keyword +
recency). It can also create tasks, events and notes for you, and save its answers back
into the Ledger as a feed you can annotate.

## Tasks, Boards, Correspondence

- **Tasks & Events** — OCR'd off your day pages or typed, with due dates, times,
  contacts, and CalDAV/Reminders/Calendar sync.
- **Boards** — one kanban, two sources: **Local** (your on-device cards) and **Site**
  (real FluentBoards on your WordPress). Dated cards can fold into your timeline.
- **Correspondence** — replies to what you've shared, gathered in one place. Reply by
  handwriting or in markdown, attach a Gram, a Picking or a Log entry with its
  provenance intact, and optionally **save the reply back into your own Ledger** so a
  comment becomes a first-class object you can rework.
- **Messages & Community** — group chat and space feeds from FluentCommunity, with
  handwritten replies.

## Your data

- Plain JSON, one file per day, on your device.
- Sync by **WebDAV** (any server) or **Google Drive** — the Boox and the iPad read and
  write the same files, merged safely, never clobbering each other.
- **Everything exports**: settings as one JSON, and the whole ledger — every page,
  contact, board, clipping and setting — as a dated folder you can keep anywhere.
- No account required. No telemetry. The networked features are opt-in and point at
  *your* server.
