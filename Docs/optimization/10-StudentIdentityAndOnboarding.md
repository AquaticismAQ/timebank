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
| §3.4 Credential document | Done — `POST /users/activation-slips`, `CredentialSlipService`/`CredentialSlipDocument`/`SlipFont`/`SlipCopy`/`QrCodeImage`, contract §4.15 in both languages, slip text bilingual and in an embedded font (issue 087). **No admin UI yet** (see below) |
| §3.5 Activation endpoint | Done — `POST /auth/activate`, `ActivationService`/`ActivationCodeFormat`/`ActivationThrottle`, contract §2.2 in both languages |
| §3.6 Expiry sweep | Done — `ActivationExpirySweep` (hourly, ShedLock-guarded) → `ActivationExpiryTransaction`, `ActivationExpiryRepository` |
| §3.7 Password reset | Done — `POST /users/{id}/reset-password` now clears the password and streams a one-slip document (`CredentialSlipService.issueForReset` → `UserService.resetToPendingActivation` + `ActivationService.issue`), contract §4.15 in both languages. Closes the §2.4 contradiction (issue 088). The older `password_reset_token` mechanism is **still present and recommended for removal** — see below |
| §3.8 Login copy and first-time entry | Done — `ActivationPage`, `/activate`, login copy in both languages |

