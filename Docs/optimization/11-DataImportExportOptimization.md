# Data Import and Export Optimization — Implementation Plan

## 0. Scope and how to read this

`Design/*/10-RolesAndPermissions.md`, `11-DataImport.md` and `12-DataExport.md` were
written after import and export had already shipped. They describe a different system
from the one running: a seven-type export catalogue with per-type permissions, filters,
student-scoped rows and readable columns, against a built system with one blanket
`exports.create`, no scope, no filters, and database ids in every file.

This plan closes that gap. It is the build plan for those three designs, which are
**authoritative** — where this plan and a design disagree, the design wins and this
plan is wrong.

**Out of scope, deliberately:**

- **The historical-import re-enable utility.** Specified in
  `Docs/backlog/01-HistoricalImportReenableUtility.md`, not built, not scheduled.
  §3.6 below builds the window that utility reopens; it does not build the utility,
  and §5's R-6 says what that asymmetry costs.
- **The annual rollover itself** (`Design/*/13-AnnualRollover.md`). Its
  `AnnualRolloverService` work is in flight separately. This plan *depends* on its
  stage model for the grade derivation (§2.7) and on grade 12 existing, but builds
  none of it.
- **Any further `admin`/`sysadmin` separation.** `10-RolesAndPermissions.md` §6's
  closing note puts that out of scope; this plan reverses the V21 merge for
  `imports.*` and `exports.*` only.
- **The five `/reports/*` screen endpoints.** Two *export types* retire (§2.4); the
  on-screen reports they were built from stay, because `12-DataExport.md` §6's own
  reasoning for retiring the hours trend is that it "reads better on screen than in
  a CSV" — which presumes the screen keeps it.

---

## 1. What already exists (verified findings)

Read from the files, not assumed. Every row below was checked against the committed
tree on 2026-09-19.

| # | Finding | Consequence |
| :--- | :--- | :--- |
| F-1 | **`exports.create` is held by `admin` and `sysadmin` only — never by `teacher`.** `R__seed_permissions_and_roles.sql` grants `perm_exports_create` at lines 152 and 199; no `role_teacher` or `role_template_teacher` grant exists | Retiring it strands **two role rows, not a population of teachers**. It also means teacher export is an entirely *new* capability, not a narrowing of an existing one — which reshapes the risk (R-2) |
| F-2 | **The built catalogue is seven keys that mostly are not the design's seven.** `ExportServiceImpl.SUPPORTED_REPORT_TYPES` = `participation`, `completion`, `serviceHours`, `reviewEfficiency`, `topProjects`, `serviceRecords`, `students` | Only `serviceRecords` survives by key. `students` → `studentList` is a rename. `completion`/`topProjects` are *near-misses*: right subject, wrong shape (F-7). `participation`/`serviceHours` retire. `studentParticipation` and `reviewHistory` do not exist at all |
| F-3 | **No export applies any data scope whatsoever.** `StudentExportRepository.page` and `ServiceRecordExportRepository.page` are `select … from student` / `from service_record where id > :afterId` with no predicate beyond the keyset cursor; the five aggregate exports call `ReportService`, which is school-wide by design (`reports.read`-gated dashboards) | There is no scope code to *change* — there is scope code to **write**. §2.5 is new behaviour, not modified behaviour |
| F-4 | **`DataScopeSpecifications` cannot be reused here.** It returns a JPA `Specification<T>` built from Criteria `Path`s via `ScopeFields`; both export repositories are raw `NamedParameterJdbcTemplate` SQL | `12-DataExport.md` §7's checklist item "scope is resolved through `ScopeDescriptor`" is satisfiable, but only by resolving the *descriptor* and translating it to SQL parameters by hand. `DataScopeSpecifications.apply` itself is unusable on this path |
| F-5 | **The export request carries one field.** `CreateExportRequest(String reportType)`. `job.params_json VARCHAR(2000)` exists in `V10__dataexchange.sql` and is mapped on `Job`, and **nothing ever writes or reads it** | The filter model is a contract change on `POST /exports`, and it has a storage slot waiting for it |
| F-6 | **`ReportService` already takes an `academicYearId`; the export runner passes `null` to all five.** `ExportJobRunner.writeReportCsv`'s Javadoc says so explicitly, citing issue 093, and `APIContract.md` §4.13 states the limitation in prose: *"the export jobs in `POST /exports` carry no period of their own"* | The year filter is half-built already. The missing half is the request field and the plumbing, not the query support |
| F-7 | **No existing query produces a per-student or per-project row.** `completion`/`participation` are one row per grade; `serviceHours` one row per month; `reviewEfficiency` four columns per reviewer; `topProjects` a bounded top-N | Four of the design's seven types need genuinely new queries: Student Completion, Student Participation, Projects Report, Review History. Review Efficiency needs its query widened from 4 columns to 12 |
| F-8 | **Exports write database ids, centi-coins, and the literal string `null`.** `ExportJobRunner` header lines are `id,studentId,studentName,…` and `id,studentNumber,displayName,gradeId,classId,…`; `csvLine(…, String.valueOf(row.creditedUnits()), …)` renders a null `Integer` as `"null"` | This is `Docs/issues/107`, whose fix **was reverted and is not in the tree** (`git log` on `ExportJobRunner.java` ends at `c252325`, and the working tree is clean). Issue 107 is open and §3.2 closes it |
| F-9 | **Nothing in `cn.timebank.dataexchange` writes an audit entry.** `AuditService` is not imported anywhere in the module | Export — the single most concentrated data-egress action in the system — leaves no audit trail. `11-DataImport.md` §8's *"Every commit writes an audit entry"* holds only indirectly, via the per-row `AccountServiceImpl`/`StudentServiceImpl` entries; there is no job-level row naming actor, type and row count |
| F-10 | **The download token is time-boxed but not single-use.** `ExportDownloadTokenService` is a stateless HMAC over `jobId:expiry` with `TTL_SECONDS = 300`; `validate` checks the signature and the clock and nothing else | `12-DataExport.md` §3 and §8 both say "single-use". Replay inside the 300 s window is currently free |
| F-11 | **Export output files are never deleted.** `ExportJobRunner` stores `export_<jobId>.csv` through `FileStorage` and no code path removes it; nothing in `lifecycle` references `result_ref` | `12-DataExport.md` §8's *"No long-lived server-side copies"* is not true today |
| F-12 | **The 15-day historical-import window does not exist in any form** — no schema, no code, no copy. `grep` for the window finds nothing in `backend/src` or `frontend/src` | The first historical import is currently unbounded and unannounced, which is exactly the situation `11-DataImport.md` §6.2 says must not be reached |
| F-13 | **The historical commit is not one transaction.** `ImportJobRunner.commitHistorical` loops rows, calls `accountService.adjustForStudent` per row, and checkpoints every 1,000 rows so a crashed job resumes from `checkpointOffset` | `11-DataImport.md` §8's *"A commit either fully succeeds or fully rolls back … 'half imported' does not exist"* describes something else. Flagged in §7; **not** fixed by this plan |
| F-14 | **Import and Export are one tab.** `SystemAdmin.tsx` has `activeTab === 'import'` rendering two import cards, `ActivationSlips`, and one export card holding a 7-button grid. No filters anywhere | `12-DataExport.md` §3 and `11-DataImport.md` §5.2 both require two tabs and expandable per-type cards |
| F-15 | **`/admin/system` is in the admin nav unconditionally, and its default tab needs `users.read`.** `AdminLayout.tsx` line 32 adds it for every admin-portal user; `SystemAdmin.tsx`'s own comment at line 981 says the tab "is otherwise visible to them [teachers]" | A teacher opening System Admin today lands on a Users tab that 403s. Granting teachers export gives them a *reason* to open that page, which turns a latent wart into a routine one |
| F-16 | **`/grades`, `/classes` and `/academic-years` are `isAuthenticated()` and unscoped** (`AcademicStructureController`), but **`/me` already returns `scopeGradeIds`** (`MeResponse`, `AuthController.withScopeGradeIds`) | `12-DataExport.md` §4.3's "a teacher's Grade filter lists only their bound grades" needs no new endpoint — the client can intersect the dictionary with `scopeGradeIds`. The server must still enforce it (F-17) |
| F-17 | **`usePermissions().hasPermission` fails *open*.** `App.tsx` line 71: `if (!user?.permissions) return true` | Card hiding is a courtesy, never a control. Every hidden card needs a server-side refusal behind it |
| F-18 | **`exports.create` is hardcoded in the frontend and in a prior optimization doc.** `RoleManagement.tsx` line 33 lists it among the ⚠️ sensitive permissions; `07-RoleFinetune.md` §6 and its §365 catalogue row name it | Three non-obvious edit sites beyond the migration |
| F-19 | **`service_record` has no academic-year column**, and `student` has `enrollment_academic_year_id` + `graduation_year INT` | A year filter must be a `service_date BETWEEN academic_year.start_date AND end_date` test, matching how `ReportService` already scopes. The two columns §5's derivation needs are both present |
| F-20 | **`review_decision.decision` is `APPROVE` / `RETURN_FOR_CHANGES` / `REJECT` / `ESCALATE`**, `reason_code` is `NOT NULL`, `comment` nullable, and there is **no reviewer-role column** | §6.7's three-value `Decision` mapping is incomplete (Q-3), and `Reviewer Role` / `Bound Grades` must be joined from `user`/`role` and `teacher_scope_assignment` |
| F-21 | **`grade_12` now seeds, and the rule version already carries both bands.** `R__seed_base_data.sql` seeds `grade_6`…`grade_12` (uncommitted, part of the in-flight rollover work); `V25` split `rule_version` into `middle_*`/`high_annual_required_minutes` and `*_stage_required_minutes` | §6.3's `Annual Required Hours` has a real source, per rule version rather than a constant — which raises Q-6 |
| F-22 | **`replaceGradeScope` writes the same grade set to all five managed resource types** (`serviceRecords`, `eventProposals`, `students`, `accounts`, `cases`) | Resolving an export's scope against `"students"` gives the same answer as any other resource type would, so the choice (Q-1) is safe today and only matters if that ever diverges |
| F-23 | **`enrollment` is the registration table**, statuses `PENDING` / `CONFIRMED` / `WAITLISTED` / `REJECTED` / `COMPLETED` / `CANCELLED` | §6.4/§6.5's "confirmed registrations" maps to `status = 'CONFIRMED'` |
| F-24 | **Every §6.4 column has a source.** `project` carries `summary`, `capacity`, `recognized_minutes`, `eligible_grade_ids` (JSON), `owner_id`, `organization_id`, `location`, `start_at`/`end_at`, `status` | Projects Report needs joins and aggregates, not new columns |
| F-25 | **Permission codes are serialized into the session at login.** `CookieSessionUserDetails` holds an `AuthenticatedUserView` and is stored in `SPRING_SESSION_ATTRIBUTES`; `SessionRevocationService.revokeAllSessionsForUser` exists | A revoke does not take effect for a logged-in account until its session ends. R-4 |

