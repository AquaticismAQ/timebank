# 137 — Publishing a project tells nobody

**Status:** **Done and deployed** 2026-09-20. Aquaticism's request: when a project is
published, every student and teacher of the corresponding grade should be
notified.

## 1. What was missing

Publishing changed a status and wrote an audit row. Students found out by
happening to open the Project Center. The system already notified people about
service-record decisions, proposal decisions and case decisions — every one of
them addressed to a single person who had done something. **This is the first
notification that is news rather than a reply**, and the first that fans out.

## 2. Who hears about it, and who deliberately does not

| | Notified |
| :--- | :--- |
| Active students in one of the project's `eligibleGradeIds` | **yes** |
| Staff assigned to one of those grades | **yes** |
| `admin` / `sysadmin` | **no** |
| Everyone else | no |

**Administrators are left out on purpose.** They are school-wide, so "the
corresponding grade" is every grade, and a message on every publish is noise
rather than news — the thing that trains people to ignore the bell. The project's
owner is not special-cased either: if they teach one of the grades they hear
about it like anyone else, and if they do not, they already know.

A teacher assigned to two of the project's grades is still one person, so
recipients are a set.

The grade assignment is read from the `serviceRecords` scope namespace, where a
teacher's grades actually live — the same borrowing the dashboard, the reports
and the project edit rule all make. One set of grades per teacher, not one per
resource.

## 3. Edges worth stating

- **A project naming no eligible grade announces to nobody.** It is visible to
  every student (issue 129 §2), but telling the whole school on a technicality
  is not what was asked for.
- **A student with no account is skipped silently** — `POST /students` still
  provisions no login, so roster rows can exist without one.
- **A pending-activation student is notified**, so it is waiting at first login
  rather than lost.
- **No de-duplication against a previous publish**, because a project can only
  be published once: the state machine allows `DRAFT → PUBLISHED` and nothing
  returns to `DRAFT`.

## 4. Where it runs, and the sizing

Inside the publish transaction, after the flush. After, so a failed publish
cannot leave notifications about a project nobody can see; inside, so a failed
announcement rolls the publish back rather than half-announcing it.

It writes one row per recipient — a few hundred for a whole-school project.
Deliberate: the alternative is an outbox nothing else in this codebase has, and
this school has fewer than two hundred students. Worth revisiting if a publish
ever fans out to thousands.

## 5. Verification

On the deployed server, publishing one project open to `grade_6` and watching
four different accounts' unread counts:

```
student (grade 6)      61 → 62   ✓
teacher001 (grade_6)    8 →  9   ✓
teacher005 (grades 10-12) 0 → 0  ✓  ← the negative control
admin001 (school-wide)  8 →  8   ✓  ← deliberately not notified
```

And what the student actually reads:

```
有新项目可以报名
本年级有新的志愿服务项目开放报名，去项目中心看看吧。
```

The two zero-deltas are the point: a broadcast to everybody would have passed
the first two rows and failed the design. The probe project was cancelled
afterwards.

Integration tests cover both the positive case and a project with no eligible
grades notifying nobody, red-checked by removing the announcement — exactly the
first test fails.

## 6. What it must not do

- **Must not notify administrators.** §2.
- **Must not announce a project with no eligible grades.** §3.
- **Must not notify before the publish is flushed.** §4.
- **Must not introduce a second notion of "which grades does this account
  cover".** §2 — one namespace, reused.
