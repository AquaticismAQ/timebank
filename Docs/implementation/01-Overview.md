# TimeBank · Phase 2 Implementation Plan — Overview

> The execution plan for the Phase 2 web platform: what gets built, in what order, by when, and what "done" means for each stage. It implements `Docs/backend/Design.md` against the contract in `Docs/integration/APIContract.md`. Live progress is tracked in `00-Status.md` — this document is the plan, that document is the state.

---

## 1. Document Set

| Doc | Covers |
| --- | --- |
| `00-Status.md` | **Living** status tracker: phase states, work-item checklists, gates, risk states |
| `01-Overview.md` | This document: phases, sequencing, timeline, staffing, conventions |
| `02-Backend-B0-Foundation.md` | Skeleton, envelope, migrations, audit, CI, test-data seeder |
| `03-Backend-B1-Identity.md` | Users, roles, permissions, session auth, data scope, `/me` |
| `04-Backend-B2-BaseData.md` | Students, base data, organizations, rules, localization, feature flags |
| `05-Backend-B3-CoreLoop.md` | Service records, evidence files, review, ledger, progress — the riskiest phase |
| `06-Backend-B4-Projects.md` | Projects, registrations, student event proposals |
| `07-Backend-B5-AdminSurface.md` | Cases, notifications, dashboards, reports, imports, exports, admin |
| `08-Backend-B6-Hardening.md` | Data lifecycle, purge, reconciliation, load test, security review |
| `09-Backend-B7-DatabaseOps.md` | Replication, backup, PITR, restore drills — **parallel track from week 2** |
| `10-Integration-I0-ContractPrep.md` | Types module, request-body freeze, HTTP client layer, divergence fixes |
| `11-Integration-I1-ReadPath.md` | Swapping read calls from mock to real backend, domain by domain |
| `12-Integration-I2-WritePath.md` | Wiring every mutation — the surface with no prior validation |
| `13-Integration-I3-Acceptance.md` | Evidence upload, localization, E2E acceptance, UAT, pilot cutover |

Chinese counterparts are **not** produced for this set, matching the precedent of `Docs/backend/Design.md` (see its closing note). These are internal working documents, not institutional design; the `_CN` sync obligation in `CONTRIBUTING.md` applies to `Design/` and to the contract, which are kept in sync.

## 2. Phase Map

```text
Backend (sequential core)                     Integration (overlapping)

B0 Foundation ─────────┐
                       ├── B7 Database Ops (parallel, W2→W26)
B1 Identity ───────────┤
     │                 │      I0 Contract Prep ──┐
B2 Base Data ──────────┤                         │
     │                 │      I1 Read Path ──────┤ (rolling, per domain)
B3 Core Loop ══════════╡                         │
     │                 │      I2 Write Path ─────┤
B4 Projects ───────────┤                         │
     │                 │      I3 Acceptance ─────┴── Pilot cutover
B5 Admin Surface ──────┤
     │                 │
B6 Hardening ──────────┘
```

The one hard ordering rule: **B3 is built early and is never deferred.** It carries the invariant the whole system exists to protect (one transaction: review event → record status → ledger → progress → notification). Everything after B3 is comparatively routine.

## 3. Timeline

**Staffing assumption:** 2 backend engineers, 1 frontend engineer, and ~0.2 FTE of operations capacity (which may be one of the backend engineers wearing a second hat). Effort is stated in **person-weeks (pw)** so it rescales if staffing differs; calendar weeks assume the staffing above.

| Phase | Effort | Calendar | Notes |
| --- | --- | --- | --- |
| **B0** Foundation | 7 pw | W1–W3 | Includes the test-data seeder and test-server deployment, both built early on purpose |
| **B1** Identity | 5 pw | W4–W6 | |
| **B2** Base data & rules | 5 pw | W6–W8 | Overlaps B1 tail |
| **B3** Core loop | 12 pw | W9–W15 | Longest and riskiest; do not compress |
| **B4** Projects & proposals | 7 pw | W16–W19 | |
| **B5** Admin surface | 8 pw | W20–W23 | Largest endpoint count, lowest risk per endpoint |
| **B6** Hardening & lifecycle | 6 pw | W24–W26 | Load test is a release gate |
| **B7** Database operations | 4 pw | W2–W26 (spread) | Parallel; first restore drill by W12 |
| **I0** Contract prep | 3 pw | W4–W6 | Blocks all later integration |
| **I1** Read path | 4 pw | W10–W20 | Rolling, domain by domain |
| **I2** Write path | 6 pw | W14–W22 | Follows each backend domain by ~2 weeks |
| **I3** Acceptance & cutover | 5 pw | W20–W28 | Includes UAT and the import rehearsal |
| **Total** | **~72 pw** | **~28 weeks** | ≈ 6.5 months to pilot go-live |

**If staffing differs:**

| Team | Realistic calendar |
| --- | --- |
| 1 backend + 1 frontend (part-time) | ~45–50 weeks — B3 and B5 stretch worst |
| 2 backend + 1 frontend (planned) | ~28 weeks |
| 3 backend + 1 frontend | ~23 weeks — B3 does not parallelize well; the gain is in B4/B5 |

**Calendar anchoring.** Weeks are relative to project start. If work begins mid-August 2026, W28 lands around late February 2027, which suits a **spring-term pilot go-live**. Aligning cutover with a term boundary matters more than shaving two weeks: mid-term cutover collides with active service records and in-flight reviews.

