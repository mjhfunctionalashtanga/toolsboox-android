# DESIGN — Notebot chat with voice (punchlist item 12, with item 14 riding along)

Michael's words: "Notebot chat — voice — as on mjh.yoga." The mjh.yoga reference is the
floating notesbot popup — a conversation you *talk to* that actually does the business:
searches notes, creates notes, remembers the thread. This brief is the design-first pass
that bots get before any build. Nothing here is committed code; everything here is a
decision with its reason attached, so the build can disagree with a sentence instead of
an accident.

Written 2026-07-31 against:

- Android chat surface as it ships: `app/src/main/java/com/toolsboox/plugin/chat/`
  (`LedgerChatFragment`, `LedgerChatService`, `LedgerCorpusService`, `EmbeddingIndex`,
  `AiCreds`, `ChatHistoryStore`, `PersonaStore`, `AskPresetStore`)
- Voice capture as it ships: `app/src/main/java/com/toolsboox/ot/VoiceRecorder.kt`
  (MediaRecorder → AAC/m4a, counting dialog, sub-half-second discard)
- Read-aloud as it ships: `app/src/main/java/com/toolsboox/ui/plugin/LedgerTts.kt`
  (system TextToSpeech, sentence-chunked, pause/resume/skip/rate)
- The iOS twin: `ledger-ipad-correspondence/App/AskTools.swift` (the one tool registry +
  `LedgerLLM.converse` loop), `AskLedgerView.swift` (AskPanel + SFSpeechRecognizer
  dictation), `Watch/App/AskView.swift` (spoken replies, phone/direct ladder)
- The server bot: `mjh-notes-bot` on mjh.yoga — ~46 manual functions behind AI Engine,
  reachable per-endpoint via the `mjh/v1` REST routes and the `X-MJH-Ingest-Secret`
  header the iOS `create_note` double-write already uses


## 1. What it is

One conversation surface — call it **Notebot** in the chrome — that fronts BOTH reaches:

- **The local reach:** the Ask-my-Ledger corpus (book highlights, feed annotations,
  planner text, A/V grams, notes, tasks) plus the local doing verbs (add a task, make a
  note, place a gram).
- **The remote reach:** the mjh.yoga notes-bot's world — the ~150-note /notes corpus,
  its tags, its pins, its annotations — via a curated subset of its functions.

The model decides which reach a question needs. "What did I highlight in Light on Yoga"
is local; "what's in my seed notes about the wristphone essay" is remote; "make a note
of this and pin it" is both a local create and a remote create, and the loop can do both
in one turn. This is not two bots with a switcher — a switcher is a mode, and the app's
standing rule is no modes. It is one bot with two arms, and each answer's receipt line
names which arms it used.

