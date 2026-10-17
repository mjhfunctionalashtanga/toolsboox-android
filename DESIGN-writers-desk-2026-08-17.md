# DESIGN — Writer's Desk & Pickings Improvement (2026-08-17)

A PROPOSAL assembled from Michael's own ink (the 08-17 pages, #PickingsImprovement /
#WriteSurface / #WritersDesk) — his words are the spec; this document arranges them and marks
the open questions. Design docs are not canon; the ink and the commits are.

## What Michael wrote (faithfully)

**Pickings Options (the long-hold):**
> "IDEA: from a long hold press on the canvas, a 'Pickings Options' or similar. Then the
> options could be 'Suggest Pickings', 'Harvest ALL Pickings', 'Select Grams for picking'.
> Suggest uses the LLM to suggest from links and provenance, then the user selects one or
> more, then the LLM autofills a pickings template with grams from the links/provenance —
> also notes, quotes, basket 1, basket 2. A single link or multiple can make up the blanks
> for the template."

> "Missed Connections can feed into this — the LLM can use the RSS feed to create a picking
> when relevant."

> "These become rich in-flow for writing prompts, essay outlines, and synthesis."

**The Writer's Desk (the workflow):**
> "Incoming → Daily → Desk"
> "Create from Pickings, Gram Picks, All Stars."
> "Steps: Identify THESIS → Build OUTLINE / EVIDENCE MARSHALL → SECTION/PAGE BY HAND /
> shell (section outline on top, with support)."

> "This feels like a real shell construction using input from me / curation from me, and then
> shell construction / brief by an assistant — that assistant is the LLM."

> "Pickings/Grams being harvested into pickings IS the evidence marshalling. It's
> automatable, visual, and fun to continue building."

> "Having a Writers Desk can create a workflow home — or we use it as a place to build
> workflow boards with kanban."

> "I can see how each piece of the outline could be selectable/movable onto other pages to
> write each piece of the full. Is there a better way?"

> "Workflow needs a place for easier titling and linking, more intelligent use of provenance
> for connections and useful ideas and emergence. Tags can play a key role in this.
> Presentation matters."

## The shape this describes

One pipeline, three stations, each already half-built in the app:

1. **CATCH (exists):** Incoming → All Stars / Gram Picks — the day's material, starred and
   grabbed, every gram carrying its sourceLink provenance.
2. **MARSHALL (the Pickings Options long-hold):** a board is a THESIS's evidence table.
   - *Select Grams for picking* — manual: multi-select from the day's grams onto a board.
   - *Harvest ALL Pickings* — mechanical: every harvestable gram on this page → board.
   - *Suggest Pickings* — the assistant: LLM reads the page's links + provenance (and, when
     relevant, the feed corpus / Missed Connections pool), proposes candidate boards
     ("these five grams argue about attention"), Michael picks one or more, the LLM fills a
     pickings TEMPLATE: notes · quotes · basket 1 · basket 2 (slots fed by one link or many).
3. **SHELL (the Desk station):** thesis (written or picked) → LLM drafts the outline/brief
   FROM the marshalled board — section outline on top, each section a piece with its
   supporting grams under it — then each outline piece is *movable onto a page* to be
   written by hand. The kanban lens can carry the workflow (a piece's column = its state:
   outline → drafting → written).

The assistant only ever works from what Michael curated — the graph the grams already carry.
Provenance is the raw material ("more intelligent use of provenance for connections"); tags
are the vocabulary; presentation matters.

## RULING (the "Ledger iOS" typed note, later on 08-17)

> "The android considerations for a workflow in daily/desk and what iOS is doing are both
> almost it. The synthesis/shell should lead to a write. Synthesis as the next step post
> evidence marshaling/picking makes sense. Desk as location for missed connections and quick
> wins I get. I can see these as individual paths:
> **today → gratitude → notes**
> **gram picks → pickings → synthesize → write**
> Gram picks as inflow — pickings as evidence marshalling — Synthesize being the shell maker
> and thesis finder?"

So: the Desk is NOT a new station — the pipeline rides the EXISTING surfaces as a walked
path, twin to the daily ritual. Synthesize un-retires as the pipeline's shell/thesis
station (its engines are the LLM seam — question 3 answered). The missing wire is ONE
door: **a synthesis artifact leads to a Write** — "→ Begin the Write" on the synthesis
output mints a named Lines document seeded with the shell (outline on top, supporting
grams' provenance carried). Question 1 (outline pieces → pages) folds into that same
minting move. Also ruled in the same note: the Roots band leaves the iOS today page
(Android's 2026-08-03 retirement, extended).

## Open questions for Michael (the ones the ink itself asks)

- "Each piece of the outline movable onto other pages … **is there a better way?**" —
  candidate answer: a piece IS a named Lines document minted from the outline row (title
  pre-filled, its supporting grams placed at top), so "move onto a page" is one tap and the
  document indexes itself. Ruling wanted.
- Does the Desk live as a fifth Daily station, or inside Desk (the folder), or as a lens
  over Pickings? ("workflow home — OR a place to build workflow boards with kanban")
- Which LLM seam: the existing Ask/Synthesize engines (SynthEngines custom engine per step?)
  or a dedicated flow? The three steps (thesis / outline / brief) map cleanly onto three
  named engines fed by board material.

## Already fixed alongside this doc

- A NAMED note's page-one tag strip no longer wears the whole day's tags — only its own
  ("when starting a new one, it loaded tags at the top from earlier notes during the same
  day"). The day's numbered pages keep the day-index strip, which is what it was designed
  to be.
