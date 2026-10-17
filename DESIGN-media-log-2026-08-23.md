# The media log — examined and specified, 2026-08-23

**Nothing is built by this document.** It is the reading that goes before the build, and it exists
because the last thing Michael said changed what the thing is:

> "we should also have a read history, watch history. and listen history available in the
> log/history section"

> "once we have that media log, I would be able to have the llm/ask make slides from the article,
> or create critical thinking questions, or create pictures, or create a mind map, etc etc
> (article, video, podcast...)"

The second sentence is the design. A history is a list you look at. What he is describing is a
**corpus you act on** — and that is a different thing to build, with a different centre of gravity.

---

## THE FINDING: three of the four pieces already exist

The instinct on hearing "media log with LLM verbs" is that this is a large feature. It is not,
because everything expensive is already on disk and already working:

| piece | state | where |
|---|---|---|
| **the verbs** | ✅ built, and already material-parameterised | `SynthEngines` — 13 engines (3 Questions · Counterargument · Distill · Patterns · Connections · Essay Outline · Class Plan · Reading Path · Next Actions · Aphorisms · Writing Prompt · Sermon Sketch · Letter Draft) **plus user-defined custom engines** (`upsert`). `runEngine(engine, material: String)` takes PLAIN TEXT and does not care where it came from |
| **the material — articles** | ✅ cached | `FeedCache.saveContent(id, html)` — the readable copy, now fetched eagerly by the fetch cycle |
| **the material — podcasts & video** | ✅ cached | `FeedCache.saveTranscript(id, text)` — normalised "[m:ss] line" text; made EAGER on the keep-offline ask today (1.07.27) |
| **the record of what you consumed** | ❌ **the only gap** | `ListenState` covers listening (position, done, merged, synced). Nothing durable records that you READ an article or WATCHED a video |

So the work is not "build a media log with AI features." It is: **record the consumption, and open a
door from a record to `runEngine` with the cached text as material.** The verbs he named — slides,
critical-thinking questions, mind map — are one custom engine each, and he can already write those
himself; "3 Questions" and "Counterargument" are the critical-thinking ones, shipped.

---

## WHAT IS ACTUALLY MISSING

### 1. `MediaLog` — a synced record of consumption
A sidecar in the shape the fleet already uses (`ListenState`, `NotePageTemplates`, `Connection`):

```
id        the entry URL — THE SAME IDENTITY ListenState merges on, deliberately
kind      "read" | "watch" | "listen"
title     what it was called
source    the feed / show / channel
at        when you last engaged with it
done      finished (monotonic through merge, the done-is-monotonic rule)
updated   newest write wins on everything else
deletedAt tombstone, so a delete survives an id-union merge
```

**It does NOT hold the material.** The text is already in `FeedCache` keyed by the same entry id, and
copying it here would mean two truths about one article and a sidecar that grows without bound.
The log POINTS; the cache HOLDS.

**Why not extend `ListenState` instead:** two different jobs. `ListenState` is RESUMABLE STATE —
where the playhead is, so you can carry on. `MediaLog` is HISTORY — that you did this, and when.
One is overwritten constantly and means nothing once you have finished; the other is the record.
Folding them would make "what did I read in July" a question about playhead positions.

### 2. Recording, at the honest moments
- **read** — an article opened and actually read. (Not "opened": the pane opens on arrow-key
  paging. The existing `markReadMode()` already draws this line and should be reused rather than a
  second opinion invented.)
- **listen** — playback started; `ListenState` already knows this and can drive it.
- **watch** — the embed played.

### 3. The door: a logged row → `runEngine`
The row's material resolves in one function: `FeedCache.loadTranscript(id) ?: loadContent(id) ?:
entry.content`. Transcript first — for a podcast or video it IS the article. Then the ⚗ engine
picker that Synthesize already opens, unchanged, and the result lands where synthesis results
already land.

### 4. Where it lives
"The log/history section." NOTE: `LedgerHistory` is a **30-place NAVIGATION ring** (where you went,
collapsed by destination) — a different thing wearing a similar word, and the media log must not be
bolted onto it. It is its own surface, and it wants date grouping and a kind filter, because "what
did I read this week" and "what have I been listening to" are the two questions it exists to answer.

---

## THE ORDER TO BUILD IT

1. `MediaLog` record + merge + pinned tests, ONE COMMIT PAIR across both forks (it is wire).
2. Recording on the three moments, Android first.
3. The surface, with kind filter and date grouping.
4. The ⚗ door from a row — which is the point of all of it, and is small once 1–3 stand.
5. iOS twin of the surface.

**Do not start at 4.** A verb over a corpus that is not being recorded yet has nothing to act on,
and the temptation is real because 4 is the interesting part and nearly free.

---

## ONE THING TO DECIDE BEFORE BUILDING

Does the log record **everything** consumed, or only what you **kept**? Recording everything makes
"what did I read in July" true and complete, and makes the log grow with every arrow-key page turn.
Recording only what you kept or starred makes it a shelf rather than a history — smaller, more
deliberate, and it silently loses the article you read once and want to find again, which is
precisely the thing a history is for.

**Recommendation: record everything, prune by age.** The failure mode of the first is a long list;
the failure mode of the second is a list that does not contain the thing you are looking for.
