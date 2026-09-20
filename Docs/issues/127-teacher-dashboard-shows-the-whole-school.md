# 127 — The teacher dashboard shows the whole school

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report:
Active students, Approved this month, Avg review time, Total service time,
Annual Completion Rate and Active Projects should reflect the teacher's grades,
not the school.

## 1. What is wrong

Teachers use the Admin Portal with scoped permissions, and its dashboard calls
`GET /dashboards/admin` for everyone. `ReportQueryRepository.adminStats` took no
scope at all — six `select count(*) from student` / `from project` /
`from service_record` subqueries over the whole database.

Measured on the live server, the same day, for `admin001` and `teacher005`:

```
admin001   {activeStudents 56, totalProjects 91, activeProjects 12,
            approvedThisMonth 108, totalServiceHoursThisYear 240, reviewAvgHours 23.41}
teacher005 {activeStudents 56, totalProjects 91, activeProjects 12,
            approvedThisMonth 108, totalServiceHoursThisYear 240, reviewAvgHours 23.41}
```

Identical to the digit. **One number did differ** — `pendingReviews` (9 vs 2) —
because it comes from `reviewService.summary(auth)`, which has always been
scoped. So the dashboard was already mixing scoped and unscoped figures on one
row of cards, which is worse than being consistently wrong: nothing on screen
said which was which.

**And `avgCompletionRate` was never sent at all.** The card has read that field
since it was built; `AdminStatsDto` had no such component, so "Annual Completion
Rate" showed an em dash on every dashboard in the system.

## 2. Three tables, three ways to reach a grade

This is the part that is not boilerplate:

| Table | In scope when |
| :--- | :--- |
| `student` | it carries `grade_id` / `class_id` directly |
| `service_record` | **its student** is — reached with `exists (select 1 from student …)` |
| `project` | one of the grades it is *open to* is — `eligible_grade_ids` is a JSON array, so `json_overlaps`, not a column comparison |

The project row is a judgement, not a lookup: a project has no owning student
and `ProjectManagement` already records that projects have no teacher-grade data
scope of their own. "Open to a grade I teach" is the only reading of *my* active
projects that means anything, and it is the one a teacher would expect.

A `Classes` scope reaches projects through the grades of those classes; `Self`
and `Projects` scopes resolve to nothing rather than to everything, because a
dashboard is never reached by such a principal and "show them the school" is the
wrong way to be wrong about it.

## 3. Avg review time and the completion rate

`reviewEfficiency` backs `GET /reports/review-efficiency`, whose shape is in the
contract, so it is untouched; the dashboard gets
`scopedOverallReviewAvgHours(start, end, scope)` — one number, the same
stale-round guard, scoped through the record's student.

The completion rate is built from the **existing** completion report rather than
new SQL: it already computes a rate per grade against that grade's published
requirement, and the only question the dashboard adds is which grades count.
Unweighted across grades on purpose — this is "how are my grades doing", not a
school total in which a large cohort outvotes a small one. Null, not zero, when
no grade in scope has a requirement to measure against.

## 4. Verification

The same two accounts, after:

| | admin001 | teacher005 |
| :--- | ---: | ---: |
| activeStudents | 56 | **24** |
| totalProjects | 91 | **12** |
| activeProjects | 12 | **4** |
| approvedThisMonth | 108 | **39** |
| totalServiceHoursThisYear | 240 | **90** |
| reviewAvgHours | 23.41 | **37.13** |
| avgCompletionRate | 0.0179 | 0 |

Every teacher figure is a strict subset of the administrator's, and the review
average moved *up* rather than down — a scoped mean is not a scaled one, which
is the sort of thing a "smaller is correct" check would have missed.

Integration tests take the `ScopeDescriptor` directly, testing the SQL rather
than the login that produces one:

- two grades must **partition** the whole, not merely be smaller than it — a
  query that scoped nothing would pass a "smaller" assertion on a small fixture;
- an **empty** scope must report zero, not everything.

Red-checked by disabling the student clause: exactly those two fail, and the
account-summary test — which does not depend on it — still passes.

**One bug this caught in itself:** `avg()` over no rows returns a single row
holding null, and `.stream().findFirst()` on it throws — `Optional.of(null)`.
`DashboardReportingIT` has no decisions to average and turned it up immediately.

## 5. What it must not do

- **Must not scope only the student figures.** §2 — the records and projects are
  the ones a teacher was most misled by.
- **Must not resolve `Self`/`Projects` to everything.** §2.
- **Must not change `/reports/review-efficiency`.** §3.
- **Must not report 0% completion where the answer is "no requirement set".**
  §3 — the card renders null as an em dash, which is the honest reading.
