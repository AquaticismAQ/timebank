# 121 — Every server error reaches the user in English

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report
("a error code is important, also the copy to draft have same issue").

## 1. What is wrong

Issue 120 localized one message by giving it a code. Aquaticism's point: that is the
right shape, so do it properly — and Copy to draft has the same problem.

It does, and so does everything else, because there is exactly one funnel:

```ts
// useFieldErrors.tsx, before
toast.error(mapped.message);                       // the server's English
action: { label: 'Refresh', … }                    // a hardcoded English literal
toast.error(opts.fallbackMessage ?? 'Request failed.');   // another one
```

`handleMutationError` is called from 23 places and shows `err.message` verbatim.
Field errors were no better: `useFieldErrors` stored `fe.message` — Bean
Validation's `"must not be null"` — and printed it under the input.

So Copy to draft was never specially broken. **Nothing was translated**, and it
was only the first place Aquaticism happened to look after 120.

## 2. The shape

Unchanged from §2 of issue 120, and now applied generally: the server sends
**facts and codes**, the client sends **words**. `api/errorMessages.ts` resolves
in three tiers:

| Tier | Source | Covers |
| :--- | :--- | :--- |
| 1 | `errors.field.<CODE>` from a coded `fieldErrors[]` entry | the specific ones |
| 2 | `errors.code.<CODE>` from the envelope | **everything else**, correctly, in the right language |
| 3 | the server's English sentence | shrinking remainder |

Tier 2 is what makes this a fix rather than a list of special cases: the ~120
`stateConflict`/`invalidRequest` sites with no code of their own now say *"That
does not match the current state — someone may have changed it since this page
loaded. Refresh and try again."* instead of a sentence written for a developer.

### Field-only codes

`NOTBLANK`, `SIZE`, `REQUIRED` and friends describe *a field*, not *what went
wrong*. "This cannot be empty" is right under an input and useless in a toast,
which cannot say which input. So they are listed in `FIELD_ONLY_CODES` and used
inline only; a request failing on one gets tier 2's "check the fields marked
below". `EVIDENCE_REQUIRED` and `OVERLAPPING_RECORD` say enough alone and are
not in that set.

**That sentence has to be true**, so the markers it points at must exist. The
new-project dialog marked only title/summary/description; the seven other fields
the server can reject now render a `FieldError` too.

## 3. The codes added

A first batch, chosen as the conflicts an ordinary student or teacher meets in
the everyday flows — not a sweep of all 209 `ApiException` sites:

| Module | Codes |
| :--- | :--- |
| project | `PROJECT_NOT_EDITABLE` |
| enrollment | `REGISTRATION_NOT_OPEN`, `ALREADY_REGISTERED`, `APPLICATION_NOT_CANCELLABLE`, `APPLICATION_NOT_CONFIRMABLE`, `APPLICATION_NOT_REJECTABLE`, `APPLICATION_NOT_COMPLETABLE` |
| servicerecord | `RECORD_NOT_EDITABLE`, `RECORD_NOT_SUBMITTABLE`, `RECORD_NOT_DELETABLE` |
| proposal | `PROPOSAL_NOT_EDITABLE`, `PROPOSAL_NOT_SUBMITTABLE`, `PROPOSAL_NOT_DELETABLE` |
| caseflow | `CASE_NOT_EDITABLE`, `CASE_NOT_SUBMITTABLE`, `CASE_NOT_DELETABLE` |
| rules | `RULE_NOT_PUBLISHABLE` |
| file | `FILE_BOUND_TO_RECORD` |

Plus the 22 field codes the backend already emitted and nobody was translating.
**Still uncoded:** the IAM/import/admin conflicts — username taken, staff id in
use, import state machine, idempotency replay. They fall to tier 2, which is
correct if unspecific, and are the obvious next batch.

`UNREVIEWED_RECORDS` moved out of `ProjectManagement.tsx`'s bespoke `if` and
into the table. That is the test of whether this generalizes: a code should not
need a special case anywhere.

## 4. Verification

On the deployed build, in both languages.

**A coded conflict, with its hand-written special case deleted:**

```
zh-CN  暂时无法归档：还有 1 名已确认的同学的服务记录尚未审核，请先在报名名单中完成审核。 | 刷新
en     Cannot archive yet — 1 confirmed student(s) still need their service record
       reviewed. Review them from the registrant list first. | Refresh
```

The action label is the proof that the hardcoded `'Refresh'` is gone too.

**A new code reaching the client**, from a stale-tab PATCH:

```
[{"field":"status","code":"PROJECT_NOT_EDITABLE","message":"ARCHIVED"}]
```

**A validation failure**, submitting the new-project dialog with its dates empty
— toast and inline, both from the tables:

```
toast:  填写的内容有问题，请检查下方标红的字段。
inline: ["此项不能为空。","此项不能为空。","此项不能为空。","此项不能为空。"]
```

The first attempt at this put the *field* message in the toast — "此项不能为空。"
with no field named. That is what `FIELD_ONLY_CODES` exists to prevent, and it
was found by reading the toast rather than by reading the code.

**An uncoded conflict** was confirmed at the envelope (`409 STATE_CONFLICT`,
`fieldErrors: []` — a duplicate service-category name), which is the input tier 2
keys off. Rendering it through a UI action is not covered: the path that would
have shown it hit a pre-existing 500 instead (issue 122).

## 5. Backend suite

`mvn verify` reports **26 failing integration tests — identical before and after
this change**, compared by stashing and re-running the full suite:

```
comm -13 before.txt after.txt   → (empty)
comm -23 before.txt after.txt   → (empty)
```

They are pre-existing on `main` (`AuthFlowIT`, `ProposalApprovalTransactionIT`,
`UserDirectoryIT`, `DashboardReportingIT`, `NotificationReadPathIT`,
`FileServiceIT`, `ServiceRecordSourceOptimizationIT`) and want their own
investigation; this change neither caused nor fixed any of them.

## 6. What it must not do

- **Must not translate server-side.** §2 of issue 120.
- **Must not put a field-only message in a toast.** §2.
- **Must not promise "the fields marked below" where nothing is marked.** §2.
- **Must not special-case a code in a component.** That is what the table is.
