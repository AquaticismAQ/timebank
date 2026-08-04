# TimeBank · Implementation Status

> **Living document.** The single place to answer "where are we?". The plan lives in `01-Overview.md` and the phase docs; this file holds only state. Update it on phase entry, phase exit, and whenever a gate or risk changes — not in a burst before a review meeting.

**Snapshot as of 2026-08-04 — B0-B2 complete; B3 and B4 backend work all done and live; B5 all three passes done and live.** Phase 1 (institutional design) is complete. The frontend is built and read-wired against a mock client. The backend has the full draft→submit→review→credit pipeline (B3), the supply side — projects, registrations, event proposals (B4) — and the complete admin-surface layer (B5): caseflow/notifications/remaining admin surfaces (Pass 1), dashboards/reports/materialized summaries and this codebase's first scheduled job (Pass 2), and the async export/import job engine — chunking, checkpointing, a dedicated executor, and a ShedLock-guarded reclaim sweep (Pass 3) — all proven against real MySQL (124 tests) and live-verified over HTTPS. **The file module (B3.4/B3.5) uses local-disk storage, not MinIO/OSS** — a deliberate test-stage substitution for D9's S3-compatible recommendation, tracked as its own follow-up phase, `14-Backend-B3F-CloudFileStorage.md` — see §15. B4 and all three B5 passes each surfaced and fixed real bugs live — see §16, §17, §18.

**Environment:** the shared test server is provisioned and in active use (MySQL 8, Java 21, Maven installed 2026-08-02). **No Docker anywhere** — the app runs as a systemd unit (`timebank-backend.service`, user `timebank`) and all tests run on the server against real MySQL (`01-Overview.md` §6). Server host and credentials live only in the untracked, git-ignored `test-server-credits.md`; they are never repeated in any tracked file, issue, or CI configuration. A **dedicated, scoped MySQL user** (`timebank_app`, privileges limited to `timebank.*` and `timebank_it_%.*`) was created rather than using the shared superuser `dbadmin` from the credentials file — see §11.

---

## 1. Legend

| Mark | Meaning |
| --- | --- |
| `—` | Not started |
| `▶` | In progress |
| `⏸` | Blocked (blocker named in the notes) |
| `✅` | Done — exit criteria met, not merely "code written" |
| `⛔` | Descoped or superseded (reason recorded) |

A phase is `✅` only when **every** exit-criteria checkbox in its own document is ticked. Work items complete individually; phases do not.

## 2. Programme Summary

| | |
| --- | --- |
| **Overall state** | In progress — B0-B2 complete; B3, B4, and B5 backend work items all complete and deployed (all three held at `▶` by their own open exit criteria) |
| **Current phase** | B5 Admin Surface, all three passes done (`▶` — B5.1-B5.12 done, deployed, live-verified; the one remaining exit criterion is the 500k-row/p95-under-load import measurement, G4/B6.10 territory — see §4 of `07-Backend-B5-AdminSurface.md` and §18 below); B4 Projects & Proposals stays `▶` (4 of 8 exit criteria unchecked, see `06-Backend-B4-Projects.md` §4 and §16); B3 Core Loop also stays `▶` (frontend-demonstration and query-count/EXPLAIN exit criteria remain, see §15); B3F (cloud file storage) not started; B0.14's automated CI trigger remains open |
| **Planned duration** | ~28 weeks to pilot go-live (2 backend + 1 frontend engineer + ~0.2 FTE ops) |
| **Total effort** | ~72 person-weeks |
| **Start date** | 2026-08-02 |
| **Target go-live** | Not set — should land on a **term boundary** (`13-Integration-I3-Acceptance.md` I3.11) |
| **Schedule reserve** | ~15% (≈4 weeks), held at programme level, unallocated |

## 3. Gates

| Gate | Meaning | Planned | Status | Evidence |
| --- | --- | --- | --- | --- |
| **G1** Contract freeze | Request bodies frozen, types module lifted, divergences 1–4 resolved | W6 | `—` | |
| **G2** Core loop proven | Full record lifecycle with atomic crediting, demonstrated on the real frontend | W15 | `⏸` | Every backend exit-criteria bullet proven against real MySQL and live HTTPS, **including** B3.4/B3.5's file-upload/access ones (§14, §15) — the two still open are "demonstrated on the real frontend" (I2's job, mock client still in place) and the review-queue/record-list query-count + `EXPLAIN` test (not yet written) |
| **G3** Restore drill | Production-sized restore, timed; RPO/RTO measured | W12, repeat W25 | `—` | |
| **G4** Load test | 300 concurrent, p95 targets met, slow-query log empty | W26 | `—` | |
| **G5** UAT sign-off | All 12 acceptance scenarios passed in both languages | W28 | `—` | |

## 4. Phase Status

### Backend

| Phase | Doc | Effort | Planned | Status | Owner | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| **B0** Foundation | `02-` | 7 pw | W1–W3 | `▶` | | Deployed and tested live on the test server 2026-08-02; only B0.14's automated CI trigger remains |
| **B1** Identity | `03-` | 5 pw | W4–W6 | `✅` | | Deployed and live-verified over HTTPS 2026-08-02 |
| **B2** Base data & rules | `04-` | 5 pw | W6–W8 | `✅` | | Deployed and live-verified over HTTPS 2026-08-02 |
| **B3** Core loop | `05-` | 12 pw | W9–W15 | `▶` | | Carries G2; do not compress. All 13 work items (B3.1-B3.13, including the file module) done and deployed 2026-08-03 — see §14, §15. Phase itself stays `▶` until the frontend-demo and query-count/`EXPLAIN` exit criteria are also met |
| **B4** Projects & proposals | `06-` | 7 pw | W16–W19 | `▶` | | All 12 work items done, deployed, and live-verified over HTTPS 2026-08-03; phase itself stays `▶` until its own exit criteria (frontend rendering, teacher-scope proof on the queue, etc.) are also met — see §16 |
| **B5** Admin surface | `07-` | 8 pw | W20–W23 | `▶` | | All 3 passes (B5.1-B5.12) done, deployed, live-verified — Pass 1 2026-08-03 (§17), Pass 2 + Pass 3 2026-08-04 (§18). Phase stays `▶` on one honestly-open criterion: the 500k-row/p95-under-load import measurement (G4/B6.10 territory) |
| **B6** Hardening & lifecycle | `08-` | 6 pw | W24–W26 | `—` | | Carries G4 |
| **B7** Database operations | `09-` | 4 pw | W2–W26 | `—` | | Parallel track; carries G3 |

### Integration

| Phase | Doc | Effort | Planned | Status | Owner | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| **I0** Contract prep | `10-` | 3 pw | W4–W6 | `—` | | Carries G1; blocks all integration |
| **I1** Read path | `11-` | 4 pw | W10–W20 | `—` | | Rolling, 8 domains |
| **I2** Write path | `12-` | 6 pw | W14–W22 | `—` | | No prior validation exists (R12) |
| **I3** Acceptance & cutover | `13-` | 5 pw | W20–W28 | `—` | | Carries G5 |

## 5. Work Items

### B0 · Foundation — `▶` in progress, core deliverables done and deployed

| ID | Item | Status | Notes |
| --- | --- | --- | --- |
| B0.1 | Project skeleton (Java 21, Boot 3.4, virtual threads) | `✅` | Package root is `cn.timebank`, not `cn.<school>.timebank` — deviation, see §11 |
| B0.2 | Module boundary enforcement (ArchUnit) | `✅` | Verified to actually fail on both a cross-module reach-in and an entity-in-controller leak, not just written |
| B0.3 | Response envelope | `✅` | Proven by MockMvc against a real controller |
| B0.4 | Error handling advice | `✅` | |
| B0.5 | requestId + structured logging | `✅` | JSON logs via logstash-logback-encoder; `X-Forwarded-For` honoured now that Nginx fronts the app |
| B0.6 | List primitives (page/sort/filter) | `✅` | Whitelisted sortable fields + deterministic `id` tiebreaker; unexercised by a real list endpoint yet (none exist until B2+) |
| B0.7 | Concurrency & idempotency helpers | `✅` | Replay / fingerprint-conflict / in-flight-conflict all proven against real MySQL on the test server |
| B0.8 | Audit infrastructure | `✅` | Repository extends bare `Repository`, not `CrudRepository` — no delete/update method exists to call |
| B0.9 | **Test-data seeder** | `✅` (scoped) | Harness only (`Seeder` interface + `SeedRunner`, `seed` profile) — no seeders registered, since `student`/`servicerecord` entities don't exist until B2/B3. Full 5,000-student run deferred to staging per plan |
| B0.10 | Flyway baseline + log partitioning | `✅` | `V1__baseline.sql`: `idempotency_record` + monthly-partitioned `audit_log` (12 months pre-created + `p_future`) |
| B0.11 | Test-server deployment (systemd, no containers) | `✅` | Live at `https://tb.example.com/api/v1/*`; systemd unit + dedicated `timebank` OS user + Nginx `/api/` proxy added to the existing vhost |
| B0.12 | Observability skeleton | `✅` | Actuator liveness/readiness; `/actuator/*` correctly unreachable externally (app bound to `127.0.0.1` only) |
| B0.13 | Test schema isolation (`timebank_it_<run>`) | `✅` | Hit and fixed a real Spring-test-context-caching bug (see §11) — worth reading before writing more `*IT` classes |
| B0.14 | CI on the server, no credentials in hosted config | `▶` partial | `scripts/verify.sh` + `scripts/deploy.sh` exist, are idempotent, and are proven to work manually. **No automated trigger yet** (no self-hosted GitHub Actions runner, no webhook) — deliberately not wired up without confirmation, since it's a persistent, harder-to-reverse infrastructure change |

### B1 · Identity — `✅` done and deployed

