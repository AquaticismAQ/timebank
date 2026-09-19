# Running TimeBank locally with Docker

For contributors who do not have a second machine to use as a test server.
Brings up MySQL and the backend on your own laptop.

**This is a development path, not a deployment one.** Production is unchanged
and still has no containers in it — a fat JAR under a systemd unit, deployed by
`backend/scripts/deploy.sh`. See §8 for exactly where that line sits and why it
did not move.

---

## 1. Which path to use

There are two ways to develop against a real backend, and this doc is only the
second one:

| | Shared test server | This local stack |
| :--- | :--- | :--- |
| **Where** | the shared test server — its real hostname is in the untracked `test-server-credits.md`, never in a committed file | your own machine |
| **You need** | SSH key + credentials from a maintainer | Docker, and nothing else |
| **Data** | already seeded, shared with everyone | yours, seeded by you |
| **Deploys** | `backend/scripts/deploy.sh`, `frontend/scripts/deploy.sh` | `docker compose up --build` |
| **Good for** | live verification against the same data everyone else sees; anything about nginx/TLS/systemd | day-to-day work, migrations, destructive experiments, working offline |
| **Bad for** | experiments that break shared data | anything that depends on the real deployment shape |

**The shared server stays the reference environment.** Live verification of a
fix — the kind `integration-test/STATUS.md` records — belongs there, because
that is the deployed configuration behind nginx that everyone else is looking
at. Use the local stack to get the work right first.

The two are not mutually exclusive: a working `.env.local` for the frontend
points at one or the other, and switching is one line.

## 2. What you need

- Docker with Compose v2 (`docker compose`, not `docker-compose`) — Docker
  Desktop, OrbStack, Colima and Rancher Desktop all work.
- Node 18+ and `pnpm`, for the frontend and the seeder. Both run on the host.
- A `mysql` client, only if you want to seed (`brew install mysql-client`).

You do **not** need Java or Maven to *run* the stack — the image compiles the
backend itself. You do need them to run the tests (§8).

Budget roughly **4 GB of RAM and 5 GB of disk** for the VM, images and the Maven
dependency cache. On a VM-backed runtime, size it when you create it — e.g.
`colima start --cpu 4 --memory 6 --disk 20`.

## 3. Start it

```bash
cp docker/.env.example .env      # optional — every value has a default
docker compose up -d --build
```

The first build takes a few minutes: it downloads the Maven dependencies and
compiles the backend. Later builds reuse the dependency layer unless `pom.xml`
changed.

Watch it come up:

```bash
docker compose ps          # backend should reach (healthy), not just Up
docker compose logs -f backend
```

`(healthy)` means the readiness probe passed, which means **Flyway has finished
migrating**. The process answers on its port well before that, so "Up" alone is
not a signal you can seed against.

Check it directly:

```bash
curl -s localhost:28080/actuator/health/readiness
# {"status":"UP",...}
```

## 4. Ports

| Service | Host | Why |
| :--- | :--- | :--- |
| Backend | **`28080`** | Not 8080. See below — a clash here is *silent* |
| MySQL | **`3307`** | Not 3306. Anyone who has installed MySQL locally already has 3306 bound |

**A port clash does not fail `docker compose up`.** On a VM-backed runtime
(Colima, Lima, Rancher, Docker Desktop) the forward is made by the VM's host
agent, so the stack comes up healthy, `docker compose ps` shows the mapping, and
the host quietly reaches whatever was already listening. This was hit twice
while verifying this file: 8080 answered with a stray Go server's
`404 page not found`, then 18080 with a Python `http.server`'s `File not found`
— both while the container itself returned `{"status":"UP"}`.

If `curl localhost:28080/actuator/health/readiness` returns a 404 that is not
Spring's, that is what happened:

```bash
lsof -nP -iTCP:28080 -sTCP:LISTEN          # who actually holds it
docker compose exec backend curl -s localhost:8080/actuator/health/readiness
```

