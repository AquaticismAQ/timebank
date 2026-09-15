# 059 — Deleting an edited draft service record 500'd with a raw FK constraint violation

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Reported directly by the user: deleting the draft service record
`TB-2025-2026-10-2024001-008` failed with an error.

## Root cause

`ServiceRecordServiceImpl.deleteDraft` only allows physically deleting a
record whose status is `DRAFT`, then calls `repository.delete(record)`
directly. That works for a draft that was created and never touched again.
But every `updateDraft` call (i.e. every time the student clicks Edit and
saves) writes a row to `service_record_revision` via `writeRevision` — a
business-facing "what changed and when" timeline, separate from the
security `audit_log` — and that table's `service_record_id` column is a
plain `FOREIGN KEY` back to `service_record.id` with no `ON DELETE CASCADE`
(`V5__core_loop.sql`). Deleting a draft that had ever been edited therefore
hit MySQL error 1451 (`Cannot delete or update a parent row: a foreign key
constraint fails ... fk_service_record_revision_record`), surfaced to the
frontend as a bare 500 `INTERNAL_ERROR`. `TB-2025-2026-10-2024001-008` had
been edited at least once, which is exactly why it — and not every draft —
hit this. There was no existing test coverage for delete at all (`git grep`
for `deleteDraft`/`DELETE .../service-records` in `backend/src/test` found
nothing before this fix), so the gap had never been caught.

## Fix

`ServiceRecordRevisionRepository` (a bare `Repository<ServiceRecordRevision, String>`
marker, deliberately exposing only `save`/`find` to keep the table
append-only in normal use) gained one more declared method,
`deleteByServiceRecordId`. `deleteDraft` now calls it immediately before
`repository.delete(record)`. This isn't a mutation of an existing revision
row (still append-only in that sense) — it's only ever reached when the
owning record itself is being physically purged (the only state that
reaches this method is `DRAFT`), so the revision history has nothing left
to be history of once its parent is gone.

Separately, while verifying this fix the user asked to also add a confirm
dialog before delete — there wasn't one at all, in either the desktop table
or mobile card action, so a single misclick permanently destroyed a draft
with no recovery. `ServiceRecords.tsx`'s row actions now open a
confirmation dialog (Cancel/Confirm Delete) instead of calling
`api.serviceRecords.delete` immediately. Rather than write a new one,
extracted the existing `ConfirmDeleteDialog` component out of
`RoleManagement.tsx` (previously a private, file-local component used for
role/template deletion) into `components/shared/ConfirmDeleteDialog.tsx`
and reused it from both places — this was already the established
confirm-before-delete pattern in the app, just not applied here yet. Its
hardcoded `admin.system.confirmDelete` button-label key generalized to a
new top-level `common.confirmDelete`, since the component is no longer
admin-only.

## Test plan

- Added `deletingADraftThatWasPreviouslyEditedSucceeds` to
  `ServiceRecordSubmissionIT` — creates a draft, edits it once (forcing a
  `service_record_revision` row to exist), deletes it, and asserts a
  follow-up `getDetail` now 404s. `mvn verify -Dit.test=ServiceRecordSubmissionIT`
  clean against real MySQL on the server (ran remotely per `verify.sh`'s
  "on the server, never Testcontainers" convention — this repo has no
  local SSH tunnel to the DB set up, only the server itself can reach
  `127.0.0.1:3306`).
- Full `mvn verify` (181 IT tests) run for broader regression coverage:
  clean except two pre-existing, unrelated `DashboardReportingIT` failures
  (see Resolution) — no other class affected.
- Live Playwright pass against the deployed server as `student001`:
  deleted the exact record ID the user reported; separately confirmed the
  new confirm dialog blocks/allows deletion correctly (Cancel leaves the
  record intact, Confirm Delete removes it) on a second, never-edited
  draft; confirmed the shared component still renders correctly from its
  original call site (`admin001`'s Role Management → Templates delete,
  Chinese locale).

## Resolution

`backend/src/main/java/cn/timebank/servicerecord/infrastructure/ServiceRecordRevisionRepository.java`:
added `void deleteByServiceRecordId(String serviceRecordId)`.
`backend/src/main/java/cn/timebank/servicerecord/application/ServiceRecordServiceImpl.java`:
`deleteDraft` now calls it before `repository.delete(record)`.
`backend/src/test/java/cn/timebank/servicerecord/ServiceRecordSubmissionIT.java`:
new regression test. Backend rebuilt and redeployed
(`backend/scripts/deploy.sh`, run on the server after an `rsync`).

`frontend/src/app/components/shared/ConfirmDeleteDialog.tsx`: new, extracted
from `RoleManagement.tsx`'s previously file-local component of the same
name (behavior unchanged, just relocated and its confirm-button label
generalized from `admin.system.confirmDelete` to a new `common.confirmDelete`
key). `RoleManagement.tsx`: now imports the shared component instead of
defining its own. `ServiceRecords.tsx`: new `deleteTargetId` state; both the
desktop-table and mobile-card Delete buttons now set it instead of calling
`handleDeleteDraft` directly; renders `ConfirmDeleteDialog` when set. New
`student.records.deleteRecord`/`confirmDeleteRecord` i18n keys (en/zh).
Frontend rebuilt, i18n-parity-checked, and redeployed
(`frontend/scripts/deploy.sh`).

While running the full `mvn verify` suite to check for regressions, found
(not fixed — separate, unrelated, pre-existing bug, flagged to the user
rather than silently investigated further): `DashboardReportingIT.completionReportUsesTheRightGradeBandsRequirement`
and `.participationReportMatchesHandComputedFiguresForASeededGrade` both
fail (`expected: 1/3 but was: 0`) on the working tree exactly as it stood
before this session's changes — confirmed by re-running just those two
tests in isolation (same failures, not order-dependent) against a diff
that touches only the `servicerecord` module. Worth a dedicated look in a
future session; out of scope here.

Also noted, not fixed (pre-existing, unrelated to this bug): `deleteDraft`'s
own audit entry passes `"serviceRecords.create"` as the `permissionUsed`
metadata field instead of a delete-specific value — likely copy-pasted
from `createDraft`. Cosmetic (nothing enforces against this string; the
real gate is `@PreAuthorize("isAuthenticated()")` plus the ownership
check), but would mislabel this action if anyone reads it off the real
Audit Logs screen.

---
*Live-verified 2026-09-15 against the real deployed server via Playwright
MCP, logged in as `student001`/Zhang Ming: reproduced the original 500
first (confirmed via the browser's network panel and the server's
`journalctl` log, which named the exact FK constraint), redeployed with
the fix, then deleted the exact record ID from the report
(`TB-2025-2026-10-2024001-008`) — it disappeared from the list, the total
count dropped from 13 to 12, and a "Draft deleted" toast confirmed success.
Separately, after adding the confirm dialog: clicked Delete on a
never-edited draft (`TB-2025-2026-10-2024001-017`), clicked Cancel,
confirmed the record was untouched; clicked Delete on another never-edited
draft (`TB-2025-2026-10-2024001-016`), clicked Confirm Delete, confirmed it
was removed (total dropped 12 → 11) — this also serves as the previously-
missing verification that the never-edited-draft delete path still works.
Also confirmed the extracted shared component still renders correctly from
`RoleManagement.tsx`'s Templates tab as `admin001` (Chinese locale,
"确定删除该模板？此操作无法撤销。"), cancelled without deleting the real
template.*
