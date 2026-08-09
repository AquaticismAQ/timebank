# 005 — Student grade/class displays as "Grade 9 Grade 9 B" instead of "Grade 10, Class A"

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

Student grade/class should render as e.g. "Grade 10, Class A", but currently renders
as a malformed duplicate like "Grade 9 Grade 9 B".

## Root cause

This is a **seed-data** bug, not a frontend string-formatting bug. The frontend
concatenation is simple and consistent across every call site (`StudentList.tsx:307,513`,
`AccountManagement.tsx:165`, `AuditCenter.tsx:263,325`, `AdminDashboard.tsx:158,180`):
`{student.grade} {student.className}`, where both values are passed through as-is from
the API (`fromStudentDto`, `frontend/src/app/api/client.ts:104-119`) with no
concatenation happening server-side either
(`StudentServiceImpl.toListItem`, `StudentServiceImpl.java:225-234`).

The actual bug is in `backend/src/main/resources/db/migration/R__seed_base_data.sql`
(lines 19-31): the `class` table's `name_en`/`name_zh` columns are seeded as the
**full grade-prefixed string** instead of just the class label:

```sql
('class_9A', '9A', '九年级A班', 'Grade 9 A', 'grade_9', ...),
('class_9B', '9B', '九年级B班', 'Grade 9 B', 'grade_9', ...),
```

So `class.name_en` = `"Grade 9 B"` already contains the grade name, and when the
frontend combines it with `grade.name_en` = `"Grade 9"`, the result is
`"Grade 9" + " " + "Grade 9 B"` = `"Grade 9 Grade 9 B"`. Same pattern for the Chinese
names (`class.name_zh` = `"九年级B班"` already contains `九年级`).
`Docs/integration/APIContract.md:189` implies `gradeName`/`className` are meant to be
independent, non-overlapping fields for callers to combine — the seed data violates
that.

This also confirms class is a general string field (not a single character), matching
the bug report's note and the separate request in
[006](./006-mock-seed-data-corrections.md) to add a class like "Mils".

## Proposed fix

1. Fix `R__seed_base_data.sql` class rows to store only the class label, not the
   grade-prefixed string, e.g.:
   ```sql
   ('class_9A', '9A', 'A班', 'A', 'grade_9', ...),
   ('class_9B', '9B', 'B班', 'B', 'grade_9', ...),
   ```
2. Update the display format to match the bug report's exact requested format —
   "Grade 10, Class A" — which needs an explicit `, Class ` separator, not just a
   space. Update all call sites listed above (`StudentList.tsx`,
   `AccountManagement.tsx`, `AuditCenter.tsx`, `AdminDashboard.tsx`) to a shared
   formatting helper (e.g. `formatGradeClass(grade, className)` in `lib/`) instead of
   inline template literals repeated in five places, so the format only needs to be
   defined once.
3. Since this is a Flyway repeatable migration (`R__` prefix), a data fix requires
   either an `UPDATE` in the same file (repeatable migrations rerun on checksum
   change, but existing rows use `INSERT IGNORE` so they won't be touched by rerunning
   the insert) or a one-off corrective `UPDATE` in a new versioned migration — check
   which is consistent with how other repeatable-migration data corrections have been
   handled in this repo before choosing.

## Acceptance criteria

- Student grade/class displays as "Grade 10, Class A" everywhere it's shown.
- No display location string-concatenates grade and class independently anymore —
  all go through the shared helper.
- Existing/already-deployed class rows are corrected (not just newly-inserted ones).

## Test plan

- Live: check Student List, Accounts, Audit Center, and Dashboard all show the
  corrected, non-duplicated format for a student in every grade.

## Resolution

- `R__seed_base_data.sql`'s 12 class rows now store only the class label (`'A'`/
  `'B'` en, `'A班'`/`'B班'` zh) — no grade prefix. Chinese keeps the "班"
  (class/homeroom) suffix, which reads naturally without a separator; English
  needs one, since its class label is now bare.
- Since `R__` repeatable migrations use `INSERT IGNORE` (never touches rows that
  already exist, even on a checksum-triggered rerun), added
  `V15__fix_class_name_double_prefix.sql` — a plain one-time `UPDATE`, the first
  data-correction migration in this repo (no `UPDATE`-based precedent existed;
  every prior migration was pure DDL/`INSERT IGNORE`) — to retroactively correct
  the 12 rows already sitting in the live server's database.
- Added `formatGradeClass(grade, className)` to `frontend/src/app/lib/format.ts`
  — language-aware (`i18n.language`), since the fix means English needs an
  explicit ", Class " separator while Chinese reads correctly as direct
  concatenation. Replaced **10 separate inline `{grade} {className}`-style
  template literals** across 6 files (`StudentList.tsx`, `AccountManagement.tsx`,
  `AuditCenter.tsx`, `AdminDashboard.tsx`, `SystemAdmin.tsx`'s two new pickers
  from issue 003, and `EventProposals.tsx`'s co-organizer picker) with calls to
  the one shared helper.
- `tsc --noEmit` clean. `mvn test`/`mvn verify` (relevant IT suites) clean.
- Deployed both backend and frontend to `https://tb.example.com/`. Live-verified via
  the real API: `GET /students` now returns `className: "A"`/`"B"` (previously
  `"Grade 9 B"` etc.) for every seeded student — confirmed across all six grades —
  so `formatGradeClass` renders "Grade 9, Class B" correctly rather than
  "Grade 9 Grade 9 B".

---
*Live-verified 2026-08-10 against `https://tb.example.com/` (API-level: corrected `className` field confirmed for every grade). The rendered display string itself wasn't screenshotted — no browser tool available this session.*
