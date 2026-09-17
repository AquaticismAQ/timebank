# 093 — Reports page shows placeholder and empty figures

**Status:** In progress. Findings 2, 3 and 5 fixed; 4 is a seed-data gap; 1 needs a decision.
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
