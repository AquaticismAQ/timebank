# Student Identity and Onboarding — Implementation Plan

## 0. Scope and how to read this

Students arrive by bulk CSV import, not one at a time through an admin form. Nothing
currently answers the question that follows: **how does a freshly imported student get into
their account for the first time?**

This plan settles that, and the identity model around it: what a student types to log in,
how their first credential reaches them on paper, and what happens when it doesn't.

**Land this before the next re-seed.** The seeded dataset should be built with the new
usernames and activation states already in place, rather than seeded twice.

**Out of scope:** multi-factor authentication (deferred, see
`Design/english/08-mlps-level1-security-design-guide.md` §8), and any change to how staff
accounts are created.

---

## 1. What already exists (verified findings)

Checked against the running system and schema, not assumed.

| # | Finding | Consequence |
| :--- | :--- | :--- |
| F-1 | **The login page advertises a capability that does not exist.** The placeholder reads `学号 / 工号 / 邮箱` ("Student ID / Staff ID / Email"), but authentication calls `findByUsername` and nothing else — student number, staff ID and email all fail | The form has been lying since it shipped. Fixing the copy is part of this plan |
| F-2 | **`staff_id` exists, is wired through `UserController` and `CreateUserRequest`, and holds zero rows** | Evidence the identifier-login idea was designed and abandoned. This plan finishes the intent differently |
| F-3 | **`username` and `staff_id` use `utf8mb4_0900_ai_ci`** | Case-insensitive login comes free; `Alex3254` and `alex3254` already cannot coexist |
| F-4 | **`username` is validated only as `@NotBlank @Size(min = 3, max = 64)`** — no regex, no numeric parsing anywhere | An all-digit username needs no validation change |
| F-5 | **`password_hash` is `NOT NULL`** | An account cannot currently exist without a password. The activation design needs this relaxed |
| F-6 | **`UserStatus` is `ACTIVE`/`INACTIVE`/`LOCKED` only**, and `LOCKED` pairs with `locked_until` for brute-force lockout | There is no pending state, and `LOCKED` must not be overloaded to mean "never activated" |
| F-7 | **No must-change-password flag exists** | Needs a migration |
| F-8 | **Nothing generates QR codes**; PDFBox arrived in Wave 2 for evidence watermarking | PDF generation is free; QR needs one small dependency |

---

## 2. Decisions

All settled. Recorded with the alternatives rejected, because several are the obvious first
answer and will be proposed again otherwise.

### 2.1 Students log in with an 8-digit username

Random, in `10000000`–`99999999`, stored as a string, stable for life.

**Rejected — sequential (`student001` … `student52834`).** Grows visibly ugly, and leaks
enrollment order and cohort size.

**Rejected — the student number as the login (a "TBID").** Opaque Crockford is materially
harder to remember than anything name-based, and it couples the roster key to the
credential.

**Rejected — derived from the English name (`Alex L.` → `alex3254`).** Genuinely the most
memorable option, and it survived several rounds before failing on students with no Latin
name. Every fix was worse than the problem: pinyin transliteration needs a surname table, a
polyphonic-exception table (單 is Shàn not dān, 解 is Xiè not jiě, 曾 is Zēng not céng) and a
compound-surname table (欧阳, 司马, 诸葛), and still guesses wrong sometimes — printing a
username that is *incorrect about the student's own name* is worse than one that never
claimed to know it. Assigning a name from a dictionary avoids that but invents an English
name the student does not have, and needs the list to be gender-neutral because the system
stores no gender.

**Accepted cost:** eight random digits is *less* memorable than `alex3254` — one word-chunk
plus four digits beats four digit-pairs. Uniformity wins anyway: one rule, no dictionary, no
transliteration, no fallback cohort, no student asking why they are called Maya. Digits also
type cleanly on a phone numeric keypad, leak no name, and resist enumeration far better
(10⁸ versus 10⁴ once an attacker knows a first name).

