# 084 — Student Management's Service Hours column is always empty

Status: **Fixed and live-verified (2026-09-16)**

## Symptom

Reported directly by the user while reviewing the reseeded test server: students
in Admin → Student Management showed a time-coin balance but **no service
hours**, and Annual Progress read `0%` for every row, which also drove every
student's status pill to "Critical".

Rendered DOM, before the fix:

| Name | Annual Progress | Service Hours | Time Coins |
| --- | --- | --- | --- |
| Oakley Z. | 0% | — | 54 |
| Hayden W. | 0% | — | 23 |
| Skyler H. | 0% | — | 9 |

The initial suspicion was bad seed data — coins credited without backing
records. That was ruled out (see below).

## Finding

**The data was correct; one screen could not see it.**

`GET /students` returns `StudentListItemDto`, which carries exactly ten fields:

```
id, studentNumber, displayName, gradeId, gradeLevel,
classId, className, status, graduationYear, version
```

There is no `completedMinutes` and no `annualRequiredMinutes`. `StudentList.tsx`
renders `student.completedMinutes !== undefined ? ... : '—'`, so the column was
**permanently empty for every student regardless of data**, and Annual Progress
divided by a missing required-minutes value to reach `0%`.

The same screen already had this exact problem for balances and solved it
in issue 006 by fetching `/accounts` separately and merging — hours simply never
got the same treatment. With six seeded students and most of them at zero, a
dead column was easy to miss; at 50 students with real balances it was obvious.

The underlying data was verified sound three independent ways:

- Live SQL: no account balance disagrees with the sum of that student's
  approved records, and all 229 ledger rows carry reason `SERVICE_APPROVED`.
- `GET /students/{id}/progress` returns `completedMinutes: 5340` against
  `timeCoinBalance: 8900` — 89 hours, 89 coins, the exact 1:1 the active rule
  implies.
- The student's **own portal** rendered it correctly all along:
  "356% 年度达标进度 · 89 / 25 小时 · 89h 已完成 · 89 时间币 · 🥈 银牌志愿者".

## Fix

Merge progress into the list the same way balances already are.

The obvious alternative — adding the two fields to `StudentListItemDto` — was
**rejected because it creates a module cycle**: `servicerecord` already imports
`cn.timebank.student.application`, so having `student` reach back into
`servicerecord` to sum approved minutes would close the loop. This codebase
guards against that (a `file` ↔ `servicerecord` cycle was caught before landing
during B3).

- New `api.students.progressSummary(id)` returns the raw `ProgressDto` with no
  grade/academic-year backfill. The existing `progress()` makes **three**
  requests per student (progress + student + academic years), which is fine for
  one student and unacceptable for a page of them.
- `StudentList.tsx` fetches it for the current page's students and merges
  `completedMinutes`/`annualRequiredMinutes`, mirroring the balance merge three
  lines above it.
- Concurrency is capped at 6 because a page can hold 100 students and the test
  server is a 1 vCPU box. A row whose request fails is left blank rather than
  failing the whole list.

No backend change, no contract change, no new endpoint.

## Known limitation

This is one request per student, not one per page. That is acceptable for an
admin screen opened occasionally, and it matches the pattern already in the
file. If it becomes a problem, the right follow-up is a bulk progress endpoint
in the `progress` module — which legally depends on both `student` and
`servicerecord`, so it avoids the cycle that blocks the DTO approach.

## Test plan

1. Admin → Student Management shows real hours for students with approved
   records, and `—` only for those genuinely at zero. — done
2. Annual Progress and the status pill reflect real progress rather than 0%
   /Critical for everyone. — done
3. A student with zero approved records shows `0h`, **not** `—`. — done, and
   this is a deliberate change from what was first written here. Before the
   fix, `—` meant "this screen has no idea"; now the value is known to be zero
   and says so. `—` is still shown for the coin column of a student who has no
   account row at all, which is genuinely a different state from a zero
   balance.
4. The student's own portal is unchanged. — done

Verified live: `54h/54 coins`, `23h/23`, `20h/20`, `9h/9` — hours and coins
agree exactly, as the 1 hour = 1.00 coin rule requires. Annual Progress now
reads 72%/31%/80%/12% instead of 0% for everyone, and the status pill spreads
across On Track / Behind / Critical instead of marking every student Critical.

## Trap hit while verifying

The first verification after deploying appeared to show the fix had not worked.
It had: the server was serving the new bundle (`index-DiSPmIF7.js`), but the
browser was still running the previous one (`index-K63-BZpB.js`) from a cached
`index.html`.

nginx sends `ETag`/`Last-Modified` for `index.html` but **no `Cache-Control`**,
so browsers fall back to heuristic caching and can keep serving a stale
document — which pins them to the old hashed bundle indefinitely after a
deploy. Worth fixing as part of `Docs/optimization/08-SecurityPrivacyHardening.md`
§3.2's nginx pass (`Cache-Control: no-cache` for `index.html`, while the
content-hashed assets stay immutable). Filed here rather than separately
because it is the reason this fix looked broken.
