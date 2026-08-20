# 038 — Time Coin Accounts' global "Manual Adjust" button applied a correction to the wrong student's account

Status: **Fixed and live-verified (2026-08-20)**

## Symptom

Found while live-verifying issue 037's account-adjustment-memo fix against `https://tb.example.com/`: opened Time Coin Accounts, clicked the page-header "Manual Adjust" button (not a per-row one), typed "Liu Yufei" into the Student field, filled in an amount/reason/note, and submitted. The success toast read "Liu Yufei credited 1 time coins" — but the balance that actually changed was **Li Xiaoyu's** (2.5 → 3.5 coins), a different, unrelated student. Liu Yufei's own balance never moved.

This is a real, serious defect: any admin using the page-header "Manual Adjust" entry point (as opposed to expanding a specific student's row and using *that* row's "Manual Adjust" button) could silently correct the wrong student's time-coin balance while believing — from the toast alone — that it worked as typed.

## Root cause

`AccountManagement.tsx` held two separate pieces of state for the adjustment target: `adjustStudentId` (the real id sent to the API) and `adjustStudent` (a display string). The **only** place that ever called `setAdjustStudentId` was the per-row "Manual Adjust" button (pre-filling both from the already-known `student` object it closed over). The page-header button just called `setShowAdjust(true)` with no student context, and the modal's "Student" field was a plain `<input type="text">` bound only to `adjustStudent` — typing into it never touched `adjustStudentId`, which stayed `''` (or, if the modal had previously been opened from a specific row and cancelled, whatever stale id was left over from that).

The submit handler then called `api.accounts.get(adjustStudentId)`. With an empty (or stale) id, the backend's `GET /accounts?filter[studentId]=...` resolution didn't error — it silently fell back to returning *some* account (in this instance, Li Xiaoyu's, apparently whichever the query resolved to at all when the filter didn't cleanly match), and the adjustment was applied there. The toast's success message was built from `adjustStudent` (the typed text) with no cross-check against which account actually got written to, so it displayed the intended name regardless of where the money really went.

## Fix

Replaced the free-text `<input>` with a real `SearchPicker` (the same debounced-search, resolves-to-a-real-id component already used for co-organizers/mentor-teacher/partner-org pickers elsewhere in the app — `Docs/optimization/01-i3-partner-school-terminology.md`), backed by `api.students.directory(search)`. Its `onChange` sets both `adjustStudentId` and `adjustStudent` together, exactly matching how the per-row button already did it — so there is now exactly one way this pairing gets set, not two (one correct, one silently broken). The header "Manual Adjust" button now also resets `adjustStudentId`/`adjustStudent`/`adjustAmount`/`adjustReason`/`adjustNote` when opening, closing a secondary staleness path (a cancelled per-row adjustment leaving its id behind for a subsequent header-button open). Submit validation now checks `adjustStudentId` (a real resolved id) rather than `adjustStudent` (display text that could be non-empty while still unresolved).

**Immediate data correction:** the erroneous +1 coin (`txn_eg627h3ggcb37hhh4fdrxr2yj2`) on Li Xiaoyu's live account was reversed via the ledger's own `POST /account-transactions/{id}/reverse` endpoint (narrated and confirmed with the user first) — never edited or deleted, per this app's append-only ledger invariant. Her balance is back to 2.5 coins, matching pre-test state. Liu Yufei's account was never touched by the original error and needed no correction.

## Test plan

- Frontend: `pnpm build` clean.
- Live: as `admin001`, opened the header "Manual Adjust" button, typed a partial student name, confirmed the picker shows real matching students and requires an explicit selection (no submit possible on typed-but-unselected text); performed a real adjustment through the fixed picker and confirmed via the account's transaction history that the *correct* student's balance changed.
- No backend change was needed for this bug (frontend-only both in the defect and the fix); backend's `mvn test`/`mvn verify` results from issue 037's fix are unaffected and still apply.

## Resolution

Deployed to `https://tb.example.com/` as part of v0.1.6 (same deploy as issue 037). Live-verified via Playwright as `admin001`, both narrated and confirmed with the user first: opened the page-header "Manual Adjust" button, typed "Zhou Junjie" into the new picker, and confirmed it now shows a real dropdown of matching students requiring an explicit selection (previously, typing did nothing at all to the resolved id). Attempting the adjustment against Zhou Junjie surfaced a separate, pre-existing, unrelated gap: he has never earned any coins and so has no `account` row yet, and `api.accounts.get()` throws a plain (non-`ApiError`) `Error` in that case, which the UI presents as a generic "Something went wrong" toast rather than a clear message — this is not part of what this issue's fix touches, so it wasn't fixed here, only noted. Retried against Zhang Ming (an existing account) instead: a real +1 coin adjustment landed correctly on *his* balance (4.5 → 5.5 coins, confirmed via a direct API read of his transaction history, not just the toast), proving the picker now resolves to the account it displays. Reversed that verification transaction immediately via the ledger's reverse endpoint.

*The original erroneous transaction on Li Xiaoyu's account is fully corrected (reversed on the real ledger, confirmed via a direct API read showing `balanceAfter` back to 250). The verification transaction on Zhang Ming's account is likewise fully reversed (confirmed `balanceAfter` back to 450). No net live-data change remains from either this issue or issue 037's verification work.*
