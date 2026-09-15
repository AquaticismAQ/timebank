# 063 — Duration preview missing after project autofill until the student manually checks "Ends the next day"

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user: in the Service Info step, the calculated
duration line (e.g. "Duration: 9 hours") doesn't appear when first landing
on the page after picking a project — it only shows up once the student
"updates the time."

## Root cause

Issue 062's project-window autofill effect sets `serviceDate`/`startTime`/
`endTime` from the selected project's `startAt`/`endAt`, but never touches
`endsNextDay`. The one real confirmed-project fixture's window is
`2026-06-08 16:00` → `2026-07-09 01:00` (local) — its end *time-of-day*
(`01:00`) is numerically earlier than its start time-of-day (`16:00`).
The duration calculation (`durationMinutes`) treats start/end as the same
calendar day unless `endsNextDay` is checked, so it computed a *negative*
duration — which the existing `durationMinutes > 0` guard correctly (by
design) hides rather than showing a nonsensical negative number. The
result: the preview silently never appeared after autofill, with nothing
telling the student that an unrelated-looking checkbox ("Ends the next
day") was the reason. Manually checking that box — which is what "update
the time" turned out to mean in practice — flips the calculation positive
and the preview appears.

This is a direct, previously-unhandled consequence of issue 062's own
change: removing issue 061's same-day guard means the autofill now always
sets a start/end pair from the project's raw timestamps, including exactly
this overnight-crossing case, without ever setting the one additional
field (`endsNextDay`) needed to interpret that pair correctly.

## Fix

The autofill effect now also sets `endsNextDay: start.date !== end.date`
— i.e., whenever the project's own start and end fall on different local
calendar dates, the checkbox is set to match automatically, so the
duration calculation reads the same start/end pair the same way a student
manually checking the box would.

## Test plan

- `tsc --noEmit`/`pnpm build` clean.
- Live Playwright pass against the deployed server as `student001`:
  reproduced the bug first against the previous deploy (project selected,
  times auto-filled to 16:00/01:00, "Ends the next day" left unchecked, no
  Duration line present); confirmed manually checking the box made
  "Duration: 9 hours" appear, confirming the root cause precisely.
  Redeployed with the fix, repeated the exact same steps in a fresh
  "New Record" flow: "Ends the next day" is now checked automatically the
  moment the project is picked, and "Duration: 9 hours" renders
  immediately, no further interaction needed.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx`: the
project-window autofill effect (from issue 062) now also sets
`endsNextDay` based on whether the project's local start/end dates
differ. Frontend rebuilt and redeployed.

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming. One verification-process note
worth recording: the first post-deploy check against the fix appeared to
fail (checkbox still unchecked) — turned out to be a stale cached JS
bundle in the Playwright browser session (confirmed via a direct `curl`
against the server, which was already serving the new bundle, versus the
browser's `document.scripts` still pointing at the previous build's
filename); a hard `page.reload()` resolved it, after which the fix
verified correctly. Not a product bug, but worth noting since it could
recur in future sessions using this same long-lived browser session.*
