# 001 — Teacher grade scope not enforced on student list/detail/directory

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

`teacher001` is documented (`Docs/TestAccounts.md`) as scoped to `grade_10`, but when
logged in, the admin/teacher Student List page shows students from every grade, not
just grade_10. A scoped teacher must not be able to see, fetch, or act on any student
outside their bonded grade(s) at all — not just in the list view, but if they access a
specific student directly (e.g. by ID/URL), that must be blocked too.

## Root cause

Two independent gaps, both required to actually fix this:

1. **The data-scope mechanism is never invoked for students.** `cn.timebank.security`'s
   `DataScopeResolver` / `DataScopeSpecifications` (`backend/src/main/java/cn/timebank/security/application/`)
   is already wired up for `serviceRecords`, `accounts`, `eventProposals`, and `cases`
   (B3/B4), but `cn.timebank.student` has no `ScopeFields` implementation at all.
   `StudentController.list` (`backend/src/main/java/cn/timebank/student/web/StudentController.java:48-57`)
   is gated only by `@PreAuthorize("hasAuthority('students.read')")` — a permission
   `role_teacher` legitimately holds — and `StudentServiceImpl.list()`
   (`backend/src/main/java/cn/timebank/student/application/StudentServiceImpl.java:52-67`)
   builds its query purely from optional client-supplied `gradeId`/`classId` filter
   params, never consulting the authenticated principal's scope. This matches
   `Docs/implementation/00-Status.md:927`, which documents `StudentService.list()` as
   "unscoped, already proven safe" — a judgment scoped to the capped 20-row
   `/students/directory` search, not the unbounded `/students` list.

2. **Even if scoping were wired up, teacher001 has no scope row for the `students`
   resource type.** `teacher_scope_assignment` rows are per-`resourceType`
   (`backend/src/main/java/cn/timebank/security/domain/TeacherScopeAssignment.java`).
   `integration-test/seed.ts:262-266` only seeds teacher001's grade_10 bond for
   `resourceType` in `['serviceRecords', 'eventProposals']` — there is no `students`
   (or `accounts`) row. No admin UI exists yet to create these rows for any resource
   type (`00-Status.md:901`).

This is a real, previously-undocumented gap: no line in `00-Status.md` marks
student-list scoping as done, deferred, or explicitly descoped — it's simply never
picked up after B2.

**The gap is not limited to the list endpoint.** `StudentController`
(`backend/src/main/java/cn/timebank/student/web/StudentController.java`) has no
scope check on any student-specific route:
- `GET /students/{id}` (`:79-86`) — `@PreAuthorize("hasAuthority('students.read') or
  #id == principal.studentId()")`. Any principal with `students.read` (i.e. every
  teacher) can fetch any student by ID directly, regardless of scope.
- `PATCH /students/{id}` (`:97-103`), `POST /students/{id}/activate` (`:106-110`),
  `POST /students/{id}/deactivate` (`:112-116`) — all gated only by
  `students.manage`, no scope check either.

So even once the list view is fixed to only *display* in-scope students (items 1–2
above), a scoped teacher could still reach an out-of-scope student's detail page or
mutate their record by guessing/reusing an ID. All of these must be scope-checked,
not just the list.

## Proposed fix

1. Add a `ScopeFields` implementation for the student resource (mirroring the existing
   `ServiceRecordScopeFields`/`AccountScopeFields` pattern) and call
   `DataScopeResolver`/`DataScopeSpecifications` from `StudentServiceImpl.list()`
   (and `/students/directory` if it should also respect scope) before applying the
   client-supplied filters, using the authenticated principal.
2. Apply the same scope check to `StudentServiceImpl.getDetail()`, `update()`,
   `activate()`, and `deactivate()` — a scoped teacher requesting/mutating an
   out-of-scope student ID should get a 403 or invisible 404 (consistent with the
   existing pattern proven for `serviceRecords`/`accounts`/`eventProposals`/`cases`,
   per `Docs/integration/APIContract.md:839` acceptance scenario 8), not a bare
   `students.manage`/`students.read` permission check.
3. Admin (`grantAll`) continues to bypass scope entirely, same as the existing
   resources — see [002](./002-teacher-admin-grade-bonding-ui-missing.md) and
   [006](./006-mock-seed-data-corrections.md) for how "admin sees all grades" is
   represented today (a hardcoded bypass, not data).
