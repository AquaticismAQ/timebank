# 090 — Cohort purge breaks on `activation_code` foreign keys

**Status:** Fixed for the student path — not yet live-verified. One question left open (see §4).
**Found:** 2026-09-17, while reviewing the issue-089 removal work.
**Introduced:** `V27__student_activation.sql` (plan 10 §3.1), 2026-09-16.

## 1. Symptom

`PurgeQueryRepository.deleteAllForStudent` ends with `delete from user where id = :userId`.
`V27` added `activation_code` with **two** foreign keys onto `user`:

- `fk_activation_code_user` (`activation_code.user_id`)
- `fk_activation_code_issued_by` (`activation_code.issued_by`)

Neither is cleared before the user row is deleted, and the purge deletes no rows from
`activation_code` at all. Purging a graduated student who holds any activation-code row —
including a spent one, since rows are not removed on redemption — fails on
`fk_activation_code_user`.

This was found by code review, not by a failure: no purge has run since V27 landed.

## 2. Why it was not caught

The purge is raw SQL. Nothing referenced the new table, so nothing failed to compile and no
test changed colour. The same property that hid this hid the `password_reset_token` coupling
in issue 089 — that one was caught only because the table was being dropped deliberately.

The purge's own unit tests do not run against a real schema, so foreign keys are not
exercised. Only an integration run against real MySQL would have surfaced it.

## 3. Fix

Delete the student's own activation codes before deleting the user row, alongside the
existing `notification` / `user_role` / `teacher_scope_assignment` deletes.

## 4. Open: what should happen to `issued_by`

`activation_code.issued_by` is `NOT NULL` and points at the **administrator who issued the
slip**, not at the student. So purging a member of staff who ever issued slips would fail the
same way, and the fix is not symmetric with the student case:

- Deleting those rows would destroy *other, still-current* students' codes because one admin
  issued them.
- Nulling the column is not possible while it is `NOT NULL`.
- Refusing the purge is arguably correct but is a behaviour change.

`deleteAllForStudent` is the only purge path today, so no staff account can currently be
purged and the question is not yet reachable. It becomes reachable the moment a staff purge
or account-deletion path is added. Recorded here rather than guessed at.

Note the provenance line on a printed slip names the issuing admin (§2.8), so `issued_by` is
the database's copy of something already on paper — deciding this is partly a records
question, not only a referential-integrity one.

## 5. Verification owed

Run `deleteAllForStudent` against a real schema for a student that holds both a spent and an
outstanding activation code. Unit tests cannot reach this; it is the same gap that let the
bug in.
