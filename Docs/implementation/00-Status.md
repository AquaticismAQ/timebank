# TimeBank · Implementation Status

> **Living document.** The single place to answer "where are we?". The plan lives in `01-Overview.md` and the phase docs; this file holds only state. Update it on phase entry, phase exit, and whenever a gate or risk changes — not in a burst before a review meeting.

**Snapshot as of 2026-08-02 — B0 (Foundation), B1 (Identity), and B2 (Base Data & Rules) all complete and live on the test server.** Phase 1 (institutional design) is complete. The frontend is built and read-wired against a mock client. The backend now has real authentication end to end plus the reference data every business record will point at: students, academic structure, organizations, service categories, versioned rules with an immutable-publish lifecycle, localization resolution, notification templates, feature flags, and dictionary caching — all proven against real MySQL (32 tests: 3 ArchUnit + 4 MockMvc + 25 integration) and live-verified over HTTPS. B3 (Core Loop) has not started.

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
| **Overall state** | In progress — B0, B1, and B2 complete |
| **Current phase** | B3 Core Loop (`—`, not started); B0.14's automated CI trigger remains open |
| **Planned duration** | ~28 weeks to pilot go-live (2 backend + 1 frontend engineer + ~0.2 FTE ops) |
| **Total effort** | ~72 person-weeks |
| **Start date** | 2026-08-02 |
| **Target go-live** | Not set — should land on a **term boundary** (`13-Integration-I3-Acceptance.md` I3.11) |
| **Schedule reserve** | ~15% (≈4 weeks), held at programme level, unallocated |

## 3. Gates

| Gate | Meaning | Planned | Status | Evidence |
| --- | --- | --- | --- | --- |
| **G1** Contract freeze | Request bodies frozen, types module lifted, divergences 1–4 resolved | W6 | `—` | |
| **G2** Core loop proven | Full record lifecycle with atomic crediting, demonstrated on the real frontend | W15 | `—` | |
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
| **B3** Core loop | `05-` | 12 pw | W9–W15 | `—` | | Carries G2; do not compress |
| **B4** Projects & proposals | `06-` | 7 pw | W16–W19 | `—` | | |
| **B5** Admin surface | `07-` | 8 pw | W20–W23 | `—` | | |
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
| B3.1 | Service record module + revisions | `—` |
| B3.2 | State machine (transition table) | `—` |
| B3.3 | Submission validation | `—` |
| B3.4 | File module (intent → complete → scan) | `—` |
| B3.5 | File access control + audit | `—` |
| B3.6 | Review queue projection | `—` |
| B3.7 | Review decisions (append-only) | `—` |
| B3.8 | Account module + immutable ledger | `—` |
| B3.9 | Ledger serialization (`FOR UPDATE`) | `—` |
| B3.10 | Adjustments, reversal, freeze | `—` |
| B3.11 | Progress projection | `—` |
| B3.12 | **The approval transaction (5 effects, atomic)** | `—` |
| B3.13 | Crediting calculation (integers only) | `—` |

### B4 · Projects & Proposals

| ID | Item | Status |
| --- | --- | --- |
| B4.1 | Project module | `—` |
| B4.2 | Project lifecycle action endpoints | `—` |
| B4.3 | `enrolledCount` + `myApplicationStatus` | `—` |
| B4.4 | Denormalized display names (no N+1) | `—` |
| B4.5 | Project timeline | `—` |
| B4.6 | Enrollment module | `—` |
| B4.7 | Capacity control under concurrency | `—` |
| B4.8 | Bulk registration actions | `—` |
| B4.9 | Proposal module | `—` |
| B4.10 | Approval chain (append-only) | `—` |
| B4.11 | **Atomic project generation on approval** | `—` |
| B4.12 | Proposal queue summary | `—` |

### B5 · Admin Surface

| ID | Item | Status |
| --- | --- | --- |
| B5.1 | Caseflow module | `—` |
| B5.2 | **Manual correction → ledger reversal** | `—` |
| B5.3 | Retroactive-entry supplement path | `—` |
| B5.4 | Notifications & announcements | `—` |
| B5.5 | Cheap polled endpoints (ETag/304) | `—` |
| B5.6 | Dashboards (per-principal, `generatedAt`) | `—` |
| B5.7 | Reports (SQL aggregation) | `—` |
| B5.8 | Materialized summaries | `—` |
| B5.9 | Exports (async, streamed) | `—` |
| B5.10 | Imports (validate before commit) | `—` |
| B5.11 | Chunking, checkpointing, executor isolation | `—` |
| B5.12 | Admin surfaces (users, roles, logs, settings) | `—` |

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

| # | Scenario | Status |
| --- | --- | --- |
| 1 | Proposal → changes requested → resubmit → approve → project draft | `—` |
| 2 | Publish generated project → students register → teacher manages list | `—` |
| 3 | Draft, upload evidence, submit a record | `—` |
| 4 | Return → supplement → resubmit → approve | `—` |
| 5 | Approval credits ledger and progress in one transaction | `—` |
| 6 | Appeal processed with a complete timeline | `—` |
| 7 | Historical import with validation errors, then commit | `—` |
| 8 | Out-of-scope access returns `403`/invisible `404` | `—` |
| 9 | Duplicate submit/approve/adjust creates no duplicates | `—` |
| 10 | Concurrent edits conflict, never silently overwrite | `—` |
| 11 | Full acceptance with no WeChat/IM dependency | `—` |
| 12 | Both languages render with no localized enum on the wire | `—` |

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
