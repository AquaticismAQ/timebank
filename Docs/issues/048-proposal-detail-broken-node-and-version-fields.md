# 048 — Event Proposal detail's "Approval Status" card shows a blank "Current Node" pill and literal "Version {{count}}" / "vNaN"

Status: **Fixed and live-verified (2026-08-23)**

## Symptom

On the Student Portal's Propose Volunteer Activity → proposal detail view, the "Approval Status" card showed:
- "Current Node" with an empty pill (no text).
- The literal untranslated string "Version {{count}}" as the row label, and "vNaN" as its value.

The same broken "Current Node" value also appeared (blank, not garbled) in the proposal list's desktop table column and mobile card row, and the header's revision badge (`v{{count}}` pattern) silently never rendered since its guard condition was always false.

## Root cause

`EventProposal.currentApprovalNode` and `EventProposal.revisionCount` (`frontend/src/app/api/types.ts`) were declared as required fields but never correspond to anything the real backend `ProposalDto` returns — a pre-existing, already-documented gap (see the comment at `frontend/src/app/api/client.ts:339-341`, from earlier I3 work) that was known to leave these fields `undefined` at runtime despite the TS type claiming otherwise. `EventProposals.tsx` read `p.currentApprovalNode` directly (renders blank when `undefined`) and computed `` `v${p.revisionCount + 1}` `` (renders `vNaN` since `undefined + 1` is `NaN`).

Separately, the `student.proposals.version` i18n key's text was `"Version {{count}}"`, but the call site (`t('student.proposals.version')`) never passed a `{ count }` interpolation object — i18next leaves an unresolved `{{count}}` placeholder untouched in the rendered string, compounding the same row's display.

The backend does have a real, working revision-tracking field: `EventProposal.currentRevision` (`backend/src/main/java/cn/timebank/proposal/domain/EventProposal.java:139`), an `int` starting at `1` and incremented (`ProposalServiceImpl.java:390`, via `writeRevision`) on every draft/changes-requested save, exposed as `ProposalDto.currentRevision` and in `APIContract.md` §3.2's field list — plus a full revision-history table (`EventProposalRevision`, `GET /event-proposals/{id}/revisions`). There is, however, no backend concept matching "current approval node" at all — approval progress is a flat `status` enum plus a single `assignedReviewerId`, not a multi-step node/workflow chain. `currentApprovalNode` was a leftover mock/template field that was never backed by real data, not a rename or typo.

## Proposed fix

Frontend only, no backend change needed:

- Rename `EventProposal.revisionCount` → `currentRevision` in `types.ts` to match the real `ProposalDto.currentRevision` field exactly, so the wire-to-model mapping in `fromProposalDto` needs no extra mapping code (the `...dto` spread already carries the real value through under the matching name).
- Remove `EventProposal.currentApprovalNode` entirely — there is no backend data to back it.
- `EventProposals.tsx`: remove the "Current Node" row from the detail status card, the desktop table column, and the mobile card row (three call sites, no backing data for any of them). Change the header revision badge and the detail card's "Version" row to use `currentRevision` directly (already 1-based, so no `+1`); the header badge's guard becomes `currentRevision > 1` (only shown once the proposal has actually been revised past its initial save).
- Fix the `student.proposals.version` i18n key text in `en.json`/`zh.json` to drop the unused `{{count}}` placeholder (the number is rendered in a separate sibling element, not interpolated into the label).
- Remove the now-fully-dead `statusCard` i18n block (a duplicate, entirely unreferenced copy of this same namespace) and the `currentNode`/`nodeLabel` keys that become unreferenced once the three call sites above are removed.

Not in scope: `myRole` (also documented as unmapped/always-`undefined` in the same `client.ts` comment) renders as empty text, not garbled text, in the same views — a separate, lower-severity gap left for a future pass since fixing it requires backend work to attribute a per-student role (initiator vs. co-organizer) that doesn't exist yet.

## Test plan

1. Log in as `student001`, open Propose Volunteer Activity.
2. Open a proposal that has been saved/resubmitted at least once (`currentRevision > 1`) — confirm the header badge shows `v<n>` (a real number, not `vNaN`), and the detail card's "Version" row shows the same real number with a plain "Version" label (no literal `{{count}}`).
3. Confirm the "Current Node" row is gone from the detail card, the desktop table has no "Current Node" column, and the mobile card has no "Node:" line.
4. Repeat in Chinese — confirm "版本" label and no literal placeholder text.
5. `tsc --noEmit` clean (already verified locally).

## Resolution

Deployed via `frontend/scripts/deploy.sh` to the shared test server, then live-verified with Playwright as `student001`: opened the "Community Elderly Digital Skills Support Workshop" proposal (status `changesRequested`) in both English and Chinese. The "Approval Status" card now shows only "Version" / "版本" with a real value (`v1`, not `vNaN`), no `{{count}}` literal, and no "Current Node"/"当前节点" row; the desktop table has no "Current Node" column and the header has no stray blank pill. No console errors during the pass.

*Live-verified via Playwright against `https://tb.example.com/` (real deployed frontend + backend) in both languages. Did not separately re-verify the mobile-card layout (no "Node:" line) in a mobile viewport this session — the code change removing that line is identical in shape to the desktop-table fix already verified, but the visual layout itself wasn't screenshotted at a mobile width.*
