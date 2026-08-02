# I2 · Write Path Wiring

> Phase I2 of `01-Overview.md`. The surface with **no prior validation of any kind**: today every mutation in the frontend ends in `toast.success(...)` and no request body has ever been constructed. This is where integration gaps concentrate (risk R12).

| | |
| --- | --- |
| **Depends on** | I0 (G1), I1 for the same domain, and each domain's backend phase |
| **Blocks** | I3 acceptance |
| **Effort / calendar** | 6 pw · W14–W22 |
| **Design refs** | `APIContract.md` §13.2, §13.4, §2.8, §3; `Design.md` §11, §12, R12 |

---

## 1. Goal

Turn every stubbed action into a real mutation with honest failure handling — validation errors on the right fields, conflicts that do not silently overwrite, and duplicate submissions that do not double-credit.

## 2. Sequencing

| Order | Domain | Actions | Ready after | Target |
| --- | --- | --- | --- | --- |
| 1 | Service records | create draft, update, delete draft, submit, withdraw | B3.1–B3.3 | W14–W16 |
| 2 | Evidence upload | intent → PUT → complete → poll → attach | B3.4–B3.5 | W16 (detail in I3.1) |
| 3 | Review decisions | approve, return, reject, escalate, assign, bulk | B3.6–B3.7, B3.12 | W17 |
| 4 | Account operations | adjustment, reversal, freeze/unfreeze | B3.10 | W18 |
| 5 | Projects | create, update, publish, close, archive | B4.1–B4.2 | W19 |
| 6 | Registrations | register, cancel, confirm, reject, bulk actions | B4.6–B4.8 | W20 |
| 7 | Proposals | create, update, submit, withdraw, review decisions | B4.9–B4.11 | W21 |
| 8 | Cases & admin | appeals, supplements, corrections, notifications, imports, exports | B5 | W22 |

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **I2.1** | Wire each stubbed handler to its client method — the `toast.success(...)` placeholders become real calls with real bodies per `APIContract.md` §13.4 | Optimistic UI is **not** used for anything that touches the ledger; the toast follows the response, not the click |
| **I2.2** | Request bodies contain only writable fields — no status fields, no denormalized display names, no server-computed values (§13.4 general rule) | The backend rejects these with `422`; sending them is a frontend bug, not a tolerated habit |
| **I2.3** | Validation error mapping — `fieldErrors` land on the corresponding form fields with the server's message, and the form does not clear the user's input on failure | The record submission form is the hardest case: ~15 fields with cross-field rules |
| **I2.4** | Action endpoints, never `PATCH {status}` — submit, publish, close, archive, confirm, cancel each call their own endpoint | Contract §4.2; the generic `PATCH` rejects status changes |
| **I2.5** | Idempotency keys attached to record submission, review decisions, account adjustments, proposal approval, and import commit; a key is **generated once per user intent** and reused on retry | Contract §2.8; a retried submit must replay, not duplicate |
| **I2.6** | Conflict handling — `409` shows "someone else changed this", offers a refresh, and never silently overwrites; the server's current version is adopted on refresh | Contract §11.10 acceptance scenario |
| **I2.7** | Two-step flows with server-owned outcomes — proposal approval shows the generated project, review approval shows the credited amount **returned by the server**, never a locally computed figure | The frontend displays state; it does not derive it |
| **I2.8** | Draft lifecycle — only drafts are deletable, and the UI reflects that rather than offering a delete that will fail | Contract §2.9 |
| **I2.9** | Bulk actions — partial-failure results rendered row by row; a batch is not reported as successful because it returned `200` | B4.8, B5's bulk review |
| **I2.10** | Async job flows — import and export create a job and poll `GET /imports/{id}` while the modal is open, with progress, errors, and a cancel path | Contract §3.8; polling stops when the modal closes |
| **I2.11** | Permission-aware affordances — actions the principal lacks permission for are absent or disabled with a reason, and the backend rejection is still handled (the UI is a convenience, not the check) | Contract §2.4 |
| **I2.12** | Feature-flag gating — Phase 3/4 entry points show "Not yet available" rather than a broken action | `Design.md` §14; nothing beyond the flag is wired |

## 4. Exit Criteria

- [ ] Every action in the eight domains performs a real request; no `toast.success(...)` stub remains.
- [ ] A submission with invalid fields shows server errors on the right fields and preserves the user's input.
- [ ] Double-clicking submit, or retrying after a network failure, credits exactly once.
- [ ] A concurrent edit produces a clear conflict, and the second writer never silently overwrites the first.
- [ ] Approving a review shows the credited amount **from the response**, and the balance the account screen then reads matches it.
- [ ] Approving a proposal shows the generated project draft.
- [ ] A bulk action with one failing row reports that row and applies the rest, per the server's result.
- [ ] An import runs from the UI: upload, validate, review errors, commit, and see the job complete.
- [ ] Actions the principal may not perform are not offered — and are still rejected correctly if forced.
- [ ] `APIContract.md` §11 scenarios 1–5 and 9–10 pass end to end through the UI.

## 5. Risks and Watch-Outs

- **R12 in one line: this is all new ground.** Every other phase adapts something that exists; I2 builds behaviour that has never run. Estimate accordingly and integrate domain by domain rather than saving it for the end.
- **Optimistic UI on ledger operations is the wrong instinct.** Showing "approved" before the transaction commits invites a UI that disagrees with the ledger — the one disagreement this system cannot tolerate.
- **Idempotency keys regenerated per retry defeat the mechanism.** The key belongs to the user's intent (one submission), not to the HTTP attempt.
- **Forms silently dropping fields.** A field the mock accepted and the contract does not will fail at `422` — and the fastest wrong fix is to delete it from the form. Check §13.4 first: it may be the contract that is missing something, in which case both documents change in one PR (§10.2).
- **Error messages for students.** Server messages are written for correctness; some need student-facing phrasing in both languages. Budget for it rather than shipping `VALIDATION_FAILED` to a Grade 6 student.

## 6. Hand-Off

I3 takes a fully wired application into evidence upload, localization verification, acceptance, and cutover.
