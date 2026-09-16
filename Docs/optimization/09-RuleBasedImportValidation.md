# Rule-Based Import Validation (Phase 2) — Implementation Plan

## 0. Scope and how to read this

Phase 2 of the security and privacy work begun in `08-SecurityPrivacyHardening.md`. It
replaces the hardcoded import validators with a **declarative, rule-driven** engine, so that
what a CSV must satisfy is *data* rather than Java branching, and so the import template and
the validation rules can no longer drift apart.

**Depends on Phase 1.** Step `08-…` §3.1 removes three columns from the student import, so
the student rule set should be authored against the post-drop shape. Doing Phase 2 first
would mean writing rules for fields about to disappear.

**Two concerns are combined here on purpose:** making validation configurable, and closing the
remaining input-side security gaps. They touch the same code path, and splitting them would
mean rewriting the parser twice.

**Out of scope:** the export-side formula-injection fix (already done in Phase 1 §3.5), the
import UI, and any change to what import *does* once a file is valid — the commit path,
job lifecycle, and progress reporting stay as they are.

---

## 1. What already exists (verified findings)

### 1.1 The import pipeline is sound and stays

`ImportController` exposes a real two-phase workflow — `templates/{type}`, upload (multipart),
`{id}`, `{id}/validate`, `{id}/errors`, `{id}/commit`, `{id}/cancel` — with jobs run
asynchronously, progress written per job, and a reclaim sweep for stranded jobs. Commit
deliberately re-validates rather than trusting the validate phase's stored result. **None of
that changes.**

### 1.2 Validation itself is hardcoded

Two sibling validators, `StudentImportRowValidator` and `HistoricalImportRowValidator`,
each hold their rules as inline Java:

- **Columns are positional.** `line.split(",", -1)` with fixed indices, and a `parts.length < 7`
  guard whose error message spells the column order out in a string literal.
- **Rules are branches.** Required, uniqueness, reference existence, reference consistency,
  numeric range, and date format are a sequence of `if` statements returning
  `new Invalid(field, code, message)`.
- **Error codes are string literals** scattered through the method (`REQUIRED`,
  `UNKNOWN_GRADE`, `CLASS_GRADE_MISMATCH`, `INVALID_YEAR`, …).
- **Messages are hardcoded English**, despite the system being bilingual everywhere else.
- **The template is authored separately** from the validator, so a column added to one can
  silently not exist in the other.
- Adding a new importable entity means writing a third validator by copy-and-adapt.

### 1.3 Input-side gaps that remain after Phase 1

| # | Finding |
| :--- | :--- |
| G-1 | **`split(",", -1)` cannot parse RFC 4180 quoted fields.** Export writes properly quoted fields; import cannot read them back. Any name containing a comma silently shifts every subsequent column — an export → edit → re-import round trip corrupts data without an error |
| G-2 | **No formula-injection rejection on input.** Phase 1 neutralizes it on export; input should refuse it too, so hostile content never enters storage |
| G-3 | **No control-character or bidirectional-override rejection.** `U+202E` and friends make displayed text differ from stored text |
| G-4 | **No length validation** against column widths — `displayName` is `VARCHAR(100)`, `student_number` `VARCHAR(32)`; over-long input fails at the database instead of as a clean row error |
| G-5 | **No student-number format rule.** Phase 1's design settles on a school-issued stable hash; nothing currently constrains the shape |
| G-6 | **No file-level caps** on row count or byte size |

---

## 2. Decisions needed

### 2.1 Where do rule definitions live? *(the central decision)*

| Option | Pros | Cons |
| :--- | :--- | :--- |
| **A. YAML/JSON in the repo**, loaded at startup | Version-controlled, reviewable in PRs, testable, no schema work, no UI | Changing a rule needs a deploy |
| **B. Database table**, versioned with an immutable-publish lifecycle | Editable without deploy; mirrors `cn.timebank.rules` (`RuleVersion`/`RuleStatus`), a pattern already proven in this codebase | Needs a migration, an admin UI to be useful, and an approval story; rules become live data that must be backed up and audited |
| **C. Hybrid** — built-in defaults in the repo, optional DB overrides | Flexible | Two sources of truth for "what is the rule right now", the hardest to reason about when something rejects unexpectedly |

