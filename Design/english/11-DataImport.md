# Data Import Design

> This document describes the bulk data import types, workflow, validation and
> permission constraints.
> Keep `Design/chinese/11-DataImport.md` in sync when editing this file — the
> Chinese version is the source of truth.
> Related: the permission and scope model in `10-RolesAndPermissions.md`;
> exports in `12-DataExport.md`; the validation implementation record in
> `Docs/optimization/09-RuleBasedImportValidation.md`.

---

## 1. The question this design answers

Import is the **only place in this system where an outside file writes student
data directly**. It has three properties at once:

1. the data comes from outside the system, and neither its shape nor its content
   can be trusted;
2. one operation affects hundreds or thousands of rows;
3. one of the two import types lands directly on the time-coin ledger.

Together these mean the cost of a bad import is not an error message — it is a
batch of wrong data inside the system that someone has to trace back row by row.
**So the core choice is: validate first, commit second, as two separate steps
with a human decision in between.**

---

## 2. The two import types

| Type key | Name | Writes to | Row limit |
| :--- | :--- | :--- | ---: |
| `students` | Student roster | `student` table + login accounts | 5,000 |
| `serviceRecordsHistorical` | Historical service records | Service records + **the time-coin ledger** | 20,000 |

### 2.1 Student roster (`students`)

Used to establish the roster in bulk at the start of a school year. Each row
produces two things: a roster record, and a **password-less, pending-activation**
login account (`09-StudentActivationFlow.md` §3).

Columns: `studentNumber`, `displayName`, `gradeId`, `classId`,
`enrollmentAcademicYearId`, `graduationYear`, `joinedAt`.

**The username is not a column and cannot be supplied.** It is generated as 8
random digits — a sequential username leaks enrolment order and cohort size,
which the identity design rejected explicitly.

### 2.2 Historical service records (`serviceRecordsHistorical`)

Used at go-live to bring in service hours already recognised by the school, so
that students' cumulative figures do not start from zero.

Columns: `studentNumber`, `serviceDate`, `durationMinutes`, `description`.

**This is the highest-risk import**, because it credits the time-coin ledger
directly. Two constraints:

- Each row converts using **the rule version in effect on that row's service
  date**, not today's rule. If no rule was in effect on that date, the row fails
  with `NO_RULE_IN_EFFECT` and blocks the commit — this is not a check that can
  be configured away, it is a conversion that cannot logically be performed.
- A historical import **creates no review workflow**. What it imports is an
  outcome the school already recognised, not a pending application; putting it in
  the review queue would ask teachers to re-decide what the school settled years
  ago.

---

## 3. The three-stage workflow

```
① Upload (imports.create)
        │  File becomes a job, status = VALIDATING
        │  No business data has been written yet
        ▼
② Validate (automatic)
        │  Rules run row by row, producing an error report
        │  status = VALIDATED or VALIDATION_FAILED
        ▼
③ Human confirmation and commit (imports.commit)
        │  The administrator reads the error report, then confirms explicitly
        │  status = COMMITTING → COMMITTED or COMMIT_FAILED
        ▼
④ Done
```

### 3.1 Why validation and commit must be separate

Because the output of validation **is for a person to read**. An 800-row roster
with 12 duplicate student numbers is not something a machine can decide about —
is the wrong file loaded, or do those 12 genuinely need checking by hand? Fusing
validation and commit into one step makes the system take that decision, while
the person bears the cost of it being wrong.

The state machine is therefore eight explicit states rather than
success/failure: `QUEUED`, `VALIDATING`, `VALIDATED`, `VALIDATION_FAILED`,
`COMMITTING`, `COMMITTED`, `COMMIT_FAILED`, `CANCELLED`. "Validated, awaiting
confirmation" is a real resting state and has to be expressible.

### 3.2 A failed validation cannot be committed

A `VALIDATION_FAILED` job has no commit path. There is no "ignore errors and
import anyway" — once that button exists, it gets pressed on the day everyone is
in a hurry. The correct route is to fix the file and upload again.

---

## 4. Validation rules

Rules live in `backend/src/main/resources/import-rules.yaml`, and **that file is
the validation logic**; the engine only interprets it. Three constraints:

1. **Rules ship with the code, not in a database.** A wrong rule silently
   corrupts the roster, so "changing a rule goes through code review" is a
   feature, not friction.
2. **There is no expression language.** Rule types come from a fixed catalogue.
   An evaluator that can run arbitrary logic is a security surface of its own, in
   the one component whose whole job is handling files someone else supplied.
3. **The import template is generated from the same rules.**
   `GET /imports/templates/{type}` reads the same column definitions, so the
   template and the validation cannot drift apart.

