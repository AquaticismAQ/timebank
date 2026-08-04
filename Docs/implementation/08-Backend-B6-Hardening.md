# B6 · Data Lifecycle, Reconciliation, and Hardening

> Phase B6 of `01-Overview.md`. The phase that converts every performance and capacity claim in `Design.md` §16 from hypothesis into measurement, and builds the two jobs that keep the system bounded and honest.

| | |
| --- | --- |
| **Depends on** | B3, B5, B7 (staging dataset and backups) |
| **Blocks** | Pilot go-live. **Gate G4** |
| **Effort / calendar** | 6 pw · W24–W26 |
| **Design refs** | `Design.md` §16, §18, §19, D12, §22.1 Q7; `APIContract.md` §8 |

---

## 1. Goal

Two jobs that must exist (retention purge, ledger reconciliation), one measurement that must pass (load test at 300 concurrent on production-sized data), and one review that must happen (indexes, slow queries, security).

## 2. Scope

**In:** log partitioning and pruning, session and idempotency expiry, the cohort purge job, checkpointed reconciliation, rate-limit completion, alerting, index and slow-query review, load test, security review.

**Out:** anything that adds a feature. B6 changes no endpoint's behaviour except where a review finds a defect.

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B6.1** | Log lifecycle — `login_log` and `audit_log` retained 1 year, pruned by `DROP PARTITION` on the monthly partitions created in B0.10 | Pruning is instant, never a multi-million-row `DELETE` |
| **B6.2** | Short-lived table sweeps — `session` and `idempotency_record` expiry, ShedLock-guarded | Every table has a lifecycle (`Design.md` principle 9) |
| **B6.3** | **Cohort purge job** (§16.3) — keyed to **graduation year**, never row age; deletes service records, transactions, reviews, cases, enrollments, files, notifications, student and guardian rows at graduation + 1 year | The purge key exists because B2.2 put it there. A row-age purge would destroy a current student's Grade 6 records — unrecoverable (R5) |
| **B6.4** | Purge safety — dry-run mode reporting what would be deleted, the first production run executed deliberately rather than on a timer, the purge itself audited (cohort, time, row counts, policy version), and an alert if it **fails to run** | PIPL makes deletion an obligation; a purge silently not running for years is a compliance failure |
| **B6.5** | Object deletion with rows — the purge removes evidence objects from the bucket in the same pass as the `file` metadata | Deleting only metadata leaves unattributable photographs of minors that nothing records and nobody can reclaim |
| **B6.6** | Storage lifecycle rules — Standard → Infrequent Access (~90 d) → Archive (~1 y), objects deleted with the cohort (D9) | Configured on the bucket, verified against a sample object |
| **B6.7** | **Ledger reconciliation** (D12) — nightly pass over accounts touched since the last checkpoint, weekly full `GROUP BY accountId` re-sum, alert identifying the account and divergence point on any mismatch | Silent ledger drift is the most damaging failure this system could have (R4) |
| **B6.8** | Rate limiting completed — Nginx for login and password reset, Bucket4j for upload-intent and export creation | Verified across two instances, not one |
| **B6.9** | Index and query review — `EXPLAIN` on every list and report path against the seeded dataset; deep-pagination decision per endpoint (keyset vs capped depth); `totalItems` counting strategy per endpoint (exact / cached / approximate) | §16.1 becomes acceptance criteria, endpoint by endpoint |
| **B6.10** | **Load test** (§19) — k6/Gatling against production-sized staging: 300 concurrent sustained; burst of ~500 students submitting within 30 minutes including uploads; 500k-row import concurrent with interactive load; virtual-thread pinning check | Results recorded in `00-Status.md`, not just observed |
| **B6.11** | Alerting and dashboards (§18) — ledger mismatch, job failure, purge non-execution, replication lag, disk and DB capacity, error-rate spike, sustained pool saturation, SLA breaches | Each alert has a named recipient and a documented response |
| **B6.12** | Security review — upload MIME/magic-byte enforcement, EXIF stripping, secrets handling, DB network exposure, sensitive-field suppression spot-check across every list endpoint, CSRF and origin coverage | A findings list with each item closed or explicitly accepted |

## 4. Exit Criteria — Gate G4