| ID | Item | Status | Notes |
| --- | --- | --- | --- |
| B1.1 | IAM module + permission catalog | `✅` | 48 permission codes, 4 roles, seeded as `R__` repeatable migration; live-verified via `GET /users` |
| B1.2 | Password lifecycle + lockout | `✅` | BCrypt strength 12; lockout threshold proven end to end in `AuthFlowIT` (5 failures locks, correct password then still rejected) |
| B1.3 | Session auth with instant revocation | `✅` | Spring Session JDBC; revocation proven for both deactivation and password-change in `AuthFlowIT`. Mid-request `changeSessionId()` fixation regeneration deliberately **not** wired at login — see finding below |
| B1.4 | CSRF + origin protection | `✅` | Cookie double-submit pattern; required switching off Spring Security 6.2's default XOR request handler — see finding below |
| B1.5 | Pluggable principal resolution (Phase 3 seam) | `✅` | `TimeBankPrincipal` interface; `CookieSessionUserDetails` is the only implementation |
| B1.6 | Permission enforcement + matrix tests | `✅` | ArchUnit scan (`PermissionAnnotationTest`) verified to actually fail on a missing `@PreAuthorize`, same discipline as B0.2 |
| B1.7 | **Data-scope resolver (in-query)** | `✅` (mechanism only) | `ScopeDescriptor`/`DataScopeResolver`/`DataScopeSpecifications` built and the resolver proven against real assignment data (`DataScopeResolverIT`). **`DataScopeSpecifications`' query-predicate half is untested against a real entity** — no business resource exists yet; real proof lands with B2/B4, consistent with how B0.9's seeder was scoped |
| B1.8 | Existence hiding (`404` not `403`) | `✅` (convention only) | `ApiException.notFound`/`forbidden` exist and `GlobalExceptionHandler`/`SecurityErrorHandlers` map them correctly; no scoped resource exists yet to exercise the actual out-of-scope case (same caveat as B1.7) |
| B1.9 | `/me` + profile self-service | `✅` | Live-verified over HTTPS: login → `/me` → `/users` → logout → `/me` (401) |
| B1.10 | Login logs + auth audit | `✅` | `login_log` partitioned like `audit_log`; audit entries on create/update/unlock/reset/password-change |
| B1.11 | Auth rate limiting at Nginx | `✅` | `limit_req_zone` in `/etc/nginx/conf.d/timebank-rate-limit.conf` (10r/m, burst 5), scoped to `/api/v1/auth/(login\|forgot-password\|reset-password)` only |

### B2 · Base Data & Rules — `✅` done and deployed

| ID | Item | Status | Notes |
| --- | --- | --- | --- |
| B2.1 | Student module (guardian fields excluded from lists) | `✅` | `StudentPrivacyIT` caught a real bug — see §13 |
| B2.2 | Academic structure + graduation year | `✅` | `graduation_year` `NOT NULL` from the first migration |
| B2.3 | Organizations | `✅` | `projectCount` hardcoded to 0 with a comment referencing B4 — honest placeholder, not a broken query |
| B2.4 | Service categories with per-language names | `✅` | 6 seeded categories match the frontend mock's set |
| B2.5 | Localization resolution | `✅` | `LocalizationIT`; live-verified with both `Accept-Language: zh-CN` and `en` |
| B2.6 | Notification templates | `✅` | Rendering only, no parameter substitution yet (none needed until B3) |
| B2.7 | Rules module (immutable published versions) | `✅` | `RuleVersioningIT`; live-verified (`PATCH` on published → `409`) |
| B2.8 | Rule seeding (rounding, 1:1 conversion, tiers) | `✅` | 30 min rounding, 60 min → 100 centi-coins, tiers 25/75/150/300h |
| B2.9 | **Rule resolution by service date** | `✅` | `RuleVersioningIT` proves an old service date keeps resolving to the closed-window rule after a new one publishes |
| B2.10 | School info + system settings | `✅` | System settings endpoint was already built in B1 under `iam` (contract §4.15 grouping) |
| B2.11 | Feature flags | `✅` | Wired into `/me`; Phase 3/4 mutation-rejection N/A — no such endpoint exists yet |
| B2.12 | Dictionary caching | `✅` | Caffeine, 60s TTL; `DictionaryCachingIT` — a real cache-key collision was caught and fixed here, see §13 |

### B3 · Core Loop

| ID | Item | Status |
| --- | --- | --- |
| B3.1 | Service record module + revisions | `✅` |
| B3.2 | State machine (transition table) | `✅` |
| B3.3 | Submission validation | `✅` |
| B3.4 | File module (intent → complete → scan) | `✅` |
| B3.5 | File access control + audit | `✅` |
| B3.6 | Review queue projection | `✅` |
| B3.7 | Review decisions (append-only) | `✅` |
| B3.8 | Account module + immutable ledger | `✅` |
| B3.9 | Ledger serialization (`FOR UPDATE`) | `✅` |
| B3.10 | Adjustments, reversal, freeze | `✅` |
| B3.11 | Progress projection | `✅` |
| B3.12 | **The approval transaction (5 effects, atomic)** | `✅` |
| B3.13 | Crediting calculation (integers only) | `✅` |

B3.4/B3.5 are built against a local-disk `FileStorage` adapter (real magic-byte/MIME/size checks; no AV engine) — a deliberate test-stage substitution for MinIO/OSS, tracked as its own phase (`14-Backend-B3F-CloudFileStorage.md`), not a gap in B3 itself. See §15.

### B4 · Projects & Proposals

| ID | Item | Status |
| --- | --- | --- |
| B4.1 | Project module | `✅` |
| B4.2 | Project lifecycle action endpoints | `✅` |
| B4.3 | `enrolledCount` + `myApplicationStatus` | `✅` |
| B4.4 | Denormalized display names (no N+1) | `✅` |
| B4.5 | Project timeline | `✅` |
| B4.6 | Enrollment module | `✅` |
| B4.7 | Capacity control under concurrency | `✅` |
| B4.8 | Bulk registration actions | `✅` |
| B4.9 | Proposal module | `✅` |
| B4.10 | Approval chain (append-only) | `✅` |
| B4.11 | **Atomic project generation on approval** | `✅` |
| B4.12 | Proposal queue summary | `✅` |

B4's field set for `EventProposal` follows `APIContract.md` §3.2, not the frontend mock's divergent shape (zero real write call sites on that surface) — a new named divergence, not a silent reconciliation. Two real concurrency/staleness bugs were found and fixed live before this phase closed — see §16.

### B5 · Admin Surface

| ID | Item | Status |
| --- | --- | --- |
| B5.1 | Caseflow module | `✅` |
| B5.2 | **Manual correction → ledger reversal** | `✅` |
| B5.3 | Retroactive-entry supplement path | `✅` |
| B5.4 | Notifications & announcements | `✅` |
| B5.5 | Cheap polled endpoints (ETag/304) | `✅` |
| B5.6 | Dashboards (per-principal, `generatedAt`) | `✅` |
| B5.7 | Reports (SQL aggregation) | `✅` |
| B5.8 | Materialized summaries | `✅` |
| B5.9 | Exports (async, streamed) | `✅` |
| B5.10 | Imports (validate before commit) | `✅` |
| B5.11 | Chunking, checkpointing, executor isolation | `✅` |
| B5.12 | Admin surfaces (users, roles, logs, settings) | `✅` |

