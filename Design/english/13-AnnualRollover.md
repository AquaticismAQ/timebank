# Annual Rollover and Graduation Design

> This document describes the once-a-year promotion and graduation process, and
> how the two school stages join.
> Keep `Design/chinese/13-AnnualRollover.md` in sync when editing this file —
> the Chinese version is the source of truth.
> Related: roles and permissions in `10-RolesAndPermissions.md`; imports in
> `11-DataImport.md`; the export grade derivation depends on the stage model
> defined here, `12-DataExport.md` §5.

---

## 1. The question this design answers

**No code in this system ever moves a student up a grade.** `student.grade_id` is
never rewritten after the roster import, and the `GRADUATED` value in
`StudentStatus` is written by no path at all.

This is not a gap that can keep being deferred, because other designs already
depend on it being filled:

- `12-DataExport.md` §5 derives a student's grade in a given year from their
  enrolment year. That derivation **only matches reality if something actually
  advances `grade_id` each year** — otherwise the derived grade and the stored
  grade diverge a little more annually, and both appear in the same export.
- The time-coin annual requirement is measured per academic year. If the year
  turns over and the grade does not, every student stays on the same annual
  requirement forever.
- Retention purging keys on graduation year, and nothing marks a student as
  having graduated.

---

## 2. Two stages, not one six-year line

The school runs grades 6 to 12, organised as **two stages**:

| Stage | Grades | Length |
| :--- | :--- | ---: |
| Stage 1 | 6, 7, 8 | 3 years |
| Stage 2 | 9, 10, 11, 12 | 4 years |

> **Implementation prerequisite**: the `grade` table currently holds `grade_6`
> through `grade_11` and has **no `grade_12`**. This design describes grades 6-12,
> so building it means adding that grade row first, and updating the
> `integration-test` fixture, whose `graduationYearFor` still assumes a
> six-year run ending at grade 11. The rule version's junior/senior cutoff at
> grade 8 already matches the two stages and needs no change.

### 2.1 Grade 8 to grade 9 is not a promotion — it is graduation plus fresh intake

This is the most consequential decision here.

At the end of grade 8 a student **graduates** from stage 1. Grade 9 students are
**re-imported as new entrants** by the Student Development Centre, with newly
assigned student numbers, and their time coins and service hours **start from
zero**.

**Why records are not migrated across**: migration is unreliable in practice —

- student numbers change;
- class naming may be redone entirely for stage 2;
- some students leave the school after grade 8.

With all three true at once, attaching old records to a new identity requires a
manual reconciliation, and a reconciliation error credits one student's service
hours to a different student. **Rather than automate something that will get it
wrong, this design deliberately does not do it.**

### 2.2 The cost, and its boundary

The cost is real: a student who attends from grade 6 to grade 12 exists as **two
student records with two student numbers**, and their stage-1 hours do not count
toward the stage-2 cumulative requirement.

This does not conflict with the rule design: the annual and cumulative
requirements are already **defined per stage** (junior 25h/year and 75h
cumulative; senior 75h/year and 300h cumulative). The stage-2 cumulative
requirement measures stage-2 service, and starting from zero is exactly what it
means.

---

## 3. Runs automatically on 1 September

The process **runs by itself when the new academic year begins**, so no one has
to remember to press anything, and it produces a report.

> **Implementation note**: the scheduled job checks daily rather than pinning a
> cron to 1 September. A once-a-year trigger has exactly one chance to fire, and
> if the server happens to be restarting that night nobody is promoted and
> nothing says so — the next signal would be a teacher noticing their class list
> is a year stale. The observable behaviour is still "it happens on 1
> September"; the difference is that a missed night is recovered the following
> morning instead of being silently lost.

```
① Determine the target year (the academic year 1 September falls in)
        │
        ▼
② Idempotency check: has this year already been processed?
        │  Already done → write nothing, report why, stop
        ▼
③ Create the target year's class rows, carrying the suffixes forward (§4)
        │
        ▼
④ For every ACTIVE student:
        │   6 → 7      promote, class carried forward
        │   7 → 8      promote, class carried forward
        │   8          **graduate** (end of stage 1)
        │   9 → 10     promote, class carried forward
        │  10 → 11     promote, class carried forward
        │  11 → 12     promote, class carried forward
        │  12          **graduate** (end of stage 2)
        ▼
⑤ Mark the target year as current (is_current)
        ▼
⑥ Report: promoted, graduated, skipped and why
```

### 3.1 What graduation does

- `student.status` becomes `GRADUATED` — **the first place in this system that
  ever writes that value**.
- `student.grade_id` is **left unchanged**, still the grade they finished in. It
  records how far the student got, not a grade 13 that does not exist.
- The student's **login account is deactivated**. One person should hold one
  usable identity at a time (the same position `12-DataExport.md` takes on
  graduates).
- Service records, the time-coin account and review history are **all retained**
  and stay queryable by staff.

### 3.2 Where grades 6 and 9 come from

Neither is produced by this process. They are the two stage entry points and are
established by the Student Development Centre through a **roster import**
(`11-DataImport.md` §2.1).

So after the 1 September run, grades 6 and 9 are empty until the import lands.
That is correct, not a fault — and the report must say "grade 6: 0 students,
grade 9: 0 students, awaiting roster import" rather than leaving someone to
discover it.

### 3.3 Constraints that follow from running unattended

Nobody is watching, so:

- **The whole run is a single transaction.** "Half promoted" does not exist.
- **Idempotent, locked per academic year.** A repeat trigger for the same year
  writes nothing a second time.
