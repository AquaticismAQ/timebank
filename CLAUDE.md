# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

TimeBank (时间银行) is an institutional system for recording, auditing, and incentivizing middle-school student volunteer service. Service hours become "time coins" (never a currency/token — see Content Principles below). The project has four phases:

- **Phase 1 — Institutional design**: complete, documented in `Design/chinese/*.md`.
- **Phase 2 — Online system (current focus)**: React web frontend is nearly complete (`frontend/`); a Java/Spring Boot + MySQL backend is about to start and does not exist yet.
- **Phase 3 — POV (Proof of Volunteering)**: WeChat Mini Program witness/AI-verification layer, reserved via feature flags, not implemented.
- **Phase 4 — Redemption/borrowing/value-added**: reserved via feature flags, not implemented.

Repo layout:

```
Design/chinese/          Phase 1 institutional design docs (制度设计, source of "why")
Docs/frontend/Design.md  Phase 2 frontend page/flow spec (English + _CN Chinese twin)
Docs/integration/APIContract.md   The REST API contract — authoritative for both frontend and future backend
frontend/                React + Vite web app (Student Portal + Admin/Teacher Portal)
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

There is currently no backend directory — when it's created, prefer confirming the module layout (single Spring Boot app vs. multi-module Maven/Gradle) with the user before assuming a structure, since none exists yet to infer from.

## Frontend architecture

**Everything lives under `frontend/src/app/`** (not `src/` directly — routes, contexts, and API client are all nested one level in). Path alias `@` → `frontend/src`.

### Data flow: mock-backed API client standing in for the real backend

`frontend/src/app/api/client.ts` exports a single `api` object grouped by resource domain (`api.projects`, `api.serviceRecords`, `api.accounts`, `api.eventProposals`, `api.reviewQueue`, etc.), each method returning `Promise<T>` and simulating latency via `delay()`. It currently reads/writes in-memory mock arrays from `frontend/src/app/mock/data.ts`, which also doubles as **the TypeScript type source** (`User`, `Project`, `ServiceRecord`, `Account`, `EventProposal`, etc.).

This is intentional scaffolding for a **frontend-driven** build: the whole app talks to `api.*` only, never to `MOCK_*` constants directly, so that once the Spring Boot backend exists, only `client.ts` method bodies need to change from mock logic to `fetch` calls — no component changes. `Docs/integration/APIContract.md` §12 codifies this pattern explicitly:

```tsx
// Correct — go through the API client
const [projects, setProjects] = useState<Project[]>([]);
useEffect(() => { api.projects.list().then(setProjects).catch(() => {}); }, []);

// Wrong — bypasses the client, breaks the mock→real-backend swap path
import { MOCK_PROJECTS } from '../../mock/data';
```

`frontend/src/app/api/useApi.ts` provides a `useApi(fn, deps)` hook (loading/error/data/refetch) as an alternative to manual `useState`+`useEffect` for read-only fetches.

When the real backend lands, the API surface it must implement is `Docs/integration/APIContract.md` — treat it as the contract, not `client.ts`'s mock behavior (e.g. review-approval must be one backend transaction that writes a review event, updates record status, generates an account transaction, updates progress, and creates a notification — the mock client currently does this as a shortcut, not as a template for real transactional boundaries).

### Routing, auth, and workspaces

`frontend/src/app/App.tsx` is the composition root: `ThemeProvider` (light/dark/auto, auto follows local hour 7–19) → `AuthProvider` (user + workspace persisted to `localStorage`) → `LogsProvider` (client-only operation log, seeded, not backed by an API yet) → `BrowserRouter`.

A user can hold both `student` and `admin` workspace access; `RequireAuth` redirects to `/workspace` (portal picker) when workspace is ambiguous, and to `/login` when unauthenticated. Routes are nested under `/student/*` (`StudentLayout`) and `/admin/*` (`AdminLayout`), matching the Student Portal / Admin+Teacher Portal split in `Docs/frontend/Design.md` §3–7 — teachers use the same Admin Portal as school admins but with scoped permissions, not a separate portal.

### Component organization

```
components/auth/       login, workspace picker
components/layout/     AppShell, StudentLayout, AdminLayout (shell/nav chrome)
components/student/    one file per student-portal page (StudentHome, ProjectCenter, ServiceRecords, ...)
components/admin/      one file per admin-portal page (AdminDashboard, AuditCenter, ProjectManagement, ...)
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

## Content/documentation conventions (when editing Design/Docs)

From `CONTRIBUTING.md`, still binding for this repo:

- Chinese docs are primary; English docs mirror their structure exactly. Keep `_CN` and non-`_CN` doc pairs in sync.
- Preserve cross-references between design docs (`见 XDoc.md` / `see XDoc.md`) and don't renumber existing sections gratuitously — other docs cite section numbers.
- Don't weaken anti-fraud/anti-Sybil/legal-compliance provisions without explicit justification.
- Avoid financial vocabulary (代币/发币/挖矿/提现/钱包) anywhere in new content, per the non-financial design principle.
