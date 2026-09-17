# 101 — Support running the stack locally with Docker

**Status:** Implemented. **`docker compose up` was not executed** — no container
runtime exists in the environment this was written in (§5).
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
of the point of having it up. `TIMEBANK_DB_HOST` now selects a direct-TCP mode.
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

## 5. What was **not** verified, and how to

**`docker compose up --build` has not been run.** There is no container runtime
on this machine — no `docker`, `podman`, `colima` or `nerdctl`, and no Docker
Desktop or OrbStack installed — so the image has never been built and the stack
has never started. Everything in §4 was verified some other way precisely
because this could not be.

What that leaves genuinely unproven: that the Maven and JRE base images resolve,
that `dependency:go-offline` succeeds in the build stage, that `apt-get install
curl` works in the runtime image, that the healthchecks behave as written, and
that the backend reaches `(healthy)` against the compose MySQL.

On a machine with Docker:

```bash
cp docker/.env.example .env
docker compose up -d --build
docker compose ps                                     # backend → (healthy)
curl -s localhost:8080/actuator/health/readiness      # {"status":"UP"}

cd integration-test && npm install
TIMEBANK_DB_HOST=127.0.0.1 TIMEBANK_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app TIMEBANK_DB_PASSWORD=timebank-not-for-production \
TIMEBANK_BASE_URL=http://localhost:8080 npm run seed
```

Then log in through `pnpm dev` with an account the seeder printed. If anything
in that sequence needs a fix, it belongs on this issue.
