# 014 — Resubmit form keeps a stale `version` after a save-then-submit fails, causing a spurious 409 on retry

Status: **Proposed fix** (found 2026-08-10, not yet implemented)

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

## Proposed fix

After a successful `saveRecord()` inside `handleSubmitRecord` (and
`handleSaveDraft`), update `editing` with the returned record's new
`version` before calling `submit()`, e.g.:

```ts
const record = await saveRecord();
if (editing) setEditing({ id: record.id, version: record.version });
await api.serviceRecords.submit(record.id, submitKeyRef.current);
```

## Test plan (once fixed)

- Live: reproduce a save-then-submit-fails sequence (e.g. via issue 013's
  overnight-date scenario, or any other submit-time-only validation error),
  correct the problem without leaving the form, submit again, confirm no 409.

## Resolution

Not yet implemented — filed for a future session. Worked around during
discovery by leaving the form and re-opening "Resubmit" from the list (fresh
`GET` picks up the current `version`), which is how the affected live record
was ultimately submitted successfully.

---
*Found live 2026-08-10 against `https://tb.example.com/` via browser MCP while
verifying issue `03-ServiceRecordFormOptimization.md`'s implementation. Not
fixed this session — proposed fix only, out of scope for that feature's
implementation pass.*
