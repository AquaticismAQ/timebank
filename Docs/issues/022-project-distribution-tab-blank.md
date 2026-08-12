# 022 — Reports' "Project Distribution" tab rendered blank

Status: **Fixed and live-verified (2026-08-12)**

## Symptom

Reported directly by the user: Admin → Reports → "Project Distribution"
tab showed nothing — just the two section headings ("Popular Projects
Participation" / "Top Projects") with no chart, no list, and no
explanation.

## Root cause

Not a code defect in the query or the API — `GET /reports/top-projects`
correctly returned `{"topProjects": []}`. The live dataset genuinely has
zero enrollments in `CONFIRMED`/`COMPLETED` status right now (confirmed
via `GET /projects`: every one of the 8 seeded projects shows
`enrolledCount: 0`). The one real `CONFIRMED` registration this dataset
ever had (`student001` → "Campus Open Day Volunteers", created live
2026-08-10 per this file's own 2026-08-10 entry) was since cancelled —
`GET /applications?filter[projectId]=...` shows it `CANCELLED` at
`2026-08-11T21:37:51Z`, most likely incidental fallout from a later
live-testing session, not a deliberate action. Not re-investigated
further since re-confirming that registration wouldn't fix the actual
gap this issue is about.

The real defect: `ReportsPage.tsx`'s `projects` tab had no empty-state
handling at all — `pieData`/`data.topProjects` being `[]` just made
`<PieChart>`/the `divide-y` list render as empty space under their
headers, unlike every other list surface in the app (which already uses
the shared `ListStateBanner` for this).

## Fix

`ReportsPage.tsx`: when `data.topProjects.length === 0`, render
`ListStateBanner` (`empty` + a new `admin.reports.noProjectData` i18n
key) instead of the pie chart and top-projects list; unchanged when
there's real data. `en.json`/`zh.json` gain `admin.reports.noProjectData`.

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Live: loaded Reports → Project Distribution with the current
  (zero-confirmed-registrants) dataset and confirmed the new empty state
  renders instead of a blank tab.

## Resolution

`frontend/src/app/components/admin/ReportsPage.tsx` (import
`ListStateBanner`, wrap the `projects` tab body in the
`data.topProjects.length === 0` check), `frontend/src/i18n/en.json`/
`zh.json` (`admin.reports.noProjectData`). Frontend redeployed.

This does not restore the tab's positive-data path — no seeded/live
project currently has a confirmed registrant, so the pie chart/list
still can't be exercised end-to-end. A future session that needs to
verify the non-empty rendering should confirm a real registration first
(e.g. via `POST /projects/{id}/applications` + `.../confirm` as an
admin/teacher, same as the 2026-08-10 entry did) and re-check this tab.

---
*Live-verified 2026-08-12 against `https://tb.example.com/` via Playwright
MCP, logged in as `Teacher Wei Chen`: Project Distribution tab now shows
"No projects have confirmed registrants yet." with the standard
inbox-icon empty state instead of blank space, confirmed via screenshot.
The positive-data (non-empty) rendering path was not exercised this
session — no confirmed registrant exists in the live dataset to trigger
it, as noted above.*
