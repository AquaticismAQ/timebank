# Roles and Permissions Design

> This document describes the system's role templates, permission catalogue and
> data-scope model. It is the shared foundation for the data import
> (`12-DataImport.md`) and data export (`13-DataExport.md`) designs.
> Keep `Design/chinese/11-RolesAndPermissions.md` in sync when editing this file
> — the Chinese version is the source of truth.
> Related: compliance basis in `08-mlps-level1-security-design-guide.md`;
> the earlier role adjustment record in `Docs/optimization/07-RoleFinetune.md`.

---

## 1. The question this design answers

"Who can do what" has never been one question in this system. It is two:

1. **Which kind of action may be performed?** — may this person start an export,
   may they submit a review decision.
2. **Which rows may the action touch?** — may they export the whole school, or
   only the grades they teach.

Collapsing these into one concept is the commonest way a permission system
fails: the moment "teacher export" becomes a single permission, there is no way
left to express "a teacher may export their own grades only", so the choice
narrows to granting the whole school or granting nothing.

**The core choice here: permission and data scope are two independent axes,
checked separately, and both must pass.** A request must hold the permission for
the action *and* have the target rows fall inside the caller's scope.

---

## 2. Three concepts

| Concept | The question it answers | Where it lives |
| :--- | :--- | :--- |
| **Permission** | Which kind of action is allowed | `permission` table, codes shaped `resource.action` |
| **Data scope** | Which rows the action may touch | `teacher_scope_assignment`, bound per resource type |
| **Role** | A named template of permissions | `role` / `role_permission` |

A role is only a set of permissions; it carries no scope of its own. Two holders
of the same Teacher role — one bound to Grade 7, one to Grade 9 — have identical
permissions and see entirely different data.

**Every user holds exactly one role** (`07-RoleFinetune.md` §2.5). Stacking roles
turns "what can this person actually see" into something that has to be derived,
and the value of a permission system is precisely that it should not need
deriving.

---

## 3. The four role templates

### 3.1 Student (`student`)

**Who**: an enrolled student. The account is created by the roster import and
first entered with an activation slip (`10-StudentActivationFlow.md`).

**Can**: submit service records and event proposals, register for projects,
appeal a review outcome, view their own time-coin account and progress, browse
projects and partner organisations.

**Deliberately cannot**: see any other student's data. A student's scope is
permanently "self" and depends on no binding configuration — that is structural,
not configured.

**Import/export**: neither. Students hold no `imports.*` or `exports.*`
permission.

### 3.2 Teacher (`teacher`)

**Who**: staff carrying review and year-group duties. The account is created by
an administrator, who also chooses the username.

**Can**: within their **bound grades**, review service records and event
proposals, view the student directory and progress, create and publish projects,
maintain partner organisations, view the service-category vocabulary read-only
(retired entries included, §5a), and export the student-facing reports for that
scope.

**Deliberately cannot**:
- **Import anything.** Bulk writes to the roster and to historical records are a
  Student Development Centre responsibility, not a year-group one.
- Manage users, roles or system settings.
- Adjust or reverse time-coin accounts.
- Export the Review Efficiency report — that is peer performance comparison, §5.3.

**Scope**: grade bindings in `teacher_scope_assignment`. **A teacher bound to no
grade resolves to "self"**, and the consequence is that any attempt to view
someone else's data returns 404 rather than 403 (B1.7's deliberate
no-existence-leak). That is very easily misread as "the data is missing", so
binding a grade is a required step whenever a teacher account is created.

### 3.3 SDC Admin (`admin`)

**Who**: the Student Development Centre administrator who runs this system as a
piece of school business.

**Can**: every business action, school-wide — roster import, historical record
import, credential slip issuance and reset, account adjustment and reversal, rule
version publication, project archival and cancellation, case decisions, and
**every export type**. Scope is permanently "all" and depends on no binding.

**Deliberately cannot**: this role is the business authority, not the technical
one. It does not own servers, deployment or the database.

**This is the only role that holds import permissions.**

### 3.4 System Admin (`sysadmin`)

**Who**: the administrator responsible for running the system technically.

**Can**: user and role management, system settings, audit and login logs,
retention purges and ledger reconciliation.

**Deliberately cannot**:
- **Import anything.**
- **Export any student data.**

