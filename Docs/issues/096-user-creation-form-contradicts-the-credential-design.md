# 096 — The user-creation form contradicts the credential design

**Status:** Fixed, live-verified (§5–§7).
**Reported:** 2026-09-17 by the user, reviewing System Admin → Users → Create User.

Four findings. Three are defects in the form; the fourth is a gap the activation
work already built everything to close.

## 1. Email is marked required, and the form genuinely needs it — for the wrong reason

`CreateUserRequest.email` carries `@Email` and **no** `@NotBlank`: the API treats
it as optional. The form marks it `*` and refuses to submit without it.

The label is not the bug. The form sends:

```ts
username: form.email.split('@')[0]
```

The **username is derived from the email**, so the field is load-bearing and
removing the asterisk alone would create accounts with no username. The real
defect is that the form invented a requirement the API does not have, by
overloading a contact field as an identity field.

**Fix:** an explicit username input, and email genuinely optional — matching the
API. Deriving one identifier from another also means a teacher who changes their
email address has a username that no longer resembles it, with nothing
explaining why.

## 2. `student` is offered in the role dropdown

`HIDDEN_ROLE_CODES` hides only `sysadmin`, so an admin can create a student here.
That is not merely a workflow shortcut — it produces a student account that
contradicts the identity model in two ways at once:

- **With an admin-typed password**, bypassing the activation flow that exists so
  no student account is usable before its slip is delivered (plan 10 §2.4).
- **With an email-derived username** such as `alex.li`, not the 8 random digits
  §2.1 specifies — and §2.1's whole argument is that a name-derived username
  leaks a name and is enumerable.

Students arrive by roster import. There is no second legitimate way in.

**Fix:** add `student` to `HIDDEN_ROLE_CODES`.

## 3. The form asks the admin to type an initial password

`initialPassword` is `@NotBlank`. So an admin invents a password, knows it, and
must convey it to the teacher through a channel nobody controls — a message, a
sticky note, a conversation. It then very likely never changes.

`Design/*/08-mlps-level1-security-design-guide.md` §8.2 tells schools the system
produces no "初始密码清单" / initial-password list. Typing thirty of them into a
form does not avoid that list; it relocates it into the administrator's notes,
where the system's controls do not reach.

## 4. Staff creation should use the activation slip (decided)

**The flow is already role-agnostic.** `ActivationServiceImpl` and
`CredentialSlipServiceImpl` contain no role checks; `PENDING_ACTIVATION`, the
`/activate` page, the slip renderer and §3.7's admin reset all work on any
account. A teacher could be created with no password and handed a slip today
using entirely existing code.

**Decision (user, 2026-09-17): non-student accounts use the same activation slip
as students.** Create the account with no password in `PENDING_ACTIVATION`, issue
one single-use code, produce a one-person slip.

Why this and not the alternatives:

- It removes the second credential mechanism rather than maintaining it. The
  repository has already paid for having two once — issue 089, where the older
  of two mechanisms was the one nobody could see, and was writing live tokens
  into the server log.
- It makes §2.4's promise — *no password plaintext exists at any point* — true
  **without exception**. The MLPS document already states this to schools with
  no "except staff" caveat.
- A server-generated one-time password was the other obvious option and is
  exactly what issue 088 deleted: server-generated plaintext returned in an API
  response.

The accepted cost is that a new teacher needs a piece of paper rather than a
sentence in a chat window — the same trade already accepted for students, and
for the same reason.

## 5. What was built

**Backend.** `CreateUserRequest` lost `initialPassword` entirely, and
`UserServiceImpl.create` now builds the account through
`User.pendingActivation(...)` — the same factory the roster import uses — so it
is `PENDING_ACTIVATION` with a null hash. Nothing else was needed for §4: the
slip endpoint `POST /users/{id}/reset-password` already produces a one-person
slip for any account and already tolerates one that is *already* pending, so
the frontend calls it straight after creating.

