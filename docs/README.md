# ViBook Planner (Tools for Boox fork)

A fork of [Tools for Boox](https://github.com/gaborauth/toolsboox-android) by [Gabor Auth](https://github.com/gaborauth), optimized for daily planning on Boox e-ink tablets with pen color support, full-screen drawing, pinch-zoom for smaller devices, home screen widgets, and [Ultrabridge](https://github.com/jdkruzr/ultrabridge) integration for handwriting OCR and task sync.

## What's different in this fork

### Drawing & navigation
- **Full-screen calendar** — no top toolbar, no dashboard; the planner page fills the entire screen
- **Pinch-zoom + two-finger pan** — the 1404x1872 canvas renders at native resolution on any device. Pinch to zoom (up to 4x), two-finger drag to pan, double-tap to toggle 2x zoom / reset. Designed for the Palma 2 Pro where the page would otherwise be cramped. Strokes are stored in canvas space, so zoom never distorts handwriting or affects export/sync.
- **Two-finger swipes for navigation** — left/right to change days, up/down for week/notes. Single-finger gestures are ignored to prevent accidental swipes while resting your hand on the screen.
- **Pen color picker** — tap the pen while active to choose Black / Red / Blue / Green and Fine / Med / Thick / Bold
- **Red ink = tasks** — write in red and [Ultrabridge](https://github.com/jdkruzr/ultrabridge) automatically creates a todo from your handwriting
- **Lasso, copy, paste** — select strokes, move them around, paste with per-stroke color persistence
- **Undo / Redo** — 50-step history
- **Collapsible toolbar** — grey strip when collapsed, tap to expand; keeps the drawing area clean

### Home screen widgets
Three widget variants render today's planner page directly on the home screen, auto-refreshing every 30 minutes and immediately whenever you save strokes:

- **ViBook Planner** — full planner page (schedule + tasks + notes)
- **ViBook Schedule** — schedule column split horizontally: morning (5am–1pm) on the left, afternoon (1pm–10pm) on the right
- **ViBook Tasks/Notes** — right column rearranged side-by-side: tasks on the left, notes on the right

All three include your handwritten strokes and overlay device calendar events. Tap any widget to open the app to today's page.

### Sync & export
- **Google Drive sync** — bidirectional auto-sync on page load and close
- **WebDAV backup** — PDF + raw JSON upload to Ultrabridge (or any WebDAV server)
- **JSON export** — day page stroke data syncs to `ToolsForBoox/json/` for downstream processing (OCR, task extraction)

### Polish
- **Atkinson Hyperlegible font** — optimized for e-ink readability
- **No ads, no Firebase** — stripped completely
- **Lighter alternating cells** — better contrast on e-ink displays

## Ultrabridge Integration

When paired with the [Ultrabridge Ledger Processor](https://github.com/mjhfunctionalashtanga/ultrabridge-ledger), your handwritten planner pages get:

1. **Full-page OCR** via Claude Sonnet — schedule, tasks, and notes transcribed
2. **Automatic task creation** — unchecked items in the Tasks section become CalDAV todos
3. **Due date parsing** — write "due 6/15" in a task and it becomes a real due date
4. **Sync everywhere** — tasks appear in Apple Reminders, tasks.org, Thunderbird, or any CalDAV client

## Build

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

./gradlew assembleDevDebug

# Install on connected device
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/dev/debug/toolboox-dev-debug-*.apk
```

## Setup

1. Install the APK on your Boox tablet
2. Open the app — it goes straight to today's calendar page
3. To enable sync: tap the toolbar → Settings
   - **Google Drive**: sign in for bidirectional cloud sync
   - **WebCal Backup**: enter your WebDAV URL + credentials for PDF and JSON backup

## Credits

This is a GPLv3 fork of [Tools for Boox](https://github.com/gaborauth/toolsboox-android) by [Gabor Auth](https://github.com/gaborauth). Please support the original project:

- [Original repo](https://github.com/gaborauth/toolsboox-android)
- [Google Play](https://play.google.com/store/apps/details?id=com.toolsboox)
- [Patreon](https://www.patreon.com/toolsboox)
- [PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=SVJ9HDCVKAAKS)

## License

GPLv3 — same as the original. See [LICENSE](../LICENSE).
