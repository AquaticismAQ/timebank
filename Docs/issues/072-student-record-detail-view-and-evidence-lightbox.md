# 072 — Students can't view a submitted record's details, and evidence has no in-app viewer

Status: **Fixed and live-verified (2026-09-15)**

## Request

Reported directly by the user:

> for student's service record list, only the draft record have a edit
> button, other's should have a extra 'View' button to show the detail
> record info, create a new form for it, the evidence should have review
> feature like the audit's view. also for both this form and audit's
> evidence view, click the reviewing content(pictures, pdfs) will open a
> large dialog to view single one with download and close button

Three things:

1. A **View** action on every non-draft record in the student's list,
   opening a new read-only detail form (drafts keep Edit).
2. That form's evidence uses the same thumbnail preview the Audit Center
   already has, instead of the student list's plain text links.
3. In **both** places, clicking a preview opens a large single-file viewer
   dialog with **Download** and **Close**.

## Current behaviour

- **Desktop table**: no way to see a record's details at all. The action
  column only offers status-specific actions (Resubmit / Edit+Delete+Submit
  / Revert / Appeal). A submitted or approved record is a row of summary
  columns and nothing more — a student cannot re-read what they wrote, or
  re-check which evidence they attached.
- **Mobile cards**: an inline expander shows the fields, but its evidence
  is `EvidenceLink` — a plain text link that fetches a URL on click and
  opens a new tab. No thumbnail, no in-app view.
- **Audit Center**: already uses `EvidencePreview` (thumbnail tiles,
  issue 060), but each tile is an `<a target="_blank">` — clicking leaves
  the app for a browser tab, losing the reviewer's place in the queue.

## Plan

**`EvidenceViewerDialog`** (new, shared). Large modal for one file:
images as `<img>`, PDFs in an `<iframe>`, anything else a clear "can't
preview, download it" state. (The iframe source turned out to need a
`blob:` URL rather than the content URL — see "Two things found" below.) Two actions, Download and
Close. Download is a same-origin `<a download={name}>` against the
existing `/files/{id}/content` URL — no backend change: the endpoint
serves `inline`, and the `download` attribute is what flips that to a save
for a same-origin URL.

**`EvidencePreview`** tiles become buttons that open that dialog instead
of `<a target="_blank">`. Both consumers get the behaviour at once, which
is what the request asks for. The already-fetched `FileMetadata` is handed
to the dialog, so opening it costs no extra round trip and no download URL
is cached beyond component state (I3.3).

**`ServiceRecordDetail`** (new, student-side, read-only) showing
record number and status, source, project/organization, service date,
local start/end times and duration, location, category, supervisor,
travel-time disclosure, description, the review outcome (reviewer, review
notes, return/reject reason, credited coins), a timeline, and the evidence
preview grid.

It fetches via `api.serviceRecords.get(id)` rather than rendering the list
row: `list()`'s mapping always returns `evidenceFiles: []` (no
denormalised name/size without a per-file `GET /files/{id}` — see
`fromServiceRecordDto`'s own note), so a detail view built off the row
would show no evidence at all. This is the same reason `openEdit` already
re-fetches.

**View button** added to the action group in both layouts for every
non-draft status.

## Decisions

- **The mobile inline expander stays.** It's a quick peek that predates
  this and nobody asked for it to go; View is added alongside it. Worth
  revisiting if the duplication proves annoying in use, but silently
  deleting a working affordance wasn't part of the request.
- **Drafts get no View button**, per the request — Edit already opens the
  full wizard with every field, so a read-only view would be strictly
  less useful.

## Two things found while building this

**PDFs wouldn't frame at all.** The first PDF attempt rendered
"<host> refused to connect" inside the dialog: Spring Security sends
`X-Frame-Options: DENY` on every API response, which blocks framing the
content URL *even same-origin*. The header is invisible until you actually
try to frame something, so this was only ever going to show up by testing
a real PDF — which is why one was generated and uploaded rather than
reasoning that "PDF works because the endpoint says inline".

Fixed client-side: the viewer fetches the PDF and frames a `blob:` URL,
which carries no such header. The alternative — relaxing `X-Frame-Options`
to `SAMEORIGIN` on the file route — was rejected: it weakens a clickjacking
protection on an API serving minors' data to save a `fetch`. The object URL
is revoked on unmount.

**The record's `reviewedAt` is always empty**, so the first version of the
timeline silently omitted the review step on an approved record.
`ServiceRecordDto` deliberately omits it (a record has no single "last
reviewed" moment). Switched to the real `GET /service-records/{id}/timeline`
— the same append-only source the Audit Center's Review History tab uses,
and readable by the record's own student — so the student now sees the full
history including the reviewer's comments, not just created/submitted.

Separately, testing this surfaced **issue 073** (Save Draft leaving the
wizard open with its state torn down).

## Test plan

- `tsc --noEmit`, `pnpm build`, en/zh key parity — all clean.
- Live Playwright pass against the deployed server:
  - Action columns: `View` present on every non-draft row (Returned,
    Approved, Submitted) and absent on Draft, which keeps
    Edit/Delete/Submit.
  - Detail form on an approved record: record number, status badge, the
    reviewer's comment banner, all fields with **local** start/end times,
    credited coins, description, evidence thumbnail, and the full
    timeline — `Created → Returned for changes → Fields updated →
    Submitted → Approved by reviewer`, each with its comment and local
    timestamp.
  - Image viewer: opens at full size (1268×1032 actually loaded),
    **Download** fires a real browser download (`1.jpg`, no failure),
    **Close** returns to the detail form rather than dismissing
    everything, and **Esc** closes the viewer (2 overlays → 1).
  - PDF viewer: renders the real PDF in the browser's own PDF viewer from
    a `blob:` URL.
  - Audit Center as `teacher001`: same viewer, same Download, Esc returns
    to the record detail with the queue still behind it.

## Resolution

`frontend/src/app/components/shared/EvidenceViewerDialog.tsx` (new) — the
single-file viewer.
`frontend/src/app/components/shared/EvidencePreview.tsx` — tiles are now
buttons opening that viewer instead of `<a target="_blank">`; both the
Audit Center and the new student form inherit it.
`frontend/src/app/components/student/ServiceRecordDetail.tsx` (new) — the
read-only detail form, with the timeline sourced from `/timeline`.
`frontend/src/app/components/student/ServiceRecords.tsx` — `View` action on
non-draft rows in both the desktop table and the mobile cards.
`frontend/src/i18n/{en,zh}.json` — `common.view`/`download`/
`evidenceNoPreview`, the `student.records.timelineType.*` set, and the
detail form's field labels. `common.close` was an unused duplicate of
`common.collapse` ("Collapse") and was repurposed to actually mean "Close".
