#!/usr/bin/env bash
# Self-test for the push guard (scripts/githooks/pre-push).
#
#   scripts/githooks/selftest.sh [folder]
#
# Builds a throwaway git repository in [folder] (default: a new temp folder),
# commits made-up secrets and personal details one at a time, and checks that
# the guard catches each one, names the file and commit, and never prints the
# value itself. Then checks that a clean commit goes through, that only the
# commits leaving the machine are read, that the allow list and the override
# work, and that --install turns it on for every worktree and never lets a push
# through unchecked.
#
# Nothing is pushed: the hook is fed the same lines git would give it (or run
# with `git hook run`, which is how git itself runs it, without a push).
# The made-up values are put together at run time from short pieces, so this
# file itself passes the guard.

set -u

HOOK="$(cd "$(dirname "$0")" && pwd)/pre-push"
[ -x "$HOOK" ] || { echo "selftest: $HOOK is missing or not executable" >&2; exit 2; }

DIR=${1:-}
MARK=.ledgable-guard-selftest
if [ -z "$DIR" ]; then
  DIR=$(mktemp -d "${TMPDIR:-/tmp}/ledgable-guard-selftest.XXXXXX") || exit 2
elif [ -e "$DIR" ]; then
  if [ -f "$DIR/$MARK" ]; then
    rm -rf "$DIR"
  elif [ -n "$(ls -A "$DIR" 2>/dev/null)" ]; then
    echo "selftest: $DIR is not empty and is not an earlier self-test folder; pick another." >&2
    exit 2
  fi
fi
mkdir -p "$DIR" && cd "$DIR" || exit 2
DIR=$(pwd)
: > "$MARK"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$*"; }

export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
unset LEDGABLE_PUSH_ALLOW LEDGABLE_PUSH_OVERRIDE LEDGABLE_PUSH_FINGERPRINTS LEDGABLE_GUARD_NO_PYTHON
git init -q -b main repo && cd repo || exit 2
git config user.name "Guard Selftest"
git config user.email "selftest@example.com"
git config commit.gpgsign false
git config tag.gpgsign false
git config remote.origin.url "$DIR/no-such-remote.git"   # never contacted
printf '# throwaway\n' > README.md
git add README.md && git commit -q -m "base"
BASE=$(git rev-parse HEAD)
git update-ref refs/remotes/origin/main "$BASE"           # pretend this much is on the remote
COMMON=$(cd "$(git rev-parse --git-common-dir)" && pwd)

# ---------------------------------------------------------------- made-up values
AT='@'
R1=Zq8Lw3Nx; R2=Rt5Vb7Mk; R3=Hy2Jd9Pc; R4=Qe6Ug4Fs; R5=Wa1Tn0Ko; R6=Xi7Bm3Lr
LONG40="$R1$R2$R3$R4$R5"                                  # 40 mixed characters
LONG48="$R1$R2$R3$R4$R5$R6"
UP16=Q7XZ3M9KD2LP8W4R
AWS_V="AK""IA$UP16"
GOOGLE_V="AI""za${R1}${R2}${R3}${R4}abc"                 # AIza + 35
STRIPE_V="sk_""live_${R1}${R2}${R3}"
GITHUB_V="gh""p_${R1}${R2}${R3}${R4}${R5:0:4}"           # ghp_ + 36
SLACK_V="xo""xb-1234567890-${R1}${R2}"
OPENAI_V="sk-""proj-${LONG40}"
PEM_V="-----BEGIN ""RSA PRIVATE KEY-----"
BEARER_V="${R1}${R2}${R3}"
PASSWORD_V="Tr0ub4dor${R3}"
PHPDEF_V="${R4}${R5}${R6}"
EMAIL_V="jane.q.public${AT}gmail.com"
EMAIL2_V="r.mendoza${AT}outlook.com"
EMAIL3_V="sam.selftest.owner${AT}gmail.com"               # stands in for your own user.email
EMAIL4_V="lee.published${AT}fastmail.com"                 # an author already on the remote
EMAIL5_V="kim.author${AT}proton.me"                       # the author of the commit being pushed
XCOM_V="jdoe${AT}x.com"
CARD_DIGITS=4$((200 + 42))                                # 4242
PHONE_V="202-867-""5309"
SALT_V="k#7Q g]V+2w;Z9 |pX""${AT}4r<0Tn{eL3~mB"            # a WordPress salt can hold spaces
FP_V="meadow-lantern-quartz-orbit"                        # plain words: only the fingerprint list knows it
# A MichaelFilter-style intake key: 48 hex characters, and shorter shapes of it.
H1=959de24d; H2=09ffb423; H3=c5a2f416; H4=f41c225e; H5=c2379003; H6=6303ee97
HEX48="$H1$H2$H3$H4$H5$H6"
HEX40="$H1$H2$H3$H4$H5"
HEX32_LOW="f76f3bbd""edbffff4""be0e920f""b9bbeccf"       # random hex the old 3.5 cut-off let through
KEY24="ILIDVSB9""7zXzMEr1""5BlBoh3v"
MF_URL="https://mf.example.net/wp-json/mjh-rss/v1/intake"

