# 041 — System Admin's "System Settings" tab showed fabricated Feature Flags and School Info data

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Found during a systematic scan of `frontend/src` for other client-only mock data, prompted directly by the user after issue 040. Two separate fields in System Admin → "System Settings" were fake regardless of real backend state:

- **Feature Toggles** (POV/Redemption/Borrowing/Appreciation): always rendered as off, no matter what the real backend flags actually held.
- **System Info** panel: "Admin Contact" always showed the literal string `"Wei Chen / Mei Zhao"` and "Office Location" always showed `"H300 / H504"` — two hardcoded example values, not read from anywhere.

## Root cause

- `SystemAdmin.tsx` declared its own local `features` state (`useState({ pov: false, redemption: false, borrowing: false, appreciation: false })`), never populated from anywhere — even though `User.featureFlags` (the real value from `GET /me`, already flowing through `useAuth()`) carries the actual flag state and is used correctly elsewhere (e.g. Phase 3/4 gating checks). The toggle switch itself is legitimately a no-op (`toggleFeature` shows a "not yet available" toast — there is no real write endpoint, matching this repo's Phase 3/4 feature-flag convention), but the *displayed* on/off state should have come from the real value and never did.
- The same component's `useEffect` called `api.school.getInfo()` (the real `SchoolInfoDto`, which already carries `contact`/`studentDevCenter` — confirmed used correctly in `LoginPage.tsx`/`ProfilePage.tsx`/`AdminProfilePage.tsx`) but discarded everything except `.name` into a `schoolName` string, then hardcoded the "Admin Contact"/"Office Location" rows as fixture-looking literals instead of the real `contact`/`studentDevCenter` fields.

## Fix

- `features` is now derived directly from `user?.featureFlags` (no local state, no possibility of drifting from the real value).
- `schoolInfo` is now stored as the full `SchoolInfo` object (not just `.name`); "Admin Contact"/"Office Location" now render `schoolInfo?.contact`/`schoolInfo?.studentDevCenter`.

## Test plan

- Frontend: `pnpm build` clean.
- Live: as `admin001`, confirm System Settings shows the real feature-flag state (all off, matching the real, unimplemented Phase 3/4 backend state) and the real school contact/office fields instead of the two hardcoded names.

## Resolution

Deployed to `https://tb.example.com/` (frontend only). Live-verified via Playwright as `admin001`: "Admin Contact" now shows "Teacher Wei Chen" (the real `SchoolInfoDto.contact` value) instead of the hardcoded "Wei Chen / Mei Zhao"; "Office Location" shows "Student Development Center H300" (the real `studentDevCenter` value, matching the login page's footer) instead of "H300 / H504". Confirmed the feature-toggle display is now genuinely reading real state (not coincidentally matching) via a direct `GET /me` call showing `featureFlags: {pov: false, redemption: false, borrowing: false, appreciation: false}` — matching what's rendered.

*Live-verified via Playwright against the real deployed frontend+backend. No live data was written or needed reverting — this was a pure read-path fix.*

## Known gap found in the same scan, not fixed here

The same tab's "Rule Settings" section (`INITIAL_RULES`, `handleRuleSave`) is entirely disconnected from the real backend `rules` module — `api.rules` doesn't exist anywhere in `client.ts`, despite a fully-built backend module (`cn.timebank.rules`, versioned draft → validate → publish → archive lifecycle, contract §4.12) existing. Editing a rule in this tab only ever mutates client-side React state (`INITIAL_RULES`), reset on every reload, and never calls any real endpoint. This is a substantially larger fix than the two above (a flat "edit 6 numbers" UI would need to become a versioned-lifecycle UI to match the real backend model, not just a data-source swap) — flagged here rather than built silently; needs an explicit scope decision before starting.
