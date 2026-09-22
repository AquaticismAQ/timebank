# AGENTS.md

This is the generic, tool-agnostic guide for this repository. It applies to
**every AI coding agent** (Claude Code, Codex, Copilot, Devin, or any
other) and **every human collaborator** working here — not one tool in
particular. Read it in full before making changes. A Claude-Code-specific
file (`CLAUDE.md`) also exists at the repo root with the same project facts
in Claude Code's preferred format; the two should be kept factually
consistent when project state changes, but this file is the one every other
agent and every human collaborator should treat as authoritative.

## Project overview

TimeBank (时间银行) is an institutional system for recording, auditing, and
incentivizing middle-school student volunteer service. Service hours become
"time coins" (never a currency/token — see Domain conventions below). The
project has four phases:

- **Phase 1 — Institutional design**: complete, documented in `Design/chinese/*.md`, with same-named English mirrors in `Design/english/*.md`. The Chinese documents are the source of truth; when the two versions differ, follow the Chinese version and update the English mirror to match.
- **Phase 2 — Online system (current focus)**: React web frontend deployed at `https://tb.example.com/` (`frontend/`). Java/Spring Boot + MySQL backend implementation started 2026-08-02 (`backend/`) per `Docs/implementation/`; B0-B6 backend work is done and deployed to the shared test server (B6 with named infra-blocked gaps), B7 (Database Operations) started and mostly blocked on infrastructure that doesn't exist — see `Docs/implementation/00-Status.md` for current state. Integration: I0 done, I1 mostly done, I2 done (all 10 exit criteria met), I3 in progress (I3.1-I3.6 done, I3.7 8/12 acceptance scenarios proven) — see `00-Status.md` §21–§34.
- **Phase 3 — POV (Proof of Volunteering)**: WeChat Mini Program witness/AI-verification layer, reserved via feature flags, not implemented.
- **Phase 4 — Redemption/borrowing/value-added**: reserved via feature flags, not implemented.

`Docs/implementation/00-Status.md` is a living document whose opening
snapshot can predate later findings elsewhere in the same file — read its
latest dated sections, not just the top summary, before relying on it.
Cross-check against recent `git log` and the actual code; a status
paragraph describes what was true when written.

Repo layout:

```
Design/chinese/          Phase 1 institutional design docs (制度设计, authoritative source of "why")
Design/english/          Same-named English mirrors of Design/chinese/; Chinese remains the source of truth
Docs/frontend/Design.md  Phase 2 frontend page/flow spec (English + _CN Chinese twin)
Docs/integration/APIContract.md   The REST API contract — authoritative for both frontend and backend
Docs/implementation/     Phase-by-phase backend + integration build plan and the living status tracker (00-Status.md)
Docs/optimization/       Post-launch redesign/clarification docs for specific subsystems (numbered)
Docs/issues/             One doc per live-verified or reported defect (numbered, see Documentation rules)
Docs/TestAccounts.md     Login credentials for the seeded test accounts on the shared test server
integration-test/        Seeds a realistic dataset onto the shared test server; STATUS.md logs live-testing bugs/fixes
frontend/                React + Vite web app (Student Portal + Admin/Teacher Portal)
backend/                 Spring Boot 3.4 / Java 21 backend, single Maven module, package root `cn.timebank`
```

## Commands

All frontend commands run from `frontend/`:

```bash
cd frontend
pnpm install        # install deps (pnpm workspace, see pnpm-lock.yaml/pnpm-workspace.yaml)
pnpm dev            # start Vite dev server
npx -y -p typescript tsc --noEmit -p tsconfig.json   # typecheck (Vite's build does not do this separately)
pnpm build          # production build
pnpm preview        # preview a production build
```

There is no lint, test, or typecheck script configured in
`frontend/package.json` — do not assume `pnpm lint`/`pnpm test`/`pnpm
typecheck` exist. TypeScript is `strict: true` but
`noUnusedLocals`/`noUnusedParameters` are off.

**Frontend testing/verification uses the real shared test server, not a
local mock.** There is no separate staging environment. Real hostname and
credentials live in the untracked `test-server-credits.md` (this repo is
meant to eventually go public — `tb.example.com` elsewhere in these docs is
a placeholder, never the real host). Before any live frontend test or browser verification pass,
check whether frontend source has changed since the last deploy (`git log`
against the deploy commit) and run `frontend/scripts/deploy.sh` first if so
— testing stale static output against current backend behavior (or vice
versa) produces false results. Log in with a real seeded account from
`Docs/TestAccounts.md`. The server carries a full seeded dataset
(`integration-test/`, README + STATUS.md) — check `integration-test/
STATUS.md` before assuming a bug is new, and before assuming an empty
screen means missing data.

