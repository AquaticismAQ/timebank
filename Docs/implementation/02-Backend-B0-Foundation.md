# B0 · Foundation

> Phase B0 of `01-Overview.md`. Everything every later phase assumes: project skeleton, response envelope, migration discipline, audit infrastructure, CI, and a production-sized test dataset.

| | |
| --- | --- |
| **Depends on** | Nothing |
| **Blocks** | All backend phases; `B0.9` blocks G3/G4 |
| **Effort / calendar** | 7 pw · W1–W3 |
| **Design refs** | `Design.md` §5–§7, §9, §11, D7, D11, §20.1–20.2 |

---

## 1. Goal

A running, deployable, empty application that already has the cross-cutting behaviour every endpoint depends on — so that no later phase has to invent an error format, a pagination shape, or an audit hook. Plus the seeder, built now rather than late, because every performance and migration decision from B3 onward is unverifiable without production-sized data.

## 2. Scope

**In:** project and module skeleton, common web layer, Flyway baseline, audit infrastructure, idempotency and locking helpers, test-server deployment and CI (no containers), test schema isolation, observability skeleton, test-data seeder.

**Out:** any business endpoint; any domain entity beyond what the seeder needs; authentication (B1).

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B0.1** | Project skeleton — Java 21, Spring Boot 3.4.x, Maven single module, package root `cn.<school>.timebank`, `spring.threads.virtual.enabled=true` | Builds and runs; module packages per `Design.md` §7 created empty |
| **B0.2** | Module boundary enforcement — Spring Modulith or ArchUnit | A test that **fails** on a cross-module reach-in and on an entity leaking into a controller |
| **B0.3** | Response envelope — `ApiResponse<T>`, `{data, page, meta}` shapes, `meta.requestId` | Contract §2.5 shapes verified by MockMvc tests |
| **B0.4** | Error handling — single `@RestControllerAdvice`: validation → `422` + `fieldErrors`, optimistic lock → `409`, access denied → `403`, not-found/out-of-scope → `404`; no stack traces, SQL, or Hibernate text ever reaching the client | Every code in contract §2.6 reachable by a test |
| **B0.5** | Request context — `requestId` filter into MDC, structured JSON logging, never logging tokens, session ids, evidence URLs, or personal-data bodies | A request's log lines correlate by `requestId` across both instances |
| **B0.6** | List primitives — `page[number]`/`page[size]` with a hard server max (100), `sort`, `filter[...]` parsing, stable ordering, and a `Pageable` mapping helper | Reusable by every later list endpoint; unstable-sort test |
| **B0.7** | Concurrency & idempotency helpers — `@Version` base entity, `If-Match`/version handling, `idempotency_record` table and the insert-then-execute flow (`Design.md` §11) | Replay returns the stored response; different fingerprint → `409`; in-flight → `409` |
| **B0.8** | Audit infrastructure — `audit_log` table, append-only repository with no update/delete method, service invoked inside the business transaction, compact JSON before/after | An attempt to update an audit row fails at the repository layer, not by convention |
| **B0.9** | **Test-data seeder** — 5,000 students, ~450k service records, ~1.1M login-log rows, realistic distributions of review states and file counts | A documented command populates staging; runtime and resulting row counts recorded |
| **B0.10** | Flyway baseline — `V1__baseline.sql`, `utf8mb4_0900_ai_ci`, `ddl-auto=validate` everywhere, `R__` repeatable seeds, **log-table monthly `PARTITION BY RANGE` decided now** (retrofitting later is expensive) | Migration runs clean from empty; partition strategy in the first migration |
| **B0.11** | **Test-server environment — no containers.** JDK 21 and the app deployed as a **systemd unit** from a fat JAR; MinIO installed as a native binary under its own unit; Spring profiles; secrets in a `0600` systemd environment file on the host, never in the repository | A documented deploy script builds, uploads, and restarts the service over SSH. Nothing to install locally except a JDK |
| **B0.12** | Observability skeleton — Actuator `/health` liveness + readiness, `/metrics`, internal-only access, Micrometer wired to the provider's monitoring | Load balancer can register an instance on readiness |
| **B0.13** | **Test schema isolation** — integration runs create `timebank_it_<run>`, migrate it with Flyway, and drop it afterwards; fallback is one pre-created `timebank_it` schema behind a run lock. **The application schema is never used by tests** | A test run cannot damage the shared development data — verified by pointing a run at a populated server |
| **B0.14** | CI on the server — self-hosted runner or SSH-invoked build running compile, unit, integration (real MySQL), ArchUnit, and emitting `openapi.json`. **No credentials in any hosted CI configuration** | Green build reachable from the repository; test schemas cleaned up even on failure |

## 4. Exit Criteria

- [ ] A clean clone reaches a **running app on the test server** through one documented deploy command; the service survives a host reboot (systemd enabled).
- [ ] Every envelope and error shape in contract §2.5–§2.6 is produced by a passing test.
- [ ] The ArchUnit/Modulith test fails when a deliberate boundary violation is introduced (verify by trying it).
- [ ] Idempotency replay, fingerprint conflict, and in-flight conflict all covered by integration tests against the server's real MySQL.
- [ ] An integration run creates and drops its own schema, leaves the application schema untouched, and cleans up after a **failed** run as well as a successful one.
- [ ] No credential appears anywhere in the repository — verified by a grep for the host, the database account, and the password before the first commit of this phase.
- [ ] Audit rows cannot be updated or deleted through any repository method that exists.
- [ ] Seeder produces the target dataset on the test server; runtime and row counts are recorded in `00-Status.md`.
- [ ] Flyway migration runs from empty with `validate`, including log-table partitioning.
- [ ] CI is green and publishes `openapi.json` as a build artifact.

## 5. Risks and Watch-Outs

- **The seeder is the item most likely to be deferred** ("we'll generate data when we need it"). Deferring it means B3's index decisions, B6's load test, and B7's restore drill are all built on 100-row fixtures — the exact failure `Design.md` §3.3 exists to prevent. It is a phase exit criterion for that reason.
- **Envelope-by-convention decays.** If controllers can return bare `ResponseEntity`, some will. Prefer the explicit `ApiResponse<T>` return type so a bare return is visible in review.
- **Virtual-thread pinning** (D3 caveat): confirm Hibernate, HikariCP, and the MySQL driver versions are pinning-free now, so B6's load test is a confirmation rather than a discovery.
- **Partitioning decided late is partitioning not done.** B0.10 is small; skipping it costs a multi-million-row table rewrite in B6.
- **A shared database is the cost of dropping Testcontainers.** Without per-run containers, two carelessly written suites will fight over the same rows, and a suite that truncates tables will wipe a colleague's working data. B0.13 is not optional plumbing — it is what replaces the isolation Testcontainers gave for free.
- **Credentials leak through convenience.** A connection string pasted into `application.yml` "just to get it running" is the most likely way the server details reach GitHub. Config reads from the environment from the very first commit; there is no temporary hard-coded path.

## 6. Hand-Off

B1 starts against a working skeleton, a settled error contract, and a seeder that can produce users at scale. I0 can begin in parallel from W4 — it needs the envelope shape (B0.3–B0.6) fixed, nothing more.
