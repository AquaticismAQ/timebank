# Project Management and Partner Organization — Comprehensive Clarification

## 0. How to read this document

This is a rewrite of the original short issue list into a grounded clarification: every claim about "current behavior" below was checked directly against `backend/src/main/java/cn/timebank/project/`, `cn.timebank.organization`, `cn.timebank.proposal`, and the corresponding frontend components — not assumed from the design docs. Three kinds of content follow:

- **Finding** — a real gap or bug in the current implementation, unambiguous, not something you need to choose between. Listed so it isn't lost, not because it needs a decision.
- **Settled** — something the original ask is straightforward to build and doesn't conflict with anything already decided or implemented.
- **⚠️ Decision needed** — a real fork where the original ask conflicts with something already implemented/decided (most importantly, with `01-i3-partner-school-terminology.md`), or where the ask itself is underspecified enough that two reasonable implementations would behave differently. Each has 2–3 concrete options, a comparison, and a recommendation where I have one — but the final call is left to you, per your request.

Section 1 resolves the conflict with `01-i3-partner-school-terminology.md` specifically, since that's the one place this doc's original §30 actively reopens a question 01 had already closed. Sections 2–4 cover Project Status, Project Creation/Edit, and Partner Organization Management in turn.

**2026-08-08 — all five decision points resolved.** §1 confirmed Option A, §4's delete-vs-archive confirmed Option A, §4's description field confirmed Option A (add it), §3's owner reassignment confirmed Option B (admin can reassign later via edit), and §2's project-status question was answered directly rather than by picking from the three original options — the answer reshapes the state machine more than any single original option did, so §2 below is rewritten in full rather than left as a table row. Sections below are updated in place; each resolved item is marked **Decided** instead of *Decision needed*, with the reasoning kept so the "why" isn't lost the way a bare status-flip would lose it.

## 1. Conflict with `01-i3-partner-school-terminology.md`

**No conflict on terminology itself.** 01 drew a three-way distinction — deployment school / partner organization (in an event proposal) / partner school (undefined, not a real concept in this system) — and this doc's "partner organization" section uses the term the same way 01 does: an `Organization` row referenced by `Project.organizationId` / `EventProposal.organizationId`. Nothing here reintroduces "partner school." That part of 01 stands as-is and needs no further action beyond what §6 of 01 already recommends for the implementation docs (`13-Integration-I3-Acceptance.md`/`00-Status.md` wording cleanup, unrelated to this doc).

**Real conflict: §30's "student-created organization" lifecycle vs. 01's already-implemented resolution.** This is worth walking through precisely, because the tension is specific, not just a vibe:

- **What 01 designed, and what is now actually built** (`ProposalServiceImpl.resolveOrganizationId`, confirmed in code): when a student submits a proposal with a new organization name (no existing `organizationId`), the backend creates a real `Organization` row — full `ACTIVE` status, a real opaque id, `type = COMMUNITY` — *inside the same transaction as the proposal submission*, before any review happens. This is deliberate: 01 explicitly reasoned that students should **not** get `organizations.manage` (§2 of 01: "不应把组织管理权限直接扩大给学生"), so the org has to be created by the *proposal* write path itself, as a privileged internal call, not by the student calling an organization-management endpoint directly. The org is immediately visible to anyone with `organizations.read` (which is everyone — students, teachers, admins) the moment the proposal is submitted, regardless of whether it's later approved, rejected, or left sitting in review.

- **What this doc's original §30 asked for**: mark such an org "created by student," let the student edit it, and add/remove it from the partner-organization list based on the proposal's approval/rejection outcome.

These don't compose cleanly:

