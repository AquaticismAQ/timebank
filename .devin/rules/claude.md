---
description: Legacy Claude Code project guidance for TimeBank
trigger: always_on
---

# TimeBank Legacy Rules

This file provides guidance to Devin when working in this repository.

## Project overview

TimeBank (时间银行) is an institutional system for recording, auditing, and incentivizing middle-school student volunteer service. Service hours become "time coins" (never a currency/token — see Content Principles below). The project has four phases:

- **Phase 1 — Institutional design**: complete, documented in `Design/chinese/*.md`.
- **Phase 2 — Online system (current focus)**: React web frontend deployed at `https://tb.example.com/` (`frontend/`). Java/Spring Boot + MySQL backend implementation started 2026-08-02 (`backend/`) per `Docs/implementation/`; B0-B6 backend work is done and deployed to the shared test server (B6 with named infra-blocked gaps), B7 (Database Operations) started and mostly blocked on infrastructure that doesn't exist — see `Docs/implementation/00-Status.md` for current state. Integration: I0 done, I1 mostly done, I2 done (`✅`, all 10 exit criteria met), I3 in progress (I3.1-I3.6 done, I3.7 8/12 acceptance scenarios proven) — see `00-Status.md` §21–§34.
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

## Commands

All frontend commands run from `frontend/`:

```bash
cd frontend
pnpm install        # install deps (pnpm workspace, see pnpm-lock.yaml/pnpm-workspace.yaml)
pnpm dev            # start Vite dev server
pnpm build           # production build (tsc noEmit is not run separately; Vite handles the build)
pnpm preview         # preview a production build
```

There is no lint, test, or typecheck script configured in `frontend/package.json` — do not assume `pnpm lint`/`pnpm test` exist. TypeScript is `strict: true` but `noUnusedLocals`/`noUnusedParameters` are off (see `frontend/tsconfig.json`).

**Frontend testing/verification uses the real shared test server, not a local mock.** `https://tb.example.com/` serves the real frontend against the real backend (`frontend/scripts/deploy.sh`, deployed 2026-08-07). Before any live frontend test or browser verification pass, check whether frontend source has changed since the last deploy (`git log` against the deploy commit) and re-run `frontend/scripts/deploy.sh` first if so — testing stale static output against new backend behavior (or vice versa) produces false results. This mirrors the backend's existing rsync-then-`verify.sh` live-verification workflow; there is no separate local/staging frontend environment. Log in with one of the real seeded accounts in `Docs/TestAccounts.md` (`admin001`/`student001`/`student002`/`teacher001`/`both001`) — the login page's old mock-only "demo accounts" quick-login card was removed 2026-08-07. The server also carries a full seeded dataset (`integration-test/`, README + STATUS.md) — projects/records/proposals in every workflow status — so screens aren't empty during a live test; check `integration-test/STATUS.md` before assuming a bug is new.

Key frontend components added during I3 (§24-§34): `EvidenceLink.tsx` (short-lived URL evidence viewer, used in both portals), `SearchPicker.tsx` (generic Command+Popover picker for directory search), `ProjectRegistrants.tsx` (admin confirm/reject/bulk), `ProposalReview.tsx` (admin proposal review), `CaseReview.tsx` (admin case review). Language persistence consolidated in `lib/language.ts`. Timezone-safe date helpers in `lib/datetime.ts` (`localDateTimeToISO`/`isoToLocalDateTime`).

Backend commands run from `backend/` (single Maven module, no multi-module split — that question from earlier is resolved):

```bash
cd backend
mvn compile           # local compile check only
mvn test               # fast tests: MockMvc slices + ArchUnit, no external dependency
mvn verify              # adds *IT integration tests — needs TIMEBANK_DB_HOST/_USERNAME/_PASSWORD env vars
                         # for an account with CREATE/DROP on `timebank_it_%` (see test-server-credits.md, untracked)
```

