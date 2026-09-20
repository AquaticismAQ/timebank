# 118 — Validation messages are not translated

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's
report, immediately after issue 117.
**Reported as:** missing i18n for "Please fill out this field".

## 1. What is wrong

Issue 117 made the proposal form's required markers real by putting `required`
on the controls and calling `reportValidity()`. The check works; the sentence it
produces does not belong to us.

**The browser writes constraint-validation messages in the browser's own UI
language, not the document's.** `<html lang>`, `Accept-Language` and i18next are
all invisible to it. So a student reading a Chinese page in an English Chrome is
told *"Please fill out this field."* — and the reverse, for staff whose browser
is Chinese and whose portal is English, produces the same mismatch in the other
direction. Nothing in the app can influence it, because nothing in the app wrote
it.

The one hook the platform provides is `setCustomValidity`, which replaces the
message outright.

## 2. It is not just the proposal form

The same untranslated sentence is reachable from every form in the app that
uses native validation:

| Where | How it surfaces |
| :--- | :--- |
| `EventProposals.tsx` | `reportValidity()` on Next (issue 117) |
| `ServiceRecords.tsx` | `reportValidity()` on Next, **and** the browser's automatic check on the final `type="submit"` button |
| `LoginPage.tsx` | the automatic check — the **first screen anyone sees** |
| `ActivationPage.tsx` | the automatic check |
| `PageControls.tsx` | `min`/`max` on the page-jump box, on Enter |

How reachable each of those is varies, and it is worth being exact rather than
alarmed:

- **`LoginPage` is fully exposed**, and it is the front door. It has no check of
  its own — `required` is the only guard — and it has the keys
  (`auth.login.accountRequired`) without ever using them.
- **`PageControls` is exposed**: type a page past the last one and press Enter.
- **`ActivationPage` is not**, because `canSubmit` disables the button until the
  code is well-formed and the passwords match. Its own localized errors are
  unreachable for the same reason, so nothing there is currently wrong; it only
  matters if that gate is ever loosened.
- **`ServiceRecords` is barely** — Next is likewise gated on `checkValidity()`,
  so the bubble is mostly theoretical there. The submit path is not gated.

## 3. What it should do

**Every message the user can see comes from the bundles.** A shared
`lib/validity.ts`:

- `reportLocalizedValidity(form, t)` — finds the first offending control, sets a
  translated message on it with `setCustomValidity`, and reports it. Same
  behaviour as `form.reportValidity()`, same anchored bubble and focus, our
  words.
- `clearCustomValidity(form)` — retracts them.

Five keys under `common.validation`, covering what these forms can actually
produce: `required`, `select`, `min`, `max`, and a catch-all `invalid`. No more;
a key per hypothetical `ValidityState` flag would be copy nobody ever reads.
`select` is separate from `required` because the browser distinguishes them too,
and "fill this out" is wrong advice for a dropdown.

Forms that already validate themselves get `noValidate` and keep their own
messages — `ActivationPage` is that case. `LoginPage` gets `noValidate` *and* a
call to the helper, because removing the native check there without adding one
back would be removing the only guard it has.

## 4. The part that needs care

A custom validity message **is itself a validity failure for as long as it is
set**, and only a real `input`/`change` event should retract it.
`ServiceRecords.tsx` gates its Next button on `checkValidity()`, and one of its
controls is `TimeInput`'s hidden proxy, whose value is driven by React and which
therefore never fires those events. Left set, a single failed check would
disable Next permanently — a worse bug than the one being fixed.

So the helper clears on `input`/`change`, and the `checkValidity()` gate clears
before it asks.

## 5. Verification

Against the deployed build, in a Chromium whose UI language is **en-US** while
the page is Chinese — the condition that produces the bug. The first line is the
red-check: without it, a Chinese message proves nothing, because a Chinese
browser would have said that by itself.

```
native message in this browser: "Please fill out this field."   ← the control group
login: still on /login: true
login: message on empty username: "请填写此项"
login: succeeds with real credentials: /student/home
proposal: blocked with title empty: true
proposal: message: "请填写此项"
proposal: message retracted after typing: ""
proposal: advances once filled: true
```

The page-jump box, which is where `min`/`max` actually surfaces:

```
max pages: 3
message past the last page: "请输入不大于 3 的数值"
```

And §4, by planting on `TimeInput`'s proxy exactly what a failed check leaves
there — the plant is the state, not a simulation of it:

```
records: Next is disabled while the step is empty: true
records: step valid, Next enabled: true
records: planted message is a real failure: true
records: form valid again after a change: true
records: Next still enabled: true
```

Rows 2 and 5 are the ones that matter alongside the rest: a guard that blocks
everything is not validation, and a button that never re-enables is worse than
the message it was protecting.

**Not exercised:** the `select` branch. Both `required` dropdowns sit behind
`ServiceRecords`' disabled Next, so there is no way to make one report from the
UI today. It is three lines mapping a standard flag in a helper whose whole job
is mapping those flags, and the alternative is telling someone to "fill out"
a dropdown — but it is untested, and should be checked the first time a form
puts a required `<select>` in front of an ungated button.

## 6. What it must not do

- **Must not translate by guessing the browser's wording.** Matching strings
  against "Please fill out this field" would break on the next Chrome release
  and on every browser that words it differently.
- **Must not drop `noValidate` on a form with no check of its own.** That trades
  a bad message for no validation (§3, `LoginPage`).
- **Must not leave a custom message set.** §4.
- **Must not add a key per validity flag.** Only the ones these forms can reach.