**Three things that bite if missed:**
- **Random, not sequential** — otherwise it reintroduces exactly what was rejected above.
- **No leading zeros.** `00482155` silently becomes `482155` the moment anyone opens a list
  in a spreadsheet.
- **Stored as a string**, so nobody optimises it to an integer column and reintroduces both
  problems plus a type mismatch with `teacher001`.

### 2.2 Login accepts a username only

Fix F-1's copy to say *username* in both languages. Staff keep `teacher001`-style names;
students get digits. One namespace, one unique index, no precedence rules.

**Rejected — resolving username, then student number, then staff ID.** A typed value could
match one person's username and a different person's student number, so it needs a
precedence rule, and getting that wrong logs somebody into the wrong account.

Case-insensitivity is already true (F-3); usernames are stored lowercase for consistency.

### 2.3 The student number stays Crockford base32, and is not a credential

It remains the roster key, is displayed in Student Management, and is embedded in service
record numbers. The collision argument that motivated it holds independently of login.

### 2.4 First access is by activation code, not by an initial password

**This is the decision that simplifies everything else.** The slip carries a single-use
activation code. The student sets their own password at activation. **No password is ever
generated server-side, so no password plaintext exists at any point.**

What this removes outright: generating initial passwords, the one-shot download problem
(bcrypt means plaintext exists only at generation), the need to gate account activation on
the admin downloading a PDF, and the "regenerate the batch" recovery path existing purely to
recover lost passwords.

It also makes the guarantee we wanted true *by construction*: an account with no password
cannot be logged into, so no account is ever usable before its slip has been delivered.

### 2.5 The activation code is 10 characters, and the QR encodes the same code

Nine Crockford data characters plus one check character, printed grouped: `HKTZ4-9QP2M`.
The QR is that same code wrapped in an activation URL — **one secret, two delivery forms**,
nothing extra to track.

The length is not a style choice. An attacker guessing against the endpoint is not guessing
*a* code, they are guessing against **the whole pool of outstanding activations**, so 500
pending codes make every guess 500× more likely to land. Over a 7-day window with 500
pending:

| Code length | Behind the rate limit | Distributed, limits bypassed |
| :--- | ---: | ---: |
| 6 characters | 4.6% | 100% |
| 8 characters | 0.005% | 2.7% |
| **9 characters** | 0.0001% | 0.086% |

The per-IP rate limit cannot be relied on, so the right-hand column decides the length.

**A global failure budget on the activation endpoint** — a couple of hundred failures per
hour across the entire system — does more than adding characters, because legitimate
activation traffic is tiny and bursty (one class, once). It is what actually closes the
distributed case.

The code is stored **hashed** and looked up by hash, which is why no username is needed to
redeem it: the code is itself the lookup key, exactly as a password-reset token is.
Single-use, and expiring per §2.6.

The check character catches a mistyped or transposed character in the browser, so a child
sees "you mistyped that" rather than a round-trip and a bare "invalid code".

### 2.6 Codes expire, and expiry is configurable

Default **7 days** from issue, shown on the slip.

Two days is too short: import Monday, email the sheet, print and distribute Wednesday, and
the window is gone before a student sees it — a weekend or a sick teacher makes the recovery
path the normal path. But it cannot be long either, because the expiry is precisely what
makes emailing a class's slips tolerable. Configurable, because that tension belongs to the
school.

An expired, never-activated account goes to a **distinct state, not `LOCKED`** (F-6).
Overloading `LOCKED` would leave an admin unable to tell a brute-forced account from one
whose slip was never collected, and the two need different responses.

**Activated means the password was set**, not merely that a code was redeemed — otherwise a
half-finished activation leaves an account in an ambiguous state.

### 2.7 Regeneration spares activated students

If slips are lost, regeneration reissues codes for **every still-pending member of the
batch** — never for students who have already activated and chosen a password. Losing five
slips must not reset the twenty-five students who were fine.

