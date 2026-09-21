# 150 — A countable claim that was not true

**Status:** **Fixed and deployed 2026-09-21.** The §12 self-assessment's A-16
row. Aquaticism: *"correct sentence of A-16, clean up the config."*

## 1. The sentence

`08-mlps-level1-security-design-guide.md` §8.1 said:

> 免认证接口全系统仅有两个——登录与激活，且两者都不会因调用方指名某个账号而签发凭证。
> (The system has exactly two unauthenticated endpoints — login and activation — and
> neither will issue a credential because a caller named an account.)

The second clause is true and is the one the design rests on. The first is not:
there are seven. Logout, CSRF-token retrieval, two health probes and the
school-name endpoint the login page needs are all `permitAll` as well.

This is the worst kind of wrong sentence in a compliance document, because it is
*checkable* — an assessor can count — and it counts wrong. Nothing about the
system was less safe than claimed; the claim was simply not the one to make.

## 2. What it says now

All seven listed, then the property that actually matters:

- **Login** requires that account's password.
- **Activation takes no username at all.** The code is its own lookup key, so the
  endpoint never has to be told — and therefore never has to confirm — which
  student a code belongs to. That is a stronger statement than "it does not
  issue a credential for a named account": it *cannot* name one.
- The other five touch no account. Logout destroys only the caller's own
  session, a CSRF token is tied to no account, the probes report liveness, and
  the school-name endpoint returns a school's name.

`/actuator/**` is named too, with its loopback-only reachability, rather than
left out of a list that claims to be complete.

A list of seven that can be checked line by line beats an assertion of two that
cannot.

## 3. The configuration

The reverse proxy still carried

```
location ~ ^/api/v1/auth/(login|forgot-password|reset-password)$ {
    limit_req zone=timebank_auth_per_ip burst=5 nodelay;
```

naming two endpoints removed with the self-service recovery feature. Reduced to
`location = /api/v1/auth/login`.

**`activate` was deliberately not added** while the rule was being edited. It is
the other unauthenticated credential path, so adding it looks like an
improvement — but the zone is `10r/m` per IP, and a class activating together
sits behind one school NAT address. A rate limit keyed on the source address
would fail the thirtieth child in the room. Activation's throttling belongs
where it already is: in the application, which has both a per-IP limit and
§8.3's global failure budget, tuned knowing that.

## 4. Verification

The regex became an exact-match location, so the thing to prove is that it still
matches — a typo here removes login's rate limiting silently.

```
real login                     200
14 × login, nonexistent user   401 401 401 401 401 503 503 503 503 503 503 503 503 401
                               ↑ five through (burst=5), then refused, then one
                                 as the 10r/m bucket refills
14 × /ping (control)           200 × 14   — unaffected, so it is the location
                                            doing this and not something global
```

A nonexistent username on purpose: login-failure lockout is real, and hammering
a live account to test a proxy rule would lock it.

And the paths the rule used to name:

```
POST /api/v1/auth/forgot-password → 404 RESOURCE_NOT_FOUND
POST /api/v1/auth/reset-password  → 404 RESOURCE_NOT_FOUND
```

(with a CSRF token — without one both return 403 from the CSRF filter before
routing, which would have looked like the endpoints existing.)

## 5. What it must not do

- **Must not state a countable fact that cannot be counted.** §2.
- **Must not rate-limit activation at the proxy.** §3.
- **Must not stop rate-limiting login.** §4.
