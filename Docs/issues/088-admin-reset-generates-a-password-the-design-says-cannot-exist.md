# 088 — The admin reset generates a password the security design says cannot exist

Status: **Fixed — not yet live-verified**

## Symptom

`Docs/optimization/10-StudentIdentityAndOnboarding.md` §2.4 states the guarantee
the whole onboarding design rests on:

> **No password is ever generated server-side, so no password plaintext exists
> at any point.**

That sentence is not only an internal design note. It has been written into
`Design/chinese/08-mlps-level1-security-design-guide.md` §8.2 and its English
mirror as a **compliance claim shown to schools** — "the server never generates
an initial password at any point. No password plaintext therefore exists in the
system" — and again in the control-mapping table at §17 ("no server-generated
initial password").

It was false. `POST /users/{id}/reset-password` generated a 16-character
password server-side, hashed it, and **returned the plaintext in the response
body**; the Admin Portal then displayed it in a success toast. Every admin
password reset produced exactly the artefact §2.4 says the design eliminates.

This is a correctness defect in a stated security property, not a cosmetic one:
the claim was being made to a reader who cannot check it, about a system that
was doing the opposite on its most routine credential operation.

## Root cause

The endpoint predates the activation-code design by months. It was built in B1,
when a server-generated temporary password was the only way an admin could put
somebody back into an account — there was no notification module to mail a link
with and no activation mechanism at all — and the DTO says so in its own
Javadoc: *"No mail/notification module exists until B5 — the admin-triggered
reset therefore returns the generated temporary password once, in this response,
for the admin to relay out of band. Revisit once B5's notification module can
email a reset link instead."*

Plan 10 then introduced the mechanism that makes it unnecessary (§2.4, §3.4,
§3.5) and recorded in §3.7 that reset should reuse it. §3.7 was the last slice
and had not been built, so the two coexisted: §2.4's claim was propagated into
the design guides while the code contradicting it stayed untouched.

Three places carried it:

| File | What it did |
| :--- | :--- |
| `iam/application/UserServiceImpl.java` | `adminResetPassword` → `generateTemporaryPassword()`, hashed into `password_hash` |
| `iam/application/dto/AuthDtos.java` | `AdminResetPasswordResponse(username, temporaryPassword)` |
| `frontend/.../admin/SystemAdmin.tsx` | `toast.success(t('admin.system.passwordReset') + ': ' + result.temporaryPassword)` |

There was also a **second, older credential mechanism** still present — see
"The other mechanism" below.

## Fix

An admin reset now issues a fresh single-use activation code and returns the
same credential slip document a class batch prints (plan 10 §3.7). Implemented
on the existing seams, with no parallel machinery: `ActivationService.issue`
mints the code (it deliberately applies no status filter, precisely so §3.7 can
issue for an already-active account), and `CredentialSlipDocument` renders the
one-slip document in the same format and the same resolved language.

**What happens to the account, and why.** Its stored password is **cleared** and
it returns to `PENDING_ACTIVATION` — not replaced with a new one. A reset is
asked for in one of two situations, and both want the old password gone rather
than rotated: it was forgotten, so it is worthless, or the account is
compromised, so it must stop working immediately. The consequences line up
rather than merely being tolerable:

- `hasUsableCredential()` already makes a password-less account impossible to
  log into — the pre-authentication check requires status `ACTIVE` **and** a
  stored hash, so the account is inert by construction until the slip is used.
- §3.6's expiry sweep moves it to `ACTIVATION_EXPIRED` if the slip is never
  redeemed, which is the correct end state for "a credential was issued and
  never collected", and another reset re-issues.
- `LOCKED` is reset normally and comes back unlocked: a brute-force lockout is
  the single most common reason a reset is requested at all.

**Two refusals**, both `409`:

- **A self-reset.** Only an authenticated admin can reach this endpoint, so an
  admin resetting their own account cannot have forgotten the password — they
  just used it. What remains is "I may be compromised", and
  `POST /me/change-password` answers that better: it revokes every session too,
  and leaves them holding a working login. Allowing a self-reset would let the
  last remaining admin clear their own password and stake their way back in on a
  PDF download completing, with nobody else able to issue them a slip.