- **A failure writes nothing partially and produces a failure report.** Failure
  is not silent.
- **The process rewrites "current state" only, never historical events** — what
  follows and what does not is §3.4.

### 3.4 Which grade snapshots follow, and which do not

Grade and class **are not stored only on the student row**. They are denormalised
onto six tables, and scope filtering reads **each table's own column**, not the
student's live grade (`ServiceRecordScopeFields`, `CaseScopeFields`,
`AccountScopeFields` and `ProposalScopeFields` all return `root.get("gradeId")`).

So promotion has to answer a question first: do those snapshots follow? The
answer splits along **current state** versus **historical event**:

| Table | Nature | At promotion |
| :--- | :--- | :--- |
| `student` | current state | **rewritten** |
| `account` | current state (one row per student, holds the balance) | **rewritten** |
| `service_record` | historical event | left alone |
| `case_record` | historical event | left alone |
| `event_proposal` | historical event | left alone |
| `class` | structure, per academic year | new rows for the year, §4 |
| `grade_*_summary` | already keyed by (grade, year) | left alone |

**`account` must follow, and this is the easiest one to miss.** A time-coin
account is not a historical record; it is a continuing piece of current state. If
its `grade_id` stays on the old grade, then the moment promotion completes a
teacher loses access to their own students' accounts — the data is not gone, it
has simply fallen outside their scope, and what it looks like from the outside is
"the student's account has disappeared".

**Historical events do not follow, because their grade is the fact they record.**
A service record created in grade 8 should carry grade 8; rewriting it to grade 9
falsifies what was true when it happened, and would make previous years' by-grade
figures change retroactively.

---

## 4. How classes carry forward

Class rows are stored per academic year (`class.academic_year_id`), so a new year
needs new class rows.

**Within a stage, the class suffix carries forward:**

```
9A      →  10A
9-Mars  →  10-Mars
```

The grade prefix advances; the suffix (`A`, `B`, `Mars`, `Luna`) is preserved
exactly. The process creates the corresponding class rows for the target year and
moves each student into the same-suffixed new class.

**Nothing carries across a stage boundary.** Stage 2 may name its classes
completely differently, and grade 9 is a fresh intake whose classes come from the
roster file.

### 4.1 The assumption this rests on

Carrying the suffix forward assumes **classes are not reshuffled mid-stage**. If
the school does reshuffle, the class this process assigns is wrong — and wrong
silently. That is accepted on the stated practice: classes stay stable within a
stage, and are renamed at the start of stage 2.

If mid-stage reshuffling is ever genuinely needed, the right answer is to
**overwrite class assignment with a roster import**, not to make this process
guess.

---

## 5. Relationship to the grade derivation

`12-DataExport.md` §5 derives a student's grade in a given year from their
enrolment year. This process is the precondition for that derivation holding:
**the stored `grade_id` only matches the derived grade if something advances it
once a year.**

So the two check each other:

```
derived grade (current year) == stored grade_id
```

An inequality has only two causes — a year's run was missed, or that student
repeated or skipped. The report should include this consistency check; it is the
only signal that will ever reveal a missed year after the fact.

### 5.1 The entry grade is itself derived

A student may enter at grade 6 or at grade 9, so "the grade they entered at" is
not a constant:

```
entry grade = current grade − (current year − enrolment year)
```

The result is always 6 or 9. A stage-1 student's grade in year Y is
`6 + (Y − enrolment year)`; a stage-2 student's is `9 + (Y − enrolment year)`.

---

## 6. Retention of graduated students

`graduation_year` means **the year the student left their stage**: for a stage-1
student, the year they finished grade 8; for a stage-2 student, the year they
finished grade 12. The roster import must fill it consistently with the stage.

Retention uses the existing policy, with no special rule added: **the existing
cohort purge keys on `graduation_year` and removes a student's data after 31
August of the following year.**

> **A consequence worth knowing**: a stage-1 graduate's service history is purged
> roughly **one year** after they finish grade 8. "Still queryable after
> graduation" therefore holds for about a year, not indefinitely. This follows
> directly from reusing the existing retention policy and has been accepted
> deliberately; if stage-1 history should be kept longer, that is a change to the
> retention policy, not a special case inside this process.

---

## 7. What the report contains

The process is unattended, so the report is the only output and has to explain
itself without help:

| Item | Detail |
| :--- | :--- |
| Target year | e.g. `2027-2028` |
| Outcome | Ran / skipped because already run / failed |
| Promoted | Broken down by grade |
| Graduated | Stage 1 and stage 2 listed separately |
| Account snapshots updated | `account` rows rewritten alongside the grade, §3.4 |
| Skipped, and why | Not active, no class, class suffix unmatched |
| Empty-grade notice | Grades 6 and 9 awaiting roster import |
| Consistency check | Whether §5's derived grade matches the stored grade |

---

## 8. Deliberately not built

- **No repeat or skip handling.** The system does not model either; where it
  genuinely happens, an administrator adjusts that student's grade by hand and
  accepts that §5's consistency check will report them as inconsistent.
- **No cross-stage record migration.** Reasoning in §2.1.
- **No automatic reshuffling of classes.** Suffixes carry forward; anything else
  is the roster import's job.
- **No automatic purging of graduates.** Retention belongs to the existing cohort
  purge. The two stay separate: rollover runs every year and must succeed, while
  purging performs irreversible deletion.
- **No frontend trigger.** This runs on the calendar. It is not a button.
