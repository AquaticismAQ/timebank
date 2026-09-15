# 079 — Case titles freeze the filer's UI language into the database

Status: **Fixed and live-verified (2026-09-16)**

## Symptom

Reported directly by the user, from Case Review with the interface set to
English:

> why it mixed with english and chinese while the language set to english,
> the title already show 'Supplement', but why still have the '材料补充',
> also the title in the detail view is incorrect, not all items, some of
> them

Three of the ten cases in the live queue show a Chinese title — `材料补充`,
`材料补充`, and the outright mixed `材料补充: Overnight Watch Program` — while
every other column on the same card (status badge, type label) is correctly
in English. Opening one of them shows the same string as the detail
workbench's heading *and* as its Type field, so the heading identifies
nothing.

## Root cause

`CaseFilingModal.tsx` builds the case title client-side out of a
**translated** label and writes it into the database:

```tsx
title: `${t(titleKey)}${subject ? `: ${subject.projectTitle || subject.organizationName}` : ''}`,
```

`t(titleKey)` is `student.records.supplement` / `.appeal`, so whatever
language the student happened to be using at filing time is frozen into
`case_record.title` forever. A case filed in Chinese reads as Chinese to an
English reviewer, and vice versa — and when the attached record's own title
is English, the stored string is mixed in *any* language.

This is the same class of mistake as storing a formatted date: a display
concern resolved once, at write time, in one user's context, then served to
everyone else.

The three bad rows are not seed data — the seeded cases all have real English
prose titles. They were filed through this modal during the 2026-08-11 and
2026-08-17 live-testing sessions with the UI in Chinese.

## Fix

**The stored title stops carrying the type at all.** Every surface that
shows a case already renders the type as its own translated element — the
list card has a type chip next to the status badge, and the detail workbench
has a Type field — so repeating it in the title was redundant even when the
language matched. The title now carries only what is genuinely this case's
own content and is therefore language-neutral:

- with a record attached: the record's project or organization name;
- with none: the first line of the student's own reason, trimmed to fit
  `case_record.title` (`VARCHAR(200)`).

`description` is already required by the modal, so the fallback can never be
empty (the backend's `@NotBlank title` stays satisfied).

Nothing else writes a case title — `api.cases.create` has exactly one caller.

## Database repair

The three affected rows were rewritten to what the new rule would have
produced, so history and new filings read alike. `mysqldump` of
`case_record` taken first to `/root/timebank-case-title-fix-<stamp>/` on the
server.

| case | before | after |
| --- | --- | --- |
| `case_8p5mb8pg…` | `材料补充: Overnight Watch Program` | `Overnight Watch Program` (its record's organization; the record has no project) |
| `case_t4yr7tw9…` | `材料补充` | the description's first line (a beach-cleanup request) |
| `case_419r951z…` | `材料补充` | `ff` — the whole description; junk from an early test, and the rule applied honestly rather than inventing a nicer title. **Subsequently deleted** on the user's instruction, see below |

The five seeded cases were left alone. Their titles (`Appeal: …`,
`Supplement: …`) are English prose a student could plausibly have typed, not
a UI label resolved at write time, so they are not instances of this bug.

## Test plan

- `tsc --noEmit`, `pnpm build`, en/zh key parity.
- Live: file a supplement **with the UI in Chinese**, both with and without
  an attached record, and confirm the stored title contains no translated
  label and reads the same with the interface in either language; confirm
  the detail heading is no longer a duplicate of the Type field.

## Resolution

`frontend/src/app/components/student/CaseFilingModal.tsx` — new `caseTitle()`
helper; `title:` no longer interpolates `t(...)`. The modal's own heading
still uses the translated label, which is correct: it is rendered live, not
stored.

## Live verification (2026-09-16)

Filed two supplements as `student002` **with the interface in Chinese**:

- unattached → stored title `为 079 验证：标题不应包含界面语言的标签。` — the
  student's own first line, second line correctly excluded, no `材料补充`
  prefix;
- attached to a record → stored title
  `Core Workflow Re-verification — Evening Reading Room`, i.e. the record's
  own name with no label at all.

Then re-read the Case Review queue as `admin001` **in English**: every one of
the 12 cases now shows a translated type chip plus a title that carries no
type label, and the only Chinese title left is the one whose student wrote it
in Chinese — which is their content, not a UI string. The repaired
`Overnight Watch Program` case's detail heading now names the case instead of
repeating its own Type field.

The two cases filed for this check were removed afterwards (with their
revision rows); the queue is back to the 10 it held before.

## Follow-up: the `ff` case deleted

On the user's instruction the `ff` case (`case_419r951z…`) was deleted
outright rather than left carrying a meaningless title — it was junk from an
early manual test: a `SUPPLEMENT`, description `ff`, no attached record and
no `requestedAmountCentiCoins`, so nothing in the ledger depended on it. Its
one `case_decision` row and one notification went with it; `mysqldump` of the
row and its dependents taken first to `/root/timebank-case-ff-delete-<stamp>/`
on the server.

**Its three `audit_log` rows were deliberately kept.** The audit log is
append-only by design, and an entry describing a decision that really
happened stays true whether or not the case row still exists; deleting from
it is reserved for the PII case that forced it once before. A future reader
finding `case.decision` entries for a `resource_id` with no case row should
read that as this deletion, not as corruption.

Queue after: 9 cases.
