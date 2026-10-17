# The stores, examined — 2026-08-23

**This document changes nothing.** It is the EXAMINATION that ruling #2 of
`DESIGN-gather-verbs-2026-08-23-DRAFT.md` asked for, and the standing instruction on it is
"migration never improvised." What follows is what the code actually does today on both
forks, what the real differences are (they are not where they look), how far the CPT
target already is from here, and what a first migration step would have to cost. No key is
rekeyed, no index is moved, nothing is deprecated by this file.

The ruling it answers to, his words:

> "we should look at how the stores is done, because gram picks and pickings are just
> surfaces for grams and strokes like notes. but with a different template and the big diff
> is that for ease of use maybe we get diff doors for transformation, but ultimately, they
> are the same."

And the addendum that sets the target:

> "grams, they really are just like a CPT, just like notes can be … Notes can be a cpt,
> gram can be a cpt, grams and notes can be related, etc. just like mjh.yoga/notes they
> have a way they can be presented of course but we keep the strokes too, right?"

---

## 1. THE FINDING, FIRST

**He is right, and more literally than the phrasing suggests. At the CONTENT layer the
convergence he is describing already happened — years ago, by accident of the day file's
shape. There is nothing to converge there.**

Every page of every surface — a Pickings board, a Gram Picks page, a numeric note, a named
Write document, the daily Doodle, the Gratitude station — stores its material in exactly
one place and in exactly one shape:

| what | where it lives |
|---|---|
| strokes | `CalendarDay.noteStrokes[pageKey]` |
| grams (images) | `CalendarDay.imageElements` where `.page == pageKey` |
| typed text | `CalendarDay.textElements` where `.pageKey == pageKey` |

That is the whole storage model, identical on both forks, byte-compatible on the wire.
**A page IS a key. A surface IS a key convention plus a template.** Nothing else in the
system distinguishes a pickings board from a note page.

So the question is not "how do we make these the same substance." They are the same
substance. The question the examination actually has to answer is narrower and more
awkward: **why does the code need FOUR different mechanisms to answer "which pages exist
and what are they called?"** — and that is where the divergence really lives.

---

## 2. WHAT EXISTS TODAY — the index layer, both forks

The content is one model. The INDEX over that content is four:

### (a) `LedgerDocumentStore` — Write · Synthesize · Grid · Jot
One file per surface, `<dir>/pages.json`, WebDAV-synced, entries identified `"key|date"`,
newer-`at`-wins merge, with tombstones (`LedgerDocumentTombstones`). Daily key = the family
name (`write`, `grid`, `sketch`, `synthesize`); named documents minted `<key>-<millis>`.
Present and matched on both forks (Kotlin `LedgerDocumentStore`, Swift `LedgerDocumentStore`
in `ElementsView.swift`).

Note the honest asymmetry already recorded inside it: `dailyIsNameable` is true for Write /
Grid / Jot and FALSE for Synthesize, because Synthesize's index is keyed by key alone and
holds real topic data — widening it would be a migration, so it wasn't done. That is not a
wart to clean up; it is a worked example of the discipline this document is written under.

