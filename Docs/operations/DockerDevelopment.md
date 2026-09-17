# Running TimeBank locally with Docker

For contributors who do not have a second machine to use as a test server.
Brings up MySQL and the backend on your own laptop.

**This is a development path, not a deployment one.** Production is unchanged
and still has no containers in it — a fat JAR under a systemd unit, deployed by
`backend/scripts/deploy.sh`. See §6 for exactly where that line sits and why it
did not move.

---

## 1. What you need

- Docker with Compose v2 (`docker compose`, not `docker-compose`) — Docker
  Desktop, OrbStack, Colima and Rancher Desktop all work.
- Node 18+ and `pnpm`, for the frontend and the seeder. Both run on the host.
- A `mysql` client, only if you want to seed (`brew install mysql-client`).

You do **not** need Java or Maven. The image compiles the backend itself.

## 2. Start it

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
curl -s localhost:8080/actuator/health/readiness
# {"status":"UP",...}
```

## 3. Ports

| Service | Host | Why |
| :--- | :--- | :--- |
| Backend | `8080` | |
| MySQL | **`3307`** | Not 3306. Anyone who has installed MySQL locally already has 3306 bound, and the failure looks like a broken container rather than a port clash |

Both are overridable in `.env` (`TIMEBANK_BACKEND_PORT`, `TIMEBANK_DB_HOST_PORT`).

## 4. Seed it

An empty database is a working system with nothing in it — every admin screen
is blank and nothing can be exercised. The seeder fills it.

```bash
cd integration-test
npm install
TIMEBANK_DB_HOST=127.0.0.1 \
TIMEBANK_DB_PORT=3307 \
TIMEBANK_DB_USERNAME=timebank_app \
TIMEBANK_DB_PASSWORD=timebank-not-for-production \
TIMEBANK_BASE_URL=http://localhost:8080 \
npm run seed
```

`TIMEBANK_DB_HOST` is what selects direct-connection mode. Without it the
seeder talks to MySQL over SSH, which is right for the shared server — where
the database is not reachable over TCP from a workstation at all — and useless
here.

It prints a username and password for every account it creates. **That output
is the only place those passwords exist**; copy the ones you need somewhere
before you close the terminal.

It refuses to run twice. To start over, §5.

## 5. Common operations

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

## 6. Running the frontend

The frontend is deliberately **not** in the compose file. It runs on the host:

```bash
cd frontend
pnpm install
cp .env.example .env.local        # then set VITE_DEV_PROXY_TARGET
pnpm dev                          # http://localhost:5173
```

Set `VITE_DEV_PROXY_TARGET=http://localhost:8080` in `.env.local` so the dev
server proxies `/api` to the container.

Containerising it would mean rebuilding an image on every edit and losing hot
reload — for a Vite dev server that watches files, a container is strictly
worse. `TIMEBANK_ALLOWED_ORIGINS` already includes `http://localhost:5173` for
this reason; if you change the dev server's port, change it there too, because
CORS rejects an unlisted origin with a 403 that says nothing about this setting
(issue 027).

## 7. Where the no-Docker rule still applies

`Docs/implementation/01-Overview.md` §6 said, and still says, that **production
runs no containers**. That has not changed, and this compose file is not a step
towards changing it:

- **Deployment is still systemd + fat JAR.** `backend/scripts/deploy.sh` is
  unchanged, builds on the server, and knows nothing about images.
- **Integration tests still do not use Testcontainers.** `mvn verify` creates a
  real `timebank_it_<random>` schema on a MySQL it is given credentials for
  (`MySqlTestSchemaExtension`). You can point those credentials at the container
  here, which is a genuine convenience, but no test starts a container.
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

## 8. Troubleshooting

| Symptom | Cause |
| :--- | :--- |
| `backend` exits immediately, logs name a `TIMEBANK_*` variable | That variable has no default and startup fails deliberately rather than running degraded (issue 027). Set it in `.env` |
| `Connection refused` on 8080 while the container is running | The app bound loopback inside the container. Check `TIMEBANK_SERVER_ADDRESS=0.0.0.0` is still in the compose environment |
| Backend crash-loops with a JDBC error at boot | MySQL was not ready. `depends_on: service_healthy` should prevent this; if you edited it back to `service_started`, put it back |
| `docker compose up` says port is allocated | Something else holds 8080 or 3307. Change the port in `.env` |
| Seeder: "Refusing to seed: these usernames already exist" | It is a first-run tool, not a reconciler. `docker compose down -v` and start over |
| Seeder: "Could not run the mysql client" | No `mysql` on your PATH. `brew install mysql-client` |
| 403 from the API in the browser, nothing useful logged | The dev server's origin is not in `TIMEBANK_ALLOWED_ORIGINS` |
