# Data Export Design

> This document describes the export type catalogue, the filter and scope model,
> the column conventions and the permission constraints.
> Keep `Design/chinese/12-DataExport.md` in sync when editing this file — the
> Chinese version is the source of truth.
> Related: the permission and scope model in `10-RolesAndPermissions.md`;
> imports in `11-DataImport.md`.

---

## 1. The question this design answers

Export is where student data leaves this system. Once a CSV exists it is bound by
none of this system's permissions — it gets forwarded, left on a shared drive,
opened on a laptop nobody administers.

So an export design has to make three things true at once:

1. **only the rows that should leave, leave** — decided jointly by the permission
   axis and the scope axis;
2. **what leaves is readable by the recipient** — not a set of internal
   identifiers only this system can interpret;
3. **every export can explain itself** — title, description and the filters that
   produced it, comprehensible outside the file.

---

## 2. General principles

### 2.1 No database identifiers

`id`, `studentId`, `projectId`, `reviewerId`, `gradeId` and their kind **never
appear in an exported file**.

The reason is not redundancy but meaninglessness to the recipient: by contract an
id is an opaque server-generated string carrying no interpretable information, so
it cannot be reconciled against anything — it is a join handle into a database
they do not have.

To identify a student, use the **student number**. To identify a project, use its
**name**.

Pagination still walks on `id` (keyset paging, so a deep export does not degrade
into a full scan), but that lives inside the query and is never written out.

### 2.2 Wherever a student name appears, the student number appears with it

A name is not an identifier. Shared names are ordinary in a school, and this
system's own test roster contains several. Any use of the file that groups,
reconciles or merges by student is unreliable with names alone.

**So the Student Name column is always paired with a Student Number column**,
number first.

### 2.3 The first column is a row number, not an ID

Every export's first column is a 1-based row counter named `No.`.

It is **not called `ID`**: this design has just removed database identifiers from
exports, and naming a row counter `ID` invites the reader to treat it as a system
number and reference it elsewhere. A row number means something only inside this
one file, and changes the moment the filters do.

### 2.4 An absent value is an empty cell

Where a column has no value for a row, write an **empty cell** — not `null`, not
`N/A`, not `-`.

This rule comes from a real defect: records that had not been approved carried no
credited amount, and the code converted `null` straight to a string, so the file
contained the literal word `null`. A spreadsheet treats it as text, which breaks
summing, filtering and sorting on that column.

### 2.5 Units and formats

| Kind | Convention |
| :--- | :--- |
| Duration | Whole minutes, column name contains `Minutes`; totals in hours, name contains `Hours` |
| Time coins | In **coins** (`1`, `1.5`), never the hundredths used in storage |
| Rates | Percentage to two decimal places, e.g. `85.71%` |
| Dates | `YYYY-MM-DD` |
| Timestamps | `YYYY-MM-DD HH:MM` |
| Statuses / roles | Stable readable English (`Approved`), not the database enum (`APPROVED`) |

### 2.6 Column names are not translated; titles and descriptions are

**Data column names are fixed English** and do not follow the interface language.
Export files are routinely handed to third-party tools or archived for years, and
column names that drift with language break whatever consumes them downstream.

**The export card's title and description must be available in both Chinese and
English** — those are read by the person choosing the export.

### 2.7 CSV safety

A cell beginning with `=`, `+`, `-`, `@`, tab or carriage return is executed as a
formula by spreadsheets. Student names and descriptions are user-controlled, so
every field is formula-injection-neutralised before being written. This already
holds in the implementation and must not be weakened.

---

## 3. Interface shape

- Export and Import are **two separate tabs**; a user without the permission
  **does not see** the corresponding tab.
- The export page is a **single-column list of cards**, one card per export type.
- Each card carries a **title**, a **description**, and an **expand** control;
  expanding reveals that export's filters and its "Export…" button.
- **Cards for export types the user cannot access are not rendered**, rather than
  shown disabled.
- A card is **collapsed by default and carries no Export control until it is
  opened**. This is the point of the expand step rather than a side effect of it:
  every filter defaults to All, so an always-present button would make one
  unconsidered click the cheapest way to obtain the largest file the account can
  produce, and narrowing first would cost more effort than not narrowing. For a
  screen whose output is student records that is the wrong way round.
- **An export with no narrowing filter applied asks for confirmation**, naming
  what "everything" means for that user — the whole school for an SDC Admin, one
  grade for a teacher bound to one. Narrowing is judged on the dimensions that
  decide *whose* rows appear (Grade, Class, Reviewer), not on Academic Year: a
  year of the whole school is still the whole school. A teacher's Review History
  needs no confirmation, because the server has already pinned it to their own
  decisions.
