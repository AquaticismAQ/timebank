# AGENTS.md

This is the generic, tool-agnostic guide for this repository. It applies to
**every AI coding agent** (Claude Code, Codex, Copilot, Devin, or any
other) and **every human collaborator** working here — not one tool in
particular. Read it in full before making changes. A Claude-Code-specific
file (`CLAUDE.md`) also exists at the repo root with the same project facts
in Claude Code's preferred format; the two should be kept factually
consistent when project state changes, but this file is the one every other
agent and every human collaborator should treat as authoritative. The
tool-specific folders (`.cursor/rules/`, `.devin/`, `.codex/`) deliberately
hold no rules of their own — they point here, so there is one copy to keep
current.

New to the project, or a student contributor without access to the shared
test server? Start with `CONTRIBUTING.md` → "Your First Code Change", which
runs the whole system on your own machine.

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
Docs/operations/         Deployment, configuration, maintenance mode, and the local Docker stack
Docs/TestAccounts.md     Login credentials for the seeded test accounts on the shared test server (untracked)
integration-test/        Seeds a realistic dataset onto the shared test server; STATUS.md logs live-testing bugs/fixes
e2e/                     Drives whole workflows through a real browser — a narrated live demo and a regression check (see e2e/README.md)
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
local mock.** There is no separate staging environment. The real hostname
and credentials live in the untracked `~/.timebank.env`, which every
deploy/seed script sources, and account passwords in the untracked
`Docs/TestAccounts.md` (this repo is meant to go public — `tb.example.com`
in these docs is a placeholder, never the real host; see
`Docs/operations/DeploymentEnv.md`). The server can sit behind a
maintenance gate that returns `503` to the SPA **and** `/api/*`; the seeder
and the e2e harness then need `TIMEBANK_MAINTENANCE_BYPASS_KEY` exported
(`Docs/operations/MaintenanceMode.md`). Before any live frontend test or browser verification pass,
check whether frontend source has changed since the last deploy (`git log`
against the deploy commit) and run `frontend/scripts/deploy.sh` first if so
— testing stale static output against current backend behavior (or vice
versa) produces false results. Log in with a real seeded account from
`Docs/TestAccounts.md` — staff keep readable names (`admin001`,
`teacher001`), but **student logins are 8 random digits**, as a real roster
import produces (`Design/*/10-StudentActivationFlow.md` §3, issue 105);
`studentNNN` is only the handle the docs use for a student. The server carries a full seeded dataset
(`integration-test/`, README + STATUS.md) — check `integration-test/
STATUS.md` before assuming a bug is new, and before assuming an empty
screen means missing data.

**Verify on the shared test server, not on the Docker stack**, when you have
access to it. For an ordinary change — anything needing no re-seed, schema
rebuild, or destructive experiment — deploy and verify live; the local
stack's data differs from the real one, so a pass there can be green for a
shape that never occurs live. Keep the Docker stack for work that would be
rude or impossible on a shared box. Contributors without server access use
the Docker stack for everything (`Docs/operations/DockerDevelopment.md`).

Backend commands run from `backend/` (single Maven module, package root
`cn.timebank`):

```bash
cd backend
mvn compile   # local compile check only
mvn test      # fast: MockMvc slices + ArchUnit, no external dependency
mvn verify    # adds *IT integration tests — needs TIMEBANK_DB_HOST/_USERNAME/_PASSWORD
              # for an account with CREATE/DROP on `timebank_it_%`
```

The build needs JDK 21. If the machine's default `java` is another version,
point `JAVA_HOME` at a JDK 21 for the command (on Homebrew:
`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test`). **No Docker in deployment or tests** — but there
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
- **Review approval is one transaction with five effects**: decision event, record status, ledger credit, student progress, notification (`ReviewDecisionTransaction`) — all or none, never split.
- **Project lifecycle**: `CLOSED` is time-derived (the registration window elapsed), not an action. `POST /projects/{id}/cancel` with a required reason archives from any status. Archiving a `COMPLETED` project is refused until every confirmed student's record is reviewed. A registration-open project with zero confirmed registrants auto-archives when registration closes. Read `Docs/optimization/02-PorjectOrganization.md` before changing this.
- **Service-record sources**: new records are `PROJECT` or `SELF`; `EXTERNAL` is rejected on create and survives only for editing/resubmitting historical records (`Docs/optimization/03-ServiceRecordFormOptimization.md`).
- **Roles**: each user is assigned exactly one `roleCode` through the API (the schema is many-to-many, the API is not). `student`, `admin` and `sysadmin` are protected; `sysadmin` is hidden from the standard Roles/Users UI. Custom roles can never receive the student-only permissions (`eventProposals.create`, `applications.create`, `serviceRecords.create`). Organizations are managed by admin/sysadmin, not teachers — but a student's proposal may name a new organization (`newOrganizationName`) without that permission.

## Backend conventions

Read a similar module before writing new code, and follow it.

