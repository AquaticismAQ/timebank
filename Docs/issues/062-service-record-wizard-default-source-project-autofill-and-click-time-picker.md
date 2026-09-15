# 062 — Service Record wizard: default to School Project, always follow project time, replace time picker with a click-to-pick digital-clock-style popover

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Direct follow-up requests from the user on issue 061's work:

1. "School Project" should be the default selected source when creating a
   new service record (was "Self-Initiated").
2. After picking a project, start/end time should always update to follow
   the project — issue 061 had deliberately *skipped* the time autofill for
   a multi-day project (to avoid a nonsensical prefill); the user wants it
   to always follow regardless.
3. The digit-only `HH`/`MM` text-box time input from issue 061 "doesn't
   work" — replace it with a real click-to-pick time picker, similar in
   spirit to MUI's `TimePicker` (not a literal port, "similar style is
   ok, it's just an example").

## Root cause / what "doesn't work" turned out to mean

1. Simply never set — `EMPTY_FORM.sourceType` defaulted to `'self'`.

2. Issue 061's autofill effect intentionally only filled start/end time
   when the project's own `startAt`/`endAt` fell on the same calendar day,
   guarding against the one real seeded project (a month-long window)
   producing a confusing `16:00 → 01:00`-looking prefill. The user's
   explicit ask here overrides that caution: always follow the project,
   every time the pick changes — the same-day guard is removed.

3. Not something newly broken by issue 061 — a genuine, separate UX
   dissatisfaction with the text-box approach itself (imprecise
   two-segment typing vs. a real picker). Replaced with a proper picker
   component instead of iterating further on the text-box design.

## Fix

**1.** `EMPTY_FORM.sourceType` now defaults to `'project'`.

**2.** The project-window autofill effect (issue 061) had its same-day
guard removed — it now always sets `serviceDate`/`startTime`/`endTime`
from the selected project's `startAt`/`endAt`, every time the selected
project changes (not just on first pick), still skipped entirely while
`editing` an existing record so its real saved values aren't clobbered.

**3.** Rewrote `frontend/src/app/components/shared/TimeInput.tsx` as a
Popover-based picker (Radix `Popover` + two scrollable digit columns, one
for hour 00–23 and one for minute 00–59 — the same "digital clock" list
shape MUI's picker uses, not an analog clock face) instead of two typed
text segments. Clicking an hour or minute commits immediately (defaulting
the other half to `00` if not yet picked), auto-scrolls to the currently
selected values when opened, and has a "Done" button to close explicitly.

Since the trigger is now a `<button>`, not a native `<input>`, it can no
longer participate in the wizard's `reportValidity()`/`checkValidity()`
gating (issues 058/061) on its own. Added a visually-hidden `required`
proxy `<input>` mirroring the picked value to keep that working — **and
found a real bug while doing it**: the proxy was initially `readOnly`,
and a `readonly` control is "immutable" per the HTML spec, which makes it
*always* valid regardless of `required` — silently defeating the entire
point of the proxy (confirmed live: "Next" stayed enabled with both time
fields completely empty). Fixed by dropping `readOnly` in favor of a
no-op `onChange` (satisfies React's controlled-input contract without
the spec's readonly-exemption; the picker, not the hidden input, is the
only real write path).

## Test plan

- `tsc --noEmit`/`pnpm build`/i18n-parity clean (new `common.done` key).
- Live Playwright pass against the deployed server as `student001`, two
  iterations (the hidden-proxy bug was caught in the first, fixed, then
  re-verified):
  - Confirmed "School Project" renders highlighted/selected by default on
    a fresh "New Record" open, via screenshot.
  - Picked the one real confirmed project (still the month-long fixture):
    confirmed start/end time now auto-fill to its raw `startAt`/`endAt`
    time-of-day unconditionally (`16:00`/`01:00`), matching the new
    explicit ask.
  - Opened the new picker, clicked hour "09" then minute "15" for Start
    Time, confirmed the trigger button updates to "09:15" and the popover
    stays open for further adjustment; repeated for End Time, clicked
    "Done", confirmed the popover closes and "Duration: 2 hours" renders.
  - **Caught the readonly-proxy bug directly**: filled every other
    required field on a Self-Initiated record but deliberately left both
    time pickers untouched — "Next" was (incorrectly) enabled. Fixed,
    redeployed, repeated the exact same steps — "Next" correctly stayed
    disabled with time empty, then enabled only after both Start and End
    Time were actually picked.
  - Edited a real pre-existing draft (10:45–16:30, "Duration: 5.8 hours")
    to confirm an edit-loaded record's time still populates the rebuilt
    picker correctly and "Next" is immediately enabled.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx`:
`EMPTY_FORM.sourceType` defaults to `'project'`; the project-window
autofill effect's same-day guard removed. `frontend/src/app/components/shared/TimeInput.tsx`:
rewritten as a Popover-based two-column click picker with a `required`
hidden proxy input (non-`readOnly`). `frontend/src/i18n/en.json`/`zh.json`:
new `common.done` key. Frontend rebuilt and redeployed twice in this
session (once per fix, since the hidden-proxy bug was caught after the
first deploy).

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming — see Test plan above. The
readonly-proxy validity bug was caught only because a dedicated
skip-the-time-fields-and-check-Next pass was run specifically to sanity-
check the new picker's validation wiring — a pass that only checked the
"happy path" (pick a time, confirm Next enables) would have missed it
entirely, same lesson as issue 061's stale-closure bug. Did not verify the
picker's scroll-to-selected-value behavior when the trigger is deep in a
long-scrolled parent viewport — only tested at the top of the form,
in-view without additional page scrolling.*
