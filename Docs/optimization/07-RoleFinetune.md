# Role & Permission Management — Clarification & Implementation Plan

## 0. Scope and how to read this

The original draft of this file was a five-line sketch, written before checking the actual implementation — it assumed a fixed `Admin`/`Teacher`/`Student` role system with no role-management UI at all. Neither is true: the backend already has a fully data-driven `Role`/`Permission` model with working CRUD (list/create/update, no delete) behind `/roles` and `/permissions`, seeded with **four** roles (`student`, `teacher`, `admin`, `sysadmin`) and ~50 fine-grained permission codes — none of it consumed by the frontend, which still hardcodes a 3-value role enum and has zero UI for role/permission management.

This rewrite follows the `01`–`06` convention: **Finding** (verified against actual code, `file:line`, no decision needed), **Settled** (an implementation detail decided directly, not a product fork), **Decided** (a genuine fork — options, comparison, recommendation — resolved below with your 2026-08-19 answers, reasoning kept so a later session can see why).

**Scope grew substantially past the original draft during this pass.** Two of your answers (§2.1, §2.5) turned out to require more than "add a Roles tab": making custom roles actually see school-wide data requires touching a data-scope check repeated in ~14 backend files, and moving to one role per user requires retiring the already-shipped dual-workspace picker and deleting the `both001`-style test account that depends on it. Both are captured explicitly below with their full consequences, not folded in quietly — read §2.1 and §2.5 carefully before implementation starts, since they're the two decisions with the widest blast radius.

**2026-08-19 follow-up:** §2.2 revised — `teacher` dropped from the protected-role list (it becomes a regular, deletable role once unassigned), and `sysadmin` is now explicitly hidden from the new Roles/Users UI rather than just under-supported, with its own management flow deferred to a future round. §6 is new: the full permission catalog, grouped by resource, marked with what's assignable to a role today, for your review.

**2026-08-19 follow-up #2 (after Phases 1-6 deployed and live-verified):** three further refinements, captured in §2.6 — protected roles (`student`/`admin`/`sysadmin`) are now fully locked (not just undeletable), grade assignment is now required for any role other than `admin`/`student`/`sysadmin` rather than literally just `teacher`, and the Users tab's role picker is a dropdown rather than a button row. These supersede specific sentences in §2.1/§2.2 — the superseded sentences are left in place with a note rather than deleted, so the reasoning trail stays intact.

**2026-08-19 follow-up #3 — role templates (§2.8):** new feature, not a refinement — a template is a reusable, named permission preset that pre-fills a new role's picker, so an admin doesn't have to re-tick the same ~10-20 boxes for every similar staff role. Seeded with one 'Teacher' template matching the live `teacher` role's grants. Two genuine forks were asked and resolved (simplified name+permissions-only fields; applying a template replaces rather than merges the current selection); one further restriction was added mid-implementation — template *management* (create/update/delete) is `admin`/`sysadmin`-only by role code, narrower than the `roles.manage` permission that gates ordinary role management.

## 1. What already exists (Finding)

### 1.1 Backend — Role/Permission are already fully data-driven, mostly built

- `Role` (`backend/src/main/java/cn/timebank/iam/domain/Role.java:21-26`) is a real entity — code, name, `sessionTimeoutMinutes`, a `Set<Permission>` — with its own Javadoc stating the seeded four "are data, not a fixed vocabulary the code depends on." (§1.3 below shows that promise isn't fully kept yet.)
- `Permission` (`.../domain/Permission.java`) — id/code/description, ~50 rows seeded in `backend/src/main/resources/db/migration/R__seed_permissions_and_roles.sql:8-60`.
- `RoleController`/`RoleService`/`RoleServiceImpl` (`.../iam/web/RoleController.java`, `.../iam/application/RoleService{,Impl}.java`) already implement `GET /roles`, `GET /permissions` (both `roles.read`), `POST /roles`, `PATCH /roles/{id}` (both `roles.manage`) — full create/update, including reassigning a role's permission set and its session timeout. **No delete endpoint exists** (§1.4).
- `User` ↔ `Role` is `@ManyToMany` (`.../iam/domain/User.java:94-98`) — today's schema already supports a user holding multiple roles (see §1.5).
- `UserController.create`/`.update` (`.../iam/web/UserController.java:82-116`) already accept an arbitrary `roleCodes` set — the backend users API isn't limited to the three hardcoded values the frontend uses.

### 1.2 Frontend — hardcoded 3-role enum, zero role-management UI (the real gap)

- `SystemAdmin.tsx:167,193` hardcodes `type UserRole = 'admin' | 'teacher' | 'student'` and `const ALL_ROLES: UserRole[] = ['admin', 'teacher', 'student']` — the seeded 4th role, `sysadmin`, **cannot be created or edited from this UI at all**; every `sysadmin` account (including the seeded test ones) exists only because someone ran SQL directly.
- No `api.roles` / `api.permissions` methods exist anywhere in `frontend/src/app/api/client.ts` — the backend endpoints in §1.1 are entirely unconsumed.
- `frontend/src/i18n/en.json:1652-1656` / `zh.json` (`labels.roles`) only has `admin`/`teacher`/`student` keys — no `sysadmin`, and no mechanism at all for a server-defined role name once roles become configurable (a role's display name has to come from the API, not an i18n key).
- Handling of the 4th role is already inconsistent *before* any new work starts: `client.ts:124`'s `roleMap` collapses `sysadmin` → frontend `Role` value `'admin'` for one display path, while `api/types.ts:16` separately declares `Role = 'student' | 'teacher' | 'admin' | 'sysadmin'` as if it were a first-class value elsewhere.
- `AdminLayout`'s nav item list (`frontend/src/app/components/layout/AdminLayout.tsx:20-29`) has **no permission gating** — every admin-workspace user sees all nine nav entries (Dashboard, Audit Center, ... System Admin, Profile) regardless of which permissions their role grants; each page enforces its own access at the component level instead (e.g. `SystemAdmin.tsx`'s `canManageUsers`). Not new breakage from this doc, but a narrowly-scoped custom role (see §2.1) will make a dead nav link far more visible than it is today with only four fixed high-trust roles. Flagged as a Finding; not fixed in this round — see §3.

### 1.3 The load-bearing Finding: data-scope "see everything" checks are hardcoded to literal role codes, not permissions

`DataScopeResolver` (`backend/src/main/java/cn/timebank/security/application/DataScopeResolver.java:6-25`) is a shared, generic scope resolver — but its own Javadoc says the `grantAll` boolean passed in "typically [comes] from a permission check already made via `@PreAuthorize`... never by inferring it from a role or permission name itself." **The actual call sites don't follow that.** Every one of the following computes `grantAll` by checking literal role codes:

```java
boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
```

— repeated near-verbatim in `ServiceRecordServiceImpl.java:673`, `DashboardServiceImpl.java:138`, `FileServiceImpl.java:223`, `StudentServiceImpl.java:248`, `ProjectServiceImpl.java:476-477`, `ProposalServiceImpl.java:412`, `CaseServiceImpl.java:267`, `ReviewServiceImpl.java:94`, `AccountServiceImpl.java:241`, `ImportServiceImpl.java:184`, `ExportServiceImpl.java:91`, plus `EnrollmentServiceImpl.java:200-201` (permission-OR-role-code variant).

**Consequence:** before this round, a brand-new custom role — no matter which permissions it's granted through a future Roles tab — could never see school-wide data. It would fall through to `DataScopeResolver`'s non-`grantAll` branch: `Self` scope (own records only), unless it also happened to hold `teacher_scope_assignment` rows, a mechanism currently wired only to the literal `teacher` role (`UserController.java:45,109-113`, `TeacherScopeAssignmentService`). §2.1 resolves this.

Beyond data-scope, a few more places treat the four seeded codes as a closed vocabulary, not configuration:
- `UserController.requireRoleFields` (`.../iam/web/UserController.java:45-47,129-139`) — `TEACHER_ROLE`/`ADMIN_ROLE`/`STUDENT_ROLE` constants gate which fields are required.
- `ProjectController.isAdmin` (`.../project/web/ProjectController.java:125-127`) — owner-reassignment gate checks literal `"admin"` only, **not** `"sysadmin"`, already an inconsistency today (§2.3 fixes this as a side effect of the admin/sysadmin merge).
- `ProjectServiceImpl.java:380`, `ProposalServiceImpl.java:517` — "is this user an eligible proposal supervisor" checks `hasActiveRole(id, "teacher")` literally.
- `PurgeQueryRepository.isStudentOnlyAccount` (`.../lifecycle/infrastructure/PurgeQueryRepository.java:145-151`) — retention-purge safety check, literal `"student"`.
- `UserServiceImpl.deriveWorkspaces` (`.../iam/application/UserServiceImpl.java:322-330`) — `student` role code → `student` workspace, any other role → `admin` workspace. This one is already correctly generic (any non-`student` code routes to the admin workspace, custom roles included) — no fix needed here.

### 1.4 No role deletion — not implemented, not contracted (Finding)

`RoleController`/`RoleService` only expose list/create/update. `Docs/integration/APIContract.md:684-687` (§4.15) documents only `GET/POST /roles` and `PATCH /roles/{id}` — no `DELETE`. This is new API surface, not a gap-fill.

### 1.5 Multi-role accounts are an already-shipped feature, not an oversight (Finding — motivated the cardinality question in §2.5)

`User`↔`Role` being many-to-many isn't unused: `integration-test/mock-data.ts:72` seeds `both001` with `roleCodes: ['student', 'teacher']` specifically to exercise the **workspace picker** — `frontend/src/app/components/auth/WorkspaceSelect.tsx`, `App.tsx:90-91,153,170-172,223-227` (`RequireAuth`'s `user.workspaces.length > 1` branch, `/workspace` route), and `AdminLayout`'s "switch to student" button (`switchTarget={{ workspace: 'student', ... }}`). `CLAUDE.md`'s architecture section documents this as intentional: "A user can hold both `student` and `admin` workspace access." §2.5 retires this deliberately, with the consequences spelled out.

