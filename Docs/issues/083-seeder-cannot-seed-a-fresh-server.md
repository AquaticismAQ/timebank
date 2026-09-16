# 083 — `seed.ts` can no longer seed a fresh server (four stale assumptions)

Status: **Fixed and live-verified (2026-09-16)**

## Summary

Seeding a genuinely empty schema failed **four times in a row**, each on a
different stale assumption, each only reachable after fixing the one before
it. All four share one root cause: **the seeder has not been run since
2026-08-07**, while the backend kept changing underneath it. Every later
fixture change was applied to the live `tb.example.com` database directly
(see `integration-test/STATUS.md`) instead of by re-seeding, so nothing ever
re-exercised the create path.

| # | Failure | Broken by |
| --- | --- | --- |
| 1 | `422` `deliveryScope` `NOTBLANK` on `POST /projects` | issue 03 / `V18`, 2026-08-10 |
| 2 | `500` `NoResourceFoundException` on `POST /projects/{id}/close` | `02-PorjectOrganization.md` §2, 2026-08-08 |
| 3 | `400` `sourceType EXTERNAL is historical data only` | issue 03 §3/§7.3, 2026-08-10 |
| 4 | `404` `Student not found` on `POST /cases` | pre-existing — wrong id kind sent, never exercised |

None of these are new-server-specific: all four would have failed
identically on `tb.example.com` had its schema ever been reset.

## How it surfaced

While standing up the new alternative dev/test server `timebank.example.com`
(a second, independent box — see `Docs/operations/DeploymentEnv.md`), the
first real seed run against its empty schema failed:

```
== Phase 3: API — projects ==
Seed failed: POST /api/v1/projects -> 422: The request contains invalid fields.
  [{"field":"deliveryScope","code":"NOTBLANK","message":"must not be blank"}]
```

Phases 0–2 (organizations, users, students, all six logins) succeeded, so
the failure is specific to the project-creation payload.

## Finding

`deliveryScope` became a **required** field on `POST /projects` on
2026-08-10, as part of `Docs/optimization/03-ServiceRecordFormOptimization.md`
(migration `V18`, `ON_CAMPUS`/`OFF_CAMPUS`/`HYBRID`, validated with
`@NotBlank` in `ProjectRequests` and parsed with `DeliveryScope.valueOf` in
`ProjectServiceImpl.parseDeliveryScope`).

`integration-test/mock-data.ts` and `seed.ts` were never updated to send it.

This went unnoticed for over a month because **the seeder has not been run
since 2026-08-07** — the existing dataset on `tb.example.com` predates the
field, and every later fixture change was applied to that live database
directly (see `integration-test/STATUS.md`) rather than by re-seeding. The
defect is therefore invisible on the old server and only appears the moment
anyone seeds a genuinely fresh schema, which is exactly what a second
server requires.

Note this is *not* a new-server-specific problem: it would have failed
identically on `tb.example.com` had its schema ever been reset.

## Fix

- `SeedProject` gains a required `deliveryScope: 'ON_CAMPUS' | 'OFF_CAMPUS' | 'HYBRID'`,
  so a future project added to the fixture set cannot omit it silently —
  `tsc` rejects it.
- All six seeded projects assigned a scope from their own existing
  description, not chosen to hit enum values for their own sake:
  `ON_CAMPUS` for the open day (campus exhibition areas), the library
  (Library H504) and the welcome party (School Auditorium); `OFF_CAMPUS`
  for the rural teaching support trip and the community park cleanup;
  `HYBRID` for the nursing home visit, whose description already specifies
  an on-campus training session before the off-campus visit. All three
  enum values end up covered as a consequence.
- `seed.ts` passes the field through in the `POST /projects` payload.
- `MOCK_DATA_GUIDE.md`'s "Adding a new project" section documents the field,
  including that the wire format is the raw enum name — the frontend's
  camelCase `onCampus` form is **not** accepted by `valueOf`.

### 2. `POST /projects/{id}/close` no longer exists

`Docs/optimization/02-PorjectOrganization.md` §2 split the two meanings that
used to share `CLOSED`. `CLOSED` now means **exactly one thing — the
registration window elapsed** — and is reachable only as a `SYSTEM`,
time-derived transition in `ProjectStateMachine`. Manager-initiated
cancellation became `POST /projects/{id}/cancel`, which archives and
requires a reason. The `/close` endpoint was removed; `ProjectController`
exposes only `publish`/`archive`/`cancel`.

