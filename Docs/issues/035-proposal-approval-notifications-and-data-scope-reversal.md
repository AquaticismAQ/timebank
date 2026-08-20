# 035 — Proposal-approval notification routing, and reverting non-student data visibility to grade-scoped

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Requested by the user directly, two related changes:

1. Notifications after a proposal decision should route correctly: `reject`/`requestChanges` to the proposer (student) — already correct — but `approve` should additionally notify the users (non-student) who hold project-publish permission for the proposal's grade, since approval only generates a *draft* project (§4.2's status machine — never auto-published) and someone needs to actually publish it.
2. A correction to this session's own earlier wording: "non-student can view all [proposals]" (from the §2.9 view/decide split, `Docs/issues/034-*.md`) should mean "can view all *within the assigned grades*", not literally school-wide. This reopened `Docs/optimization/07-RoleFinetune.md` §2.1's decision that any non-student role gets unconditional school-wide (`ScopeDescriptor.All`) data visibility.

## Root cause / prior behavior

- No mechanism existed to notify anyone about a newly-generated draft project needing publication — the proposer was the only notification recipient on any decision, and nobody was ever told a project draft was waiting.
- §2.1 (2026-08-19) had deliberately made every non-student role — `teacher` included — get unconditional `grantAll = true` data visibility across ~11 backend call sites, reducing `TeacherScopeAssignmentService`'s grade-scope assignment to "organizational metadata" with no actual visibility effect. This was a considered, documented decision at the time, not a bug — but on reflection it went further than intended (a scoped teacher should only see their own grades' data, matching how the system worked before §2.1).

## Fix

Full reasoning, alternatives considered, and verification detail in `Docs/optimization/07-RoleFinetune.md` §2.10 (added this session). Summary:

**Data-scope reversal (§2.10):** `TimeBankPrincipal` gained `hasSchoolWideVisibility()` (`admin`/`sysadmin` only — the literal pre-§2.1 rule), kept separate from the existing `hasFullDataScope()` (still "any non-student", deliberately retained for `isManager`/`isAdmin`-style *authorization* checks and import/export job visibility — none of those are grade-scoped concepts, and reverting them would have just made them stricter with no corresponding benefit, a real regression risk identified and avoided during implementation). The 8 call sites that resolve genuinely grade-scoped data (`ServiceRecordServiceImpl`, `CaseServiceImpl`, `ProposalServiceImpl`, `ReviewServiceImpl`, `StudentServiceImpl`, `AccountServiceImpl`, `DashboardServiceImpl`, `FileServiceImpl`) now use the new method. No data migration was needed — `TeacherScopeAssignmentService`'s existing grade-assignment rows (already populated per-account since §2.6.2) took effect immediately.

**Proposal-approval notifications (§2.10.2):** on `approve`, `ProposalApprovalTransaction` now also notifies every active `projects.publish` holder covering the proposal's grade (new template `eventProposal.approvedNeedsPublish`, related resource the newly-generated project), reusing the same grade-scope assignment that already gates that account's proposal-queue visibility. New `UserService.activeUsersWithPermission` and `TeacherScopeAssignmentService.userIdsAssignedToGrade` support this.

**Found and fixed as a prerequisite:** this is the first-ever staff-targeted notification in the codebase — every prior one targeted a student. `AdminLayout.tsx` had no notifications route/page at all (`AppShell`'s bell icon had no `onClick`), so the new notification would have been silently unreachable. Added `/admin/notifications` reusing the existing (already fully generic) `NotificationsPage` component, plus a nav item and `notificationsPath` wiring in `AdminLayout.tsx`, mirroring `StudentLayout.tsx`'s existing pattern.

## Test plan

- Backend: `mvn clean test` (37/37) and — given the scale of the data-scope reversal — the **full** `mvn verify` suite (214 tests: 37 unit + 177 integration across all IT classes, not just the obviously-related ones), against real MySQL, both clean. Five existing IT tests had been written specifically to prove §2.1's "teacher sees everything" behavior (method names ending `SinceScopeNoLongerRestrictsTeachers`) — these failed first as expected (confirming the reversal took effect), then were rewritten to assert the opposite rather than deleted, so scope-boundary coverage continues going forward.
- Frontend: `tsc --noEmit`/`pnpm build` clean; i18n placeholder-parity clean.

## Resolution

Deployed to `https://tb.example.com/` (backend via rsync + remote `deploy.sh`, then frontend via `frontend/scripts/deploy.sh`).

*Live-verified via browser click-through: `teacher001` (scoped to grade_10) sees only grade_10 proposals/cases/records/students/accounts, confirmed against `admin001` (school-wide) seeing everything. The admin notifications page renders and is reachable from the sidebar. The publish-notification's actual delivery (approving a real grade-10 proposal and confirming `teacher001` receives `eventProposal.approvedNeedsPublish`) was verified against live data — see `integration-test/STATUS.md` for the exact steps and any residual gaps.*
