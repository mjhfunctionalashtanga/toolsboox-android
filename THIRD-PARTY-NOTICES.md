## Seeneva — speech-balloon detection model

`app/src/main/assets/yolo_seeneva.tflite` is the speech-balloon/panel detection model from
the **Seeneva comic book reader** by **Sergei Solodovnikov**, licensed **GPL-3.0-or-later**
(the same license as this application), lifted with attribution per that license.

- Source: https://github.com/Seeneva/seeneva-reader-android (model in `logic/src/main/assets/`)
- Project: https://seeneva.app/
- Ledgable runs it in a background pass when a comic is opened (see
  `plugin/reader/ot/BalloonIndex.kt`), starting at the page on screen. The SPEECH_BALLOON class
  drives the balloon walk; since 2026-10-11 the PANEL class is stored in the index beside it but
  not yet walked. Each balloon's words come from ML Kit's on-device Latin text recogniser, not
  from Seeneva's code.

## Onyx HWR service interface (research credit)

The binding to the Boox firmware's handwriting-recognition service
(`app/src/main/aidl/com/onyx/android/sdk/hwr/service/`, `HwrParcelables.kt`, `ot/OnyxHwr.kt`)
is an independent implementation of Onyx's own service wire. The interface facts — component
name, AIDL transaction order, parcel layout, and the HWRInputProto field numbers — were
learned from the interface research published by the Aragonite project
(https://github.com/jdkruzr/aragonite, GPL-3.0), which is gratefully credited as
documentation. No Aragonite code is included in this application.

## foliate-js — the reading engine

`app/src/main/assets/foliate/` is **foliate-js** by **John Factotum**, licensed **MIT**, with
Ledgable's own changes (among them `comic-book.js`'s page order and saved-position mapping,
2026-10-11) and additions (`reader-embed.js`, `comic-gestures.js`, `comic-panels.js`).

- Source: https://github.com/johnfactotum/foliate-js

```
MIT License

Copyright (c) 2022 John Factotum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## zip.js — the zip reader foliate-js opens CBZs with

`app/src/main/assets/foliate/vendor/zip.js` is **zip.js** by **Gildas Lormeau**, licensed
**BSD-3-Clause**, as vendored by foliate-js. `plugin/reader/ot/CbzDirectory.kt` decodes member
names exactly as zip.js does, so its CP437 table is zip.js's table, transcribed (2026-10-11) —
the comic balloon index and the reader must name every page alike.

- Source: https://github.com/gildas-lormeau/zip.js

```
BSD 3-Clause License

Copyright (c) 2023, Gildas Lormeau

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Reading fonts (Aa Type) — SIL Open Font License 1.1

The faces the book and article readers can set text in (Aa Type, 2026-10-11) are all under the
**SIL Open Font License, Version 1.1**, used unmodified. Full license text and each font's
copyright line: `app/src/main/assets/fonts/OFL.txt`; each font also carries its notice in its own
name table.

- `app/src/main/assets/fonts/`: **Literata** (The Literata Project Authors / Google, designed by
  TypeTogether), **Vollkorn** (The Vollkorn Project Authors, Friedrich Althausen), **Inter**
  (The Inter Project Authors), **Jost** (The Jost Project Authors, indestructible type*),
  **Poppins** (The Poppins Project Authors, Indian Type Foundry) — the same files the iOS app
  ships.
- `app/src/main/res/font/` (already in the app, now also served to the readers):
  **Atkinson Hyperlegible Next** (The Atkinson Hyperlegible Next Project Authors), **Fast Sans**
  (Inter-derived) and **Fast Mono** (Fira Code-derived) from the Fast Font project by Born2Root.

Not offered to the readers: **Fast Serif** (`res/font/fast_serif.ttf`). Its name table names a
Dalton Maag Ltd. EULA (daltonmaag.com/eula) that requires written permission to modify, and the
file is itself a modification — so its license could not be verified as OFL/Apache.

Fonts a reader imports with "Import font…" are copied into the app's private storage on that
device only and are never redistributed.

## The lineage — apps that taught this one

Ledgable stands on the shoulders of open projects whose ideas, research, or code shaped its
surfaces. Beyond the license-bearing notices above, these deserve naming:

