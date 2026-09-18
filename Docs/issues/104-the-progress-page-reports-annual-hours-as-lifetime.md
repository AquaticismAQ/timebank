# 104 — The Progress page reports annual hours as if they were lifetime

**Status:** Fixed, verified against the local Docker stack (§4). Not yet deployed to the shared test server.
**Found:** 2026-09-19, verifying the reworked seed fixture from a student
account (issue 103 §6.2). Not caused by that work — **exposed** by it.

## 1. Why it could not be seen before

Until issue 103 the whole dataset sat inside one academic year, so every
student's *lifetime* total was identical to their *current-year* total. Every
place that confused the two gave the right answer by coincidence. The moment the
fixture spanned two years the page began contradicting itself on screen.

`student030` — Grade 11, 304.5 hours lifetime, 8.5 of them this year — is the
case that shows it. `GET /students/{id}/progress` answers correctly:

```json
{"annualRequiredMinutes":4500,"stageRequiredMinutes":18000,
 "completedMinutes":510,"underReviewMinutes":0,"gapMinutes":3990,
 "timeCoinBalance":30450,"recognitionLevel":"excellent"}
```

`completedMinutes` is documented on `ProgressDto` as an annual figure, and is.
`recognitionLevel` is documented as lifetime-derived, and is. The page mixes
them.

## 2. Three defects

### 2.1 The honour tier ignores `recognitionLevel` and re-derives from annual hours

```tsx
const currentLevel = RECOGNITION_LEVELS.find((l, i) =>
  completedHours >= l.minHours && completedHours < (RECOGNITION_LEVELS[i + 1]?.minHours ?? Infinity)
) || RECOGNITION_LEVELS[0];
```

`completedHours` is `completedMinutes / 60` — this year's 8.5h. So a student the
server calls `excellent` is displayed as **无等级**, with "16.5h to the next
level" (bronze's 25h minus 8.5h). `progress.recognitionLevel` is never read.

Two things make this more than a slip:

- **The sidebar on the same screen reads 304.5 时间币**, and the **account page
  shows 优秀志愿者 correctly**, so two screens in one portal disagree about the
  same student. `StudentHome` also gets it right, from lifetime coins.
- It is a direct violation of `APIContract.md` §268: *"progress totals are
  derived server-side. The client renders them and never recomputes or overrides
  them."*

### 2.2 The graduation bar divides annual hours by the lifetime threshold

```tsx
const stagePct = progress.completedMinutes / progress.stageRequiredMinutes;
```

Annual numerator, cumulative denominator. The card renders **"已完成 8.5 / 300
小时"** for a student who has passed 300.

This one cannot be fixed in the frontend alone: **`ProgressDto` exposes no
lifetime-completed figure at all.** `ProgressServiceImpl` already loads the
`student_progress` row that holds it and keeps only the honour level:

```java
String recognitionLevel = repository.findById(studentId)
        .map(p -> p.getHonourLevel().toLowerCase()).orElse("none");
```

