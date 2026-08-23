---
description: E2E verification against the real test server — credentials, workflow,
  and safety rules
trigger: glob
globs:
- frontend/**
- backend/**
---

# E2E Verification — Test Server Access & Rules

## Server Credentials

**Credentials live ONLY in `test-server-credits.md` (git-ignored, untracked).** Read that file at runtime when needed. NEVER:
- Embed credentials in code, docs, commit messages, cursor rules, or agent transcripts
- Hardcode host/port/password/username in any tracked file
- Echo credentials in shell commands that get logged (use env vars instead)

## Test Server Environment

**ALWAYS verify against the deployed server, NEVER against a local `pnpm dev` instance.** Deploy first (`frontend/scripts/deploy.sh`), then test at the real URL.

- **URL:** `https://tb.example.com/` — real frontend + backend deployed (since 2026-08-07)
- **Host:** see `test-server-credits.md` for SSH and DB access
- **Backend:** runs as a systemd unit (`timebank-backend.service`, user `timebank`)
- **Database:** MySQL 8 on localhost:3306, app schema `timebank`
- **Frontend:** static files in `/var/www/timebank/`, SPA fallback via `try_files $uri $uri/ /index.html`
- **Nginx:** serves frontend + proxies `/api/*` to `127.0.0.1:8080`
- **No Docker** — everything runs natively

## Seeded Test Accounts

See `Docs/TestAccounts.md` for real seeded accounts: `admin001`, `student001`, `student002`, `teacher001`. The login page's old mock "demo accounts" card was removed 2026-08-07.

The server also carries a full seeded dataset (`integration-test/`, README + STATUS.md) — projects/records/proposals in every workflow status — so screens aren't empty during a live test. Check `integration-test/STATUS.md` before assuming a bug is new.

## E2E Verification Workflow

### 1. Pre-flight — check if deploy is current

```bash
# Check if frontend source changed since last deploy
git log --oneline frontend/ | head -5
# If changed, re-deploy first:
frontend/scripts/deploy.sh

# Verify backend is running
curl https://tb.example.com/api/v1/ping

# For local dev-proxy testing (alternative to deployed version):
cd frontend
cp .env.example .env.local  # then edit VITE_DEV_PROXY_TARGET to the real test-server URL
pnpm dev
```

### 2. Test User Options

**Option A (preferred): Use seeded accounts** — see `Docs/TestAccounts.md`

**Option B: Disposable SQL users** — for isolated testing. Create via SQL (SSH to server). Use bcrypt hashes via `htpasswd -nbBC 12 '' 'password'`. **Delete all test data after verification.**

Pattern from I3 (§33):
- Admin: `usr_<prefix>_admin` with `SYSTEM_ADMIN` + `SCHOOL_ADMIN` roles
- Teacher: `usr_<prefix>_teach` with `TEACHER` role
- Student: `usr_<prefix>_stu` with `STUDENT` role, linked to a real `student` row (`std_<prefix>01`)

### 3. Playwright Workaround

Playwright/Chromium doesn't exempt `localhost` from `Secure`-flagged cookies (unlike real desktop browsers). For dev-proxy testing:
1. Obtain session via `curl` (proven reliable)
2. Inject into Playwright context with `secure: false`
3. Seed `localStorage['timebank-user']` matching `AuthProvider.login()`'s real branch

For testing against the deployed `https://tb.example.com/` directly, this workaround is unnecessary.

### 4. What to Look For

- **Console errors:** Open DevTools → Console; no red errors during any action
- **Network tab:** Every mutation sends to `/api/v1/*`, not to mock
- **Idempotency:** Double-click submit → only one record created
- **Error handling:** Invalid input → server error shown as toast, form preserved
- **Optimistic locking:** Edit same record in two tabs → second edit shows conflict toast
- **Enum casing:** No raw UPPER_SNAKE values visible in UI
- **No "undefined" strings:** Check all pre-filled form fields — unmapped DTO fields show as literal `"undefined"`
- **No raw i18n keys:** Check rendered text — missing translations show as `key.path.here`
- **Evidence files:** Verify they actually upload and pre-fill on resubmit (not just decorative)

### 5. Post-verification Cleanup

```sql
-- Delete all test data created during verification
-- Users, students, service records, accounts, proposals, cases, organizations, etc.
-- Always clean up; never leave test data on the shared server
-- Use a .sql file (never inline -e) for traceability
```

### 6. Reporting

When a real defect is found and reproduced against the deployed app, follow the full bug-tracking workflow (see `bug-tracking.mdc`):

1. **`Docs/issues/0NN-<slug>.md`** — one doc per bug, numbered sequentially
2. **`integration-test/STATUS.md`** — append to §2 bug log table + §4 fix history
3. **`Docs/implementation/00-Status.md`** — only if this bug fix also changes a phase's status, an acceptance-criteria gate, or a named risk; a UI-consistency/polish bug is 1+2 only (see `bug-tracking.md` §3)

1 and 2, in the same session, always. Don't skip the issue doc just because the fix is small or data-only.
