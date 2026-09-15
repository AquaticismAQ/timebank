# 073 — "Save Draft" tears down the wizard's state but leaves the wizard open

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found while testing issue 072's PDF preview path: a PDF was uploaded on
the Evidence step, **Save Draft** was pressed, and then — still in the
same, still-open wizard — the record was submitted. The submit was
refused:

```
422 VALIDATION_FAILED
fieldErrors: [{ field: "evidenceFileIds", code: "EVIDENCE_REQUIRED",
               message: "At least one evidence file is required." }]
```

The evidence had been uploaded and saved onto the draft server-side; the
wizard had simply forgotten about it.

The quieter and more damaging half: `editing` is cleared at the same time,
so a subsequent **Confirm Submit** is no longer a `PATCH` of the draft
just saved — it's a `POST`, creating a **second, duplicate record**. Had
evidence still been attached, that submit would have succeeded and left
the student with two records for one session, one of them a duplicate the
reviewer then has to catch.

## Root cause

`handleSaveDraft` runs the same teardown as the submit path — clearing
`evidenceFiles` and `editing` — but, unlike `handleSubmitRecord`, never
actually closes the wizard:

```tsx
const handleSaveDraft = async () => {
  await saveRecord();
  toast.success(t('student.records.draftSaved'));
  setEvidenceFiles([]);   // teardown…
  setEditing(null);       // …teardown…
  refetch();
  // …but no setView('list') / setStep(0) — the wizard stays on screen
};
```

Compare `handleSubmitRecord`, which does `setView('list'); setStep(0);`
alongside the same two resets.

So the teardown is right and the intent is unambiguous — "we're finished
with this wizard" — it just doesn't happen. The form stays up, fully
populated and looking usable, while the two pieces of state that make it
*work* have been thrown away underneath it. Every field still shows its
value (those live in `formData`, which isn't reset), so nothing on screen
hints that the evidence list is now empty and that the next submit will
create a new record.

## Fix

Complete the intent: close the wizard and return to the list after a
successful draft save, exactly as the submit path does. That also matches
what the "Draft saved" toast already implies, and the draft is right there
in the list with Edit/Delete/Submit to carry on with.

The alternative — keep the wizard open and *preserve* the state, re-binding
`editing` to the saved record's new id/version — was considered and not
taken: it's more moving parts to get right for a flow whose own teardown
code says the author meant to leave.

## Test plan

- `tsc --noEmit`, `pnpm build`.
- Live Playwright pass against the deployed server: upload evidence, press
  Save Draft, and confirm the wizard closes to the list with the draft
  present and its evidence intact (re-open via Edit and check the file is
  still listed); then submit that draft and confirm it succeeds rather
  than failing `EVIDENCE_REQUIRED`, and that no duplicate record appears.

## Resolution

`frontend/src/app/components/student/ServiceRecords.tsx` — `handleSaveDraft`
now closes the wizard (`setView('list')`, `setStep(0)`) after saving,
matching `handleSubmitRecord`.