1. **Visibility timing.** §30 implies the org shouldn't appear as a normal partner organization until its originating proposal is approved. But today it's real and listed the instant the proposal is *submitted* — there's no "pending" state on `Organization` at all (`OrganizationStatus` is `ACTIVE | INACTIVE | ARCHIVED`, nothing else). Satisfying §30 literally means adding a fourth status and filtering the list/dropdown by it, not a small change.
2. **Editability.** 01 explicitly decided against giving students `organizations.manage`. §30 wants a student to edit an org they created — that has to be a narrower, ownership-scoped right ("edit only the org(s) *you* created, only while pending"), not the existing role permission. Nothing like that exists today (no `createdBy`/`source` column on `Organization` at all).
3. **Deletion-on-rejection is unsafe once the org is reusable.** If a student-created org is visible and selectable the moment it's created (as it is today), a *second*, unrelated proposal could pick the same org before the first one is decided. Deleting/removing it when the first proposal is rejected would then silently break the second proposal's (or an already-approved project's) `organizationId` reference. Any version of §30 that auto-removes on rejection needs to first decide whether a pending, student-created org can be reused by other proposals at all.

### Options

**Option A — Drop §30's deferred-visibility/edit ask; keep the current (01) design as-is.** No schema change. A proposal-sourced org is real and `ACTIVE` the moment it's created, exactly like today. If it turns out to be junk (typo, duplicate, the proposal gets rejected), an admin or teacher cleans it up manually through the new Partner Organization management tab (§4) — the same way any other bad data entry gets fixed, no different from a mistyped project title. Zero new schema, zero new authorization logic, nothing to get wrong around reuse-across-proposals. Loses the "auto-tidies itself on rejection" convenience §30 wanted.

**Option B — Give proposal-sourced organizations a real pending lifecycle.** Add `source` (`ADMIN_CREATED` / `STUDENT_PROPOSAL`) and a `createdByUserId` column to `Organization`; add a `PENDING` status. Proposal-sourced orgs start `PENDING` and are excluded from the general partner-organization list/dropdown for everyone except the creating student and whoever is reviewing that proposal. On approval, flip to `ACTIVE`. On rejection, flip to `ARCHIVED` (not hard-deleted — protects against the reuse case above). The creating student gets edit rights on their own `PENDING` org only, checked by matching `createdByUserId`, not a role grant. **Still needs an explicit answer to the reuse question**: can a second proposal select a still-`PENDING` org created by someone else's not-yet-decided proposal? If yes, the "auto-archive on rejection" step has to check for other live references first (more moving parts). If no, the org picker has to hide `PENDING` orgs from every proposal except their own creator's — actually a smaller, cleaner rule, but means two students independently working with the same new partner organization will get two separate org rows until the first is approved. This is real backend + migration + frontend work, not a small addition.

**Option C — Cosmetic provenance only, no lifecycle change.** Keep orgs `ACTIVE` immediately (same as Option A), but compute — at read time, no new column — whether an org's *only* associated project-origin is a still-undecided proposal (join against `event_proposal.organization_id` + status), and show a "From a pending proposal" badge in the management tab. Gives admins visibility into provenance without building state machinery. No student edit right; no auto-removal. Smallest change that still answers part of §30's spirit.

**Recommendation: Option A**, unless the auto-cleanup behavior in §30 was actually the point (e.g., you're worried about the partner-org list accumulating junk from rejected proposals at real scale). Option A matches what's already built and tested, and B's reuse-across-pending-proposals question doesn't have an obviously-correct default — that's exactly the kind of fork 01 already flagged once (needing a new permission surface) and asked to be decided explicitly rather than guessed.

**⚠️ Decision needed: A, B, or C above.**

## 2. Project Status

**Decided 2026-08-08.** Neither of the original A/B/C options — you picked the substance of "Option A" (`Closed` becomes a normal automatic stage) but with a cleaner split than A proposed: `CLOSED` keeps exactly one meaning ("registration window elapsed," system-only), and the cancellation meaning moves to a brand-new, separate action (`Cancel and archive`) that never passes through `CLOSED` at all. That sidesteps A's original problem (one status meaning two unrelated things) without C's cost (a whole extra status value). You also raised a real gap neither original option addressed: what actually gates `COMPLETED → ARCHIVED`, since today it's an unconditional manager button click with no check that review work is actually done. Both pieces are designed below.

**Finding — current real state machine, for reference** (`ProjectStateMachine.java`, `ProjectServiceImpl.syncTimeBasedStatus`):

```
DRAFT --(manager: publish)--> PUBLISHED --(system, at registrationOpenAt)--> REGISTRATION_OPEN
  --(system, at project startAt)--> IN_PROGRESS --(system, at project endAt)--> COMPLETED --(manager: archive, ungated)--> ARCHIVED

PUBLISHED, REGISTRATION_OPEN, or IN_PROGRESS --(manager: close, any time)--> CLOSED --(manager: archive)--> ARCHIVED
```

### 2.1 New state machine

```
DRAFT --(manager: publish, projects.publish)--> PUBLISHED
PUBLISHED --(system, at registrationOpenAt)--> REGISTRATION_OPEN
REGISTRATION_OPEN --(system, at registrationCloseAt)--> CLOSED            [NEW — CLOSED now means only "registration window elapsed"]
CLOSED --(system, at project startAt)--> IN_PROGRESS                      [NEW — was previously reached directly from REGISTRATION_OPEN]
IN_PROGRESS --(system, at project endAt)--> COMPLETED
COMPLETED --(manager: archive, projects.archive, GATED — see 2.2)--> ARCHIVED

Side branch — any of {PUBLISHED, REGISTRATION_OPEN, CLOSED, IN_PROGRESS, COMPLETED}
  --(manager: "Cancel and archive", new projects.cancel permission, reason required, ungated)--> ARCHIVED
```

- `syncTimeBasedStatus` gains one more sequential check (`REGISTRATION_OPEN → CLOSED` at `registrationCloseAt`), following the exact pattern the other three already use (each `if` re-checks the project's now-possibly-just-mutated status, so a project that's been dormant long enough to have crossed two or three time boundaries at once still cascades correctly in one call — the code already relies on this for the existing three-step chain, a fourth step is the same shape).
- The old manual `close()` action (`projects.close`, "close a project early," `PUBLISHED/REGISTRATION_OPEN/IN_PROGRESS → CLOSED`) is **removed** — `CLOSED` is system-only now, so there's nothing left for a manual "Close" button to do. Its menu item goes away. The `projects.close` permission can be left granted-but-unused in the seed file or dropped; either is fine, it's dead either way.
- **`Cancel and archive` is the new escape hatch**, replacing what manual "close" used to be for (a manager abandoning/cancelling a project outright) — except now it goes straight to `ARCHIVED` in one step, not through `CLOSED`, since `CLOSED` no longer means "abandoned." Deliberately available from `COMPLETED` too, not just the pre-completion statuses — that's what lets it double as the override for 2.2's gate (see below), so a stalled project with an unresponsive student doesn't get permanently stuck un-archivable. **Revised 2026-08-08**: also available from `DRAFT` — a manager needs a way to discard an unwanted draft, and there was otherwise no path to remove one at all (a draft can't be published-then-archived, since publishing is a real state change that shouldn't be forced just to clean up junk). Not available from `ARCHIVED` (already terminal).
- New permission `projects.cancel`, granted to the same holders as `projects.archive` today (`role_admin` only — `role_teacher`'s seed grant currently has `publish` but not `close`/`archive`, per the existing split noted in `ProjectManagement.tsx`'s own comment on why those three menu actions are gated separately). Kept distinct from `projects.archive` rather than reusing it, since "cancel a live project" and "archive a naturally-completed one" are different-weight actions worth being able to grant separately later, even though they start out with the same holder.
- New audit event: `AuditEntry(actorId, "projects.cancel", "project.cancel", "project", project.getId(), ..., request, ...)` — matching the existing pattern (`publish`/`close`/`archive` each already log their own distinct event type). The request body carries a required `reason` string (new field, no existing analog — today's `archive()`/`close()` calls pass `null` for detail; cancellation is the first project action that needs a human-readable reason captured, not just the actor+timestamp), so the audit log entry actually explains *why* a project was cancelled, not just that it was.

### 2.2 What gates `COMPLETED → ARCHIVED`

Today `archive()` is unconditional — any admin can archive a `COMPLETED` project with outstanding, unreviewed service records still sitting in the queue, and nothing about the project's own state reflects that. Per your direction, archiving a completed project should wait until the students in it have been reviewed.

**Rule:** `archive()` on a `COMPLETED` project is rejected (a structured validation error, same shape as any other field-error response) unless every `CONFIRMED`-enrolled student's service record for that project has reached a terminal review decision — `APPROVED`, `REJECTED`, or `CORRECTED` (`ServiceRecordStatus`). Records still `DRAFT`, `SUBMITTED`, `UNDER_REVIEW`, `RETURNED`, or `APPEAL_PENDING` block it, and so does a `CONFIRMED` enrollment with **no** service record at all yet (a student who never submitted anything) — the gate treats "never submitted" as "not yet reviewed," not as vacuously fine, since silently letting a no-show slide through unnoticed is worse than making an admin consciously decide to bypass it. `WAITLISTED`/`REJECTED`/`CANCELLED` enrollments are out of scope for the check (they never participated).

This reading of "approved" as "reached *a* terminal decision" rather than literally "every record's outcome is `APPROVED`" is deliberate — the latter would make a project with even one legitimately rejected submission permanently unarchivable, which can't be the intent.

**The override for the no-show/stalled case is `Cancel and archive` (2.1)**, not a separate mechanism — since it's available from `COMPLETED` and is explicitly ungated, an admin who's satisfied a lingering unreviewed record isn't going anywhere (student never responds, evidence was withdrawn, whatever) uses it instead of the normal `archive()` action, with the reason logged. No second gate-bypass path needed.

**Where the readiness signal surfaces:** Audit Center already lists individual pending service records for review — no change needed there. What's missing is a project-level view of "how close is this project to archivable," so `ProjectManagement.tsx`'s project card for a `COMPLETED` project should show either a "Ready to archive" indicator (gate satisfied) or a "N reviews remaining" count (gate not yet satisfied, computed from the same query the backend uses to enforce the gate) — giving the admin a reason to go finish reviewing in Audit Center before coming back to archive, without a new screen. (Simplified in the actual implementation to just surfacing the count in the archive-attempt error message, rather than a persistent badge, to keep the change scoped.)

### 2.3 Labels

`registrationOpen`'s label becomes "Registration Open"/报名开放 as originally asked — now literally accurate, since `CLOSED` unambiguously owns "registration window elapsed" and there's no more overlap to worry about. `closed`'s label becomes "Registration Closed"/报名关闭 — also now literally accurate, since it no longer means project cancellation. The filter dropdown order matches the new normal flow: Draft, Published, Registration Open, Registration Closed, In Progress, Completed, Archived — `Object.entries(PROJECT_STATUS_LABELS)`'s insertion order in `statusLabels.ts` needs reordering to match (today it's draft/published/registrationOpen/inProgress/completed/archived/closed, with `closed` last).

### 2.4 No-registrants auto-archive (added 2026-08-08)

A project whose registration window closes with **zero confirmed registrants** has nothing left to run — there's no student to serve, no evidence to review, nothing for `IN_PROGRESS`/`COMPLETED` to mean. Rather than let it sit as a permanently-empty `CLOSED` (and later `IN_PROGRESS`/`COMPLETED`) project cluttering the list, the `REGISTRATION_OPEN → CLOSED` time-derived transition checks the confirmed-enrollment count first: zero confirmed enrollments archives the project directly (a new `REGISTRATION_OPEN → ARCHIVED` `SYSTEM` edge, distinct from both the normal `CLOSED` path and from `Cancel and archive`), skipping `CLOSED`/`IN_PROGRESS`/`COMPLETED` entirely. A real audit log entry is written even though there's no human actor to attribute it to (`actorId`/`permissionUsed` both `null` — `audit_log.actor_id` is nullable for exactly this case) — `action: "project.autoArchiveNoRegistrants"`, `reasonCode: "NO_REGISTRANTS_AT_REGISTRATION_CLOSE"`, so it's distinguishable in the audit trail from every other status change, which was your explicit ask ("with a log about no registrants"). A project with at least one confirmed registrant proceeds through `CLOSED` exactly as before, regardless of how the review process for that registrant later goes.

### 2.5 Registrants menu item hidden for drafts (added 2026-08-08)

`ProjectManagement.tsx`'s actions menu showed "Registrants" for every project regardless of status, including `DRAFT` — but a draft was never published, so registration never opened and there's nothing to view. Gated behind `proj.status !== 'draft'` alongside the existing `applications.read` permission check.

## 3. Project Creation/Edit

**Settled — share the create form with the edit form.** No conflict with anything, straightforward to build the same way `EventProposals.tsx`'s `'edit' | 'copy'` mode split already works (see `backend-design-status` memory §31 for that precedent) — parameterize the existing `EditModal` by a `mode: 'create' | 'edit'` prop instead of maintaining the separate, currently much-thinner `newForm`/`showForm` state and its own inline JSX block.

**Finding — the current "new project" form is missing real fields, not just cosmetically thin.** Checked `ProjectManagement.tsx`'s `newForm` state and its two `api.projects.create(...)` call sites directly:
- `description` and `evidenceRequirements` are never collected or sent at all.
- `eligibleGrades` is never sent — a project created through this form today has **no eligible-grade restriction and is silently open to every grade**, unlike the edit form, which requires at least one grade to be selected before saving.
- `registrationOpenAt`/`registrationCloseAt` are not real, separate inputs — the code currently sends the project's own `startAt`/`endAt` values for both, conflating the registration window with the activity window itself.

These are unambiguous bugs, not decisions — merging into the shared form (above) fixes all four as a side effect, since the edit form already collects every one of these correctly.

**Decided 2026-08-08 — Option B: owner settable at creation, reassignable later via edit, both admin-only.** The backend has **no `ownerId` field on `CreateProjectRequest` or `UpdateProjectRequest` at all** today — `ownerId` is always set to the creating user (`actorId`), unconditionally, with no override path — so this needs adding to both requests, not just `Create`. On both, the field is permission-checked (only an admin caller may set it to someone other than themselves; a teacher caller's own `ownerId` value, if sent at all, is ignored and forced to themselves, matching "for teacher users, it should be automatically set to the current user"). Reassignment on update goes through the same `project.update` audit event that already fires for every other field change — no new event type needed, since `AuditEntry` already carries the full before/after request body. No notification-to-old/new-owner is added as part of this (not asked for, and `notification` already has a real send path elsewhere in the app if it turns out to be wanted later — a separate, small addition, not bundled in here).

**Finding — implementation note for whichever option is chosen.** `GET /users/directory?role=` (built for the proposal co-organizer/supervisor pickers, per `backend-design-status` memory §30) already exists and is the right endpoint to reuse for this dropdown rather than building a new one — but as it stands today it (a) takes exactly one `role` value, not "teacher or admin" combined, and (b) requires a non-blank `search` term and returns nothing otherwise (`UserServiceImpl.searchDirectory`), since it was built for a type-ahead box, not a browsable list. The existing `SearchPicker.tsx` component (same memory reference) already matches that search-as-you-type shape, so the simplest path is calling `/users/directory?role=teacher` and `/users/directory?role=admin` and merging results client-side inside a `SearchPicker`, rather than extending the endpoint — no backend change needed for this part specifically, independent of the A/B decision above.

## 4. Partner Organization

**Settled — new "Partner Organizations" tab before Settings.** No conflict; `SystemAdmin.tsx`'s `Tab` type is currently `'users' | 'rules' | 'import' | 'logs' | 'settings'` — adding `'organizations'` before `'settings'` is a direct, unambiguous addition.

**Settled — list/search visible to all users.** Already true today: `organizations.read` (list/get) is already granted to `role_student`, `role_teacher`, and `role_admin` in the seed permissions — no change needed for the "for all users" part.

**Finding — teachers can't manage organizations yet; needs a permission grant.** The original ask ("Add/Edit/Delete: admin and teacher users only") requires granting `organizations.manage` to `role_teacher` in `R__seed_permissions_and_roles.sql` — currently only `role_admin` holds it. Straightforward, no conflict, just needs doing.

**Decided 2026-08-08 — Option A: archive-only, no hard delete.** There is no hard-delete endpoint for organizations today — only `POST /organizations/{id}/archive` (soft, sets status to `ARCHIVED`; the org row and its id stay valid for any project that already references it) — and that stays the only mechanism. The UI action in the new management tab is labeled "Archive"/"停用", not "Delete"/"删除", to match what it actually does.

**Decided 2026-08-08 — Option A: add a real `description` field.** `Organization` today has `name`, `type` (enum: SCHOOL/COMMUNITY/NONPROFIT/GOVERNMENT/OTHER), `contactName`, `contactEmail`, `contactPhone`, `address` — no free-text field exists. Adding it needs: a new `description TEXT` (nullable) column via migration, `description` added to `CreateOrganizationRequest`/`UpdateOrganizationRequest`/`OrganizationDto`, and a textarea in the new create/edit form alongside the existing structured fields. Also applies to the student-proposal new-organization path (`ProposalServiceImpl.resolveOrganizationId`'s `CreateOrganizationRequest` call, §1) — worth deciding whether the proposal form should collect a description for a brand-new organization too, or leave it blank there (student proposals don't currently ask for one, and §1's Option A means an admin can fill it in later during cleanup if it matters).

**Student-created organizations (§30 of the original doc)** — covered in full in §1 above, since it's the section that actually conflicts with `01-i3-partner-school-terminology.md`. Don't re-decide it here; the three options (A/B/C) and recommendation live in §1.

## 5. Decisions — final index

All five points from the original draft are resolved as of 2026-08-08. Nothing left open in this document; the next step is implementation, not further clarification.

| # | Section | Question | Decision |
|---|---------|----------|----------|
| 1 | §1 (conflicts with 01) | Should student-proposal-created organizations get a pending/deferred-visibility lifecycle with student edit rights? | No — keep current design (immediate, real, `ACTIVE` on creation; admin cleans up manually via the new management tab if needed) |
| 2 | §2 (project status) | What does "Closed" mean, and what gates archiving? | `CLOSED` = registration window elapsed only, fully automatic, no manual close action. Cancellation becomes a new, separate `Cancel and archive` action (any pre-archive status except Draft, reason required, audit-logged, ungated). Normal `COMPLETED → ARCHIVED` is gated on every confirmed student's service record reaching a terminal review decision; `Cancel and archive` is the override for stalled/no-show cases |
| 3 | §3 (project owner) | Can an admin reassign a project's owner after creation, or only set it once at creation? | Both — settable at creation and reassignable later via edit, admin-only either way; teachers always become owner of what they create, non-negotiable |
| 4 | §4 (partner org delete) | Hard-delete unused organizations, or archive-only? | Archive-only — no hard delete, UI labeled "Archive" not "Delete" |
| 5 | §4 (partner org description) | Add a real free-text description field, or treat existing structured fields as sufficient? | Add it — new `description` column, request fields, and form textarea |
