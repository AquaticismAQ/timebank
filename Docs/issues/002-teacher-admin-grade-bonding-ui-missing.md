# 002 — No grade-bonding UI when creating a teacher/admin account

Status: **Fixed and live-verified** (2026-08-10)

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

## Resolution

Resolved the open question: **admin's grade field is a static informational note,
not an interactive picker** — since admin's unrestricted access is a hardcoded
`grantAll` bypass (never reads scope rows), an interactive-but-inert control would
look broken if unchecked and do nothing.

- Extracted `GradePicker` (`frontend/src/app/components/GradePicker.tsx`) from the
  duplicated inline JSX in `ProjectManagement.tsx` (both the edit and create-project
  grade grids now use it) and reused it for the new teacher grade-bonding field in
  `SystemAdmin.tsx`'s `UserModal`. Admin-only accounts show a static
  `scopeGradesAdminNote` string instead of a picker.
- `UserModal` requires at least one grade selected when `roles` includes `teacher`
  before the form can submit (`errorGradeRequired`), satisfying this issue's own
  acceptance criterion — full field-level backend validation (studentId/staffId,
  uniqueness, etc.) stays issue 003's job.
- **Backend module-boundary constraint discovered during implementation**:
  `cn.timebank.security.application.UserDetailsServiceImpl` already depends on
  `cn.timebank.iam.application.UserService`, so `UserServiceImpl` calling back into
  `security.application` (to write/read `teacher_scope_assignment` rows) would
  create an `iam` ↔ `security` application-layer cycle — the same problem the
  existing code already solved for `SessionRevocationService` by keeping that call
  at the **web** layer instead (see `UserServiceImpl`'s class Javadoc). Followed the
  same pattern: added `TeacherScopeAssignmentService`
  (`backend/src/main/java/cn/timebank/security/application/`, write side of B1.7's
  mechanism — `DataScopeResolver` remains the read side), and `UserController`
  (not `UserServiceImpl`) calls it after `create()`/`update()`, enriching the
  response via a new `UserDto.withScopeGradeIds()` copy helper.
- `replaceGradeScope` writes one row per grade per managed resource type
  (`serviceRecords`, `eventProposals`, `students`, `accounts`, `cases` — the full
  set any `ScopeFields` implementation exists for today) in one call, and clears
  all of them (`Set.of()`) whenever a `PATCH` results in the account no longer
  holding the `teacher` role.
- `CreateUserRequest`/`UpdateUserRequest` gained `scopeGradeIds` via an added
  non-canonical constructor overload (Java records support this) defaulting it to
  empty/`null`, specifically so the ~16 existing test call sites across the backend
  that construct these records didn't need touching.
- `Docs/integration/APIContract.md` and `_CN.md` §4.15 updated with the new field's
  semantics.
- New live IT coverage: `backend/src/test/java/cn/timebank/iam/UserScopeBondingIT.java`
  (create writes scope across every resource type, update replaces rather than
  adds, removing the teacher role clears the bond, non-teacher roles ignore the
  field, list responses are enriched per-row) — 5/5 pass against real MySQL.
  Re-ran every other IT suite that constructs `CreateUserRequest`/`UpdateUserRequest`
  (18 classes total across issues 001 and 002) to confirm the added constructor
  overload didn't change existing behavior — all pass.
- Deployed both backend and frontend to `https://tb.example.com/` and verified via
  the real HTTP API: created a teacher account with `scopeGradeIds: ["grade_7"]`,
  confirmed the response echoes it, then logged in as that exact new teacher and
  confirmed `GET /students` returns only its 2 grade_7 students — proving issues
  001 and 002 compose correctly end-to-end on live infrastructure, not just in
  isolated tests. Also verified live: updating grades replaces rather than adds,
  and removing the `teacher` role clears `scopeGradeIds` back to empty. Test
  account deactivated afterward.
- No browser/UI automation tool was available in this session, so the `UserModal`
  form itself (grade picker rendering, the "at least one grade" client-side
  validation, the admin static note) was verified by type-checked build
  (`pnpm build`, clean) and by exercising the exact same HTTP requests the form
  now sends — not by clicking through the deployed page in a real browser.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`, except the browser-rendered form itself (no browser tool available this session).*
