# 115 — Case Review and Proposal Review are not scoped to an academic year

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report.
**Reported as:** the Case Review and Proposal Review pages need an academic year
filter, defaulting to current.

## 1. What is wrong

Both are review queues, and neither has any notion of a year. `GET /cases` takes
`status`, `type`, `gradeId` and `search`; `GET /event-proposals` takes `status`,
`categoryId`, `gradeId` and `search`. As years accumulate, a screen built for
working through what came in this term fills with closed items from every term
the system has ever held — the same defect issue 112 fixed for Project
Management.

## 2. What "an academic year's cases/proposals" means

Neither has an academic-year column, so the rule has to be derived, and the two
derive it **differently on purpose**.

| Resource | Key | Why |
| :--- | :--- | :--- |
| `Case` | `createdAt` | A case has no event of its own — it is an action taken *about* something else, and its only dates are creation, submission and last update |
| `EventProposal` | `plannedStartAt` | Approval generates the official project in the same transaction, and `GET /projects` scopes that project by `startAt` (issue 112) |

The proposal rule is the load-bearing one. Dating a proposal by when it was
written would let a proposal and the project it becomes land in **different
years** — two screens disagreeing about one event, which is exactly what issue
112 §4 forbade.

For cases, `createdAt` rather than `submittedAt` because **a draft has no
`submittedAt`**, and a student's own case list shows drafts. Keying on a nullable
column would drop every draft out of every year. The two differ only for a case
drafted either side of the turnover date.

**The window comes from the stored `academic_year` row**, never from arithmetic
on the turnover date, which is configurable (`TIMEBANK_ACADEMIC_YEAR_START`).

## 3. What it should do

### 3.1 Backend

- Both endpoints accept `filter[academicYearId]`.
- **Absent means every year** and **an unknown id is a 404**, matching
  `/projects` (issue 112 §3.1).
- **No student pin**, unlike the Project Center. Both endpoints also serve the
  students' own case and proposal history, and last year's appeal is something a
  student is entitled to read — pinning would hide their own past from them. The
  current-year default therefore lives in the two review screens, not in the API.

### 3.2 Frontend

A year dropdown beside the existing filters on both pages, defaulting to the
current year, offering **All years** so nothing becomes unreachable.

## 4. One rule, not three

Projects (issue 112) had this logic inline in `ProjectServiceImpl`. Adding two
more copies would be three chances for one of them to drift into answering a bad
id with a good-looking page, so it was extracted to
`student.application.AcademicYearWindowResolver` — the `student` module owns
academic years — and `ProjectServiceImpl` was moved onto it in the same change.

Deliberately **not** merged with `ReportAcademicYearResolver`, which reads an
absent parameter as *the current year*. That is right for a report, a snapshot of
now, and wrong for a general-purpose list several screens share. Two rules,
deliberately, each stated where it applies.

On the frontend the same shape repeated, so `useAcademicYearFilter` holds it. Its
one subtlety is an `applied` flag: without it the effect cannot tell "not chosen
yet" from "chose All years" and re-applies the current year the moment a user
picks All — a control that undoes itself. It starts **true** when the URL already
names a year, because these screens sync filters to the query string and a copied
link pinned to a past year must open on that year.

## 5. Verification

`CaseAndProposalAcademicYearScopeIT` puts one case and one proposal in the
current year and one of each in a past year — the only arrangement that can tell
scoping from no scoping. The two proposals are given **identical `created_at`**
and differ only in `planned_start_at`, so `proposalsAreScopedByTheirPlannedEventNotWhenTheyWereWritten`
fails if the implementation keys on creation instead.

Red-checked: removing both specifications fails 4 of the 6, while the two
asserting "absent means every year" correctly still pass. `ProjectAcademicYearScopeIT`
and `ProjectLifecycleIT` were re-run after the resolver extraction and are
unchanged. `mvn test` 353, 0 failures.

**Verified live.** Through the API, both resources: no parameter and the current
year each return 4, a past year returns 0, and an unknown id returns
`404 No academic year with id ay_nope`. In the browser, both screens load on
2026-2027 with the year written into the URL; switching to 2025-2026 empties the
list, All years restores it and removes the key; and **opening a link that names
2025-2026 stays on 2025-2026** rather than being overwritten by the default.

The live server holds no cases or proposals outside the current year, so the live
pass proves the filter executes and bounds correctly but cannot show a past-year
row being hidden. That half rests on the IT's two-year fixture.

## 6. What it must not do

- **Must not date a proposal by when it was written.** It must agree with the
  project its approval creates.
- **Must not key cases on `submittedAt`.** Drafts would vanish.
- **Must not pin students to a year.** They must be able to read their own past
  appeals and proposals.
- **Must not recompute the year boundary** from the turnover date.
- **Must not drop All years**, or past data becomes unreachable through the UI.
