# 113 — Feature Toggles are shown to every admin, and System Info omits the repository

**Status:** **Fixed** 2026-09-20, same day. Filed on Aquaticism's report.
**Scope:** Frontend only. No backend change and no migration — see §3.

## 1. What is wrong

System Admin → Settings opens with a **Feature Toggles** card listing the four
reserved Phase 3/4 features (POV, redemption, borrowing, appreciation) with a
switch beside each. It renders for **anyone who can reach the Settings tab**,
which includes a school admin and, where the permission is granted, a teacher.

These switches decide whether whole phases of the product exist. That is a
system-operator decision, not a school-operations one — the same line already
drawn one card lower, where **School Info** is editable by sysadmin only,
enforced by a hardcoded role-code check that matches the backend's own gate.
Feature Toggles sits above it with no line drawn at all.

**Nothing could actually be switched.** `toggleFeature` has always answered with
a "not yet available" toast rather than calling an endpoint, so this was never a
privilege escalation — the flags are read from the session payload and shown.
It is a misleading control, not an open door, which is why the fix is frontend
only and why there is no backend gap behind it (§3).

Separately: the **System Info** card lists name, version and API version, and
does not say where the source lives. The login page footer already links the
repository; a signed-in administrator has no way to reach it.

## 2. What it should do

- **Hide the Feature Toggles card from anyone who is not a sysadmin**, by the
  same `isSysadmin` role-code check School Info already uses in the same
  component. Hidden rather than shown-disabled: a school admin cannot act on
  these, and a dead switch invites the belief that someone could — the rule
  already applied to one-option export filters and to the student Project
  Center's absent year selector (`Docs/issues/112` §4).
- **Add the repository URL to the bottom of System Info**, hardcoded.

## 3. Why there is no backend half

A gate the UI enforces alone is usually a bug. Not here: there is **no endpoint
to gate**. Feature flags reach the client inside the session payload — every
role needs them, because the student portal uses them to render "Not yet
available" instead of a broken action — and no write path exists in any
controller. When Phase 3/4 makes these switches real, the endpoint that does it
must carry its own `@PreAuthorize`; this change does not create that
expectation, and the card's visibility must not be mistaken for the control.

## 4. How it was built

- The Feature Toggles card is wrapped in `isSysadmin &&` in
  `components/admin/SystemAdmin.tsx`. The variable already existed for School
  Info, so no new concept was introduced.
- `PROJECT_REPO_URL` moved out of `components/auth/LoginPage.tsx` into
  `lib/project.ts`, and both the login footer and the new System Info row read
  it. It is a property of the codebase rather than of a deployment, so it is a
  constant and not another `school_info` field — a second school installing
  this would not change it, and an editable field would only invite the link to
  be pointed somewhere wrong.
- One new i18n key, `admin.system.sourceCode`, in both bundles.

## 5. Verification

Verified on the deployed test server, in a browser, on both roles rather than
only the one being restricted — a check run solely as an admin cannot tell
"hidden from everyone" from "hidden from the right people":

- **`admin001` (school admin):** the Settings tab shows System Info and School
  Info and **no Feature Toggles card**; the four feature labels are absent from
  the page text.
- **`sysadmin001`:** the same tab still shows Feature Toggles with all four
  switches.
- **System Info** ends with a Source Code row linking the repository, for both
  roles, in both language bundles.

## 6. What it must not do

- **Must not disable the switches instead of hiding them.** A control that
  cannot change anything is worse than an absent one.
- **Must not read the repository URL from the API or from `school_info`.** It
  describes the codebase, not the school.
- **Must not be treated as an access control.** §3 — the gate for a future
  write path belongs on that path.
