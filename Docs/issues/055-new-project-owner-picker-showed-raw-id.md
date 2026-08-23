# 055 — New Project's Owner picker displayed the raw user ID instead of the selected name

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user: Admin Portal → Project Management → New
Project, the Owner field lets you search and select a teacher/admin
correctly, but after selecting someone, the picker displayed the raw
(unreadable) user ID instead of that person's name.

## Root cause

`ProjectManagement.tsx`'s "New Project" form (`newForm`) only ever stored
`ownerId`, never a matching `ownerName`. The `SearchPicker`'s `selected`
prop was built as `[{ id: newForm.ownerId, label: newForm.ownerId }]` —
using the ID as both the id *and* the display label, since there was no
`ownerName` field to read from. The sibling "Edit Project" modal
(`EditModal`) never had this bug: its `EditForm` already carries a real
`ownerName` field (populated from the existing project's `ownerName` via
`toForm`), so its picker correctly showed `label={form.ownerName}`.

## Fix

Added `ownerName: ''` to `newForm`'s initial state, and updated the picker's
`onChange` to store both `opts[0].id` and `opts[0].label` (matching
`EditModal`'s existing pattern exactly) instead of only `ownerId`. The
picker's `selected` now reads `label={newForm.ownerName}`. `ownerName` is
display-only — `buildNewProjectPayload()` still only sends `ownerId`,
matching what the backend actually expects.

## Test plan

- `pnpm build` (clean).
- Live Playwright pass against the deployed server as `admin001`: opened
  New Project, searched "Louis" in the Owner picker, selected "Louis Yu",
  confirmed the field then displays "Louis Yu" (not a raw ID).

## Resolution

`frontend/src/app/components/admin/ProjectManagement.tsx`: `newForm`
state gains `ownerName`; the New Project Owner `SearchPicker`'s `selected`/
`onChange` updated to track and display it. Frontend rebuilt and
redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `admin001`/Wei Chen: New Project modal's Owner field
searched "Louis", selected "Louis Yu" from the suggestion list, field
then displayed "Louis Yu" with a clear (×) button — no raw ID visible.
No console errors.*
