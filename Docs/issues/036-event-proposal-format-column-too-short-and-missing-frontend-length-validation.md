# 036 — `event_proposal.format` column too short, and no frontend maxLength anywhere in the app

Status: **In progress (2026-08-20)**

## Symptom

Found while creating demo proposal data as `student001` against the live `https://tb.example.com/` server (issue 035's follow-up session): submitting a proposal with a reasonably normal "Activity Format" value ("Classroom donation drive plus delivery visit", 45 characters) failed with a generic "An unexpected error occurred." toast and an HTTP 500.

Reported by the user directly once diagnosed, with two explicit asks: (1) the underlying column limit (32) is too small — raise it to 64; (2) the frontend has no `maxLength` enforcement anywhere, so the same silent-500 failure mode is latent on every other free-text field in the app — audit and fix systematically, not just this one field.

## Root cause

`event_proposal.format` (`EventProposal.java`'s `format` field) is `VARCHAR(32)` (`V7__projects_and_proposals.sql`). Nothing between the browser and MySQL enforces that limit before the `INSERT`: `ProposalRequests.CreateProposalRequest`/`UpdateProposalRequest` have no Bean Validation `@Size` on `format` (or on any other string field — checked, this is true of every request DTO in the app, not just proposals), and the frontend's `EventProposals.tsx` wizard renders the field as a plain `<Input>` with no `maxLength`. MySQL's non-strict truncation-as-error behavior (this schema doesn't run in ANSI/STRICT mode for this class of overflow) surfaces as a raw `DataIntegrityViolationException` → an unhandled 500, not a clean 400 field error.

A repo-wide audit (`grep` across every JPA entity's `@Column(..., length = N)` on `String` fields, cross-referenced against every `<input>`/`<Textarea>` in the frontend's create/edit forms) found this is a systemic gap: **zero** frontend inputs in the app had `maxLength` before this fix, across all of Proposal creation/edit, Case filing, Service Record submission, Project creation/edit, Organization management, and Account transaction notes/admin decision-comment fields.

## Fix

**Backend column bump:** `V23__event_proposal_format_length.sql` (`ALTER TABLE event_proposal MODIFY format VARCHAR(64)`), `EventProposal.java`'s `format` field `@Column(length = 64)`.

**Frontend systematic audit:** grepped every JPA entity's `@Column(..., length = N)` on a `String` field, then walked every `<input>`/`<textarea>` in every admin/student create-or-edit form and added a matching `maxLength` (Hibernate's implicit default of 255 where an entity has no explicit `length`; skipped columns with `columnDefinition = "text"`, e.g. `Organization.description`, since those are genuinely unbounded). Files touched, with the field → column mapping:

- `frontend/src/app/components/student/EventProposals.tsx` — the proposal create/edit wizard (where `format` actually broke): `title`→255, `goal`→`objective` 1000, `description`→4000, `expectedOutcomes`→`expectedOutcome` 1000, `location`→255, `activityFormat`→`format` 64, `targetParticipants`→500, `volunteerTasks`→1000, `contactInfo`→`externalContact` 255, `riskMeasures`→`safetyPlan` 2000, `materialNeeds`/`budgetNeeds`→jointly `resourceNeeds` 1000 (split 500/499, since the two are joined with `\n` before sending), `recruitmentMethod`→`recruitmentPlan` 1000, `proofMethod`→`evidencePlan` 1000
- `frontend/src/app/components/SearchPicker.tsx` — the `allowCreateNew` search box (only used by `EventProposals.tsx`'s partner-org picker) that turns typed text into a new `Organization.name` → 255
- `frontend/src/app/components/student/CaseFilingModal.tsx` — `reason`→`Case.description` 4000
- `frontend/src/app/components/student/ServiceRecords.tsx` — `location`→255, `organizationName`→255, `description`→2000, `travelTimeReason`→500, `supervisorName`→255, `supervisorContact`→255
- `frontend/src/app/components/admin/ProjectManagement.tsx` — both the edit form and the separate new-project form: `title`→255, `summary`→255, `description`→4000, `location`→255, `evidenceRequirements`→1000; plus the cancel-reason textarea, which doesn't map to a `Project` column at all — it lands in `audit_log.reason_code` (64)
- `frontend/src/app/components/admin/OrganizationManagement.tsx` — shared `OrgFormFields` (used by both create and edit): `name`→255, `contactName`→255, `contactPhone`→255, `contactEmail`→255, `address`→255
- `frontend/src/app/components/admin/ProposalReview.tsx` / `CaseReview.tsx` / `AuditCenter.tsx` — each decision modal's free-text `comment` → the respective `*ReviewEvent`/`*DecisionEvent`/`CaseDecisionEvent` entity's `comment` column, 1000 (the `reasonCode` alongside it is always a `<select>`, never free text, so no column-overflow risk there)
- `frontend/src/app/components/admin/StudentList.tsx` — `name`→`displayName` 100, `studentNumber`→32, `email`→255
- `frontend/src/app/components/admin/RoleManagement.tsx` — `code`→32, both role and role-template `name`→100
- `frontend/src/app/components/admin/SystemAdmin.tsx` — user create/edit `name`→`displayName` 100, `email`→255, `staffId`→64

**Known related gap, not fixed here (scope discipline — the user's ask was frontend-focused):** request DTOs still have no server-side `@Size` validation, so a non-browser API caller can still trigger the same raw-500 failure mode by exceeding a column limit directly. Frontend `maxLength` closes the practical UI path; closing the API path too would mean adding `@Size(max=N)` to every string field across every request DTO in the app — a larger, separate change. Flagged here rather than silently left undiscovered.

## Test plan

- Backend: `mvn clean test` (37/37, MockMvc + ArchUnit) clean; `mvn clean verify -Dit.test=ProposalApprovalTransactionIT` (15/15) against real MySQL over an SSH tunnel, confirming the new `V23` migration applies cleanly on the live schema and nothing broke.
- Frontend: `pnpm build` clean.
- Live: resubmit a proposal with a `format` value between 33–64 characters (previously guaranteed 500) as `student001`, confirm success; spot-check a couple of the other newly-`maxLength`-bounded fields visually (browser blocks further typing at the limit, no server round-trip needed to prove that part).

## Resolution

*Not yet deployed/live-verified — the fix and its test suite are complete; deploy and live click-through are the next step.*
