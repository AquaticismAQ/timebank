# 034 — Proposal Review: split view from decide permission, remove Recommend Approval

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Requested by the user directly (not a live-testing find): Recommend Approval added an unnecessary third tier on top of what was already a two-step approval process (proposal approval, then a separate project publish) — remove it. Separately, Proposal Review had no clean way to let a non-decision-making staff account view the queue: the whole page (and every backing read endpoint) required `eventProposals.read`, which happened to always be granted alongside `eventProposals.review` in every seeded role, so the two had never actually been exercised as independent gates.

## Root cause / prior behavior

- `ProposalDecision.RECOMMEND_APPROVAL` was a no-op decision (kept the proposal at `underReview`, logged an internal-only review event, sent no student notification, generated no project) — a two-tier "recommend, then someone else approves" workflow that added a third UI-visible action beyond the two-step approve→publish split the system already had.
- `ProposalController`'s `list`/`get`/`revisions`/`timeline`/`reviews` (GET) endpoints all required `@PreAuthorize("hasAuthority('eventProposals.read')")`, and `ProposalReview.tsx`'s whole page 403'd (rendered a "no permission" message) for any account lacking it — even though `DataScopeResolver` already independently narrows a student to their own proposals and any non-student role to the full queue (07-RoleFinetune.md §2.1's `grantAll` rule), regardless of this permission.

## Fix

Full detail and reasoning in `Docs/optimization/07-RoleFinetune.md` §2.9 (added this session as a follow-up to that doc's permission-model work). Summary:

**Recommend Approval — full removal, not just hidden:**
- Backend: removed `ProposalDecision.RECOMMEND_APPROVAL`, its state-machine branch in `ProposalApprovalTransaction.execute()`, and the notification no-op branch. Confirmed zero live `event_proposal_review` rows ever recorded this decision before deleting the enum constant (a non-zero count would have required a data migration first, since the column is `@Enumerated(STRING)`).
- Frontend: removed the `ThumbsUp` button, the `recommendApproval` `Decision` type value, `client.ts`'s decision-map entry, and the `admin.proposals.recommendApproval` i18n key (both locales). Consolidated the footer from a split mobile-2x2-without-Cancel / desktop-5-across layout into the same unified `grid-cols-2 sm:flex` + `closeButton="none"` pattern `CaseReview.tsx` already uses for its 3 decisions — 3 decision buttons now fit alongside Cancel in a clean mobile 2x2 grid, so the mobile-only header X is no longer needed either.

**View access decoupled from `eventProposals.read`:**
- Backend: `ProposalController`'s five read endpoints changed from `hasAuthority('eventProposals.read')` to `isAuthenticated()`. View access is now purely role-based via the existing `DataScopeResolver` scope resolution (unchanged) — a student sees only their own proposals, any non-student role sees the full queue, regardless of which specific permissions their role holds.
- Frontend: removed `ProposalReview.tsx`'s page-level `if (!hasPermission('eventProposals.read')) return <NoPermission>` gate. `canReview = hasPermission('eventProposals.review')` is unchanged and still the sole gate for whether decision buttons render (`showDecisions = canReview && ...`) — so the net behavior is exactly what was asked: any non-student can open the page and view a proposal's detail; only an account holding `eventProposals.review` sees the approve/reject/request-changes buttons.
- `eventProposals.read` itself was **not** removed from the permission catalog — it's still a real, assignable permission code (07-RoleFinetune.md §6) for contract completeness, it simply no longer gates anything in this controller. Not extended to Case Review or Audit Center, which keep their existing `cases.read`/`reviews.read` gates — a deliberate, narrower change scoped to Proposal Review only, per the request.

## Test plan

- Backend: `mvn test` (37/37) from a **clean** build (`mvn clean test`, not a plain `mvn test` — this repo has previously hit stale-incremental-compile false positives, per the earlier grade-filter issue this session) and a scoped `mvn verify -Dit.test=ProposalApprovalTransactionIT` (15/15) against real MySQL, both clean.
- Frontend: `tsc --noEmit`/`pnpm build` clean; repo-wide `en.json`/`zh.json` placeholder-parity check clean.
- Live: confirmed no live `event_proposal_review` row ever recorded `RECOMMEND_APPROVAL` before removing the enum constant.

## Resolution

Deployed to `https://tb.example.com/` (backend via rsync + remote `deploy.sh`, then frontend via `frontend/scripts/deploy.sh`).

*Live-verified via browser click-through: Recommend Approval button is gone (3 buttons + Cancel in a 2x2 mobile grid); a submitted proposal opened as `admin001` (holds `eventProposals.review`) still shows Approve/Request Changes/Reject.*
