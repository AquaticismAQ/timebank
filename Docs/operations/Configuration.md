# Runtime configuration (`/etc/timebank-backend.env`)

Every setting the backend reads at startup, what it does, and what happens if it
is wrong.

**This is the server's file, not the workstation's.** `Docs/operations/DeploymentEnv.md`
documents `~/.timebank.env`, which lives on *your machine* and holds deploy
targets and test credentials. The two share a `TIMEBANK_` prefix and a few names,
which is confusing exactly once: `TIMEBANK_DB_USERNAME` in `~/.timebank.env` is
the account `mvn verify` uses to create throwaway test schemas, while
`TIMEBANK_DB_USERNAME` here is the account the running application uses. Setting
one does nothing for the other.

## 1. How it is read

`backend/src/main/resources/application.yml` is the only file that names these
variables. Every property is written `${VARIABLE:default}`, so the YAML holds the
default and the env file holds the deviation — there is no second list to keep in
sync, and `grep '\${' application.yml` is the authoritative inventory if this
document ever falls behind it.

The systemd unit loads `/etc/timebank-backend.env`. **Changes take effect on
restart, never before**: `systemctl restart timebank-backend`, which
`backend/scripts/deploy.sh` does for you.

Six variables have **no default** and the application refuses to start without
them. That is deliberate — each one is a value where a plausible-looking fallback
would be worse than a failure to boot:

| Variable | Why it must be set |
| --- | --- |
| `TIMEBANK_DB_URL` | No sane default exists |
| `TIMEBANK_DB_USERNAME` | As above |
| `TIMEBANK_DB_PASSWORD` | A default password is a published password |
| `TIMEBANK_FILE_TOKEN_SECRET` | Signs evidence and export download links; a guessable value hands out student data |
| `TIMEBANK_ALLOWED_ORIGINS` | Issue 027: a placeholder default rejects every real browser request with 403 while every curl-based check still passes |
| `TIMEBANK_SITE_ROOT_URL` | Printed on the credential slips handed to children; a wrong value puts a domain the school does not own onto paper |

`deploy.sh` checks the last two *before* building and refuses, so the failure
arrives in seconds rather than after a restart.

---

## 2. School calendar

### `TIMEBANK_ACADEMIC_YEAR_START`

**Default `09-01`. Format `MM-DD`.** The day a school year turns over — and so
**the day promotion and graduation run** (`Design/*/13-AnnualRollover.md` §2).

It decides exactly two things:

- which academic year "today" falls in, and therefore when the nightly rollover
  job finds itself due;
- the `start_date`/`end_date` of academic-year rows the **rollover creates**.

It decides **nothing about existing data**. Every academic year keeps the dates
it was created with, and every query that scopes data to a year — all seven
exports, the reports — reads those stored dates rather than recomputing from this
setting. So moving the boundary affects years created after the change and never
silently reinterprets a year students already have records in.

Two consequences worth knowing before you change it:

- **The seed bootstraps at 1 September regardless.** `R__seed_base_data.sql` is a
  repeatable SQL migration and cannot read a Spring property. On a school that
  configures something else, the two seeded years keep 1 September and the first
  *rolled-over* year is the first to follow the new setting. If you need the
  seeded years to match, edit their `academic_year` rows directly.
- **It is validated strictly, and refuses startup when wrong.** An unparseable
  value, or `02-29`, stops the application rather than falling back to the
  default. This is deliberately stricter than the numeric settings below: a
  number outside its range has an obvious nearest sane value, a date does not,
  and quietly substituting 1 September would promote a school that asked for
  20 August twelve days late with nothing on screen saying so. `02-29` is refused
  specifically because it does not exist in three years out of four, so a
  rollover scheduled there would simply not happen — no error, no promotion, a
  whole cohort left behind.

---

## 3. Data import and export

### `TIMEBANK_HISTORICAL_IMPORT_WINDOW_DAYS`

**Default `15`, clamped to `1`–`60`.** How long historical service-record import
stays open after the first successful import (`Design/*/11-DataImport.md` §6).

Configurable because a migration that genuinely runs long should not need a code
change and a deploy at the worst possible moment. Clamped because an unbounded
value can be set to `3650` and the mechanism is gone while still looking like a
window. A value outside the range is clamped **with a warning** rather than
refused — unlike the calendar above, the nearest sane value here is obvious.

The length that actually applied is stored on the window row, so changing this
later never reinterprets a window already running. Once a window has expired,
**this setting cannot reopen it** — that is the console utility's job
(`Docs/backlog/01-HistoricalImportReenableUtility.md`, not yet built).

### Export file retention — *not currently configurable*

Produced export files are deleted **7 days** after the job finishes, and spent
download tokens after **1 day**. Both are constants in
`ExportOutputRetentionJob`, matching `ShortLivedTableSweepJob` so an operator has
one short-data retention figure to remember rather than two. Making them
properties is a small change if a deployment needs it.

---

## 4. Server and network

| Variable | Default | Notes |
| --- | --- | --- |
| `TIMEBANK_SERVER_PORT` | `8080` | Port the JVM listens on; nginx proxies to it |
| `TIMEBANK_SERVER_ADDRESS` | `127.0.0.1` | Loopback on purpose — the app is reached through nginx, never directly |
| `TIMEBANK_ALLOWED_ORIGINS` | *(required)* | Comma-separated. Must be the browser-visible origin, scheme included |
| `TIMEBANK_SITE_ROOT_URL` | *(required)* | Public root, used for activation deep links and printed credential slips |
| `TIMEBANK_ENVIRONMENT` | `unspecified` | Label only; appears in logs and the version endpoint |

