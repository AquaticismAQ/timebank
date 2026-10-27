---
description: Deploy the frontend to the shared test server
---

# Deploy Frontend

Build locally and deploy the static output to the shared test server. Project
rules are in `AGENTS.md`; read it first.

## Prerequisites

- `~/.timebank.env` (untracked, outside the repo) holds the real target — see
  `Docs/operations/DeploymentEnv.md`. The deploy script sources it itself.

## Steps

1. Check whether frontend source changed since the last deploy:
   ```bash
   git log --oneline -5 -- frontend/
   ```

2. Deploy:
   ```bash
   frontend/scripts/deploy.sh
   ```
   The script installs with the frozen lockfile, typechecks (`tsc --noEmit`),
   builds, rsyncs `dist/` to `/var/www/timebank/`, then validates and reloads
   nginx.

3. Check the site answers:
   ```bash
   source ~/.timebank.env
   curl -s -o /dev/null -w "%{http_code}\n" "$TIMEBANK_BASE_URL/"   # expect 200
   ```

## Notes

- The server has no Node/pnpm — build locally, deploy static output only.
- Deploy before any browser verification, or you are testing stale output.
- If the maintenance gate is on, step 3 returns `503`; see
  `Docs/operations/MaintenanceMode.md`.
