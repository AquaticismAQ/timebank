# 021 — Student Management's "Records" button was a stub

Status: **Fixed and live-verified (2026-08-12)**

## Symptom

Reported directly by the user: the "Records" button on each row of the
Student Management page (desktop table and mobile card) only showed a
"View records coming soon" toast — no way to actually see a given
student's service record history from the admin side.

## Root cause

Never built. `StudentList.tsx`'s two "Records" buttons
(`components/admin/StudentList.tsx:415`/`:633`) called
`toast.info(t('admin.students.recordsComingSoon'))` unconditionally —
a placeholder that was never replaced with a real feature. Separately,
`GET /students/{id}/service-records` had been documented in
`APIContract.md` §4.9 since early in the project as the intended way to
fetch this, but the endpoint was never implemented server-side
(`StudentController.java` has no such route) — the contract described
an intended shape that the backend never caught up to, the same class of
drift `accounts.get(studentId)` had already been resolved for.

## Fix

Rather than build the never-implemented dedicated sub-resource, added
`filter[studentId]` to the existing `GET /service-records` endpoint
(`ServiceRecordController`/`ServiceRecordServiceImpl`) — the same
resolution `api.accounts.get(studentId)` already uses for the identical
problem, reusing infrastructure every other admin service-record screen
already depends on instead of adding a second filter/sort/pagination
implementation to maintain. The filter narrows within the caller's
existing data-scope specification (a scoped teacher can only query
students already in their scope; an out-of-scope `studentId` returns
zero rows, not a 403).

Built a new `StudentRecords.tsx` component: a read-only, paginated,
expandable-card list of a student's service records (status, record
number, date, duration; expanded view adds location/category/supervisor/
description/review note/evidence links) — no decision actions, since
approve/reject/return already lives in Audit Center's review workflow.
Wired into both of Student Management's "Records" buttons in place of the
toast. `APIContract.md`/`_CN.md` §4.9 corrected to strike the
never-built endpoint and point at the real resolution.

## Test plan

- Backend: `mvn clean compile`/`test-compile`/`test` clean; `mvn verify
  -Dit.test=ServiceRecordSubmissionIT` against real MySQL over an SSH
  tunnel — extended `listFiltersByStatusAndSearchesByOrganizationName`
  with a second student and asserted `filter[studentId]` returns exactly
  one student's records even when another student's records exist in the
  same scope (10/10 tests pass).
- Frontend: `pnpm build` clean.
- Live: opened Records for a student with real history, expanded a row.

## Resolution

Backend: `ServiceRecordService.java`/`ServiceRecordServiceImpl.java`/
`ServiceRecordController.java` gain the `studentId` filter param;
`DashboardServiceImpl.java`'s existing call site updated for the new
signature (an incremental `mvn compile` initially missed this —
caught by the server's own clean-package deploy build, not locally;
re-verified with `mvn clean compile`/`clean test-compile` before
redeploying). `ServiceRecordSubmissionIT.java` extended. Both backend
and frontend redeployed.

Frontend: new `frontend/src/app/components/admin/StudentRecords.tsx`;
`StudentList.tsx` wired to it; dead `recordsComingSoon`/
`viewRecordsComingSoon` i18n keys removed, new `noRecords`/
`recordLocation`/`recordCategory`/`recordSupervisor`/`recordDescription`/
`recordReviewSummary` keys added (`en.json`/`zh.json`).

Docs: `Docs/integration/APIContract.md`/`_CN.md` §4.9's
`/students/{id}/service-records` row struck with an explanatory note;
§13.1's `serviceRecords.list` note extended.

---
*Live-verified 2026-08-12 against `https://tb.example.com/` via Playwright
MCP, logged in as `Teacher Wei Chen`: opened Records for "Jia Ming"
(real data, grade_10/A班) and confirmed 7 real service records rendered
with correct status badges (已通过/已提交/草稿), record numbers, dates,
and durations — matching the "4.5" total hours already shown in that
student's table row. Expand/collapse and the Cancel button both
confirmed working.*