# ---------------------------------------------------------------- helpers
LAST_OUT=""
ENGINE=full          # full = python3 checks; basic = the awk checks used when python3 is missing
scan() {
  if [ "$ENGINE" = basic ]; then LAST_OUT=$(LEDGABLE_GUARD_NO_PYTHON=1 "$HOOK" --scan "$@" 2>&1)
  else LAST_OUT=$("$HOOK" --scan "$@" 2>&1); fi
}

# case_blocks <both|full> <title> <file> <expected label text> <value that must not be printed> <content...>
#   both = the basic checks must catch it too; full = only the python3 checks do
case_blocks() {
  local where=$1 title=$2 file=$3 label=$4 value=$5; shift 5
  if [ "$ENGINE" = basic ]; then
    [ "$where" = both ] || return 0
    title="basic: $title"
  fi
  git checkout -q -B "case" "$BASE"
  mkdir -p "$(dirname "$file")"
  printf '%s\n' "$@" > "$file"
  git add -A && git commit -q -m "add $file"
  local sha; sha=$(git rev-parse --short=10 HEAD)
  scan "$BASE..HEAD"; local rc=$?
  if [ "$rc" -ne 1 ]; then bad "$title: not stopped (exit $rc)"; return; fi
  if [ "$ENGINE" = basic ]; then
    case "$LAST_OUT" in *"basic checks only"*) ;; *) bad "$title: basic checks did not run"; return ;; esac
  fi
  case "$LAST_OUT" in *"$file"*) ;; *) bad "$title: file name not shown"; return ;; esac
  case "$LAST_OUT" in *"$sha"*) ;; *) bad "$title: commit not shown"; return ;; esac
  case "$LAST_OUT" in *"$label"*) ;; *) bad "$title: expected \"$label\""; printf '%s\n' "$LAST_OUT" | sed 's/^/        /'; return ;; esac
  if [ -n "$value" ]; then
    case "$LAST_OUT" in *"$value"*) bad "$title: the value itself was printed"; return ;; esac
  fi
  ok "$title"
}

# case_passes <both|full> <title> <file> <content...>: an everyday line the guard must leave alone
case_passes() {
  local where=$1 title=$2 file=$3; shift 3
  if [ "$ENGINE" = basic ]; then
    [ "$where" = both ] || return 0
    title="basic: $title"
  fi
  git checkout -q -B "case" "$BASE"
  mkdir -p "$(dirname "$file")"
  printf '%s\n' "$@" > "$file"
  git add -A && git commit -q -m "add $file"
  scan "$BASE..HEAD"; local rc=$?
  if [ "$rc" -eq 0 ]; then ok "$title"; else bad "$title: blocked (exit $rc)"; printf '%s\n' "$LAST_OUT" | sed 's/^/        /'; fi
}

