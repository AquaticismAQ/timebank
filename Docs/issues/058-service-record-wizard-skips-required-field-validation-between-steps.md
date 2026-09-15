# 058 — New Service Record wizard let students skip required fields between steps, surfacing as an unrecoverable submit-time error

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user: creating a new service record with "School
Project" as the source and a local evidence file attached failed at the
final step (just before submit) with the error "A project-sourced record
needs a projectId."

## Root cause

`ServiceRecords.tsx`'s "New Service Record" flow is a 4-step wizard
(`source` → `serviceInfo` → `evidence` → `confirmSubmit`) inside a single
`<form>`, with each step's fields conditionally rendered only while
`step === N`. The "Next" button is `type="button"` and just calls
`setStep(s => s + 1)` — it never triggers the browser's native
`required`-field validation. Native validation only runs on the final
`type="submit"` click, and by then every earlier step's fields (including
the `required` project-select dropdown shown only at step 1 when
`sourceType === 'project'`) have been unmounted and removed from the DOM,
so the browser has nothing left to validate. A student could click through
all four steps without ever picking a project and reach Submit with
`formData.projectId === ''`, which the frontend dutifully sent, and only
the backend's `ServiceRecordServiceImpl` caught it — as a bare toast the
student had no way to act on, since the field/UI needed to fix it
(the project dropdown) wasn't visible at the confirm step.

This is a general property of the wizard, not specific to `projectId`: every
other `required` field in steps 0–2 (`serviceDate`, `startTime`, `endTime`,
`location`, `description`, supervisor fields) has the same gap — none of
them are actually enforced before submit, they just weren't hit first
because minimally filling out earlier steps' visible text/date/time inputs
is closer to the natural flow than remembering to open a dropdown with no
default selection.

## Fix

Gave the `<form>` a `ref` and changed the "Next" button's handler to call
the browser's own `formRef.current.reportValidity()` before advancing
(`if (...) setStep(s => s + 1)`). Because only the current step's fields
are mounted in the DOM at that moment, this validates exactly that step's
`required` inputs — no per-step validation logic needs to be hand-written
or kept in sync with the fields above. Uses the existing native validation
bubble UI already implied by every `required` attribute already present in
this form.

Not in scope for this fix (flagged for a separate look, not blocking): the
"Travel time reason" textarea (step 1) is labeled with a required-looking
`*` but has no actual `required` attribute — a separate, smaller labeling
bug, unrelated to the wizard-navigation gap above.

## Test plan

- `npx tsc --noEmit` / `pnpm build` clean.
- Live Playwright pass against the deployed server as `student001`:
  reproduce the original failure (pick "School Project" as source, skip the
  project dropdown, click "Next") to confirm it's now blocked on step 1 by
  native validation, then complete the flow correctly (pick a confirmed
  project, fill the remaining required fields, attach an evidence file) and
  confirm the record is created and submitted successfully.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx`: added a
`formRef` (`useRef<HTMLFormElement>`), attached it to the wizard's `<form>`,
and changed the "Next" button from unconditionally calling
`setStep(s => s + 1)` to `if (formRef.current?.reportValidity()) setStep(s => s + 1)`.
Frontend rebuilt and redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming: (1) reproduced the original bug
— picked "School Project," advanced to the Service Info step, clicked
"Next" without picking a project — and confirmed it no longer advances
(the project dropdown gets focused for native browser validation instead);
(2) completed the flow correctly — selected "School Library Organization
and Classification" as the project, filled service date/time/description,
uploaded the exact evidence file path the user reported
(`test-evidence/1.jpg`) — and confirmed the record was created and
submitted without error (`TB-2025-2026-10-2024001-019`, "Submitted",
2h, correct category/date), with a "Service record submitted, pending
review" toast. Did not separately re-verify the other `required` fields
named in Root Cause (`serviceDate`/`startTime`/`endTime`/`location`/
`description`/supervisor fields) with a dedicated skip-and-click-through
attempt for each — the fix is a single shared code path
(`reportValidity()` on the current step), not a per-field rule, so this is
a reasoned inference from the one field proven above, not independently
click-proven for every other field.*
