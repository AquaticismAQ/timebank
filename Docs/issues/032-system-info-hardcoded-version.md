# 032 — System Admin → System Info showed a hardcoded, wrong version string

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Reported by the user: the System Admin page's "System Info" panel showed a "Version" row that never changed no matter what was actually deployed — a permanently hardcoded string.

## Root cause

`frontend/src/app/components/admin/SystemAdmin.tsx`'s System Info panel hardcoded `{ label: t('admin.system.sysVersion'), value: 'v2.0.0-beta (Phase 2)' }` directly in the render list. This string didn't correspond to anything real — there was no backend concept of "system version" at all before this session (`backend/pom.xml`'s own `<version>` had sat at the placeholder `0.1.0-SNAPSHOT` since project init, and nothing exposed it over the API), so the frontend had no real value to source it from even if it had tried.

## Fix

Part of the broader version-management scheme introduced in the same session (see `Docs/operations/VersionManagement.md`):

- Backend: new `GET /api/v1/system/version` (`cn.timebank.common.web.SystemVersionController`, contract §4.17), reading the real deployed version from Spring Boot's `BuildProperties` bean (itself sourced from `spring-boot-maven-plugin`'s `build-info` goal stamping `pom.xml`'s `<version>` at build time — never hand-typed).
- Frontend: new `api.system.version()` client method. `SystemAdmin.tsx` now fetches it on mount and renders the real value (`v${systemVersion}`, `—` while loading/on failure) instead of the hardcoded string.

While building this out, also added a version display to the sidebar's "Time Bank" brand row (desktop only — not requested as a bug fix, but the same underlying API, documented separately in `Docs/operations/VersionManagement.md` rather than as its own issue doc since it's a new feature, not a fix).

## Test plan

- Backend: `mvn test` clean; `mvn -DskipTests package` confirmed `build.version` lands in `target/classes/META-INF/build-info.properties`.
- Frontend: `tsc --noEmit`/`pnpm build` clean.
- Live: System Admin → System Info's "Version" row shows the real deployed version (`v0.1.1`), matching `GET /api/v1/system/version`'s response and `backend/pom.xml`'s `<version>`.

## Resolution

Deployed to `https://tb.example.com/` (backend then frontend, per `Docs/operations/VersionManagement.md`'s release checklist). Live-verified as `admin001`: System Info's Version row reads "v0.1.1", matching a direct `GET /api/v1/system/version` call and the backend's own `build-info.properties`.

*Live-verified via browser click-through on the real deployed frontend against the real backend.*
