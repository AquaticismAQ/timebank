# 007 — `POST /auth/login`/`/auth/refresh` omit a scoped teacher's `scopeGradeIds`

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

Found while browser-verifying issues 001–006 (the previous session had no browser
tool, so this specific rendering gap was never caught). Logged in as `teacher001`
(scoped to grade_10) and the grade filter dropdown on both the Student List page and
the Time Coin Accounts page listed all 6 grades, not just grade_10 — even though
issue 004's fix already added `scopeGradeIds` to `GET /me` specifically to drive this.

## Root cause

`GET /api/v1/me` (`MeController.java:39-46`) enriches its response with the real
grade bond via `teacherScopeAssignmentService.gradesOf(userId)`. `POST /auth/login`
and `POST /auth/refresh` (`AuthController.java`) both build their response the same
way (`userService.buildMeResponse(...)`) but never applied that same enrichment —
they returned `scopeGradeIds: []` even for a genuinely scoped teacher.

That alone wouldn't matter if the frontend re-fetched `/me` after establishing a
session, but it doesn't: `api.auth.me()` exists in `client.ts` but is never called
anywhere in the app (confirmed via `grep -rn "auth\.me\b" src`). `App.tsx` seeds the
whole-session `User` object directly from the login (or refresh) response and never
corrects it, so the empty `scopeGradeIds` from login persisted for the entire session
regardless of what `/me` would have said.

Live-verified before the fix via direct HTTP calls against `tb.example.com`:
`POST /auth/login` as `teacher001` → `scopeGradeIds: []`; `GET /api/v1/me` (same
session, immediately after) → `scopeGradeIds: ["grade_10"]`.

Not a data leak — the actual list/filter queries are enforced server-side by
`DataScopeResolver` regardless of what the client thinks its own scope is (confirmed:
selecting an out-of-scope grade in the dropdown just returns 0 results). This is a
UX/correctness bug in what the filter dropdown *offers*, not an access-control gap.

## Fix

`AuthController` now injects `TeacherScopeAssignmentService` and applies the same
4-line enrichment `MeController` already does, on both `login` and `refresh`, via a
small private `withScopeGradeIds` helper (duplicated rather than pushed down into
`UserServiceImpl.buildMeResponse` for the same `iam`/`security` module-cycle reason
already documented on `MeController`/`UserController`).

## Test plan

- New `AuthFlowIT.loginAndRefreshBothCarryTheTeachersScopeGradeIds`: creates a
  teacher, assigns `grade_10` via `TeacherScopeAssignmentService.replaceGradeScope`,
  logs in for real (full CSRF handshake, real cookies, real embedded server + MySQL),
  and asserts `"scopeGradeIds":["grade_10"]` is present on both the login response
  body and a subsequent `/auth/refresh` response body.
- Live: re-checked `POST /auth/login` as `teacher001` after deploy returns
  `scopeGradeIds: ["grade_10"]`; grade filter dropdown on Student List/Accounts pages
  now shows only grade_10 for `teacher001` in the browser.

## Resolution

- `backend/src/main/java/cn/timebank/iam/web/AuthController.java`: added
  `TeacherScopeAssignmentService` dependency and `withScopeGradeIds(...)` helper,
  applied to both `login` and `refresh`.
- `mvn compile` clean; `AuthFlowIT` (new test included) passes against real MySQL
  over an SSH tunnel to `tb.example.com`, same protocol as issue 001.
- Deployed to `https://tb.example.com/`; live-verified via direct API call and browser.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`, found and fixed during a
browser-verification pass of issues 001–006 (which the prior session, lacking a
browser tool, couldn't do).*
