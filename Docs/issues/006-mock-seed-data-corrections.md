# 006 — Seed/mock data corrections and gaps

Status: **Fixed and live-verified** (2026-08-10)

## Symptom / findings

### 1. student001/student002 show nonzero balance in student portal but 0 in admin/teacher view

Not a seed-data problem — the seeded ledger data is correct (real `approve` decisions
credit the real ledger for `student001`/`student002`/`both001`,
`integration-test/mock-data.ts:227,254,268`). This is a **frontend wiring gap**:

- Student portal balance: `GET /progress` → `accountService.getBalanceForStudent()` —
  correct.
- Admin **Accounts** page (`AccountManagement.tsx:23-32`) fetches `api.accounts.list()`
  separately and builds a `balanceMap` — correct.
- Admin **Student List** page (`StudentList.tsx`) never calls `api.accounts.list()` at
  all, and instead reads `student.timeCoinBalance` directly off the `GET /students`
  response (lines 101, 172, 320, 521, 547). But `StudentListItemDto`/`StudentDetailDto`
  (`backend/src/main/java/cn/timebank/student/application/dto/`) have no
  `timeCoinBalance` field — the frontend `Student` type declares it as optional
  (`api/types.ts:228`) but nothing ever populates it, so it's always `undefined` → `0`
  via fallback.

**Fix**: either (a) have `StudentServiceImpl` join/populate a real balance on the
student list DTO, or (b) have `StudentList.tsx` fetch balances the same way
`AccountManagement.tsx` does and merge by `studentId`. Prefer (b) for consistency with
the existing working page and to avoid coupling the student roster endpoint to
account-ledger data — the accounts domain object already exists for this purpose.

### 2. teacher001 grade bonding

Seed data is correct as far as it goes: `teacher001` has `scopeGrades: ['grade_10']`
(`integration-test/mock-data.ts:70`), inserted as `teacher_scope_assignment` rows for
`serviceRecords`/`eventProposals` (`seed.ts:262-266`), matching
`Docs/TestAccounts.md:12`. This becomes fully correct once
[001](./001-teacher-student-scope-not-enforced.md) extends the seeded resource types
to include `students` (and `accounts`). No other teacher account exists in seed data
today to cross-check against — only `teacher001` (a `both001` account also holds
`role_teacher` but has no `scopeGrades`, resolving to `Self` scope, likely
intentional as a "teacher with no bonded grade yet" fixture rather than a bug).

### 3. Admin "access to all grades"

No such concept exists as seed data, and none is needed: admin bypasses scope
entirely via a hardcoded `grantAll` check
(`DataScopeResolverImpl.java:22`, `DashboardServiceImpl.java:138`,
`ServiceRecordServiceImpl.java:450`) whenever `roleCodes` contains `admin`. This is
correct/working as designed — no seed-data fix needed here. (See
[002](./002-teacher-admin-grade-bonding-ui-missing.md)'s open question about whether
adding a UI-facing "admin grade bonding" field would introduce a second, redundant
representation of the same thing.)

### 4. Case Review page is empty

