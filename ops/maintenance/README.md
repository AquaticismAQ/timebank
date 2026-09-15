# TimeBank maintenance mode

Gates the whole site (static frontend *and* `/api/*`) behind a bilingual
"under maintenance" page, without touching any backend/frontend source —
this is entirely an nginx + systemd addition on the server. Full design
rationale lives in `Docs/operations/MaintenanceMode.md`; this file is the
"how do I actually operate it" reference.

## How it works, briefly

- A marker file (`/var/www/timebank-maintenance.flag`) drives the gate.
  `timebank-maintctl` (below) is the single thing that ever creates or
  removes it — directly for `on`/`off`, or via systemd timers for
  `schedule` mode.
- An nginx snippet (`nginx-maintenance.conf.inc`, `include`d from the
  site's server block) checks that file on every request; if present, it
  returns `503` with the bilingual page (`index.html`) instead of serving
  the SPA or proxying to `/api/*`.
- A one-time secret-URL visit (`/__ops/enter?key=...`) sets a 30-day cookie
  that skips the gate for that browser, **in every mode** — that's how we
  keep working no matter what state the site is in.

## Three modes

`ops/maintenance/maintctl.sh` (run from a workstation) controls which one
is active. Exactly one at a time; switching modes is immediate, not
"at the next timer tick."

| Mode | Command | Behavior |
| --- | --- | --- |
| Force on | `maintctl.sh on` | Gate active always, until switched away from. |
| Force off | `maintctl.sh off` | Gate inactive always, until switched away from. |
| Schedule (default) | `maintctl.sh schedule [--start HH:MM] [--end HH:MM]` | Gate active during `[start, end)` Beijing time daily, inactive otherwise. Defaults to `18:00`–`08:00` if this mode has never been configured; omit both flags to just re-apply the currently configured window (e.g. after a reboot). |
| — | `maintctl.sh status` | Shows the active mode, the configured window, whether the gate is active right now, and the next two timer firings. |

Changing the schedule's start/end time takes effect immediately — if the
new window already contains the current time, the gate flips right then,
it doesn't wait for a timer to fire.

## First-time setup

1. Generate a bypass key once and save it in `~/.timebank.env` (see
   `Docs/operations/DeploymentEnv.md`):
   ```bash
   openssl rand -hex 24
   # add to ~/.timebank.env:
   export TIMEBANK_MAINTENANCE_BYPASS_KEY='<the generated value>'
   ```
2. Run the deploy script:
   ```bash
   ops/maintenance/deploy.sh
   ```
   This syncs the page, the nginx snippet (with the real key substituted
   in — the key is never written into the repo), and the base systemd unit
   *definitions*. It deliberately does **not** turn maintenance mode on —
   see the next step — and will **not** reload nginx yet the very first
   time either, see "One-time nginx wiring" below.
3. Pick a mode for the first time:
   ```bash
   ops/maintenance/maintctl.sh schedule   # the normal default, or `on`/`off`
   ```

### One-time nginx wiring

`deploy.sh` only syncs `/etc/nginx/snippets/timebank-maintenance.conf`; it
doesn't edit the live site config itself, since that file is
Certbot-managed and a script silently rewriting it is exactly the kind of
thing that should be a reviewed, deliberate edit instead. Add one line near
the top of the `server { ... }` block (the `443 ssl` one, not the `:80`
redirect-only one) in `/etc/nginx/sites-enabled/<the real site file>`:

```nginx
server {
    server_name ...;
    root /var/www/timebank;
    index index.html;

    include /etc/nginx/snippets/timebank-maintenance.conf;   # <-- add this line

    location ~ ^/api/v1/auth/... { ... }
    ...
```

It must come before the `location` blocks so the gate applies to all of
them. Back up the file first (`cp` it somewhere), then `nginx -t` and
`systemctl reload nginx` after adding the line. Re-running `deploy.sh`
after this point will detect the include is present and reload nginx for
you on every subsequent sync.

## Day-to-day operation

**Switch modes:**
```bash
ops/maintenance/maintctl.sh on
ops/maintenance/maintctl.sh off
ops/maintenance/maintctl.sh schedule --start 22:00 --end 06:00
ops/maintenance/maintctl.sh schedule            # re-apply the current window as-is
```

**Check current state:**
```bash
ops/maintenance/maintctl.sh status
```
This also writes/refreshes `MAINTENANCE_ACCESS.md` in the repo root
(gitignored — it embeds the real key, never commit it): a ready-to-click
bypass link if the site is gated right now, or a plain note that it isn't,
if not. Handy for grabbing the link without re-typing the key from
`~/.timebank.env` each time.

**Keep working no matter the mode:** open the link from
`MAINTENANCE_ACCESS.md` (or visit
`https://<real-host>/__ops/enter?key=<TIMEBANK_MAINTENANCE_BYPASS_KEY>`
directly) once in your browser. The cookie lasts 30 days; clear cookies
(or wait it out) to re-lock yourself out for testing the gate itself.

**Rotate the bypass key:** update `TIMEBANK_MAINTENANCE_BYPASS_KEY` in
`~/.timebank.env`, re-run `ops/maintenance/deploy.sh` — the old cookie
value stops working immediately since the nginx snippet is regenerated
with the new key. This doesn't change or need to touch the active mode.

**Emergency fallback**, if you only have raw shell access and not this
repo (e.g. SSH'd directly into the server): `timebank-maintctl` is
installed at `/usr/local/sbin/timebank-maintctl` and can be run there
directly with the same `on`/`off`/`schedule`/`status` subcommands, or as a
last resort `touch`/`rm /var/www/timebank-maintenance.flag` directly — but
that bypasses mode tracking (`/etc/timebank-maintenance/state.conf`) and
`status`/a later `schedule` call will overwrite whatever you set by hand.

## Known limitations

- The `/__ops/enter?key=...` comparison is a plain nginx string compare,
  not a constant-time/cryptographic check — acceptable for an internal ops
  toggle on a test server, not something to reuse for anything handling
  real secrets.
- `Persistent=false` on both timers (deliberate): if the server is down
  across a scheduled boundary, the timer does *not* fire late on boot — it
  just waits for the next scheduled time. This avoids maintenance mode
  unexpectedly kicking in mid-afternoon after a restart, at the cost of
  occasionally missing a window entirely if downtime lines up badly. Run
  `maintctl.sh schedule` (no flags) after any boot/restart if you want the
  gate's current-active/inactive state re-evaluated immediately rather
  than waiting for the next natural boundary.
- Only one schedule window is supported (a single daily `[start, end)`) —
  no different-per-weekday schedules, no multiple windows per day.
