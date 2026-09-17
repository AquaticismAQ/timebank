# 097 — The "Locked" status can never match anything

**Status:** Fixed, live-verified (§5).
**Raised:** 2026-09-17 by the user, asking how and when an account reaches the
`Locked` status offered in the Users tab's status filter.

The honest answer was: it doesn't. The lockout mechanism works correctly and
has always worked — it just doesn't use the column the UI reads.

## 1. What actually happens on a lockout

`UserServiceImpl.recordFailedLogin`:

```java
int attempts = user.getFailedLoginAttempts() + 1;
if (attempts >= maxFailedAttempts) {
    user.setLockedUntil(Instant.now().plus(lockoutDurationMinutes, ChronoUnit.MINUTES));
```

- **Trigger:** five consecutive failed passwords against an *existing* username
  (`TIMEBANK_LOCKOUT_MAX_ATTEMPTS`, default 5). An unknown username has no row
  to increment, so it cannot lock anything.
- **Effect:** `locked_until = now + 15 minutes`
  (`TIMEBANK_LOCKOUT_DURATION_MINUTES`). The counter is cumulative **since the
  last successful login**, not windowed — three failures on Monday and two on
  Friday lock the account.
- **Enforcement:** `User.isLocked()` → `CookieSessionUserDetails
  .isAccountNonLocked()` → Spring throws `LockedException` → `AuthServiceImpl`
  returns 401 *"This account is temporarily locked"* and writes a login-log row
  with reason `LOCKED`.
- **Release:** automatic once the clock passes `locked_until`; a successful
  login clears the counter; `POST /users/{id}/unlock` clears both.

**`status` is never written.** It stays `ACTIVE` throughout.

## 2. Three consequences

**The filter matches nothing.** `UserListQueryRepository` filters
`and u.status = :status`, so selecting *Locked* asks for rows no code path
produces. Confirmed against the live database: 37 `ACTIVE`, 1
`PENDING_ACTIVATION`, 1 `INACTIVE`, **0 `LOCKED`** — with one `ACTIVE` account
carrying failed attempts.

**The column lies.** A currently locked-out account displays as **Active**, so
an administrator fielding "I can't get in" has nothing on screen to confirm it,
and the one status that is genuinely time-limited is the one never shown.

**There is no unlock button.** `api.users.unlock` exists in the API client and
has **no caller anywhere in `frontend/src`**. The endpoint has been reachable
only by hand since it was written.

A fourth, latent: `unlock()` clears `failed_login_attempts` and `locked_until`
but not `status`. Since `isLocked()` is `status == LOCKED || locked_until >
now`, an account that somehow reached `status = 'LOCKED'` — via `PATCH
/users/{id}` with `{"status":"LOCKED"}`, which `parseStatus` accepts — could
**not be released by the unlock endpoint at all**. Nothing in the UI sends that
today, so it is a trap rather than a live defect.

This is the family issue 095 §2 describes: a control that looks authoritative
while answering a narrower question than the one asked. Here it is narrower
still — the question it answers has no answers.

## 3. Why not simply set `status = LOCKED` on lockout

That is the obvious fix and it is the wrong one. The lock **expires on a clock**,
and nothing runs at expiry to write the status back. A persisted `LOCKED` would
therefore be correct for fifteen minutes and stale forever after, unless a sweep
job were added purely to un-write it — a scheduled job to maintain a value that
is already derivable from a column sitting in the same row.

It also makes the trap above permanent rather than latent: every lockout would
write the status that `unlock()` cannot clear.

## 4. The fix

**`status` on the wire becomes derived; `status` in the database keeps its
meaning.** The stored column stays "the lifecycle state an administrator set"
(`ACTIVE` / `INACTIVE`) or the activation machinery set (`PENDING_ACTIVATION` /
`ACTIVATION_EXPIRED`). What `GET /users` reports is
`locked_until > now ? LOCKED : status`, and the filter matches that same
expression rather than the raw column.

Expiry is then self-healing — the derivation stops being true the instant the
clock passes — and the column and the filter agree by construction rather than
by two pieces of code being written to agree.

One definition, mirrored in two places because the list read model is raw SQL:
`User.effectiveStatus()` in the domain, and a `case when` in
`UserListQueryRepository`. A test asserts the two agree.

Four supporting changes:

- **`PATCH /users/{id}` refuses `status: "LOCKED"`** with a message pointing at
  the unlock endpoint. Now that `LOCKED` is derived, persisting it is exactly
  the unreleasable state described in §2, and refusing it at the service closes
  that for every caller rather than for the one form.
