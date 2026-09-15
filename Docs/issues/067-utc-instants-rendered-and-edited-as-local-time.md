# 067 — UTC instants rendered (and re-submitted) as if they were local time

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found during the three-student end-to-end verification pass. A service
record submitted for **21:00–23:00** Beijing time showed up in the
Admin/Teacher Portal's Audit Center record detail as:

```
Start Time   2026-09-15 13:00
End Time     2026-09-15 15:00
```

— exactly 8 hours early, i.e. the raw UTC instant. `Duration` (2 hours)
and `Service Date` were right, so nothing looked obviously broken; a
reviewer would simply believe the student served in the early afternoon.

Chasing the same pattern turned up a worse instance in Project
Management: opening a project's **Edit** dialog pre-fills its four
`datetime-local` fields the same way, and saving converts them back
assuming they are local — so every edit-and-save silently shifted the
project's start/end/registration window 8 hours earlier, compounding on
each round trip.

## Root cause

All four timestamps are UTC instants on the wire. The code sliced the raw
ISO-8601 string instead of converting:

```tsx
detailRecord.startAt?.slice(0, 16).replace('T', ' ')    // AuditCenter
startAt: p.startAt.slice(0, 16),                        // ProjectManagement toForm
<div>{proj.startAt.slice(0, 10)} → …</div>              // list card dates
```

`frontend/src/app/lib/datetime.ts` already exists for exactly this and
already documents the hazard in the *other* direction (`localDateTimeToISO`:
"Naively appending `Z` … silently shifts every submitted instant by the
browser's UTC offset — 8 hours for a China-based user"), and
`ProjectManagement` already imports `localDatetimeLocalToISO` for its save
path. The read path just never got the matching treatment, so the write
half of the round trip was timezone-correct and the read half was not —
which is precisely what makes the edit-dialog case corrupt data rather
than merely display it wrong.

The date-only slices (`slice(0, 10)`) are the same defect with a smaller
blast radius: they only differ from the local date when the instant falls
on the other side of midnight UTC, e.g. a 23:00 Beijing start renders as
the previous day.

## Fix

Two new inverse helpers alongside the existing ones in `lib/datetime.ts`:

- `isoToLocalDatetimeLocal(iso)` — a UTC instant → one
  `<input type="datetime-local">` value, the exact inverse of
  `localDatetimeLocalToISO`.
- `isoToLocalDate(iso)` — a UTC instant → its local `YYYY-MM-DD`.

Call sites converted:

- `AuditCenter.tsx` — a small `formatLocalInstant` wrapper for the
  Start/End Time fields.
- `ProjectManagement.tsx` — `toForm`'s four `datetime-local` pre-fills
  (the round-trip corruption) and the list card's date range.
- `ProjectCenter.tsx` — the student-facing project card's start date.

## Test plan

- `pnpm build` clean.
- Live Playwright pass against the deployed server as `teacher001`: opened
  the Audit Center detail for a record whose stored instants are
  `2026-09-15T13:00:00Z`/`15:00:00Z`; it now reads `2026-09-15 21:00` /
  `2026-09-15 23:00`, matching both what the student entered and the
  project's own advertised window. Confirmed the same values were shown
  as `13:00`/`15:00` on the previous deploy immediately beforehand.

## Resolution

`frontend/src/app/lib/datetime.ts` — added `isoToLocalDatetimeLocal` and
`isoToLocalDate`.
`frontend/src/app/components/admin/AuditCenter.tsx`,
`frontend/src/app/components/admin/ProjectManagement.tsx`,
`frontend/src/app/components/student/ProjectCenter.tsx` — ISO-string
slicing replaced with those helpers.

## Follow-up sweep (same day)

The "not swept exhaustively" caveat below did not survive contact with the
next verification pass: the very next run — the full submit → audit
re-verification the user asked for — hit it again on the **Review History**
timeline, where a decision made at 20:16 local displayed as `12:16`. That
is the reviewer-facing audit trail of the core workflow, not a low-stakes
column, so the sweep was done properly rather than deferred again.

`formatLocalInstant` moved out of `AuditCenter.tsx` into
`lib/datetime.ts` alongside `isoToLocalDate`, and every remaining raw-ISO
slice in both portals was converted:

- Timelines: `AuditCenter`, `ProposalReview`, `CaseReview`, `StudentCases`,
  `EventProposals`.
- `NotificationsPage` timestamps; `CaseReview`'s submitted-at field and
  list subtitle; `StudentCases` submitted-at.
- Date-only (`slice(0, 10)`) columns: both account ledgers
  (`AccountPage`, `AccountManagement`), both registration-deadline labels
  (`ProjectCenter`, `ProjectManagement`), and `EventProposals`' updated/
  submitted/event dates.

Verified against the raw API rather than by eye: the timeline entry whose
stored value is `2026-08-07T13:48:41Z` now renders `2026-08-07 21:48`.

**Still not claimed as exhaustive**, but the remaining risk is now
different in kind: `grep` for `.slice(0, 16).replace('T', ' ')` returns
zero hits across `components/`, and the date-only slices that remain are
on `LocalDate`-typed fields (`serviceDate`), which are *not* instants and
are correct as-is.