Backend commands run from `backend/` (single Maven module, package root
`cn.timebank`):

```bash
cd backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile   # local compile check only
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test       # fast: MockMvc slices + ArchUnit, no external dependency
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify     # adds *IT integration tests — needs TIMEBANK_DB_HOST/_USERNAME/_PASSWORD
                                                       # for an account with CREATE/DROP on `timebank_it_%`
```

The machine's default `java` may not be JDK 21 — set `JAVA_HOME` explicitly
as shown. **No Docker in deployment or tests** — but there
*is* a local development stack (`docker-compose.yml`, MySQL + backend on one
machine, for contributors without a second host: `Docs/operations/DockerDevelopment.md`,
issue 101). Production remains systemd + fat JAR and no test starts a container. Integration tests (`*IT` classes, run via `mvn
verify`/Failsafe, not `mvn test`/Surefire) run against real MySQL on the
shared test server, each in its own `timebank_it_<random>` schema created
and dropped per test class — never against the application's own `timebank`
schema. The app deploys as a systemd unit (`backend/scripts/deploy.sh`), not
a container; `backend/scripts/verify.sh` is the full-verification entry
point. Both scripts read secrets from the environment and contain none
themselves. Check `Docs/implementation/00-Status.md` before assuming what
backend code exists — it is built phase by phase, and later modules may
still be empty scaffolding.

## Frontend architecture

**Everything lives under `frontend/src/app/`** (not `src/` directly).
Path alias `@` → `frontend/src`.

`frontend/src/app/api/client.ts` exports a single `api` object grouped by
resource domain, each method making real HTTP calls via `api/http.ts` (base
URL `/api/v1`, same-origin in production, dev-proxied locally). There is no
mock mode, no in-memory mock data, and no real/mock toggle — every screen
talks to `api.*` only. `frontend/.env.local` (copied from `.env.example`)
with `VITE_DEV_PROXY_TARGET` is required for `pnpm dev`; Vite fails fast at
startup if it's missing. `frontend/src/app/api/useApi.ts` provides
`usePagedApi` (primary, paginated) and `useApi` (simple) hooks; use these
rather than component-specific `fetch` calls.

`frontend/src/app/App.tsx` is the composition root: `ThemeProvider` →
`AuthProvider` (user persisted to `localStorage`) → `LogsProvider` →
`BrowserRouter`. Every user holds exactly one role, so exactly one
workspace — there is no `/workspace` picker and no multi-role UI. Routes
are nested under `/student/*` (`StudentLayout`) and `/admin/*`
(`AdminLayout`); teachers use the same Admin Portal as school admins but
with scoped permissions, not a separate portal.

Component organization:

```
components/auth/       login
components/layout/     AppShell, StudentLayout, AdminLayout (shell/nav chrome)
components/student/    one file per student-portal page
components/admin/      one file per admin-portal page
components/shared/     EvidenceLink (short-lived URL viewer), SearchPicker (Command+Popover picker)
components/ui/         shadcn/Radix primitives — treat as generated/vendored, prefer composing over editing
components/figma/      ImageWithFallback — Figma-import artifact
```

This is organized by portal/page today, not by business-domain module —
don't assume a `projects/`/`service-records/`/`reviews/` reorganization has
happened unless you've checked.

Tailwind CSS v4 (via `@tailwindcss/vite`, no `tailwind.config.js` — config
lives in CSS). Mobile-first is a hard requirement: build the mobile layout
first, then progressively enhance for tablet/desktop, and don't invent new
breakpoints outside Tailwind's defaults.

## Domain and status-model conventions

Set by `Docs/integration/APIContract.md` and `Design/chinese/`; both
frontend and backend work must respect them:

- **Never call it a "token"/"currency"**: time coins are a non-financial school-internal accounting unit. Don't introduce redemption/cash-out/transfer semantics outside the explicitly reserved Phase 4 namespace.
- **Status machines are backend-owned**: `Project`, `EventProposal`, `Application`, `ServiceRecord`, `Case` all have defined status transitions — the frontend displays state, it does not derive or invent it.
- **Account balances are transaction-derived only**: no direct balance edit endpoint exists or should exist; every change is a new immutable `AccountTransaction` (including corrections/reversals — never overwrite or delete a transaction).
- **Reviews/approvals are non-overwritable events**: changing a decision appends a new event rather than mutating the old one.
- **Event Proposal → Project pipeline**: a student "activity proposal" (`EventProposal`) is not a `Project` and never appears in the Project Center until approved; approval must atomically generate the official `Project` draft and backfill `approvedProjectId` in the same transaction — never create the project first and separately link it.
- **Phase 3/4 features are feature-flagged, not half-built**: disabled features must show "Not yet available" rather than a broken/blank action.
- **IDs are opaque server-generated strings**: never parse or derive meaning from an ID format.
- **Durations/amounts are integers**: minutes for duration, minimum accounting units for time-coins — no floats.

