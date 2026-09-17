# 095 — List search, filter and sort must be fully server-side

**Status:** Open — specified, not implemented.
**Raised:** 2026-09-17 by the user, after asking how much data the list screens
load at once.

## 1. The requirement

**Every list screen's search, filter, sort and pagination must execute on the
server, over the whole matching set — never over the rows that happen to be
loaded.** A page of results must be a true slice of everything that matches, and
the pager's totals and Previous/Next must describe that same matching set.

Concretely:

- **Search** gets an icon **inside** the input, at its trailing edge — not a
  separate button beside it. Search runs when the icon is clicked or **Enter** is
  pressed. **It is not live.** Typing changes nothing until the user commits.
- **Filter and sort** each trigger a fresh request carrying the *current
  combination* — the committed search text plus every active filter plus the
  sort. They are not layered on top of an already-fetched page.
- **Pagination** — total items, total pages, and Previous/Next — reflects the
  active search and filters, not the unfiltered table.
- **To see everything again, the user clears the search.** There is no implicit
  reset.

## 2. Why: the current behaviour gives silently wrong answers

Nothing loads the full set today — the default page is 20, and the backend hard
caps at 100 (`PageQueryArgumentResolver.MAX_PAGE_SIZE`, verified enforced:
asking for 5000 returns 100). That part is sound.

The problem is that several screens then **filter and sort the loaded page in
the browser while presenting the result as if it covered everything.** At the
seeded 50 students this is invisible. At 2,000 it is a correctness bug, not a
performance one:

> An admin selects "below 25%" on Student Management, sees 3 students, and the
> pager still reads 100 pages. The honest answer might be 300. Nothing on screen
> distinguishes "3 students are below 25%" from "3 of the 20 rows I happen to
> have loaded are below 25%".

Sorting has the same shape: sort by time-coin balance and you get the lowest of
*that page*, presented as the lowest overall.

`StudentList.tsx` is candid about it in a comment — *"all three stay client-side
(current page only)"*, and the summary tiles *"read as 'on this page', not
school-wide"* — but **the UI says none of this**, and the reader has no way to
know. This is the same family as issue 093 §1 and the filters in issue 094: a
control that looks authoritative while answering a narrower question than the
one asked.

A secondary problem: **search currently fires a request per keystroke**
(`onChange` → `setSearch` → refetch). At 2,000 rows that is a burst of queries
nobody reads, and it is why the committed-search model above is wanted.

## 3. Current state, per screen

Sixteen screens use the shared `PageControls`. Pagination is server-side on all
of them. What differs is whether the *controls above the table* are:

| Screen | Client-side filter/sort remaining |
| :--- | :--- |
| `StudentList.tsx` | **Worst case.** 3 client-only sorts (`gradeLevel`, `completedHours`, `timeCoinBalance`) + the progress filter (`onTrack`/`below50`/`below25`) + page-only summary tiles |
| `ProjectManagement.tsx` | 3 client-only sorts + local filtering |
| `ProjectCenter.tsx` | 3 client-only sorts |
| `AccountManagement.tsx` | local filter + local sort |
| `ProjectRegistrants.tsx` | local filter |
| `SystemAdmin.tsx` (Users) | **None — already fully server-side** (issue 094) |
| `AuditCenter`, `CaseReview`, `ProposalReview`, `ServiceRecords`, `StudentRecords`, `StudentCases`, `EventProposals`, `NotificationsPage`, `AccountPage`, `OrganizationManagement` | none found |

## 4. What the backend is missing

Only two list endpoints accept any filtering parameters at all today:

| Controller | Query parameters |
| :--- | :--- |
| `StudentController` | has some |
| `UserController` | has some (added by issue 094) |
| `ProjectController`, `ServiceRecordController`, `CaseController`, `ProposalController`, `AccountController`, `OrganizationController` | **none** — `PageQuery` only |

So this is mostly backend work. Each endpoint needs its screen's filters and
sorts, and each sort key needs a real column or a read model.

Two known traps, both already solved once and worth reusing rather than
rediscovering:

- **`gradeLevel` cannot sort on `grade_id`** — it sorts lexically, putting
  `grade_10` before `grade_6`. Issue 094 solved this by sorting on
  `grade.sequence`; `StudentList`'s comment claiming the backend "deliberately
  doesn't offer it" is now out of date.
- **`completedHours` / `timeCoinBalance` are record-derived aggregates** with no
  column to sort on. They need either a read model (the `UserListQueryRepository`
  precedent from issue 094) or a materialised figure.

## 5. Acceptance

A filter or sort that is accepted and ignored looks identical to one that works —
this repository has shipped that bug more than once (issues 093 §1, 094). So for
each screen:

- With a data set **larger than one page**, applying a filter must change
  `totalItems`, not merely the visible rows.
- A sort must be verified across a **page boundary** — the last row of page 1
  must order correctly against the first row of page 2. A sort that only orders
  within a page passes any single-page test.
- Searching then clearing must return `totalItems` to its unfiltered value.
- Every combination of search + filter + sort must be exercised together, since
  the bug being fixed is precisely that they were applied at different layers.

Note the seeded data set (50 students, 37 users) is smaller than two default
pages for most screens, so it **cannot** demonstrate any of this. Verification
needs a larger fixture — see issue 093 §4 for the same lesson, where an empty
data set hid a real defect for as long as it stayed empty.
