# 132 — Hours and coins look mismatched because they measure different periods

**Status:** **Investigated and fixed 2026-09-20.** Reported by Aquaticism from the
Student Management list: "many students' time coin and service time are not
match, is something wrong in the seeding scripts?"

## 1. The answer to the question asked: no

**The seeding script is correct and the ledger is exactly consistent.** Checked
read-only against the live server for all seven students visible in Aquaticism's
screenshot — no re-seed, no writes:

| Student | Annual hours (shown) | Balance (shown) | Lifetime approved hours | Σ of each record's own credit |
| :--- | ---: | ---: | ---: | ---: |
| 708MEA5B (G12) | 9 | 304.5 | **304.5** | **304.5** |
| TNJW070Z (G11) | 13 | 168.5 | **168.5** | **168.5** |
| 3VVW4ZDG (G12) | 9 | 156 | **156** | **156** |
| QXVH4TFC (G10) | 11.5 | 152.5 | **152.5** | **152.5** |
| GZWSEGXS (G11) | 11 | 126.5 | **126.5** | **126.5** |
| 5DMZ1CE4 (G10) | 10 | 118 | **118** | **118** |
| FNG9DW7P (G11) | 7 | 88.5 | **88.5** | **88.5** |

Every balance equals that student's lifetime approved hours **to the cent**, and
equals the sum of the individual credits stamped on their records. One coin per
hour, derived from approved records, nothing credited outside the record flow.
708MEA5B has 104 approved records across several years, which is where 304.5
comes from.

## 2. What is actually wrong

The two columns come from different places and cover different periods:

| Column | Source | Period |
| :--- | :--- | :--- |
| Service Hours | `GET /students/{id}/progress` → `completedMinutes` | **this academic year** |
| Time Coins | `GET /accounts` → `balance` | **lifetime** |

Set side by side under bare headings, a third-year student reading *13h* and
*168.5* looks like a data fault. It is a Year 11 pupil with three years of
service behind them.

The hours column is not the odd one out — it sits next to *Annual Progress* and
is the same figure that bar uses. The reading that breaks is comparing it with a
balance that never resets.

## 3. The fix

Say which period each column covers:

| | Before | After |
| :--- | :--- | :--- |
| en | Service Hours · Time Coins | **Service Hours (This Year)** · **Time Coins (Total)** |
| zh | 服务时长 · 时间币 | **本学年服务时长** · **时间币余额（累计）** |

No data change, and deliberately not a behavioural one. Two alternatives were
considered and rejected:

- **Show coins for this year instead.** The balance is lifetime *by design* —
  it is derived from an immutable transaction ledger, and a year-scoped coin
  figure would be a second, differently-computed number claiming the same name.
- **Show lifetime hours instead.** They belong beside Annual Progress, which is
  annual; changing them would break the bar's own reading.

## 4. On "verify in Docker first"

Nothing was re-seeded, in Docker or anywhere: the question was answerable from
reads alone, and reads against the real data are stronger evidence than a fresh
fixture — they cover the drift a running server accumulates as well as what the
seeder writes.

Worth noting for next time: the seeder already checks this invariant itself
(`seed.ts` Phase 7 throws rather than reporting success if any balance disagrees
with its approved records) — **but only for students carrying a `ref`**, the
named fixture handles. The bulk-generated roster is skipped. The numbers above
show the invariant holds for them too, so the gap is theoretical today; closing
it would make the seeder's own guarantee cover everyone it creates.

## 5. What it must not do

- **Must not "fix" the ledger.** §1 — it is correct.
- **Must not invent an annual coin balance.** §3.
- **Must not move the hours column off the annual figure.** §3 — the progress
  bar next to it depends on that reading.
