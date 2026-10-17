# DESIGN — Sharing INTO Ledgable (Michael's ruling, 2026-08-22 late)

> "Later list works as a local Instapaper, but also for video and audio also.
> Sharing into Ledgable on iOS looks so good with all of the gram options. I want
> that for Android. But I also want to be able to still add to Ledgable just like
> Instapaper for read later. Different share-into options from sharing."

## The model — two lanes in, distinct from the lanes out

Share-INTO options are their own surface, not the mirror of share-out. Two lanes:

1. **⏳ Later — the local Instapaper.** One tap from any share sheet, zero
   ceremony, silent save. And not just articles: **video and audio too** — a
   YouTube link, a podcast episode, a page all land the same way. The Later list
   is the read/watch/listen-later shelf, local-first.
2. **🖼 The gram lane — iOS's rich share, brought to Android.** The iOS
   share-into flow with all the gram options (which page it lands on, how it's
   placed) is the bar; Android's share-in gets the same richness.

Both lanes ride the existing SEND intent filters; the chooser inside Ledgable
offers the lanes, so the system share sheet stays one entry ("Ledgable") and the
fork happens in our own chrome — fast default (Later) reachable without reading,
rich option (gram) one tap deeper. Exact chrome per the no-modes canon.

## Notes

- Known bug folds in here: "Later-list shares/saves don't import" (the 08-17
  queue) — fixed as part of this lane, not separately.
- Later's media breadth composes with the Listen/Watch machinery (Read Aloud,
  the player) — a Later item knows whether it reads, plays, or watches.
- Naming: Michael says "Ledgable" — the share-sheet label should say what he
  says.