- **A filter with only one possible answer is stated, not offered as a control.**
  A teacher bound to a single grade gains nothing from a dropdown whose two
  entries select the same students, and their Review History must not show "All
  reviewers" — that would contradict what the file will actually contain.
- Export is asynchronous: the click creates a job, and the finished file is
  retrieved with a **single-use, time-boxed download link** — an HMAC-signed
  token valid for 300 seconds that is refused on any second presentation.
  Presenting a spent link returns `STATE_CONFLICT`, deliberately distinct from
  the `INVALID_REQUEST` an expired one gives, so a stale click and a reused link
  are told apart. Re-issuing a link is a click and does not re-run the export.

---

## 4. The filter model

### 4.1 Three filter dimensions

| Filter | Values | Default |
| :--- | :--- | :--- |
| Academic Year | All years / a specific year | **All years** (except §4.2) |
| Grade | All grades / a specific grade | **All grades** |
| Class | All classes / a specific class | **All classes** |

**Every filter defaults to "All …"**, never to a specific value. Defaulting to a
specific value means a user who touches no filter receives data that has been
quietly trimmed.

### 4.2 The single exception: Student Completion

Student Completion's Academic Year filter **defaults to the current year**,
because its Annual Required Hours and Completion Rate are by definition measures
**within one academic year**. A completion rate across all years has no meaning —
the denominator does not exist.

### 4.3 Filters and data scope are different things

A filter is the user **narrowing voluntarily**. Scope is the system **imposing a
ceiling**.

A teacher bound to Grade 9 who sets the Grade filter to "All grades" still
receives Grade 9 only. A filter can never widen scope — only narrow within it.

**A teacher's Grade filter lists only their bound grades**, not grades they
cannot see. Offering an option that returns nothing when selected just reads as a
broken system.

### 4.4 Exports always scope by the **student**

Grade is denormalised onto service records, cases and proposals, and on those
screens the system's scope filter reads **the row's own grade snapshot** rather
than the student's live grade (`10-RolesAndPermissions.md` §7.2.1).

**Exports do not inherit that filtering.** An export is a report *about
students*:

1. first resolve the **set of students** from the data scope — a teacher gets the
   current students of their bound grades, an SDC Admin gets all;
2. then take those students' data for the selected academic year, **regardless of
   which grade those records were filed under at the time**.

If snapshot filtering were inherited, a student who has just come up into grade 9
would have their grade-8 hours vanish from a Grade 9 teacher's export while the
student's own row remained in the table — the student present, their data absent,
in the same file. That is not a narrower scope; it is a self-contradicting report.

**Record-subject** operational screens such as the review queue are unaffected
and keep snapshot filtering: there, "what happened inside my grade" is the
correct reading.

---

## 5. Academic years, grades and graduated students

### 5.1 Grade is derived per year, not looked up

`student.grade_id` is a **present-tense** field: it records which grade a student
is in today, and the database keeps no historical roster. So "which grade was
this student in during 2023-2024" cannot be queried directly.

The derivation rests on the stage model (`13-AnnualRollover.md` §2): the school
runs **two stages — grades 6-7-8 (three years) and grades 9-10-11-12 (four
years)** — and students enter at grade 6 or at grade 9.

The entry grade is itself derived, and is always 6 or 9:

```
entry grade   = current grade − (current year − enrolment year)
grade in Y    = entry grade + (Y − enrolment year)
```

For a student entering stage 1 in 2026-2027:

| Selected academic year | Grade column shows |
| :--- | :--- |
| 2026-2027 | Grade 6 |
| 2027-2028 | Grade 7 |
| 2028-2029 | Grade 8 |
| 2029-2030 onward | `Graduated (2029)` |

The same rule answers "was this student even here that year":

```
enrolled in the year starting Y  ⇔  enrolment year ≤ Y ≤ graduation year − 1
```

Students not enrolled in the selected year **do not appear in that year's
export**.

### 5.2 One person may be two student records

Grade 8 to grade 9 is **graduation plus fresh intake**, not promotion
(`13-AnnualRollover.md` §2.1): the student number is reassigned, and time coins
and service hours start from zero.

So **no export will ever show one person's continuous history from grade 6 to
grade 12** — that is two student records with two student numbers, one per stage.
This is not a defect but a direct consequence of that decision: migrating records
across the stage boundary is unreliable because student numbers change, classes
are renamed and some students leave, so the system deliberately does not attempt
it.

Reconstructing one person's whole trajectory means a human comparing the two
records by name and year; the system does not link them.

