# The Period Walk — 2026-08-27, Michael's evening rulings

His words: "If you were to be on the Weeks almanac/today page and used the page skipper
to go down, it would take you to the all stars for the week and all picks for the week
and then if you go down again it would take you to note for that week. Similar for the
day/month/quarter." · "Creating a new note on the day would allow the person to name the
note, or it would be named for the day with a -1 or similar such -2, -3, etc" · "each of
those pages can have a template of the users selection" · "a new note can use another
template."

## 1. THE MODEL

The daily ritual walk (Today → All Stars → All Picks → Today's Note) is not a daily
feature — it is THE walk, and every granularity gets it:

```
Day:     Today page   → All Stars · TODAY   → All Picks · TODAY   → today's note
Week:    Week page    → All Stars · WEEK N  → All Picks · WEEK N  → that week's note
Month:   Month page   → All Stars · <MONTH> → All Picks · <MONTH> → that month's note
Quarter: Quarter page → All Stars · QN      → All Picks · QN      → that quarter's note
Year:    Year page    → All Stars · YYYY    → All Picks · YYYY    → that year's note
```

- **All Stars · period** — the intake renderer already draws scoped windows
  ("ALL STARS · WEEK 30" via `scopedGrams`/`scopeLabel`). The station reuses that
  scoped read over the period's days. Read-only aggregation; grams still LIVE on days.
- **All Picks · period** — same aggregation over the days' `grampicks` pages.
- **Period note** — day: existing; week/month: the -22 promoted surfaces; quarter:
  `toQuarterNote` and `toYearNote` exist dormant, same promotion.

## 2. NAMING (ruling 2)

Minting a note on a day offers the NAME PAD (existing promptTitle); left blank, the
note auto-names `<day> -1`, `-2`, `-3`… (extends LedgerDefaultName's collision rule —
display name only, keys unchanged).

## 3. TEMPLATES (rulings 3 + 4)

- New note mints on ANY template — the ＋ New note HOLD chooser (shipped -25) is the
  deliberate hand; the tap stays last-paper.
- Per-page ▤ extends to every walk page: week/month/quarter/year notes and the period
  All Stars/All Picks faces record templates against their own file identity
  (NotePageTemplates rows are `key|date`-shaped; the period key is the identity).

## 4. PHASES

1. **-28** Day-suffix naming + name-pad-on-mint (ruling 2 + 3 polish).
2. **-29** Week walk: skipper on the week page steps Week → All Stars·wk →
   All Picks·wk → week note; ▤ on the week note.
3. **-30** Month + quarter + year walks (same wiring, the rest of the ladder).
4. iOS twin — gated on SPEC-week-month-notes-2026-08-27.md and the Boox field verdict.

## 5. WHAT THIS DOES NOT TOUCH

Day JSON, stores, keys, sync. Period stations are POINTERS over the days' stores
(the Notebook principle); the walk is navigation, not storage.
