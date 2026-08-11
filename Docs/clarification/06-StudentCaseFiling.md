# Student-Facing Case Filing (Appeal / Supplement / Manual Correction) — Clarification & Implementation Plan

## 0. Scope and how to read this

Admin/teacher review of `Case` records (appeals, supplement requests, manual corrections) is complete — `CaseReview.tsx`, built during `04-DataViewOptimization.md`, then decluttered/redesigned during `05-SearchFilterSortConsistency.md` §5. What's missing is the **student-facing side**: the entry points a student actually uses to *create* a case in the first place.

This file follows the `01`–`05` convention: **Finding** (verified fact, no decision needed), **Settled** (implementation detail I'm deciding directly, not a product fork), **⚠️ Decision needed** (a genuine fork with 2–3 real options, needs your call before I build anything). Every claim below is grounded in `file:line`, verified by reading the actual code this session, not inferred from `APIContract.md` or memory.

## 1. What already exists (Finding)

### 1.1 Backend — fully built for all three case types, nothing blocking

- `Case` entity (`backend/.../caseflow/domain/Case.java:23-110`, table `case_record`): `type`, `studentId`, `gradeId`/`classId` (denormalized), `subjectType`/`subjectId` (nullable), `title`, `description`, `requestedAmountCentiCoins`, `reasonCode`, `status`. `status` has no public setter — only `CaseStateMachine.transition` can change it.
- `CaseType` (`domain/CaseType.java`): `APPEAL | SUPPLEMENT | MANUAL_CORRECTION`.
- `CaseStatus`/state machine (`domain/CaseStateMachine.java:22-29`): `DRAFT --(owner)--> SUBMITTED --(reviewer)--> IN_REVIEW --(reviewer)--> RESOLVED | NEEDS_INFORMATION | REJECTED`, and `NEEDS_INFORMATION --(owner)--> SUBMITTED` (resubmit after being asked for more info).
- `POST /cases` (`cases.create`) **is granted to `role_student`** (`R__seed_permissions_and_roles.sql:31,78`), same as `role_teacher`/`role_admin`. `GET /cases` (`cases.read`) is also granted to `role_student` (`:30,79`), and `CaseScopeFields.ownerIdPath` (`caseflow/application/CaseScopeFields.java:14-16`) scopes it to the caller's own `studentId` — **a student calling `GET /cases` today already sees only their own cases, correctly scoped, with zero backend work needed.**
- `CreateCaseRequest` (`application/dto/CaseRequests.java:18-21`): only `type`/`title`/`reasonCode` are required; `studentId` is optional and defaults to the caller's own student id when omitted (`CaseServiceImpl.java:105-108`) — a student never needs to know or send their own id.
- Case creation always lands in `DRAFT`; a separate `POST /cases/{id}/submit` call moves it to `SUBMITTED` (`CaseServiceImpl.java:160-177`).
- **Manual correction is the one case type that touches the ledger.** Resolving a `MANUAL_CORRECTION` case (`CaseDecisionTransaction.java:88-96`) directly calls `AccountService.adjustForStudent(...)` with `requestedAmountCentiCoins` — appeal/supplement resolutions are "communication + tracking only" (line 88's comment, citing `Design.md` Q6). A resolve on a manual-correction case with no `requestedAmountCentiCoins` set throws (`:90-93`).
- Notification templates for all three decision outcomes already exist and are worded generically for **both** appeal and supplement (`R__seed_base_data.sql:132-134`: `case.resolved`/`case.needsInformation`/`case.rejected`, English text literally says "Your appeal/supplement has been resolved") — the schema was clearly designed with supplement getting real UI eventually, it just never happened.

### 1.2 Frontend — only "Appeal" exists, and only for rejected records

- The **only** student-facing case-creation code anywhere in the frontend is inlined directly in `frontend/src/app/components/student/ServiceRecords.tsx:47-76` (state) and `:951-972` (modal JSX) — there is no separate `CaseModal`/`AppealModal` component.
- It hardcodes `type: 'appeal'`, `subjectType: 'serviceRecord'`, `reasonCode: 'STUDENT_APPEAL'` (`:57-63`), takes only a free-text reason, and calls `create` immediately followed by `submit` (`:57,65`) — the draft state is invisible to the student, "file" and "submit" are the same click.
- The button that opens it only renders when `rec.status === 'rejected'` (desktop `:837-842`, mobile `:936-939`). A `returned` record gets a completely different, Case-independent "Resubmit" button instead (`:819-824`/`:920-923`) that reopens the record's own edit form — no `Case` is ever created for a `returned` record today.
- **No UI exists anywhere for `type: 'supplement'` or `type: 'manualCorrection'`** — confirmed by grepping the whole `frontend/src/app/` tree; `api.cases.create`'s only caller is the one appeal flow above.
- **No "my cases" list/history exists on the student side** — after the one-time success toast, a student has no page to check a filed case's status again except waiting for the eventual decision notification (which does work — `NotificationsPage.tsx` exists and case-decision notifications are real, §1.1).
- Dead code found in passing: `en.json:276`/`zh.json:276`'s `appealComingSoon` key is leftover from a pre-`api.cases` stub, referenced nowhere — will delete as part of this work regardless of which options below are picked.

### 1.3 `APIContract.md` drift (Finding, will fix regardless of decisions below)

- §4.8's endpoint table omits `GET /cases/{id}/revisions` and `GET /cases/{id}/decisions`, both real and `cases.read`-gated (`CaseController.java:96-100,111-116`).
- `subjectType`'s valid values (today, only `serviceRecord` resolves to a real `subjectLabel` — `CaseServiceImpl.java:284-290`) are never enumerated in the contract at all, only implicit in code.
- No `reasonCode` taxonomy is documented anywhere — today it's just a free string; the frontend hardcodes `'STUDENT_APPEAL'` for appeals, admin decisions hardcode `'RESOLVED'/'REJECTED'/'NEEDS_MORE_INFO'` (`CaseReview.tsx:104`). Worth deciding whether student-initiated supplement/manual-correction need their own reason-code conventions, or free text is fine (§2.5 below).

## 2. Decisions needed

### 2.1 Which case types get a real student-creation flow this round?

Appeal already works end to end. Supplement has zero UI despite being fully backend-ready and literally referenced in the seeded notification copy. Manual correction is different in kind — resolving one **directly credits/debits the student's time-coin ledger** (§1.1), which is exactly the kind of "financial" action `CLAUDE.md`'s anti-fraud provisions ask me not to weaken casually. Nothing backend-side currently stops a student from filing a `MANUAL_CORRECTION` case naming their own `requestedAmountCentiCoins` — the resolve step still requires a reviewer's approval before any coins move, but a student being able to *propose* their own adjustment number at all is a real product-policy question, not an engineering one.

**Option A — Appeal (generalize) + Supplement, this round. Manual Correction stays staff-initiated-only (no student UI), matching how it's used in the existing seed data.** Smallest surface that closes the two clearest gaps (§1.2); manual correction can be revisited later with its own dedicated discussion of ledger-request guardrails (e.g., a hard cap, or requiring an existing approved record as evidence) if you ever want students to self-file it.

**Option B — All three types, including Manual Correction, with the same free-text `requestedAmountCentiCoins` field exposed to students.** Most complete, matches the entity model treating all three as peers, but means any student can request literally any number of coins for themselves subject only to a reviewer noticing and rejecting it — no automatic sanity bound exists anywhere in the code today.

**Option C — Appeal (generalize) + Supplement now; Manual Correction later, but *only* after adding a policy guardrail (e.g., cap `requestedAmountCentiCoins` to the record's own `durationMinutes`-derived value, or require it to reference an existing service record and pre-fill the delta rather than a free number).** Same rollout as A, but names Manual Correction as a real planned follow-up with its own safety design, not just "later, maybe."

*My recommendation: A or C — I'd lean C since it keeps Manual Correction on the roadmap explicitly rather than leaving it an open question forever, but the guardrail design is its own decision I'd want to make with you separately, not bundle into this round.*

**Confirmed: Option A — Appeal + Supplement only this round; Manual Correction stays staff-initiated-only, no student UI.**

### 2.2 Should "Appeal" be extended beyond `rejected` records?

Real seeded case data (referenced in earlier session memory, e.g. "Appeal: hours undercounted on library service") implies a student disputing the **credited hours on an already-approved record** — a scenario the current UI can't produce at all, since the Appeal button only renders for `status === 'rejected'` (§1.2). An approved record's hours are presumably final/already paid out in coins, so "appealing" one has different stakes than appealing an outright rejection.

**Option A — Keep Appeal scoped to `rejected` only, as today.** Zero behavior change to an already-working flow; if a student disagrees with an approved record's credited hours, they'd need a different path (e.g., a supplement/general case not tied to a specific record's terminal status) rather than reusing "Appeal" for it.

**Option B — Extend Appeal to also cover `approved` records** (disputing final credited hours), keeping `rejected` as today. Two different `subjectId`-bearing appeal contexts under one button, gated on `status === 'rejected' || status === 'approved'`.

**Option C — Leave Appeal's status-gating alone, but make Supplement (§2.1) the mechanism for the "disagree with an approved record" case** (its `title`/`description` framed as a supplement/adjustment request rather than an appeal) — Appeal stays strictly "contest a rejection," Supplement becomes the general "add/correct something about a record regardless of its current status" entry point.

*My recommendation: C — keeps "Appeal" meaning one specific thing (reject an outright rejection) instead of overloading it, and gives Supplement a clear, distinct reason to exist beyond just "duplicate of resubmit."*

**Confirmed: Option C — Appeal stays scoped to `rejected` only; Supplement is the mechanism for disputing an approved record's credited hours.**

### 2.3 What is Supplement actually *for*, given `returned` records already have a full resubmit flow?

This is the crux of why Supplement was never built even though it's fully backend-ready: a `returned` record already lets a student add new evidence and resubmit *without* going through `Case` at all (`ServiceRecords.tsx`'s resubmit flow, completely separate code path from `api.cases.*`). So a plain "add evidence to my returned record" need is already served. What's actually missing a home:

**Option A — Supplement = "add evidence/context to a record that is NOT `returned`"** (e.g., an `approved` or `rejected` record where the normal edit-and-resubmit path is unavailable because the record is locked in a terminal-ish state) — lets a student say "I have additional proof I didn't originally submit" without it counting as contesting the decision (that's Appeal's job per §2.2-C).

**Option B — Supplement = a standalone request not tied to any specific service record at all** (`subjectType`/`subjectId` left empty) — e.g., "I performed a service that was never logged as a record and I want credit for it," closer to a from-scratch manual-entry request than a follow-up on an existing one.

**Option C — Both A and B are valid; the create form lets a student optionally attach a service record (pre-filling `subjectType`/`subjectId`) or leave it unattached, both landing as `type: 'supplement'`.**

*My recommendation: C — it's the same form either way (§2.4 will need a service-record picker for the attach case regardless, since Appeal already needs one), and doesn't force a student to guess which narrower bucket their situation fits.*

**Confirmed: Option C — one form, optionally attaches a record (`subjectType`/`subjectId`) or files unattached.**

### 2.4 Entry point(s) and navigation

Today the only entry point is a button embedded in a specific record's row. With Supplement potentially unattached to any record (§2.3), a single embedded button isn't enough on its own.

**Option A — Keep per-record buttons only** (Appeal button on `rejected` rows per §2.2's resolution; a new "Supplement" button wherever §2.3 lands), no standalone page. Simplest, matches the existing pattern exactly, but an unattached supplement (§2.3-B) has nowhere to launch from.

**Option B — Add a standalone "File a Request" entry point** (a button on `StudentHome.tsx` or a new small section on the Service Records page's toolbar) that opens the same create form with no record pre-attached, *in addition to* the existing/new per-record buttons for the attached case.

**Option C — Option B, plus a new "My Cases" nav item** (`StudentLayout.tsx`'s nav array, `:24-38`) showing a student's own filed cases and their current status/decision comment — closing the "no history" gap from §1.2. This is a real 9th top-level nav item, not free — worth weighing against just adding a "Cases" tab/section inside an existing page (e.g., Profile) instead of the main nav.

*My recommendation: B for the entry point, and for history — a lighter option than a full new nav item: surface "My Cases" as a section within the existing Service Records page (a new tab, next to whatever the page's current top-level grouping is) rather than a 9th nav link. Full standalone nav item only if you expect case volume high enough to justify permanent chrome real estate.*

**Confirmed: standalone "file a request" entry point + a "My Cases" section folded into an existing page (not a new top-level nav item).**

### 2.5 Reason codes and form fields

Appeal today hardcodes `reasonCode: 'STUDENT_APPEAL'` with a single free-text reason field and no dropdown. Extending to Supplement (and possibly Manual Correction later) raises whether that stays true.

**Option A — Keep it simple: a single free-text field per case type, with a type-specific hardcoded `reasonCode`** (`'STUDENT_APPEAL'`, new `'STUDENT_SUPPLEMENT'`) — no dropdown, no new dictionary. Matches today's pattern exactly, cheapest to build.

**Option B — Add a small reason-category dropdown per type** (mirroring `AuditCenter.tsx`'s admin-side reject/return reason dropdowns, e.g. `admin.audit.reason*` — `AuditCenter.tsx` reason `<option>`s), giving reviewers a filterable, consistent reason taxonomy on the admin side instead of freeform titles. More building (new i18n keys, no backend dictionary endpoint exists for this — would need to be another hardcoded frontend list, since nothing in `APIContract.md` defines one either).

*My recommendation: A — matches the existing, working Appeal pattern; B is a real admin-side UX improvement but is scope creep beyond "give students an entry point that doesn't exist," worth its own future pass once there's real usage data on what reasons actually come in as free text.*

**Confirmed: Option A — free text only, no reason-category dropdown.**

## 3. Implementation plan (once §2 is confirmed)

Sequenced so each phase is independently deployable/verifiable, matching this repo's established pattern:

1. **Extract a shared `CaseFilingModal`/`CaseFilingForm` component** (`components/student/` or `components/shared/`) out of `ServiceRecords.tsx`'s inline appeal state — parameterized by `type`, optional pre-attached `subjectType`/`subjectId`, title/description copy per §2.5's decision. Existing Appeal flow becomes its first caller, behavior-preserving (regression risk: none, same `create`+`submit` calls, same reason field).
2. **Wire Supplement's entry point(s)** per §2.3/§2.4's resolution — new button(s), reusing the component from step 1 with `type: 'supplement'`.
3. **Extend Appeal's status gating** if §2.2 picked B, or leave alone if A/C.
4. **"My Cases" view** per §2.4's resolution (`usePagedApi(api.cases.list, ...)`, already correctly self-scoped server-side per §1.1 — zero backend work) — a read-only list, no decision actions (that stays admin-only), probably close to a slimmed-down version of `CaseReview.tsx`'s own card rendering minus the review-action footer.
5. **`APIContract.md`/`_CN.md` corrections** from §1.3 (missing endpoint rows, `subjectType` enumeration) — bundled here since I'm already touching this surface, not deferred.
6. **i18n**: new keys for supplement copy, delete the dead `appealComingSoon` key (§1.2) from both `en.json`/`zh.json`.
7. **End-to-end live verification** (per `CLAUDE.md`'s live-testing workflow): file a real supplement (attached and unattached, if §2.3-C), confirm it appears correctly in admin `CaseReview.tsx`, decide it, confirm the student sees the resulting notification and (if built) the "My Cases" status update. Any real defect found gets its own `Docs/issues/0NN-*.md` per convention.

## 4. Decision index

| # | Section | Question | Status |
|---|---------|----------|--------|
| 1 | §2.1 | Which case types get real student UI this round | **Confirmed** — Appeal + Supplement only; Manual Correction stays staff-only |
| 2 | §2.2 | Should Appeal extend beyond `rejected` records | **Confirmed** — No; Supplement covers approved-record disputes |
| 3 | §2.3 | What Supplement is actually for | **Confirmed** — Both attached and unattached |
| 4 | §2.4 | Entry point(s) | **Confirmed** — Standalone "file a request" + "My Cases" section (no new top-level nav item) |
| 5 | §2.5 | Reason codes | **Confirmed** — Free text only |

All five confirmed 2026-08-12 — proceeding to §3's implementation plan.

## 5. Implementation status

**2026-08-12 — Complete, `pnpm build` clean, deployed and live-verified against `https://tb.example.com/`.** All 7 steps of §3 done in one pass (frontend-only change, no backend touched — §1.1 confirmed the backend needed nothing new):

- New `components/student/CaseFilingModal.tsx`: shared create+submit modal for `appeal`/`supplement`, replacing the inline state/JSX that used to live in `ServiceRecords.tsx`. No backdrop-click-close (issue 020's convention applied from the start, not retrofitted).
- New `components/student/StudentCases.tsx`: read-only "My Cases" list (`api.cases.list`, already self-scoped server-side, §1.1), expandable rows fetch `api.cases.timeline` on demand.
- `ServiceRecords.tsx`: "Appeal" button unchanged (still `rejected`-only); new "Supplement" button on `approved` rows; new standalone "File a Request" header button (opens Supplement unattached, with an optional picker over the student's own approved/rejected records); new "My Records"/"My Cases" tab switcher, no new top-level nav item.
- `APIContract.md`/`_CN.md` §4.8 corrected: added the two missing endpoint rows (`revisions`/`decisions`), documented `subjectType`'s real behavior and the Manual-Correction-stays-staff-only convention.
- i18n: deleted the dead `appealComingSoon` key; added `supplement*`/`fileRequest`/`myRecords`/`myCases`/`noCases`/`noHistory` to both `en.json`/`zh.json`; reused existing `admin.cases.type*`/`CASE_STATUS_LABELS` for `StudentCases.tsx` rather than duplicating a student-namespaced copy.

Live-verified via Playwright as `student001` (Jia Ming): filed a real attached Supplement on an approved record ("Overnight Watch Program", disputing 2h vs. 3h actual), filed a real unattached Supplement (a service never logged at all) via the picker's "no record" default, confirmed both appeared correctly in "My Cases" alongside pre-existing seed cases the student could now see for the first time (including the "hours undercounted" appeal referenced in §1.2's finding — confirming that gap is closed), expanded a resolved case and confirmed its real timeline rendered. Switched to `admin001` and confirmed both new cases appeared in `CaseReview.tsx` with correct type/status/student/reasonCode/description, and completed one real "Request Information" decision end to end (toast confirmed, status flipped to "Needs Information" live).
