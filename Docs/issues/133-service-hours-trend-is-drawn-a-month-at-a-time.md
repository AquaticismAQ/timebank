# 133 — The service-hours trend is drawn a month at a time

**Status:** **Done and deployed** 2026-09-20. Aquaticism's request: keep the x-axis
marked in months, draw the curve in days, and rename the chart to *Service Hours
Trend*.

## 1. Why the shape was unreadable

Twelve points across a school year is too coarse to say anything. One busy
weekend and a quiet fortnight average into the same flat line, and the thing a
trend is for — seeing *when* service actually happens — is exactly what gets
smoothed away. The live data makes the point: 240 hours across the year, and
**all of it falls on 18 days**, all in September. Monthly, that is one tall bar
and eleven empty ones.

## 2. Two resolutions, not one

`GET /reports/service-hours` now returns both:

| Field | Shape | Used for |
| :--- | :--- | :--- |
| `byMonth` | `{month, hours}` | the Peak / Lowest **month** cards beside the chart |
| `byDay` | `{day, minutes}` | the curve |

The monthly figures stay rather than being derived from the daily ones: the
summary beside the chart is a statement *about months*, and computing "peak
month" from a daily series would answer a subtly different question.

**Minutes, not hours.** `byMonth` divides by 60 as integers, which is fine for a
month and rounds a 30-minute day to nothing. Durations are integer minutes
everywhere else on the wire; the client formats.

**Every day appears, zero-minute days included.** A line chart spaces its points
evenly however far apart their dates really are — a sparse series would draw a
fortnight's gap the same width as a weekend and quietly misstate the shape. 365
points, filled server-side.

## 3. The axis

Month ticks on a daily series: the first day of each month, which is a value the
axis actually holds. A label per day is unreadable and nobody navigates a school
year by date. Per-point dots are off — 365 of them is a solid band, not a line.

Renamed to **Service Hours Trend** / 服务时长趋势. The key was renamed too
(`monthlyHoursTrend` → `hoursTrend`) rather than left saying "monthly" in the
bundle while the chart no longer is.

## 4. Verification

The API, with the cross-check that matters — two resolutions of one year must
agree on its total:

```
days 365   2026-09-01 … 2027-08-31   daysWithService 18
monthTotalHours 240   dayTotalHours 240      ← the same year, counted both ways
busiest: 2026-09-12 2730min · 2026-09-05 2520min · 2026-09-06 2220min
```

And the rendered chart:

```
heading:       服务时长趋势
x-axis ticks:  2026-09 … 2027-08   (12, one per month)
curve segments: 365                 (one per day)
```

## 5. A page I broke on the way, and how

The first version put the two derived series behind `useMemo` — **below the
`if (!data || !stats) return …` guard** that sits above them. Hooks after a
conditional return run on some renders and not others, so React threw #310 and
the whole Reports page rendered blank. It was deployed in that state for a few
minutes before a probe caught it.

They are plain consts now, and the comment says why: a 365-element map is not
worth a hook, and a hook there is not safe. **The lesson is about the check, not
the mistake** — `tsc` passes a hooks-order violation happily, and so does a
screenshot of a different tab. What found it was loading the page and reading
the console.

## 6. What it must not do

- **Must not drop `byMonth`.** §2 — the cards beside the chart are about months.
- **Must not send a sparse day series.** §2.
- **Must not label every day.** §3.
- **Must not put a hook below the loading guard.** §5.
