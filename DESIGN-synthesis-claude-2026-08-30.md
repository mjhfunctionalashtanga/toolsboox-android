# DESIGN — Synthesis reborn: the AI-collaborative handwriting notebook (2026-08-30)

Michael's ruling: the app in the r/ClaudeAI post ("I built a handwriting notebook app where
Claude writes back and it's the most fun I've had learning in years", u/tokentrillionaire,
app name **Penombra**, ~4.4k upvotes) "does what I had wanted Ledgable to be able to do.
This would be the synthesis section I intended." This document is the design for that rebirth.
Investigation + design only; no implementation rides with it.

Revised same day on Michael's redlines: the summon verb is **Synth** (one word,
everywhere); the correspondence takes the **incoming flow** — feed articles, mail
letters, and picks, through one generic door (“Picks can be synthed. No need to make
it special.”) — as input (§5a); **ordinary notes are full Synth surfaces** with
one-at-a-time or multi gram selection (§5); and Ask gains an **ambient paper surface**
whose sessions are **stored like chats but ARE pages** (§7). Approved ("Great"), with
the closing ruling that replies wear **pick gram aesthetics** and are first-class picks
(§4).

## 1. What the reference app actually does

Penombra runs on a Daylight DC-1 (any Android tablet with stylus; iPad port in progress).
The loop, from the post and the mod-bot's thread digest:

- You create a note and start **exploring a topic in handwriting**. Claude **writes back on
  the same page** and explores the ideas with you — a correspondence, not a chat panel.
- **Delta discipline:** the app sends only the **vector data of NEW strokes** since the last
  turn — not page re-renders — which is why it isn't a token guzzler.
- **The model decides what to say and roughly where to put it; the APP renders the reply**
  in its own consistent style (commenters asked "what font is that?" — the machine's voice
  is typed/rendered, not fake handwriting). Drawings come from a **small vocabulary of
  semantic primitives**, never arbitrary vectors — a commenter singled this discipline out
  as "one of the most persuasive implementations of generative UI" precisely because it
  keeps a coherent style.
- **Reading feeds it:** read a PDF/ebook, mark a passage, and Claude "jumps in and creates
  a note to what I have marked and we can discuss like I am talking to a professor." (Our
  version of this mechanic runs over MORE rails than Penombra has: the Reader, the feeds
  lenses, and the mail — §5a.)
