# 028 — Student Management's action buttons don't match the new icon+colored-border row-action style

Status: **Fixed and live-verified (2026-08-19)**

## Symptom

Reported directly by the user, immediately after the Users/Roles/Templates/
Rule Settings tabs were unified to a consistent row-action button style
(icon + label + colored, non-filled border, one color per action —
blue for edit/view, red for delete): Student Management (`StudentList.tsx`)
was not included in that pass, even though it was the page that style was
originally modeled on ("like the Student Management page"). Its row
actions (Edit / Remind / Records) still use a single muted-gray bordered
style, with only Edit carrying an icon and no action-specific color at
all — now the one page in the admin portal that doesn't match the
convention it inspired.

## Root cause

`StudentList.tsx`'s row-action buttons predate the `ACTION_BTN_BLUE`/
`ACTION_BTN_RED`/`ACTION_BTN_RED_DISABLED` constants introduced in
`SystemAdmin.tsx`/`RoleManagement.tsx` for the Users/Roles/Templates/Rule
Settings fix — the same "each admin list grew its own ad hoc button
styling independently, with no shared convention" gap that fix closed for
those four tabs, just not yet applied here.

## Fix

Applied the same shape to `StudentList.tsx`'s row actions (desktop table
and mobile card) — icon + label, bordered, colored per action, never
filled:
- Edit → blue, `Pencil` icon.
- Remind → amber, `Bell` icon (an informational, non-mutating action).
- Records → cyan, `FileText` icon (a read-only sub-view, distinct from
  both edit-blue and the destructive-red used elsewhere in the admin
  portal, since Records isn't an edit and isn't destructive).

Implementation: a new `ACTION_COLORS` map (`blue`/`amber`/`cyan` border/
text/hover-background fragments) composed with layout-specific base
classes at each of the two call sites (desktop table cell's compact
`px-2 py-1`; mobile card's equal-width `flex-1 py-2 justify-center`),
rather than one fixed className per color — the two layouts need
different padding/flex shape, matching how `SystemAdmin.tsx`/
`RoleManagement.tsx`'s `ACTION_BTN_BLUE`/etc. constants were composed for
the earlier Users/Roles/Rule Settings pass. No backend changes.

## Test plan

- `pnpm build`.
- Live Playwright pass against Student Management (desktop table + mobile
  card), confirming icon + colored + bordered (not filled) buttons render
  for every row action, no console errors.

## Resolution

`frontend/src/app/components/admin/StudentList.tsx`: `Bell`/`FileText`
icon imports added; new `ACTION_COLORS` constant; both the desktop table
and mobile card action-button blocks updated. Frontend rebuilt and
redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-19 against the real deployed server via Playwright
MCP, logged in as `admin001`/`wei.chen@example.edu.cn`: desktop table
shows 编辑/提醒/记录 as blue/amber/cyan bordered icon+text buttons; at a
390px mobile viewport, expanding a student card shows the same three
buttons as an equal-width row with the same coloring. No console errors
on either breakpoint.*