**No Docker anywhere in this project** — not locally, not in tests, not in deployment (`Docs/implementation/01-Overview.md` §6). Integration tests (`*IT` classes, run via `mvn verify`/Failsafe, not `mvn test`/Surefire) run against real MySQL on the shared test server, each in its own `timebank_it_<random>` schema created and dropped per test class (`cn.timebank.testsupport.MySqlTestSchemaExtension`) — never against the application's own `timebank` schema. The app deploys as a systemd unit (`backend/scripts/deploy.sh`), not a container; see `backend/scripts/verify.sh` for the test entry point a CI runner would call. Both scripts read secrets from the environment and contain none themselves.

The backend follows `Docs/implementation/` phase by phase — check `Docs/implementation/00-Status.md` before assuming what exists. As of B2 (Base Data & Rules), real code exists in `cn.timebank.common.*` (envelope, error handling, request-id logging, pagination, idempotency, i18n resolution), `cn.timebank.audit` (append-only audit log), `cn.timebank.iam` (users, roles, permissions, login logs, password lifecycle), `cn.timebank.security` (session auth, CSRF, `@PreAuthorize` on every endpoint, the data-scope resolver mechanism — not yet wired to a real resource), `cn.timebank.student` (students, academic years, grades, classes), `cn.timebank.organization` (partner organizations, service categories, school info, feature flags, the shared `RequestLanguageService`), `cn.timebank.rules` (versioned rules with an immutable-publish lifecycle and date-based resolution), and `cn.timebank.notification` (template rendering only, no send path yet). Local Maven builds need `JAVA_HOME=/opt/homebrew/opt/openjdk@21` explicitly set — the default `java` on this machine is JDK 17. Every other business module (`servicerecord`, `project`, etc.) has no code yet beyond an empty `package-info.java` marking its boundary for B0.2's ArchUnit rules. Package root is `cn.timebank`, not `cn.<school>.timebank` as `Docs/backend/Design.md` §7 originally proposed — deviation and rationale recorded there and in the status doc.

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

`frontend/src/app/App.tsx` is the composition root: `ThemeProvider` (light/dark/auto, auto follows local hour 7–19) → `AuthProvider` (user + workspace persisted to `localStorage`) → `LogsProvider` (client-only operation log, seeded, not backed by an API yet) → `BrowserRouter`.

A user can hold both `student` and `admin` workspace access; `RequireAuth` redirects to `/workspace` (portal picker) when workspace is ambiguous, and to `/login` when unauthenticated. Routes are nested under `/student/*` (`StudentLayout`) and `/admin/*` (`AdminLayout`), matching the Student Portal / Admin+Teacher Portal split in `Docs/frontend/Design.md` §3–7 — teachers use the same Admin Portal as school admins but with scoped permissions, not a separate portal.

### Component organization

```
components/auth/       login, workspace picker
components/layout/     AppShell, StudentLayout, AdminLayout (shell/nav chrome)
components/student/    one file per student-portal page (StudentHome, ProjectCenter, ServiceRecords, EventProposals, ...)
components/admin/      one file per admin-portal page (AdminDashboard, AuditCenter, ProjectManagement,
                       ProjectRegistrants, ProposalReview, CaseReview, AccountManagement, UserManagement, ...)
components/shared/     EvidenceLink (short-lived URL viewer), SearchPicker (Command+Popover picker)
components/ui/         shadcn/Radix primitives (button, dialog, table, ...) — treat as generated/vendored, prefer composing over editing
components/figma/      ImageWithFallback — Figma-import artifact
```

This is organized by portal/page today, not yet by the business-domain module layout (`projects/`, `service-records/`, `reviews/`, ...) that `Docs/frontend/Design.md` §12 recommends as the target structure — don't assume that reorganization has happened.

### Styling

Tailwind CSS v4 (via `@tailwindcss/vite`, no `tailwind.config.js` — config lives in CSS). Theme tokens in `frontend/src/styles/theme.css`/`globals.css`. Mobile-first is a hard requirement per the design doc: build the mobile layout first, then progressively enhance for tablet/desktop, and don't invent new breakpoints outside Tailwind's defaults.

## Domain and status-model conventions

These conventions are set by `Docs/integration/APIContract.md` and `Design/chinese/`, and both frontend and future backend work must respect them:

