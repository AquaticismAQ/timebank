# Updating `mock-data.ts`

## Check your work: `npm run verify-fixture`

**Run it after every edit, before you seed anything.** It is also run by
`seed.ts` itself, so a fixture that breaks an invariant cannot reach a database.

```bash
cd integration-test && npm run verify-fixture
```

Every rule in this guide used to be prose enforced by nothing, which meant a
violation could only be found by opening the one screen that exposed it — that
is what issue 103 is about, and it cost two evenings of one-defect-at-a-time
fixes. The rules are executable now. `verify-fixture.ts` groups them three ways,
and the grouping tells you how urgent a failure is:

| | What a failure means |
| :--- | :--- |
| **BACKEND** | The real API would reject this. Your seed run dies partway through with a 422 and leaves a half-populated database |
| **REPORTS** | A report will answer nothing, or answer wrongly, while looking like it worked — the expensive kind, because the screen still shows a number |
| **CREDIBLE** | The data is implausible to a school. Nothing else in this project has an opinion about these, which is how 99 hours of volunteering inside 17 days once passed a full verification pass |

If you are adding a rule to this guide, add it to the checker in the same edit.

## The dataset spans two school years

`R__seed_base_data.sql` derives the academic year from the current date, so in
mid-September the *current* year is about a fortnight old. Everything used to be
crammed into it, which made the data self-consistent and unbelievable.

So the fixture populates **both** the current year and the one before it:

- **Prior year** carries the history — about 90% of the hours, spread across all
  twelve months. This is what gives the Reports trend a curve, and it is the
  only place the *cumulative* stage requirement (75h middle, 300h high) can be
  reached, since one fortnight cannot hold 300 hours.
- **Current year** carries recent activity only — roughly the share of the year
  that has actually elapsed, so the month in progress never shows more hours
  than a complete month of last year.

`academicYearWindow(startYear)` gives the day-offset bounds of either. Nothing
in this file may use a literal year.

## Students belong to enrolment cohorts

Students enter at Grade 6 and stay six years, so `enrollmentAcademicYearId` is
derived from the grade and `seed.ts` inserts a cohort row per year. This is not
bookkeeping: `ReportQueryRepository` joins `ay.start_date <= :end`, so a student
enrolled in a year *after* the one being reported on is excluded from that
year's population entirely.

The visible consequence is correct and worth keeping: **Grade 6 has no
prior-year history**, because that cohort had not joined yet. Their coin targets
are correspondingly tiny — the only window they can have served in is the days
since term started.

## Why this file looks the way it does

`mock-data.ts`'s exported arrays (`organizations`, `users`, `students`, `projects`, `serviceRecords`, `eventProposals`) are shaped close to the real backend's `Create*Request` DTOs — real field names, real id formats — not the frontend's view-model shapes in `frontend/src/app/mock/data.ts` (which has its own `User.password`, denormalized display names, `myApplicationStatus`, etc., and is maintained independently for the app's own offline mock mode). The two files will drift from each other over time; that's an accepted tradeoff, not a bug — see the plan discussion that produced this directory if you want the reasoning.

Every date field is an **offset in days relative to whenever `seed.ts` runs**, never an absolute date. `ProjectStatusTarget`'s `registrationOpen`/`inProgress`/`completed` are computed by the real backend from "is this timestamp before/after now" (`ProjectServiceImpl`'s lazy time-based status sync) — a hardcoded absolute date would be correct on the day it was written and silently wrong every day after.

## Curated vs generated data

Two kinds of fixture live in this file, and they are edited differently:

- **Curated** — organizations, staff accounts, projects, event proposals, cases, and the `narrativeRecords` array. Each of these carries a specific narrative that some screen or workflow needs, so they are written out by hand. Edit them directly.
- **Generated** — the 50 students and the ~230 approved service records backing their balances, produced by the builders at the bottom of the file from a **fixed PRNG seed**. Hand-writing them would make this file unreviewable. Do not paste generated rows in by hand; change the builder or its inputs instead.