echo "Push guard self-test in $DIR"
echo
run_cases() {
  echo "Secrets:"
  case_blocks both "private key header"            keys/deploy.txt         "a private key"                  "$PEM_V" \
    "$PEM_V" "MIIEowIBAAKCAQEA0000"
  case_blocks both "AWS access key"                app/src/Cloud.kt        "Amazon (AWS) access key"        "$AWS_V" \
    "val cloudId = \"$AWS_V\""
  case_blocks both "Google API key"                app/src/Maps.kt         "a Google API key"               "$GOOGLE_V" \
    "const val MAPS = \"$GOOGLE_V\""
  case_blocks both "Stripe secret key"             server/pay.php          "a Stripe secret key"            "$STRIPE_V" \
    "<?php \$stripe = new StripeClient('$STRIPE_V');"
  case_blocks both "GitHub token"                  scripts/release.sh      "a GitHub token"                 "$GITHUB_V" \
    "curl -H \"Authorization: token $GITHUB_V\" https://api.github.com/"
  case_blocks both "Slack token"                   scripts/notify.py       "a Slack token"                  "$SLACK_V" \
    "client = WebClient(\"$SLACK_V\")"
  case_blocks both "OpenAI-style key"              app/src/Ai.swift        "OpenAI-style API key"           "$OPENAI_V" \
    "let client = OpenAI(\"$OPENAI_V\")"
  case_blocks full "password= in a settings file"  config/app.properties   "a password, secret or API key"  "$PASSWORD_V" \
    "db.password=$PASSWORD_V"
  case_blocks both "api_key= in code"              app/src/Feed.kt         "a password, secret or API key"  "${R2}x9${R3}" \
    "val api_key = \"${R2}x9${R3}\""
  case_blocks both "PHP define() of a secret"      wp/plugin.php           "a PHP constant"                 "$PHPDEF_V" \
    "define( 'MJH_SYNC_SECRET', '$PHPDEF_V' );"
  case_blocks both "PHP const of a token"          wp/routes.php           "a PHP constant"                 "$LONG40" \
    "const FEED_TOKEN = '$LONG40';"
  case_blocks full "WordPress salt with spaces"    wp/wp-config.php        "a PHP constant"                 "" \
    "define( 'AUTH_KEY', '$SALT_V' );"
  case_blocks both "fallback secret (the Bluesky shape)" scripts/gram.php  "long random-looking value"      "$LONG40" \
    "return defined( 'X_SYND_SECRET' ) ? X_SYND_SECRET : '$LONG40';"
  case_blocks both "random value given to a token name" app/src/Sync.kt    "long random-looking value"      "$LONG48" \
    "private val syncToken = \"$LONG48\""
  case_blocks both "Bearer token"                  scripts/call.sh         "a Bearer login token"           "$BEARER_V" \
    "curl -H 'Authorization: Bearer $BEARER_V' https://api.example.net/"
  case_blocks both "private file name (.env)"      .env                    "usually private"                "" \
    "DEBUG=1"
  case_blocks both "random hex with low spread (32 characters)" scripts/sms.py "long random-looking value"  "$HEX32_LOW" \
    "twilio_auth_token = \"$HEX32_LOW\""

  echo
  echo "The intake key, in the shapes it really travels in:"
  case_blocks both "curl -d \"key=...&...\""        docs/intake.md          "long random-looking value"      "$HEX48" \
    "curl -d \"key=$HEX48&url=https://x.example.net/a\" $MF_URL"
  case_blocks both "?key=... in a web address"     app/src/Feed2.kt        "long random-looking value"      "$HEX48" \
    "val u = \"https://mf.example.net/feed?key=$HEX48&n=3\""
  case_blocks both ".add(\"key\", ...) (the old client)" app/src/Intake.kt "long random-looking value"      "$HEX48" \
    "form.add(\"key\", \"$HEX48\")"
  case_blocks both "adb --es mf_key '...'"         docs/adb.md             "long random-looking value"      "$HEX48" \
    "adb shell am broadcast -a com.example.SET_CONNECTOR_KEY -n com.example/.Receiver --es mf_key '$HEX48'"
  case_blocks full "adb --es mf_key ... (no quotes)" scripts/adb-key.sh    "long random-looking value"      "$HEX48" \
    "adb shell am broadcast -a com.example.SET --es mf_key $HEX48"
  case_blocks both "export INTAKE_KEY=..."         scripts/env.sh          "long random-looking value"      "$HEX48" \
    "export INTAKE_KEY=$HEX48"
  case_blocks both "curl -H \"X-MF-Key: ...\""      scripts/try.sh          "long random-looking value"      "$HEX48" \
    "curl -H \"X-MF-Key: $HEX48\" $MF_URL"
  case_blocks full "Android string resource"       app/src/main/res/values/keys.xml "long random-looking value" "$HEX48" \
    "<string name=\"mf_intake_key\">$HEX48</string>"
  case_blocks full "Info.plist <key> then <string>" ios/Info.plist         "long random-looking value"      "$HEX48" \
    "<key>MFIntakeKey</key>" "<string>$HEX48</string>"
  case_blocks both "INTAKE_KEY=... in notes"       NOTES.md                "long random-looking value"      "$HEX48" \
    "INTAKE_KEY=$HEX48"
  case_blocks both "a 24-character key constant"   app/src/K24.kt          "long random-looking value"      "$KEY24" \
    "const val INTAKE_KEY=\"$KEY24\""
  case_blocks both "a 40-hex key constant"         app/src/K40.kt          "long random-looking value"      "$HEX40" \
    "const val INTAKE_KEY=\"$HEX40\""
  case_blocks both "a key in JSON"                 app/src/test/r.json     "long random-looking value"      "$HEX40" \
    "{\"intake_key\":\"$HEX40\"}"
  case_blocks both "local.properties.bak"          local.properties.bak    "usually private"                "" \
    "sdk.dir=/Users/someone/Library/Android/sdk"
  case_blocks both "a saved database (notes.db)"   data/notes.db           "usually private"                "" \
    "rows"

  echo
  echo "Personal data:"
  case_blocks both "real email address"            app/src/test/MailTest.kt "a real email address"         "$EMAIL_V" \
    "val from = \"Jane <$EMAIL_V>\""
  case_blocks both "email in a mail fixture"       fixtures/renewal.eml    "a real email address"           "$EMAIL2_V" \
    "From: Store <$EMAIL2_V>" "Subject: Your renewal"
  case_blocks both "an x.com address"              app/src/Share.kt        "a real email address"           "$XCOM_V" \
    "val to = \"$XCOM_V\""
  case_blocks both "a sign-off line inside a file" CONTRIBUTORS.txt        "a real email address"           "$EMAIL2_V" \
    "Signed-off-by: R Mendoza <$EMAIL2_V>"
  case_blocks both "card digits: ending in"        fixtures/receipt.txt    "card digits"                    "" \
    "Paid with the Visa ending in $CARD_DIGITS"
  case_blocks both "card digits: stars"            fixtures/order.eml      "card digits"                    "" \
    "Payment method: visa ***$CARD_DIGITS"
  case_blocks both "phone number in a fixture"     fixtures/contact.json   "a phone number"                 "$PHONE_V" \
    "{\"name\": \"Pat\", \"phone\": \"$PHONE_V\"}"

  # An address in the file name: the report shows the folder, never the name.
  local t="email in a file name" sha
  [ "$ENGINE" = basic ] && t="basic: $t"
  git checkout -q -B "case" "$BASE"
  mkdir -p fixtures && printf 'x\n' > "fixtures/$EMAIL_V.eml" && git add -A && git commit -q -m "fixture"
  sha=$(git rev-parse --short=10 HEAD)
  scan "$BASE..HEAD"; rc=$?
  case "$rc:$LAST_OUT" in
    1:*"$EMAIL_V"*) bad "$t: the address was printed" ;;
    1:*"fixtures/(file name withheld"*"$sha"*"email address in the file name"*) ok "$t: stopped, folder shown, address withheld" ;;
    *) bad "$t: not reported as expected (exit $rc)"; printf '%s\n' "$LAST_OUT" | sed 's/^/        /' ;;
  esac

  echo
  echo "Everyday lines that must pass:"
  case_passes both "a settings name with words and numbers" app/src/Prefs.kt \
    "const val FONT_KEY = \"reader_v2_font_size_2024\""
  case_passes both "a document key with a timestamp" app/src/test/doc.json \
    "{\"key\": \"synthesize-1760284800000#1\", \"base\": \"synthesize\"}"
  case_passes full "Info.plist bundle id"          ios/Info2.plist \
    "<key>CFBundleIdentifier</key>" "<string>com.example.ledger.ios.app</string>"
  case_passes both "a commit hash"                 app/src/Build.kt \
    "val sha = \"$HEX40\""
  case_passes both "a counting test pattern"       app/src/Fixture.kt \
    "val testKey = \"0123456789abcdef0123456789abcdef\""
  case_passes full "an ordinary string resource"   app/src/main/res/values/s.xml \
    "<string name=\"app_name\">Ledgable</string>"
  case_passes full "adb with an ordinary extra"    docs/adb2.md \
    "adb shell am start --es mode reader_view_main_screen"
  case_passes both "a one-letter test address"     app/src/test/Contacts.kt \
    "let sarah = contact(\"Sarah Lin\", email: \"s${AT}x.com\")"
}