- **It can quiz you** on the material if you ask — called the sleeper feature in the thread
  ("making it ask you to recall the material is the part that would actually make the
  learning stick").

What the thread loved: Tom Riddle's diary / the *Diamond Age* Illustrated Primer — paper
that talks back; the token discipline; the consistent rendered voice; the quiz. Nothing in
it that Ledgable's rails don't already have a stronger version of — except the loop itself.

## 2. The rails we already own (verified in code, this repo)

Every part of Penombra's machinery exists here already, built better and pointed at a
richer corpus. Nothing below needs to be invented; the design only wires it into a loop.

- **The model + keys:** `plugin/chat/nw/AiCreds.kt` (Anthropic `claude-sonnet-5` default /
  OpenAI, EncryptedSharedPreferences). `LedgerChatService.run/ask/educate` for one-shot
  grounded calls; `NotebotLoop.converse` for the tool loop (6 rounds max, `max_tokens`
  1024 per call — the cost guardrail is structural; wire pinned in DESIGN-NOTEBOT-VOICE.md §6).
- **Reading ink:** `plugin/calendar/nw/VisionOcr.kt` — vision-model transcription of
  rendered ink, with the structured confabulation brake; `plugin/calendar/ot/PanelOcr.kt`
  (ML Kit) as the keyless/offline fallback. `dispatchPages` (CalendarDayFragment ~3207)
  already gathers a page pack: write-draft + typed `textElements` + strokes → vision OCR
  with PanelOcr fallback. Penombra sends stroke vectors; we send a rendered ink image to a
  vision model — same economy if we only render the NEW strokes (strokes carry timestamps).
- **Landing a machine reply ON a page — already shipped in miniature:**
  `placeSourcedQuestions()` (CalendarDayFragment ~5090) places a source gram
  (`ImageElement` carrying `sourceLink`/`sourceLabel` back-refs) plus `TextElement` rows
  onto a page, saves through the ordinary day JSON, and refreshes. `synthesizeQuestions()`
  ("🔬 Synthesize · 3 questions", synth-page wrench menu) is the proto-loop: page ink →
  OCR → LLM → typed questions land on the Synthesize page. The rebirth is this verb grown
  into a correspondence.
- **The elements ARE the wire:** `da/TextElement.kt` (Atkinson Hyperlegible, `pageKey`) and
  `da/ImageElement.kt` (base64/media-ref, `sourceLink`, `z`) ride the day JSON through the
  same save + Drive sync + id-keyed merge as strokes. **iOS reads them today with zero wire
  change.** This satisfies the sacred-wire rule by construction.
- **The pages:** `SynthPageStore` — the daily `synthesize` key (deliberately un-nameable;
  key migration ruled a wire change) plus named topic pages `synthesize-{ts}`, a global
  index because "a synthesis is a TOPIC you return to over weeks," content riding each
  day's JSON under the key.
- **The incoming flow already mints placeable, sourced cards:** the feeds lenses' star /
  "✍ Onto today's note" / `FeedNoteGram` trips place an article's link-face gram carrying
  `sourceLink` + excerpt; `MailVerbs.starToTodo` → `placeMailStarGram` places a letter's
  link-face gram (`sourceLink = mail://<id>`, the letter's opening lines as excerpt, body
  persisted to the keep pile). A source gram is already how outside material stands on a
  page — §5a only adds a door that points these trips at a Synthesis page and a ring that
  lets the correspondent read what the card stands for.
- **The rhizome:** `NotebotTools` (search_ledger, missed_connections, todays_plan,
  due_cards, add_task, create_note, save_gram + the 8 remote notes-bot tools),
  `EmbeddingIndex`, `LedgerCorpusService`, and `AskBridge` — which already sends any
  highlight to Ask *knowing where it came from*, provenance and connection edges riding the
  system context, never the visible question.
- **The voice:** `PersonaStore` — SYNTHESIZE / BRAINSTORM / INTERVIEWER / SPIRAL prompts in
  the approved house voice ("sardonic, straightforward, kind. Short sentences. No markdown…
  never invent; a shrug is better than manufactured insight").
- **The verbs:** lasso menu already carries "🔎 Ask about this" (→ AskBridge → chat),
  "🎓 Educate me" (one-shot lookup → filed to intake feed), "🎓 Pick to Educate Me",
  "🔍 Find in Ledger".

The one thing that does NOT exist: the reply coming back **to the page you wrote on**, in
place, so you can answer it in ink and go again. Every current verb exits the page — to a
chat screen, a dialog, a feed. That exit is the whole gap.

## 3. Canon this design is bound by

- **Ink is sacred and stays ink.** The machine never adds, alters, or erases a stroke.
  Replies are typed/rendered elements; placement must never overlay his ink (clamp to clear
  space below the newest writing).
- **The machine writes in its OWN voice.** Typed Atkinson Hyperlegible — the distinction
  already lives on the page: ink is his hand, type is the machine's. No fake handwriting,
  ever. Each reply opens with a `⟡ ` glyph so a machine line can never be mistaken for a
  placed quote or his own typed text. Default black — grays dither on e-ink
  (feedback_eink_highlight_color_invisible). Michael's closing ruling gives the voice its
  face: replies wear the **pick/card aesthetic** — the `QuoteCardRenderer` visual
  language, the ⟡ voice as the card's typed text (§4 step 4).
- **Offer, never mint.** The Synth loop's tool roster is READ-ONLY (search_ledger,
  missed_connections, embeddings). If the model thinks something should become a task, it
  *says so in its reply*; Michael's own lasso/hold gestures do the minting. `add_task`,
  `create_note`, `save_gram` stay in the Ask chat where a human is at the keyboard.
- **Surfaces that guess are dead; surfaces you ask, live.** The summon is an explicit
  verb. Nothing watches him write; no reply arrives unasked. (Penombra leans ambient in
  the watching sense; that is the one part of it we deliberately do not copy. §7's
  "ambient paper" is ambient in the other sense — always THERE, never acting first.)
- **Hold-to-act, no modes.** Summoning is a tap; there is no "conversation mode" to enter
  or leave. A reply element is an ordinary element — hold it to move/delete like any other.
- **Templates are paper.** Conversation is a capability of the page, not a background;
  nothing new in `NotePageTemplates`.
- **Day-JSON wire is sacred.** Replies are ordinary `TextElement`/`ImageElement` rows.
  Turn bookkeeping goes in a SIDECAR (the `NotePageTemplates`/`LedgerDocumentStore`
  pattern), so an older build or iOS ignores it and still renders every element correctly.
- **E-ink discipline.** No Penombra-style stroke animation: the reply appears in one
  refresh. Progress is the existing toast ("Reading the ink…" / "Writing back…").

## 4. The core loop

**Write → Synth → it reads → it writes back on the page → you answer in ink → again.**

The verb is **Synth** — one word, everywhere it appears: the page verb, the lasso
one-shot, the article/letter door. Named by Michael; it is also the honest name — the verb
lives where the Synthesize verbs already live and does what the section was always for.

1. **Write.** Ink on a Synthesis page (or any page via lasso — §5). Nothing watches.

2. **Synth.** Primary verb: **✒ Synth** — one tap. On synth pages it sits in the
   wrench/Go menu beside the existing synth verbs, and (recommended) as a small rail glyph
   so the loop's beat is a single tap, not menu spelunking. Everywhere else: the lasso
   menu gains "✒ Synth", scoped to the circled ink.

3. **It reads.** Delta discipline, Penombra's one real lesson: render ONLY strokes newer
   than the last reply (stroke timestamps make this a filter, not new state) at the usual
   1600px through `CalendarPdfRenderer.renderInk`, and send the image — vision reads the
   actual handwriting, arrows and sketches included, which beats OCR text as input. Context
   rides in the rings (§6). First Synth on a page reads the whole page.

4. **It writes back onto the page — as a pick.** Michael's closing ruling: "You can use
   pick gram aesthetics for the responses." The reply lands as an **`ImageElement` card**
   in the pick/card visual language — a `QuoteCardRenderer` face carrying the ⟡-prefixed
   reply as its typed Atkinson text — placed just below the bounds of the ink it is
   answering, clamped to clear space, ~1100–1220f wide with height from the renderer's
   own measure. The model returns words only — the client renders and places.

   **Replies are FIRST-CLASS PICKS**, and the doc should be plain about the consequences:
   - The card stamps `cardText` = the full reply text, `sourceLink` =
     `ledger://<date>/<pageKey>` (the page the turn was written on — shipped resolution
     machinery, no new URI scheme; the turn id rides the §8 sidecar), `sourceLabel` =
     "⟡ Synth · <date>". So a reply card joins the pick economy whole: **hold it, move
     it, crop it, write on it in ink, send it to any other note or gram destination —
     and re-Synth it**, because the §5a resolver's card rung reads `cardText` and its
     source rung follows `ledger://` back to the turn's page. A good reply travels; a
     conversation can seed another conversation.
   - **Long replies:** one card is capped at roughly half a page tall (~900f). Past the
     cap the reply splits into a **multi-card run** — sequential cards in one placement
     batch, a small "⟡ 2/3" counter on the face, each card an independent pick (so the
     one paragraph worth keeping moves without its siblings), each with the same
     provenance and its own slice in `cardText`. If the run outgrows the page it flows
     onto the next sub-page and the last face says so.
   - Citations keep their own shape: a passage the model drew on lands as its OWN small
     gram with the source's `sourceLink`, distinct from the reply cards — our native
     version of Penombra's semantic primitives.
   - The day-JSON wire is still untouched: `ImageElement` + `cardText` + `sourceLink`
     are shipped machinery (`PickingsPlacement` stamps them today); iOS renders reply
     cards day one.

5. **You answer in ink** under the reply, Synth again. **The page IS the transcript** —
   no chat history store to sync: reconstruct turns from element order plus the sidecar's
   cached OCR of prior ink (§8), so old turns cost no re-OCR and no re-tokenizing of
   images. The conversation runs across sub-pages and — on topic pages — across weeks.

## 5. Where the correspondence lives

**Ordinary notes are full Synth surfaces.** Michael: "Notes can be created like normal,
grams can be added to them, and then they can be synthed on note — one at a time or
multi." The loop is not gated on the synthesize keys:

- **Make a note like normal, add picks/grams to it** the way grams already move, then
  **✒ Synth on the note** reads the page and the correspondent replies ON that note —
  ⟡ reply cards (§4 step 4), ordinary elements on that note's own page in the day JSON,
  paging like everything else. This is the pane-is-not-a-page reconciliation: nothing floats and nothing docks —
  the correspondence is IN the book, on the page, turned past like any page.
- **Gram selection — one at a time or multi.** Hold a gram → its menu gains "✒ Synth"
  (that one card is the subject); or select several (the existing selection mechanics —
  lasso around the cards) and Synth the set. The SELECTION feeds the §5a resolver at
  full text; the unselected rest of the page rides as ring-1 context at excerpt
  strength. No selection = the whole note, the §4 default.
- **Memory is already note-generic:** the §8 sidecar is keyed `date|pageKey`, so any
  note's correspondence remembers. Nothing about the loop is synth-key-specific.

**The synthesize keys remain the HOME, not the gate:**

- **Named topic pages (`synthesize-…`) are the notebooks with a professor in them** —
  exactly what `SynthPageStore` was built for ("a TOPIC you return to over weeks"), now
  with ring-2 memory across days (§6). Same keys, same store, same un-nameable daily
  page, no migration — the *meaning* changes, not the wire. Paper's sessions are minted
  here too (§7).
- **The daily `synthesize` page is the scratch correspondence** — day-scoped, the way
  Pickings resets daily.
- **Lasso "✒ Synth" on circled ink anywhere** stays as the scoped variant — a reply
  beside the selection, a margin note. When a margin exchange outgrows the margin, the
  reply's tail offers: "⟡ Want to take this to a Synthesis page?" — offered, never
  minted; accepting runs `SynthPageStore.add` + carries the selection over as a source gram.
- **Not a template.** Nothing in the Template sheet changes; paper stays paper.

The Reader closes Penombra's book loop: the highlight menu (which already has Ask rows)
gains "✒ Synth" — mints a topic page named for the book (a deliberate create, behind the
reader's explicit tap), places the passage as a source gram, and the professor conversation
happens there in ink. Quizzing is the same loop with a persona: a "🎓 Quiz me" verb on
synth pages has the model write questions as reply elements; you answer in ink; Synth
grades in the next reply. Prompt only, zero new machinery.

## 5a. The incoming flow joins the loop (one generic door)

Michael's redlines: "It's missing input from feeds and emails," then "Picks can be
synthed. No need to make it special." So there is exactly ONE mechanism, generic over
**anything that is — or mints — a source card**: a feed article, a letter, a pick. No
per-source specialness. Penombra discusses a PDF; Ledgable discusses the morning's almanac.

**The door: "✒ Synth" wherever a card is (or is about to be).** One row, one word, one
behavior: *the card lands on the chosen Synthesis page and the correspondence opens
there.* The card is whatever that surface already mints — `FeedNoteGram`'s article
link-face, `placeMailStarGram`'s letter face (`sourceLink = mail://<id>`), a pick's
existing card as it stands. So the door appears:

- on a feed article (the same tuck-panel rank as star / "✍ Onto today's note");
- on a letter (the same menu family as `MailVerbs`' star/reply doors — thinking-about-
  the-letter, distinct from `replyDoors`' answering it; the correspondent may END with
  the reply-gram offer, never mint it);
- on a pick's hold menu (it is already a card with `sourceLink`; Synth just re-places it
  onto the synth page like any gram move-with-destination).

Destination: the same chooser discipline as grams everywhere — topic pages + today's
daily page + "New topic…" (`SynthPageStore.add`, named from the card's label), with the
remembered-destination default so the second card in a session is one tap. The
correspondent's opening reply is NOT auto-fired (surfaces that guess are dead): the card
sits on the paper; he writes a line or just taps ✒, and the reply engages with it
because ring 1 carries its resolved text (below).

**The resolver: one ladder, a source gram on the page is READABLE, not just visible.**
Whenever a gram on the page being Synthed carries a source, ring 1 resolves what the card
stands for by its existing fields (`sourceLink`/`sourceLabel`/`cardText`) — nothing new
on the wire:

- http(s) feed source → the entry's stored/readable text, via the feeds store;
- `mail://<id>` → the kept body from the keep pile, resolved as the gram's tap-through does;
- `ledger://date/page` → that page's cached transcript (§8) or its typed text;
- book/reader source → the annotation/highlight text from the annotation corpus;
- **otherwise — the card itself:** its `cardText`/quote face, or vision OCR of the face
  when it is ink. A pick with no richer source resolves here, and that is enough: the
  pick IS the material.

A card whose source can't be resolved contributes its excerpt and label and the
correspondent says what it couldn't read. Budget rule: full text for the card(s) inside
or nearest the summoning selection, excerpts for the rest of the page's cards.

This is the section's quiet promotion: the feeds and the mail stop being places the flow
terminates (starred, filed, replied) and become places it TURNS INTO thinking — the
almanac's finite daily flow feeding the day's finite paper.

## 6. What the model gets (the rings, priced separately)

1. **This page (always):** the delta ink image, the page's prior turns as text (cached
   transcript + prior ⟡ elements), the page's typed text, the placed grams' labels AND
   resolved source texts (§5a), and an AskBridge-style grounding preamble (page identity,
   date, connection edges touching it).
2. **The notebook (topic pages only):** the topic's run across days — prior days' cached
   transcripts under the same key, its `SynthesisIdeaStore` entries, its source grams'
   links. Bounded, like NotebotLoop's 8-turn history: last N turns verbatim, older as a
   one-line-per-day digest.
3. **The rhizome (on request only):** `NotebotLoop.converse` with the read-only tool
   subset — the model reaches into the corpus (search_ledger, missed_connections,
   embeddings) only when it decides it needs to, inside the existing 6-round/1024-token
   guardrails. The reply names what it drew on, per the SYNTHESIZE prompt's own rule.

New persona beside SYNTHESIZE in `PersonaStore` — the CORRESPONDENT prompt, house voice,
naming what it must not become: not a lecturer, not a summarizer of what he just wrote;
short replies that press on one specific thing — sized for a card face, the card being
the forcing function for brevity — questions that move the ink forward;
permission to shrug; "end when the page should go back to him" — because on paper, unlike
chat, the default is that the human holds the pen.

## 7. The paper in Ask (Paper sessions)

Michael's redlines: "there should be an ambient surface in Ask that looks like paper,"
then, superseding this doc's first identity call: "The synth paper in Ask would be a
dedicated surface that gets stored like a chat but in paper."

**Ask gains a Paper surface** — you type (or write, where the pad is at hand) at the
bottom; your words land ON the paper as ordinary typed lines; the reply lands under them
in the same ⟡ voice; the transcript IS the paper. Ambient in the right sense: always
there behind the Ask door, never acting first — in Paper, *sending is the Synth*, so the
explicit-ask canon holds by construction.

**The identity ruling (revised): Paper is its OWN dedicated surface, with sessions
stored like chats — and each session IS a real page.** The storage shape chosen:

- **A Paper session lazily auto-mints a `SynthPageStore` topic page on first send**
  (`synthesize-{ts}`, auto-named from the first line, renameable; mint-on-first-send so
  an opened-and-abandoned Paper leaves nothing behind). Everything in the session — his
  typed lines (plain `TextElement`, no ⟡), the ⟡ replies, any grams he adds — are
  ordinary elements riding that page's day JSON under its key: the already-shipped shape.
- **Ask lists Paper sessions the way it lists chats:** newest first, first-line preview,
  "New paper" on top; opening the Paper door resumes the most recent session. The list
  is a filter of the synth index — `synth-index/pages.json` (already a sidecar) gains
  one optional field, `origin: "paper"`, so Ask knows which topic pages are its own
  history. A build that ignores the field just sees more topic pages. Nothing else new.
- **A session is not trapped in Ask.** Because it is a topic page, it also appears in
  the Notebook with the other synthesize pages, opens on the day surface with the ink
  tools live, and a topic page started on the day surface can equally be picked up in
  Paper. Stored like a chat; IS a page.

Why this shape and not a parallel transcript store: content stays on the sacred wire
(day-JSON elements under an existing key family — zero new wire; the only new datum is
one optional field in an existing sidecar index); "Ask produces, Notes hold" survives
(the paper HOLDS, so it must be a page); and parity collapses to nearly free (iOS
renders every session day one because sessions are pages; only the list view and verb
need porting). A private Ask-owned paper store would be a parity gap by construction
and a second sync surface for no gain.

What Paper does NOT get: Ask's write-capable tools. Paper runs the §4 loop
(CORRESPONDENT persona, read-only ring 3); the bubble chat keeps the full Notebot
roster. The pane toggle is the honest contract switch: bubbles can DO things for you,
paper THINKS with you. AskBridge sends keep landing in bubbles; "open in Paper" on the
bridge banner is the §5a door from inside Ask — place the source gram on the current
session and switch. On e-ink the surface refreshes per turn like any page.

## 8. The sidecar (the only new persistence)

`correspondence/pages.json`, exactly the `NotePageTemplates` shape: rows keyed
`date|pageKey`, each holding `lastReplyAt` (the delta watermark), the cached transcript of
turns `[{at, who: ink|typed|machine, text}]`, and the model/persona used. Newer-`at` wins
on merge; no tombstones; an older build ignoring the file loses nothing but the delta
optimization. Day JSON untouched. iOS reads the same sidecar when its twin lands; until
then it renders every reply element correctly anyway, because they are ordinary elements.

## 9. Phases (Android first; each phase is a same-day ship)

**P1 — the loop exists.** "✒ Synth" on synth pages (wrench menu). Whole-page read (no
delta yet), one-shot `LedgerChatService.run` with the CORRESPONDENT prompt, reply placed
via the `placeSourcedQuestions` geometry below the newest ink. No sidecar. This is
Penombra's demo, on our rails, in one day.

**P2 — the correspondence remembers.** The `correspondence/` sidecar: delta ink reads,
cached transcripts, multi-turn context on one page. Rail glyph for one-tap Synth.

**P3 — the correspondence lives on any note.** "✒ Synth" on ordinary notes (wrench
row everywhere, not just synth pages) with gram selection — hold one gram, or select
several, and the selection feeds the resolver at full text while the rest of the page
rides as context. Plus the lasso scoped variant on circled ink, with the
"take this to a Synthesis page?" offer in the tail. The sidecar is already
`date|pageKey`-generic; this phase is verbs, not storage.

**P4 — the incoming flow.** The one generic "✒ Synth" door on anything that is or
mints a source card — feed articles, mail letters, picks' hold menus (§5a: existing
card mints aimed at a synth page + navigate) — and the one resolver ladder in ring 1
(article text / kept letter body / page transcript / annotation / the card's own
face-text or OCR). Feeds first, mail and picks the same day or the next; one door, one
resolver, three surfaces.

