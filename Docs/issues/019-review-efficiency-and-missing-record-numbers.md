# 019 — Negative average review time, and missing record numbers on pre-feature records

Status: **Fixed and live-verified (2026-08-11)**

## Symptom

Reported directly by the user, two apparently-related "seed data" issues:

1. Admin Dashboard's "平均审核时长"/"Avg Review Time" stat showed **-0.3 天/days**.
2. Several pending-review service records in Audit Center had no record
   number (`记录编号: —`), while others did.

## Root cause

**#1 is a real backend calculation bug, not a data problem.**
`ReportQueryRepository.reviewEfficiencyByReviewer` computed each decision's
turnaround time as `timestampdiff(second, sr.submitted_at, rd.created_at)`
— but `service_record.submitted_at` only ever holds the record's *current*
submission time, overwritten on every resubmission. Two live-tested records
(`rec_3t67kqy4q6pqggapzmrtm3qhwr`, returned then resubmitted during issue
013/014 verification; `rec_p7s7j89aqphs3jymg6aca02n6d`, same pattern) each
have an earlier `RETURN_FOR_CHANGES` decision whose `created_at` now
predates the record's *later* resubmission time — the query compared that
stale decision against a submission from its own future, producing -73
hours and -0.04 hours respectively. Averaged across all 7 decisions
system-wide, that dragged the mean to -7.6 hours ≈ -0.3 days. Every
underlying timestamp is individually accurate; nothing in the data itself
is wrong.

**#2 is genuinely historical data, not a script bug.** 3 pending-review
records (`rec_2g2823pyzytwbmnbqps60gabya`, `rec_3t67kqy4q6pqggapzmrtm3qhwr`,
`rec_50s0r1280j2j2fszz8x3d5ccv1`) were created 2026-08-07, three days
before `03-ServiceRecordFormOptimization.md`'s `recordNumber` generation
shipped (2026-08-10) — they simply predate the feature. A fresh reseed
today would not reproduce this: every record created through the current
API gets a real number (confirmed — the record created for issue 018 got
`TB-2025-2026-10-2024002-001` automatically).

## Fix

**#1 (code):** Added `and rd.created_at >= sr.submitted_at` to the query's
`WHERE` clause. A decision from an already-superseded review round always
has `created_at` before the record's *current* `submitted_at`; only the
decision(s) actually responding to the current submission satisfy the
inequality. This naturally excludes both stale rows without needing any
historical submission-time trail (which doesn't exist —
`service_record_revision.snapshot_json` doesn't capture `submittedAt`
either, confirmed by inspection).

**#2 (data):** Backfilled the 3 missing record numbers using the app's own
atomic generator mechanism (`record_number_seq`, the same
`INSERT ... ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)` idiom
`RecordNumberGenerator.nextSequence` uses) via direct SQL against the live
`timebank` schema — not hand-picked numbers, so no collision risk with any
past or future real allocation. Ran once per record, in each affected
student's own sequence:

| Record | Student | Assigned number |
| --- | --- | --- |
| `rec_2g2823pyzytwbmnbqps60gabya` | student001 | `TB-2025-2026-10-2024001-006` |
| `rec_3t67kqy4q6pqggapzmrtm3qhwr` | student001 | `TB-2025-2026-10-2024001-007` |
| `rec_50s0r1280j2j2fszz8x3d5ccv1` | student002 | `TB-2025-2026-10-2024002-002` |

Scoped deliberately to *pending-review* (`SUBMITTED`/`UNDER_REVIEW`)
records only, per the report — one `DRAFT` and one already-`APPROVED`
historical record from the same 2026-08-07 seed run still have no record
number and were left alone (out of the reported scope; the `APPROVED` one
is also why one of Case Review's cases still shows no "关联对象" label —
a known, already-documented limit from issue 018, not new).

## Test plan

- `mvn test` clean (no existing coverage of this query — a real gap, not
  added here since it wasn't part of the report; worth a follow-up).
- Live: Admin Dashboard's avg review time and Audit Center's record-number
  display, both checked directly.

## Resolution

- `backend/src/main/java/cn/timebank/reporting/infrastructure/ReportQueryRepository.java`
  updated; backend redeployed.
- Live `timebank` schema: 3 `service_record` rows backfilled with real,
  generator-issued record numbers.

---
*Live-verified 2026-08-11 against `https://tb.example.com/` via Playwright
MCP, logged in as `admin001`: Dashboard now shows "0.2 天" (was "-0.3
天"); the "Example City City Library" record in Audit Center now shows "记录编号:
TB-2025-2026-10-2024001-006" in both the row's subtitle and its Basic Info
tab. The other 2 backfilled records were not separately click-verified
(same code path, same mechanism) — direct DB query after the `UPDATE`
confirms all 3 landed correctly.*
