# 068 — Three small display defects found during the end-to-end pass

Status: **Fixed and live-verified (2026-09-15)**

Grouped because each is a one-line display fix with no shared root cause;
all three were spotted while walking the full
view → register → record → submit → audit flow for issues 064–067.

## 1. Student ledger showed the raw backend reason code

Time Coin Account → Transaction History rendered `SERVICE_APPROVED` in
the Description column of every approval credit.

`AccountTransactionDto` has no `description` field — `api/client.ts`
deliberately substitutes `reasonCode` (optionally `reasonCode: memo`), and
that decision is sound. What was missing is that the substituted value is
a machine enum, shown verbatim to a student.

**Fix:** a `useTxDescription` helper in `AccountPage.tsx` translates the
code half through `student.account.reason.<CODE>` with the raw code as the
i18n `defaultValue`, so an unrecognised code degrades to today's behaviour
rather than to a blank cell. `SERVICE_APPROVED`, `CORRECTION` and
`REVERSAL` are seeded in both locales.

Note this does **not** address the related §2.11 gap that
`relatedRecordId` arrives without a `relatedRecordTitle` — the UI already
prefers `tx.relatedRecordTitle` when present, so filling that in
backend-side would supersede the reason-code fallback for service credits
entirely. Left unfixed and recorded here rather than silently expanded
into.

## 2. `Owner: : Teacher Wei Chen` in Project Management

`admin.projects.owner` already ends in `": "` / `"："` in both locales, and
the project card's JSX appended a second `: `. Fixed at the call site
(`ProjectManagement.tsx`) rather than by editing the shared string, since
the string's trailing colon matches how the Chinese locale conventionally
renders the label.

## 3. `(2hours)` in the wizard's Confirm & Submit summary

The Time line built its parenthetical as
`` `${minutesToHours(...)}${t('common.hours')}` `` with no separator,
unlike every other `common.hours` call site. Added the space; Chinese
renders "2 小时", which is the normal spacing there too.

## Test plan

- `pnpm build` clean.
- Live Playwright pass against the deployed server: ledger as the
  grade-10 student with two approved records now reads "Service record
  approved" for both credits; the wizard summary reads "21:00 - 23:00
  (2 hours)". The owner label was verified by reading the deployed card
  markup.

## Resolution

`frontend/src/app/components/student/AccountPage.tsx`,
`frontend/src/app/components/admin/ProjectManagement.tsx`,
`frontend/src/app/components/student/ServiceRecords.tsx`,
`frontend/src/i18n/{en,zh}.json`.

## Seen but deliberately not fixed

Two further cosmetic observations from the same pass, left alone as
lower-value than the churn of touching them now:

- The student project-detail expansion renders an empty "Organizer:"
  label when a project has no partner organization, rather than omitting
  the row.
- Student Home's "Registering now" card shows an untranslated `Open`
  status badge while the rest of the page is in Chinese.
