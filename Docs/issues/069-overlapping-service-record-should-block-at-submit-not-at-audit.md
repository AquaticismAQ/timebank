# 069 — An overlapping service record should be blocked at submit, not routed to the reviewer as an anomaly

Status: **Fixed and live-verified (2026-09-15)**

## Request

Reported directly by the user, following the end-to-end verification pass
that produced issues 064–068 (during which a deliberately duplicated
record was submitted and surfaced to `teacher001` flagged
`Anomaly — Overlaps another service record's time window for this
student.`):

> when a student create a service record that overlaps other of himself,
> it should be gated when submit the record and show a error dialog give
> two choices: cancel(delete) or save as draft, not gated by audit
> operation

So: the overlap check moves from a soft reviewer-facing warning to a hard
student-facing block at submit time, with the student given an explicit
choice about what happens to the record they just tried to submit.

## Current behaviour

`ServiceRecordServiceImpl.runSubmissionValidation` runs the overlap query
*after* the hard-validation block has already thrown-or-passed, and treats
a hit as advisory:

```java
// Duplicate detection — a business rule, not a hard block (warning + review routing per the phase doc).
boolean overlaps = !repository.findOverlapping(...).isEmpty();
if (overlaps) {
    record.setHasAnomaly(true);
    record.setAnomalyNote(appendNote(record.getAnomalyNote(),
            "Overlaps another service record's time window for this student."));
}
```

The record submits successfully and lands in the Audit Center flagged
`Anomaly`, leaving a human reviewer to notice and return it — which is
precisely the "gated by audit operation" the user is asking to remove.

This was a deliberate choice, not an oversight:
`Docs/implementation/05-Backend-B3-CoreLoop.md` §"Design cautions" says

> **Duplicate detection is a business rule, not a unique index.** Same
> student, overlapping time window, same project. Specify it explicitly
> with the school before implementing, and make it a warning-plus-review-
> routing rather than a hard block **where the rule is fuzzy**.

The hedge was conditional on the rule being unspecified. This request is
that specification arriving, so the doc's own condition no longer holds
and the bullet needs updating alongside the code rather than being left to
contradict it.

## Intended behaviour

**Backend.** The overlap check becomes an ordinary hard validation error,
alongside the existing `REQUIRED`/`FUTURE_DATE`/`EVIDENCE_REQUIRED` rules,
with its own field-error code so the frontend can recognise it
specifically rather than string-matching a message:

```
field: "startAt", code: "OVERLAPPING_RECORD"
```

The anomaly flag and note for overlap are removed — with submission now
blocked, that path is unreachable, and leaving it would imply a state the
system can no longer produce. **Travel-time disclosure keeps its anomaly
flag unchanged** — that is a separate rule (Q5) that genuinely does want
reviewer attention rather than a block.

The overlap query itself is unchanged, including which statuses count
(`SUBMITTED`/`UNDER_REVIEW`/`APPROVED`/`RETURNED`/`CORRECTED`, excluding
`DRAFT` and `REJECTED`) and the self-exclusion by id. Drafts staying out
of the comparison set is what makes "save as draft" a coherent escape
hatch: parking the record cannot itself start blocking something else.

**Frontend.** When submit fails with `OVERLAPPING_RECORD`, instead of the
generic field-error/toast handling, the student gets a dialog explaining
the clash with two actions:

- **Delete this record** — `DELETE /service-records/{id}`, then close the
  wizard. This is the "cancel" choice: the record the student just tried
  to submit was created moments earlier by the wizard's own
  create-then-submit sequence, so there is a real draft to remove.
- **Keep as draft** — leave it in `DRAFT` and close the wizard, so the
  student can go back and fix the times later.

The two choices map exactly onto the wizard's existing two-call submit
flow (`POST /service-records` → `POST /service-records/{id}/submit`): by
the time the submit fails, the draft already exists, so "delete" and
"keep" are the only two things that can be done with it.

## Known wrinkle: the resubmit path

The same dialog is reachable from two other entry points, and neither can
honour both choices:

- **Submitting an existing draft** from the records list
  (`handleSubmitDraft`) — both choices are valid, since the record is a
  `DRAFT`.
- **Resubmitting a returned record** (`openResubmit` → the wizard with
  `editing` set) — the record is `RETURNED`, not `DRAFT`. The backend's
  `deleteDraft` only permits deleting a `DRAFT` (issue 059), so "delete"
  is not available, and "keep as draft" would be a lie — it stays
  `RETURNED`.

For that case the dialog shows the same explanation with a single
acknowledge action. Blocking with a clear message is the right outcome
there; inventing a delete-a-returned-record path to make the button count
match is not, and would quietly undo `deleteDraft`'s deliberate
status guard.

## Follow-up: the dialog shows which record it clashes with

Requested mid-implementation: the dialog should show detail, not just
"there's a conflict" — the existing record and the overlapping time range.