Everything above is covered by **unit tests only** (`mvn test`). No integration
test and no live-server verification has run against any of it, so the migration
is unapplied and neither the import path nor the activation path is proven end
to end. Two specific things unit tests here cannot reach, both flagged by the
slices that wrote them: §3.6's two SQL reads (the sweep's *decisions* are in
Java and tested, but no candidate query has run against a real
`activation_code` table), and §3.5's conditional `UPDATE ... WHERE redeemed_at
IS NULL`, whose single-use guarantee rests on the row count MySQL actually
returns.

**§3.4 now closes the delivery half.** `POST /users/activation-slips` takes a
`classId` and a `slipsPerPage` of 1 or 2, issues one code per still-pending
member of the class, and streams the batch back as a single
`application/pdf` — never stored, and deliberately not through the evidence
`FileStorage` path. What the slice settled, beyond restating §2:

| Item | Resolution |
| :--- | :--- |
| QR library | `com.google.zxing:core` 3.5.3, Apache-2.0, pinned in `pom.xml`. Only `core`: zxing's `javase` module exists to bridge a `BitMatrix` to `java.awt.image`, which is the loop in `QrCodeImage`, and it drags in command-line tooling dependencies nothing here uses. Error correction `M` — the slip lives in a school bag, so a crease across the code is the normal case, while `H` would shrink each module below what a phone resolves at the two-per-page size |
| `slipsPerPage` typing | A Jackson `@JsonValue`/`@JsonCreator` enum of `ONE(1)`/`TWO(2)` on the wire as the plain number. §2.8's point is that 1000 is *unrepresentable*, not that it is rejected: anything else fails deserialization and becomes `400 INVALID_REQUEST` before a single code is minted. Omitted means `ONE` |
| Authorization | `@PreAuthorize("hasAuthority('users.manage')")` **plus** an explicit `admin`/`sysadmin` role-code check, resolved in the controller and passed into the service as a boolean — the same shape `PATCH /school/info` uses, which keeps the rule unit-testable. Permission alone would silently widen the moment a school defines a custom staff role holding `users.manage`, which is exactly the "second, wider population of accounts that can mint logins" §2.10 exists to prevent |
| Selection | `ISSUABLE_STATUSES = {PENDING_ACTIVATION, ACTIVATION_EXPIRED}`, applied in `CredentialSlipServiceImpl`, not in `issueAll`. `LOCKED` and `INACTIVE` are excluded too — both already have a password, and an unlock and a reactivation are their respective answers, not a new slip |
| New config | `timebank.auth.activation.site-root-url` (env `TIMEBANK_SITE_ROOT_URL`), under the existing activation prefix. **No default, fails fast at startup**, and `deploy.sh` refuses to deploy without it — §2.9 makes the printed URL a phishing anchor, so a placeholder would put a domain the school does not own onto paper handed to children, which no script-based check would ever notice (issue 027's lesson, with higher stakes) |
| Slip language | **Bilingual, from the request's resolved language** (issue 087). Originally English-only, on the reasoning that Standard-14 fonts need no embedded typeface — which was half right and half a latent production bug. The *names* were exempt and drawn through AWT into an embedded image, and AWT's logical `SANS_SERIF` resolves against **system-installed** fonts: the deployment host has only DejaVu, so every Chinese name printed as empty boxes there while passing on a macOS build machine. Both halves are now fixed the same way — `NotoSansSC.ttf` (SIL OFL 1.1) ships in the jar and is embedded as a subset, and every human-readable string on the slip is real PDF text in it. Labels reuse the frontend's own values (`auth.login.firstTimeEntry`, `auth.activation.codeLabel`, …) per §2.9. Only the username and code stay Standard-14 Courier, being ASCII by construction and wanting a fixed pitch |
| No admin UI | The endpoint is the deliverable; nothing in the Admin Portal calls it yet. `api/http.ts` unwraps the `data`/`meta` envelope on every response, so a PDF download needs a raw-fetch path beside it — small, but not this slice's scope. **Open** |

Two things unit tests here cannot reach, matching the pattern above: the new
`findByStudentIdIn` has not run against real MySQL, and no slip has been printed
on paper or scanned with a real phone camera — the QR is decoded from a
rasterised page by zxing, which proves the payload and the module size but not
the ink.

**§3.8's three open guesses are now closed by §3.5**, which adopted the
frontend's assumed contract rather than changing it:

| Item | Resolution |
| :--- | :--- |
| Endpoint | Adopted unchanged: `POST /api/v1/auth/activate` with `{ code, newPassword }`, no username, no session established on success. Now in `APIContract.md` §2.2 and its `_CN` twin |
| Check character | Crockford's standard `value mod 37` symbol, implemented in `ActivationCodeFormat` and mirrored in `isWellFormedActivationCode`. 37 being prime and coprime to both 32 and 31 is what makes it catch *every* single-character substitution and *every* adjacent transposition, so §2.5's "you mistyped that" is now delivered in the browser. The generator never mints a code whose check character is one of Crockford's five check-only extras (`*~$=U`) — they are miserable to type on a phone — but both sides still accept them |
| Error codes | Four distinct `fieldErrors[0].code` values on a shared `422`: `ACTIVATION_CODE_MALFORMED` / `_INVALID` / `_EXPIRED` / `_ALREADY_USED`, translated in both languages. `_INVALID` is deliberately the vaguest — it is what a guess gets |

**The seam §3.4 and §3.7 build on** is `ActivationService`:
`issue(userId, issuedByUserId)` and `issueAll(userIds, issuedByUserId)`, each
returning the plaintext code **once** (`IssuedCode`, whose `toString()`
redacts it) along with the username, issue time and expiry the slip's
provenance line needs. Issuing retires any unredeemed code the user still
holds, which is what §2.7 requires of a reissue — the lost slip has to stop
working. `issueAll` deliberately applies no status filter: §2.7's "regeneration
spares activated students" is a selection rule for the caller assembling the
batch, because §3.7's admin reset legitimately issues a code for an already
active account.

**Where the failure budget's threshold lives.** `timebank.auth.activation.failure-budget-per-hour`
(env `TIMEBANK_ACTIVATION_FAILURE_BUDGET_PER_HOUR`, default 300), alongside
`attempts-per-ip-per-hour` (60) and `code-ttl-days` (§2.6's configurable
window, 7). Only a well-formed code matching no row is charged against the
shared budget; a failed checksum is not, or an attacker could empty it with
garbage and lock the school out of activation, and neither is an expired or
already-redeemed code, which are the legitimate recovery paths. It is
in-memory and therefore per-instance, the same trade-off
`common.web.RateLimitInterceptor` already documents.

**Two gaps the foundation work surfaced, neither settled by this plan:**

- **`POST /students` still creates no account.** Only the import path provisions
  one, because putting it in `StudentService.create` would make `student → iam`
  a module cycle (`iam` already depends on `student`). An admin adding a single
  student therefore gets a roster row with no login. §3.5 did not settle this —
  it owns redemption, not provisioning — and §3.4 did not either: a class's slip
  batch skips a roster row with no account rather than failing, so one
  hand-added student cannot stop a class printing, but that student still has
  no way in. **Still open.**
- **Activation codes are stored as unsalted SHA-256**, not bcrypt, because §2.5
  requires lookup *by* hash and a salted hash cannot be looked up. The tradeoff
  is acceptable — codes are single-use, expire in days, and protect an account
  holding no data yet — but it is a weaker primitive than the one protecting
  passwords, and `Design/*/08-mlps-level1-security-design-guide.md` §8.2 should
  not be read as claiming otherwise.

**§3.7 closes the last slice, and with it a live contradiction.** §2.4's "no
password is ever generated server-side, so no password plaintext exists at any
point" was not merely aspirational — it had been written into
`Design/*/08-mlps-level1-security-design-guide.md` §8.2 as a compliance claim
shown to schools, while `UserServiceImpl.adminResetPassword` generated a
16-character password, hashed it, and returned the plaintext in the response
body of `POST /users/{id}/reset-password`, which the Admin Portal then printed
in a toast. Filed and fixed as `Docs/issues/088-*.md`. What the slice settled:

| Item | Resolution |
| :--- | :--- |
| What the reset does to the account | **Clears** `password_hash` and returns the account to `PENDING_ACTIVATION`, rather than setting a new password. Both reasons a reset is ever requested want the old password *gone*: forgotten (worthless) or compromised (must stop working now). The consequences are coherent rather than merely tolerable — `hasUsableCredential()` already makes a password-less account unusable, §3.6's sweep moves it to `ACTIVATION_EXPIRED` if the slip is never used, and another reset re-issues. `LOCKED` is reset normally and comes back unlocked, since a lockout is the commonest reason to ask |
| Self-reset | **Refused, `409`.** Only an authenticated admin can reach the endpoint, so a self-reset cannot be the forgotten-password case — they just used the password. That leaves "I may be compromised", which `POST /me/change-password` answers better: it revokes every session too and leaves them holding a working login. Allowing it would let the last remaining admin clear their own password and stake their way back in on a PDF download completing, with nobody else able to issue them a slip |
| A deactivated account | **Refused, `409`.** A reset would move it to `PENDING_ACTIVATION` and hand out a redeemable code, quietly undoing a deliberate deactivation. `ActivationServiceImpl.redeem` refuses `INACTIVE` and §3.4's `ISSUABLE_STATUSES` excludes it, for the same reason |
| Authorization | Narrowed from `users.manage` alone to `users.manage` **plus** the `admin`/`sysadmin` role check §3.4 uses. D-10 already said "issue, reissue and reset are all admin"; the endpoint predates that decision and had not been brought in line |
| Where it lives | `CredentialSlipController`, beside the class batch, not `UserController`. It *is* a slip issuance now: same admin rule, same language resolution, same PDF response, same reason for being a POST. The path is unchanged — `POST /users/{id}/reset-password` still describes what an admin is doing, and renaming it would churn the contract, the client and the permission model for no behavioural gain |
| Audit | Two rows, each where the mutation happens: `user.adminResetCredential` (before/after carrying `status`/`hasPassword`/`mustChangePassword`, so "had a password, now does not" is stated rather than implied) and §3.5's `user.issueActivationCode`. No third batch-level row — §3.4's exists because a batch's subject is a *class*, which nothing else names |
| Session revocation | Preserved, in the controller, after the transaction commits. Clearing a password does not end a session: a cookie session carries its own authenticated principal and never presents a password again |

**Recommendation: remove the `password_reset_token` mechanism (code now,
schema later).** §2.4's point is that there should be *one* credential flow, and
after this slice there are still two. The older one is `POST
/auth/forgot-password` → `POST /auth/reset-password`, backed by V3's
`password_reset_token` table, `PasswordResetToken`,
`PasswordResetTokenRepository`, `AuthService.forgotPassword`/`resetPassword` and
`UserService.resetPasswordWithToken`. It is:

- **Unreachable from the frontend.** Nothing calls either endpoint; the login
  page's "forgot password" line is static copy reading *"Forgot password?
  Contact admin"*, which now points at exactly the flow §3.7 rebuilt.
- **Non-functional by design as deployed.** With no mail infrastructure,
  `forgotPassword` *logs the token as a warning* for an operator to relay by
  hand — a live credential written to the server log, which is precisely what
  the rest of this plan is careful never to do.
- **Actively hazardous next to §3.7.** A token minted for a just-reset account
  would set a password on it while leaving it `PENDING_ACTIVATION`: unable to
  log in either way, invisible to §3.6's sweep (which reads a stored hash as
  proof of activation), and having bypassed the slip. This slice closed that
  with a one-line guard — `forgotPassword` is silently a no-op for an account
  with no stored password — but the guard is a patch on a mechanism that should
  not be there.

A removal would delete the two endpoints and their DTOs, the two `AuthService`
methods, `UserService.resetPasswordWithToken`, the entity and the repository —
roughly 120 lines, no caller left behind. **A migration dropping the table is
warranted but is a separate decision**, and not urgent: the table holds
short-lived rows, `PurgeQueryRepository` already deletes from it by user id with
raw SQL that needs no entity, and an unused table costs nothing. Dropping it
should wait until the code removal has been live for a release, so the rollback
path is a redeploy rather than a restore. **Not done in this slice** — removing
a public authentication endpoint from a deployed system wants an explicit yes,
not a "while I was in here".
