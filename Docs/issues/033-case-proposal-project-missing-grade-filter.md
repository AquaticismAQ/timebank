# 033 — Case Review, Proposal Review, and Project Management missing the grade filter Audit Center has

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Reported by the user: for admin/teacher roles, Case Review, Proposal Review, and Project Management had no grade filter dropdown, unlike Audit Center — inconsistent filtering capability across the four admin list screens that all deal with grade-linked data. The user specifically flagged Project Management as needing extra care, since a project can be eligible for multiple grades at once (`eligibleGradeIds`), not a single grade like the other three resources.

## Root cause

Backend: `CaseDto`/`ProposalDto` never carried grade/class fields at all (unlike `ReviewQueueItemDto`, which already had `gradeId`/`gradeLevel`/`className` from earlier grade-display work), even though both underlying entities (`Case`, `EventProposal`) already store their own snapshotted `grade_id`/`class_id` columns from creation time — the data existed, it just was never surfaced through the DTO or the `list()` filter chain. `ProjectController`/`ProjectServiceImpl.list()` had no grade filter parameter at all.

Project Management's case is structurally different: `Project.eligibleGradeIds` is a MySQL JSON array column (`@Convert`ed `List<String>`), not a scalar `grade_id` — a project can be eligible for several grades simultaneously. A naive `FilterSpecifications.equalTo("eligibleGradeIds", gradeId)` JPA Criteria predicate cannot express "this JSON array contains X" against a `@Convert`-mapped collection column.

## Fix

**Case Review / Proposal Review** (single-grade, same pattern as Audit Center's `ReviewQueueItemDto`):
- `CaseDto`/`ProposalDto` gained `gradeId`, `gradeLevel` (int), `className` — resolved from the entity's own snapshotted `gradeId`/`classId` via `AcademicStructureService` (never a live re-lookup of the student's *current* grade), not from the student record.
- `CaseService.list()`/`ProposalService.list()` gained a `gradeId` parameter; `CaseController`/`ProposalController` pass `query.filter("gradeId")` through; filtering is a plain `FilterSpecifications.equalTo("gradeId", gradeId)` JPA Specification, since the entities already carry that column directly.
- Frontend: `CaseReview.tsx`/`ProposalReview.tsx` gained a third filter dropdown (grade), narrowed to the teacher's own `scopeGradeIds` when the caller is a non-admin teacher — identical to `AuditCenter.tsx`'s existing `availableGrades` logic, copied verbatim. Grade/class now also shows in each row and the detail workbench (`formatGradeClass`).

**Project Management** (multi-grade — the case the user flagged):
- `ProjectRepository` gained a native-SQL `findIdsByEligibleGrade(gradeId)` query using `JSON_CONTAINS(eligible_grade_ids, JSON_QUOTE(:gradeId))` — the same idiom `ServiceRecordReferenceRepository` already uses for `evidence_file_ids`, chosen specifically because a JPA Criteria predicate can't express "array contains" against this column type.
- `ProjectServiceImpl.list()` folds the resulting id set into the existing Specification pipeline via a plain `id in (...)` predicate (`eligibleGradeSpecification`), rather than a second parallel filtering path — pagination/sorting/scope all still go through one query.
- `ProjectService.list()`/`ProjectController` gained the `gradeId` parameter; two other backend call sites (`DashboardServiceImpl`'s upcoming/active-projects lookups) needed a `null` added for the new parameter to keep compiling.
- Frontend: `ProjectManagement.tsx` gained a third filter dropdown, **not** narrowed to the teacher's own grades — projects have no teacher-grade data scope at all (`ProjectServiceImpl.visibilitySpec` is draft-ownership only), so narrowing the dropdown would have implied a restriction that doesn't exist. Also added a previously-missing "Eligible Grades: ..." line to each project card (there was no way to see a project's eligible grades outside the edit modal before this).

Unrelated but required for the changed `ProjectService.list()`/`ProposalService.list()` signatures to compile: two backend test files (`ProjectLifecycleIT`, `ProposalApprovalTransactionIT`) needed an extra `null` argument added at their existing `.list(...)` call sites.

## Test plan

- Backend: `mvn test` (37/37 pass) and a scoped `mvn verify -Dit.test=ProjectLifecycleIT,ProposalApprovalTransactionIT` (22/22 pass) against real MySQL, both from a **clean** build (`mvn clean test-compile` first caught a stale-incremental-compile false negative that a plain `mvn compile` had missed — the exact class of gotcha this repo has hit before, see `backend-design-status` memory).
- Frontend: `tsc --noEmit`/`pnpm build` clean; repo-wide `en.json`/`zh.json` placeholder-parity check clean.
- Live: verified the multi-grade JSON_CONTAINS filter specifically, since that was the flagged risk — filtering Project Management by Grade 11 against real seeded data (multiple projects with 2–6 overlapping eligible grades) returned exactly the 4 projects whose `eligibleGradeIds` actually contain grade 11, correctly excluding projects eligible for other grades; Grade 6 returned exactly the 2 matching projects. Also verified Case Review/Proposal Review's single-grade filter (Grade 6 → 0 results, Grade 10 → all results, against an all-grade-10 seed dataset) and the teacher-scoping split: logging in as `teacher001` (scoped to grade_10) narrowed Case/Proposal Review's dropdown to only "All Grades"/"Grade 10", while Project Management's dropdown still showed and correctly filtered by all 6 grades.

## Resolution

Deployed to `https://tb.example.com/` (backend via rsync + remote `deploy.sh`, then frontend via `frontend/scripts/deploy.sh`). Live-verified as both `admin001` and `teacher001` per the test plan above.

*Live-verified via browser click-through and direct filter-selection testing against real seeded multi-grade project data on the real deployed frontend and backend — not just API-level or unit testing, since the user specifically asked for the multi-grade correctness to be proven, not assumed.*