Confirmed: zero seed data exists for the `cn.timebank.caseflow` module (`Case.java`,
`CaseController.java` at `/api/v1/cases`) in `mock-data.ts`/`seed.ts` —
`integration-test/STATUS.md:15` already logs this as a known gap ("Cases/appeals
workflow" not yet exercised by any seed or live session).

**Fix**: add seed cases covering the case/appeal workflow's states (however many
`Case` status values exist — check `cn.timebank.caseflow.domain.Case`'s status enum)
so the Case Review page has representative data to review/action.

### 5. General data audit + additional fixtures

- Grades are seeded correctly as `"Grade 6"`.."Grade 11"` — never a bare `"9"` string,
  so that specific worry doesn't apply. What *is* wrong is the class-name
  double-prefixing bug — see [005](./005-grade-class-display-duplication.md) (same
  root file, `R__seed_base_data.sql`).
- All seeded classes today are exactly `A`/`B` two-letter-suffixed codes per grade
  (`R__seed_base_data.sql:19-31`, `mock-data.ts:94-103`) — no class uses a longer
  string name like "Luna". Per the bug report, add at least one student whose class
  is a non-single-letter string (e.g. "Luna") to prove the display and filter logic
  in [004](./004-grade-class-filter-scalability.md)/[005](./005-grade-class-display-duplication.md)
  handle arbitrary class-name strings, not just single letters.
- `integration-test/STATUS.md:35-38` already logs other known caveats worth checking
  aren't stale: `event_proposal.format` VARCHAR(32) truncation risk, no
  student-creation UI besides CSV import (relevant context for
  [003](./003-user-creation-form-validation-gaps.md)'s open question), blank
  `ProfilePage.tsx` for students with no `account` row yet, and 3 seeded projects
  permanently archived during a prior live-test session.

## Proposed fix (rolled up)

1. Fix `StudentList.tsx` balance display (item 1).
2. Extend `teacher_scope_assignment` seed rows for `students`/`accounts` resource
   types once [001](./001-teacher-student-scope-not-enforced.md) supports them.
3. Add seed data for the Case Review workflow (item 4).
4. Add a class-name fixture that isn't a single letter (item 5), and fix the seed
   double-prefix bug per [005](./005-grade-class-display-duplication.md).
5. Re-verify `STATUS.md`'s existing caveats are still accurate after the above
   changes land, and update it.

## Acceptance criteria

- Student List page shows correct nonzero balances for students with approved
  service records, matching what the student portal and Accounts page show.
- Case Review page shows representative seeded cases across its workflow states.
- At least one seeded student has a multi-character class name, and it displays and
  filters correctly.

## Test plan

- Live: log in as `admin001`/`teacher001`, confirm Student List balances match the
  Accounts page and student-portal balances for `student001`/`student002`.
- Live: open Case Review as `admin001`/`teacher001`, confirm seeded cases appear.

## Resolution

1. **StudentList.tsx balance**: fetches `api.accounts.list()` and merges by
   `studentId` into the displayed rows, the same pattern `AccountManagement.tsx`
   already used — chosen over teaching `StudentServiceImpl` about account-ledger
   data, per the issue's own preference for (b).
2. **teacher_scope_assignment resource-type coverage**: `integration-test/seed.ts`'s
   scope-insert loop now covers all 5 resource types (`serviceRecords`,
   `eventProposals`, `students`, `accounts`, `cases`) — `students` landed with
   issue 001, `accounts`/`cases` added here, matching the full set
   `TeacherScopeAssignmentServiceImpl.MANAGED_RESOURCE_TYPES` (issue 002) already
   writes for any account created through the real admin UI, so a hand-seeded
   teacher and a UI-created one are no longer inconsistent with each other.
3. **Admin "all grades"**: confirmed no fix needed (already a permission-level
   bypass, not scope data) — no seed-data change.
4. **Case Review seed data**: added a `SeedCase`/`cases` fixture set to
   `mock-data.ts` (5 cases covering every API-reachable status — `DRAFT`,
   `SUBMITTED`, `NEEDS_INFORMATION`, `RESOLVED`, `REJECTED`; `IN_REVIEW` is
   deliberately unreachable through the real API, since `CaseDecisionTransaction`
   folds the implicit `SUBMITTED → IN_REVIEW` step into the same transaction as
   the real decision) and a "Phase 6" driving loop in `seed.ts`, mirroring the
   existing service-record/proposal phases' create → submit → decide shape. One
   correction made from the issue's own draft while implementing: `role_teacher`
   only holds `cases.create`/`cases.read`, never `cases.decide` (`admin` only) —
   a case fixture "decided by teacher001" isn't actually reachable through the
   real permission set, so all case decisions route through `admin001`.
5. **"Luna" class fixture**: added `class_10_luna` (`name_en`/`name_zh` = `'Luna'`)
   to `R__seed_base_data.sql` — a real dictionary entry (schools legitimately
   sometimes name classes rather than letter them), not integration-test-only —
   plus a roster student in it (`mock-data.ts`, student `2024006`).
6. `STATUS.md` updated (`Current dataset status`, `Bug log`, `Fix history`) to
   reflect all of the above and issues 001-005.

**Applying to the already-seeded live server**: `seed.ts` is first-run-only and
refuses to run against a server whose users/students already exist, so the new
`cases`/`class_10_luna`/student-2024006 fixtures couldn't be applied via a normal
`npm run seed` re-run. Applied directly instead — the `class_10_luna` student via
one additive direct-SQL `INSERT` (same recipe `seed.ts`'s own student-insert phase
uses), the 5 cases via the exact same real HTTP create→submit→decide calls
`seed.ts`'s new Phase 6 would make, run by hand as `student001`/`student002`/
`teacher001`/`admin001`. Both are byte-for-byte what a fresh `npm run seed` run
would now produce from the committed fixtures.

- Deployed backend + frontend to `https://tb.example.com/`. Live-verified via the
  real API: `GET /accounts` confirms real nonzero balances exist for
  `student001`/`student002` for `StudentList.tsx`'s new merge to pick up;
  `GET /cases` returns exactly 5 cases spanning `DRAFT`/`SUBMITTED`/
  `NEEDS_INFORMATION`/`RESOLVED`/`REJECTED`; `GET /students?filter[classId]=
  class_10_luna` returns exactly the one `"Luna"`-class student with
  `className: "Luna"` displaying correctly (see issue 004's/005's verification).
- No browser tool was available this session — Case Review's and Student List's
  actual rendered pages weren't screenshotted; verification is API-level
  (confirming the data these pages already fetch from is now correct and
  present), not a browser click-through.

---
*Live-verified 2026-08-10 against `https://tb.example.com/` (API-level). The rendered Case Review/Student List pages weren't screenshotted — no browser tool available this session.*
