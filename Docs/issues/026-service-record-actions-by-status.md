# 026 — Service record actions not correctly gated by status

Status: **Fixed** (2026-08-17)

## Symptom

Reported directly by the user. Under Student → Service Records, three action/button issues:

1. **Approved records show a "Supplement" button** — approved records are already completed; there is no meaningful supplement action for them.
2. **Draft records have no "Edit" button** — drafts can only be deleted or submitted, but a student should be able to edit a draft before submitting.
3. **Submitted records have no "Revert" button** — a submitted (pending review) record should be revertable back to draft so the student can edit and resubmit.

## Root cause

In `frontend/src/app/components/student/ServiceRecords.tsx`:

1. The `approved` status block renders a Supplement button (line ~846 desktop, ~949 mobile) — this was intended for filing a supplement *case* but is inappropriate for approved records which are already finalized.
2. The `draft` status block (line ~827 desktop, ~932 mobile) only renders Delete and Submit buttons — no Edit action, even though `openResubmit` already provides the full edit-form flow and could be reused.
3. The `submitted` status block has no action buttons at all — `api.serviceRecords.withdraw` exists in the client (line 518) and the backend supports `SUBMITTED → DRAFT` (contract §4.5, `ServiceRecordStateMachine` line 37), but the UI was never wired.

## Proposed fix

All changes are frontend-only in `ServiceRecords.tsx` and i18n files.

### 1. Remove Supplement button from approved records
Remove the `rec.status === 'approved'` button block in both desktop table and mobile cards.

### 2. Add Edit button to draft records
Add an "Edit" button before Delete/Submit in the `draft` status block. Reuse `openResubmit` (renamed conceptually to `openEdit`) which loads the record into the form with `editing` set — the form already handles PATCH vs POST based on `editing`. The title should say "Edit Record" rather than "Edit Returned Record" when the source status is `draft`.

### 3. Add Revert button to submitted records
Add a "Revert" button in the `submitted` status block that calls `api.serviceRecords.withdraw(id)` to revert the record to draft status. Only `submitted` status is eligible — `underReview` cannot be withdrawn (state machine line 38, `SUBMITTED → UNDER_REVIEW` is REVIEWER-only; no `UNDER_REVIEW → DRAFT` edge exists).

## Acceptance criteria

- [x] Approved records show no Supplement button (no action buttons).
- [x] Draft records show an Edit button that opens the record in the edit form.
- [x] Submitted records show a Revert button that calls withdraw and reverts to draft.
- [x] `underReview` records do not show a Revert button (backend would reject).
- [x] Frontend `tsc --noEmit` clean.
- [x] i18n keys added to both `en.json` and `zh.json`.

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Live: log in as `student001`, verify draft/edit → submit → revert → edit → resubmit flow; verify approved records have no Supplement button.

## Resolution

All changes are frontend-only in `frontend/src/app/components/student/ServiceRecords.tsx` and i18n files (`frontend/src/i18n/en.json`, `frontend/src/i18n/zh.json`).

### 1. Supplement button removed from approved records
The `rec.status === 'approved'` button block was removed from both the desktop table and mobile card action sections. Approved records now show no action buttons.

### 2. Edit button added to draft records
An "Edit" button (`openEditDraft(rec)`) was added as the first action in the `draft` status block in both desktop and mobile views. This reuses the existing `openEdit` function which loads the record into the multi-step form with `editing` set (PATCH path). The form title now shows "Edit Draft Record" (`editDraftTitle`) when `editing.status === 'draft'`, vs "Edit Returned Record" (`editTitle`) for returned records.

The `editing` state was extended from `{ id, version }` to `{ id, version, status }` to track the original record status for the correct form title.

### 3. Revert button for submitted records
The `handleRevert` function (calling `api.serviceRecords.withdraw`) and the Revert button on `submitted` records were already present in the code — the `api.serviceRecords.withdraw` client method (orphaned per project notes) was already wired here. No change was needed for this item; it was already working.

### i18n keys added
| Key | en.json | zh.json |
|-----|---------|---------|
| `student.records.edit` | Edit | 编辑 |
| `student.records.revert` | Revert | 撤回 |
| `student.records.editDraftTitle` | Edit Draft Record | 编辑草稿记录 |
| `student.records.revertedToDraft` | Record reverted to draft | 记录已撤回为草稿 |
| `student.records.draftLoaded` | Draft loaded for editing | 草稿已载入，可编辑后提交 |

### Verification

- `tsc --noEmit` clean (exit code 0).
- Deployed 2026-08-17 to `https://tb.example.com/` via `frontend/scripts/deploy.sh`.
- **Bundle verification:** Deployed JS bundle (`index-cr53pVb7.js`) contains:
  - `editDraftTitle` key (en: "Edit Draft Record", zh: "编辑草稿记录") — 3 occurrences (en dict, zh dict, form title conditional)
  - `revertedToDraft` key (en: "Record reverted to draft", zh: "记录已撤回为草稿") — 3 occurrences
  - `draftLoaded` key (en: "Draft loaded for editing", zh: "草稿已载入，可编辑后提交") — 3 occurrences
  - Form title conditional: `ee.status==="draft"?"student.records.editDraftTitle":"student.records.editTitle"` — confirms draft vs returned title logic is deployed
- **API verification with `student001` session:**
  - **Draft edit (Scenario 2):** `PATCH /service-records/{draftId}` with `{"version":2,"description":"Edited via API test"}` → `200` status=DRAFT version=3, description updated. Edit flow works.
  - **Submitted revert (Scenario 3):** `POST /service-records/{submittedId}/withdraw` → `200` status=DRAFT version=8. Record reverted from SUBMITTED to DRAFT successfully.
  - **Approved supplement (Scenario 1):** UI-only change — the Supplement button was removed from the `approved` status block in both desktop table and mobile cards. No API endpoint for supplement on approved records exists; verified by code inspection.
