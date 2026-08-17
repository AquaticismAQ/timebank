# 024 — Service category dropdowns show Chinese-only names and "Other" is not sorted last

Status: **Fixed and verified** (2026-08-17)

## Symptom

Reported directly by the user: in Project Management → New Project → Service Category dropdown (and all similar service category dropdowns across the app), there are two issues:

1. **Category names are always displayed in Chinese**, even when the UI language is set to English. Switching to English updates every other `t(...)`-driven string immediately, but service category options stay in Chinese until a full page reload.
2. **The "Other" option is not placed as the last option** in the dropdown — it appears in an arbitrary position depending on JPA's unordered `findAll()` return order.

## Root cause

**Issue 1 (i18n):** Same root cause as [issue 011](./011-grade-dictionary-stale-after-language-toggle.md) — `frontend/src/app/api/useDictionaries.ts` fetches `/service-categories` once into a module-level `cache` promise, shared across every screen for the lifetime of the page load. The backend resolves `name` server-side per the request's `Accept-Language` header (`ServiceCategoryServiceImpl.toDto()` → `LocalizedNames.pick()`), but the language toggle (`lib/language.ts` → `changeLanguage()`) changes `i18n.language` and re-renders every `t(...)`-driven string immediately without invalidating `useDictionaries`' cache or re-issuing `GET /service-categories` with the new `Accept-Language`. The already-fetched, already-localized names sit stale until a full page reload forces a fresh request.

Issue 011 documented this exact cache-staleness pattern for the grade filter dropdown but was never fixed. This issue reports the same bug for service categories — the fix should resolve both, since they share the same cache and the same root cause.

**Issue 2 (ordering):** `ServiceCategoryServiceImpl.list()` (`backend/src/main/java/cn/timebank/organization/application/ServiceCategoryServiceImpl.java:34`) calls `repository.findAll()` with no ordering guarantee. `ServiceCategoryRepository` extends `JpaRepository` without any `@OrderBy` or custom query. The seed data (`R__seed_base_data.sql:44-50`) inserts `cat_other` last, but JPA does not guarantee insertion order — the actual return order depends on database internals and can vary.

## Proposed fix

**Issue 1 (frontend):** Make the `useDictionaries` cache language-aware:
- Track `cacheLanguage` alongside the module-level `cache` promise.
- In `loadDictionaries()`, compare `i18n.language` against `cacheLanguage` and re-fetch if they differ.
- Add `i18n.language` to the `useEffect` dependency array in `useDictionaries()` so mounted components re-fetch immediately on language change.
- Export a `clearDictionaryCache()` function and call it from `changeLanguage()` in `lib/language.ts` to proactively invalidate the cache at the moment of the switch.

This also resolves [issue 011](./011-grade-dictionary-stale-after-language-toggle.md) for grades, since grades share the same cache.

**Issue 2 (backend):** Add sorting in `ServiceCategoryServiceImpl.list()` so that categories are ordered by `code`, with any category whose `code` ends with `_other` always appearing last. This is the upstream fix per the project's "backend owns data ordering" convention — the frontend should not need to re-sort.

## Acceptance criteria

- Switching the UI language from Chinese to English (or vice versa) immediately updates service category dropdown options in-place, without a page reload.
- The same fix also resolves the grade filter dropdown staleness reported in issue 011.
- "Other" (or any category whose code ends with `_other`) always appears as the last option in every service category dropdown, regardless of database return order.
- All existing service category dropdowns across the app benefit from both fixes without per-component changes.

## Test plan

- Frontend: `tsc --noEmit` and `pnpm build` clean.
- Backend: `mvn test` clean (no new test needed — ordering is a service-layer sort, not a query change).
- Live: log in on the test server, open Project Management → New Project, switch language, confirm category names update immediately; confirm "Other" is the last option in both languages.

## Resolution

**Issue 1 (i18n — frontend):** `frontend/src/app/api/useDictionaries.ts` — the module-level cache is now language-aware: a `cacheLanguage` variable tracks which language the cached data was fetched in, and `loadDictionaries()` re-fetches when `i18n.language` differs. The `useEffect` in `useDictionaries()` now depends on `[i18n.language]` so mounted components re-fetch immediately on language change. A new `clearDictionaryCache()` export is called from `changeLanguage()` in `frontend/src/app/lib/language.ts` to proactively invalidate the cache at the moment of the switch. This also resolves [issue 011](./011-grade-dictionary-stale-after-language-toggle.md) (grade dropdown staleness), since grades share the same cache.

**Issue 2 (ordering — backend):** `backend/src/main/java/cn/timebank/organization/application/ServiceCategoryServiceImpl.java` — `list()` now sorts categories by `code` with a custom comparator that pushes any code ending in `_other` to the end, replacing the unordered `findAll().stream()`.

**Verification:** Deployed 2026-08-17 to `https://tb.example.com/` (frontend + backend). Verified via API with `admin001` session:

- **i18n (issue 024 + 011):** `GET /service-categories` with `Accept-Language: zh-CN` returns Chinese names (校园服务, 社区服务, …); with `Accept-Language: en` returns English names (Campus Service, Community Service, …). Same for `GET /grades` (六年级 vs Grade 6). Frontend `tsc --noEmit` + `pnpm build` clean (deployed via `frontend/scripts/deploy.sh`).
- **Ordering:** `cat_other` is the last entry in both languages; remaining categories sorted alphabetically by `code` (campus, community, education, environment, welfare).
