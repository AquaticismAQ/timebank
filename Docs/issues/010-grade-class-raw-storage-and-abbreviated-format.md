# 010 — Grade/class should display as "G10, Class A" / "十年级, A班", and stop storing translated strings for what is really raw data

Status: **Fixed and live-verified (2026-08-10)**

## Symptom

Reported directly (not caught via live browser testing this session): the student
grade/class display should read **"G10, Class A"** / **"G10, Class Mils"** in English and
**"十年级, A班"** / **"十年级, Mils班"** in Chinese. The format shipped in issue 005
(`formatGradeClass`) instead produces "Grade 10, Class A" (spelled-out grade, not
abbreviated) and "十年级A班" (no comma separator between grade and class).

Separately: the `class` table stores its display strings as **hardcoded, per-row
translated text** (`name_zh`/`name_en`, e.g. `'A班'`/`'A'`) baked directly into
`R__seed_base_data.sql`. That's not really translation — a class name like "Mils" is a
proper noun, never actually translated between languages, and the "班" (class/homeroom)
suffix baked into `name_zh` is UI decoration, not content. Storing it this way is what
let issue 005 happen (a grade prefix baked into the same field) and is why the
`class_10_mils` seed row is *still* inconsistent today — its `name_zh` is bare `'Mils'`
with no `班` suffix, unlike the `A`/`B` rows' `'A班'`/`'B班'` (nobody remembered to
hand-add the suffix when issue 006 added a named class).

## Root cause

Two separate but related problems:

1. **Format**: `formatGradeClass` (`frontend/src/app/lib/format.ts`) joins the server's
   already-localized `gradeName`/`className` strings with issue 005's chosen separator
   (", Class " for English, nothing for Chinese) — that separator choice just doesn't
   match what's wanted now.
2. **Storage**: `class.name_zh`/`class.name_en` hold fully-formed display strings
   (including the `班` suffix) rather than a single raw class label. Every future class
   name (a new named class, a new grade) requires hand-writing both a Chinese and an
   English "translation" of what is not actually translatable content, which is exactly
   how the "Mils" row's `班` suffix got missed and how issue 005's grade-prefix bug
   happened in the first place. `grade.name_zh`/`name_en` have the same shape but were
   **deliberately left alone** in this fix — see "Scope decision" below.

## Proposed fix