## 2. Decisions (resolved 2026-08-19)

### 2.1 Data-scope: how do custom roles get school-wide visibility?

**Decided: any role other than the protected `student` role gets full school-wide (`ScopeDescriptor.All`) visibility, unconditionally — not an opt-in permission.**

Your reasoning: manageable/custom roles are meant to represent staff (admin-/teacher-like) accounts that never do record submission, and staff should always be able to see school-wide data regardless. The simplest rule that matches that is to stop special-casing `admin`/`sysadmin` and instead special-case the one role that *shouldn't* get it:

```java
boolean grantAll = !principal.roleCodes().contains("student"); // was: contains("admin") || contains("sysadmin")
```

This is the smaller of the two workable designs I'd considered (the other being a new opt-in `data.scope.all` permission) — no new permission code, no seed-data change needed for existing roles, and it composes cleanly with §2.5's one-role-per-user decision (a user's single role is either `student` or it isn't).

**Named consequence — this changes `teacher`'s current behavior.** Today `teacher` is deliberately grade-scoped (`TeacherScopeAssignmentService`, `scopeGradeIds` required at account creation) so a teacher only sees their assigned grades' records. Under this rule `teacher` becomes `grantAll = true` like `admin`/`sysadmin`, so **`TeacherScopeAssignmentService`'s grade-scope assignment stops restricting anything** — `DataScopeResolverImpl.resolve` (`.../security/application/DataScopeResolverImpl.java:21-24`) short-circuits to `All` before it ever looks at scope-assignment rows. I checked whether `TeacherScopeAssignment` is used anywhere else (assignment routing for proposal/review reviewers, etc.) — it isn't; `TeacherScopeAssignmentRepository`/`Service` are only referenced from `DataScopeResolverImpl` and the IAM web layer (`MeController`, `UserController`, `AuthController`). So there's no second consumer silently relying on it.

**Settled:** keep the "select at least one grade" requirement in the teacher create/edit form as-is (`UserController.requireRoleFields`, `GradePicker` in `UserModal`) — it's now purely organizational metadata ("which grades does this teacher primarily work with"), not a visibility restriction. Removing the requirement is unrelated scope creep; not doing it this round. **(Superseded 2026-08-19 by §2.6.2 — the requirement is generalized from literal `teacher` to every role except `admin`/`student`/`sysadmin`; still organizational metadata, not a visibility restriction, per this paragraph's original reasoning.)**

### 2.2 Protected roles (revised 2026-08-19)