The error report gives a stable error code and bilingual message per row and per
column, rather than only "something is wrong on line 37". The code is the machine
contract and does not change when the wording does.

---

## 5. Permissions and the interface

### 5.1 One role holds import

`imports.create` / `imports.read` / `imports.commit` are held **by SDC Admin
only** (`10-RolesAndPermissions.md` §6).

- **Teachers have no import permission.** Bulk roster writes are a Student
  Development Centre responsibility, not a year-group one.
- **System Admin has no import permission.** Reasoning in
  `10-RolesAndPermissions.md` §6.1.

### 5.2 Import and export are two separate tabs

In the interface, Data Import and Data Export are separate tabs, independent of
one another.

**A user without the permission does not see the tab** — rather than seeing a tab
that errors when opened. A teacher who can only export should not encounter the
concept of "import" at all; something visible but unusable only invites repeated
attempts and the suspicion that the user is doing it wrong.

### 5.3 Import is a dangerous action and the interface must say so

The confirmation screen before commit must state plainly: how many rows will be
created, how many updated, and **whether this import will credit the time-coin
ledger**. The historical import needs its own wording — "import 1,204 records"
and "import 1,204 records and credit 3,610 time coins" are events of different
magnitude.

---

## 6. The 15-day window on historical import

Historical record import is a **migration-era capability**: its purpose is to
bring already-recognised hours in when the system goes live, and once that is
done there is no next time. It is also the only bulk write in the system that
credits the time-coin ledger directly.

An entrance used once, which can alter the ledger, should not stay one click away
after the migration is over.

### 6.1 The rule

- The **first** successful historical import opens a **15-day** window.
- Further imports are allowed inside it; **later imports do not extend the
  window**. The clock is started once, by the first one.
- When the window expires, historical import is **closed permanently**.
- Once closed, **the card disappears from the screen** rather than showing as
  disabled — the same principle as §5.2: a control you cannot use should not be
  visible.
- The only way to reopen it is a **maintenance utility on the server console**,
  run by a system administrator. That utility is not built; its specification is
  `Docs/backlog/01-HistoricalImportReenableUtility.md`.

### 6.2 Two things the interface must show

**Before any import** — the card carries a warning stating that the first import
opens a 15-day window, that the feature closes when it expires, and that only a
system administrator can reopen it on the server. This must appear **before** the
first import, or the administrator starts an irreversible clock without knowing
it exists.

**After an import** — the card shows a **countdown and the expiry date**. "Days
remaining" alone is not enough: the countdown says how long is left, but only the
date can be written into a migration plan.

### 6.3 Why a system administrator reopens something they cannot use

This reads as a contradiction: `10-RolesAndPermissions.md` §6.1 says a system
administrator may not import any data, and here they are the one who reopens
import.

The two do not conflict, because **the switch is not the door**. The utility
reopens the window and grants the operator no `imports.*` permission — after
reopening, the import itself can still only be performed by an SDC Admin, still
through §3's validate-then-confirm flow.

Putting the reopen on a console rather than in the interface is what makes it a
deliberate act requiring server access and leaving a trace, rather than a button
that can be pressed on the day everyone is behind schedule.

---

## 7. Idempotency and duplicate submission

Import jobs use an idempotency key to stop the same request being applied twice.
But idempotency only covers "the same request was re-sent", not "the same file
was uploaded twice" — the latter is two independent, legitimate expressions of
intent, and the system cannot make that judgement for the administrator.

So the real defence against duplicates is **uniqueness rules during
validation**: rows whose student number already exists are flagged at the
validation stage, and the administrator decides whether that is an update or a
mistake.

---

## 8. Properties import must guarantee

| Property | How it holds |
| :--- | :--- |
| **Validation never writes business data** | Validation only reads and produces a report; every business write happens at commit |
| **A commit either fully succeeds or fully rolls back** | Commit runs in a single transaction; "half imported" does not exist |
| **Import produces no password plaintext** | Roster-imported accounts have no password hash and are first entered with an activation code |
| **Conversion always uses the rule in effect on the service date** | The historical import resolves the rule version per row; no rule means the row fails |
| **Every commit writes an audit entry** | Actor, type, row count and job id |

---

## 9. Deliberately not built

- **No incremental or differential import.** There is no "import only the changed
  rows" — knowing which rows changed needs a baseline, the baseline comes from
  the previous import, and once it is out of step it drifts silently.
- **No scheduled automatic import.** An import requires a person who has read the
  error report and confirmed; a scheduled job has no such person.
- **No mixed-type files.** One file, one type.
- **No in-app "reopen historical import" control.** Reasoning in §6.3; such a
  button is equivalent to having no window at all.
- **No importing of usernames, passwords or roles.** Those are decided by the
  identity design, the activation flow and role assignment respectively — they
  are not contents of a roster file.