### 2.8 One PDF, one page per student by default

`slipsPerPage` is a **closed enum of 1 or 2**, supplied per download request, defaulting
to 1. Not a free integer: making 1000 unrepresentable is better than validating a range,
since a range of 1–100 still admits ninety-odd nonsense values. The ceiling is not arbitrary
either — it falls out of the minimum scannable QR size alongside a legible name and username.

**Where the setting lives: the download request**, not a config file and not a settings
screen. It is a printing preference with no security meaning, the person who cares is the
admin at the moment of download, and different classes may warrant different answers. A
static config would need a deploy to change; a stored setting would need a migration, an API
and a UI for something chosen once per download.

**Rejected — one PDF per student, thirty attachments.** The goal was avoiding cutting, and
one 30-page PDF achieves that with one attachment and one print job. Thirty files are worse
three ways: many school mail gateways cap attachment *count*; the head teacher must print
thirty files individually; and filenames would carry student identifiers into the mail
client, the Downloads folder and every mail-server backup — outside the document whose
contents we were careful about.

Each slip carries the student's name, their username, the QR, the typed code, and a
provenance line: *issued by, at, valid before*. **Provenance goes on every slip, not once
per page** — the sheet may be cut, and a page-level mark survives on exactly one piece.


### 2.9 The slip prints the root URL; the login page carries the activation entry

The QR encodes a deep link with the code embedded, so scanning is one tap and lands on the
activation page ready to go. The **typed** path is different: the slip prints the site's
**root URL**, and the login page carries a prominent first-time entry.

**Rejected — printing a deep link (`…/activation`) for typing.** It teaches a URL used
exactly once, while the login page is used for years; a student who remembers the activation
address may keep returning to it next term. The root URL is also shorter to type, which
matters on the fallback path — and the fallback path is by definition the one where
something already went wrong (no camera, a shared classroom computer, a parent's phone that
will not scan).

**The printed URL is a phishing anchor, not merely an address.** This design teaches children
to scan a code and then type a credential into whatever page appears, which is precisely the
behaviour phishing exploits. Printing the real domain in text beside the QR gives them
something to check the address bar against. That alone justifies printing it, independently
of whether anyone types it.

**Wording must match the paper, and match the student's mental model.** A twelve-year-old
holding a slip is thinking "I need to get in for the first time", not "I must activate my
account". Use *"First time logging in? Use your activation code"* / 首次登录？使用激活码 —
and the screen must use **the same words as the slip**. If the paper says "activation code",
the page cannot say "token" or "sign-up key", or the student cannot match one to the other.

Keep the route name plain and descriptive. Informal or idiomatic names tend not to survive
translation, and this is the one page a parent is likely to see. Avoid "sign up" — the
account already exists.

### 2.10 Only administrators issue slips

Every issuance, reissuance and password reset goes through an administrator. Teachers get no
slip path, not even a scoped one for their own class.

The argument against was operational: the head teacher is the one who notices a student never
activated, so routing reissues through an admin may be the bottleneck that makes people
photograph slips instead. That is a real cost and it was accepted deliberately.

What buys it: the slip is the one artefact in the system that carries a working credential.
Keeping its issuance on a single role means one permission to audit, no data-scope logic on
the credential path, and no second, wider population of accounts that can mint logins for
students. A data-scope bug on a reporting endpoint leaks a list; the same bug on this endpoint
mints credentials.

If the bottleneck proves real in practice, revisit it with usage evidence — a scoped teacher
path can be added later without reworking anything here, whereas withdrawing the capability
once teachers have it is much harder.

---

## 3. Implementation plan

### 3.1 Schema

One migration: relax `password_hash` to nullable (F-5), add a pending/expired state
alongside `ACTIVE`/`INACTIVE`/`LOCKED` (F-6), add the must-change-password flag (F-7), and
add an activation table holding the code hash, the owning user, issue and expiry timestamps,
single-use state, and the issuing admin.