### (b) `PickingsStore` — Pickings boards
A DIFFERENT shape for the same job: one file **per day**, `pickings-index/<date>.json`,
entries `{key, name}`, no tombstones, and the default board is SYNTHESIZED when absent
(`list` invents it, `listSaved` deliberately does not — the daily cover leans on that
difference so a silent day doesn't look like fourteen boards).

### (c) No index at all — Gram Picks, the Doodle, the stations
`grampicks`, `doodle`, `intake`, `gratitude`, `selfexec`: the key IS the existence. Nothing
records that they exist; the renderer knows their names and the walk knows to include or
exclude them. `DatedNote.families` is the list, hand-written.

### (d) `NotePageTemplates` / `NotePageTemplate` — the presentation index
`page-templates/pages.json`, `"key|date"`, newer-wins. The newest of the four, and the only
one designed as a PROPERTY OF A PAGE rather than a registry of a surface.

**Four mechanisms, one question.** That is the actual convergence work, and it is index
work, not content work.

---

## 3. THE SEAMS THAT WILL BITE ANY MIGRATION

Written down now so the migration pass doesn't rediscover them at cost.

1. **The sub-page spelling.** Android writes `write#1`; iOS writes `write-1`. Both spellings
   ride real days. `DatedNote.split` on both forks now reads both (08-23), and the
   discriminator is the tail length: a dash tail of TEN OR MORE digits is a minted document
   (`write-1785354515034`), fewer is a sub-page. **Any unified key space inherits this.** It
   cannot be normalized away without rewriting stored keys, which is item 2 below.

2. **Rekeying is the tripwire.** The convergence fixtures are byte-identical day files. Any
   change that renames a key in `noteStrokes` invalidates them and, more to the point,
   invalidates Michael's existing ink. This is why the Notes IA unification was done as a
   WALK over existing keys rather than a re-map, and why the template became a per-page
   PROPERTY rather than a new store. **Both precedents point the same way: add a property,
   never move a key.**

3. **Type is PARSED, not RECORDED.** Today "what kind of page is this?" is answered by
   string-prefix inspection — `isPickings(key)`, `isMine(key)`, `base == GRAM_PICKS`,
   `families.contains(base)` — scattered across ~40 call sites on Android alone (counted
   08-23 during the sub-page-spelling port). Every one of them is a small parser of the same
   convention, and they do not all agree.

4. **~40 call sites reach for `substringBefore('#')` directly.** Most concern named-document
   keys where dash-splitting would be actively WRONG. This is an examination one site at a
   time, never a find-and-replace, and it is a PREREQUISITE for any key-space change rather
   than a consequence of one.

---

## 4. THE CPT TARGET — how far is it, honestly

His target shape is an object with: a TYPE, an IDENTITY, a PRESENTATION, RELATIONS, and the
RAW MATERIAL kept. Scored against what stands today:

| pillar | state | evidence |
|---|---|---|
| **raw material kept** | ✅ **done** | strokes always ride the day file under the key; every export/render path reads them, none replace them |
| **presentation** | ✅ **done** | `NotePageTemplates` / `NotePageTemplate` — the template is a per-page property with a newer-wins merge and a pinned resolve ladder, on both forks. Gratitude joined the picker 08-23 |
| **identity** | 🟡 **works, unstated** | the page key (+ date for daily keys) is a real identity and merges correctly — but it is a STRING CONVENTION, not a declared one |
| **type** | ❌ **absent** | parsed from the key prefix at ~40 sites; never recorded, so it cannot be queried, migrated, or disagreed with safely |
| **relations** | ❌ **absent between pages** | `Rhizome` relates WORDS across objects (an inverted index over recurring terms — deliberately modest, "not a topic model"). `LedgerLinks` relates `[[wiki links]]`, `LedgerTags` relates `#tags`. **Nothing relates a PAGE to a PAGE as objects.** Gram→board provenance is the closest thing and it is a field, not an edge |

**Three of five pillars already stand.** That is the real news of this examination, and it
changes what the migration is: not a rebuild, but two additions.

---

## 5. WHAT A FIRST STEP WOULD COST — options, with prices

### Option A — Record the type. (Recommended as the FIRST and ONLY next step.)
Add a `type` property to the page-templates sidecar (or a twin of it), exactly as `template`
was added: additive, tolerant-decode, newer-wins, `"key|date"`. Backfill is not required —
an absent type falls back to the parse that runs today, so nothing breaks on day one and the
parse becomes the DEFAULT rather than the mechanism.

- Cost: one sidecar field, one resolver, two pinned tests, per fork.
- Rekeys nothing. Fixtures untouched. Fully reversible.
- Buys: the ~40 parse sites gain a single answer to ask, and can be converted one at a time
  under their own tests instead of all at once.
- **This is the only step that makes the others safe**, because every later move needs to ask
  "what kind of thing is this?" without guessing from a string.

### Option B — Converge the indexes onto one store.
Fold `PickingsStore`'s per-day files and the un-indexed keys into `LedgerDocumentStore`'s
shape.
- Cost: a real migration of Michael's board names, on both forks, with a tombstone story and
  a per-day→single-file merge that has to survive two devices disagreeing mid-flight.
- Buys: one mechanism instead of four.
- **Not next.** It touches live names for a gain that is structural rather than felt, and the
  Synthesize precedent is the standing warning about exactly this trade.

### Option C — Page-to-page relations (the CPT half he is actually reaching for).
An edge store: `(fromKey, fromDate) → (toKey, toDate, kind)`.
- Cost: a new sidecar, a merge rule, and — the expensive part — the DOORS, because a relation
  nobody can make or see is not a feature.
- **Blocked on A.** An edge between two things whose types are parsed from their names is an
  edge you cannot validate.

### Option D — Do nothing structural; keep adding doors.
Legitimate, and worth stating: the content model is already right, and the four index
mechanisms cost the USER nothing today. The price of D is paid only by the next feature that
needs to ask what kind of page it is holding.

---

## 6. THE RECOMMENDATION

**A, alone, and not yet.** Record the type as a property; leave every key, index and file
where it is; convert the parse sites afterwards at leisure and under test. Then re-examine —
because with type recorded, Option C gets cheap and Option B may turn out never to be worth
doing at all, which would be the best outcome available.

What must NOT happen, in order of how much it would cost him:

1. **No rekeying of `noteStrokes`.** Not for tidiness, not for symmetry, not to unify the two
   sub-page spellings. His ink is addressed by those keys.
2. **No find-and-replace over the ~40 parse sites.** Named-document keys and sub-page keys
   look alike and mean opposite things.
3. **No index migration before type is recorded.** Moving board names around while "what is a
   board" is still a string prefix is the improvisation the ruling forbade.
4. **Synthesize stays as it is** until someone has a reason better than symmetry.

---

## 7. WHAT THIS EXAMINATION DID NOT COVER

Named honestly, so the next pass knows its own edges:

- **The web half.** mjh.yoga/notes is the working CPT precedent and the addendum names it as
  the model, but this examination read only the two forks. How a page-object would round-trip
  to WordPress is unexamined.
- **Gram provenance as an edge.** `graduatedTo` / `sourceLink` are proto-relations already in
  the wire; whether they become edges under Option C or stay fields was not decided.
- **Synthesize's identity.** Ruling #3 asked whether Synthesize should stay a station page or
  become "just a template like pickings." Under this examination the answer looks like "it
  already is a template plus engines plus a door" — but its index is the one keyed by key
  alone, so the question is entangled with Option B and is deliberately left open.
