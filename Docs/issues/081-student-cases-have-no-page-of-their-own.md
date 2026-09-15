# 081 — A student's cases have no page of their own, and no lifecycle actions

Status: **Fixed and live-verified (2026-09-16)**

## Request

Reported directly by the user:

> A real gap: student can submit a case, but no page to show the status of
> the cases submitted, also can not revert, delete, draft the cases like
> service records do, so we need a new page for the cases management, the
> 'Fire Case' should be moved to the new page, and the 'Home' page should
> show a summary of the progress of the cases, be careful the 'Case' in
> chinese should be '申诉' not '个案', the admin frontend have some incorrect
> i18n

## What exists today

`StudentCases.tsx` does exist, but it is **a tab inside Service Records**,
rendered next to "My Records" — not a page, not in the navigation, and not
reachable from anywhere else. A student who files a case from the Home page
or from a record has no obvious route back to it.

More importantly it is nearly read-only. The only lifecycle action a student
has is Respond & Resubmit on a `needsInformation` case (issue 076). Compare a
service record, which the same student can save as a draft, edit, submit,
withdraw while it is still `submitted`, and delete while it is still a draft.

## The gaps, and where each one lives

**Backend — two transitions/endpoints genuinely missing.** `CaseStateMachine`
has no `SUBMITTED → DRAFT` edge, so a filed case cannot be withdrawn even
though the equivalent exists for both service records and proposals; and
there is no delete endpoint, so a draft case cannot be removed. Everything
else the page needs is already there: `createDraft` and `submit` are separate
calls, `updateDraft` accepts `DRAFT` and `NEEDS_INFORMATION`, and `GET
/cases` is already scoped server-side to the caller's own student id.

**Frontend — the filing modal always submits.** `CaseFilingModal` calls
`create` then `submit` back-to-back, so "draft" is unreachable from the UI
even though the backend has always supported it.

**Frontend — no page, no summary.** Needs a real `/student/cases` route in
the navigation, the standalone "File a case" action moved onto it, and a
progress summary on Home.

**i18n — 案件/个案 should be 申诉.** Confirmed wrong in three places:
`admin.dashboard.pendingCases` (待处理个案), `admin.cases.count`
({{count}} 个案件), `admin.cases.noCases` (暂无待处理案件), plus
`student.cases.respondSuccess` which says 案件. `admin.cases.title` and
`layout.admin.caseReview` already read 申诉处理, which is the wording to
match.

## Design decisions

**The per-record Appeal/Supplement buttons stay where they are.** Only the
standalone "File a case" button moves. An appeal against a specific rejected
record belongs on that record's row — that is the context the student is in
when they need it (06-StudentCaseFiling.md §2.2) — while the standalone
entry point is a general one and belongs with the rest of case management.

**Withdraw mirrors the service record exactly.** `SUBMITTED → DRAFT` for the
OWNER only, which makes "review has not started yet" enforced by the state
machine rather than by a separate check — a case already `IN_REVIEW` simply
has no such edge.

**Delete is draft-only and physical.** Same rule as a service record: only a
`DRAFT` may ever be physically deleted, and its `case_revision` rows must go
first or the delete fails on the foreign key (issue 059's lesson).

## Test plan

- `mvn test` for the new edges; an IT for withdraw and delete including their
  refusals.
- `tsc --noEmit`, `pnpm build`, en/zh key parity.
- Live: file a case as a draft, edit it, submit it, withdraw it, delete it;
  confirm a submitted-then-claimed case cannot be withdrawn; confirm the Home
  summary counts match the page; confirm no screen says 个案/案件.

## Resolution

Backend:

- `CaseStateMachine` — `SUBMITTED → DRAFT` for the OWNER (withdraw).
- `CaseService.withdraw(...)` / `deleteDraft(...)` and their
  `POST /cases/{id}/withdraw` / `DELETE /cases/{id}` endpoints. Withdraw
  clears `submittedAt`; delete removes the case's revision rows first.
- `CaseRevisionRepository.deleteByCaseId(...)`, with a doc comment on why an
  append-only repository grows a delete (the revisions belong to the draft).
- `CaseStateMachineTest` — withdraw allowed from `SUBMITTED` only, and only
  for the owner. `CaseDecisionTransactionIT` — three new cases: withdraw
  works until a reviewer claims the case then stops, delete is refused for a
  submitted case and works after withdrawing, and a draft *with revisions*
  still deletes (the FK trap). 9/9 green on the server.

Frontend:

- `components/student/StudentCases.tsx` — now the `/student/cases` page:
  status filter, the standalone file-a-case button, and per-status actions
  (Edit / Submit / Delete on a draft, Withdraw on a submitted case, Respond &
  Resubmit on `needsInformation`), plus the existing timeline.
- `CaseFilingModal.tsx` — create and submit are now separable (**Save Draft**),
  and the same modal edits an existing draft (`draft` prop).
- `App.tsx` / `StudentLayout.tsx` — route and nav entry.
- `ServiceRecords.tsx` — the My Records / My Cases tab pair and the standalone
  file-a-case button removed; per-record Appeal/Supplement stay.
- `StudentHome.tsx` — a three-number case summary (needs you / with reviewer /
  closed), hidden entirely when the student has never filed one, plus a
  pending-action row when something is waiting on the student.
- `api/client.ts` — `cases.withdraw`/`cases.remove`; `api/types.ts` — the
  `CaseStatus` union named so screens can share it.
- i18n — new `student.cases.*`, `student.home.cases*`,
  `layout.student.cases`; 案件/个案 corrected to 申诉 in
  `admin.dashboard.pendingCases`, `admin.cases.count`, `admin.cases.noCases`,
  `admin.cases.searchPlaceholder` and `student.cases.respondSuccess`; the two
  now-unused tab labels pruned.

## Live verification (2026-09-16)

Full lifecycle as `spencer`, interface in Chinese: filed a case with **保存草稿**
→ it appears under the 草稿 filter; **编辑** reopened it with the description
prefilled and no record picker (the subject is fixed at creation); submitting
from the edit modal moved it to 已提交 and the stored title tracked the edited
text; **撤回** returned it to `DRAFT` with `submittedAt` cleared; **删除**
removed it after a confirmation naming the draft. `GET /cases` afterwards
shows the student's two original cases and nothing else.

`audit_log` for that case id holds the complete chain —
`case.create → case.update → case.submit → case.withdraw → case.delete`.

Home shows **0 待你处理 / 0 审核中 / 2 已结束**, matching that student's two
closed cases.

Wording: Case Review reads 申诉处理 / **9 项申诉**, the admin dashboard tile
reads **待处理申诉**, and a regex sweep for 个案/案件 across both screens and
`zh.json` returns nothing.