### 3.2 Username generation

Random 8-digit, retried against the unique index on collision — mandatory, not optional.
Generated once at import and never regenerated, including when a display name is later
corrected: it is the student's login, not a derived attribute.

### 3.3 Import creates pending accounts

Commit creates the student and user rows with no password and a pending status. Accounts are
inert until activated.

### 3.4 Credential document

Generated on demand and **streamed, never stored** — and deliberately not through the
evidence `FileStorage` path, which sanitizes and watermarks *evidence* and would be the wrong
pipeline for credentials. Audited: who generated slips for which class, and when.

**Admin-only, with no data-scope path for teachers** (§2.10). The authorization is a plain
role check, not a scope resolver — there is deliberately no "own class" branch to get wrong.

QR generation needs one small dependency (zxing or equivalent, Apache-2.0); PDFBox is already
present from Wave 2.

### 3.5 Activation endpoint

Public and unauthenticated by necessity — currently the only public endpoint is login, so
this is a new surface and wants reviewing as one. Needs single-use enforcement, expiry
checks, the global failure budget from §2.5, and its own rate limit. Redeeming sets the
password, clears the must-change flag, and activates the account in one transaction.

### 3.6 Expiry sweep

A scheduled job moves expired, never-activated accounts to the expired state. A lazy check at
login is not enough: uncollected accounts would otherwise sit looking pending forever in the
admin view. ShedLock is already wired for the grade-summary materialisation and the import
reclaim sweep.

### 3.7 Password reset reuses the same path

An admin reset issues a fresh activation code and slip rather than a new password, so there
is one flow, one document format, and still no server-generated password anywhere.

### 3.8 Login copy and the first-time entry

Fix the placeholder in both `en.json` and `zh.json` to say username (F-1), and add the
first-time entry described in §2.9 — wording identical to the slip's, in both languages.

---

## 4. Decision index

| ID | Decision | Status |
| :--- | :--- | :--- |
| D-1 | 8-digit random student usernames (§2.1) | Settled |
| D-2 | Username-only login; staff keep named accounts (§2.2) | Settled |
| D-3 | Student number stays Crockford, not a credential (§2.3) | Settled |
| D-4 | Activation code instead of an initial password (§2.4) | Settled |
| D-5 | 9 data + 1 check character; QR encodes the same code (§2.5) | Settled |
| D-6 | 7-day default expiry, configurable; distinct expired state (§2.6) | Settled |
| D-7 | Regeneration spares activated students (§2.7) | Settled |
| D-8 | One PDF, `slipsPerPage` ∈ {1,2} on the request, default 1 (§2.8) | Settled |
| D-8a | Slip prints the root URL; login page carries the first-time entry (§2.9) | Settled |
| D-9 | Slip generation is **admin only** (§2.10) | Settled |
| D-10 | Teachers get no slip path at all — issue, reissue and reset are all admin (§2.10) | Settled |

---

## 5. Implementation status

| Section | Status |
| :--- | :--- |
| §3.1 Schema | Done — `V27__student_activation.sql` |
| §3.2 Username generation | Done — `StudentUsernameGenerator` |
| §3.3 Import creates pending accounts | Done — `ImportJobRunner.commitStudents` → `UserService.createPendingStudentAccount` |
| §3.4 Credential document | Not started |
| §3.5 Activation endpoint | Not started |
| §3.6 Expiry sweep | Not started |
| §3.7 Password reset | Not started |
| §3.8 Login copy and first-time entry | Not started |

§3.1–§3.3 are covered by unit tests only (`mvn test`); no integration test or
live-server verification has run against them yet, so the migration itself is
unapplied and the import path is unproven end to end. Until §3.4/§3.5 land,
an imported account is created and can never be logged into — which is the
intended interim state (§2.4), not a gap, but it does mean the next re-seed
must wait for the activation path.
