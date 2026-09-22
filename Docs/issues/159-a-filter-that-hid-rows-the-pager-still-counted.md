# 159 — A filter that hid rows the pager still counted

**Status:** **Fixed and deployed 2026-09-23.** Aquaticism: on Student Management, filter to
*On Track ≥50%* and the page shows no students — but the pager says three pages,
and the last page has them. Asked for the root cause and for every list page to
be checked for the same thing.

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
| **Student Management** | status bucket **filter** | **rows disappear, pager lies** — the reported bug |
| Student Management | sort by grade / hours / coins | orders one page, presented as ordering the list |
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

## 4a. Still page-only, and not fixed here

The **sorts** are the same defect in a quieter form, and three remain:

| Page | Sort | Why not fixed |
| :--- | :--- | :--- |
| Student Management | grade / hours / coins | needs the same aggregate work again, as sortable columns |
| Project Management | registrations | `enrolledCount` has no backend column |
| Project Center | registrations | same |

Measured rather than assumed, on Time Coin Accounts before its fix: page 1's
highest balance was **152.5** coins and page 2 held **304.5**. "Sort by balance"
was showing the top of a page.

These are left because each needs a sortable server column, and inventing three
more aggregate sorts is a larger change than the bug reported here. Raised rather
than quietly left: the options are to make them server-sortable or to remove the
controls, and removing a control is a product decision.

## 5. What it must not do

- **Must not filter or sort a page and present it as the list.** Either the
  server does it, or the control does not exist.
- **Must not report a total that a filter does not apply to.**
- **Must not keep the per-row progress fetch.** It is the same defect: a list
  endpoint that does not carry what the list is about.
