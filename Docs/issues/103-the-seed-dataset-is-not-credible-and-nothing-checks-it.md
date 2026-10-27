# 103 — The seed dataset is not credible, and nothing checks that it is

**Status:** Fixed, verified against the local Docker stack (§5). The shared test server is not re-seeded yet (§6).
**Raised:** 2026-09-19 by the user, after two evenings of one-defect-at-a-time
fixes to the seed data:

> We already spent two nights fighting with the mock seeding data… you need to
> have the full picture of the whole project, key workflows and other things. Do
> a systematic research and review of the seeding scripts to ensure the data is
> reasonable, self-consistent and ready to demonstrate for schools.

## 1. Why it took two evenings — the real root cause

Not the difficulty of the data. **There is no invariant suite.** Every rule the
fixture has to satisfy is written in prose in `MOCK_DATA_GUIDE.md` and enforced
by nothing, so a violation is only discoverable by opening the one screen that
happens to expose it. That turns a bounded problem into an unbounded sequence of
them: each round found exactly one more broken rule, and there was never any way
to know how many remained.

Issues 083, 100 and the 2026-09-18 Reports fix are all the same shape — a real
rule (a status precondition, an academic-year bound, a report's scoping key)
that the fixture silently violated and no check asserted.

**This issue's primary deliverable is therefore the checker, not the data.** The
data changes are what the checker demands once it exists.

## 2. What the review found

Measured against the fixture as committed at `a29f541`, on 2026-09-19.

### 2.1 Everything is crammed into a 17-day window (severity: demo-fatal)

`R__seed_base_data.sql` derives the academic year from `CURDATE()` (issue 100),
so on 19 September the current year is **17 days old**. The 2026-09-18 fix moved
every record inside it. The data became self-consistent and stopped being
believable:

| | |
| :--- | :--- |
| `student028` | **99h across 17 days** — 5.8h/day, active on every single one, 38 records |
| `student030` | 78h across 17 days |
| Student-days of service | **230**, inside 17 calendar days, for 28 students |
| Weekday distribution | Thu 55, Wed 50 … Sat 30, Sun 33 — **inverted**; volunteering is weekend-weighted |

A school reads "99 hours of volunteering in 17 days, mostly on school days" and
stops trusting the numbers. The previous fix bought report correctness with
plausibility.

### 2.2 `enrollment_academic_year_id` is wrong for every student

`seed.ts` writes the **current** year for all 50, while `joined_at` is generated
at 300–1169 days ago. The same row contradicts itself, and every Grade 11
student is recorded as having entered the school this September.

It also makes the prior year permanently unreportable:
`ReportQueryRepository.participationByGrade` joins
`academic_year ay on ay.id = s.enrollment_academic_year_id and ay.start_date <= :end`.
For the prior year `:end` is 2026-08-31 and the current year starts 2026-09-01,
so **no student qualifies** and the prior-year population is empty by
construction — the exact "looks like a working filter, answers nothing" failure
issue 093 §1 set out to remove.

### 2.3 The recognition system cannot be demonstrated at all

```
honour tiers reached: none 20, bronze 8, silver 2, gold 0, excellent 0
students meeting the cumulative stage requirement: 0 of 30
```

`targetCoins` caps at 100h, so **Gold (150h) and Excellent (300h) are
unreachable**, and no student meets the cumulative stage requirement (75h middle
/ 300h high). Annual completion is thin too — grade_10 has 0 of 5 students over
its 75h bar. Honour tiers and the annual-vs-cumulative distinction are headline
features of the Phase 1 institutional design (`Design/*/01-TimeBankDesign.md`
§4.2/§6/§8.1); the dataset demonstrates neither.

### 2.4 The 2026-09-18 clamp squashed three projects together

`clampProjectToAcademicYear` pulled every completed project into the same
16-day slot, so School Library Organisation, Community Reading Companions and
Neighbourhood Digital Help Desk all start and end on the identical two days.

### 2.5 Two stale literals of the kind issue 100 fixed elsewhere

- `mock-data.ts`'s `graduationYearFor` hardcodes `2026 + (11 - level) + 1`.
- `R__seed_base_data.sql`'s `rule_version.effective_from` hardcodes
  `2025-09-01` while its own comment claims "effective from the current academic
  year's start". Harmless today only because a past `effective_from` still
  resolves.

## 3. The fix

### 3.1 Seed two academic years (the user's decision)

Offered as a choice, since it reverses issue 093's deliberate "the prior year is
left empty". Chosen: **populate both.** The prior year carries the history; the
current 17 days carry recent activity. This is what a school actually looks like
in mid-September, and it is the only arrangement in which the cumulative stage
requirement and the Gold/Excellent tiers are reachable at all.

Issue 093's testability concern is met differently and better: the year filter
is now exercised by **two populated years with different figures** rather than
by data-versus-nothing, and the older enrolment-cohort years (§3.2) are
genuinely empty, so "a year that returns nothing" is still directly testable.

### 3.2 Enrolment cohorts become real

Students enter at Grade 6 and stay six years, so an honest
`enrollment_academic_year_id` needs six cohort years, not two. `seed.ts` inserts
the four older ones (`is_current = 0`, no data of their own); a student's
enrolment year is derived from their grade, and `joined_at` is derived from the
same figure instead of a free-running random offset.

The consequence is a feature, not a cost: **Grade 6 students correctly have no
prior-year history**, because they had not joined yet — which is precisely the
population rule issue 093 §1 describes, finally visible in real data.

### 3.3 The checker

`integration-test/verify-fixture.ts`, run by `npm run verify-fixture` **and from
`seed.ts` before it touches a server**, so a fixture that violates an invariant
cannot reach a database at all. Three families:

- **Backend rules** — things `POST`/`submit` would reject: the 30-minute rule,
  no future dates, no overlapping records for one student, evidence and reviewer
  scope, project status-versus-date-window, a closed registration window with
  zero confirmed registrants (which silently auto-archives).
- **Report correctness** — every record inside a real academic year, no record
  predating its student's enrolment, project-linked records inside their own
  project's window, and both populated years actually containing enough to make
  each report non-empty.
- **Plausibility** — at most one session per student per day, a cap on hours per
  day, weekend weighting, and the coverage the demo needs (both stage
  requirements met by somebody, all four honour tiers populated, empty-state
  students preserved).

What it caught on its first run is §4; the fixture's final figures are §5.

## 4. What the checker found the first time it ran

Worth recording, because every one of these had been in the fixture for some
time and none of them had a symptom anyone had noticed:

- **Three hand-pinned `PROJECT` records named students who were not registrants
  of the project.** `seed.ts` had been papering over this by *mutating* the
  imported `projects` array at run time, which is why it never failed — and also
  why the fixture as written was never self-consistent and no offline check
  could have seen the difference. Reconciliation moved into `mock-data.ts`
  (`withPinnedRegistrants`) and the run-time mutation is gone.
- **Two of those three students were in a grade their project does not admit** —
  a Grade 10 student on a Grade 7-9 project, and a Grade 7 student on a Grade
  9-11 one. The enrolments were inserted by raw SQL, which bypasses the
  validation that would have refused them. Fixed by swapping the two authors.
- **A Grade 6 student held a project record dated before their cohort existed**,
  because `assignRegistrants` filtered on grade eligibility but not on whether
  the student had joined yet.
- **The no-service slot landed on students 001-006** — precisely the students
  the narrative records and all five cases attach to, so the students meant to
  demonstrate the empty state were the ones carrying the richest history.
  `gradeSlot` now rotates.
- **`targetCoins` double-counted.** It was treated as "hours to generate" while
  three students already held approved records from the pinned fixtures, so
  those three quietly exceeded the slot whose threshold was the whole point of
  the number.

## 5. Verification

Per the user's instruction, against the **local Docker stack** (issue 101)
rather than the shared server:

> We already added Docker support; for quick verification you can use Docker
> first before re-seeding the test server.

`docker compose down -v` then `up --build`, so Flyway rebuilt the schema from
scratch and the derived `effective_from` was exercised on a genuinely fresh
database. It resolved to `2025-09-01` — the same value the literal held, which
is what confirms the derivation is correct rather than merely different.

Seed run: **exit 0, all phases**, and Phase 7's independent balance check
passed for all 30 students. Final shape: 630 records (622 approved), 1,920h,
12 projects, 50 students across 6 enrolment cohorts.

### The reports, run as their own SQL

Not read off a screen — the queries from `ReportQueryRepository` were run
directly, because a screen showing a number is exactly what hid the earlier
defects.

| Check | Result |
| :--- | :--- |
| Monthly trend | **13 months**, 2025-09 to 2026-09, 121-188h each — a curve, not a point |
| `topProjects`, current year | Autumn Term Orientation Helpers 5 人/11h, Neighbourhood Recycling Drive 5 人/7.5h, Rural Teaching 2 人/2.5h |
| `topProjects`, prior year | School Library 5 人/14h, Community Reading 5 人/4.5h, Neighbourhood Digital 3 人/4h — **no 0h rows** |
| `participationByGrade`, prior year | grades 7-11 present, **grade 6 correctly absent** — that cohort had not joined |
| `participationByGrade`, current year | all six grades |
| Enrolment cohorts | grade_11 → `ay_2021_2022` … grade_6 → `ay_2026_2027` |
| `approvedThisMonth` | **67**, not 622 — the backdating in Phase 6.5 |
| Review efficiency | 34-38h average turnaround per reviewer, not minutes |
| Timestamp ordering | 0 rows with submit-before-create, update-before-create, or a future date |

### In the browser

Driven against the container through the Vite dev server as `admin001`.

- Prior year, Service Hours Trend: x-axis carries all twelve months,
  peak 2026-08 · 188h, trough 2026-06 · 121h.
- Project Distribution: three rows, every one with hours.
- Participation: 22 bars across all six grades.

**The trend line and the pie looked blank in full-page screenshots again, and
again they were not.** DOM inspection found one curve with a 427-character path
and 12 dots; recharts animates the stroke and the capture lands mid-animation.
This is the second time that has nearly been reported as a defect — check the
DOM, not the pixels.

## 6. The student portal, and a defect this change introduced

Verified as three students chosen to sit at opposite ends of the data:
`student030` (Grade 11, 304.5h, Excellent, 98 records), `student001` (Grade 6,
one rejected record, a resolved appeal, **no `account` row at all**) and the
roster's empty-state students.

`student001` re-confirms issue 099: a student with no account row opens 时间币账户
and 我的 without error, showing a 0 balance and 暂无流水记录 rather than failing.
Everything else on both accounts was correct — case number
`TB-2026-2027-6-HKTZ4HKV-001` encodes the right year, grade and student number;
98 records paginate 20 to a page in date order.

### 6.1 The ledger's running balance was scrambled — my fault, now fixed

The account page showed a 余额 column that contradicted its own dates: `+4`
landing on 76.5 in one row and 196 in the next.

**Cause: the Phase 6.5 backdating reorders the ledger.** The backend stamps
`account_transaction.balance_after` as it credits, in approval order; moving
`created_at` to match each record's service date changes the order the page
reads them in, and the stamped balances no longer form a running total. It was
invisible before this issue because with every record created today inside one
year, insertion order and date order were the same thing.

Fixed in three parts, each needed:

1. **`mock-data.ts` exports records in chronological order.** The generator
   emitted a student's prior-year sessions before their current-year ones, with
   pinned and project records appended after both. A new REPORTS invariant
   asserts it — **confirmed red by reverting the sort: 294 failures.**
2. **Phase 6.5 restates `balance_after`** as a window function over
   `(created_at, id)`. Sorting alone was not enough: a review turnaround that
   varies by a day or two means a record served later can be decided earlier, and
   56 rows were still wrong after the fixture itself was ordered.
3. **Decision timestamps vary by minute, not just by day.** Pinned at a fixed
   `09:40`, two decisions made on the same day got *identical* timestamps, the
   newest-first sort broke the tie arbitrarily, and the top two rows of the
   ledger appeared in the wrong order.

Checked afterwards in SQL, independently of the restatement: **0 accounts whose
newest ledger row disagrees with `account.balance`** (that column is never
touched by the restatement), 0 tied timestamps, 0 rows breaking the running sum,
and Phase 7's API-driven balance check green for all 30 students. Then read back
in the browser — 12 consecutive rows where each balance equals the row below it
plus its own delta, **0 arithmetic breaks**.

### 6.2 Two pre-existing Progress-page defects, found not fixed

Not caused by this work, but exposed by it: until now every student's lifetime
total *equalled* their current-year total, because all records sat in one year.
Now they differ, and the page contradicts itself.

For `student030` the API returns, correctly:

```json
{"annualRequiredMinutes":4500,"stageRequiredMinutes":18000,
 "completedMinutes":510,"gapMinutes":3990,
 "timeCoinBalance":30450,"recognitionLevel":"excellent"}
```

- **The honour tier ignores `recognitionLevel`.** `ProgressPage.tsx` re-derives
  it client-side from `completedMinutes`, which is **annual**-scoped — so a
  student the API calls `excellent` is shown as 无等级 with "16.5h to the next
  tier" (25h bronze minus 8.5h annual). The sidebar beside it reads 304.5 时间币,
  and the **account page gets it right** (优秀志愿者), so two screens in the same
  portal disagree.
- **The graduation bar divides annual hours by the lifetime threshold.**
  `stagePct = completedMinutes / stageRequiredMinutes` mixes two periods,
  rendering "已完成 8.5 / 300 小时" for a student who has in fact passed 300.
  This one is a **contract gap, not just a frontend slip**: `ProgressDto` exposes
  no lifetime-completed figure at all, though `ProgressServiceImpl` already reads
  the `student_progress` row that holds it and uses only its honour level.

Left for a separate issue and the user's call, since the fix crosses the API
contract rather than being a fixture change.

## 7. Still open

- **The shared test server has not been re-seeded.** The user asked to verify on
  Docker first; re-seeding it is a separate, destructive
  action needing its own go-ahead.
- **Reviewer load is lopsided** — 339 decisions for the grade 10/11 teacher
  against 9 for the Grade 6 one. It is a correct consequence of the scope map
  and of Grade 6 having just arrived, so it is left alone, but it makes the
  review-efficiency report look imbalanced.