**Schedule reserve.** The figures above are working estimates with no buffer. Carry ~15% reserve (≈4 weeks) held at the programme level, not distributed into phases where it silently disappears.

## 4. Gates

Five checkpoints where work stops until the criterion is met. They exist because each guards a failure that is expensive to discover late.

| Gate | When | Criterion |
| --- | --- | --- |
| **G1 Contract freeze** | End of I0 (W6) | Request bodies (`APIContract.md` §13.4) reviewed and frozen; types module lifted; divergences 1–4 resolved. Backend and frontend sign off jointly |
| **G2 Core loop proven** | End of B3 (W15) | A service record goes draft → submitted → approved with evidence, ledger entry, progress update and notification in one transaction, demonstrated on the real frontend |
| **G3 Restore drill** | By W12, repeated W25 | A production-sized backup restored into a scratch instance with checksums matching, and RPO/RTO **measured** |
| **G4 Load test** | End of B6 (W26) | p95 < 300 ms lists/details, < 1 s dashboards at 300 concurrent on production-sized data; slow-query log empty |
| **G5 UAT sign-off** | End of I3 (W28) | Teachers and students complete all `APIContract.md` §11 scenarios in both languages; historical import rehearsed |

## 5. Conventions

**Work-item IDs** are `<Phase>.<n>` (e.g. `B3.4`, `I2.2`) and are stable — `00-Status.md` tracks them by ID, so items are never renumbered. New work appends.

**Definition of done for an endpoint** is `APIContract.md` §10.3, without exception: behaviour matches the contract, response matches the types module, permission and data-scope tests pass (including invisible-`404`), conflict and duplicate behaviours are tested, audit and idempotency are implemented per risk level, and **the frontend screen renders against it with the mock disabled**.

**Definition of done for a phase** is its own exit-criteria section. A phase with unchecked exit criteria is not done, regardless of how much of its work-item list is complete.

**Testing is written with the code, not after.** The `§19` test-level table applies per phase; phases do not accumulate a testing debt to be paid in B6.

**Every phase updates `00-Status.md`** on entry and exit. A status doc that is updated in a single burst before a review meeting is worthless.

## 6. Environments

**There is no Docker in this project.** Not in development, not in test, not in production. Services run as **systemd units** from a fat JAR, and every environment shares that one packaging model. Two consequences run through the whole plan: there is no `docker compose up` to bring a stack up locally, and **Testcontainers is not available**, so integration tests run against a real MySQL schema on the shared test server (`Design.md` §19).

| Environment | What it is | Used for |
| --- | --- | --- |
| **Test server** | A provisioned host running MySQL 8 and the application natively, reached over SSH. Host, database name, and account live **only** in `test-server-credits.md` at the repository root — untracked and git-ignored; this table deliberately does not repeat them | Development integration, all integration and API tests, the seeded dataset, day-to-day frontend-against-backend work |
| **Staging** | Production-sized, anonymized dataset | Load test (G4), migration timing, restore drill (G3) |
| **Production** | The school's deployment | Pilot go-live |

**Working model on the test server:**

- The application is deployed as a systemd unit; a restart is a service restart, not a container replacement.
- **Tests execute on the server**, against real MySQL — either run there directly or driven from a workstation over SSH. There is no local database to install and no local stack to compose.
- **Schema separation is mandatory.** The application schema is never the test schema: integration runs use their own schema (`timebank_it_<run>`), created, migrated, and dropped per run; the fallback where the account cannot create schemas is one pre-created test schema behind a run lock. A test suite that truncates the shared application schema destroys everyone's working data.
- MinIO runs on the server as a **native binary under systemd** (it is a single static executable), preserving the S3-compatible port from `Design.md` D9 without containers.
- The seeded 5,000-student dataset (B0.9) lives on the server, so every developer measures against the same volumes.

**Credential handling — a hard rule.** `test-server-credits.md` is git-ignored and must never be committed, pasted into a document, quoted in an issue, or added to CI configuration in a repository. Documents reference *the file*, never its contents. The same applies to database passwords, SSH keys, and object-storage credentials: they live in systemd environment files (mode `0600`) on the host, outside the repository. Because of this, **CI runs on the server** (a self-hosted runner or an SSH-invoked build) rather than in a hosted runner that would need the credentials injected as secrets.

## 7. What This Plan Does Not Cover

- **Phase 3 (POV) and Phase 4 (redemption/borrowing)** — feature flags only, per `Docs/backend/Design.md` §14. B2 delivers the flag table; nothing else is built or stubbed.
- **Frontend feature work** beyond wiring the existing screens to the backend. The UI is built (`Docs/frontend/Design.md`); integration adapts it, it does not extend it.
- **Table-level DDL and field-level schemas.** Those are produced inside each phase as Flyway migrations, reviewed there, not pre-specified here.
- **The Chinese backend design counterpart** (`Docs/backend/Design_CN.md`), still pending per that document's closing note.

## 8. Related Documents

| Document | Relationship |
| --- | --- |
| `Docs/backend/Design.md` | The design this plan executes. Section references throughout |
| `Docs/integration/APIContract.md` | Authoritative for behaviour and request bodies; §13 maps the frontend's actual consumption |
| `Docs/frontend/Design.md` | Pages, flows, and acceptance criteria the integration phases wire up |
| `Design/chinese/*.md` | Institutional rules behind the business logic |
