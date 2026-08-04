# TimeBank Operations Runbooks

> B7.14's handover deliverable. Written for the school IT staff who will operate this system after go-live — not for the people who built it. Each procedure states plainly whether it has actually been rehearsed or only documented; do not treat an unrehearsed procedure as trustworthy under real pressure. See `Docs/implementation/09-Backend-B7-DatabaseOps.md` for why this track exists and what each procedure is for.
>
> **Status as of 2026-08-04:** the system runs as a single instance against a single, self-hosted MySQL server (the "test server," `tb.example.com`) — there is no staging environment, no replica, and no offsite object storage yet. Every procedure below that needs one of those is marked **BLOCKED** and describes what it will do once that infrastructure exists, not what has been tried. Only the Cohort Purge and Alert Response runbooks describe something that has actually run, been tested, and been observed working.

---

## 1. Backup & Restore

**Status: BLOCKED — documented procedure, not implemented, not rehearsed.** Needs B7.2 (replica, so backups never load the primary), B7.5 (nightly XtraBackup to object storage), and B7.6 (binlog shipping for PITR) — none of which exist. There is currently **no automated backup of any kind** for the live `timebank` database on the test server; a hardware failure or operator mistake today would lose everything since the last manual export, if any.

**What is missing, concretely, before this runbook can be trusted:**
1. An object-storage account (S3-compatible) reachable from the database host or its replica.
2. A replica host, so `xtrabackup` reads from the replica, never the primary (Design.md §17.1).
3. `xtrabackup` (or an equivalent physical-backup tool) installed and scripted to run nightly, stream to that object storage, and alert on failure.
4. Binlog shipping (continuous copy of the binlog files already being generated — see §2 below — to the same object storage) for point-in-time recovery between nightly backups.
5. A scratch MySQL instance to restore into weekly and check row counts/checksums against the source (B7.7) — the "an unverified backup is not a backup" principle from the phase doc.