Because the PRNG seed is fixed, every run produces byte-identical data. A re-seed is reproducible, and any difference you see between two runs is a real change, not noise.

## Coin targets are chosen to cover the design, not to look random

`COIN_TARGETS_BY_GRADE` gives each grade five slots, and every grade gets all
five exactly once:

| Slot | What it demonstrates |
| ---: | :--- |
| 0 | no service at all — the empty-state screens |
| 1 | short of the annual requirement — the "gap to target" state |
| 2 | annual requirement met |
| 3 | cumulative stage requirement met |
| 4 | a sustained volunteer, high honour tier |

The magnitudes rise with the grade because an older student has had longer to
accumulate *and* because the bar itself rises (grades 6-8: 25h a year, 75h
cumulative; grades 9-11: 75h and 300h).

`targetCoins` is the student's **total approved hours**, including any
hand-pinned or project record they already carry — the generator subtracts what
is already there rather than stacking on top.

Two things the checker enforces that are easy to break by accident: every honour
tier must have at least one student in it, and at least five students must have
no service at all. Raising targets across the board quietly costs you the empty
states.

## One session per student per day

The generator consumes a day when it places a session and never revisits it, so
two records for the same student on the same day are unrepresentable rather than
merely checked for. Sessions are weekend-weighted about two to one, and the
length menu differs by day kind — a weekend shift is 2-6h, a school-day one is
1-2.5h after the last lesson.

This is what the CREDIBLE checks defend. Before it, the busiest student did 99
hours in 17 days, active on every one of them, with more records on a Thursday
than a Saturday.

## Two consistency rules you must not break

**1. Durations are whole multiples of 30 minutes.** The active rule (`rule_default_2025`) rounds half-up to 30 minutes, converts 60 minutes to one unit, and pays 100 centi-coins per unit — so **one approved hour is exactly 1.00 coin**. Keeping every `endHour - startHour` on a 30-minute boundary makes the rounding a no-op, so the hours a student sees served always equal the coins they see credited. A 1h47m record would round to 2h and credit 2.00 coins, which reads as a bug to anyone looking at the screen. Half hours (`.5`) are fine and are deliberately used in places to exercise the fractional display path.

**2. Balances are never written directly.** Coins exist only because an approved record credited the ledger. `GeneratedStudent.targetCoins` is the figure the builder *composes approved records to reach*; it is not stored anywhere. There is no balance field, and adding one would break the invariant the whole ledger design rests on. `seed.ts`'s final phase re-reads every student's live balance through the API and fails the run if it does not equal the sum of that student's approved record hours.

## Names, student numbers, and classes

Student names follow the masking convention from `Design/english/08-mlps-level1-security-design-guide.md` §4.1 — `Alex L.(李*红)`: English given name plus surname initial, Chinese name with its middle character hidden, so both halves carry the same masking strength. Student numbers are opaque 8-character **Crockford base32** identifiers (`HKTZ4HKV`), not sequential roll numbers. That alphabet omits I, L, O and U so nothing can be misread as 1 or 0, and it is displayed uppercase.

These are fictional fixtures, so the masking protects nobody. The point is to exercise the **real format** in the UI — CJK plus parentheses inside column widths, search, sort, and truncation behaviour, and an opaque hash where a readable number used to sit.

`namedClasses` adds `9-Mars` and `10-Luna` via `seed.ts`'s SQL phase rather than to `R__seed_base_data.sql`. Base data ships to every real deployment and test fixtures do not belong in it. Short word class names are deliberate: they prove class handling is not hardcoded to single letters.

## Teachers and review scope

There are five teachers, and `reviewerForGrade` maps each grade to the one that holds scope over it. Between them they cover all six grades with no overlap, which is what makes cross-scope isolation testable — a grade-6 record must be invisible to the grade-11 teacher.

**Always route a review through `reviewerForGrade`.** A teacher without scope over the student's grade gets a `404`, not a `403` (B1.7's deliberate no-existence-leak), so a mis-routed decision fails in a way that looks like missing data rather than a permission error. Admins bypass scope entirely via `grantAll`.