- **An `INACTIVE` account.** A reset would move it to `PENDING_ACTIVATION` and
  hand out a redeemable code, quietly undoing a deliberate deactivation.
  `ActivationServiceImpl.redeem` refuses `INACTIVE` and §3.4's
  `ISSUABLE_STATUSES` excludes it, for exactly this reason.

**Authorization narrowed** from `users.manage` alone to `users.manage` plus the
`admin`/`sysadmin` role check §3.4 uses. D-10 already settled that "issue,
reissue and reset are all admin"; this endpoint predated the decision.

**Session revocation preserved.** Clearing the password does not end a session —
a cookie session carries its own already-authenticated principal and never
presents a password again — so the controller still revokes, after the
transaction commits.

**Audited** as two rows, each where its mutation happens:
`user.adminResetCredential` (before/after carrying `status` / `hasPassword` /
`mustChangePassword`, so "had a password, now does not" is stated rather than
inferred) and §3.5's `user.issueActivationCode`. Neither carries the code, its
hash, or a password; nor does any log line or error message.

**The path is unchanged** (`POST /users/{id}/reset-password`): from the admin's
side the operation is still "reset this user's password", and renaming would
churn the contract, the client and the permission model for no behavioural gain.
The handler moved to `CredentialSlipController`, beside the class batch, because
it is now a slip issuance — same admin rule, same language resolution, same PDF
response, same reason for being a POST.

## The other mechanism

§2.4's point is that there should be **one** credential flow. After this fix
there are still two: `POST /auth/forgot-password` → `POST /auth/reset-password`,
backed by V3's `password_reset_token` table, `PasswordResetToken`,
`PasswordResetTokenRepository`, `AuthService.forgotPassword`/`resetPassword` and
`UserService.resetPasswordWithToken`.

Checked rather than assumed:

- **Unreachable from the frontend.** Nothing in `frontend/src` calls either
  endpoint. The login page's "forgot password" affordance is static copy reading
  *"Forgot password? Contact admin"* — which now points at exactly the flow this
  issue rebuilt.
- **Non-functional as deployed.** With no mail infrastructure, `forgotPassword`
  writes the token to the server log as a warning for an operator to relay by
  hand. That is a live credential in a log file, which is the one thing the rest
  of the activation design is careful never to do.
- **Hazardous beside the new reset.** A token minted for a just-reset account
  would set a password on it while leaving it `PENDING_ACTIVATION`: unable to
  log in either way, invisible to §3.6's sweep (which reads a stored hash as
  proof of activation), and having bypassed the slip entirely.

The hazard is closed here with a one-line guard — `forgotPassword` is silently a
no-op for an account with no stored password, silent for the same reason its
unknown-username branch is — and covered by a test with a control.

**Recommendation: remove the mechanism at code level, and drop the table later
as a separate decision.** Removal would delete the two endpoints and their DTOs,
the two `AuthService` methods, `UserService.resetPasswordWithToken`, the entity
and the repository — roughly 120 lines with no caller left behind. A migration
dropping `password_reset_token` is warranted but should wait until the code
removal has been live for a release, so a rollback is a redeploy rather than a
restore; `PurgeQueryRepository` already deletes from the table with raw SQL that
needs no entity, so nothing breaks in the meantime. **Not done in this change**
— removing a public authentication endpoint from a deployed system wants an
explicit decision, not a drive-by.

## Test plan

Every assertion below was verified red by reverting the behaviour it guards, not
just observed green.

