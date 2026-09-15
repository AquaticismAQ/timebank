# Maintenance mode

**Status: implemented and live-verified, 2026-09-15** (schedule-only gate,
same day; extended same day to add the three-mode control script below).
Requested directly by the user: gate the whole site behind a bilingual
"under maintenance" page every night, 18:00–08:00 Beijing time by default,
without touching any backend/frontend source, with a real way to force it
on/off or change the window on demand. `ops/maintenance/README.md` is the
day-to-day operator reference; this doc is the design rationale and the
alternatives that were considered and rejected.

## Why this lives entirely outside `backend/`/`frontend/`

The explicit ask was "without change our source." The server already
terminates TLS and reverse-proxies both the static frontend and `/api/*`
through a single nginx site (`/etc/nginx/sites-enabled/tb.example.com`),
so gating traffic at that layer needs zero application code, zero new
deploys of the app itself, and applies uniformly to the SPA and the API in
one place rather than needing matching logic in two codebases. This also
matches the project's existing "no Docker, systemd + nginx is the
deployment contract" model (`CLAUDE.md`, `Docs/operations/Runbooks.md`
Appendix) — this is the same category of change as an nginx config edit
for TLS or a reverse-proxy rule, not a new kind of infrastructure.

## Design decisions and the alternatives rejected