## Adding a new organization

Add to `organizations`. `type` must be one of `SCHOOL`/`NONPROFIT`/`COMMUNITY`/`GOVERNMENT`/`OTHER` (the real `Organization.Type` enum, `cn.timebank.organization.domain.Organization`) — nothing else validates it, so a typo silently inserts an organization with a type the frontend's `mapEnumFields` won't recognize.

## Adding a new user (login account)

Add to `users`. `roleCodes` must be role **codes**, not ids — `student`/`teacher`/`admin`/`sysadmin`, or a school-defined custom role (see `R__seed_permissions_and_roles.sql`'s `role` table; that file is also the authoritative source for what each role can actually do — cross-check there, not against `frontend/src/app/mock/data.ts`'s permission arrays, if you're deciding what a new persona should be able to see). **Exactly one code, always** — every user holds exactly one role (`07-RoleFinetune.md` §2.5; the dual-workspace/multi-role account pattern this array's type still technically permits was retired 2026-08-19, see the `both001` entry's comment above). Don't add a second one no matter what persona you're building.

For a plain student login, set `linkedStudentNumber` to a `studentNumber` that also exists in the `students` array. `seed.ts` resolves this to a real `student.id` and sets `user.student_id` directly via SQL after both rows exist — **there is no API surface for this link** (confirmed against `CreateUserRequest`, which has no free-typed grade/class/student-number field, by design — issue 003), so don't try to route it through the API phase.

If a **teacher** persona needs to review (`serviceRecords`/`eventProposals`) a student who isn't themselves, set `scopeGrades: ['grade_10', ...]`. A teacher with zero `teacher_scope_assignment` rows resolves to `ScopeDescriptor.Self` (`DataScopeResolverImpl`) — reviewing anyone else's record/proposal 404s, not 403s (B1.7's deliberate no-existence-leak). Admin personas never need this — `admin`'s permission grant bypasses scope entirely (`grantAll`). There is no admin UI for scope assignment yet, so this is also a direct-SQL-only field.

`seed.ts` generates and bcrypt-hashes a real password for every user in this array — you never write a password here.

## Adding a new student (roster row, no login)

Students are **generated**, not hand-listed — see "Curated vs generated data" above. To change the roster, edit `buildStudents()`: `STUDENT_COUNT` (50) sets the roster size and `STUDENTS_WITH_LOGIN` (30) how many get a portal account, with the rest roster-only. That split is realistic — most schools issue accounts to a subset first — and it also keeps re-seeding affordable, because nginx rate-limits `/api/v1/auth/login` to 10/min per IP and every student must log in as themselves.

`gradeId`/`classId` must be real ids: `grade_6`..`grade_11` and `class_6A`/`6B`..`class_11A`/`11B` from `R__seed_base_data.sql`, plus `class_9_mars`/`class_10_luna` from `namedClasses`. `graduationYear` is a plain int (`@Min(2000)` on the real `CreateStudentRequest`).

There are no `email`, `guardianName` or `guardianContact` fields: the columns were dropped in `V26` under data minimization (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1) because no screen ever displayed them. Do not reintroduce them. `user.email` on the login account is unaffected.

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
- `approved`/`returned`/`rejected`/`underReview` — create, upload evidence, submit, then `reviewerUsername` decides. The wire values are `APPROVE`, `RETURN_FOR_CHANGES`, `REJECT` and `ESCALATE` (`underReview` is reached by escalating; it is a real resting state, unlike the proposal and case statuses removed in issues 080/082). `approve` credits the real ledger and sends a real notification. `reviewerUsername` must hold `reviews.decide` (`teacher` or `admin`), must not be the student, and **must hold scope over the student's grade** — use `reviewerForGrade`.

Remember the 30-minute rule when choosing `startHour`/`endHour`: the difference must be a whole or half hour, or the credited coins will not match the hours shown.

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