**B5 was built in three passes** (user's explicit choice, mirroring B3's core-loop/file-module split): **Pass 1** (B5.1-B5.5 + B5.12) done 2026-08-03, deployed, live-verified — see §17. **Pass 2** (dashboards/reports/materialized summaries — this codebase's first scheduled job) and **Pass 3** (exports/imports — first async executor + chunking/checkpointing + reclaim sweep) both done 2026-08-04, deployed, live-verified — see §18. All 12 work items are `✅`; the phase itself stays `▶` only on the 500k-row/p95-under-load measurement named in `07-Backend-B5-AdminSurface.md` §4.

### B6 · Hardening

| ID | Item | Status |
| --- | --- | --- |
| B6.1 | Log partition pruning | `—` |
| B6.2 | Session/idempotency sweeps | `—` |
| B6.3 | **Cohort purge job** | `—` |
| B6.4 | Purge safety (dry run, audit, alert) | `—` |
| B6.5 | Object deletion with rows | `—` |
| B6.6 | Storage lifecycle tiering | `—` |
| B6.7 | **Ledger reconciliation + alerting** | `—` |
| B6.8 | Rate limiting completed | `—` |
| B6.9 | Index & query review | `—` |
| B6.10 | **Load test** | `—` |
| B6.11 | Alerting & dashboards | `—` |
| B6.12 | Security review | `—` |

### B7 · Database Operations

| ID | Item | Status |
| --- | --- | --- |
| B7.1 | Environment provisioning | `—` |
| B7.2 | Primary + replica | `—` |
| B7.3 | Durability config + binlog | `—` |
| B7.4 | Buffer-pool sizing | `—` |
| B7.5 | Nightly backups to object storage | `—` |
| B7.6 | PITR (binlog shipping) | `—` |
| B7.7 | **Automated weekly restore verification** | `—` |
| B7.8 | **G3 restore drill (timed, RPO/RTO measured)** | `—` |
| B7.9 | Manual failover runbook | `—` |
| B7.10 | DB monitoring | `—` |
| B7.11 | Patching procedure | `—` |
| B7.12 | Log aggregation | `—` |
| B7.13 | Deployment mechanics (rolling, expand/contract) | `—` |
| B7.14 | Handover package for school IT | `—` |

### I0 · Contract Prep

| ID | Item | Status |
| --- | --- | --- |
| I0.1 | Lift types to `api/types.ts` | `—` |
| I0.2 | **Freeze request bodies (§13.4)** | `—` |
| I0.3 | HTTP transport layer | `—` |
| I0.4 | Envelope handling | `—` |
| I0.5 | Error-to-UI mapping | `—` |
| I0.6 | Idempotency + `If-Match` helpers | `—` |
| I0.7 | Per-domain mock/real toggle | `—` |
| I0.8 | Divergence 1 — proposal status rename | `—` |
| I0.9 | Divergence 2–3 — centi-coin units | `—` |
| I0.10 | Divergence 4 — drop `*Hours` from the wire | `—` |
| I0.11 | Divergence 5 — dictionary names from server | `—` |
| I0.12 | Dev environment recipe | `—` |

### I1 · Read Path (by domain)

| ID | Domain | Ready after | Status |
| --- | --- | --- | --- |
| I1-d1 | Session & school | B1, B2.10 | `—` |
| I1-d2 | Dictionaries | B2 | `—` |
| I1-d3 | Students & progress | B2, B3.11 | `—` |
| I1-d4 | Accounts | B3.8 | `—` |
| I1-d5 | Records & review queue | B3 | `—` |
| I1-d6 | Projects & proposals | B4 | `—` |
| I1-d7 | Notifications | B5.4–B5.5 | `—` |
| I1-d8 | Dashboards & reports | B5.6–B5.8 | `—` |

Cross-cutting items I1.2–I1.10 (pagination, filtering, error states, polling, localization checks, fixture deletion, mobile checks) are tracked per domain as part of each swap.

### I2 · Write Path (by domain)

| ID | Domain | Ready after | Status |
| --- | --- | --- | --- |
| I2-d1 | Service records | B3.1–B3.3 | `—` |
| I2-d2 | Evidence upload | B3.4–B3.5 | `—` |
| I2-d3 | Review decisions | B3.6–B3.7, B3.12 | `—` |
| I2-d4 | Account operations | B3.10 | `—` |
| I2-d5 | Projects | B4.1–B4.2 | `—` |
| I2-d6 | Registrations | B4.6–B4.8 | `—` |
| I2-d7 | Proposals | B4.9–B4.11 | `—` |
| I2-d8 | Cases & admin | B5 | `—` |

### I3 · Acceptance & Cutover

| ID | Item | Status |
| --- | --- | --- |
| I3.1 | Evidence upload flow | `—` |
| I3.2 | Upload failure states | `—` |
| I3.3 | Evidence viewing (short-lived URLs) | `—` |
| I3.4 | `preferredLanguage` persistence | `—` |
| I3.5 | Localization sweep (both languages) | `—` |
| I3.6 | Notification template rendering | `—` |
| I3.7 | **12 acceptance scenarios** | `—` |
| I3.8 | **Historical import rehearsal** | `—` |
| I3.9 | **UAT with real users** | `—` |
| I3.10 | Production data seeding | `—` |
| I3.11 | Cutover plan + rollback | `—` |
| I3.12 | Post-launch watch | `—` |

## 6. Acceptance Scenarios (`APIContract.md` §11)

| # | Scenario | Status | Notes |
| --- | --- | --- | --- |
| 1 | Proposal → changes requested → resubmit → approve → project draft | `—` | Submit → approve → project generated proven live and in `ProposalApprovalTransactionIT` (§16); the changes-requested → resubmit leg of this specific scenario isn't separately exercised yet |
| 2 | Publish generated project → students register → teacher manages list | `✅` | Live HTTPS verification (§16): generated project published, registration opened lazily, 2 confirmed + 1 waitlisted at capacity 2 |
| 3 | Draft, upload evidence, submit a record | `—` | Backend proven end to end over HTTPS (§15) — status stays `—` until demonstrated on the real frontend (I2's job, mock client still in place) |
| 4 | Return → supplement → resubmit → approve | `—` | Return→resubmit proven (`ServiceRecordSubmissionIT`/`ApprovalTransactionIT`); "supplement" is a B5 case-module concept |
| 5 | Approval credits ledger and progress in one transaction | `✅` | `ApprovalTransactionIT.approvalProducesExactlyOneOfEachOfTheFiveEffects` + live HTTPS verification (§14) |
| 6 | Appeal processed with a complete timeline | `✅` | `CaseDecisionTransactionIT.appealTimelineRendersACompleteReadableSequence` — create→submit→resolve, timeline asserted end to end (§18) |
| 7 | Historical import with validation errors, then commit | `✅` | `ImportExportIT.importValidateProducesCorrectCountsAndErrorReport` / `.importCommitCreditsExactlyTheValidRowsOnce`, live-verified over HTTPS (§18) — at small scale; the 500k-row/p95 claim in `07-Backend-B5-AdminSurface.md` §4 is separately still open |
| 8 | Out-of-scope access returns `403`/invisible `404` | `—` | `serviceRecords`/`accounts` now have real Specification-based scope (§14) but a dedicated out-of-scope-teacher acceptance test isn't written yet |
| 9 | Duplicate submit/approve/adjust creates no duplicates | `✅` | `ApprovalTransactionIT.duplicateIdempotencyKeyReplaysInsteadOfDoubleCrediting` |
| 10 | Concurrent edits conflict, never silently overwrite | `✅` | `ServiceRecordSubmissionIT.concurrentEditsToOneRecordReturnConflictNotSilentOverwrite`, `AccountConcurrencyIT` |
| 11 | Full acceptance with no WeChat/IM dependency | `—` | |
| 12 | Both languages render with no localized enum on the wire | `—` | |

## 7. Risk Register State

Mirrors `Design.md` §21. Update the state, not the wording.

| ID | Risk | Phase | State |
| --- | --- | --- | --- |
| R1 | Self-hosted MySQL, small ops team | B7 | Open — highest |
| R2 | Untested performance assumptions | B0.9, B6.10 | Open |
| R3 | Data-scope omission on a new endpoint | B1.7 | Open |
| R4 | Ledger drift bypassing `AccountService` | B3.8, B6.7 | Open |
| R5 | Purge implemented as row-age not cohort | B6.3 | Open |
| R6 | Dirty historical ledger import | B5.10, I3.8 | Open |
| R7 | Rule versioning implemented late | B2.9 | Open |
| R8 | Coin unit scale not agreed | I0.9 | **Closed by design** — centi-coins fixed in contract §2.1; execution pending |
| R9 | Module boundary erosion | B0.2 | Open |
| R10 | Contract drift during integration | I0, §10.2 | Open |
| R11 | Background job starving interactive load | B5.11, B6.10 | Open |
| R12 | Frontend write path entirely unwired | I0.2, I2 | Open — schedule risk |

## 8. Measurements

Filled in as they are taken. Empty rows are unmeasured, not passing.

| Measurement | Target | Actual | Taken |
| --- | --- | --- | --- |
| Seeder runtime / row counts | 5,000 students, ~450k records | | |
| p95 list & detail @ 300 concurrent | < 300 ms | | |
| p95 dashboards @ 300 concurrent | < 1 s | | |
| Slow-query log during load test | empty | | |
| 500k-row import runtime, heap, resume | completes, bounded, resumes | | |
| Full restore time (production-sized) | RTO ≤ 4 h | | |
| Measured RPO | ≤ 5 min | | |
| Database size at steady state | 10–20 GB | | |

## 9. Decision & Change Log

Material decisions taken during implementation that change a plan or contract document. Design-level decisions belong in `Design.md` §22–§23; this log records execution-time changes.

| Date | Change | Affects | Recorded in |
| --- | --- | --- | --- |
| 2026-08-02 | Plan created; D10 rewritten to a layered source of truth; contract §2.10, §2.11, §10, §13 added | Contract, backend design | `Design.md` D10, `APIContract.md` §13 |
| 2026-08-02 | **Docker dropped from every environment**; fat JAR + systemd instead. Testcontainers replaced by real MySQL on the shared test server with per-run schema isolation; CI moves onto the server so no credential enters a hosted configuration | B0.11/B0.13/B0.14, B3.9, B7.13, I0.12, `Design.md` §8/§17/§19 | `01-Overview.md` §6 |
| 2026-08-02 | **B0 implemented and deployed.** Package root changed from `cn.<school>.timebank` to `cn.timebank` (D8 rationale — see §11 below); a scoped MySQL user (`timebank_app`) created instead of using the shared `dbadmin` superuser from the credentials file; test server found to be a genuinely shared multi-tenant box, not dedicated — sizing and heap choices adjusted accordingly | B0 (all items), `Design.md` §7 | §11 below |
| 2026-08-02 | **B1 implemented and deployed.** `TeacherScopeAssignment` moved from `iam` to `security` module (owns scope-assignment storage, since `DataScopeResolverImpl` needs it and `iam`→`security` already exists via session revocation — kept the module dependency graph one-directional); Spring Security 6.2's default XOR CSRF handler replaced with the plain one for the double-submit-cookie pattern; `AuthService.authenticate()` made non-`@Transactional` since it deliberately throws after writes it needs to keep | B1 (all items) | §12 below |
| 2026-08-02 | **B2 implemented and deployed.** `school_info.current_academic_year_code` denormalized to a plain string (not a FK to `academic_year.id`) to avoid an `organization`↔`student` module cycle; `RequestLanguageService` placed in `organization.application` (not `common`) since resolving the school-default language requires reading `school_info`, and `common` must stay dependency-free; `StudentServiceImpl.getDetail()`'s `readOnly` transaction and three no-arg `@Cacheable` methods' default-key collision both fixed after being caught live — see §13 below | B2 (all items) | §13 below |
| 2026-08-03 | **B3's core-loop items implemented and deployed.** New `notification` row/writer added ahead of B5 since the approval transaction needed somewhere to write its fifth effect; `iam.UserService` gained `findUserIdByStudentId` so `review` can resolve a notification recipient without reaching into `iam.domain`; B1.7/B1.8's data-scope mechanism wired to a real entity for the first time (`serviceRecords`, `accounts`); three real bugs found and fixed live — see §14 below | B3.1-3.3, 3.6-3.13, `iam.application.UserService`, `notification` module | §14 below |
| 2026-08-03 | **B3.4/B3.5 (file module) implemented and deployed against a local-disk `FileStorage` adapter, not MinIO/OSS** — a deliberate test-stage substitution for D9's S3-compatible recommendation, tracked as its own phase (`14-Backend-B3F-CloudFileStorage.md`) rather than reopening B3's scope. A second module-cycle risk (`file` ↔ `servicerecord`) was caught and resolved before landing — see §15 | B3.4, B3.5, new `file` module, `14-Backend-B3F-CloudFileStorage.md` | §15 below |
| 2026-08-03 | **B4 (Projects & Proposals) implemented and deployed.** `EventProposal`'s field set follows `APIContract.md` §3.2, not the frontend mock's divergent shape — a new named divergence (zero real write call sites on that surface). Project's time-derived status (`registrationOpen`/`inProgress`/`completed`) syncs lazily on read/action access rather than via a scheduled job, since no ShedLock/cron infrastructure exists yet — a scope decision, not a gap. `organization.projectCount` (B2's placeholder) now reflects real non-archived projects. Two real bugs caught live before this phase closed, one a genuine oversubscription risk — see §16 | B4 (all items), `iam.UserService`, `organization` | §16 below |
| 2026-08-03 | **B5 split into three passes** (user's explicit choice, mirroring B3's core-loop/file-module split): Pass 1 (caseflow, notifications, remaining admin surfaces — no new infrastructure), Pass 2 (dashboards/reports/materialized summaries — first scheduled job), Pass 3 (exports/imports — first async executor + chunking/checkpointing). **Pass 1 implemented and deployed.** `AccountService` gained `adjustForStudent` (lazily creates an account, mirroring `credit`) since a manual correction can be a student's first-ever credit — `adjust` alone requires an account to already exist. `role_teacher`/`role_admin` granted `cases.create` (a pre-existing seed gap: only `role_student` had it). Tomcat's default query-character validation rejects the contract's own `filter[field]` convention (B0.6) over real HTTP — fixed with `server.tomcat.relaxed-query-chars`, the first time that convention was ever exercised end to end. Three real bugs caught live before this pass closed — see §17 | B5.1-B5.5, B5.12, `account.application.AccountService`, `R__seed_permissions_and_roles.sql`, `application.yml` | §17 below |
| 2026-08-04 | **B5 Pass 2 (dashboards, reports, materialized summaries) and Pass 3 (exports, imports, the async job engine) implemented and deployed, closing out B5's work items.** New `reporting` module (JdbcTemplate end to end, D6) and `dataexchange` module (the `job` table, D5). ShedLock added as a dependency — this codebase's first scheduled job (the hourly grade-summary materialization) and its second (B5.11's lease-reclaim sweep), both JDBC-backed, no new infrastructure (D1). Historical import deliberately credits the ledger directly via `AccountService.adjustForStudent` per row rather than fabricating synthetic `ServiceRecord`/review history — a named scope decision, not a gap. Historical import format is CSV, not the Excel/EasyExcel-or-POI-SXSSF Design.md D5 literally names — a named deviation, same pattern as B3F's local-disk substitution. Five real bugs caught live before this pass closed, two of them classic Spring pitfalls worth remembering — see §18 | B5.6-B5.11, new `reporting` module, new `dataexchange` module, `pom.xml` (ShedLock), `V9`/`V10` migrations | §18 below |

## 10. How to Update This Document

1. On **phase entry**: set the phase to `▶`, fill in owner and actual start.
2. On **work-item completion**: flip the item's mark. Do not batch this weekly — the value is in it being current.
3. On **phase exit**: verify every exit-criteria checkbox in the phase document, then set `✅`. A phase is not done because its items are done.
4. On **gate**: record the evidence (a test run, a measurement, a signed-off review), not just the mark.
5. On **blockage**: set `⏸` and name the blocker and its owner in the notes column. A `⏸` with no named blocker is an unowned problem.
6. **Never renumber work-item IDs.** New work appends; descoped work becomes `⛔` with a reason.

## 11. B0 Implementation Findings (2026-08-02)

Real findings from actually building and deploying B0, kept here rather than only in commit messages because they change assumptions later phases would otherwise make.

**The test server is a genuinely shared multi-tenant box, not a dedicated one.** It already runs an AIS backend, an LLM gateway, a quiz app, and Sharewifi (Docker is installed and used for *those*, not for TimeBank), plus MySQL, Redis, and Nginx serving several other vhosts. Resources are real constraints, not headroom: **2 CPUs, 3.8 GB RAM total, ~5.2 GB disk free**. Consequences:
- The app runs with a conservative `-Xmx512m` and a 5-connection HikariCP pool — **not** `Design.md` §16.4's production sizing (2–4 GB heap, 20–30 connections), which targets a dedicated host. Do not copy the §16.4 numbers onto this box.
- B0.9's full 5,000-student seed run is correctly deferred to a staging environment that doesn't yet exist, per the plan — this is now doubly true given the disk budget, not just the phasing argument.
- No MinIO was installed. It was speculatively planned for B0.11 but nothing needs it until B3 (file module); installing unused infrastructure on a disk-constrained shared box ahead of need was reversed.

**Package root changed: `cn.timebank`, not `cn.<school>.timebank`.** `Design.md` §7's literal guidance bakes a specific school into the compiled package name, which cuts against D8 (one codebase, many single-tenant deployments, school identity as data/config, not code). Modules are `cn.timebank.<module>` (e.g. `cn.timebank.audit`). `Design.md` §7 should be updated to match next time it's touched.

**A scoped MySQL user was created, not reused from the credentials file.** `test-server-credits.md` provides `dbadmin`, a superuser with `ALL PRIVILEGES` on every tenant's database on the box (`aisdb`, `quizonline`, `sharewifi`) plus broad global grants. Rather than point the app at that account, a dedicated `timebank_app` user was created (via `root`'s `unix_socket` auth, since `dbadmin` itself lacks `GRANT OPTION`), scoped to `timebank.*` and `timebank_it_%.*` only. Its password lives in `/etc/timebank-backend.env` (mode `0600`) on the server, not in this repository.

**A real Spring-test-context-caching bug, worth knowing before writing more `*IT` classes.** `AuditServiceIT` and `IdempotencyServiceIT` both extend `AbstractIntegrationTest`, which registers one `@DynamicPropertySource` method. Spring's test-context cache keys on the *declaration* of that method, not the values its supplier returns — so without intervention, the second `*IT` class silently reuses the first class's cached `ApplicationContext`, and therefore its `DataSource`, which is permanently bound to the *first* class's (by-then-dropped) schema. Fixed with `@DirtiesContext(classMode = AFTER_CLASS)` on `AbstractIntegrationTest`, forcing a fresh context — and therefore a fresh `DataSource` bound to the right schema — per test class. Any new `*IT` class gets this for free by extending the base class; nothing further to do, but worth understanding if a future `*IT` test behaves as though it's talking to the wrong schema.

**A related, separate JPA gotcha in the idempotency insert path.** The original implementation caught `DataIntegrityViolationException` (the unique-constraint race) *inside* the same `@Transactional(REQUIRES_NEW)` method that threw it. That doesn't work: once a Hibernate flush fails, the persistence context is marked rollback-only internally regardless of whether the Java exception is caught, so the method appeared to "succeed" but `UnexpectedRollbackException` surfaced at commit. Fixed by letting the exception propagate out of the `REQUIRES_NEW` method (so Spring rolls back and closes that persistence context cleanly) and catching it in the non-transactional caller instead, where a fresh persistence context handles the subsequent lookup. Documented in `IdempotencyTransactions`' Javadoc so it isn't reintroduced.

**CI is partial by design, not by oversight.** `backend/scripts/verify.sh` and `backend/scripts/deploy.sh` exist, are checked in, and are proven to work end-to-end manually. Wiring an automated trigger (self-hosted GitHub Actions runner or a webhook) was deliberately not done without explicit confirmation — registering a persistent runner against the GitHub repo is a harder-to-reverse infrastructure change than anything else in B0, and belongs in its own decision, not folded into "start the backend."

**Live verification, for reference:**
```
$ curl https://tb.example.com/api/v1/ping
{"data":{"status":"ok"},"meta":{"requestId":"req_..."}}
```
13 tests pass on the server against real MySQL: `ModuleBoundaryTest` (2), `PingControllerTest` (4), `AuditServiceIT` (3), `IdempotencyServiceIT` (4).

## 12. B1 Implementation Findings (2026-08-02)

Three real bugs, each with a non-obvious root cause, found only because the exit criteria demand live proof rather than reasoning. Kept here because each is the kind of mistake that's easy to reintroduce.

**Session objects must be `Serializable` — the error this produces is unrecognizable.** Spring Session JDBC stores session attributes (including the whole `SecurityContext`, and therefore the `Authentication` principal) in `SPRING_SESSION_ATTRIBUTES` via Java serialization by default. `CookieSessionUserDetails` and `AuthenticatedUserView` didn't implement `Serializable` initially. The failure this produces is a `ConversionFailedException` buried inside `JdbcIndexedSessionRepository.serialize()` at session-save time — nothing at the point of the actual mistake (writing a non-serializable class) hints at the cause. Both classes now implement `Serializable`, with a comment explaining why, so this isn't rediscovered the hard way by whoever writes the next class that ends up in a session.

**A `@Transactional` method that deliberately throws on failure will roll back the very state it's trying to record.** `AuthService.authenticate()` is designed to throw on every failure path (wrong password, locked, inactive) so the controller gets a clean exception to map to `401`. It was also annotated `@Transactional` — meaning the exception it throws rolled back the whole method, **including the failed-login-attempt counter increment and the login-log write that happened earlier in the same method**. The lockout counter silently never advanced. Fixed by removing `@Transactional` from `authenticate()` entirely; each collaborator (`UserService.recordFailedLogin`, `LoginLogService.record`) manages its own transaction and commits independently of whatever `authenticate()` does afterward. General lesson, not first time this session: **any method that intentionally throws after performing a write it wants to keep must not be `@Transactional` itself** — see B0's idempotency finding for the mirror-image version of this (a `REQUIRES_NEW` method that must let its exception propagate rather than swallow it).

**Spring Security 6.2+'s default CSRF token handler breaks the double-submit-cookie pattern.** The default `CsrfTokenRequestHandler` (`XorCsrfTokenRequestAttributeHandler`, added for BREACH protection on server-rendered forms) makes the value a controller sees via `CsrfToken.getToken()` deliberately different from the raw value stored in the cookie. For a pure REST API where the client is expected to read the cookie and echo it back in a header, this mismatch silently fails every CSRF check. Fixed by explicitly configuring the plain `CsrfTokenRequestAttributeHandler` — the officially documented setting for this exact scenario, not a workaround. Also required `@EnableWebSecurity` (not strictly needed for the filter chain itself, but needed for `CsrfToken`'s MVC argument-resolver support) and a dedicated `GET /auth/csrf` endpoint that actually **calls** `csrfToken.getToken()` — accepting the parameter unused does nothing, since what's injected is a deferred supplier, not an already-materialized token.

