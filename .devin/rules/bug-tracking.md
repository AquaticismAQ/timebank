---
description: Bug tracking and issue documentation workflow — when a live-verified
  defect is found against the real deployed app. Follow Docs/README.md §2.4 (binding).
trigger: glob
globs:
- Docs/issues/**
- integration-test/STATUS.md
- Docs/implementation/00-Status.md
- frontend/**
- backend/**
---

# Bug Tracking & Issue Documentation

**The authoritative source is `Docs/README.md` §2.4. Read it before creating or editing any issue doc.**

When a live-verification/browser-testing session against `https://tb.example.com/` finds a real defect (reproduced against the real deployed app, not a code-review finding), document it — don't just fix it silently.

## When to file an issue doc

File one **as the work starts**, not only after silently shipping a fix. This applies to:

- A real defect found during live-verification/browser-testing against the actual deployed app
- Anything a maintainer directly flags and wants tracked — including UI-consistency or polish gaps
- A data-only fix (e.g. a missing seed row corrected via direct SQL, no application code touched)

Before filing, check both `Docs/issues/` (highest number, and whether this symptom is already named) **and** `integration-test/STATUS.md`'s bug log — but re-verify against the live app rather than trusting either blindly.

## Required outputs for every live-found bug

### 1. Per-bug doc: `Docs/issues/0NN-<slug>.md`

Numbered sequentially from the highest existing file in the directory (check first — never renumber or reuse). Shape:

```markdown
# 0NN — Short human-readable title

Status: Proposed fix — not yet implemented
   — or: Fixed and live-verified (YYYY-MM-DD)

## Symptom
What the user sees. Be specific (page, role, action, observed vs expected).

## Root cause
Why it happens. Reference actual code paths, permissions, data gaps.

## Fix
What was changed (code, SQL, config). Reference files/functions.
— or: ## Proposed fix (if not yet implemented)

## Test plan
How correctness was verified — IT tests, live browser check, API calls.

## Resolution
Summary of what shipped (files changed, deployed, SQL applied).

---
*Live-verified YYYY-MM-DD against `https://tb.example.com/` (describe what was
actually clicked/observed). [Name any verification gap explicitly — e.g.
"no browser tool available this session" or "API-level only, browser
click-through deferred.']*
```

Status values, in order of progress:
- `Proposed fix — not yet implemented` — filed at the start of work, before any fix exists
- `Fixed and live-verified (YYYY-MM-DD)` — only once the fix has actually been confirmed against the real deployed app. Never write this status before that verification has actually happened.

### 2. Update `integration-test/STATUS.md`

In the **same session** (not batched later):

- **§2 Bug log table**: append a numbered row — `| # | Date | Area | Symptom | Root cause | Fix | Status |`
- **§4 Fix history**: append a dated bullet summarizing what was found, fixed, and how it was verified

Update both files again when a "proposed fix" issue doc's fix actually lands and is verified.

### 3. Update `Docs/implementation/00-Status.md` — phase/gate changes only, not every bug

`00-Status.md` tracks build *phases* (per `Docs/README.md` §2.5), not individual
bugs — in practice it has **not** gotten a new numbered section for any of the
issues filed after §37 (2026-08-10); ongoing bug-fix activity lives entirely in
`integration-test/STATUS.md` instead. Only add/update a `00-Status.md` section
when this bug fix also changes a phase's status, an acceptance-criteria gate, or
a named risk (e.g. it closes an I3.7 scenario, or completes/reopens a phase). A
UI-consistency or polish fix — even one the user asked to have tracked — is
`integration-test/STATUS.md`-only unless it also does one of those things.

## Rules

- **Data-only fixes get a doc too.** A missing seed row fixed via direct SQL is exactly as reportable as a code change.
- **Check before assuming new.** Read `Docs/issues/` and `integration-test/STATUS.md`'s bug log before writing a new doc — the symptom may already be filed (re-verify live rather than trusting the doc blindly, since live data can drift).
- **Live DB writes need narration.** Direct `INSERT`/`UPDATE` against the real `timebank` schema is shared-state and hard to reverse — narrate the SQL and get confirmation before running, same as any risky action.
- **Say what wasn't verified.** The italic footer must explicitly state whether verification was browser-level (actual click-through) or API-level only, so a later session knows whether to close that gap.
- **One issue per file.** If a fix surfaces a second bug, log it separately (reference the original in both docs).
