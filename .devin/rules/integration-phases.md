---
description: Integration phase work items (I0-I3) — I3 in progress, I3.1-I3.6 done
trigger: always_on
---

# Integration Phases — I2 Done, I3 In Progress

I0 `✅`. I1 `▶` (5/7, functionality complete). **I2 `✅` (all 10 exit criteria met).** I3 `▶` (I3.1-I3.6 done, I3.7 8/12 scenarios).

## I0 · Contract Prep — `✅` done (2026-08-04, §21)

Built: `api/types.ts` (lifted), `api/http.ts` (transport), `api/config.ts` (toggle), enum mapping, dev-proxy recipe. Gate G1 met.

## I1 · Read Path — `▶` mostly done (2026-08-05, §22)

All 8 domains swapped (18 methods, 10 screens). Backend gained `FilterSpecifications` + per-domain filter/search. Shared infra: `usePagedApi`, `PageControls`, `ListStateBanner`, `fromBackendEnum`/`toBackendEnum`.

Two exit criteria open (verification only): full bilingual re-audit, live `304` polling check.

## I2 · Write Path — `✅` done (2026-08-06, §23-§25)

All 10 exit criteria met. All mutation flows wired with real branches in `client.ts` + component handlers, E2E verified against the real deployed backend. Major items beyond the initial wiring (§24-§25):

- Evidence upload UI built (was decorative-only dropzone, §24)
- `CaseReview.tsx` — admin cases screen (appeal→resolve chain, §23.2)
- `ProjectRegistrants.tsx` — admin confirm/reject/bulk-actions (§25)
- `ProposalReview.tsx` — admin proposal review screen (§25.1)
- `applications.cancel` student-facing button (§25.1)
- `accounts.freeze`/`unfreeze` toggle (§25.1)

### Still-orphaned methods (found §25.1, not fixed — named gaps)

- `serviceRecords.withdraw` — pull submitted-but-not-reviewed back to draft
- `accounts.reverse` — undo a specific ledger transaction
- `users.unlock` — needs backend DTO change first (`lockedUntil` field missing)

## I3 · Acceptance & Cutover — `▶` in progress (2026-08-06/07, §26-§34)

### Done (I3.1-I3.6)

| ID | Item | Status | Notes |
|----|------|--------|-------|
| I3.1 | Evidence upload flow | `✅` | Built in §24 (orphaned-method sweep found it missing) |
| I3.2 | Upload failure states | `✅` | Client-side pre-validation (20MB, PDF/JPG/PNG) + server scan rejection surfaced per-file (§26) |
| I3.3 | Evidence viewing | `✅` | `EvidenceLink.tsx` — fetches fresh short-lived URL per click, wired in both student + admin (§26) |
| I3.4 | Language persistence | `✅` | `lib/language.ts` helper → `PATCH /me/profile`; login syncs from server (§26) |
| I3.5 | Localization sweep | `✅` | Static analysis: 826 keys audited, 8 gaps fixed in both locales including `common.unknownError` (§26) |
| I3.6 | Notification render-on-read | `✅` | Backend renders `title`/`body` from `templateCode` + reader's `Accept-Language` per request (§27) |

### I3.7 Acceptance Scenarios — 8/12 done

| # | Scenario | Status | Notes |
|---|----------|--------|-------|
| 1 | Changes-requested → resubmit → approve (proposal) | `✅` | Full chain proven live §33; org-fields fix §30, edit-mode fix §31 |
| 4 | Returned → resubmit → approve (record) | `✅` | Full chain proven live §33; resubmit fix §32, evidence pre-fill fix §33 |
| 8 | Out-of-scope teacher access | `✅` | 3 new IT tests for serviceRecords/accounts/proposals (§28) |
| 12 | Bilingual + mobile (student portal spot-check) | partial | 6 student screens automated (§33); admin/teacher portal not yet done |
| 2,3,5,6,7,9,10,11 | Various | `✅` | Already proven in prior passes |

### I3.8-I3.12 — split into engineering-readiness vs institutional-acceptance (§29)

| ID | Item | Engineering half | Needs target school |
|----|------|-----------------|-------------------|
| I3.8 | Historical import rehearsal | Import code exists and works | Real historical data |
| I3.9 | UAT with real users | All flows buildable | Real teachers + students |
| I3.10 | Production data seeding | Seeder infrastructure exists | Real student/class roster |
| I3.11 | Cutover plan + rollback | Documented | Term boundary decision |
| I3.12 | Post-launch watch | Reconciliation code exists | Live production traffic |

### Additional fixes landed during I3 (§30-§33)

- Proposal organizational fields: co-organizer pickers, supervisor picker, organization dual-path (select existing or create new) — `SearchPicker.tsx` component, `directory.read` permission (§30)
- `GET /me` returns `studentId` (§31)
- `openForm()` edit vs copy mode for proposals (§31)
- Service record resubmit: full edit-mode with evidence pre-fill from `api.serviceRecords.get()` (§32-§33)
- `reviewSummary` wired so students see return/reject reasons (§32)
- `plannedDate` ISO-instant→date-string mapping fix for proposal resubmit (§33)