---

## 2. Decisions

Recorded with the rejected alternative wherever the rejected one is the obvious first
answer, because it will be proposed again otherwise.

### 2.1 `exports.create` is retired by **revoking its grants**, never by deleting its row

`10-RolesAndPermissions.md` §5.2 already requires the row to survive, marked retired,
"never deleted, never reused" — the same treatment `projects.close` got. The migration
therefore does two different things to one permission: `DELETE` from `role_permission`,
`UPDATE` the `permission.description` to say retired and why.

**Rejected — leaving the grants in place and simply stopping checking the code.** An
account would still show `exports.create` in its permission list and in
`RoleManagement`'s editor, so the role screen would keep offering a permission that
grants nothing. A permission that is visible and inert is worse than one that is gone.

**The repeatable seed cannot do this on its own.** `R__seed_permissions_and_roles.sql`
is `INSERT IGNORE` throughout: removing a line from it does not remove the row from a
live schema. The revoke must be an explicit `DELETE` in a **versioned** migration, and
the repeatable seed must stop granting it so a from-scratch schema agrees. Both halves,
or the live server and a fresh IT schema disagree — the exact trap `V21`'s own comment
documents from the other direction.

### 2.2 The seven new permissions are inserted granted to nobody, then granted in a second statement

`10-RolesAndPermissions.md` §9.4: *"A new permission is granted to no role by default,
then assigned deliberately."* So the migration is `INSERT` the seven rows, then a
separate `INSERT … VALUES` per (role, permission) pair from §5.3's table — 7 for
`admin`, 6 for `teacher`, 0 for `sysadmin`.

**Rejected — deriving the teacher grants from `exports.create`'s existing holders.**
There are none (F-1), so it would grant nothing; and even if there were, "whoever held
the coarse permission gets all seven fine ones" is precisely the claw-back pattern §9.4
forbids.

**`role_template_teacher` gets the same six.** `V22`/the repeatable seed maintain it as
a separate row; a template that does not match the role it templates is a trap for the
next person who creates a teacher from it.

### 2.3 `sysadmin` loses import and export in the same migration

`DELETE` `perm_exports_create` and all three `perm_imports_*` from `role_sysadmin`, and
drop those four lines from the repeatable seed's `role_sysadmin` block.
`10-RolesAndPermissions.md` §6.1 is explicit that this reverses `V21`'s merge for these
two groups only.

**This is a live-behaviour removal, unlike §2.1.** `sysadmin` accounts can import and
export today and will stop being able to. R-7.

### 2.4 The two retiring export types are removed from the accepted set; their permission rows do not exist to retire

`participation` and `serviceHours` retire as *export types*. They never had per-type
permissions — there was only `exports.create` — so `12-DataExport.md` §6's "their
permission rows are kept and marked retired" has nothing to act on beyond §2.1's single
row. Note that in the migration comment so the next reader does not go looking.

Removing them from `SUPPORTED_REPORT_TYPES` makes `POST /exports {"reportType":
"participation"}` a `400 INVALID_REQUEST`. The two buttons in `SystemAdmin.tsx` go in
the same phase, so nothing user-reachable breaks.

### 2.5 Export scope resolves to a **student-id set**, computed once per job, before any data query

This is the concrete form of `12-DataExport.md` §4.4 and `10-RolesAndPermissions.md`
§7.2.1. One new component — call it `ExportScopeResolver` in
`cn.timebank.dataexchange.application` — does:

1. `DataScopeResolver.resolve(principal, "students", hasSchoolWideVisibility())` →
   a `ScopeDescriptor`;
