# AGENTS.md

This file provides guidance to Codex when working in this repository.

## Project overview

TimeBank (时间银行) is an institutional system for recording, auditing, and incentivizing middle-school student volunteer service. Service hours become "time coins" (never a currency/token — see Content Principles below).

- **Phase 1 — Institutional design**: complete in `Design/chinese/*.md`.
- **Phase 2 — Online system (current focus)**: the React frontend and Spring Boot/MySQL backend are both deployed at `https://tb.example.com/`. B0-B6 are implemented and deployed, with B0.14 plus named performance/infrastructure exit criteria still open; B7 Database Operations is partial and mostly blocked on missing production infrastructure. I0 is done, I1/I2 have their remaining verification or UX-polish criteria, and I3 is in progress: I3.1-I3.6 are done and 11 of 12 I3.7 acceptance scenarios are proven; only the complete bilingual/mobile+desktop sweep in scenario #12 remains open. The Role & Permission Management overhaul is implemented, deployed, and live-verified (2026-08-19). `00-Status.md` is a living historical tracker whose opening snapshot predates later findings; read its latest sections as well as its phase tables, then check recent commits and the relevant optimization document before relying on a summary.
- **Phase 3 — POV (Proof of Volunteering)**: WeChat Mini Program witness/AI-verification layer, reserved via feature flags, not implemented.
- **Phase 4 — Redemption/borrowing/value-added**: reserved via feature flags, not implemented.

Repository layout:

```
Design/chinese/          Phase 1 institutional design docs (source of "why")
Docs/frontend/           Phase 2 frontend design, English + Chinese twins
Docs/integration/        Authoritative REST API contract, English + Chinese twins
Docs/implementation/     Build plans and living phase/status tracker
Docs/TestAccounts.md     Seeded shared-test-server credentials
integration-test/        Realistic server dataset and live-test status/bug log
frontend/                React + Vite Student and Admin/Teacher portals
backend/                 Spring Boot 3.4 / Java 21 / MySQL backend
```

`Docs/frontend/Design.md` and `Docs/integration/APIContract.md` each have a `_CN.md` Chinese counterpart. Keep each pair structurally and semantically synchronized when edited.

## Start-of-work checklist

Use this order before changing code:

1. Read the summary, phase table, acceptance table, and latest findings in `Docs/implementation/00-Status.md`.
2. Read `integration-test/STATUS.md` for the deployed dataset and latest live-verification findings.
3. Check `git status` and recent `git log`; inspect actual code and scripts. Historical status paragraphs describe what was true when written and may be superseded later.
4. Read applicable clarification documents: `Docs/optimization/02-PorjectOrganization.md` for project/organization behavior, `03-ServiceRecordFormOptimization.md` for service-record sources, and `07-RoleFinetune.md` for users, roles, permissions, and data scope.
5. After a deployed-app test finds or verifies a defect, update `integration-test/STATUS.md`. Update `00-Status.md` when phase or acceptance evidence changes.

## Frontend

Everything lives under `frontend/src/app/`; `@` aliases `frontend/src`.

### Commands

Run from `frontend/`:

```bash
pnpm install
pnpm dev
npx -y -p typescript tsc --noEmit -p tsconfig.json
pnpm build
pnpm preview
```

There is no lint, test, or typecheck script in `frontend/package.json`; do not assume `pnpm lint`, `pnpm test`, or `pnpm typecheck` exists. Vite's build does not typecheck, so use the explicit `tsc` command above. TypeScript is strict, while `noUnusedLocals`/`noUnusedParameters` remain off.

### Real-backend-only data flow

There is no frontend mock mode. It was removed 2026-08-07. Every `api.*` method in `frontend/src/app/api/client.ts` calls the real backend through `api/http.ts`; `frontend/src/app/mock/`, `api/config.ts`, `isRealDomain`, and `VITE_API_REAL_DOMAINS` no longer exist. Do not recreate mock branches or bypass the API client.

`pnpm dev` requires a real backend proxy target. Copy `frontend/.env.example` to the git-ignored `frontend/.env.local` and set `VITE_DEV_PROXY_TARGET` to the real test-server URL (see the untracked `test-server-credits.md` — this repo is public, so `tb.example.com` elsewhere in these docs is a placeholder, not the real host). `vite.config.ts` intentionally fails at startup without it. Production is same-origin: nginx serves the static bundle and proxies `/api/*`, so `frontend/.env.production` is intentionally empty of API toggles.

