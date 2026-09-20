# 109 — Plan 10 left 28 integration tests red, and nothing noticed for two days

**Status:** Diagnosed, mostly not fixed. **`StudentPrivacyIT` is fixed and green
(2026-09-20) — and it was never this issue's root cause; see §3.1.** Seven classes
remain, on the one cause below.
**Found:** 2026-09-19, by two subagents independently reporting that `mvn verify`
does not pass on `main` — and then confirmed here against a pre-session commit.
**Broke:** 2026-09-17, plan 10 (`ddb309e`, `c16a2c3`, migrations `V27`/`V28`).

## 1. What is red

`mvn verify` on `main` (`3b69b04`): **10 failures + 18 errors** across eight
classes. `mvn test` is entirely green — this is only reachable through Failsafe,
which is why it went unseen.

| Class | Errors |
| :--- | ---: |
| `ProposalApprovalTransactionIT` | 10 |
| `AuthFlowIT` | 6 |
| `ServiceRecordSourceOptimizationIT` | 2 |
| `StudentPrivacyIT` (2 failures — **a different cause, §3.1; fixed 2026-09-20**) | 0 |
| `UserDirectoryIT`, `DashboardReportingIT`, `NotificationReadPathIT`, `FileServiceIT` | the balance |

## 2. It is not this session's work

Worth stating plainly, because "pre-existing on `main`" was initially ambiguous:
several of this session's commits edited `R__seed_base_data.sql`, and the failure
messages (`activation_code` FK, "ownerId must be an active teacher or admin
user") look exactly like seed-data breakage. They are not.

Three representative classes were run at `a29f541` (2026-09-18, before any of
this session's seed or rollover work) and at `main`:

| | `a29f541` | `main` |
| :--- | ---: | ---: |
| `ServiceRecordSourceOptimizationIT` | 2 errors | 2 errors |
| `ProposalApprovalTransactionIT` | 10 errors | 10 errors |
| `AuthFlowIT` | 6 errors | 6 errors |

Identical. The annual-rollover and two-stage seed work neither caused nor
worsened it.

### 3.1 `StudentPrivacyIT` was misfiled here, and is now fixed

**Corrected 2026-09-20.** This class was counted above on the strength of being
red at the same time, and the "one root cause, eight classes" headline was wrong
about it. Its two are **failures, not errors**, and they have nothing to do with
plan 10 or the activation FK:

`a0e9fa6` (2026-09-**16**, the day *before* plan 10) made
`StudentServiceImpl.canonicalStudentNumber` store one case, so a number goes in
as `stu_privacy_1` and comes back `STU_PRIVACY_1`. Two tests asserted on their
own input literal and went red that day.

Confirmed by bisecting rather than by reading: `StudentPrivacyIT` is **6/6 green
at `a0e9fa6~1` and 2 failures at `a0e9fa6`**, in a clean worktree against real
MySQL.

Fixed by asserting on what `create` returned instead of on the literal passed
in, which makes the two tests independent of the casing rule rather than
encoding a second copy of it. A third test, `creationStoresTheStudentNumberInOneCanonicalCase`,
now pins the rule in the one place it belongs — red-checked by removing the
`toUpperCase`, which fails that test alone and leaves the other six passing. The
class is **7/7 green**.

The lesson for the seven that remain: *red at the same time* is not *red for the
same reason*. Each should be run and read before being swept into the shared
fixture helper §5 proposes.

## 3. Cause — one change, two consequences

Plan 10 made an account's default state *inert*. `UserServiceImpl.create` now
yields `PENDING_ACTIVATION` with a null password hash
(`UserServiceImpl.java:124`), which was the point: an imported student must not
be a live login until someone redeems a code for it.

Every IT fixture predates that and still assumes `create` returns a usable,
active user. Two distinct symptoms follow:

**a. The actor id `"system"` is no longer insertable.** `V27` added
`fk_activation_code_issued_by` → `user(id)`. `AuthFlowIT.createUser` (line 195)
calls `activationService.issue(created.id(), "system")`, and `"system"` is a
convention, not a row:

```
Cannot add or update a child row: a foreign key constraint fails
(`activation_code`, CONSTRAINT `fk_activation_code_issued_by`
 FOREIGN KEY (`issued_by`) REFERENCES `user` (`id`))
```

All six `AuthFlowIT` errors are this one line. The FK is correct; the fixture's
sentinel actor is what is wrong.

**b. A freshly created teacher is not an active teacher.** `ProjectServiceImpl.java:382`
rejects a non-active owner, and `ProposalServiceImpl.java:131,187` rejects a
non-active supervisor — both correctly. Fixtures that create a teacher and
immediately name them as owner/supervisor now get
`ownerId must be an active teacher or admin user.` and
`Proposal organization/people fields failed validation.`

## 4. Why it survived two days

Plan 10 was recorded as complete and live-verified on 2026-09-17. Live
verification through the browser exercised the *product* correctly — activation
genuinely works — while `mvn verify` was not run, or was run and its red
ignored. The gap is exactly the one this repo keeps rediscovering: a pass that
confirms the happy path in the UI says nothing about the fixtures that assert
the edges.

## 5. The fix, when it is picked up

Not attempted here — it is a fixture change across the seven remaining classes,
unrelated to the import/export work in flight, and merging it into that would
make both harder to review. (`StudentPrivacyIT` was fixed separately because it
turned out not to need any of this — §3.1.)

The shape is a shared test helper rather than eight local patches:

- **A real actor row.** Fixtures need a persisted user to charge issuance to,
  not `"system"`. One seeded per class is enough.
- **An `activeUser(...)` fixture** that creates *and* activates, so callers
  naming an owner or supervisor get what the validation requires. `AuthFlowIT`
  already redeems a code — that path is the model; it just needs to be the
  default everywhere instead of open-coded once.

Deliberately **not** the fix:

- **Relaxing the two "must be active" checks** — they are the correct rule and
  the reason the roster import is safe.
- **Dropping the `issued_by` FK** — it is what makes an audit trail of who
  issued a credential worth reading.

## 6. Related

- Plan 10: `Design/chinese/09-StudentActivationFlow.md`, memory
  `plan-10-identity-onboarding-status`.
- `108` is the same species of miss from the other direction: a screen that
  renders without error while displaying a figure a third of the truth.
