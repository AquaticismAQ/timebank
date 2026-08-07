# integration-test

Seeds a realistic dataset onto the TimeBank shared test server (`https://tb.example.com/`), because the real server's database is otherwise nearly empty — the only rows on it are whatever accumulated from ad-hoc manual live-verification during development. Without real projects, service records, and proposals in every workflow status, most admin/teacher screens (review queue, audit center, reports, account ledger) have nothing to show and can't actually be tested against the real backend.

This is **not** the production-scale (5,000-student) seeder that `Docs/backend/Design.md` §20.1 describes as a future deliverable — that one still doesn't exist (`cn.timebank.common.seed.Seeder` is a scaffolded-but-empty interface, gated behind a `seed` Spring profile, meant for a staging environment that doesn't exist yet; see `Docs/implementation/00-Status.md`). This is a much smaller, hand-curated dataset (a handful of organizations, 10 students, 6 projects, ~8 service records, ~4 proposals) sized for manually exercising every screen and status, not for load/performance testing.

## What's here

- `mock-data.ts` — the canonical seed dataset. Read **`MOCK_DATA_GUIDE.md`** before editing it.
- `seed.ts` — pushes `mock-data.ts` onto the real server. Organizations/users/students are inserted directly via SQL (fast, no interesting workflow of their own); projects/service records/event proposals are driven through the **real HTTP API** as the real student/teacher/admin personas — real `POST`/`submit`/`decide` calls, so the review queue, the account ledger, and notifications all come out workflow-correct, not hand-typed into a plausible-looking-but-inconsistent state.
- `STATUS.md` — the running log of what's been live-tested against the real server, real bugs that testing found, and their fixes. Update it whenever a live-verification session finds or fixes something — read it first before assuming a screen is untested or a bug is new.

## Running it

```bash
cd integration-test
npm install
TIMEBANK_DB_USERNAME=timebank_app TIMEBANK_DB_PASSWORD=<from test-server-credits.md> npm run seed
```

Optional env vars: `TIMEBANK_SSH_TARGET` (default `root@tb.example.com`), `TIMEBANK_BASE_URL` (default `https://tb.example.com`).

Requires: SSH key access to the server (same key used for `backend/scripts/deploy.sh`), Node 18+ (for global `fetch`/`Headers.getSetCookie()`).

At the end, the script prints the username/password for every account it created — **that output is the only place the plaintext passwords exist**; copy them into `Docs/TestAccounts.md` yourself (the script doesn't touch that file, since it's a committed doc a human should review before it changes).

## Re-run semantics

**This is a first-run seeding tool, not a reconciler.** Before writing anything, it checks whether any of `mock-data.ts`'s usernames or student numbers already exist on the server and refuses to run if so, rather than silently duplicating rows or failing halfway through the API phase with half the dataset committed. To re-seed from scratch, delete the conflicting `user`/`student`/`user_role` rows first (see `Docs/TestAccounts.md`'s "re-seeding" section for the same direct-SQL pattern used to seed them) — there is no `--force` flag and no update-in-place mode.

## Adding more data

See `MOCK_DATA_GUIDE.md`.
