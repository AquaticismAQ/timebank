# 071 — Cancelling a project registration permanently blocks re-registering

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found while verifying issue 070's previously-uncovered waitlist branch, on
the cancel path that issue 070 itself touched.

A student registers for a project, then cancels. The card returns to an
enabled **"Register Now"** / **"Join Waitlist"** button — but pressing it
fails, every time, forever:

```
POST /api/v1/projects/{id}/applications
409 { "code": "STATE_CONFLICT", "message": "Already registered for this project." }
```

So the student is locked out of a project they are eligible for, with a UI
that keeps inviting them to join. Cancelling by mistake, or changing their
mind, is unrecoverable without an admin touching the database.

## Root cause

`enrollment` has `UNIQUE KEY uk_enrollment_project_student (project_id,
student_id)` — deliberately one row per student per project. Cancelling
doesn't delete that row, it sets `status = CANCELLED` and stamps
`cancelled_at`. But `EnrollmentServiceImpl.register` guards on the row
merely *existing*, with no regard for what state it's in:

```java
if (repository.findByProjectIdAndStudentId(projectId, studentId).isPresent()) {
    throw ApiException.stateConflict("Already registered for this project.");
}
```

The guard is right to exist — it is what stops a double registration from
violating the unique key — it is just too broad: a `CANCELLED` row means
"this student is *not* registered", which is precisely the case the guard
should let through.

The frontend has **the same bug shape in its own guard**, which only
became visible once the backend was fixed: `handleApply` bailed out on any
truthy status at all, so it never even called the API for a cancelled
registration —

```tsx
if (applied.has(proj.id) || proj.myApplicationStatus) { toast.info(t('...alreadyApplied')); return; }
```

— reporting "Already applied" locally. Caught live: with the backend fix
deployed, re-registering still failed, now with a client-side toast and no
network request at all. The button's own label logic was already correct
(`cancelled` isn't in the applied-status list, so it renders as an
available action); it was this one truthiness check that was wrong.

## Fix

Re-register by **reusing** the existing row instead of rejecting it. A
`CANCELLED` enrollment is reset to a fresh registration — status
recomputed from current capacity exactly as a first-time registration is,
and `cancelled_at`/`confirmed_at` cleared or re-stamped to match — which
satisfies the unique key without a delete.

Any other existing status still conflicts, unchanged.

`REJECTED` is deliberately **not** re-registerable: that state is an
administrator's decision about this student and this project, and letting
the student clear it by pressing a button would quietly undo a staff
action. Only the student's own cancellation is theirs to reverse.

On the frontend, the active-status set is now named once
(`ACTIVE_APPLICATION_STATUSES`) and used by both the guard and the
`isApplied` derivation, so the two can't drift apart again.

`appliedAt` is `updatable = false` on the entity and keeps its original
value on a revived row. Left alone rather than making the column mutable:
nothing currently orders a waitlist by it (promotion is a manual admin
`confirm`), so the only effect is an accurate record of when the student
first applied.

The audit entry distinguishes the two paths (`enrollment.register` with a
`REREGISTER` reason when a cancelled row is revived), so the trail shows a
re-registration rather than looking like a duplicate first-time one.

## Test plan

- New IT `aCancelledRegistrationCanBeRegisteredAgain` (cancel, re-register,
  assert `CONFIRMED` and that the row was reused rather than duplicated),
  plus `aRejectedRegistrationCannotBeRegisteredAgain` pinning the
  deliberate asymmetry.
- `EnrollmentCapacityIT` 3 → 5 tests, green on the server against real
  MySQL. The rejected-path test needed fixing once: `reject` only accepts
  `PENDING`/`WAITLISTED`, so the fixture had to fill a capacity-1 project
  first to get the student onto the waitlist before rejecting.
- Live Playwright pass against the deployed server, as the student who
  actually hit the bug: the previously-cancelled registration re-registered
  successfully, coming back as `WAITLISTED` (the single seat was taken) with
  the correct "On Waitlist" label and waitlist toast, and — checked against
  the API — **the same enrollment row** (`enr_2331…`) was revived rather
  than a duplicate being created.

## Resolution

`backend/.../enrollment/application/EnrollmentServiceImpl.java` — `register`
revives a `CANCELLED` enrollment instead of rejecting it.
`backend/.../enrollment/EnrollmentCapacityIT.java` — the two tests above.
`frontend/src/app/components/student/ProjectCenter.tsx` — `handleApply`'s
guard now checks for an *active* status instead of any truthy one, via the
shared `ACTIVE_APPLICATION_STATUSES`.
