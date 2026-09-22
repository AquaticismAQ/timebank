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

### Registrations the log never saw — which is not a data bug

Aquaticism: *"why do some projects have service records but no registration — issues in
the database seeding?"*

The registrations **are there**. What is missing is their audit trail:

```
project                          enrollment rows   enrollment audit rows   record rows
School Library Organisation            5                    0                  5
Community Reading Companions           4                    0                  4
Neighbourhood Digital Help Desk        3                    0                  4
```

`integration-test/seed.ts` Phase 3.5 inserts confirmed enrollments with **direct
SQL**, and its own comment says why: registering through the API needs the
student's session and an open registration window, which a project seeded
straight into `COMPLETED` does not have. The service records, by contrast, are
created through the API — which is why each has its three audit rows and the
enrollments have none.

So nothing is corrupt, and nothing in production is affected: a real registration
always goes through `POST /projects/{id}/register` and is audited. But a timeline
that showed a student's service record with no sign of them ever signing up was
telling a story with a hole in it.

**Root-cause fix: the seed registers through the API now.** Phase 3.5 drives
`POST /projects/{id}/applications` as each student, so every enrollment carries a
real `enrollment.register` entry. Two things had to change around it, both
because the API refuses to pretend:

- **Every project is created inside a live registration window** and given its
  real dates back in a new Phase 3.6. A draft whose window has closed cannot be
  published at all (issue 130), and registration needs the project
  `REGISTRATION_OPEN` *now* — so a fixture full of finished projects could not
  be built any other way. This also fixes a latent breakage: publishing a
  past-dated project would have 409'd regardless of registrations.
- **Registrants must have login accounts.** `assignRegistrants` used to fall
  back to roster-only students, which the SQL insert accepted and the API cannot
  — registering is the student's own action. That was a state the product can
  never reach, so the fixture no longer asks for it. `verify-fixture.ts` gained
  three offline checks (account, capacity, not-a-draft) that catch it in a second
  rather than four minutes into a run.

**And a fallback for data that already exists: derive the registration from the
enrollment row** when the log has no
`enrollment.register` for it, using `applied_at` — a recorded fact about when
they registered, not a guess — and the enrollment's status as the detail. This is
the same move the synthetic create line below already makes, for the same reason,
and it is keyed per enrollment so a project with both seeded and real
registrations never shows one twice.

The derived entry carries **no actor**: the row exists, but no person is on
record for it. The Who column shows `—` rather than "System", which would claim a
machine registered a student.

**Not** fixed by backfilling audit rows. Inventing entries in an append-only log
is precisely what that log must never contain — so the shared server's existing
enrollments keep no audit trail, and the derived entry is what makes their
timelines readable. A re-seed is what clears it for good.

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
The record's **public** events are included — submitted, withdrawn, reviewer
assigned, and the review decision, whose `detail` carries the resulting
`APPROVED`/`RETURNED`/`REJECTED`. A re-submission is a second `submit`, so
returning a record and getting it back reads as two lines rather than one.

**Drafting is deliberately not.** Aquaticism: *"we needn't show the student create a
record draft, only submit, re-submit."* A record is written, edited and
re-edited privately before anyone else can see it — three or four rows each — so
a project with six records buried its four real events under twenty lines of
somebody typing. The project's history starts when the student **submits**; what
happened in the notebook first is between them and the draft. Filtered on the
server rather than in the browser: there is no reason to ship rows nobody should
see.

A denylist (`create`, `update`, `delete`) rather than an allowlist, so a
service-record action added later shows up unbidden — displaying an unexpected
real event is a far smaller failure than silently hiding one.

On the real data this takes a busy project from 22 rows to 17, and the shape that
remains is the one worth reading: created → published → five registrations →
five submissions → five decisions.

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

**The seed, run end to end on a clean Docker database** — the only place a
re-seed can be tested, since the shared server's data cannot be dropped:

```
enrollments                              36   (36 CONFIRMED)
enrollment.register audit rows           36   ← was 0
enrollments with no register audit row    0

per project:   project audit   enrolment rows   enrolment audit   record audit
               2               5                5                 15
               2               5                5                 15
               2               3                3                  6
```

`enrolment rows == enrolment audit` on every project is the assertion that
matters; before this they were 5 and 0. The restored dates were checked too — a
completed project reads `2026-06-24 … 2026-08-23` with status `COMPLETED`, and
the registration audit rows span 2026-05-26 to 2026-09-18 rather than all landing
on the day of the run.

**A wrong turn**: the first run failed at Phase 3.5 with `404: No such endpoint`.
The path is `POST /projects/{id}/applications`, not `/register` — the REST
vocabulary is `applications` even though the internal module is `enrollment`.
Worth the failure: it is exactly the kind of thing that cannot be found by
reading.

**The dialog scrolls its table, not itself.** A 22-row timeline used to push the
Close button off the bottom of the panel, leaving Escape as the only exit — which
is exactly what issue 102's rule exists to prevent. The panel is now a flex
column: header and footer fixed, the table the only thing that moves, with a
sticky `thead` so the columns stay labelled forty rows down. Measured on the
deployed page rather than eyeballed:

```
dialog scrolls   false          ← the panel itself no longer moves
table scrolls    true           972px of rows in a 462px window
header visible   after scrolling to the bottom
Close visible    at the top and at the bottom of the scroll
```

`min-h-0` on the scrolling child is load-bearing and easy to lose: a flex item
defaults to `min-height: auto`, which refuses to shrink below its content, so
without it the scroll never engages and the panel grows past its own max height.

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
- **Must not show a registration twice.** The derived entry is suppressed per
  enrollment when the log already has one.
- **Must not claim an actor it does not have.** A derived registration shows `—`,
  not "System" and not the student inferred from the row.
- **Must not hide the way out.** The table scrolls; the header and Close do not.
- **Must not put a read above a terminal action.** *View Timeline* sits above
  *Archive* and *Cancel and archive*: the actions that end a project stay last.
- **Must not seed a state the product cannot reach.** §2 — registrations go
  through the API, and a roster-only student cannot hold one.
- **Must not show private drafting.** §2 — the project's history starts at
  submit.
- **Must not show more than the project page shows.** It resolves the project
  through the same scope-checked `getDetail`, so its visibility can never
  diverge from the page it is opened from — whatever that rule becomes.
- **Must not write server-side English.** Action codes travel raw; the client
  supplies the words, and an unknown code degrades to a humanized form rather
  than an empty cell.