**Decided: only `student`, `admin`, and `sysadmin` stay protected** — undeletable, code fixed. `teacher` is dropped from the protected list: it stays a seeded row (existing `teacher001`-style accounts are unaffected — the role isn't removed from the database, just un-flagged), but becomes an ordinary role from the Roles tab's point of view — renamable in effect via `name`, reassignable permissions (already true for every role), and **deletable once no user holds it** (§2.4's block-if-assigned rule still applies — this isn't "delete `teacher` out from under active accounts," it's "an admin who reorganizes staff roles can eventually retire it like any custom role"). Name, permission set, and session timeout remain editable for protected roles exactly as they are today (`RoleService.updateRole` already supports this — no change needed). New custom roles, and now `teacher`, are ordinary, fully-manageable rows. **(Superseded 2026-08-19 by §2.6.1 — protected roles are now fully locked, not just undeletable: no field, including name/permissions/timeout, can change via `PATCH /roles/{id}`.)**

**`sysadmin` is additionally hidden from the UI, not just protected.** It keeps its `is_protected = TRUE` flag (§1.3's `grantAll`/permission-merge logic in §2.3 still depends on the row existing and being undeletable) and keeps its full merged permission set (§2.3), but is explicitly out of scope for this round's UI: the Roles tab's role list and the Users tab's role picker both filter it out (`role.code !== 'sysadmin'`), so it can't be seen, assigned, or edited from the new screens. `sysadmin` accounts continue to exist and function exactly as today — created only via direct SQL, same as the seeded test account — with no regression and no new capability. A dedicated sysadmin management flow (if ever needed) is future work, not part of this round.

**Consequence — un-protecting `teacher` reopens part of §1.3's literal-code-dependency finding, addressed here rather than left as a landmine:**
- The data-visibility side is already moot: §2.1 makes every non-`student` role (`teacher` included) `grantAll = true`, so `TeacherScopeAssignmentService`'s grade-scope assignment was already reduced to organizational metadata, not a security boundary — deleting `teacher` later doesn't change any access-control outcome there.
- The one check that *does* still matter: `ProjectServiceImpl.java:380` / `ProposalServiceImpl.java:517`'s proposal-supervisor eligibility, `hasActiveRole(id, "teacher")`. **Settled:** generalize this to a permission check, `hasPermission("eventProposals.review")` — today held by exactly `teacher`, `admin`, and `sysadmin` (identical population, zero immediate behavior change), but now data-driven, so if `teacher` is ever renamed or deleted in favor of a differently-named custom role, supervisor eligibility keeps working as long as the replacement role holds `eventProposals.review`. (Superseded — §3 no longer lists this as "not changing.")
- `UserController.requireRoleFields`'s teacher-specific required-field rules (grade scope, `staffId`) stay keyed to the literal `"teacher"` code — **not** generalized. This is now pure create/edit-form UX (which optional fields a form shows), not a security check, and it naturally goes inert if `teacher` is ever deleted; a school that replaces `teacher` with a custom role gets that role's own (simpler) required-field set, with no special-cased grade picker. Not extending this is consistent with §3's stance on not generalizing `teacher`-specific behavior to hypothetical replacements.

**Settled implementation:** add `role.is_protected BOOLEAN NOT NULL DEFAULT FALSE` (migration `V20`), backfill `TRUE` for **`student`, `admin`, `sysadmin` only** — `teacher` stays `FALSE` (the column's own default). `RoleService.createRole` never sets it for new rows. `DELETE /roles/{id}` (§2.4) rejects with 409 if `isProtected`, before it even checks assignment count.

### 2.3 Admin/sysadmin merge and who gets the Roles tab

**Decided: make `admin`'s permission set equal to `sysadmin`'s** — union both roles' current grants and assign the union to *both* role rows, so after this round they're functionally identical (same `role_permission` rows), even though both codes/rows remain (per §2.2, both stay protected and distinct).

Concretely, today:
- `admin` has 44 of ~50 permissions — missing exactly `roles.manage`, `settings.manage`, `securityLogs.read` (the "sysadmin-exclusive" ones) plus `projects.close` (deliberately dead, see the seed file's own comment), and the three student-submission permissions (`eventProposals.create`, `applications.create`, `serviceRecords.create` — correctly excluded, not part of this merge).
- `sysadmin` has 23 permissions — read-only across nearly every business domain plus `users.manage`/`roles.manage`/`securityLogs.read`/`settings.manage`/`lifecycle.*`, but none of `admin`'s create/update/publish/decide/manage operational permissions (can't create a project, decide a review, adjust an account, manage rules, etc.).

**Settled migration (`V21`):** `INSERT IGNORE INTO role_permission` the missing rows both directions — `admin` gains `roles.manage`/`settings.manage`/`securityLogs.read`; `sysadmin` gains every operational permission `admin` has (excluding the three student-submission ones, which stay `student`-only per §2.3's ceiling below). `projects.close` stays excluded from both — it's retired.

**Consequence — this obsoletes the "escalation ceiling" question I'd originally raised.** If `admin` already holds every permission `sysadmin` does, there's no privilege `admin` could grant a new role that `admin` doesn't already hold themselves — no separate guard needed against `admin` "escalating" via the Roles tab. Roles-tab access stays exactly `roles.manage`-gated as today; it's just no longer sysadmin-only once the merge migration runs.

**What the "admin-adjacent" ceiling in the original draft actually meant, resolved:** re-reading your §2.1 answer ("customisable roles... doesn't have record submission and related features") against the permission catalog, the real restriction isn't about admin-vs-sysadmin at all — it's that **manageable roles should never be assignable the three student-submission permissions**, since those only make sense for the protected `student` role. **Settled:** `RoleService.createRole`/`updateRole` reject `permissionCodes` containing `eventProposals.create`, `applications.create`, or `serviceRecords.create` with a 422 ("reserved for the student role"), for every role — protected or custom.

**Small mechanical fix bundled in:** `ProjectController.isAdmin` (`ProjectController.java:125-127`) currently checks only literal `"admin"`, so a `sysadmin` account — despite now holding identical permissions — couldn't reassign a project owner. Extend the check to `roleCodes().contains("admin") || roleCodes().contains("sysadmin")` (or better: since §2.5 makes role cardinality 1, compare the principal's single role against the *protected* set) so the promise "admin and sysadmin are the same" actually holds everywhere, not just in the permission table.

### 2.4 Role deletion semantics

**Decided: block deletion (409) if the role is currently assigned to any user**, naming the affected count. Combined with §2.5 (exactly one role per user), this is simpler than the general many-to-many case: deleting a role just means "no user's single role is currently set to this one" — there's no partial-removal accounting to reason about (a user is never left with a smaller-but-nonempty role set, since they only ever had one). Reassignment (edit each affected user's role first) is a manual, explicit step before delete — no bulk-reassign tool in this round.

**Settled:** `DELETE /roles/{id}` (`roles.manage`) → 409 with the count if `isProtected` (§2.2) or if `SELECT COUNT(*) FROM user_role WHERE role_id = ?` > 0.

### 2.5 Role cardinality: exactly one role per user

**Decided: every user holds exactly one role, always — no exceptions.** This retires the multi-role/dual-workspace architecture described in §1.5 as a deliberate product change, not an oversight to route around. Concretely:

- **Frontend:** `WorkspaceSelect.tsx`, the `/workspace` route and its `RequireAuth` branch (`App.tsx:226`, `user.workspaces.length > 1`), and `AdminLayout`'s "switch to student" `switchTarget` button become permanently unreachable once no user can ever hold more than one workspace — remove them rather than leave dead code. `AuthProvider`'s `currentWorkspace`/`timebank-workspace` localStorage state collapses to "the one workspace this user's single role implies," derived at login, no picker ever shown.
- **Backend contract:** `roleCodes: Set<String>` → a single `roleCode: String` field, everywhere it appears — `CreateUserRequest`, `UpdateUserRequest`, `UserDto`, and the corresponding `Docs/integration/APIContract.md`/`_CN.md` §4.15 rows. Leaving the wire shape as a one-element set instead would be a half-migration (`Set<String> roleCodes` implying multiplicity that can never actually occur) — not doing that.
- **`User`↔`Role` mapping:** stays `@ManyToMany` at the schema level (no reason to force a `role_id` FK column onto `user` this round — the join table already models "zero or one" as a special case of "many," and this codebase doesn't add DB-level constraints for invariants the application layer already enforces elsewhere, e.g. status machines). **Settled:** enforce cardinality in `UserController` (reject create/update where the resolved role set's size ≠ 1), same layer `requireRoleFields` already lives in.
- **`UserModal`'s role picker** (`SystemAdmin.tsx:324-339`): multi-select checkbox row → single-select (radio-style, same visual treatment). `isAdminOnly`/`isTeacher && form.roles.includes(...)`-style dual-role conditionals throughout `UserModal` simplify to a single `form.role !== 'student'` check.
- **`User.sessionTimeoutMinutes`** (`User.java:122-130`, "the shortest idle timeout among the user's roles") — the `min()` over multiple roles becomes moot (always exactly one role), but leave the method's shape alone; it's correct and harmless for the single-element case, and rewriting it isn't required by this decision.

**Data migration (manual, not via automatic Flyway on deploy — CLAUDE.md's live-write-confirmation rule applies):**
1. On the shared server, `SELECT user_id, COUNT(*) FROM user_role GROUP BY user_id HAVING COUNT(*) > 1` to find every multi-role account, not just the known `both001` — confirm there isn't a second one nobody's tracked before assuming the list is just `both001`.
2. Per your answer, **delete** every such account outright (not collapse to one role) — matches `both001`'s literal purpose (workspace-picker test fixture) having no purpose left once the picker is retired. Follow the existing purge pattern for a full account delete (`user_role`, `password_reset_token`, `teacher_scope_assignment`, `user` rows — same shape as `PurgeQueryRepository`'s helper, run by hand here since this is a one-time cleanup, not the retention-purge feature).
3. This is a real live-database write against `tb.example.com`'s `timebank` schema — narrate it and get explicit confirmation before running it, same as every other live write this project does, and write it up (`integration-test/STATUS.md` at minimum; a `Docs/issues/0NN` entry if anything about it turns out to be a bug rather than planned cleanup) per `CLAUDE.md`'s bug-tracking convention.
4. Only after that cleanup does the `size() != 1` rejection in `UserController` go live — turning it on first would just turn the existing multi-role accounts' next unrelated edit into an error.

### 2.6 Follow-up (2026-08-19, after deployment): protected-role locking, grade-scope generalization, role dropdown

Raised after §2.1-§2.5 were already implemented, deployed, and live-verified — three refinements to the shipped behavior, not new scope.

#### 2.6.1 Protected roles are fully locked, not just undeletable

**Decided: `student`, `admin`, and `sysadmin` can no longer be edited at all** — not just undeletable (§2.2's original rule), but immutable: name, permission set, and session timeout are all fixed once a role is protected. `PATCH /roles/{id}` now rejects any change to a protected role with 409, checked in `RoleServiceImpl.updateRole` right after the role is fetched (same position as the student-only-permission check — before the concurrency-version compare, since this isn't a conflict worth surfacing as "stale version," it's never allowed regardless of version).

Reasoning: `admin` and `student` aren't just "a convenient starting permission bundle" — §2.1 wires structural behavior to their literal codes (`hasFullDataScope()`'s student exception, and now §2.6.2's grade-scope exception for both `admin` and `student`). Letting an operator freely rename them or strip their permissions via the Roles tab could silently break that wiring (e.g. an admin account that's had `users.manage` removed from the `admin` role can no longer create the account needed to fix it) — locking them entirely removes that failure mode. `sysadmin` is included for the same structural reason (also a system-spanning role) even though it's already unreachable via this round's UI (§2.2) — this only matters if a future round exposes it.

**Not extended to `teacher` or any custom role** — those stay fully editable (name/permissions/timeout) and deletable-once-unassigned exactly as §2.2 already decided; only the three protected codes lock.

**UX (`RoleManagement.tsx`):** editing a protected role now opens the same modal in a read-only view — name/timeout inputs and every permission checkbox are disabled, the two-step delete control was already hidden for protected roles, and the footer shows a single "Close" button instead of Cancel/Save. Search and the accordion stay interactive (browsing what a protected role holds is still useful) — only mutation is blocked. The banner text changed from "...but its name, permissions, and session timeout can still be changed" to "...cannot be deleted or edited."

#### 2.6.2 Grade assignment generalizes from literal `teacher` to "any role except admin/student/sysadmin"

**Decided: grade-scope assignment (`scopeGradeIds`, backed by `TeacherScopeAssignmentService`) is required at user-creation/edit time for every role except `admin`, `student`, and `sysadmin`** — not just the literal `teacher` code. Your framing: admin covers every grade by design (already true structurally via §2.1's `hasFullDataScope()`), a student account's grade comes from its linked roster row (not a manual picker), and every other role — `teacher` and any future custom staff role — should get the same grade-assignment field without needing a new per-role configuration flag. A fixed 3-code exclusion list is the simplest rule that achieves this, avoiding a `Role.requiresGradeScope` column or similar.

This directly supersedes the "Settled" note at the end of §2.1 and part of §2.2's consequence list (both marked superseded above, reasoning left in place) — the requirement was deliberately kept `teacher`-only at the time because generalizing it felt like unrelated scope creep; revisiting it now because the actual product need (multiple staff-type roles, not just `teacher`) has become concrete.

**Implementation:**
- `UserController.NO_GRADE_SCOPE_ROLES = {student, admin, sysadmin}`; `requireRoleFields` and the `teacherScopeAssignmentService.replaceGradeScope(...)` call sites in `create`/`update`/`withScopeGradeIds` all key off `needsGradeScope(roleCode) = !NO_GRADE_SCOPE_ROLES.contains(roleCode)` instead of the old literal `TEACHER_ROLE.equals(roleCode)`.
- `SystemAdmin.tsx`'s `UserModal`: `isTeacher` renamed to `needsGradeScope`, same exclusion set, gates the `GradePicker` block, the `errorGradeRequired` validation, and whether `scopeGradeIds` is included in the create/update request body (`handleCreate`/`handleEdit`).
- `sysadmin` is included in the exclusion set for backend correctness (a direct-SQL-created `sysadmin` account shouldn't suddenly be told it needs a grade), even though it's unreachable via this form's role dropdown (§2.2) — defensive, not reachable in practice today.
- An informational note (`admin.system.scopeGradesAdminNote`, already translated but unused since the single-role migration) is shown when `admin` is selected, explaining why no grade picker appears — restored rather than left silently blank.

#### 2.6.3 Users tab role picker is a dropdown, not a button row

**Decided:** `UserModal`'s role selector (`SystemAdmin.tsx`) changes from a `flex flex-wrap` row of buttons — one per role, laid out to fit three (`admin`/`teacher`/`student`) — to a `Select` dropdown (`components/ui/select.tsx`, an already-vendored shadcn/Radix primitive, previously unused anywhere in the app). A button row doesn't scale once the Roles tab (§2.1-§2.5) lets an admin create an arbitrary number of custom roles; a dropdown does. Same single-select semantics as before (§2.5) — this is a widget change, not a cardinality change.

### 2.8 Role templates (new feature, 2026-08-19 follow-up #3)

A role template is a reusable, named permission preset — never assigned to a user, only ever read to pre-fill a new role's permission picker at creation time. Motivation: several manageable roles tend to share most of their grants (e.g. "discipline officer" and "activities coordinator" might both start from something close to `teacher`'s set); re-ticking ~20 checkboxes from scratch every time is exactly the kind of friction a preset removes.

**2.8.1 Data model — a `role` row with a flag, not a parallel schema (per your steer: "think about some light impl... maybe a simple flag").** `role.is_template BOOLEAN` (migration `V22__role_templates.sql`), reusing `role_permission` for a template's grants. `code`/`session_timeout_minutes` stay on the row (both `NOT NULL`) but are meaningless placeholders for a template — never exposed over the wire. Every existing role-facing read/write path was audited and made template-blind despite the shared table:
- `RoleServiceImpl.listRoles()` now queries `findByIsTemplate(false)`; `updateRole`/`deleteRole` both filter `!role.isTemplate()` before acting, so hitting `PATCH`/`DELETE /roles/{templateId}` 404s instead of silently operating on a template through the wrong endpoint.
- `UserServiceImpl.resolveRole` uses a new `findByCodeAndIsTemplateFalse` query — a template's code can never be assigned to a user as their role, even if guessed directly (defense in depth; the id-generation prefix (`rtpl_...`) doesn't collide with any admin-chosen role code in practice, but the guard doesn't rely on that).
- Seeded 'Teacher' template (`role_template_teacher` / `template_teacher`) copies the live `teacher` role's 21 grants at seed time — a one-time starting point, not a live sync; editing `teacher` later doesn't change the template, and vice versa. Same two-tier V-migration/R-migration split as V20/V21 (§2.2/§2.3's pattern) for the fresh-schema-vs-live-schema reason documented in both files.

**2.8.2 Decided (asked, two genuine forks):**
- **Template fields: simplified name + permissions only**, not the full role shape (code/session-timeout/permissions). Your answer, with reasoning: neither field would ever be read for a template, so carrying them in the UI is dead weight.
- **Applying a template replaces the current selection, not merges it.** Your answer: predictable "start from this preset" semantics — picking a template (or a second, different template) always yields exactly that template's set, not an accumulating union. Implemented in `RoleFormModal.applyTemplate`, filtered defensively through the picker's own assignable-codes set.

**2.8.3 Mid-implementation addition: template management is `admin`/`sysadmin`-only, not just `roles.manage`-gated.** Raised after the data model and CRUD were already built: ordinary role management stays permission-based (any role holding `roles.manage` can create/edit/delete roles, per §2.1-§2.5), but curating the *template library* itself is narrower — only an account whose role code is literally `admin` or `sysadmin` may create, update, or delete a template, even if some future custom role were also granted `roles.manage`. Reads stay `roles.read`-gated like everything else, so any role that can create a role can still *use* a template as a preset — only the write path is restricted. Enforced in `RoleController.requireTemplateManager` (checks `TimeBankPrincipal.roleCodes()`, not `@PreAuthorize`, since Spring Security's authorities here are permission codes, not role codes — same reason `ProjectController.isAdmin`/`PurgeQueryRepository.isStudentOnlyAccount` are plain Java checks rather than SpEL). Frontend mirrors this only to hide buttons that would otherwise 403 (`SystemAdmin.tsx`'s `canManageTemplates = canManageRoles && (admin or sysadmin)`) — the server-side check is what's actually load-bearing.

**2.8.4 UI (`RoleManagement.tsx`):** two stacked cards in the Roles tab — Role Templates (top) and Roles (below), per your instruction. Template list: name + permission count + edit (no code/timeout columns, §2.8.2). The shared grouped/searchable permission picker (search, accordion, group-select-all, translated labels — §2.6.2's follow-up-#4 polish) was extracted into a standalone `PermissionPicker` component so both `RoleFormModal` and the new `RoleTemplateFormModal` use the identical widget instead of duplicating it. In `RoleFormModal`, **create mode only** (§2.7's explicit "only for role creation"), a `Select` dropdown renders inline with the picker's own search box (`searchRowExtra` prop) listing every existing template by name; picking one replaces the form's permission selection per §2.8.2.

### 2.9 Proposal Review: view vs. decide split, and Recommend Approval removal (2026-08-20 follow-up)

Raised directly against Proposal Review specifically, not a new general-purpose permission — two related changes.

**2.9.1 Recommend Approval removed entirely.** `ProposalDecision.RECOMMEND_APPROVAL` — a no-op decision that kept the proposal at `underReview` and logged an internal-only endorsement, with no student notification and no project generated — was a two-tier "recommend, then someone else approves" workflow layered on top of what was already a two-step process (proposal approval, then a separate project publish, §3.2). Decided this made the review workflow more complex than that two-step split needed, so it's gone: the enum value, its state-machine branch and notification no-op in `ProposalApprovalTransaction`, the frontend `ThumbsUp` button, the `Decision` type value, and the `admin.proposals.recommendApproval` i18n key. Confirmed zero live `event_proposal_review` rows ever recorded this decision before removing it, so no data migration was needed — a future analogous removal that finds non-zero historical rows would need one (the enum column is `@Enumerated(STRING)`; deleting a constant that's actually in use breaks deserializing those rows).

**2.9.2 Viewing a proposal is now role-based, not permission-based.** Before this: `ProposalReview.tsx`'s whole page, and every backing `GET` endpoint (list/get/revisions/timeline/reviews), required `eventProposals.read` (`ProposalController`'s `@PreAuthorize("hasAuthority('eventProposals.read')")` on all five) — separately, `eventProposals.review` gated the decision buttons and the decide endpoint. In the four seeded roles, `.read` and `.review` happened to always travel together (`teacher`/`admin`/`sysadmin` each hold both), so this distinction had never actually been exercised — there was no existing account that could view the queue without also being able to decide it.

**Decided:** viewing (list, detail, revisions, timeline, decision-history) no longer requires holding `eventProposals.read` at all — every one of those endpoints is now `isAuthenticated()`-gated, relying on `DataScopeResolver`'s existing §2.1 rule (`grantAll` = not the protected `student` role) to naturally show a student only their own proposals and any non-student role the full queue. Only *deciding* (`POST .../reviews`, gated on `eventProposals.review`) and *assigning* (`POST .../assign`, `GET /event-proposal-queue/summary`, gated on `.review`/`.assign`) stay permission-gated. Concretely: any account whose role lacks `eventProposals.review` can now still open Proposal Review, browse the queue, and open a proposal's detail in the same `DetailWorkbench` — just with the decision buttons in the footer omitted (`canReview = hasPermission('eventProposals.review')`, unchanged; only the page-level gate that used to block rendering entirely was removed).

**Consequence:** `eventProposals.read` no longer gates anything in this controller — it remains in the permission catalog (§6) for contract completeness and because it's still a real, assignable permission code (nothing currently reads it, but removing it from the catalog entirely wasn't asked for and isn't needed), but a custom role missing it is no longer blocked from viewing proposals. This is a deliberate simplification for this resource specifically — **not** extended to Case Review or Audit Center, which keep their existing `cases.read`/`reviews.read` gates unchanged; if the same "anyone non-student can view, only permission-holders can act" pattern is wanted there too, that's a separate decision, not implied by this one.

## 3. What's explicitly *not* changing this round (Finding, logged so it isn't re-discovered as a surprise)

- **Proposal-supervisor eligibility is *not* opened up to arbitrary custom roles**, even though it's now permission-based rather than `teacher`-code-based (§2.2's revision) — a new custom role only becomes supervisor-eligible if it's explicitly granted `eventProposals.review`; it doesn't happen automatically just by being non-student.
- **`PurgeQueryRepository.isStudentOnlyAccount`** stays keyed to literal `"student"` — correct and unaffected, since `student` remains protected (§2.2).
- **`AdminLayout` nav items stay permission-ungated** (§1.2) — a low-privilege custom role will see nav entries it can't act on. Worth fixing, but it's a pre-existing gap this doc's changes make more visible, not something this doc's decisions require. Log as a candidate for a future pass, not built here.
- **No bulk role-reassignment tool.** §2.4's block-on-delete means reassigning every affected user happens one edit at a time through the existing Users tab. Fine at current account volumes; revisit if a school-scale seeder (`Docs/implementation/00-Status.md`'s noted future 5,000-student seeder) ever makes this painful.

## 4. Implementation plan

**Phase 1 — Backend schema & permission data**
1. `V20__role_protected_flag.sql`: add `role.is_protected`, backfill `TRUE` for **`student`/`admin`/`sysadmin` only** — `teacher` stays unprotected (§2.2, revised 2026-08-19).
2. `V21__merge_admin_sysadmin_permissions.sql`: union `admin`/`sysadmin` grants per §2.3 (excluding `projects.close` and the three student-submission permissions).

**Phase 2 — Backend application code**
1. Replace the ~11 `grantAll = roleCodes().contains("admin") || roleCodes().contains("sysadmin")` call sites (§1.3's list) with a single shared check (e.g. a default method on `TimeBankPrincipal`, `isNotStudent()`/similar) implementing §2.1's rule — don't re-inline the literal in each file again.
2. Fix `EnrollmentServiceImpl.java:200-201`'s permission-OR-role-code variant the same way.
3. Fix `ProjectController.isAdmin` (§2.3) to treat `admin`/`sysadmin` alike.
4. `RoleService.createRole`/`updateRole`: reject the three student-submission permission codes (§2.3); `RoleController.deleteRole` (new): 409 on protected or assigned (§2.2, §2.4).
5. `UserController`: `roleCodes` set → singular `roleCode` (§2.5) across `CreateUserRequest`/`UpdateUserRequest`/`UserDto`/`requireRoleFields`; enforce exactly-one at this layer.
6. `ProjectServiceImpl.java:380` / `ProposalServiceImpl.java:517`: generalize proposal-supervisor eligibility from `hasActiveRole(id, "teacher")` to `hasPermission("eventProposals.review")` (§2.2's revision) — same population today, no behavior change, just no longer keyed to a code that can now be deleted.

**Phase 3 — Live data migration (manual, confirmed, see §2.5's four steps)**
1. Audit `user_role` for multi-role accounts on `tb.example.com`.
2. Confirm the list with you before deleting anything.
3. Delete them; log in `integration-test/STATUS.md`.
4. Only then land Phase 2 step 5's cardinality rejection.

**Phase 4 — Frontend**
1. Add `api.roles.{list,create,update,delete}` / `api.permissions.list` to `client.ts`.
2. New `RoleManagement.tsx` (new `Tab` value `'roles'` in `SystemAdmin.tsx`), gated on `roles.read` for visibility / `roles.manage` for create-edit-delete actions (same pattern as `canManageUsers`) — permission picker grouped by resource prefix (the part before the first `.` in each code), given ~50 flat codes. List and picker both filter out `code === 'sysadmin'` (§2.2, revised) — `sysadmin` stays invisible in this screen.
3. `SystemAdmin.tsx`/`UserModal`: replace hardcoded `UserRole`/`ALL_ROLES`/`ROLE_LABELS` with data from `GET /roles` (same `sysadmin` filter as above applied to the Users tab's role picker); role picker becomes single-select (§2.5); drop the now-unreachable multi-role conditionals.
4. Remove `WorkspaceSelect.tsx`, the `/workspace` route, `RequireAuth`'s multi-workspace branch, `AdminLayout`'s "switch to student" button, and the `client.ts:124` `roleMap`/`types.ts` `Role` inconsistency (§1.2) — roles/labels come from the server now, not a fixed frontend enum.
5. Add `sysadmin` (and any future custom role names) to wherever role display currently reads `labels.roles.*` — since role names are now server data, this becomes "render `role.name` from the API," not an i18n lookup, for anything beyond the four seeded ones.

**Phase 4 detail — permission-assignment UI/UX (Settled 2026-08-19)**

48 flat permission codes across ~19 resource groups is too many for `GradePicker.tsx`'s flat button-grid pattern (built for ~12 grades) — needs grouping and collapse, not a bigger grid. Built entirely from primitives already vendored in `components/ui/` (`accordion.tsx`, `checkbox.tsx`, `badge.tsx`) — no new UI primitives needed.

*Roles tab list* (before opening a modal): same dual layout `SystemAdmin.tsx` already uses for the Users tab (`hidden sm:block` table / `sm:hidden` cards) — reused rather than inventing a third list pattern in the same file. Each row: name, code, a permission-count badge ("23 permissions"), session timeout, and a small lock icon + "Protected" label for `student`/`admin`/`sysadmin` (§2.2). `sysadmin` is filtered out of this list entirely (§2.2).

*Create/edit modal*: `UserModal`'s existing shell (sticky header, scrollable body, sticky footer, bottom-sheet on mobile / centered on desktop) — but wider than `UserModal`'s `sm:max-w-md`, sized closer to `04-DataViewOptimization.md`'s decided detail-workbench pattern (full-screen on mobile, large modal — `sm:max-w-2xl`+ — on desktop) since a permission picker needs real vertical room:

```
┌─ Create Role ──────────────────────────── ✕ ┐
│ Name        [___________________]           │
│ Session tmo [___] minutes                    │
│ ─────────────────────────────────────────── │
│ 🔍 Search permissions...                     │
│ ─────────────────────────────────────────── │
│ ▸ Projects              4/7 selected  ☐→◐    │  ← accordion, header checkbox
│ ▾ Event Proposals       2/3 selected  ◐      │     = select-all-in-group
│    ☑ eventProposals.review                   │     (indeterminate when partial)
│       Review/approve/reject event proposals  │
│    ☑ eventProposals.assign                   │
│       Assign or transfer a proposal reviewer │
│ ▸ Applications           —                   │
│ ▸ Accounts               —          ⚠️        │  ← soft nudge, group has a
│ ...(15 more groups)...                       │     risk-flagged permission
│ ─────────────────────────────────────────── │
│ Selected: [reviews.decide ×] [cases.read ×]  │  ← chip summary, always visible
│ ...                                           │
├───────────────────────────────────────────── │
│              [Cancel]   [Save]                │
└───────────────────────────────────────────────┘
```

Behavior:
- **Grouping = §6's table's own grouping** (the code's prefix before the first `.`) — one source of truth, so the doc and the UI never drift apart.
- **Search** filters both group headers and rows by code/description substring, auto-expanding matching groups. Most groups default collapsed (except ones containing already-selected permissions, on edit) — mobile vertical space is the constraint, per `CLAUDE.md`'s mobile-first rule.
- **Group-header checkbox** = select/deselect all permissions in that group, tri-state/indeterminate when partially selected — `checkbox.tsx` supports this natively.
- **The 4 non-assignable codes never render** (3 student-submission + dead `projects.close`) — a finalized backend rule (§2.3), not a show-then-disable state. One disclaimer line under the search box covers it ("Permissions reserved for the built-in Student role aren't shown here").
- **⚠️-flagged permissions** (§6: `accounts.*`, `users.manage`, `roles.manage`, `settings.manage`, `lifecycle.purge`, `rules.publish`, `exports.create`, `imports.commit`) get a small icon next to the row — a soft nudge, not friction, since §2.3 deliberately left no hard ceiling beyond the student-submission exclusion.
- **Selected-chip summary** stays visible above the footer regardless of scroll position or which accordion sections are open — important when editing an existing role, so its current grants are visible without hunting through 19 sections.
- **Delete** reuses `UserModal`'s existing two-step inline confirm (destructive banner, Cancel/Confirm). A 409 (protected or in-use) shows as an error *before* the confirm banner appears, not as a failed confirm.

**Phase 5 — Contract & docs**
1. `Docs/integration/APIContract.md` + `_CN.md` §4.15: add `DELETE /roles/{id}`, change `roleCodes` → `roleCode` on the relevant request/response shapes, document the one-role-per-user invariant and the student-submission-permission exclusion.
2. `CLAUDE.md` / `AGENTS.md`: update the "A user can hold both `student` and `admin` workspace access" line (§1.5) to reflect §2.5.

**Phase 6 — Verification**
1. `mvn test` / `mvn verify` (new `*IT` coverage for: role delete blocked by protection/assignment, student-submission-permission rejection, grantAll now true for a non-student custom role, cardinality rejection).
2. `npx tsc --noEmit`, `pnpm build`.
3. Live browser verification against `tb.example.com` (redeploy both frontend and backend first, per `CLAUDE.md`'s real-server testing convention) — walk through: create a custom role, assign it to a new user, confirm it sees school-wide data and cannot be assigned a student-submission permission; attempt deleting an assigned/protected role and confirm the 409; confirm the workspace picker is gone and `both001`-equivalent accounts no longer exist.
4. Write up whatever the live pass finds per `CLAUDE.md`'s bug-tracking workflow (`Docs/issues/0NN-*.md`, `integration-test/STATUS.md`).

## 5. Acceptance criteria

- A `roles.manage` user can create a custom role, assign it any permission except the three student-submission ones, and assign it to a user (as that user's sole role).
- That user immediately sees school-wide data on every list endpoint that previously only granted it to `admin`/`sysadmin`.
- `admin` and `sysadmin` hold identical permission sets; either can reach the Roles tab and reassign a project owner.
- Deleting a role in use, or one of the three protected roles (`student`/`admin`/`sysadmin`), returns 409 with a clear reason; `teacher` deletes cleanly once no user holds it.
- `sysadmin` never appears in the Roles tab's list or the Users tab's role picker, but still exists, is still protected, and still functions exactly as before (created only via direct SQL).
- No account on the live server holds more than one role; the workspace picker UI is gone; every remaining test account logs straight into its one workspace.
- `Docs/integration/APIContract.md`/`_CN.md` and `CLAUDE.md`/`AGENTS.md` reflect all of the above — no doc still describes multi-role accounts or `roleCodes` as a set.
- Editing a protected role (`student`/`admin`/`sysadmin`) — any field, not just permissions — returns 409; the Roles tab shows it as fully read-only, not editable-then-rejected.
- Creating or editing a user with any role other than `admin`/`student`/`sysadmin` requires at least one grade; `admin` and `student` never show a grade picker (an explanatory note appears for `admin`).
- The Users tab's role selector is a dropdown, not a button row.
- A seeded 'Teacher' role template exists with the same grants as the live `teacher` role; any `roles.read` holder can list templates and use one to pre-fill a new role's permissions (create mode only), but only `admin`/`sysadmin` can create, edit, or delete a template — a `roles.manage`-holding non-admin/sysadmin role gets a 403 attempting any of the three.
- Templates never appear in the Roles tab's role list or the Users tab's role picker, and a template's code can never be assigned to a user.

## 6. Permission catalog — what's assignable to a role (for your review)

All ~52 permission codes from `R__seed_permissions_and_roles.sql:8-60`, grouped by resource exactly as the Roles-tab picker will group them (§4 Phase 4.2). **Assignable** means "can be included in a role's `permissionCodes` via `POST/PATCH /roles`" per §2.3's rule (everything except the three student-submission codes and the one dead code). **Risk** flags permissions worth a second look before granting to a new custom role — not a system restriction, just a pointer for your review, since §2.3 deliberately left no automatic ceiling beyond the student-submission exclusion.

| Resource | Code | Description | Assignable | Risk |
|---|---|---|---|---|
| **Projects** | `projects.read` | View projects within visible scope | ✅ | |
| | `projects.create` | Create project drafts | ✅ | |
| | `projects.update` | Edit projects | ✅ | |
| | `projects.publish` | Publish a project | ✅ | |
| | `projects.close` | Close a project early | ❌ dead — retired by `02-PorjectOrganization.md` §2 (`CLOSED` is system-only now); kept as an unused row, not offered in the picker | |
| | `projects.archive` | Archive a completed project once every confirmed student's record is reviewed | ✅ | |
| | `projects.cancel` | Cancel and archive a project outright, with a reason | ✅ | |
| | `projects.audit.read` | View project change timeline | ✅ | |
| **Event Proposals** | `eventProposals.read` | View event proposals within scope | ✅ | |
| | `eventProposals.create` | Submit an event proposal | ❌ student-only (§2.3) | |
| | `eventProposals.review` | Review/approve/reject event proposals | ✅ | now doubles as proposal-supervisor eligibility (§2.2) |
| | `eventProposals.assign` | Assign or transfer a proposal reviewer | ✅ | |
| **Applications** | `applications.read` | View registrations within scope | ✅ | |
| | `applications.create` | Register for a project | ❌ student-only (§2.3) | |
| | `applications.manage` | Confirm, reject, or bulk-manage registrations | ✅ | |
| **Service Records** | `serviceRecords.read` | View service records within scope | ✅ | |
| | `serviceRecords.create` | Create and submit a service record | ❌ student-only (§2.3) | |
| **Reviews** | `reviews.read` | View the review queue | ✅ | |
| | `reviews.decide` | Submit a review decision | ✅ | |
| | `reviews.bulkDecide` | Submit batch review decisions | ✅ | |
| | `reviews.assign` | Assign or transfer a record reviewer | ✅ | |
| **Cases** | `cases.read` | View appeals/supplements within scope | ✅ | |
| | `cases.create` | Create an appeal or supplement | ✅ not student-only — staff already file corrections on a student's behalf today | |
| | `cases.decide` | Decide an appeal or supplement | ✅ | |
| **Students** | `students.read` | View student directory within scope | ✅ | |
| | `students.manage` | Create or update student records | ✅ | |
| **Progress** | `progress.read` | View progress tracking | ✅ | |
| **Organizations** | `organizations.read` | View partner organizations | ✅ | |
| | `organizations.manage` | Create or update partner organizations | ✅ | |
| **Accounts** | `accounts.read` | View account balances and transactions | ✅ | |
| | `accounts.adjust` | Create a manual account adjustment | ✅ | ⚠️ moves time-coins |
| | `accounts.reverse` | Reverse an account transaction | ✅ | ⚠️ moves time-coins |
| | `accounts.freeze` | Freeze or unfreeze an account | ✅ | ⚠️ |
| **Rules** | `rules.read` | View rule versions | ✅ | |
| | `rules.manage` | Create or edit rule drafts and service categories | ✅ | |
| | `rules.publish` | Publish a rule version | ✅ | ⚠️ school-wide policy effect |
| **Reports** | `reports.read` | View dashboards and reports | ✅ | |
| **Exports** | `exports.create` | Create a data export job | ✅ | ⚠️ bulk data egress |
| **Imports** | `imports.create` | Create a data import job | ✅ | |
| | `imports.read` | View import job status and errors | ✅ | |
| | `imports.commit` | Confirm and commit an import | ✅ | ⚠️ bulk write |
| **Users** | `users.read` | View user accounts | ✅ | |
| | `users.manage` | Create, update, unlock, or reset users | ✅ | ⚠️ can create new accounts of any role |
| **Roles** | `roles.read` | View roles and the permission catalog | ✅ | |
| | `roles.manage` | Create, update, or delete roles | ✅ | ⚠️ can grant any of this table's ✅ rows to any role, including this one |
| **Audit Logs** | `auditLogs.read` | View the operation audit log | ✅ | |
| **Security Logs** | `securityLogs.read` | View login logs | ✅ | |
| **Settings** | `settings.read` | View system settings | ✅ | |
| | `settings.manage` | Update system settings | ✅ | ⚠️ |
| **Lifecycle** | `lifecycle.purge` | Trigger a cohort retention purge (dry-run or real) | ✅ | ⚠️ destructive, irreversible |
| | `lifecycle.reconcile` | Trigger a ledger reconciliation run and view its results | ✅ | |
| **Directory** | `directory.read` | Minimal id+displayName directory search (no contact/role/status fields) | ✅ | today granted only to `student` in the seed — worth deciding whether staff roles should get it too while you're reviewing this table |

**48 of 52 assignable** (4 excluded: 3 student-submission codes + 1 dead code). No permission in this table is currently blocked from a custom role beyond those 4 — flag here (or just tell me directly) if any `⚠️`-flagged one should also be excluded or given its own guardrail before implementation starts.

## 7. Decision index

| # | Section | Question | Decision | Status |
|---|---------|----------|----------|--------|
| 1 | §2.1 | How do custom roles get school-wide data visibility? | Any non-`student` role gets `grantAll` unconditionally (no new permission); named consequence: `teacher` becomes school-wide too, its grade-scope input becomes organizational metadata only | **Decided (2026-08-19)** |
| 2 | §2.2 | Which roles are protected from deletion/code changes, and is `sysadmin` exposed in the new UI? | `student`/`admin`/`sysadmin` protected; `teacher` dropped from protection (regular role, deletable once unassigned); `sysadmin` stays protected but is hidden from both new UI screens, deferred to future work | **Revised (2026-08-19)** |
| 3 | §2.3 | Admin/sysadmin relationship and Roles-tab access/ceiling | Merge to identical permission sets (both protected, both distinct rows); ceiling reframed as "no student-submission permissions assignable to any role," not an admin-vs-sysadmin escalation guard | **Decided (2026-08-19)** |
| 4 | §2.4 | What happens deleting a role with assigned users? | Block (409) with affected-user count; no cascade, no partial-removal case (see §2.5) | **Decided (2026-08-19)** |
| 5 | §2.5 | Role cardinality per user | Exactly one, always — retires the dual-workspace picker and the `both001`-style multi-role test account; wire format moves from `roleCodes: Set<String>` to `roleCode: String` | **Decided (2026-08-19)** |
| 6 | §2.6.1 | Are protected roles still editable? | No — `student`/`admin`/`sysadmin` are fully locked (name/permissions/timeout immutable), not just undeletable; supersedes part of §2.2 | **Decided (2026-08-19, follow-up)** |
| 7 | §2.6.2 | Which roles require grade assignment? | Every role except `admin`/`student`/`sysadmin` (was: literally just `teacher`); supersedes §2.1's "Settled" note | **Decided (2026-08-19, follow-up)** |
| 8 | §2.6.3 | Users tab role picker widget | Dropdown (`Select`) instead of a button row — doesn't scale with custom roles | **Decided (2026-08-19, follow-up)** |
| 9 | §2.8.2 | Role template fields: full role shape or simplified? | Simplified — name + permissions only, no code/session-timeout | **Decided (2026-08-19, follow-up #3)** |
| 10 | §2.8.2 | Applying a template: replace or merge current selection? | Replace | **Decided (2026-08-19, follow-up #3)** |
| 11 | §2.8.3 | Who can manage (create/edit/delete) role templates? | `admin`/`sysadmin` only, by role code — narrower than `roles.manage` | **Decided (2026-08-19, follow-up #3, mid-implementation)** |

All eleven resolved.

## 8. Implementation status

**2026-08-19 — Phases 1, 2, and 4 built and verified locally; not deployed.**

- **Phase 1** (`V20__role_protected_flag.sql`, `V21__merge_admin_sysadmin_permissions.sql`): done.
- **Phase 2** (backend application code): done — `TimeBankPrincipal.hasFullDataScope()` replacing every hardcoded `grantAll` check, `RoleService` student-only-permission rejection + `DELETE /roles/{id}`, `UserService.hasActivePermission` + generalized proposal-supervisor eligibility, `roleCode` singular across `CreateUserRequest`/`UpdateUserRequest`/`UserDto`/`UserController`. `mvn compile`, `mvn test-compile`, `mvn test` all pass.
- **Phase 4** (frontend): done — `api.roles`/`api.permissions` in `client.ts`, new `RoleManagement.tsx` (accordion permission picker per the design below §6), `SystemAdmin.tsx`'s Users tab converted to dynamic single-role selection, dual-workspace architecture removed (`WorkspaceSelect.tsx` deleted, `App.tsx`/`AppShell.tsx`/`AdminLayout.tsx`/`StudentLayout.tsx`/`LoginPage.tsx` simplified to one-role-one-workspace). `tsc --noEmit` and `pnpm build` both pass.
- **Bonus fix found and fixed along the way, unrelated to this doc's original scope**: `api.users.list()`'s wire type never matched either backend `UserDto` shape (old `roleCodes: string[]` or new `roleCode: string`) — it assumed `roles: {code}[]`, which doesn't exist on either. The Users tab has been silently stuck on a hardcoded 5-user mock array (`INITIAL_USERS`, itself a mock-mode leftover that should've been deleted 2026-08-07) this whole time, with the real error swallowed by a stray `.catch(() => {})`. Fixed as part of rewriting this exact code path; needs live confirmation (Phase 6).
- **Phase 3** (live data migration): done 2026-08-19. Audited `user_role` on `tb.example.com` — exactly one multi-role account, `both001` (confirmed with you before acting). Deleted its login (`user_role`/`password_reset_token`/`teacher_scope_assignment`/`user` rows); its linked `student` roster row and service-record history were left untouched. Re-audit confirmed zero multi-role accounts remain. Logged in `integration-test/STATUS.md` (2026-08-19 entry) and `Docs/TestAccounts.md`; `integration-test/mock-data.ts`/`MOCK_DATA_GUIDE.md` updated so a future from-scratch reseed doesn't recreate a multi-role account.
- **Phase 5** (contract/docs): done — `APIContract.md`/`_CN.md` §2.2 and §4.15 (`DELETE /roles/{id}`, `roleCode` singular, protected-role/student-only-permission rules, widened `staffId` requirement); `CLAUDE.md`/`AGENTS.md`/`.devin/global_rules.md`'s architecture sections; `Docs/frontend/Design.md`/`_CN.md` §3.1/§3.3/§11.2 (role table now says roles are configurable not fixed, portal-picker/workspace-switcher language removed).
- **Phase 6** (verification): new `*IT` coverage done (`RoleManagementIT` + additions to `DataScopeResolverIT`/`ProposalApprovalTransactionIT`) and — critically — running the full suite against real MySQL surfaced a genuine migration-ordering bug: `V20`/`V21` are versioned migrations that ran *before* `R__seed_permissions_and_roles.sql` (a repeatable migration, which Flyway always runs after every versioned one), so on a from-scratch schema their `UPDATE`/`INSERT...SELECT` statements fired against an empty `role`/`role_permission` table and were no-ops — every IT test schema got `is_protected = FALSE` for all roles and an unmerged admin/sysadmin. Fixed by moving the actual seed values into `R__seed_permissions_and_roles.sql`'s own `INSERT IGNORE` (which always reflects the fresh-install case correctly); `V20`/`V21` still do the real work of backfilling the *live* schema, which already has these rows from before the columns/merge existed. Also fixed 6 pre-existing tests that asserted `teacher`'s old grade-scoped behavior (now `hasFullDataScope()`, §2.1's explicit consequence) and an unrelated validation-ordering bug in `RoleServiceImpl.updateRole` (student-only-permission check now runs before the concurrency check, so it can't be masked by a stale version). `mvn verify` — all ~30 IT classes, real MySQL over an SSH tunnel — passes clean.

**Deployed and live-verified 2026-08-19.** Backend (`backend/scripts/deploy.sh`) and frontend (`frontend/scripts/deploy.sh`) both redeployed to `tb.example.com`; live DB spot-check confirmed `role.is_protected` (student/admin/sysadmin `true`, teacher `false`) and the admin/sysadmin permission merge (48 permissions each) landed correctly. Live Playwright pass as `admin001` found and fixed one real, pre-existing, unrelated production config gap that blocked *all* browser login (`TIMEBANK_ALLOWED_ORIGINS` missing from `/etc/timebank-backend.env` — `Docs/issues/027-*.md`, `integration-test/STATUS.md` bug #31); after that fix, verified: no workspace picker (direct redirect to `/admin/dashboard`), Users tab shows real data (confirming the earlier-found `api.users.list()` wire-format bug is fixed), Roles tab lists exactly 3 roles with `sysadmin` correctly hidden, full role lifecycle (create with the grouped/searchable permission picker and group-select-all, edit showing the protected-role banner for built-in roles vs. a working two-step delete for a custom role), and the Users tab's role picker is single-select with `sysadmin` likewise hidden. Also found, not fixed (harmless — nothing checks for it anymore): `admin`'s live grants include the retired `projects.close` permission, pre-existing data drift now visible for the first time via the new UI.

**All six phases done.**

**2026-08-19 follow-up #2 (§2.6) — built and verified locally, deploying next.**

- `RoleServiceImpl.updateRole` rejects any change to a protected role (409), checked right after fetch, before the version compare.
- `UserController`: `NO_GRADE_SCOPE_ROLES = {student, admin, sysadmin}` replaces the literal `TEACHER_ROLE` check across `requireRoleFields`, `create`, `update`, `withScopeGradeIds`.
- `RoleManagement.tsx`: protected-role edit view is fully read-only (name/timeout inputs and every permission checkbox disabled, chip-remove buttons hidden, footer shows only "Close"); banner text updated.
- `SystemAdmin.tsx`: `UserModal`'s role picker is now a `Select` dropdown (`components/ui/select.tsx`); `isTeacher` renamed to `needsGradeScope` with the new exclusion set, threaded through validation and `handleCreate`/`handleEdit`; the previously-unused `scopeGradesAdminNote` i18n key is shown when `admin` is selected.
- New IT coverage: `RoleManagementIT.updatingAProtectedRoleIsBlockedRegardlessOfWhatChanges`, `UserScopeBondingIT.aCustomNonTeacherStaffRoleAlsoRequiresAndWritesGradeScope` (a role that is neither `teacher` nor `admin`/`student`/`sysadmin`, proving the generalization, not just the two boundary cases the old tests already covered).
- `mvn compile`/`test-compile`/`test` all pass; `pnpm build` passes; targeted `mvn verify` runs of `RoleManagementIT` and `UserScopeBondingIT` against real MySQL over the SSH tunnel both pass clean.

**Deployed and live-verified 2026-08-19.** Backend and frontend both redeployed to the real server (`backend/scripts/deploy.sh`, `frontend/scripts/deploy.sh`). Live Playwright pass as `admin001`/`wei.chen@example.edu.cn` confirmed all three: (1) editing the protected `admin` role shows every field disabled (name, session timeout, all permission checkboxes), the updated banner text ("cannot be deleted or edited"), and only a Close button in the footer — while the unprotected `teacher` role's edit modal is unchanged (Cancel/Save/Delete all present and working); (2) the Create User form's role dropdown correctly lists only `admin`/`teacher`/`student` (`sysadmin` still hidden), selecting `teacher` shows the grade-bonding picker, and selecting `admin` hides it and shows the "admins have access to all grades" note instead; (3) the role selector renders as a proper dropdown/combobox, not a button row. No new defects found — nothing to log in `Docs/issues/` or `integration-test/STATUS.md`'s bug table this pass.

**2026-08-19 follow-up #4 — permission-picker UI polish, deployed and live-verified.** Four refinements to `RoleManagement.tsx`'s permission picker (§4 Phase 4 detail's mockup), raised after the above was live:
1. Group header spacing fixed ("Accounts4/4" → "Accounts 4/4") — was relying on `justify-between` filling the trigger's full width, which didn't reliably hold; replaced with an explicit `gap-2` + `flex-1` layout.
2. Each expanded group now renders as a two-column row (translated description + risk icon on the left, checkbox on the right) instead of a checkbox-then-code-then-description stack — the raw permission code (e.g. `accounts.adjust`) is no longer shown anywhere in this picker.
3. The selected-permissions chip summary below the accordion was removed — redundant with each group's own `n/total` count and the checkboxes themselves.
4. **Both the resource-group label and the permission description are now translatable** (`admin.roles.permissionGroups.<prefix>` / `admin.roles.permissionDescriptions.<code>` in `en.json`/`zh.json`), not just the mechanical camelCase-to-spaced-words conversion or the server's English `description` column. This is a deliberate exception to "a role's own `name` has no i18n path" (§4 Phase 4 detail) — permission codes and their resource-group prefixes come from a fixed, backend-owned catalog (`R__seed_permissions_and_roles.sql`), a closed enumerable set unlike a role's free-text name, so they *can* be given real translations; `t(key, { defaultValue })` falls back to the mechanical conversion / server description for anything not yet translated (e.g. a newly added permission before its translation ships). The search box now matches against the translated text too, so searching in Chinese actually finds Chinese-displayed permissions.

`pnpm build` passes; live Playwright pass (both `zh` and `en`) confirmed all four against the real server — Chinese group labels and descriptions render correctly with no leftover English or raw codes, search matches translated text, and the unprotected `teacher` role's edit flow (search, expand/collapse, save) still works normally.

**2026-08-19 follow-up #3 — role templates (§2.8), built and verified locally.**

- `V22__role_templates.sql`: `role.is_template` column; seeds `role_template_teacher` (code `template_teacher`, name "Teacher") with a copy of the live `teacher` role's grants. `R__seed_permissions_and_roles.sql` carries the same 21 `role_permission` rows directly (fresh-schema case) — the `role` row itself isn't duplicated there, since V22's own insert isn't conditional on `role_teacher`'s grants existing yet (unlike the permission rows, which are).
- `RoleTemplateDto`/`CreateRoleTemplateRequest`/`UpdateRoleTemplateRequest` (name + permissions only, §2.8.2); `RoleServiceImpl` gains `listRoleTemplates`/`createRoleTemplate`/`updateRoleTemplate`/`deleteRoleTemplate`, and `listRoles`/`updateRole`/`deleteRole` are now template-blind (§2.8.1). `UserServiceImpl.resolveRole` uses `findByCodeAndIsTemplateFalse`.
- `RoleController`: `GET/POST /role-templates`, `PATCH/DELETE /role-templates/{id}`; the three write endpoints additionally call `requireTemplateManager` (role-code check, admin/sysadmin only — §2.8.3).
- Frontend: `api.roleTemplates.{list,create,update,remove}`, `RoleTemplateSummary` type; `RoleManagement.tsx` restructured into two cards (templates, then roles), permission-picker logic extracted into a shared `PermissionPicker` component, `RoleFormModal`'s create mode gets a template-select dropdown next to the search box (§2.8.4); `SystemAdmin.tsx` computes `canManageTemplates` (admin/sysadmin only) and threads it through.
- New IT coverage (`RoleTemplateIT`, 9 tests): seeded-template grants match `teacher`'s live grants; student-only-permission rejection; create/update/delete round-trip; template↔role list isolation both directions; a template's code can never be assigned to a user; `/roles` endpoints can't reach a template row; a `roles.manage`-holding non-admin/sysadmin principal is forbidden from creating/updating/deleting a template via the controller (role-code check proven at the HTTP-handler layer, not just the service layer); an admin principal succeeds.
- Two bugs found and fixed while getting `mvn verify` clean against real MySQL: (1) `OpaqueIds.generate("roletmpl")` overflowed the `role.id`/`code` VARCHAR(32) columns — same class of bug as an earlier `role.code` overflow this session — fixed by shortening the prefix to `rtpl`; (2) the fresh-schema seed duplicated the `role_template_teacher` row itself (not just its grants) in both `V22` and `R__seed_permissions_and_roles.sql`, which is harmless (`INSERT IGNORE` on the same primary key) but logged a noisy "Duplicate entry" warning on every fresh install — fixed by only duplicating the `role_permission` rows in `R__seed` (the part that's actually order-dependent), not the `role` row itself (which V22 always creates unconditionally).
- `mvn compile`/`test-compile`/`test` pass; `pnpm build` passes; `mvn verify -Dit.test=RoleTemplateIT,RoleManagementIT,UserScopeBondingIT` against real MySQL over the SSH tunnel passes clean (all 9 `RoleTemplateIT` tests plus the two pre-existing classes, unaffected).

**Deployed and live-verified 2026-08-19.** Backend and frontend both redeployed to the real server; `V22` applied cleanly against the live schema. Live Playwright pass as `admin001`/`wei.chen@example.edu.cn` confirmed: the Roles tab shows two stacked cards, Role Templates (top, "1 templates", seeded "Teacher" template with 21 permissions) above Roles (unchanged); the Create Role dialog's "Start from a template" dropdown renders inline with the search box; selecting "Teacher" correctly replaces the (empty) permission selection with the template's exact grants (spot-checked Accounts 1/4, Applications 1/2, Cases 2/3 against the seeded template's known set); no console errors. The admin/sysadmin-only write restriction (§2.8.3) was **not** re-verified by clicking through as a non-admin/sysadmin `roles.manage` holder — no such live test account currently exists to log in as — but is proven end-to-end at the HTTP-handler layer by `RoleTemplateIT`'s controller-level tests (`aCustomRoleHoldingRolesManageStillCannotCreateATemplate`/`...UpdateOrDeleteATemplate`), which passed against real MySQL. A future session with a suitable test account should close this gap with an actual click-through if it matters.

**2026-08-19 follow-up #5 — list-level View/Delete actions, deployed and live-verified.** Raised after §2.8 was live: two UI refinements to the Roles/Templates row actions, applied identically to both.
1. A protected role's row action now reads "View" instead of "Edit" (`RoleManagement.tsx`, keyed off `r.isProtected`) — accurate labeling, since clicking it always opens the read-only view (§2.6.1), never an editable one. The modal's own header follows suit ("View Role" vs "Edit Role"), and the create/edit form's title logic gained a third branch for this. Purely a label change — the underlying click handler (`setEditTarget`) and the protected-role-is-fully-locked behavior are unchanged.
2. **Delete moved out of the create/edit modal into its own list-level action + standalone confirm dialog**, for both roles and templates — the modal no longer has any delete affordance at all (the `onDelete` prop was removed from both `RoleFormModal` and `RoleTemplateFormModal`, along with their embedded two-step inline confirm). A new shared `ConfirmDeleteDialog` component (title + message + Cancel/Confirm Delete, centered modal, own backdrop) is triggered by a "Delete" button next to "Edit"/"View" in each row (desktop table and mobile card) — protected roles never show a Delete action at all (409 either way, so hiding it is accurate, not just cosmetic). `handleDelete`/`handleTemplateDelete` now operate on new `deleteTarget`/`templateDeleteTarget` state instead of reusing `editTarget`/`templateEditTarget`, since delete no longer happens from inside the edit flow.

`pnpm build` passes; live Playwright pass confirmed: `SDC Admin`/`Student` rows show "View" only (no Delete); `Teacher` role and the `Teacher` template both show "Edit" + "Delete"; clicking a row's Delete opens the new standalone confirm dialog (verified on the template row, canceled without deleting the seeded template); clicking "View" on `Student` opens the modal titled "View Role" in the same fully-read-only state as before (no change in lock behavior, only the label and the removed delete section).

**2026-08-19 follow-up #6 — Users column + disabled Delete when in use, deployed and live-verified.** Confirmed against §2.4 (re-checked, not re-litigated): deletion is already blocked (409) if any user holds the role — this pass makes that visible and pre-empted in the UI rather than only discoverable by a failed confirm.
- `RoleDto` gains `userCount` (`RoleServiceImpl.toDto`, one extra `userRepository.countByRolesId` query per role — fine at this table's size, not worth batching). `RoleTemplateDto` deliberately does **not** get an equivalent field — templates are never assigned to a user (§2.7), so the count would always be zero and the column would be dead weight; the Templates card has no Users column.
- Roles table (desktop + mobile) gains a "Users" column (`{{count}} users`) between Permissions and Session Timeout.
- The row-level Delete action is **disabled** (native `disabled` attribute, not just styled) whenever `userCount > 0`, with a `title` tooltip explaining why — pre-empting a doomed delete attempt rather than letting the confirm dialog open and then fail. Protected roles still show no Delete action at all (unrelated, pre-existing rule, §2.6.1/§2.9's earlier follow-up). The backend 409 remains the authoritative guard for the (unlikely) race where another admin assigns a user between page load and a delete click — `handleDelete`'s existing `ApiError` catch/toast already surfaces that correctly, no new handling needed.
- New IT coverage: `RoleManagementIT.deletingAnUnprotectedRoleStillAssignedToAUserIsBlocked` now also asserts `RoleDto.userCount() == 1` after assignment; `deletingAnUnassignedUnprotectedRoleSucceeds` asserts `userCount() == 0` on a fresh role.
- `mvn compile`/`test-compile` pass; `mvn verify -Dit.test=RoleManagementIT,RoleTemplateIT` against real MySQL passes clean. `pnpm build` passes. Live Playwright pass: Users column shows accurate live counts (`SDC Admin` 2, `Student` 3, `Teacher` 2, matching the seeded test accounts); `Teacher` role's Delete button renders with the native `disabled` attribute (confirmed via accessibility snapshot, not just visual styling) since it has 2 users, while the `Teacher` template's Delete (never gated by user count) stays fully active.

**2026-08-19 follow-up #7 — row-action button consistency across Users/Roles/Rule Settings, deployed and live-verified.** Not a 07-RoleFinetune.md-specific change in scope — a UI-consistency pass across every tab this doc's work touched, plus the pre-existing Rule Settings tab — but logged here since it's the same session and the same files.
- All three tabs' row actions (Users' Edit; Roles'/Templates' Edit/View/Delete; Rule Settings' per-section Edit) now use the same icon + text, bordered-not-filled button shape, colored per action (blue for edit/view, red for delete, muted when disabled) — matching Student Management's row-action shape (bordered pill, icon+text) but adding persistent per-action color, which Student Management's own buttons don't have (muted-gray only). Previously these three tabs used plain colored text links with no icon and no border on desktop, and bordered-but-uncolored buttons on mobile — inconsistent both with each other and with Student Management.
- Implementation: `SystemAdmin.tsx` gets a module-level `ACTION_BTN_BLUE` constant (Users desktop+mobile Edit, Rule Settings Edit); `RoleManagement.tsx` gets its own `ACTION_BTN_BLUE`/`ACTION_BTN_RED`/`ACTION_BTN_RED_DISABLED` constants (Roles/Templates desktop+mobile). No shared cross-file component introduced — matches this codebase's existing convention of hand-rolled Tailwind classes per admin list rather than a shared button-variant abstraction (`components/ui/button.tsx` exists but isn't used by any admin list in this app, including Student Management itself). `Pencil`/`Eye`/`Trash2` icons (all already used elsewhere in the app) — Edit uses `Pencil`, View uses `Eye`, Delete uses `Trash2`.
- `pnpm build` passes; live Playwright pass across all three tabs confirmed the new bordered/colored/iconed buttons render correctly (including the protected-role "View" with `Eye` icon and the disabled Delete's muted styling), no console errors.
