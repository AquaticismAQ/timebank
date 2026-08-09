# 003 — User creation form missing required-field validation

Status: **Open**

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

---
*This document will be updated once the fix has been implemented and verified.*