**A near-cycle between `iam` and `security` was caught before it landed, not after.** `security`'s `UserDetailsServiceImpl` legitimately needs `iam.application.UserService`. The first draft of `UserServiceImpl` also depended on `security.application.SessionRevocationService` (to kill sessions on deactivation/password-reset) — a cycle. Resolved by moving session-revocation calls out of `iam.application` and into `iam.web` controllers, which are allowed to depend on both modules' published interfaces (that's what a web/composition layer is for); `UserService` methods that need to trigger revocation now just return the affected username for their caller to act on. Separately, `TeacherScopeAssignment` (originally drafted under `iam.domain`) was relocated to `security.domain` before any code depended on it, since "which grades can this teacher see" is authorization data, and leaving it in `iam` would have forced the same cycle via `DataScopeResolverImpl`.

**Session-fixation regeneration at login is a known, tracked gap, not a silent omission.** The standard idiom — call `changeSessionId()` after establishing a new authenticated session — threw `IllegalStateException: Cannot change session ID. There is no session associated with this request` even immediately after `getSession(true)` on the same request, seemingly specific to how Spring Session's `SessionRepositoryFilter` request wrapper tracks session association. Not root-caused within this session's time budget. Current behavior: a fresh session is always created at login (so an unauthenticated visitor never has a pre-existing session ID to plant), but an already-authenticated visitor's stale session id is not explicitly regenerated on login. Low real-world risk here specifically (there is no pre-auth session state for an attacker to fixate), but worth resolving before this pattern is copied elsewhere. Documented in `AuthController`'s code and in B1's exit criteria.

**B1.7/B1.8's data-scope mechanism is proven in isolation, not yet wired to a real resource** — same honest-scoping pattern as B0.9's seeder. `ScopeDescriptor`, `DataScopeResolver`, and `DataScopeSpecifications` all exist and the resolver is proven against real assignment rows (`DataScopeResolverIT`). What's *not* proven: `DataScopeSpecifications`'s JPA `Specification` construction against a real entity, and the full "teacher scoped to Grade 9 sees `404` for Grade 11" scenario — both require a real scoped resource, which doesn't exist until B2 (students/grades) and B3/B4 (records/projects). Flagged explicitly in B1's exit criteria rather than checked off.

**Live verification, for reference** (test user created directly via SQL for this check, then deleted):
```
$ curl -X POST https://tb.example.com/api/v1/auth/login ... 
{"data":{"user":{"roles":["admin"],"permissions":[...39 codes...]},"workspaces":["admin"], ...}}
$ curl https://tb.example.com/api/v1/me        → 200, same shape
$ curl https://tb.example.com/api/v1/users     → 200, paginated list
$ curl -X POST .../auth/logout               → 200
$ curl https://tb.example.com/api/v1/me        → 401 AUTHENTICATION_REQUIRED
```
22 tests pass on the server against real MySQL (up from 13 at the end of B0), confirmed via a full `mvn clean verify` run, not assumed from an earlier partial run: `ModuleBoundaryTest` (2), `PermissionAnnotationTest` (1, new), `PingControllerTest` (4), `AuditServiceIT` (3), `IdempotencyServiceIT` (4), `AuthFlowIT` (4, new), `DataScopeResolverIT` (4, new).

## 13. B2 Implementation Findings (2026-08-02)

Two real bugs, both found only because B2's exit criteria demanded live proof against real MySQL rather than a clean local compile plus passing ArchUnit rules — neither would have surfaced from either of those alone.

**A `readOnly` transaction silently swallows a write that legitimately belongs inside it.** `StudentServiceImpl.getDetail()` was annotated `@Transactional(readOnly = true)` — reasonable, since it's a read endpoint — but conditionally writes an audit row when `includeSensitiveFields` is true (contract §9's "sensitive access is audited" requirement). Called via the controller (a real Spring-proxied call), the audit write silently never committed: Hibernate configures `FlushMode.MANUAL` for a read-only transaction, and the transaction ends in an implicit rollback rather than a commit. It *appeared* to work during manual testing of `create()`, because `create()` calls `getDetail()` as a same-instance self-invocation, which bypasses the Spring proxy entirely and therefore runs under `create()`'s own non-read-only transaction instead of `getDetail()`'s annotation. `StudentPrivacyIT` calls `getDetail()` directly (as the controller does) and caught the gap: audit count didn't increase after a permitted sensitive read. Fixed by removing `readOnly = true` from `getDetail()`, with a comment explaining why. **General lesson:** a `readOnly` transaction must never contain a conditional write — audit or otherwise — and a self-invocation from a writable method can mask exactly this bug in ad hoc testing.