| Proof | Test | Revert that turns it red |
| :--- | :--- | :--- |
| No plaintext password anywhere | `an_admin_reset_generates_no_password_and_returns_none` — the stored hash is `null`, and the **serialized** response has no field whose name contains "password" and no bcrypt hash | Reinstating `AdminResetPasswordResponse`'s `temporaryPassword` on the response (red on the field-name sweep); reinstating `generateTemporaryPassword()` (red on the null-hash assertion) |
| Nothing leaks into audit or logs | `the_audit_rows_record_the_reset_without_any_credential_material`, `nothing_logs_the_code_or_a_hash` — the log assertion uses a canary line so a detached appender cannot make it pass vacuously | Same reverts (6 tests red in total) |
| The old password stops authenticating | `the_old_password_stops_working_while_an_untouched_account_still_signs_in` — through a real `DaoAuthenticationProvider`, with an untouched account presenting **the same password string** as the control | Leaving the credential untouched: the target authenticates, the control still passes |
| Exactly one usable code, and it is the one on the slip | `redeeming_the_slips_code_sets_a_new_password_and_reactivates` (the code is **scraped out of the rendered PDF**, hashed, and matched against the stored row before being redeemed), `an_earlier_outstanding_code_is_retired_by_the_reset` | Printing a code that was never issued (3 red); removing `deleteUnredeemedFor` from issuance (1 red) |
| Sessions are revoked | `CredentialSlipControllerTest.a_reset_revokes_every_live_session_of_the_account_it_reset`, `the_admins_own_session_is_not_the_one_revoked`, `a_failed_reset_revokes_nothing` | Removing the `revokeAllSessionsForUser` call (2 red) |
| The forgot-password token cannot resurrect a cleared account | `a_reset_account_cannot_be_recovered_with_a_forgot_password_token`, with a control proving the endpoint does mint tokens for accounts that still have a password | Disabling the guard (red) |

`mvn clean test`: **250 tests, 0 failures, 0 errors** (21 new). `pnpm build`
clean; en/zh key parity re-checked programmatically (1903 keys, identical
order).

Live, not yet performed: reset a real account in Admin Portal → System Admin →
Users, confirm a PDF downloads instead of a toast showing a password, scan or
type the code on the slip through `/activate`, sign in with the newly chosen
password, confirm the old password is refused, confirm the account's other
session was terminated, and confirm `GET /audit-logs` shows
`user.adminResetCredential` followed by `user.issueActivationCode` with no code
in either row. Also worth checking on the live server: that a self-reset and a
reset of a deactivated account both return `409` with readable messages.

## Resolution

Backend:

- `iam/application/UserService.java` / `UserServiceImpl.java` —
  `adminResetPassword` → `resetToPendingActivation`; `generateTemporaryPassword`
  deleted; `CredentialSummary` audit pair added.
- `iam/application/CredentialSlipService.java` / `CredentialSlipServiceImpl.java`
  — `issueForReset` + `ResetSlip`; slip construction and the filename rule
  shared with the class batch.
- `iam/application/dto/AuthDtos.java` — `AdminResetPasswordResponse` removed.
- `iam/application/AuthServiceImpl.java` — the password-less no-op guard on
  `forgotPassword`.
- `iam/web/CredentialSlipController.java` — the endpoint, with revocation;
  `iam/web/UserController.java` — the handler removed.
- Tests: `iam/application/AdminCredentialResetTest.java` (16),
  `iam/web/CredentialSlipControllerTest.java` (5).

Frontend (only what the removed field forced):

- `app/api/client.ts` — `users.resetPassword` returns `{ blob, filename }` via a
  raw `fetch` with manual CSRF, since the response is a PDF rather than the
  envelope `http.ts` unwraps.
- `app/components/admin/SystemAdmin.tsx` — downloads the slip; the toast no
  longer concatenates a password; a `downloadBlob` helper now shared with the
  import-template download.
- `i18n/en.json` / `zh.json` — `admin.system.resetPasswordHint` and
  `admin.system.passwordReset` rewritten in both languages. The dead
  `admin.system.users.*` duplicate block (issue 086) was left alone: its two
  strings are plain labels that assert nothing about a temporary password.

Docs: `Docs/integration/APIContract.md` §4.15 and its `_CN.md` twin;
`Docs/optimization/10-StudentIdentityAndOnboarding.md` §5 (the §3.7 row, the
slice's decision table, and the removal recommendation).
`Design/*/08-mlps-level1-security-design-guide.md` §8.2 was **not** edited — its
claims are now true of the reachable system, and the remaining unreachable token
mechanism is tracked above rather than caveated into an institutional design
document.

*Verified by unit tests only, each one confirmed red against a revert of the
behaviour it guards, plus a clean `pnpm build`. **Verification gap: nothing was
deployed, no integration test (`mvn verify`) was run, and no browser pass
happened this session** — the live checks listed in the test plan are open, and
so is the decision on removing the `password_reset_token` mechanism.*
