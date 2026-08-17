# 011 — Grade filter dropdown stays in the old language after switching UI language without a reload

Status: **Fixed and verified** (2026-08-17)

## Symptom

On Admin → Student Management, clicking the EN/中 language toggle switches every
other piece of UI text immediately (nav labels, table headers, the "G10, Class A"
grade/class column, the class filter dropdown), but the **grade** filter dropdown's
options stay in the previous language (e.g. still "六年级"/"七年级"... after switching
to English) until the page is fully reloaded, at which point it correctly shows
"Grade 6"/"Grade 7"...

## Root cause

`frontend/src/app/api/useDictionaries.ts` fetches `/grades`/`/classes`/`/academic-years`/
`/service-categories` **once** into a module-level `cache` promise, shared across every
screen for the lifetime of the page load — a deliberate simplification ("Grades/classes/
academic years/service categories... barely change at runtime, so every screen sharing
one fetch-once-per-page-load cache is simpler... than each screen re-fetching"). `GET
/grades`'s `name` field is resolved server-side from the request's `Accept-Language`
header at fetch time (issue 010's carve-out doesn't apply to grade's dictionary
endpoint — it's still genuinely server-localized). The language toggle changes
`i18n.language` and re-renders every `t(...)`-driven string immediately, but doesn't
invalidate `useDictionaries`' cache or re-issue `GET /grades` with the new
`Accept-Language` — so the already-fetched, already-localized grade names just sit
there stale until something forces a fresh page load (full navigation/reload), which
starts a new `Accept-Language` request from scratch.

This is not new behavior from issue 010 — `GET /grades`'s `name` field was already
server-localized before that change, and `useDictionaries`' cache was already
fetch-once. It surfaced during issue 010's live-verification pass only because that
pass happened to toggle the language in-session while comparing English/Chinese
grade/class formatting.

The class filter dropdown does *not* have this problem post-issue-010, since
`class.name` is now raw/language-invariant data the frontend formats client-side
(`formatClassName`) — no server refetch is needed for it to update on a language
toggle.

## Proposed fix

Not designed here. Two directions worth considering in a future session: (a) have the
language toggle invalidate/reset `useDictionaries`' module-level `cache` so the next
mount re-fetches, or (b) move grade's dictionary `name` to the same raw-value pattern
issue 010 applied to grade's student-linked fields (though `GradeDto.name` has real
cross-module consumers — Project's eligible-grades display, Reports/CSV export — that
would need to move too, a larger change; see issue 010's "Scope decision" section for
why that was deliberately not done there).

## Test plan

Not yet fixed — no test plan to report.

## Resolution

Fixed by [issue 024](./024-service-category-dropdown-i18n-and-ordering.md)'s fix to `useDictionaries.ts`: the module-level cache is now language-aware (`cacheLanguage` tracking + `i18n.language` in the `useEffect` dependency array), and `changeLanguage()` in `lib/language.ts` calls `clearDictionaryCache()` to proactively invalidate the cache on language switch. Since grades and service categories share the same `useDictionaries` cache, both dropdowns now re-fetch with the new `Accept-Language` immediately on language toggle, without a page reload.

---
*Found live on `https://tb.example.com/` (Admin → Student Management, EN/中 toggle without a page reload) while verifying issue 010, 2026-08-10. Confirmed pre-existing (unrelated to issue 010's change — a full reload immediately shows the correct language) and confirmed NOT a regression before logging it here rather than fixing it silently.*
