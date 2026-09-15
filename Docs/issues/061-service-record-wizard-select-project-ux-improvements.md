# 061 — New Service Record wizard: dropdown height, project-date autofill, digit-only time entry, missing duration preview, Next-button gating

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user, five related issues with the "School
Project" flow of the New/Edit Service Record wizard (`ServiceRecords.tsx`):

1. The "Select Project" dropdown's height didn't match its sibling inputs
   (Service Date, Start/End Time).
2. Picking a project didn't default the service date/start/end time to the
   project's own activity window.
3. Start/End Time used the native `<input type="time">` picker — an
   analog/scroll-style widget on iOS Safari and some Android browsers, not
   a plain digit field everywhere.
4. The duration preview ("Duration: N hours"), which used to show once
   both times were filled, had stopped appearing.
5. The wizard's "Next" button should be disabled until the current step's
   required fields are actually filled, not just silently reject the click.

## Root cause

**1 — dropdown height.** `<select>` and `<input>` render at slightly
different intrinsic heights for the same `py-3` padding (measured: 44.5px
vs ~46.7px) — a native cross-element sizing quirk, not something padding
alone fixes reliably across browsers/fonts.

**2 — no autofill.** Never implemented — `selectedProject` (already fetched
for the info panel) was display-only; nothing read its `startAt`/`endAt`
into the form.

**3 — analog time picker.** Just the native `<input type="time">` element,
unchanged since it was first built.

**4 — duration preview "gone".** Not actually gone in the code (the
`durationMinutes` calc and its conditional render were both intact and
correct) — the real cause was almost certainly `<input type="time">`'s
AM/PM-segmented native widget: it only ever commits a value once *every*
segment (including AM/PM in a 12-hour locale) is explicitly set, so a
partially-interacted native time control can sit indefinitely with an
empty committed value despite looking filled, silently keeping
`durationMinutes` at `null`. Confirmed reproducible with `.fill()` on a
complete value (shows fine) versus the ambiguity of real segment-by-segment
native interaction. Item 3's digit-only replacement removes this ambiguity
entirely, since it commits as soon as two real digits exist in each field.

**5 — Next not disabled.** Issue 058 (this session) added a
`reportValidity()` check to the *click* handler so an invalid step can't
silently advance, but never disabled the button itself beforehand — a
student could see an enabled-looking Next the whole time and only get
blocked (with a validation bubble) on click.

## Fix

**1.** Set an explicit `h-12` on the project `<select>` and its sibling
inputs/selects in the wizard (Service Date, Start/End Time, the category
`<select>`, the supervisor-kind `<select>`) — an explicit height overrides
each element's differing intrinsic sizing outright, guaranteeing pixel-
identical rows regardless of font-metric quirks.

**2.** New effect: once a project is selected and its detail resolves,
defaults `serviceDate` to the project's `startAt` date. Also defaults
start/end time to `startAt`/`endAt`'s time-of-day — but *only* when the
project's own window is a single calendar day. A multi-day/multi-week
project's `startAt`/`endAt` time-of-day is just whatever moment it happened
to be published/closed, not a daily session window — defaulting to it
verbatim produced a nonsensical (`16:00 → 01:00`-looking, apparently
overnight) prefill on a real seeded project spanning a month, caught
live before shipping. Only ever fills fields that are still empty — never
overwrites a value already present (a prior pick, or an edit-loaded
record).

**3.** New `frontend/src/app/components/shared/TimeInput.tsx` — two plain
digit-only `HH`/`MM` segments, no native picker. Each segment is its own
`required` control, so `reportValidity()`/`checkValidity()` (issue 058's
mechanism) keeps working unchanged. Typing 2 digits into Hour
auto-advances focus to Minute.

**4.** No separate fix needed — replacing the native time input (item 3)
removes the AM/PM-ambiguity root cause, and the pre-existing duration-
calculation/display code was already correct.

