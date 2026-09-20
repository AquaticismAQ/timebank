# 112 — Project lists are not scoped to an academic year

**Status:** **Fixed and deployed** 2026-09-20, same day. Verified against the
Docker stack, then re-verified on the deployed server.
**Reported as:** Project Management (admin/teacher) should have an academic year
filter defaulting to the current year; the student Project Center should show only
the current year's projects.

## 1. What is wrong

`GET /projects` accepts `status`, `categoryId`, `gradeId` and `search`. It has **no
academic year filter at all**, so both screens show every project the account can
see, from every year the system has ever held, in one list.

- **Project Management** (`components/admin/ProjectManagement.tsx`) has status,
  category and grade filters and no year. As years accumulate, a page built for
  running this year's projects fills up with closed ones.
- **Project Center** (`components/student/ProjectCenter.tsx`) is a student's
  "what can I sign up for" screen, and it lists projects from previous years
  alongside the live ones. Those are not joinable, so every one of them is noise
  at best and a misunderstanding at worst.

## 2. What "an academic year's projects" means

A `project` row has **no academic year column** — it has `start_at` and `end_at`.
The scoping rule therefore has to be derived, and it already exists twice in this
codebase, identically:

| Existing | Rule |
| :--- | :--- |
| `/reports/top-projects` (`APIContract.md` §4.13) | "Projects **scheduled** inside the year (`startAt`)" |
| The `projects` export (`ProjectReviewExportRepository.projectsPage`) | `p.start_at >= yearFrom and p.start_at < dayAfter(yearTo)` |

This change uses the same rule, so the Projects Report and the Project Management
page cannot disagree about which year a project belongs to.

**The window comes from the stored `academic_year` row**, never from arithmetic on
the year number. The turnover date is configurable
(`TIMEBANK_ACADEMIC_YEAR_START`), and a year keeps the dates it was created with,
so recomputing 1 September here would drift the moment either changed — the same
correction already applied to the exports.

## 3. What it should do

### 3.1 Backend

- `GET /projects` accepts `filter[academicYearId]`.
- **Absent means no year restriction**, which keeps every existing caller behaving
  as it does today. This differs deliberately from `/reports/*`, where absent
  means the current year: a report is a snapshot of now, while `/projects` is a
  general-purpose list several screens and any future integration share, and
  silently narrowing it would change behaviour nobody asked to change.
- **An unknown id is a 404**, not a silent fall back to the current year —
  matching `ReportAcademicYearResolver`'s reasoning: a caller asking for a year
  that does not exist has a bug, and answering with a different year's projects
  hides it behind rows that look real.
- **A student is pinned to the current academic year, server-side**, whatever they
  pass. Aquaticism's requirement is that the Project Center shows only the current year;
  enforcing that in the query rather than the component means it cannot be
  sidestepped by editing a request, the same reasoning that pins a teacher's
  Review History to their own decisions (`12-DataExport.md` §6.7).

### 3.2 Frontend

- **Project Management**: a year dropdown beside the existing filters, defaulting
  to the current year, and offering **All years**. Without an all-years option a
  project from a past year becomes unreachable through the UI, and making existing
  data invisible is worse than a longer list.
- **Project Center**: no control. It is pinned to the current year by the server,
  and a dropdown offering a choice that the server overrules would be a lie.

## 4. How it was built

- `ProjectServiceImpl.academicYearSpecification` joins the existing filter chain;
  `ProjectService.list` and `ProjectController` carry the new parameter, and
  `DashboardServiceImpl` passes `null` at its two call sites (its own year scoping
  is separate).
- `ProjectManagement.tsx` reads `academicYears` from `useDictionaries()` and applies
  the current year once the dictionary resolves, guarded so a user's own choice is
  never overwritten by a late load.
- `ProjectCenter.tsx` got a comment only, recording *why* it has no year control.

## 5. Verification

`ProjectAcademicYearScopeIT` seeds one project in the current year and one in a past
year — the only arrangement that can tell scoping from no scoping, since a test with
all its projects in one year passes either way. It covers absent-means-every-year,
narrowing and coming back, the student pin, and the 404. Removing
`academicYearSpecification` from the filter chain fails three of the four while
`absentMeansEveryYearForStaff` correctly still passes.

Then against the running Docker stack, whose database holds **3 projects in
2025-2026 and 10 in 2026-2027**:

| Call | Result |
| :--- | :--- |
| staff, no parameter | 13, of which 3 start before 1 September |
| staff, current year | 10, none before 1 September |
| staff, `ay_2025_2026` | exactly the 3 |
| staff, `ay_nope` | `404 No academic year with id ay_nope` |
| student, no parameter | 8, none before 1 September |
| student, `ay_2025_2026` | **the same 8** — the request is overruled |

In the browser: the dropdown loads on 2026-2027 showing 10, 2025-2026 shows the
three pre-September projects, **All years** shows 13, and the label renders in both
language bundles. The student Project Center shows 8, none of the three past-year
titles, and its filter row contains no year control.

**Re-verified on the deployed server** after the backend and frontend deploys,
which matters because the live data is a different shape from Docker's: Project
Management loaded on 2026-2027 with 10 projects and **All years** returned 13,
the extra three starting 2026-06-21, 2026-07-06 and 2026-07-21. A real student
account's Project Center returned 8, all starting on or after 2026-09-04, with no
year control among its filters.

## 6. What it must not do

- **Must not invent a second definition of "this year's projects."** If this
  disagrees with the Projects Report, one of them is wrong; they must share §2's
  rule.
- **Must not recompute the year boundary.** Read the `academic_year` row.
- **Must not give the student a year selector**, even a disabled one. The server
  decides; a control that cannot change the outcome invites the belief that it can
  (the same rule applied to one-option export filters).
- **Must not hide a project from the staff who owns it** with no way back. Hence
  All years on the management screen.
- **Must not change what `GET /projects` returns when the parameter is absent.**
