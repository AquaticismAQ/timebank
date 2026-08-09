# 003 — User creation form missing required-field validation

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

The create/edit user account form (`UserModal` in
`frontend/src/app/components/admin/SystemAdmin.tsx:213-412`) is missing several
required fields and validation rules:

1. Student ID / staff ID should be required for every role.
2. Role = student: grade (single-select) and class should be required; class should
   be an editable dropdown — pick an existing class for the selected grade, or type
   a new class name that doesn't exist yet (not a plain free-text input); student ID
   required and unique.
3. Role includes teacher: grade bonding required (see
   [002](./002-teacher-admin-grade-bonding-ui-missing.md)), defaulting to none, at
   least one grade must be chosen; staff ID required and unique.
4. Role includes admin: staff ID required and unique; grade bonding required,
   defaulting to all grades.

## Root cause

Confirmed via code inspection — none of this exists today, on either side:

- **Frontend**: `UserModal` has no `studentId`/`staffId` field, no `grade`/`class`
  fields, and no grade-bonding field. Payload sent to the backend is only
  `{ username, initialPassword, displayName, email, roleCodes }`
  (`SystemAdmin.tsx:464-470`).
- **Backend**: `CreateUserRequest`
  (`backend/src/main/java/cn/timebank/iam/application/dto/CreateUserRequest.java:10-16`)
  has no `studentId`/`staffId`/grade fields or validation annotations for them.
  The `User` entity does have an existing `studentId` column
  (`backend/src/main/java/cn/timebank/iam/domain/User.java:57-59`), but
  `UserServiceImpl.create()` never sets it — it's an orphaned column. There is no
  `staffId` column anywhere in the schema. Only `username` uniqueness is enforced
  today (`UserServiceImpl.java:101`, `existsByUsername`).
- `Docs/integration/APIContract.md` §4.15 (`POST /users`, lines 642-659) doesn't
  define these fields either — the contract needs updating alongside the
  implementation (remember to keep `APIContract_CN.md` in sync per CLAUDE.md).
- Note: `cn.timebank.student.domain.Student` already has required, validated
  `gradeId`/`classId`/`studentNumber` fields
  (`backend/src/main/java/cn/timebank/student/application/dto/StudentRequests.java:15-20`)
  — but that's the separate student-roster-import flow (CSV-based), not the
  login-account creation form. This bug is specifically about the `User`
  (login-account) creation form gaining equivalent fields, not reusing the roster
  entity directly. Needs a design decision (see Open questions).

## Proposed fix

1. **Schema/DTO**: add `staffId` (new column) to the `User` entity; start actually
   populating the existing `studentId` column. Add both to `CreateUserRequest`/
   `UpdateUserRequest` with conditional `@NotBlank` validation based on `roleCodes`,
   and add uniqueness checks (`existsByStudentId`/`existsByStaffId`) mirroring the
   existing `existsByUsername` pattern.
2. **Frontend `UserModal`**: add conditional fields —
   - Always: student ID or staff ID depending on role (student → student ID; teacher
     or admin → staff ID; if a user is both student and staff somehow, clarify with
     product — likely disallowed, since student and teacher/admin are probably
     mutually exclusive in practice even though the role picker technically allows
     combining them).
   - Role = student: grade single-select (required) + class editable combobox
     (required) — options populated from the existing classes for the selected
     grade, but also accepts a typed value not yet in the dictionary (new class).
     Reuse `frontend/src/app/components/SearchPicker.tsx`, which already supports
     exactly this pattern (`allowCreateNew`/`createNewLabel` props, Command+Popover
     UI) for other pickers (co-organizer, supervisor, partner organization) —
     single-select mode, `fetchOptions` scoped to the selected grade's classes.
   - Role includes teacher or admin: grade-bonding field per
     [002](./002-teacher-admin-grade-bonding-ui-missing.md).
   - Inline uniqueness validation (check-on-blur or on-submit, surfacing the
     backend's uniqueness error via the existing `ApiError.fieldErrors` mechanism in
     `api/http.ts`).
3. **API contract**: update `Docs/integration/APIContract.md` §4.15 and its `_CN`
   twin with the new request fields and validation rules.

## Open questions (resolve before implementing)

- Should `studentId` on `User` be the *same* identifier as `Student.studentNumber`
  (i.e., account creation for a student should link to/require an existing roster
  `Student` row), or an independent free-text ID? The existing roster-import flow
  already assigns `studentNumber` — creating a second, independently-validated
  "student ID" on `User` risks the two drifting out of sync. Recommend linking
  account creation to an existing `Student` record by `studentNumber` rather than
  free-typing a new ID, but this needs confirmation since it changes the proposed
  scope.

