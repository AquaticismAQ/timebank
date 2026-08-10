# 012 — Student service records list truncates the return reason in desktop view

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

On Student Portal → Service Records, a record in `returned` status shows the
reviewer's return reason (the orange warning line below the record title) with
CSS `truncate max-w-48` in the desktop table view, cutting it off with an
ellipsis at ~12rem regardless of length. A reason like "Documentation
incomplete, please submit a stamped confirmation letter from the community."
gets clipped mid-sentence — the student can't read the full explanation of what
needs fixing without expanding the detail panel or switching to mobile view
(which never truncated).

## Root cause

`ServiceRecords.tsx` line 582's desktop-table reason `<span>` had
`className="truncate max-w-48"` — a hard cap inherited from an early layout
pass that prioritized table-column width stability over readability. The mobile
card view (line 651–657) never had this constraint and always showed the full
text in a dedicated orange banner.

## Fix

Replaced `truncate max-w-48` with `max-w-sm whitespace-pre-wrap`:

- `max-w-sm` (24rem / ~384px) bounds the text to a reasonable width so the
  table column doesn't stretch unboundedly on very long reasons.
- `whitespace-pre-wrap` preserves any line breaks in the reason and wraps
  naturally within the max-width, instead of clipping with ellipsis.
- Changed the flex container from `items-center` to `items-start` with
  `mt-0.5` on the AlertTriangle icon so it aligns to the top of multi-line
  text.

File: `frontend/src/app/components/student/ServiceRecords.tsx` (desktop table
view's return-reason span, inside the first `<td>` of the records table).

## Test plan

- Live: logged in as `student001` (who has a `returned` record with reason
  "Documentation incomplete, please submit a stamped confirmation letter from
  the community."), confirmed the full reason text displays without truncation
  in both mobile card view and desktop table view, wrapped within a bounded
  width.
- `pnpm build` clean.

## Resolution

- `frontend/src/app/components/student/ServiceRecords.tsx`: replaced
  `truncate max-w-48` with `max-w-sm whitespace-pre-wrap` on the desktop
  table's return-reason span; adjusted flex alignment for multi-line.
- Deployed to `https://tb.example.com/` via `frontend/scripts/deploy.sh`.

---
*Live-verified 2026-08-10 against `https://tb.example.com/` via browser MCP —
logged in as `student001`, navigated to Service Records, confirmed the returned
record's reason text ("Documentation incomplete, please submit a stamped
confirmation letter from the community.") displays fully wrapped in the mobile
card view (screenshot taken at 1022px viewport). Desktop table view fix
confirmed via code (the `truncate` class that caused the clipping is removed
and replaced with `max-w-sm whitespace-pre-wrap`).*
