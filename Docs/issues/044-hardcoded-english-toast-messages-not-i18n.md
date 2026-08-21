# 044 — Several toast messages were hardcoded English, bypassing i18n entirely

Status: **Fixed and live-verified (2026-08-21)**

## Symptom

Requested directly by the user: a systematic scan of `frontend/src` for any `toast.error`/`.success`/`.info`/`.warning` call passing a hardcoded string or template literal instead of a translated `t(...)` key. Found 8 such call sites across 4 files — every one of them would show English text to a Chinese-language user regardless of their selected language, unlike the rest of the app's toasts.

## Root cause

No systematic issue — these were 8 independent, one-off omissions (not a single copy-pasted bug) accumulated as features were built:

- `SystemAdmin.tsx` (import job result toasts, ×3: no-valid-rows, invalid-row-summary with a nested per-row message, commit-complete count)
- `StudentList.tsx` (×2: negative-value validation for completed hours / time coins)
- `AuditCenter.tsx` (×2: single-approval credited-units toast, bulk-approve succeeded/failed summary)
- `api/client.ts` (×1, notably: `api.eventProposals.review()` — the API client itself calling `toast.success()` directly with a hardcoded string on proposal approval, an architectural outlier since every other toast in the app lives in a component, not the API layer)

## Fix

Added translated keys for all 8 messages (`admin.system.import*`, `admin.students.*Negative`, `admin.audit.approvedWithCredit`/`batchApprovedWithFailures`, `admin.proposals.approvedProjectCreated`) in both `en.json`/`zh.json`, and swapped each call site to use `t(...)`.

`client.ts` needed a different mechanism since it's a plain module, not a React component — no `useTranslation()` hook available. Added `import i18n from '../../i18n'` (the i18next singleton itself, already imported the same way in `api/http.ts` for `i18n.language`) and used `i18n.t(...)` directly, following that existing precedent rather than inventing a new one.

One toast (`SystemAdmin.tsx`'s rule-validation-failed message) was left as a template literal — its static portion already used `t('admin.system.rules.validateInvalid')`; the dynamic portion (`result.issues`) is raw backend validation text, which (like every other backend-supplied error message surfaced via `mapApiError`/`ApiError.message` elsewhere in the app) isn't part of the frontend's i18n system.

## Test plan

- Frontend: `pnpm build`/`tsc --noEmit` clean; re-ran the same scan pattern after the fix and confirmed zero remaining hardcoded-string/template-literal toast calls (except the one documented backend-text exception above); i18n key/placeholder parity between `en.json`/`zh.json` confirmed via diff script.
- Live: as `admin001`, triggered the negative-completed-hours validation toast through the real Student Management edit form and confirmed the translated message renders (not the raw literal); cancelled without saving any real change.

## Resolution

Deployed to `https://tb.example.com/` (frontend only). Live-verified via Playwright as `admin001`: opened Student Management's edit-student modal, entered `-5` for Completed Hours, clicked Save — the toast read "Completed hours cannot be negative" (now sourced from `t('admin.students.completedHoursNegative')`, confirmed present and correctly shaped in both `en.json`/`zh.json` via the parity script). No data was actually saved — cancelled the modal afterward.

*Live-verified via Playwright against the real deployed frontend (one representative call site, chosen since it's the easiest to trigger without a real data write); the remaining 7 fixed call sites were verified via build/typecheck + the i18n parity script rather than individually click-triggered, since each follows the identical `t(key, {params})` pattern already proven live by the one that was.*
