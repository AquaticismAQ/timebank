# 093 — Reports page shows placeholder and empty figures

**Status:** In progress. Findings 2–8 fixed and live-verified; 1 (year filter) in progress.
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

**Not fixed — needs a decision**, because the options differ a lot in size:
implement year scoping across five endpoints and their queries, or remove the
control until it is implemented. A dropdown that silently does nothing is the
worst of the three, so it should not stay as it is either way.

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