Reasoning in §6.

---

## 4. The permission catalogue

Permission codes are shaped `resource.action`, grouped by resource. Existing
groups:

`projects`, `eventProposals`, `applications`, `serviceRecords`, `reviews`,
`cases`, `students`, `progress`, `organizations`, `accounts`, `rules`,
**`serviceCategories`**, `reports`, **`exports`**, **`imports`**, `users`,
`roles`, `auditLogs`, `securityLogs`, `settings`, `lifecycle`, `directory`.

This design changes two of them: `exports` and `imports`.
`serviceCategories` is a group added later — see §5a.

---

## 5. The `exports` group: one permission per export type

### 5.1 Why not a single `exports.create`

A single `exports.create` would mean that holding it allows every export type.
The moment a teacher needed the student list, they would also gain the ability to
export Review Efficiency — a report whose every row is a colleague's review count
and approval rate. At that grain the only options are granting too much or
granting nothing.

**So each export type has its own permission**, and `exports.create` is retired:
its row survives so the audit trail of who once held it still resolves, but it
grants nothing and cannot be assigned (§5.2).

### 5.2 Naming rules

```
exports.<exportTypeKey>
```

- **Use the export type's stable key, never its display title.** Titles are i18n
  copy and get rewritten; a permission code is a database key and a string in the
  audit log, and must not follow the copy.
- **Never encode scope in the name.** There is no `exports.studentList.ownGrade`
  — scope is the other axis, decided in §7. Encoding it here re-merges the two
  axes this design exists to keep apart.
- **Never encode format in the name.** Today only CSV exists; an XLSX variant
  later would be another rendering of the same export, not another permission.
- **A retired permission keeps its row, marked retired — never deleted, never
  reused** — the same treatment as `projects.close`. Reusing an old code silently
  changes what historical audit entries mean.

### 5.3 The permissions, and who holds them

| Permission | Export type | Teacher | SDC Admin | System Admin |
| :--- | :--- | :---: | :---: | :---: |
| `exports.studentList` | Student List | ✓ | ✓ | — |
| `exports.serviceRecords` | Service Records | ✓ | ✓ | — |
| `exports.studentCompletion` | Student Completion | ✓ | ✓ | — |
| `exports.studentParticipation` | Student Participation | ✓ | ✓ | — |
| `exports.projects` | Projects Report | ✓ | ✓ | — |
| `exports.reviewHistory` | Review History | ✓ (own only) | ✓ (all) | — |
| `exports.reviewEfficiency` | Review Efficiency | — | ✓ | — |

`exports.reviewEfficiency` is withheld from teachers because every row is a
colleague's review volume, average turnaround and approval rate. That data is
reasonable in a management context and not between peers.

`exports.reviewHistory` *is* granted to teachers, but **the Reviewer filter is
pinned to themselves** — the permission allows the report, the scope decides it
covers only their own decisions. This is exactly what separating the two axes
buys.

---

## 5a. The `serviceCategories` group: split out of `rules.manage`

### 5a.1 Why it should never have lodged inside `rules.manage`

Service categories had no permission of their own. All three write endpoints
checked `rules.manage`, and the catalogue row's own description admitted it:
*"Create or edit rule drafts **and service categories**."* One permission, two
unrelated things.

They are not alike. A rule version is a versioned, immutable-once-published
policy object whose change recalculates every student's compliance; a service
category is a word on a dropdown. Binding them to one permission means a school
cannot draw a line between "let someone tidy the category list" and "hand over
the rules that decide who graduates" — exactly the grain §5.1 rejected for
exports, where the only choices are *too much* or *nothing*.

### 5a.2 Two permissions, and a third deliberately not added

| Permission | Covers | Student | Teacher | SDC Admin | System Admin |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `serviceCategories.read` | the management view — the full list **including retired** categories | — | ✓ | ✓ | ✓ |
| `serviceCategories.manage` | create, rename, retire, delete | — | — | ✓ | ✓ |

**The teacher holds read.** A teacher works with categories all day when
reviewing records, and seeing the whole vocabulary — including what has been
retired, and so why an old record names something no longer offered — is reading
the school's own dictionary, not an administrative power.

### 5a.3 The plain dictionary read stays ungated

