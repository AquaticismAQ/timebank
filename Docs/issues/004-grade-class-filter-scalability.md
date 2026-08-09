# 004 — Grade/class filtering incomplete on high-volume admin list pages

Status: **Open**

## Symptom

For a school with 1000+ students, admins/teachers need to narrow list pages by grade
and class. The bug report specifically calls out the Student List page's grade filter
as "not working properly," and flags the time coin accounts page as needing the same
capability.

## Root cause

Investigation found the underlying plumbing for the *existing* grade filter on the
Student List page is actually correct end-to-end today: `StudentList.tsx`'s
`handleGradeChange` → `usePagedApi` → `api.students.list` → `buildListQuery` →
`filter[gradeId]=<id>` → `PageQueryArgumentResolver.parseFilters` →
`StudentServiceImpl.list()` → `FilterSpecifications.equalTo("gradeId", ...)`. No
defect was found in that specific chain.

What's actually broken/missing, and likely what the report is really describing:

1. **No class filter exists on the Student List page at all**, even though the
   backend already accepts and applies `filter[classId]`
   (`StudentServiceImpl.java:58`). `classes` dictionary data is fetched
   (`StudentList.tsx:42`) but only used in the edit modal, never wired to a filter
   control.
2. **The time coin accounts page has no grade or class filter whatsoever.**
   `AccountManagement.tsx:19` calls `usePagedApi<Student>(api.students.list, {})`
   with no filter ever set — the only input is free-text search.
3. **Once [001](./001-teacher-student-scope-not-enforced.md) is fixed**, a
   teacher's grade filter dropdown should be limited to the grades they actually have
   access to, not show every grade in the school. Today the dropdown always lists all
   grades regardless of who's logged in — this is very likely the actual complaint
   behind "not working properly": a scoped teacher can see (and pick) grades outside
   their bond in the filter UI, which is misleading even though the underlying data
   returned is currently unscoped anyway (same root cause as 001).
4. Server-side page size is hard-capped at 100
   (`PageQueryArgumentResolver.java:24,45`), which makes the lack of filtering on the
   accounts page especially painful at 1000+ students — pagination alone doesn't
   substitute for filtering.

## Proposed fix

1. Add a class filter control to `StudentList.tsx`, following the same pattern as the
   existing grade filter, using the already-supported `filter[classId]` param. Make
   the class options depend on the selected grade (a class belongs to one grade).
2. Add grade + class filter controls to `AccountManagement.tsx`, reusing the same
   filter pattern/component as `StudentList.tsx` (`api.students.list` already backs
   both pages).
3. Once [001](./001-teacher-student-scope-not-enforced.md) lands, populate the grade
   filter dropdown's options from the operator's accessible grades (admin: all
   grades; scoped teacher: only their bonded grades) rather than the full grade
   dictionary.
4. Keep filters single-select per the existing pattern (grade, then class dependent
   on grade) — this satisfies "filter by the selected grade(s) if the operator has
   access to multiple grades" without needing multi-select UI, since a scoped
   teacher's dropdown will already be pre-narrowed to their grades per item 3.

## Acceptance criteria

- Student List page: selecting a grade narrows the class dropdown to that grade's
  classes; selecting a class further narrows the list.
- Accounts page: same grade/class filtering capability as Student List.
- A teacher scoped to one grade only ever sees that grade in their filter dropdown.

## Test plan

- Live: as `admin001`, filter Student List by grade_10 then class 10A, confirm
  correct narrowing; repeat on the accounts page.
- Live: as `teacher001`, confirm the grade dropdown offers only grade_10.

---
*This document will be updated once the fix has been implemented and verified.*
