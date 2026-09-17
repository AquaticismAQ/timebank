# 094 — System Admin Users tab lacks fields and list controls

**Status:** In progress.
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
