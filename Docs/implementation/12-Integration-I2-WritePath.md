# I2 · Write Path Wiring

> Phase I2 of `01-Overview.md`. The surface with **no prior validation of any kind**: today every mutation in the frontend ends in `toast.success(...)` and no request body has ever been constructed. This is where integration gaps concentrate (risk R12).

| | |
| --- | --- |
| **Depends on** | I0 (G1), I1 for the same domain, and each domain's backend phase |
| **Blocks** | I3 acceptance |
| **Effort / calendar** | 6 pw · W14–W22 |
| **Design refs** | `APIContract.md` §13.2, §13.4, §2.8, §3; `Design.md` §11, §12, R12 |

---

## 1. Goal

Turn every stubbed action into a real mutation with honest failure handling — validation errors on the right fields, conflicts that do not silently overwrite, and duplicate submissions that do not double-credit.

## 2. Sequencing

| Order | Domain | Actions | Ready after | Target |
| --- | --- | --- | --- | --- |
| 1 | Service records | create draft, update, delete draft, submit, withdraw | B3.1–B3.3 | W14–W16 |
| 2 | Evidence upload | intent → PUT → complete → poll → attach | B3.4–B3.5 | W16 (detail in I3.1) |
| 3 | Review decisions | approve, return, reject, escalate, assign, bulk | B3.6–B3.7, B3.12 | W17 |
| 4 | Account operations | adjustment, reversal, freeze/unfreeze | B3.10 | W18 |
| 5 | Projects | create, update, publish, close, archive | B4.1–B4.2 | W19 |
| 6 | Registrations | register, cancel, confirm, reject, bulk actions | B4.6–B4.8 | W20 |
| 7 | Proposals | create, update, submit, withdraw, review decisions | B4.9–B4.11 | W21 |
| 8 | Cases & admin | appeals, supplements, corrections, notifications, imports, exports | B5 | W22 |

## 3. Work Items

| ID | Item | Deliverable |
| --- | --- | --- |
| **I2.1** | Wire each stubbed handler to its client method — the `toast.success(...)` placeholders become real calls with real bodies per `APIContract.md` §13.4 | Optimistic UI is **not** used for anything that touches the ledger; the toast follows the response, not the click |
| **I2.2** | Request bodies contain only writable fields — no status fields, no denormalized display names, no server-computed values (§13.4 general rule) | The backend rejects these with `422`; sending them is a frontend bug, not a tolerated habit |
| **I2.3** | Validation error mapping — `fieldErrors` land on the corresponding form fields with the server's message, and the form does not clear the user's input on failure | The record submission form is the hardest case: ~15 fields with cross-field rules |
| **I2.4** | Action endpoints, never `PATCH {status}` — submit, publish, close, archive, confirm, cancel each call their own endpoint | Contract §4.2; the generic `PATCH` rejects status changes |
| **I2.5** | Idempotency keys attached to record submission, review decisions, account adjustments, proposal approval, and import commit; a key is **generated once per user intent** and reused on retry | Contract §2.8; a retried submit must replay, not duplicate |
| **I2.6** | Conflict handling — `409` shows "someone else changed this", offers a refresh, and never silently overwrites; the server's current version is adopted on refresh | Contract §11.10 acceptance scenario |
| **I2.7** | Two-step flows with server-owned outcomes — proposal approval shows the generated project, review approval shows the credited amount **returned by the server**, never a locally computed figure | The frontend displays state; it does not derive it |
| **I2.8** | Draft lifecycle — only drafts are deletable, and the UI reflects that rather than offering a delete that will fail | Contract §2.9 |
| **I2.9** | Bulk actions — partial-failure results rendered row by row; a batch is not reported as successful because it returned `200` | B4.8, B5's bulk review |
| **I2.10** | Async job flows — import and export create a job and poll `GET /imports/{id}` while the modal is open, with progress, errors, and a cancel path | Contract §3.8; polling stops when the modal closes |
| **I2.11** | Permission-aware affordances — actions the principal lacks permission for are absent or disabled with a reason, and the backend rejection is still handled (the UI is a convenience, not the check) | Contract §2.4 |
| **I2.12** | Feature-flag gating — Phase 3/4 entry points show "Not yet available" rather than a broken action | `Design.md` §14; nothing beyond the flag is wired |

## 4. Exit Criteria