run_cases

echo
echo "Known-secrets list (fingerprints):"
FPFILE="$DIR/fingerprints"
if command -v shasum >/dev/null 2>&1; then printf '%s' "$FP_V" | shasum -a 256 | awk '{print $1}' > "$FPFILE"
else printf '%s' "$FP_V" | sha256sum | awk '{print $1}' > "$FPFILE"; fi
git checkout -q -B "case" "$BASE"
mkdir -p app/src && printf 'val motto = "%s"\n' "$FP_V" > app/src/Motto.kt
git add -A && git commit -q -m "motto"
MOTTO=$(git rev-parse HEAD)
scan "$BASE..HEAD"; rc=$?
[ "$rc" -eq 0 ] && ok "without the list, the plain-words value passes" || bad "plain-words value blocked without the list (exit $rc)"
LAST_OUT=$(LEDGABLE_PUSH_FINGERPRINTS="$FPFILE" "$HOOK" --scan "$BASE..HEAD" 2>&1); rc=$?
case "$rc:$LAST_OUT" in
  1:*"known-secrets list"*) case "$LAST_OUT" in *"$FP_V"*) bad "fingerprint: value printed" ;; *) ok "with the list, the same value is stopped" ;; esac ;;
  *) bad "fingerprint not caught (exit $rc)" ;;
