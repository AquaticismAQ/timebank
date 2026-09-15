# 080 — The proposal status `underReview` is unreachable and should go

Status: **Fixed and live-verified (2026-09-16)**

## Question that started it

Asked directly by the user, looking at Proposal Review:

> for 'Proposal Review', what's the status 'Under Review' mean, i didnt find
> the way to set a proposal to status 'Under Review', do we really need it?

## Finding

There is no way to set it, because nothing can leave a proposal in it.

- `ProposalApprovalTransaction` steps `SUBMITTED → UNDER_REVIEW` and then on
  to the decision's real target **inside the same transaction**, so the
  intermediate state is never committed and never observable.
- The one decision that used to park a proposal there — `recommendApproval`,
  "keep at `underReview`, log an internal-only endorsement" — was **removed
  on 2026-08-20** as an unnecessary third review tier (recorded in
  `ProposalDecision`'s javadoc and `APIContract.md` §3.2). Removing it left
  the status with no way in and nobody noticed.
- `POST /event-proposals/{id}/assign` exists but only sets
  `assignedReviewerId`; it deliberately does not touch status. So even
  claiming a proposal does not produce `underReview`.

The Proposal Review status filter therefore offers a value that can never
match anything, and `showDecisions` tests for a status no proposal can hold.

Confirmed against the live database before deciding: **0** proposals in
`UNDER_REVIEW`, **0** proposals with an assigned reviewer, and **0**
`audit_log` rows for `resource_type = 'eventProposal'` whose before/after
summary mentions `UNDER_REVIEW`. Nothing anywhere depends on the value.

Note the service-record and case flows are **not** affected and keep their
own `underReview`/`inReview` states: a service record really can rest in
`UNDER_REVIEW` (that is what the `escalate` decision does), and `Case` has
`IN_REVIEW`. This is specific to proposals, which lost their only reason for
the state when the two-tier review went away.

## Decision

Remove it, per the user's choice among hiding the filter, giving it meaning
via `assign`, and deleting it outright. It models nothing the system does,
and a status that cannot occur is worse than no status: it invites exactly
this question, and every `switch`/filter/label has to carry a dead branch.

`ProposalStateMachine` gains the direct edges `SUBMITTED → APPROVED /
CHANGES_REQUESTED / REJECTED` that the two-step hop was standing in for, so
the decision transaction transitions once instead of twice.

**The V7 migration's column comment is left alone.** `event_proposal.status`
is a plain `VARCHAR(20)` — no database constraint enumerates the values, so
no migration is required — and V7 is already applied everywhere; editing it
would change its Flyway checksum and break validation on every existing
deployment. The stale `UNDER_REVIEW` in that comment is a historical record
of what the column held when it was written, which is what an applied
migration is for.

## Test plan

- `mvn test` (`ProposalStateMachineTest` rewritten for the direct edges).
- `tsc --noEmit`, `pnpm build`, en/zh key parity.
- Live: the Proposal Review filter no longer offers the status; submitting a
  proposal and approving / rejecting / requesting changes still works, still
  audit-logs `SUBMITTED → <target>`, and still generates the project draft on
  approval.

## Resolution

Backend:

- `ProposalStatus` — `UNDER_REVIEW` removed; javadoc records why.
- `ProposalStateMachine` — the three `UNDER_REVIEW → …` edges replaced by
  direct `SUBMITTED → APPROVED / CHANGES_REQUESTED / REJECTED`.
- `ProposalApprovalTransaction` — the implicit hop deleted; one transition per
  decision. The audit entry's `before` is unchanged (it always recorded the
  pre-decision status, `SUBMITTED`, not the intermediate).
- `ProposalServiceImpl.queueSummary` — pending is `statusIn(SUBMITTED)`.
- `ProposalStateMachineTest` — rewritten for the direct edges, plus a new
  case that an OWNER still cannot decide their own proposal.

Frontend:

- `api/types.ts` — `ProposalStatus` union;
  `components/admin/ProposalReview.tsx` — status filter and `showDecisions`;
  `lib/statusLabels.ts` and `i18n/{en,zh}.json` — the label removed.
  (`ReviewStatus`/`RecordStatus` keep their own `underReview` — different flow.)

Docs: `APIContract{,_CN}.md` §3.2 diagram and divergence-table row 1.

## Live verification (2026-09-16)

- The Proposal Review status filter now offers **Pending Review / Changes
  Requested / Approved / Rejected** only.
- **Request Changes** through the real UI: `SUBMITTED → CHANGES_REQUESTED`,
  "Decision recorded".
- **Approve** via the API against a live proposal: `200`, status `APPROVED`,
  and `approvedProjectId` present **in the same response** — so B4.11's
  atomic project generation still happens with the hop gone. The audit log
  shows `eventProposal.reviewDecision "SUBMITTED" -> "APPROVED"` and
  `project.generatedFromProposal` for it.
- **Reject** was not driven live in this pass (no `SUBMITTED` proposal was
  left after the other two, and manufacturing one would have churned more
  live data than the check is worth). It is covered by
  `ProposalStateMachineTest`, and all three decisions now run the identical
  single-`transition` code path, so nothing about reject is special.

Live data left behind by this pass: one seeded proposal moved to
`CHANGES_REQUESTED` and one to `APPROVED`, the latter generating a draft
project (`proj_vyhg0j8…`), which is the normal consequence of approving.