- **Never call it a "token"/"currency"**: time coins are a non-financial school-internal accounting unit. Don't introduce redemption/cash-out/transfer semantics outside the explicitly reserved Phase 4 namespace.
- **Status machines are backend-owned**: `Project`, `EventProposal`, `Application`, `ServiceRecord`, `Case` all have defined status transitions (see APIContract §3) — the frontend displays state, it does not derive or invent it.
- **Account balances are transaction-derived only**: no direct balance edit endpoint exists or should exist; every change is a new immutable `AccountTransaction` (including corrections/reversals — never overwrite or delete a transaction).
- **Reviews/approvals are non-overwritable events**: changing a decision appends a new event rather than mutating the old one.
- **Event Proposal → Project pipeline**: a student "activity proposal" (`EventProposal`) is not a `Project` and never appears in the Project Center until approved; approval must atomically generate the official `Project` draft and backfill `approvedProjectId` in the same transaction — never create the project first and separately link it.
- **Phase 3/4 features are feature-flagged, not half-built**: disabled features must show "Not yet available" rather than a broken/blank action; don't wire up POV or redemption/borrowing UI beyond what's already flagged.
- **IDs are opaque server-generated strings**: never parse or derive meaning from an ID format.
- **Durations/amounts are integers**: minutes for duration, minimum accounting units for time-coins — no floats.

## Bug-tracking and live-verification workflow

When a live-verification/browser-testing session against `https://tb.example.com/` finds a real defect (not a code-review finding — something reproduced against the real deployed app), write it up rather than just fixing it silently:

- **One doc per bug in `Docs/issues/0NN-<slug>.md`**, numbered sequentially continuing from the highest existing number in that directory — never renumber or reuse a number, and check the directory first so two sessions don't collide. Shape (see any of `001`–`009` for the pattern): a `Status:` line (`Fixed and live-verified (YYYY-MM-DD)` once actually verified, not before), `## Symptom`, `## Root cause`, `## Fix` (or `## Proposed fix` before it's implemented), `## Test plan`, `## Resolution`, and a closing italic footer line stating what was live-verified and naming any gap (e.g. "no browser tool available this session" — say this explicitly whenever verification stopped at the API/code level instead of an actual click-through, so a later session knows to close that gap rather than assuming it's covered).
- **Also update `integration-test/STATUS.md`** every time — it's the living record of actual live-testing activity (distinct from the per-bug doc, and distinct from `Docs/implementation/00-Status.md`'s build-phase tracking): append a row to the bug log table (§2) and a dated entry to the chronological fix history (§4). Do this as you go in the same session, not as a batch rewrite before a review.
- **A data-only fix still gets a doc.** If the root cause is a live-database gap (e.g. a missing seed row) fixed via direct SQL with no application code touched, that's exactly as reportable as a code fix — don't skip the issue doc just because there's no diff to point to.
- **Check `Docs/issues/` and `integration-test/STATUS.md`'s bug log before assuming something is new** — a symptom that looks fresh may already be a named, fixed issue (re-verify against the live app rather than trusting the doc blindly, since live data/deploys can drift from what a doc claims — see issue `008` for a case where a prior fix's data never actually landed).
- Live database writes (direct `INSERT`/`UPDATE` against `tb.example.com`'s real `timebank` schema, not a `timebank_it_*` test schema) are a shared-state, hard-to-reverse action — narrate them and get confirmation before running them, same as any other risky action, even though this repo's established pattern is to do them directly (no admin UI exists yet for e.g. teacher grade-scope assignment).

## Content/documentation conventions (when editing Design/Docs)

From `CONTRIBUTING.md`, still binding for this repo:

- Chinese docs are primary; English docs mirror their structure exactly. Keep `_CN` and non-`_CN` doc pairs in sync.
- Preserve cross-references between design docs (`见 XDoc.md` / `see XDoc.md`) and don't renumber existing sections gratuitously — other docs cite section numbers.
- Don't weaken anti-fraud/anti-Sybil/legal-compliance provisions without explicit justification.
- Avoid financial vocabulary (代币/发币/挖矿/提现/钱包) anywhere in new content, per the non-financial design principle.
