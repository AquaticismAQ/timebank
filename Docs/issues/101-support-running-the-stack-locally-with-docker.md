# 101 — Support running the stack locally with Docker

**Status:** Implemented and **verified end to end** — stack built, started, seeded and exercised (§5).
**Raised:** 2026-09-18 by the user: not every contributor has a second machine
to use as a test server, and running the backend in Docker is the ordinary way
to avoid needing one.

## 1. This reverses part of a standing rule, deliberately

`Docs/implementation/01-Overview.md` §6 read:

> **There is no Docker in this project.** Not in development, not in test, not
> in production.

The user was told that when they first mentioned Docker, and reaffirmed the
request with a reason: the rule assumed every contributor has a spare host, and
most do not. That is their call, and it is a good one — but the rule is not
deleted, it is **scoped**, because the half of it that earned its keep is still
true:

| | Before | Now |
| :--- | :--- | :--- |
| Production deployment | systemd + fat JAR | **unchanged** |
| Integration tests | real MySQL, no Testcontainers | **unchanged** |
| CI | builds no image | **unchanged** |
| A contributor's laptop | nothing provided | `docker compose up -d --build` |

The rule existed so *production* would not have two packaging models drifting
apart. A development stack on one machine does not create that: if
`docker-compose.yml` breaks tomorrow, `deploy.sh` is untouched. `01-Overview.md`
§6, `CLAUDE.md` and `AGENTS.md` were all amended, because a rule the repository
states absolutely and then contradicts is worse than either answer.

## 2. Three things that would have silently broken it

Found by reading the code and the build output rather than by writing a
plausible Dockerfile.

**The app binds loopback only.** `application.yml` had a literal
`address: 127.0.0.1` — correct on a host, where Nginx fronts it, and fatal in a
container, where loopback is the *container's* loopback. A published port
connects to nothing and the symptom is a bare `Connection refused` from a
process that is running and healthy. Now
`${TIMEBANK_SERVER_ADDRESS:127.0.0.1}`: the default is unchanged, so systemd
keeps the property the line exists for, and compose is the only thing that
overrides it.

**The JAR has no version in its name.** `pom.xml` sets
`<finalName>timebank-backend</finalName>`, so `mvn package` produces
`timebank-backend.jar` — not `timebank-backend-0.1.7.jar` as the artifactId and
version suggest. A `COPY target/timebank-backend-*.jar` glob matches **zero
files** and fails the build. Caught by running `mvn package` and listing the
directory.

**The seeder required SSH.** `runSqlOnServer` shells through `ssh` to reach
MySQL. Against a container on localhost there is no SSH host, so a contributor
could bring the stack up and then have no way to put data in it — which is most
of the point of having it up. `TIMEBANK_SEED_DB_HOST` now selects a direct-TCP mode.
SSH stays the default: on the shared server the database is not reachable over
TCP from a workstation at all (VPC-internal,
`08-mlps-level1-security-design-guide.md` §3), so it is not a stylistic choice
there.

## 3. Smaller decisions worth recording

- **MySQL publishes 3307, not 3306.** Anyone who has installed MySQL locally
  already has 3306 bound — as this machine does — and the failure presents as a
  broken container rather than a port clash.
- **The db healthcheck authenticates.** An unauthenticated `mysqladmin ping`
  answers during initialisation, before `MYSQL_DATABASE`/`MYSQL_USER` exist, so
  waiting on it hands the backend a live server it cannot log into.
- **`depends_on: service_healthy`**, not `service_started`: Flyway opens a
  connection within a second of boot, and against a still-initialising MySQL the
  app exits and compose reports what looks like a code fault.
- **The container healthcheck probes readiness**, not the port. The process
  listens well before Flyway has finished migrating, and "something is
  listening" would let compose call the stack ready mid-migration.
- **`MYSQL_PWD`, not `-p<secret>`** in the direct-DB mode: a password on the
  command line is visible in `ps` to every user on the machine. It is a local
  development password either way; the habit is the point.
- **A `.dockerignore`**, because the build context is the repo root — without it
  every build uploads `.git`, two `node_modules` trees and an 85 MB fat JAR.
- **The frontend stays on the host.** Containerising a Vite dev server means
  rebuilding an image per edit and losing hot reload — strictly worse. It
  proxies to the container instead.

## 4. What was verified

| Check | Result |
| :--- | :--- |
| `mvn package` produces what the Dockerfile copies | `target/timebank-backend.jar`, 85 MB — and proved the version-glob would have matched nothing |
| `mvn test` after the `server.address` change | 327 passed |
| `docker-compose.yml` parses, services/volumes/env resolve | 2 services, 2 volumes, `depends_on` condition `service_healthy`, 9 backend env vars |
| **The seeder's new direct-DB mode, against real MySQL** | ran the real `npm run seed` through an SSH tunnel to the server's MySQL on a spare port: Phase 0 connected, authenticated, ran its pre-flight `SELECT`, parsed the result and correctly refused because the fixture already exists — **no writes** |
| **…and that SSH is genuinely out of that path** | re-ran it with `TIMEBANK_SSH_TARGET=nobody@this-host-does-not-exist.invalid` and it behaved identically. Without this the run above could not distinguish direct mode from a silent fall back to SSH |
| The academic-year query the seeder now runs (issue 100) | returns exactly one row plus a header, which its `.slice(1)` handles |

