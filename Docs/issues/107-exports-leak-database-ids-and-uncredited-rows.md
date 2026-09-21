# 107 — Exports leak database ids, omit student numbers, and ship uncredited rows

**Status:** **Closed, not fixed as an issue.** The export rework is planned work rather than a defect fix, and is now `Docs/optimization/11-DataImportExportOptimization.md`. The code written against this issue was reverted, not committed (§5, §6).
**Raised:** 2026-09-19 by the user, as four points about data export:

> 1. For all data export, the database ids like 'id', 'student id' should be
>    removed. 2. All export data with student name should also provide student
>    number. 3. The service records exported data have some 'null' values in the
>    'creditedUnits' column while 'durationMinutes' is not zero, and only
>    approved records need export, remove the 'status' column.

## 1. Database ids in seven exports

Every export carried at least one opaque key:

| Export | Columns removed |
| :--- | :--- |
| `serviceRecords` | `id`, `studentId` |
| `students` | `id` (and `gradeId`/`classId`, §3) |
| `participation` | `gradeId` |
| `completion` | `gradeId` |
| `reviewEfficiency` | `reviewerId` |
| `topProjects` | `projectId` |

These are `OpaqueIds`-generated strings. The contract already says an id is
opaque and carries no meaning (`APIContract.md` §"IDs are opaque
server-generated strings"), so the recipient of a CSV cannot do anything with
one: it is a join handle into a database they do not have. The columns cost
width in a file a school opens in a spreadsheet.

`id` is still *selected* in both paginated exports, because the keyset walk
needs it as a cursor. It is no longer written.

## 2. A name is not an identifier

`serviceRecords` exported `studentName` alone. Two students sharing a name is
ordinary, and this dataset has several — the roster is deliberately built with
repeated given names. Any use of the file that groups or reconciles by student
was therefore wrong in a way nothing on the row could reveal.

`studentNumber` now sits beside the name, joined from `student`. The `students`
export already had it.

## 3. `gradeId` / `classId` were unreadable, not just redundant

Removing them outright would have cost a roster export the two columns it most
needs. They are joined out to labels instead: `grade_9` becomes the grade's
name, and `class_9_mars` becomes its code (`9-Mars`).

`class.code` rather than `class.name`, deliberately: `name` is the bare suffix
(`Mars`, `A`) which is ambiguous the moment it is read without the grade column
beside it, while `code` is the full label.

## 4. `creditedUnits` — two separate defects behind one symptom

The user saw `null` in `creditedUnits` on rows whose `durationMinutes` was
non-zero. Both halves of their third point turn out to be the same fix, plus one
more problem they were looking straight at.

**The `null` is uncredited rows.** `credited_units` is *"centi-coins, set on
approval only"* (`V5__core_loop.sql`), so a draft or rejected record has NULL,
and `String.valueOf((Integer) null)` renders the literal string `null`. Against
live data:

```
APPROVED      646    0 null
DRAFT           2    2 null
REJECTED        1    1 null
RETURNED        1    1 null
SUBMITTED       3    3 null
UNDER_REVIEW    1    1 null
```

Every NULL was a non-approved row, and every approved row had a value. So
"export approved records only" removes the nulls entirely — the two requests
are one change, and no null-coalescing is needed anywhere.

**`status` goes with them.** Filtered to approved, the column holds one repeated
value.

**And the number itself was misleading.** `credited_units` is stored in
hundredths, so a 60-minute record exported `100`. Beside `durationMinutes` that
reads as an arithmetic error rather than a unit difference — one coin is one
hour in this system, so the two columns should agree on sight. The column is now
`creditedCoins` and renders `1`, `1.5`, `4`. This goes beyond the literal
request; it is included because it is why the column looked wrong in the first
place.

## 5. Status — superseded before verification

**The live check in this issue was never completed, and its result table has been
removed rather than left standing.** An earlier revision of this file carried a
filled-in verification table — header, row count, sample lines — written in
advance of running the check. That was wrong to write: it stated results that had
not been obtained. Recording it here rather than silently deleting it, because
the mistake is the kind that survives only when nobody writes it down.

What is actually true:

- The code changes are written and `mvn test` passes (336). The backend is
  deployed to the test server.
- Two export jobs were created live (`job_7agwzwekehkxts7zbdxe6cf3xp`,
  `job_2mjb5wt4bsbn70dnkfgwvwtysb`) but the polling path used the wrong endpoint,
  so **neither job's outcome was confirmed and neither CSV was downloaded**.

## 6. Superseded by the export design

`Design/*/13-DataExport.md` now specifies this export, and **one decision in it
reverses part of §4 of this issue**: Service Records exports **every submitted
status with a `Status` column**, not approved rows only.

The rest of this issue stands and is carried into that design:

| This issue | In the design |
| :--- | :--- |
| Remove database ids | §2.1 — no database identifiers in any export |
| Student number beside the name | §2.2 — the pairing is a general rule |
| Coins, not centi-coins | §2.5 — coins are a unit convention |
| Approved rows only, no `Status` | **Reversed** — §6.2 keeps all submitted statuses |
| `null` in `creditedUnits` | §2.4 — absent values are *empty cells*; this is now the fix, since keeping every status means the column is legitimately empty on unapproved rows |

The code written against this issue was **reverted and never committed.** What
it did — removing database ids, pairing the student number with the name,
rendering coins instead of centi-coins — survives as design rules, not as a
patch, and lands with the rest of the export rework.

**This issue is closed as not-a-defect.** The observations above were correct,
but treating them as a bug fix was the wrong shape: it produced a change that
contradicted the design being written alongside it. The work now belongs to
`Docs/optimization/11-DataImportExportOptimization.md`, which plans the whole
export and import surface against `Design/*/10`, `11` and `12` rather than
patching one export at a time.
