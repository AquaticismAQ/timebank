# 045 — School Info had no edit path, Partner Organizations showed the system school, no sysadmin test account existed

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Requested directly by the user, referencing `Docs/optimization/03-ServiceRecordFormOptimization.md` §4.1 (the school-organization design doc): four related gaps around the system's single "school organization" record.

1. The school's own info (name, full name, office location, contact) had no edit UI anywhere — only a read-only `GET /school/info`, mixed into System Settings' general "System Info" card alongside unrelated version/API info.
2. System Admin → "Partner Organizations" listed the system school organization (`isSchoolOrganization: true`) alongside real partners — confusing, since it's not a partner.
3. No `sysadmin` login existed on the seeded dataset, so the admin/sysadmin distinction §4.1 depends on (school settings edits must be sysadmin-only) was unexercisable.
4. The live database had a second, decoy `type=SCHOOL` organization named confusingly similarly to the real flagged school org ("Example City HD School" vs. the real "Example City HD"), risking real confusion in the admin UI.

## Root cause

§4.1 always intended school info to be editable only through "a permission-protected school settings flow," but that flow was never built — `SchoolInfoController` only ever had `GET`. Separately, `OrganizationServiceImpl.list()` (backing both the Partner Organizations tab and every partner-org picker built on the same endpoint) never excluded the school org, since nothing had needed to distinguish "list of assignable partners" from "list of every organization row" until now. The decoy organization and missing sysadmin account were pre-existing seed-data gaps, invisible until this feature made them relevant.

## Fix

Backend:
- New `PATCH /api/v1/school/info` (contract §4.16) — partial body (`name`/`fullName`/`studentDevCenter`/`contact`, all optional), no version/optimistic-locking field (a single, rarely-edited row with exactly one class of editor). Authorization is `sysadmin`-role-code, not a permission — `admin` and `sysadmin` hold identical permission grants since `V21`'s merge, so a `@PreAuthorize("hasAuthority(...)")` check alone couldn't separate them. Following the existing `ProjectController.isAdmin(auth)` convention, the role check is resolved in the controller and passed into `SchoolInfoService.update(request, isSysadmin, actorId)` as a boolean, keeping the actual authorization rule unit/integration-testable without a real HTTP round-trip. Writes a real audit entry (`schoolInfo.update`, real before/after) — see `[[feedback-mutations-must-audit-log]]`.
- `SchoolInfoDto` gained `fullName` (the entity already had the column; it was just never exposed via the API before).
- `OrganizationServiceImpl.list()` now always excludes `isSchoolOrganization: true` rows — the school organization is the school's own archive record, not a partner, and has its own read/edit surface via `/school/info`. `get(id)`/`getSchoolOrganization()` still resolve it directly by id; only the list is filtered.

Frontend (`SystemAdmin.tsx`):
- New "School Info" card in System Settings, separate from the now-trimmed "System Info" card (which keeps only System Name/Version/API Version — the school-specific fields moved out). Shows an "Edit" button only when `user.roles.includes('sysadmin')` — hardcoded role-code check, no new permission, matching the backend's identical gate; a non-sysadmin (including `admin`) sees a lock note instead, and the underlying `PATCH` call would `403` even if someone bypassed the UI.
- No change needed for the Partner Organizations tab itself — the backend now excludes the school org from `GET /organizations` entirely, so the existing, unmodified list component simply stops showing it.

Database/seeding:
- `sysadmin001` (display name "Teacher Vera Xu" — an already-referenced persona name in this codebase's UI copy for rule-change contacts) added to `integration-test/mock-data.ts`'s `users` array for future from-scratch reseeds, and inserted directly on the live server (the same bcrypt-hash-then-`INSERT` procedure `Docs/TestAccounts.md`'s "Re-seeding" section already documents for single-account additions, since `seed.ts` refuses to re-run against an already-seeded server). Credentials recorded in `Docs/TestAccounts.md`.
- The decoy organization (`org_083bedc38fdc570f1fef`, "Example City HD School", `is_school_organization=0`) was **renamed**, not deleted or archived — it turned out to be in active use (3 real projects' partner organization, 1 service record's immutable historical snapshot), so hard-deleting would have broken FKs and archiving alone wouldn't have fixed the confusing name wherever it's still displayed. Renamed to "Example County Experimental Middle School" (narrated and confirmed with the user first, given the real references at stake) — no data lost, no FK broken, name is now unambiguous.

## Test plan

- Backend: `mvn clean test` (fast suite, incl. `PermissionAnnotationTest`) clean. New `SchoolInfoIT` (sysadmin succeeds and is audited; a resolved `isSysadmin=false` — modeling `admin` — is rejected) and a new test in `OrganizationProjectCountIT` (`listNeverIncludesTheSchoolOrganization`, proving `list()` excludes it while `get(id)` still resolves it) both pass against real MySQL, alongside the pre-existing `DictionaryCachingIT`/`SystemSettingsIT` (unaffected, confirming no regression).
- Live: full edit round-trip as `sysadmin001` (edit → save → confirm displayed value changed → confirm via `GET /audit-logs` that a real audited row exists with correct before/after → revert to the original value); confirmed `admin001` sees a read-only card and gets a real `403` hitting `PATCH /school/info` directly (not just a hidden button); confirmed Partner Organizations count/list no longer includes the school org; confirmed `sysadmin001` login works end-to-end.

## Resolution

Deployed to `https://tb.example.com/` (backend + frontend). Live-verified via Playwright:

- As `admin001`: School Info card is read-only with "Only a system administrator can edit school info."; Partner Organizations shows "7 partner organizations" with no school-org row; a direct `PATCH /api/v1/school/info` call returns a real `403 FORBIDDEN`.
- As `sysadmin001` (logged in as "Teacher Vera Xu", confirming the new account and its display name work end-to-end): School Info card shows a real "Edit" button; editing Admin Contact to a test value, saving, and confirming the new value displayed; confirmed via `GET /audit-logs?filter[resourceType]=schoolInfo` that both the test edit and its revert produced real, correctly-shaped audit rows (`action: "schoolInfo.update"`, real before/after JSON, real actor id); reverted the value back to the original ("Teacher Wei Chen") — no net data change.
- Database: confirmed via direct query that the decoy org's rename to "Example County Experimental Middle School" preserved all 3 referencing projects' `organization_id` unchanged (no FK breakage), and that `org_school_default` ("Example City HD", `is_school_organization=1`) remains the sole flagged school organization.

*Live-verified via Playwright and direct API calls against the real deployed frontend+backend+database, as both a sysadmin and a non-sysadmin admin account. The only live-data write beyond the reverted test edit is the decoy org's rename — narrated and confirmed with the user first, since it touched a row with 3 real project references; no data was lost or FK broken.*