**Class (full raw-storage change):** `class` is used *only* within the `student`
module (`AcademicStructureService{,Impl}`, `AcademicStructureController`,
`StudentServiceImpl`, `ReviewQueueRepository`'s read-model join) — no cross-module
consumer depends on a localized class name. Collapse `class.name_zh`/`name_en` into a
single raw `class.name` column (`V16__class_raw_name.sql`, backfilled from the existing
`name_en` values, which are already bare labels post-issue-005 — `'A'`, `'B'`,
`'Mils'`). `ClassDto`/`SchoolClass` frontend type keep a `name` field, but it's now
language-invariant raw data, never picked via `Accept-Language`. The `AcademicStructureService.listClasses()` /
`GET /classes` cache key drops its `:language` suffix accordingly.

**Grade (scope decision — NOT changed the same way):** `grade.name_zh`/`name_en` stay.
Unlike class, `GradeDto`'s localized `name` is a real cross-module dependency —
`ProjectServiceImpl` (eligible-grades display map), `ReportServiceImpl`/
`SummaryMaterializationJob`/`ExportJobRunner` (dashboard charts, CSV export headers) all
read it, and none of those are part of this bug. Ripping grade's dictionary apart to
chase the "no hardcoded Chinese" principle all the way through would touch Reporting and
Project — modules with no display bug, at real regression risk, for no user-visible
benefit. Instead, the **student-linked** raw grade number was already sitting right
there and unused for display purposes: `grade.sequence` (existing int, 6–11,
language-invariant since the table's first migration). `StudentListItemDto`/
`StudentDetailDto`/`StudentDirectoryEntryDto`/`ReviewQueueItemDto` swap their
`gradeName: String` field for `gradeLevel: int` (sourced from `grade.sequence`, not
`grade.name_*`) — the frontend now builds "G10"/"十年级" from that number itself. The
`/grades` dictionary endpoint and its consumers (grade filter dropdowns, Project/Reports)
are untouched and keep showing the full localized grade name ("Grade 10"/"十年级") where
they already did — that's a different, correctly-functioning display, not the bug
reported here.

**Frontend format:**
```ts
const ZH_GRADE_NUMERALS: Record<number, string> = { 6: '六', 7: '七', 8: '八', 9: '九', 10: '十', 11: '十一' };
function formatGradeClass(gradeLevel: number, className: string): string {
  const zh = i18n.language.startsWith('zh');
  const grade = zh ? `${ZH_GRADE_NUMERALS[gradeLevel] ?? gradeLevel}年级` : `G${gradeLevel}`;
  if (!className) return grade;
  return zh ? `${grade}, ${className}班` : `${grade}, Class ${className}`;
}
```
Every existing call site (`StudentList.tsx`, `AccountManagement.tsx`, `AuditCenter.tsx`,
`AdminDashboard.tsx`, `SystemAdmin.tsx`, `EventProposals.tsx`) switches from passing the
old `grade`/`gradeName` string to the new `gradeLevel` number. A companion
`formatClassName(name)` (`zh ? name + '班' : name`) covers standalone class-only labels
(the class filter/edit `<select>` options in `StudentList.tsx`/`AccountManagement.tsx`,
which read `class.name` directly, bypassing `formatGradeClass` — those need the `班`
suffix reinstated as a display concern now that it's no longer baked into stored data).

Also caught and fixed along the way (same root files, not worth a separate issue
number): `StudentList.tsx`'s edit-modal header shows `editStudent.grade` bare (no class,
inconsistent with the row above it, which uses `formatGradeClass`) — switched to
`formatGradeClass(editStudent.gradeLevel, editStudent.className)`.

**Not fixed here (flagged, out of scope):** `api.dashboards.student()`
(`frontend/src/app/api/client.ts`) hardcodes `grade: ''` for the student self-view
dashboard (`StudentHome.tsx`) rather than backfilling it the way `students.progress()`
does with an extra `/students/{id}` call — a pre-existing gap, unrelated to raw-vs-
localized storage, that deserves its own issue if the self-view grade/class display is
ever reported empty.

## Test plan

- `mvn test`/`mvn verify` (`LocalizationIT` extended to prove classes are now
  language-invariant same as grades already were; `StudentPrivacyIT` updated for the
  `gradeLevel` rename).
- `tsc --noEmit` clean.
- Live: Student List, Accounts, Audit Center, Dashboard, and the grade-bonding/
  co-organizer pickers (SystemAdmin, EventProposals) all show "G10, Class A"/"G10, Class
  Mils" in English and "十年级, A班"/"十年级, Mils班" in Chinese, for a grade-10 A-class
  student and a grade-10 Mils-class student, in both languages.

## Resolution

- Backend: `V16__class_raw_name.sql` collapsed `class.name_zh`/`name_en` into a single
  raw `class.name` (backfilled from `name_en`, then dropped both columns) — applied
  cleanly to both a real-MySQL `timebank_it_*` IT schema and the live `timebank`
  schema (confirmed via direct query post-deploy: `class.name` is `'A'`/`'B'`/`'Mils'`
  everywhere, no `班` baked in). `grade.name_zh`/`name_en` deliberately left alone (see
  "Scope decision" above). `SchoolClass.java`/`ClassDto` updated; `AcademicStructureServiceImpl.listClasses()`
  dropped its `resolvedLanguage` param and cache-key language suffix.
  `StudentListItemDto`/`StudentDetailDto`/`StudentDirectoryEntryDto`/`ReviewQueueItemDto`
  swapped `gradeName: String` for `gradeLevel: int` (from `grade.sequence`, not
  `grade.name_*`) and now source `className` from the new raw `class.name`.
  `ReviewQueueRepository`'s raw-SQL read-model query updated to select
  `g.sequence`/`c.name` instead of the four `name_zh`/`name_en` columns.
  `StudentService`'s `resolvedLanguage` parameter is now unused for grade/class but
  kept in the public interface (documented inline) rather than removed, to avoid
  churning ~20 unrelated call sites across other modules' production code and IT
  tests that pass it through for reasons unrelated to this bug.
- `LocalizationIT` extended with `classesAreLanguageInvariant()` (proves `class.name`
  is now the same regardless of `Accept-Language`, including the two edge-case rows —
  `class_10A` = `"A"`, `class_10_mils` = `"Mils"`); `StudentPrivacyIT` updated for the
  `gradeLevel` rename. `mvn test` clean; `mvn verify -Dit.test=LocalizationIT,StudentPrivacyIT,ReviewQueueIT`
  clean against real MySQL (via an SSH tunnel to the shared test server) before
  deploying.
- Frontend: `formatGradeClass(gradeLevel: number, className: string)` rewritten per
  the format above; new `formatClassName(name)` for the standalone class-only
  `<select>` options in `StudentList.tsx`/`AccountManagement.tsx` (previously bare
  `c.name`, now needs the `班` suffix reinstated for Chinese since it's no longer
  baked into stored data). Every `formatGradeClass` call site updated to pass the new
  `gradeLevel` field instead of a pre-localized grade string
  (`StudentList.tsx` ×2 plus the previously-inconsistent bare `editStudent.grade` in
  the edit-modal header, `AccountManagement.tsx`, `AuditCenter.tsx` ×2 plus its grade
  filter dropdown, `AdminDashboard.tsx` ×2, `SystemAdmin.tsx` ×2, `EventProposals.tsx`).
  `students.progress()` (`client.ts`) now builds `StudentProgress.grade` via
  `formatGradeClass(student.gradeLevel, student.className)` instead of passing through
  a bare pre-localized grade string — this incidentally fixes that self-view path
  (`StudentHome.tsx`/`ProfilePage.tsx`/`ProgressPage.tsx`/`StudentLayout.tsx`'s header)
  showing grade only with no class, since the assembled string now always includes
  both. `api.dashboards.student()`'s separate, still-empty `grade: ''` gap is
  unrelated (student self-view *dashboard*, not the profile/progress fetch path) and
  intentionally left alone — flagged in "Not fixed here" above, not silently dropped.
  `tsc --noEmit` (via `frontend/scripts/deploy.sh`, which runs it explicitly since
  Vite's own build doesn't type-check) caught one missed import
  (`formatGradeClass` in `client.ts`) before deploy; clean after.
- `Docs/integration/APIContract.md`/`_CN.md` §2.10 updated with the grade/class
  carve-out rationale; the `/students/directory` field list line updated
  (`gradeName` → `gradeLevel`).
- Deployed backend (`backend/scripts/deploy.sh`, ran the migration against the live
  `timebank` schema — narrated and confirmed with the user first since it's a
  DROP COLUMN, even though backfill-before-drop meant no data loss) and frontend
  (`frontend/scripts/deploy.sh`) to `https://tb.example.com/`.
- Live-verified via Playwright as `admin001`/`teacher001` (Wei Chen): Student List
  table, its class filter dropdown, and the edit-student-modal header all show
  "十年级, A班" (zh) / "G10, Class A" (en) for grade-10 A students and "十年级, Mils班" /
  "G10, Class Mils" for the grade-10 Mils student (`Xu Ran`, `2024006`) in both
  languages; Audit Center's table and grade filter dropdown ("G10"); Admin Dashboard's
  review-queue widget; the Create-User grade-bonding student-search picker (confirmed
  "G10, Class Mils" sublabel searching for "Xu Ran"). Logged in as `student001` and
  confirmed the self-view header/profile page now show "十年级, A班" (previously grade
  only, no class).
- While verifying, found and separately logged (not fixed, out of scope for this
  issue) `Docs/issues/011-grade-dictionary-stale-after-language-toggle.md` — the grade
  filter dropdown doesn't refresh to the new language on an in-session toggle without
  a page reload. Confirmed pre-existing, not a regression from this change.

---
*Live-verified 2026-08-10 against `https://tb.example.com/` via Playwright, both
languages, admin/teacher and student personas, including the Mils-class edge case
throughout.*
