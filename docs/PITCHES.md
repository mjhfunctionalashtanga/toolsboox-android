# Ledger — the pitch, by who's listening

One product, six doors. The through-line: **you write by hand, and what you write stays
yours.** Ledger is a paper planner that happens to be software, on your devices and your
server.

---

## 1. WordPress + Fluent people

**"Your site already is the backend. Ledger is its handwriting."**

You've got FluentCRM, FluentBoards, FluentCommunity. You've paid for them, you know
them, and your data is already in your own database. What you don't have is a way to
*write* into it that feels like anything but a form.

Install one plugin. Now a handwritten card on an e-ink tablet becomes a FluentBoards
task with your actual strokes as the cover. A note you write about a client lands on
their FluentCRM timeline — with the handwriting attached — applies a tag, and fires an
action hook your automation is already listening to. Replies to what you post come back
into a Correspondence inbox where you answer them *by hand*, and that reply can be saved
back into your own ledger as an object you can rework later.

No SaaS in the middle, no per-seat pricing, no export request. It's your WordPress.

> Best first demo: handwrite a note → watch it appear on a contact's timeline → watch
> your existing tag automation fire.

---

## 2. The WordPress builder / open-source case

**"A first-class native client for the Fluent stack — that you can fork."**

The interesting part for builders isn't the planner; it's the bridge. `ledgr-fb-bridge`
is a single, readable PHP plugin exposing `/wp-json/ledgr/v1/*`, authenticated with
ordinary application passwords, with permissions split between staff and members. The
clients (Android/Kotlin for Boox, SwiftUI for iPad) are ordinary apps talking to it.

That means: if you build for WordPress clients, here is a worked example of a native,
offline-first, handwriting app that treats WordPress as the system of record — including
a merge contract that lets two devices edit the same day file without clobbering each
other, and a stroke format that round-trips across platforms.

Fork it, white-label it, point it at your own post types. The day files are plain JSON.
The bridge is 2,000 lines you can read in an afternoon.

---

## 3. The e-ink community

**"Written for the panel, not ported to it."**

If you own a Boox, you know the tax: apps designed for OLED that ghost, smear, and
animate. Ledger was built on e-ink first and it shows in the details —

- Solid black strokes on white. No shadows, no gradients, no alpha that dithers to mud.
- Full GC refreshes at the moments that actually smear (drag-end, page turn).
- Pen-up is O(one stroke), not O(page) — the ANR that made long writing sessions freeze
  is gone, and the fix is documented.
- The stylus pipeline honors exclude-rects so floating controls don't eat your ink.
- Volume-key page turns, mono glyph sets, instant paging.

And the same ledger opens on an iPad with an Apple Pencil, syncing page for page — so
the e-ink device can be the *writing* device without being the only device.

---

## 4. Digital garden people

**"Rhizome, not hierarchy — and the edges run both ways."**

Ledger doesn't file your thinking into folders. It grows edges. A quote you keep becomes
a Picking. A Picking becomes part of a Synthesize page. That becomes an essay you post.
Someone replies. The reply lands in Correspondence — where you can answer it in your own
handwriting *and save that reply back into your garden as an object*, carrying provenance
that points at the thread it came from.

Every gram remembers where it came from and everywhere it's been used ("Where used…" is
a real button). Nothing is a dead end; you can always walk back to the source, or forward
to what grew out of it.

It publishes to a real, self-hosted, public place — your WordPress — instead of a walled
lawn.

---

## 5. PKM people

**"Handwriting-first capture, with retrieval that actually reads your handwriting."**

Everything you write is searchable: on-device OCR on the pages, structured extraction for
tasks, transcripts for voice and video notes. **Ask my Ledger** is RAG over your *own*
corpus — day pages, notes, pickings, book highlights, feed articles, A/V transcripts,
tasks — using hybrid retrieval (embeddings blended with keyword and recency), and it can
create tasks and notes back into the ledger rather than just talking about them.

The capture surfaces are the point though: lasso a scrawl → task. Photograph a napkin →
object plus text. Star an article → it lands in your daily Log with the passage. The
quality gates are deliberate — a vision model that can't read your handwriting is made to
say so instead of inventing a plausible task, and nothing files without you seeing the
reading first.

Storage is plain JSON, one file per day, synced over WebDAV. No proprietary vault format,
no lock-in, full export.

---

## 6. RSS people

**"The reader that's also the notebook."**

A complete Miniflux client — or bring no server at all and subscribe by URL / OPML
import. The Read, The Listen, The Watch. Smart feeds. Full-text search. Offline caching
at file time, not fetch time. Podcast playback with a player that follows you around the
app. Volume-key page turning. Reader settings shared with the book reader, so articles
and EPUBs read the same way.

The difference: what you do *after* reading. Highlight a passage and it lands in your
day's Log. Send an article to a Picking board — title, blurb, author, site, featured
image, link home — and start writing around it. File a link for later and it's cached,
published to your own RSS feed, and mirrored back so any other reader you use can see it
too.

Reading feeds writing instead of replacing it.

---

## 7. Cozy tech people

**"Software that feels like paper someone loved."**

Grams get taped into the page like photos in a scrapbook — a white mat, a hard rule, two
bits of tape. Pages have real margins and a title. Your handwriting stays handwriting:
when you write an event on the schedule, the app reads the title but *keeps your
writing* as the event's picture. Nothing is flattened into a database row and thrown
away.

It's quiet, too. No badges. No unread counts anywhere. No streaks, no nagging, no
engagement loop. It doesn't phone home; if you never configure a server, it never makes
a network call you didn't ask for.

Small, warm, yours, and slightly imperfect on purpose.

---

## The one-liner, if you only get one

**Ledger is a handwritten planner, reader, and notebook that syncs across e-ink and iPad
and publishes to your own WordPress. Your handwriting stays handwriting. Your data stays
in plain files you own.**
