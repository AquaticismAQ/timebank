# 111 — A user cannot change their own password, and the app tells them so

**Status:** **Fixed** 2026-09-20, same day. Built on Docker and verified in a
browser; **not yet deployed**.
**Scope:** Frontend wiring plus two small backend loose ends. Every piece this
needs already exists; nothing has to be designed from scratch (§4).

## 1. What is wrong

`POST /api/v1/me/change-password` exists, works, is permissioned
(`isAuthenticated()`), is covered by
`AuthFlowIT.changingPasswordRevokesTheOldSessionImmediately`, and is documented in
`APIContract.md` §2. **No client ever calls it.** There is no method on the api
client, no form, and no button anywhere in `frontend/src`.

Worse than absent: both profile pages actively tell the user it is impossible.

| Where | Copy |
| :--- | :--- |
| `student.profile.accountSafety1` | "…To change your password, please visit the Student Affairs Office or contact the system administrator." |
| `admin.profile.accountSafety1` | "…To change your password, please contact the system administrator." |

So a user who wants a new password — not a *forgotten* one — has no route but to
ask an administrator, who can only run
`POST /users/{id}/reset-password`. That does not set a password: it clears the
hash, moves the account to `PENDING_ACTIVATION` and issues an activation slip.
A voluntary password change therefore costs a staff member's time, a printed slip,
and an in-person identity check, for an operation the account holder is entitled
to perform alone.

**This is not the no-self-service-recovery rule.** That rule
(`Design/*/08-mlps-level1-security-design-guide.md` §8.1/§8.2, issue 089) is about
a *forgotten* password, where the person cannot prove who they are — and it stays
exactly as it is. Changing a password you can already supply is the opposite case:
`currentPassword` is the proof. `AuthController` already draws that distinction in
its own comment; the UI never did.

## 2. What it should do

- **An entry on the profile page**, in both portals — `components/student/ProfilePage.tsx`
  and `components/admin/AdminProfilePage.tsx`. Aquaticism: the profile page is where this
  belongs, and it should work "same as activation".
- **Strength checking that refuses weak passwords while typing**, matching the
  activation page exactly (§4). Refuse, not warn: a form that lets a weak password
  through to a server rejection has taught the user nothing and cost them a round
  trip.
- **Ask for the current password**, which is what makes this safe to expose and is
  already required by the endpoint.
- **Say that every session ends.** The endpoint revokes all sessions for the user
  including the caller's, so the user is signed out and must log in again. That is
  correct behaviour and must not be a surprise — state it before they submit, and
  send them to `/login` afterwards rather than leaving a dead session rendering.
- **Replace the three misleading strings** (student + admin, English + Chinese).
  The remaining advice in those blocks — don't share your account, log out on
  shared devices — is good and stays.

## 3. Two backend loose ends found while looking

Both are small, and neither blocks §2.

- **`changeOwnPassword` does not clear `mustChangePassword`.** Only
  `ActivationServiceImpl` and the admin reset clear it
  (`UserServiceImpl.java:312`). An account flagged to change its password would
  therefore stay flagged after doing exactly that.
- **`mustChangePassword` never reaches the client.** It is a column and two audit
  summaries; it is absent from `AuthenticatedUserView`, so no screen can act on it
  and nothing forces a change.

**Decision (Aquaticism, 2026-09-20): keep the column, reserved.** It is held for a
future forced-rotation policy — "a password older than N days must be changed" —
rather than being dropped. Nothing sets it today and nothing reads it, and that is
now a deliberate reservation rather than an oversight.

The first half is therefore **fixed**: `changeOwnPassword` clears the flag. That is
not tidiness but a precondition of the reserved feature — a forced-rotation prompt
that survived the change answering it would trap the user in a loop they cannot
exit. `SelfServicePasswordChangeIT` asserts it, and the assertion was confirmed by
reverting the one line and watching it fail.

## 4. What already exists (do not rebuild)

| Piece | Where |
| :--- | :--- |
| The endpoint | `AuthController.changePassword` → `UserServiceImpl.changeOwnPassword` |
| Server-side policy | `cn.timebank.iam.domain.PasswordPolicy` — ≥12 chars, ≤128, ≥3 of 4 character classes, must not contain the username, blocklist, repeat/sequence checks |
| Frontend policy mirror | `frontend/src/app/lib/passwordPolicy.ts`, character-for-character, with the backend's own failure codes |
| Live strength UI | `components/shared/PasswordStrength.tsx` — takes `password`, `username`, `confirm` |
| A working precedent | `components/auth/ActivationPage.tsx` uses both of the above |

`passwordPolicy.ts`'s header is explicit that the mirror must stay identical to
the backend rule, and that a rule relaxed in the browser buys a green tick
followed by a server rejection. Reuse it; do not write a second strength check.

## 5. How it was built

- `api.auth.changePassword(currentPassword, newPassword)` in the api client.
- `components/shared/ChangePasswordDialog.tsx` — one component used by **both**
  profile pages, so the student and staff experiences cannot drift apart. It
  reuses `PasswordStrength` and `isAcceptable`; no second policy check was
  written.
- A **Change password** row on both profile pages, beside Account Security.
- The four `accountSafety1` strings lost the sentence telling users to contact an
  administrator. The rest of that advice — don't share your account, log out on
  shared devices — is still true and stays.
- `UserServiceImpl.changeOwnPassword` now clears `mustChangePassword` (§3).

**Verified in a browser**, student portal, against the real backend: the entry
opens the dialog; submit is disabled until the rules pass; **four weak variants
were each refused** — too short, one character class only, long-but-guessable, and
one containing the username — while an acceptable password enabled the button and
a mismatched confirmation disabled it again. A wrong current password was refused
with "Current password is incorrect." and the dialog stayed open. A correct change
signed the user out to `/login`, after which the **old password returned 401 and
the new one 200**.

## 6. What it must not do

- **Must not become password recovery.** No email, no reset link, no "forgot
  password". The endpoint requires the current password, and that requirement is
  the whole reason this is safe to add.
- **Must not skip the current-password check** for convenience, including for an
  admin changing their own.
- **Must not leave the user on a dead session.** Sessions are revoked server-side;
  the UI has to follow.
- **Must not relax the policy** to make the form feel easier.
