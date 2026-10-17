# Push guard

A check that runs on this Mac every time you `git push`. It looks at the commits
that are about to go up and stops the push if one of them holds something that
should stay private: a key or password, or someone's personal details.

It only ever names the **file, the commit and the line number**. It never shows
the thing it found, so the warning itself is safe to paste anywhere.

## Turn it on (once per repository)

From any checkout or worktree that has this folder:

```sh
scripts/githooks/pre-push --install
```

That puts a small launcher in the repository's shared git folder
(`.git/hooks/pre-push` of the main checkout), which every worktree uses: the
main checkout even when it is on an older branch, your other worktrees, and
agent worktrees. Before each push the launcher runs this folder's guard if the
worktree has it, and otherwise a copy of the guard that `--install` keeps in the
git folder. If it can find neither, **the push is stopped**: a push is never let
through unchecked. Run `--install` again after the guard changes, to refresh
that copy.

Check it is on, from wherever you push:

```sh
scripts/githooks/pre-push --status
```

If you turned it on the old way (`git config core.hooksPath .../scripts/githooks`),
`--install` removes that setting: it let pushes through unchecked whenever that
folder was missing, for example after the checkout switched to an older branch.

To turn it off: delete `.git/hooks/pre-push` in the main checkout.

## What stops a push

**Secrets**

- the first line of a private key file (`BEGIN ... PRIVATE KEY`)
- tokens with a known beginning: Amazon (AWS), Google API keys and sign-in
  secrets, Stripe, GitHub, Slack, OpenAI-style `sk-` keys, Anthropic, SendGrid,
  npm, Hugging Face, GitLab, Twilio, Mailgun, signed login tokens (JWT)
- `password=`, `secret=`, `api_key=` and names like them given a real-looking
  value. Empty values, `changeme`, `your-key-here`, `${SOMETHING}`,
  `BuildConfig.SOMETHING`, a settings name such as `"imap_password"` and the
  like are fine
- a PHP `define()` or `const` whose name has SECRET, KEY, TOKEN or PASSWORD in
  it and whose value is a real-looking literal of 20 or more characters
  (WordPress salts included)
- a random-looking value given to a key, secret, token or password name, quoted
  or not, or written on the same line as one: `INTAKE_KEY=...` in a script or
  notes, `"intake_key": "..."`, `?key=...` in a web address, `curl -d "key=..."`,
  `-H "X-MF-Key: ..."`, `.add("key", "...")`, `adb ... --es mf_key '...'`, an
  Android `<string name="..._key">` and an Info.plist `<key>...Key</key>` with
  its `<string>` on the next line. A name that ends in key, secret, token or
  password needs 20 characters of random-looking value, other names 32. Hex
  values (0-9 and a-f only) are judged against what random hex looks like, so a
  32-character hex token is caught too. Names strung together
  (`reader_v2_font_size`), words with a timestamp, and counting patterns
  (`0123456789abcdef`) are fine
- `Bearer` and `Basic` login headers with a real token, web addresses with a
  password in them, WordPress application passwords
- files that are normally private: `.env`, `local.properties`, keystores
  (`.jks`, `.keystore`, `.p12`), `.pem` and `.key` files, `google-services.json`,
  `credentials.json`, saved app data (`.db`, `.sqlite`, `.realm`, `.zip`,
  browser `.har` logs), and the same names with `.bak`, `.orig`, `.old` or `~`
  on the end
- readable text inside a binary file the commits add: tokens with a known
  beginning, and anything on your known-secrets list
- anything on your known-secrets list (below)

**Personal details**

- email addresses, except made-up and system ones: `example.com`, `.org` and
  `.net`; anything ending in `.test`, `.example`, `.invalid` or `.localhost`;
  `noreply@anthropic.com`; GitHub's `users.noreply.github.com`; addresses in
  commit-message trailers such as `Co-Authored-By:`; mail message ids
- ...and addresses that are out already or go up anyway: your own `user.email`,
  the author and committer addresses of the commits being pushed (git sends
  those with every commit), and any address the remote's main branch already
  shows, in its files or as an author
- card digits: "ending in" or "last 4" followed by four digits, or a row of
  stars, bullets or `xxxx` followed by four digits
- phone numbers in `.eml`, `.json`, `.txt`, `.vcf` and `.ics` files (555
  numbers are let through)

**What it reads**: only what leaves this Mac with this push. For a new branch
that means the commits no remote branch has yet; for an existing branch, the
commits the remote doesn't have. In those commits it reads the lines that were
added, the names of files that were added or renamed, readable text in binary
files, and the commit message; for an annotated tag, the tag's message too.

**What it can't see**: people's names in ordinary text, values that don't look
random (a plain-words password in code; put it on your known-secrets list), and
anything already on a remote branch: those commits are skipped as already sent.

## When a push is stopped

You'll see something like:

```
========================================================================
  Push stopped: something private would go up with these commits.
========================================================================
Checked 12 commit(s). Nothing has been sent.

  app/src/test/resources/mail/receipt.eml
      commit 1a2b3c4d5e (2026-10-17)  lines 2, 9: a real email address [email]
```

Take it out **of that commit** (or swap in a made-up value such as
`someone@example.com`), then push again. Deleting it in a later commit is not
enough: the earlier commit still goes up with it.

When a folder or file name itself holds an email address, the report shows the
folder and says the name is withheld.