- **toolsboox** (github.com/gaborauth/toolsboox, GPL-3.0) — the trunk this fork grew from.
- **Readest** (github.com/readest/readest) — note-on-the-selection, the quiet reading footer,
  and the standard the book reader is measured against; Aa Type's vocabulary (one view-settings
  model, override switches, custom font faces) follows theirs. Ideas only — Readest is AGPL-3.0
  and none of its code is in this application.
- **foliate-js** (github.com/johnfactotum/foliate-js) — the reading engine itself.
- **Capy Reader** (github.com/jocmp/capyreader) — the always-full-content-per-feed lesson and
  the bar for a humane RSS reader.
- **AntennaPod** (github.com/AntennaPod/AntennaPod) — the podcast grammar: auto-advance into the
  next unheard episode, the sleep timer, continue-listening, headless episode keeping.
- **Podcast Republic** (podcastrepublic.net) — the per-show lesson: a show you always play at
  ×1.5, a show whose first 45 seconds are always the same jingle. Design studied, no code.
- **Handy News Reader** (github.com/yanus171/Handy-News-Reader) — the offline-first article
  lesson: once updated, nothing left to load — the pictures come along.
- **Mihon extensions-lib** (github.com/mihonapp/extensions-lib, Apache-2.0) — the extension ABI,
  vendored under `eu/kanade/tachiyomi/` with real bodies written for this host (the published
  library is compile-time stubs); HttpSource/ParsedHttpSource semantics mirrored from the Mihon
  app (Apache-2.0). This is what lets keiyoushi extension APKs dex-load and run in Ledgable.
- **keiyoushi/extensions** — the community extension index and APKs the 🧩 Comic sources door
  browses and installs. The extensions themselves are their authors'; Ledgable only runs them.
- **EinkBro** (github.com/plateaukao/einkbro, GPL-3.0) — the e-ink browsing grammar The Net
  is built to: no animation, everything one tap deep, the page is the unit — and the
  save-web-as-EPUB lesson (our writer, their idea): a kept page becomes a book on the shelf;
  and Aa Type's import-your-own-TTF/OTF and bold boost (their idea, our code).
- **Notate** (github.com/alexdremov/notate) — draw-and-hold shape snapping, taken whole as an
  idea: the dwell is the ask.
- **Notable** (github.com/olup/notable) — two-finger-tap undo, three-finger redo.
- **Aragonite** (github.com/jdkruzr/aragonite, GPL-3.0) — the interface research that opened
  the firmware's MyScript recognizer (see the Onyx HWR notice above).
- **Saber** (github.com/saber-notes/saber) — folders as a lens over a flat notebook, recents
  always first.
- **Seeneva** (github.com/Seeneva/seeneva-reader-android, GPL-3.0) — the speech-balloon
  detector (see its notice above).
- **Mihon** (github.com/mihonapp/mihon, Apache-2.0) — the chapter walk: finishing a comic offers
  the next; and two comic touch rules (2026-10-11): a tap at the edge of a zoomed page pans by one
  window before it turns (written, parked until it is asked for), and a hold right after a tap is
  not a long-press; and a comic's pages in NATURAL order (`ArchivePageLoader`), so `page2` comes
  before `page10` and a `.JPG` page is not dropped (2026-10-11, `CbzDirectory.kt` and
  `assets/foliate/comic-book.js` — unlike Mihon, case is not folded, so the iPad and the Boox
  number a book's pages alike). Ideas only, no code.
- **Subsampling Scale Image View** (github.com/mihonapp/subsampling-scale-image-view, Apache-2.0)
  and **DirectionalViewPager** (github.com/tachiyomiorg/DirectionalViewPager, Apache-2.0) — the
  rest of the comic touch grammar in `assets/foliate/comic-gestures.js`: a new first finger
  resets every gesture, a pan never hands off mid-gesture, and a page turns on distance OR speed
  (25 dp at 400 dp/s). Ideas and thresholds only; the code is ours.
- **Thunderbird for Android / K-9 Mail** (github.com/thunderbird/thunderbird-android) — the
  measure for the mail room.
- **WordPress for Android** (github.com/wordpress-mobile/WordPress-Android) — the measure for
  the publishing doors.
- **Simplenote** (github.com/Automattic/simplenote-android) — the notes companion off-device,
  and the Simperium wire the coming bridge speaks.
- **Miniflux** (github.com/miniflux/v2) — the feed server behind Incoming.

Thank you — every one of you made this notebook better.