esac
# The list kept in the shared git folder is read from every worktree.
cp "$FPFILE" "$COMMON/ledgable-secret-fingerprints"
git worktree add -q "$DIR/wt-motto" "$MOTTO" 2>/dev/null
LAST_OUT=$(cd "$DIR/wt-motto" && "$HOOK" --scan "$BASE..HEAD" 2>&1); rc=$?
case "$rc:$LAST_OUT" in
  1:*"known-secrets list"*) ok "the shared list in the git folder is read from another worktree" ;;
  *) bad "shared list not read from a worktree (exit $rc)" ;;
esac
git worktree remove --force "$DIR/wt-motto"
rm -f "$COMMON/ledgable-secret-fingerprints"

echo
echo "Commit and tag messages:"
git checkout -q -B "case" "$BASE"
printf 'x\n' > note.txt && git add note.txt
git commit -q -m "wire sync" -m "temporary: api_key=${R5}${R6}9" -m "Co-Authored-By: Someone <noreply${AT}anthropic.com>"
scan "$BASE..HEAD"; rc=$?
case "$rc:$LAST_OUT" in 1:*"(commit message)"*) ok "a key in a commit message is stopped" ;; *) bad "commit message key not caught (exit $rc)" ;; esac

echo
echo "Things that must pass:"
git checkout -q -B "clean" "$BASE"
mkdir -p app/src fixtures wp
cat > app/src/Clean.kt <<EOF
// Made-up and everyday values the guard must leave alone.
val support = "someone${AT}example.com"
val dev = "dev${AT}localhost"
val bot = "12345+bot${AT}users.noreply.github.com"
val pwField = "imap_password"
val password = ""
val apiKey = BuildConfig.MF_KEY
val token = prefs.getString("sync_token", null)
val secretHint = "Paste your secret here"
if (password == other) return
val label = "Card ending in"
val id = "com.toolsboox.plugin.feeds.ui.LedgerDirectoryFragment"
val hash = "d41d8cd98f00b204e9800998ecf8427e"
EOF
printf '%s\n' "define( 'MY_PLUGIN_VERSION', '1.2.3' );" "define( 'AUTH_KEY', 'put your unique phrase here' );" > wp/config-sample.php
printf '%s\n' '{"phone": "202-555-0143"}' > fixtures/sample.json
printf '%s\n' "Message-ID: <CAKx${R1}${R2}${R3}${AT}mail.gmail.com>" "From: Someone <someone${AT}example.org>" > fixtures/ok.eml
git add -A && git commit -q -m "clean commit" -m "Co-Authored-By: Claude <noreply${AT}anthropic.com>"
scan "$BASE..HEAD"; rc=$?
if [ "$rc" -eq 0 ]; then ok "a clean commit passes"; else bad "clean commit blocked (exit $rc)"; printf '%s\n' "$LAST_OUT" | sed 's/^/        /'; fi

echo
echo "As a real hook (git's input, no push):"
ZERO=0000000000000000000000000000000000000000
hook() { LAST_OUT=$(printf '%s\n' "$1" | env ${2:+"$2"} "$HOOK" origin "$DIR/no-such-remote.git" 2>&1); return $?; }

CLEAN=$(git rev-parse clean)
hook "refs/heads/clean $CLEAN refs/heads/clean $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "new clean branch goes through" || bad "new clean branch blocked (exit $rc)"

git checkout -q -B "leaky" "$BASE"
printf '%s\n' "val from = \"$EMAIL_V\"" > leak.kt && git add leak.kt && git commit -q -m "leak"
LEAKY=$(git rev-parse HEAD)
hook "refs/heads/leaky $LEAKY refs/heads/leaky $ZERO"; rc=$?
case "$rc:$LAST_OUT" in 1:*"Push stopped"*leak.kt*"[email]"*) ok "new branch with an address is stopped, its kind shown" ;; *) bad "new leaky branch not stopped (exit $rc)" ;; esac

hook "refs/heads/main $LEAKY refs/heads/main $BASE"; rc=$?
[ "$rc" -eq 1 ] && ok "updating an existing branch is checked too" || bad "existing-branch update not stopped (exit $rc)"

hook "refs/heads/leaky $LEAKY refs/heads/leaky $ZERO" LEDGABLE_PUSH_OVERRIDE=1; rc=$?
case "$rc:$LAST_OUT" in 0:*"PUSHING ANYWAY"*) ok "LEDGABLE_PUSH_OVERRIDE=1 lets it through, loudly" ;; *) bad "override did not work (exit $rc)" ;; esac

hook "(delete) $ZERO refs/heads/old $BASE"; rc=$?
[ "$rc" -eq 0 ] && ok "deleting a remote branch is not checked" || bad "branch delete blocked (exit $rc)"

