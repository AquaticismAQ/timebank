# 094 — System Admin Users tab lacks fields and list controls

**Status:** Fixed, deployed, and verified against the live server.
**Reported:** 2026-09-17 by the user (`drafts/02-system-admin-users.md`).

The Users tab is the last admin list that never received the treatment
`Docs/optimization/05-SearchFilterSortConsistency.md` applied everywhere else.
It loads every user in one unpaginated call and offers no search, filter or sort.

## 1. What it does today

`SystemAdmin.tsx` calls `api.users.list()` with **no arguments** inside a
`useEffect` and maps the whole result into local state. There is no pagination,
no search box, no filter row and no sort controls — unlike Project Management,
Student Management, Audit Center, Case Review and Proposal Review, all of which
share one established pattern.

## 2. Why it cannot be fixed in the frontend alone

| Need | Backend today |
| :--- | :--- |
| Grade column | **`UserDto` has no grade.** It carries `scopeGradeIds` (a *teacher's* scope, not a student's own grade) and `studentId`, nothing more |
| Class column | **`UserDto` has no class** |
| Search by name | `GET /users` takes only `PageQuery` — no `search` |
| Filter by role / status / grade / class | no filter parameters at all |
| Sort by name / grade / class | no sort support |

Compare `GET /students`, which already accepts `search`, `gradeId`, `classId`
and sorting — the Users endpoint simply never grew them.

Doing this client-side was explicitly rejected: the credential-slips work
(issue 093 §4 era) already had to page `GET /users` at 100/page and join grades
in the browser, and that is exactly the workaround this issue exists to remove.

## 3. What was asked for

- **Columns:** grade (`All`, `G6`, `G6, G8` for multi-grade staff scope) and
  class. Class applies to students only; everything else shows `-`.
- **Pagination**, matching the other lists.
- **Search** by name.
- **Filter** by role, status, grade, and class — class offered *only* when the
  role filter is `student`.
- **Sort** by name, grade, and class — class sorting disabled unless the role
  filter is `student`.

## 4. Notes for whoever implements it

- The grade column means two different things depending on the role: a
  student's own grade, versus a staff member's *scope* (which can be several
  grades, or all). The `All` / `G6` / `G6, G8` formats in the request reflect
  that — do not collapse them into one meaning.
- Student Management (`StudentList.tsx`) is the closest existing pattern and
  already solves the grade→class cascade: changing grade clears the class,
  because a class belongs to exactly one grade and a stale selection is invalid.
- `Docs/optimization/05-SearchFilterSortConsistency.md` §1.4 fixes the "All"
  copy convention across pages; follow it rather than inventing new wording.

## 5. Fix

**Backend.** `GET /users` gained `search` (display name) and
`filter[role|status|gradeId|classId]`, and `SORTABLE` widened to include
`gradeLevel` and `className`. `UserDto` gained `gradeId`, `gradeLevel`,
`classId`, `className` — the linked student's placement, null for staff, with
the record's Javadoc stating outright that these are **not** `scopeGradeIds` and
that neither substitutes for the other.

**On the module boundary.** `student` has no back-edge to `iam`, so the
web-layer-enrichment detour `scopeGradeIds` needs was not required here.
Single-user paths enrich through `StudentService`'s published interface; the
list path uses a new `UserListQueryRepository` read model, because Java-side
enrichment can populate grade and class but cannot **order a page by them**, and
a JPA association to `student.domain.Student` is precisely what
`ModuleBoundaryTest` forbids. Raw SQL carries no Java dependency — the precedent
`ReviewQueueRepository` set.

Two details in that query that would have been easy to get wrong: the role is
read by correlated subquery and filtered with `exists` rather than a join, so a
multi-role row cannot duplicate and corrupt `totalItems` and the page
boundaries; and `sort=gradeLevel` maps to `g.sequence`, not `grade_id`, which
would have put `grade_10` ahead of `grade_6`.

**Frontend.** Class filter and class sort both derive from one flag, so they
cannot disagree. Leaving the Student role clears a selected class *and* falls an
active class sort back to name — otherwise a constraint would persist with no
visible control to clear it.

## 6. Verification

**The SQL had never executed.** `UserListQuerySqlTest` asserts the statement's
shape, not that MySQL accepts it, so a wrong column name would have passed. It
was run against the live server after deploying: 37 users, 8 pages, grade and
class populated for a student.

Every filter was checked to **narrow**, not merely be accepted:

| Query | Rows |
| :--- | ---: |
| unfiltered | 37 |
| `role=student` / `role=teacher` | 30 / 5 |
| `gradeId=grade_9` | 5 |
| `classId=class_9B` | 3 |
| `search=Parker` | 1 |

`classId` returning fewer than `gradeId` is the check that a class filter is not
quietly behaving as a grade filter.

**The status filter needed a different test.** Every seeded account is `ACTIVE`,
so `status=active` returns 37 — identical to unfiltered, and therefore proof of
nothing. Asking for the statuses that *should* be empty is what distinguishes
working from ignored: `inactive`, `locked`, `pendingActivation` and
`activationExpired` each return **0**, where an ignored filter would have
returned 37.

Sorting flips: `displayName` gives Alex L. first, `-displayName` gives Teacher
Wei Chen. Browser pass 6/6 — both columns present, a student row reading
`G6 / B`, a staff row reading its scope with `—` for class, pagination, and the
class filter absent until the role filter is Student.

## 7. Follow-ups (all closed)

**Integration test — closed.** `UserListFilterSortIT` (7 cases) runs the read
model against real MySQL and passes. Each filter asserts a positive *and* a
negative, because a filter accepted and ignored returns the unfiltered set and is
otherwise indistinguishable from one that works. The status case is the sharp
one: every account is created `ACTIVE`, so `status=ACTIVE` proves nothing —
asking for `INACTIVE` and getting none is what separates filtering from ignoring.

**`lastLoginAt` — closed.** There is no `user.last_login` column; the value is
the newest successful `login_log` row. Added to the list read model as a
correlated subquery, for the same reason `role_code` is one: joining `login_log`
would multiply each user row by their login count and corrupt the page counts.
Single-user responses leave it null rather than paying a second query for a
screen with nowhere to show it.

**`ActivationSlips.tsx` — closed.** It now asks for the one class it needs
(`filter[role]=student&filter[classId]=…`) instead of paging the whole school and
joining in the browser, so the cost no longer scales with the size of the school.

## 8. Login is now audited (requested during this work)

Signing in reached `login_log` only — the audit log an administrator reads after
an incident contained **no logins at all**, despite §10 of the MLPS design guide
committing to the audit trail covering security events.

Written inside `LoginLogServiceImpl.record`, the single funnel all four call
sites already pass through (one success, three refusals), so a fifth cannot be
added that records a login without auditing it. `user.login` / `user.loginFailed`
with the attempted username, the client IP and the failure reason;
`actor_id` is null for an unknown username, which the V1 schema allows. The
password is not a parameter of that method and so cannot reach the row —
verified: zero audit rows contain either test password.

One defect found by reading the rows it actually wrote: the summary was built as
a JSON string, but `AuditService` puts the value through Jackson, so it came back
quoted and escaped as a JSON *string* while every other row is an object. Now a
`Map`, and the stored shape matches its neighbours.
