# Ledger UX Unification — Session Handoff

## Session 2 — on-device review fixes (Android, all built + installed to Tab8 + Palma2 Pro C)
Fixed from Michael's device testing:
1. **Menu reorder + rename** — `LedgerDirectory.kt` order now Day · Almanac · Personal · Ledger Log · Tasks · Feed · Bookshelf · Ask · Settings. "Notes & Annotations" → **"Ledger Log"** everywhere (strings.xml `reading_log_title`/`card_saved_to_notes`, FeedsFragment toast). NOTE: Ledger Log slotted after Personal (Michael's 8-item list omitted it) — confirm placement.
2. **Cloud settings crash** — `CloudFragment.kt`: unguarded crypto self-test (line ~170) threw on every open; wrapped in runCatching. Play Billing + Google Sign-In in onResume also guarded (Boox lacks full Play Services). Cloud creds DO persist (MAIN prefs) but aren't in SettingsBackup/Settings screen — his real sync is Ultrabridge WebDAV (that IS in Settings).
3. **Book import crash (first-time)** — `ReaderFragment.kt`: added `engineReady` gate so `openBookURL` waits for foliate "ready"; `importAndOpen` now fails safe (no uncaught throw / zero-byte file). New string `reader_import_failed`.
4. **Reader highlight menu** — tapping a highlight now opens Copy text / Add note / Remove (was Delete-only). `reader-embed.js` show-annotation now sends the highlighted text; `showHighlightMenu()` in ReaderFragment.
5. **OCR "Copy text"** — `CalendarDayFragment.onSelectionExtract` lasso menu gained "📋 Copy text" → selectable/editable dialog + clipboard (`copyTextFromSelection`/`showCopyText`). OCR model already user-configurable (aiCreds → VisionOcr model param, default claude-sonnet-5).
6. **YouTube feed embeds** — `FeedsFragment.kt` + `FeedArticleFragment.kt`: added `iframe{max-width:100%}` + `@supports aspect-ratio 16/9` CSS (mis-size), and a real https baseUrl instead of `null` (fixes embed **error 150** = opaque origin). `articleBaseUrl()` helper.
7. **Back-anchor (feed/book → Day note → back)** — new `LedgerReturn` + `ReturnAnchorProvider` (ui/plugin). Jumping to Day from an open article/book stashes an anchor; Day page shows an indefinite "↩ Back to '…'" Snackbar. Feed reopens exact entry via `pendingInPaneEntry`; book restores via KEY_BOOK.
8. **Overlapping strokes on fast paging** — `SurfaceFragment.onPause` now bakes the pending pen-up stroke (`applyStrokes(strokes,true)`) then forces a full GC EPD refresh (`forceFullEpdRefresh()` → `EpdController.repaintEveryThing(UpdateMode.GC)`) to wipe the Onyx hardware overlay. `renderPage` guarded on isAdded/isResumed. VERIFY ON-DEVICE.
9. **Feed panel closes on entry open** — `setDirectoryDrawerVisible(false)` in openEntry (non-persisted), restored in closeArticlePane.

### Session 2b — media epic (LedgerPlayer as universal player)
- **`LedgerPlayer`** (ui/plugin) is now the one player for ALL in-app media: TTS (articles/books) AND real audio via MediaPlayer (voice memos, podcasts, audiobooks). Survives navigation; control modal (image + title/source + ⏮ ▶/⏸ ⏭ ⏹); "▶️ Now Playing" appears atop the ▦ menu when active. `isAudioFile()` helper distinguishes audiobooks from e-books.
- **Menu**: ☀️ Today (was Day) · Almanac · ❤️ Daily (Pickings/Gratitude/A-V Grams/✍️ Write) · Tasks · Feed · **Bookshelf dropdown** (recent + All books, every surface) · Ask · Ledger Log · Settings.
- **Podcasts**: `enclosureAudio` now parsed (MinifluxClient) + cached (FeedCache) + on FeedEntry (`audioUrl`). A "listen" entry with audio plays through the player instead of the article pane.
- **Add-to-Later** (`feeds_later` pill, `ic_go_later`): appends the entry to today's Later intake (`IntakePageStore`) by kind, no star needed; if it's media, `LaterMedia.download()` grabs the audio to `filesDir/later-media` (hash-named) for offline. Later playback uses `LaterMedia.playableSource` (local if cached).
- **Audiobooks**: importing/opening an audio file (mp3/m4a/m4b/…) routes to the player (LedgerDirectory.openBook + ReaderFragment.loadBookFile), e-books still open in foliate.

### Session 2c — search, persona, corpus, iOS fixes
- **Hybrid retrieval** (`chat/nw/EmbeddingIndex.kt` + `LedgerCorpusService.retrieveHybrid`): OpenAI text-embedding-3-small blended with keyword+recency (0.7 cos / 0.2 kw / 0.1 recency), cached per snippet-hash in `filesDir/ledger-embeddings`; falls back to keyword-only without an OpenAI key. Chat uses it.
- **Whole corpus searchable**: `Section.SECTIONS` (OCR'd page sections) + `Section.NOTES` (Text Notes) added; `LedgerCorpusService` injects `@ApplicationContext` and scans `page-sections/` + `text-notes/`. Chat `selectedScope()` always includes both.
- **Text Notes → WebDAV** on page-leave (`TextNotesStore.backup`, Ultrabridge creds), decoupled from the per-keystroke local save.
- **Persona mode** (`chat/nw/PersonaStore.kt` + `persona_button`): save/name/reuse system prompts; active persona prepends to the grounding prompt in `LedgerChatService.ask(..., persona)`.
- **Menu**: Tasks & Events folded into Almanac + Ledger Log (no standalone); Today (☀️), Daily Ledgers (❤️, +Text Notes), OCR-model picker in Settings, ▶️ Now Playing.

### iOS (compile-verified via simulator build; ship to device via Xcode/TestFlight)
- `SidebarMenu.swift`: **Local Feeds moved INTO Feed Ledger** submenu (`localFeedsSection`→`localFeedsGroup`, invoked after `smartFeedsGroup`; removed the top-level section). Smart Feeds were already nested.
- `PlannerModel.swift`: Daily-Ledgers `.intake` title "Later List" → **"For Later"** (leaves the Feed Ledger "Later List" B untouched).
- `Bookshelf.swift`: reader **exit chevron now ALWAYS visible** (dimmed when chrome hidden) — fixes the fullScreenCover trap where hiding chrome left no way back.
- iOS "Personal" was ALREADY "Daily Ledgers". The two Feed-Ledger "Later"s are "Starred" (RSS stars) + "Later List" (intake) — genuinely different; left as-is.
- iOS feature parity still TODO: unified media player/podcasts/audiobooks (extend `MiniPlayer.swift`/`SpeechReader.swift`), Add-to-Later + download, Text Notes surface, whole-page/auto OCR, hybrid embeddings (`LedgerCorpus.swift`), persona mode (`LedgerLLM.swift`/`AskLedgerView.swift`), OCR-model picker.

### Session 2d — Ask Ledger read/write + notes
- **Ask Ledger searches tasks/events/calendar** (`Section.TASKS`; snippetsOf reads `ledgerItems` + `events`).
- **Ask Ledger creates** task/event/note via a ```ledger-create fenced block (system prompt in `LedgerChatService.ask`); `executeCreates` writes `LedgerItem` (+ `LedgerTaskSync.pushTask` CalDAV) with real date+time, or `TextNotesStore.addNote`.
- **Dictation**: mic button → system `RecognizerIntent` into the question box (graceful fallback if no recognizer).
- **Save answers to RSS**: `feeds/nw/AskFeedStore.kt` + feed mode `asklog` + directory entries ("🗨 Ask Answers"); "💾 Save to feed" button. Saved answers are normal FeedEntries (star/note/later all work).
- **Chat history**: `chat/nw/ChatHistoryStore.kt` (rolling 200) + "🕘 History" button to reopen past exchanges.
- **Multiple titled Text Notes per day**: `TextNotesStore` now a JSON list of `TextNote(id,title,body)` (migrates old `.txt`); `TextNotesFragment` = title+body editor with "≡ Notes (N)" switcher + "＋". Corpus `gatherExtras` reads the new `.json` (title+body per note). WebDAV backup as JSON.

### Session 2e — Pickings epic + Synthesize→Write + A/V grams
- **Multi-page named Pickings** (`PickingsStore`): Daily Ledgers → ❝ Pickings opens a picker (boards · New · Rename); boards are `pickings-<id>` keys; render/swipe routed via `PickingsStore.isPickings`.
- **Synthesize→Write pipeline** (Brainstorm→**Synthesize**, key `synthesize`, grid page): tools menu → Synthesize·3 questions (→ text boxes on page), Writing prompt→Write (3 choices), Essay outline→Write (movable text boxes). Runs off page OCR/typed text (`pageOcrText`+`runLlm`). **Book side**: reader ☰ → Synthesize (3 Q → Text Note) / Essay outline → Write (`AiCreds` shared helper + `getReaderText`).
- **A/V grams**: `Transcribe` (Whisper). Record A/V gram from the lasso menu ("🎬 A/V gram" → captureAvGram → persist avGram → transcribe → gram studio). Ledger Log item → "❝ Add to Pickings" (transcribe + card). Gram studio → "❝ Add to Pickings".
- **Cross-surface placement** (`PickingsPlacement`): chooser Today's/New/Saved board → writes an `ImageElement(page=key)` into the day JSON. Wired from gram studio, Ledger Log, feed article note dialog ("❝ Pickings"), book highlight menu ("❝ Add to Pickings").
- **Feed Pickings**: feed mode `pickings` + submenu entry; lists boards (with grams/ink) across 60 days as rows; tap → `ledger://pickings/<date>/<key>` jumps to the board (`openEntry` special-case).

### iOS Cluster A — Ask my Ledger (DONE, simulator BUILD SUCCEEDED)
- `LedgerLLM.swift`: `ask()` gained `persona:` param + create-capability instruction (```ledger-create block, today's date). Appended `Persona`/`PersonaStore` + `ChatTurn`/`ChatHistoryStore` (UserDefaults).
- `LedgerCorpus.swift`: rewritten with `CorpusSnippet` + `gather()` (adds tasks/events + Later List) + **hybrid** `buildContext(query:)` (cosine 0.7 / keyword 0.2 / recency 0.1); appended `EmbeddingIndex` (OpenAI text-embedding-3-small, djb2 cache under Documents/ledger-embeddings). Reuses `ai_key_openai`.
- `AskLedgerView.swift`: persona Menu, 🎤 dictation (`SpeechDictation` SFSpeechRecognizer), 🕘 history sheet, Save-to-feed (`IntakePageStore.publish`), and `applyLedgerCreates()` (task/event → ledgerItems via `saveDay`; note → Later List `fileLink`). `Info.plist` gained `NSSpeechRecognitionUsageDescription`.
- New files avoided (project uses explicit refs) — all types appended into existing project files.

### iOS Cluster B — Media & Feed (DONE, simulator BUILD SUCCEEDED)
- `SpeechReader.swift`: added `playAudio(url:title:)` — local files via existing `AVAudioPlayer`, remote streams via new `AVPlayer` (+ end observer). `togglePlayPause`/`stop` updated to handle it. Mini-player "just works" (nowPlaying/isSpeaking).
- `MinifluxModels.swift`: `MinifluxEntry.audioURL` computed (audio enclosure by MIME/ext, else direct-audio entry URL). Enclosures were already parsed.
- `FeedLedgerView.swift`: row context menu gains "Play audio" when `entry.audioURL != nil` → `SpeechReader.shared.playAudio`.
- `Bookshelf.swift`: `LocalBook.isAudio` + `audioExtensions` (mp3/m4a/m4b/aac/wav); `reload()`/`localBook`/`importTypes` widened; grid tap routes audio → `playAudio` instead of the epub reader.
- NOT done in B: Add-to-Later media auto-download (playback streams for now); podcast Later uses kind "read"; sidebar recent-book tap doesn't route audiobooks (only the shelf grid does).

### iOS Cluster D — Text Notes + Transcribe (DONE, simulator BUILD SUCCEEDED)
- `LaterListView.swift`: appended `TextNote`/`TextNotesStore` (multi-note per day, JSON under Documents/text-notes) + `TextNotesView` (title+body editor, ≡ Notes switcher, ＋ new).
- `SidebarMenu.swift`: `onOpenTextNotes` + "Text Notes" button in Daily Ledgers. `PlannerShell.swift`: `showTextNotes` + `.sheet { TextNotesView() }`.
- `LedgerLLM.swift`: appended `Transcribe` (Whisper multipart) for A/V grams.
- `LedgerCorpus.swift`: Text Notes now gathered into the corpus (searchable).
- `AskLedgerView.swift`: note-create now → `TextNotesStore.addNote` (parity with Android).

### iOS Cluster C — Synthesize (PARTIAL, simulator BUILD SUCCEEDED)
- `Bookshelf.swift` reader menu: **Synthesize · 3 questions** and **Essay outline → Notes** — `controller.readerText` → `LedgerLLM.run` → `TextNotesStore.addNote`. (Book side of the Synthesize→Write loop.)
- STILL TODO on iOS: the Pickings epic (multi-page named boards, cross-surface gram placement, Feed Pickings), day-page Synthesize→Write (place questions/outline as on-page text boxes), and A/V gram transcription → pickings. These are the largest, most UI-heavy iOS pieces (touch PlannerModel/PageStore/pickings templates/GramStudioView) — best built on a device-verified base.

### iOS TestFlight builds
- **Build 9** shipped (Clusters A/B/C-reader/D + pickings placement/transcription/Feed Pickings).
- **Build 10** shipped — test-feedback fixes: book selection menu "Gram → Pickings" (`HighlightWebView.onGram` → `EpubReaderView` → screen renders card → `PickingsGram.place`); Notes&Annotations card TAP menu now has Share-as-gram + Add-to-Pickings, long-press gained Add-to-Pickings (`ReadingTimelineView` confirmationDialog + contextMenu + `pickingsCard`); Later List sidebar → DisclosureGroup with The Read/Watch/Listen (`LaterListView.kind` filter + `onOpenLaterList: (String?)->Void` threaded through PlannerShell `laterKind`); **Feed Pickings = explicit marking** (`FeedPickingStore` by day-string): feed "Add to Feed Pickings" places + marks; pickings page tools menu "Save as Feed Picking" toggles; `FeedStore.pickingsFeedEntries` lists marked days. Build via `sed CURRENT_PROJECT_VERSION` bump + archive/exportArchive/altool (apiKey B28W2Y3P38).

### iOS Cluster C tail — Pickings epic (MOSTLY DONE, simulator BUILD SUCCEEDED)
- **Cross-surface gram → Pickings** (`ElementsView.swift` `PickingsGram.place`): writes an `ImageElement(page:"pickings")` into the day via `LocalCalendarStore`. Wired from: gram studio ("Pickings" button), feed row menu ("Add to Pickings" → QuoteCard), book reader menu ("Selection → Pickings" via new `controller.selectionText`).
- **A/V gram transcription** (`Transcribe` was greenfield-unused): `PlannerShell.addAVGram` now transcribes audio/video (Whisper) into `TranscriptStore` (side JSON, keyed by attachment id — keeps the Boox `Attachment` model intact). Transcripts added to the corpus (`LedgerCorpus.gather` avGrams loop) → searchable.
- **Feed Pickings** (`FeedStore.feedPickings` source): synthetic feed listing days whose pickings board holds grams, rendering the gram cards inline (base64 `<img>`). Sidebar button in `feedLedgerSection`. `FeedOfflineCache.key` case added.
- STILL TODO: **multiple NAMED pickings boards** — storage already supports arbitrary `pickings-<id>` keys, but navigation/rendering is `PlannerPage`-enum-locked; needs a `selectedBoard` override threaded through `pageKey`/`noteSuffix`/`PageZones` + a board switcher. High-risk without device verification — deferred. (Placement currently targets the single default "pickings" board.) Also deferred: day-page synthesize-to-textboxes (reader synthesize→Text Notes is done).
Only the 3 structural fixes are done + compiled (Local Feeds into Feed submenu, "For Later" rename, book exit button). The whole Android feature set above + earlier (media player, podcasts, Add-to-Later, audiobooks, Text Notes, OCR, hybrid search, persona, Ask-create/history, pickings epic, synthesize, A/V grams) is NOT yet ported to Swift. Seams identified in the iOS map (MiniPlayer/SpeechReader, LedgerCorpus, LedgerLLM, Bookshelf, SidebarMenu, PlannerModel).

Still open (feature directions, not yet built): section-as-WebView-note + full OCR (Ledger Log/notes web view), Simplenote-style text capture surface, dedicated OCR-model picker (Sonnet/Haiku), smart-feeds/search iPad-parity (they ALREADY exist on Android in the RSS directory drawer — "🔍 Search…" row + "Smart Feeds" folder — confirm what differs vs iPad).

---



Context: a long session that unified the navigation/UX across the Boox (toolsboox)
list surfaces and brought iOS to parity. Michael is continuing on **Android** and
has been testing on-device. Everything below is committed to the working tree on
branch `feature/michaelfilter-intake` (not git-committed unless he asks).

## Devices & install

**Android (adb, `~/Library/Android/sdk/platform-tools/adb`)** — package `com.toolsboox.mjh.debug`:
| adb id | device |
|---|---|
| 221AA5FE | Boox Tab8 |
| 881f0a48 | Boox Palma2_Pro_C |
| 7F1C7889 | Boox Palma2 (drops off the bus often) |
| E5E30C46 | Boox Tab X |
| DAF39E26 | Boox Go 6 (Go6_2) |

Build + install (installs to whichever are on the bus):
```
cd ~/toolsboox-android && ./gradlew :app:assembleProdStandardDebug
APK=$(ls -t app/build/outputs/apk/prodStandard/debug/*.apk | head -1)
for d in $(adb devices | grep -w device | cut -f1); do adb -s $d install -r "$APK"; done
```
Devices come and go on USB; re-run for whichever reconnect. Gradle/sourcekit
"consecutive statements" diagnostics are NOISE — trust the actual build output.

**iOS (`~/ledger-ipad`, Xcode)** — "Maxi" iPad Pro 11", coredevice `FE419C5A-64BC-5C47-BEE8-8108BE0E9D29`:
```
xcodebuild -project LedgerIpad.xcodeproj -scheme Ledger -configuration Debug \
  -destination 'generic/platform=iOS' -derivedDataPath build-device -allowProvisioningUpdates build
xcrun devicectl device install app --device FE419C5A-64BC-5C47-BEE8-8108BE0E9D29 \
  build-device/Build/Products/Debug-iphoneos/Ledger.app
```
TestFlight (last shipped = **build 8**): archive → exportArchive (ExportOptions in
`build-device/ExportOptions.plist`) → `xcrun altool --upload-app -f build-device/export/Ledger.ipa -t ios --apiKey B28W2Y3P38 --apiIssuer 0b0e2f64-0e27-43c9-8033-690c36b46f5f`.
Only ship to TestFlight when Michael says "ship it". iPhone can't sideload (free
team, device not registered) — use TestFlight there.

## The unified nav language (applied to Feed Ledger, Notes & Annotations/AV, Tasks & Events)
- **Almanac navigator strip on top** — the REAL day navigator (`CalendarNavBarHost` →
  `navigatorImageView`), NOT a look-alike ladder. On list surfaces, tapping a
  Day/Week/Month/Quarter/Year **slot FILTERS** the list to that period (via the new
  `onSelectPeriod` callback in `CalendarDayNavigator.onTouchEvent`), instead of
  jumping into the calendar. Arrows step by the selected granularity.
- **Floating nav pill** — `‹ up · ☀ today-then-menu · down ›`. Center button:
  first tap → back to today; second tap (already today) → the section menu
  (`showAccordion(ledgerDirectoryFolders(this))`). Up/down paginate the list (or the
  in-pane article when one is open). Grip drags/collapses.
- **Hamburger (☰) menu anchor on the LEFT**, on the nav line left of the ‹ carrot.

## Feed Ledger (`plugin/feeds/ui/FeedsFragment.kt`, `fragment_feeds.xml`)
- **Two-pane**: left directory DRAWER (feeds + counts) toggled by the **top-left RSS
  button** (`ledger_button`, `applyDirectoryDrawer`, persisted `KEY_DIR_OPEN`) + the
  article list. Header is one line: `[RSS] ‹navigator›`, no title.
- **In-pane article reader** (`openEntry` → `article_pane` + `article_web`; `Back`
  and system-back return via `articleBackCallback`). Selecting a feed from the left
  directory while reading closes the article (row onClick closes pane).
- **Pill article actions** (visible only while reading, `setArticleActions`):
  `feeds_star` (star + log ReadingEvent), `feeds_note` (`noteArticle` reads the WebView
  selection then a note dialog → `logArticleEvent`), `feeds_parsed` (reader-view ↔
  original toggle, `showParsed`/`renderArticle`), `feeds_tts` (`toggleArticleTts` via
  `LedgerTts`).
- **Wrench** (`settings_button`, in the pill left of ↑) → `showFeedSettings()` DIALOG
  (URL · token · "Reader view by default" · Save · Refresh). It does NOT close the
  article (was previously an inline panel hidden behind the article pane — that was the
  "not responding" bug).
- **Date filter**: `onDateChanged` — today's day = live `feed` (unread); any other
  window = `read` timeline filtered by `filterByNavDay`/`navWindow`. Miniflux has no
  date-range query, so old windows are limited to the ~100-entry read window.
- **Offline cache** (`plugin/feeds/nw/FeedCache.kt`): on Ok fetch, saves entries (with
  unparsed content) per `cacheKey()` + background `prefetchParsed` (readability HTML per
  entry). On Err (offline), falls back to cached entries. In-pane reader prefers cached
  PARSED, falls back to unparsed. Later List is inherently offline (local intake sidecar).
- Smart feeds (`SmartFeedStore`), server search (`MinifluxClient.search`), local RSS,
  OPML — from earlier.

## Notes & Annotations / AV (`plugin/calendar/ui/ReadingLogFragment.kt`, `fragment_reading_log.xml`)
- **Day-based** now (the Range ladder was removed; `range` fixed to DAY, driven by the
  navigator). Navigator strip on top; slot taps map to Range via `onSelectPeriod`.
- Header: `[☰ hamburger] ‹navigator›` on the nav line; title below.
- **Floating pill**: actions on the LEFT (`origin_button` filter · `gram_button` capture ·
  `export_button`), **nav group `‹up · ☀ · down›` on the FAR RIGHT**. Center =
  today-then-menu.
- Item detail popup → **"Go to"** navigates to source: article → **in-app Feed Ledger
  reader** (`FeedSelection.pendingInPaneEntry`, opened on FeedsFragment view-created),
  book → reader, picking → that day's Pickings, AV → that day.

## Tasks & Events (`plugin/calendar/ui/LedgerItemsFragment.kt`, `fragment_ledger_items.xml`)
- Title removed; calendar/menu button on the nav line left of the navigator.
- Floating pill added (up/☀/down + today-then-menu).

## Section menu (`ui/plugin/ScreenFragment.kt` `showAccordion`)
- **Outline** (rounded GradientDrawable stroke) around an expanded dropdown for clarity.
- Sizes to content: `lp.height = WRAP_CONTENT` (inner ScrollView still scrolls if taller
  than screen) — was a fixed 0.72×screen cap.

## Stylus fix (`ui/plugin/SurfaceFragment.kt`) — verify on-device
Root cause: Onyx `TouchHelper.setLimitRect(limit, ArrayList())` used an EMPTY exclude
list, so the raw pen reader owned the whole surface — stylus inked over the floating
pills and couldn't drag them (finger worked via `enableFingerTouch`). Fix: new
`provideExcludeViews()` (overridden in `CalendarDayFragment` = nav+tool pills, and the
4 almanac fragments = nav pill) feeds pill bounds as exclude rects via `rawExcludeRects()`
into both `setLimitRect` call sites; `refreshRawExcludeRects()` re-applies on pill
drag-end (wired in `makeDraggable`).

## Other
- **Reader** (`plugin/reader/ui/ReaderFragment.kt`): empty-state "Add a book" button
  (`reader_empty`/`reader_add_book`); sunshine (`today_button`) → section nav
  (`showLedgerDirectory`) instead of jumping to the day page.
- **Pickings template**: removed the `drawZones(...)` call (~CalendarDayFragment:1408) so
  the original template shows with NO zone boxes/labels/small-image outlines. Zone
  CAPTURE still runs (`captureSections` uses `PageZones.zones` directly). WebDAV syncs
  full-page strokes, never a cropped/rendered template.

## iOS parity (`~/ledger-ipad`)
iOS already had the reader actions (parsed toggle `toggleParse`, TTS `toggleSpeech`,
star, note), offline cache, smart feeds, account types (Fever/GReader/Feedbin — untested
against live servers), no-AI search, annotation export, dictionary/define, footnote
popups, feed date time-filter (NavigatorBar), Pickings zone-lines removed, keyboard
shortcuts, badge/BG-refresh/widget/notifications, drawer toggle, stepper today-then-menu.
Added this session: **`FeedStore.prefetchContent`** (offline parsed prefetch, called from
`ArticleListScreen.load`). Maxi has the latest. The Android floating-pill / navigator-strip
/ in-pane paradigms map to iOS's NavigationSplitView + toolbar — no functional gap to port.

## Known limitations / not done
- Later List's *external linked pages* aren't pre-downloaded (only the typed text/links,
  which are already local).
- Gram-studio share dialog "Go to source" intentionally OMITTED (you're already on the
  source when creating a gram there — Michael agreed it's redundant).
- The 3 new iOS account backends (Fever/GReader/Feedbin) are compile-verified only, never
  tested against a live server — see `[[project_ledger_feed_backends]]` memory.
