# B4 · Projects, Registrations, and Student Event Proposals

> Phase B4 of `01-Overview.md`. The supply side of service: projects students can join, and the pipeline by which a student's own activity idea becomes an official project.

| | |
| --- | --- |
| **Depends on** | B2, B3 |
| **Blocks** | B5's dashboards and participation reports; I1/I2 project and proposal screens |
| **Effort / calendar** | 7 pw · W16–W19 |
| **Design refs** | `Design.md` §12 invariant 6, §7; `APIContract.md` §3.1–§3.3, §4.2–§4.4, §13.4 |

---

## 1. Goal

Two state machines and one atomic pipeline: project lifecycle, registration lifecycle, and **proposal approval that creates the official project draft in the same transaction** — so an `approved` proposal without a linked project is impossible.

## 2. Scope

**In:** project CRUD and lifecycle actions, capacity and registration windows, registrations with waitlist and bulk actions, event proposals with revisions and an approval chain, atomic project generation, proposal queue summary.

**Out:** publishing workflow beyond permission checks; project-level reporting (B5); POV or on-site verification (Phase 3, not built).

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B4.1** | `project` module — fields per contract §3.1, drafts, updates with optimistic locking | `GET/POST/PATCH /projects` |
| **B4.2** | Project lifecycle — `draft → published → registrationOpen → inProgress → completed → archived`, plus `closed`; **action endpoints only**, and a generic `PATCH` that rejects any attempt to set `status` | `/publish`, `/close`, `/archive`; status is determined by time *and* management action, never derived client-side |
| **B4.3** | Server-computed and viewer-derived fields — `enrolledCount` (§2.11 rule 3) and `myApplicationStatus` (rule 2, per principal, never cross-user cached) | Project list renders the student's own registration state without a second call |
| **B4.4** | Denormalized display names on project responses — `categoryName`, `organizationName`, `ownerName` (§2.11 rule 1) | Delivered by SQL projection; a query-count test guards against N+1 on the list |
| **B4.5** | Project timeline — `GET /projects/{id}/timeline` from the module's own event/revision tables | Distinct from the security audit log (`Design.md` §13) |
| **B4.6** | `enrollment` module (contract's `Application`) — register, cancel, confirm, reject, waitlist promotion, mark complete | Contract §4.4 endpoints; internal package is `enrollment`, REST path stays `/applications` |
| **B4.7** | Capacity control — registration is serialized against the project row so capacity cannot be oversubscribed under concurrency; overflow goes to `waitlisted`, not `rejected` | Concurrency test: N simultaneous registrations for M places yield exactly M confirmations |
| **B4.8** | Bulk registration actions — `POST /applications/bulk-actions` (confirm / reject / mark complete) with per-row results and partial-failure reporting | One row's failure does not silently discard the rest of the batch |
| **B4.9** | `proposal` module — full field set per contract §3.2, draft CRUD, delete-drafts-only, submit, withdraw, revisions, timeline | Contract §4.3 endpoints |
| **B4.10** | Proposal approval chain — decisions `requestChanges` \| `recommendApproval` \| `approve` \| `reject`, appended as non-overwritable events; reviewer assignment and transfer; whether a teacher may approve directly is a **permission**, not a hard-coded rule | `POST /event-proposals/{id}/reviews`, `/assign` |
| **B4.11** | **Atomic project generation** (invariant 6) — approval writes the approval event, sets `approved`, generates the official `Project` draft from the approved revision, records the student as `studentOrganizer` and the supervising teacher as owner, backfills `approvedProjectId`, and creates the notification — all in one transaction, under `Idempotency-Key` | Duplicate approval creates exactly one project; the generated project still requires a separate publish action |
| **B4.12** | Proposal queue summary — `GET /event-proposal-queue/summary` with pending counts and SLA | Mirrors the review-queue summary shape from B3 |

## 4. Exit Criteria

- [ ] Project lifecycle transitions work through action endpoints; a `PATCH` carrying `status` is rejected with `422`.
- [ ] A proposal goes draft → submitted → changes requested → resubmitted → approved, and approval generates exactly one project draft with `approvedProjectId` backfilled.
- [ ] **Duplicate approval produces no second project** (contract §11.9), verified with a concurrent double-submit.
- [ ] A failure during project generation rolls back the approval — no `approved` proposal without a project, and no orphan project.
- [ ] Concurrent registration against the last available place yields one confirmation and one waitlist entry, never two confirmations.
- [ ] An unapproved proposal is invisible in the Project Center and cannot accept registrations.
- [ ] Project and proposal list endpoints render on the real frontend with pagination, filters, and sorting served by the backend.
- [ ] Teacher data scope applies to the proposal approval queue: out-of-scope proposals return `404`.

## 5. Risks and Watch-Outs

- **The two-step temptation.** Creating the project first and then updating the proposal is the exact pattern contract §4.3 forbids: it produces orphan projects when the second call fails. One transaction, one entry point.
- **Status derived from time.** `registrationOpen` looks like a function of the clock, and a scheduled job that flips statuses is the natural implementation — but the frontend must never derive it independently, and the job must be ShedLock-guarded so two instances do not both flip it.
- **Capacity races are easy to miss in review** because they only fail under load. Write the concurrency test before the implementation.
- **Proposal field sprawl.** The proposal carries ~25 fields (safety plan, resources, recruitment, evidence plan). Validate them as a versioned checklist tied to the rules module rather than as hard-coded required fields, so the school can adjust the form without a release.

## 6. Hand-Off

B5 gets projects and registrations to aggregate. I1/I2 wire the Project Center, project management, and proposal screens — the largest remaining block of frontend work.
