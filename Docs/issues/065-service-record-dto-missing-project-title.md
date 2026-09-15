# 065 — Project-sourced service records show a blank Project/Org everywhere (`ServiceRecordDto` has no `projectTitle`)

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found during the three-student end-to-end verification pass. Every
project-sourced (`sourceType: PROJECT`) service record rendered with an
**empty** Project/Org cell:

- Student Portal → Service Records: the row showed only the record number,
  no project name.
- Admin/Teacher Portal → Audit Center → record detail → Basic Info:
  "Project / Organization" read `—`.

Self-initiated and external records were fine, because those carry a
free-text `organizationName` on the record itself.

The Audit Center's *list* was fine too, which is what made this easy to
miss: it renders from `ReviewQueueItemDto`, which does carry the title.
Only the two surfaces fed by `ServiceRecordDto` were blank — including the
reviewer's detail panel, i.e. exactly the screen where knowing which
project a record belongs to matters most.

## Root cause

`ServiceRecordDto` exposes `projectId` with no accompanying
`projectTitle`. Both consuming components already do the right thing
(`rec.projectTitle || rec.organizationName`), and the frontend type
already declares `projectTitle?: string` — the field simply never arrived
on the wire, so the `||` fell through to an `organizationName` that is
null for project-sourced records.

This is a direct violation of APIContract §2.11 rule 1 ("Referenced
entities ship with their display name"), which names `projectId` →
`projectTitle` in its first table row. The DTO's own class javadoc even
opens with *"Denormalized names travel with their id (contract §2.11),
resolved once per read by the service"* — the rule was stated and then not
applied to the one field the rule's own example calls out.

## Fix

Backend-only.

`ServiceRecordDto` gains a `projectTitle` component next to `projectId`.
`ServiceRecordServiceImpl.lookupsFor` collects the distinct non-null
`projectId`s on the page and resolves them through the already-existing
`ProjectService.titlesOf(List<String>)` in **one** batched call, matching
how `reviewerNames` is already resolved and honouring §8's prohibition on
row-by-row follow-up requests. `toDto` reads the title out of that map.

No frontend change was needed.

## Test plan

- `mvn compile` and `mvn test` clean (37 tests).
- Live Playwright pass against the deployed server, using records created
  earlier in the same session:
  - Student Portal as `spencer`, Service Records: the project-sourced row
    now reads "E2E Flow Verification — Community Library Helpers" instead
    of a blank cell.
  - Audit Center as `teacher001`, record detail → Basic Info:
    "Project / Organization" now shows the same title instead of `—`.
  - Self-initiated rows still show their `organizationName` unchanged.

## Resolution

`backend/.../servicerecord/application/dto/ServiceRecordDto.java` — added
`projectTitle`.
`backend/.../servicerecord/application/ServiceRecordServiceImpl.java` —
`DtoLookups` carries a `projectTitles` map, populated by one batched
`projectService.titlesOf(...)` call per page and read in `toDto`.