- **Layout**: business modules use `web/` (controllers only), `application/` (public service interface + package-private `*ServiceImpl`, request/response records in `dto/`), `domain/` (entities, enums, state machines) and `infrastructure/` (repositories). `lifecycle` and `reporting` have no `domain/`; `common/` is shared utilities.
- **ArchUnit enforces** (`backend/src/test/java/cn/timebank/architecture/`): `@PreAuthorize` on every mapped `@RestController` method; nothing in `..web..` depends on an `@Entity`; no module reaches into another module's `domain`/`infrastructure` — cross-module calls go through application services.
- **Responses**: controllers return `ApiResponse<T>`; only file/CSV/PDF downloads return `ResponseEntity`.
- **Status changes** go through the module's `XxxStateMachine.canTransition(from, to, actor)` and dedicated action endpoints (`/submit`, `/publish`, `/cancel`), never a `PATCH` of `status`.
- **Multi-module atomic writes** live in `*Transaction` beans (`ReviewDecisionTransaction`, `ProposalApprovalTransaction`, `ImportCommitTransaction`, …), called through the Spring proxy — a `new`-ed or self-invoked `@Transactional` method is not transactional.
- **Optimistic locking**: editable entities extend `VersionedEntity`; check with `ConcurrencyControl.checkVersion()` (both in `cn.timebank.common.concurrency`). The client sends the version in the request body, never `If-Match`.
- **Idempotency**: `IdempotencyService.execute()` wraps record submission, review/proposal/case decisions, account adjustments and reversals, and import commit.
- **Data scope**: `DataScopeResolver` → `ScopeDescriptor` → `DataScopeSpecifications`. `admin`/`sysadmin` are school-wide by role code; every other role, teachers and custom roles included, is scoped by its `teacher_scope_assignment` rows (grade, class or project), else to self. Out of scope answers `404`, not `403`.
- **IDs**: `OpaqueIds.generate(prefix)` → `prefix_` + 26 random base32 characters, stored as `VARCHAR(32)`.
- **DTOs** are Java `record`s with Jakarta validation; entity→DTO mapping is hand-written (`toDto`). MapStruct is declared in `pom.xml` but unused — don't introduce it piecemeal. Entities use Lombok `@Getter`/`@Setter`.
- **Migrations**: Flyway `V{n}__*.sql` plus repeatable `R__*.sql`; `ddl-auto: validate`. Never edit an applied migration — its checksum is recorded on every database it ran on (`Docs/README.md` §2.10).
- **Infrastructure**: Caffeine cache, 60 s TTL (dictionaries, dashboards, rules); ShedLock on every scheduled job; Java 21 virtual threads.
- **Tests**: `FooTest` (Surefire, no database) vs `FooIT` (Failsafe) extending `AbstractIntegrationTest`, which gives each class its own `timebank_it_<random>` schema.

## API wire format

`Docs/integration/APIContract.md` is authoritative; these are the rules most often needed.

- Base path `/api/v1`, `camelCase` JSON. Success `{ data, page?, meta: { requestId } }`; error `{ error: { code, message, fieldErrors?, requestId } }`.
- Error codes: `400 INVALID_REQUEST`, `401 AUTHENTICATION_REQUIRED`, `403 FORBIDDEN`, `404 RESOURCE_NOT_FOUND`, `409 STATE_CONFLICT`, `413 FILE_TOO_LARGE`, `422 VALIDATION_FAILED`, `429 RATE_LIMITED`, `500 INTERNAL_ERROR`, `503 SERVICE_UNAVAILABLE`.
- Lists: `page[number]`, `page[size]` (capped at 100), `sort=-submittedAt,studentName`, `filter[field]=value`, `search=text`. Filter and sort on the server — a client-side filter over one page is a defect.
- Durations are integer minutes; amounts are integer centi-coins (`7550` = 75.5 coins). Times are ISO 8601 with offset.
- `Accept-Language: zh-CN` or `en` on every request.
- Session cookie is `HttpOnly`, `Secure`, `SameSite=Lax` (Spring Session JDBC); CSRF is cookie-to-header. Session length is per role (`session_timeout_minutes`: 480 staff, 720 student by default).

## Frontend write path

- Use the helpers, not hand-rolled equivalents: `newIdempotencyKey()` (once per user intent, reused on retry), `withVersion(body, version)`, `mapApiError(err)` (all in `api/http.ts`); `toBackendEnum`/`fromBackendEnum` (`lib/pagination.ts`); `localDateTimeToISO`/`isoToLocalDateTime` (`lib/datetime.ts`).
- Send writable fields only — never status, computed values or display names.
- No optimistic UI on ledger operations: show what the server returned.
- Wire `fieldErrors` to the form's fields; never render a raw error object.
- Every user-visible string goes in both `frontend/src/i18n/en.json` and `zh.json`.

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
  by editing git config. The committer must always equal the author —
  including after a history rewrite (`git filter-repo`, rebase, amend),
  which otherwise records whoever ran it as committer. This is a standing
  project convention until explicitly changed.
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