# A binary file is read for the token beginnings it holds.
git checkout -q -B "blob" "$BASE"
mkdir -p assets && printf 'PK\000\001\002%s\000\003tail' "$AWS_V" > assets/pack.bin && git add assets/pack.bin && git commit -q -m "a binary"
hook "refs/heads/blob $(git rev-parse HEAD) refs/heads/blob $ZERO"; rc=$?
case "$rc:$LAST_OUT" in
  1:*"$AWS_V"*) bad "binary file: the value was printed" ;;
  1:*"assets/pack.bin"*"inside the file"*"Amazon (AWS)"*) ok "a key inside a binary file is stopped" ;;
  *) bad "key in a binary file not caught (exit $rc)" ;;
esac

# An annotated tag's message goes up with the tag: it is read too.
git update-ref refs/remotes/origin/clean "$CLEAN"         # the commit itself is already up
git tag -a v1-selftest -m "release" -m "api_key=${LONG40}" "$CLEAN"
TAGSHA=$(git rev-parse v1-selftest)
hook "refs/tags/v1-selftest $TAGSHA refs/tags/v1-selftest $ZERO"; rc=$?
case "$rc:$LAST_OUT" in 1:*"(tag message)"*) ok "a key in a tag message is stopped" ;; *) bad "tag message not read (exit $rc)" ;; esac
LAST_OUT=$(printf '%s\n' "refs/tags/v1-selftest $TAGSHA refs/tags/v1-selftest $ZERO" | LEDGABLE_GUARD_NO_PYTHON=1 "$HOOK" origin "$DIR/no-such-remote.git" 2>&1); rc=$?
case "$rc:$LAST_OUT" in 1:*"(tag message)"*) ok "basic: a key in a tag message is stopped" ;; *) bad "basic: tag message not read (exit $rc)" ;; esac
git tag -d v1-selftest >/dev/null
git update-ref -d refs/remotes/origin/clean

# Commits that are already on the remote are not read again.
git update-ref refs/remotes/origin/leaky "$LEAKY"
git checkout -q -B "ontop" "$LEAKY"
printf 'more\n' >> README.md && git commit -q -am "on top"
hook "refs/heads/ontop $(git rev-parse HEAD) refs/heads/ontop $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "only the commits leaving the machine are read" || bad "already-public commit read again (exit $rc)"

# An address the remote's main branch already shows is let through; a new one is not.
git update-ref refs/remotes/origin/main "$LEAKY"
git checkout -q -B "again" "$LEAKY"
printf '%s\n' "val replyTo = \"$EMAIL_V\"" > again.kt && git add again.kt && git commit -q -m "same address again"
hook "refs/heads/again $(git rev-parse HEAD) refs/heads/again $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "an address already on the remote passes" || bad "already-public address blocked (exit $rc)"
printf '%s\n' "val cc = \"$EMAIL2_V\"" >> again.kt && git commit -q -am "a new address"
hook "refs/heads/again $(git rev-parse HEAD) refs/heads/again $ZERO"; rc=$?
[ "$rc" -eq 1 ] && ok "a new address next to it is stopped" || bad "new address not stopped (exit $rc)"
git update-ref refs/remotes/origin/main "$BASE"
git update-ref -d refs/remotes/origin/leaky

# Addresses that are out already or go up anyway: yours, the remote's authors, the commit's own author.
git checkout -q -B "mine" "$BASE"
git config user.email "$EMAIL3_V"
printf '%s\n' "val owner = \"$EMAIL3_V\"" > owner.kt && git add owner.kt
GIT_AUTHOR_EMAIL=selftest@example.com GIT_COMMITTER_EMAIL=selftest@example.com git commit -q -m "my own address"
hook "refs/heads/mine $(git rev-parse HEAD) refs/heads/mine $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "your own address (user.email) passes" || bad "own user.email address blocked (exit $rc)"
git config user.email "selftest@example.com"
git checkout -q -B "pub" "$BASE"
printf 'p\n' > pub.txt && git add pub.txt && git commit -q -m "by lee" --author "Lee <$EMAIL4_V>"
git update-ref refs/remotes/origin/main "$(git rev-parse HEAD)"
printf '%s\n' "val maintainer = \"$EMAIL4_V\"" > maint.kt && git add maint.kt && git commit -q -m "credit lee"
hook "refs/heads/pub $(git rev-parse HEAD) refs/heads/pub $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "an author already on the remote passes" || bad "remote author's address blocked (exit $rc)"
LAST_OUT=$("$HOOK" --scan --strict "pub~1..pub" 2>&1); rc=$?
[ "$rc" -eq 1 ] && ok "--scan --strict lets nothing through as already public" || bad "--strict did not stop it (exit $rc)"
git update-ref refs/remotes/origin/main "$BASE"
git checkout -q -B "kim" "$BASE"
printf '%s\n' "val author = \"$EMAIL5_V\"" > kim.kt && git add kim.kt && git commit -q -m "kim's file" --author "Kim <$EMAIL5_V>"
hook "refs/heads/kim $(git rev-parse HEAD) refs/heads/kim $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "the commit's own author address passes (it goes up with the commit anyway)" || bad "commit author address blocked (exit $rc)"