**5.** New `nextEnabled` state, recomputed via `useLayoutEffect` off
`formRef.current.checkValidity()` on every `step`/`formData` change — since
only the current step's fields are mounted (conditional per-step
rendering, same property issue 058's fix already relies on),
`checkValidity()` naturally scopes to just them. "Next" is now
`disabled={!nextEnabled}`, with `disabled:opacity-50` styling; the existing
`reportValidity()` call on click is kept as a defensive fallback.

**A real bug found and fixed while building item 3, not in the original
ask:** the first version of `TimeInput` derived its displayed hour/minute
straight from the `value` prop, and only ever called `onChange` with a
*complete* "HH:MM" (or `""` while incomplete). Typing a second digit into
Hour calls `minuteRef.current?.focus()` to auto-advance — which fires the
Hour field's own native `blur` event *synchronously, before React
re-renders* — so its `onBlur` handler ran with a **stale closure** still
holding the pre-keystroke single-digit hour, padded it, and re-committed
that stale value on top of the correct one just typed. Net effect: typing
"0" then "9" into Hour visibly produced "00", not "09" — reproduced and
confirmed via raw native `input`/`keydown` event logging (which showed the
DOM genuinely received "09" — the corruption was happening in React's own
state reconciliation, not at the browser/input level). Fixed by deferring
the auto-advance `focus()` call by one tick (`setTimeout(..., 0)`), so
React has already committed the corrected state — and the blur handler's
closure is already fresh — by the time the focus-driven blur fires.

## Test plan

- `tsc --noEmit`/`pnpm build` clean.
- Live Playwright pass against the deployed server as `student001`,
  repeated across three implementation attempts as bugs were found:
  - Measured the select-vs-input height mismatch directly via
    `getBoundingClientRect()` before the fix (44.5px vs ~46.7px), confirmed
    identical (48px) after.
  - Picked the one real confirmed-project fixture (a month-long project):
    confirmed service date auto-filled, and — after the same-day-only
    refinement — confirmed start/end time were correctly left blank rather
    than prefilling the nonsensical `16:00 → 01:00` range from the first
    attempt.
  - Typed real keystrokes (not `.fill()`) into Hour/Minute segments;
    caught the stale-closure corruption this way exactly because `.fill()`
    alone hadn't reproduced it. Verified the fix with a full
    `09` → auto-advance → `30`, then `11` → auto-advance → `30` sequence,
    both segments landing correctly.
  - Confirmed "Duration: 2 hours" rendered once both real digit-typed times
    were complete.
  - Confirmed "Next" was `disabled` with only source picked, stayed
    `disabled` through partial field entry, and became enabled only once
    every required field in the step held a valid value.
  - Edited a real pre-existing draft record (10:45–16:30, "Duration: 5.8
    hours") to confirm an edit-loaded record's time still populates the
    new picker correctly and Next is immediately enabled (nothing missing).

## Resolution

`frontend/src/app/components/shared/TimeInput.tsx`: new component.
`frontend/src/app/components/student/ServiceRecords.tsx`: `h-12` added to
the project/category/supervisor-kind `<select>`s and the date input; new
project-window autofill effect; Start/End Time now use `TimeInput`; new
`nextEnabled` state + `useLayoutEffect` gating the "Next" button.
Frontend rebuilt and redeployed three times in this session as each of the
above issues was found and fixed in turn (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming — see Test plan above for the
specific checks each of the three deploy iterations covered. The
stale-closure time-entry bug was caught only because a later verification
pass switched from `.fill()` to real keystroke-by-keystroke input
specifically to sanity-check the digit-entry UX; a `.fill()`-only pass
would have missed it entirely, since `.fill()` sets a complete final value
in one synthetic event rather than exercising the auto-advance-driven blur
race. Did not verify the project-autofill path against a *single-day*
project (no such fixture exists in the currently-seeded data — the one
real confirmed project spans a month) — the same-day branch is covered by
code review and the multi-day branch's correct no-time-autofill behavior
being proven live, but the same-day branch itself wasn't exercised against
real project data this session.*