If the second command says `UP`, the app is fine and only the forward is lost.
Change `TIMEBANK_BACKEND_PORT` in `.env`, and add the new URL to
`TIMEBANK_ALLOWED_ORIGINS` as well — the frontend's dev proxy sends the backend's
own URL as the `Origin` header, so the two settings have to agree. (This is
about the **backend's** port. The *dev server's* port needs no such entry — §7.)

Both are overridable in `.env` (`TIMEBANK_BACKEND_PORT`, `TIMEBANK_DB_HOST_PORT`).

## 5. Seed it

An empty database is a working system with nothing in it — every admin screen
is blank and nothing can be exercised. The seeder fills it.

```bash
cd integration-test
npm install
TIMEBANK_SEED_DB_HOST=127.0.0.1 \
TIMEBANK_SEED_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app \
TIMEBANK_DB_PASSWORD=timebank-not-for-production \
TIMEBANK_BASE_URL=http://localhost:28080 \
npm run seed
```

`TIMEBANK_SEED_DB_HOST` is what selects direct-connection mode. Without it the
seeder talks to MySQL over SSH, which is right for the shared server — where
the database is not reachable over TCP from a workstation at all — and useless
here.

It prints a username and password for every account it creates. **That output
is the only place those passwords exist**; copy the ones you need somewhere
before you close the terminal.

It refuses to run twice. To start over, §5.

## 6. Common operations

```bash
docker compose logs -f backend     # follow logs
docker compose restart backend     # restart after changing .env
docker compose up -d --build       # rebuild after changing backend source
docker compose down                # stop, keep the data
docker compose down -v             # stop and delete the database and evidence
```

`down -v` is the way to start completely over — it drops the MySQL volume, so
the next `up` migrates a fresh schema and the seeder will run again.

Connect to the database directly:

```bash
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u timebank_app -p timebank
```

## 7. Opening the web UI

**`docker compose up` gives you an API, not a web page.** There is no web
server in the compose file — browsing to `localhost:28080` returns 404s,
because the backend only serves `/api` and `/actuator`. The UI is the Vite dev
server, on the host, proxying to the container:

```bash
cd frontend
pnpm install
printf 'VITE_DEV_PROXY_TARGET=http://localhost:28080\n' > .env.local
pnpm dev
```

Then open the URL it prints — **http://localhost:5173** by default.

`.env.local` is required, not optional: there is no mock mode, and
`vite.config.ts` refuses to start without it rather than 404ing on every
request.

**If 5173 is taken**, Vite silently picks the next free port, so read the
"Local:" line it prints rather than assuming. To pin one:

```bash
pnpm dev --port 5180 --strictPort
```

Any port works — you do **not** need to add it to `TIMEBANK_ALLOWED_ORIGINS`.
The dev proxy rewrites the `Origin` header to the proxy target
(`vite.config.ts`, for `RequestOriginFilter`), so the backend only ever sees
`http://localhost:28080`, which is already allow-listed.

Log in with any account the seeder printed — `admin001` for the admin portal,
one of the 8-digit numbers for the student one — student logins are random digits by design (issue 105), so read them off the seeder's output rather than guessing.

Containerising the frontend would mean rebuilding an image on every edit and
losing hot reload — for a dev server that watches files, a container is
strictly worse.

## 8. Running the tests against it

`mvn test` needs nothing — it is MockMvc slices and ArchUnit, no database:

```bash
cd backend && mvn test
```

`mvn verify` also runs the `*IT` integration tests, and those create and drop a
real `timebank_it_<random>` schema per test class. **The app user cannot do that
out of the box** — the MySQL image grants it rights on the `timebank` database
only, so you get:

```
Access denied for user 'timebank_app'@'%' to database 'timebank_it_probe'
```

Grant it once; the volume keeps it until `down -v`:

```bash
docker compose exec db mysql -u root -proot-not-for-production \
  -e "GRANT ALL PRIVILEGES ON \`timebank_it_%\`.* TO 'timebank_app'@'%'; FLUSH PRIVILEGES;"
```

