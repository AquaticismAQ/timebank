# 114 — Graduates have nowhere to be read, and will collide with current students

**Status:** Built 2026-09-20 on Aquaticism's request. Filed as work started.
**Reported as:** after promotion, grade 8 and grade 12 are treated as graduated
and their data is kept for a year, but there is nowhere to see them — perhaps two
options on Student Management's grade filter.

## 1. Two problems, one screen

### 1.1 Graduates are unreachable

`14-AnnualRollover.md` §3.1: graduation sets `student.status = GRADUATED`,
deactivates the login, and **retains** service records, the time-coin account and
review history, "queryable by staff". Nothing in the UI queries them. Student
Management offers grade, class and a progress bucket; none of them is status, and
`Student.status` is carried on every row but rendered nowhere.

### 1.2 Worse: graduates collide with current students

Graduation **leaves `grade_id` unchanged** — deliberately, since it records how
far the student got, not a grade 13 that does not exist. So from the first
rollover onward, `filter[gradeId]=grade_8` returns **two cohorts at once**: this
year's grade 8 and last year's graduates.

A teacher counting their grade 8 gets roughly double, with nothing on screen
saying why. That is a wrong answer rather than extra context, and it appears
whether or not anything is added for §1.1.

## 2. What it should do

### 2.1 The default is the roster

