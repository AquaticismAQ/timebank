# B3 · Core Loop — Records, Evidence, Review, Ledger, Progress

> Phase B3 of `01-Overview.md`. The phase the whole system exists for, and the one a graduation audit years from now will be judged on. Built early and never compressed.

| | |
| --- | --- |
| **Depends on** | B1, B2 |
| **Blocks** | B4's completion path, B5's reports, I2's first real mutations. **Gate G2** |
| **Effort / calendar** | 12 pw · W9–W15 |
| **Design refs** | `Design.md` §12 (invariants), D5, D6, D9, D12, §11; `APIContract.md` §3.4–§3.7, §4.5–§4.7, §4.11, §13.4 |

---

## 1. Goal

A service record travels draft → submitted → reviewed → credited, with evidence attached, and the approval performs **five side effects in one transaction or none of them**: review event, record status, account transaction, progress update, notification.

## 2. Scope

**In:** service records with revisions, evidence upload and scanning, submission validation, review queue and decisions, the immutable ledger, stored balances with reconciliation hooks, progress projection, approval notifications.

**Out:** appeals and manual corrections (B5 — but the ledger's reversal mechanism is built here); bulk review (B5); project-linked completion (B4); dashboards (B5).

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B3.1** | `servicerecord` module — entity, revisions, `sourceType` (`project` \| `external` \| `self`), draft CRUD, delete-drafts-only, timeline and revision endpoints | Contract §4.5; a submitted record cannot be deleted by any code path that exists |
| **B3.2** | State machine — explicit transition table with `canTransition(from, to, actor)` and a single `transition()` entry point; **no scattered `if (status == …)`** | Applies to `ServiceRecord` here and is the template for `Project`, `EventProposal`, `Application`, `Case` |
| **B3.3** | Submission validation (§4.5) — required fields per `sourceType`, time and duration rules, project eligibility and registration relationship, **duplicate detection**, evidence completeness, travel-time disclosure blocking credit (Q5) | Runs at `/submit`, not at create; each rule individually tested with its own error code |
| **B3.4** | `file` module — `POST /files/upload-intents` → presigned PUT → `POST /files/{id}/complete` → async magic-byte type check, size, AV scan, EXIF-stripping re-encode, SHA-256 | `S3FileStorage` port behind MinIO (test server, native binary under systemd) and OSS (prod). **The app never proxies file bytes** |
| **B3.5** | File access control — `GET /files/{id}` checks permission and scope, then issues a short-lived URL; access to another student's evidence writes an audit entry | A `pending`/`processing`/`rejected` file cannot be bound to a submission — enforced at submit, not in the UI |
| **B3.6** | `review` module — queue projection with `daysWaiting`, `priority`, rule-based `hasAnomaly`/`anomalyNote` (not AI in Phase 2), assignment/transfer, SLA summary | Contract §4.7; the queue is a purpose-built read DTO via `JdbcTemplate`, never an entity graph (D6) |
| **B3.7** | Review decisions — `approve` \| `returnForChanges` \| `reject` \| `escalate` as **appended, non-overwritable events** (invariant 3), carrying `reasonCode`, `comment`, `recordVersion` | Changing a decision appends another event; history is complete and readable |
| **B3.8** | `account` module — accounts, **append-only** `account_transaction` (`amount` in signed centi-coins, `balanceAfter`, `sourceType`, `sourceId`, `reasonCode`, `operatorId`), repository with no update or delete method | Invariants 1–2; corrections and reversals append opposite-signed entries referencing the original |
| **B3.9** | Ledger serialization — every mutation through `AccountService`, `SELECT … FOR UPDATE` on the account row, transaction appended and balance updated together (D12) | Concurrent credits interleave correctly under a concurrency test against the test server's real MySQL |
| **B3.10** | Adjustments, reversal, freeze/unfreeze endpoints (contract §4.11), each requiring a standardized reason code | Reversal creates a reverse transaction; the original is untouched |
| **B3.11** | `progress` module — stored projection per student, updated inside the approval transaction, computed against the rule version in effect at the **service date** (invariant 8) | `GET /students/{id}/progress`; never recomputed over full history on page load |
| **B3.12** | **The approval transaction** — review event + record status + account transaction + progress + notification, atomically, with `Idempotency-Key` (invariant 5) | A forced failure at each of the five steps rolls back all of them, proven by five tests |
| **B3.13** | Crediting calculation — exact minutes stored, rounded to the nearest 30 at crediting only, converted by the versioned rule, result an exact integer in centi-coins | No `float`, `double`, or `BigDecimal` anywhere in the duration or coin path |

## 4. Exit Criteria — Gate G2

- [ ] A record goes draft → evidence uploaded → submitted → returned → resubmitted → approved, **demonstrated on the real frontend** with the mock client disabled.
- [ ] Approving that record produced exactly one ledger entry, one progress update, one notification, and one review event.
- [ ] **Concurrent approval of the same record credits exactly once** (contract §11.9).
- [ ] A duplicate `Idempotency-Key` replays the stored response instead of double-crediting.
- [ ] A forced failure in any of the five approval steps leaves *none* of them committed.
- [ ] Reversal produces an opposite transaction and leaves the original byte-identical.
- [ ] Publishing a new rule does not alter any already-credited record.
- [ ] Concurrent edits to one record return `409` with the current version; no silent overwrite.
- [ ] A file in `processing` cannot be attached to a submission; a rejected file cannot either.
- [ ] Evidence download issues a short-lived URL and writes an audit entry for cross-student access.
- [ ] Review queue and record list endpoints run against the seeded dataset with no N+1 and no unindexed scan (query-count test + `EXPLAIN`).

## 5. Risks and Watch-Outs

- **R4 — a code path that bypasses `AccountService`.** The repository exposing no balance setter is the structural defence; a code-review rule is the human one; B6's reconciliation is the alarm. All three, not one.
- **The transaction is the product.** If schedule pressure arrives, cut queue polish, cut bulk actions, cut anomaly heuristics — never split B3.12 into "credit now, notify later". A partially-applied approval is the failure mode this design was written to prevent.
- **Evidence of minors.** Every path that returns a file URL must have passed a permission check in the same request. Short-lived URLs are not access control on their own.
- **Duplicate detection is a business rule, not a unique index.** Same student, overlapping time window, same project. Specify it explicitly with the school before implementing, and make it a warning-plus-review-routing rather than a hard block where the rule is fuzzy.
- **Query discipline starts here.** The review queue and the record list are the two busiest admin surfaces at 5,000 students. Build them as SQL projections from day one rather than refactoring them in B6.

## 6. Hand-Off

B4 attaches projects and registrations to a proven record pipeline. I2 wires its first genuine mutations against endpoints whose request bodies were frozen at G1.