Then:

```bash
cd backend
TIMEBANK_SEED_DB_HOST=127.0.0.1 TIMEBANK_SEED_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app TIMEBANK_DB_PASSWORD=timebank-not-for-production \
mvn verify
```

This is the one place the local stack is *better* than the shared server rather
than merely more convenient: the integration tests previously needed a MySQL
account with `CREATE`/`DROP` rights that only a maintainer had, so a contributor
could not run them at all. Verified — `UserListFilterSortIT`, 11 tests, passing
against the container.

The tests never touch the application's own `timebank` schema, so running them
does not disturb your seeded data.

## 9. Where the no-Docker rule still applies

`Docs/implementation/01-Overview.md` §6 said, and still says, that **production
runs no containers**. That has not changed, and this compose file is not a step
towards changing it:

- **Deployment is still systemd + fat JAR.** `backend/scripts/deploy.sh` is
  unchanged, builds on the server, and knows nothing about images.
- **Integration tests still do not use Testcontainers.** `mvn verify` creates a
  real `timebank_it_<random>` schema on a MySQL it is given credentials for
  (`MySqlTestSchemaExtension`). Pointing those credentials at the container is
  supported and documented (§8) — but the test starts no container, and the
  schema it uses is a real one on a MySQL that already exists.
- **Nothing in CI builds this image.** It exists for a contributor's laptop.

The reason the rule existed is that a second packaging model for *production*
means two things to keep working and two ways for them to diverge. A
development stack on one machine does not create that problem: if this file
breaks, the deployment path is untouched.

`server.address` is the one place the container reaches into the application's
configuration. It defaults to `127.0.0.1` — the property a systemd deployment
depends on, since Nginx terminates TLS in front of it — and compose overrides it
to `0.0.0.0`, because inside a container loopback means the *container's*
loopback and a published port would connect to nothing. The container boundary
provides the isolation the loopback bind provides on a host.

## 10. Troubleshooting

| Symptom | Cause |
| :--- | :--- |
| `backend` exits immediately, logs name a `TIMEBANK_*` variable | That variable has no default and startup fails deliberately rather than running degraded (issue 027). Set it in `.env` |
| `Connection refused` on 28080 while the container is running | The app bound loopback inside the container. Check `TIMEBANK_SERVER_ADDRESS=0.0.0.0` is still in the compose environment |
| Backend crash-loops with a JDBC error at boot | MySQL was not ready. `depends_on: service_healthy` should prevent this; if you edited it back to `service_started`, put it back |
| `docker compose up` says port is allocated | Something else holds 28080 or 3307. Change it in `.env` — and note that on a VM-backed runtime a clash usually does *not* say this, see §4 |
| Seeder: "Refusing to seed: these usernames already exist" | It is a first-run tool, not a reconciler. `docker compose down -v` and start over |
| Seeder: "Could not run the mysql client" | No `mysql` on your PATH — macOS `brew install mysql-client`, Debian/Ubuntu `apt install mysql-client`, or use the one in the container: `docker compose exec db mysql …` |
| Seeder pauses with "rate limited on /files/upload-intents" | Expected, not an error. The API allows 20 upload-intents per student per minute and the seeder is fast enough locally to hit it; it backs off and continues. A full run took ~9 of these |
| 404 from `localhost:28080` that is not Spring's | Port clash — see §4 |
| `localhost:28080` shows 404 in a browser | Expected — the backend serves only `/api` and `/actuator`. The UI is the dev server, §7 |
| `pnpm dev` refuses to start, naming `VITE_DEV_PROXY_TARGET` | No `frontend/.env.local`. §7 |
| Dev server came up on a port you did not expect | 5173 was taken and Vite moved on. Read its "Local:" line, or pass `--port N --strictPort` |
| `mvn verify`: "Access denied ... to database `timebank_it_...`" | The app user has rights on `timebank` only. Run the one-off GRANT in §8 |
