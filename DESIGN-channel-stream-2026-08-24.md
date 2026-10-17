# The channel stream — a design pass, 2026-08-24

**Nothing is built by this document.** His ask, mid-walk:

> "a daily organizer framework for creating emergent flows through creativity using a channel
> stream that filters organically for the user and expands out strongly for the power user.
> I'm thinking that as an option could be good for a user — and one day make my current
> syncthing network less necessary."

Two ideas arrived in one sentence and they are different sizes. This pass separates them.

---

## 1. WHAT LEDGER ALREADY IS, read honestly

The app already has every INGREDIENT the sentence names:

| ingredient | where it lives today |
|---|---|
| the daily organizer | the Day page — the one surface everything already lands on |
| things that flow IN | feeds, mail, books, comics, Bluesky, tasks, calendar, shared links, grams |
| the reader's own signal | stars, marks, notes, grams, Asks, reading events — every one timestamped onto a day |
| filtering | MichaelFilter tiers on RSS; the Bee surfacers; the digests |
| expansion for the power user | the hub's accordion, the corpus search, the rhizome links |

What it does NOT have is a single STREAM: one channel-shaped surface where the day's incoming
flow arrives already filtered, and where drilling in is expansion rather than app-hopping.
Today each lane (feeds / mail / shelf / journal) is its own room. The stream idea is one hall.

## 2. THE SHAPE: channels over the day, not beside it

The wrong version of this feature is a second inbox. The right version is a LENS over what
the day already collects:

- **A channel is a stated interest**, not a folder: "ashtanga", "comics I'm reading",
  "people I correspond with", "tools". Channels are FEW (a handful), user-named, and each is
  a filter expression over the one corpus (feed items + mail + marks + grams + journal).
- **The stream is the day's flow through those channels** — a single chronological surface,
  grouped by channel, quiet by default: a channel with nothing new today simply isn't there.
- **Organic filtering** = the reader's own acts are the training signal. A star, a gram, a
  "read it whole", a hold — these already exist and already sync. The filter learns from
  what the hands do, never from a settings page. (MichaelFilter already proves this pattern
  on RSS; the stream generalises the tiers across sources.)
- **Expands strongly** = every row opens INTO its full object (the article, the thread, the
  book, the day page it landed on) and every object carries its rhizome links outward. The
  casual reader sees six calm rows; the power user can walk from any row to the whole graph.
- **Opt-in, a surface not a takeover**: a door on the hub beside the Day page. The Day page
  remains the home. Low-distraction is the default posture; the stream is where flow is
  WANTED, not imposed.

### What this is NOT
- Not an algorithmic feed of other people's engagement signals — the only signal is his own.
- Not a new store — it reads the corpus that exists; it writes nothing but reads/positions.
- Not notifications.

## 3. THE HONEST FIRST BUILD (when its turn comes)

1. **Channel = saved filter** over the existing corpus index (keywords + sources + kinds),
   stored as a small JSON the fleet syncs like any sidecar. No ML to start — the MF tiers
   and plain matching are already good.
2. **One stream surface**: today's (and yesterday's) matches, grouped by channel, e-ink-flat,
   rows open the real object. Reuses the feed row / mail row / shelf row renderers.
3. **The organic part, v1**: acts already recorded (star, gram, open, dwell via reading
   events) bump a channel's sources; nothing new to capture.
4. Later, if wanted: the LLM lane (Ask key already in settings) proposes channel membership
   for ambiguous items — the MichaelFilter pattern, per channel.

Cost of v1: one new fragment + one saved-filter store + queries over the existing corpus.
No new sync machinery. Fits the "surfaces that guess vs surfaces you ask" thesis: the stream
GUESSES quietly, and every guess is one tap from the real thing.

## 4. THE OTHER HALF OF THE SENTENCE: "make Syncthing less necessary"

Different project, and it is ALREADY UNDERWAY as of 1.07.49-04: the library hub now carries
declared-tree bytes behind the "☁ Hub carries folder books" gate. That is the pattern that
retires Syncthing lane by lane:

| Syncthing carries today | hub replacement | state |
|---|---|---|
| day files / sidecars | WebDAV sidecar sync | DONE, years in |
| books (ebooks folder) | library hub bytes | DONE for app-dir; declared-tree now gated ON |
| comics (Books/Mihon) | same gate | SHIPPED -04, needs his toggle + field verify |
| attachments/blobs | hub attachments dir | partial (remote attachments exist) |
| notebooks / raw ink | ultrabridge mirror | DONE |

So "less necessary" is not a new system — it is finishing the inventory above, then measuring
for a month. The B6 (not yet in the Syncthing mesh) is the perfect test subject: if the B6
never needs Syncthing, the claim is proved on real hardware.

## 5. ORDER

The stream is a REAL feature and a good one, but it queues behind the comics walk being
field-solid and the annotation loop being loved — his stated priority order. When it comes up:
design detail pass first (channel vocabulary, the row grammar, what "quiet" means per channel),
then v1 as §3. The Syncthing-retirement half needs no design — only the gate toggled and the
month of evidence.
