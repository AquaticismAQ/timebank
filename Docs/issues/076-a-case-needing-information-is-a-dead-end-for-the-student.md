# 076 — A case in "Needs Information" is a dead end: the student can see the request but cannot answer it

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found while verifying the case flow (student files → admin resolves /
rejects / requests information) end to end.

An administrator decides **Request Information** on a case. The case moves
to `NEEDS_INFORMATION` and the reviewer's question reaches the student
correctly — it is visible in the case's timeline under My Cases.

And there it stops. The student has **no action of any kind** on that
case. `StudentCases.tsx` renders every case as a single expand/collapse
button and nothing else, so the reviewer's question can never be answered,
the case can never return to `SUBMITTED`, and it sits in
`NEEDS_INFORMATION` permanently.

That breaks the loop the state machine was explicitly written to support.

## Root cause

Purely a missing UI affordance — everything underneath it already works:

- `CaseStateMachine` has the edge:
  `rule(CaseStatus.NEEDS_INFORMATION, CaseStatus.SUBMITTED, ActorRole.OWNER)`.
- `CaseServiceImpl.updateDraft` and `doSubmit` both explicitly accept
  `NEEDS_INFORMATION` alongside `DRAFT`
  ("Only a draft or needs-information case can be edited/submitted").
- `api.cases.update` and `api.cases.submit` both exist in the client and
  are otherwise unused for this path.

So the backend, the state machine and the API client were all built for
this loop, and only the button was never added. Compare the equivalent
loops elsewhere, which *do* have one: a returned service record has
**Resubmit**, and a proposal with changes requested has **Revise &
Resubmit**.

## Fix

`StudentCases.tsx` gains a **Respond & Resubmit** action, shown only on
cases in `needsInformation`. It opens a small modal with the reviewer's
question quoted above the input (so the student answers the actual
question rather than recalling it), takes the student's reply, then
`PATCH`es the case description and `POST`s `/submit` — the same
two-call shape the filing modal already uses.

The reply is appended to the existing description rather than replacing
it, so the original request and the answer both survive; the case's own
revision history (`writeRevision` in `updateDraft`) keeps the prior text
either way.

## Test plan

- `tsc --noEmit`, `pnpm build`, en/zh parity.
- Live: with a case sitting in `NEEDS_INFORMATION`, confirm the action
  appears (and does *not* appear on cases in other statuses), respond,
  and confirm the case returns to `Submitted`, the reply is visible to
  the reviewer, and the reviewer can then resolve it.

## Resolution

`frontend/src/app/components/student/StudentCases.tsx` — Respond &
Resubmit action plus its modal, `PATCH` + `submit`.
`frontend/src/i18n/{en,zh}.json` — `student.cases.respond*` keys.

## Also confirmed during this pass (not bugs)

- **Teachers cannot decide cases, by design.** `cases.decide` is granted
  only to `role_admin`/`role_sysadmin`
  (`R__seed_permissions_and_roles.sql`), so `teacher001` sees the Case
  Review queue and case details but no Resolve/Request Information/Reject
  buttons. `07-RoleFinetune.md` §188 explicitly declines to extend the
  "anyone non-student can view, only permission-holders can act" relaxation
  to Case Review. Worth knowing when reading a request for "teacher
  resolve/reject" — it is an admin-only action today.
