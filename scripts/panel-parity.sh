#!/bin/sh
# One panel language, both apps (Michael 2026-10-11): the seat table each app renders must be the
# same text. Diffs this repo's golden against the iOS repo's twin; run before every paired build.
#   IOS_REPO=/path/to/ledger-ipad-correspondence scripts/panel-parity.sh
set -eu
here="$(cd "$(dirname "$0")/.." && pwd)"
ios="${IOS_REPO:-$HOME/ledger-ipad-correspondence}"
a="$here/app/src/test/resources/panel-seats.golden"
b="$ios/Tests/LedgerCoreTests/Fixtures/panel-seats.golden"
[ -f "$a" ] || { echo "missing $a" >&2; exit 2; }
[ -f "$b" ] || { echo "missing $b (iOS twin not built yet?)" >&2; exit 2; }
if diff -u "$b" "$a"; then echo "panel seats: both apps agree"; else echo "panel seats DIFFER (ios ← → android)" >&2; exit 1; fi
