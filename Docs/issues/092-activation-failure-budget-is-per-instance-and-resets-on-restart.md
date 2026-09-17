# 092 — The activation failure budget is per-instance and resets on restart

**Status:** Open — not fixed, deliberately. Cannot be fixed without shared state
that does not exist yet. See §5 for when this becomes urgent.
**Found:** 2026-09-17, answering a question about how a 10-character activation
code resists guessing.

## 1. What the budget is

`ActivationThrottle` guards `POST /auth/activate` with two limits. The per-IP
limit stops one host hammering the endpoint; the **global failure budget**
(default 300 guesses/hour, `timebank.auth.activation.failure-budget-per-hour`) is
the one that matters, because a distributed attacker keeps every individual IP
under its own limit while grinding the pool of outstanding codes anyway. Plan 10
§2.5 says this explicitly: the per-IP limit "cannot be relied on".

The security argument for a 45-bit code therefore rests almost entirely on this
budget, not on the code's length.

## 2. The limitation, exactly

The budget is a Bucket4j **local, in-heap** bucket held in a field on a singleton
`@Component`. No Redis, no JDBC, no distributed proxy:

```java
private final Bucket globalFailureBudget;   // built in the constructor
```

Three consequences:

1. **N application instances = N × the configured budget.** The class javadoc
   already acknowledges "up to double" for `Design.md` §17.1's two-instance
   topology. There is one instance today, so 300/hour is real *now*, and
   silently becomes 600/hour the day a second one starts.
2. **Every restart refills the budget to full.** `capacity(300)` starts full, so
   each deploy hands an attacker a fresh 300 guesses immediately. Four deploys
   happened on the day this was written. Not attacker-triggerable, but it means
   the budget is **not a durable ceiling** over a code's 7-day lifetime.
3. **`refillGreedy`, not a fixed window.** ~1 token every 12 seconds,
   continuously. The endpoint never hard-locks; under sustained attack it
   degrades to a permanent slow drip. There is no escalation and no ban.

## 3. What it costs, in numbers

Codespace is 32⁹ = 35,184,372,088,832 (~45 bits). The tenth character is a
checksum determined by the other nine, so it contributes **zero** entropy against
an attacker who computes it — it exists to catch a child's typo.

Against the intended 300/hour over a code's full 7-day window (50,400 guesses):

| Outstanding codes | P(any hit) |
| :--- | ---: |
| 30 (one class) | ~1 in 23,000,000 |
| 500 | ~1 in 1,400,000 |
| 2,000 (whole school issued at once) | ~1 in 350,000 |

Restarts and extra instances multiply the guess count linearly, so the margin
degrades linearly with them — it does not collapse. That is why this is filed
rather than fixed in a hurry.

## 4. Why it is not fixed now

A correct fix needs the budget in shared state — Bucket4j's distributed proxy
over Redis, or a small database-backed counter. The project has **no Redis and no
Docker** (`01-Overview.md` §6), so introducing one is an infrastructure decision,
not a code change. A database-backed counter is possible but puts a write on the
path of every failed guess, which is the exact path an attacker controls.

## 5. When this becomes urgent

- **Before any second application instance.** This is the trigger. The budget
  silently doubles and the §2.5 analysis stops holding as written.
- **If a school ever issues activation codes for the whole school in one batch**,
  the 2,000-outstanding row above is the operating point, and the margin is
  65× worse than per-class issuance. Issuing per class costs nothing and
  restores it.

## 6. Related

- The per-IP half of this throttle was separately found to be bypassable — it
  keyed on a client-supplied `X-Forwarded-For`. Fixed the same day; see the
  commit "Stop trusting a client-supplied X-Forwarded-For".
- The exhaustion warning used to fire once per guess (~7,200 lines/day during an
  attack). Fixed the same day, one line per 10 minutes carrying the count.
