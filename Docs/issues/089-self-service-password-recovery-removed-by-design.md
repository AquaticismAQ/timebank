# 089 — Self-service password recovery removed: the system has none, by design

Status: **Fixed — not yet live-verified**

## Symptom

Two unauthenticated endpoints existed that between them would set a new password
on any account a caller could name:

| Endpoint | What it did |
| :--- | :--- |
| `POST /auth/forgot-password` | Took a username, minted a single-use reset token, stored its SHA-256 and **wrote the plaintext token to the server log at WARN** |
| `POST /auth/reset-password` | Took that token and a new password, and set the password |

The urgent half is the log line. `AuthServiceImpl.forgotPassword` ended with:

```java
log.warn("Password reset token generated for username='{}' (no mail infra yet — relay manually): {}",
        username, rawToken);
```

That is a **working credential sitting in a log file**, written there
deliberately so an operator could relay it by hand, and the logging policy
(`Design/*/08-mlps-level1-security-design-guide.md` §10.1, and the legal
retention obligation behind it) keeps those logs for **six months**. Anyone with
log access — which is a wider set of people than "anyone who may reset an
account", and includes whoever operates the host — could read a live reset token
for any account whose username they had typed at the endpoint. For students the
username is eight digits printed on a slip that lives in a school bag.

The rest of the symptom is structural rather than a bug report: the mechanism
should not have existed at all. `Docs/optimization/10-StudentIdentityAndOnboarding.md`
§5 has carried a standing recommendation to remove it since issue 088, and 088
itself declined to act on it on the grounds that *"removing a public
authentication endpoint from a deployed system wants an explicit decision, not a
drive-by"*. This change is that decision.

## Root cause

Not a coding error. The endpoints were built in B1, when a token mailed to a user
was the ordinary way to answer "I forgot my password" and there was no other
mechanism to reach for. The log line was an acknowledged stopgap for the absent
notification module, tracked in `00-Status.md` rather than hidden.

What made it wrong was everything built afterwards. Plan 10 §2.4 settled that
first access is by **activation code**, not by a server-generated password, and
§3.7 (issue 088) rebuilt the admin reset on the same path. From that point the
system had two credential mechanisms where the design says there is one — and the
older one was the one nobody could see, because no client ever called it.

It had also become actively hazardous next to §3.7: a token minted for a
just-reset account would set a password on it while leaving it
`PENDING_ACTIVATION` — unable to log in either way, invisible to §3.6's expiry
sweep (which reads a stored hash as proof of activation), and having bypassed the
slip that was the point of the reset. Issue 088 closed that with a one-line guard
in `forgotPassword`, but a guard patching a mechanism that should not be there is
not a resolution.

## The decision

**The system has no self-service password recovery.** A user who forgets their
password contacts an administrator **offline** — in person, or by a paper or
email request. The administrator verifies who they are and issues a single-use
activation slip through `POST /users/{id}/reset-password` (plan 10 §3.7), which
is unchanged by this issue. Three reasons, now recorded in §8.1/§8.2 of both
design twins because they are a statement made to schools, not an internal note:

1. **No unauthenticated endpoint may mint a credential for an account a caller
   names.** Whatever such an endpoint does with the credential afterwards, its
   nature is that knowing a username is sufficient to make the system generate a
   usable one. The strong password policy and lockout in §8.1 defend the
   *guessing* path; they do nothing about a second issuing path that goes around
   passwords entirely. This is the bypass-resistance MLPS Level 1 asks for.
   `POST /auth/activate` stays unauthenticated and is not the same shape: it is
   never told which account it acts on, because the code is its own lookup key.
2. **Delivering a credential needs a trusted channel, and §4.3 deliberately
   collects no student contact details.** Recovery has to reach *the person*, and
   the only channels the system could prove belong to them are an email address
   or a mobile number. Collecting a minor's contact details to support a recovery
   feature trades a convenience for personal data that otherwise need not exist.
   Offline verification needs none of it — the administrator knows the student
   and the school has a roster. §4.3 was tightened in this change to say the
   dependency runs both ways: no self-service recovery is what makes contact
   details unnecessary, and collecting no contact details is what stops
   self-service recovery from being quietly reintroduced.
3. **One credential mechanism, not two.** §8.2's promise — the server never
   generates a password, no password plaintext exists anywhere — has to hold
   everywhere at once, and fails if either mechanism gives way. With one path it
   is guaranteed by there being exactly one code path that sets a password.

