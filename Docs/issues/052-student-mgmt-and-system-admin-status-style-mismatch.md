# 052 — Student Management and System Admin → Users status columns used different, inconsistent styles

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user: the "Status" column in Admin Portal →
Student Management (on-track/behind/critical) and Admin Portal → System
Admin → Users tab (active/inactive) didn't share a visual style. The user
asked for both to use the same look: a colored pill, no icon.

## Root cause

Neither screen actually used the app's established status-pill convention
(`text-xs px-2 py-0.5 rounded-full whitespace-nowrap` + `STATUS_COLOR_CLASSES`,
already used by `StudentRecords.tsx`/`ProposalReview.tsx`/`CaseReview.tsx`/
`StudentCases.tsx` — see issue 051):

- `StudentList.tsx` (Student Management) rendered `critical` as plain
  colored text with an `AlertCircle` icon, `behind`/`onTrack` as plain
  colored text with no background at all — three different treatments in
  one column.
- `SystemAdmin.tsx`'s Users tab rendered `active`/`inactive` as plain
  colored text with no background, no icon.

Both predate the shared `STATUS_COLOR_CLASSES` map and grew their own ad
hoc status styling independently, the same "each screen invented its own
convention" gap issue 050 closed for row-action buttons.

## Fix

Both now use the shared pill: `text-xs px-2 py-0.5 rounded-full
whitespace-nowrap ${STATUS_COLOR_CLASSES[...]}`, no icon.

- `StudentList.tsx`: desktop table's status column — `critical` → red,
  `behind` → yellow, `onTrack` → green pill (previously icon+text/plain
  text). Mobile card's inline critical indicator — same red pill, `AlertCircle`
  icon dropped; visibility unchanged (still only shown when at-risk, matching
  the mobile card's prior behavior — `behind`/`onTrack` have no mobile badge,
  same as before, since the progress bar already carries that color there).
  Removed the now-unused `AlertCircle` import.
- `SystemAdmin.tsx` Users tab: desktop table + mobile card status —
  `active` → green, `inactive` → gray pill (previously plain text, no
  background).

No backend changes.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass against both screens as `admin001`, at both desktop
  (1280px) and mobile (390px) widths, confirming the pill renders
  identically in both places.

## Resolution

`frontend/src/app/components/admin/StudentList.tsx` and
`frontend/src/app/components/admin/SystemAdmin.tsx`: both import
`STATUS_COLOR_CLASSES` from `lib/statusLabels.ts` and use it for their
status column. Frontend rebuilt and redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `admin001`/Wei Chen: Student Management's desktop table
and mobile cards show red "Critical" pills (11/11 seeded students are
currently critical); System Admin → Users tab's desktop table and mobile
cards show green "Active" / gray "Inactive" pills. Same shape, padding,
and no-icon treatment on both screens, both viewports. No console errors.*
