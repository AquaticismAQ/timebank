# 077 — Case decisions (resolve / request information / reject) are never written to the audit log

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found while verifying the case flow end to end, by doing what the
"every mutation must audit-log" rule actually asks for: not reading the
code, but querying `GET /audit-logs` after the decisions.

Three case decisions were made as `admin001` — Request Information,
Resolve, Reject. Afterwards the audit log contained only the *student's*
actions:

```
15:54:34 case.submit      (student's reply)
15:54:34 case.update      (student's reply)
15:48:54 case.submit      (original filing)
15:48:54 case.create      (original filing)
```

Not one entry for any of the three administrator decisions.

## Root cause

`CaseDecisionTransaction` does not inject `AuditService` at all — there is
no `audit` import, no field, no call. The decision writes a
`CaseDecisionEvent` (the append-only business record) and sends a
notification, and stops there.

The string that looks like an audit action is not one:

```java
private void notifyStudent(Case c, CaseDecision decision) {
    String templateCode = switch (decision) {
        case RESOLVE -> "case.resolved";
        ...
```

`case.resolved` is a *notification template code*. Grepping for
`case.resolved` finds it and it reads like an audit action, which is
probably how this stayed hidden.

Every comparable decision path *is* audited, which is what makes this an
omission rather than a design choice:

| path | audit action |
| --- | --- |
| service-record review | `serviceRecord.reviewTransition` |
| proposal review | `eventProposal.reviewDecision` |
| case create/update/submit | `case.create` / `case.update` / `case.submit` |
| **case decision** | **— nothing —** |

It also matters more here than the gap's size suggests: a
`MANUAL_CORRECTION` case resolved by an administrator triggers a **ledger
adjustment** in the same transaction, so time coins could move with no
audit entry naming the decision that moved them. (The adjustment itself is
separately audited by `AccountService`, so the money is traceable — but the
decision authorising it was not.)

## Fix

Inject `AuditService` and record the decision alongside the existing
decision event, mirroring `ProposalApprovalTransaction`'s entry exactly in
shape: actor, the `cases.decide` permission used, the status before and
after, and the reason code.

Status *before* is captured before the state-machine transitions, so the
entry shows the real `SUBMITTED → RESOLVED` (or
`NEEDS_INFORMATION → …`) movement rather than the intermediate
`IN_REVIEW`.

## Test plan

- `mvn test`; `CaseDecisionTransactionIT` extended to assert an audit
  entry is written for a decision.
- Live: make a decision and confirm it appears in a real
  `GET /audit-logs` query — the same check that found the gap.

## Resolution

`backend/.../caseflow/application/CaseDecisionTransaction.java` — injects
`AuditService`, records `case.decision` with from/to status and reason code.
`backend/.../caseflow/application/CaseDecisionTransactionIT.java` — asserts
the audit entry exists.
