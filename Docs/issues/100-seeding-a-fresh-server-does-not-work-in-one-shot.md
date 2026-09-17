# 100 — Seeding a fresh server does not work in one shot

**Status:** Scripts fixed; **not applied to the live server** (§5).
**Raised:** 2026-09-18 by the user — a contributor should be able to initialise
their own test server in one shot, with no manual fixes afterwards.

Audited the two seeding paths against what the code now requires. Two real
breakages, both invisible until well after the seed "succeeds".

## 1. The current academic year had already ended

`R__seed_base_data.sql` seeded two literal years, the newer flagged current:

```sql
('ay_2025_2026', '2025-2026', '2025-09-01', '2026-08-31', 1, ...)
```

On 2026-09-18 that year was **eighteen days expired**, and a fresh seed today
reproduced it exactly.

This is not cosmetic. `end_date` is a real query bound —
`ReportQueryRepository` joins on it, and annual progress sums
`service_date between :yearStart and :yearEnd` — while `integration-test`
deliberately dates every fixture relative to *now* (`mock-data.ts`: "a
hardcoded date would silently go stale the day after it was written"). The two
halves disagreed:

| | |
| ---: | :--- |
| 237 | seeded service records |
| 227 | inside the flagged current year |
| **10** | **after it ended** |

Ten today, and growing by roughly one a day. A contributor seeding in November
would have most of their fixture sitting outside the year every report scopes
to — visible only as progress figures that quietly undercount, which is the
hardest kind of wrong data to notice.

`seed.ts` then hardcoded `'ay_2025_2026'` twice more, for `class.academic_year_id`
and `student.enrollment_academic_year_id`, so it enrolled students into the
expired year regardless of what the database said.

**Fixed by deriving the year from the current date.** A school year is taken to
start on 1 September; September onwards belongs to the year named for the
calendar year it starts in. `seed.ts` now *asks* the database which year is
current instead of assuming, and fails loudly if the answer is not exactly one
row.

A literal year is wrong from the first September after it is typed. This cannot
be, which is the point — the alternative was bumping it by hand every August.

### A leap-year bug caught while writing it

The first version used `MAKEDATE(@ay_start, 244)` for 1 September. Verified
against real MySQL rather than assumed:

| Start year | `MAKEDATE(y, 244)` | Month arithmetic |
| :--- | :--- | :--- |
| 2025 | 2025-09-01 ✓ | 2025-09-01 |
| 2027 | 2027-09-01 ✓ | 2027-09-01 |
| **2028** | **2028-08-31 ✗** | 2028-09-01 |
| **2032** | **2032-08-31 ✗** | 2032-09-01 |

Day 244 is 1 September only in non-leap years. It now uses
`MAKEDATE(y, 1) + INTERVAL 8 MONTH`, which is correct for all of them — a bug
that would have surfaced in 2028 as a one-day gap nobody would have connected
to this file.

## 2. Every seeded staff account was unusable in the admin UI

`seed.ts` inserted staff with `staff_id` NULL. Since issue 003,
`UserController.requireRoleFields` rejects a `PATCH /users/{id}` whose
*effective* staff id is null — and "effective" falls back to the stored value,
so the account cannot be edited at all:

```
400  A staff ID is required for non-student accounts.
```

Every one of `admin001`, `sysadmin001`, `teacher001`–`teacher005` was in this
state on the live server. It bites the first time anyone opens Edit User on a
seeded teacher and presses Save, on a form that never showed the field as empty.
I hit it myself while verifying issue 097 and had to work around it.

`SeedUser` now carries `staffId`, every staff fixture has one, and the insert
writes it.

## 3. Checked and *not* changed

- **`initialPassword`** — issue 096 removed it from `POST /users`, but the
  seeder inserts users by direct SQL with a real bcrypt hash and
  `status = 'ACTIVE'`, so it never used that endpoint. Unaffected.
- **Accounts** — issue 099 confirmed accounts are created on first credit. The
  seeder correctly does not insert `account` rows; the API phase creates them
  through real approvals.
- **`PATCH status: LOCKED`** — refused since issue 097; the seeder never sends
  it.
- **Docker** — there is none in this project, deliberately and repo-wide
  (`Docs/implementation/01-Overview.md` §6, restated in `CLAUDE.md`). A
  contributor initialises a real MySQL schema and a systemd unit, not a
  container. No Docker path was added here; that would be a design decision,
  not a seeding fix.

## 4. What one shot now looks like

1. Point the backend at an empty schema and start it — Flyway applies `V1…V29`
   plus `R__seed_base_data.sql`, which derives the academic years from today.
2. `cd integration-test && npm install && npm run seed`.
3. Copy the printed passwords into `Docs/TestAccounts.md`.

No manual SQL, and no step that is only correct in a particular month.

## 5. Not applied to the live server — this needs a decision

**The seeder was not re-run**, as instructed. But `R__seed_base_data.sql` is a
*repeatable* migration: Flyway re-applies it whenever its checksum changes, so
**the next backend deploy will run this against the live database**. On that
server it will:

- insert `ay_2026_2027` (2026-09-01 → 2027-08-31);
- move `is_current` from `ay_2025_2026` to it, via an `UPDATE` that guarantees
  exactly one current year — `INSERT IGNORE` alone cannot, and two current
  years would be worse than a stale one, since `ReportAcademicYearResolver`
  would take whichever the database returned first.

Existing `class` and `student` rows are **not** re-pointed: they stay in
`ay_2025_2026`, which is now historically accurate rather than wrong. Nothing
is deleted or overwritten.

That is a correction, not a regression — the current school year really is
2026-2027 — but it is still a write to shared state, so it is recorded here
rather than slipped in with a deploy. The existing 237 seeded records keep
their dates and will then sit in the prior year, which is what they are.