`frontend/src/app/api/useApi.ts` provides `usePagedApi` and `useApi`. `api/http.ts` handles envelope unwrapping, CSRF, `Accept-Language`, idempotency keys, session-expiry redirects, and `ApiError` classification. Continue using these shared paths rather than component-specific `fetch` calls.

### Deploy and browser verification

The shared test deployment (real hostname in the untracked `test-server-credits.md`) is not a production school deployment. The server has no Node/pnpm. Build locally and deploy static output with:

```bash
frontend/scripts/deploy.sh                    # defaults to root@tb.example.com
frontend/scripts/deploy.sh user@other-host    # optional target
```

The script runs frozen dependency installation, explicit TypeScript checking, `pnpm build`, `rsync --delete` to `/var/www/timebank/`, then validates and reloads nginx. Deploy frontend changes before browser/live verification; otherwise the browser may be testing stale static output.

Use accounts from `Docs/TestAccounts.md`. The shared server has realistic data seeded by `integration-test/seed.ts`. Read `integration-test/README.md`, `MOCK_DATA_GUIDE.md`, and especially `STATUS.md` before reseeding or declaring a bug new. The seeder is first-run-only, refuses conflicts, and is not the future 5,000-student performance seeder. Some live tests are irreversible (for example project archive); account for the current dataset state recorded in `STATUS.md`.

### Architecture

`frontend/src/app/App.tsx` composes `ThemeProvider` → `AuthProvider` → `LogsProvider` → `BrowserRouter`. Every user holds exactly one role, so exactly one workspace (`07-RoleFinetune.md` §2.5 — the dual-workspace/multi-role architecture and its `/workspace` picker were retired 2026-08-19). `RequireAuth` sends unauthenticated users to `/login` and a workspace mismatch straight to the user's own portal home. Routes live under `/student/*` and `/admin/*`; teachers use the Admin Portal with scoped permissions, not a separate portal.

Component organization:

```
components/auth/       login
components/layout/     AppShell, StudentLayout, AdminLayout
components/student/    StudentHome, ProjectCenter, ServiceRecords, EventProposals, ...
components/admin/      dashboards, reviews, projects, registrants, organizations, accounts, roles, ...
components/shared/     EvidenceLink, SearchPicker
components/ui/         shadcn/Radix primitives; compose rather than edit when possible
components/figma/      ImageWithFallback
```

Key I3 additions include `EvidenceLink.tsx`, `SearchPicker.tsx`, `ProjectRegistrants.tsx`, `ProposalReview.tsx`, `CaseReview.tsx`, and `OrganizationManagement.tsx`. `RoleManagement.tsx` owns the configurable Roles and Role Templates tab; do not reintroduce a frontend role enum or workspace picker. Language persistence is centralized in `lib/language.ts`; timezone-safe conversion lives in `lib/datetime.ts`.

Styling uses Tailwind CSS v4 through `@tailwindcss/vite`, with tokens in `frontend/src/styles/theme.css` and `globals.css`. Build mobile-first, then enhance at Tailwind's standard breakpoints.

## Backend

The backend is a single Maven module rooted at package `cn.timebank` (not `cn.<school>.timebank`). Real code exists across `account`, `caseflow`, `dataexchange`, `enrollment`, `file`, `lifecycle`, `notification`, `organization`, `progress`, `project`, `proposal`, `reporting`, `review`, `servicerecord`, plus the base IAM/security/student/rules/audit modules. Do not use the old B2-era assumption that later business modules contain only `package-info.java`.

Local Maven commands run from `backend/`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify
```

`mvn test` runs fast MockMvc/unit/ArchUnit tests without external services. `mvn verify` adds Failsafe `*IT` tests and needs `TIMEBANK_DB_HOST`, `TIMEBANK_DB_USERNAME`, and `TIMEBANK_DB_PASSWORD` for an account with CREATE/DROP on `timebank_it_%`. Each IT class creates and drops its own random schema via `MySqlTestSchemaExtension`; never point tests at the application's `timebank` schema.

There is no Docker anywhere in this project. The backend runs as the `timebank-backend` systemd unit. The normal shared-server sequence is sync → full verification → deploy:

```bash
rsync -az --delete --exclude 'target/' --exclude '.git/' \
  backend/ root@tb.example.com:/opt/timebank-backend/