**A marker file + systemd timers drive the schedule, not nginx doing time
arithmetic.** nginx has no native "is the current time between X and Y"
comparison — the usual workaround is regex-matching `$time_iso8601`
against a set of hour patterns, which is fragile (has to separately handle
the day boundary, DST if the timezone ever observes it, and is awkward to
read/audit). Two systemd timers (`OnCalendar=*-*-* 18:00:00 Asia/Shanghai`
/ `08:00:00 Asia/Shanghai` — systemd's per-timer timezone suffix means the
server's own system clock, which is UTC, never needs to change) instead
just `touch`/`rm` a marker file, and nginx's gating logic is a single
`if (-f ...)` check. This also gives a trivial manual override —
`touch`/`rm` the file by hand any time — without needing to reload nginx
or wait for a schedule.

**A `503`, not a redirect, and it gates the API too.** A `30x` redirect to
a `/maintenance` URL would tell crawlers/clients the requested resource
permanently or temporarily *moved*, which isn't true — it's the same URL,
just unavailable right now. `503 Service Unavailable` (with a
`Retry-After` header) is the correct HTTP semantics, applied uniformly to
both the static site and `/api/*` from one check so a bypassed admin
session is the only way to see a half-working app (UI up but API down, or
vice versa, was rejected as more confusing than a clean full gate).

**Cookie-based bypass, not an IP allowlist.** The person operating this
doesn't have a fixed IP (home network, mobile tethering, etc.), so an
allowlist would need constant upkeep. A one-time visit to a secret URL
(`/__ops/enter?key=...`) sets a 30-day cookie that nginx checks alongside
the marker file — works from anywhere, nothing to maintain, and rotating
the key (regenerate, redeploy) invalidates every existing bypass cookie
at once if it's ever suspected of leaking.

**One bilingual static page, not two separate English/Chinese pages with
server-side language negotiation.** The maintenance page has to be
reachable even if something about the app itself is unhealthy, so it's a
plain static HTML file with both languages stacked (no `Accept-Language`
routing, no dependency on anything else being up) — simplest option that
still serves both audiences without picking a default that might be wrong.

## Three modes, added as a same-day follow-up

The first pass only supported the schedule (always 18:00–08:00, no way to
force it on/off outside that window short of manually touching the marker
file). Follow-up ask: support three modes — **force on**, **force off**,
and **schedule** (now with a changeable window) — with the bypass cookie
still working in every one of them.

**One state file is the single source of truth**
(`/etc/timebank-maintenance/state.conf` on the server: `MODE`,
`START_TIME`, `END_TIME`), written only by `timebank-maintctl`
(`ops/maintenance/timebank-maintctl`, installed to
`/usr/local/sbin/` — the real logic; `ops/maintenance/maintctl.sh` is a
thin workstation-side SSH wrapper around it, mirroring `deploy.sh`'s own
shape). `on`/`off` stop and disable both timers entirely and set the
marker file directly; `schedule` re-enables them with the requested
window and re-evaluates the marker file immediately (not "at the next
tick") by computing whether the current Beijing time already falls inside
`[start, end)` — including the overnight-crossing case (`start > end`,
e.g. the default `18:00`–`08:00`) the same way as a same-day window.

**Changing the window uses a systemd timer drop-in, not editing the base
unit files.** `OnCalendar=` is a list-type directive, so a drop-in
(`/etc/systemd/system/timebank-maintenance-{on,off}.timer.d/override.conf`)
has to start with an empty `OnCalendar=` line to clear the base unit's
own `18:00`/`08:00` before adding the requested time — otherwise both
would be active at once. This keeps `ops/maintenance/*.timer` in the repo
as a pure "shipped default," with the server's actual current schedule
living in server-only state, the same way the marker file itself does.

**`deploy.sh` no longer enables/starts the timers.** It originally did,
which meant re-running it (e.g. after an `index.html` copy edit) would
silently re-enable the timers even if an operator had explicitly forced
mode to `on` or `off` — a real coupling bug, caught before it shipped by
tracing through what a routine re-deploy would do to an active force
mode, not by hitting it live. `deploy.sh` now only syncs files and reloads
systemd's unit *definitions*; `maintctl.sh` owns all enable/disable/start
state exclusively.

## What's actually deployed

- `ops/maintenance/index.html` — the bilingual page, served from
  `/var/www/timebank-maintenance/` on the server (a location entirely
  separate from `/var/www/timebank`, the React app's build output, so
  redeploying the frontend never touches it and vice versa).
- `ops/maintenance/nginx-maintenance.conf.inc` — the gating logic,
  `include`d near the top of the site's `server {}` block. Checked into
  the repo with a `__BYPASS_KEY__` placeholder; `ops/maintenance/deploy.sh`
  substitutes the real key (from `$TIMEBANK_MAINTENANCE_BYPASS_KEY`,
  `Docs/operations/DeploymentEnv.md`) only in the copy it uploads — the
  real key is never written into this repository.
- `ops/maintenance/timebank-maintenance-{on,off}.{service,timer}` — the
  two systemd units (shipped defaults; a `schedule --start/--end` call
  customizes them via a drop-in on the server, not by rewriting these).
- `ops/maintenance/timebank-maintctl` — the real on/off/schedule/status
  logic, installed to `/usr/local/sbin/timebank-maintctl` on the server.
- `ops/maintenance/maintctl.sh` — thin workstation wrapper around it (syncs
  the script, then runs it over SSH) — this is the one operators actually
  run day to day.
- `ops/maintenance/deploy.sh` — idempotent sync script for the page, the
  nginx snippet, and the base systemd unit files; deliberately does
  **not** edit the live site config itself (that file is Certbot-managed,
  and a script silently rewriting it is exactly the kind of edit that
  should be reviewed by a human once, not automated) — it just tells you
  if the one-time `include` line is still missing. Also deliberately does
  **not** enable/start the timers or touch the marker file — see above.

## Bugs found while building and verifying this live

Two real bugs surfaced during live verification (a curl-first, then
Playwright, pass against the actual deployed server — see
`ops/maintenance/README.md` for the exact commands used):

1. **The maintenance page itself 503'd with nginx's bare default error
   page**, not the real bilingual one. Root cause: the exemption that lets
   `error_page 503`'s internal redirect to `/__maintenance.html` actually
   reach its own `location` block was checking `$request_uri`, which stays
   frozen at the *original* request URI (`/`) even after an internal
   redirect — so the redirected sub-request re-entered the same gate,
   503'd again, and nginx fell back to its own default page rather than
   loop. Fixed by checking `$uri` instead, which does reflect the current
   (possibly internally-rewritten) URI.
2. **`deploy.sh`'s "is the include line already wired up" check always
   reported "not wired," even right after manually adding it.**
   `/etc/nginx/sites-enabled/tb.example.com` is a symlink into
   `sites-available/`, and `grep -r` (lowercase) does not follow symlinks
   while recursing — it silently skipped the one file that mattered.
   Fixed by using `grep -R` (capital, follows symlinks) instead.

## Verification performed (three-mode follow-up)

Live against the real deployed server, using `ops/maintenance/maintctl.sh`
exactly as an operator would (not lower-level manual `touch`/`systemctl`
calls this time): `status` before any mode had been explicitly set showed
`schedule`/`18:00-08:00` but the gate reported inactive despite the real
Beijing time already being inside that window, since the mode had never
actually been *applied* yet — running `schedule` (no flags) applied it and
immediately flipped the gate active, confirmed via `curl` (`503` with no
cookie, `200` with the real bypass cookie). `off` then correctly forced
`200` even though still inside the normal window, and disabled/stopped
both timers (`0 timers listed`). `on` then correctly forced `503`
regardless of time, with the bypass cookie still working. `schedule
--start 22:00 --end 06:00` was applied next: `systemctl list-timers`
showed the *exact* correct next-fire timestamps for the new window
(cross-checked the UTC output by hand against 22:00/06:00 Beijing time),
and the gate immediately flipped to inactive since the then-current time
fell outside the new window — proving the immediate-reevaluation logic,
not just the timer scheduling. Finally re-applied `18:00`-`08:00`, which
correctly re-activated the gate immediately (genuinely inside that window
at verification time), left as the real final state — `503` with no
cookie, `200` with the bypass cookie, confirmed one last time.

## Verification performed (original schedule-only pass)

Live against the real deployed server (`curl` for the HTTP-level checks,
Playwright for a real-browser pass): confirmed normal `200` on both `/`
and `/api/v1/ping` with the gate inactive; manually activated the marker
file and confirmed `503` + the real bilingual page body on both `/` and
`/api/v1/ping`; confirmed a wrong bypass key `404`s and a correct one sets
the cookie and redirects to `/`; confirmed the cookie actually lifts the
gate (`200` on both paths) while a wrong cookie value stays gated;
confirmed in a real browser that the bypassed session can use the full
app normally (navigated to the student portal home, real data rendered);
started both systemd *services* directly (not just the manual
`touch`/`rm` used for the nginx-level tests) to confirm they create/remove
the marker file correctly; deactivated the gate afterward and confirmed
`200` returned on both paths with no leftover state.

**Not verified**: an actual scheduled firing of either timer (both fire at
18:00/08:00 Beijing time; this session's verification used
`systemctl start <service>` to invoke the same units on demand rather than
waiting for the real clock trigger). `systemctl list-timers` after
enabling showed both correctly scheduled for the next occurrence — see
`ops/maintenance/README.md` for the exact command to re-check this later.
