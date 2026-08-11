# 020 — Admin review modals close on an accidental outside click

Status: **Fixed and live-verified (2026-08-12)**

## Symptom

Reported directly by the user: clicking anywhere outside an open modal
dialog in Audit Center, Case Review, Proposal Review, or Student
Management dismisses it, including modals with in-progress unsaved input
(a typed review comment). Too easy to lose work with a single stray click.

## Root cause

Every one of these pages' modal backdrops carried an `onClick` handler
that called the same close function as the explicit X/Cancel button —
the shared `DetailWorkbench.tsx` (`components/shared/DetailWorkbench.tsx:40`,
used by Audit Center/Case Review/Proposal Review's detail view), each
page's own decision-confirmation modal (`AuditCenter.tsx:482`,
`CaseReview.tsx:301`, `ProposalReview.tsx:334`), and Student Management's
edit-student modal (`StudentList.tsx:432`). This is a common enough modal
pattern in general, but wrong here specifically because several of these
dialogs hold a typed comment (the review decision modals) or an in-progress
edit (Student Management), where an accidental dismiss silently discards
it with no confirmation.

## Fix

Removed the backdrop `onClick` handler from all five call sites — the
backdrop `<div>` stays as a purely decorative dimmed overlay. Every modal
already has an explicit close path (X button, or — following
`05-SearchFilterSortConsistency.md` §5's redesign — a footer "Cancel"
button) that remains the only way to dismiss. The new
`components/admin/StudentRecords.tsx` modal (built in the same session,
see issue 021) was written with this pattern from the start rather than
needing the same fix twice.

## Test plan

- `pnpm build` clean.
- Live: dispatch a real click event at the backdrop's outer corner
  (away from the centered modal card) and confirm the modal element is
  still present in the DOM afterward, for both a `DetailWorkbench`-based
  view (Audit Center) and the newer `StudentRecords` modal.

## Resolution

`frontend/src/app/components/shared/DetailWorkbench.tsx`,
`frontend/src/app/components/admin/AuditCenter.tsx`,
`frontend/src/app/components/admin/CaseReview.tsx`,
`frontend/src/app/components/admin/ProposalReview.tsx`,
`frontend/src/app/components/admin/StudentList.tsx` — backdrop `onClick`
removed from each. Frontend redeployed.

---
*Live-verified 2026-08-12 against `https://tb.example.com/` via Playwright
MCP: a synthetic bubbling click dispatched at the backdrop's top-left
corner (well outside the centered card) left both the Audit Center
`DetailWorkbench` and the new Student Management "Records" modal open
in both cases; each page's own Cancel button still closes its modal
normally.*