2. `switch` on it exhaustively:
   - `All` → no student restriction;
   - `Grades` → `select id from student where grade_id in (:gradeIds)` — the
     student's **live** grade, per §7.2;
   - `Classes` → the same on `class_id`;
   - `Self` → **refuse the job** with §7.3's message (see below);
   - `Projects` → unreachable for exports; throw rather than fall through.
3. Apply the user's own Academic Year / Grade / Class filters **as an intersection**,
   never a union (§4.3: a filter can only narrow).

Every export query then joins against that student set, including the record-subject
ones. A Service Records export selects records **of those students**, not records
carrying those grade snapshots. That is the whole behavioural change §4.4 asks for, and
it is why the resolution happens once, in one place, instead of each query reaching for
`root.get("gradeId")` the way `ServiceRecordScopeFields` does for the review queue.

**Rejected — reusing `DataScopeSpecifications` with a `ScopeFields` that points at the
student join.** It only produces JPA `Specification`s and the export path is raw JDBC
(F-4). Converting the export repositories to JPA to reuse it would give up the keyset
pagination that `12-DataExport.md` §2.1 explicitly keeps.

**A `Self`-scoped teacher is refused, not given an empty file.** §7.3 requires the
message "you have not been assigned any grade" rather than an empty export. So this is
a `422` at `POST /exports` with its own error code, not a zero-row CSV — a downloaded
empty file is indistinguishable from "nothing happened that year", and the whole point
of §7.3 is that the two must not look alike.

### 2.6 Filters travel in `params_json`; the request grows one nested object

`CreateExportRequest` becomes
`{ reportType, filters: { academicYearId?, gradeId?, classId?, reviewerId? } }`, all
filter fields nullable, `null` meaning "All" (§4.1). The service serializes `filters`
into the existing `job.params_json` (F-5) and `ExportJobRunner` reads it back off the
claimed job.

**Rejected — new columns on `job`.** `params_json` exists for exactly this and is
unused; four nullable columns that only one job kind ever populates is a worse schema.

#### 2.6.1 Which filters a type has is part of the type, declared server-side

The request above is a flat object with four nullable fields, and **nothing in that
shape says which of them a given export accepts**. Left there, two things go wrong:

- `POST /exports` would take a `classId` on Review History — an export with no class
  dimension at all — and **silently ignore it**. A filter that is accepted and then
  ignored is indistinguishable from one that works, which is precisely the failure
  issue 093 §1 existed to remove.
- the frontend would hard-code the filter set per type, so adding an export means
  editing a list in two codebases, and the two are free to disagree.

So the filter set is declared **once**, as part of the export type's definition:

| Export type | Declared filters |
| :--- | :--- |
| `studentList`, `serviceRecords`, `studentCompletion`, `studentParticipation` | Academic Year, Grade, Class |
| `projects` | Academic Year, Grade |
| `reviewEfficiency` | Academic Year, Grade |
| `reviewHistory` | Academic Year, Reviewer |

