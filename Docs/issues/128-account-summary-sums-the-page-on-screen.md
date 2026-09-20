# 128 — The account summary sums the page on screen

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report:
`teacher005` sees Total Circulating Coins 1361.5 and an administrator sees a
different, smaller number on the same page.

## 1. What is wrong

Not a scoping bug. The three cards were computed in the browser from `items` —
**the rows on the current page**:

```tsx
{formatCoins(items.reduce((s, st) => s + getBalance(st), 0))}   // "Total Circulating Coins"
{items.filter(s => getBalance(s) > 0).length}                   // "Accounts with Balance"
{formatCoins(Math.round(total / items.length))}                 // "Average Balance"
```

The code said so, in a comment that has been sitting there the whole time:
*"Summary — computed from the current page only; there's no aggregate-stats
endpoint"*. The label above it says *Total*.

So the two readings differ for a reason that has nothing to do with who is
looking. Measured live before the fix:

| | rows on page 1 | its sum | **true total** |
| :--- | ---: | ---: | ---: |
| admin001 | 20 of 29 | 159300 | **201750** |
| teacher005 | 12 of 12 | 144350 | 144350 |

`teacher005`'s scope fits on one page, so their card was accidentally correct.
The administrator's was a subtotal of whichever twenty accounts sorted first —
which is also why it can come out *lower* than a teacher's.

## 2. What it should do

`GET /accounts/summary`, aggregated in SQL over **everything the caller may
see**, using the same scope specification `GET /accounts` already applies — so
the cards cannot disagree with the rows beneath them. A teacher's total covers
their grades, an administrator's covers the school, and both are totals.

Criteria rather than a derived method, because Spring Data has no aggregate form
of `JpaSpecificationExecutor` and a Java fold over `findAll(spec)` would load
every account in the school to add up three numbers — the thing `AdminStatsDto`
already refuses to do.

The average is carried rather than left to the client so that "average over how
many" has one answer: every account in scope, including those at zero. Integer
division, because coins are minimum accounting units and an average is still a
quantity of them.

## 3. Also fixed: the page never refreshed after an adjustment

Crediting or deducting from this screen updated nothing — no `refetch()`, no
totals. The rows and the cards both kept their pre-adjustment values until a
reload. A stale total is the same lie in a newer form, so both refresh now.

## 4. Verification

The summary against an independently computed truth — every page of
`/accounts`, summed by the probe rather than by the server:

```
admin001   summary {totalBalance 201750, accountCount 29, accountsWithBalance 29, averageBalance 6956}
           truth   {rows 29, sum 201750, withBalance 29}
teacher005 summary {totalBalance 144350, accountCount 12, accountsWithBalance 12, averageBalance 12029}
           truth   {rows 12, sum 144350, withBalance 12}
```

Both exact, and the administrator's total is now larger than the teacher's,
which it always should have been.

An integration test asserts the summary equals the unpaged list it sits above —
total, count and average together — so the cards and the rows cannot drift
apart again.

## 5. What it must not do

- **Must not aggregate in the client.** §1 is what that produces.
- **Must not use a different scope from the list.** The cards describe the rows.
- **Must not return a float.** §2 — minimum accounting units throughout.
- **Must not leave the totals stale after a mutation.** §3.