## Acceptance criteria

- Submitting the create-user form without the role-appropriate ID field, grade, or
  class blocks submission with a clear inline error.
- Duplicate student ID / staff ID is rejected by the backend and surfaced in the UI.
- Admin role defaults to all-grades bonding; teacher role defaults to none and
  requires an explicit selection.

## Test plan

- Backend: unit tests on `UserServiceImpl.create()` for each role's required-field
  and uniqueness rules.
- Live: attempt to create a duplicate student ID / staff ID via the admin UI and
  confirm rejection; attempt to submit a teacher account with no grade selected and
  confirm the form blocks it.

## Resolution

**Open question resolved**: creating a student-role account **links to an existing
`Student` roster row** (search-and-pick via `SearchPicker`, same pattern as the
co-organizer/supervisor pickers), rather than free-typing a new grade/class/
student-number — avoids a second, independently-editable copy of data the CSV-
imported roster already owns.

- **Backend**: `V14__user_staff_id.sql` adds `user.staff_id` (new column) plus
  unique constraints on both `staff_id` and the pre-existing (never-enforced-unique)
  `student_id`. `CreateUserRequest`/`UpdateUserRequest` gained `studentId`/`staffId`
  (via the same additive-constructor-overload trick as issue 002, so none of the ~16
  existing test call sites needed touching). `UserServiceImpl.create()`/`update()`
  validate a submitted `studentId` actually exists (reusing `StudentService
  .displayNamesOf`'s existing "absent id means unknown" convention — no new method
  needed) and that neither `studentId` nor `staffId` is already linked to a
  different account.
- **Required-ness lives in `UserController`, not `UserServiceImpl`**: each rule
  ("teacher needs ≥1 grade," "student needs a linked studentId," "teacher/admin
  needs a staffId") is conditional on `roleCodes`, and enforcing it in the service
  would also apply to every existing IT test that calls `UserService` directly as a
  fixture-setup shortcut without supplying every field a real submission would.
  Real traffic always goes through the controller, so checking there is sufficient
  — documented explicitly on `UserController` and `CreateUserRequest` so the
  distinction doesn't get "fixed" into a regression later. `UserController.update()`
  computes each field's *effective* value (request value if provided, else the
  account's current value) before validating, so an unrelated edit (e.g. just
  `displayName`) doesn't spuriously demand re-submitting fields that were already
  set.
- **Frontend**: `UserModal` gained a `staffId` text input (teacher/admin, required)
  and a `SearchPicker`-based student-link field (student role, required) —
  `frontend/src/app/api/client.ts`'s `students.get()` seeds the picker's label when
  editing an already-linked account (one lookup, since the account list response
  only carries the opaque `studentId`).
- `Docs/integration/APIContract.md`/`_CN.md` §4.15 updated with both fields'
  semantics.
- New live IT coverage (`UserScopeBondingIT`, extended): staffId required for
  teacher/admin, duplicate staffId rejected, student role requires a linked
  student, unknown studentId rejected, linking an already-linked student rejected
  — 11/11 pass against real MySQL (up from 5 after issue 002). Re-ran every IT
  suite that constructs `CreateUserRequest`/`UpdateUserRequest` (18 classes across
  issues 001-003) against real MySQL after a clean rebuild — all pass. (Two
  suites — `AuthFlowIT`, `DataScopeResolverIT` — showed spurious failures on an
  earlier non-`clean` run; a `mvn clean verify` rerun confirmed both pass 100%,
  a stale-incremental-compile artifact, not a real regression — see the same
  class of issue noted during issue 001's verification.)
- Deployed both backend and frontend to `https://tb.example.com/` and live-verified
  via the real HTTP API: created a teacher without a `staffId` (rejected, "staff
  ID is required"), created two accounts with the same `staffId` (second rejected,
  409), created a student-role account linked to a real roster row (`studentId`
  echoed back correctly), and attempted to link the same student twice (second
  rejected). No browser tool was available this session, so — as with issue 002 —
  the rendered form itself (picker UI, inline validation) was verified via a clean
  `tsc --noEmit` and by exercising the exact HTTP requests the form now sends, not
  by clicking through the deployed page.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`, except the browser-rendered form itself (no browser tool available this session).*
