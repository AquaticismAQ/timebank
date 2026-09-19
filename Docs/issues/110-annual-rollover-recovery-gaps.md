# 110 — Annual rollover: failure left no trace, and a wrong run cannot be undone

**Status:** The failure-record defect is **fixed** (§2). Three recovery gaps
remain open (§4), the first of which is the one that matters for a production
RDS deployment.
**Found:** 2026-09-20, from the question "what happens if the backend or RDS goes
offline mid-rollover, and how do we recover?"

## 1. What was already safe

The rollover runs as **one transaction**, so the frequently-feared failure —
a half-promoted school, some students moved and some not — cannot occur. A
crash, a killed process, an RDS failover mid-run: the transaction never commits
and the roster is exactly as it was.

This is asserted rather than assumed: `AnnualRolloverFailureIT` forces a failure
after graduation has run and before promotion completes, then compares every
student's grade and status against a snapshot taken beforehand.

## 2. The defect: a failed rollover left no trace

`V30`'s own comment says keeping a failed attempt visible "is the point of having
the table at all". It was not true.

`runRepository.fail(...)` was called from inside the rollover's transaction, on
the path where that transaction is already doomed. The `FAILED` row was written
and then rolled back with everything else. An operator arriving the morning after
a failed turnover would find an unpromoted school and **nothing at all** saying
why — not a row, not a status, only whatever happened to be in the log.

**Fixed by moving the call outside the transaction.** `runFor` is no longer
`@Transactional`; it opens the work's transaction explicitly through a
`TransactionTemplate` and records the failure after that transaction has rolled
back and released its locks.

Two things this surfaced, both worth keeping in mind:

- **`REQUIRES_NEW` on its own does not fix it.** The obvious fix — annotate
  `fail()` so it escapes the doomed transaction — deadlocks. The rollover holds
  `SELECT ... FOR UPDATE` on the target `academic_year` row for its whole
  duration, and InnoDB takes a lock on a referenced parent row when a child row is
  written. `annual_rollover_run` has a foreign key to that year, so the separate
  transaction blocks until the lock wait times out: a 50-second stall ending in a
  second failure that buries the first. Confirmed by running it.
- **`complete()` must stay inside the transaction**, unlike `start()` and
  `fail()`. A `SUCCEEDED` row that committed while the work rolled back would tell
  the idempotency guard the year was done, and the school would never be promoted
  — silently and permanently. Evidence of an *attempt* must survive a rollback; a
  claim of *success* must not.

## 3. How the guard was verified

Reverting the fix two different ways gave two different answers, which is the
useful part:

| Reverted | Test result | Conclusion |
| :--- | :--- | :--- |
| The `REQUIRES_NEW` annotation on `fail()` | **passes** | the annotation is not what fixes it |
| The structure — failure recorded inside the transaction | **fails**, after a 105s lock timeout | the call site placement is the mechanism |

The annotation is kept as a second line of defence, and its Javadoc now says so
rather than claiming to be the fix. If a future caller ever wraps the rollover in
a transaction, it converts silent loss of the record into a loud timeout.

## 4. Still open

### 4.1 A successful but *wrong* run cannot be undone

**The design now specifies the fix** (`Design/*/13-AnnualRollover.md` §3.3.1): a
rollover records each changed student's previous grade, class and status inside
the same transaction, so a completed run can be reversed as a bounded operation.
The code does not do this yet, which is what keeps this issue open — the gap is
now a gap against a written design rather than an idea nobody wrote down.

This is the significant one. Atomicity protects against a run that fails. Nothing
protects against a run that **succeeds against the wrong inputs** — a misconfigured
turnover date, a class-suffix mismatch that silently skips a cohort, an intake
grade that was not supposed to graduate yet. The idempotency guard then actively
prevents a corrective re-run, and there is no recorded before-state to restore
from: `student.grade_id`, `class_id` and `status` are overwritten in place.

Recovery today is a database restore, which on a shared RDS instance means
rolling back **everything else** that happened since — service records, reviews,
account transactions — to undo a change to one column on a few hundred rows.

The fix is to record the before-state. One row per student changed, or one JSON
document per run, written inside the same transaction, holding the previous
grade, class and status. That makes a reversal a bounded, reviewable operation
instead of a restore.

### 4.2 No dry run

There is no way to see what a rollover *would* do. The report is produced only by
performing it. A `--dry-run` that executes the whole thing and rolls back
deliberately would produce the same report with no writes, and would have caught
the bootstrap hazard (§39) by inspection rather than by reasoning.

### 4.3 The ShedLock window is a guess

`@SchedulerLock(lockAtMostFor = "PT30M")`. If a run is killed without releasing
the lock, no other instance retries for 30 minutes. That is a reasonable default
and it is untested against a real rollover's duration on a full roster — nobody
has measured how long the process takes with a few hundred students, so 30
minutes is a number chosen for comfort rather than from evidence.

## 5. Why 4.1 is not fixed here

It is a schema addition, a write path and a reversal operation — bigger than the
defect this issue was opened for, and it would make the fix in §2 harder to
review. It is also the difference between "we can recover" and "we can restore a
backup", so it should not be bundled in as an afterthought.