ssh root@tb.example.com
# In the remote shell, export TIMEBANK_DB_HOST/_USERNAME/_PASSWORD first:
cd /opt/timebank-backend
./scripts/verify.sh
./scripts/deploy.sh
```

`backend/scripts/verify.sh` reads DB credentials only from the environment and runs full `mvn verify`. `backend/scripts/deploy.sh` packages server-side with tests skipped, fixes ownership, restarts systemd, and checks that the service is active. Run relevant focused tests locally, then full server verification before deployment. B0.14's automated CI trigger still does not exist.

## Current domain rules

- **Non-financial terminology**: time coins are a school-internal accounting unit. Never introduce token/currency, cash-out, transfer, mining, or wallet semantics outside explicitly reserved Phase 4 design.
- **Backend-owned states**: `Project`, `EventProposal`, `Application`, `ServiceRecord`, and `Case` transitions are backend-owned. Frontend code displays state and invokes action endpoints; it does not derive or patch status directly.
- **Immutable accounting**: balances are transaction-derived. Corrections and reversals append immutable `AccountTransaction` rows; never edit a balance or overwrite/delete a transaction.
- **Append-only reviews**: review/approval changes append events rather than overwriting prior decisions.
- **Proposal → Project**: proposal approval atomically writes the review event, marks the proposal approved, creates the official project draft, and backfills `approvedProjectId`. Never create then link in separate frontend calls.
- **Proposal organization input**: choose an existing partner organization by opaque `organizationId`, or provide `newOrganizationName`; the backend atomically resolves/creates the organization during proposal creation. Students do not need `organizations.manage` merely to propose a new organization.
- **Current project lifecycle**: `CLOSED` is time-derived and means the registration window elapsed. Managers use `POST /projects/{id}/cancel` with a required reason to cancel/archive unwanted projects, including drafts. Normal archive from `COMPLETED` is gated until every confirmed student's service record is reviewed. A registration-open project with zero confirmed registrants auto-archives when registration closes and records a system audit reason. Read `Docs/optimization/02-PorjectOrganization.md` and the bilingual contract before changing this state machine.
- **Service-record sources**: new records are `PROJECT` or `SELF`. A `PROJECT` record requires the student's confirmed registration and snapshots project-derived organization/category/location plus the project owner as supervisor; those fields remain immutable on update. A `SELF` record needs an explicit supervisor kind; external-manual supervisors require name and contact. The legacy free-text `EXTERNAL` source is resubmit-only for historical records. Respect record-number sequencing and the explicit overnight (`endsNextDay`) behavior; see `Docs/optimization/03-ServiceRecordFormOptimization.md` before changing this flow.
- **Roles, users, and scope**: each user has exactly one `roleCode` and therefore one workspace. Roles and permissions are data-driven; do not restore fixed frontend role lists, `roleCodes`, or the retired `/workspace` picker. Every non-`student` role has school-wide data scope; grade assignments for staff roles are organizational metadata, not a visibility boundary. `student`, `admin`, and `sysadmin` are fully protected roles; `sysadmin` is intentionally hidden from the standard Roles and Users UI. Custom roles cannot receive the three student-submission permissions (`eventProposals.create`, `applications.create`, `serviceRecords.create`). Role templates are permission presets; only `admin`/`sysadmin` may manage them. See `Docs/optimization/07-RoleFinetune.md` before changing any of these rules.
- **Phase 3/4 flags**: disabled features show “Not yet available”; do not half-build POV or redemption/borrowing features.
- **Opaque IDs**: IDs are server-generated strings. Never parse or derive semantics from their format.
- **Integer units**: durations use integer minutes and time-coin amounts use integer minimum accounting units; never use floats.

## Documentation rules

- Chinese design/contract docs are primary; English twins mirror their structure. Update both twins together.
- Preserve cross-references and avoid gratuitous section renumbering.
- Do not weaken anti-fraud, anti-Sybil, privacy, or legal-compliance provisions without explicit justification.
- Avoid financial vocabulary (代币/发币/挖矿/提现/钱包) in new content.
- Keep secrets out of tracked files and scripts. `Docs/TestAccounts.md` contains shared-test credentials by explicit project convention; do not reuse them elsewhere.

## Live-verification and issue tracking

- Before browser testing, deploy the relevant changed frontend/backend artifact; do not treat stale shared-server output as verification.
- For every defect reproduced against the deployed app, first check `Docs/issues/` and `integration-test/STATUS.md`. Create the next sequential `Docs/issues/0NN-<slug>.md` entry, then update the integration-test bug log and chronological history in the same session. Data-only live fixes follow the same process.
- A live database write against the shared `timebank` schema is shared, hard-to-reverse state. State the exact intended change and obtain user confirmation before performing it. Never run integration tests against that schema.
