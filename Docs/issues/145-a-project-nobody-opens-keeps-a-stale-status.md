# 145 — A project nobody opens keeps a stale status

**Status:** **Fixed and deployed 2026-09-21.** Follows the audit in
`00-Status.md` §41.38, which found the time-based statuses correct but lazy, and
named the scheduler as the fix. Aquaticism: *"yes, impl a scheduler in java, not
crond."*

## 1. The gap

`ProjectServiceImpl.syncTimeBasedStatus` advances a project through its whole
clock-driven chain — `PUBLISHED → REGISTRATION_OPEN → (CLOSED | ARCHIVED) →
IN_PROGRESS → COMPLETED` — and runs on every path a user can reach, including
the locking read that registration itself goes through. So a stale status was
never displayed and never gated a sign-up.

It was still stale **in the table**, until something read it. That is invisible
to users and visible to the report aggregates, which are raw SQL and bypass the
service: the dashboard's "active projects" counts `status in ('PUBLISHED',
'REGISTRATION_OPEN', 'IN_PROGRESS')` straight from `project`, so a project that
should have archived itself at its registration close was counted as active for
as long as nobody happened to open it. A wrong number with nothing looking
wrong.

## 2. Correcting the reason the audit gave

§41.38 said the lazy design stood because "no cron/ShedLock infrastructure
exists". **That was true when B4 decided it and has not been true since B5.**
ShedLock 6.3.0 is a dependency, `@EnableScheduling` is on
`TimeBankApplication`, `common/scheduling/SchedulingConfig.java` wires the JDBC
lock provider, `V9__reporting_and_scheduling.sql` created the `shedlock` table,
and ten `@Scheduled` jobs already run against it. Adding one more cost a class,
not an infrastructure decision. §41.38 has been corrected.

## 3. Fix

`ProjectStatusSweepJob` — `@Scheduled(cron = "0 */5 * * * *")` with
`@SchedulerLock`, the same shape as every other job in `lifecycle/`.

Three things it deliberately does not do:

- **It does not restate the state machine.** It calls
  `projectService.reconcileTimeBasedStatuses()`, which calls the same private
  `syncTimeBasedStatus` every read calls. A second copy of those rules is how
  they drift.
- **It does not scan the table.** `findOvertakenByClock` selects only the four
  clock-driven edges (`PUBLISHED`/`REGISTRATION_OPEN`/`CLOSED`/`IN_PROGRESS`
  each past its own timestamp). `DRAFT`, `COMPLETED` and `ARCHIVED` have no
  clock edge leaving them, so selecting them would load rows to do nothing to.
- **It does not log a heartbeat.** Only when something moved — a line every five
  minutes saying nothing happened is how a log stops being read.

Five minutes, not one: nothing a person sees depends on this, since the reads
still correct themselves first, so the interval only bounds how stale an
aggregate can be.

## 4. Verification

Forced on the deployed server, with a control. Two projects created at
`07:13:59`; the first published with a live window and then PATCHed so its
registration close sat 10 seconds in the past, the second published with
everything still an hour out. **Then nothing read either of them** — the rows
below were read with SQL, which does not sync, because asking the API would have
been what fixed it.

```
07:14:01  stale    REGISTRATION_OPEN   close 07:13:48  (already elapsed)
07:14:01  control  PUBLISHED           open  08:13:58

          -- 07:15:00 tick, nobody opened either project --

07:15:00  log      "Project status sweep advanced 1 project(s) past a time boundary"
07:15:00  shedlock projectStatusSweep  locked_at 07:15:00.009  lock_until 07:15:10.009
07:15:45  stale    ARCHIVED
07:15:45  control  PUBLISHED
```

The audit trail is the same one the lazy path writes, and attributed to nobody
rather than to the admin who last touched the project:

```
actor_id  action                             created_at
usr_1a43… project.update                     07:14:01
NULL      project.autoArchiveNoRegistrants   07:15:00   NO_REGISTRANTS_AT_REGISTRATION_CLOSE
```

Both probe projects were archived afterwards.

`ProjectLifecycleIT.theSweepAdvancesAProjectNobodyHasReadAndLeavesAFutureOneAlone`
covers it, reading statuses through `ProjectRepository` rather than the service
for the same reason. Red-checked: with the sweep stubbed to find nothing, it
fails alone (`0 to be greater than or equal to 1`) and the other eleven pass.

## 5. What it must not do

- **Must not hold its own copy of the transition rules.** §3.
- **Must not advance a project early.** The control above; the state machine's
  own time guards back it up.
- **Must not assume one instance.** `@SchedulerLock`, as with every other job
  here. The work is idempotent regardless — "advance to what the clock says"
  gives the same answer however often it runs.