**Recommended: A.** Import rules change rarely, and they are closer to schema than to policy —
a wrong rule silently corrupts the roster, so review-before-deploy is a feature, not friction.
Option B is the right answer only if the school is expected to self-serve rule changes, which
has not been asked for. The engine should keep the rule *source* behind an interface so B
remains reachable without rewriting the evaluator.

### 2.2 Rule expressiveness

A fixed catalogue of parameterized rule types (`required`, `regex`, `maxLength`, `range`,
`dateFormat`, `enum`, `uniqueInFile`, `uniqueInDb`, `referenceExists`, `referenceConsistent`,
`unsafeContent`) versus a general expression language (SpEL/CEL) evaluated per row.

**Recommended: the fixed catalogue.** It is auditable, cannot execute arbitrary logic on
untrusted input, produces stable machine-readable error codes, and covers every rule the two
existing validators contain. An expression language is a security surface of its own in a
component whose entire job is handling hostile files.

### 2.3 Cross-field and cross-row rules

Some existing checks are not single-field: class-belongs-to-grade is cross-field,
`DUPLICATE_IN_FILE` is cross-row, and `STUDENT_NUMBER_TAKEN` needs a database lookup. The
model must express all three without falling back to code. Proposal: field rules, row rules
(with access to the whole parsed row), and file rules (with accumulated state).

### 2.4 Error message localization

Error codes stay stable and machine-readable; messages become i18n keys resolved through the
existing `RequestLanguageService`. Decide whether the error report returns the resolved
message, the code plus parameters for the client to render, or both. Returning both is the
safest for an API consumed by a bilingual UI.

### 2.5 Header-based columns and backward compatibility

Moving from positional to header-named columns fixes fragility, but existing files the school
already has are positional and headerless.

Proposal: require a header row, match columns by name, and reject a file whose header does not
match the declared rule set with a clear diff ("missing: `gradeId`; unexpected: `guardianName`").
Confirm the school is not mid-flight with headerless files — if they are, accept a documented
positional fallback for one release.

---

## 3. Implementation plan

### 3.1 Step 1 — RFC 4180 parser (G-1)

Replace `split(",", -1)` with a compliant reader handling quoted fields, escaped quotes, and
embedded newlines, so the system can read back what it writes. Parse failures become a
row-level error, not an exception.

**Verification:** export students, edit a name to contain a comma, re-import, and confirm the
columns still land correctly — the exact round trip that silently corrupts data today.

### 3.2 Step 2 — Rule model and engine

- A rule set per import type, declaring columns and the rules bound to each.
- Three scopes: **field** (value only), **row** (whole parsed row, for cross-field checks),
  **file** (accumulated state, for in-file uniqueness and caps).
- Evaluation is ordered and short-circuits per field, so one bad value yields one clear error
  rather than a cascade.
- The engine returns a structured result — row number, column, code, parameters — which the
  existing error-report endpoint renders.
- Rule source sits behind an interface (§2.1) so a database-backed source stays reachable.

### 3.3 Step 3 — Rule catalogue

Implement the §2.2 types. Between them they must reproduce **every** check the two current
validators perform, with no behavioural regression:

| Existing check | Expressed as |
| :--- | :--- |
| `REQUIRED` | `required` |
| `DUPLICATE_IN_FILE` | `uniqueInFile` (file scope) |
| `STUDENT_NUMBER_TAKEN` | `uniqueInDb` (file scope, repository-backed) |
| `UNKNOWN_GRADE` / `UNKNOWN_CLASS` / `UNKNOWN_ACADEMIC_YEAR` | `referenceExists` |
| `CLASS_GRADE_MISMATCH` | `referenceConsistent` (row scope) |
| `INVALID_YEAR` | `range` |
| `INVALID_DATE` | `dateFormat` |
| `PARSE_ERROR` | header/arity mismatch from §3.5 |