**The cost is explicit and accepted**: a student who forgets their password at
night waits until they can find an administrator. For a system where a student
account reaches only that student's own service records and carries nothing of
realisable value, that is a smaller cost than an internet-facing endpoint that
issues credentials for any named account.

## Fix

Removed, not disabled or feature-flagged — a flag would leave the code, and the
log line, one configuration change away from being live again.

**Backend code:**

| File | Change |
| :--- | :--- |
| `iam/web/AuthController.java` | Both handlers deleted, with a comment recording why the paths are absent |
| `iam/application/AuthService.java` | `forgotPassword` / `resetPassword` deleted from the interface; its Javadoc no longer describes "the password-recovery token flow" |
| `iam/application/AuthServiceImpl.java` | Both implementations, plus `generateRawToken`, `sha256Hex`, `RESET_TOKEN_TTL_MINUTES`, the `SecureRandom`, the `PasswordResetTokenRepository` dependency, **and the `Logger` itself** — `AuthServiceImpl` no longer logs at all, because the WARN line was its only log statement |
| `iam/application/UserService.java` / `UserServiceImpl.java` | `resetPasswordWithToken` deleted, along with its `user.resetPassword` / `FORGOT_PASSWORD_TOKEN` audit row |
| `iam/application/dto/AuthDtos.java` | `ForgotPasswordRequest` and `ResetPasswordRequest` deleted |
| `iam/domain/PasswordResetToken.java` | Deleted |
| `iam/infrastructure/PasswordResetTokenRepository.java` | Deleted |
| `iam/domain/ActivationCode.java` | Javadoc cross-reference to the deleted entity rewritten (it had explained the hash-as-lookup-key shape by pointing at it) |
| `lifecycle/infrastructure/PurgeQueryRepository.java` | `delete from password_reset_token where user_id = :userId` removed — see below |

**Schema:** `V28__drop_password_reset_token.sql` drops the table.

Plan 10 §5 had recommended dropping the table *later*, as a separate decision, so
that a rollback would be a redeploy rather than a restore. That reasoning is
reversed here on one fact it did not have: **the table holds zero rows on the
live server** (verified before this change). There is nothing a rollback would
restore, so staging bought nothing, and an orphaned credential table is exactly
the sort of thing a later session reconnects to something.

**`PurgeQueryRepository` is the one that could not wait.** Its delete is a raw
SQL string, so nothing would have failed to compile if it had been left behind:
the first symptom would have been the graduated-cohort purge blowing up against a
table that no longer exists, in a batch job, long after the change. It goes in
the same commit as the drop.

**Deliberately not changed:**

- **The login page copy.** `auth.login.forgotPassword` already reads *"Forgot
  password? Contact admin"* / *"忘记密码？联系管理员"* in both languages. That is
  the new policy stated correctly; it was never a link to the removed endpoints.
  No frontend file was touched by this change at all — nothing in `frontend/src`
  called either endpoint.
- **`V3__iam.sql`.** The `CREATE TABLE password_reset_token` stays where it is.
  Applied migrations are immutable history; V3 is not edited to pretend the table
  never existed.
- **`POST /users/{id}/reset-password`.** The admin-initiated path is the
  sanctioned recovery route and is untouched. It is the regression that would
  actually hurt, and is covered below.

## Test plan

Each guard below was confirmed red by reverting what it guards, not merely
observed green.

