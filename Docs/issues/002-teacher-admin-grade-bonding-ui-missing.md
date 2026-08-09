# 002 — No grade-bonding UI when creating a teacher/admin account

Status: **Open**

## Symptom

Creating a new teacher (or admin) account has no way to pick which grade(s) they are
responsible for — the concept doesn't exist in the account-creation form at all.

## Root cause

- The account-creation form (`UserModal` in
  `frontend/src/app/components/admin/SystemAdmin.tsx:213-412`) only has `name`,
  `email`, `roles` (multi-select `admin`/`teacher`/`student`), `status`, and password
  fields. There is no grade-bonding field anywhere in the UI or in the create/update
  payload sent to `api.users.create`/`update` (`SystemAdmin.tsx:461-514`).
- Backend: `CreateUserRequest`/`UpdateUserRequest`
  (`backend/src/main/java/cn/timebank/iam/application/dto/`) have no grade-related
  fields, and there is no endpoint or service call that writes
  `teacher_scope_assignment` rows during user creation. The scope-assignment table
  (`cn.timebank.security.domain.TeacherScopeAssignment`) only gets populated today via
  direct seed-data inserts (`integration-test/seed.ts`) — no admin-facing write path
  exists (`Docs/implementation/00-Status.md:901`: "no admin UI exists to create these
  yet").
- The bug report assumes a reusable "grade picker" component already exists in the
  project management form with distinct mobile/desktop layouts — this is **not
  accurate**. The grade multi-select in `ProjectManagement.tsx` is inline JSX,
  duplicated verbatim in two places (edit modal `ProjectManagement.tsx:250-264`, new
  project modal `ProjectManagement.tsx:740-755`), both using the same single
  responsive Tailwind grid (`grid grid-cols-3 sm:grid-cols-6 gap-2`) — there is no
  extracted component, and no mobile-vs-desktop branching logic, just a responsive
  column count via Tailwind breakpoints.

## Proposed fix

1. Extract a reusable `GradePicker` component (multi-select of grades, using the
   existing dictionary data) from the duplicated `ProjectManagement.tsx` inline JSX,
   and use it in both `ProjectManagement.tsx` call sites (eliminating the
   duplication) and the new teacher/admin grade-bonding field in `SystemAdmin.tsx`.
   Keep the existing responsive grid behavior — no new mobile/desktop split is needed
   since one didn't previously exist.
2. Add a grade-bonding field to `UserModal` (`SystemAdmin.tsx`), shown when `roles`
   includes `teacher` or `admin`:
   - Teacher: multi-select, defaults to **none selected**, must choose at least one
     grade before submit (see validation rules in
     [003](./003-user-creation-form-validation-gaps.md)).
   - Admin: multi-select, defaults to **all grades selected**. Confirm with product
     whether admin grade-bonding is actually stored/enforced, or whether admin's
     "sees everything" behavior stays a hardcoded `grantAll` bypass (see
     [006](./006-mock-seed-data-corrections.md)) and this field is purely
     informational/future-proofing. Recommend keeping admin's bypass as-is
     server-side and treating the admin grade selection as informational only, to
     avoid introducing a second, redundant "all grades" representation.
3. Backend: add grade-bonding fields to `CreateUserRequest`/`UpdateUserRequest`, and
   have `UserServiceImpl.create()`/`update()` write the corresponding
   `teacher_scope_assignment` rows (for `resourceType` in
   `serviceRecords`/`eventProposals`/`students`/`accounts`/`cases` — whichever set
   [001](./001-teacher-student-scope-not-enforced.md) ends up wiring student scope
   into) inside the same transaction as user creation.

## Acceptance criteria

- Creating a teacher account requires selecting at least one grade before the form
  can be submitted.
- Creating/editing an admin account shows grade selection pre-checked to all grades.
- The grade picker UI is a single shared component used by both Project Management
  and the account-creation form.
- Newly created teachers show up correctly scoped in
  [001](./001-teacher-student-scope-not-enforced.md)'s enforcement without a manual
  seed-data step.

## Test plan

- Live: create a new teacher account scoped to grade_7 only; confirm they can see
  grade_7 students/records and nothing else.
- Live: verify the Project Management grade picker still behaves identically after
  extraction (both edit and create flows, mobile and desktop viewport widths).

---
*This document will be updated once the fix has been implemented and verified.*
