# 013 — Editing/resubmitting a record with an overnight (crosses-midnight) time range corrupts the end time

Status: **Proposed fix** (found 2026-08-10, not yet implemented)

## Symptom

A service record whose `startAt`/`endAt` span midnight (e.g. start 23:00 on
2026-07-10, end 01:00 on 2026-07-11 — a legitimate 2-hour overnight service
window) cannot be safely edited or resubmitted through the student "New
Record"/"Edit Returned Record" form. Opening such a record for resubmit shows
Start Time 23:00 / End Time 01:00 under a single "Service Date" of 2026-07-10.
If the student makes any change and saves (draft or submit), the end time is
silently recombined with the *start* date instead of the original end date,
producing an `endAt` that is earlier than `startAt` on the same calendar day.
Draft-save accepts this (no range validation at save time), but submit
correctly rejects it: `422 VALIDATION_FAILED` with `INVALID_RANGE` /
`INVALID_DURATION` on `endAt`/`durationMinutes`. Discovered live: this exact
sequence corrupted the seeded `returned` record "Example District A Community"
belonging to `student001`, temporarily leaving its stored `endAt` before its
`startAt` until fixed forward with a manually-corrected same-day time range.

## Root cause

`ServiceRecords.tsx`'s form state models a service window as one
`serviceDate` plus independent `startTime`/`endTime` strings — there is no
field for "end date", and `openResubmit()` populates the form from
`isoToLocalDateTime(full.endAt)` but only keeps `end.time`, discarding
`end.date`:

```ts
const start = isoToLocalDateTime(full.startAt);
const end = isoToLocalDateTime(full.endAt);
setFormData({
  ...
  serviceDate: full.serviceDate || start.date,
  startTime: start.time,
  endTime: end.time,
  ...
```

`buildRecordPayload()` then reconstructs both timestamps from the single
`serviceDate`:

```ts
const startTime = formData.startTime ? localDateTimeToISO(formData.serviceDate, formData.startTime) : undefined;
const endTime = formData.endTime ? localDateTimeToISO(formData.serviceDate, formData.endTime) : undefined;
```

If the original end date differed from the start date, this information is
lost the moment the form is populated — there was never a UI affordance (e.g.
an "ends the next day" toggle) to represent it. This logic is unchanged from
before the Service Record Form Optimization rewrite (issue
`03-ServiceRecordFormOptimization.md`) — confirmed identical in `HEAD` prior
to that work — so it is a pre-existing defect, not introduced by it; it was
only surfaced now because live-verifying the new supervisor-kind/PROJECT-SELF
split exercised the resubmit path against a seed record that happens to span
midnight.

## Proposed fix

Add an explicit "ends the next day" checkbox (or a second date field) to the
service-info step, defaulting to checked when `endTime < startTime` on load
from an existing record, and have `buildRecordPayload()` add one day to the
end date when set. This is a product/UX decision (checkbox vs. explicit end
date input) best made alongside the rest of the form's design, not bundled
into this write-up.

## Test plan (once fixed)

- Live: as `student001`, open the "Example District A Community" returned record
  (or any record with a genuinely overnight window), confirm the end date is
  preserved on load, and confirm a resubmit with an unmodified overnight range
  succeeds without an `INVALID_RANGE` error.
- Regression: a same-day range still works unchanged.

## Resolution

Not yet implemented — filed for a future session. The one live record this
corrupted (`rec_3t67kqy4q6pqggapzmrtm3qhwr`, "Example District A Community") was
fixed forward during discovery: its time range was manually corrected to a
valid same-day window (23:00–23:59, 59 minutes) and successfully resubmitted,
so no record is left in a broken state — but its service window no longer
matches the original overnight seed data exactly.

---
*Found live 2026-08-10 against `https://tb.example.com/` via browser MCP while
verifying issue `03-ServiceRecordFormOptimization.md`'s implementation
(resubmitting a locked-source historical record). Not fixed this session —
proposed fix only, since the correct UX (checkbox vs. explicit end-date field)
is a product decision out of scope for that feature's implementation pass.*