| Proof | Test | Revert that turns it red |
| :--- | :--- | :--- |
| (a) Both paths are genuinely unroutable | `iam/web/AuthControllerRoutingTest` — real `DispatcherServlet` through MockMvc; each removed path asserted to resolve to **no `HandlerMethod`** and to answer **404**, under POST and under GET | Re-adding a bare `@PostMapping("/api/v1/auth/forgot-password")` stub with no DTO and no service call: 2 tests red. That the stub had no body and no dependencies is the point — the guard is about the path answering, not about the old code returning |
| (a) …and the 404 is not an artefact of the slice | `the_surviving_auth_paths_are_routed_in_this_same_slice` — `/auth/login` and `/auth/activate`, same MockMvc, same body, assert a `HandlerMethod` **is** resolved and the status is 400 | A slice that mapped nothing would 404 on everything and pass without this |
| (b) The admin reset still works end to end | `iam/application/AdminCredentialResetTest` (14) — unchanged except for the deleted companion test: the credential is cleared, the old password stops authenticating through a real `DaoAuthenticationProvider` while an untouched control account presenting the same password string still signs in, the code scraped **out of the rendered PDF** redeems once and sets the new password, an earlier outstanding code is retired, and no password, code or hash appears in any response, audit row or log line | Untouched by this change; it is the regression gate, and it passes |
| (b) Session revocation on reset | `iam/web/CredentialSlipControllerTest` (5) | Untouched |
| (c) No reference survives in `backend/src/main` | `architecture/NoSelfServicePasswordRecoveryTest` — scans every `.java` under `src/main/java` with comments stripped, for `PasswordResetToken`, `ForgotPasswordRequest`, `forgotPassword`, `resetPasswordWithToken`, `auth/forgot-password`, `auth/reset-password`, `password_reset_token`; and allows only V3 and V28 to name the table in a migration | Restoring `PurgeQueryRepository`'s raw SQL line: red, naming the file and the token. That case is the reason the rule reads source rather than byte-code — a table name in a string is invisible to ArchUnit and is precisely the reference that fails at runtime rather than at compile time |
| (c) …and the scanner is not passing vacuously | `the_scanner_reads_real_content` — asserts the tree was found (>100 files), that `/api/v1/auth/activate` and `/{id}/reset-password` **are** found after comment stripping (so the forbidden list is narrow enough not to have banned the surviving admin path), and that the comment stripper removes comments rather than code | A stripper that flattened files to nothing would make the scan pass on an empty string |

Comments are stripped before matching on purpose: several files now carry a
comment explaining *why* the mechanism is gone, and those comments name it. A
rule that banned the words would push the reasoning out of the codebase in order
to satisfy itself. The rule is about code — a mapping, an identifier, a table
name in a query.

`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn clean test`: **257 tests, 0
failures, 0 errors, BUILD SUCCESS** (8 new: 5 routing, 3 architecture; 1 deleted
with its subject).

**Not performed this session, by instruction:** no deploy, no `mvn verify` / `*IT`
run, no browser pass. The migration is therefore **unapplied** — V28 has not run
against any real MySQL, so the `DROP TABLE` has been reviewed but not executed,
and the emptiness of `password_reset_token` was verified before the change rather
than re-confirmed at drop time.

Live checks left open: apply V28 on deploy and confirm Flyway reports V28
applied; `curl` both removed paths against the deployed server and confirm 404
(the deployed app serves the built frontend from the same context, so confirm
specifically that neither returns a credential or a 200, rather than assuming the
status code); run one full admin reset through Admin Portal → System Admin →
Users and confirm the slip still downloads and redeems; and run the graduated
cohort purge, or at least one `deleteAllForStudent`, against a real schema after
V28 — that is the one path this change could break and unit tests cannot reach.

## Resolution

Backend: as tabulated under **Fix**, plus `V28__drop_password_reset_token.sql`.
Tests: `iam/web/AuthControllerRoutingTest.java` (new),
`architecture/NoSelfServicePasswordRecoveryTest.java` (new),
`iam/application/AdminCredentialResetTest.java` (the forgot-password companion
test removed with its subject; a comment in its place records what it guarded and
where the replacement guarantee is asserted).

Docs:

- `Design/chinese/08-mlps-level1-security-design-guide.md` and its English twin —
  §8.1 gains a bullet stating there is no self-service recovery and that the
  system has exactly two unauthenticated endpoints; §8.2 gains the full statement
  and its three reasons, including the explicit cost accepted; §4.3's
  account-recovery line tightened so the dependency is stated as mutual rather
  than one-directional; §11's identification-and-authentication mapping row and a
  new §12.5 checklist item **A-16**. Item IDs A-01…A-15 were not renumbered.
- `Docs/integration/APIContract.md` and `_CN.md` — the two rows removed from the
  §2.2 endpoint table, and a paragraph below it recording that their absence is
  deliberate and that no client-side "forgot password" flow should be added.
- `Docs/optimization/10-StudentIdentityAndOnboarding.md` §5 — the removal
  recommendation marked done, the §3.7 status row updated, and the "wait a
  release before dropping the table" reasoning reversed with the fact that
  reversed it.

No frontend file was touched.

*Verified by unit tests only, with proofs (a) and (c) each confirmed red against
a real revert and proof (b) resting on the pre-existing admin-reset suite, which
was itself built revert-by-revert in issue 088. **Verification gaps, all
deliberate this session: nothing was deployed, `mvn verify`/`*IT` was not run, no
browser pass happened, and V28 has therefore not been applied to any database.**
The cohort purge after the table drop is the specific path a later session should
exercise against real MySQL before treating this as closed — it is raw SQL, and
raw SQL is what the compiler cannot check.*