`FieldErrorDetail` is contract §2.6's fixed `{field, code, message}` and
isn't ours to widen, and formatting the other record's times into the
message server-side would render them in **UTC** — the exact defect issue
067 had just fixed. So the server adds one more coded entry carrying only
the clashing record's id:

```
{ field: "startAt", code: "OVERLAPPING_RECORD_ID", message: "<recordId>" }
```

The client resolves it through the existing `GET /service-records/{id}`
(always the same student's own record, so already authorized) and renders
both windows in local time with its own translated labels. A failed
lookup is non-fatal: the block still stands, the dialog just omits the
comparison rows.

The dialog shows the attempted window, then the clashing record's status
badge, project/organization name, record number, local time range and
duration.

## Test plan

- `mvn test` (37) clean; `ServiceRecordSubmissionIT` extended from 10 to
  12 tests and green on the server against real MySQL. The previous
  `overlappingRecordsAreFlaggedAsAnomalyNotBlocked` test asserted the old
  soft-warning contract and was rewritten as
  `overlappingRecordsAreBlockedAtSubmitAndLeftAsDraft` (asserts the
  `OVERLAPPING_RECORD`/`OVERLAPPING_RECORD_ID` codes, that the id names
  the record actually clashed with, and that the blocked record stays
  `DRAFT` so "keep as draft" has something real to keep). Added
  `anOverlappingDraftDoesNotBlockSubmission` to pin the DRAFT exclusion
  that the escape hatch depends on.
- Full `mvn verify` on the server: 182 tests, only the two pre-existing
  unrelated `DashboardReportingIT` failures.
- `tsc --noEmit` and `pnpm build` clean; en/zh key parity verified
  programmatically (zero-diff).
- Live Playwright pass against the deployed server as a real student who
  already had an `APPROVED` record at 21:00–23:00:
  - Wizard path (fresh record, 21:30–23:30): submit refused with `422`,
    dialog shown naming the clashing record.
  - List path (`Submit` on the parked draft): same dialog, attempted
    window read off the row rather than the wizard's stale form state.
  - **Delete record** → record gone (3 → 2 rows).
  - **Keep as draft**: the first attempt at this was a *weak* test and is
    recorded here deliberately. It was exercised only from the list path,
    where the record was **already** a `DRAFT` before the button was
    clicked — so "still a draft afterwards" is equally consistent with the
    button working and with it doing nothing at all, and proved neither.
    Re-run properly from the wizard path against a clean baseline (3
    records, zero drafts): a brand-new record was created, blocked, and
    **Keep as draft** persisted it as `TB-…-006`, `Draft`, 3 → 4 rows —
    an outcome a no-op cannot produce. Then the escape hatch the dialog
    actually promises ("correct the date and times before submitting
    again") was followed end to end: re-opened via **Edit** with the
    times, duration and the uploaded `1.jpg` evidence all intact, times
    corrected to a free window (15:00–16:30), and re-submitted
    successfully to `Submitted`. The verification record was then
    reverted and deleted, restoring the 3-record baseline.
  - Chinese locale: full dialog renders translated, including the status
    badge ("已通过") and "(2 小时)".
  - Regression: a non-overlapping record (2026-09-05 09:00–11:00)
    submitted normally and reached `Submitted`.

One bug was found and fixed during that live pass: the dialog rendered
nothing on the wizard path, even though the block fired and the conflict
lookup ran. `ServiceRecords.tsx` returns early for `view === 'new'`, so a
dialog declared only at the end of the list branch is unreachable from
exactly the screen that raises it. It is now built once and rendered in
both branches.

## Resolution

`backend/.../servicerecord/application/ServiceRecordServiceImpl.java` —
overlap moved from an anomaly flag into the hard-validation error list as
`OVERLAPPING_RECORD`, plus the `OVERLAPPING_RECORD_ID` detail entry.
`backend/.../servicerecord/ServiceRecordSubmissionIT.java` — old
soft-warning test rewritten, DRAFT-exclusion test added.
`frontend/src/app/components/shared/OverlapBlockedDialog.tsx` (new) and
`frontend/src/app/components/student/ServiceRecords.tsx` — the dialog and
both entry points into it.
`frontend/src/i18n/{en,zh}.json` — `common.ok` and six
`student.records.overlap*` keys.
`Docs/implementation/05-Backend-B3-CoreLoop.md` — the design caution this
supersedes now records that the rule was specified and is a hard block.

## Found, not fixed

Every message in `runSubmissionValidation` is a hardcoded English literal
regardless of `Accept-Language` — `FUTURE_DATE`, `EVIDENCE_REQUIRED`,
`NOT_CONFIRMED` and the rest all surface untranslated to a Chinese-locale
student. This dialog sidesteps it by rendering its own copy, but the
inline field errors for every other submission rule are still English-only.
Not fixed here: it is a separate sweep across the whole validation surface
(and arguably the wider error-message layer), not something to fold into
this change.
