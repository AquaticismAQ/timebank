# B7 · Database Operations — Parallel Track

> Phase B7 of `01-Overview.md`. Not a phase in sequence but a **track running from week 2 to the end**, because it guards the one dataset that cannot be reconstructed from anything else, and because discovering it late is how projects lose ledgers.

| | |
| --- | --- |
| **Depends on** | Infrastructure availability (not on other backend phases) |
| **Blocks** | Pilot go-live. **Gate G3** (first drill by W12, repeated W25) |
| **Effort / calendar** | 4 pw spread across W2–W26 |
| **Design refs** | `Design.md` §17.1–§17.2, R1, Q1, Q10 |

---

## 1. Goal

Make self-hosted MySQL operable by a small team with basic DevOps experience: explicit, automated, and **rehearsed**. The measure of success is not that backups exist but that a restore has been performed, timed, and checksum-verified — repeatedly, by automation.

## 2. Why This Is a Separate Track

`Design.md` records this as risk **R1**, the highest-risk combination in the design: self-hosted MySQL (Q1) operated by school IT with basic DevOps experience (Q10), guarding a ledger that must survive a full student lifecycle. Sequencing it after the application would mean the first real restore attempt happens near go-live, which is precisely when there is no time to discover that it does not work.

## 3. Work Items

| ID | Item | Deliverable | Target |
| --- | --- | --- | --- |
| **B7.1** | Environment provisioning for **staging and production** — VPC, application subnet, database hosts; **MySQL reachable only from the application subnet**, never the public internet; distinct credentials per environment. The test server already exists and is out of this track's scope except for its backup story (B7.5) | Staging and production topology | W2–W4 |
| **B7.2** | Primary + asynchronous replica on separate hosts; replica serves as standby and backup source so backups never load the primary | Replication running, lag monitored | W4–W6 |
| **B7.3** | Durability configuration — `innodb_flush_log_at_trx_commit = 1`, `sync_binlog = 1`, `binlog` in `ROW` format retained ≥ 7 days | Non-negotiable for a ledger; the throughput cost is irrelevant at ~60 req/s | W4–W6 |
| **B7.4** | Buffer-pool sizing — ~60–70% of the database host's RAM, on a host large enough to hold the active academic year's working set with headroom (§16.4) | The single most effective performance lever available | W6 |
| **B7.5** | Nightly full backup — Percona XtraBackup streamed to object storage with versioning and a lifecycle rule | Automated, alerting on failure | W6–W8 |
| **B7.6** | PITR — continuous binlog shipping to object storage | Recovery to any point within the retention window | W8–W10 |
| **B7.7** | **Automated weekly restore verification** — restore into a scratch instance, assert row counts and checksums, report result | The highest-value automation in the whole operations plan. An unverified backup is not a backup | W10–W12 |
| **B7.8** | **G3 restore drill** — full restore of a production-sized dataset, end to end, **timed**; RPO and RTO measured against the ≤ 5 min / ≤ 4 h objectives | Measured figures recorded in `00-Status.md`, not assumed | W12, repeat W25 |
| **B7.9** | Manual failover runbook — documented promotion procedure. **Automatic failover is deliberately not adopted**: with two nodes and a small team it causes split-brain more often than it prevents downtime | A runbook a non-author can follow under pressure | W12–W14 |
| **B7.10** | Monitoring — replication lag, disk headroom, buffer-pool hit ratio, slow query log, backup success/failure | Feeds B6.11's alerting | W14–W16 |
| **B7.11** | Patching procedure — documented minor-version upgrade path, rehearsed once on staging | Unpatched database servers are a standing risk even when unreachable from the internet | W16–W20 |
| **B7.12** | Log aggregation — centralized logs with `requestId` correlation across both application instances | With two instances, per-host journald logs are no longer greppable in place | W8–W12 |
| **B7.13** | Deployment mechanics — **fat JAR + systemd units, no containers**; two units per host for rolling restart, expand/contract migration policy so two versions can run briefly; documented maintenance-window fallback where a migration cannot be made backward-compatible | Rolling deploys work without sticky sessions (the app is stateless by design); the test server runs a single unit, production runs two | W18–W22 |
| **B7.14** | Handover package — runbooks for backup, restore, failover, patching, purge execution, and alert response, written for school IT staff rather than for the authors | Q10 makes this a deliverable, not documentation debt | W22–W26 |

## 4. Exit Criteria — Gate G3 and Handover

- [ ] Replication running with monitored lag; replica confirmed as the backup source.
- [ ] Durability settings verified on the live instances, not just in a config file in the repository.
- [ ] Nightly backups landing in object storage with alerting on failure.
- [ ] PITR demonstrated: recovery to a chosen point in time on staging.
- [ ] Weekly automated restore verification running and reporting.
- [ ] **A production-sized restore performed and timed; RPO and RTO measured and recorded.**
- [ ] Failover runbook executed on staging by someone who did not write it.
- [ ] Patching rehearsed once on staging.
- [ ] Handover package reviewed with the staff who will operate it after go-live.

## 5. Risks and Watch-Outs

- **R1 is the project's top risk and this track is its entire mitigation.** If any item slips, it should be this track's schedule that gets protected, not the feature phases.
- **"We have backups" is not the claim that matters.** The claim that matters is "we restored one, last week, automatically, and the checksums matched."
- **A managed database remains the lower-risk option** (`Design.md` §17.2). If budget appears mid-project, revisiting Q1 converts most of this track into a provider's responsibility — that trade stays worth making until roughly B7.7.
- **The runbooks are for the people who will inherit this**, most of whom will not have read the design documents. Write them accordingly.
- **No containers means the host matters.** Without an image pinning the runtime, JDK version, systemd unit definitions, and OS packages are part of the deployment contract. Record them in the runbook and keep test, staging, and production on the same JDK — "works on the test server" must not depend on an undocumented host difference.

## 6. Hand-Off

Operations capability is in place before go-live, rehearsed rather than documented, and owned by named people at the school.
