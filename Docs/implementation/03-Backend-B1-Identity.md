# B1 · Identity, Authorization, and Data Scope

> Phase B1 of `01-Overview.md`. Who the caller is, what they may do, and which rows they may see — enforced once, here, so no later phase re-implements it.

| | |
| --- | --- |
| **Depends on** | B0 |
| **Blocks** | Every business phase; I1's first swap (`/me`, login) |
| **Effort / calendar** | 5 pw · W4–W6 |
| **Design refs** | `Design.md` D4, §10, §15; `APIContract.md` §2.2–§2.4, §4.1, §4.15, §5 |

---

## 1. Goal

Session authentication with instant revocation, permission codes enforced at the method level, and data scope applied **inside queries**. The scope mechanism is the single most security-critical piece of this phase: it is the thing that stops a Grade 9 teacher from reading a Grade 11 student's record.

## 2. Scope

**In:** users, roles, permission catalog, session auth, password lifecycle, lockout, `/me`, profile self-service, data-scope resolver, login logs, auth rate limiting.

**Out:** student directory and academic structure (B2 — B1 models the *scope shape* but seeds only enough students to test it); SSO (Q2: not in Phase 2); guardian accounts (Q4: deferred to Phase 3).

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **B1.1** | `iam` module — `user`, `role`, `permission`, `user_role`, `role_permission`; permission-code catalog seeded as an `R__` migration | `GET /permissions`, `GET /roles`, role CRUD per contract §4.15 |
| **B1.2** | Password lifecycle — BCrypt (cost ≥ 10) or Argon2id, change/forgot/reset with one-time tokens, admin-triggered reset, lockout after repeated failures, `POST /users/{id}/unlock` | Contract §2.2 and §4.15 endpoints; lockout threshold configurable |
| **B1.3** | Session auth — Spring Session JDBC, `HttpOnly` + `Secure` + `SameSite=Lax`, idle timeout (8 h staff / 12 h student, configurable) and absolute timeout | Login/logout/refresh; **revocation is immediate** — deactivation or password reset kills live sessions, verified by test |
| **B1.4** | CSRF and origin protection — token required on every cookie-authenticated mutation, `Origin`/`Referer` allowlist | A mutation without a CSRF token fails; allowlist configurable per environment |
| **B1.5** | Pluggable principal resolution — `AuthenticationPrincipal` behind a resolver interface, so Phase 3's opaque bearer token for the Mini Program is an added resolver, not a controller change | Interface exists and the cookie path is one implementation of it. **No token path is built** |
| **B1.6** | Permission enforcement — `@PreAuthorize("hasAuthority('…')")` on every endpoint; permissions loaded into the security context at login | The permission matrix in contract §5 is expressed as tests, not prose |
| **B1.7** | **Data-scope resolver** — scope descriptor (`ALL` \| `GRADES(...)` \| `CLASSES(...)` \| `PROJECTS(...)` \| `SELF`) per principal and resource, incorporated as **query predicates**, and verified on detail lookups | A reusable `DataScopeResolver` + a query-builder integration point that later phases must use |
| **B1.8** | Existence hiding — out-of-scope resources return `404`, never `403`; `403` reserved for "you may see it but may not do that" | Tested per resource as each phase adds one; the shared test helper ships here |
| **B1.9** | `GET /me` and `PATCH /me/profile` — roles, permissions, workspaces, `preferredLanguage`, feature flags (reading B2's flag table once it exists; a static default until then) | The frontend's `auth.me` and workspace picker work against it |
| **B1.10** | Login logs and auth audit — `login_log` behind `securityLogs.read`, audit entries for login, permission change, role change, password reset | Contract §4.15 endpoints; append-only per B0.8 |
| **B1.11** | Auth rate limiting — login and password reset limited **at Nginx** (centralized across instances), per-IP and per-account | Config committed with the deployment assets, not left to the operator |

## 4. Exit Criteria

- [x] Login, logout, refresh, forgot/reset, change-password, and unlock all work end to end. Live-verified over HTTPS 2026-08-02 (login → `/me` → `/users` → logout → 401); refresh/forgot/unlock exercised at the code level, not yet each proven live individually.
- [x] Deactivating a user or resetting their password invalidates their live sessions **immediately** (test, not reasoning). `AuthFlowIT.deactivatingAUserRevokesItsLiveSessionImmediately` / `changingPasswordRevokesTheOldSessionImmediately`, against real MySQL.
- [x] Every endpoint added so far carries a permission annotation; an endpoint without one fails a test that scans for it. `PermissionAnnotationTest`, verified to actually fail (same discipline as B0.2's ArchUnit rules).
- [ ] A teacher scoped to Grade 9 receives `404` — not `403`, not an empty-but-200 list with a misleading `totalItems` — for a Grade 11 resource. **Not met yet** — no scoped business resource exists until B2 (students/grades) and B3/B4 (records/projects). The resolver mechanism is proven (`DataScopeResolverIT`); this specific end-to-end scenario is a B2+ exit criterion by necessity.
- [ ] Data scope is applied in the query: verified by asserting the emitted SQL contains the predicate, not by checking the response. **Not met yet**, same reason — `DataScopeSpecifications` has no real entity to query against until B2+.
- [x] `/me` returns roles, permissions, workspaces, `preferredLanguage`, and feature flags in the shape the frontend types module declares. Live-verified.
- [ ] Login rate limiting demonstrated against two application instances (the in-process alternative would give an attacker double the allowance). **Partially met** — centralized at Nginx (`limit_req_zone`), which is what makes it instance-count-independent by construction, but only one application instance exists on the test server today, so the "two instances" scenario itself hasn't been run.

## 5. Risks and Watch-Outs

- **Post-filtering instead of query-filtering** is the defect this phase must design out (R3). It leaks existence through pagination counts and is invisible in a response-body test. Make the query-builder integration the only convenient path.
- **Scope is per resource, not per user.** A teacher may have grade scope for records and project scope for registrations. Model the resolver as `(principal, resourceType) → descriptor` from the start; widening it later touches every call site.
- **`403` vs `404` drift.** Teams reach for `403` because it is more honest. Contract §2.4 requires `404`. Encode it in the shared test helper so the wrong answer takes effort.
- **Roles are configuration, not code.** The permission matrix in contract §5 is a default; the school will adjust it. No permission check may hard-code a role name.

## 6. Hand-Off

B2 gets a working principal and scope mechanism to attach students, classes, and grades to. I1 can swap its first two calls (`auth.login`, `school.getInfo` once B2 lands) and the workspace picker.