**`GET /students` excludes `GRADUATED` unless `filter[status]` names it**
(Aquaticism's decision). This narrows on an absent parameter where `GET /projects`
deliberately does not (issue 112 §3.1). The two are not inconsistent: on
`/projects`, past-year rows were harmless history a staff member could ignore;
here they land inside a single count and make it wrong.

Naming the status still reaches every value, `GRADUATED` included — hidden by
default, never unreachable.

The default also fixes §1.2 for the other two callers of the endpoint without
either of them asking: `ActivationSlips` must not offer to issue a credential
slip to a deactivated account, and `AccountManagement` lists live balances.

### 2.2 One dropdown, not two

The graduate options join the existing grade filter rather than arriving as a
fourth `<select>`. That row already carries search plus three filters, and
"which grade" and "which graduating cohort" are the same question asked about
different people. A grade option sends `filter[gradeId]`; a graduate option
sends `filter[gradeId]` **and** `filter[status]=GRADUATED`.

They are grouped under a heading rather than appended, so "Grade 8" and
"Grade 8 graduates" cannot read as two spellings of one thing.

### 2.3 The exit grades are published, not hardcoded

**`GET /lifecycle/config`** (new, `APIContract.md` §4.18) returns
`exitGradeLevels` and `academicYearStart`. The frontend derives its graduate
options from the former.

A literal `[8, 12]` in a component would be a second source of truth for a fact
only `AnnualRolloverRepository.EXIT_LEVELS` really owns — nothing would keep the
two in step, and the screen would be wrong on the day a school's stage structure
differs. If the config cannot be loaded the graduate options are **absent**
rather than guessed: a guess that disagrees with the rollover is worse than a
missing feature.

### 2.4 The retention deadline is on the screen

A cohort is purged on 31 August of the year after `graduation_year` — about
twelve months (§6). A filter option alone would leave that undiscoverable until
a transcript request came back empty, so selecting a graduate cohort shows the
purge date for the cohorts actually listed, derived from their `graduationYear`
rather than from today.

### 2.5 Graduate rows say what they are

- The progress verdict (On Track / Behind / Critical) is replaced by a neutral
  **Graduated {year}** chip. A student who has left has no annual requirement to
  be on track for, and the year is what tells two graduated cohorts apart.
- **Edit** and **Remind** are gone for those rows: the rollover deactivates the
  login, so a reminder reaches nobody and editing a departed student's placement
  is editing history.
- **Records** stays, and is the whole point — reading the service history before
  it is purged.

Read off the row's own `status`, not off the active filter, so a row says what it
is however it was reached.

## 3. The rollover date in School Info

Asked for in the same breath, and it shares the new endpoint. System Settings →
School Info gains an **Academic Year Start** row showing `academicYearStart`.

It is **read-only, and deliberately has no edit control** even for a sysadmin who
can edit every other row in that card: the value lives in the deployment's
environment (`TIMEBANK_ACADEMIC_YEAR_START`), not the database, so a field here
would be a control that saves nothing. A hint line says where it does live. The
alternative — printing "September 1" — stops being true the moment a deployment
configures something else, which is exactly why the value became configurable.

## 4. What already existed

| Piece | Where |
| :--- | :--- |
| `filter[status]` on `GET /students` | `StudentServiceImpl.list`, already wired to `StudentStatus` |
| `status` + `graduationYear` on every list row | `StudentListItemDto` |
| `'graduated'` in the frontend student type | `api/types.ts` |
| The exit levels | `AnnualRolloverRepository.EXIT_LEVELS` |
| The turnover date | `AcademicYearCalendar.boundary()` |

So the backend work is one default and one small read endpoint, not a new
capability. `graduationYear` was missing from the frontend `Student` type only.

## 5. Verification

`GraduatedStudentVisibilityIT` puts a graduate **and a current student in the
same grade** — the only arrangement that can tell the new default from the old
behaviour, since a graduate in a grade of its own is filtered out either way. It
covers the default, the grade collision, reaching graduates by name, that an
explicit `ACTIVE` still means ACTIVE, and the config endpoint's contents.

Red-checked: reverting the default to "absent means everyone" fails exactly
`anAbsentStatusFilterHidesGraduates` and `filteringToTheGradeDoesNotMixTheTwoCohorts`,
while the three describing unchanged behaviour still pass.

`mvn test` 353, 0 failures. `StudentPrivacyIT` has 2 failures both before and
after this change, measured at `2b2e200` in a clean worktree — its fixture
asserts lowercase `stu_privacy_1` while student numbers now come back uppercased.
Pre-existing and unrelated; `Docs/issues/109` covers the red ITs on `main`.

## 6. What it must not do

- **Must not make graduates unreachable.** Hidden by default is the point;
  removed from the API is not.
- **Must not hardcode the exit grades or the turnover date** in a component.
- **Must not offer to edit or notify a graduate.** The account is deactivated.
- **Must not present the rollover date as editable.** It is not stored.
- **Must not mix cohorts in one grade count**, which is the defect this exists
  to prevent.

## 7. Follow-up: grade selectors split by stage (2026-09-20)

Aquaticism, same day: the seven grades should lay out in two lines by segment, in
every dialog that picks a grade, driven by the same `EXIT_LEVELS` this issue
already publishes.

Seven grades in one flat run read as an undifferentiated list when they are
really two stages a student cannot cross — a grade-8 bond and a grade-9 bond
mean different cohorts, and the control should say so before anyone clicks.

`lib/gradeStages.ts` does the split: a stage is everything up to and including
its exit level, which is what an exit level means. With `[8, 12]` and grades
6…12 that is `[[6,7,8], [9,10,11,12]]`. Derived, never written down — a literal
"6-8 / 9-12" would be the same second-source-of-truth mistake §2.3 exists to
avoid.

Two shapes, because a dropdown cannot be laid out in two lines:

- **`GradePicker`** (the multi-select chip grid) renders one grid per stage,
  stacked. Each stage is its own grid, so on a phone a stage may wrap within
  itself but never runs into the next one; at `sm:` and up, four columns put a
  6–8 / 9–12 school on exactly two lines.
- **`GradeOptions`** (new, same file) renders one `<optgroup>` per stage for
  single-select `<select>`s. The group label is the bare range — "6–8" — not a
  stage name: the API publishes boundaries, and inventing "Middle School" here
  would put back the hardcoded structure this removes.

Both read the levels from the shared dictionary cache rather than from a prop,
deliberately: a call site that forgot to pass them would silently render the old
flat list. Both fall back to ungrouped when the config did not load.

**Verified live**, in the browser, on each distinct host:

| Where | Result |
| :--- | :--- |
| Create User dialog, teacher grade bonding | two rows — 六/七/八, then 九/十/十一/十二 |
| Project create form, eligible grades | same two rows |
| Student edit dialog, grade `<select>` | `optgroup` "6–8" (3) and "9–12" (4) |
| Data Import → activation slips, grade `<select>` | same two groups, after "All grades" |

The project **edit** form is the third `GradePicker` call site and was not
opened by hand: it renders the same component with the same props as the create
form above, and the split is computed inside the component, so there is no
per-call-site path that could differ. Stated rather than glossed.

**Filter dropdowns were left ungrouped** — Student Management, Audit Center,
Case Review, Proposal Review, Time Coin Accounts, Project Management. They are
not dialogs, which is what was asked for; and Student Management's already
carries the graduate `optgroup` from §2.2, where a second grouping axis in one
control would compete with it. Worth revisiting together rather than piecemeal.
