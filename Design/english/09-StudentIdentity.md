# Student identity design

> This document covers the two identifiers a student has in the system — their **username**
> and their **student number** — where each comes from and where each stops, and how a school
> prepares the roster CSV before importing it. **First login, activation codes and the
> credential slip are not here**; see `10-StudentActivationFlow.md`.
> When editing this document, keep `Design/chinese/09-StudentIdentity.md` in sync.
> Related: masking conventions and authentication in `08-mlps-level1-security-design-guide.md`
> §4/§8; the import flow in `12-DataImport.md`; the decision record and the alternatives that
> were rejected in `Docs/optimization/10-StudentIdentityAndOnboarding.md`.

---

## 1. What this design answers

A student has two identifiers, and they are **deliberately not the same one**:

| Identifier | What it is | Who creates it |
| :--- | :--- | :--- |
| **Username** `username` | What they type to log in | The system, at import |
| **Student number** `studentNumber` | The roster key, and part of every service-record number | The school, before import |

Making them one identifier is the obvious first idea, and it is explicitly rejected (§2).
Keeping them apart is what buys a property that can be stated in a sentence: **the student
number can be printed on paper, shown on screen and embedded in a record number, because it
cannot be used to log in.**

---

## 2. The username: eight random digits

A student's username looks like `48215577` — a **random** number between `10000000` and
`99999999`, stored as a **string**, **stable for life**. Staff keep `teacher001`-style names.
Both share **one namespace and one unique index**.

### 2.1 Three constraints that get "optimised" away

| Constraint | What breaks without it |
| :--- | :--- |
| **Random, not sequential** | A sequential number leaks enrolment order and cohort size — precisely why the sequential scheme was rejected in §2.2 |
| **No leading zeros** | `00482155` becomes `482155` the moment anyone opens the list in a spreadsheet |
| **Stored as a string** | An integer column brings both problems back and collides in type with `teacher001` |

### 2.2 Three rejected alternatives

- **Sequential (`student001` … `student52834`).** Grows visibly ugly, and leaks enrolment
  order and total size.
- **The student number as the login.** An opaque Crockford string is harder to remember than
  anything name-based, and it couples the roster key to the credential — the sentence in §1
  would stop being true.
- **Derived from the English name (`Alex L.` → `alex3254`).** Genuinely the most memorable
  option, and it survived several rounds before failing on **students with no Latin name**.
  Every fix was worse than the problem: pinyin transliteration needs a surname table, a
  polyphonic-exception table (單 is Shàn not dān, 解 is Xiè, 曾 is Zēng) and a compound-surname
  table (欧阳, 司马, 诸葛), and still guesses wrong sometimes — **printing a username that is
  incorrect about the student's own name is worse than one that never claimed to know it.**
  Assigning one from a word list instead invents an English name the student does not have,
  and the list would have to be gender-neutral, since the system stores no gender.

**The accepted cost is explicit**: eight random digits really is less memorable than
`alex3254` — one word-chunk plus four digits beats four digit-pairs. What it buys is a single
rule: no dictionary, no transliteration, no fallback cohort for the students the scheme
cannot serve, and no child asking why they are called Maya. Digits also type cleanly on a
phone's numeric keypad, leak no name, and resist enumeration far better (10⁸ rather than 10⁴
once an attacker knows a first name).

### 2.3 Login accepts a username and nothing else

There is no "try the username, then the student number, then the staff ID" fallback. One
typed value could match one person's username and a different person's student number, and
getting the precedence wrong logs somebody into the wrong account (`08-...` §8.1).

Comparison is case-insensitive — the database collation already makes it so, which is why
`Alex3254` and `alex3254` could never have coexisted.

---

## 3. The student number: Crockford base32, and not a credential

The student number is generated **by the school, before the data reaches the system**. The
system validates its format and uniqueness; it **does not generate student numbers**.

- **The alphabet deliberately omits I, L, O and U**, so nothing can be misread as 1 or 0 —
  this value is read off a screen by a teacher, printed on a card, and typed back in by hand.
- Eight characters give about 1.1×10¹² values; at 5,000 students the collision probability is
  roughly one in 220,000 (eight hex characters at the same scale would be about one in 340).
- The system enforces uniqueness: a collision fails the import with a clear error and
  **never silently overwrites data**.

### 3.1 One hard requirement: stable for life

**Student numbers must not be regenerated per year or per batch.** A service-record number
looks like `TB-{year}-{grade}-{studentNumber}-{seq}`, so the student number is part of it. If
a student's number changes, every record number already issued, every historical association
and every later incremental import stops matching at once.

This requirement lands on the school — the system cannot check whether a value is the same
one as last year. It is why §5.6's offline tool must be re-runnable with a stable result.

### 3.2 Asterisk-masked student numbers are a bad idea

Something like `******24`: the remaining digits have too small a value space to avoid
collisions at a few hundred students, and an asterisk inside a record number damages both its
uniqueness and its readability.

### 3.3 The student number is not a credential

It is shown in the admin portal, printed on the credential slip, and embedded in service
record numbers. **Even if it leaks, it cannot be used to log in**: logging in needs a username
and a password, and the student number is neither the username (§2.3 rules out the fallback)
nor the password.

---

## 4. Names are masked before they reach the system

The system **does not receive a student's full real name**. The school masks it before import,
per `08-...` §4.1:

