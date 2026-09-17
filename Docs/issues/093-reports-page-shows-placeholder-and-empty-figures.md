# 093 — Reports page shows placeholder and empty figures

**Status:** Closed. All nine findings fixed, deployed, and verified in a browser against the live server (9/9).
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

## 8. Top Projects multiplied its hours by the participant count — *found while fixing §4*

Fixing §4's data immediately exposed a second, real bug underneath it. With
three approved records totalling **150 minutes** linked to one project, the
report returned **600**.

The query joined `enrollment` *and* `service_record` and grouped:

```sql
count(distinct e.student_id) as participants,
coalesce(sum(sr.duration_minutes), 0) as total_minutes
from project p
join enrollment e on ...
left join service_record sr on ...
group by p.id, p.title
```

Joining both tables fans the rows out — each approved record is paired with
every confirmed enrollment — so the sum came back multiplied by the participant
count: 150 × 4 enrollments = 600. `count(distinct ...)` silently absorbed the
same fan-out on the other column, which is why only the hours looked wrong.

**This is why §4 mattered beyond cosmetics.** While no record had a
`project_id`, every sum was 0, and 0 × 4 is still 0 — the defect was invisible
precisely because the dataset never covered the path. A report that is "showing
zero" is not the same as a report that is correct.

**Fix:** compute the two aggregates as independent scalar subqueries so neither
can fan out, with `exists` preserving the original "projects that have
registrants" filter. Verified live: the report now returns 150, matching the
database.

## 9. Top Projects rounded 150 minutes to "3h" — *found during browser verification*

The browser pass reported §8 as passing while showing `3h` for a project holding
150 minutes. 150 minutes is **2.5** hours; `Math.round(150/60)` is 3 — a 20%
overstatement on a service-hours figure.

The API client mapped `totalHours: Math.round(p.totalMinutes / 60)`, while the
rest of the application formats durations with `lib/format.ts`'s
`minutesToHours`, which yields `2.5`. `ReportData` now carries `totalMinutes`,
matching the repo's stated "minutes on the wire, derive hours" convention, and
the page formats with the shared helper.

Worth noting how this was nearly missed: the check asserted the page showed
`Math.round(minutes/60)` + `"h"` — it computed the expected value the *same
wrong way* the code did, so it agreed with the bug. It now asserts the exact
figure (`2.5h`).

## Browser verification

All nine verified against the live server with Playwright:

| § | Check | Result |
| :--- | :--- | :--- |
| 1 | Year options come from the API; switching years changes the page | options `ay_2025_2026`, `ay_2024_2025`; switching changed the page |
| 2 | Participation Rate is a number | `50%` |
| 3 | Peak/lowest match the plotted series | `2026-07 · 194h` / `2026-01 · 7h`, matching series max/min |
| 4 | Top Projects shows real hours | 150 min on one project |
| 5 | Export removed from Reports | 0 occurrences of "Export" |
| 6 | Data updated is live | a real timestamp, not `2026-07-27 08:00` |
| 7 | Export labels honest | no "Export Audit Logs"; "Participation Report" present |
| 8 | Hours not inflated | API 150 = DB 150 (was 600) |
| 9 | Hours formatted correctly | renders `2.5h`, not `3h` |

§1 needed a second academic year to be testable at all — with only one year
seeded, the check could confirm the parameter was *sent* but not that it changed
anything. `ay_2024_2025` was added to the live server for that: scoped to it the
reports return 0 months / 0h, against 8 months / 560h for the current year.
