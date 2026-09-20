# 136 — The report cards show one reading where a teacher needs two

**Status:** **Done and deployed** 2026-09-20. Aquaticism's request: the four summary
cards at the top of Reports should carry two values — the school's and the
teacher's own grades'.

## 1. Four cards, three different problems

They are not one change repeated four times, and it is worth saying which is
which:

| Card | Where it came from | What was missing |
| :--- | :--- | :--- |
| Total Service Hours | summed from the trend series | the scoped series existed already (issue 135) — just unused here |
| Participation Rate | derived from the per-grade participation report | nothing: the report already answers this a grade at a time |
| Active Projects | `/dashboards/admin` | **the school-wide figure** — this one is already scoped |
| Approved This Month | `/dashboards/admin` | same |

The last two are the interesting case. Issue 127 made the dashboard stats scoped
to the caller, so for a teacher those two cards were **already** their own
grades' numbers, silently, under a heading that said nothing about scope. The
addition there is the *school-wide* counterpart, not the scoped one.

## 2. What was added, and where not to add it

`AdminStatsDto` gains `activeProjectsSchoolWide` and
`approvedThisMonthSchoolWide`, **null for a caller who is already school-wide** —
an administrator's two readings are the same number, and the second query to
fetch it is work done to say nothing twice. The dashboard itself does not render
them; only Reports does.

Participation is filtered out of the per-grade breakdown the page already loads
rather than fetched again. A second endpoint would be a second definition of the
same rate, and two screens disagreeing about "participation" is a worse outcome
than one extra `filter`.

## 3. Which number gets the large type

The **school-wide** figure keeps it, with the scoped one beneath, named:

```
240h
91h  我的年级
总服务时长
```

The scoped line is labelled because an unlabelled second number is a riddle —
and the school figure stays dominant because it is the baseline the other is
being read against. An administrator sees the card exactly as before.

## 4. Verification

Both roles, the administrator as the control:

```
teacher005 (G10-12)
  API    activeProjects 5 / schoolWide 13 · approvedThisMonth 39 / schoolWide 108
  cards  240h + 91h 我的年级 · 55% + 58% 我的年级 · 13 + 5 · 108 + 39
  errors []

admin001
  API    no *SchoolWide fields at all
  cards  240h · 55% · 13 · 108        (one reading each, unchanged)
  errors []
```

**Participation reads 58% against the school's 55%** — the teacher's grades take
part slightly *more* than average. Worth pointing at: it is the one figure here
where the scoped value is not simply a smaller copy of the school's, which is
what a card showing a share rather than a total should do.

## 5. What it must not do

- **Must not query the school-wide figures for an administrator.** §2.
- **Must not fetch participation a second time.** §2 — one definition of the
  rate.
- **Must not lead with the scoped number.** §3 — the school figure is the
  baseline.
- **Must not leave the second line unlabelled.** §3.
