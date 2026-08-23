---
description: Deploy the backend to the shared test server
---

# Deploy Backend

Sync backend source to the shared test server, run full verification, then deploy.

## Prerequisites

- `test-server-credits.md` (untracked) contains the real server hostname and DB credentials
- `TIMEBANK_DB_HOST`, `TIMEBANK_DB_USERNAME`, `TIMEBANK_DB_PASSWORD` must be available for verification

## Steps

1. Sync backend source to the server:
   ```bash
   rsync -az --delete --exclude 'target/' --exclude '.git/' \
     backend/ root@tb.example.com:/opt/timebank-backend/
   ```

2. SSH to the server:
   ```bash
   ssh root@tb.example.com
   ```

3. In the remote shell, export DB credentials and run verification:
   ```bash
   export TIMEBANK_DB_HOST=...
   export TIMEBANK_DB_USERNAME=...
   export TIMEBANK_DB_PASSWORD=...
   cd /opt/timebank-backend
   ./scripts/verify.sh
   ```
   `verify.sh` runs full `mvn verify` (including `*IT` integration tests against real MySQL).

4. If verification passes, deploy:
   ```bash
   ./scripts/deploy.sh
   ```
   `deploy.sh` packages server-side with tests skipped, fixes ownership, restarts systemd, and checks that the service is active.

5. Verify the backend is running:
   ```bash
   curl https://tb.example.com/api/v1/ping
   ```

## Notes

- No Docker — the backend runs as a systemd unit (`timebank-backend.service`)
- `verify.sh` reads DB credentials only from the environment
- Run relevant focused tests locally first, then full server verification before deployment
- B0.14's automated CI trigger still does not exist — this is a manual process
