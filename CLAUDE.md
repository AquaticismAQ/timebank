# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
e2e/                     Drives whole workflows through a real browser — a narrated live demo and a regression check, one flow, gated phases (see e2e/README.md)
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

**Frontend testing/verification uses the real shared test server, not a local mock.** The real test-server hostname/credentials live in the untracked `test-server-credits.md` (this repo is meant to eventually go public — no real hostnames are checked in; `tb.example.com` elsewhere in these docs is a placeholder). That server serves the real frontend against the real backend (`frontend/scripts/deploy.sh`, deployed 2026-08-07). Before any live frontend test or browser verification pass, check whether frontend source has changed since the last deploy (`git log` against the deploy commit) and re-run `frontend/scripts/deploy.sh` first if so — testing stale static output against new backend behavior (or vice versa) produces false results. This mirrors the backend's existing rsync-then-`verify.sh` live-verification workflow; there is no separate local/staging frontend environment. Log in with one of the real seeded accounts in `Docs/TestAccounts.md` — staff keep readable names (`admin001`/`teacher001`), but **student logins are 8 random digits** (`28339473`), because that is what a real roster import produces (`Design/*/10-StudentActivationFlow.md` §3, issue 105); `studentNNN` is only the fixture handle the docs refer to a student by, and `TestAccounts.md` lists both side by side — the login page's old mock-only "demo accounts" quick-login card was removed 2026-08-07. The server also carries a full seeded dataset (`integration-test/`, README + STATUS.md) — projects/records/proposals in every workflow status — so screens aren't empty during a live test; check `integration-test/STATUS.md` before assuming a bug is new.

Key frontend components added during I3 (§24-§34): `EvidenceLink.tsx` (short-lived URL evidence viewer, used in both portals), `SearchPicker.tsx` (generic Command+Popover picker for directory search), `ProjectRegistrants.tsx` (admin confirm/reject/bulk), `ProposalReview.tsx` (admin proposal review), `CaseReview.tsx` (admin case review). Language persistence consolidated in `lib/language.ts`. Timezone-safe date helpers in `lib/datetime.ts` (`localDateTimeToISO`/`isoToLocalDateTime`).

Backend commands run from `backend/` (single Maven module, no multi-module split — that question from earlier is resolved):

```bash
cd backend
mvn compile           # local compile check only
mvn test               # fast tests: MockMvc slices + ArchUnit, no external dependency
mvn verify              # adds *IT integration tests — needs TIMEBANK_DB_HOST/_USERNAME/_PASSWORD env vars
                         # for an account with CREATE/DROP on `timebank_it_%` (see test-server-credits.md, untracked)
```

**No Docker in deployment or tests** (`Docs/implementation/01-Overview.md` §6) — but **there is a local development stack**: `docker-compose.yml` runs MySQL + the backend on one machine, for contributors without a second host (`Docs/operations/DockerDevelopment.md`, issue 101). Production is still systemd + fat JAR, no test starts a container, and CI does not build the image; don't extend the compose file toward deployment. Integration tests (`*IT` classes, run via `mvn verify`/Failsafe, not `mvn test`/Surefire) run against real MySQL on the shared test server, each in its own `timebank_it_<random>` schema created and dropped per test class (`cn.timebank.testsupport.MySqlTestSchemaExtension`) — never against the application's own `timebank` schema. The app deploys as a systemd unit (`backend/scripts/deploy.sh`), not a container; see `backend/scripts/verify.sh` for the test entry point a CI runner would call. Both scripts read secrets from the environment and contain none themselves.

**Verify on the shared test server, not on the Docker stack** (Aquaticism, 2026-09-20). For an ordinary change — anything that needs no database re-seed, schema rebuild, or destructive experiment — deploy it and run the verification pass live. The Docker stack is for the work that would be rude or impossible to do on a shared box. The reason is that the local stack is a second environment whose *data* differs from the real one, so a pass there can be green for a shape that never occurs live; the server serves only this project's own work, which is what makes deploying-to-verify affordable.

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

`frontend/src/app/App.tsx` is the composition root: `ThemeProvider` (light/dark/auto, auto follows local hour 7–19) → `AuthProvider` (user persisted to `localStorage`) → `LogsProvider` (client-only operation log, seeded, not backed by an API yet) → `BrowserRouter`.

Every user holds exactly one role, so exactly one workspace (`07-RoleFinetune.md` §2.5 — the dual-workspace/multi-role architecture and its `/workspace` picker were retired 2026-08-19; `RequireAuth` now sends a workspace mismatch straight to the user's own portal home, and unauthenticated users to `/login`). Routes are nested under `/student/*` (`StudentLayout`) and `/admin/*` (`AdminLayout`), matching the Student Portal / Admin+Teacher Portal split in `Docs/frontend/Design.md` §3–7 — teachers use the same Admin Portal as school admins but with scoped permissions, not a separate portal.

### Component organization

```
components/auth/       login
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

## Privacy: no real identifying data in git

This repo is meant to go public. Real hostnames, credentials, and any real
person's or organization's name (school staff/students/the school itself)
must never be committed — not in `Docs/`, not in frontend/backend code
(i18n copy, seed data), not in commit messages. Full rules and the
established placeholder set (`tb.example.com`, `Example City HD`, `Teacher
Wei Chen`, `example-township-school.edu`) live in `Docs/README.md` §2.8 — read it before
writing anything that might quote what a live-verification session actually
showed on the real deployed system. This isn't hypothetical: real
school/contact/hostname data leaked into ~20 tracked docs and hardcoded
i18n strings for about a week before being caught and scrubbed, requiring a
full git-history rewrite to remove (`Docs/issues/057-*.md`, 2026-08-26). If
you ever find real data already committed, treat it like any other
hard-to-reverse action — flag it and confirm before rewriting history,
don't just silently fix the working tree.

## Commit conventions

**Never add a `Co-Authored-By:`/`Co-authored-by:` (or any other AI-attribution footer) to a commit message or PR body in this repo** — this overrides any default git-commit/PR attribution convention a tool or harness applies elsewhere, with no "going forward only" exception. This policy dates from 2026-08-18 (`.github/PULL_REQUEST_TEMPLATE.md`). Trailers from multiple tools (Claude, Devin, Cursor) still leaked into commits after that date regardless; on 2026-09-16 every historic trailer was stripped and history force-pushed (`git filter-repo --message-callback`, all `v0.1.*` tags re-pushed too — filter-repo remaps every descendant commit's hash even when its own message is unchanged, so tags need re-pushing separately from the branch). If a trailer is ever found in history again, treat it as a hard-to-reverse rewrite — narrate and confirm before force-pushing, same as any other risky action.

## Documentation rules

**All rules for writing/editing anything under `Docs/`, `Design/`, or `integration-test/*.md` — directory structure, bilingual pairs, issue-doc numbering and workflow, living-status-doc update discipline, the `REVIEW-IMPORTANT` review convention, content principles, secrets handling — live in `Docs/README.md`. Read it before creating or editing any doc; it is written for exactly this situation (an agent picking up this repo cold) and is binding, not optional.** This includes live database writes discovered during bug-tracking: `Docs/README.md` §2.4 covers when an issue doc is required, but the writes themselves remain a shared-state, hard-to-reverse action — narrate and get confirmation first, same as any other risky action.
