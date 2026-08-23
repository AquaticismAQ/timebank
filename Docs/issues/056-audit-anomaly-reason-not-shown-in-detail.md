# 056 — Audit Center's "Anomaly" badge gave no reason in the record detail view

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

Reported directly by the user: in the Admin Portal's Audit Center, some
service records show an "Anomaly" badge in the list, but opening a flagged
record's detail/review dialog gave no indication of *why* it was flagged —
the reviewer had to trust the badge with no explanation to act on.

## Root cause

The backend already computes and stores a reason (`hasAnomaly`/`anomalyNote`
on `ServiceRecord`, set in `ServiceRecordServiceImpl.validateAndFlag` for two
rule-based cases: overlapping time window with another record, or disclosed
travel time) and already returns both fields on the single-record GET used
by the detail dialog. But:

1. The frontend `ServiceRecord` TypeScript type (`api/types.ts`) never
   declared `hasAnomaly`/`anomalyNote` — only the separate `ReviewQueueItem`
   type (used for the list row's badge) had them — so the detail dialog had
   no typed way to read the reason even though the data was already in the
   response.
2. `AuditCenter.tsx`'s Basic Info tab never rendered the note anywhere.

## Fix

- Added `hasAnomaly?: boolean` / `anomalyNote?: string` to the `ServiceRecord`
  type in `frontend/src/app/api/types.ts`, matching the backend
  `ServiceRecordDto` fields that were already being returned but unused.
- Added a red-bordered "Anomaly Reason" block at the end of the Basic Info
  tab in `AuditCenter.tsx`'s detail workbench, shown only when
  `detailRecord.hasAnomaly` is true, displaying `detailRecord.anomalyNote`.
- Added the `admin.audit.fieldAnomalyReason` i18n key ("Anomaly Reason" /
  "异常原因") to both `en.json` and `zh.json`.

## Test plan

- `pnpm build` / `tsc --noEmit` (via `frontend/scripts/deploy.sh`) — clean.
- Live Playwright pass against the deployed server as `admin001`: opened
  Audit Center, opened Zhang Ming's record flagged "Anomaly" (record
  `TB-2025-2026-10-2024001-004`), confirmed the Basic Info tab now shows a
  red "Anomaly Reason" box reading "Travel time disclosed — requires manual
  review." at the end of the field grid.

## Resolution

`frontend/src/app/api/types.ts`: added `hasAnomaly`/`anomalyNote` to
`ServiceRecord`. `frontend/src/app/components/admin/AuditCenter.tsx`: new
conditional red "Anomaly Reason" section in the Basic Info tab. `en.json`/
`zh.json`: new `admin.audit.fieldAnomalyReason` key. Frontend rebuilt and
redeployed (`frontend/scripts/deploy.sh`).

---
*Live-verified 2026-08-23 against the real deployed server via Playwright
MCP, logged in as `admin001`/Wei Chen: opened the flagged Zhang Ming record
in Audit Center, confirmed the red "Anomaly Reason" box renders at the end
of Basic Info with the correct travel-time-disclosure note. Chinese-locale
label text was not separately screenshotted this session (only verified in
the `zh.json` source) — a later session should spot-check it under `zh` if
that matters.*