### 5.2.1 The limits of this derivation

The derivation assumes **no repeated or skipped years**, because the system
models neither. Where a student does repeat, their historical grades shift by
one. `13-AnnualRollover.md` §5's consistency check reports such students as
inconsistent, and is the only signal that will surface them.

### 5.3 When Academic Year is "All years"

There is no single year to derive against, so the **Grade column shows the
present grade**, and graduated students show `Graduated (YYYY)`.

### 5.4 The `GRADUATED` status is written by the annual process

`StudentStatus` contains `GRADUATED`, and the annual process defined in
`13-AnnualRollover.md` is the only thing that writes it: on the day a school year
turns over, grade 8 and grade 12 students are set to `GRADUATED` and their login
accounts are deactivated at the same time.

"Graduated" in an export is nevertheless **always derived from the graduation
year** (§5.1), never read from the status column. That is deliberate, and it is
not redundancy: the derivation was correct before the annual process existed, and
stays correct if a year's run is ever missed or is still pending on the day
someone runs the export. Reading the status column gives the wrong answer in
every one of those cases.

---

## 6. The export catalogue

Seven types. Each gives its title, card text, required permission, filters and
columns.

**The card text is an explanation, not a label.** A one-line description tells
someone what an export is called, which they could already guess from the title.
What they actually need is enough to decide *without downloading it* whether this
is the file they want — and to know the one thing about it that would otherwise
be discovered halfway through a spreadsheet.

So each card states three things:

1. **what one row is** — a student, a record, a decision;
2. **the question the file answers**, and where to go instead if that is not the
   question;
3. **the caveat**, where one exists. `Credited Coins` being empty on unapproved
   rows is not a footnote to find later; it is the difference between summing
   that column and getting the school's credited total, or getting nonsense.

These strings are i18n copy in both languages (§2.6), so they are written as
prose for a reader, not as field labels.

> The former `participation` (participation rate by grade) and `serviceHours`
> (monthly hours trend) exports are **retired**: the first is covered, in more
> detail, by Student Completion, and the second is a twelve-point trend line that
> reads better on screen than in a CSV. Their permission rows are kept and marked
> retired per `10-RolesAndPermissions.md` §5.2.

---

### 6.1 Student List (`studentList`)

- **Title**: Student List
- **Card text**: One row per student on the roster. Identity and placement only
  — number, name, grade, class, status, and the years they entered and are due
  to leave. It carries no service or time-coin figures: for those, use Student
  Completion. The Grade column is the grade for the academic year you select, so
  a past year shows where that student was *then*, not where they are now.
- **Permission**: `exports.studentList`
- **Filters**: Academic Year, Grade, Class

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | Row number |
| 2 | `Student Number` | |
| 3 | `Student Name` | |
| 4 | `Grade` | Derived for the selected year (§5) |
| 5 | `Class` | Class code, e.g. `9A`, `9-Mars` |
| 6 | `Status` | `Active` / `Inactive` / `Graduated` |
| 7 | `Enrollment Academic Year` | e.g. `2021-2022` |
| 8 | `Graduation Year` | |
| 9 | `Joined At` | |

---

### 6.2 Service Records (`serviceRecords`)

- **Title**: Service Records
- **Card text**: One row per submitted service record, in every review state —
  approved, awaiting review, returned and rejected. **This is a record of
  submissions, not of credit.** `Credited Coins` is filled only on approved
  rows, so summing that column does not give the school's credited total; filter
  to `Approved` first. Drafts are excluded: an unsubmitted draft is the student's
  private working state, not a claim made to the school.