- [ ] p95 < 300 ms for list and detail endpoints and < 1 s for dashboards, at 300 concurrent users on production-sized data — **blocked, not attempted**: no production-sized staging environment exists (the shared test server is 2 CPUs/3.8 GB RAM, already flagged unsuitable for this back in B0 — `00-Status.md` §11). User's explicit direction (2026-08-04): build everything genuinely testable now, defer what needs infra rather than fake a small-scale substitute. Blocked on a real staging environment (B7-adjacent, not yet planned).
- [ ] The slow-query log is **empty** for the duration of the load test — blocked, same reason (no load test has run).
- [ ] No virtual-thread pinning observed under load (D3 caveat closed) — blocked, same reason.
- [ ] A 500k-row import completes with bounded heap, resumes after a forced restart, and leaves interactive p95 within target — **partially verified**: bounded-heap streaming design and a real (if small-scale) checkpoint-resume proof exist from B5.9/B5.10 (`ImportExportIT`); an actual 500k-row run, a forced `kill -9`, and any p95-under-load measurement are blocked on the same missing staging environment.
- [x] The purge job's dry run against a seeded graduated cohort reports exactly the expected rows and objects — and reports **zero** rows for currently enrolled students. `CohortPurgeIT.dryRunReportsExactCountsAndRealRunDeletesEverythingLeavingSiblingsUntouched` proves exact per-table counts for a graduated cohort and complete absence of a currently-enrolled sibling from the results; live-verified over HTTPS (`00-Status.md` §19).
- [x] Purge and reconciliation both alert when they do not run. `LifecycleWatchdogJob` checks both `purge_run`/`reconciliation_run` for a successful run within 36 hours and logs a structured `ERROR` if not — a real gap was caught live here: the purge only wrote an audit row per *eligible* cohort, so on the (overwhelmingly common) day with nothing eligible, no row was ever written and the watchdog could not distinguish "ran, nothing to do" from "never ran"; fixed with an explicit "0 eligible" heartbeat row. See §19.
- [x] Reconciliation detects a deliberately injected drift and names the account. `LifecycleJobsIT.reconciliationDetectsAndNamesADeliberatelyInjectedDrift` corrupts `account.balance` directly (bypassing `AccountService` entirely) and confirms the mismatch is reported with the correct stored/computed balances and the divergent transaction id; a clean sibling account is confirmed absent from the same run's results.
- [x] Log partition pruning demonstrated on the seeded dataset. `LifecycleJobsIT.droppingAPartitionRemovesExactlyItsRowsAndNoOthers` proves `DROP PARTITION` removes exactly the targeted month's rows and leaves an adjacent partition's rows untouched; `ensuringAFuturePartitionAddsExactlyOneNamedPartition` proves the ahead-of-need `REORGANIZE PARTITION` path.
- [ ] Rate limits verified against both application instances — **partially verified**: Bucket4j enforcement is proven correct for a single instance (`RateLimitInterceptorTest`, per-principal buckets, correct 429 on the 6th request within a window); a live two-instance test is not possible on this environment (one systemd instance exists). The per-instance limitation itself is an accepted design trade-off (D1: "soft rate limits are per-instance"), not a defect to close.
- [x] Security review findings closed or explicitly accepted with a named owner. See `00-Status.md` §19 for the full findings list — one real gap found (EXIF/metadata stripping was entirely missing) and fixed this pass; everything else reviewed and confirmed already correct.

## 5. Risks and Watch-Outs

- **R5 is the highest-consequence item in this phase.** A purge bug is irreversible in a way no other defect here is. Dry-run first, cohort-keyed always, audited every time, and a reconciliation check before deletion.
- **A load test that only produces a number is wasted.** The value is in what it changes: the missing index, the deep-pagination cap, the count strategy. Budget time for the fixes, not just the run.
- **Alert fatigue starts here.** Twelve alerts nobody owns are worse than four that someone answers. Assign each one.
- **Staging must be production-sized** or every measurement in this phase is fiction — which is why the seeder was B0.9 and not B6.1.

## 6. Hand-Off

The backend is feature-complete and measured. Remaining work is integration acceptance (I3), the operations drill (B7 G3 repeat), and cutover.