**P5 — the rhizome ring.** Swap one-shot for `NotebotLoop.converse` + read-only tools;
AskBridge-style grounding; replies cite what they reached for; source-gram citations as
ImageElements.

**P6 — the paper in Ask.** Paper sessions (§7): lazy topic-page mint on first send,
`origin: "paper"` in the synth index, the chat-style session list, typed input →
TextElement, replies through the §4 placement path. The loop parts all exist by P2;
this phase is a view plus a list.

**P7 — the professor and the quiz.** Reader highlight → "✒ Synth" (topic page mint +
passage gram); "🎓 Quiz me" persona verb on synth pages.

**P8 — topic memory across weeks.** Ring 2 digests; the topic page's Go menu shows "⟡
correspondence · N turns" opening the transcript.

**P9 — iOS twin.** Day one it already renders replies (elements ride the day JSON). Then:
pin the sidecar format in the parity brief FIRST (the handoff rule), port the Synth verb
onto `AskTools.swift` / the iOS LedgerLLM.converse and its vision path, same prompts, same
placement clamps, and the Paper pane as a view over the same elements. Full parity is a
requirement, not an aspiration.

Deliberately out: stroke-by-stroke reply animation (e-ink), fake handwriting (canon),
unasked replies (canon — "ambient" means present, not proactive), any write-capable tool
in the loop (offer-never-mint), new page keys or day-JSON fields (wire).
