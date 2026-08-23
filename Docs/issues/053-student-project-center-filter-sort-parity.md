# 053 — Student Portal's Project Center lacked admin-style filters and had no sorting at all

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user: Student Portal → Project Center should have
the same filter and sort controls as Admin Portal → Project Management
(`ProjectManagement.tsx`, the reference layout `Docs/optimization/
05-SearchFilterSortConsistency.md` established for the five admin list
pages), minus the grade filter (not meaningful for a student's own project
list).

## Root cause

`ProjectCenter.tsx` predates the 05-SearchFilterSortConsistency.md pass,
which only covered the five admin list pages — the student Project Center
was never brought forward to the same design language:

- Its status/category filters were rendered as a desktop sidebar
  (`<aside>`) + a separate mobile toggle panel, both using click-button
  lists — a third, page-specific pattern distinct from admin's inline
  `<select>` dropdowns.
- It had **no sort control at all** — no equivalent of admin's
  created/start-date/enrolled sort buttons, server-side sort was never
  wired up (`usePagedApi`'s `setSort` was destructured but unused).

## Fix

Rebuilt the search/filter/sort area to match `ProjectManagement.tsx`
exactly (05-SearchFilterSortConsistency.md §2.4/§2.5 layout rules):

- Search + status `<select>` + category `<select>` in one row, `lg:` and
  up merges to one line, below `lg:` the two selects form a `grid
  grid-cols-2`. No grade filter (student has no analogous need). The
  student-appropriate curated status set (`all`/`canApply` → maps to
  `registrationOpen`/`inProgress`/`completed`) is unchanged — only the
  presentation (button list → select) changed, not which statuses are
  offered.
- New sort row (`grid grid-cols-3`, always, no breakpoint), same 3 keys as
  admin: `createdAt`/`startAt`/`enrolledCount` (the last is client-only —
  same `CLIENT_ONLY_SORT_KEYS` split and `handleSortClick` toggle-direction
  interaction as `ProjectManagement.tsx`, since `ProjectController`'s
  `SORTABLE` set doesn't include a computed enrollment-count column).
- Removed the sidebar/mobile-panel/`FilterPanel` component and the
  `showMobileFilters` state and mobile-filter-toggle button entirely; the
  desktop `lg:flex` two-column (sidebar + grid) layout collapsed to a
  single-column flow, matching admin's page structure.
- New i18n keys `student.projects.sortCreated`/`sortStartAt`/`sortEnrolled`
  (en+zh), reusing the exact same wording as `admin.projects.sortCreated`
  etc.

No backend changes — `GET /projects`' sort/filter capability was already
there and already used by the admin page; the student page just wasn't
calling `setSort`.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass against the deployed server as `student001`, at
  desktop (1280px) and mobile (390px): confirmed the search+filters row
  and sort row render identically to admin's layout at both widths, no
  wrapping/overflow; clicking a sort button re-orders the grid and shows
  the active/direction state; selecting a status filter (`Available`)
  actually narrows the list server-side (6 → 1 project, all
  `Registration Open`).

## Resolution

`frontend/src/app/components/student/ProjectCenter.tsx` rewritten per
above. `frontend/src/i18n/en.json` and `zh.json`: 3 new keys under
`student.projects`. Frontend rebuilt and redeployed
(`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming: at 1280px, search box + status
select + category select share one row alongside the 3-button sort row;
at 390px, search sits alone with the two selects below it as a 2-column
grid, sort row unchanged. Clicked "Enrolled" sort — list re-ordered by
enrollment count, button showed active state + "↓". Selected "Available"
in the status filter — project count dropped from 6 to 1, matching the
single `Registration Open` project in the seeded dataset. No console
errors.*