**Procedure, once the above exists (drafted now so it isn't designed from scratch under pressure later):**
1. Identify the target: a specific nightly backup (for a full restore) or a point in time (for PITR, using the nightly backup plus binlog replay up to the target timestamp).
2. Provision or reuse a scratch/recovery MySQL instance — never restore over the live primary in place.
3. Restore the physical backup (`xtrabackup --prepare` then `--copy-back`, or the equivalent for whatever tool is actually adopted).
4. For PITR: replay binlog events from the backup's end position up to the target timestamp using `mysqlbinlog`.
5. Start MySQL on the recovered data directory; run the row-count/checksum verification (the same check B7.7's automated weekly job would run) against known table counts.
6. Record: which backup/point-in-time was used, wall-clock time from "restore initiated" to "verified queryable" (this is the RTO measurement), and how far behind the failure point the restored data is (the RPO measurement).
7. Only after verification succeeds does the recovered instance become the new primary (see the Failover runbook, §2) or get used to extract specific lost data.

**Do not consider this runbook trustworthy until §B7.8's drill has actually been run once, timed, with the RPO/RTO figures recorded in `00-Status.md` — not assumed from this document.**

## 2. Failover

**Status: documented, NOT rehearsed** — Design.md explicitly rejects automatic failover for a two-node, small-ops-team setup ("it causes split-brain more often than it prevents downtime"), so this will always be a manual, deliberate procedure, not a script. It cannot be rehearsed on staging because no staging/replica topology exists yet (B7.1/B7.2).

**When to use this:** the primary MySQL host is down, corrupted, or unreachable, and a replica exists and is caught up (or acceptably close, per the last known replication lag reading from monitoring, §7.10).

**Procedure (once a primary+replica topology exists):**
1. **Confirm the primary is actually down**, not just slow — check from at least two vantage points (the application host and a separate network path) before touching anything. A false failover under a transient network blip is worse than the outage it "fixes."
2. **Stop the application** (`systemctl stop timebank-backend` on every instance) — never let the app write to a primary that might come back inconsistently, and never let it start writing to a replica still catching up.
3. **Confirm the replica's replication position** (`SHOW REPLICA STATUS` — check `Seconds_Behind_Source` and that the I/O and SQL threads were both running up to the last received event). If the replica is behind, the gap between its last-applied transaction and the primary's failure moment is unrecoverable data loss — record this gap; it is the real RPO for this incident, not the target figure.
4. **Promote the replica**: stop its replication threads (`STOP REPLICA`), reset it as a standalone writable server (`RESET REPLICA ALL`; remove `read_only`/`super_read_only`).
5. **Point the application at the new primary** — update the connection host in `/etc/timebank-backend.env` (`TIMEBANK_DB_HOST`), then restart the application.
6. **Verify**: `GET /api/v1/ping`, then a real authenticated read (`/me`), then confirm a write path works (e.g., a throwaway idempotent action) before declaring the incident over.
7. **Do not attempt to bring the old primary back as a replica of the new one without a full re-sync** (`xtrabackup`-based rebuild or `mysqldump`) — assume its data has diverged and cannot be trusted for replication just by restarting it.
8. Write an incident record: what failed, when, promotion time, data-loss window (from step 3), and total downtime — this feeds the RTO/RPO history the same way a restore drill does.

**This procedure has never been executed, on staging or otherwise.** The phase doc's own exit criterion — "executed on staging by someone who did not write it" — is unmet. Treat every step above as a draft to be corrected the first time it is actually tried.

## 3. Patching

**Status: documented, NOT rehearsed** — needs a staging environment (B7.1) to rehearse on before ever running against the live server.

**Procedure:**
1. Read the MySQL release notes for the target minor version — specifically the "Changes Affecting Upgrades" section; look for changed defaults, deprecated variables, or storage-engine behaviour changes (this project depends on several defaults being exactly what they are today, per B7.3 — see the pinned config comment in `/etc/mysql/mysql.conf.d/mysqld.cnf`).
2. Take a fresh backup immediately before patching (once §1 exists) — a patch is exactly the kind of change that should never run against an unbacked-up database.
3. Rehearse the exact upgrade command sequence on staging first, including a full application smoke test afterward (`verify.sh`-equivalent against staging data).
4. Only once the staging rehearsal is clean, schedule a maintenance window for production, repeat the same steps, and monitor error logs and slow-query logs closely for the first hour after.
5. Have the previous minor version's packages cached/available locally in case a downgrade is needed — "roll forward only" is not a safe assumption for a database patch the way it can be for the application.

**This has never been rehearsed, on staging or otherwise**, since no staging exists. Do not patch the live database's MySQL version using only this document as guidance without first provisioning somewhere to rehearse on.

## 4. Cohort Purge Execution

**Status: implemented and tested — real, not aspirational.** Unlike the sections above, this procedure describes code that exists, has passed integration tests against real MySQL, and has been exercised over live HTTPS (`Docs/implementation/00-Status.md` §19). See `Docs/implementation/08-Backend-B6-Hardening.md` for the design (Design.md §16.3, R5).

**Automated behavior (already running, no action needed under normal operation):** `CohortPurgeJob` runs daily at 02:00 server time and performs a **dry run only** — it never deletes anything automatically. It records a `purge_run` row every time it runs (even when nothing is eligible, so the non-execution watchdog can tell "ran, found nothing" from "didn't run"). If it finds a graduation-year cohort past its retention window (graduation + 1 year), it logs a `WARN`-level line naming how many students are eligible.

**To actually purge a cohort (a deliberate, human-triggered action — this is irreversible):**
1. First, **always run the dry run** via `POST /api/v1/admin/purge-runs?dryRun=true` (requires the `lifecycle.purge` permission — `role_admin`/`role_sysadmin` only) and review the response's `perStudent` list. Confirm the student count and per-student row counts look plausible for the cohort you expect (e.g., cross-check against how many students graduated in that year).
2. **Before running for real, ensure a recent export exists** for the affected cohort (`POST /exports` with `reportType: "serviceRecords"`, per B5.9) — this is the school's own decision about what to retain outside the system (Design.md §16.3: "what the school preserves outside the system is the school's decision"); the purge itself keeps nothing.
3. Run for real: `POST /api/v1/admin/purge-runs?dryRun=false`, same permission. This is synchronous and returns the actual counts of what was deleted, per student.
4. Confirm via `GET /api/v1/dashboards/admin` (or a direct student lookup) that the purged students are gone and that students from other cohorts are unaffected.
5. The `purge_run` audit row (queryable directly in the database; no list endpoint exists for it yet) is the permanent compliance record — cohort, timestamp, row/object counts, policy version, who triggered it.

**If the daily dry-run job stops running** (no `purge_run` row in the last 36 hours, per `LifecycleWatchdogJob`), see the Alert Response runbook below.

## 5. Ledger Reconciliation

**Status: implemented and tested — real, not aspirational.** `LedgerReconciliationJob` runs a checkpointed pass nightly (03:00) over accounts touched since the last check, and a full re-sum of every account weekly (Sunday 04:00). Both compare each account's stored `balance` against an independently recomputed sum of its `account_transaction` rows and log a structured `ERROR` naming the account, the two balances, and (where determinable) the first transaction where they diverge.

**If a mismatch alert fires:**
1. Do not attempt to "fix" the balance directly — the ledger is append-only by design (invariant 1/2); a manual `UPDATE account SET balance = ...` would itself be exactly the kind of drift this system exists to prevent.
2. Query `reconciliation_mismatch` for the account to get the divergent transaction id, stored balance, and computed balance.
3. Investigate how the divergence happened — the most likely causes, in order of likelihood: a direct database write that bypassed `AccountService` (should be structurally rare — the account/ledger module is the only writer), a bug in a specific transaction type's crediting math, or a partially-applied migration.
4. Once the cause is understood, correct the balance the same way every other correction in this system happens: an appended `account_transaction` (via a manual correction case, B5.2, or `POST /accounts/{id}/adjustments`) bringing the stored balance back in line with the true ledger sum — never an in-place edit.
5. Trigger `POST /api/v1/admin/reconciliation-runs?full=true` (requires `lifecycle.reconcile`) to confirm the account no longer appears in the mismatch list.

**If the reconciliation job stops running** (no `reconciliation_run` row in the last 36 hours), see Alert Response below.

## 6. Alert Response

All B6 alerting is currently **structured `ERROR`/`WARN`-level application log lines** (JSON, via `logstash-logback-encoder`) — there is no email/SMS/chat notification yet (a deliberate scope decision, not an oversight: no delivery channel had been decided as of 2026-08-04). Whoever operates this system needs to be watching the logs, or this section is theoretical.

| Alert | Where it appears | What it means | What to do |
| --- | --- | --- | --- |
| `LIFECYCLE ALERT: the cohort purge dry-run job has not completed successfully in over 36 hours` | App log, `ERROR` | The daily 02:00 dry-run job hasn't recorded a success — likely the scheduler itself is broken, the app has been down, or `ShedLock` is stuck | Check `systemctl status timebank-backend`; check for a stuck row in the `shedlock` table (`name = 'cohortPurgeDryRun'`) with a `lock_until` far in the future from a crashed run — if so, and the app is confirmed not actually running that job, delete the stuck row |
| `LIFECYCLE ALERT: ledger reconciliation has not completed successfully in over 36 hours` | App log, `ERROR` | Same failure mode as above, for `LedgerReconciliationJob` | Same diagnosis; check `shedlock` rows named `ledgerReconciliationCheckpoint`/`ledgerReconciliationFull` |
| `LEDGER RECONCILIATION MISMATCH: account {id} stored balance {x} != computed balance {y}` | App log, `ERROR` | A real ledger drift was detected | Follow the Ledger Reconciliation runbook (§5) above |
| `Purge run {id} (cohort {year}, dryRun={bool}) failed after processing {n} of {m} students` | App log, `ERROR` | A purge run threw partway through a cohort | Check the `purge_run` row's `error_message`; the already-processed students in that run were each their own transaction (one student's failure doesn't roll back prior ones in the same run) — re-run the dry run to see what's left, investigate the specific student that failed |
| `Import job {id} commit failed` / `Import job {id} validation failed` | App log, `ERROR` | An admin-triggered import job failed | Check `GET /api/v1/imports/{id}` for `errorMessage`; the job can be re-validated or, for a partially-committed import, re-committed (it resumes from its checkpoint — see `Docs/implementation/00-Status.md` §18 for the one known limitation: a crash exactly mid-chunk can re-process that chunk) |
| Any `Unhandled exception for requestId=...` | App log, `ERROR`, with a stack trace | A genuine unexpected application error | Use the `requestId` to correlate with the client-visible error response (`X-Request-Id` response header, or `error.requestId` in the response body) if the affected user reports it; read the stack trace |

**Not yet built, blocked on infrastructure that doesn't exist (B6.11's fuller scope, B7.10):** replication-lag alerting (no replica), disk/DB-capacity alerting, error-rate-spike alerting, connection-pool-saturation alerting, and SLA-breach alerting all need a monitoring stack (Prometheus/Grafana or equivalent) that has not been set up. Until then, "watching the logs" is the entire alerting story for anything not in the table above.

---

## Appendix: Environment Facts an Operator Needs

- **No containers anywhere.** The app is a fat JAR run as a systemd unit (`timebank-backend.service`); MySQL is a plain OS package install. There is no image pinning the runtime — JDK version (21), OS packages, and systemd unit definitions are the deployment contract. Keep test/staging/production on the same JDK.
- **Deploy:** `backend/scripts/deploy.sh` (builds the JAR, restarts the systemd unit). **Test:** `backend/scripts/verify.sh` (full `mvn verify` against real MySQL, no Testcontainers). Both read secrets from the environment and contain none themselves.
- **Secrets** live in `/etc/timebank-backend.env` (mode `0600`) on the server — never in the repository, never in a container image (there is none).
- **The current single-instance topology is real, not staging-shaped.** Everything in this document that assumes a replica, a second application instance, or a staging environment is describing the target design (Design.md §17.1), not what is running today. Check `Docs/implementation/00-Status.md`'s latest snapshot before assuming any of that exists.
