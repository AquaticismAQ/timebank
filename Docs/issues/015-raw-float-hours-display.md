# 015 — Duration/hours shown as raw floating-point division instead of a rounded value

Status: **Fixed and live-verified (2026-08-10)**

## Symptom

Several screens across both portals displayed a service duration or
completed-hours figure as a raw JavaScript floating-point division result
instead of a human-readable number — e.g. a 59-minute record showed
`0.9833333333333333h` (student Service Records list) and a 100-minute
project showed `1.6666666666666667 hours` (student Project Center list
card). Reported directly by the user, not found via this session's own
browser testing.

## Root cause

`frontend/src/app/lib/format.ts` already has a `minutesToHours()` helper
built exactly for this ("240 -> 4", "90 -> 1.5", trims trailing zeros) and
carries its own comment: *"Convert in exactly these two places — grep for
direct `/ 100` or `/ 60` elsewhere before adding one."* That discipline had
been violated repeatedly since — 16+ call sites across both portals did
`somethingMinutes / 60` directly inline instead of calling the helper,
which is invisible for any value that happens to be an exact multiple of 60
(most seed data) but produces a full-precision `Number.toString()` the
moment it isn't (real minute values from actual start/end times, e.g. a
59-minute or 100-minute record).

## Fix

Replaced every raw `minutes / 60` **display** occurrence with
`minutesToHours(minutes)`:

- `components/student/ProjectCenter.tsx` — project list card duration.
- `components/student/ProgressPage.tsx` — added `*Label` string variants
  (`completedHoursLabel`, `annualRequiredHoursLabel`, etc.) alongside the
  existing raw numeric variables, since the numeric ones are still needed
  for `CircleProgress`/`ProgressBar` percentage math and
  `RECOGNITION_LEVELS` threshold comparisons — only the text-interpolation
  sites were switched to the formatted strings. The "distance to next
  level" line (`nextLevel.minHours - completedHours`) was changed to
  `minutesToHours(nextLevel.minHours * 60 - progress.completedMinutes)` so
  the subtraction happens in whole minutes, not fractional hours.
- `components/student/StudentHome.tsx` — recent-records mobile card/table,
  active-project card.
- `components/student/ServiceRecords.tsx` — desktop table + mobile card
  duration column.
- `components/admin/AuditCenter.tsx` — desktop table, mobile card, and
  expanded-detail "reported hours" line.
- `components/admin/ProjectManagement.tsx` — project list card duration
  display. The two `<input type="number">` recognized-hours fields (edit
  modal and new-project form) can't take a formatted string, so those got
  `Math.round((minutes / 60) * 100) / 100` instead — same underlying
  problem (unrounded division), a 2-decimal cap rather than the trim-
  trailing-zeros string formatter.
- `components/admin/AdminDashboard.tsx` — recent-reviews table/card,
  active-projects card.
- `components/admin/StudentList.tsx` — edit-form completed-hours pre-fill
  and the audit-log change-summary message, same `Math.round(...*100)/100`
  treatment (a numeric edit-form field, not display text).
- `components/admin/AccountManagement.tsx` — the "earned coins" line was
  doing `(completedMinutes / 60) * 2` inline (an existing, separate
  hardcoded-2-coins-per-hour approximation, not the real rules-engine
  calculation — left as is, out of scope here) and suffered the identical
  unrounded-division problem; switched to
  `formatCoins(Math.round(minutes / 60 * 2 * 100))` to reuse the existing
  centi-coins trim-trailing-zeros formatter instead of inventing a third
  rounding convention.
- `components/student/EventProposals.tsx` — the two duration-hours form
  fields pre-filled from a template's minutes got the same
  `Math.round(...*100)/100` treatment as the other numeric inputs above.

Deliberately left untouched: `client.ts:827`'s `Math.round(p.totalMinutes /
60)` (already rounds) and `ProgressPage.tsx`'s raw numeric `*Hours`
variables where only used for math, not display.

## Test plan

- Live: the two reported cases — a 59-minute service record ("Example District C
  District Community") in Service Records, and a 100-minute project ("No
  Registrants Auto-Archive Test") in Project Center — both show a rounded
  value instead of a repeating decimal.
- `tsc --noEmit` clean (no type errors from the `Math.round`/string-vs-
  number changes across the numeric `<input>` fields).

## Resolution

- Ten frontend files updated (see Fix above), all via `minutesToHours()` or
  a consistent `Math.round(... * 100) / 100` for the handful of numeric
  `<input>` fields that can't hold a formatted string.
- Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`.

---
*Live-verified 2026-08-10 against `https://tb.example.com/` via browser MCP,
logged in as `student001`: Service Records list now shows "1h" (was
"0.9833333333333333h") for the 59-minute "Example District A Community"
record; Project Center now shows "1.7 小时" (was "1.6666666666666667
hours") for the 100-minute "No Registrants Auto-Archive Test" project. The
remaining fixed call sites (admin Audit Center, Admin Dashboard, Project
Management, Student List, Account Management, student Progress/Home pages)
were not separately click-verified this session — all use the same
already-proven `minutesToHours()` helper or the same rounding pattern, and
`tsc --noEmit` confirms no type regressions, but say so explicitly per this
repo's convention: a later session should spot-check these if a fresh
odd-minute record becomes available in each of those screens.*