### 3.4 Step 4 — Security rules (G-2 … G-6)

New rule types, applied to every text column by default rather than opt-in:

- **`unsafeContent`** — reject leading `=`, `+`, `-`, `@`, tab, or CR (formula injection,
  input side; complements Phase 1's export-side escaping); reject NUL and other C0/C1 control
  characters; reject bidirectional override characters.
- **`maxLength`** — bound to the actual column widths (`displayName` 100, `studentNumber` 32)
  so over-long values fail as clean row errors instead of database exceptions.
- **`regex`** — student number constrained to `[0-9a-zA-Z_-]`, matching the school-issued
  stable-hash format agreed in the design doc.
- **File caps** — maximum row count and byte size, rejected before parsing begins.

### 3.5 Step 5 — Header handling and template generation

- Match columns by header name per §2.5, with an explicit header-mismatch error.
- **Generate `GET /imports/templates/{type}` from the rule set**, so template and validation
  cannot diverge — this removes the drift described in §1.2 structurally rather than by
  discipline.

### 3.6 Step 6 — Migrate both validators

Port `StudentImportRowValidator` and `HistoricalImportRowValidator` onto the engine and delete
the hand-written branching. Both must keep their current error codes so existing error reports
and any client handling stay valid.

### 3.7 Step 7 — Localized messages

Resolve messages through the existing i18n mechanism per §2.4, keeping codes stable.

### 3.8 Step 8 — Tests

- Unit tests per rule type, including the hostile inputs: a formula-injection name, an
  embedded `U+202E`, a NUL byte, an over-long name, a comma-containing quoted name.
- A regression suite asserting the migrated rule sets produce **identical** codes to today's
  validators for the same fixtures — the safety net for Step 6.
- An integration test covering the full validate → commit path against real MySQL, including
  the in-file and in-database uniqueness rules that need a live schema.

**Verification must force the real conditions**, not just assert the happy path: an import that
should reject must be shown rejecting, with the specific code, against the live server.

---

## 4. Decision index

| ID | Decision | Status |
| :--- | :--- | :--- |
| D-1 | Where rule definitions live (§2.1) | Open — repo-versioned YAML recommended |
| D-2 | Fixed rule catalogue vs expression language (§2.2) | Open — catalogue recommended |
| D-3 | How cross-field, cross-row, and DB-backed rules are modelled (§2.3) | Open — three scopes proposed |
| D-4 | Error report returns code, message, or both (§2.4) | Open — both recommended |
| D-5 | Header-based columns; whether a positional fallback is needed (§2.5) | Open |

---

## 5. Execution

**Run this as a single agent, sequentially.** The steps in §3 form a chain — the parser
feeds the engine, the engine needs the catalogue, the catalogue must exist before either
validator can migrate onto it, and the regression suite only means anything once the
migration is done. Splitting it across agents would create more coordination cost than
the parallelism is worth, and every step lands in the same module
(`cn.timebank.dataexchange`).

This plan is **blocked on `08-SecurityPrivacyHardening.md` §3.1 only**, not on that plan
as a whole. Once the column drop lands, this work runs *in parallel with* Phase 1's
remaining tracks — see that plan's §4.2, where it is scheduled as a Wave 2 track.

Two boundaries with Phase 1, repeated here so this plan does not have to be read
alongside it:

- Phase 1 §3.1 makes a minimal edit to `StudentImportRowValidator`'s positional parsing.
  §3.6 below deletes that code. Do not treat the Phase 1 version as a base to build on.
- Phase 1 §3.5 escapes formula injection on **export**; §3.4 below rejects it on
  **import**. Both are needed and neither is sufficient alone.

The coordinator retains deploys, live browser verification, live database writes and
commits, for the reasons set out in Phase 1 §4.3 — chiefly that there is one test server,
one database, and a single shared browser session.

## 6. Implementation status

Not started. Blocked on `08-SecurityPrivacyHardening.md` §3.1, which changes the student
import's column set.