- **Permission**: `exports.serviceRecords`
- **Filters**: Academic Year, Grade, Class

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Student Number` | |
| 3 | `Student Name` | |
| 4 | `Grade` | Derived for the selected year |
| 5 | `Class` | |
| 6 | `Service Date` | |
| 7 | `Service Category` | |
| 8 | `Project / Organisation` | Project title for project-sourced records; organisation name for self-initiated ones |
| 9 | `Duration Minutes` | |
| 10 | `Credited Coins` | **Only approved rows have a value; otherwise an empty cell** |
| 11 | `Status` | `Approved` / `Pending Review` / `Returned` / `Rejected` |

**Status mapping, and what happens to drafts:**

| System status | Exported as |
| :--- | :--- |
| `APPROVED` | `Approved` |
| `SUBMITTED`, `UNDER_REVIEW` | `Pending Review` |
| `RETURNED` | `Returned` |
| `REJECTED` | `Rejected` |
| `DRAFT` | **Not exported** |

A draft is a student's unsubmitted private working state, not a claim made to the
school, so it belongs in no export.

> **Note**: this export covers every submitted status, so it is a record of
> submissions rather than of credits. `Credited Coins` therefore necessarily
> contains empty cells — correct, since an unapproved record credits nothing, but
> it means the column must not be summed as "the school's total credit". For a
> credit figure, filter to `Status = Approved` first.

---

### 6.3 Student Completion (`studentCompletion`)

- **Title**: Student Completion
- **Card text**: One row per student for a **single academic year**: hours
  approved, coins credited, the annual requirement for their stage, how many
  hours remain, and the completion rate. Always one year — the requirement is an
  annual one, so a rate across all years has no denominator and the filter does
  not offer it. `Gap Hours` is the actionable column: "six hours short" is more
  use to a form tutor than "67% complete".
- **Permission**: `exports.studentCompletion`
- **Filters**: Academic Year (**defaults to the current year**, §4.2), Grade, Class

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Student Number` | |
| 3 | `Student Name` | |
| 4 | `Grade` | |
| 5 | `Class` | |
| 6 | `Projects Participated` | Projects with at least one approved record that year |
| 7 | `Approved Service Hours` | Approved hours within the year |
| 8 | `Credited Coins` | Coins credited within the year |
| 9 | `Annual Required Hours` | The band's requirement (junior 25h, senior 75h) |
| 10 | `Gap Hours` | `max(0, required − approved)` |
| 11 | `Completion Rate` | `approved / required`, two decimal places |

> Columns 7 and 8 hold the same number under the current rule (one hour is one
> coin), but they are **not redundant**: the conversion rate belongs to the rule
> version, and each record converts using the rule in effect on its own service
> date. If a rule with a different rate is ever published, the two columns
> diverge. `Gap Hours` is the actionable figure for a teacher — "six hours short"
> is more directly useful than "67% complete".

---

### 6.4 Projects Report (`projects`)

- **Title**: Projects Report
- **Card text**: One row per project that ran in the selected year — who ran it,
  with which partner, when, for how many places, and what was actually delivered
  against them. Use it to see which projects convert registrations into service.
  `Approval Rate` is approved ÷ submitted **records**, so it measures how well
  service on that project was documented, not how many students turned up; a low
  rate usually means evidence problems rather than absence.
