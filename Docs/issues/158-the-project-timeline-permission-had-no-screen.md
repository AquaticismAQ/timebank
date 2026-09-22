# 158 — The project timeline permission had no screen

**Status:** **Fixed and deployed 2026-09-22.** Aquaticism: `projects.audit.read` exists
but is not implemented — add a *View Timeline* action to the project card's more
menu, opening a compact table of datetime, action and username covering the whole
project lifecycle (created, edited, registered, cancelled, …). Collect it from
the audit log if that works. Also give the permission to teachers.

## 1. What was there

More than nothing, and worse than nothing. `GET /projects/{id}/timeline` existed,
gated on `projects.audit.read`, and answered:

```java
entries.add(new TimelineEntryDto("created", "Project created", project.getOwnerId(), project.getCreatedAt()));
for (ProjectRevision rev : revisionRepository.findByProjectIdOrderByRevisionNumberAsc(id)) {
    entries.add(new TimelineEntryDto("revised", "Fields updated", rev.getEditedBy(), rev.getCreatedAt()));
}
```

Two kinds of event, two hardcoded English sentences, and raw user ids. It knew
nothing about publishing, archiving, cancelling, or any registration — so the one
question a timeline exists to answer, *who did what to this project and when*, it
could not answer. No client called it, which is why that never showed.

## 2. Where the answer comes from

**The audit log**, as Aquaticism suggested. Every one of these events is already
recorded there by the append-only log that exists precisely so no action is
deniable. A second history would be a second thing to keep in step with the
first, and the moment the two disagreed neither would be trustworthy.

### The bridge to registrations, which is the part that isn't obvious

A registration's audit rows are recorded against the **enrollment**
(`resource_type='enrollment'`, `resource_id=<enrollment id>`), and **nothing on
those rows names the project**. So "the registration events of project X" is not
a question the audit log can answer alone.

The `enrollment` table is the only link. The timeline asks it through
`EnrollmentService.enrollmentIdsOf(projectId)` — through the owning module's
interface, not by reaching into its tables — and then asks the audit log for
those ids.

The consequence worth having: **no migration and no backfill.** The join works
against every audit row already written, so a project that ran months ago has its
full history the first time anyone opens the dialog.

### A synthetic first line, for projects the log never saw

Seeded projects were inserted directly rather than through `POST /projects`, so
no `project.create` row exists for them and their story would open in the middle.
When the log has no create event, one is synthesized from the project's own
`createdAt` and `ownerId`, so a timeline always has a beginning.

### What it deliberately does not show

- **Field diffs.** Three columns: when, what, who. A timeline is read to
  reconstruct a sequence, and the moment it carries before/after values it stops
  being scannable. The full detail of any one change is in System Admin → Audit
  Logs, which is the screen for that question.
- **Service records.** A project's students submitting and having records
  reviewed is arguably lifecycle too, but it is the *student's* record, reviewed
  in Audit Center, and thirty students would bury the six project events under
  sixty rows. Raised with Aquaticism rather than decided silently.

## 3. Where the code lives

`ProjectTimelineService` sits beside `ProjectService` rather than inside it, and
is called straight from the controller. `EnrollmentServiceImpl` already injects
`ProjectService`, so a project-module service that both injected
`EnrollmentService` and was injected by `ProjectServiceImpl` would close a bean
cycle and the application would not start. Same shape, and the same reason, as
`StudentReminderService` in issue 155.

## 4. The permission goes to teachers

`projects.audit.read` was `admin`/`sysadmin` only; `V36` grants it to `teacher`
and the Teacher role template, with the seed edit as the from-scratch half. A
teacher creates and publishes projects and confirms their registrants, so "what
has happened to this project" is a question about their own work.

**This widens nothing about which projects a teacher can see.** The timeline
resolves the project through the same scope-checked `getDetail` the project page
uses, so it can never show a project that page would not — and for staff that
rule is already school-wide by design: `ProjectServiceImpl.visibilitySpec`
returns no restriction for anyone holding `projects.update`, because a teacher
browses and copies any project and is stopped at *editing* one outside their
grades. The grade restriction belongs to students, who hold neither this
permission nor this screen. So the grant adds **history for projects a teacher
could already open**, and nothing else.

It is a read of one resource's history, not of the audit log screen: that stays
`auditLogs.read`, which a teacher does not hold. That distinction is what this
permission was always for.

## 5. Verification

**On the deployed server, against real history rather than a fixture.**

As `admin001`, a project that had actually lived a whole life:

```
2026-09-20 14:07  Project created          Teacher Wei Chen
2026-09-20 14:07  Published                Teacher Wei Chen
2026-09-20 14:07  Student registered       Alex L.(李*红)      CONFIRMED
2026-09-20 14:47  Cancelled and archived   Teacher Wei Chen    Automated end-to-end run cleanup (e2e --cleanup).
```

The third line is the one that matters: a registration, by the student who made
it, on a project whose enrollment audit row names no project. That is the row the
old stub could not have produced by any amount of fixing — and it is historical
data, written months before this existed, which is the point of reading the audit
log rather than starting a new history.

As `teacher001`, after `V36`: `projects.audit.read` present, **View Timeline** in
the card menu, and the timeline of a project owned by someone else returning its
four entries — consistent with the project page, which already shows it.

**A wrong turn worth recording.** The first scope test looked for a project a
teacher could not read, and could not find one — twelve probed ids all returned
200, including a draft owned by `admin001`. That is not a bug: `visibilitySpec`
deliberately gives any `projects.update` holder the whole school. The claim in
§4's first draft — "a teacher cannot read the history of a project outside their
grades" — was wrong, and is corrected above rather than quietly dropped. The
test that could have failed was the one that found this out.

**Cosmetic defect found and fixed in the same pass.** `detail` first rendered as
`"CONFIRMED"` with the JSON quotes showing, because `afterSummary` is stored as
compact JSON and a bare status is a quoted scalar. Stripped, and only for a
single quoted scalar — trimming an object or array summary would corrupt it.

## 6. What it must not do

- **Must not invent a second history.** §2 — the audit log is the record.
- **Must not miss registrations.** §2 — they are the events the old stub could
  not see, and the reason the enrollment bridge exists.
- **Must not show more than the project page shows.** It resolves the project
  through the same scope-checked `getDetail`, so its visibility can never
  diverge from the page it is opened from — whatever that rule becomes.
- **Must not write server-side English.** Action codes travel raw; the client
  supplies the words, and an unknown code degrades to a humanized form rather
  than an empty cell.
