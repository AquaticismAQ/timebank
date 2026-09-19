# 108 — Admin summary tiles count the current page, not the school

**Status:** Student headline count fixed. Six other tiles across two screens
remain page-scoped and need a server-side aggregate (§4).
**Found:** 2026-09-19, during the live-verification pass the user asked for after
the two-stage re-seed. **Pre-existing** — not caused by that work, only made
visible by it.

## 1. What the screens say versus what is true

Both figures are labelled as school-wide and computed from whatever page happens
to be loaded.

| Screen | Tile | Shows | Database |
| :--- | :--- | ---: | ---: |
| Student Management | *N 名学生* | **20** | **56** |
| Student Management | 达标 / 不足 / 严重不足 | **0 / 20 / 19** | over 56 students |
| Time Coin Accounts | 全校流通时间币 | **664.5** | **1,948.5** |
| Time Coin Accounts | 有余额账户数 | **9** | **28** |
| Time Coin Accounts | 人均余额 | **33.23** | **69.6** |

The pagination control on the same screen reads **"56 total"** at the same
moment the headline reads "20 名学生", which is the clearest statement of the
bug: the page already knows the real number and the tile does not use it.

## 2. Cause

`StudentList.tsx:313`

```tsx
{t('admin.students.count', { count: students.length })}
```

`AccountManagement.tsx:157-171`

```tsx
{formatCoins(items.reduce((s, st) => s + getBalance(st), 0))}   // 全校流通时间币
{items.filter(s => getBalance(s) > 0).length}                    // 有余额账户数
{formatCoins(Math.round(… / items.length))}                      // 人均余额
```

`students` and `items` are one page of results — 20 rows. The word 全校
("school-wide") sits on a number that is page-wide.

The accounts screen lists **students**, not accounts, so `getBalance` returns 0
for the roster-only students who have no account at all. That is why 9 of the
first 20 rows show a balance while the school has 28 accounts: two different
undercounts compounding.

## 3. Why it went unnoticed until now

With 50 students and a page size of 20 the headline read "20 名学生" — wrong,
but not obviously so. The two-stage roster took the school to 56 students and
1,948.5 coins, and 664.5 against 1,948.5 is far enough out to notice.

It is worth recording that **this was found only because the pass actually
looked at the numbers**, rather than checking that the page rendered. An earlier
sweep in the same session reported all eight admin screens as fine, because
"fine" meant "rendered without a console error" — which these screens do, while
displaying a figure that is a third of the truth.

## 4. The fix, and the half that is not done

**Student count — fixed.** `usePagedApi` already exposes
`page.totalItems`, which is what the pagination control renders. The headline now
reads the same source.

**Student Management's three progress tiles — open.** 达标 / 不足 / 严重不足 are
computed the same way, over the loaded page. Fixing the headline beside them and
leaving these is a half-measure, and is called out here so it is not mistaken
for completeness: the screen now reads "56 名学生" above "20 不足", which is
internally inconsistent in a way the old all-20 version at least was not.

**The three account tiles — open.** These cannot be corrected client-side: a
school-wide balance total is not derivable from one page of students, and raising
the page size to cover it would be a fetch of the whole roster to render three
numbers. They need a server-side aggregate, which is a new endpoint and therefore
a contract change.

Deliberately **not** done as a stopgap:

- **Relabelling them "this page"** — a per-page coin total answers no question
  anyone has.
- **Removing them** — the figures are wanted; it is the arithmetic that is wrong.

Until the aggregate exists the tiles are wrong, which is why this issue stays
open rather than being closed on the half that was easy.

## 5. Related

The same shape may exist elsewhere: any tile computed from a paged list rather
than from `page.totalItems` or a server aggregate. Worth a sweep for
`items.reduce`, `items.filter(...).length` and `.length` inside summary blocks
when this is picked up.
