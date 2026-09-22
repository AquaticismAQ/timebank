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

### The bridge to registrations and records, which is the part that isn't obvious

A registration's audit rows are recorded against the **enrollment**, and a
service record's against the **record** — and **nothing on either names the
project**. So "the registration and record events of project X" is not a question
the audit log can answer alone.

The two owning tables are the only link. The timeline asks them through
`EnrollmentService.enrollmentSubjectsOf` and
`ServiceRecordService.recordSubjectsOfProject` — through each owning module's
interface, not by reaching into its tables — and then asks the audit log for
those ids.

**Both bridges return the student's name with the id**, which is not decoration.
The actor on a review decision is the *reviewer*, so "Review decision · APPROVED
— Teacher Wei Chen" never says whose record it was. The same gap existed for
`enrollment.confirm`. A fourth column, **Subject**, carries whose registration or
record each event is about, and reads `—` for the project's own events, which are
about the project.

The consequence worth having: **no migration and no backfill.** The join works
against every audit row already written, so a project that ran months ago has its
full history the first time anyone opens the dialog.

### A synthetic first line, for projects the log never saw

Seeded projects were inserted directly rather than through `POST /projects`, so
no `project.create` row exists for them and their story would open in the middle.
When the log has no create event, one is synthesized from the project's own
`createdAt` and `ownerId`, so a timeline always has a beginning.

### Service records are in, at Aquaticism's instruction

The first pass left them out on the grounds that a record belongs to the student
and thirty students would bury six project events under sixty rows. Aquaticism asked
for them, and he is right: a project's point is the service done on it, so a
history that stops at "registered" stops one step before the part that matters.
All seven record actions are included — drafted, edited, submitted, withdrawn,
deleted, reviewer assigned, and the review decision, whose `detail` carries the
resulting `APPROVED`/`RETURNED`/`REJECTED`.

On the real data this lands at roughly twenty to twenty-five rows for a busy
project, which is still one scannable table.

### What it still deliberately does not show

- **Field diffs.** A timeline is read to reconstruct a sequence, and the moment
  it carries before/after values it stops being scannable. The full detail of any
  one change is in System Admin → Audit Logs, which is the screen for that
  question.

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

As `admin001`, a project that had actually lived a whole life — 22 rows, read
through the real dialog:

```
When              Action                    Subject          Who
2026-09-21 00:08  Project created           —                Teacher Wei Chen
2026-09-21 00:08  Published                 —                Teacher Wei Chen
2026-09-21 00:08  Student registered        Alex L.(李*红)   Alex L.(李*红)     CONFIRMED
2026-09-21 00:08  Service record drafted    Alex L.(李*红)   Alex L.(李*红)
2026-09-21 00:08  Service record edited     Alex L.(李*红)   Alex L.(李*红)
2026-09-21 00:08  Service record submitted  Alex L.(李*红)   Alex L.(李*红)     SUBMITTED
2026-09-21 00:08  Review decision           Alex L.(李*红)   Teacher Wei Chen   APPROVED
…                 (two more record cycles, ending RETURNED and REJECTED)
```

Three things that could each have failed separately. The **registration** and the
**record rows** are reached only through their bridges, since neither audit row
names the project. The **Review decision** rows show the split the Subject column
exists for — subject `Alex L.`, actor `Teacher Wei Chen`. And all of it is
historical data written on 2026-09-21, months before this code existed, which is
the whole point of reading the audit log rather than starting a new history.

As `teacher001`, after `V36`: `projects.audit.read` present, **View Timeline** in
the card menu, and the timeline of a project owned by someone else returning its
entries — consistent with the project page, which already shows it.

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
- **Must not miss registrations or service records.** §2 — they are the events
  the old stub could not see, and the reason the two bridges exist.
- **Must not leave a review decision anonymous in its subject.** The actor is the
  reviewer; the Subject column says whose record it was.
- **Must not show more than the project page shows.** It resolves the project
  through the same scope-checked `getDetail`, so its visibility can never
  diverge from the page it is opened from — whatever that rule becomes.
- **Must not write server-side English.** Action codes travel raw; the client
  supplies the words, and an unknown code degrades to a humanized form rather
  than an empty cell.