echo
echo "Letting findings through (allow list):"
hook "refs/heads/leaky $LEAKY refs/heads/leaky $ZERO" LEDGABLE_PUSH_ALLOW=email; rc=$?
case "$rc:$LAST_OUT" in 0:*"let through"*) ok "LEDGABLE_PUSH_ALLOW=email lets an address through" ;; *) bad "LEDGABLE_PUSH_ALLOW=email did not work (exit $rc)" ;; esac
git checkout -q -B "mixed" "$BASE"
printf '%s\n' "val from = \"$EMAIL_V\"" "val syncToken = \"$LONG48\"" > mixed.kt && git add mixed.kt && git commit -q -m "mixed"
hook "refs/heads/mixed $(git rev-parse HEAD) refs/heads/mixed $ZERO" LEDGABLE_PUSH_ALLOW=email; rc=$?
case "$rc:$LAST_OUT" in 1:*"random-looking value"*) ok "...while a secret on the same push still stops it" ;; *) bad "secret let through with ALLOW=email (exit $rc)" ;; esac
LAST_OUT=$("$HOOK" --allow "$(git rev-parse --short=10 "$LEAKY")" email leak.kt 2>&1); rc=$?
[ "$rc" -eq 0 ] && [ -s "$COMMON/ledgable-push-allow" ] && ok "--allow writes one line to the allow list" || bad "--allow failed (exit $rc)"
hook "refs/heads/leaky $LEAKY refs/heads/leaky $ZERO"; rc=$?
[ "$rc" -eq 0 ] && ok "the allowed finding goes through" || bad "allowed finding still blocked (exit $rc)"
git checkout -q -B "other" "$BASE"
printf '%s\n' "val cc = \"$EMAIL2_V\"" > leak.kt && git add leak.kt && git commit -q -m "another address"
hook "refs/heads/other $(git rev-parse HEAD) refs/heads/other $ZERO"; rc=$?
[ "$rc" -eq 1 ] && ok "the same kind in another commit is still stopped" || bad "allow list matched another commit (exit $rc)"
LAST_OUT=$("$HOOK" --allow not-a-commit email 2>&1); rc=$?
[ "$rc" -eq 2 ] && ok "--allow refuses something that is not a commit" || bad "--allow accepted a bad commit (exit $rc)"
rm -f "$COMMON/ledgable-push-allow"

echo
echo "Turning it on (--install), for every worktree:"
run_git_hook() {  # run the pre-push hook exactly as git would find it, without pushing
  local refs=$1 wt=$2 extra=${3:-}
  printf '%s\n' "$refs" > "$DIR/refs.txt"
  if git hook run -h 2>&1 | grep -q to-stdin; then
    LAST_OUT=$(cd "$wt" && env ${extra:+"$extra"} git hook run --ignore-missing --to-stdin="$DIR/refs.txt" pre-push -- origin "$DIR/no-such-remote.git" 2>&1)
  else
    local h; h=$(cd "$wt" && git rev-parse --path-format=absolute --git-path hooks/pre-push)
    [ -x "$h" ] || { LAST_OUT="no hook at $h"; return 0; }   # git runs nothing: the push would go through
    LAST_OUT=$(cd "$wt" && env ${extra:+"$extra"} "$h" origin "$DIR/no-such-remote.git" < "$DIR/refs.txt" 2>&1)
  fi
}
LEAK_REFS="refs/heads/leaky $LEAKY refs/heads/leaky $ZERO"
git worktree add -q "$DIR/wt-old" "$BASE" 2>/dev/null          # like a main checkout on an older branch
git config core.hooksPath "$DIR/wt-old/scripts/githooks"        # the old way: a folder that is not there
run_git_hook "$LEAK_REFS" "$DIR/wt-old"; rc=$?
[ "$rc" -eq 0 ] && ok "(the old way: core.hooksPath at a missing folder pushes unchecked; this is what --install fixes)" ||
  bad "the old way did not behave as described (exit $rc)"
