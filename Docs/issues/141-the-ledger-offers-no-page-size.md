# 141 — The ledger offers no page size

**Status:** **Fixed and deployed 2026-09-21.** Aquaticism's report: the student's
Transaction History on the Time Coin Account page should have the page-size
dropdown the other paginated lists have.

## 1. What was missing

`PageControls` takes an optional `pageSize`/`onPageSizeChange` pair. The account
page rendered it without them, so the control was simply absent and the ledger
was fixed at twenty rows a page.

It is the list most likely to be long — a Year 12 student's account holds every
credit they have ever earned — and it was the one offering no way to see more of
it at once.

## 2. Why the list guard did not catch it

`check-list-conventions.mjs` (issue 138) checks search and the empty state, and
deliberately **not** page size: "a rule that fires on half the codebase gets
switched off rather than obeyed", and eleven of seventeen lists passed the
control at the time.

That judgement stands — but it is worth being honest that this is the gap it
leaves, and that this page was found by a maintainer rather than by the check.
Tightening the rule to page size is a reasonable next step once the remaining
lists carry it; doing it now would still fire on several screens at once.

## 3. Fix

Pass the pair through from `usePagedApi`. The options come from
`lib/pagination.ts`, which is deliberately not overridable per screen, so this
list cannot offer a different set from the rest.

## 4. Verification

On the deployed page, watching the request rather than the dropdown — a control
that changes nothing on the wire would look identical:

```
control present   options ["10","20","50","100"]
default           page[number]=1&page[size]=20
choose 50       → page[number]=1&page[size]=50
rendered rows     40 → 76
page errors       []
```

## 5. What it must not do

- **Must not offer its own option set.** §3.
- **Must not filter client-side.** The size goes to the server, as above.
