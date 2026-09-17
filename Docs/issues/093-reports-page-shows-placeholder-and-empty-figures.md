# 093 — Reports page shows placeholder and empty figures

**Status:** In progress. Findings 2, 3, 5 fixed and live-verified; 6 and 7 (found
while fixing) fixed; 1 implemented but **not yet live-verified** (no deploy or
browser pass in the session that built it); 4 in progress.
**Reported:** 2026-09-17 by the user, as five symptoms on the Reports page.

Filed as one document rather than five: they arrived as one report about one
screen, and three of them share a root cause — content that was mocked while the
page was built and never replaced when the real endpoints landed.

## 1. The academic-year filter does nothing

**Symptom:** changing the year changes nothing on the page.

**Cause, in two layers.** `ReportsPage.tsx` holds `yearFilter` in state and never
reads it: `useEffect` has `[]` dependencies and calls `api.reports.data()`, which
takes no argument. The `<option>` values are hardcoded `2025-2026` / `2024-2025`
rather than read from the academic-year API.

Underneath that, **no report endpoint accepts an academic year at all** —
`participation`, `completion`, `service-hours`, `review-efficiency` and
`top-projects` take a language and nothing else. So this cannot be fixed by
wiring the existing control up; the filter has no server-side counterpart to
call.

**Decided: implement it**, rather than removing the control. Every report
endpoint now takes an optional `academicYearId`, and the dropdown is populated
from `GET /academic-years` with the current year selected on load.

**What "scoped to a year" means is not the same for all five.** Nothing in the
schema tags a student or a service record with an academic year, so each report
is bounded by the nearest thing it actually has (contract §4.13 carries the same
table):

| Report | Bounded by |
| :--- | :--- |
| participation | service records **dated** in the year; population = active students already enrolled by the end of it |
| completion | same, judged against the required minutes in effect **during** that year, not today's rule |
| service-hours | approved records **dated** in the year, by month |
| review-efficiency | decisions **taken** in the year — when the reviewer decided, not when the service happened |
| top-projects | projects **scheduled** in the year (`project.start_at`), hours from records dated in it |

Two things could not be done honestly and are not faked. A past year's
population is "students active *now* who had enrolled by then":
`student.status` is a present-tense fact and there is no historical roster, so
anyone who has since left is missing from it. (The enrolment side *is*
corrected — `student.enrollment_academic_year_id` keeps a cohort that started
after the year out of its denominator, where it would otherwise sit as a
guaranteed non-participant.) And export jobs carry no period, so a report export
stays current-year until `POST /exports` grows a field for it.

**Default when no year is given: the current one** — not "all years", and not a
404. Four of the five endpoints already resolved the current year internally, so
for them this is the behaviour that was already there, now merely nameable. For
`/reports/top-projects` it is a **change**: it had no period at all and ranked
every project ever run, under a page header naming a single year. It is now the
current academic year like the rest, so callers of that endpoint (including the
System Admin `topProjects` export) will see fewer projects than before. An
`academicYearId` that matches no year is a 404 rather than a quiet fall back to
the current year — otherwise a caller with a bad id gets real-looking numbers
for a year it never asked about, which is this same bug wearing a different hat.

One more card had to move with it: "Total Service Hours" read
`stats.totalServiceHours` from `GET /dashboards/admin`, which is computed for
the current year and takes no year parameter — it would have kept showing this
year's figure under another year's header. It is now summed from the year-scoped
trend the page already loads. "Active Projects" and "Approved This Month" are
left as they are: both name their own period and are deliberately "right now"
figures.

## 2. The Participation Rate card always shows `—`

**Symptom:** the card renders an em dash, never a number.

**Cause:** the card reads `stats.avgCompletionRate`, and `GET /dashboards/admin`
does not return that field — it returns `totalStudents`, `activeStudents`,
`totalProjects`, `activeProjects`, `pendingReviews`, `approvedThisMonth`,
`totalServiceHoursThisYear`. The API client's own comment says so outright: "no
`avgCompletionRate` at all". The field is optional in `AdminStats` and nothing
ever populates it, so the fallback branch is the only reachable one.

There is a second, quieter bug in the same line: the card is **labelled**
Participation Rate but was reading a **completion** field. Those are different
measures — participating at all versus meeting the requirement.

**Fix:** compute the real participation rate from data the page already loads,
`sum(participated) / sum(total)` over `participationByGrade`. No backend change,
and it now matches its own label.

## 3. Service Hours Trend: the stated peak and lowest months contradict the curve

**Symptom:** the peak/lowest cards disagree with the plotted line.

**Cause:** they were never derived from the data. Both are fixed i18n strings —
`"peakMonthValue": "April · 620h"`, `"lowestMonthValue": "February · 90h"` — with
invented supporting copy ("Typical spring charity peak", "Reduced activity during
winter break"). This is mock content from before the endpoint existed; the
2026-08 mock-data purge removed the mock *data* but left these strings, because
they live in the translation bundle rather than in a mock module.

**Fix:** derive both from the trend series actually being plotted, and delete the
invented descriptions. A number that disagrees with the chart beside it is worse
than no number.

## 4. Top Projects all show 0 hours while the pie chart is populated

**Symptom:** the Projects Distribution pie shows a sensible spread; every row in
Top Projects reads `0h`.

**Cause: the data, not the report.** The query is correct —

```sql
join enrollment e on e.project_id = p.id and e.status in ('CONFIRMED','COMPLETED')
left join service_record sr on sr.project_id = p.id and sr.status = 'APPROVED'
```

The pie uses `participants`, which comes from `enrollment` and is real. The hours
come from approved service records joined on `project_id`, and on the live server:

- 229 approved service records
- **0** of them have a `project_id`
- all 229 are `source_type = SELF`

So `sum(duration_minutes)` is legitimately 0 and the page is reporting the truth.
The seeder produces no `PROJECT`-sourced records at all, so this dataset cannot
exercise the figure. Same family as issue 084 (students holding coins with no
service records): the seed data is internally consistent but does not cover a
path the UI displays.

**Fix:** the seeder must produce approved `PROJECT`-sourced records, and the live
dataset needs the same so the page can be verified against something.

## 5. Export duplicates System Admin → Data Import/Export

**Symptom:** the same capability in two places.

**Cause:** the Reports page grew its own export button, including its own polling
loop, before the System Admin export tab existed.

**Fix:** remove it from the Reports page. Confirmed first that the System Admin
path covers the same report exports, so nothing becomes unreachable.

## 6. "Data updated" printed a fixed date — *found while fixing, not reported*

The header read `Data updated: 2026-07-27 08:00`, hardcoded into the `t()` call.
Same family as §3: placeholder content that outlived the mock era. Every report
endpoint already returns a real `generatedAt`, which the API client was
discarding. `ReportData` now carries it and the page renders it.

## 7. System Admin's export buttons were labelled wrong — *found while fixing*

Three of the five buttons described exports that do not exist. The backend
supports exactly seven report types — `students`, `serviceRecords`,
`participation`, `completion`, `serviceHours`, `reviewEfficiency`, `topProjects`
— and there is **no audit-log or transaction export at all**. Yet:

| Label | Actually requested |
| :--- | :--- |
| Export Transactions | `serviceHours` |
| Export Reports | `participation` |
| **Export Audit Logs** | **`completion`** |

An administrator clicking "Export Audit Logs" during an incident would have
received a completion report. Labels now name what each button produces, and the
two types that had no button at all (`reviewEfficiency`, `topProjects`) are
reachable, so the UI covers exactly what the backend supports — no more, no less.
