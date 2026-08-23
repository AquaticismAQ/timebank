---
description: Project guidance for TimeBank — synced from CLAUDE.md and AGENTS.md
trigger: always_on
---

# TimeBank Project Guidance

This file provides guidance to Devin when working in this repository.

## Project overview

TimeBank (时间银行) is an institutional system for recording, auditing, and incentivizing middle-school student volunteer service. Service hours become "time coins" (never a currency/token — see Content Principles below). The project has four phases:

- **Phase 1 — Institutional design**: complete, documented in `Design/chinese/*.md`.
- **Phase 2 — Online system (current focus)**: the React frontend and Spring Boot/MySQL backend are both deployed at `https://tb.example.com/`. B0-B6 are implemented and deployed, with B0.14 plus named performance/infrastructure exit criteria still open; B7 Database Operations is partial and mostly blocked on missing production infrastructure. I0 is done, I1/I2 have their remaining verification or UX-polish criteria, and I3 is in progress: I3.1-I3.6 are done and 11 of 12 I3.7 acceptance scenarios are proven; only the complete bilingual/mobile+desktop sweep in scenario #12 remains open. The Role & Permission Management overhaul is implemented, deployed, and live-verified (2026-08-19). `00-Status.md` is a living historical tracker whose opening snapshot predates later findings; read its latest sections as well as its phase tables, then check recent commits and the relevant optimization document before relying on a summary.
- **Phase 3 — POV (Proof of Volunteering)**: WeChat Mini Program witness/AI-verification layer, reserved via feature flags, not implemented.
- **Phase 4 — Redemption/borrowing/value-added**: reserved via feature flags, not implemented.

Repo layout:

```
Design/chinese/          Phase 1 institutional design docs (制度设计, source of "why")
Docs/frontend/Design.md  Phase 2 frontend page/flow spec (English + _CN Chinese twin)
Docs/integration/APIContract.md   The REST API contract — authoritative for both frontend and future backend
Docs/implementation/     Phase-by-phase backend + integration build plan and the living status tracker (00-Status.md)
Docs/TestAccounts.md     Login credentials for the seeded test accounts on the shared test server
integration-test/        Seeds a realistic dataset onto the shared test server; STATUS.md logs live-testing bugs/fixes
frontend/                React + Vite web app (Student Portal + Admin/Teacher Portal)
backend/                 Spring Boot 3.4 / Java 21 backend, single Maven module, package root `cn.timebank` (see below)
```

`Docs/frontend/Design.md` and `Docs/integration/APIContract.md` each have a `_CN.md` Chinese counterpart that must stay in sync when edited.

## Start-of-work checklist

Use this order before changing code:

1. Read the summary, phase table, acceptance table, and latest findings in `Docs/implementation/00-Status.md`.
2. Read `integration-test/STATUS.md` for the deployed dataset and latest live-verification findings.
3. Check `git status` and recent `git log`; inspect actual code and scripts. Historical status paragraphs describe what was true when written and may be superseded later.
4. Read applicable clarification documents: `Docs/optimization/02-PorjectOrganization.md` for project/organization behavior, `03-ServiceRecordFormOptimization.md` for service-record sources, and `07-RoleFinetune.md` for users, roles, permissions, and data scope.
5. After a deployed-app test finds or verifies a defect, update `integration-test/STATUS.md`. Update `00-Status.md` when phase or acceptance evidence changes.

## Commands

All frontend commands run from `frontend/`:

```bash
cd frontend
pnpm install
pnpm dev
npx -y -p typescript tsc --noEmit -p tsconfig.json
pnpm build
pnpm preview
```

There is no lint, test, or typecheck script in `frontend/package.json`; do not assume `pnpm lint`, `pnpm test`, or `pnpm typecheck` exists. Vite's build does not typecheck, so use the explicit `tsc` command above. TypeScript is strict, while `noUnusedLocals`/`noUnusedParameters` remain off.