"$HOOK" --status >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "--status says it is not on" || bad "--status said on when it is not (exit $rc)"
LAST_OUT=$("$HOOK" --install 2>&1); rc=$?
case "$rc:$LAST_OUT" in 0:*"Removed the old core.hooksPath"*) ok "--install turns it on and removes the old setting" ;; *) bad "--install failed (exit $rc)"; printf '%s\n' "$LAST_OUT" | sed 's/^/        /' ;; esac
[ -z "$(git config --get core.hooksPath)" ] && ok "core.hooksPath is no longer set" || bad "core.hooksPath still set"
W=$(cd "$DIR/wt-old" && git rev-parse --path-format=absolute --git-path hooks/pre-push)
[ "$W" = "$COMMON/hooks/pre-push" ] && [ -x "$W" ] && ok "a worktree on an older branch uses the shared launcher" || bad "worktree hook path is $W"
run_git_hook "$LEAK_REFS" "$DIR/wt-old"; rc=$?
case "$rc:$LAST_OUT" in 1:*"Push stopped"*) ok "...and its push is checked (by the copy in the git folder)" ;; *) bad "worktree push not checked (exit $rc)" ;; esac
run_git_hook "refs/heads/clean $CLEAN refs/heads/clean $ZERO" "$DIR/wt-old"; rc=$?
[ "$rc" -eq 0 ] && ok "...and a clean push from there goes through" || bad "clean push from the worktree blocked (exit $rc)"
(cd "$DIR/wt-old" && "$HOOK" --status >/dev/null 2>&1); rc=$?
[ "$rc" -eq 0 ] && ok "--status says it is on" || bad "--status says off after --install (exit $rc)"
# A worktree that has its own scripts/githooks/pre-push uses that one first.
mkdir -p "$DIR/wt-old/scripts/githooks"
printf '#!/bin/sh\necho "own copy ran" >&2\nexit 1\n' > "$DIR/wt-old/scripts/githooks/pre-push"
run_git_hook "refs/heads/clean $CLEAN refs/heads/clean $ZERO" "$DIR/wt-old"; rc=$?
case "$rc:$LAST_OUT" in 1:*"own copy ran"*) ok "a worktree's own scripts/githooks/pre-push runs first" ;; *) bad "worktree's own copy not used (exit $rc)" ;; esac
rm -rf "$DIR/wt-old/scripts"
# With no guard anywhere, the push is stopped, not let through.
mv "$COMMON/ledgable-push-guard" "$DIR/guard-aside"
run_git_hook "refs/heads/clean $CLEAN refs/heads/clean $ZERO" "$DIR/wt-old"; rc=$?
case "$rc:$LAST_OUT" in 1:*"push guard is missing"*) ok "with the guard gone, the push is stopped" ;; *) bad "push with no guard not stopped (exit $rc)" ;; esac
run_git_hook "refs/heads/clean $CLEAN refs/heads/clean $ZERO" "$DIR/wt-old" LEDGABLE_PUSH_OVERRIDE=1; rc=$?
[ "$rc" -eq 0 ] && ok "...unless LEDGABLE_PUSH_OVERRIDE=1" || bad "override with no guard did not work (exit $rc)"
mv "$DIR/guard-aside" "$COMMON/ledgable-push-guard"
# --install never overwrites somebody else's hook.
mv "$COMMON/hooks/pre-push" "$DIR/launcher-aside"
printf '#!/bin/sh\nexit 0\n' > "$COMMON/hooks/pre-push"
LAST_OUT=$("$HOOK" --install 2>&1); rc=$?
if [ "$rc" -eq 2 ] && ! grep -q ledgable-push-guard-launcher "$COMMON/hooks/pre-push"; then ok "--install leaves another pre-push hook alone"
else bad "--install overwrote another hook (exit $rc)"; fi
mv -f "$DIR/launcher-aside" "$COMMON/hooks/pre-push"
git worktree remove --force "$DIR/wt-old"

echo
echo "Without python3 (basic checks):"
ENGINE=basic
run_cases
git checkout -q -B "case" "$BASE"
mkdir -p app/src && printf 'val motto = "%s"\n' "$FP_V" > app/src/Motto.kt
git add -A && git commit -q -m "motto"
LAST_OUT=$(LEDGABLE_GUARD_NO_PYTHON=1 LEDGABLE_PUSH_FINGERPRINTS="$FPFILE" "$HOOK" --scan "$BASE..HEAD" 2>&1); rc=$?
case "$rc:$LAST_OUT" in
  1:*"known-secrets list"*) case "$LAST_OUT" in *"$FP_V"*) bad "basic: fingerprint value printed" ;; *) ok "basic: the known-secrets list works" ;; esac ;;
  *) bad "basic: fingerprint not caught (exit $rc)" ;;
esac
git checkout -q clean
scan "$BASE..HEAD"; rc=$?
[ "$rc" -eq 0 ] && ok "basic: the clean commit passes" || { bad "basic: clean commit blocked (exit $rc)"; printf '%s\n' "$LAST_OUT" | sed 's/^/        /'; }
ENGINE=full

echo
echo "$PASS passed, $FAIL failed.  (Throwaway repository: $DIR/repo)"
[ "$FAIL" -eq 0 ]