## 5. Verified by running it

A container runtime was installed for this (`brew install colima docker
docker-compose`; Colima VM at 4 CPU / 6 GB / 20 GB disk, chosen small because
the host had 20 GiB free). Everything below is an observed result.

| Step | Result |
| :--- | :--- |
| `docker compose build backend` | image built, 17 steps |
| `docker compose up -d` | `db Healthy` → backend started → **`backend Up (healthy)` in ~20s** |
| Readiness from the host | `{"status":"UP"}` on `localhost:28080` |
| Flyway on a fresh schema | **31 migrations**, all successful |
| **Issue 100, on a genuinely fresh database** | `ay_2026_2027` current and containing today; `ay_2025_2026` prior — the derived years work, not just the ones already on the shared server |
| `npm run seed` in direct-DB mode | **exit 0, all 8 phases**, including Phase 7's balance-consistency check |
| Seeded result | 37 users, 50 students, 287 records, 11 projects, 28 accounts, 1,211 audit rows |
| Login + 5 list endpoints from the host | all 200: students 50, users 37, audit-logs 1,212, projects 11, service-records 287 |
| Issue 098 on this stack | facets returns 14 actions / 6 types; `search=nonsense-xyzzy` → 0 |
| Issue 100's `staff_id` | all seven staff accounts carry one |

## 6. Four things that only failed once it was actually run

**The build context was 188 MB.** The blocklist `.dockerignore` was letting
`backend/target` through. Rewritten as deny-all plus `!backend/pom.xml` /
`!backend/src`, which is ~20 MB and, more importantly, cannot silently regrow
when a directory is added.

**Port 8080 was taken — silently.** The backend was healthy inside the
container and `localhost:8080` answered `404 page not found` from an unrelated
Go process. On a VM-backed runtime the forward is made by the VM's host agent,
so a clash does **not** fail `up`: the stack reports healthy and the host
reaches the squatter. Moved to 18080 — which on this machine was *also* taken,
by a Python `http.server` — and finally 28080. The lesson is in the compose
comment and §3 of the doc, because the next person will read a 404 as "the app
is broken".

**The seeder still shelled to SSH to hash passwords.** `runSqlOnServer` had a
direct mode; `bcryptHashOnServer` did not, so Phase 1 died on a host that does
not exist. Now hashes in-process with `bcryptjs` (pure JS, so `npm install`
needs no compiler). It emits `$2b$` where the Python path emitted `$2a$` —
which is *why* "37 personas logged in" above matters: it is what proves Spring
accepts the prefix, rather than a claim that it does.

**The API rate-limited the seeder.** `RateLimitInterceptor` allows 20
upload-intents per principal per minute; against a local stack there is no
network latency to pace the loop, so one student spends that in seconds. The
same script had been fine against the shared server only because the round trip
was slow enough by accident. The seeder now backs off and retries a `429`
(typed, not string-matched, so a reworded message cannot defeat it) — the limit
is a real control and waiting is the correct response to it. A full run took
**9 backoffs** and completed.

## 7. The web UI

Asked separately, and worth stating plainly because the compose file does not
answer it: **`docker compose up` gives you an API, not a web page.** Browsing to
`localhost:28080` returns 404 — the backend serves only `/api` and `/actuator`.
The UI is the Vite dev server on the host, proxying to the container.

Verified against this stack: `pnpm dev` with
`VITE_DEV_PROXY_TARGET=http://localhost:28080`, logged in as `admin001` through
the real login form, landed on the admin dashboard showing seeded data
("4 待审核记录"), and Student Management paged the real 50 students
(`50 total · page 1 / 3`, 20 rows).

Two things that surprised me and are now in the doc: 5173 was already taken on
this machine so Vite silently moved to another port — its "Local:" line is the
only reliable source — and **the dev server's port does not need adding to
`TIMEBANK_ALLOWED_ORIGINS`**, because the proxy rewrites `Origin` to the proxy
target before the request reaches `RequestOriginFilter`.

## 8. Reproducing it

```bash
cp docker/.env.example .env
docker compose up -d --build
docker compose ps                                      # backend → (healthy)
curl -s localhost:28080/actuator/health/readiness       # {"status":"UP"}

cd integration-test && npm install
TIMEBANK_SEED_DB_HOST=127.0.0.1 TIMEBANK_SEED_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app TIMEBANK_DB_PASSWORD=timebank-not-for-production \
TIMEBANK_BASE_URL=http://localhost:28080 npm run seed
```

Full walkthrough: `Docs/operations/DockerDevelopment.md`.
