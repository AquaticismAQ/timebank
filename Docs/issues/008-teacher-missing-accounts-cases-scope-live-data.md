# 008 — `teacher001` missing `accounts`/`cases` scope rows on the live database

Status: **Fixed and live-verified** (2026-08-10)

## Symptom

Found while browser-verifying issue 006 (which claimed accounts/cases scope was
seeded 2026-08-10 for `teacher001`, alongside `students`). Logged in as `teacher001`
and:
- Time Coin Accounts page showed **0 coins for every student**, including Jia Ming
  (`student001`), who genuinely has a 2.5-coin balance (confirmed both from their own
  student-side account page and from `admin001`'s Accounts page, which correctly
  showed 2.5/2.5/2 for the three grade-10 students with any balance).
- Case Review page showed **0 cases**, even though 5 real cases exist (confirmed via
  `admin001`'s Case Review page, which correctly listed all 5).

This is exactly the "shows 0 in teacher/admin view" symptom from the original bug
report — but the code itself is not at fault (proven by `admin001`'s view being
correct); the *data* backing the fix never actually reached the live database for the
teacher role.

## Root cause

Confirmed via direct SQL against `tb.example.com`'s `timebank` database:

```
SELECT resource_type FROM teacher_scope_assignment WHERE user_id = <teacher001's id>;
→ eventProposals, serviceRecords, students   (accounts, cases missing)
```

`integration-test/STATUS.md`'s fix history for issue 001/006 claims `accounts`/
`cases` scope rows were added for `teacher001` on 2026-08-10 "via direct SQL...
since there's no admin UI for this yet." They were not present. The only rows in the
whole table using `resource_type IN ('accounts','cases')` belonged to a different,
unrelated user — `issue003_dup1_27013`, a leftover duplicate-username test account
from issue 003's live uniqueness-validation testing. The most likely explanation is
that the intended `INSERT`s for `teacher001` were run against the wrong `user_id` (or
were part of a test flow whose data got cleaned up along with the duplicate account),
not that the mechanism itself is broken — `DataScopeResolver` correctly falls back to
self-only scope when no `GRADE` assignment row exists for a resource type, which for
a staff account with no `studentId` means zero visible rows, i.e. exactly the "0"
symptom observed.

## Fix

Live data-only fix (no code change) — inserted the two missing rows directly, matching
the existing pattern for the other three resource types:

```sql
INSERT INTO teacher_scope_assignment (id, user_id, resource_type, scope_type, scope_value, created_at) VALUES
('tsa_7ccdd902a37b6a8a1bde', 'usr_15b86497a1062a9ef106', 'accounts', 'GRADE', 'grade_10', NOW(3)),
('tsa_3c26b608b26a7f6fda57', 'usr_15b86497a1062a9ef106', 'cases', 'GRADE', 'grade_10', NOW(3));
```

`teacher001` now has all 5 managed resource types (`serviceRecords`/`eventProposals`/
`students`/`accounts`/`cases`) bonded to `grade_10`, matching `Docs/TestAccounts.md`'s
description and `integration-test/MOCK_DATA_GUIDE.md`'s documented setup.

## Test plan

- Live: logged in as `teacher001`, confirmed Time Coin Accounts page now shows 2.5/
  2.5/2 coins for the correct three students (previously all 0).
- Live: logged in as `teacher001`, confirmed Case Review page now shows the cases
  linked to grade-10 students (previously 0).
- No code changed, so no new automated test — this is purely a live-data gap fix. The
  existing `UserScopeBondingIT` already proves the *mechanism* (creating/updating a
  teacher via the real account-creation flow correctly writes all 5 resource types
  atomically); this issue was specifically about a mismatch between what
  `integration-test/STATUS.md` documented as done and what the live database
  actually contained for one already-existing account created before that flow
  existed.

## Resolution

- Two rows inserted directly into `timebank`.`teacher_scope_assignment` on the live
  server (see SQL above). No application code or deployment involved.
- `integration-test/STATUS.md`'s dataset snapshot should be treated as the source of
  intent, not a guarantee of live state, until this account (and any other
  hand-seeded fixture) is folded into `integration-test/seed.ts` for real, idempotent
  reconciliation rather than one-off manual SQL.

---
*Live-verified 2026-08-10 against `https://tb.example.com/`, found during a browser
verification pass of issue 006 (which the prior session, lacking a browser tool,
could only verify at the API level as `admin001` — the gap was specific to the
`teacher001` role and never surfaced).*