| Field | Convention | Example |
| :--- | :--- | :--- |
| `displayName` | Chinese names hide the middle character; English names keep the given name and the surname initial | `Alex L.(李*红)` |

Both halves use **the same masking strength**, so there is no "the Chinese is masked but the
English still identifies the child" case.

**The system does not check masking strength, and could not** — what reaches it is a string.
The convention is executed by the school before import, and §5.6's tool exists to make that
practical, not to move the judgement into the system.

---

## 5. Preparing the roster CSV

### 5.1 What one row is

One row = one student = **one roster record plus one password-less, pending-activation login
account**. That account cannot be logged into until the student redeems their activation code
(`10-StudentActivationFlow.md` §3).

### 5.2 The columns

**The header must match exactly, and in this order** (§5.3, item 1).

| Column | Meaning | Requirements | Example |
| :--- | :--- | :--- | :--- |
| `studentNumber` | The school's stable student number | Crockford base32 (digits and letters, excluding I, L, O, U), at most 32 characters; unique within the file and not already in the system | `HKTZ4HKV` |
| `displayName` | The masked name | Required, at most 100 characters | `Alex L.(李*红)` |
| `gradeId` | Grade identifier | Must already exist in the system | `grade_6` |
| `classId` | Class identifier | Must exist, **and belong to the grade in the same row** | `class_6A` |
| `enrollmentAcademicYearId` | Enrolment academic year | Must already exist in the system | `ay_2025_2026` |
| `graduationYear` | Expected graduation year | Integer, 2000 or later | `2032` |
| `joinedAt` | Enrolment date | `yyyy-MM-dd` | `2026-09-01` |

**Username, password and role are not columns and cannot be supplied.** They are decided by
§2, `10-StudentActivationFlow.md` and `11-RolesAndPermissions.md` respectively — they are not
the roster file's business.

### 5.3 The three things that go wrong most

1. **The header must match word for word and in order.** A file whose header does not match
   is rejected whole at row 0, with a diff naming what is missing and what is unexpected. The
   system **does not match columns by position** — positional parsing is how a roster gets
   imported with names in the grade column and no error anywhere.
2. **`gradeId`, `classId` and `enrollmentAcademicYearId` are internal identifiers, not
   "Grade 6" and "6A".** Their real values come from this system: read them from the grade and
   class management screens, or download the template at `GET /imports/templates/students`,
   which is generated from the same rule definitions the validator uses and therefore cannot
   disagree with it.
3. **`classId` must belong to the same row's `gradeId`.** This is a cross-column check: the
   class exists, the grade exists, and they still do not go together — that row is an error.

### 5.4 The file itself

| Item | Requirement |
| :--- | :--- |
| Encoding | UTF-8 |
| Separator | Comma, with RFC 4180 quoting — **a name containing a comma must be quoted**, or the columns shift |
| Limits | 5,000 rows / 5 MiB |
| Rejected content | Text fields beginning `=`, `+`, `-`, `@`, tab or carriage return (spreadsheet formula injection); NUL and other control characters; bidirectional override characters such as U+202E |

### 5.5 The whole file is valid, or nothing is imported

**One invalid row and none of the file is imported** — there is no "skip the bad rows and load
the rest" outcome (`12-DataImport.md` §3.2, §8).

That single rule decides what preparation is aiming at: **zero errors, not "a few bad rows are
fine"**. Validation reports a stable error code and explanation per row and per column; fix
and re-upload. Until it all passes, not one student has appeared in the system.

### 5.6 An offline conversion tool (planned, not yet built)

The roster a school holds has **real names and real student numbers**. This system wants
**masked names and Crockford student numbers**. That conversion deliberately stays on the
school's side, outside the system: the system never holds information that identifies a child
directly (`08-...` §4.1), and the moment the conversion happens inside it, that sentence stops
being true.

The plan is an **offline** tool that reads the school's existing roster (Excel/CSV) and
produces two things:

| Output | Where it goes |
| :--- | :--- |
| The import CSV (§5.2's seven columns) | Uploaded to this system |
| **The mapping table**: real name / real student number ↔ masked name / this system's student number | **Stays at the school**; never enters this system |

The mapping table is the school's asset and the school's responsibility. Its existence is also
exactly why the data this system holds is **de-identified** rather than anonymised
(`08-...` §1.3, item 3).

Three requirements, without which the tool creates the problem it was meant to solve:

1. **Student-number generation must be stable and re-runnable** — running it again for the
   same student must produce the same number (§3.1). So the numbers cannot be generated
   randomly and discarded: the tool either continues from the mapping table or derives them
   deterministically.
2. **It runs offline, with no network access.** It handles every student's real identity.
3. **The mapping table and the import file are separate outputs**, so real identities cannot
   be uploaded along with the import.

**Until the tool exists**, schools prepare the file per §5.2 and guarantee §3.1's stability
themselves. This section is written ahead of the work because it decides the tool's shape;
`Docs/backlog/` is where work of this kind — specified, not yet started — belongs.

---

## 6. Identity-adjacent, but not in this document

- **First login, activation codes, credential slips** → `10-StudentActivationFlow.md`
- **Password rules** (one set system-wide, including "must not contain your own username")
  → `08-...` §8.2
- **Staff account creation and role assignment** → `11-RolesAndPermissions.md`
- **Multi-factor authentication** is not enabled; the options are compared in `08-...` §8.5
- **A student created singly through `POST /students` currently gets no login account** —
  only the import path provisions one. A known gap, not part of this design.
