# 057 — Real school name, contact name, and hostname leaked into tracked docs/i18n copy and git history

Status: **Fixed and live-verified (2026-08-26)**

## Symptom

This repo is intended to go public eventually and already claims (`Docs/README.md`
§2.8) that real hostnames/credentials never appear in tracked docs — only
placeholders like `tb.example.com`. That claim was false in practice: the real
school's short name, its real admin contact's name, its real email domain, and
the real shared test server's hostname had all leaked into tracked files — both
hardcoded frontend i18n copy (shipped in the public build) and narrative prose
in `Docs/issues/`, `Docs/optimization/`, and `integration-test/STATUS.md`. The
same strings existed across git history (introduced 2026-08-19 through
2026-08-23).

The actual seeding scripts (`integration-test/mock-data.ts`, `seed.ts`,
`backend/.../R__seed_base_data.sql`) were already clean — they seed placeholder
values (`Example City HD`, `Example Township Middle School`, `Teacher Wei Chen`,
matching what's now used everywhere else too). The real values only ever
entered the repo via narrative documentation quoting what was actually observed
on the live server, and via leftover hardcoded UI strings that pre-date the
school info being wired up as dynamic (`GET /school/info`).

**Note for future readers/agents**: precisely because this doc's job is to
record that a leak happened, it deliberately does **not** quote any of the
real strings that were removed — doing so would just recreate the leak this
issue exists to close. If you need to know exactly what the placeholder text
now reads, read the current `frontend/src/i18n/*.json` directly; if you're
verifying no real value survives anywhere, `git log --all -p | grep` for
whatever your own out-of-band knowledge of the real values is, not for
anything written in this repo.

## Root cause

No mechanism prevented an agent session from quoting real live-server values
(admin contact name, decoy org name, deployed hostname) directly into an issue
doc or `STATUS.md` entry while narrating live-verification results — the
`tb.example.com` convention in `Docs/README.md` §2.8 was written but never
enforced by anything, and was violated repeatedly across ~20 issue docs.
Separately, several frontend i18n strings (school full-name/footer/login-notice/
privacy-footer/new-project-placeholder/email-placeholder/admin-contact-support
copy) hardcoded the real school/contact name as static copy instead of only
ever surfacing it dynamically via the already-built `schoolInfo` API.

## Fix

Working tree: replaced every real-value occurrence with the placeholder
equivalent, across all 31 affected tracked files, reusing the two placeholder
conventions already established elsewhere in the repo (
`backend/src/main/resources/db/migration/R__seed_base_data.sql`'s
`Example City HD` / `Example Township Middle School` / `Teacher Wei Chen`, and
`Docs/integration/APIContract_CN.md`'s `示例中学` example response) rather than
inventing a third. Two mechanical duplications introduced by the blind
substitution were hand-fixed afterward: the login-notice string's placeholder
school name and the following word both containing "中学" (collapsed the
duplication), and a stray now-unnecessary space before the Chinese contact
name (the space existed only because the original mixed a Latin first name
into otherwise-Chinese text).

`Design/chinese/07-AI.md` / `Design/english/07-AI.md`'s references to the
school's home city (for the AI climate-template feature) were deliberately
left unchanged — the city alone (~9M population) isn't uniquely identifying
once the school name itself is scrubbed; confirmed with the maintainer before
starting. `frontend/src/i18n/*.json`'s `locationPlaceholder` (an unrelated
example-location string that happened to also name the same city) was left
for the same reason.

No new config-file mechanism was built for seeding — investigated first and
found unnecessary: the real school's data already lives in the database
(`school_info` table), configurable per-deployment via the existing
sysadmin-only `PATCH /school/info` endpoint (issue 045), not in any file this
repo tracks.

History: rewrote the full git history (`git filter-repo --replace-text=...
--replace-message=...`, same replacement list applied to both blob content and
commit/tag messages) to remove the same strings from every past commit, then
force-pushed all branches and tags to `origin`. This is the repo's second full
history rewrite (the first was the `783d971` split, also 2026-08-26); a local
mirror backup was taken immediately beforehand
(`~/Documents/Misc/TimeBank/repo-backup-pre-scrub-2026-08-26.git`) in case
anything needed recovering. All commit hashes from before this rewrite are now
invalid — anyone with an existing local clone must re-clone or hard-reset to
the new history rather than pull/merge.

One residual leak was found and fixed in a second pass: this doc's own first
draft, written *before* the history rewrite, quoted the real strings verbatim
in its "before/after" explanation (to document exactly what was being
replaced) — those quotes then got caught by the same rewrite that was
supposed to remove them, since `--replace-text` doesn't distinguish "this is
the sensitive string" from "this is prose about the sensitive string." That
produced nonsensical self-referential text (`X → X`) in both this doc and the
matching `STATUS.md` entries, rewritten in this pass to describe categories of
what changed instead of quoting literal old values — which is what let this
doc pass its own review criteria for the first time.

## Test plan

- `git grep` sweep for all six real-value patterns across the whole tracked
  tree returns zero matches (only the deliberately-retained city-name mentions
  remain).
- `git log --all -p | grep` for the same six patterns across full rewritten
  history returns zero matches.
- `python3 -c "import json; json.load(...)"` on both `frontend/src/i18n/en.json`
  and `zh.json` — valid JSON.
- `pnpm build` — clean.
- `git push --force` to `origin` succeeds and `git ls-remote origin` shows the
  new (post-rewrite) commit hashes.

## Resolution

Working-tree sweep applied across 31 files, then the full git history (141
commits, 1 branch, 6 tags) was rewritten with `git filter-repo` to remove the
same strings from blob content and commit/tag messages, and force-pushed to
`origin`. `git grep`/`git log --all -p | grep` both confirmed zero remaining
occurrences across the entire tracked tree and full history (city-name
mentions intentionally excluded, confirmed with the maintainer). `pnpm build`
clean, i18n JSON valid. A local mirror backup of the pre-rewrite repo exists
locally in case of any recovery need.

*Verified via repo-wide `git grep` (working tree) and `git log --all -p |
grep` (full history) sweeps for all six real-value patterns, both returning
zero matches after the fix; `pnpm build` clean. Not separately
browser-verified since no functional/deployed behavior changed — this is a
copy-and-history-only change, and the live deployed app's actual `schoolInfo`
row (in the database, not this repo) is unaffected by any of it.*
