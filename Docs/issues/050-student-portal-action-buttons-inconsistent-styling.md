# 050 — Student Portal's Service Records / Propose Volunteer Activity action buttons didn't match the icon+colored-border row-action style

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user: on the Student Portal, `Service Records`
(desktop table and mobile card) and `Propose Volunteer Activity` (desktop
table) row-action buttons were inconsistently styled — a mix of plain
`border-border`/`text-foreground` outline buttons and filled
`bg-primary`/`text-primary-foreground` buttons, with no per-action color
and, on several buttons, no icon. The user asked for these to be brought in
line with the theme already used on Admin Portal → Student Management
(`StudentList.tsx`), which the prior issue 028 fix established as the
icon + label + colored, non-filled-border convention (one color per
action).

## Root cause

`ServiceRecords.tsx`/`EventProposals.tsx` predate the `ACTION_COLORS`/
`ACTION_BTN_BLUE`-style convention introduced for the Admin Portal
(`StudentList.tsx` issue 028, and independently `SystemAdmin.tsx`/
`RoleManagement.tsx`'s `ACTION_BTN_BLUE`/`ACTION_BTN_RED`) — the same
"each list grew its own ad hoc button styling independently" gap those
fixes closed for the admin side, just never carried over to the Student
Portal's own action buttons.

## Fix

Applied the same shape to both files' row actions — icon + label,
bordered, colored per action, never filled:

- `ServiceRecords.tsx` (desktop table + mobile card): new `ACTION_COLORS`
  map (`blue`/`green`/`red`/`amber`/`purple`). Edit → blue (`Pencil`);
  Delete → red (`Trash2`); Submit/Resubmit → green (`Send`, previously
  filled `bg-primary`); Revert → amber (`RotateCcw`, new icon); Appeal →
  purple (`Gavel`, new icon, distinct from the other four since it's
  neither an edit nor purely destructive).
- `EventProposals.tsx` (desktop table): new `ACTION_COLORS` map
  (`blue`/`cyan`). Edit → blue (existing `Edit` icon); Copy → cyan
  (existing `Copy` icon, matching `StudentList.tsx`'s cyan "read-only
  sub-view" convention for a non-destructive, non-edit action).

No backend changes.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass against both pages as `student001`, confirming
  icon + colored + bordered (not filled) buttons render for every row
  action, no console errors.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx`: `Pencil`/
`RotateCcw`/`Gavel` icon imports added; new `ACTION_COLORS` constant; both
the desktop table and mobile card action-button blocks updated.
`frontend/src/app/components/student/EventProposals.tsx`: new
`ACTION_COLORS` constant; desktop table action-button block updated.
Frontend rebuilt and redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming: Service Records' desktop table
shows Edit/Delete/Submit as blue/red/green bordered icon+text buttons on
draft records and Revert as amber on a submitted record; Propose Volunteer
Activity's desktop table shows Edit as blue and Copy as cyan. No console
errors. This student's seeded data had no `returned`/`rejected` service
records at verification time, so the green Resubmit and purple Appeal
buttons were confirmed by code review and the shared `ACTION_COLORS`
constant rather than an on-screen click-through — a later session with a
`returned`/`rejected` record present should confirm those two visually.*
