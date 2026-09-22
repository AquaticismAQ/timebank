# 159 — A filter and a sort that each only saw one page

**Status:** **Fixed and deployed 2026-09-23.** Aquaticism: on Student Management, filter to
*On Track ≥50%* and the page shows no students — but the pager says three pages,
and the last page has them. Asked for the root cause and for every list page to
be checked for the same thing.

Then, after that fix: sorting by service time could not see the 30h student the
same filter had just shown. Same root cause, second control — §4a.

## 1. Reproduced

```
filter: On Track ≥50%
page 1/3   0 rows
page 2/3   0 rows
page 3/3   1 row   Alex L.(李*红)
pager says: 56 total · page 1 / 3
```

56 is the count of **every** student. One of them is on track.

## 2. Root cause: the filter and the pager are looking at different lists

The status bucket is applied **in the browser, to the twenty rows the server
happened to send**. The pager is drawn from the server's `page.totalItems`, which
knows nothing about that filter.

```tsx
// StudentList.tsx
const statusFiltered = students.filter(s => {
  const pct = (s.completedMinutes ?? 0) / (s.annualRequiredMinutes ?? 1);
  return filterStatus === 'onTrack' ? pct >= 0.5 : …
});
```

So the server pages 56 students into three pages of twenty; the browser then
deletes from each page the rows that do not match. Pages one and two contain no
on-track student, so they render empty while still insisting two more pages
exist. Nothing is broken in the pager and nothing is broken in the filter — they
simply disagree about what is being paged, and **the disagreement is invisible
until a filter is selective enough to empty a whole page.**

The code says so itself, in a comment that reads as a note and should have read
as a defect:

> The status bucket … is derived from a ratio the backend doesn't expose as a
> filterable field, so it's still applied client-side to whatever page came back.

### Why the ratio was not server-side

`StudentListItemDto` carries no progress at all — no completed minutes, no annual
requirement. The page gets them by calling `GET /students/{id}/progress` **once
per row**, twenty requests per page, after the list arrives. A filter cannot run
on the server over a field the server does not return, so it ran where the data
was.

That N+1 is the same defect seen from the other side: the list endpoint does not
know what the list is about.

## 3. The same shape elsewhere — the audit Aquaticism asked for

Every paginated list was checked for work done in the browser over one page.

| Screen | What is client-side | Effect |
| :--- | :--- | :--- |
| **Student Management** | status bucket **filter** | **rows disappear, pager lies** — the first reported bug |
| **Student Management** | sort by grade / hours / coins | **orders one page, presented as ordering the list** — the second (§4a) |
| Student Management | the three summary tiles | counts one page (`0 / 20 / 20` against 56 students) |
| Time Coin Accounts | sort by balance, **always on** | "highest balances" is the highest *on this page* |
| Project Management | sort by registrations | orders one page |
| Project Center (student) | sort by registrations | orders one page |
| Notifications | unread count | counts one page; the sidebar badge uses the real endpoint, so the two can differ |

Only the first hides rows. The rest mislead without hiding: a sort that orders
twenty of fifty-six rows answers "who is top" with "who is top of this page",
which is wrong far more quietly.

## 4. Fix

**The list row carries what the list is about.** `StudentListItemDto` gained
`completedMinutes` and `annualRequiredMinutes`, filled for the page in one query
instead of one request per row. That alone removes the N+1 — twenty requests a
page become zero.

**The bucket became a server filter.** `GET /students` takes
`filter[progressStatus]=onTrack|behind|critical`, resolved into an id set
*before* paging, so the page and its total describe the same list.

The sums live in `service_record`, and both obvious routes are closed: a JPA
criteria subquery would need the `ServiceRecord` entity, which
`ModuleBoundaryTest` forbids reaching across modules, and injecting
`ServiceRecordService` closes a bean cycle because it already injects
`StudentService`. So `StudentProgressQueryRepository` reads the table in SQL and
carries no Java dependency on that module — the argument
`AuditLogQueryServiceImpl` already makes for joining `user`.

Two queries rather than one clever one: the candidates the filter could match,
then their minutes. The roster is under two hundred students, and the
alternative is a second copy of the list query's scope, filter, sort and paging
logic in SQL — which is the copy that would drift.

**The tiles count the school, not the page.** Three `pageSize: 1` requests read
for `totalItems`, the trick `api.cases.pendingCount()` already uses. They are
scoped by the *other* active filters but never by the bucket, because the tiles
are how you choose a bucket.

**Time Coin Accounts stopped re-sorting its page.** Not fixed by sorting
server-side: the paged resource there is `/students`, with balances merged in
from `/accounts` afterwards, so there is no column to sort on. Roster order is
what the page actually has.

### Verified

Server, per bucket — totals and page counts now agree with the rows:

```
all       total=56  pages=3
onTrack   total=1   pages=1     Alex L. 1800 / 1500
behind    total=55  pages=3
critical  total=53  pages=3
```

`1 + 55 = 56`, and `critical` ⊂ `behind`, which is the arithmetic the buckets
claim. In the browser, the reported case:

```
before   tiles 0 / 20 / 20   On Track → 0 rows, "56 total · page 1 / 3"
after    tiles 1 / 55 / 53   On Track → 1 row,  "1 total · page 1 / 1"
```

