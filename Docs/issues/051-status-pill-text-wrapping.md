# 051 — Status/label pills wrapped onto two lines, breaking the pill shape

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user with a screenshot of the Student Portal's
`Propose Volunteer Activity` list: multi-word status pills like "Pending
Review" and "Changes Requested" wrapped their text onto a second line,
turning the rounded-pill shape into a stacked, broken-looking rectangle.
The user asked for this to be checked and fixed on all pages, across both
the Student Portal and Admin/Teacher Portal, not just the one screen shown.

## Root cause

The shadcn `Badge` component (`components/ui/badge.tsx`) already carries
`whitespace-nowrap`, but most status/category pills across the app are
hand-rolled `<span className="text-xs px-2 py-0.5 rounded-full ...">`
elements that predate/bypass `Badge` and never picked up that rule. On
narrow mobile cards, the pill's `w-fit` sizing lets the container shrink
below the text's natural width, so the browser wraps the label inside the
pill instead of letting the pill widen.

## Fix

Added `whitespace-nowrap` to every hand-rolled `rounded-full` label pill
found across both portals (22 spots across 12 files):

- Student Portal: `StudentHome.tsx` (×3), `ProjectCenter.tsx`,
  `ServiceRecords.tsx` (×2), `EventProposals.tsx` (×4, incl. the revision
  `v{n}` chip), `NotificationsPage.tsx`, `StudentCases.tsx`.
- Admin/Teacher Portal: `ProjectManagement.tsx` (×2),
  `OrganizationManagement.tsx` (×2), `ProjectRegistrants.tsx`,
  `StudentRecords.tsx`, `ProposalReview.tsx`, `AdminDashboard.tsx`,
  `SystemAdmin.tsx`, `CaseReview.tsx`.

No backend changes. The `Badge` component itself needed no change since it
already had `whitespace-nowrap`.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass at a 390px mobile viewport against both portals:
  Student Portal's Propose Volunteer Activity list (the reported page) and
  Admin Portal's Proposal Review list, confirming every status pill stays
  on one line.

## Resolution

12 files edited under `frontend/src/app/components/{student,admin}/`, all
adding `whitespace-nowrap` to existing pill `className`s (no other class
changes). Frontend rebuilt and redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP at a 390×844 mobile viewport: logged in as `student001`/Zhang Ming,
confirmed "Pending Review", "Changes Requested", "Approved", and
"Rejected" pills all render on one line on the Propose Volunteer Activity
list (the exact page from the reported screenshot). Logged in as
`admin001`/Wei Chen, confirmed "Pending Review" pills stay on one line on
Admin Portal → Proposal Review. No console errors on either pass.*
