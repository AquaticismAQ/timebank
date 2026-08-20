# 029 — Student Home/Progress/Profile pages show a blank Academic Year (Home also missing Grade)

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Reported by the user as a Chinese-only bug ("in the home page, the actual Academic Year data missing after the hello title"). Live-verification found the underlying defect is **not** language-specific — it reproduces identically in English and Chinese:

- Student Home (`/student/home`): under "Hello, {name} 👋" the subtitle read `· Academic Year` (zh: `· 学年`) — both the grade (`G10, Class A`) and the academic year value (`2025-2026`) were missing, leaving only the trailing label.
- The `AdminLayout`/`StudentLayout` header banner at the top of every student page read `Academic Year` alone (zh: `学年`) — grade showed correctly there, but the academic year value was blank.
- Student Progress (`/student/progress`) and Profile (`/student/profile`) pages showed the same blank academic year next to a correctly-populated grade.

## Root cause

`frontend/src/app/api/client.ts` — both `students.progress(id)` and `dashboards.student()` hardcoded `academicYear: ''` when mapping the backend's wire DTO to the frontend `StudentProgress` type, with a comment noting "academicYear ... [has] no backing endpoint in this pass, defaulted honestly rather than invented." `dashboards.student()` additionally hardcoded `grade: ''` (unlike `students.progress()`, which already backfilled grade from a second `students.get(id)` call).

This was stale: `GET /academic-years` already exists and returns a `current: true` flag on the active year (confirmed live: `{"id":"ay_2025_2026","code":"2025-2026",...,"current":true}`), but neither mapping function called it. The blank fields were identical in both `zh-CN` and `en` because the code path never varied by language — the user most likely only checked the Chinese UI and reasonably assumed a translation gap, but the value was empty everywhere.

## Fix

`frontend/src/app/api/client.ts`:
- `students.progress(id)`: now also fetches `api.dictionaries.academicYears()` in the same `Promise.all` as the progress/student calls, and sets `academicYear` from the entry with `current: true` (falls back to `''` on fetch failure, same as the existing grade fallback).
- `dashboards.student(studentId?)`: gained an optional `studentId` parameter. When provided, it fetches `api.students.get(studentId)` to backfill `grade` (mirroring `students.progress()`), and always fetches `academicYears()` to backfill `academicYear` the same way.

`frontend/src/app/components/student/StudentHome.tsx`: its `useEffect` now calls `api.dashboards.student(user?.studentId)` instead of `api.dashboards.student()`, so the dashboard call has a student id to backfill grade with.

No backend or i18n changes were needed — this was purely a frontend data-mapping gap, not a missing translation.

## Test plan

- `tsc --noEmit` and `pnpm build` clean.
- Live: log in as `student001`, check Home's "Hello" subtitle, the layout header banner, Progress page, and Profile page in both English and Chinese — all four should show the real grade and academic year (`G10, Class A · 2025-2026 Academic Year` / `十年级, A班 · 2025-2026 学年`).

## Resolution

Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`. Live-verified as `student001` in both languages: Home page subtitle, layout header banner, Progress page, and Profile page all now show the real grade and `2025-2026` academic year, matching the live `GET /academic-years` response's `current: true` entry.

*Live-verified via browser click-through on the real deployed frontend against the real backend, in both English and Chinese, across all four affected screens.*