### 4.1 Reverse proxy — two settings that live nowhere else

TLS terminates at nginx, so these are not environment variables and not in this
repository; they exist only in the proxy's own configuration, which is why they
are written down here. Both are checked items in the security design's
self-assessment (`Design/*/08-mlps-level1-security-design-guide.md` §9, T-02 and
T-03).

| Setting | Required value | Why here |
| --- | --- | --- |
| `ssl_protocols` | `TLSv1.2 TLSv1.3` — **in the global `http` block**, not only in the site's `server` block | The distribution default still names TLS 1.0 and 1.1. A site that does not include its own list inherits that, so setting it only on this vhost leaves the next one weak (issue 147) |
| `Strict-Transport-Security` | `max-age=31536000` | A short max-age is a short window of protection. One year is the usual figure. `includeSubDomains`/`preload` deliberately unset — preload is slow to leave, and the production domain is not settled |

After changing either: `nginx -t`, then `systemctl reload nginx`. To check the
TLS one you must force the client to offer the old versions, or the result is
meaningless — a modern OpenSSL will not send a TLS 1.0 ClientHello unprompted
and the failure looks the same either way:

```bash
openssl s_client -connect <host>:443 -servername <host> -tls1 -cipher 'ALL:@SECLEVEL=0'
# refused correctly => "alert protocol version" and `Cipher : 0000`
# the summary line still prints `Protocol: TLSv1`; that is not success
```

---

## 5. Database

| Variable | Default | Notes |
| --- | --- | --- |
| `TIMEBANK_DB_URL` | *(required)* | JDBC URL |
| `TIMEBANK_DB_USERNAME` | *(required)* | The **application** account — needs DML on `timebank` only, never `CREATE DATABASE` |
| `TIMEBANK_DB_PASSWORD` | *(required)* | |
| `TIMEBANK_DB_POOL_SIZE` | `5` | HikariCP maximum pool size. The box is shared and resource-constrained; raising it competes with MySQL itself for memory |

---

## 6. Sessions, lockout and activation

| Variable | Default | Notes |
| --- | --- | --- |
| `TIMEBANK_SESSION_DEFAULT_TIMEOUT_MINUTES` | `480` | A school day. Per-user overrides exist in the database |
| `TIMEBANK_LOCKOUT_MAX_ATTEMPTS` | `5` | Failed logins before an account locks |
| `TIMEBANK_LOCKOUT_DURATION_MINUTES` | `15` | How long it stays locked |
| `TIMEBANK_ACTIVATION_CODE_TTL_DAYS` | `7` | How long a credential slip's activation code remains valid |
| `TIMEBANK_ACTIVATION_ATTEMPTS_PER_IP_PER_HOUR` | `60` | Per-IP throttle on redemption attempts |
| `TIMEBANK_ACTIVATION_FAILURE_BUDGET_PER_HOUR` | `300` | Global failure budget across all IPs |
| `TIMEBANK_ACTIVATION_EXPIRY_SWEEP_CRON` | `0 40 * * * *` | When expired codes are swept |
| `TIMEBANK_ACTIVATION_EXPIRY_SWEEP_BATCH_LIMIT` | `500` | Cap per sweep, so a whole school lapsing at once is not one unbounded burst |

**A throttle is not a permission.** The activation limits exist to make brute
force expensive, not to decide who may activate. Raising them during a school-wide
onboarding day is reasonable; switching them off is not.

---

## 7. Files and evidence

| Variable | Default | Notes |
| --- | --- | --- |
| `TIMEBANK_FILE_STORAGE_PATH` | `data/evidence-storage` | Relative to the process working directory (`/opt/timebank-backend`). **Never inside the rsync'd source tree** — `deploy.sh` excludes `data/` for this reason, and issue 060 is what happens when it does not |
| `TIMEBANK_FILE_MAX_SIZE_BYTES` | `20971520` (20 MB) | Per uploaded file |
| `TIMEBANK_FILE_PDF_MAX_PAGES` | `100` | Page cap for PDF evidence |
| `TIMEBANK_FILE_TOKEN_SECRET` | *(required)* | HMAC key for evidence **and** export download links. Rotating it invalidates every link currently in flight — harmless, since they live 300 seconds |

---

## 8. Changing a setting safely

1. Edit `/etc/timebank-backend.env` on the server.
2. `systemctl restart timebank-backend`.
3. Watch readiness, not liveness: `curl -fsS http://127.0.0.1:<port>/actuator/health/readiness`.
   The process answers on the port well before Flyway and Hibernate have
   finished, so "the service is running" is not "the service is working" —
   `deploy.sh` waits on the readiness probe for exactly this reason.
4. If it refuses to start, the log names the variable. The required six and
   `TIMEBANK_ACADEMIC_YEAR_START` all fail loudly by design.

**Permission changes are not here.** Who may import, export or administer is
stored in the database and managed through migrations and the Roles screen, not
through this file. There is no environment variable that grants anybody anything.
