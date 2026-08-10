# 009 — Create User form: selecting "Admin" silently also selected "Teacher"

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

Found while browser-verifying issues 002/003. On a fresh "Create User" dialog
(System Admin → Users & Roles), clicking the "Admin" role toggle button once caused
both "Admin" **and** "Teacher" to render as selected. Reproduced twice on two
independently-freshly-opened dialogs. This forced the (correct, working) required
Grade Bonding validation to trigger for what should have been a pure-admin account —
admins are meant to get an informational "full access by design, no grade needed"
note instead (`admin.system.scopeGradesAdminNote`), which only renders when `admin`
is selected *without* `teacher`.

## Root cause

`SystemAdmin.tsx`'s `emptyForm()` (the initial state for a brand-new "create" dialog)
seeded `roles: ['teacher']` by default — every other field in the same object starts
genuinely empty (`name: ''`, `staffId: ''`, `scopeGradeIds: []`, etc.), but `roles`
did not. `toggleRole` itself is a correct, generic add/remove toggle
(`form.roles.includes(role) ? filter out : append`) — not buggy in isolation. Clicking
"Admin" on the pre-seeded form simply appended `'admin'` onto the already-present
`['teacher']`, producing `['teacher', 'admin']`, and both then rendered as selected
via `form.roles.includes(role)`.

The "admin defaults to all grades" part of the original bug report turned out to
already be correctly implemented — via the informational note, not pre-checked
checkboxes, since admins aren't scope-limited server-side at all (only teachers go
through `DataScopeResolver`) — so literally pre-checking all 6 grade boxes for an
admin would have been misleading busywork with no backing enforcement. That design
was fine; only the stray default role was the actual defect.

## Fix

`emptyForm()`'s `roles` now starts as `[]`, matching every other field's genuinely-
empty default. The existing `handleSave` validation (`form.roles.length === 0` →
error) already requires an explicit choice, same as it does for name/email/password,
so this doesn't weaken anything — it just removes the one field that was silently
pre-filled.

## Test plan

- Live: opened a fresh Create User dialog, clicked "Admin" once — only "Admin" is
  selected, Grade Bonding section is replaced by the "full access by design" note
  (no forced grade selection). Clicked "Teacher" instead on a fresh dialog — only
  "Teacher" selected, Grade Bonding section appears and correctly requires ≥1 grade.
- `pnpm build` clean.

## Resolution

- `frontend/src/app/components/admin/SystemAdmin.tsx:215`: `roles: ['teacher']` →
  `roles: []`.
- Deployed to `https://tb.example.com/`; live-verified via Playwright.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`, found during a browser
verification pass of issues 002/003 (which the prior session, lacking a browser
tool, could only verify at the API level).*