**Deliberately no new endpoint.** A `POST /users/{id}/activation-slip` would
have read better at the call site and would have been a second way to mint a
credential — the exact thing §4 chose this design to avoid. The audit trail is
honest either way: `user.adminResetCredential` records `hasPassword: false →
false` on a new account, which reads as the no-op clear it is, and
`user.issueActivationCode` records the issuance.

**Frontend.** An explicit `username` input (create only; read-only when
editing, since `PATCH /users/{id}` does not accept a new one and the audit
trail is written against the old one), email labelled `(optional)` and
genuinely optional end to end, the password boxes replaced by a notice saying
what will happen, and `student` filtered out of the role dropdown — but only
when creating, and only when the account being edited does not already hold it
(§6).

**Guard test.** `StaffAccountCreationTest` asserts the saved row has no hash and
is pending, that the username is the one given rather than derived from the
email, that a created account genuinely cannot authenticate (through a real
`DaoAuthenticationProvider`, with a control that does), and — reflectively —
that `CreateUserRequest` has no field whose name contains "password", so the
removed field cannot quietly return.

## 6. Two things the fix broke, and how

Both found while verifying, not while writing.

**`student` hidden everywhere would have broken editing a student.** The issue
text above said "add `student` to `HIDDEN_ROLE_CODES`", which filters the role
list at fetch time. Every existing student account would then have opened its
Edit dialog with a role dropdown whose current value matches no option —
rendering blank, and saving whatever the admin did next with an empty
`roleCode`. The filter is therefore in the modal, conditional on mode and on
the account's own role.

**An account with no email white-screened the Users tab.** Making email
optional made emailless accounts possible for the first time, and Jackson's
non-null inclusion means `GET /users` omits the field rather than sending
`null`. `UserDtoWire` declared it `string`, so `form.email.trim()` ran on
`undefined` and React unmounted the whole page — a blank screen, no error
visible to the user. Normalised once at the API-client boundary
(`u.email ?? ''`) rather than guarded at each read. `PATCH` now sends
`undefined` for a blank box too, so clearing an address restores `NULL`
instead of writing `""`.

The first would have been caught by any attempt to edit a student; the second
only by creating an account without an email and then reopening it — which is
exactly the new state this change makes possible, and is why the verification
below does both rather than stopping at "the account was created".

## 7. Verification

Deployed to the test server (backend + frontend) and driven in a real browser
as `admin001`, with the two claims that matter checked against the database and
the API rather than the screen.

| Check | Result |
| :--- | :--- |
| Create form fields | `Username *`, `Name *`, `Email (optional)`, `Roles *` — **no password input of any kind** in the dialog |
| Role dropdown, create | `Admin`, `Teacher` — `Student` absent |
| Role dropdown, editing a student | still reads `Student`, username shown read-only as `student001` |
| Submit enabled with **no** email | yes — email is genuinely optional |
| Username shorter than 3 | submit disabled; 3 characters re-enables it |
| Malformed email (`not-an-email`) | submit disabled; clearing the box re-enables it |
| Account created | row shows `Pending Activation`, empty email |
| Slip | `activation-slip-20260917.pdf` downloaded on save; its text carries the username and one code |
| **Database row** | `status=PENDING_ACTIVATION`, `password_hash IS NULL`, `email IS NULL` (null, not `""`) |
| **Login before activation** | `401 AUTHENTICATION_REQUIRED` — "This account is inactive" |
| **Redeem the printed code** | `200`, password chosen by the account holder |
| **Login after activation** | `200`, session established, `roles: ["teacher"]` |
| Reopen the emailless account | Edit dialog renders (the §6 crash, after the fix) |

The last four are the point: a screen that says "Pending Activation" proves
nothing on its own, and neither does a PDF that downloads. What is being
claimed is that the account has no usable credential until a specific piece of
paper is redeemed, so the check is a real failed login, a real redemption of
the code printed on that paper, and a real successful login afterwards.

The probe account was deactivated afterwards through the UI (deactivation is
the domain rule — no `DELETE` endpoint exists).
