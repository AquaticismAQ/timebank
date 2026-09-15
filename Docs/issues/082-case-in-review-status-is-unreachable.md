# 082 — The case status `inReview` is unreachable too

Status: **Fixed and live-verified (2026-09-16)**

## Question that started it

Asked directly by the user, looking at the new My Cases page:

> for student 'My Cases' page, what's the different of 'Submitted' and 'In
> review'?, why we need the 'In review'?

## Finding

There is no difference a student can observe, because nothing can leave a
case in `IN_REVIEW`. This is issue 080 again, in the sibling module:

- `CaseDecisionTransaction` steps `SUBMITTED → IN_REVIEW` and then straight
  on to the decision's real target (`RESOLVED` / `NEEDS_INFORMATION` /
  `REJECTED`) **inside the same transaction**, so the intermediate state is
  never committed and never observable.
- Cases do not even have the assign endpoint proposals have.
  `assignedReviewerId` exists on `Case` and on `CaseDto`, but **nothing in
  the codebase ever writes it** — so there is no claim step that could park a
  case in this status either.

Confirmed against the live database before deciding: **0** cases in
`IN_REVIEW`, **0** with an assigned reviewer, and **0** `audit_log` rows for
`resource_type = 'case'` mentioning it.

The practical consequence: the status filter chip added to My Cases in issue
081, and the admin Case Review filter, both offer a value that can never
match anything.

## Correction to issue 080

Issue 080's doc states that "service records and cases keep their own
`underReview`/`inReview`, which are real resting states." That is true of
service records — `ReviewDecisionTransaction`'s `escalate` decision really
does park a record in `UNDER_REVIEW` — and **wrong about cases**, which were
asserted without being checked. 080's doc is corrected as part of this
change.

## Decision

Remove it, per the user's choice among the same three options offered for
proposals (remove / give it meaning via a claim action / hide the filters).
`CaseStateMachine` gains the direct edges `SUBMITTED → RESOLVED /
NEEDS_INFORMATION / REJECTED` that the two-step hop stood in for.

`assignedReviewerId` is **kept**. Unlike the status, it is inert rather than
misleading: nothing displays it to a student, and it is the natural field a
future claim feature would use. Removing a column that costs nothing and
misleads nobody is churn for its own sake.

**The V8 migration's column comment is left alone**, same reasoning as 080 —
`case_record.status` is a plain `VARCHAR`, no constraint enumerates the
values, and editing an applied migration would break its Flyway checksum.

## Test plan

- `mvn test` for the new edges.
- `tsc --noEmit`, `pnpm build`, en/zh key parity.
- Live: a case still resolves / is rejected / has information requested, each
  audit-logged as `"SUBMITTED" -> <target>`; neither the student's nor the
  admin's status filter offers In Review.

## Resolution

Backend:

- `CaseStatus` — `IN_REVIEW` removed; javadoc records why.
- `CaseStateMachine` — the three `IN_REVIEW → …` edges replaced by direct
  `SUBMITTED → RESOLVED / NEEDS_INFORMATION / REJECTED`.
- `CaseDecisionTransaction` — the implicit hop deleted; one transition per
  decision. The audit entry's `before` is unchanged: it always recorded the
  pre-decision status (`SUBMITTED`), never the intermediate.
- `CaseStateMachineTest` — rewritten for the direct edges, plus a case that an
  OWNER still cannot decide their own case. `CaseDecisionTransactionIT`
  unchanged and still 9/9 green on the server, including issue 081's
  withdraw/delete cases which exercise the neighbouring edges.

Frontend:

- `api/types.ts` `CaseStatus`; `StudentCases.tsx` status chips and
  `OPEN_STATUSES`; `CaseReview.tsx` filter and `showDecisions`;
  `StudentHome.tsx`'s "with reviewer" count; `api/client.ts`'s
  `cases.pendingCount` (now `submitted` + `needsInformation`);
  `lib/statusLabels.ts` and both i18n files.

Docs: `APIContract{,_CN}.md` §3.6's diagram, and the correction in issue 080.

## Live verification (2026-09-16)

- Admin Case Review's status filter offers **已提交 / 需补充信息 / 已解决 /
  已驳回**; the student's My Cases chips read **All / Draft / Submitted /
  Needs Information / Resolved / Rejected**. No In Review on either.
- Decided a real submitted case through the UI (**要求补充信息**): it moved
  straight to `NEEDS_INFORMATION`, and the audit log shows
  `case.decision "SUBMITTED" -> "NEEDS_INFORMATION"` — one step, no
  intermediate.
- Resolve and reject were not re-driven live in this pass: all three
  decisions now run the identical single-`transition` code path, they were
  driven live on 2026-09-15 under the old code, and the state machine test
  covers each edge.

Live data left behind: the seeded "Overnight Watch Program" supplement moved
from `SUBMITTED` to `NEEDS_INFORMATION`.
