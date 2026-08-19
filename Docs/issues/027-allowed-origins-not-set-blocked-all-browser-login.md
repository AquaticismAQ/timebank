# 027 — `TIMEBANK_ALLOWED_ORIGINS` unset on the live server, blocking every browser-driven login

Status: **Fixed and live-verified (2026-08-19)**

## Symptom

Found while live-verifying `Docs/optimization/07-RoleFinetune.md`: logging in
as `admin001` via a real browser (Playwright) failed every time with
"Invalid account or password, please try again," even with confirmed-correct
credentials. The same login succeeded immediately via a raw `curl` request
with the correct CSRF token.

## Root cause

`/etc/timebank-backend.env` on the live server had no `TIMEBANK_ALLOWED_ORIGINS`
entry at all. `application.yml` defaults that value to the tracked
placeholder `https://tb.example.com`
(`allowed-origins: ${TIMEBANK_ALLOWED_ORIGINS:https://tb.example.com}`) when
the env var is absent — with the env var unset, the backend was enforcing
that placeholder instead of the server's real domain. A real browser's
request carries an `Origin` header set to the real domain it's actually
running on, which doesn't match the placeholder, so `RequestOriginFilter`
rejected every request with `403 FORBIDDEN` / `"Request origin is not
allowed."` before it ever reached the login controller. `curl` doesn't send
an `Origin` header by default, so it skipped this check entirely and
succeeded — this is the exact gap `frontend/README.md` already documents for
the local dev-proxy case, just hit here in production instead. The untracked
`test-server-credits.md` already documented that this env var "must" be set
explicitly to the real domain; it simply never was (or was lost at some
point) on this server.

This is unrelated to `07-RoleFinetune.md`'s own changes — it would have
blocked *any* real-browser login, for any account, at any point before this
fix. It surfaced now only because this was the first live browser
verification pass run against this server logging this specific detail.

## Fix

Appended `TIMEBANK_ALLOWED_ORIGINS=<the real domain>` (see the untracked
`test-server-credits.md`) to `/etc/timebank-backend.env` (original backed up
alongside it, `timebank-backend.env.bak-<timestamp>`) and restarted
`timebank-backend`. Narrated and confirmed with the user first, per this
repo's live-change convention, since it's a production server config edit.

Also fixed the underlying process gap so this can't silently recur:
`backend/scripts/deploy.sh` now verifies `TIMEBANK_ALLOWED_ORIGINS` is set
in `/etc/timebank-backend.env` before restarting the service, failing loudly
instead of letting the backend quietly fall back to the placeholder again on
some future fresh server or a reset env file.

## Test plan

- Direct `curl` with a real CSRF token: login succeeded before and after
  (never exercised the Origin check, confirming it wasn't a credentials or
  CSRF problem).
- Live: Playwright browser login as `admin001` — failed with `403`/"Request
  origin is not allowed" before the fix, succeeded immediately after,
  redirecting straight to `/admin/dashboard` (no picker, confirming
  `07-RoleFinetune.md` §2.5's single-workspace change separately).
- `backend/scripts/deploy.sh`'s new pre-flight check verified to fail loudly
  against a scratch env file missing the variable, and pass against one that
  has it.

## Resolution

`/etc/timebank-backend.env` on the live server gained
`TIMEBANK_ALLOWED_ORIGINS=<the real domain>`; `timebank-backend` restarted.
`backend/scripts/deploy.sh` now checks for this variable before every future
deploy.

---
*Live-verified 2026-08-19 against the real deployed server via Playwright
MCP: reproduced the 403 pre-fix (network tab showed the exact
`FORBIDDEN`/"Request origin is not allowed" response body), then confirmed a
clean login as `admin001` post-fix. Also incidentally found, not fixed
(harmless, no functional impact): `admin`'s live permission grants include
the retired `projects.close` permission, a pre-existing data-drift artifact
from before that permission was retired (`02-PorjectOrganization.md` §2) —
nothing server-side checks for it, and `07-RoleFinetune.md`'s new Roles-tab
UI simply makes it visible for the first time via the permission chip list.
Left as-is; an admin can clear it via the chip's own remove button if
desired.*