## It is fine to go up: the allow list

Looked at a finding and it may go up (your own business address in a mail test
file, say)? Let that one finding through, using the commit and the kind in
[brackets] from the report:

```sh
scripts/githooks/pre-push --allow 1a2b3c4d5e email                          # that commit, every file
scripts/githooks/pre-push --allow 1a2b3c4d5e email 'app/src/test/resources/mail/*'
scripts/githooks/pre-push --allow '*' email 'app/src/test/resources/mail/*' # any commit, those files
```

The list is kept in `.git/ledgable-push-allow` of the main checkout, one
`<commit or *> <kind or *> [<path, * wildcards allowed>]` per line, shared by
every worktree and never committed. Everything else is still checked: a secret in the
same commit still stops the push.

For a single push, `LEDGABLE_PUSH_ALLOW` lets every finding of the kinds you name
through (secrets of other kinds still stop it):

```sh
LEDGABLE_PUSH_ALLOW=email git push ...
LEDGABLE_PUSH_ALLOW=email,phone git push ...
```

The guard says how many findings it let through each time.

## Check without pushing

```sh
scripts/githooks/pre-push --scan HEAD --not --remotes      # everything not on any remote yet
scripts/githooks/pre-push --scan origin/main..HEAD         # any range git understands
```

It answers 0 when nothing was found and 1 when something was.

**Before making a private repository public**, check its whole history with
nothing let through as "already on the remote" (a private remote is not public):

```sh
scripts/githooks/pre-push --scan --strict --all
```

## Publishing a snapshot to the public fork

The public fork gets the code without the working history: one commit per
publish, holding the tree as it stands, minus the paths in `.public-exclude`
(handoff and audit notes, the odd design note that names an account, assistant
and tooling folders). Your full history stays on this Mac.

```sh
scripts/publish-snapshot.sh --list                    # what is left out
scripts/publish-snapshot.sh -m "comics reader and mail"
```

It builds the commit with `git commit-tree` on top of the public branch
(`origin/punchlist-2026-07-26` unless you pass `--base`), checks it with this
guard, and only then moves the local branch `public-snapshot` to it. If the
guard finds anything, the branch stays where it was. Each later run builds on
the last snapshot, so every push is a fast-forward. It never pushes; it ends by
printing the command to run yourself:

```sh
git push origin public-snapshot:punchlist-2026-07-26
```

The snapshot is made from the last commit, not from uncommitted changes, and the
exclude list is read from that commit too (`--exclude-file .public-exclude` tries
an edited one first). `--dry-run` makes and checks the commit without moving the
branch; `--fetch` refreshes the public branch first. Your allow list still
applies to this check, the same as it will to the push; `LEDGABLE_PUSH_ALLOW`
does not.

## Push anyway

```sh
LEDGABLE_PUSH_OVERRIDE=1 git push ...
```

Everything goes up, with a loud warning that lists what went up. If any of it
was a real secret, change that secret. Prefer the allow list: it lets through
only what you looked at.

## Your known-secrets list

The list holds one sha256 fingerprint per line, never the secret itself. It
lives in the main checkout's git folder, `.git/ledgable-secret-fingerprints`, so
every worktree reads the same list and it can never be committed. Every piece
of every added line that is 20 to 80 characters long is fingerprinted and
compared with the list, so a listed secret is caught in any shape, even when it
looks like ordinary words.

To add one, run this in Terminal from the repository folder. It asks for the
secret without showing it and writes only the fingerprint:

```sh
scripts/githooks/pre-push --add-fingerprint
```

Or by hand (the secret stays out of your shell history):

```sh
read -rs S && printf '%s' "$S" | shasum -a 256 | cut -d' ' -f1 >> "$(git rev-parse --git-common-dir)/ledgable-secret-fingerprints"; unset S
```

Lines starting with `#` are notes. The guard also still reads
`scripts/githooks/known-secret-fingerprints` (the old place, kept out of git by
`.gitignore`), and a list at `LEDGABLE_PUSH_FINGERPRINTS` if you set that to a
path. Only values 20 to 80 characters long are compared.

## Without python3

The full checks need `python3` (it comes with Apple's command line tools, like
git). The guard also looks in `/opt/homebrew/bin` and `/usr/local/bin`, which
apps that push for you often leave off their path. Without python3 a smaller
set runs in `awk`: the same token beginnings, private keys, PHP constants,
random values given to or next to secret names, Bearer tokens, web addresses
with passwords, emails, card digits, phone numbers, private file names, commit
and tag messages, and the known-secrets list. Not covered there: unquoted values
after `--es` in adb lines, Android string resources, Info.plist pairs, unquoted
values in settings files, WordPress salts with spaces, Basic logins, WordPress
application passwords and text inside binary files. The guard says "basic checks
only" when this happens.

## Self-test

```sh
scripts/githooks/selftest.sh [folder]
```

Builds a throwaway repository (in `folder`, or a new temp folder), commits
made-up secrets and personal details one at a time and checks that each one is
stopped, named by file and commit, and never printed. It also checks that
everyday lines and a clean commit go through, that only the commits leaving the
machine are read, that the allow list and the override work, that `--install`
covers every worktree, that a worktree's own copy runs first, and that a push
with no guard to be found is stopped. Nothing is pushed: the hook is given the
same lines git would give it, or run with `git hook run`.