**No-arg `@Cacheable` methods sharing one cache region collide on Spring's default key.** `SchoolInfoServiceImpl.getInfo()`, `FeatureFlagServiceImpl.getFlags()`, and `AcademicStructureServiceImpl.listAcademicYears()` were each annotated `@Cacheable(CacheConfig.DICTIONARIES)` with no explicit `key`. Spring's `SimpleKeyGenerator` maps every no-arg method call to the same `SimpleKey.EMPTY`, and all three share the same named Caffeine region — so whichever method's result lands in the cache first "wins" that slot, and every other no-arg method in the region gets back a value of the wrong type. This surfaced live as a `ClassCastException` (`FeatureFlagsDto` cannot be cast to `SchoolInfoDto`) the first time `GET /school/info` was called after `/me` (which reads feature flags) had already populated the shared slot — a 500 in production, not a compile or unit-test failure. The other three `@Cacheable` methods in the same region (`listGrades`, `listClasses`, `ServiceCategoryService.list`) were already safe, since each takes `resolvedLanguage` and therefore gets a distinct key for free — the bug is specific to no-arg methods. Fixed by giving each no-arg method an explicit string key (`'schoolInfo'`, `'featureFlags'`, `'academicYears'`); `DictionaryCachingIT` now calls all three back-to-back against a real Caffeine cache to catch a regression. **General lesson:** every `@Cacheable` method sharing a named region needs a key that's unique *within that region*, not just within its own method — a rule easy to satisfy by accident when a cacheable method takes arguments, and easy to violate by accident when it doesn't.

**Live verification, for reference** (throwaway admin + student created directly via SQL/API for this check, then deleted; a rule published then archived, with the seeded default's effective window explicitly reopened afterward since archiving a superseding rule does not automatically reopen the one it closed):
```
$ curl https://tb.example.com/api/v1/school/info          (authenticated) → 200, {"name":"Example City HD",...}
$ curl -H "Accept-Language: zh-CN" .../grades            → 六年级, 七年级, ...
$ curl -H "Accept-Language: en" .../grades                → Grade 6, Grade 7, ...  (same ids/codes/sequence)
$ curl .../me                                              → featureFlags all false, matching the seed row
$ curl -X POST .../students  → 201, list omits guardian/email, detail includes them
$ curl -X POST .../rules → draft; POST .../rules/{id}/publish → 200; PATCH .../rules/{id} → 409 STATE_CONFLICT
```
32 tests pass on the server against real MySQL (up from 22 at the end of B1), confirmed via a full `mvn clean verify` run: `ModuleBoundaryTest` (2), `PermissionAnnotationTest` (1), `PingControllerTest` (4), `AuditServiceIT` (3), `IdempotencyServiceIT` (4), `AuthFlowIT` (4), `DataScopeResolverIT` (4), `RuleVersioningIT` (3, new), `StudentPrivacyIT` (3, new), `LocalizationIT` (3, new), `DictionaryCachingIT` (1, new).

## 14. B3 Implementation Findings (2026-08-03) — core loop, file module deferred

**Scope of this pass.** Per discussion at phase entry, B3 was split in two: this pass built everything except the file/evidence module (B3.1-B3.3, B3.6-B3.13 — service records, revisions, the state machine, submission validation, the review queue, append-only decisions, the ledger, progress, and the five-effect approval transaction). B3.4 (file upload/scan) and B3.5 (file access control) were taken up immediately afterward, in the same day — against a local-disk `FileStorage` adapter rather than MinIO, per updated direction (disk space explicitly not a concern for this test-stage volume; a real S3-compatible adapter is `14-Backend-B3F-CloudFileStorage.md`). See §15 for that work's findings. Gate G2's remaining gaps are the frontend demonstration (I2's job) and the query-count/`EXPLAIN` test for the review queue/record list, not the file module.

**A `readOnly`-adjacent bug's mirror image: a mutating method that never flushes returns a stale `version` to a caller that immediately needs the true one.** `ServiceRecordServiceImpl.submit()`/`withdraw()`/`updateDraft()` and `beginReviewTransition()` each mutate an already-persisted `ServiceRecord` and build their response DTO from the same in-memory entity — but JPA's `@Version` field is only incremented by Hibernate when the pending `UPDATE` actually executes, which for a `@Transactional` method with no intervening query is deferred to commit, not immediately after the setter calls. The returned DTO's `version` therefore reflected the *pre-increment* value. This is invisible for a method whose result is only ever displayed, but B3.12's approval flow chains `submit()`'s returned version straight into `ConcurrencyControl.checkVersion` inside the very next call (`reviews.decide`) — exactly the case that exposed it: `ApprovalTransactionIT` failed with a spurious "changed by someone else, current version is 2" on a record nothing else had touched. Fixed by calling `repository.saveAndFlush(record)` before building the DTO in each of the four methods, forcing the increment to happen (and the in-memory field to update) before the method returns. **General lesson, worth checking before B4/B5 copy this pattern for `Project`/`Case`:** any mutating method whose returned `version` might be chained by a caller into a subsequent optimistic-lock check needs an explicit flush — auto-flush-at-commit is not soon enough.

**A "read-then-lock" pattern for lazy resource creation defeats its own pessimistic lock on the (far more common) already-exists path.** `AccountServiceImpl.getOrCreateLocked()` originally called an unlocked `findByStudentId` first (to decide whether to create), then a locked `findWithLockById` second, for every credit — not just the first-ever one. Because both queries ran inside the same persistence context/session, Hibernate's first-level cache returned the *same managed entity instance* for the second (locked) query rather than re-hydrating its fields — meaning the lock was genuinely acquired at the database, but the in-memory `version` the transaction would later write back with was whatever the *first, unlocked* read had seen. Under `AccountConcurrencyIT`'s real concurrent threads, this surfaced as a reproducible `ObjectOptimisticLockingFailureException` even though the row-level lock was serializing correctly. Fixed by adding `findWithLockByStudentId` and using it as the *only* read for the common case, falling back to create+re-lock only when it's genuinely absent. **General lesson:** never issue an unlocked read and a locked read for the same identity within one persistence context expecting the second to refresh the first's cached state — it won't.

**B1.7/B1.8's data-scope mechanism, built in B1 and left "mechanism only" through B2, is now wired to two real entities.** `ServiceRecordScopeFields`/`AccountScopeFields` implement `ScopeFields<T>` against the denormalized `studentId`/`gradeId`/`classId` columns each entity carries (mirroring the existing display-name denormalization pattern); `list()`/`getDetail()`-style reads on both `servicerecord` and `account` now run through `DataScopeSpecifications.apply(...)`, with `grantAll` decided by the calling service checking for `admin`/`sysadmin` role codes — the resolver itself still never infers scope from a role name. Owner-gated mutation endpoints (`PATCH`/`DELETE`/`submit`/`withdraw`) use a simpler direct ownership check instead (`record.studentId == principal.studentId()`), since "Owner" in the contract's permission column is a different, narrower question than "in scope" and a teacher's Grades/Classes scope should never let them edit a student's draft.

**The review queue (B3.6) is a hand-written `NamedParameterJdbcTemplate` projection against `service_record`/`service_category`, not an entity graph** — deliberately, since it's flagged as one of the two busiest admin surfaces at scale. This is a legitimate cross-module *read-model query* (Design.md §6's "or a read-model query" clause): raw SQL carries no Java class dependency on `servicerecord.domain`, so `ModuleBoundaryTest` doesn't (and shouldn't) flag it, even though `review` is reading `servicerecord`'s table directly.

