# 054 — Service Records / Propose Volunteer Activity lacked search/filter/sort parity; "All"-only default option labels; missing pagination footer

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user, as a follow-up to issue 053 (Project
Center's filter/sort parity): Student Portal's `Service Records` and
`Propose Volunteer Activity` should get the same search/filter/sort
treatment — replacing their pill-button status tabs with the admin-style
filter dropdowns. The user also flagged two things found along the way:
default filter options must never read as a bare "All" (must be
domain-specific, e.g. "All Statuses"/"All Categories") — and that issue
053's own `ProjectCenter.tsx` change had exactly this problem — and that
these pages also need visible pagination.

## Root cause

- `ServiceRecords.tsx`/`EventProposals.tsx` predate the
  `05-SearchFilterSortConsistency.md` pass (admin-only, 5 pages) and issue
  053 (which brought `ProjectCenter.tsx` up to that standard but didn't
  touch these two): both used a horizontally-scrolling row of status pill
  buttons, no category filter, no search on the proposals page, and no
  sort control at all.
- Issue 053's `ProjectCenter.tsx` status `<select>` reused the pre-existing
  `student.projects.all` key ("All") as its default option label instead of
  a dedicated "All Statuses" key — every other filter dropdown in the
  codebase (`admin.projects.allStatuses`, `admin.proposals.allCategories`,
  etc.) uses a domain-specific "All XXX" label; this one slipped through.
- `PageControls` renders nothing at all (`return null`) when a list fits on
  one page *and* the caller didn't pass `pageSize`/`onPageSizeChange` (its
  `enhanced` flag) — every list on these two pages was small enough to fit
  on one page in the seeded dataset, so the pagination footer (and the
  "N total" count) was invisible even though the component was already
  wired in. `ProjectCenter.tsx` had the same gap.

## Fix

**Filter/sort parity** (`ServiceRecords.tsx` mirrors `AuditCenter.tsx`'s
domain; `EventProposals.tsx` mirrors `ProposalReview.tsx` exactly, since
both share the same backend `SORTABLE`/filter set as their admin
counterpart):

- Both pages: status-tab pill row removed, replaced by the
  `05-SearchFilterSortConsistency.md` §2.4 layout (search alone below
  `lg:`, filters as an `Nx1` grid alongside it, merged into one row at
  `lg:` and up) with a status `<select>` + category `<select>` (no grade
  filter — not meaningful for a student's own list). `EventProposals.tsx`
  had no search box at all before; now has one (`ProposalController`
  already accepted a generic `search` param, just never wired up here).
- Sort row added to both (`grid grid-cols-3`, always, §2.5): `ServiceRecords`
  uses `serviceDate`/`createdAt`/`status` (matches
  `ServiceRecordController.SORTABLE`); `EventProposals` uses
  `plannedStartAt`/`createdAt`/`status` (matches `ProposalController.SORTABLE`,
  identical set to `ProposalReview.tsx`'s admin view). Both derive
  `sortKey`/`sortDesc` from `params.sort` rather than separate `useState`,
  matching `AuditCenter.tsx`/`ProposalReview.tsx`'s exact pattern — no
  client-only sort split needed since every key here is a real backend
  column (unlike `ProjectCenter`'s `enrolledCount`).
- `ListStateBanner`'s existing `hasActiveFilters`/`onClearFilters` props
  (already used by `AuditCenter.tsx`) wired up on both pages so a
  filtered-empty result offers a "clear filters" link instead of the
  generic empty state.

**"All XXX" wording** — new dedicated keys added everywhere a bare "All"
was about to be (or already was) used as a filter-dropdown default:
`student.projects.allStatuses` (fixes issue 053's `ProjectCenter.tsx`,
which now uses it instead of the bare `student.projects.all`),
`student.records.allStatuses`/`allCategories`, `student.proposals.allStatuses`/
`allCategories` — all en+zh, wording matched to the equivalent existing
admin keys (e.g. `admin.proposals.allStatuses`).

**Pagination visibility** — all three student list pages
(`ProjectCenter.tsx`, `ServiceRecords.tsx`, `EventProposals.tsx`) now pass
`pageSize`/`onPageSizeChange` to `PageControls`, matching every admin list
page's convention (`ProjectManagement.tsx`, `AuditCenter.tsx`,
`ProposalReview.tsx`, `CaseReview.tsx`). This makes `PageControls` render
its "N total" count + page-size selector even when there's only one page,
instead of disappearing entirely.

No backend changes — every filter/sort/search capability used here already
existed server-side (`ServiceRecordController`/`ProposalController`), just
wasn't wired up from these two screens.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass against the deployed server as `student001`, at
  desktop (1280px) and mobile (390px) for both pages: search+filter row
  and sort row render identically to their admin counterparts' layout, no
  wrapping/overflow; selecting a status filter narrows the list correctly
  (`Service Records` 9→5 on "Draft", `Propose Volunteer Activity` 6→1 on
  "Approved"); clicking a sort button shows the active/direction state;
  pagination footer ("N total · page X / Y" + per-page selector) visible
  on both pages even with a single page of results.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx`,
`EventProposals.tsx`, `ProjectCenter.tsx` (pagination-only fix) edited per
above. `frontend/src/i18n/en.json`/`zh.json`: new `allStatuses`/
`allCategories`/sort-label keys under `student.records`/`student.proposals`,
plus the `student.projects.allStatuses` fix. Frontend rebuilt and
redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming, at 1280px and 390px:
`Service Records` — "All Statuses"/"All Categories" dropdowns and
"Service Date"/"Created"/"Status" sort buttons render correctly (caught
and fixed one bug here: the sort-button label lookup initially built the
wrong i18n key — `sortCreatedAt` instead of `sortCreated` — for the
`createdAt` field, showing a raw untranslated key on screen; fixed to an
explicit per-key label lookup, redeployed, re-verified); selecting
"Draft" narrowed 9→5 records, all shown rows "Draft". `Propose Volunteer
Activity` — "All Statuses"/"All Categories" dropdowns and "Planned
date"/"Created"/"Status" sort buttons render correctly; selecting
"Approved" narrowed 6→1 proposal. Both pages' pagination footer shows
"N total · page 1 / 1" with a working per-page selector. No console
errors on either page at either viewport.*
