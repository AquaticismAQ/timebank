# Docs/ — directory guide and documentation rules

> **If you are an AI coding agent (Claude, Codex, Copilot, or otherwise) working in this repo, read this file before creating or editing any document under `Docs/`, `Design/`, or `integration-test/*.md`.** This project has been maintained across many separate agent sessions and by multiple human collaborators; the rules below exist because past sessions — human and AI alike — have repeatedly forgotten them. They are not style preferences. Follow them exactly, every time, even mid-task.

This file intentionally does **not** enumerate individual documents inside
fast-growing directories (`issues/` in particular adds a new file almost
every session). Run `ls Docs/<dir>` to see current contents. What follows is
the *shape* of the directory tree and the rules for adding to it correctly.

**Current project status (2026-08-26):** Phase 1 institutional design is
complete, including the same-named English mirrors in `Design/english/`.
Phase 2, the online system, is ongoing and approximately **90% complete**.
The React frontend and Spring Boot backend are deployed together on the
shared test server and have been verified there through real API and browser
flows. Remaining work is primarily acceptance/cutover completion and
infrastructure-dependent operations; this test deployment is not a
production launch. For authoritative item-by-item status and verification
evidence, read the latest dated sections of
`Docs/implementation/00-Status.md`.

---

## 1. Directory structure

