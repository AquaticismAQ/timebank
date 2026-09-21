# 152 — "Earned coins" was a number the browser invented

**Status:** **Fixed and deployed 2026-09-21.** Aquaticism, on the Time Coin Accounts card
shipped in issue 151: *"for laptop view, do not show the number and grade in a
new line, show it follow the student name"*, and *"what's the 'Earned Coins: 0,
Redeem 0 coins'? … why is the earned all 0 even [when] the student ha[s]
approved records?"*

## 1. The layout

Issue 151 stacked the student number under the name because at 390px sharing one
line truncated the name. On a laptop there is room for both, and the stack wastes
a line. Now stacked below `md`, inline from `md` up.

## 2. Why "earned" was always zero

```tsx
earned: formatCoins(Math.round((student.completedMinutes ?? 0) / 60 * 2 * 100))
```

Three things wrong in one expression, and the zero is only the visible one:

1. **`completedMinutes` is not on this object.** The students list endpoint
   returns `id`, `studentNumber`, `displayName`, grade, class, status,
   graduation year, version — no minutes. So the field was `undefined` on every
   card, `?? 0` made it `0`, and the arithmetic dutifully produced zero for
   everyone. It was never a bug that appeared under some condition; it could not
   ever have shown anything else.
2. **It computed coins from minutes in the browser,** at a hardcoded two coins
   per hour. Conversion rates are versioned server-side rules
   (`cn.timebank.rules`); a multiplier typed into a component is a second,
   silent copy of one, and it would not have been right even with real minutes.
3. **Earned is not derivable from service time anyway.** The balance is
   transaction-derived — corrections, reversals and manual adjustments are all
   transactions — so "hours × rate" is a different quantity from what the ledger
   says, and the ledger is the one that counts (APIContract §3).

**`Redeemed` was the literal `0`.** Not a field, not a lookup — the number zero
passed to the template. Redemption is Phase 4 and cannot happen, so it was
accidentally accurate, which is the worst kind of correct.

## 3. Fix

`totalEarned` becomes a real field on `AccountDto`: the sum of the account's
**positive** transactions, from the ledger, computed server-side.

One grouped query per page rather than one per row — the list already loads
every account in scope, and an aggregate per row is how a list of two hundred
becomes two hundred and one queries.

**`Redeemed` is left as it was**, at zero, because Aquaticism's question was about
`earned` and said explicitly that redemption is known to be unimplemented. It is
worth recording that the same hardcoded zero also appears on the Student Portal's
home and account pages, so removing it is one decision about three screens rather
than a tidy-up of this card — and doing it here alone would leave the portals
disagreeing. Raise it as its own change if the zero should go.

The fix to `totalEarned` reaches those pages for free, and fixes a quieter
version of the same problem there: `StudentHome` derives a student's recognition
level from `account.totalEarned`, which until now was the balance. A correction
could have moved a student down a level.

## 4. Verification

**On the deployed site**, both widths:

```
1280px   Oakley Z.(赵*敏)  708MEA5B · G12, Class A      name and number on one line
         Earned 304.5 coins · Redeemed 0 coins          was "Earned 0" for everyone
         Morgan L.(刘*强)  152.5 · Sage W.(吴*燕) 80.5 · Alex L.(李*红) 75

390px    Oakley Z.(赵*敏)
         708MEA5B · G12, Class A                        still stacked, name intact
```

Every earned figure equals that student's balance, which is correct today and
also why the screen cannot prove the number is real rather than an echo of the
balance — nobody on the server has had a correction.
`AccountLedgerIT.totalEarnedCountsCreditsOnlyAndDivergesFromTheBalanceAfterACorrection`
is where that is proven: two credits of 500 and 300, then an adjustment of −200,
and it asserts balance 600 with earned still 800. **Red-checked** by restoring
the `earned = balance` approximation, which fails it alone (`expected: 800 but
was: 600`) — without the correction step the test would have passed against
exactly the bug it exists to catch.

373 unit tests and `AccountLedgerIT` 12/12, `ScopedDashboardFiguresIT` 3/3.

## 5. What it must not do

- **Must not compute coin amounts in the browser.** §2.
- **Must not issue a query per row.** §3.
- **Must not present `Redeemed` as anything but a placeholder.** It stays at
  zero until Phase 4 has a value behind it; §3 records that the decision to
  remove it spans three screens, not this one.
