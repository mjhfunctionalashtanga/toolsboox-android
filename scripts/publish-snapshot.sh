#!/usr/bin/env bash
# Publish a clean snapshot of this branch to the public fork, without its history.
#
# Takes the tree of the current branch (or --source), leaves out every path that
# .public-exclude names, and makes ONE commit of what is left on top of the public
# branch's tip. That commit goes on the local branch public-snapshot, which moves
# forward on every run, so each push is a plain fast-forward. The push guard checks
# the new commit before the branch moves; if it finds anything, the branch stays where
# it was. Nothing is ever pushed: the script prints the one push command to run.
#
#   scripts/publish-snapshot.sh -m "comics reader and mail"     make the snapshot
#   scripts/publish-snapshot.sh --dry-run                        build and check, move nothing
#   scripts/publish-snapshot.sh --list                           only show what is left out
#
# Options:
#   -m, --message TEXT    the summary after "Snapshot of <date>: " (one line)
#   --base REF            the public branch as last fetched (default origin/punchlist-2026-07-26,
#                         or $PUBLIC_SNAPSHOT_BASE)
#   --branch NAME         the local snapshot branch (default public-snapshot)
#   --source REV          the commit whose tree is published (default HEAD)
#   --exclude-file PATH   read the exclude list from this file instead of the source commit's
#                         .public-exclude
#   --date YYYY-MM-DD     the date in the message (default today)
#   --trailer LINE        a trailer line for the message (repeatable; replaces the defaults)
#   --no-trailers         no trailer lines
#   --fetch               git fetch the public branch's remote first (a read; nothing is sent)
#   --dry-run             make and check the commit, but leave the branch where it is
#   --list                print the paths that would be left out, then stop
#
# See "Publishing a snapshot to the public fork" in scripts/githooks/README.md.

set -u

BASE=${PUBLIC_SNAPSHOT_BASE:-origin/punchlist-2026-07-26}
BRANCH=public-snapshot
SOURCE=HEAD
EXCLUDE_FILE=""
SUMMARY=""
DATE=$(date +%Y-%m-%d)
DRY_RUN=0
LIST_ONLY=0
FETCH=0
DEFAULT_TRAILERS=(
  "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  "Claude-Session: https://claude.ai/code/session_01Bkm9fHknrqE9iSKrv89YAW"
)
TRAILERS=("${DEFAULT_TRAILERS[@]}")
CUSTOM_TRAILERS=0

say() { printf '%s\n' "$*" >&2; }
die() { say "publish-snapshot: $*"; exit 1; }
usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//' >&2; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    -m|--message) [ "$#" -ge 2 ] || die "$1 needs a value"; SUMMARY=$2; shift 2 ;;
    --base) [ "$#" -ge 2 ] || die "$1 needs a value"; BASE=$2; shift 2 ;;
    --branch) [ "$#" -ge 2 ] || die "$1 needs a value"; BRANCH=$2; shift 2 ;;
    --source) [ "$#" -ge 2 ] || die "$1 needs a value"; SOURCE=$2; shift 2 ;;
    --exclude-file) [ "$#" -ge 2 ] || die "$1 needs a value"; EXCLUDE_FILE=$2; shift 2 ;;
    --date) [ "$#" -ge 2 ] || die "$1 needs a value"; DATE=$2; shift 2 ;;
    --trailer)
      [ "$#" -ge 2 ] || die "$1 needs a value"
      if [ "$CUSTOM_TRAILERS" = 0 ]; then TRAILERS=(); CUSTOM_TRAILERS=1; fi
      TRAILERS+=("$2"); shift 2 ;;
    --no-trailers) TRAILERS=(); CUSTOM_TRAILERS=1; shift ;;
    --fetch) FETCH=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown option: $1" ;;
  esac
done

case "$SUMMARY" in *$'\n'*) die "the summary must be one line" ;; esac
printf '%s' "$DATE" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' || die "--date wants YYYY-MM-DD"

TOP=$(git rev-parse --show-toplevel 2>/dev/null) || die "not inside a git repository"
cd "$TOP" || die "cannot enter $TOP"
COMMON=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || die "cannot find the git folder"

