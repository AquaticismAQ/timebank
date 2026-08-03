# B5 · Admin Surface — Cases, Notifications, Dashboards, Reports, Data Exchange

> Phase B5 of `01-Overview.md`. The largest endpoint count in the plan and the lowest risk per endpoint — with two exceptions that carry real weight: manual correction (it touches the ledger) and historical import (it seeds the ledger at launch).

| | |
| --- | --- |
| **Depends on** | B3, B4 |
| **Blocks** | I3's acceptance scenarios; the launch import rehearsal |
| **Effort / calendar** | 8 pw · W20–W23 |
| **Design refs** | `Design.md` D5, D6, §16.2, §13; `APIContract.md` §3.6, §3.8, §4.1, §4.8, §4.13–§4.15 |

---

## 1. Goal

Everything an administrator needs to run the system for a term: handle appeals and corrections, see the school-wide picture, produce reports, and get historical paper ledgers into the system without corrupting balances.

## 2. Scope

**In:** appeals/supplements/manual corrections, notifications and announcements, dashboards, reports and materialized summaries, exports, imports, user/role administration surfaces, audit and login log queries, system settings.

**Out:** the retention purge and reconciliation jobs (B6); any Phase 3/4 feature.

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B5.1** | `caseflow` module — `appeal` \| `supplement` \| `manualCorrection`, status machine `draft → submitted → inReview → resolved` with `needsInformation` and `rejected` branches, timeline | Contract §4.8 endpoints |
| **B5.2** | **Manual correction → ledger** — a resolved correction appends a reversing or adjusting transaction through `AccountService` (B3.8–B3.10), never an edit | The one case type that touches the ledger; tested to the same standard as B3.12 |
| **B5.3** | Retroactive-entry path — the `supplement` case type carries the late/unsigned service scenario from `01-TimeBankDesign.md` §8.5 (Q6: no pre-registration for off-campus service) | No new resource, no new workflow — a case type and its decision path |
| **B5.4** | Notifications — list, unread count, mark read, mark all read, announcements; rendered from `templateCode` + params per request language (B2.6) | Contract §4.1 |
| **B5.5** | **Cheap polled endpoints** (D2 hard requirement) — `/notifications/unread-count` served by a covering index on `(user_id, is_read)`, `ETag`/`304`-capable, with a server-configurable client poll interval | At 300 concurrent users this endpoint carries ~10 req/s; it must cost almost nothing |
| **B5.6** | Dashboards — `/dashboards/student`, `/teacher`, `/admin`; 30–60 s cache; `generatedAt` returned; **viewer-derived, so never cached across principals** | Contract §4.13; home-page aggregation only, never a mega-endpoint |
| **B5.7** | Reports — participation, completion, service-hours, review-efficiency, top-projects; aggregated in SQL (`GROUP BY`), never in Java loops | Frontend composes the four report calls its single `reports.data()` used to mock |
| **B5.8** | Materialized summaries — participation and compliance by grade computed by a scheduled job into summary tables, served with `generatedAt` (§16.2) | Reports are never computed synchronously over the full ledger |
| **B5.9** | Exports — async `Job` rows, chunked, streamed directly to object storage, short-lived download URL, permission-gated and audited | Contract §4.13; also the mechanism the school uses to preserve cohort records before B6's purge |
| **B5.10** | Imports — templates, create, validate, error list, commit, cancel; **validate before commit is mandatory**; duplicates prevented by file hash + business unique key + `Idempotency-Key` together | Contract §4.14; reversible by import batch id |
| **B5.11** | Import/export execution model (D5) — dedicated bounded executor (never the request pool), ~500–1,000 row chunks per transaction, checkpointing (`processedRows`, `checkpointOffset`), lease claim (`claimedBy`, `leaseExpiresAt`), ShedLock-guarded reclaim sweep | A 500k-row import survives a deploy mid-job and resumes from its checkpoint |
| **B5.12** | Administration surfaces — users, roles, permission catalog, `/audit-logs`, `/login-logs`, `/system/settings` | Contract §4.15; audit logs unreachable by any mutation API (invariant 10) |

## 4. Exit Criteria

**Pass 1 (B5.1-B5.5, B5.12 — done; see `00-Status.md` §17):**

- [ ] An appeal runs end to end and produces a complete, readable timeline (contract §11.6) — **partially verified**: `CaseDecisionTransactionIT` proves an appeal's create→submit→resolve cycle never touches the ledger, but no test calls `CaseService.timeline(...)` and asserts its rendered entries; the endpoint exists and is built the same way `ProposalService.timeline` (B4, proven) is, but isn't itself exercised by a test.
- [x] A manual correction changes a balance **only** by appending a transaction; the original is intact (`CaseDecisionTransactionIT.manualCorrectionResolveGeneratesExactlyOneAccountTransaction`, `.duplicateIdempotencyKeyReplaysInsteadOfDoubleAdjusting`; live verification in `00-Status.md` §17).
- [x] `/notifications/unread-count` returns `304` on an unchanged badge (`NotificationReadPathIT`, live verification §17) — **the "single index-covered query" half is unmeasured**, not proven: the covering index (`idx_notification_recipient_unread`, V8) exists and the query is written to use it, but no `EXPLAIN` test confirms it, the same honesty gap B3/B4 left for their own query-count claims.
- [x] No admin API can modify or delete an audit log row (`AuditLogQueryIT.theControllerHasNoMutatingMappingAtAll` — structural, not permission-based; `AuditServiceIT.repositoryDeclaresNoUpdateOrDeleteMethod` already covered the repository side since B0).

**Pass 2 (dashboards, reports, materialized summaries — not started):**

- [ ] Dashboards return `generatedAt` and are not shared across principals.
- [ ] Report figures for a seeded grade match a hand-computed SQL query — reports that are merely plausible are not verified.

**Pass 3 (exports, imports, executor/chunking/checkpointing infrastructure — not started):**

- [ ] A 500k-row import: validates with a row-level error report, commits successfully, resumes correctly after a forced restart, and **does not degrade p95 latency** for concurrent interactive users (R11).
- [ ] Re-importing the same file does not double-credit anything.
- [ ] Export of a full academic year streams to object storage with bounded heap.

## 5. Risks and Watch-Outs

- **R6 — dirty historical ledgers.** The launch import decides every opening balance. Validate-before-commit, a row-level error report, a dry run, and a reversible batch id are not optional, and the rehearsal in I3 is where they get proven with real school data.
- **Import is where "it was fast on 186 rows" bites.** Chunk and checkpoint from the first commit; retrofitting streaming into a working-but-heap-hungry importer is a rewrite.
- **Dashboard caching across users** is the easiest privacy bug in this phase: a cache keyed only by endpoint would serve one teacher another's scoped counts.
- **Report aggregation in Java** over 5,000 students works in development and collapses on real data (D6). `GROUP BY`, or a summary table.
- **Scope creep.** This phase touches every admin screen and will attract "while we're here" requests. The frontend is built; match it, do not extend it.

## 6. Hand-Off

B6 hardens what now exists. I3 runs the acceptance scenarios and the import rehearsal against a functionally complete backend.
