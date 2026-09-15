# 064 — Admin "Reset Password" collects a password, validates it, then throws it away

Status: **Fixed and live-verified (2026-09-15)**

## Symptom

Found during the three-student end-to-end verification pass (not reported
by the user): reactivating a dormant student login through System Admin →
Users → Edit User, the dialog's "Reset Password" section was expanded, a
password was typed into both "New password" and "Confirm new password",
and the dialog saved with a success toast. The subsequent login attempt
with that password failed with `AUTHENTICATION_REQUIRED`, and the
account's `failed_login_attempts` incremented — i.e. the account was fine,
the password simply was not the one that had been typed.

## Root cause

`SystemAdmin.tsx`'s edit dialog offered two password fields and validated
them (non-empty, ≥ 6 characters, both matching), but `handleEdit` never
sent the value anywhere:

```tsx
if (form.showResetPwd && form.password) {
  const result = await api.users.resetPassword(editTarget.id);
  toast.success(t('admin.system.passwordReset') + (result.temporaryPassword ? `: ${result.temporaryPassword}` : ''));
}
```

`api.users.resetPassword(id)` takes only an id, because
`POST /users/{id}/reset-password` takes no request body at all
(`UserController`) — `UserServiceImpl.adminResetPassword` always generates
a fresh random temporary password server-side and returns it. That is the
contract's intended design (APIContract §7, "Admin-triggered reset"): an
administrator resets an account, they do not choose someone else's
password.

So the backend was behaving correctly and the frontend was asking a
question whose answer it then discarded. The generated password *was*
appended to the success toast, but an admin who typed a password into two
labelled boxes reasonably reads that toast as confirmation, not as "here
is a different password you now need". The two confirmations even appeared
as separate toasts ("User information updated" / "Password has been
reset: …"), making the generated value easy to miss entirely.

Reproduced deliberately during verification: a password was typed into
both boxes, and the toast came back reading `Password has been reset:` with
a completely different, server-generated value. (Neither is quoted here —
the generated one is a live credential on the shared test server; it lives
only in the untracked `Docs/TestAccounts.md`.)

## Fix

Frontend-only; the backend and the contract are already right, so nothing
there changed and no new endpoint was invented.

The two password inputs are gone. "Reset Password" is now an explicit
arm-the-reset toggle (checkbox affordance rather than a disclosure
chevron) that, when armed, explains exactly what will happen:

> On save, the system generates a new temporary password for this user and
> shows it to you once. Pass it on to them — they can change it after
> signing in. You cannot choose the password here.

`handleSave`'s edit-mode password validation is removed (there is nothing
left to validate) and `handleEdit`'s guard drops the now-meaningless
`&& form.password`.

## Test plan

- `pnpm build` clean.
- Live Playwright pass against the deployed server as `admin001`: opened
  Edit User for a real account and expanded "Reset Password" — confirmed
  the section now contains zero `input[type=password]` elements and shows
  the explanatory text instead. Cancelled without resetting (the account's
  current password is recorded in the untracked `Docs/TestAccounts.md`).
- The reset path itself was exercised for real before the fix, which is
  how the bug was found and how the generated-vs-typed divergence was
  proven.

## Resolution

`frontend/src/app/components/admin/SystemAdmin.tsx` — misleading password
inputs replaced with an explicit toggle plus a description of the real
behaviour; edit-mode password validation removed.
`frontend/src/i18n/{en,zh}.json` — new `admin.system.resetPasswordHint`.

The now-unused `admin.system.newPasswordPlaceholder` /
`confirmNewPassword` / `errorNewPasswordRequired` keys were deliberately
left in place: they are still used by the *create* dialog's initial-password
fields, which really do send what is typed (`initialPassword: form.password`).