- **`unlock()` also clears a persisted `LOCKED`**, so an account left in that
  state by an older client is recoverable rather than permanently stuck.
- **`V29` normalises any existing `status = 'LOCKED'` row to `ACTIVE`.** Zero
  rows here; other deployments are not guaranteed to be so lucky, and such a row
  is unreachable by every remaining code path.
- **`lockedUntil` joins the DTO**, so the UI can say *when* the lock releases
  instead of only that it exists, and an **Unlock** action appears on locked
  rows — finally giving `api.users.unlock` a caller.

### The write-back trap this introduces, and how it is closed

Deriving the status the UI shows creates a new hazard: the edit dialog seeds its
form from the row it was opened on, and `handleEdit` sent `status` unconditionally.
An administrator opening a locked account to fix a typo in the display name would
have written the derived `LOCKED` into the database — producing, through an
unrelated edit, precisely the unreleasable account §2 describes.

`handleEdit` now sends `status` **only when it differs from what was loaded**.
`PATCH` already treats an omitted field as "don't touch", so an untouched toggle
sends nothing. The backend's refusal of `LOCKED` is the second line: the UI
cannot rely on itself here.

## 5. Verification

Backend and frontend deployed; `V29` applied cleanly (0 rows to normalise, as
expected). The lockout was produced the real way — **five genuinely failed
logins through `POST /auth/login`** — not by writing `locked_until` by hand, so
what is tested is the mechanism, not a fixture resembling it.

| Check | Result |
| :--- | :--- |
| 5 wrong passwords for `teacher003` | 5 × 401 |
| 6th attempt | 401 *"This account is temporarily locked. Try again later."* |
| Stored row | `status=ACTIVE`, `failed_login_attempts=5`, `locked_until=2026-09-17 16:09:08.473` — **the column is untouched, which is the premise** |
| `filter[status]=LOCKED` | `totalItems: 1` → `teacher003`, `status: "LOCKED"`, `lockedUntil` present |
| `filter[status]=ACTIVE` | `totalItems` 37 → **36**, `teacher003` absent — it answers to one or the other, not both |
| Users tab, Locked selected | `1 total · page 1 / 1`, row badge reads **Locked** |
| Edit dialog | "This account is locked out" + *"It unlocks by itself at 2026-09-18 00:09"*; status toggle **disabled** with "Unlock this account before changing its status" |
| **Unlock now** | row leaves the filtered set → `0 total`; DB shows `failed_login_attempts=0`, `locked_until=NULL` |
| Sign in afterwards | **200** with the account's *original* password — an unlock restores access, it does not reset a credential |
| `filter[status]=ACTIVE` again | back to **37**; `LOCKED` back to **0** |
| `PATCH {status:"LOCKED"}` | 400 *"An account cannot be set to LOCKED…"* |
| Control: same body, `status:"INACTIVE"` | **200** |

The last two are a pair on purpose. The first probe of the refusal returned 400
with *"A staff ID is required for non-student accounts"* — the controller's
cross-field check firing before `parseStatus` was ever reached. That is a
passing-looking result that proves nothing, and it is the second time in two
issues a malformed probe nearly banked a false positive. The control is what
establishes that the 400 above is about `LOCKED` specifically.

`totalItems` is asserted throughout rather than the visible rows: a filter
applied to the page rather than the query looks identical on a one-row result.

### `UserListFilterSortIT` — not run at the time, run since

The four new integration tests compile but were **not executed**. `mvn verify`
needs a MySQL account with `CREATE`/`DROP` on `timebank_it_%`, which lives in
the untracked `test-server-credits.md`; this environment has only the
application credentials, and the run fails at
`MySqlTestSchemaExtension.beforeAll` with *"Access denied for user
'timebank_app'"*. The live verification above covers the same ground against the
real deployed stack, but the standing guard is unproven until someone runs it
with those credentials.

One thing they would have caught, found by reading rather than running: since
issue 096 every account is created `PENDING_ACTIVATION`, so the class's
`createUser` fixture no longer produced the `ACTIVE` accounts that
`theStatusFilterExcludesOtherStatuses` and the new lockout tests assert against.
The helper now activates, and its Javadoc says why. `mvn test` cannot see this —
the file compiles either way.

**Resolved 2026-09-18 (issue 101).** The local Docker stack made those
credentials available to anyone: `mvn verify` against the container, after the
one-off `GRANT ... ON \`timebank_it_%\``, runs **`UserListFilterSortIT`, 11
tests, all passing** — including the four lockout tests added here and the
fixture fix above. The guard is no longer unproven.
