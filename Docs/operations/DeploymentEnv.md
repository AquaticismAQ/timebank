# Local deployment env file (`~/.timebank.env`)

Deploying and debugging against the shared test server needs several
`TIMEBANK_*` environment variables (deploy target, DB account, etc.) that
are the same for every collaborator but must never be committed — this repo
is public. Instead of re-exporting them by hand every session, keep them in
a single file **outside the repo**, in your home directory:

```
~/.timebank.env
```

This path is untracked by design (it's outside the repo entirely, so
`.gitignore` doesn't even need an entry) and is never read by anything
except your own shell and the scripts below. The real values come from the
untracked `test-server-credits.md` at the repo root (see that file if you
don't have a copy) — this doc describes the *shape* of `~/.timebank.env`,
not the secrets themselves.

## File structure

Plain `bash`, one `export` per variable, meant to be `source`d:

```bash
# ── Shared test-server target ──────────────────────────────────────────
# Used by frontend/scripts/deploy.sh (rsync/ssh target) and
# integration-test/seed.ts (SSH target + API base URL).
export TIMEBANK_DEPLOY_TARGET=root@<real-host>
export TIMEBANK_SSH_TARGET=root@<real-host>
export TIMEBANK_BASE_URL=https://<real-host>

# ── Backend app DB account (timebank_app) ──────────────────────────────
# Used by backend/scripts/verify.sh (mvn verify's *IT tests, CREATE/DROP on
# timebank_it_% only) and integration-test/seed.ts's direct-SQL seed phase.
export TIMEBANK_DB_HOST=127.0.0.1
export TIMEBANK_DB_PORT=3306
export TIMEBANK_DB_USERNAME=timebank_app
export TIMEBANK_DB_PASSWORD='<real-password>'
```

Fill in the `<real-...>` placeholders from `test-server-credits.md`. Quote
the password — it contains `=` and `^`.

Restrict permissions once created, since it holds a real DB password:

```bash
chmod 600 ~/.timebank.env
```

## What reads it

| Script | Variables it needs | Behavior |
| --- | --- | --- |
| `frontend/scripts/deploy.sh` | `TIMEBANK_DEPLOY_TARGET` | Sources `~/.timebank.env` first if present, then falls back to a CLI arg, then the tracked `tb.example.com` placeholder. |
| `backend/scripts/verify.sh` | `TIMEBANK_DB_HOST`, `TIMEBANK_DB_USERNAME`, `TIMEBANK_DB_PASSWORD` | Sources `~/.timebank.env` first if present; still hard-fails if any of the three are unset (no placeholder fallback for DB credentials — see the script). |
| `integration-test/seed.ts` (`npm run seed`) | `TIMEBANK_SSH_TARGET`, `TIMEBANK_BASE_URL`, `TIMEBANK_DB_USERNAME`, `TIMEBANK_DB_PASSWORD` | Not a shell script, so it doesn't auto-source anything — run `source ~/.timebank.env` in your shell first, then `npm run seed` picks up the exported vars like any child process would. |

Both `deploy.sh`/`verify.sh` source the file *conditionally* (`if [ -f
"$HOME/.timebank.env" ]`) — nothing breaks if it's absent, and neither
script embeds or requires a specific secrets store, so both stay safe to
commit.

## Where each variable is actually consulted, if you need to double check

- `TIMEBANK_DEPLOY_TARGET` — `frontend/scripts/deploy.sh` (rsync target for
  `dist/`, and the `ssh` used to reload nginx).
- `TIMEBANK_SSH_TARGET` / `TIMEBANK_BASE_URL` — `integration-test/seed.ts`
  (SSH host for the direct-SQL phase; base URL for the HTTP-driven phase).
- `TIMEBANK_DB_HOST/_PORT/_USERNAME/_PASSWORD` — `backend/scripts/verify.sh`
  (via `cn.timebank.testsupport.MySqlTestSchemaExtension`, which creates and
  drops a random `timebank_it_<random>` schema per `*IT` test class — never
  the application's own `timebank` schema) and `integration-test/seed.ts`'s
  SQL phase (same `timebank_app` account, since it only has grants on
  `timebank` and `timebank_it_%`).

`backend/scripts/verify.sh` normally runs **on the server itself** (see
`AGENTS.md`'s sync → verify → deploy sequence), so if you want the
auto-sourcing convenience there too, copy the same file to the server's
`~root/.timebank.env` — it's the identical format, just placed in a
different home directory. `frontend/scripts/deploy.sh` and
`integration-test/seed.ts` run from your workstation, so your local
`~/.timebank.env` is what they read.

This file intentionally does **not** cover `frontend/.env.local`
(`VITE_DEV_PROXY_TARGET`) — that one is Vite-specific, must live inside
`frontend/` to be picked up by the dev server, and uses a different (no
`export`) syntax. See `frontend/README.md` for that one; the value is the
same real host as `TIMEBANK_BASE_URL` above.
