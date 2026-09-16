# 085 — `student.viewSensitive` is half the audit log, and audits nothing

Status: **Fixed (2026-09-16)**

## Symptom

Reported by the user: the audit log is too noisy to use, dominated by
"Student — view Sensitive" entries. A read is a basic feature and should not
produce an audit record, and the imbalance will get far worse in production
where students outnumber staff by roughly 200 to 1.

## Finding

Measured on the original server, which has weeks of real browsing on it:

| Action | Rows | Share |
| :--- | ---: | ---: |
| `student.viewSensitive` | 708 | **50%** |
| `file.crossStudentAccess` | 219 | 15% |
| `serviceRecord.create` | 82 | 6% |
| `serviceRecord.submit` | 52 | 4% |
| everything else | 359 | 25% |
| **total** | **1420** | |

One read action was **half of the entire audit log**, and that is with a
handful of test accounts rather than a real roster.

**The top producers are students reading their own profiles**, not staff
inspecting anyone:

| Actor | `viewSensitive` rows |
| :--- | ---: |
| `student001` | 351 |
| `spencer` | 99 |
| `usr_finalverify_student` | 94 |
| `usr_i3v_stu` | 54 |
| `issue003_stu_3283` | 35 |
| `student002` | 23 |

The cause is in `StudentController`:

```java
boolean sensitive = principal.hasPermission("students.manage") || id.equals(principal.studentId());
```

The second clause means **a student opening their own profile counted as a
sensitive access** and wrote an audit row every time. That was arguably
defensible when the flag gated `email`/`guardianName`/`guardianContact` — it
recorded that someone had seen guardian contact details.

It is not defensible now. `V26` (issue `08-SecurityPrivacyHardening.md` §3.1)
dropped all three columns, so the flag gated **no fields at all** and the event
recorded a read that exposed nothing sensitive. This was flagged during that
work as `VESTIGIAL AFTER V26` and deliberately left as a design decision rather
than silently gutted; this issue is that decision.

## Fix

Remove the flag and the event entirely.

- `StudentService.getDetail` loses its `includeSensitiveFields` and `actorId`
  parameters on both overloads: `getDetail(id, lang)` and
  `getDetail(id, auth, lang)`.
- `StudentServiceImpl.toDetailDto` no longer writes an audit row. Both
  overloads become `@Transactional(readOnly = true)` — they previously could
  not be, precisely because the audit write had to commit.
- `StudentController` drops the `sensitive` computation quoted above.
- Nine call sites across `progress`, `enrollment`, `servicerecord`, `proposal`
  and `caseflow` updated to the shorter signature.
- `StudentPrivacyIT`'s two audit tests, which existed only to assert the flag's
  two branches, are replaced by one asserting that reading a student writes
  **no** audit row — the failure mode now worth guarding is a well-meaning
  reintroduction of read auditing.

### What was deliberately kept

**`file.crossStudentAccess` stays.** It is the number-two entry at 15%, so it
was tempting to remove alongside. It is not the same thing: it fires only when
someone opens an evidence file **they did not upload**, which is a genuine
"who looked at whose material" record — exactly the traceability the evidence
watermarking work (`08-*.md` §3.6/§3.7) is being built to support. It is
signal, not noise.

**Mutation auditing is untouched.** Every create, update, review decision and
ledger movement still writes a row, per the project's standing rule.

**Existing rows are not deleted.** The audit log is append-only by design and
purging it to tidy up would break the guarantee that makes it worth having.
The 708 historical rows stay; the event simply stops being produced.

## Effect

On the original server's data this removes 708 of 1420 rows — the log halves,
and mutations become the majority of its content rather than a minority buried
under reads.

## Test plan

1. Clean `mvn clean test` passes, including the rewritten `StudentPrivacyIT`.
   — done (`clean` specifically: incremental compilation masked stale
   references during §3.1 and reported a false pass)
2. Reading a student through the API writes no new audit row. — **done**: ten
   reads (five students, detail + progress each) after the deploy produced
   zero rows; `student.viewSensitive` stayed frozen at its pre-deploy count of
   5, the newest of which predates the deploy.
3. Mutations still audit. — **done**: two real `PATCH /students/{id}` calls
   wrote two `student.update` rows.
4. Viewing another student's evidence still writes `file.crossStudentAccess`.
   — untouched by this change; the code path was not modified.

A measurement note for whoever verifies next: `GET /audit-logs` does **not**
support `filter[action]`, so counting through the API returns 0 regardless and
looks like auditing is broken. Count in the database instead — that is what
distinguished "reads stopped auditing" from "auditing stopped working" here.
