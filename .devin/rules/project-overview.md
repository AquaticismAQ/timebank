---
description: TimeBank project overview — phases, status, and what to build next
trigger: always_on
---

# TimeBank Project Context

TimeBank (时间银行) is a middle-school volunteer service recording system. Service hours → "time coins" (non-financial, never call them tokens/currency).

## Current State (2026-08-19)

**Backend (B0–B6 work items done, deployed, live on test server):**
- B0 Foundation, B1 Identity, B2 Base Data: `✅`
- B3–B5 all work items `✅`, phases `▶` on exit criteria (frontend demos, scale tests)
- B6 Hardening: most done; load test blocked on staging infra
- B7 Database Ops: mostly blocked on infrastructure
- B3F Cloud File Storage: not started (local-disk adapter in use)
- Notification render-on-read (I3.6): done — `title`/`body` rendered per-request from `templateCode` + reader's language
- New endpoints: `GET /students/directory`, `GET /users/directory` (capped name search for student pickers), `GET /me` now includes `studentId`
- `reviewSummary` wired in `ServiceRecord` so students see return/reject reasons
- Role & Permission Management overhaul: implemented, deployed, and live-verified (2026-08-19)

**Frontend (deployed to `https://tb.example.com/` — 2026-08-07):**
- I0 `✅` — types lifted, HTTP transport, envelope/error handling, dev-proxy recipe
- I1 `▶` (5/7 exit criteria met) — all 8 read domains swapped, shared pagination infra. Two open: bilingual re-audit, live `304` polling check
- I2 `✅` — all mutations wired and E2E verified; all 10 exit criteria met per `12-Integration-I2-WritePath.md`
- I3 `▶` — I3.1-I3.6 done; I3.7 acceptance scenarios 11/12 done; only scenario #12 (bilingual/mobile+desktop sweep) remains open

**Deployment:**
- `frontend/scripts/deploy.sh` — builds locally, rsyncs `dist/` to server, nginx serves SPA + proxies `/api/*`
- `frontend/.env.production` — intentionally empty of API toggles (same-origin in production)
- Both frontend and backend live at `https://tb.example.com/`

## What Comes Next

**I3 remaining work:**
- I3.7: only scenario #12 still open (complete bilingual/mobile+desktop sweep across admin/teacher portal)
- I3.8-I3.12: engineering-readiness halves buildable now; institutional-acceptance halves need the target deployment school
- Rules UI redesign (backend's draft→publish lifecycle vs UI's in-place edit)

**Known named gaps (found, not yet fixed):**
- `serviceRecords.withdraw`, `accounts.reverse`, `users.unlock` — orphaned client methods with no UI
- `EventProposal` has no `reviewSummary` equivalent (student can't see why changes were requested)
- Scenario #12: full admin/teacher portal bilingual + mobile click-through not yet done

## Key Documents

| Doc | Purpose |
|-----|---------|
| `Docs/implementation/00-Status.md` | Living status — **read before assuming what exists** |
| `Docs/implementation/01-Overview.md` | Phase map, timeline, gates, conventions |
| `Docs/integration/APIContract.md` | The REST API contract — authoritative for both sides |
| `Docs/backend/Design.md` | Backend architecture, decisions (D1–D12), module layout |
| `Docs/frontend/Design.md` | Frontend page/flow spec |
| `Docs/implementation/10-..through..13-` | I0–I3 detailed work items and exit criteria |
| `Docs/TestAccounts.md` | Login credentials for seeded test accounts |
