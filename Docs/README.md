# Docs/ — directory guide and documentation rules

> **If you are an AI coding agent (Claude, Codex, Copilot, or otherwise) working in this repo, read this file before creating or editing any document under `Docs/`, `Design/`, or `integration-test/*.md`.** This project has been maintained across many separate agent sessions and by multiple human collaborators; the rules below exist because past sessions — human and AI alike — have repeatedly forgotten them. They are not style preferences. Follow them exactly, every time, even mid-task.

This file intentionally does **not** enumerate individual documents inside
fast-growing directories (`issues/` in particular adds a new file almost
every session). Run `ls Docs/<dir>` to see current contents. What follows is
the *shape* of the directory tree and the rules for adding to it correctly.

---

## 1. Directory structure

```
repo/
├── README.md                    Project overview (bilingual, root)
├── CONTRIBUTING.md              Contribution workflow, language/style, content principles
├── Design/chinese/               Phase 1 institutional design (制度设计) — source of "why", Chinese-primary
├── integration-test/            Live-dataset seeding for the shared test server; own README.md + STATUS.md
├── frontend/, backend/          Application source — see root CLAUDE.md
└── Docs/
    ├── README.md                 This file
    ├── TestAccounts.md           Login credentials for seeded test accounts on the shared test server
    ├── frontend/                 Phase 2 frontend page/flow design spec (Design.md + Design_CN.md)
    ├── backend/                  Backend architecture design (module decomposition, tech stack, rationale)
    ├── integration/               The REST API contract — authoritative for both frontend and backend (APIContract.md + _CN.md)
    ├── implementation/           Phase-by-phase backend + integration build plan, numbered 0N-<Phase>.md,
    │                             plus 00-Status.md — the living "where are we now" tracker
    ├── optimization/             Post-launch redesign/improvement proposals for already-shipped features,
    │                             numbered 0N-<slug>.md, largest number = most recent
    ├── issues/                    One doc per real defect found via live-verification or reported directly by
    │                             a maintainer, numbered 0NN-<slug>.md sequentially, never renumbered/reused
    ├── operations/                Deployment environment, runbooks, version management for the live system
    └── cost-estimation/           Deployment cost estimates (DeploymentCostEstimate.md + _CN.md)
```

A doc's directory tells you its *kind* (design vs. plan vs. defect vs.
operational procedure) — don't mix kinds into one file, and don't create a
new top-level directory without checking whether an existing one already
fits.

---

## 2. Documentation rules

### 2.1 Language policy — English is primary in `Docs/`; new docs are English-only

This inverts `CONTRIBUTING.md`'s Chinese-primary rule, which still governs
`Design/chinese/` (Phase 1 institutional design — historical, left as-is,
translate to English later, not urgent). **Within `Docs/` specifically:**

- **Future documents are English-only.** Do not create a new `_CN.md` twin
  for any new doc, including new numbered docs added to `issues/`,
  `implementation/`, or `optimization/`.
- **Existing bilingual pairs** — currently `Docs/frontend/Design.md`,
  `Docs/integration/APIContract.md`, and
  `Docs/cost-estimation/DeploymentCostEstimate.md` (each with a `_CN.md`
  twin) — stay paired for now. Check `ls` the same directory before editing
  one of these three. English is the source of truth: when you change the
  English file, mirror the change into `_CN.md` (structure and section
  numbering kept identical); don't edit `_CN.md` independently or treat it
  as the phrasing authority.
- **Chinese-only docs with no pair** (if any remain in `Docs/`): leave them
  as-is. Don't block unrelated work on translating them; translate
  opportunistically later if asked.
- Keep terminology consistent wherever English and Chinese coexist: "time
  coin" (never "token"), "evidence storage" (never "blockchain
  transaction"), "witness attestation" (never "witness signature").

### 2.2 Cross-references and section numbers are load-bearing

Other documents cite sections by number (`见 XDoc.md §3` / `see XDoc.md
§3`). Do not renumber existing sections just because a doc feels reorganized
— only do it when restructuring is genuinely necessary, and when you do,
grep the whole repo for references to the old numbers and update every one.
Preserve cross-references in general; if you add or remove a section, check
what pointed at neighboring sections too.

### 2.3 Numbered sequential docs — never renumber, never reuse

`implementation/`, `optimization/`, and `issues/` all use a leading
sequence number (`0N-` or `0NN-`). For all three:

- Before creating a new one, check the **highest existing number in that
  specific directory** (`ls` it — don't guess from memory, another session
  may have added one since).
- The new doc gets the next number. Never renumber an existing doc to make
  room, and never reuse a number that was skipped or superseded.
- If a doc is superseded rather than simply extended, say so in its own
  status line/heading rather than deleting or renumbering it — the sequence
  is a history, not just a index.

### 2.4 Issue docs (`Docs/issues/0NN-<slug>.md`) — the most-forgotten rule

