# 039 — Approving/rejecting an Event Proposal writes no audit log entry

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Reported directly (not found via live browser testing): the Proposal Review feature works end to end — approving a proposal correctly generates a draft `Project` (with `approvedProjectId` backfilled) and sends the proposer a notification — but no audit log entry is ever written for the review decision itself. `GET /audit-logs?filter[resourceType]=eventProposal&filter[resourceId]=<id>` (and the Admin Audit Center UI) shows entries for `create`/`update`/`delete`/`submit`/`withdraw`/`assignReviewer`, but nothing for the actual approve/reject/request-changes decision — the single most consequential action in the whole proposal lifecycle leaves no audit trail.

## Root cause

`ProposalServiceImpl` (`backend/src/main/java/cn/timebank/proposal/application/ProposalServiceImpl.java`) does call `auditService.record(...)` for `create`/`update`/`delete`/`submit`/`withdraw`/`assignReviewer`. But the actual decide/approve/reject logic doesn't live in that class — `ProposalServiceImpl.decide(...)` just delegates through `IdempotencyService.execute(...)` to a separate `@Transactional` bean, `ProposalApprovalTransaction.execute(...)` (kept as a dedicated bean for the same self-invocation-proxy reason as `ReviewDecisionTransaction`, per its class comment). That method transitions the proposal's status, writes the append-only `ProposalReviewEvent` (a domain review-history record, not an audit log entry), generates the draft project on approval, and sends the proposer notification — but never had an `AuditService` dependency at all, so none of that ever produced an audit log row. This affects both branches equally: `APPROVE` and `REJECT`/`REQUEST_CHANGES` share the same `execute()` method with one `switch` on decision, so the gap was never conditional on outcome.

## Fix

Injected `AuditService` into `ProposalApprovalTransaction` and added one `auditService.record(...)` call right after the status transition (covers all three decision outcomes, since they share the same method) — `backend/src/main/java/cn/timebank/proposal/application/ProposalApprovalTransaction.java`:

```java
auditService.record(new AuditEntry(actorId, "eventProposals.review", "eventProposal.reviewDecision",
        "eventProposal", proposalId, before.name(), proposal.getStatus().name(), request.reasonCode()));
```

`before`/`after` are the proposal's status before/after the transition (e.g. `SUBMITTED`→`APPROVED`); `reasonCode` reuses the same reason code already captured on the `ProposalReviewEvent`. Modeled on the existing pattern in `ServiceRecordServiceImpl.beginReviewTransition(...)` (the closest structural analog — a review-decision transition that already carries an audit call) and `ProposalServiceImpl`'s own existing calls for the proposal's other actions.

## Test plan

- Backend: added assertions to the existing `ProposalApprovalTransactionIT` (`backend/src/test/java/cn/timebank/proposal/application/ProposalApprovalTransactionIT.java`) — `approvalGeneratesExactlyOneProjectAndBackfillsApprovedProjectId` now also asserts an `eventProposal.reviewDecision` audit row exists with `APPROVED` in its after-summary, and `rejectDoesNotGenerateAProject` asserts the same with `REJECTED`. Ran `mvn verify -Dit.test=ProposalApprovalTransactionIT` against the real MySQL on `tb.example.com` (via SSH tunnel, since the app DB isn't reachable directly from a workstation) — all 15 tests in the class pass, including the two new assertions.
- Live: approve and reject a real proposal via the deployed Admin Portal (`admin001`) and confirm the decision now shows up in Audit Center for that proposal's `eventProposal` resource.

## Resolution

Deployed to `https://tb.example.com/` (rsync + `backend/scripts/deploy.sh`, no frontend change needed for this fix). Live-verified via Playwright as `admin001`: rejected the "Winter Coat Drive for Rural School Partnership (Copy)" proposal (`prop_8sn1ykhgstb6xwvxrgha0rje7v`, a pre-existing duplicate test proposal from an earlier session, chosen specifically so nothing new needed cleanup) through the real Proposal Review workbench. A direct `GET /audit-logs?filter[resourceType]=eventProposal&filter[resourceId]=prop_8sn1ykhgstb6xwvxrgha0rje7v` call (made from an authenticated browser session via `fetch`) now shows a new `eventProposal.reviewDecision` entry (`beforeSummary: "SUBMITTED"`, `afterSummary: "REJECTED"`, `reasonCode: "REJECT"`, real `actorId`/`requestId`/timestamp) alongside the pre-existing `create`/`submit` entries — confirming the fix works against the real deployed backend, not just the IT test.

Note: there's no dedicated Admin UI page that lists `GET /audit-logs` rows directly — the nav item labeled "Audit Center" is actually the service-record pending-review queue, a different screen entirely (confusingly similar name); the real audit trail is currently only reachable via the API itself or the "Export Audit Logs" data-export type in System Admin. That's a pre-existing gap, not something this fix touches, but worth flagging so a future session doesn't go looking for this proposal's audit entry in the wrong screen.

*Live-verified via Playwright against the real deployed backend (`https://tb.example.com/`, backend v0.1.6 build after this fix): a real reject decision now produces a real, persisted audit log row. No live data needed reverting — the proposal used was already a disposable duplicate slated for rejection anyway.*
