#!/usr/bin/env bash
# The comic gesture TWINS (Michael, 2026-10-11 gesture pass): the touch grammar must be one thing
# on the Boox and the iPad, so two pieces are kept byte-identical across the repos —
#   • comic-gestures.js            (the pure decisions; tested by scripts/test-comic-gestures.mjs)
#   • reader-embed.js between the  ">>> COMIC-GESTURES >>>" and "<<< COMIC-GESTURES <<<" sentinels
#                                  (the wiring; it leans on module state, so it stays in place)
# Usage: scripts/check-comic-gestures-twin.sh [android-repo] [ios-repo]
set -u
A="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
I="${2:-$HOME/ledger-ipad-correspondence}"
AJS="$A/app/src/main/assets/foliate"
IJS="$I/Resources-foliate-js"
block() { awk '/>>> COMIC-GESTURES >>>/{on=1} on{print} /<<< COMIC-GESTURES <<</{on=0}' "$1"; }
fail=0
if ! cmp -s "$AJS/comic-gestures.js" "$IJS/comic-gestures.js"; then
    echo "DIFFERS: comic-gestures.js"; diff "$AJS/comic-gestures.js" "$IJS/comic-gestures.js" | head -40; fail=1
fi
if [ -z "$(block "$AJS/reader-embed.js")" ]; then echo "MISSING: sentinel block in $AJS/reader-embed.js"; fail=1; fi
if ! diff <(block "$AJS/reader-embed.js") <(block "$IJS/reader-embed.js") > /dev/null; then
    echo "DIFFERS: reader-embed.js COMIC-GESTURES block"
    diff <(block "$AJS/reader-embed.js") <(block "$IJS/reader-embed.js") | head -40; fail=1
fi
[ $fail -eq 0 ] && echo "COMIC GESTURE TWINS: identical"
exit $fail