**Frontend testing/verification uses the real shared test server, not a local mock.** The real test-server hostname/credentials live in the untracked `test-server-credits.md` (this repo is public — no real hostnames are checked in; `tb.example.com` elsewhere in these docs is a placeholder). That server serves the real frontend against the real backend (`frontend/scripts/deploy.sh`, deployed 2026-08-07). Before any live frontend test or browser verification pass, check whether frontend source has changed since the last deploy (`git log` against the deploy commit) and re-run `frontend/scripts/deploy.sh` first if so — testing stale static output against new backend behavior (or vice versa) produces false results. This mirrors the backend's existing rsync-then-`verify.sh` live-verification workflow; there is no separate local/staging frontend environment. Log in with one of the real seeded accounts in `Docs/TestAccounts.md` (`admin001`/`student001`/`student002`/`teacher001`) — the login page's old mock-only "demo accounts" quick-login card was removed 2026-08-07. The server also carries a full seeded dataset (`integration-test/`, README + STATUS.md) — projects/records/proposals in every workflow status — so screens aren't empty during a live test; check `integration-test/STATUS.md` before assuming a bug is new.

Key frontend components added during I3 (§24-§34): `EvidenceLink.tsx` (short-lived URL evidence viewer, used in both portals), `SearchPicker.tsx` (generic Command+Popover picker for directory search), `ProjectRegistrants.tsx` (admin confirm/reject/bulk), `ProposalReview.tsx` (admin proposal review), `CaseReview.tsx` (admin case review), `OrganizationManagement.tsx`, and `RoleManagement.tsx`. Language persistence consolidated in `lib/language.ts`. Timezone-safe date helpers in `lib/datetime.ts` (`localDateTimeToISO`/`isoToLocalDateTime`).

Backend commands run from `backend/` (single Maven module, no multi-module split — that question from earlier is resolved):

```bash
cd backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify
```

**No Docker anywhere in this project** — not locally, not in tests, not in deployment (`Docs/implementation/01-Overview.md` §6). Integration tests (`*IT` classes, run via `mvn verify`/Failsafe, not `mvn test`/Surefire) run against real MySQL on the shared test server, each in its own `timebank_it_<random>` schema created and dropped per test class (`cn.timebank.testsupport.MySqlTestSchemaExtension`) — never against the application's own `timebank` schema. The app deploys as a systemd unit (`backend/scripts/deploy.sh`), not a container; see `backend/scripts/verify.sh` for the test entry point a CI runner would call. Both scripts read secrets from the environment and contain none themselves.

The backend is a single Maven module rooted at package `cn.timebank` (not `cn.<school>.timebank`). Real code exists across `account`, `caseflow`, `dataexchange`, `enrollment`, `file`, `lifecycle`, `notification`, `organization`, `progress`, `project`, `proposal`, `reporting`, `review`, `servicerecord`, plus the base IAM/security/student/rules/audit modules. Do not use the old B2-era assumption that later business modules contain only `package-info.java`. Local Maven builds need `JAVA_HOME=/opt/homebrew/opt/openjdk@21` explicitly set — the default `java` on this machine is JDK 17.

## Frontend architecture

**Everything lives under `frontend/src/app/`** (not `src/` directly — routes, contexts, and API client are all nested one level in). Path alias `@` → `frontend/src`.

### Data flow: API client (real backend only)

`frontend/src/app/api/client.ts` exports a single `api` object grouped by resource domain (`api.projects`, `api.serviceRecords`, `api.accounts`, `api.eventProposals`, `api.reviewQueue`, etc.), each method making real HTTP calls via `api/http.ts` (base URL `/api/v1`, same-origin in production, dev-proxied locally). Mock mode was removed entirely (2026-08-07): there is no in-memory mock data, no `mock/` directory, and no per-domain real/mock toggle. `frontend/.env.local` (copied from `.env.example`) with `VITE_DEV_PROXY_TARGET` is required for `pnpm dev` — Vite fails fast at startup if it's missing.

The app talks to `api.*` only:

```tsx
const [projects, setProjects] = useState<Project[]>([]);
useEffect(() => { api.projects.list().then(setProjects).catch(() => {}); }, []);
```

`frontend/src/app/api/useApi.ts` provides `usePagedApi` (primary, paginated) and `useApi` (simple) hooks. The `api/http.ts` transport handles envelope unwrapping, CSRF, `Accept-Language`, idempotency keys, and error classification (`ApiError` with `code`, `fieldErrors`, `requestId`).

