---
description: Deploy the frontend to the shared test server
---

# Deploy Frontend

Build locally and deploy static output to the shared test server.

## Prerequisites

- `test-server-credits.md` (untracked) contains the real server hostname/credentials
- `frontend/.env.local` exists with `VITE_DEV_PROXY_TARGET` set (not needed for deploy, but required for dev)

## Steps

1. Check if frontend source has changed since last deploy:
   ```bash
   git log --oneline frontend/ | head -5
   ```

2. Run the deploy script (defaults to `root@tb.example.com`):
   ```bash
   frontend/scripts/deploy.sh
   ```
   Or specify a different target:
   ```bash
   frontend/scripts/deploy.sh user@other-host
   ```

   The script:
   - Runs frozen dependency installation (`pnpm install --frozen-lockfile`)
   - Explicit TypeScript checking (`tsc --noEmit`)
   - Production build (`pnpm build`)
   - `rsync --delete` to `/var/www/timebank/`
   - Validates and reloads nginx

3. Verify the deploy:
   ```bash
   curl -s -o /dev/null -w "%{http_code}" https://tb.example.com/
   # Expect: 200
   ```

## Notes

- The server has no Node/pnpm — build locally, deploy static output only
- `.env.production` is intentionally empty (same-origin in production)
- Deploy before any browser/live verification to avoid testing stale output