`total_credited_minutes` on that row is exactly the number the bar needs — a
stored lifetime projection maintained inside the approval transaction
(`V5__core_loop.sql`: *"each contribution already reflects the rule in effect at
its own service date, so this table never re-derives"*).

### 2.3 The per-grade band boxes are hardcoded mock scaffolding

```tsx
{ label: t('student.progress.grade9'),  target: 75, done: true },
{ label: t('student.progress.grade10'), target: 75, done: false, current: true },
{ label: t('student.progress.grade11'), target: 75, done: false },
```

Every value is a literal. A Grade 11 student is shown Grade 9 as complete and
Grade 10 as in progress; a middle-school student is shown high-school grades
with a 75h target instead of their own 25h.

## 3. The fix

### 3.1 `stageCompletedMinutes` joins `ProgressDto`

The field the page needed and never had. `ProgressServiceImpl` keeps the
`student_progress` row it was already fetching and reads
`totalCreditedMinutes` from it alongside the honour level. No new query.

A student with no approvals has no `student_progress` row; that reads as 0,
which is what it means. The honour level lookup is null-guarded at the same
time — the column is nullable and `.getHonourLevel().toLowerCase()` would have
thrown on such a row rather than returning "none".

`StudentProgress` in `frontend/src/app/api/types.ts` gains the same field.
`APIContract.md` §1401 makes that types module authoritative for this shape, so
it is the contract change; there is no field list in the contract doc to mirror.

### 3.2 The page renders what the server sent

`recognitionLevel` drives the tier, and `stageCompletedMinutes` drives the
graduation bar and the distance-to-next-tier figure. The client-side
re-derivation is deleted, not merely corrected — keeping it would leave a second
definition of the tier boundaries free to drift from the rule table the server
resolves against.

### 3.3 The hardcoded band boxes are removed

Removed rather than fixed, because **the data they claim does not exist.** Their
"done" flags are per-academic-year completion, and there is no per-year history
endpoint — `summarizeForProgress` answers for one year at a time and nothing
stores a per-year result. Deriving the student's own band from their grade would
correct the labels while leaving the ticks invented, which is the worse failure:
a plausible-looking wrong answer rather than an obviously absent one.

The graduation card directly above them states the cumulative position
truthfully once §3.1 lands, which is what the boxes were gesturing at. A real
per-year breakdown is a new endpoint and a separate piece of work.

## 4. Verification

### 4.1 The guards, confirmed red first

`ProgressPeriodsTest` — five tests, built around a fixture that gives the
student **8.5 annual hours against 304.5 lifetime**, so a figure read from the
wrong period cannot coincidentally match the expected one. That is the whole
point: the defect survived for months precisely because those two numbers were
always equal.

Reverting the service to its previous behaviour turns them red, which is what
makes them worth keeping:

```
Tests run: 5, Failures: 2, Errors: 1
  theStageFigureIsLifetimeNotThisYear ....................... FAILURE
  aStudentWithNoApprovalsReadsZeroRatherThanFailing ......... FAILURE
  anUnsetHonourLevelReadsNoneRatherThanThrowing ............. ERROR
      NullPointer: Cannot invoke "String.toLowerCase()" because the return
      value of "StudentProgress.getHonourLevel()" is null
```

The third is the null guard from §3.1 earning its place — not a hypothetical,
an actual 500 on a page whose only job is to report a number.

Restored: **`mvn test` — 332 tests, 0 failures.** `pnpm build` clean.

### 4.2 In the browser, at both ends of the data

Backend rebuilt into the local Docker stack (issue 101); the seeded data was
left in place so these are the same two students as issue 103 §6.

`GET /students/{id}/progress` now carries both periods:

```json
{"annualRequiredMinutes":4500,"stageRequiredMinutes":18000,
 "completedMinutes":510,"stageCompletedMinutes":18270,
 "timeCoinBalance":30450,"recognitionLevel":"excellent"}
```

| | `student030` (Grade 11, 304.5h lifetime) | `student001` (Grade 6, no service) |
| :--- | :--- | :--- |
| 年度要求 | 8.5 / **75** h, 还需 66.5 | 0 / **25** h, 还需 25 |
| 毕业累计要求 | **304.5 / 300** h (was 8.5 / 300) | **0 / 75** h |
| 荣誉等级 | **🏆 优秀志愿者** (was 无等级) | — 无等级, 距下一等级 25h |
| 等级列表 "当前" | on 优秀志愿者 | on 无等级 |
| 距下一等级 | correctly absent — top tier reached | present |

The Grade 6 row is the one that shows §2.3 was a real defect rather than a
cosmetic one: a middle-school student now sees their own **25h / 75h** bands,
where the hardcoded boxes asserted 75h and marked Grade 9 complete.

The sidebar (8.5h 已完成 · 304.5 时间币) now agrees with the panels beside it,
and with the account page, which was right all along.

### 4.3 A fourth instance, caught only by a mid-tier student

The first pass fixed the tier's *selection* but left the
distance-to-next-tier figure still subtracting the annual total:

```tsx
{minutesToHours(nextLevel.minHours * 60 - progress.completedMinutes)}h
```

Neither test account could show it. `student030` is at the top tier, so
`nextLevel` is undefined and nothing renders; `student001` has 0 in both
periods, so `25 - 0` is right either way. It took `student022` — 44.5h lifetime,
2.5h this year, sitting between tiers — to expose it: the page said **72.5h to
the next level** (75 minus this year's 2.5) when the answer is **30.5h** (75
minus 44.5).

Fixed and re-verified live: 距下一等级 now reads 30.5h, with the annual gap still
correctly reading 72.5h beside it — the two figures are distinct and both right,
which is the whole point of the issue.

**The lesson is about the test accounts, not the code.** Two accounts at
opposite extremes both passed while a defect sat between them. A middle case was
needed, and is now named in `Docs/TestAccounts.md` so the next person picks one.