**Live verification, for reference** (throwaway admin, student, and linked student-user created directly via SQL for this check — the `student ↔ user` link has no API surface yet, by design — then deleted along with every row the flow created):
```
$ curl -X POST .../service-records                      → 201, DRAFT, version 1
$ curl -X POST .../service-records/{id}/submit            → 200, SUBMITTED, version 2
$ curl .../review-queue          (as admin)                → 200, the record, priority "low", hasAnomaly false
$ curl -X POST .../service-records/{id}/reviews {"decision":"APPROVE",...} → 200, APPROVED, creditedUnits 100, version 4
```
Database check after approval: exactly one `review_decision`, one `account_transaction` (`SERVICE_CREDIT`, amount 100, `balance_after` 100), one `student_progress` row (`total_credited_minutes` 60, `total_credited_centi_coins` 100, `honour_level` `NONE`), one `notification` (rendered `serviceRecord.approved`, Chinese — matching the student's `preferredLanguage`). `GET /students/{id}/progress` returned `completedMinutes: 60, gapMinutes: 4440, timeCoinBalance: 100, recognitionLevel: "none"` — all consistent.

65 tests pass on the server against real MySQL (up from 32 at the end of B2), confirmed via a full `mvn verify` run: the 32 existing B0-B2 tests unchanged, plus `ServiceRecordStateMachineTest` (9, new, unit — no Spring context), `AccountLedgerIT` (6, new), `AccountConcurrencyIT` (1, new — 20 real threads against one row), `ReviewQueueIT` (3, new), `ApprovalTransactionIT` (8, new — the full happy path, idempotency replay, rule-immutability, and five separate forced-failure-per-effect tests each proving the other four effects were not committed), `ServiceRecordSubmissionIT` (6, new — one test per B3.3 validation rule plus the optimistic-concurrency exit criterion).

## 15. B3.4/B3.5 Implementation Findings (2026-08-03) — file module, local-disk storage

**What changed from §14's plan.** Immediately after the core-loop pass above, the file/evidence module was taken up the same day rather than left for a separate MinIO-installation checkpoint — per updated direction, this pass builds the file module now against a **local-disk `FileStorage` adapter**, with disk space explicitly not a concern for this test-stage volume, and the real S3-compatible adapter (MinIO test-server / Aliyun OSS production) tracked as its own new phase, `14-Backend-B3F-CloudFileStorage.md` (appended after B7 rather than inserted into the B0–B7 sequence, per §10's "never renumber" rule). `FileScanner` does real magic-byte/MIME/size checks; it does not scan for malware — that gap is named, not hidden, and is B3F.6.

**Storage path on the test server:** `/opt/timebank-backend/data/evidence-storage/` — one file per evidence upload, named by its opaque `fileId` (e.g. `file_qd9q5hvjc2dgm39vd1mjx6rvcj`), no subdirectories or extensions. Configured as `timebank.file.storage-path` in `application.yml`, defaulting to the **relative** path `data/evidence-storage` (env override: `TIMEBANK_FILE_STORAGE_PATH`) — it resolves against the process's working directory, which is `/opt/timebank-backend` under the `timebank-backend.service` systemd unit's `WorkingDirectory=` (confirmed live: `readlink -f /opt/timebank-backend/data/evidence-storage` on the server). `LocalFileStorage` creates the directory on startup if missing. Untracked by git (`backend/data/` added to `.gitignore`) since `mvn verify` creates the same relative path locally when integration tests run, and none of it is real evidence — it is disposable, unlike whatever B3F's real object-store adapter will hold.

**A second module-cycle risk, caught before it landed.** The natural design for B3.5's "does this non-uploader have visibility into a record this file is attached to" check would have called `ServiceRecordService.getDetail(...)` from `file` — but `servicerecord` already needs to call `file.statusesOf(...)` for B3.3's evidence-readiness validation, so that would have created a `file` ↔ `servicerecord` cycle exactly like the one B1 caught between `iam` and `security`. Resolved the same way: the direction of the dependency was chosen (`servicerecord` → `file`, never the reverse) and `file`'s own scope check was reimplemented directly against `DataScopeResolver`/`ScopeDescriptor` using denormalized columns `ServiceRecordReferenceRepository` already returns, rather than delegating to `ServiceRecordService`. Caught by `mvn compile` immediately (`BeanCurrentlyInCreationException`-shaped failure at the design stage, before it ever reached a running context) once the two services' constructors were wired up.

**The "presigned URL" step of contract §4.6 is a real, self-hosted endpoint in this pass, not a native object-store feature.** With no S3-compatible store behind the port yet, `POST /files/upload-intents` returns an `uploadUrl` pointing at this app's own `PUT /files/{id}/content` — meaning, unlike D9's stated goal, **the app does proxy file bytes** for this test-stage pass. This is flagged explicitly in the file module's package Javadoc and in `14-Backend-B3F-CloudFileStorage.md` as the thing a real adapter swap fixes automatically, not a design regression to carry forward.

**Short-lived download URLs are app-issued HMAC tokens, not native presigned GETs**, for the same local-disk reason — `DownloadTokenService` signs `fileId:expiry` with `HmacSHA256` under a required (`TIMEBANK_FILE_TOKEN_SECRET`, no default — matches every other secret in this project), 5-minute-TTL scheme. The permission/scope check still happens once, at token-issue time (`GET /files/{id}`); the token's only job is bounding how long a leaked link stays useful, exactly as a real presigned URL's would.

