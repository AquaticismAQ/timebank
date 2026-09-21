# 153 — Exports came out in no order at all

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism: every export CSV carrying grade
and class columns should be sorted by grade then class — unless it carries a date
column or describes something that happened in sequence, which should be sorted
by datetime ascending.

## 1. What they were sorted by

`order by id asc`, in every paged export query. An opaque server-generated id
(`std_2tt61dr13t84ank7n6acsr07fc`) has no meaning to anyone reading the file, so
a roster arrived in an order that looks random and cannot be scanned: a head
teacher looking for their class has to search rather than look.

It is not merely cosmetic. A CSV a school keeps as its record of the year is read
by eye and compared against other lists, and the first thing anyone does with an
unordered one is open it in a spreadsheet and sort it themselves — which is a
tell that the file was not finished.

## 2. The rule, and what it decides

| Export | Sorted by | Why |
| :--- | :--- | :--- |
| Student List | grade, class | identity and placement; no event in it |
| Student Completion | grade, class | one row per student for a year |
| Student Participation | grade, class | one row per student |
| Service Records | **service date** ascending | each row is a thing that happened |
| Projects | **start date** ascending | each row is an activity that ran |
| Review History | **decided at** ascending | each row is a decision made at a moment |
| Review Efficiency | unchanged (most reviews first) | an aggregate per reviewer; no per-row grade, class or date |

Review Efficiency is deliberately left alone. Its grade column is a *list* of the
reviewer's bound grades, not a grade the row is in, and its existing order —
busiest reviewer first — is a real answer to the question the file is opened to
ask.

## 3. Why this is more than an `ORDER BY`

Every paged export walks the table by keyset: `where id > :afterId order by id
asc limit`, with the last row's id carried into the next page. **The ordering
*is* the pagination.** Changing only the `ORDER BY` would leave the cursor
walking a different order from the one being produced — which does not merely
look wrong, it silently skips and repeats rows.

So each query's cursor becomes the sort key plus the id as a tiebreaker, compared
as a row value (`(a, b, id) > (:a, :b, :id)`), which MySQL evaluates as one
comparison and can still use an index for.

The id stays in the key even where the sort column is unique enough in practice.
Two students in the same class, or two records on the same date, must have a
deterministic order or the walk can drop one.

### Grade ordering is by `sequence`, never by id or name

`grade_10` sorts before `grade_6` as a string, and "Grade 10" before "Grade 6"
as a label. Only `grade.sequence` orders a school the way a school does.

### The derived-grade question, and why it turns out not to matter

Student List does not print the student's stored grade — it prints the grade they
were in *during the selected year* (`ExportGradeDerivation`, §5.1). Sorting by the
stored grade would therefore be sorting by a column the file does not show.

It comes out identical anyway, and the arithmetic says why: the derived level is
`liveGradeLevel + (selectedYear − currentYear)`, so for any one file every row
shifts by the *same* constant. A constant offset cannot reorder anything. Sorting
by the stored grade's sequence is correct for every year, and this paragraph is
here so the next person does not have to re-derive it before daring to touch it.

## 4. Verification

**On the deployed server**, every type exported and the file itself inspected:

```
studentList            58 rows   Grade 6,7,8,9,10,11,12 in that order; 6A before 6B
serviceRecords        737 rows   Service Date ascending, 2025-09-04 … 2026-09-21
studentCompletion      58 rows   grade order as above
studentParticipation   58 rows   grade order as above
projects              111 rows   Start Date ascending, 2026-06-21 … 2026-11-03
reviewHistory         728 rows   Decided At ascending, 2025-09-06 … 2026-09-21
```

`serviceRecords` returning **737** is the row-count check that matters: the same
export produced 737 before the change, so the reordered keyset walk lost nothing.

`ExportScopeIT` gained three tests. The roster one spans grade 7 and grade 10
deliberately — their ids and labels sort in the opposite order to their
sequences, so ordering by id, code or label all put grade 10 first and only
`sequence` puts it last. **Red-checked**: restoring `order by grade_id` fails the
roster test and reversing the date order fails the service-record one, each
alone. The third test asserts every row appears exactly once, which is what a
mis-seated cursor breaks first and what no ordering assertion would catch.

### An outage on the way, caused by the renumbering the day before

The first deploy of this change would not start: **Flyway checksum mismatch on
V30, V31 and V32**. The design-doc renumbering had rewritten doc references
inside those files — comments, and one string literal — and Flyway checksums the
whole file, so three already-applied migrations no longer matched their recorded
hashes and validation refused to start the application.

Fixed by restoring the three files byte-for-byte to the content that was applied,
not by repairing the checksums in the database: **an applied migration is
immutable**, and the cost is one stale document name inside a comment. The
repeatable `R__` seeds are a different case — they are meant to re-run when their
content changes — so their updated references stayed.

The renumbering was compile-checked and reviewed, and neither could catch this:
the application only reads those files when it starts against a database that
already has them. The lesson is in `Docs/README.md` §2.10 now.

## 5. Also found, not fixed here

Two rows at the top of the completion export show a **blank Grade**. They are
probe students I created while working on issues 146 and 149, and the blank is
correct arithmetic on bad data: they are in grade 6 but recorded as enrolling in
2025-2026, which implies entry at grade 5, and a 6-12 school has no grade 5. Real
roster imports cannot produce this, since entry is at grade 6 or 9. Cleaning them
up is a live database write and is being raised separately rather than done in
passing.

## 6. What it must not do

- **Must not skip or duplicate a row.** §3 — the cursor and the order are one
  thing.
- **Must not order grades as strings.** §3.
- **Must not reorder Review Efficiency.** §2.
- **Must not edit an applied migration**, not even a comment inside one. §4.
