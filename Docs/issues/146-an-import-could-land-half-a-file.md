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

## 5. What it must not do

- **Must not commit a file with a known-invalid row.** §2.
- **Must not apply some rows and report failure**, nor apply none and report
  success. The status and the rows are one transaction.
- **Must not re-apply a row.** No resume, no automatic reclaim of a commit.
- **Must not open the import window for an import that rolled back.**
