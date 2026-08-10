# 014 — Resubmit form keeps a stale `version` after a save-then-submit fails, causing a spurious 409 on retry

Status: **Fixed and live-verified (2026-08-10)**

## Symptom

When resubmitting a returned record, if the PATCH (save) succeeds but the
subsequent `submit` call fails validation (`422`), the record's `version` on
the server has already advanced (the PATCH incremented it). The open form
still holds the pre-PATCH `version` in its `editing` state. If the student
then corrects the validation problem and tries again without leaving/
re-entering the form, the second PATCH sends the now-stale `version` and the
server correctly rejects it as an optimistic-concurrency conflict: `409`. The
student sees a generic error and has no way to proceed except abandoning the
in-progress edit and re-opening "Resubmit" from the list (which does a fresh
`GET` and picks up the current `version`).

Live reproduction: resubmitting `student001`'s "Example District A Community"
record — first submit attempt failed with `422 INVALID_RANGE` (see issue
`013`), then correcting the time and submitting again in the same form session
produced `409` on the PATCH.

## Root cause

`ServiceRecords.tsx`:

```ts
const saveRecord = (): Promise<ServiceRecord> => editing
  ? api.serviceRecords.update(editing.id, { ...buildRecordPayload(), version: editing.version })
  : api.serviceRecords.create(buildRecordPayload());

const handleSubmitRecord = async (e: React.FormEvent) => {
  ...
  const record = await saveRecord();
  await api.serviceRecords.submit(record.id, submitKeyRef.current);
  ...
```

`saveRecord()` always reads `editing.version` from component state, but
nothing updates `editing` after a successful `update()` call — `record`
(the fresh DTO with the new `version`) is only used to call `submit()`, never
written back into `editing`. This logic predates the Service Record Form
Optimization rewrite (confirmed identical in `HEAD`) — a pre-existing defect,
not introduced by that work. It was only exposed now because that rewrite's
live verification of the resubmit path happened to hit a first-attempt submit
failure (issue `013`) immediately followed by a same-session retry, a
sequence no prior live-testing session had exercised.

## Fix

Implemented exactly as proposed: after a successful `saveRecord()` inside
`handleSubmitRecord`, `editing` is updated with the just-saved record's fresh
`id`/`version` before calling `submit()`, so a submit-time-only validation
failure no longer leaves the form holding a stale version for the next PATCH.

```ts
const record = await saveRecord();
if (editing) setEditing({ id: record.id, version: record.version });
await api.serviceRecords.submit(record.id, submitKeyRef.current);
```

File: `frontend/src/app/components/student/ServiceRecords.tsx`
(`handleSubmitRecord`).

## Test plan

- Live: reproduce a save-then-submit-fails sequence (removing a record's
  evidence file so submit's `EVIDENCE_REQUIRED` check fails after a
  successful PATCH), correct the problem (re-add evidence) without leaving
  the form, submit again, confirm the retry's PATCH returns `200` not `409`.

## Resolution

- `frontend/src/app/components/student/ServiceRecords.tsx`: `handleSubmitRecord`
  now refreshes `editing`'s version after every successful save.
- Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`.

`handleSaveDraft` was left unchanged — it already sets `editing` to `null`
after a successful draft save (unrelated existing behavior, out of scope
here).

---
*Live-verified 2026-08-10 against `https://tb.example.com/` via browser MCP,
logged in as `student001`: reopened `rec_p7s7j89aqphs3jymg6aca02n6d` (issue
013's overnight test record, returned by `teacher001` for this test) for
resubmit, removed its evidence file and submitted — PATCH `200`, submit `422
EVIDENCE_REQUIRED` as expected. Re-added the evidence file in the same form
session (no navigation away) and submitted again — PATCH `200` (previously
would have been `409`), submit `200`, record correctly transitioned to
`submitted`.*
