---
description: Deploy the backend to the shared test server
---

# Deploy Backend

Sync backend source to the shared test server, verify, then deploy. Project
rules are in `AGENTS.md`; read it first.

## Prerequisites

- `~/.timebank.env` (untracked, outside the repo) holds the real target and
  credentials — see `Docs/operations/DeploymentEnv.md`. Never write a real
  hostname or password into a tracked file.

## Steps

1. Load the target, then sync the source. **Keep `--exclude 'data/'`**: it
   protects the server's uploaded evidence files, which `--delete` would
   otherwise wipe (issue 060).
   ```bash
   source ~/.timebank.env
   rsync -az --delete --exclude 'target/' --exclude '.git/' --exclude 'data/' \
     backend/ "$TIMEBANK_SSH_TARGET:/opt/timebank-backend/"
   ```

2. Run full verification on the server (`mvn verify`, including the `*IT`
   tests). It reads DB credentials from the server's own `~/.timebank.env`,
   and stops with a clear message if they are missing:
   ```bash
   ssh "$TIMEBANK_SSH_TARGET" 'cd /opt/timebank-backend && ./scripts/verify.sh'
   ```

3. If it passes, deploy (packages with tests skipped, restarts the systemd
   unit, checks it came up):
   ```bash
   ssh "$TIMEBANK_SSH_TARGET" '/opt/timebank-backend/scripts/deploy.sh'
   ```

4. Check it answers:
   ```bash
   curl -fsS "$TIMEBANK_BASE_URL/api/v1/ping"
   ```

## Notes

- Production shape is a systemd unit (`timebank-backend.service`) running a
  fat JAR. The Docker stack is for local development only.
- If the maintenance gate is on, step 4 returns `503`; see
  `Docs/operations/MaintenanceMode.md`.