The seeder still POSTed to `/close`, which fell through to Spring's static
resource handler — hence a `500 NoResourceFoundException` rather than a 404
(the handler mapping is what's missing, so no controller ever runs; worth
noting separately if a real 404 is wanted for unmatched `/api/**` paths).

Fixed by deleting the call and reaching `closed` the only way it is now
reachable: `registrationCloseOffsetDays` in the past, `startOffsetDays` in
the **future** (a past start date carries it straight on to `inProgress`),
and at least one confirmed registrant (zero registrants auto-archives it
instead). `Nursing Home Visit and Care Activity` moved from
`start -5 / end 10` to `start 6 / end 13` for exactly this window. Its
attached service record is unaffected — nothing validates a record's
service date against the project's window, only `endAt > startAt`.

### 3. `sourceType: 'EXTERNAL'` is rejected for new records

Issue 03 §3/§7.3 made `EXTERNAL` historical/resubmit-only; `POST
/service-records` rejects it outright. Four fixtures still used it. Issue
018 had already hit this once and converted the single record it was adding,
but left the four pre-existing ones alone — correctly at the time, since
they already existed on the live server and were never re-created.

All four are now `SELF`. The three that are submitted/approved already
carried a real supervisor name and contact, so they take
`supervisorKind: 'EXTERNAL_MANUAL'`; the fourth is a `draft` that never
submits, and a supervisor kind is only required at submission, so it stays
without one. `'EXTERNAL'` is **removed from the `SeedServiceRecord`
union** so a future fixture cannot reintroduce an uncreatable record.

### 4. Cases filed by staff sent the wrong kind of id

`seed.ts` sent `studentId: student.userId` — the filer's **login user id** —
where `CaseServiceImpl` passes the value straight to
`studentRepository.findById`, which expects the **roster row id**
(`student.id`). The two are distinct entities linked by `user.student_id`,
so the lookup could never match and returned `404 Student not found`.

This one is not backend drift; it has simply never worked. The only fixture
that exercises it (`filedByUsername: 'teacher001'`, a `manualCorrection`
filed on a student's behalf) was added after the last successful seed run.
Fixed by resolving through the existing `studentIdByNumber` map via the
user's `linkedStudentNumber`, with an explicit error if that link is
missing rather than sending `undefined` and getting a confusing 404.

### Incidental: the seeder swallowed its own diagnostics

`Session.request` threw with only `error.message` ("The request contains
invalid fields"), discarding `error.fieldErrors` — which is the part that
actually names the offending field. Diagnosing this required a temporary
patch to see the payload the server had already sent. The error now appends
`fieldErrors` when present, so the next schema drift is one run away from
being identified instead of two.

## Test plan

1. Drop and recreate the `timebank` schema on `timebank.example.com`, restart
   the backend so Flyway re-migrates from zero. — done (four times, once per
   defect; the seeder is a first-run tool, so every retry needs a clean schema)
2. `npm run seed` completes all six phases without error. — done
3. Confirm every project reached its **intended** status, read from the
   database rather than trusting the seeder's log. — done, and this caught a
   real subtlety: immediately after seeding, two projects still sat at
   `PUBLISHED`, because `syncTimeBasedStatus` is lazy (it runs on read/action,
   not on a schedule). They advanced correctly the moment the project list was
   opened. Final state: `draft` ×2 (one being the proposal-approval-generated
   project), `published`, `registrationOpen`, `closed`, `inProgress`,
   `completed` — every status covered.
4. Confirm all three `deliveryScope` values are present. — done
   (`ON_CAMPUS` ×3, `OFF_CAMPUS` ×2, `HYBRID` ×1).
5. Confirm the dataset renders in the real UI. — done: logged into
   `https://timebank.example.com` as `admin001` via Playwright, Project
   Management shows all 7 projects with correct status badges and real
   registrant counts (3/30, 2/8, 2/12, 2/15), and the sidebar carries live
   queue badges (Audit 2, Cases 2, Proposals 1, Notifications 1).

## Note on the seeder's log output

`seed.ts` prints `project "X" -> <targetStatus>` using the **intended**
status from `mock-data.ts`, not the status the server actually returned. It
is a statement of intent, not verification — step 3 above only worked because
it queried the database instead. Left as-is (changing it is beyond this fix),
but worth knowing before trusting a green seed run.

## Not changed

The old server `tb.example.com` is left exactly as it is. Its existing project
rows already carry a `deliveryScope` (backfilled by `V18` to `HYBRID` for
pre-existing rows), so nothing there is broken and no live write is needed —
this fix only affects what a *future* seed run produces.
