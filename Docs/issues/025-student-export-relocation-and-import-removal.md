# 025 — Student export relocated to Data Import/Export; import/export removed from Student Management

Status: **Fixed and verified** (2026-08-17)

## Symptom

Reported directly by the user:

1. **System Admin → Data Import/Export → Export Data** had no option to export student roster data, even though this is the canonical place for all data exports.
2. **Student Management** had redundant "Import" and "Export" buttons that were stubs (`toast.info('Bulk export coming soon')` / `toast.info('Bulk import coming soon')`) — import is already available in Data Import/Export, and export should live there too.

## Root cause

The Student Management page (`StudentList.tsx`) had placeholder import/export buttons that were never wired to real functionality. The Data Import/Export tab in `SystemAdmin.tsx` had export buttons for service records, account transactions, reports, and audit logs, but was missing a student roster export. The backend's `ExportJobRunner` / `ExportServiceImpl` had no `"students"` report type.

## Fix

### Backend

- **New `StudentExportRepository`** (`backend/src/main/java/cn/timebank/dataexchange/infrastructure/StudentExportRepository.java`) — keyset-paginated read of the `student` table, same pattern as `ServiceRecordExportRepository`. Exports: id, studentNumber, displayName, gradeId, classId, status, graduationYear, joinedAt.
- **`ExportJobRunner`** — added `"students"` case routing to a new `writeStudentsCsv()` method, chunked the same way as service records.
- **`ExportServiceImpl`** — added `"students"` to `SUPPORTED_REPORT_TYPES`.

### Frontend

- **`SystemAdmin.tsx`** — added a "Students" (`exportStudents`) button as the first option in the Export Data grid, changed grid from 4-col to 5-col to accommodate.
- **`StudentList.tsx`** — removed the stubbed Import/Export buttons and the now-unused `Download`/`Upload` icon imports.
- **i18n** — added `admin.system.exportStudents` key to both `en.json` ("Students") and `zh.json` ("学生信息").

## Acceptance criteria

- [x] Data Import/Export → Export Data includes a "Students" option that triggers a real async export job.
- [x] Student Management page no longer shows Import or Export buttons.
- [x] Frontend `tsc --noEmit` clean.
- [x] Backend compiles (new repository + runner method + supported type).

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Backend: `mvn compile` clean.
- Live: log in as admin, navigate to System Admin → Data Import/Export → Export Data, click "Students", confirm export job is created and downloads a CSV with student roster columns. Navigate to Student Management, confirm no Import/Export buttons.

## Resolution

Deployed 2026-08-17 to `https://tb.example.com/` (frontend + backend). Verified via API with `admin001` session:

- **Student export:** `POST /api/v1/exports` with `{"reportType":"students"}` → job `job_f641kxfj1yzff7zyw58jpqs8w5` created (status `QUEUED`). Polled `GET /exports/{id}` after 3s → `SUCCEEDED`, 11 rows. Downloaded via `GET /exports/{id}/download` → `GET /exports/{id}/content?token=...` → CSV with header `id,studentNumber,displayName,gradeId,classId,status,graduationYear,joinedAt` and 11 student rows (Jia Ming, Yun Li, Jordan, etc. — matches the seeded roster).
- **Student Management buttons removed:** Deployed JS bundle (`index-Dqf5qnuY.js`) contains only 1 occurrence of "Bulk export coming soon" (the i18n dictionary entry `admin.students.exportComingSoon`), down from 2 (i18n + hardcoded button string). The `toast.info('Bulk export coming soon')` button code is gone.
- **Export button in Data Import/Export:** Bundle contains `exportStudents` key (3 occurrences: en.json, zh.json, SystemAdmin.tsx reference).
- Frontend `tsc --noEmit` + `pnpm build` clean (deployed via `frontend/scripts/deploy.sh`). Backend deployed via `backend/scripts/deploy.sh` — service active.
