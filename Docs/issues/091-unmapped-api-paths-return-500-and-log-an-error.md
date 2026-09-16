# 091 — Unmapped API paths return 500 and write an ERROR line

**Status:** Fixed — verified live.
**Found:** 2026-09-17, while verifying issue 089's endpoint removal on the deployed server.
**Age:** Pre-existing. Not introduced by 089; the removal is only what made anyone look.

## 1. Symptom

A request to a path that maps to nothing under `/api/v1/auth/**` returned **500
`INTERNAL_ERROR`**, and wrote an ERROR line with a full stack trace.

Probed live, before the fix:

| Path | Status |
| :--- | :--- |
| `POST /api/v1/auth/this-never-existed` | **500** |
| `POST /api/v1/auth/forgot-password` (removed in 089) | **500** |
| `POST /api/v1/totally-made-up` | 401 |
| `POST /api/v1/users/nope-not-real` | 401 |

Paths outside `/auth/**` never reach the problem: the security filter refuses them
with 401 first. `/auth/**` is `permitAll`, so an unmatched path there falls all the
way through.

## 2. Why it matters more than the status code

The wrong status is the smaller half.

Every such request logged at **ERROR** with a stack trace. Anything scanning that
prefix — a vulnerability scanner, a stale client, a typo in a script — could
manufacture unlimited ERROR-level noise in a log that §10 of the MLPS design commits
to retaining for **six months**. A log where routine 404s appear as errors is a log
where a real fault is harder to find, and the one place that is guaranteed to be read
after an incident.

The response body did *not* leak internals: it was the standard envelope with a
generic message and a `requestId`.

## 3. Cause

Spring resolves an unmatched URL against the static-resource handler last, so "no such
endpoint" surfaces as `NoResourceFoundException`. `GlobalExceptionHandler` had no
branch for it, so it fell to the catch-all `@ExceptionHandler(Exception.class)` —
which is correctly built to assume anything reaching it is a genuine server fault, and
so logs at ERROR and returns 500.

## 4. Fix

An explicit `@ExceptionHandler(NoResourceFoundException.class)` returning
`RESOURCE_NOT_FOUND` (404), logged at **DEBUG**. A 404 is a statement about the
request, not about the server's health.

## 5. The test that was passing while production was broken

`AuthControllerRoutingTest` (issue 089) asserted 404 for the removed paths — and
passed — **because it excluded `GlobalExceptionHandler` from its slice**. The
exclusion was documented and deliberate, and its stated reason was accurate: the
catch-all would have turned the 404 into a 500. But the consequence was a test
asserting behaviour the deployed application did not have. The live probe is what
exposed the gap.

The exclusion is now removed, so the test runs through the same handler the real
application uses and would fail again if the new branch were deleted.

Removing it also corrected a second assertion. The control case asserted that
surviving paths answer **400** to an empty body; with the real handler in the slice
they answer **422** (`VALIDATION_FAILED`, contract §2.6), which is what the live
server actually returns. The 400 had been Spring's un-handled default, visible only
because the handler was excluded.

**The general lesson, worth carrying:** excluding a component to make an assertion
observable can quietly change what is being asserted. Where the excluded component is
part of the production request path, the test stops describing production.

## 6. Verification

Live, after deploying: unmapped paths return 404 with no ERROR line, and both the
removed and surviving paths from issue 089 keep their correct statuses.