File one **as the work starts**, not only after silently shipping a fix.
This applies to:

- A real defect found during live-verification/browser-testing against the
  actual deployed app (not a code-review finding on unshipped code).
- Anything a maintainer directly flags and wants tracked — including a
  UI-consistency or polish gap, not only strict correctness bugs.
- A data-only fix (e.g. a missing seed row corrected via direct SQL against
  the live database, no application code touched) — this is exactly as
  reportable as a code fix; don't skip the doc just because there's no diff.

Before filing, check both `Docs/issues/` (highest number, and whether this
symptom is already a named issue) **and** `integration-test/STATUS.md`'s bug
log — but re-verify against the live app rather than trusting either
blindly, since a prior doc's claimed fix can fail to have actually landed on
the live server.

Required shape (see any existing issue doc for the pattern):

```
# 0NN — <short title>

Status: <see below>

## Symptom
## Root cause
## Fix              (or "## Proposed fix" if not yet implemented)
## Test plan
## Resolution

*<italic closing line: what was actually verified, and — explicitly — any
verification gap, e.g. "no browser tool available this session," so a later
session knows to close it rather than assume it's covered>*
```

`Status:` line values, in order of how far the work has progressed:

- `Proposed fix — not yet implemented` — filed at the start of work, before
  any fix exists.
- `Fixed and live-verified (YYYY-MM-DD)` — only once the fix has actually
  been confirmed against the real deployed app. Never write this status
  before that verification has actually happened.

Every issue doc must also get a matching row in `integration-test/STATUS.md`
§2 (bug log table) and a dated entry in its §4 (chronological fix history),
added **in the same session**, not batched up later. Update both files again
when a "proposed fix" issue doc's fix actually lands and is verified.

### 2.5 Living-status documents — update on state change, not in a pre-review burst

`Docs/implementation/00-Status.md` and `integration-test/STATUS.md` each
describe *current state*, not a changelog of everything that ever happened.
Update them:

- On phase entry, phase exit, or a gate/risk change (`00-Status.md`).
- Whenever a live-verification session against the real test server finds
  or fixes something (`integration-test/STATUS.md`).
- As you go, in the same session the change happens — not as a rewrite
  assembled just before a review.

These two files track different things and both can be true at once: a
build phase can be `✅` in `00-Status.md` while `integration-test/STATUS.md`
still logs new bugs found while using that "done" feature.

### 2.6 `REVIEW-IMPORTANT` — how design-doc review feedback is given

A maintainer may review a long design doc by editing the file directly and
inserting a `REVIEW-IMPORTANT` marker inline (under a heading, or as an
extra column in an open-questions table), then asking you to "update the doc
following REVIEW-IMPORTANT" or similar. When that happens:

1. `grep -rn REVIEW-IMPORTANT` the doc before doing anything else.
2. Treat each annotation as an authoritative decision that overrides the
   draft — including overriding a recommendation you argued for.
3. Don't just patch the annotated line: propagate the consequence through
   every dependent section, and remove reasoning that is now stale.
4. Convert an answered open question into a recorded decision (with its
   design impact captured), not just a deleted question.

### 2.7 Content principles — full text in `CONTRIBUTING.md`

`CONTRIBUTING.md` (§Content Principles, §Language & Style) is the
authoritative source for: the non-financial-vocabulary ban (no
代币/发币/挖矿/提现/钱包, no "token"/"currency"/"wallet" in English), the
privacy-minimum-necessity principle, the anti-fraud/anti-Sybil
non-weakening rule, legal-compliance-with-`Legal.md` review for any
blockchain/data/coin-economics proposal, and preserving spontaneous,
informal volunteering as a first-class case. These apply to every document
in this repo, not just `Design/`. Read `CONTRIBUTING.md` directly rather
than relying on a paraphrase — it is the source of truth, this file only
points at it.

### 2.8 Secrets never go in tracked docs

Real hostnames, credentials, and connection strings for the shared test
server live only in the untracked, git-ignored `test-server-credits.md`.
Never repeat them in a tracked doc, issue, commit message, or CI
configuration — use a placeholder (`tb.example.com`) instead, as every
existing doc does.

---

## 3. Quick checklist before finishing any doc edit

- [ ] If this is a new doc, is it English-only (no new `_CN.md` created)?
- [ ] If this doc is one of the three existing bilingual pairs, did I update its `_CN.md` twin too?
- [ ] Did I preserve every existing cross-reference and section number?
- [ ] If this is a new numbered doc, did I check the highest existing number
      in that directory just now (not from memory)?
- [ ] If this is an issue doc, does `integration-test/STATUS.md` have a
      matching bug-log row and fix-history entry, added in this same session?
- [ ] Did I avoid financial vocabulary and avoid weakening any anti-fraud/
      legal-compliance provision?
- [ ] Are there real hostnames or credentials anywhere in this diff? (There
      should not be.)
