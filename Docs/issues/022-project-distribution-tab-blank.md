# 022 — Reports' "Project Distribution" tab rendered blank

Status: **Fixed and live-verified (2026-08-12)**

## Symptom

Reported directly by the user: Admin → Reports → "Project Distribution"
tab showed nothing — just the two section headings ("Popular Projects
Participation" / "Top Projects") with no chart, no list, and no
explanation.

## Root cause

Not a code defect in the query or the API — `GET /reports/top-projects`
correctly returned `{"topProjects": []}`. The live dataset genuinely had
zero enrollments in `CONFIRMED`/`COMPLETED` status at the time (confirmed
via `GET /projects`: every one of the 8 seeded projects showed
`enrolledCount: 0`). The one real `CONFIRMED` registration this dataset
ever had (`student001` → "Campus Open Day Volunteers", created live
2026-08-10) was since cancelled — `GET /applications?filter[projectId]=...`
showed it `CANCELLED` at `2026-08-11T21:37:51Z`, most likely incidental
fallout from a later live-testing session, not a deliberate action.

The separate real defect: `ReportsPage.tsx`'s `projects` tab had no
empty-state handling at all — `pieData`/`data.topProjects` being `[]`
just made `<PieChart>`/the `divide-y` list render as empty space under
their headers, unlike every other list surface in the app (which already
uses the shared `ListStateBanner` for this). This half is a real,
permanent gap regardless of dataset state — a future zero-participant
period (e.g. before the school year's first project opens) would hit it
again — so it's fixed independently of the data backfill below.

## Fix

**Code**: `ReportsPage.tsx` — when `data.topProjects.length === 0`,
render `ListStateBanner` (`empty` + a new `admin.reports.noProjectData`
i18n key) instead of the pie chart and top-projects list; unchanged when
there's real data. `en.json`/`zh.json` gain `admin.reports.noProjectData`.

**Data**: the underlying "no confirmed registrants anywhere" gap was also
closed, both for future fresh reseeds and on the live server directly.
`mock-data.ts`'s `SeedProject` gained an optional `registrantStudentNumbers`
field; `seed.ts` gained a new Phase 3.5 that inserts `CONFIRMED` enrollment
rows directly via SQL (like organizations/users/students already are) —
**not** through the real `POST /projects/{id}/applications` endpoint,
because that call only succeeds while a project's registration window is
genuinely open *right now*, which is true for at most one project in this
dataset at any given time; every other target status's window has
already elapsed by design, so the real API can't backfill historical
registrants no matter when `seed.ts` runs. Four projects were given
registrants: "Campus Open Day Volunteers" (Jia Ming, Yun Li,
Jordan), "Rural Teaching Support Team (Summer)" (Huang Zihan, Zhao
Siyuan), "School Library Organization and Classification" (Jia Ming,
Yun Li — the first already has an approved PROJECT-sourced record
against this project), "Nursing Home Visit and Care Activity" (Li
Xiaoyu, Chen Meiling — Yun Li already has an approved PROJECT-sourced
record here too).

The same 8 rows were applied directly to the live `timebank` schema
(narrated and confirmed with the user first, per this repo's live-write
convention) since `seed.ts` refuses to re-run against an already-seeded
server (its Phase 0 pre-flight check) — updating the script alone
wouldn't have changed what the live Reports page shows today. One
wrinkle live-only: Jia Ming's existing `CANCELLED` "Campus Open Day
Volunteers" enrollment collides with `uk_enrollment_project_student`
(project_id, student_id) — a plain `INSERT` would have violated it (and
the real API can't re-register a cancelled student either, for the same
reason). Reactivated that specific row via `UPDATE ... SET status =
'CONFIRMED', cancelled_at = NULL` instead of inserting a new one; the
other 7 rows were plain inserts. Every row's `applied_at`/`confirmed_at`
was computed from that project's own `registration_open_at + 1 day`
(`INSERT ... SELECT ... FROM project WHERE id = ...`), not a hand-picked
date.

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Script: `npx tsc --noEmit -p integration-test/tsconfig.json` clean.
- Live: loaded Reports → Project Distribution before the data fix
  (confirmed the new empty state) and after (confirmed the real pie
  chart + ranked list).
- Live DB: `SELECT` against `enrollment` post-write confirmed all 8 rows
  landed with the intended project/student pairing and `CONFIRMED` status.

## Resolution

`frontend/src/app/components/admin/ReportsPage.tsx` (import
`ListStateBanner`, wrap the `projects` tab body in the
`data.topProjects.length === 0` check), `frontend/src/i18n/en.json`/
`zh.json` (`admin.reports.noProjectData`). Frontend redeployed.

`integration-test/mock-data.ts` (`SeedProject.registrantStudentNumbers`,
populated for 4 projects), `integration-test/seed.ts` (Phase 3.5 direct
enrollment insert, new `sqlDateTime()` helper for MySQL's
`YYYY-MM-DD HH:MM:SS.sss` literal shape). Live `timebank.enrollment`:
1 `UPDATE` (reactivating Jia Ming's cancelled row) + 7 `INSERT`s, run
directly via SSH/`mysql`, matching this file's script changes.

---
*Live-verified 2026-08-12 against `https://tb.example.com/` via Playwright
MCP, logged in as `Teacher Wei Chen`. Before the data fix: Project
Distribution showed "No projects have confirmed registrants yet." with
the standard inbox-icon empty state instead of blank space (screenshot
confirmed). After seeding the 8 live enrollment rows: the tab shows a
real 4-slice pie chart (Campus Open Day Volunteers 33%, Nursing Home
Visit and Care Activity 22%, Rural Teaching Support Team 22%, School
Library Organization and Classification 22%) and a ranked "Top Projects"
list with correct participant counts and hours (screenshot confirmed).
Both the empty-state and non-empty rendering paths are now exercised.*
