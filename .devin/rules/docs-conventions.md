---
description: Documentation conventions when editing Design/ or Docs/ files — follow Docs/README.md (binding)
trigger: glob
globs: '{Design,Docs}/**/*.md'
---

# Documentation Conventions

**The authoritative source is `Docs/README.md`. Read it before creating or editing any doc. These are the most critical rules summarized from there.**

## Language Policy (§2.1)

- **Within `Docs/`, English is primary.** New docs are English-only — do not create new `_CN.md` twins.
- **Existing bilingual pairs** (`Docs/frontend/Design.md`, `Docs/integration/APIContract.md`, `Docs/cost-estimation/DeploymentCostEstimate.md`) stay paired. English is source of truth; mirror changes into `_CN.md` (structure and section numbering kept identical). Don't edit `_CN.md` independently.
- `Design/chinese/` (Phase 1) is Chinese-primary, historical, left as-is.
- Terminology: "time coin" (never "token"), "evidence storage" (never "blockchain transaction"), "witness attestation" (never "witness signature").

## Cross-References (§2.2)

- Section numbers are load-bearing — other docs cite them (`见 XDoc.md §3` / `see XDoc.md §3`)
- Don't renumber existing sections gratuitously; if you must, grep the whole repo and update every reference
- Check what pointed at neighboring sections when adding/removing

## Numbered Sequential Docs (§2.3)

- `implementation/`, `optimization/`, and `issues/` all use leading sequence numbers (`0N-` or `0NN-`)
- Before creating a new one, `ls` the directory to check the highest existing number — don't guess from memory
- Next doc gets the next number. Never renumber, never reuse a skipped/superseded number
- If superseded, say so in the doc's own status line — don't delete or renumber

## Issue Docs (§2.4) — the most-forgotten rule

- File one **as the work starts**, not only after silently shipping a fix
- Applies to: real defects found via live-verification, maintainer-flagged items, data-only fixes (direct SQL with no code diff)
- Before filing, check both `Docs/issues/` and `integration-test/STATUS.md`'s bug log — re-verify against live app
- Required shape: `Status:` line, `## Symptom`, `## Root cause`, `## Fix` (or `## Proposed fix`), `## Test plan`, `## Resolution`, italic closing footer
- Status values: `Proposed fix — not yet implemented` → `Fixed and live-verified (YYYY-MM-DD)` (only after actual verification)
- Every issue doc must also get a matching row in `integration-test/STATUS.md` §2 (bug log) + §4 (fix history), in the **same session**

## Living-Status Documents (§2.5)

- `Docs/implementation/00-Status.md` and `integration-test/STATUS.md` track *current state*, not changelog
- Update on phase entry/exit/gate change (`00-Status.md`) or live-verification finding (`STATUS.md`)
- Update as you go, in the same session — not as a pre-review burst

## REVIEW-IMPORTANT (§2.6)

- When a maintainer edits a doc with `REVIEW-IMPORTANT` markers: `grep -rn REVIEW-IMPORTANT` first
- Treat each annotation as authoritative — overrides the draft, including your own recommendations
- Propagate consequences through all dependent sections; remove stale reasoning
- Convert answered open questions into recorded decisions

## Content Principles (§2.7)

- `CONTRIBUTING.md` (§Content Principles, §Language & Style) is authoritative
- No financial vocabulary: 代币/发币/挖矿/提现/钱包, no "token"/"currency"/"wallet"
- Privacy-minimum-necessity, anti-fraud/anti-Sybil non-weakening, legal-compliance-with-`Legal.md` review
- Preserve spontaneous, informal volunteering as a first-class case

## Secrets (§2.8)

- Real hostnames, credentials, connection strings live only in untracked `test-server-credits.md`
- Never repeat them in tracked docs, issue docs, commit messages, or CI config — use `tb.example.com` placeholder

## Pre-Finish Checklist (§3)

- New doc is English-only (no new `_CN.md`)?
- If editing a bilingual pair, updated its `_CN.md` twin too?
- Preserved every existing cross-reference and section number?
- If new numbered doc, checked highest existing number just now?
- If issue doc, `integration-test/STATUS.md` has matching bug-log row + fix-history entry in this session?
- Avoided financial vocabulary and anti-fraud/legal-compliance weakening?
- No real hostnames or credentials in the diff?