**Live verification, for reference** (throwaway admin + student + linked student-user created directly via SQL, then deleted, matching §14's pattern):
```
$ curl -X POST .../files/upload-intents {"filename":"evidence.jpg","mimeType":"image/jpeg",...} → 201, fileId, uploadUrl, status implied PENDING
$ curl -X PUT .../files/{id}/content --data-binary @evidence.jpg                                 → 200
$ curl -X POST .../files/{id}/complete                                                            → 200, status READY, downloadUrl present
$ curl .../files/{id}/content?token=...                                                           → 200, bytes byte-identical to the uploaded file
$ curl -X POST .../service-records {"evidenceFileIds":["file_..."],...}  → 201, DRAFT
$ curl -X POST .../service-records/{id}/submit                            → 200, SUBMITTED (evidence readiness check passed)
$ curl -X POST .../service-records/{id}/reviews {"decision":"APPROVE",...} (as a *different* admin) → 200, APPROVED, creditedUnits 100
$ curl .../files/{id}                              (as the same admin, not the uploader) → 200, metadata + a fresh download token
```
Database check confirmed the cross-student access wrote exactly one `audit_log` row (`action = file.crossStudentAccess`, `actor_id` = the admin, `resource_id` = the file).

76 tests pass on the server against real MySQL (up from 65 above), confirmed via a full `mvn verify` run: the 65 tests above unchanged, plus `FileServiceIT` (9, new — upload/complete pipeline, MIME-mismatch rejection, unsupported-type rejection at intent time, uploader-vs-cross-student read access with the audit entry, delete blocked once bound to a submitted record, delete allowed while only bound to a draft, `statusesOf`'s missing-id handling, and a real byte-for-byte download round-trip), plus two tests added to `ServiceRecordSubmissionIT` (a `PROCESSING` file and a `REJECTED` file each independently block submission with their own `EVIDENCE_NOT_READY` field error).

**What's still open, honestly.** Gate G2's "demonstrated on the real frontend" bullet is I2's job, not this pass's — the mock client is still in place. The review-queue/record-list query-count + `EXPLAIN` exit-criteria bullet was not written this pass either (the queue's single-`JdbcTemplate`-query construction makes an N+1 structurally impossible, but that's an argument from design, not a measurement — a real query-count test is still owed). Neither blocks moving on to B4, but both should be picked up before Gate G2 is actually declared closed.

## 16. B4 Implementation Findings (2026-08-03) — Projects & Proposals

**What changed from the plan.** Built as designed: `project` (time-derived lifecycle, synced lazily on read/action access — no ShedLock/cron introduced for one phase), `enrollment` (the contract's `Application`, capacity/waitlist under row locking), and `proposal` (draft → submit → review → approve, with approval atomically generating the official project and backfilling `approvedProjectId`, invariant 6). `EventProposal`'s field set follows `APIContract.md` §3.2, not the frontend mock's heavily divergent shape — the mock has zero real write call sites on this surface, so per D10.3's precedence this is a new named divergence, not a silent reconciliation. `organization.projectCount` (B2's `= 0` placeholder) is now a real `JdbcTemplate` count against `project`, the same cross-module read-model pattern as B3.6's review queue. Two circular-Spring-bean risks were designed around before they could land: `EnrollmentBulkActionTransaction` holds its own `EnrollmentRepository`/`AuditService` rather than depending on `EnrollmentService` (which constructs it), and `ProposalServiceImpl`'s `submit()` was initially written with the exact self-invocation trap B0/B1 already found (a non-`@Transactional` method calling a `@Transactional` private helper via a lambda) — caught and fixed before deployment by mirroring `ServiceRecordServiceImpl.submit()`'s proven shape.

**A REPEATABLE READ snapshot bug that defeated a correctly-acquired row lock.** `EnrollmentServiceImpl.register()` originally ran a plain (non-locking) "already registered?" `SELECT` *before* `projectService.lockForRegistration()`'s `SELECT ... FOR UPDATE`. Under MySQL's default REPEATABLE READ, a transaction's consistent-read snapshot is established at its *first* plain read, not at `BEGIN`; a locking read bypasses that snapshot and always reads the latest committed row. Because the plain duplicate-check ran first, every concurrent transaction took its snapshot before ever waiting on the lock — so even though the lock correctly serialized the transactions one at a time, each one's later `countByProjectIdAndStatus` capacity count still read its own *pre-lock* snapshot, oblivious to seats other transactions had just confirmed. `EnrollmentCapacityIT` (10 concurrent registrations, capacity 3) caught this precisely: 7 confirmed instead of 3. Fixed by reordering `register()` so the locking read happens first, before any plain read in the transaction — the same "one locked query only" discipline `AccountRepository`'s B3 lesson already named, but here the trap was a plain read that *preceded* the lock, not a second unlocked read *after* it. **Worth internalizing for B5/B6:** the ordering of the *first* read in a transaction matters just as much as which reads are locked.

**The B3-documented "flush before returning a chainable version" lesson had one more instance, caught by live verification, not by the test suite.** `ProjectServiceImpl.getDetail()` (and `list()`) call `syncTimeBasedStatus()`, which conditionally mutates `status` in memory — but neither method flushed before building its response DTO. `ProjectLifecycleIT.registrationOpenStatusSyncsLazilyOnceItsTimeHasPassed` asserted the returned `status` and passed, because the state machine mutation *is* visible in memory immediately; but the entity's JPA `@Version` field only increments at flush time, so the DTO's `version` was one behind the row Hibernate was about to write at commit. This was invisible to the original test (which never chained the returned version into a follow-up call) and was only caught by live verification: `GET /projects/{id}` returned `version: 3` while `SELECT version FROM project` showed `4` immediately after. A caller chaining that stale version into `close`/`archive`/`publish` would hit a spurious optimistic-lock conflict. Fixed with `repository.saveAndFlush(project)` (`saveAllAndFlush` for the list path) right after `syncTimeBasedStatus()`, and the IT test extended to chain the returned version into a real `close()` call so this class of bug fails a test next time, not just a live check.

**Opaque-ID prefixes have a hard 5-character ceiling, easy to blow past without noticing.** `OpaqueIds.generate(prefix)` produces `prefix + "_" + 26 random chars`; every id column in this schema is `VARCHAR(32)`. Two prefixes chosen for new append-only rows — `"prjrev"` (`ProjectRevision`) and `"proprev"` (used for *both* `EventProposalRevision` and `ProposalReviewEvent`) — were 6–7 characters, pushing the generated id to 33–34 characters and failing every insert with a data-truncation error. This surfaced confusingly: the truncation happened *inside* the `IdempotencyService`-wrapped action, so the symptom at the call site was a generic "Duplicate request is being processed" (the idempotency wrapper's own failure path), not an obvious column-width error — the real cause only showed up by reading the full stack trace, not the top-level exception message. Shortened to `"prjr"`, `"eprv"`, `"eprw"` respectively (all comfortably under the limit). `project.status VARCHAR(16)` was also one character too narrow for `REGISTRATION_OPEN` (17 chars) — widened to `VARCHAR(20)`, matching `event_proposal.status`'s already-correct sizing. **Worth checking for B5/B6:** any new `OpaqueIds.generate(prefix)` call should have its prefix length checked against the target column width by arithmetic, not by assumption.

**Live verification, for reference** (throwaway admin, teacher, 3 students + linked student-users created directly via SQL, then deleted):
```
$ curl -X POST .../event-proposals {"title":"Smoke Campus Cleanup",...}        (as student) → 200, DRAFT
$ curl -X POST .../event-proposals/{id}/submit                                              → 200, SUBMITTED, version 2
$ curl -X POST .../event-proposals/{id}/reviews {"decision":"approve",...}     (as teacher)  → 200, APPROVED, approvedProjectId set, version 3
$ curl .../projects/{approvedProjectId}                                       (as admin)    → 200, DRAFT, originProposalId + studentOrganizerIds backfilled correctly
$ curl -X POST .../projects/{id}/publish {"version":2}                                       → 200, PUBLISHED
$ curl .../projects/{id}                                                                    → 200, REGISTRATION_OPEN (synced lazily), version correctly incremented after the getDetail fix above
$ curl -X POST .../projects/{id}/applications  (student 1, then 2, then 3; capacity 2)       → CONFIRMED, CONFIRMED, WAITLISTED
```
96 tests pass on the server against real MySQL (up from 76 at the end of B3.4/B3.5): 27 unit/ArchUnit + 69 integration, confirmed via a full `mvn verify` run — the 76 tests above unchanged, plus `ProjectStateMachineTest` (5, new, unit), `ProposalStateMachineTest` (6, new, unit), `ProjectLifecycleIT` (3, new — action-endpoint-only transitions, lazy time-sync, and the chained-version regression above), `ProposalApprovalTransactionIT` (4, new — happy path with exactly one project + one review event + one notification, duplicate-`Idempotency-Key` replay, a forced project-generation failure leaving no approved proposal and no orphan project, and reject-generates-no-project), `EnrollmentCapacityIT` (1, new — 10 real concurrent threads against capacity 3, proving exactly 3 confirmed after the REPEATABLE READ fix above), `OrganizationProjectCountIT` (1, new).

**What's still open, honestly.** The same two Gate G2 items named in §15 remain open (frontend demonstration, review-queue query-count/`EXPLAIN` measurement) — neither is B4's job. B4 itself has no comparable open item: every phase-document exit criterion was exercised either in the IT suite or in the live verification above.

## 17. B5 Pass 1 Implementation Findings (2026-08-03) — Caseflow, Notifications, Remaining Admin Surfaces

**What changed from the plan.** B5 was split into three passes by explicit user direction, mirroring how B3 split into core-loop-then-file-module: Pass 1 (this section) covers B5.1-B5.5 and the B5.12 remainder, since B1 already shipped `/users`, `/roles`, `/permissions`, and `/login-logs` — only `/audit-logs` and `/system/settings` were actually new. Built as designed: `caseflow` (appeal/supplement/manualCorrection, a state machine copying `ProposalStateMachine`'s shape, resolution notifying the case's student and — for `manualCorrection` + `resolve` only — appending a ledger adjustment), the `notification` module's read side (list/unread-count/mark-read/mark-all-read/announcements), and the two new admin endpoints. Zero frontend mock representation exists for `Case`/`Appeal`/`Supplement` at all, so field design came directly from the contract's status machine and `Design.md` Q6 (the retroactive-entry scenario) rather than resolving a divergence.

**A lazy-account-creation gap in the ledger, caught live, not by the test suite's first pass.** `AccountService.adjust(accountId, ...)` requires an account to already exist (`lockedOrThrow`) — correct for its own contract endpoint (`/accounts/{id}/adjustments`, which always names an existing account in the URL), but wrong for `caseflow`'s manual-correction resolution, where the whole point is often crediting a student's *first-ever* missed hours. `CaseDecisionTransactionIT` initially failed with "Account not found" on exactly this path. Fixed by adding `AccountService.adjustForStudent(studentId, gradeId, classId, request, actorId)`, which lazily creates the account first (reusing `credit()`'s existing `getOrCreateLocked` logic) before appending the correction — `caseflow` now calls this instead of `getByStudentId` + `adjust`. **Worth checking for any future caller of `adjust`:** know whether the account is guaranteed to exist before choosing which of the two methods to call.

**`JpaSpecificationExecutor` was the wrong tool for a combinable-filter read on an append-only table — it silently reintroduces the exact write capability the table's own test guards against.** `AuditLogRepository` deliberately extends the bare `Repository` marker, not `JpaRepository`, specifically so no delete/update method exists to call (invariant 10, proven by `AuditServiceIT.repositoryDeclaresNoUpdateOrDeleteMethod`'s reflection check). Adding `JpaSpecificationExecutor<AuditLog>` alongside it to support `GET /audit-logs`' multi-field filtering seemed safe (its own methods are `findOne`/`findAll`/`count`) — but Spring Data JPA's `JpaSpecificationExecutor` also declares `delete(Specification)`, which showed up in the reflection scan and failed the test immediately. Fixed by reverting the interface change entirely and building `AuditLogQueryServiceImpl` against `NamedParameterJdbcTemplate` instead — which is also what `Design.md` D6 designates for exactly this kind of dynamic-filter read (matching B3.6's review-queue precedent), not a workaround. **General lesson:** an interface's own advertised read-only surface is not a reliable guide to what it structurally adds — check its actual declared methods, not just the ones a specific list endpoint plans to call.

**The contract's own `filter[field]` query convention (B0.6) had never been exercised over real HTTP before this pass, and it doesn't work out of the box.** `PageQueryArgumentResolver` parses query-parameter *names* containing literal `[`/`]` (e.g. `filter[resourceType]=case`) — but Tomcat's default RFC 3986 query-string validation rejects `[`/`]` outright, returning a bare `400` before the request ever reaches Spring MVC. This was invisible through B0-B4 because no list endpoint's tests ever sent a real `filter[...]` parameter over an actual HTTP connection (`PageQuery`'s own Javadoc already flagged it as "unexercised by a real list endpoint yet" back in B0.6). `GET /audit-logs` is the first endpoint to actually need filters, and the live smoke test below is what caught it. Fixed with `server.tomcat.relaxed-query-chars: "[,]"` in `application.yml`. **This fix benefits every future list endpoint that adopts `filter[...]`, not just this one** — worth remembering it's already handled.

**A pre-existing seed-data gap, unrelated to any code this pass wrote:** `role_teacher` and `role_admin` never had `cases.create` — only `role_student` did (`R__seed_permissions_and_roles.sql`, seeded back in B1/B2). Since a `manualCorrection` case is realistically staff-initiated (a teacher identifying a student's missed hours, as the live scenario below exercises), both roles needed the grant. Added `perm_cases_create` to both. **Live gotcha worth remembering:** granted authorities are fixed into the session at login — a user already logged in when a permission grant lands does not see it until they log in again; the smoke test below hit exactly this (a fresh `403` after the redeploy, resolved only by a fresh login).

**Live verification, for reference** (throwaway admin, teacher, and student + linked student-user created directly via SQL, then deleted):
```
$ curl -X POST .../cases {"studentId":"stu_...","type":"manualCorrection",...}   (as teacher) → 200, DRAFT
$ curl -X POST .../cases/{id}/submit                                                          → 200, SUBMITTED, version 2
$ curl -X POST .../cases/{id}/decisions {"decision":"resolve",...}             (as admin)     → 200, RESOLVED, version 3
$ curl .../notifications/unread-count                                          (as student)  → 200, unreadCount 1, ETag "unread-1"
$ curl .../notifications/unread-count -H "If-None-Match: \"unread-1\""                        → 304, no body
$ curl -G .../audit-logs --data-urlencode "filter[resourceType]=case" --data-urlencode "filter[resourceId]=case_..." (as admin) → 200, 2 rows (create, submit)
```
Database check after resolution confirmed exactly one `account_transaction` (`CORRECTION`, amount 500, `balance_after` 500) for a student who had never earned before — proving the lazy-account-creation fix above live, not just in the IT suite.

114 tests pass on the server against real MySQL (up from 96 at the end of B4): 32 unit/ArchUnit + 82 integration, confirmed via a full `mvn verify` run — the 96 tests above unchanged, plus `CaseStateMachineTest` (5, new, unit), `CaseDecisionTransactionIT` (4, new — manual-correction resolve generates exactly one ledger transaction, appeal/supplement resolve never touches the ledger, duplicate-`Idempotency-Key` replay, out-of-scope teacher gets an invisible `404` on a case), `NotificationReadPathIT` (2, new — unread-count accuracy plus a real `304` proof against the embedded server, and mark-all-read), `AuditLogQueryIT` (4, new — filter by resource type/id, by actor, by date range, and a reflection check that the controller has no mutating mapping at all), `SystemSettingsIT` (3, new — get/update round trip, stale-version rejection).

**What's still open, honestly.** Pass 2 (dashboards, reports, materialized summaries) and Pass 3 (exports, imports, the executor/chunking/checkpointing infrastructure) were done the next day — see §18. Announcements are read-only this pass (the contract has no create endpoint); rows are populated directly, the same way `notification_template` rows are.

## 18. B5 Pass 2 & Pass 3 Implementation Findings (2026-08-04) — Dashboards, Reports, Materialized Summaries, Exports, Imports

**What changed from the plan.** Both remaining passes were built in the same session. Pass 2: a new `reporting` module — `/dashboards/{student,teacher,admin}` (composing existing modules' application services for the student dashboard's single-entity reads; raw `JdbcTemplate` aggregation via a new `ReportQueryRepository` for the teacher/admin dashboards' scoped counts and school-wide stats, per D6), the five `/reports/*` endpoints (participation/completion read a materialized summary table when one exists and fall back to the identical live query otherwise, never a 500 before the first scheduled tick; service-hours/review-efficiency/top-projects stay live since Design.md §16.2 only names participation/compliance as needing materialization), and `SummaryMaterializationJob` — an hourly `@Scheduled` method, this codebase's first, guarded by `@SchedulerLock` (ShedLock, JDBC-backed via a new `shedlock` table, added as a dependency exactly when the first scheduled job needed it, per the placeholder comment left in `pom.xml` since B0). Pass 3: a new `dataexchange` module — a single `job` table (V10) shared by imports and exports (D5), a dedicated 2-thread `jobExecutor` bean (never Tomcat's virtual-thread request pool), `ExportJobRunner`/`ImportJobRunner` as `@Async` workers, and `JobReclaimSweep` (ShedLock again) resubmitting any job whose lease expired mid-run. Historical import (`serviceRecordsHistorical`) reads CSV, not Excel — Design.md D5 names EasyExcel/POI SXSSF; CSV via plain `BufferedReader` is equally streaming/bounded-heap and needs zero new dependencies, a deliberate scope reduction in the same spirit as B3F's local-disk substitution, not silently narrower. It credits the ledger directly via `AccountService.adjustForStudent` per valid row — no synthetic `ServiceRecord`/review history is fabricated for imported rows, since a paper ledger realistically has no such history to reconstruct; the "serviceRecords" export therefore only ever contains records that went through the real submit→review→approve pipeline, never an imported one (this surfaced as a live-verification-only gap, not a test failure — see below).

**Two classic Spring pitfalls, both caught only by real MySQL under a real async executor — neither shows up against an in-memory or mocked context.** (1) **Triggering an `@Async` call from inside the same `@Transactional` method that just wrote the row it needs.** `ExportServiceImpl.create()` did `jobRepository.saveAndFlush(job)` then immediately `exportJobRunner.runAsync(job.getId())` — but `@Transactional`'s commit happens only when the *entire* proxied method returns, which is *after* the async call has already dispatched to another thread. That thread's own connection, under MySQL's default isolation, could not see the still-uncommitted insert, and `JobProgressWriter.claim()` threw "Job not found" — swallowed by Spring's default `SimpleAsyncUncaughtExceptionHandler` (logged, not rethrown), so the job silently never left `QUEUED` and a polling client waited forever. Fixed by removing `@Transactional` from `create()` entirely: `saveAndFlush` then runs in its own implicit, immediately-committing repository transaction, so the row is durably visible before the async trigger fires. **General lesson, worth checking on any future `@Async` trigger:** never call an async method from inside a still-open transaction that wrote something the async method needs to read — either let the caller's transaction close first, or make the caller's write its own standalone transaction. (2) **A buffered writer's content is invisible to a second stream opened on the same file before the writer closes.** `ImportJobRunner.validateAsync` built the error-report CSV and then, *inside the same try-with-resources block that still held the `BufferedWriter` open*, opened a fresh `FileInputStream` on the same temp file to store it — reading back whatever had made it past the writer's internal buffer, which for a small file was nothing. The bug was invisible to the row counts (`totalRows`/`validRows` were correct, computed in memory) and only showed up as an empty error report. Fixed by closing the writer (exiting its try-with-resources block) before ever reopening the file for reading.

**A column meant two different things depending on which phase read it, and the reader guessed wrong.** `Job.validRows` is validate's "how many rows are valid" — but `ImportJobRunner.commitAsync` seeded its own `committed` counter (meant as "how many rows a *prior, interrupted commit* already processed," for resume) from that same column, right after validate had just set it to "count of valid rows in the file." A fresh (non-resumed) commit therefore always over-reported its committed count by exactly the valid-row count — caught first in live verification (`validRows: 2` after committing a single-row file), not by the IT suite, because the original test only asserted the ledger balance (correct either way) and not the reported count. The ledger was never wrong — only the number displayed — but it would have made the resume-seed genuinely wrong for a real partial-commit-then-crash. Fixed by not trusting any stored "committed so far" value at all: every row is re-validated even when skipped as already-processed, so `committed` is always a live recount, self-consistent regardless of which phase last touched the column. **General lesson, in the same family as B2's cache-key collision and B4's opaque-id-prefix bug:** a column whose meaning silently depends on which code path last wrote it is a bug waiting for the next reader who assumes the obvious meaning.

**A related, narrower checkpoint bug:** `checkpointOffset` was only persisted at chunk boundaries (every 500 rows) and never on successful completion for a file smaller than one chunk — so a completed job's checkpoint could still read `0`, meaning a later reclaim of that (already fully COMMITTED) job would treat it as brand new and re-credit every row. Fixed with one unconditional final checkpoint write before `succeed()`. The deeper version of this — a crash *exactly* mid-chunk still re-processes (and re-credits) that chunk's already-applied rows on resume — is a real, named, *not* fully closed gap: full correctness would need the row-credit write and the checkpoint write to commit atomically together, which `AccountService`'s current per-call transaction ownership doesn't support. Documented in `ImportJobRunner`'s own Javadoc rather than left implicit.

**Live verification, for reference** (throwaway admin + student created directly via SQL, then deleted — the same workflow as every prior phase; local-disk export/import artifacts under `data/evidence-storage/` also deleted afterward, being disposable per §15's precedent):
```
$ curl .../dashboards/admin           (as admin) → 200, generatedAt, stats.totalStudents 1
$ curl .../reports/participation                  → 200, generatedAt, all 6 grades, grade_6 total 1
$ curl -F type=serviceRecordsHistorical -F file=@ledger.csv .../imports  → 201, QUEUED
$ curl -X POST .../imports/{id}/validate           → VALIDATED, totalRows 1, validRows 1
$ curl -X POST .../imports/{id}/commit (Idempotency-Key: ...) → COMMITTED
$ curl .../accounts?filter[studentId]=...          → balance 200 (120 min → 120 rounded → 200 centi-coins)
$ curl -X POST .../exports {"reportType":"serviceRecords"} → SUCCEEDED
$ curl .../exports/{id}/download                   → downloadUrl with a signed, time-boxed token
$ curl .../exports/{id}/content?token=...           → 200, CSV bytes
```
Second import (a differently-worded file for the same student, after the fix above) reported `validRows: 1` correctly, not `2`, confirming the fix live, not just in the IT suite.

124 tests pass on the server against real MySQL (up from 114 at the end of B5 Pass 1): 32 unit/ArchUnit + 92 integration, confirmed via a full `mvn verify` run — the 114 tests above unchanged (including `CaseDecisionTransactionIT` growing from 4 to 5 with the appeal-timeline test that closed Pass 1's own last gap), plus `DashboardReportingIT` (4, new — participation/completion reports matching hand-computed figures for a seeded grade, teacher dashboards scoped correctly per principal, student/admin dashboards carrying `generatedAt`), `ImportExportIT` (5, new — validate producing correct counts and a row-level error report, commit crediting exactly the valid rows once with the correct reported count, re-importing an identical file rejected before it can double-credit anything, the checkpoint-skip mechanism proven directly against a simulated reclaim, and exports completing asynchronously with downloadable content).

**What's still open, honestly.** The 500k-row import / p95-under-concurrent-load claim in `07-Backend-B5-AdminSurface.md` §4 is not proven — that's G4/B6.10's job, same as every other load-test-shaped claim in this programme. The mid-chunk-crash re-credit gap named above is real and undecided, not merely unmeasured. B5's own phase-level exit criteria are otherwise all met; the phase stays `▶` on that one item alone.
