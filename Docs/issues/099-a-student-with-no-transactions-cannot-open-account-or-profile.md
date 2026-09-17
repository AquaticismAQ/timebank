# 099 — A student with no transactions cannot open Time Coin Account or Profile

**Status:** Fixed, live-verified (§6).
**Reported:** 2026-09-18 by the user — signed in as `student001`, both the Time
Coin Account and Profile pages "failed to load".

Reproduced. Both render exactly this, permanently:

```html
<main><div class="p-4 lg:p-6">Time Bank</div></main>
```

No error, no spinner, no empty state — the application's own name, alone on the
page.

## 1. It is not a failed request

Every call both pages make returns `200` with correct data:

| Request | Result |
| :--- | :--- |
| `GET /students/std_76bd…` | 200 — Alex L.(李\*红), G6, class B |
| `GET /students/std_76bd…/progress` | 200 — `completedMinutes: 0`, `timeCoinBalance: 0` |
| `GET /school/info` | 200 |
| `GET /academic-years` | 200 |
| `GET /accounts?filter[studentId]=std_76bd…` | **200 — `data: []`** |

The last one is the whole story, and it is not an error either: the student
simply has no `account` row.

## 2. Why there is no row, and why that is correct

`AccountServiceImpl.getOrCreateLocked` creates the account **on the first
credit**, not when the student is enrolled. Balances are transaction-derived by
design (`CLAUDE.md`: "no direct balance edit endpoint exists or should exist"),
so a student who has never had an approved service record has no transactions,
therefore no account row — and a balance of exactly zero.

This is not a seeding accident. On the live server:

| | |
| ---: | :--- |
| 50 | students |
| 27 | accounts |
| **23** | **students with no account row** |

Nearly half. And it is not an edge case that shrinks over time — it is the
state **every student is in** from enrolment until their first record is
approved. A new cohort starts here in September.

## 3. The actual defect, in three lines of frontend

**`api.accounts.get` treats "no account yet" as a failure:**

```ts
const dto = page.data[0];
if (!dto) throw new Error('Account not found');
```

**Both pages swallow that and never recover.** `ProfilePage.tsx:25`,
`AccountPage.tsx:58` and `StudentLayout.tsx:19` all do
`.then(setAccount).catch(() => {})`, so `account` stays `null` forever.

**And the guard for `null` renders the app name.** `ProfilePage.tsx:103` and
`AccountPage.tsx:85`, identically:

```tsx
if (!account) return <div className="p-4 lg:p-6">{t('common.appName')}</div>;
```

`common.appName` is "Time Bank" / "时间银行". This was presumably meant to be a
loading key; `common.loading` exists and says "Loading…". Because the guard is
also the loading state, it cannot distinguish "still fetching" from "fetched,
there is nothing" — so a permanent null is indistinguishable from a slow
network, and neither says anything to the user.

## 4. The fix

A student with no account row **has a balance of zero** — that is what
transaction-derived means. The read path should say so instead of failing.

- **`api.accounts.getOrNull(studentId)`** returns `null` rather than throwing
  when the filter comes back empty. `get` is left throwing on purpose: the
  admin screens call it immediately before `adjust`/`freeze`, which need a real
  account id, and quietly handing those a zero placeholder would be worse than
  an error.
- **The three student-side readers** — Account, Profile, the layout's sidebar
  balance — use `getOrNull` and fall back to a zero account for display.
- **The guard becomes a real loading state**, keyed on whether the fetch has
  settled rather than on whether a row exists, and uses `common.loading`.

### Considered and not done: create the account when the student is created

It would make §2's count 50/50 and reads simpler. Rejected for now:

- It does not fix this class of bug. The frontend would still break for any
  student whose row is missing for any other reason, and would still be one
  slow request away from rendering the app name at anybody.
- It needs a backfill migration for the 23 existing rows plus a change to both
  provisioning paths (import and `POST /students`), against a read-side fix of
  a few lines.
- The ledger is deliberately transaction-derived. An account row with no
  transactions carries no information the absence of one doesn't.

Worth revisiting if a *write* path ever needs the account to pre-exist — the
admin adjust flow is already close, since `accounts.get` throwing means an
administrator cannot adjust a student who has never earned anything. That is a
real gap, distinct from this one, and is not fixed here.

## 5. Also fixed: the same wrong key in six other places

`common.appName` was the loading text in seven components, not two. The other
six resolve once their data arrives, so they flashed "Time Bank" rather than
sticking on it — still wrong, and the same mistake, so all seven now use
`common.loading`:

`ProjectCenter`, `ProgressPage`, `StudentHome`, `ServiceRecords`,
`EventProposals`, `ProjectManagement`.

The two remaining `common.appName` uses are in `AppShell`, where they are the
application's name in the sidebar and header — correct.

**`StudentHome` was checked and is not affected by the account bug.** It reads
its account from `GET /dashboards/student`, which returns an account object
regardless of whether a row exists, so it rendered fine for `student001`
throughout (10,768 characters of content). Only its loading key changed.

## 6. Verification

Frontend deployed; no backend change was needed.

**As `student001` — the reported account, 0 transactions, no `account` row:**

| Page | Before | After |
| :--- | :--- | :--- |
| `/student/account` | `<div>Time Bank</div>`, 39 chars | renders — Current Balance **0**, Total Earned 0, Exchanged 0, Frozen 0, Honor Level "No Level", 6,116 chars |
| `/student/profile` | `<div>Time Bank</div>`, 39 chars | renders — Completed Hours 0, Time Coin Balance 0, Annual Progress 0%, language and notification settings, 16,142 chars |
| Sidebar balance | `--` | `0 Time Coins` |

**Control — `student027`, who has an account (balance 89):** the normal path is
unchanged. Account page shows **89** time coins, 89 total earned and 20
transaction rows; Profile shows 89 completed hours and a 356% annual progress.
Without this control the fix would be indistinguishable from one that renders
zeroes for everybody.
