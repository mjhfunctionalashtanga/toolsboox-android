# DESIGN — Bigme ink convergence (2026-08-22)

The night's sixteen builds, four deep readers (inkit cover-to-cover, inksdk's reference
host, an adversarial review of our own path, and a sweep of the public corpus), and
Michael's field reports converge on one architecture. This document is the spec for the
convergence build and the record of why.

## The verdict

inkit works on the B6. The daemon is fine. Ledger's Bigme path failed because it broke
five disciplines the working integration keeps — and because it ran TWO state machines
(live-draw and capture) that could disagree about one pen.

## The five inkit rules (from the source, verified)

1. **Silence while the pen is down.** The host draws nothing and posts nothing between
   DOWN and UP. `onStrokeMove` is a list append. The daemon owns every pixel of the live
   stroke. (Host UI updates during writing poison daemon latency: measured 5ms → 750ms
   at 30Hz updates.)
2. **One surface post per stroke, at pen-up, after a full window rebuild.** One
   `lockCanvas` site in the whole app.
3. **Every commit point SRC-blits the full canonical WINDOW BITMAP into the daemon's ION
   buffer.** The buffer never accumulates; it is a mirror, not a scratch layer. The
   window bitmap is WHITE-based (white + background/template + ink) — the overlay keys
   on white (white = SurfaceView shows through, ink occludes), so a white-based mirror
   renders correctly under any compositing rule and carries the whole page across the
   system e-ink refresh.
4. **`force` (inValidate GU16) sparingly and precisely**: pen-stroke end = blit only;
   eraser end, page load, clear, undo, scroll = blit + full-view GU16. Never GC16,
   never HANDWRITE for corrections.
5. **One pen, one router**: stylus MotionEvents hard-dropped while the daemon is
   attached; daemon FINGER events dropped entirely (fingers belong to the app's raw
   touch); out-of-bounds stroke starts (daemon reports toolbar taps at negative coords)
   dropped before they touch undo or capture; daemon input refcount-disabled around
   EVERY occluding UI surface ("the daemon dispatches pen events by view bounds, not
   Z-order").

## The adversarial findings (ours to fix, ranked)

1. **Stroke-weld** — the "same stroke?" gate reused the garbage-jump radius (¼ of the
   view diagonal ≈ 585px). Consecutive strokes within 80ms + that radius got welded
   with a straight connector: THE phantom straight lines. Fix: separate small
   `WELD_RADIUS` (~48px view) for bridge/dedup decisions; the big radius only rejects
   garbage.
2. **penIsDown wedge** — `syncOverlay` silently skipped while a stale `penIsDown` held;
   mirror went stale; system refresh showed the stale buffer (ink "vanished" while
   counters stayed perfect). Fix: timeout-heal `penIsDown`, clear it on
   `setInputEnabled(false)`, and defer (not drop) a blocked sync.
3. **No convergence after blit** — quiet mirror relies on the next compose; repeated
   system refreshes alternate show/gone. Fix: full-view GU16 after the mirror blit
   (safe now: the buffer carries the full white-based page, so the refresh reveals the
   page, not a wipe).
4. **Pager dialogs bypass the pen-pause** — `showNotePageJump` / `showNoteSubPageJump` /
   `showNoteDatePicker` call `dialog.show()` directly; `onModalShown` never fires; the
   daemon eats the pen; the dialog "freezes". Fix: route through `showModal`, and drop
   Bigme pen events inside `provideExcludeViews()` bounds (the pager buttons).
5. **No liveness escape** — if the daemon dies/wedges, the swallow guard eats raw input
   forever: dead pen. Fix: watchdog — raw stylus swallowed but no daemon event for N
   seconds → detach → software capture resumes automatically.
6. **Triple render + transform storms** — bake draws everything twice + full-page blit;
   pan/pinch did it per finger-move with `clearPage=true`. Fix: transform-only renders
   blit the existing shadow, no rebuild; bake stays debounced at pen-pause.
7. **THE CRASH** — onPause commits the in-flight stroke AFTER removing
   `commitVisualRunnable`, re-arming it against a dead view 700ms later. Fix: commit
   first, then teardown; and the runnable bails when the view is gone.
8. **Split brain** — `drawLiveSegment` had none of the capture bracket's healing (bogus
   mid-stroke UP = live line dies; "first portion only"). Fix: ONE bracket state
   machine feeds both the live draw and the capture.
9. **Page-open dead zone** — 350ms attach debounce + 300ms wall-clock grace ate early
   ink. Fix: grace by daemon timestamps (arg 6: events stamped before attach are the
   flush), not wall clock.
10. **Live ink in non-ink modes** — lasso drags / toolbar-eraser / image mode painted
    live black lines. Fix: live-draw gate driven by the same mode state that pauses
    Onyx ink.
11. **Hygiene** — volatile the fields the binder thread reads; pref-disable detaches;
    `updateGeometry` re-mirrors after its wipe.

## The build (one client, one bracket, one bitmap)

- `BigmeFastInk` keeps: one client per surface life, update-in-place rotation
  (re-mirror after), dead-switch, white-based `syncOverlay(page)` + GU16, live drawing
  from the SAME healed bracket the capture uses, tap = dot + tight invalidate.
- `SurfaceFragment` Bigme path: no software preview during strokes; one bake per
  pen-pause building the white window composite (white + template visible through white
  + elements + ink); mirror at every commit point; out-of-bounds drop; finger events
  from daemon ignored; modal pen-guards via `showModal` everywhere; watchdog.
- Instruments stay: InkPerf counters, CrashRecorder, the long-press report.

## Facts ledger (for the next session)

- Overlay keys on WHITE (not alpha) across this family; ION never auto-clears; buffer
  may be view- or panel-sized by firmware; `getContent()` may be null (Canvas.mBitmap
  fallback needs HiddenApiBypass).
- Touch IC sleeps after ~1–2s; first stroke after a pause pays 80–170ms; unfixable;
  judge by p99/SLOW_STROKE, not p50. inkit itself hiccups here — the parity bar.
- Coords arrive view-local (daemon convertXY); 6th arg = daemon CLOCK_REALTIME ns;
  pressure = int 0..4096.
- Never sync the overlay mid-stroke; never inValidate more than ~1/16ms; AA off on
  every paint that reaches the daemon path; GU16 multi-batch caused a hard reboot once
  (single full-view calls only).
- Public corpus = the three repos; nothing on the B6 specifically; one silent-failure
  report on older Android-11 xrz firmware (inkit #2). Michael's reports are the
  frontier.
