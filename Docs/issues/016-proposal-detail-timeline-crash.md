# 016 — Student proposal detail card crashes on the "Timeline" section

Status: **Fixed, not live-verified this session**

## Symptom

`components/student/EventProposals.tsx`'s proposal detail card renders a
"Timeline" section that calls `p.timeline.map(...)` unconditionally. The
real backend `ProposalDto` has no `timeline` field at all — `fromProposalDto`
(`client.ts`) never sets it — so `p.timeline` is `undefined` at runtime for
every proposal despite the frontend `EventProposal` type declaring
`timeline: ProposalTimeline[]` as required. Opening any proposal's detail
card throws `Cannot read properties of undefined (reading 'map')`.

Found while wiring `04-DataViewOptimization.md`'s Phase 2 (adding
`api.eventProposals.timeline(id)` for the admin Proposal Review workbench)
— touching the same `timeline` field surfaced that the student-facing
consumer of the identically-named-but-unrelated field was already broken.
Not found via this session's live browser testing.

## Root cause

Two unrelated concepts share the name "timeline":

1. `EventProposal.timeline: ProposalTimeline[]` (frontend-only type,
   `{ action, actor, time, note }` shape) — never populated by any backend
   response, dead since it was declared.
2. The real, working `GET /event-proposals/{id}/timeline` endpoint
   (`{ type, description, actorId, occurredAt }` shape, merges revisions +
   review decisions server-side) — fully implemented backend, but had no
   `client.ts` method at all until this session.

`EventProposals.tsx` was written against (1), which was never real.

## Fix

Minimal, non-crashing fix only — not a full rewrite onto the real endpoint:

- `types.ts`: `timeline: ProposalTimeline[]` → `timeline?: ProposalTimeline[]`
  (stops the type from lying that the field is always present).
- `EventProposals.tsx`: both `p.timeline` references guarded with `?? []`.

The Timeline card now renders with a permanently-empty list instead of
crashing — it does **not** yet show real data. Wiring it to the real
`api.eventProposals.timeline(id)` (added this session for the admin
workbench, §5.2 of `04-DataViewOptimization.md`) is a separate, unscoped
follow-up for the student portal, deliberately not done here to keep this
fix minimal and in scope.

## Test plan

- `pnpm build` clean.
- No live click-through this session (see footer).

## Resolution

- `frontend/src/app/api/types.ts` and
  `frontend/src/app/components/student/EventProposals.tsx` updated.
- Not yet deployed/verified against `https://tb.example.com/`.

---
*Not live-verified this session — no browser tool used. Root cause and fix
are grounded in direct code reading (`fromProposalDto`'s output vs.
`EventProposal`'s type, confirmed no `timeline` field exists on the real
backend `ProposalDto`), not a reproduced click-through. A later session
should open a student proposal's detail card on the live test server to
confirm the crash is gone, and consider whether to actually wire real
timeline data in given the client method now exists.*