### Routing, auth, and workspaces

`frontend/src/app/App.tsx` is the composition root: `ThemeProvider` (light/dark/auto, auto follows local hour 7–19) → `AuthProvider` (user persisted to `localStorage`) → `LogsProvider` (client-only operation log, seeded, not backed by an API yet) → `BrowserRouter`.

Every user holds exactly one role, so exactly one workspace (`07-RoleFinetune.md` §2.5 — the dual-workspace/multi-role architecture and its `/workspace` picker were retired 2026-08-19). `RequireAuth` sends unauthenticated users to `/login` and a workspace mismatch straight to the user's own portal home. Routes are nested under `/student/*` (`StudentLayout`) and `/admin/*` (`AdminLayout`), matching the Student Portal / Admin+Teacher Portal split in `Docs/frontend/Design.md` §3–7 — teachers use the same Admin Portal as school admins but with scoped permissions, not a separate portal.

### Component organization

```
components/auth/       login
components/layout/     AppShell, StudentLayout, AdminLayout (shell/nav chrome)
components/student/    one file per student-portal page (StudentHome, ProjectCenter, ServiceRecords, EventProposals, ...)
components/admin/      one file per admin-portal page (AdminDashboard, AuditCenter, ProjectManagement,
                       ProjectRegistrants, ProposalReview, CaseReview, AccountManagement, UserManagement,
                       OrganizationManagement, RoleManagement, ...)
components/shared/     EvidenceLink (short-lived URL viewer), SearchPicker (Command+Popover picker)
components/ui/         shadcn/Radix primitives (button, dialog, table, ...) — treat as generated/vendored, prefer composing over editing
components/figma/      ImageWithFallback — Figma-import artifact
```

This is organized by portal/page today, not yet by the business-domain module layout (`projects/`, `service-records/`, `reviews/`, ...) that `Docs/frontend/Design.md` §12 recommends as the target structure — don't assume that reorganization has happened. `RoleManagement.tsx` owns the configurable Roles and Role Templates tab; do not reintroduce a frontend role enum or workspace picker.

### Styling

Tailwind CSS v4 (via `@tailwindcss/vite`, no `tailwind.config.js` — config lives in CSS). Theme tokens in `frontend/src/styles/theme.css`/`globals.css`. Mobile-first is a hard requirement per the design doc: build the mobile layout first, then progressively enhance for tablet/desktop, and don't invent new breakpoints outside Tailwind's defaults.

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
- **Phase 3/4 flags**: disabled features show "Not yet available"; do not half-build POV or redemption/borrowing features.
- **Opaque IDs**: IDs are server-generated strings. Never parse or derive semantics from their format.
- **Integer units**: durations use integer minutes and time-coin amounts use integer minimum accounting units; never use floats.

## Documentation rules

**All rules for writing/editing anything under `Docs/`, `Design/`, or `integration-test/*.md` — directory structure, bilingual pairs, issue-doc numbering and workflow, living-status-doc update discipline, the `REVIEW-IMPORTANT` review convention, content principles, secrets handling — live in `Docs/README.md`. Read it before creating or editing any doc; it is written for exactly this situation (an agent picking up this repo cold) and is binding, not optional.** This includes live database writes discovered during bug-tracking: `Docs/README.md` §2.4 covers when an issue doc is required, but the writes themselves remain a shared-state, hard-to-reverse action — narrate and get confirmation first, same as any other risky action.

## Live-verification and issue tracking

- Before browser testing, deploy the relevant changed frontend/backend artifact; do not treat stale shared-server output as verification.
- For every defect reproduced against the deployed app, first check `Docs/issues/` and `integration-test/STATUS.md`. Create the next sequential `Docs/issues/0NN-<slug>.md` entry, then update the integration-test bug log and chronological history in the same session. Data-only live fixes follow the same process.
- A live database write against the shared `timebank` schema is shared, hard-to-reverse state. State the exact intended change and obtain user confirmation before performing it. Never run integration tests against that schema.