```
repo/
├── README.md                    Project overview (bilingual, root)
├── CONTRIBUTING.md              Contribution workflow, language/style, content principles
├── Design/chinese/               Phase 1 institutional design (制度设计) — authoritative source of truth
├── Design/english/               Complete, same-named English mirrors of Design/chinese/
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
    ├── operations/                Deployment environment, runbooks, version management for the live system.
    │                             Configuration.md is the reference for every runtime setting the server reads
    │                             (/etc/timebank-backend.env); DeploymentEnv.md covers the workstation's own
    │                             ~/.timebank.env — the two are different files with overlapping names
    ├── backlog/                   Work that is specified but not built and has no target release,
    │                             numbered 0N-<Slug>.md — see §2.9
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
the Phase 1 institutional design documents. `Design/chinese/` is the
authoritative source of truth, and `Design/english/` contains complete,
same-named English mirrors. When a Chinese design document changes, update
its English mirror in the same change; do not edit the English mirror as an
independent source. **Within `Docs/` specifically:**

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

### 2.7.1 A new list page has a shape to match

Any screen rendering a server-paginated list follows
`Docs/optimization/05-SearchFilterSortConsistency.md` — search, filters, sort and
an empty state that tells "nothing here" apart from "nothing matches". This is
checked mechanically by `frontend/scripts/check-list-conventions.mjs`, which
`deploy.sh` runs beside the type check, so a new page that misses it fails to
deploy rather than shipping and being noticed months later (issue 138). Opting
out is fine and must carry a reason in the file.

### 2.8 No real identifying data anywhere in this repo — docs, code, or commit messages

This repo is intended to eventually go public. Real hostnames, credentials,
and connection strings for the shared test server live only in the
untracked, git-ignored `test-server-credits.md`. The same ban extends to any
real person's or organization's name — school staff, students, the school
itself — regardless of whether it appears in `Docs/`, in application code
(frontend i18n copy, backend seed data), or in a commit message. Use the
placeholders already established elsewhere in the repo instead of inventing
new ones: `tb.example.com` for the shared test server's hostname,
`Teacher Wei Chen` for a staff contact, and `example-township-school.edu` for
email domains.

**Two hostname placeholders, because there have been two servers.** Writing up
anything that happened before the move needs to distinguish them, and collapsing
both onto one placeholder turns a sentence that contrasts them into nonsense:

| Placeholder | Means |
| :--- | :--- |
| `tb.example.com` | the **original** shared test server, retired as the active box on 2026-09-16 |
| `timebank.example.com` | the server that has been active since then |

Pick by which box the sentence is about, not by which reads better.

**The school placeholders are not interchangeable — which one you pick says
which organisation you mean:**

| Placeholder | Means |
| :--- | :--- |
| `Example City HD` | the **host school**, the one running the system (`school_info.name`, and its own `org_school_default` row) |
| `Example City HD Middle School` | that same host school's **full name** (`school_info.full_name`) |
| `Example Township Middle School` | a **partner school** — an ordinary organisation the host school works with (`integration-test/mock-data.ts`) |

This list previously ran `Example City HD` and `Example Township Middle School`
together as if either would do for "the school". They would not:
`R__seed_base_data.sql` seeded the host school's `full_name` with the *partner's*
placeholder, so every screen preferring `full_name` displayed a partner
organisation as the host school, on the login page included.

This is easy to violate without noticing. Narrating a live-verification
session ("logged in as `admin001`, saw '<real contact name>' in Admin
Contact") naturally wants to quote whatever the real deployed system
actually shows — but that's exactly how real PII leaked into ~20 issue docs
and `STATUS.md` entries, undetected for a week (`Docs/issues/057-*.md`,
found and scrubbed 2026-08-26, including a full git-history rewrite). When
writing up a live-verification result, describe *what field/value it maps
to* rather than quoting a literal live value unless you're certain it's
fixture data you created yourself, not something the real deployment
actually holds.

Once something real lands in a commit, it exists in git history even after
the file is fixed. Finding real data already committed is a shared,
hard-to-reverse-action situation like §2.4's live database writes: flag it
and get confirmation before any history rewrite, don't just quietly fix the
working tree and move on. And when writing up the fix itself, describe
*categories* of what changed rather than quoting the removed value verbatim
— a rewrite that replaces a string throughout history will catch that quote
too and mangle it into nonsense (`Docs/issues/057-*.md`'s own first draft
hit exactly this).

### 2.9 `Docs/backlog/` — specified but not built

For work that has been **designed in enough detail to be relied on by another
document**, but that nobody has built and nothing has scheduled.

It exists because a design doc will sometimes promise a mechanism that has no
implementation — `Design/*/12-DataImport.md` §6 closes the historical-import
window and says the only way to reopen it is a console utility. That utility has
to be written down somewhere, or the design has promised a door nobody specified.

**How it differs from the neighbouring directories:**

| Directory | Holds |
| :--- | :--- |
| `implementation/` | The build plan for work that *is* scheduled, phase by phase |
| `optimization/` | Proposals to improve a feature that already ships |
| `issues/` | A defect in something that exists |
| `backlog/` | Something that does not exist, is not scheduled, and is referenced by a design |

Rules:

- Numbered `0N-<Slug>.md`, sequential, never renumbered or reused.
- The first line states its status and what depends on it. A backlog doc that
  nothing references is a note, not a specification — put it elsewhere or delete it.
- It must say what the thing **must not** do, not only what it should. These docs
  are read by whoever eventually builds the feature, often long after the
  reasoning has been forgotten.
- When the work is built, the doc does not move: update its status line to point
  at the implementation, the same way an issue doc is closed rather than deleted.

---

### 2.10 Renaming a document does not license editing an applied migration

A repo-wide rewrite of document references — the sort of thing a renumbering
needs — must **exclude `backend/src/main/resources/db/migration/V*.sql`.**

Flyway checksums the whole file, comments included, and refuses to start against
a database where an applied migration's checksum has changed. So changing
`10-RolesAndPermissions.md` to `11-` inside a comment in an already-applied
migration does not tidy a reference; it takes the application down on the next
deploy, with an error that names Flyway and says nothing about documents. That
happened on 2026-09-21 and is written up in `Docs/issues/153-*.md` §4.

Two things that will not save you: it compiles, and it reviews cleanly. The
files are only read when the application starts against a database that already
has them.

The repeatable seeds (`R__*.sql`) are the opposite case — they are *designed* to
re-run when their content changes, so their references should be kept current.

If an applied migration's text genuinely needs to change, it does not: write a
new migration. The old one is a record of what was run, not a document.

### 2.11 A design document states the design, never how it got there

`Design/chinese/*.md` and their English mirrors describe **the system as
designed, in the present tense.** They are read by someone asking "how does this
work and why is it like that" — never "what changed last Tuesday".

So none of the following belongs in one, no matter how true it is:

| Do not write | Write instead |
| :--- | :--- |
| "changed 2026-09-22 — previously teachers held it" | the rule as it stands, with its reason |
| "this design changes two of the groups" | which groups need explaining, and why |
| "X used to live inside Y; we split it out" | why X and Y are separate things |
| "§5a is a section added later" | nothing — the section is simply there |
| "`V34` carries the migration; `INSERT IGNORE` cannot update a row" | nothing — that is implementation mechanics |
| "not yet implemented" / "planned for a later phase" | the design; build state lives elsewhere |
| "reversing the earlier merge", "X loses Y" | what each role holds now, and why they differ |

**Where the history goes instead**, because it is genuinely worth keeping:

- *why a change was made and what it cost* → the issue doc (`Docs/issues/0NN-*.md`)
- *what state the build is in* → `Docs/implementation/00-Status.md`
- *what broke in live use* → `integration-test/STATUS.md`
- *what actually ran against the database* → the migration file itself

A design doc that carries its own changelog decays in a specific way: the reader
cannot tell which sentences describe the system and which describe a detour, so
every statement needs dating before it can be trusted. §2.5 says the same thing
about the status docs from the other direction — they hold current state, not
everything that ever happened.

**When revising one, rewrite the affected passage in place.** Do not append a
correction, do not leave the superseded sentence beside the new one, and do not
mark what changed. The diff already records that; the document should read as
though it had always said this.

Two things this rule does **not** forbid:

- **Citing another document** for a rule's basis or a mechanism's detail. A
  cross-reference is navigation, not narration.
- **Naming something retired**, when the retired thing still exists and a reader
  will meet it — a permission row that grants nothing, a status a state machine
  no longer reaches. State what it is now ("grants nothing, cannot be assigned"),
  not the story of its retirement.

One constraint on tidying: **section numbers stay put** (§2.2). A section that
reads like an insertion — `§5a` — is still better than renumbering, because
section numbers are cited from applied migrations, which cannot be edited
(§2.10).

## 3. Quick checklist before finishing any doc edit

- [ ] If this is a new doc, is it English-only (no new `_CN.md` created)?
- [ ] If this changes a Phase 1 design document, are the same-named files in
      `Design/chinese/` and `Design/english/` synchronized, with Chinese
      treated as the source of truth?
- [ ] If this doc is one of the three existing bilingual pairs, did I update its `_CN.md` twin too?
- [ ] Did I preserve every existing cross-reference and section number?
- [ ] If this is a design doc, does every sentence describe the system as it is
      — no dates, no "previously", no "this design changes", no migration
      filenames, no changelog (§2.11)?
- [ ] If this is a new numbered doc, did I check the highest existing number
      in that directory just now (not from memory)?
- [ ] If this specifies work nobody is building yet, is it in `backlog/` (§2.9)
      rather than in `implementation/` or `optimization/`, and does something
      reference it?
- [ ] If this is an issue doc, does `integration-test/STATUS.md` have a
      matching bug-log row and fix-history entry, added in this same session?
- [ ] Did I avoid financial vocabulary and avoid weakening any anti-fraud/
      legal-compliance provision?
- [ ] Are there real hostnames, credentials, or any real person's/school's
      name anywhere in this diff? (There should not be — check code and
      i18n copy too, not just this doc; see §2.8.)
