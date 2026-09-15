# 075 — A proposal's "Estimated Duration" and "Suggested Certified Hours" are secretly the same field

Status: **Fixed and live-verified (2026-09-15)**

Filed as its own doc rather than merged: recent fixes were merged into
existing issues because a closely-related one existed (evidence preview →
060, Save Draft → 073). This is the proposal module, where no related issue
exists, so merging it anywhere would bury it.

## Symptom

Found while verifying the proposal → review → approve flow end to end.

The New Proposal wizard asks for two different numbers:

- step 2, **Estimated Duration** (hours) — how long the activity runs;
- step 5, **Suggested Certified Hours** — how many volunteer hours should
  be credited.

They are not the same thing, and a student can reasonably enter e.g. a
2-hour session that should credit 3 hours. But whatever is typed into
**Estimated Duration is silently discarded**: entering `20` for duration
and `60` for certified hours produced a proposal that reads back
`Estimated Duration = 60 hours`. The typed 20 never reaches the server and
never comes back.

## Root cause

Both form fields are bound to the *one* backend field,
`proposedRecognizedMinutes`. The backend has no duration concept at all —
`CreateProposalRequest` carries `plannedStartAt`/`plannedEndAt`,
`capacity` and `proposedRecognizedMinutes`, and nothing else.

On write, the certified-hours value wins and duration is only a fallback:

```ts
const proposedRecognizedMinutes = data.suggestedMinutes || data.estimatedDurationMinutes || 60;
...plannedStartEnd(data.plannedDate, proposedRecognizedMinutes),
```

On read, both fields are populated from that same value:

```ts
estimatedDurationMinutes: dto.proposedRecognizedMinutes ?? dto.estimatedDurationMinutes,
suggestedMinutes:         dto.proposedRecognizedMinutes ?? dto.suggestedMinutes,
```

So the two inputs can never hold different values, and the duration input
is a phantom — it cannot round-trip.

**This is not only cosmetic.** `plannedStartEnd` derives `plannedEndAt`
from that same number, so the proposal's planned window is sized by the
*credited hours* rather than by how long the event actually runs. On
approval `ProposalApprovalTransaction` copies `plannedStartAt`/
`plannedEndAt` straight into the generated project's `startAt`/`endAt` —
so a wrong window propagates into the official project, which is what the
project lifecycle (`REGISTRATION_OPEN → CLOSED → IN_PROGRESS → COMPLETED`)
and the service-record wizard's time autofill both run on.

Observed live: a proposal whose certified hours were 6 generated a project
with a 6-hour activity window, regardless of the duration entered.

## Fix

Frontend only — the data needed already exists, no backend change and no
new field:

- **Write**: the planned window comes from `estimatedDurationMinutes`
  (what it means), and `proposedRecognizedMinutes` comes from
  `suggestedMinutes` (what *that* means). Each falls back to the other when
  absent, so a proposal that only carries one of them still behaves as
  before.
- **Read**: `estimatedDurationMinutes` is derived from the stored window
  (`plannedEndAt − plannedStartAt`) and `suggestedMinutes` from
  `proposedRecognizedMinutes`. Both now round-trip independently.

## Test plan

- `tsc --noEmit`, `pnpm build`.
- Live round-trip: enter a duration and a *different* certified-hours
  figure, submit, and confirm the stored proposal keeps both — the planned
  window matching the duration and `proposedRecognizedMinutes` matching the
  certified hours — and that re-opening the proposal shows the same two
  distinct numbers rather than one repeated.

## Resolution

`frontend/src/app/api/client.ts` — `eventProposals.create`/`update` size the
planned window from the duration, and `fromProposalDto` derives the duration
back from the window.
