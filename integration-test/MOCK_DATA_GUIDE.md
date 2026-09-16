# Updating `mock-data.ts`

## Why this file looks the way it does

`mock-data.ts`'s exported arrays (`organizations`, `users`, `students`, `projects`, `serviceRecords`, `eventProposals`) are shaped close to the real backend's `Create*Request` DTOs — real field names, real id formats — not the frontend's view-model shapes in `frontend/src/app/mock/data.ts` (which has its own `User.password`, denormalized display names, `myApplicationStatus`, etc., and is maintained independently for the app's own offline mock mode). The two files will drift from each other over time; that's an accepted tradeoff, not a bug — see the plan discussion that produced this directory if you want the reasoning.

Every date field is an **offset in days relative to whenever `seed.ts` runs**, never an absolute date. `ProjectStatusTarget`'s `registrationOpen`/`inProgress`/`completed` are computed by the real backend from "is this timestamp before/after now" (`ProjectServiceImpl`'s lazy time-based status sync) — a hardcoded absolute date would be correct on the day it was written and silently wrong every day after.

## Adding a new organization

Add to `organizations`. `type` must be one of `SCHOOL`/`NONPROFIT`/`COMMUNITY`/`GOVERNMENT`/`OTHER` (the real `Organization.Type` enum, `cn.timebank.organization.domain.Organization`) — nothing else validates it, so a typo silently inserts an organization with a type the frontend's `mapEnumFields` won't recognize.

## Adding a new user (login account)