# --- where the push goes: refs/remotes/<remote>/<branch> --------------------
BASE_FULL=$(git rev-parse --symbolic-full-name "$BASE" 2>/dev/null || :)
REMOTE=""
REMOTE_BRANCH=""
for r in $(git remote); do
  case "$BASE_FULL" in
    "refs/remotes/$r/"*) REMOTE=$r; REMOTE_BRANCH=${BASE_FULL#"refs/remotes/$r/"} ;;
  esac
done
[ -n "$REMOTE" ] || die "--base must be a remote branch such as origin/punchlist-2026-07-26 (got: $BASE)"

if [ "$FETCH" = 1 ]; then
  say "Fetching $REMOTE $REMOTE_BRANCH (read only)..."
  git fetch --quiet "$REMOTE" "$REMOTE_BRANCH" || die "git fetch $REMOTE $REMOTE_BRANCH failed"
fi

BASE_SHA=$(git rev-parse -q --verify "$BASE^{commit}") || die "cannot read $BASE (try --fetch)"
SRC_SHA=$(git rev-parse -q --verify "$SOURCE^{commit}") || die "cannot read $SOURCE"

# --- the exclude list ---------------------------------------------------------
TMP=$(mktemp -d "${TMPDIR:-/tmp}/publish-snapshot.XXXXXX") || die "cannot make a temp folder"
trap 'rm -rf "$TMP"' EXIT

if [ -n "$EXCLUDE_FILE" ]; then
  [ -f "$EXCLUDE_FILE" ] || die "no such file: $EXCLUDE_FILE"
  cp "$EXCLUDE_FILE" "$TMP/exclude"
  EXCLUDE_FROM=$EXCLUDE_FILE
else
  git show "$SRC_SHA:.public-exclude" > "$TMP/exclude" 2>/dev/null ||
    die "the source commit has no .public-exclude (commit one, or pass --exclude-file)"
  EXCLUDE_FROM=".public-exclude in $(git rev-parse --short "$SRC_SHA")"
  if [ -f .public-exclude ] && ! cmp -s .public-exclude "$TMP/exclude"; then
    say "Note: .public-exclude has uncommitted changes; using the committed one (or pass --exclude-file .public-exclude)."
  fi
fi
if [ "$SOURCE" = HEAD ] && ! git diff --quiet HEAD -- 2>/dev/null; then
  say "Note: uncommitted changes are not part of the snapshot; it is made from commit $(git rev-parse --short "$SRC_SHA")."
fi

# Each line is a git glob matched from the repository root; a trailing / means the
# folder and everything in it. Comments (#) and blank lines are skipped.
SPECS=()
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%%$'\r'}
  line=$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  case "$line" in ''|'#'*) continue ;; esac
  case "$line" in */) line="${line}**" ;; esac
  case "$line" in /*) line=${line#/} ;; esac
  SPECS+=(":(glob)$line")
done < "$TMP/exclude"
[ "${#SPECS[@]}" -gt 0 ] || die "the exclude list is empty: $EXCLUDE_FROM"

# --- build the tree in a private index (the worktree and its index are not touched)
export GIT_INDEX_FILE="$TMP/index"
git read-tree "$SRC_SHA" || die "cannot read the tree of $SOURCE"
git ls-files -z -- "${SPECS[@]}" > "$TMP/excluded.z" || die "cannot match the exclude list"
tr '\0' '\n' < "$TMP/excluded.z" > "$TMP/excluded"
NEXCLUDED=$(grep -c . "$TMP/excluded" || :)

if [ "$LIST_ONLY" = 1 ]; then
  say "Left out of the snapshot ($NEXCLUDED path(s), from $EXCLUDE_FROM):"
  sed 's/^/  /' "$TMP/excluded" >&2
  exit 0
fi

if [ -s "$TMP/excluded.z" ]; then
  git update-index --force-remove -z --stdin < "$TMP/excluded.z" || die "cannot leave out the excluded paths"
fi
TREE=$(git write-tree) || die "cannot write the snapshot tree"
# Read the written tree back and match the list again: nothing on it may be left.
GIT_INDEX_FILE="$TMP/check" git read-tree "$TREE" || die "cannot read back the snapshot tree"
LEFT=$(GIT_INDEX_FILE="$TMP/check" git ls-files -- "${SPECS[@]}") || die "cannot check the snapshot tree"
[ -z "$LEFT" ] || die "an excluded path is still in the snapshot tree; nothing was made"
[ -n "$(git ls-tree "$TREE")" ] || die "the snapshot tree is empty; nothing was made"
unset GIT_INDEX_FILE

# --- the parent: the newer of the public tip and the last local snapshot -------
OLD_SNAP=$(git rev-parse -q --verify "refs/heads/$BRANCH^{commit}" || :)
if [ "$DRY_RUN" = 0 ] && git worktree list --porcelain | grep -qx "branch refs/heads/$BRANCH"; then
  die "$BRANCH is checked out in a worktree; switch that worktree to another branch first (nothing was made)"
fi
if [ -z "$OLD_SNAP" ]; then
  PARENT=$BASE_SHA
elif git merge-base --is-ancestor "$BASE_SHA" "$OLD_SNAP"; then
  PARENT=$OLD_SNAP          # not pushed yet (or pushed and equal): build on it
elif git merge-base --is-ancestor "$OLD_SNAP" "$BASE_SHA"; then
  PARENT=$BASE_SHA          # pushed, and the public branch has moved on since
else
  die "$BRANCH and $BASE have split apart; look at both before publishing (nothing was made)"
fi

if [ "$(git rev-parse "$PARENT^{tree}")" = "$TREE" ]; then
  say "Nothing to publish: the snapshot would be the same as $(git rev-parse --short "$PARENT")."
  exit 0
fi

# --- the commit ---------------------------------------------------------------
[ -n "$SUMMARY" ] || SUMMARY="the current code, with internal notes left out"
{
  printf 'Snapshot of %s: %s\n\n' "$DATE" "$SUMMARY"
  printf '%s\n' "One commit holding the whole tree as it stands; the working history stays private."
  printf '%s\n' "Paths named in .public-exclude (internal notes, local tooling) are left out."
  if [ "${#TRAILERS[@]}" -gt 0 ]; then
    printf '\n'
    printf '%s\n' "${TRAILERS[@]}"
  fi
} > "$TMP/message"
NEW=$(git commit-tree "$TREE" -p "$PARENT" -F "$TMP/message") || die "git commit-tree failed"

# --- the push guard checks exactly what the push will carry --------------------
GUARD="$TOP/scripts/githooks/pre-push"
[ -x "$GUARD" ] || GUARD="$COMMON/ledgable-push-guard"
[ -f "$GUARD" ] || die "no push guard found (scripts/githooks/pre-push); refusing to finish"
say "Checking the snapshot with the push guard..."
# Everything the push will carry: this snapshot, plus any earlier one not pushed yet.
if ! env -u LEDGABLE_PUSH_ALLOW -u LEDGABLE_PUSH_OVERRIDE bash "$GUARD" --scan "$BASE_SHA..$NEW"; then
  say ""
  say "publish-snapshot: the push guard flagged the snapshot, so $BRANCH was NOT moved."
  say "Fix it in the source branch (or add the path to .public-exclude), commit, and run this again."
  say "(The unused commit $(git rev-parse --short "$NEW") is left for git to clean up.)"
  exit 1
fi

if [ "$DRY_RUN" = 1 ]; then
  say ""
  say "Dry run: snapshot $(git rev-parse --short "$NEW") passed the guard; $BRANCH was not moved."
  git diff --shortstat "$PARENT" "$NEW" >&2
  exit 0
fi

# --- move the branch ----------------------------------------------------------
git update-ref -m "publish-snapshot: $SUMMARY" "refs/heads/$BRANCH" "$NEW" "${OLD_SNAP:-}" ||
  die "could not move $BRANCH (it changed while this ran?)"

say ""
say "Snapshot made: $BRANCH is now $(git rev-parse --short "$NEW")"
say "  parent:      $(git rev-parse --short "$PARENT") ($( [ "$PARENT" = "$BASE_SHA" ] && printf '%s' "$BASE" || printf 'the previous snapshot, not pushed yet' ))"
say "  source:      $(git rev-parse --short "$SRC_SHA") ($(git rev-parse --abbrev-ref "$SOURCE" 2>/dev/null || printf '%s' "$SOURCE"))"
say "  left out:    $NEXCLUDED path(s) (scripts/publish-snapshot.sh --list shows them)"
say "  change:     $(git diff --shortstat "$PARENT" "$NEW")"
say ""
say "Nothing was pushed. When you are ready, push it yourself with:"
say ""
say "    git push $REMOTE $BRANCH:$REMOTE_BRANCH"
say ""