The architecture is already built once, on iOS: `ToolRegistry` declares each tool once
(name, description, typed params, executor) and emits Anthropic or OpenAI wire format
from the same declaration; `LedgerLLM.converse` runs the loop, six rounds max, the cap
round forced to words. **Android ports that shape rather than inventing a second one.**
The Android chat today has no tool loop at all — retrieval is front-loaded and creation
is a fenced ```ledger-create block parsed out of prose. Both halves become tools, the
same supersession iOS already performed. The fenced-block path stays for the grounded
one-shot callers (`llm.ask` equivalents: educate, zone prompts) that never converse.

Both forks build this. The 07-30 lesson is standing policy: **the wire format is pinned
in this brief** (section 6) so a conversation started on the Boox and a tool schema on
the iPhone cannot drift apart the way the handwritten titles did.


## 2. Voice in

**Gesture.** The mic button grows a second verb, on the app's standing grammar:

- **Tap the mic** — dictate into the question field. Exactly today's behavior on both
  forks: recognition lands in the field, you read it, fix it, send it.
- **Hold the mic** — talk-and-send. Release ends the recording; the transcript lands in
  the question field *visibly*, a 2.5-second countdown pill appears ("Sending — tap to
  edit"), and then it sends. Tapping anywhere during the countdown cancels the send and
  leaves the transcript in the field for correction.

Hold-to-act is the app's answer to "where does the second verb live," and it puts the
cheap, reversible action on the long press where a mis-fire costs one follow-up question.

**Why auto-send is right here when it was wrong for titles.** The `LedgerTitlePad` rule
— recognition lands in an editable field, never saved blind — exists because a misread
title is a document filed under a name you will never search for, discovered a year
later. The stake analysis, not the mechanism, is the rule. A chat question has the
opposite stakes: a misheard question produces a visibly wrong answer *immediately*, the
transcript sits in the question box as the auditable record of what was actually asked,
and the correction is a follow-up turn — which a conversation is made of anyway. So:
tap-path keeps the full TitlePad discipline; hold-path trades it for flow because the
failure is loud, instant, and one turn deep. Nothing is ever sent that was not first
shown.

**Recording state.** Flat. A full-width bar over the input row: `● Listening 0:07 —
release to send` (hold) or `● Listening 0:07 — tap Stop` (tap), counting the way
`VoiceRecorder`'s dialog counts. No waveform, no pulse animation — a moving level meter
on e-ink is a strobing grey rectangle that costs refreshes and communicates nothing the
counter doesn't.

**Transcription path.** Primary: **record with the `VoiceRecorder` machinery (AAC/m4a —
capture is a solved problem in this codebase) and transcribe via the OpenAI audio
transcription endpoint** (`whisper-1` today, ~$0.006/min; the 4o-transcribe models are a
drop-in when preferred). Fallback: **the system `RecognizerIntent`** the mic button
already uses.

Reasons, in order of weight:

- *It cannot not work.* `LedgerChatFragment.startDictation` already carries the
  apology — "some Boox units lack Google services." A voice feature whose front door is
  absent on the device it was built for is not a feature. The API path works on every
  unit that can chat at all.
- *Offline is not on the table anyway.* The conversation itself needs the network — the
  answer comes from the Anthropic API. There is no offline turn for on-device
  recognition to preserve, so its one structural advantage is void here. (Where it IS
  the advantage — the watch — it is exactly what we use; see section 5.)
- *Vocabulary.* The corpus is full of Ashtanga Sanskrit, contact names, book titles.
  Whisper-class models handle these far better than the free recognizer, and a
  transcription that mangles "Pasasana" defeats the retrieval it feeds.
- *Cost is noise.* A heavy month of voice chat — say 60 minutes of speech — is ~$0.36.
- *Anthropic audio is not an option:* the Messages API the app already speaks has no
  audio-transcription modality, so "one key, one provider" was never available. The
  OpenAI key is already in the encrypted prefs for embeddings (`EmbeddingIndex.apiKey`);
  transcription reuses that exact credential path, zero new setup.

When no OpenAI key is saved, the mic falls back to `RecognizerIntent` where present, and
says plainly what it needs where absent — never a dead button.

**Latency budget.** Hold-release → transcript in field is the number to watch: upload of
a ~15-second m4a (~120 KB) plus Whisper turnaround should land in 1.5–3 s on the Boox's
radio. Slice 1 measures this before anything else is built (section 8), because if it's
6 s the countdown design has to change (start the LLM call optimistically) and better to
know in week one.


## 3. Voice out

**Engine.** Android: `LedgerTts` — it already exists, chunks correctly, and pauses. iOS:
`AVSpeechSynthesizer`, which the watch's AskView already speaks through. **System TTS,
both platforms, v1.** The OpenAI TTS voice (the `tts_openai_key` the iOS SpeechReader
holds) is strictly nicer to listen to, but it costs money per character, adds a
synthesis round-trip before the first word, and — decisive — whole-answer synthesis
means silence until the file arrives, where system TTS starts speaking the first
sentence immediately. A voice upgrade can slot behind the same speak() seam later; the
conversation design owes it nothing.

**When to speak.** The rule is symmetry: **voice in → voice out; typed → silent.** A
question that arrived through the microphone gets its answer spoken, because a
microphone question means hands and probably eyes are elsewhere. A typed question gets a
read answer — e-ink is a reading surface and reading is faster than listening. One
speaker toggle in the header overrides in both directions (the watch's exact pattern:
`speaker.wave` / `speaker.slash`), for the person who wants to type and hear, or speak
and read. The toggle is remembered; the symmetry rule is only the default.

**Interrupt.** While speaking, a flat pill sits over the answer: `Speaking — tap to
stop`. One tap anywhere on the answer area stops the voice (`LedgerTts.stop`). Starting
a new recording also stops it — talking over the bot is the interrupt a conversation
already has. No pause/resume chrome in the chat; that's the read-aloud player's job, and
an answer short enough to converse with doesn't need a transport bar.

**Errors are spoken too.** If the turn came in by voice and fails, the failure is said:
"I couldn't reach Claude — the key may be wrong." Hands-free means eyes-free; an error
that only renders is an answer that silently never came.


## 4. Tools — the v1 list

One registry, two reaches. Local tools run in-process; remote tools are one HTTPS call
each to mjh.yoga with the `X-MJH-Ingest-Secret` credential. All tool names, params, and
result shapes are identical on both forks (section 6).

**Local — the iOS seven, ported to Android where the capability exists:**

| tool | mutates | notes |
|---|---|---|
| `search_ledger(query)` | no | the hybrid corpus retrieval, now callable repeatedly with sharper queries |
| `find_contact(name)` | no | iOS has the rolodex + connection graph; Android ships this only when its rolodex parity lands — a tool that always answers "no contacts here" teaches the model to stop calling it |
| `todays_plan()` | no | tasks off the day JSON + schedule rows |
| `due_cards(limit?)` | no | FluentBoards via the ledgr bridge |
| `add_task(text, date?, time?)` | yes | day JSON + CalDAV re-push, `source:"ask"` |
| `create_note(title, body)` | yes | Text Note on today; double-writes to /notes when the secret is configured |
| `save_gram(text, title?)` | yes | quote-card face onto today's notes page |

**Remote — the notes-bot subset, eight tools:**

| tool | mutates | backs onto |
|---|---|---|
| `notes_search(query, limit?)` | no | keyword across title/content/annotation |
| `notes_search_semantic(query, limit?)` | no | the embedding search (0.4+ related, 0.55+ strong) |
| `notes_recent(limit?, days?)` | no | |
| `notes_by_tag(tag, limit?)` | no | diary, shala-daily, pickings, seed, essay, techsupport |
| `notes_get_by_id(id)` | no | full content + annotation |
| `notes_create(title, content, tag?)` | yes | the same create the ingest path uses |
| `notes_annotate_append(id, text)` | yes | append-only — the overwrite variant stays server-side |
| `note_pin(id, unpin?)` | yes | |

**What stays out of v1, and why each:**

- **Anything that deletes.** The iOS bargain holds verbatim: mutating tools execute
  without confirmation *because* nothing deletes and every mutation narrates itself.
  Voice widens the mis-fire surface; the bargain is what makes that safe.
- **`mail_self`, `fcrm_send_email`, `command_*`, booking, refunds** — the portfolio-ops
  arm of the super-bot. These carry the SMS bridge's two-step "Reply Y to confirm"
  ritual for a reason, and a spoken confirmation to a machine is exactly the interaction
  this app refuses to build. When they come, they come with a designed confirm surface,
  not as list-padding.
- **`notes_repurpose_*`** — 120–300 s async fire-and-forget. A conversational turn that
  answers "queued, check later" is a worse door to that machinery than the popup that
  already has it.
- **`notes_annotate` (overwrite)** — append covers the conversational need; overwrite
  from a transcribed sentence is data loss with extra steps.
- **The generic `remote_ability(site, ability, params)` bridge** — stays a stub, for the
  reason the iOS comment already gives: it needs the per-site credential picker before
  it can be trusted with a key, and typed schemas steer the model far better than one
  stringly-typed door to 38 abilities.

Fifteen tools total. The AI Engine payload lesson (the parser choking at ~273 tools)
says curated depth beats breadth, and the SMS bridge proved the same bot is *more*
usable with fewer, sharper verbs.


## 5. The watch (item 14)

Item 14 rides on item 12's API work, and the wrist is already most of the way there:
`AskView` speaks its replies (AVSpeechSynthesizer, toggle in the toolbar), carries the
thread as history, and climbs the ladder — corpus-grounded via the phone when reachable,
direct API from the watch when not. Standalone Notebot is the direct rung growing tools:

- **Input:** the watchOS-native `TextField` it already uses — dictation, scribble,
  QWERTY come free. No custom capture on the wrist; Apple's dictation IS the on-device
  primary there, because the watch turn must survive a phone-less pocket.
- **Voice out:** default ON (already shipped). The wrist is the surface where the
  "notesbot voice feel" was named; it keeps it.
- **Tool subset, direct rung:** `todays_plan` (answers from the snapshot the phone last
  pushed — stale beats absent, and the caption says so), `add_task` (queued offline in
  the same reconcile-when-reconnected queue item 14 specifies for pickings — one queue,
  not two), and the remote five that need no phone at all: `notes_search`,
  `notes_search_semantic`, `notes_recent`, `notes_create`, `note_pin`. The remote reach
  is actually the wrist's *strong* arm — the server doesn't care which device called.
- **Not on the wrist:** `search_ledger` on the direct rung (the corpus lives on the
  phone; the phone rung keeps it), `save_gram` (no canvas to place onto), `due_cards`
  (a kanban readout is not a wrist answer).
- Transcript saving stays as built: save-chat writes a Text Note and, with the secret,
  posts to /notes — the loop that lands wrist conversations where Notebot lives.


## 6. Wire & state

**The pinned wire format** (both forks, verbatim — this paragraph is the contract):
tool schemas are the JSON-schema object `{"type":"object","properties":{...},
"required":[...]}` with the fifteen v1 names and params exactly as section 4 spells
them; the Anthropic emission wraps as `{name, description, input_schema}`, the OpenAI
emission as `{type:"function", function:{name, description, parameters}}`; the loop is
max six rounds with `tool_choice: none` forced on the last; every mutating tool returns
a one-line English sentence starting with a past-tense verb ("Added task: …"), every
read tool returns cite-tagged plain text or a plain "nothing found" sentence. Remote
tools POST JSON to `https://mjh.yoga/wp-json/mjh/v1/notebot/{tool}` with
`X-MJH-Ingest-Secret`, and get `{status, message, ...}` back — the `{status, id, title,
view_url, message}` shape the bot's functions already return.