- **Permission**: `exports.projects`
- **Filters**: Academic Year, Grade (intersection of the project's eligible
  grades and the user's bound grades)

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Project Name` | |
| 3 | `Project Summary` | One-line summary |
| 4 | `Service Category` | |
| 5 | `Partner Organisation` | |
| 6 | `Location` | |
| 7 | `Owner` | |
| 8 | `Eligible Grades` | Comma-separated |
| 9 | `Status` | |
| 10 | `Start Date` | |
| 11 | `End Date` | |
| 12 | `Recognized Minutes` | The service duration the project recognises |
| 13 | `Capacity` | |
| 14 | `Registrations` | Confirmed registrations |
| 15 | `Participants` | Students with at least one approved record |
| 16 | `Participation Rate` | `Participants / Capacity` |
| 17 | `Approved Service Hours` | |
| 18 | `Approval Rate` | `approved records / submitted records` |

> **Note**: the file carries the one-line `Project Summary` and not the project's
> full description. A paragraph of prose wrecks the row layout in a CSV, and it is
> rarely why anyone opens this file.
>
> `Recognized Minutes` is named for what it is — the service duration the project
> recognises — because a column called "duration" sitting beside the start and end
> dates gives a reader no way to tell whether it means the calendar span or the
> credited hours.
>
> `Approval Rate` is **approved ÷ submitted records**. That measures how well
> service on this project was documented, and is not distorted by a capacity that
> was set optimistically.

---

### 6.5 Student Participation (`studentParticipation`)

- **Title**: Student Participation
- **Card text**: One row per student, naming the projects they registered for
  and the projects they actually produced approved service on. **The gap between
  those two counts is the point of the file** — a student with four registrations
  and one participation signed up and did not deliver, which is visible here and
  nowhere else. "Participated" means at least one approved record for that
  project, not an approved registration.
- **Permission**: `exports.studentParticipation`
- **Filters**: Academic Year, Grade, Class

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Student Number` | |
| 3 | `Student Name` | |
| 4 | `Grade` | |
| 5 | `Class` | |
| 6 | `Registered Projects` | Confirmed registrations |
| 7 | `Registered Project Names` | Comma-separated |
| 8 | `Participated Projects` | Projects with at least one approved record |
| 9 | `Participated Project Names` | Comma-separated |
| 10 | `Approved Service Hours` | |
| 11 | `Credited Coins` | |

> **Note**: "participated" means **at least one approved service record** for that
> project — never an approved *registration*. The two readings are easy to
> conflate and give different numbers, so the column is named `Participated`
> rather than "approved", which could mean either.
>
> The gap between registrations and participation is what makes this report worth
> running: students who signed up and never delivered.

---

### 6.6 Review Efficiency (`reviewEfficiency`)

- **Title**: Review Efficiency
- **Card text**: One row per reviewer: how many decisions they made, how long
  they took on average, and how those decisions split, given as **both counts and
  rates**. Read the counts first — one review, approved, is an approval rate of
  100.00%, which sits in the column looking much like 400 reviews at 98%. This is
  peer performance data and is available to the Student Development Centre only.
- **Permission**: `exports.reviewEfficiency` (**SDC Admin only**)
- **Filters**: Academic Year, Grade

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Reviewer Name` | |
| 3 | `Reviewer Role` | |
| 4 | `Bound Grades` | Comma-separated |
| 5 | `Total Reviews` | |
| 6 | `Approved` | Count |
| 7 | `Rejected` | Count |
| 8 | `Returned` | Count |
| 9 | `Average Review Hours` | Submission to decision |
| 10 | `Approval Rate` | |
| 11 | `Rejection Rate` | |
| 12 | `Return Rate` | |

> **Note**: the three **absolute counts** are given alongside the three rates,
> because **rates mislead at low volume** — one review, approved, is an approval
> rate of 100.00%, which sits in the column looking much like 400 reviews at 98%.
> A reader must be able to see the denominator.
>
> This is peer performance material, and teachers do not hold the permission
> (`10-RolesAndPermissions.md` §5.3).

---

### 6.7 Review History (`reviewHistory`)

- **Title**: Review History
- **Card text**: One row per review decision — who decided, on whose record,
  when, what they decided, and the reason and comment they gave, including for
  returns and rejections. Use it to review how a decision was reached, or to give
  a reviewer their own record. **A teacher sees only their own decisions**; the
  Reviewer filter is fixed to them and cannot select a colleague.
- **Permission**: `exports.reviewHistory`
- **Filters**: Academic Year, Reviewer
  - **Teacher: the Reviewer filter is pinned to themselves** and cannot select a
    colleague.
  - SDC Admin: any reviewer, or all.

| # | Column | Notes |
| ---: | :--- | :--- |
| 1 | `No.` | |
| 2 | `Reviewer Name` | |
| 3 | `Reviewer Role` | |
| 4 | `Bound Grades` | |
| 5 | `Student Number` | |
| 6 | `Student Name` | |
| 7 | `Service Date` | Of the record reviewed |
| 8 | `Project / Organisation` | |
| 9 | `Decision` | `Approved` / `Rejected` / `Returned` |
| 10 | `Decided At` | |
| 11 | `Reason Code` | Stable code |
| 12 | `Comment` | The reviewer's own wording |
| 13 | `Duration Minutes` | |
| 14 | `Credited Coins` | **Only approved rows have a value** |

> **Note**: the file carries no project summary. In a file whose rows are
> individual decisions, a project blurb is repeated, wide noise.
>
> `Decided At` is present because a "history" without a timestamp cannot be
> sorted and cannot answer when something was handled.

---

## 7. Checklist for adding an export type

- [ ] The type key is stable, lowerCamelCase, and **not** a translation of the UI title
- [ ] A permission row is added to `10-RolesAndPermissions.md` §5.3 with its role assignment
- [ ] Title and description exist in Chinese and English; column names are fixed English
- [ ] First column is `No.`; every student name is paired with a student number
- [ ] No database identifiers anywhere
- [ ] Absent values are empty cells
- [ ] Coins in coins, rates to two decimal places, dates `YYYY-MM-DD`
- [ ] Scope is resolved through `ScopeDescriptor`; filters may only narrow within it
- [ ] If it carries student data, confirm it is invisible to System Admin

---

## 8. Deliberately not built

- **No user-chosen columns.** Letting each user pick columns means every file has
  to explain itself from its own contents, and a CSV carries no such explanation.
- **No scheduled exports.** An export is an act of taking data out, and someone
  should be answerable for it at the moment it happens.
- **No long-lived server-side copies.** A produced file is deleted 7 days after
  its job finishes and its job row's `resultRef` is cleared, so a link cannot
  outlive the file it points at. This matters more than it looks: §6.1 revokes
  System Admin's export permission to close a student-data exit, and a permission
  is not the only path to a file sitting on the server they administer.
- **No contact details in any export.** Student email and guardian information
  belong in no export — data minimisation matters most at the exit.