**Every filter on every list page was then exercised live**, watching whether the
change produced a request carrying it — a sharper test than counting rows, since
a client-side filter makes no request at all:

| Page | Filters | Verdict |
| :--- | :--- | :--- |
| Student Management | grade, class, completion | all server-side |
| Project Management | status, category, grade, academic year | all server-side |
| Audit Center | grade | server-side |
| Case Review | type, status, grade, academic year | all server-side |
| Time Coin Accounts | grade, class | all server-side |

No filter anywhere else was client-side. The reported bug was the only one of
its kind.

## 4a. The sorts — same defect, reported separately

The **sorts** were the same defect in a quieter form, and were left out of the
first pass. Aquaticism then found the case that makes it undeniable:

> the on track more than 50% already show a student with 30h service time, but
> sorting by service time can not see him

Both statements were true at once. The bucket filter was correct and found the
one on-track student in the school; the hours sort ordered the twenty rows the
database happened to return and could not reach them. **A screen where the
filter and the sort disagree about the same student is worse than either bug
alone** — each control individually looks like it works.

Measured rather than assumed, on Time Coin Accounts: page 1's highest balance
was **152.5** coins and page 2 held **304.5**. "Sort by balance" was showing the
top of a page.

### Why these three had no column

| Key | Where the value lives |
| :--- | :--- |
| grade | `grade_id` is a string — `order by` puts grade_10 and grade_11 ahead of grade_6 |
| hours | `service_record`, another module |
| coins | `account.balance`, another module |

### Fix

**The row carries the value, and the server does the ordering.**
`StudentListItemDto` gained `timeCoinBalance` beside the minutes it already had,
read by `StudentProgressQueryRepository.balanceByStudent` — the same raw-SQL
boundary argument as the minutes, and for the same reason: a Java dependency on
`account` is not available here.

`StudentController.SORTABLE` now accepts `gradeLevel`, `completedMinutes` and
`timeCoinBalance`. None of them can appear in an `order by`, so
`StudentServiceImpl` resolves the scope and filters to the whole matching list
first, reads the three values for all of it, and orders and slices that — the
arrangement the progress filter already uses, with the same under-two-hundred-
student justification. Ties break on `id` ascending, matching the stable-order
guarantee `PageableFactory` appends to every database sort; without it a page
boundary could show a student twice or not at all.

**The browser stopped re-sorting.** `CLIENT_ONLY_SORT_KEYS` is gone from
`StudentList.tsx`, and so is the `GET /accounts?pageSize=200` fetch it used to
run alongside every roster request to get balances.

**Changing the sort now returns to page 1.** `usePagedApi.setSort` was the one
control that did not reset the page — search, filter and page size all did.
Harmless while a sort only reordered the page you were already on; once it
reorders the roster, asking for "most hours first" from page 3 lands you in the
bottom third of the new order, and the top of the list you just asked for is the
one thing you never see. This applies to every list page, not just this one.

### Verified

Against the deployed server, walking **every page** of each sort and checking
the concatenation — not just that each page looks sorted, which is exactly what
the bug did:

```
completedMinutes desc  rows=56 total=56 pages=3 ordered=True unique=True 1800..0
completedMinutes asc   rows=56 total=56 pages=3 ordered=True unique=True 0..1800
timeCoinBalance  desc  rows=56 total=56 pages=3 ordered=True unique=True 30450..0
timeCoinBalance  asc   rows=56 total=56 pages=3 ordered=True unique=True 0..30450
gradeLevel       desc  rows=56 total=56 pages=3 ordered=True unique=True 12..6
gradeLevel       asc   rows=56 total=56 pages=3 ordered=True unique=True 6..12
```

56 rows out of 56, no duplicates and no losses across the page boundaries.
Cross-checked against a *different* endpoint that sorts in the database —
`GET /accounts?sort=-balance` and `GET /students?sort=-timeCoinBalance` return
the same five students in the same order, top to bottom.

Controls, which must not change: `sort=displayName` still comes back
alphabetically, and `sort=guardianName` still fails with `Cannot sort by
'guardianName'.` — the validation was widened, not removed.

In the browser, Aquaticism's case:

```
before   Hours ↓ → page 1 topped out at 11.5h; the 30h student was unreachable
after    Hours ↓ → Alex L.(李*红) 30h 120%, first row
         Coins ↓ → 304.5 first (was 152.5, which is now 4th)
         Grade ↑ → G6…G12, not G10 before G6
         On Track ≥50% + Hours ↓ → 1 row, the same 30h student, "1 students"
         sort changed while on page 3 → back to page 1
```

## 4b. Still page-only, and not fixed here

| Page | Sort | Why not fixed |
| :--- | :--- | :--- |
| Project Management | registrations | `enrolledCount` has no backend column |
| Project Center | registrations | same |

Raised rather than quietly left: the options are to make them server-sortable or
to remove the controls, and removing a control is a product decision.

Time Coin Accounts is no longer blocked — `GET /students` now sorts by
`timeCoinBalance` — but that screen has no sort control to restore it to, so
adding one is the same product decision rather than part of this fix.

## 5. What it must not do

- **Must not filter or sort a page and present it as the list.** Either the
  server does it, or the control does not exist.
- **Must not report a total that a filter does not apply to.**
- **Must not keep the per-row progress fetch.** It is the same defect: a list
  endpoint that does not carry what the list is about.
