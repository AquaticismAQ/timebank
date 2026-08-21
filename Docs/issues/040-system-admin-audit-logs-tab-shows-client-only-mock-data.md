# 040 — System Admin's "Audit Logs" tab never showed real backend data

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Found while live-verifying issue 039's fix: after rejecting a real proposal and confirming (via a direct `GET /audit-logs` call and the Proposal Review "Review History" tab) that a real audit entry was written to the backend, the user checked System Admin → "Audit Logs" and did not see it — the newest entry shown there was an older "approved" log, not the fresh reject.

## Root cause

The System Admin "Audit Logs" tab (`SystemAdmin.tsx`'s `logs` tab, `activeTab === 'logs'` block) has never been backed by the real `GET /api/v1/audit-logs` endpoint. It renders `useLogs().logs`, which comes from `LogsContext`/`LogsProvider` in `frontend/src/app/App.tsx` — a client-only React context seeded with 5 hardcoded fake entries (`SEED_LOGS`, e.g. "Teacher Wei Chen — Approved service record — 2026-07-18") and appended to in-session via `addLog()` calls scattered through `SystemAdmin.tsx` (rule edits, user creation) and `StudentList.tsx`. None of this is persisted or fetched from the backend — a page reload resets it back to the 5 seed rows, and nothing written by *any* real backend action (proposal decisions, service record reviews, account adjustments, etc.) ever appears there, not just this session's fix. `CLAUDE.md` already documented this component as "client-only, seeded, not backed by an API yet" — this issue is about actually closing that gap now that a real consumer (the user checking today's fix) needed it.

Confirmed via `grep` that `LogsContext`/`useLogs`/`LogsProvider` are referenced only in `App.tsx` (the provider), `SystemAdmin.tsx` (the sole reader, plus a writer), and `StudentList.tsx` (a writer only) — no other screen depends on this mechanism.

## Fix

Wire the Logs tab to the real backend instead:

- `frontend/src/app/api/types.ts`: new `AuditLogEntry` interface matching the real `AuditLogDto` wire shape.
- `frontend/src/app/api/client.ts`: new `api.auditLogs.list(params)` calling `GET /audit-logs` (paginated, sortable only by `createdAt`, filterable by `resourceType`/`resourceId`/`actorId`/`createdFrom`/`createdTo` per the real controller).
- `SystemAdmin.tsx`'s Logs tab: fetches real, paginated audit log entries instead of reading `useLogs()`; resolves `actorId` to a display name using the tab's already-loaded `users` list (falls back to a "System" label for the null-actor case, e.g. the auto-archive audit entry from issue 009, rather than showing a raw id or fabricating a name).
- Removed the now-fully-dead `LogsContext`/`LogsProvider`/`SEED_LOGS`/`useLogs`/`addLog` mechanism from `App.tsx`, and its two call sites in `SystemAdmin.tsx` (rule save, user create) and one in `StudentList.tsx` (reminder send) — confirmed via the grep above that nothing else depended on it, and its entire purpose (feeding this one client-only display) is gone once the display reads real data instead.

## Test plan

- Frontend: `pnpm build` clean.
- Live: as `admin001`, open System Admin → Audit Logs and confirm the fresh `eventProposal.reviewDecision` entry from issue 039's live-verification appears at the top, with the actor's real display name resolved.

## Resolution

Deployed to `https://tb.example.com/` (frontend only via `frontend/scripts/deploy.sh`; no backend change was needed since `GET /audit-logs` already existed). Live-verified via Playwright as `admin001`: System Admin → Audit Logs now shows "748 entries" (the real, full backend `audit_log` table, paginated 20/page across 38 pages) instead of the 5 static seed rows. The top of the list correctly shows, in order: a `file.crossStudentAccess` entry, then **`eventProposal.reviewDecision` (`Memo: REJECT`) for `prop_8sn1ykhgstb6xwvxrgha0rje7v`, actor "Teacher Wei Chen", 8/21/2026 4:53:49 PM** — issue 039's fix, now visible exactly where the user was originally looking for it. Actor names resolve correctly for both `admin001` ("Teacher Wei Chen") and `student001` ("Zhang Ming", from the proposal's own `create`/`submit` entries), confirming the `users`-list lookup works. Pagination controls render and are wired to the real `page` metadata (748 total, 38 pages).

*Live-verified via Playwright against the real deployed frontend+backend. No live data was written or needed reverting — this was a pure read-path fix.*
