# 006 — Seed/mock data corrections and gaps

Status: **Open**

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
  string name like "Mils". Per the bug report, add at least one student whose class
  is a non-single-letter string (e.g. "Mils") to prove the display and filter logic
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

---
*This document will be updated once the fix has been implemented and verified.*