**Conversation state.** The thread lives in memory for the visit and each completed turn
appends to `ChatHistoryStore` with a `session` id added to the row (the store's shape
otherwise unchanged — rolling 200, local JSON). The model sees the last 8 answered turns
as context, not the whole history: the context window is a cost surface and a
conversation that needs turn 40 needed a note at turn 39. **Chats do not sync between
devices.** The chat is working material; the durable exits are the ones that already
exist — save-as-note (which syncs as a Text Note), save-to-feed, and the /notes
double-write. Syncing raw transcripts would create a second source of truth for the same
words, and one-truth-per-thing is canon.

**Credentials.** All through the paths that exist: `AiCreds` reads provider/key/model
from the encrypted chat prefs (Anthropic default `claude-sonnet-5`); transcription reuses
the OpenAI key `EmbeddingIndex` already reads; Android gains a `notes_ingest_secret`
field in the chat settings panel, stored in the same `EncryptedSharedPreferences`
(iOS already keeps it in the Keychain via `SecureStore`, excluded from settings backup —
Android's encrypted store is the standing equivalent). No new credential *kinds*, one
new credential *field*.

**Cost guardrails are structural, not accounting:** `max_tokens` 1024 per call, six tool
rounds, eight turns of context, retrieval snippets already capped by `buildContext`,
embeddings cached by content hash, transcription billed by the half-minute of actual
speech. Worst-case turn is a few cents; there is nothing here that runs unattended, so
there is no meter to build.


## 7. E-ink UX

**Transcript, not bubbles.** Chat bubbles on e-ink are grey rounded smudges that waste
half the line width on asymmetric margins. The surface is a **ledger-page transcript**,
the idiom the watch already renders: question in bold, full width; answer in regular
weight beneath it; the receipt caption under that in small type (`· used search_ledger,
notes_create · grounded in 6 entries`); a hairline rule between turns. Newest at the
bottom, scrolled to. It reads like a page of minutes, which is what it is.

- Citations stay doors: underline only, no color — the standing e-ink rule.
- The passage banner, persona button, preset chips, history, save-as-note, save-to-feed
  all survive the move — they attach to the input row and the current answer exactly as
  they do today. Presets fire as turns now instead of replacing the lone answer.
- The input row, bottom of screen: question field, mic (tap/hold per section 2), send.
  The recording bar and the speaking pill replace the input row's space when active —
  states swap in place; nothing floats.
- **If ink ever becomes an input** (a scribbled question on a pad), the
  `promptTitle` rule applies unmodified: action buttons build INTO the panel content,
  index 0, ABOVE the ink — never platform buttons at the bottom under a hand. v1 is
  voice and type; the rule is recorded here so the v2 that adds the pad inherits it.
- **Error states:** rendered in the answer slot as plain sentences (the existing "⚠️ +
  message" pattern), and spoken when the turn was spoken (section 3). A blank answer
  area is never an error state.
- Full-refresh discipline: appending a turn invalidates from the new turn down, not the
  page; the countdown pill updates by second, not by frame.


## 8. Build plan

Risk first: the two things that can sink this are audio round-trip latency on the Boox
and the tool loop behaving on Android. Both are slice 1.

**Slice 1 — the spike that decides the shape (shippable as a hidden path).**
Android: port the registry + converse loop (Kotlin mirror of `AskTools.swift`'s
registry/loop, Anthropic wire only at first) with just `search_ledger` and `add_task`;
wire hold-mic → `VoiceRecorder` → Whisper → field → auto-send → `LedgerTts` speaks the
answer. Measure on the Go 6: release-to-transcript, transcript-to-first-word. Exit
criteria: the loop narrates its mutations correctly and the voice round trip feels like
conversation (≤ ~4 s to first spoken word) — or the countdown design gets rethought now,
cheaply.

**Slice 2 — the surface.** The transcript idiom replaces the single-answer internals of
`LedgerChatFragment`; threaded history with session ids; tap/hold mic grammar; speaking
pill + interrupts; voice-in→voice-out symmetry + toggle; spoken errors; the full local
toolset. iOS AskPanel adopts the same transcript rendering and the hold-mic grammar
(its loop already exists). Ships as the new Notebot on both forks, local reach only.

**Slice 3 — the remote reach.** The `mjh/v1/notebot/*` routes land server-side (thin
dispatchers over the existing bot functions, same auth as create-tagged); the eight
remote tools land in both registries against the pinned wire format; Android gains the
ingest-secret settings field. One Notebot, two reaches, both forks.

**Slice 4 — the wrist (item 14).** The direct rung's tool subset; the offline
add-task/picking queue with reconnect reconciliation; stale-snapshot captions. Ships as
the standalone watch.

Each slice leaves the app better than it found it; nothing waits on a later slice to be
honest about what it is.