`GET /service-categories` — selectable categories only, the list behind every
form's picker — **remains merely authenticated**. A student submitting a service
record has to have it, and **a permission every single role must hold never
denies anything**: it is decoration with a failure mode attached, since the day
it is not granted to one role, that role's forms silently break.

What `serviceCategories.read` actually gates is `?includeInactive=true`, the
management view. The only thing it adds over the form's list is **what has been
retired**. So the permission guards a real boundary rather than a nominal one —
the line falls exactly where the extra information does.

### 5a.4 `rules.manage` changes its description, never its code

`rules.manage` keeps its code and its rule-drafting meaning: a permission code is
a string in historical audit entries and must go on meaning what it meant (§9.3).
Only its wording narrows, now that the second half of it has a home.

Landing it takes both halves (§2.1's both-halves rule). The new permissions and
grants could ship through `R__seed_permissions_and_roles.sql` alone — it is
`INSERT IGNORE` throughout and re-runs on the live schema when its content
changes — but **correcting an existing row's description is something only a
versioned migration can do**, since `INSERT IGNORE` cannot update one. So
`V34__service_category_permissions.sql` carries both, and the seed keeps every
from-scratch schema correct.

---

## 6. The `imports` group: one role holds it

Imports stay divided by **workflow stage** rather than by data type:

| Permission | Meaning |
| :--- | :--- |
| `imports.create` | Upload a file and start validation |
| `imports.read` | View import job status and the error report |
| `imports.commit` | Confirm and write the rows |

Stage rather than type, because what is being gated is the "upload → validate →
commit" pipeline itself (`12-DataImport.md` §3), and with only one role holding
imports at all, a per-type split would draw a distinction nobody can currently
stand on either side of. If a second importing role ever appears, split then.

**All three are held by SDC Admin only.**

### 6.1 Why System Admin loses import and export

`Docs/optimization/07-RoleFinetune.md` §2.3 merged `admin` and `sysadmin` into an
identical permission set (migration `V21`). **This design reverses that merge for
import and export specifically**, because the risk in those two actions has
nothing to do with technical administration:

- **Import** writes student data in bulk, and the historical service-record
  import lands directly on the time-coin ledger. That is a Student Development
  Centre judgement, not an operations task.
- **Export** takes student data out in bulk — the single most concentrated
  data-disclosure action in the system. A system administrator needs the system
  to run, not the student roster in a file.

The cost is real: a system administrator debugging an export failure cannot run
one to reproduce it. Accepted — diagnosis can proceed from logs and job state,
and "the technical administrator does not hold a student-data exit by default" is
the more valuable property to keep.

**The one apparent exception is not one.** After the historical-import window
expires, only a system administrator can reopen it, from the server console
(`12-DataImport.md` §6.3). That does not weaken this rule: the utility reopens
the *window* and grants the operator no `imports.*` — **the switch is not the
door**, and after reopening, only an SDC Admin can still perform an import.

> **Not done here**: apart from import and export, `admin` and `sysadmin` keep
> identical permissions. Whether the two roles should separate further — user
> management, rule publication — is a separate question needing its own
> discussion, and is out of scope for this design.

---

## 7. The data-scope model

### 7.1 Four scopes

`ScopeDescriptor` is a sealed type, so call sites must handle every case
exhaustively and no "default allow" branch exists:

| Scope | Meaning |
| :--- | :--- |
| `All` | Everything. SDC Admin. |
| `Grades` | Students in the bound grades. Teacher. |
| `Classes` | Students in the bound classes. Unused today; the mechanism is kept. |
| `Self` | Only oneself. Students, **and any teacher bound to no grade**. |

### 7.2 A grade binding is present-tense and does not follow the year backwards

A teacher bound to Grade 9, exporting a **past** academic year, sees **the
students who are in Grade 9 today** — not the students who were in Grade 9 that
year.

This must be stated because it reads as counter-intuitive at first:

> A Grade 11 teacher exports the 2024-2025 report. The rows are the Grade 11
> students they teach now, and the Grade column on those rows reads "Grade 9",
> because in that year they were in Grade 9.

The two columns point at different moments, and each is right:

- **Scope** answers "whose data am I entitled to see today" — a **present
  responsibility**.
- **The Grade column** answers "in the year this report covers, which grade was
  this student in" — a **historical fact**.

### 7.2.1 Not every resource filters on the student's live grade

The sentence above — "the students who are in Grade 9 today" — holds only for
resources whose **subject is the student**.

Grade is denormalised onto six tables, and scope filtering reads **each table's
own column** (`14-AnnualRollover.md` §3.4). So the same teacher sees different
populations on different screens:

| Subject | Filtered on | What the teacher sees |
| :--- | :--- | :--- |
| Students, accounts | the student's / account's **live** grade | the students I teach today |
| Service records, cases, proposals | the row's own **grade snapshot** | what happened inside my grade |

The difference shows up the instant promotion runs: a student who has just come
up from grade 8 carries a grade-8 snapshot on last year's records, so the **Grade
9 teacher cannot see them**; conversely a student who has moved up to grade 10
still carries grade-9 snapshots on last year's records, so the Grade 9 teacher
**still can**.

Both behaviours are defensible: filtering the review queue by snapshot is right —
you review what happened in your grade — while "how are my current students
doing" obviously wants filtering by student.

**Exports therefore scope by the student** (`13-DataExport.md` §4.4): an export
is a report *about students*, the rows are decided by the student, and that
student's data is not trimmed because a given year's records hang off a different
grade. Record-subject operational screens such as the review queue keep snapshot
filtering and are unaffected.

**The stage boundary also bounds how far back anyone can look.** Grade 8 to
grade 9 is graduation plus fresh intake (`14-AnnualRollover.md` §2.1), so a
grade 9 student is an entirely new student record. A Grade 9 teacher selecting a
year before that intake therefore gets **zero rows** — in that year the person
existed as a different record, outside this teacher's scope. That is the correct
answer, not missing data.

The derivation rule for the Grade column is in `13-DataExport.md` §5.

### 7.3 A `Self`-scoped teacher exports nothing

A teacher with no grade binding holds the export permission and gets no rows.
That is correct behaviour, not a fault — but it is indistinguishable on screen
from a misconfigured binding, so an empty export must say "you have not been
assigned any grade" rather than producing an empty file.

---

## 8. Role template summary

| Group | Student | Teacher | SDC Admin | System Admin |
| :--- | :---: | :---: | :---: | :---: |
| Projects | read | read/create/update/publish | all | all |
| Event proposals | create/read | read/review/assign | all | all |
| Registrations | create/read | read | all | all |
| Service records | create/read | read | all | all |
| Reviews | — | read/decide/assign | all | all |
| Cases | create/read | create/read | all | all |
| Students | — | read | read/manage | read/manage |
| Accounts | read (own) | read | read/adjust/reverse/freeze | read/adjust/reverse/freeze |
| Rules | — | read | read/manage/publish | read/manage/publish |
| **Service categories** | — | **read** | **read/manage** | **read/manage** |
| Reports | — | read | read | read |
| **Exports** | — | **6 of 7 (§5.3)** | **all 7** | **none** |
| **Imports** | — | **none** | **create/read/commit** | **none** |
| Users / roles | — | — | all | all |
| Audit / login logs | — | — | read | read |
| Settings | — | — | read/manage | read/manage |
| Retention & reconciliation | — | — | all | all |

---

## 9. Rules for adding a permission

1. **Decide first whether it is a permission or a scope.** "Only their own
   grade" is a scope; "may export at all" is a permission.
2. **A new export type must ship with a new permission**, registered in the §5.3
   table with its role assignment. Forgetting either makes the export invisible
   to everyone (nobody holds it) or folds it into an existing permission (a
   regression in grain).
3. **A permission code, once used, is never renamed and never reused.** The audit
   log stores the code string.
4. **A new permission is granted to no role by default**, then assigned
   deliberately. Doing it the other way — grant to every admin role, then claw
   back — means it was allowed the whole time until the claw-back.

---

## 10. Deliberately not modelled

- **No field-level permissions.** Which columns a person sees is decided by the
  export type's definition, not adjudicated column by column.
- **No temporary grants or delegation.** There is no "lend my permission to a
  colleague for three days".
- **No negative permissions.** Permissions only add; there is no "has A except
  B", because the result of two overlapping subtraction rules cannot be worked
  out by reading them.
- **No separate roles per school stage.** The junior/senior difference lives in
  the rule version's requirements, not in permissions.
