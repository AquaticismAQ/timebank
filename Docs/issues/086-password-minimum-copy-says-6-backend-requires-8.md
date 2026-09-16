# 086 — The UI asks for a 6-character password the backend will reject

Status: **Fixed — not yet live-verified**

## Symptom

Creating a user in Admin Portal → System Admin → Users, the Initial Password
field's placeholder reads **"At least 6 characters"** / **"至少 6 位"**. An
admin who follows that instruction literally — six or seven characters — gets
past the form's own validation and then straight into a server-side validation
error on `POST /users`, with no indication that the instruction they were
given was the thing at fault.

The UI is telling the user a rule, and the rule is wrong.

## Root cause

The frontend's stated minimum was never reconciled with the backend's. The
backend requires **8** in all three places a password can be set:

| File | Field | Constraint |
| :--- | :--- | :--- |
| `iam/application/dto/AuthDtos.java:16` | `newPassword` (change password) | `@Size(min = 8, max = 128)` |
| `iam/application/dto/AuthDtos.java:23` | `newPassword` (reset password) | `@Size(min = 8, max = 128)` |
| `iam/application/dto/CreateUserRequest.java:35` | `initialPassword` | `@Size(min = 8, max = 128)` |

There is no other password policy on the backend — no complexity or character-
class rule — so `min = 8` is the whole of it, and 8 is correct.

The frontend disagreed in **two** ways, and copy was only half of it:

1. **Copy** — six strings across `en.json`/`zh.json` claimed 6.
2. **A real client-side gate** — `SystemAdmin.tsx:519`:

   ```tsx
   if (form.password.length < 6) { toast.error(t('admin.system.errorPasswordTooShort')); return; }
   ```

   This is what makes the bug reachable rather than merely misleading. Had the
   check been absent, a 6-character password would still have failed, but
   fixing only the copy would have left this line waving the bad input through
   to the server — a half-fix that looks correct in the diff.

## Fix

8 is now the single source of truth on the frontend. All six copy strings and
the one numeric check were changed; **nothing under `backend/` was touched.**

| Key / location | Before | After |
| :--- | :--- | :--- |
| `admin.system.passwordPlaceholder` (en) | `At least 6 characters` | `At least 8 characters` |
| `admin.system.newPasswordPlaceholder` (en) | `New password (at least 6 characters)` | `New password (at least 8 characters)` |
| `admin.system.errorPasswordTooShort` (en) | `Password must be at least 6 characters` | `Password must be at least 8 characters` |
| `admin.system.users.passwordPlaceholder` (en) | `At least 6 characters` | `At least 8 characters` |
| `admin.system.users.newPasswordPlaceholder` (en) | `New password (at least 6 characters)` | `New password (at least 8 characters)` |
| `admin.system.users.passwordMinLength` (en) | `Password must be at least 6 characters` | `Password must be at least 8 characters` |
| `admin.system.passwordPlaceholder` (zh) | `至少 6 位` | `至少 8 位` |
| `admin.system.newPasswordPlaceholder` (zh) | `新密码（至少 6 位）` | `新密码（至少 8 位）` |
| `admin.system.errorPasswordTooShort` (zh) | `密码至少 6 位` | `密码至少 8 位` |
| `admin.system.users.passwordPlaceholder` (zh) | `至少6位` | `至少8位` |
| `admin.system.users.newPasswordPlaceholder` (zh) | `新密码（至少6位）` | `新密码（至少8位）` |
| `admin.system.users.passwordMinLength` (zh) | `密码至少6位` | `密码至少8位` |
| `SystemAdmin.tsx:519` | `form.password.length < 6` | `form.password.length < 8` |

The zh spacing inconsistency (`至少 6 位` in one block, `至少6位` in the other)
was preserved rather than normalised — it is pre-existing, cosmetic, and
unrelated to this defect.

## Dead copy found while fixing

Only **three** of the twelve strings above can actually reach a user today.
The rest were corrected anyway, because leaving a wrong number in place for a
future reader to copy is how this bug got here:

- **`admin.system.users.*` is an entire unused duplicate block.** Nothing in
  `src/app` references any `admin.system.users.` key; the live Users modal
  reads the flatter `admin.system.*` keys. It appears to be a leftover from an
  earlier copy pass. Left in place — deleting it is a separate cleanup, not
  part of a bug fix.
- **`admin.system.newPasswordPlaceholder` / `confirmNewPassword` /
  `errorNewPasswordRequired` are dead as of issue 064.** That fix removed the
  admin-chosen "new password"/"confirm" boxes from the edit-user modal,
  because `POST /users/{id}/reset-password` takes no body and always generates
  the password server-side. The copy outlived the inputs.

So the only live path is the **create-user** form: its placeholder and its
`errorPasswordTooShort` toast.

## Other numeric claims checked

Checked every length-related number in the i18n copy against its backend
constraint. One genuine mismatch (this one); no others:

- **Maximum length — no mismatch, but no coverage either.** The backend caps
  passwords at `max = 128`. No frontend copy mentions a maximum and no password
  input carries `maxLength`, so nothing *disagrees* — a >128-character password
  would simply be refused by the server with no warning. Not fixed: that is a
  missing feature, not a false statement, and it is vanishingly unlikely to be
  hit by a human typing an initial password.
- **`username` (`min = 3, max = 64`) and `displayName` (`max = 100`)** are not
  described by any numeric claim in the copy. Nothing to contradict.
- A sweep of all i18n strings containing both a digit and a length word
  (`character`/`位`/`字符`/`length`/`长度`) returned only the six password
  strings listed above.

## Test plan

1. `pnpm build` clean. — **done**
2. en/zh key parity — same keys, same order, structurally identical. — **done**,
   checked programmatically after the edit.
3. Live, not yet performed: open Admin Portal → System Admin → Users → create a
   user, confirm the placeholder reads 8, confirm a 7-character password is
   refused by the form with the corrected message, and confirm an 8-character
   password is accepted end-to-end by `POST /users` rather than bouncing off
   backend validation. Repeat with the interface in Chinese.

## Resolution

- `frontend/src/i18n/en.json`, `frontend/src/i18n/zh.json` — six strings each.
- `frontend/src/app/components/admin/SystemAdmin.tsx` — the `< 6` gate.

*Verified by build and by reading the backend constraints directly; the
client-side gate was found by searching for a numeric comparison rather than
trusting that the copy was the only offender. **Verification gap: no live
browser pass was run this session and this was not deployed** — step 3 above is
open, and a later session should close it rather than assume the build result
covers it.*
