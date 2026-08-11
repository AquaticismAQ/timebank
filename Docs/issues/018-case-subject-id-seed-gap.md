# 018 — Seeded cases never had a real `subjectId`, blocking the new "related object" feature

Status: **Fixed and live-verified (2026-08-11)**

## Symptom

Named as a known gap in `04-DataViewOptimization.md`'s Phase 5 write-up: all 5
seeded `Case` rows have `subjectType = 'serviceRecord'` but `subjectId =
NULL`, so Case Review's new detail-workbench features that depend on a real
subject — the "关联对象"/"Related Item" field (D8's `CaseDto.subjectLabel`)
and the "相关材料"/"Related Materials" tab (real evidence file lookup) —
could never render their positive path against seed data. Every case showed
an empty field and "no materials available," regardless of whether the
underlying code was correct.

## Root cause

`integration-test/mock-data.ts`'s five `SeedCase` entries each set
`subjectType: 'serviceRecord'` but never set `subjectId` at all —
`seed.ts`'s Phase 6 loop passes `c.subjectId` straight through to
`POST /api/v1/cases`, so the backend stored `NULL`. This predates
`CaseDto.subjectLabel`/the Related Materials tab entirely (those are new
this session); the gap was invisible until a UI that actually reads
`subjectId` existed.

A second, adjacent gap surfaced while fixing the first: `seed.ts`'s service-record
creation (Phase 4) never sent `supervisorKind` at all for `SELF`-sourced
records. That was harmless when the original seed ran (2026-08-07), but
`03-ServiceRecordFormOptimization.md` (shipped 2026-08-10) added a real
submission-time validation requiring `supervisorKind` on `SELF` records — so
a *fresh* re-seed today would fail submitting any `SELF` record, including
the pre-existing "Example District A Community" one, not just anything new.

## Fix

**Seed script (for future fresh seeds):**
- `SeedServiceRecord` gained an optional `key` field — a stable lookup key
  resolved to the real server-generated record id at seed time (real ids
  don't exist until Phase 4 creates the row), mirroring the existing
  `projectIdByTitle` pattern `seed.ts` already used for project references.
- `SeedCase.subjectId`'s meaning changed: when `subjectType === 'serviceRecord'`,
  it's now a `SeedServiceRecord.key`, resolved to the real id in Phase 6 (throws
  a clear error if the key doesn't match any record, rather than silently
  sending `undefined`).
- Five `SeedCase` entries updated to reference real, narratively-matching
  records — three by adding `key` to existing records (`student001`'s
  approved library project record, `student001`'s submitted external library
  record, `student002`'s approved welfare-home project record), and one new
  `SeedServiceRecord` (`student002_returned_external`, a `SELF`-source
  record so it's actually creatable under today's rules) added specifically
  because no `returned` record existed for `student002` at all.
- `SeedServiceRecord` gained a `supervisorKind` field; `seed.ts`'s Phase 4
  request body now sends it. Applied to the new record and retrofitted onto
  the one existing `SELF` record that needed it to keep submitting cleanly
  on a fresh seed.

**Live data (the already-seeded server, which `seed.ts` refuses to
re-run against):**
- Created the one missing service record for real via the actual API — logged
  in as `student002`, uploaded evidence, created + submitted a `SELF`-source
  record, then logged in as `admin001` and returned it — using a small
  one-off script that reused `seed.ts`'s own session/upload helpers (not
  committed; deleted after use).
- Ran a direct `UPDATE case_record SET subject_id = ... WHERE id = ...` for
  all 5 existing case rows against the live `timebank` schema, matching each
  case's narrative to the appropriate real record id.

## Test plan

- `npx tsc --noEmit` clean on `integration-test/*.ts` after the script changes.
- Live: opened the "Appeal: record rejected without a documented reason" case
  (the one pointing at the brand-new record, which has a real `recordNumber`)
  and confirmed both the "关联对象" field and the "相关材料" tab now show
  real data.
- The other 4 cases point at records created 2026-08-07, before
  `recordNumber` generation existed (shipped 2026-08-10) — confirmed via
  direct query that their `record_number` is `NULL`. `subjectLabel` correctly
  stays empty for those by design (`ServiceRecordServiceImpl.recordLabel`
  never falls back to the raw id) — not a remaining bug, just a ceiling on
  how much of the positive path old seed data can demonstrate.

## Resolution

- `integration-test/mock-data.ts` and `integration-test/seed.ts` updated —
  future fresh seeds will produce cases with real `subjectId`s pointing at
  records with a real `recordNumber` (all seed-created records go through
  the current API, which always generates one now).
- Live `timebank` schema: 1 new service record created via the real API, 5
  `case_record` rows updated via direct SQL.

---
*Live-verified 2026-08-11 against `https://tb.example.com/` via Playwright MCP,
logged in as `admin001`: "Appeal: record rejected without a documented
reason" now shows "关联对象: TB-2025-2026-10-2024002-001" and a real
"evidence.pdf" link in Related Materials. The other 4 cases were confirmed
via direct DB query to correctly show no label (pre-`recordNumber` records),
not click-verified individually since their expected behavior is "show
nothing," which isn't independently distinguishable from a regression by
eye — the query confirms the cause instead.*
