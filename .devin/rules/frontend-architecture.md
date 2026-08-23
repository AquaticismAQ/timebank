---
description: Frontend architecture patterns and the mock-to-real integration path
trigger: glob
globs: frontend/**/*.{ts,tsx}
---

# Frontend Architecture

React 18 + Vite 6 + Tailwind CSS v4 + shadcn/Radix UI. Path alias `@` → `frontend/src`.

## Deployment

Frontend deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`:
- Builds locally (`pnpm build`) → rsyncs `dist/` → nginx serves SPA + proxies `/api/*`
- `.env.production` — intentionally empty of API toggles (same-origin in production)
- Before any live test, check if source changed since last deploy and re-run `deploy.sh` if so

## Data Flow

All data goes through `frontend/src/app/api/client.ts` which exports a single `api` object with real-only HTTP methods. Mock mode was removed entirely (2026-08-07): no `mock/` directory, no `config.ts`, no `isRealDomain`, no `VITE_API_REAL_DOMAINS`.

### File layout (post-I3)

```
api/client.ts      — api.* facade, real HTTP calls only, DTO mappers
api/http.ts        — fetch transport, ApiError, mapApiError, idempotency/version helpers
api/types.ts       — authoritative payload types
api/useApi.ts      — usePagedApi hook (primary), useApi (simple)
lib/pagination.ts  — ListParams, toBackendEnum/fromBackendEnum
lib/format.ts      — formatCoins, minutesToHours
lib/language.ts    — consolidated language switching (i18n + PATCH /me/profile + localStorage)
lib/datetime.ts    — localDateTimeToISO, isoToLocalDateTime (timezone-safe conversions)
```

### Integration Status (2026-08-19)

- **I0 `✅`:** types lifted, HTTP transport, envelope/error handling, dev-proxy recipe
- **I1 `▶`:** all 8 read domains swapped (18 methods), shared `usePagedApi`/`PageControls`/`ListStateBanner`
- **I2 `✅`:** all mutations wired, E2E verified against real backend
- **I3 `▶`:** I3.1-I3.6 done, acceptance scenarios 11/12 (only #12 bilingual/mobile sweep remaining)

### Key patterns

- **Enum mapping:** `toBackendEnum('underReview')` → `'UNDER_REVIEW'` (outbound), `fromBackendEnum` (inbound)
- **DTO mappers:** `fromServiceRecordDto`, `fromReviewQueueItemDto`, `fromAccountDto`, etc.
- **Version control:** `withVersion(body, ver)` — merges into body, NOT `If-Match` header
- **Idempotency:** `newIdempotencyKey()` — `crypto.randomUUID()`, generate once per intent
- **Edit vs copy mode:** `openForm(item, 'edit'|'copy')` pattern — distinguishes update from duplicate
- **Async pre-fill:** `openResubmit(rec)` fetches full record via `.get(id)` before pre-filling form
- **Directory search:** `api.students.directory(search, excludeId)` / `api.users.directory(role, search)` for pickers

## Component Organization

```
components/auth/       LoginPage
components/layout/     AppShell, StudentLayout, AdminLayout (shell/nav)
components/student/    StudentHome, ProjectCenter, ServiceRecords, EventProposals, ...
components/admin/      AdminDashboard, AuditCenter, ProjectManagement, ProjectRegistrants,
                       ProposalReview, CaseReview, AccountManagement, UserManagement,
                       OrganizationManagement, RoleManagement, ...
components/shared/     EvidenceLink (short-lived URL viewer), SearchPicker (Command+Popover)
components/ui/         shadcn/Radix primitives — treat as vendored
```

## Routing

`/student/*` → `RequireAuth` → `StudentLayout`
`/admin/*` → `RequireAuth` → `AdminLayout`

Every user holds exactly one role, so exactly one workspace. The dual-workspace/multi-role architecture and `/workspace` picker were retired 2026-08-19. `RequireAuth` sends unauthenticated users to `/login` and a workspace mismatch straight to the user's own portal home. `RoleManagement.tsx` owns the configurable Roles and Role Templates tab; do not reintroduce a frontend role enum or workspace picker.

Auth: real session cookies (Spring Session JDBC), CSRF double-submit. `AuthProvider.login()` syncs `preferredLanguage` from server on login.

## Write Path Rules (established in I2, still authoritative)

1. **Real-only in `client.ts`** — no mock branches, no dual-branch fallback
2. **Action endpoints**, never `PATCH {status}` — `POST .../submit`, `POST .../publish`
3. **`Idempotency-Key` via `newIdempotencyKey()`** — generate once per intent (useRef), reuse on retry
4. **`withVersion(body, version)`** on all updates — backend reads version from body, not `If-Match`
5. **`toBackendEnum()`** on enum fields in outbound request bodies
6. **No optimistic UI on ledger operations** — show server response, not local prediction
7. **`mapApiError(err)`** to classify errors → wire `fieldErrors` to form fields, `conflict` to refresh prompt
8. **Request bodies: writable fields only** — never send status, computed values, display names
9. **Never render raw error objects** — student-facing phrasing in both languages
10. **Timezone-safe date conversions** — use `localDateTimeToISO()` / `isoToLocalDateTime()`, never raw string concat

## Styling

- **Mobile-first** — build mobile layout first, enhance for tablet/desktop
- Tailwind v4 config in CSS (no `tailwind.config.js`)
- Theme tokens in `frontend/src/styles/theme.css` / `globals.css`
