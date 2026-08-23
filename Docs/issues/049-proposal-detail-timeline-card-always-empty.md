# 049 — Event Proposal detail's "Timeline" card is always empty, even for approved proposals

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

On the Student Portal's Propose Volunteer Activity → proposal detail view, the "Timeline" card at the bottom of the right-hand column never shows any entries, for any proposal in any status (draft, submitted, changes-requested, approved, rejected, withdrawn).

## Root cause

`EventProposals.tsx`'s detail view rendered `(p.timeline ?? []).map(...)`, reading `EventProposal.timeline` — a field that `types.ts`'s own comment already documented as "never actually populated — the real backend `ProposalDto` has no `timeline` field." It was optional and guarded with `?? []` (a prior fix, issue 016, that stopped it from crashing but never wired it to real data), so it silently rendered an empty list regardless of status — a pure client-side wiring gap, not a backend or approval-status-dependent issue.

The real data source already exists and is already consumed elsewhere: `api.eventProposals.timeline(id)` (`client.ts`) calls the real `GET /event-proposals/{id}/revisions` + review-decision-merging `GET /event-proposals/{id}/timeline` endpoint, returning `TimelineEntry[]` (`type`, `description`, `actorId`, `occurredAt`). The **admin** Proposal Review workbench (`components/admin/ProposalReview.tsx`) already fetches this correctly in a `useEffect` keyed on the open detail id and renders it as a simple chronological list. The student-facing detail view was simply never updated to do the same — it kept reading the old, always-`undefined` mock-era field shape.

## Fix

`EventProposals.tsx`: added `detailTimeline`/`detailTimelineLoading` state and a `useEffect` keyed on `detailId` that calls `api.eventProposals.timeline(detailId)`, mirroring `ProposalReview.tsx`'s exact pattern. The Timeline card now shows a loading state, a "No history yet" empty state, or the real merged entries (timestamp + `type` + raw `description`, no per-action i18n table — same convention `ProposalReview.tsx` and the System Admin Audit Logs tab already use for the same reason: raw backend text over a per-action translation table that would drift).

Removed the now-fully-dead `EventProposal.timeline`/`ProposalTimeline` type (nothing reads them anymore) and the stale `timeline` mention in `client.ts`'s "fields that don't exist on the backend" comment. Added a new `student.proposals.noHistory` i18n key (en/zh) for the empty state.

## Test plan

1. Log in as `student001`, open Propose Volunteer Activity.
2. Open any proposal that has at least one revision or review decision — confirm the Timeline card shows real dated entries (not empty).
3. Open a plain draft with no revisions/reviews yet — confirm it shows "No history yet" rather than a blank card with no explanation.
4. Repeat in Chinese — confirm "暂无记录" for the empty case.
5. `tsc --noEmit` clean (already verified locally).

## Resolution

Deployed via `frontend/scripts/deploy.sh` to the shared test server, then live-verified with Playwright as `student001`. Opened the approved "Campus Reading Promotion — Book Charity Sale and Picture Book Donation" proposal: the Timeline card now shows three real, dated entries — `created`/"Proposal created", `submitted`/"Proposal submitted for approval", `review.approve`/the real reviewer comment ("Activity plan is complete with clear objectives, approved.") — confirmed in both English and Chinese (heading "时间线" in Chinese; entry `type`/`description` values are raw backend strings shown as-is in both languages, matching `ProposalReview.tsx`'s existing convention). No console errors. The "No history yet" empty state was implemented for parity with `ProposalReview.tsx` but wasn't separately exercised — the backend's `timeline()` always emits at least a "created" entry for any persisted proposal, so that branch is unreachable in normal use on either the student or admin side.

*Live-verified via Playwright against `https://tb.example.com/` (real deployed frontend + backend) in both languages, on an approved proposal specifically (the case originally reported).*