4. Extend seed data (`integration-test/seed.ts`) to also create a `students`
   `teacher_scope_assignment` row for teacher001's `grade_10` bond, so this is
   provable end-to-end without waiting on the admin UI in
   [002](./002-teacher-admin-grade-bonding-ui-missing.md).
5. Once scope is enforced server-side, the Student List page's grade filter dropdown
   (`frontend/src/app/components/admin/StudentList.tsx`) should only offer grades the
   logged-in operator actually has access to — ties into
   [004](./004-grade-class-filter-scalability.md).

## Acceptance criteria

- `teacher001` viewing the Student List (and any other student-scoped view) sees only
  grade_10 students.
- `teacher001` fetching, editing, activating, or deactivating an out-of-scope student
  by ID directly (bypassing the list UI) is rejected (403/404), not just hidden from
  the list.
- `admin001` continues to see and act on all grades.
- A teacher with no scope rows at all sees an empty/self-only result, not everyone.
- Existing scoped resources (`serviceRecords`, `accounts`, `eventProposals`, `cases`)
  are unaffected.

## Test plan

- Backend: unit/slice test on `StudentServiceImpl.list()`/`getDetail()`/`update()`
  asserting a scoped teacher principal only receives/mutates rows within their
  `teacher_scope_assignment` grades, and gets rejected outside them.
- Live: log in as `teacher001` on the test server, confirm Student List only shows
  grade_10; try navigating directly to an out-of-scope student's detail URL and
  confirm it's rejected; log in as `admin001`, confirm all grades are visible and
  editable.

## Resolution

Fixed on the backend, deployed, and verified live against `https://tb.example.com/`:

- Added `StudentScopeFields` (`backend/src/main/java/cn/timebank/student/application/StudentScopeFields.java`),
  wiring `DataScopeResolver`/`DataScopeSpecifications` into `StudentServiceImpl`
  (mirroring `ServiceRecordScopeFields`/`AccountScopeFields`), following the exact
  pattern the module's own `StudentRepository` Javadoc had predicted ("the natural
  first real consumer once that wiring happens").
- `list()`, and a new scope-checked `getDetail(id, Authentication, ...)` overload,
  `update()`, `activate()`, `deactivate()` all now go through
  `findInScopeOrThrow`/`scopeSpecification`, returning an invisible 404 for
  out-of-scope ids rather than a bare permission check.
- Kept the original unscoped `getDetail(id, boolean, actorId, resolvedLanguage)` as a
  **separate overload** for cross-module, system-level name lookups (service
  records, proposals, cases, enrollment already call it independently of the
  caller's student-scope — those call sites have their own, separately-enforced
  scope on their own resource and must not be blocked by student-resource scope).
- `integration-test/seed.ts` now also seeds a `students`-resourceType
  `teacher_scope_assignment` row alongside the existing `serviceRecords`/
  `eventProposals` rows for any future from-scratch seed.
- Test coverage: extended `StudentPrivacyIT` with
  `outOfScopeTeacherGetsAnInvisible404OnAStudentAndTheListIsSilentlyNarrowed`
  (mirrors `ServiceRecordSubmissionIT`'s equivalent proof), run live against a real,
  isolated `timebank_it_*` MySQL schema via `mvn verify` over an SSH tunnel to the
  test server — 7/7 pass. Also re-ran `ServiceRecordSubmissionIT`,
  `ProposalApprovalTransactionIT`, `CaseDecisionTransactionIT`,
  `EnrollmentCapacityIT`, and `DataScopeResolverIT` live to confirm the unscoped
  `getDetail` overload didn't regress any cross-module caller — all pass
  (10/10, 14/14, 5/5, 3/3, 4/4).
- Deployed to the shared test server (`backend/scripts/deploy.sh`) and backfilled
  teacher001's missing `students`-resourceType scope row directly (additive
  `INSERT`, since `integration-test/seed.ts` is first-run-only and the server's
  `teacher001` already existed).
- Live-verified via the real API: `teacher001` sees exactly the 3 grade_10 students
  out of 10 total in the database on `GET /students` (matching
  `Docs/TestAccounts.md`'s description); fetching a non-grade_10 student by id
  directly returns `{"code":"RESOURCE_NOT_FOUND","message":"Student not found."}`;
  the in-scope grade_10 student is still fetchable by id; `admin001` still sees all
  10 students unrestricted.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`.*
