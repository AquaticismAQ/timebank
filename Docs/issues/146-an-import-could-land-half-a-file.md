# 146 — An import could land half a file

**Status:** **Fixed and deployed 2026-09-21.** From the §12 self-assessment pass
(issue 145's companion commit): item **I-03** was the one row that had to be
marked as diverging from the design. Aquaticism: *"for 'I-03', we need change the code
to avoid partial import."*

## 1. What the design says, and what the code did

`Design/*/08-mlps-level1-security-design-guide.md` §7.1, principle 3:

> **整份文件全部有效才提交**，避免半份数据入库造成难以收拾的中间状态。
> (Commit only when the whole file is valid; no partial loads.)

The code committed the valid rows and skipped the invalid ones. A roster of 200
students with 3 bad rows produced 197 students, a success toast, and an error
list beside it. Nothing told the operator that the file they were holding and
the database no longer matched.

Two separate ways a half file could land:

1. **Invalid rows.** Validation recorded errors, then set the job to `VALIDATED`
   anyway, and commit skipped what it could not apply.
2. **An interrupted commit.** Rows were applied one at a time, each in its own
   transaction, with a checkpoint written every 500 rows so a crashed run could
   resume. A crash left everything before the crash applied — by design, since
   resuming was the recovery plan.

The second one carried a gap the class javadoc already named: the checkpoint is
written at chunk boundaries, so a crash mid-chunk resumed from the previous
boundary and **re-credited** the rows in the chunk that had already been applied.
For the historical import that is real money in the ledger.

## 2. Fix

**One transaction for the whole commit, and nothing skipped inside it.**

- **Validation is now a gate.** Any row error — or a header mismatch — ends the
  job in `VALIDATION_FAILED` instead of `VALIDATED`. Commit only accepts
  `VALIDATED`, so a file with a single bad row can no longer reach it. The error
  report is still written and still served by `GET /imports/{id}/errors`; what
  changes is that it is now the end of the road rather than a footnote beside a
  partial success.
- **`ImportCommitTransaction`** applies every row in one `@Transactional` unit,
  on its own bean so the proxy actually applies (the self-invocation trap this
  codebase has hit twice). A row that fails re-validation at commit time throws
  instead of being skipped: the file was valid twenty minutes ago and is not
  now, which is exactly the case that must not half-apply.
- **The job's terminal status is written inside that transaction**, by a
  conditional update (`… where status = 'COMMITTING'`). "Rows applied" and "job
  says COMMITTED" therefore commit together or not at all. Previously they were
  two transactions with a window between them, and a crash in that window left
  applied rows under a job that still looked unfinished — the state a reclaim
  would re-run and double-apply.
- **Checkpoint-resume is deleted.** It was the recovery mechanism for partial
  application, and there is no longer any partial application to recover from: a
  failed commit rolled back, so re-running from row zero is not just safe, it is
  the only correct thing to do. The mid-chunk double-credit gap goes with it.

### Why the reclaim sweep no longer resubmits a commit

A commit whose lease expires used to be resubmitted automatically. That is now
the *only* remaining way to get two live commits for one file, so it is gone: an
expired commit lease marks the job `COMMIT_FAILED`, which is accurate — the run
is dead and its transaction rolled back, so nothing was applied — and an
operator retries it deliberately. Retrying re-runs the whole file, which is
correct by construction now.

The commit's lease is also raised from 2 minutes to 30, since nothing renews it
mid-run any more. If a commit somehow outlives even that and is marked failed
while still working, its closing conditional update finds a status that is no
longer `COMMITTING` and rolls the whole thing back. That is a wasted run, not a
half-applied one.

### The historical-import window

`openOnFirstCommit` starts the 15-day clock on the first successful historical
import, and the clock cannot be restarted. It was `REQUIRES_NEW`, which under
the new design would commit the window independently of the rows — so a rolled
back import could burn the window it never used. It joins the commit
transaction now.

## 3. What the operator sees

The upload flow used to validate, report the bad rows, and commit the rest
anyway. Now a file with any invalid row stops, with the same first-three-errors
summary it already showed, and nothing is written. The success path is unchanged.

## 4. Verification

**On the deployed server, with a control.** Two roster files through the real
import endpoints — one with a single bad row, one clean — and then the database
read directly, because the question is what landed, not what the response said.

```
bad file (2 rows, row 2's graduationYear = -5)
  validate  -> VALIDATION_FAILED   total 2, valid 1
  errors    -> [(row 2, INVALID_YEAR)]
  commit    -> 409 STATE_CONFLICT "Import must be validated before it can be committed."

control: clean file (1 row), same endpoints
  validate  -> VALIDATED           total 1, valid 1
  commit    -> COMMITTED           1 row

student table afterwards
  T146A…  (the *valid* row of the rejected file)  absent
  T146B…  (the invalid row)                       absent
  T146C…  (the control)                           present
```

The first of those three is the assertion that matters: the rejected file
contained a perfectly good row, and it did not land. Under the old behaviour it
would have.

**The rollback half is proven in the integration test, not live**, because
forcing it means making a row go bad between validate and commit and then
checking that an already-applied row came back out —
`ImportExportIT.aRowThatGoesBadBetweenValidateAndCommitTakesTheWholeFileDown`
validates a clean two-row roster, has somebody else take the second student
number, commits, and asserts the **first** student does not exist. Asserting on
the first row is the point: a test that only checked the bad row was skipped
would have passed against the old code too. Red-checked — restore the
skip-and-continue and it is the only one of the six that fails.

Also: 364 unit tests green (`ImportCommitTransaction`'s three new ones included),
`ImportExportIT` 6/6, `HistoricalImportWindowIT` 4/4, `ExportScopeIT` 10/10.

**Left on the server:** one probe student (`T146C…`, "Probe Clean Row"), since
the control had to actually commit for the comparison to mean anything and there
is no delete for a student. Deactivated afterwards; the rejected import's job row
is cancelled.

### 4.1 Full roster-import pass on the Docker stack

The live check above proved the refusal with a one-row control. Aquaticism asked for
the whole thing exercised properly, against a correct roster and one with real
errors in it, which means the disposable stack: the failure cases create and
destroy students, and the interesting one has to be able to roll a created
student back out.

Rebuilt the image from this branch (`docker compose up -d --build backend`)
against the seeded dev database — 56 students, 42 users to start.

**A. A file with errors in it.** Thirteen rows, ten of them bad, one failure per
documented rule. Every one fired on the row it was aimed at:

| row | field | code |
| :--- | :--- | :--- |
| 3 | studentNumber | `INVALID_FORMAT` (contains I, L, U) |
| 4 | displayName | `REQUIRED` |
| 5 | studentNumber | `DUPLICATE_IN_FILE` |
| 6 | studentNumber | `STUDENT_NUMBER_TAKEN` |
| 7 | gradeId | `UNKNOWN_GRADE` |
| 8 | classId | `CLASS_GRADE_MISMATCH` |
| 9 | enrollmentAcademicYearId | `UNKNOWN_ACADEMIC_YEAR` |
| 10 | graduationYear | `INVALID_YEAR` |
| 11 | joinedAt | `INVALID_DATE` |
| 12 | displayName | `UNSAFE_CONTENT` (`=cmd\|' /C calc'!A0`) |

```
status VALIDATION_FAILED   13 rows, 3 valid
commit -> 409 STATE_CONFLICT
students 56 -> 56     users 42 -> 42
rows 1, 2 and 13 (the valid ones)  absent
```

**B. A correct file.** Six rows across four grades, including two masked CJK
names and one name containing a comma:

```
status VALIDATED 6/6, 0 errors  ->  COMMITTED, 6
students 56 -> 62    users 42 -> 48, every one PENDING_ACTIVATION with no password hash
"Wang, Casey Z."  kept its comma, and landed in grade_7/class_7B — no column shift
李*红             stored as E69D8E 2A E7BAA2, and returned by the API as 李*红
```

(The `mysql` client prints those names as `?*?` unless told
`--default-character-set=utf8mb4`. That is the terminal, not the data — the hex
and the API response are the evidence.)

**C. A file that goes bad between validate and commit.** Three good rows;
another administrator takes row 2's student number through `POST /students`
after validation and before commit:

```
after validate   VALIDATED 3/3
commit           COMMIT_FAILED
                 "Row 2 is no longer valid (STUDENT_NUMBER_TAKEN) … Nothing was
                  imported — correct the file and import it again."
VG146R01 (row 1, applied before row 2 was reached)   absent
VG146R03                                             absent
VG146R02                                             present — the manual one only
```

Row 1 is the assertion. It was created, and the rollback took it back out,
along with its pending account (`user` rows for it: 0) and its audit entry —
`student.create` in the window totals 7, which is the six committed rows plus
the one manual creation, not eight.

**D. Through the actual screen**, since the "report the errors, then import the
rest" behaviour lived in the UI too. Admin Portal → System Admin → Data Import →
Upload Student File with file A:

> 10 invalid rows — nothing was imported. Fix the file and upload it again.
> Row 3: "studentNumber must be Crockford base32 …"; Row 4: displayName is
> required; Row 5: studentNumber VG146M01 appears more than once in this file

and no commit followed — the job went to `CANCELLED`, counts unmoved. A
two-row correct file uploaded through the same control immediately afterwards
committed (`COMMITTED 2/2`, two more students and two more pending accounts),
so the screen is refusing bad files rather than refusing everything.

**One thing worth knowing, not a regression.** A row reports only its *first*
failing rule: a single row with six independent problems produced exactly one
error (`studentNumber INVALID_FORMAT`). That was always true, but it costs more
now — before, a bad row was skipped and the rest of the file went in; now it
stops the file, so an operator fixing a badly-formed row may go around several
times to discover all of its problems. Logged as an observation rather than
fixed here.

## 5. What it must not do

- **Must not commit a file with a known-invalid row.** §2.
- **Must not apply some rows and report failure**, nor apply none and report
  success. The status and the rows are one transaction.
- **Must not re-apply a row.** No resume, no automatic reclaim of a commit.
- **Must not open the import window for an import that rolled back.**