`GET /exports/types` returns that catalogue — key, i18n title and **card text** (§6
's explanation, not a one-line label), and the
filters the type declares — and the client renders only what it is told. `POST /exports`
**rejects** a filter the type does not declare, with a field error, rather than
accepting and dropping it.

Hiding then needs no rule of its own: a card shows the filters its type declares, and a
teacher's Grade filter is additionally narrowed to their bound grades (§4.3). Adding an
eighth export type later changes one server-side definition, and the card appears with
the right controls without a frontend edit.

**This is a contract addition** (a new endpoint), not just an implementation detail —
`APIContract.md` needs the same entry.

**Rejected — query parameters on `POST /exports`.** The filters are part of what the
job *is*, and the job row has to carry them anyway so the runner (a different thread,
later) can see them. A body field and a stored field are then the same shape.

`params_json` is `VARCHAR(2000)`; four opaque ids plus keys is far inside it. The
serialized form is also what §2.10's audit entry records, so an export is
reconstructable from the audit log alone.

### 2.7 Grade is **derived in Java** from the enrolment year, and never read from `student.status`

Per `12-DataExport.md` §5.1, in one place — `ExportGradeDerivation`, a small pure class
with no repository dependency:

```
entryGrade   = currentGradeLevel − (currentYearIndex − enrolmentYearIndex)   // 6 or 9
gradeIn(Y)   = entryGrade + (Y − enrolmentYearIndex)
enrolledIn(Y) ⇔ enrolmentYearIndex ≤ Y ≤ graduationYear − 1
```

Three consequences that must be honoured and are each easy to lose:

- **Students failing `enrolledIn(Y)` are excluded from that year's export** (§5.1) —
  they are not shown with an empty grade.
- **`Graduated (YYYY)` is derived from `graduation_year`, never from
  `status = 'GRADUATED'`** (§5.4). The derivation is correct before the annual rollover
  exists and stays correct if a year's run is missed; the status column is wrong in both
  cases.
- **With Academic Year = "All years" the Grade column shows the *present* grade**
  (§5.3), because there is no year to derive against.

Pure and dependency-free so it is unit-testable against the §5.1 table (stage-1 entrant
in a given year reading Grade 6/7/8 then `Graduated`) without a database.

### 2.8 One shared CSV writer owns every column convention

`ExportJobRunner` today concatenates headers as string literals per type. The
conventions in `12-DataExport.md` §2 are cross-cutting and will drift if each of seven
writers re-implements them. So: one `ExportCsvWriter` that owns

- the `No.` counter (§2.3), emitted by the writer so no query can forget it;
- empty cell for absent (§2.4) — `null` renders `""`, and `String.valueOf` is banned
  from this file;
- centi-coins → coins (§2.5), one formatter, `1` / `1.5`, never `100`;
- percentages to two decimals with a `%` suffix (§2.5);
- `YYYY-MM-DD` / `YYYY-MM-DD HH:MM` (§2.5);
- status and role rendered through a label map, not `enum.name()` (§2.5);
- the existing `neutralizeFormulaInjection` + RFC 4180 quoting, moved as-is (§2.7 —
  "already holds in the implementation and must not be weakened").

Column **names stay fixed English** and live in code beside the query (§2.6). Card
**titles and card texts** are i18n keys in `en.json` and `zh.json` (§2.6) — the card
text is a short paragraph rather than a label, so the card layout has to give it room
and must not truncate it to one line. Those two
facts pull in opposite directions and are the single most likely thing to be got wrong
by someone "making the export translatable".

### 2.9 The historical-import window is state on the import feature, not on a job

A tiny table, `historical_import_window`, rather than a `system_settings` row: the
window has four facts (opened-at, expires-at, what opened it, and — later — who
reopened it and why), and `system_settings` is a key/value store that would spread them
across four rows with no way to read them atomically.

- Opened by the **first `COMMITTED` `serviceRecordsHistorical` job** (Q-5), inside that
  job's own transaction, `INSERT … ON DUPLICATE KEY UPDATE` nothing — later imports
  must not extend it (§6.1).
- `expires_at = opened_at + 15 days`, the 15 stored on the row rather than computed at
  read time, so the backlog utility can set a different window without the reader
  needing to know which rule applied.
- Enforced at **both** `POST /imports` (type `serviceRecordsHistorical`) and
  `POST /imports/{id}/commit`. Two checks, because a job validated before expiry must
  not commit after it (Q-8).
- `GET` exposure: one small read the frontend uses to decide between "not yet opened →
  show the warning", "open → show countdown + expiry date", "expired → render no card
  at all" (§6.1, §6.2).

**Rejected — deriving the window from the job table** (`min(finished_at)` over
committed historical jobs). It reads correctly today and becomes wrong the moment the
backlog utility reopens the window, because there is nowhere for the new expiry to
live. The design promises a reopen; the schema has to be able to represent one.

### 2.10 Export creation writes an audit entry, before the job runs

`AuditService.record` in `ExportServiceImpl.create`, carrying actor, export type, and
the serialized filters (§2.6). At `POST` time rather than on completion: the auditable
act is *asking for the data*, and a job that fails still tells you someone tried.

A second entry on successful download, naming the job and the row count. The file is
the egress; the job is only the intent.

This is `11-DataImport.md` §8's guarantee finally made true for the export half, and it
closes F-9. Per the repo's standing rule, "audited" is not established by reading the
code — it is established by querying `GET /audit-logs` after a real export on the test
server (§3.5's exit criteria).

### 2.11 Export files expire; the download token becomes single-use

Two small changes that make `12-DataExport.md` §8 true rather than aspirational:

- **Single-use token** (F-10): the issued token's id is recorded and marked consumed on
  first successful `content` fetch. Stateless HMAC alone cannot express single-use — a
  replay is indistinguishable from the first use without server state.
- **Output retention**: a scheduled sweep deletes `export_*` storage keys and nulls
  `result_ref` for jobs finished more than a configured interval ago. ShedLock is
  already wired (grade-summary materialisation, the import reclaim sweep), so this is a
  new job, not new infrastructure.

**Rejected — deleting the file immediately after the first download.** A browser that
retries, a download that fails mid-stream, or a user who closes the tab then all lose
the export with no way back except re-running it. Single-use *token* plus a
time-bounded *file* gets the property §8 wants without that.

### 2.12 Import and Export become two tabs, on a page a teacher can actually hold

`11-DataImport.md` §5.2 and `12-DataExport.md` §3 both require two independent tabs,
each hidden without its permission. In `SystemAdmin.tsx`'s `Tab` union, `'import'`
splits into `'import'` and `'export'`, each conditionally present:

- `import` shown iff `imports.create` — so a teacher never encounters the concept
  (§5.2's stated intent);
- `export` shown iff the user holds **at least one** `exports.*`;
- within Export, **one card per export type the user holds**, single column, collapsed
  by default, expanding to that type's filters and its Export button. Types the user
  lacks render **nothing** — not a disabled card (§3).

And the thing this exposes (F-15): granting teachers export makes System Admin a page
teachers routinely open, while its default tab needs `users.read`. So the same phase
picks the **first tab the user can actually see** rather than hardcoding `'users'`, and
each existing tab becomes conditional on its own permission. Not a redesign of System
Admin — just no longer landing a teacher on a 403.

**Rejected — a separate `/admin/data` page for teachers.** It duplicates the tab shell,
gives two places to look for export, and the design says "two tabs", not "two pages".

---

## 3. Phases

Each phase ships on its own and leaves the system coherent. Phases 5 and 6 depend only
on phase 1 and can be pulled forward if the window's deadline pressure demands it.

### 3.0 Build status (2026-09-20)

**All six phases are built and verified on the local Docker stack.** Nothing is
deployed to the shared test server yet.

| Phase | State | Migration |
| :--- | :--- | :--- |
| 1 — permission split, two retirements | Done, backend and frontend | `V31` |
| 2 — filters, student scope, column conventions, shared CSV writer | Done | — |
| 3 — Student Completion, Student Participation | Done | — |
| 4 — Projects, Review Efficiency, Review History | Done | — |
| 5 — egress hygiene, including F-9's audit entry | Done | `V33` |
| 6 — the 15-day historical-import window | Done | `V32` |

Also done, from §2.12: Import and Export are separate tabs, each hidden without
its own permission, and every other tab is gated too — granting teachers export
made System Admin a page they open routinely, and it used to land them on a Users
tab requiring a permission they lack.

**Verification.** 346 unit tests, 60 integration tests against real MySQL. All
seven exports produced and inspected against the seeded roster through the
browser. `Docs/issues/107` can be closed: no database ids, `No.` leads, Student
Number precedes Student Name, and a 672-row Service Records file contains no
occurrence of the string `null`, with coins reading `1` and `3.5` rather than
`100` and `350`.

**Four defects that only real data exposed**, each worth remembering because the
test suite was green in every case:

- **MySQL's `AVG()` is DECIMAL**, so Review Efficiency's `(Double)` cast threw at
  run time. The integration test passed because its fixture left `submitted_at`
  null, which made the average null and the cast unreachable — a fixture that
  omits a column can make a whole column's arithmetic untested.
- **An empty student set expands to `in ()`**, a syntax error. Reachable through
  Projects Report, where a grade filter matching no students still has projects
  to list — so the fix is zero participation, not zero rows.
- **`grade.code` is the id again** (`grade_8`), so `Bound Grades` printed ids in
  a column read by a person while the same information elsewhere read `Grade 8`.
- **The projects test passed vacuously.** An integration schema contains no
  projects, so a header-only assertion held while the row mapper was broken.

### 3.0.1 The export action is gated behind the card (2026-09-20)

The first build of the export cards had the Export button always visible, with
every filter defaulting to All. That made the cheapest gesture on the screen —
one click, no thought — produce the largest file the account could produce: for
an SDC Admin, the whole school, on every click. Narrowing first took *more*
effort than not narrowing. For a screen whose output is student records that is
the wrong way round.

Three changes, all client-side; the server's scope rules were already correct:

- **Cards are collapsed and carry no Export action.** Opening one is what reveals
  both the filters and the button, so nobody runs an export without having been
  shown what could have been narrowed.
- **An unnarrowed export asks for confirmation**, naming what "everything" means
  for *that* operator — "every student in the school" for an admin, "every
  student in Grade 6" for a teacher bound to one grade. Narrowing is measured on
  the dimensions that decide *whose* rows appear (grade, class, reviewer), not the
  academic year: a year of the whole school is still the whole school. A teacher's
  Review History counts as already narrowed, because the server has pinned it to
  their own decisions.
- **A filter with one possible answer is a statement, not a control.** A teacher
  bound to one grade gained nothing from a dropdown whose two entries selected the
  same students, and their Review History showed "All reviewers" — which was a
  plain contradiction of what the file would contain. Both now read as fixed
  facts. The same rule drops a Class dropdown with no classes under it.

The Reviewer dropdown is also populated for school-wide accounts now. It had been
left empty when Review History had no query, and stayed empty after phase 4
landed, so the filter existed server-side and offered nothing in the UI.

**Two carried forward, not done:**

- **R-4's session decision.** Permission codes are serialized into the session at
  login, so `sysadmin` accounts that are logged in keep import/export until their
  session ends. Revoking affected sessions versus documenting "re-login required"
  is a roll-out call.
- **Q-11's tense mismatch is unaddressed in the UI.** A past-year `studentList`
  row pairs a derived historical `Grade` with a present-tense `Class`. The card
  text should say so; it currently does not.

### 3.1 Phase 1 — The permission split, and the two retirements

**Backend.** One versioned migration (next free number after `V30`):

| Statement | Target |
| :--- | :--- |
| `INSERT` 7 rows | `exports.studentList`, `.serviceRecords`, `.studentCompletion`, `.studentParticipation`, `.projects`, `.reviewHistory`, `.reviewEfficiency` |
| `INSERT` 13 grants | `admin` × 7, `teacher` × 6 (all but `reviewEfficiency`), §2.2 |
| `INSERT` 6 grants | `role_template_teacher`, the same six |
| `DELETE` | `perm_exports_create` from `role_admin`, `role_sysadmin` |
| `DELETE` | `perm_imports_create/read/commit` from `role_sysadmin` |
| `UPDATE` | `permission.description` for `exports.create` → retired, citing `10-RolesAndPermissions.md` §5.1 |

`R__seed_permissions_and_roles.sql` mirrors all of it for a from-scratch schema:
add the seven rows and their grants, remove the four `role_sysadmin` lines and the
`role_admin` `perm_exports_create` line (§2.1's both-halves rule).

`ExportController.create` moves from `hasAuthority('exports.create')` to a per-type
check. `@PreAuthorize("hasAuthority('exports.' + #request.reportType())")` is the
smallest form and works, with one consequence to accept knowingly: an unknown
`reportType` now yields **403 before** the service's 400, because authorization runs
before the method body. That is the right precedence for an unknown *permission* but a
confusing status for a typo. Name it in the contract text.

`ExportServiceImpl.SUPPORTED_REPORT_TYPES` becomes the two keys this phase can serve
honestly — `studentList` (renamed from `students`) and `serviceRecords`. The other five
are not registered yet, so they have no card and `POST` them is a 400. Nothing
half-built is shown to anyone (the standing rule about flagged-not-half-built features).

**Frontend.** The `students` → `studentList` key rename; the `participation`,
`serviceHours`, `completion`, `reviewEfficiency` and `topProjects` buttons removed;
`RoleManagement.tsx`'s sensitive-permission list swaps `exports.create` for the seven
(F-18).

**Docs.** `APIContract.md` §4.13's `POST /exports` permission cell and its `_CN` twin.
`07-RoleFinetune.md` §6/§365's catalogue row gets a pointer to this plan rather than an
edit — it is a record of a past decision, not a live catalogue.

**Exit criteria.**

1. `GET /me` for a seeded admin account lists all seven `exports.*` and **not**
   `exports.create`; for a teacher, six, with `exports.reviewEfficiency` absent.
2. `GET /me` for a sysadmin account lists **no** `exports.*` and **no** `imports.*`.
3. `POST /exports {"reportType":"studentList"}` as sysadmin → 403; as admin → job
   created; as a **grade-bound teacher** → job created.
4. `POST /exports {"reportType":"participation"}` → 4xx for every role, and no such
   button exists in the UI.
5. `mvn verify` passes with `ImportExportIT` updated to the new keys; a fresh
   `timebank_it_*` schema and the live schema produce the **same** `role_permission`
   rows for the four seeded roles (the §2.1 trap, checked rather than assumed).
6. Sessions: R-4's revocation has been performed or the roll-out window has been
   stated. Not "the migration ran".

### 3.2 Phase 2 — Filters, student scope, and the column conventions

Everything cross-cutting, landed against the two types phase 1 registered so it is
provably exercised before four more types depend on it.

- `GET /exports/types` (§2.6.1) plus `ExportTypeCatalog`, so the filter set is declared
  once and the client stops hard-coding it. `APIContract.md` gains the entry.
- `CreateExportRequest` grows `filters` (§2.6); `ExportServiceImpl` validates each id
  exists (a non-existent `academicYearId` is a 404, matching `/reports/*`'s established
  behaviour) and serializes to `params_json`.
- `ExportScopeResolver` (§2.5), including the `Self` refusal.
- `ExportGradeDerivation` (§2.7) plus the `enrolledIn` filter.
- `ExportCsvWriter` (§2.8); `ExportJobRunner`'s per-type header literals and
  `csvLine`/`neutralizeFormulaInjection` move behind it.
- `StudentExportRepository` and `ServiceRecordExportRepository` re-cut: student-id set
  predicate, the year predicate from F-19, grade/class **labels** not ids (`grade.name`
  resolved per the request language for the *card*, but the column value is the stable
  grade name; `class.code` not `class.name`, for the reason `Docs/issues/107` §3 gives —
  `name` is the bare suffix and is ambiguous alone).
- Service Records: `DRAFT` excluded, the other five statuses mapped to §6.2's four
  labels, `Credited Coins` empty on unapproved rows.
- Frontend: each card gains its expandable filter panel — Academic Year / Grade / Class
  from `useDictionaries`, each defaulting to "All" (§4.1), the Grade list intersected
  with `/me.scopeGradeIds` and the Class list narrowed to the surviving grades (F-16,
  §4.3).

**Exit criteria.**

1. `Docs/issues/107` can be closed: a downloaded `studentList` CSV has no `id`,
   `gradeId` or `classId` column, its first column is `No.`, and `Student Number`
   precedes `Student Name`.
2. A downloaded `serviceRecords` CSV contains **zero** occurrences of the string `null`,
   no `DRAFT` row, and `Credited Coins` reading `1` / `1.5` where a record credited one
   hour — not `100`.
3. A teacher bound to one grade exports `studentList` with Grade = "All grades" and
   receives **only** that grade's current students; the same teacher's Grade dropdown
   offers only that grade.
4. **The scope crosses a real boundary, not a nominal one**: a student who has records
   filed under a *different* grade snapshot than their live grade appears in their
   current teacher's export **with those records** — the §4.4 case. If the fixture has
   no such student, one is created for the test. A test that cannot tell student-scoping
   from snapshot-scoping proves nothing.
5. A teacher with **no** grade binding gets §7.3's message and **no file**.
6. Selecting a past academic year returns rows whose `Grade` column reads that year's
   derived grade while the row set is the teacher's present students (§7.2's worked
   example, reproduced live).
7. A student not enrolled in the selected year is **absent**, not present-with-blanks.

### 3.3 Phase 3 — Student Completion and Student Participation

The two per-student reports (§6.3, §6.5). Both are new queries in a new
`StudentReportExportRepository`; neither reuses `ReportService`, whose grain is the
grade (F-7).

- Student Completion: approved hours and credited coins within the year, projects with
  ≥1 approved record, the band's `*_annual_required_minutes` from the rule version
  (F-21, and Q-6 on *which* version for a past year), `Gap Hours = max(0, required −
  approved)`, completion rate to two decimals.
- Student Participation: confirmed `enrollment` count and names, participated count and
  names (≥1 approved record — §6.5's disambiguation of the draft's "Approved
  Projects"), approved hours, credited coins.
- Student Completion's Academic Year defaults to the **current** year, the single
  documented exception to §4.1 (§4.2), and — per Q-4 — should not offer "All years" at
  all.

**Exit criteria.** Both types registered, permissioned, carded, filtered, scoped; a
student with zero approved records appears with `0` hours and a `0.00%` rate rather than
being absent (they are enrolled; absence would misread as "not in the school"); the
name-count columns and the name-list columns agree on every row; Completion Rate reads
`85.71%` form, not `0.8571`.

### 3.4 Phase 4 — Projects Report, Review Efficiency, Review History

The three whose rows are not students.

- **Projects Report** (§6.4): one row per project, 18 columns, all sourced (F-24).
  Its Grade filter is the intersection of the project's `eligible_grade_ids` and the
  user's bound grades — the only export whose grade filter is not a student filter, so
  it does not go through §2.5's student set for the *row* decision, only for the
  participation aggregates. Q-7 covers a project with null eligible grades.
- **Review Efficiency** (§6.6): widened from `ReviewEfficiencyReportDto`'s four columns
  to twelve — the three absolute counts alongside the three rates, because §6.6's whole
  point is that a rate without its denominator misleads at low volume. `Reviewer Role`
  and `Bound Grades` joined from `user`/`role` and `teacher_scope_assignment` (F-20).
  **Admin only**; the teacher grant does not exist (§5.3).
- **Review History** (§6.7): one row per `review_decision`. Teacher's Reviewer filter
  **pinned to themselves and not selectable** — enforced server-side, not merely
  defaulted in the UI (F-17). Q-2 on whether a teacher's student scope *also* applies;
  Q-3 on `ESCALATE`.

**Exit criteria.** All seven types live. A teacher cannot obtain another reviewer's
history by passing a `reviewerId` in the request body — verified by sending one, not by
reading the controller. A reviewer with one approval shows `1` in `Total Reviews` beside
`100.00%`, so the reader can see the denominator. `12-DataExport.md` §7's checklist runs
clean against each of the seven.

### 3.5 Phase 5 — Egress hygiene

§2.10 and §2.11: audit on create and on download, single-use token, output retention
sweep. Independent of phases 2–4.

**Exit criteria.**

1. A real export on the test server produces audit rows retrievable through
   `GET /audit-logs`, naming actor, export type and the filters — confirmed by querying
   the endpoint, not by reading `AuditService.record` call sites.
2. A download URL fetched **twice** succeeds once and is refused the second time.
3. An export's stored file is gone after the retention interval, and its job row reads
   `result_ref = NULL` rather than pointing at a deleted key.

### 3.6 Phase 6 — The 15-day historical-import window

§2.9's table, its two enforcement points, its read endpoint, and the three UI states
(`11-DataImport.md` §6.2): pre-first-import warning, post-import countdown **with the expiry date** — the
date, because only a date goes into a migration plan — and, after expiry, **no card**.

**Exit criteria.**

1. The warning is visible **before** any historical import has run, and states all
   three facts §6.2 requires: that the first import starts a 15-day clock, that the
   feature closes when it expires, and that only a system administrator can reopen it
   on the server.
2. A **second** import inside the window does **not** move `expires_at`.
3. **The expiry is crossed for real**, by winding `expires_at` back on a test schema:
   `POST /imports` for `serviceRecordsHistorical` is refused, a job validated *before*
   the wind-back is refused at commit (Q-8), the roster import is **unaffected**, and
   the card is **absent** from the page rather than present-and-disabled.
4. The refusal names the backlog utility as the only route back, so an operator reading
   the error is not left guessing.

---

## 4. Where the code changes go

| File | Change |
| :--- | :--- |
| `backend/src/main/resources/db/migration/V31__export_permission_split.sql` | New. §3.1's seven inserts, thirteen-plus-six grants, five deletes, one description update |
| `backend/src/main/resources/db/migration/V32__historical_import_window.sql` | New. §2.9's table |
| `backend/src/main/resources/db/migration/R__seed_permissions_and_roles.sql` | The seven rows + grants; remove `role_sysadmin`'s four import/export lines and `role_admin`'s `perm_exports_create` |
| `…/dataexchange/web/ExportController.java` | Per-type `@PreAuthorize` |
| `…/dataexchange/application/ExportServiceImpl.java` | New type set; filter validation + `params_json`; the `Self` refusal; §2.10's audit entry |
| `…/dataexchange/application/ExportTypeCatalog.java` | New. §2.6.1's per-type filter declarations — the single source both `GET /exports/types` and the `POST` validation read |
| `…/dataexchange/application/ExportJobRunner.java` | Loses every header literal and the five `writeXxx` report methods; becomes a dispatcher over per-type writers |
| `…/dataexchange/application/ExportCsvWriter.java` | New (§2.8). Absorbs `csvLine` / `neutralizeFormulaInjection` / `FORMULA_TRIGGERS` unchanged |
| `…/dataexchange/application/ExportScopeResolver.java` | New (§2.5) |
| `…/dataexchange/application/ExportGradeDerivation.java` | New (§2.7), pure |
| `…/dataexchange/application/ExportRetentionSweep.java` | New (§2.11), ShedLock-guarded |
| `…/dataexchange/application/ExportDownloadTokenService.java` | Single-use state (§2.11) |
| `…/dataexchange/application/dto/DataExchangeRequests.java` | `CreateExportRequest` gains `filters` |
| `…/dataexchange/infrastructure/StudentExportRepository.java` | Student-set + year predicates; labels not ids |
| `…/dataexchange/infrastructure/ServiceRecordExportRepository.java` | Same, plus the `DRAFT` exclusion and the status mapping's source columns |
| `…/dataexchange/infrastructure/StudentReportExportRepository.java` | New (§3.3) |
| `…/dataexchange/infrastructure/ProjectExportRepository.java` | New (§3.4) |
| `…/dataexchange/infrastructure/ReviewExportRepository.java` | New (§3.4), both review types |
| `…/dataexchange/application/ImportServiceImpl.java` | Window check on `create` and `doCommit` |
| `…/dataexchange/application/ImportJobRunner.java` | Opens the window on the first `COMMITTED` historical job |
| `…/dataexchange/infrastructure/HistoricalImportWindowRepository.java` | New |
| `frontend/src/app/components/admin/SystemAdmin.tsx` | Tab split, per-permission tab list, first-visible-tab default, the export card list replacing the button grid |
| `frontend/src/app/components/admin/ExportCenter.tsx` | New. The card list + filter panels, extracted rather than grown inside a 2,100-line file |
| `frontend/src/app/components/admin/RoleManagement.tsx` | Sensitive-permission list (F-18) |
| `frontend/src/app/api/client.ts` | `api.exports.create` takes filters; `api.imports.window()` |
| `frontend/src/i18n/en.json`, `zh.json` | Seven card titles + seven multi-sentence **card texts** (`12-DataExport.md` §6: what one row is, the question it answers, and the caveat — Q-3's non-summing rates and Q-11's mixed tenses both land here); the window's warning, countdown and expiry copy; the §7.3 no-grade message |
| `Docs/integration/APIContract.md` + `_CN.md` | §4.13's permission cell and the "carry no period of their own" paragraph, which stops being true in phase 2; §4.14's window states |
| `Docs/implementation/00-Status.md` | Phase entry/exit as each lands |
| `Docs/issues/107-*.md` | Closed by §3.2, with its `integration-test/STATUS.md` rows |

`cn.timebank.security.application` needs **no change**. `ScopeDescriptor`,
`DataScopeResolver` and the five `ScopeFields` implementations stay exactly as they are
— the export path consumes the descriptor and does its own translation (§2.5), and the
review queue's snapshot filtering must keep working precisely as it does
(`10-RolesAndPermissions.md` §7.2.1's last paragraph).

---

## 5. Risks

**R-1 — Retiring `exports.create` while accounts hold it.** *Verified and smaller than
it looks, with a sharp edge.* Only `role_admin` and `role_sysadmin` hold it (F-1), so no
teacher population is disrupted. The edge is mechanical: `R__seed_permissions_and_roles.sql`
is `INSERT IGNORE`, so **removing a line from it revokes nothing** on the live schema
(§2.1). A migration that only edits the repeatable seed leaves every live grant intact
while a fresh IT schema has none — the two disagree and `mvn verify` passes anyway,
because IT schemas are built from scratch. Phase 1's exit criterion 5 exists for this.
The second edge: any **school-defined custom role** holding `exports.create` (the role
system exists precisely so schools can add roles) silently loses export and gains
nothing. The migration must enumerate what it revokes from and log the row count, not
`DELETE … WHERE permission_id = 'perm_exports_create'` blind.

**R-2 — The student-scoped export change.** *The stated risk is real but points the
other way.* Nobody currently holds export except `admin`/`sysadmin`, both of which
resolve to `All`, and the export path applies no scope at all (F-3). So this change
does not alter what any existing holder sees — **it alters what a population that does
not exist yet will see.** The actual risk is that the `Grades` and `Self` branches of
§2.5 are unexercised in production until the first teacher is granted, and the first
person to find a bug in them will be a teacher who thinks their students' data is
missing. Mitigation: phase 2's exit criteria 3–7 force every branch on a real bound
teacher account before phase 3 builds anything on top, and criterion 4 specifically
crosses the snapshot-versus-student boundary rather than checking a case where the two
agree.

**R-3 — The catalogue rename is a breaking API change.** `students` → `studentList`,
and five keys stop being accepted. Anything holding a saved export type string breaks:
the frontend (fixed in-phase), `ImportExportIT`, and `integration-test/`'s fixtures.
There is no public API consumer, so the blast radius is inside the repo — but a
half-deployed pair (new frontend, old backend, or the reverse) produces 400s on every
export button. The repo's existing rule applies: check whether frontend source changed
since the last deploy and redeploy both.

**R-4 — A permission change does not reach a live session.** Permission codes are
serialized into `SPRING_SESSION_ATTRIBUTES` at login (F-25). A logged-in sysadmin keeps
`exports.create` as a granted authority until their session ends — and since phase 1
*also* stops the controller checking that code, they lose export immediately anyway;
the inverse case is worse: a teacher granted six new permissions sees none of them
until re-login, and reads that as the feature not working.
`SessionRevocationService.revokeAllSessionsForUser` exists. Decide and state which:
revoke affected sessions as part of the roll-out, or document "re-login required".
Silently doing neither is what produces a bug report.

**R-5 — The grade derivation is only as good as its inputs, and one input is known
inconsistent.** §2.7 derives the entry grade from the *live* `grade_id`, so a missed
rollover run makes both the derived grade **and** the enrolment window wrong at once,
in the same file, with no indication. `13-AnnualRollover.md` §5's consistency check is
the only signal, and it lives in a process not yet shipped. Worse for the near term:
`13-AnnualRollover.md` §2's implementation note records that `integration-test`'s
`graduationYearFor` "still assumes a six-year run ending at grade 11", so the seeded
fixture may well produce derived grades past 12 or enrolment windows that exclude
students who are plainly present. Any export verification that runs against the seeded
dataset must check the fixture's consistency first, or it will be debugging the fixture
while believing it is debugging the derivation.

**R-6 — The window is irreversible and its key is not built.** Phase 6 ships a door
that locks after 15 days, and `Docs/backlog/01` — the only way to unlock it — is
explicitly unbuilt and unscheduled. If the window is enabled on a live system and the
clock is mis-set, or the migration runs long, there is no route back. Recommendation:
land phase 6 with the window length as a configuration property (Q-9), and treat the
backlog utility as a **go-live prerequisite**, not a commit-time one. Note the
dependency in `Docs/backlog/01`'s status line when phase 6 lands, so the backlog doc
stops being a note about a hypothetical.

**R-7 — `sysadmin` genuinely loses capability.** Unlike R-1 this is a live removal
(§2.3), and the design accepts the cost in terms (§6.1: "a system administrator
debugging an export failure cannot run one to reproduce it"). Two practical notes the
design does not make: the seeded test accounts include a sysadmin, so any runbook or
verification script that exports as sysadmin breaks; and a sysadmin retains **filesystem
access to `export_*.csv`** (F-11), so revoking the permission narrows the *audited* path
while the unaudited one stays open until §2.11's retention sweep bounds it. Worth stating
plainly rather than letting §6.1 read as stronger than it is.

**R-8 — Rates divide by zero.** §6.4's `Participation Rate` divides by `Capacity`,
`Approval Rate` by submitted-record count; §6.6's three rates by `Total Reviews`. A
project with capacity 0, or with no submitted records, and a reviewer with no decisions,
are all ordinary. §2.4's empty-cell rule is the answer — an undefined rate is an empty
cell, never `0.00%`, which would read as a real measurement of failure.

**R-9 — Four of the seven exports will be the first per-student full-table queries in
the system.** Student Completion and Student Participation aggregate across
`service_record` and `enrollment` per student, over the whole roster. The existing
bounded reports read a materialized summary. At the school's real scale this is fine;
the risk is the pattern, not this year's row count — these are `NamedParameterJdbcTemplate`
queries with no keyset walk designed in, unlike the two that have one. Decide per query
whether it chunks, and do not let "the aggregate reports never needed chunking" carry
over to queries whose grain is a student.

**R-10 — `ESCALATE` has no home.** F-20/Q-3. Until it is decided, a Review History
export of a dataset containing an escalation either omits real decisions or writes an
unmapped enum value into a column the design says holds one of three labels.

---

## 6. Open questions

Each with a recommendation and what it costs. None of these are invented answers — they
are places the three design docs genuinely do not say.

> **Resolved 2026-09-19: every recommendation below is accepted.** They are kept here
> with their trade-offs rather than being folded silently into the phases, because the
> cost of each choice is the part worth re-reading when one of them turns out wrong.

| # | Question | Recommendation | Trade-off |
| :--- | :--- | :--- | :--- |
| Q-1 | Which `resource_type` does an export resolve its scope against? `teacher_scope_assignment` is keyed per resource type, and `12-DataExport.md` §4.4 says only "the data scope" | **`"students"`** — the export's subject is the student, so the student resource's binding is the honest one | Today all five managed types get identical grade sets (F-22), so the choice is unobservable. It becomes observable if per-resource bindings ever diverge, and then this decision is load-bearing and undocumented unless recorded now |
| Q-2 | For a teacher, does `exports.reviewHistory` scope by *reviewer = self* only, or **also** by their student scope? §6.7 pins the Reviewer filter and says nothing about the students in those rows | **Reviewer-pinned only** — *decided*, and note this is a scope predicate on the rows, not a filter control anyone sees. Adding the student scope on top would hide a teacher's *own* past decisions from them | The original reasoning here was that the extra predicate is "nearly always a no-op", which this column already disproved: a teacher who moves grades has decisions on students now outside their scope, so it is a no-op only until someone changes year group. The conclusion survives; the reason for it does not, and is corrected above |
| Q-3 | How is `ESCALATE` exported? §6.7's `Decision` maps three values; the column has four (F-20) | Add **`Escalated`** as a fourth label, count it in §6.6's `Total Reviews`, and exclude it from all three rates | The three rates then do not sum to 100%, which invites "the numbers don't add up". The alternative — omitting escalations — hides real decisions from a file called Review History, which is worse. Document the non-summing explicitly in the card description |
| Q-4 | May Student Completion's Academic Year be set to "All years"? §4.2 says it *defaults* to the current year and explains why an all-years rate is meaningless, but does not say the option is absent | **Remove the option for that one export.** §4.2's reasoning — "the denominator does not exist" — is an argument against the option existing, not just against it being the default | It makes one card's filter behave differently from the other six, which is a small inconsistency a user may read as a bug. Cheaper than producing a completion rate that means nothing |
| Q-5 | Does the 15-day clock start at the first successful **commit**, or the first successful job **creation**? §6.1 says "the first successful historical import" | **First `COMMITTED` job.** A validated-but-abandoned job imported nothing, and starting an irreversible clock on an upload that was never confirmed is the opposite of §3's validate-then-confirm intent | An administrator who commits a tiny 3-row test file starts the real clock. The §6.2 warning must therefore say *any* successful import starts it, not "the real one" |
| Q-6 | Which rule version supplies `Annual Required Hours` for a **past** year? §6.3 states junior 25h / senior 75h as constants, while its own note says the conversion rate belongs to the rule version, and V25 makes the requirement per-version too (F-21) | **The version in effect during the selected year**, matching how each record converts on its own service date and how `/reports/completion` already scopes ("the annual required minutes that were in effect **during** that year") | "In effect during a year" is ambiguous if a version was published mid-year. Recommend the version in effect on the year's **end date**, since the requirement is judged at year end — but say so, because the alternative (start date) is equally defensible and produces different files |
| Q-7 | Projects Report's Grade filter, for a project whose `eligible_grade_ids` is null or empty | **Treat null/empty as "open to all grades"** — the project is included for any user's grade filter | If null actually means "not yet configured" rather than "open to all", this over-includes draft projects. Worth confirming against how the Project Center renders an empty eligible-grades list before committing to it |
| Q-8 | A `serviceRecordsHistorical` job already `VALIDATED` when the window expires | **Refuse the commit**, with an error naming the window and the expiry date | A validated job becomes permanently uncommittable, which will look like a bug to whoever validated it 20 minutes earlier. The alternative — honouring in-flight jobs — makes the expiry advisory, and "validate a job now, commit it whenever" is exactly the indefinite door §6 closes |
| Q-9 | Is the window length fixed at 15 days or configurable? `11-DataImport.md` §6.1 states 15; `Docs/backlog/01` §4 lists this as open for the utility too | **Configurable, defaulting to 15**, with the applied length stored on the window row (§2.9) | A configurable window can be set to 3650 days and the mechanism is gone. Mitigate with a ceiling rather than by hardcoding: a hardcoded 15 forces a code change and a deploy for a migration that legitimately runs long, which is pressure applied at the worst possible moment |
| Q-10 | When Academic Year is "All years", which year's classes does the Class filter list? `class` rows are per academic year (F-19) | **The current year's**, matching §5.3's choice to show the present grade for "All years" | A class that existed only in a past year is then unselectable, so a user cannot export "everyone who was ever in 9-Mars". Acceptable: that question is answered by picking the year |
| Q-11 | Does `studentList`'s `Class` column show the class **as of the selected year**? §6.1 marks only `Grade` as derived | **Present class**, since no historical roster exists (§5.1's own premise) | The row then mixes tenses — a historical `Grade` beside a present-tense `Class` — which is genuinely confusing on a past-year export. Recommend naming this in the card description rather than pretending the columns agree |
| Q-12 | §6.6's `Bound Grades` for a reviewer who is an SDC Admin and has no bindings | **Empty cell**, per §2.4 | `All` is more informative and arguably more truthful (their scope *is* everything), but §2.4 admits no exceptions and an invented literal in a column of comma-separated ids is its own inconsistency. If `All` is preferred, §2.4 needs the carve-out written down |

---

## 7. What the design leaves contradictory or under-specified

Distinct from §6: these are places the design states something the built system does not
do, or states two things that cannot both hold. Each needs a decision *in the design*,
not in this plan.

1. **"Single-use download token" is not implemented and cannot be, as built.**
   `12-DataExport.md` §3 and §8 both assert it; the token is a stateless HMAC replayable
   for 300 s (F-10). §2.11 proposes making the design true. Until it lands, §8's claim
   should not be read as a property the system has.

2. **"No long-lived server-side copies" is false today.** Every export ever run is still
   on disk (F-11). This is the more serious half of the pair, because it interacts with
   §6.1's reasoning for removing sysadmin's export permission: the permission is not the
   only path to the files.

3. **`11-DataImport.md` §8's "commit runs in a single transaction; 'half imported' does
   not exist" describes a different implementation.** The historical commit is
   row-by-row with a 1,000-row checkpoint specifically so a crashed job **resumes**
   (F-13), and `ImportJobRunner`'s own comments describe live bugs caused by that
   resumability. Both behaviours are defensible — resumable is arguably better for a
   20,000-row import — but the design currently claims the one the code does not do.
   Out of scope here; it needs its own decision, and until then §8's table is wrong
   rather than merely optimistic.

4. **`11-DataImport.md` §8's "Every commit writes an audit entry: actor, type, row count
   and job id" has no implementation.** Per-row entries exist via the underlying account
   and student services; the job-level entry naming type, row count and job id does not
   (F-9).

5. **The grade derivation's two halves trust different fields.** §5.1 derives the entry
   grade from the *live* `grade_id` but bounds enrolment with the *stored*
   `graduation_year`. §5.2.1 acknowledges repeated years as a cause of divergence;
   `13-AnnualRollover.md` §5 names a **missed run** as the other cause, and §5.2.1 does
   not mention it. A missed run corrupts both halves at once and the export gives no
   signal. Worth adding to §5.2.1, since the whole point of §5.4's "always derive, never
   read the status column" is robustness against a missed run.

6. **§6.3's requirement figures are stated as constants in a design that elsewhere
   insists they are versioned.** "junior 25h, senior 75h" in the column table, against
   the same section's note that the conversion rate belongs to the rule version, and
   against V25's per-version columns. Q-6.

7. **§4.3 requires a scoped grade list that no endpoint provides.** "A teacher's Grade
   filter lists only their bound grades" is satisfiable from `/me.scopeGradeIds` (F-16),
   but §4.3 reads as though a scoped dictionary exists. Worth a sentence saying where
   the narrowing happens, since a client-side intersection is only a display narrowing
   and the server must refuse out-of-scope filter values independently (F-17).

8. **§6.2's `Project / Organisation` column covers two of three source types.**
   `service_record.source_type` is `PROJECT | EXTERNAL | SELF`; the design names "project
   title for project-sourced records; organisation name for self-initiated ones", which
   maps `EXTERNAL` and leaves `SELF` — a record with neither a project nor an
   organisation — unaddressed. §2.4 answers it (empty cell), but the column note should
   say so rather than leaving a reader to infer that `SELF` was overlooked.

9. **§7's checklist item "Scope is resolved through `ScopeDescriptor`" is true only in a
   weaker sense than it reads.** The descriptor is resolved; `DataScopeSpecifications`,
   the mechanism that normally turns one into a predicate, is unusable on the raw-JDBC
   export path (F-4). Anyone adding an export type and reading that checklist line will
   look for `DataScopeSpecifications.apply` and not find it. §2.5's resolver is the
   answer; the checklist should point at it once it exists.