Add to `users`. `roleCodes` must be role **codes**, not ids — `student`/`teacher`/`admin`/`sysadmin`, or a school-defined custom role (see `R__seed_permissions_and_roles.sql`'s `role` table; that file is also the authoritative source for what each role can actually do — cross-check there, not against `frontend/src/app/mock/data.ts`'s permission arrays, if you're deciding what a new persona should be able to see). **Exactly one code, always** — every user holds exactly one role (`07-RoleFinetune.md` §2.5; the dual-workspace/multi-role account pattern this array's type still technically permits was retired 2026-08-19, see the `both001` entry's comment above). Don't add a second one no matter what persona you're building.

For a plain student login, set `linkedStudentNumber` to a `studentNumber` that also exists in the `students` array. `seed.ts` resolves this to a real `student.id` and sets `user.student_id` directly via SQL after both rows exist — **there is no API surface for this link** (confirmed against `CreateUserRequest`, which has no free-typed grade/class/student-number field, by design — issue 003), so don't try to route it through the API phase.

If a **teacher** persona needs to review (`serviceRecords`/`eventProposals`) a student who isn't themselves, set `scopeGrades: ['grade_10', ...]`. A teacher with zero `teacher_scope_assignment` rows resolves to `ScopeDescriptor.Self` (`DataScopeResolverImpl`) — reviewing anyone else's record/proposal 404s, not 403s (B1.7's deliberate no-existence-leak). Admin personas never need this — `admin`'s permission grant bypasses scope entirely (`grantAll`). There is no admin UI for scope assignment yet, so this is also a direct-SQL-only field.

`seed.ts` generates and bcrypt-hashes a real password for every user in this array — you never write a password here.

## Adding a new student (roster row, no login)

Add to `students`. `gradeId`/`classId` must be real ids already seeded by Flyway (`grade_6`..`grade_11`, `class_6A`/`6B`..`class_11A`/`11B` — see `R__seed_base_data.sql`), not display names like "Grade 10". `graduationYear` is a plain int (matches `@Min(2000)` on the real `CreateStudentRequest`).

## Adding a new project

Add to `projects`. `categoryId` must be a real `service_category` id (`cat_campus`/`cat_community`/`cat_welfare`/`cat_education`/`cat_environment`/`cat_other`). `eligibleGradeIds` is a list of grade ids, same as above.

`deliveryScope` is **required** by `POST /projects` — one of `ON_CAMPUS`/`OFF_CAMPUS`/`HYBRID`, sent as the raw enum name (the backend does `DeliveryScope.valueOf`, so the frontend's camelCase `onCampus` form is not accepted here). Omitting it fails the whole seed run with a `422` on the first project.

`targetStatus` determines what `seed.ts` does after creating it:
- `draft` — create only, nothing else.
- `published` — create, then `POST /projects/{id}/publish`. Pick offsets where `registrationOpenOffsetDays` is still in the future (registration hasn't opened yet).
- `registrationOpen` — publish, `registrationOpenOffsetDays` in the past, `registrationCloseOffsetDays` and `startOffsetDays` in the future.
- `inProgress` — publish, `startOffsetDays` in the past, `endOffsetDays` in the future.
- `completed` — publish, `endOffsetDays` in the past. Never explicitly closed.
- `closed` — publish, `registrationCloseOffsetDays` in the past, `startOffsetDays` **in the future**, and at least one `registrantStudentNumbers` entry. `closed` means exactly one thing now — the registration window elapsed (`ProjectStateMachine`, split out 2026-08-08 by `Docs/optimization/02-PorjectOrganization.md` §2) — so it is purely date-derived: a past start date carries it straight on to `inProgress`, and zero confirmed registrants auto-archives it instead. There is no `POST /projects/{id}/close` endpoint; manager-initiated cancellation is `POST /projects/{id}/cancel`, which archives with a required reason.

There is no `targetStatus: 'archived'` — archiving is meant to be exercised manually against one of the `completed`/`closed` seeded projects during testing, not pre-seeded (archiving everything up front would leave nothing to test the archive action against).

`ownerUsername` must be a seeded user holding `projects.create` (a `teacher` or `admin`).

## Adding a new service record

Add to `serviceRecords`. `studentUsername` must be a user in `users` with `linkedStudentNumber` set — the record is created as that user, and the backend resolves the student from the session, not from a field in the request.

A record cannot reach any status past `draft` without at least one evidence file (`EVIDENCE_REQUIRED`) — `seed.ts` handles this automatically for every non-`draft` target by uploading a minimal real PDF through the actual 3-step file flow (`upload-intents` → content `PUT` → `complete`) before creating the record. A PDF, not an image, is used deliberately: `FileServiceImpl.complete()` runs a real `ImageIO` decode/re-encode on JPEG/PNG (EXIF stripping), which requires an actually-decodable image; a PDF only needs its magic bytes to match (`BasicFileScanner`), so a synthetic one is enough.

`targetStatus`:
- `draft` — create only.
- `submitted` — create, upload evidence, `submit`. Lands in the real review queue, left undecided — decide it manually during testing, or add `reviewerUsername`/`reasonCode`/`comment` to move it further.
- `approved`/`returned` — create, upload evidence, submit, then `reviewerUsername` decides (`approve` credits the real ledger and sends a real notification; `returnForChanges` sends a different real notification). `reviewerUsername` must hold `reviews.decide` (`teacher` or `admin`) and should not be the same user as `studentUsername`.

## Adding a new event proposal

Add to `eventProposals`. `supervisorUsername` must be a seeded **teacher** — `proposedSupervisorId` is checked at create/update time (`userService.hasActiveRole(id, "teacher")`), not re-validated at approval, so an admin id here will fail at create, not at the more obviously-related approve step. `plannedStartOffsetDays`/`plannedEndOffsetDays` must both be positive (submission validation requires the planned start to be in the future) and `plannedEndOffsetDays` must be strictly after `plannedStartOffsetDays` (`seed.ts` uses the same day at different hours — 09:00 vs 17:00 — for single-day activities, which already satisfies this; give them different day offsets for a multi-day one).

`organizationId` and `newOrganizationName` are mutually exclusive — set at most one (`organizationName` here, resolved to a real id from the `organizations` array; or `newOrganizationName`, which the backend creates automatically as a `COMMUNITY`-type org in the same transaction).

`targetStatus`:
- `draft` — create only. Fields the real submission validation would otherwise require (`objective`/`description`/`safetyPlan`/`evidencePlan`) can be left as empty strings for a draft, same as a real half-filled form.
- `submitted` — create, submit. Lands in the real proposal review queue.
- `changesRequested`/`approved` — create, submit, then `reviewerUsername` (a teacher) decides. **`approved` generates a real `Project`** as a side effect (`ProposalApprovalTransaction`) — check `Docs/TestAccounts.md`'s note or the script's own log output for which project that created if you need to reference it.

## After editing

1. `npx tsc --noEmit -p tsconfig.json` from `integration-test/` — this file's types are strict.
2. If you're re-running against a server that already has the *old* version of this data seeded, delete the relevant rows first (`seed.ts` will refuse to run over existing usernames/student numbers — see `README.md`'s re-run semantics).
