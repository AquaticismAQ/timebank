# 013 — Editing/resubmitting a record with an overnight (crosses-midnight) time range corrupts the end time

Status: **Fixed and live-verified (2026-08-10)**

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

## Fix

Added an explicit "Ends the next day" checkbox to the service-info step
(`student.records.endsNextDay`, both locales). New `formData.endsNextDay`
boolean: `openResubmit()` now sets it to `end.date !== start.date` when
pre-filling from an existing record (so a genuinely overnight record loads
with the box already checked); `buildRecordPayload()` adds one calendar day
to the end date via a new `addOneDay()` helper (`lib/datetime.ts`) before
building `endAt`, instead of always reusing `serviceDate`. The live duration
preview (`durationMinutes`) and the confirm-step summary were both updated to
account for the extra day too, so the preview matches what actually gets
submitted.

Files: `frontend/src/app/lib/datetime.ts` (new `addOneDay`),
`frontend/src/app/components/student/ServiceRecords.tsx` (`EMPTY_FORM`,
`openResubmit`, `buildRecordPayload`, `durationMinutes`, the new checkbox
UI, the confirm-step time line).

## Test plan

- Live: create a new SELF-sourced record with a genuine overnight window
  (23:00 → 01:00 next day), check "Ends the next day", confirm the duration
  preview shows 2 hours (not a negative/zero value), submit, confirm no
  `INVALID_RANGE`/`INVALID_DURATION` error.
- Live: resubmit that same record (after a teacher return), confirm the
  checkbox reloads pre-checked and the time fields show the original
  23:00/01:00 values, not silently collapsed to a same-day range.
- Regression: a same-day range still works unchanged (verified via the
  earlier PROJECT/SELF-source records created during
  `03-ServiceRecordFormOptimization.md`'s verification, all same-day).

## Resolution

- `frontend/src/app/lib/datetime.ts`: added `addOneDay(dateStr)`.
- `frontend/src/app/components/student/ServiceRecords.tsx`: `endsNextDay`
  form field, wired through load/save/duration-preview/confirm-summary; new
  checkbox UI after the End Time field.
- `frontend/src/i18n/en.json`/`zh.json`: new `student.records.endsNextDay` key.
- Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`.

The one live record this had corrupted during discovery
(`rec_3t67kqy4q6pqggapzmrtm3qhwr`, "Example District A Community") was already
fixed forward at discovery time (manually corrected to a valid same-day
window and resubmitted) — its service window still doesn't match the
original overnight seed data, but no record is in a broken state.

---
*Live-verified 2026-08-10 against `https://tb.example.com/` via browser MCP,
logged in as `student001`: created a new SELF-sourced record with a genuine
23:00→01:00(+1d) window (`rec_p7s7j89aqphs3jymg6aca02n6d`,
`TB-2025-2026-10-2024001-005`), confirmed the 2-hour duration preview and a
clean submit (`startAt`/`endAt` confirmed via the API response to span two
different UTC calendar dates with `durationMinutes: 120`), then — after a
teacher (`teacher001`) returned it — reopened it for resubmit and confirmed
the "Ends the next day" checkbox reloaded pre-checked with the original
23:00/01:00 values intact.*