## Documentation rules

**All rules for writing or editing anything under `Docs/`, `Design/`, or
`integration-test/*.md` — directory structure, English/Chinese language
policy, issue-doc numbering and workflow, living-status-doc update
discipline, the `REVIEW-IMPORTANT` review convention, content principles,
secrets handling — live in `Docs/README.md`.** Read it before creating or
editing any doc; it is written for exactly this situation (an agent picking
up this repo cold) and is binding, not optional.

Three rules from `Docs/README.md` are easy to get backwards and worth
restating here:

- **File the issue doc as work starts, not after the fix ships silently.**
  This applies to any real defect reproduced against the live app *and* to
  anything a collaborator directly flags and wants tracked, including a
  UI-consistency or polish gap, not only strict correctness bugs.
- **A data-only fix still gets a doc.** If the root cause is a live-database
  gap fixed via direct SQL with no application code touched, it is exactly
  as reportable as a code fix.
- **A design document states the design, never how it got there**
  (`Docs/README.md` §2.11). No dates, no "previously", no "this design
  changes", no migration filenames, no section described as added later.
  When revising one, rewrite the passage in place so it reads as though it
  had always said this — the *why it changed* belongs in the issue doc, the
  build state in `Docs/implementation/00-Status.md`.

## Operational conventions for every agent and collaborator

- **No real identifying data in git — docs or code.** This repo is meant to
  go public. Real hostnames, credentials, and any real person's/school's
  name must never be committed — in `Docs/`, in application code (frontend
  i18n copy, backend seed data), or in a commit message. Use the
  placeholders already established in the repo (`tb.example.com`,
  `Example City HD`, `Teacher Wei Chen`, `example-township-school.edu`; full rules in
  `Docs/README.md` §2.8) instead of inventing new ones or quoting what a
  live-verification session actually showed on the real deployed system.
  This has already gone wrong once: real school/contact/hostname data
  leaked into ~20 tracked docs and hardcoded i18n strings for about a week
  before being caught, requiring a full git-history rewrite to remove
  (`Docs/issues/057-*.md`, 2026-08-26). Finding real data already committed
  is a flag-and-confirm situation, not a quiet fix.
- **Audit every mutation, and verify it, not just review it.** Any new or
  modified write path (POST/PATCH/DELETE, or a service method with a real
  side effect) must call `AuditService.record(...)` with a real
  `before`/`after` pair when the action is an update. During live
  verification, don't just eyeball the feature working — also query `GET
  /audit-logs?filter[resourceType]=<type>&filter[resourceId]=<id>` (or the
  `audit_log` table directly) and confirm a real row landed with the
  correct actor. If a mutation deliberately has no business need for an
  audit entry, say so explicitly rather than silently omitting the call.
- **Live database writes are shared, hard-to-reverse state.** A direct
  `INSERT`/`UPDATE` against the shared server's real `timebank` schema
  (not a `timebank_it_*` test schema) needs the exact intended change
  stated and confirmed before running it, same as any other risky action —
  even though doing such writes directly is this repo's established
  pattern where no admin UI exists yet for a given change. Never point
  integration tests at that schema.
- **Redeploy before you trust a live test.** Frontend and backend each have
  their own deploy script (`frontend/scripts/deploy.sh`,
  `backend/scripts/deploy.sh` after `verify.sh`); check for unshipped
  changes before treating a live-verification pass as meaningful.
- **Commit identity for agent-made commits.** Unless a human collaborator is
  committing under their own GitHub identity, commits in this repo use
  author/committer name `Aquaticism`, email `aquaticism.aq@gmail.com` — set
  via `GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL`/`GIT_COMMITTER_NAME`/
  `GIT_COMMITTER_EMAIL` scoped to the `git commit` invocation itself, never
  by editing git config. This is a standing project convention until
  explicitly changed.
- **No AI attribution trailers.** Do not add `Co-Authored-By: <AI tool>` or
  similar bot/AI-attribution footers to commit messages or PR bodies in
  this repo — the project has opted out of that convention, in both the
  commit trailer and the PR template.
- **`gh` CLI access can lag `git` access.** SSH push/`git ls-remote` access
  to a repo succeeding does not mean `gh api`/`gh pr create` will work for
  the same account — they're granted separately (collaborator access vs.
  git-level access). If `gh` 404s despite working `git push` and correct
  token scopes, don't guess at token/casing workarounds; confirm
  collaborator access is actually granted first.
