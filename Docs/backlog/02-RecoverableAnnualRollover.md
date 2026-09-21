# 02 — Recoverable annual rollover (before-state journal and reversal)

**Status:** Specified, not built. No target release.
**Depends on:** `Design/*/14-AnnualRollover.md` §3.3.1, which states that a
rollover records each changed student's previous grade, class and status so a
completed run can be reversed. Nothing writes that journal today, so the design
currently promises a mechanism that does not exist — which is what this directory
is for.
**Related:** `Docs/issues/110` §4.1 tracks the gap; §1 and §2 of that issue record
what is *already* safe and should not be re-solved here.

## 1. What this is

A journal of what a rollover changed, and an operation that puts it back.

It closes one specific hole. The rollover is a single transaction, so a run that
**fails** changes nothing and is already safe. Nothing protects against a run that
**succeeds against the wrong inputs** — a misconfigured turnover date, a
class-suffix mismatch that skips a cohort, an intake grade that should not have
graduated yet. Such a run commits cleanly; the idempotency guard then refuses the
corrective re-run; and because grade, class and status are overwritten in place,
there is nothing to put back from.

Recovery today is a whole-database restore. On a shared instance that means
discarding every unrelated thing that happened since — service records, reviews,
ledger entries — to undo one column on a few hundred rows. That is not a recovery
procedure anyone will actually run on a school day.

## 2. What a rollover changes

The reversal has to be built against this list, not against an idea of it. As
implemented in `AnnualRolloverRepository`, one run:

| Change | Table and columns | Reversible from a journal? |
| :--- | :--- | :--- |
| Graduates the exit grades | `student.status` → `GRADUATED` | Yes — needs the previous status |
| Promotes each grade | `student.grade_id`, `student.class_id` | Yes — needs both previous values |
| Deactivates graduated logins | `user.status` → `INACTIVE` | Yes — **needs the previous status**, see below |
| Re-points account snapshots | `account.grade_id`, `account.class_id` | Yes, by re-deriving from the student after reversal |
| Marks the target year current | `academic_year.is_current` | Yes — needs the previously current year id |
| Creates next year's classes | `class` rows inserted | Left in place (§5) |
| Records the run | `annual_rollover_run` row | Marked reversed, never deleted |

**The login row is the subtle one.** `deactivateGraduatedLogins` sets `INACTIVE`
for every active login belonging to a graduated student. A reversal that simply
sets them all back to `ACTIVE` would **reactivate accounts that were already
inactive before the rollover ran** — a student who had left, or been suspended,
would silently regain a working login. The journal must therefore record the
previous status per user, not assume it.

## 3. What it must do

### 3.1 The journal

- **Written inside the rollover's own transaction.** If the rollover rolls back,
  the journal must roll back with it; a journal describing changes that never
  happened is worse than none, because a reversal would then apply it.
- **One row per changed entity**, not one blob per run. A blob cannot be queried
  to answer "what happened to this student", which is the question an
  administrator actually arrives with.
- **Keyed by run id**, so a reversal targets exactly one run.
- Minimum content: entity type, entity id, and the previous values of every
  column that run changed. Recording the new values too is optional and useful:
  it lets the reversal detect that something else has changed the row since (§3.2).
- **Retention outlives the correction window.** A wrong rollover is typically
  noticed within days, but a cohort-level mistake can surface at the end of a
  term. Do not tie this to the short-lived-data sweep; a year is a defensible
  floor, and the volume is a few hundred rows per year.

### 3.2 The reversal

- **Refuses when the world has moved on.** This is the hard part and the reason
  the operation must not be a button. If students have filed service records under
  their new grade, or a roster import has already populated the new intake grades,
  a reversal puts the roster back into a state those rows contradict. The
  operation must detect this — compare each row against the journal's recorded
  new values, and refuse if anything differs — and report exactly what blocked it.
  A partial reversal is not an option.
- **Reopens the year.** The `annual_rollover_run` row moves to a terminal
  `REVERSED` status rather than being deleted, and the idempotency check must
  stop treating that year as done so a corrected run can proceed. This is the
  step that makes the whole thing useful; without it the school is back where it
  started but still blocked from rolling over.
- **Re-derives the account snapshots** rather than journalling them separately.
  `account.grade_id`/`class_id` are a copy of the student's, so once students are
  restored the existing `resyncAccountSnapshots` puts accounts right.
- **Writes an audit entry**, naming the actor, the run reversed, and the counts.
- **Is itself one transaction**, for the same reason the rollover is.
- **Console-only, by a system administrator** — the same reasoning as
  `01-HistoricalImportReenableUtility.md` §2. The rollover is deliberately not a
  button (`14-AnnualRollover.md` §8); an undo button beside it would be worse.

### 3.3 Dry run (companion, lower priority)

Running the whole rollover inside a transaction that is deliberately rolled back
produces the real report with no writes. It is cheap because the machinery
already exists, and it turns "what will happen on the turnover date" into a
question that can be answered before the date rather than after.

This is prevention, not recovery, and it is listed here because the two are
usually wanted by the same person on the same day. It does not depend on the
journal and can be built first.

## 4. Open questions for whoever builds it

| # | Question | Notes |
| :--- | :--- | :--- |
| Q-1 | One journal table for all entity types, or one per type? | One table with an entity-type column keeps the reversal a single loop; per-type tables keep the columns honest. The entity count is small either way |
| Q-2 | Does reversal require the run to be the **most recent** one for that year? | Reversing an older run when a newer one exists is almost certainly a mistake. Refusing it is easy and probably right |
| Q-3 | How long after a run may it be reversed? | A hard time limit is simple but arbitrary; the §3.2 "has anything changed" check is the real safety property. A limit may still be worth having so the answer is not "forever" |
| Q-4 | Should the reversal also remove the classes the run created? | §5 says leave them. Confirm against how the Project Center and roster import treat an empty class before deciding otherwise |
| Q-5 | Where does the operator learn a rollover went wrong? | Today: the report, and the `inconsistent_grades` count. Neither is pushed anywhere. Worth considering whether a run with a non-zero inconsistency count should notify somebody |

## 5. What it must not do

- **Not a general-purpose undo.** It reverses one recorded rollover run. It is not
  a time machine for the student table, and must not accept an arbitrary date or
  an arbitrary set of students.
- **Not a frontend feature.** No endpoint, no button, no admin screen. A reversal
  that is reachable from a browser will eventually be pressed by someone who
  wanted a different report.
- **Not partial.** If some rows can be restored and others cannot, it refuses
  entirely and explains why. A half-reversed roster is worse than either end
  state, and is exactly the condition the rollover's own single transaction
  exists to prevent.
- **Not silent about what it declined to do.** Refusing is a normal outcome and
  must print the specific rows that blocked it, not a summary count.
- **Not a substitute for backups.** It addresses one known, bounded mistake. A
  restore remains the answer for anything outside that shape, and this doc should
  not be read as making backups optional.
- **Must not delete the run row.** `annual_rollover_run` is the audit trail of
  what was attempted; a reversal adds to that history rather than erasing it.
