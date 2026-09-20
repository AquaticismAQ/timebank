# 135 — A teacher cannot compare their grades to the school

**Status:** **Done and deployed** 2026-09-20. Aquaticism's request, in three parts: the
service-hours trend should carry a second line for the teacher's own grades; the
category pie should be two pies, school and own grades; and Top Projects should
get the same two tabs.

## 1. What the reports said before

Everything on the Reports page was school-wide, for everybody. A teacher
responsible for three year groups could see the school's totals and nothing
about their own — which is the one figure they can act on. Issue 127 had already
done this for the dashboard's headline numbers; the reports were still the
school's alone.

## 2. Comparison, not replacement

Every figure keeps its school-wide form and **gains** a scoped one:

| Report | Added |
| :--- | :--- |
| `/reports/service-hours` | `byDayInScope` — a second series on the same 365 points |
| `/reports/top-projects` | `topProjectsInScope`, `byCategoryInScope` |

The school-wide figure is the baseline a teacher is reading against, so removing
it would answer a narrower question than the one being asked.

**All three are empty for a school-wide caller.** An administrator already sees
everything; a second identical line, a second identical pie and a tab holding
the same ten rows are three controls that do nothing. The UI renders the extras
only when the data is there, so an administrator's page is unchanged.

## 3. Scoped through the student, not the project

The scoped figures count through the **enrolled student** — and, for hours, the
record's student — rather than the project's eligible grades.

This is the substantive choice. A project open to four year groups is not four
times any one teacher's: "participation in my grades" is a fact about *who took
part*, not about who was allowed to. Using eligible grades would have credited a
whole school-wide project to every teacher it was open to, and the three
percentages would not have added up to anything.

## 4. Shape on screen

- **Trend**: one chart, two lines, a legend that appears only when there are two
  of them. Same colours as everywhere else; the scoped line is amber against the
  school's indigo.
- **Categories**: two pies side by side rather than stacked — the point is the
  comparison, and a scroll between them defeats it. Drawn by one shared
  component, so they cannot drift apart.
- **Top Projects**: two tabs on the existing list, defaulting to the school.

## 5. Verification

Both roles, because the administrator is the control — a change that showed
everyone two of everything would pass a teacher-only check:

```
teacher005 (grades 10-12)
  API     school 365d/14400min · scoped 365d/5430min · cats 5→3 · top 10→4
  trend   2 lines, legend ["全校","我的年级"]
  pies    2, headed 各类服务参与分布·全校 and ·我的年级
  tabs    ["全校","我的年级"], rows 10 → 4 on switching
  errors  []

admin001 (school-wide)
  API     every scoped array empty
  trend   1 line, no legend
  pies    1, no scope suffix
  tabs    none
  errors  []
```

The scoped numbers are strict subsets of the school's, which is the sanity check
that they are narrowing rather than querying something else.

## 6. Two things this cost, worth recording

**`mvn -o -q test-compile` piped through `grep` told me nothing and I read that
as success.** The remote deploy then failed on two test files that had not been
updated for the new signatures. The exit code was there to be checked and was
not. Same class of mistake as issue 133's: a filtered command's silence is not a
pass.

**A regex ate a nested paren.** `\(([^)]*)\)` applied to
`reportService.serviceHours(PAST_YEAR.id())` stopped at the inner `)` and
produced `serviceHours(PAST_YEAR.id(, null))`. Caught by the compiler
immediately, but it is the reason the four call sites were fixed by hand.

## 7. What it must not do

- **Must not replace the school-wide figures.** §2 — they are the baseline.
- **Must not show an administrator two of everything.** §2.
- **Must not scope by the project's eligible grades.** §3.
- **Must not stack the two pies.** §4.
