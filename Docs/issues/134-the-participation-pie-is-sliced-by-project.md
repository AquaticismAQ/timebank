# 134 — The participation pie is sliced by project

**Status:** **Done and deployed** 2026-09-20. Aquaticism's request: the popular-project
pie should be based on categories, not project names.

## 1. Why it could not be read

Ten slices, each labelled with a project title cut to eight characters —
`Neighbou`, `Autumn T`, `Campus O`, `Rural Te` — and four of them the *same*
generated name, `[AUTO] 2`, at 4% each. Nothing in that picture is a fact
anybody can use.

Worse, it answered a question the page had already answered: the **Top Projects
table sits directly beside it**, with full titles and exact numbers. The pie was
a second, less legible copy of the table.

## 2. What a pie is for here

*Which kind of service does this school actually do?* Six categories, not ten
look-alike titles, and the shape says something at a glance: **70% community
service**, then campus, environment, welfare, education. That is a sentence
about the school; "Neighbou 19%" is not.

## 3. Counting so the slices add up

`byCategory.participants` counts **enrolments**, not distinct students.

A pie is parts of a whole. Distinct-students-per-category would double-count
anyone who served in two categories, leaving slices whose percentages are of
nothing in particular. Counted as enrolments, the slices sum to exactly the
participation the project rows above describe — 77 across five categories here.

The category ids are sent, not names: the client already holds the dictionary
and renders it in the reader's language, the division of labour issue 121
settled. An id with no dictionary entry falls back to the id rather than
dropping a slice.

The year window is the project's `start_at`, matching `topProjects` — so a
project belongs to the same year in both halves of one report.

## 4. Verification

The API, with the cross-check that the slices are a whole:

```
projects 10   categories 5
cat_community 54 · cat_campus 9 · cat_environment 7 · cat_welfare 5 · cat_education 2
categoryTotal 77
```

And the rendered chart, in Chinese:

```
heading:    各类服务参与分布
pie labels: 社区服务 70% · 校园服务 12% · 环保服务 9% · 公益服务 6% · 教育服务 3%
slices:     5        page errors: []
```

Five full names, percentages summing to 100, no truncation and no duplicate
`[AUTO]` wedges — those harness projects are all community service, and now say
so as one slice instead of four identical ones.

The page-errors line is there deliberately: the previous chart change (issue
133) took this page down with a hooks-order fault that `tsc` passed, so a render
check belongs in every verification of it now.

## 5. What it must not do

- **Must not slice by project.** §1 — the table beside it already does that,
  better.
- **Must not count distinct students per category.** §3 — the slices would not
  be parts of a whole.
- **Must not send category names.** §3.
- **Must not use a different year window from `topProjects`.** §3.
