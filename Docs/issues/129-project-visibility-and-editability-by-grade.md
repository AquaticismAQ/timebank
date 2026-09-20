# 129 — Project visibility and editability by grade

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's
restatement of the original design: teachers see every project in the school and
may view and copy any of them, but may not edit one outside their grades;
students see only their own grade's projects.

## 1. Two questions, not one

Visibility and mutability were being answered by the same code, and both
answers were wrong in opposite directions.

| | Design | Was |
| :--- | :--- | :--- |
| Teacher sees | the whole school | ✅ the whole school |
| Teacher edits | their grades only | ❌ **anything** — `updateDraft` had no such check |
| Student sees | their own grade | ❌ **every published project**, all 75, across six grades |

Measured before the change, on the live server: a Year 6 pupil's Project Center
listed projects for every year group in the school, and `teacher005` — scoped to
grades 10, 11 and 12 — could `PATCH` a Grade 7 project.

## 2. Students

`visibilitySpec` now narrows a student to projects whose `eligibleGradeIds`
contain their grade, reusing the `JSON_CONTAINS` query the grade *filter*
already uses rather than inventing a second definition of "open to a grade".

Two clauses that have to be there:

- **A project they have already applied to stays visible**, whatever its grades
  now say. Eligible grades are editable after the fact, and a student whose
  service record points at a project must be able to open it.
- **A project naming no grade at all is visible to everyone.** It restricts
  nobody; the alternative reading, "open to no one", would hide it from the
  whole school on a technicality. The admin form requires at least one grade, so
  these arrive only through the API and proposal approval — but they are real
  rows and there are seven of them on the test server today.

An unreadable student row narrows the list to nothing rather than widening it:
not knowing someone's grade is not a reason to show them every grade.

## 3. Teachers

`updateDraft` now refuses a project that is not open to any grade the actor is
responsible for, with `403` and a coded `PROJECT_OUT_OF_YOUR_GRADES` so the
client can say so in the reader's language (issue 121's arrangement). The menu
asks the same question and offers **View** instead of **Edit**, so nobody is
invited to fill in a form that will be refused on save.

**Two judgements worth challenging if you disagree:**

- **A teacher may always edit a project they own**, whatever its grades. This is
  not in the reported sentence and has to be there: `createDraft` puts no grade
  restriction on what a teacher may create, so without it a teacher could make a
  project for another year group and then never touch it again. "Not yours to
  change" is the rule; "not yours to have made" is not.
- **A teacher with no grade assignment can edit only their own projects.** Their
  scope resolves to `Self`, and treating that as "everything" would make the
  rule opt-in. It is the safe reading, but it is a real consequence.

The scope namespace is `serviceRecords`, where a teacher's grades are actually
assigned — the same borrowing `FileServiceImpl` and the dashboard already make.
There is one set of grades a teacher is responsible for, not one per resource.

## 4. What this did not change

**`publish` is still unrestricted.** A teacher can publish an out-of-grade draft
they cannot edit, which is incoherent — but the report said *edit*, and
extending the rule to publishing changes behaviour that was not asked about.
Recommended as the next step, and safe to make now that the ownership clause
exists (a teacher publishing their own cross-grade draft keeps working).
`archive`/`cancel` are admin-only permissions teachers never hold, so they are
not part of this question.

## 5. Verification

**Teachers, with the control on the same line** — a guard that refused
everything would be indistinguishable from one that works:

```
teacher005 sees 91 projects across [Grade 6..11]   own grades [grade_10, grade_11, grade_12]
PATCH a Grade 7,8,9 project  -> 403 PROJECT_OUT_OF_YOUR_GRADES
PATCH a Grade 10,11 project  -> 200 ok
```

And in the page, on **non-terminal** projects only — a completed one shows View
for a different reason (issue 119) and could not tell the two rules apart:

```
grade 10, 进行中 => ["报名名单","编辑","复制为草稿"]
grade 7,  草稿   => ["查看","发布"]
```

**Students**, broken down rather than counted — the grade *names* across a
multi-grade project made the raw list look wrong until it was:

```
total 69 (was 75)
  六年级                                60   ← their own grade
  六年级+七年级+八年级+九年级+十年级      1   ← includes their grade
  六年级+七年级+八年级                    1   ← includes their grade
  (no eligible grades)                    7   ← restricts nobody, §2
```

Not one project belonging exclusively to another grade survives.

Six integration tests cover both halves, including an administrator who may edit
any grade and a teacher editing their own out-of-grade project.

**One bug the tests caught:** the first version used `hasFullDataScope()`, which
is `!roles.contains("student")` — it would have exempted **every teacher**, the
entire population this rule is about. `hasSchoolWideVisibility()`
(07-RoleFinetune.md §2.10) is the grade-scoped one, and the guard silently never
fired until a test asked it to.

## 6. What it must not do

- **Must not narrow what staff can see.** §1 — browsing and copying the whole
  school is the design.
- **Must not hide a project a student is enrolled in.** §2.
- **Must not treat "no grades" as "no one".** §2.
- **Must not use `hasFullDataScope()` for a grade rule.** §5.
- **Must not offer Edit where the API will refuse it.** §3.