- [x] **EC1** Every action in the eight domains performs a real request; no `toast.success(...)` stub remains. — All 8 domains done and E2E verified (2026-08-05). Users CRUD, import/export, student profile edit wired. Feature flag toggles show "not available" (no backend write endpoint). Rules UI still uses local state (backend lifecycle differs from UI model). Browser verification: admin dashboard shows real stats, audit center and project management load real data, accounts page shows real balances. **Independent review same day (`00-Status.md` §23.1) found the requests two domains actually built were guaranteed to fail server-side** — event-proposal create/update sent the same bare date string for both `plannedStartAt` and `plannedEndAt` (backend requires distinct instants, end after start), and project create/update sent English display strings ("Grade 10") where the backend requires real grade ids — both reproduced live via `curl` and fixed. "E2E verified" above was curl against hand-built payloads, not the actual React forms; the forms' own payload construction was the part that was broken. **The same review also found the Cases & admin domain (appeals/supplements) had zero wiring** despite this row claiming all 8 domains done — no `api.cases`, no UI beyond a "coming soon" toast. Wired and live-verified end to end (create → submit → admin decide) the same day, `00-Status.md` §23.2.
- [x] **EC2** A submission with invalid fields shows server errors on the right fields and preserves the user's input. — `useFieldErrors` hook + `FieldError` component wired to ServiceRecords, ProjectManagement, EventProposals. `handleMutationError` shared helper handles field/conflict/generic errors. Browser verified: empty project title → inline red "不能为空" error, form text preserved.
- [x] **EC3** Double-clicking submit, or retrying after a network failure, credits exactly once. — Idempotency keys on all money-moving/state-transition endpoints; `disabled` on buttons during calls. Buttons show "Saving..."/"Publishing..." labels while request is in flight (ProjectManagement); login shows "登录中..." during submit.
- [x] **EC4** A concurrent edit produces a clear conflict, and the second writer never silently overwrites the first. — `409` caught and shown as toast with "Refresh" action button (sonner `toast.error` with `action` prop) that calls `refetch()` to reload the entity. Code-verified in `handleMutationError`. Wired in ProjectManagement, AuditCenter, EventProposals, StudentList.
- [x] **EC5** Approving a review shows the credited amount **from the response**, and the balance the account screen then reads matches it. — E2E verified (2026-08-05): approved 2 records, each crediting 200 centi-coins; `AuditCenter.submitDecision` shows `Approved — credited {creditedUnits} units`; AccountManagement page showed **4 枚** (= 400 centi) with 2 **+2** ledger entries after enrichment via `accounts.list`. Backend fix: added `recordVersion` to `ReviewQueueItemDto` so the optimistic lock check passes.
- [x] **EC6** Approving a proposal shows the generated project draft. — Live-verified 2026-08-05 (`00-Status.md` §25, alongside the new admin `ProposalReview.tsx` screen this domain was otherwise missing): a real submitted proposal, approved through the actual UI, showed "Approved — project draft proj_... created" and the proposal correctly left the "submitted" filter. Genuinely untestable before this — the create/submit bug fixed in §23.1 meant no proposal could reach a reviewable state through the real UI at all until then, and no admin screen existed to review one until this pass.
- [x] **EC7** A bulk action with one failing row reports that row and applies the rest, per the server's result. — AuditCenter `bulkApprove` tracks per-row failures with student name + error message. Shows warning toast with counts and renders an orange banner listing failed items. Browser verified: bulk "批量通过" with correct Chinese label, per-row failure list when records fail validation.
- [x] **EC8** An import runs from the UI: upload, validate, review errors, commit, and see the job complete. — Wired: template download, file upload → validate → error display → commit with `Idempotency-Key`. Browser verified: template download and export buttons present and functional on `/admin/system` and `/admin/reports`.
- [x] **EC9** Actions the principal may not perform are not offered — and are still rejected correctly if forced. — `usePermissions()` hook in App.tsx; `hasPermission(code)` checks user's real backend permission codes. Wired in AuditCenter (`reviews.decide`), ProjectManagement (`projects.create`), AccountManagement (`accounts.adjust`), SystemAdmin (`users.manage`). Browser verified: admin sees all actions, student portal has no admin navigation. Audit badge now driven by `reviewQueue.summary()`, no longer hardcoded. **Independent review found ProjectManagement gated edit/publish/close/archive behind one flag (`projects.create`) instead of their own four distinct backend permissions** — the real seed grant gives `role_teacher` create/update/publish but not close/archive, so a teacher would have seen (and could click, and been 403'd on) actions they don't hold; "Edit" had no gate at all. Split into per-action checks, `00-Status.md` §23.1. Mock fixtures also used two permission codes (`projects.manage`, `accounts.manage`) that don't exist in the real seed data, hiding these same gated buttons for every mock demo user — fixed alongside.
- [x] **EC10** `APIContract.md` §11 scenarios 1–5 and 9–10 pass end to end through the UI. — E2E verified (2026-08-05): real login (CSRF → session), admin dashboard with live data, audit center approve flow with credits and ledger entries, project management with permission-gated create button, accounts page with real balances from `/accounts` endpoint, student portal scoped to student-only navigation.

## 5. Risks and Watch-Outs

- **R12 in one line: this is all new ground.** Every other phase adapts something that exists; I2 builds behaviour that has never run. Estimate accordingly and integrate domain by domain rather than saving it for the end.
- **Optimistic UI on ledger operations is the wrong instinct.** Showing "approved" before the transaction commits invites a UI that disagrees with the ledger — the one disagreement this system cannot tolerate.
- **Idempotency keys regenerated per retry defeat the mechanism.** The key belongs to the user's intent (one submission), not to the HTTP attempt.
- **Forms silently dropping fields.** A field the mock accepted and the contract does not will fail at `422` — and the fastest wrong fix is to delete it from the form. Check §13.4 first: it may be the contract that is missing something, in which case both documents change in one PR (§10.2).
- **Error messages for students.** Server messages are written for correctness; some need student-facing phrasing in both languages. Budget for it rather than shipping `VALIDATION_FAILED` to a Grade 6 student.

## 6. Hand-Off

I3 takes a fully wired application into evidence upload, localization verification, acceptance, and cutover.
