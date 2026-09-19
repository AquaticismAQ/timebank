# 105 — Seeded student logins ignore the username spec

**Status:** Fixed, verified on the local Docker stack and re-seeded to the
shared test server (§4).
**Raised:** 2026-09-19 by the user, pointing at
`Design/chinese/09-StudentActivationFlow.md`:

> student username should follow the spec, not 'studentNNN', why you forget this

## 1. The spec, and what the fixture did instead

§3 of the activation flow is explicit about what an imported student's account
looks like:

> 登录账号：**8 位随机数字用户名**，无密码哈希，状态 = 待激活

and §4.1's sample credential slip prints `48215577` under 用户名.

`StudentUsernameGenerator` is the real implementation and pins two separate
properties, both traced back to `10-StudentIdentityAndOnboarding.md` §2.1:

- **8 digits, no leading zero.** Drawn from `10000000`–`99999999`, because
  `00482155` "silently becomes 482155 the moment anyone opens a list in a
  spreadsheet".
- **Random, not sequential.** Quoting its own Javadoc: *"A sequential username
  leaks enrollment order and cohort size, which is why the sequential option was
  rejected."*

The fixture used `student001` … `student030`, which violates **both** — wrong
format, and sequential by construction.

## 2. Why it matters more than a cosmetic mismatch

**The username is the one thing on the credential slip the student has never
seen before** (§4.2: *"登录时要输入的标识。学生此前从未见过它"*). A demo of the
activation flow — the flow this whole design document exists for — was therefore
showing a login that no real roster import could ever produce.

It also quietly undercut §4.5's password rule *"不得包含本人登录名"*: the reason
that rule exists is that a student holding a slip printed with an 8-digit login
will reach for it as a password. With `student001` as the login, the rule looks
arbitrary rather than necessary.

**Staff accounts are not affected and were correct already.** §2 has an
administrator create staff accounts and choose their usernames, and
`StudentUsernameGenerator`'s Javadoc describes `user.username` as "a VARCHAR
shared with `teacher001`-style staff names". `admin001` / `teacher001` stay.

## 3. Why it was missed

No excuse that survives contact with the facts: issue 103 rebuilt this fixture
from the ground up against the reports, the backend validation rules and the
institutional design, and the username was treated as an opaque label carried
forward from the old fixture rather than checked against a design doc that
specifies it precisely. It is the same failure mode as the rest of 103 — a rule
that existed in prose and was enforced by nothing — in a file whose entire point
had just been to stop doing that.

`verify-fixture.ts` now covers it, which is the part that stops it recurring.

## 4. The fix

### 4.1 A handle and a login are two different things

A random 8-digit number is correct as a login and useless as a cross-reference:
`narrativeRecords`, `cases` and `reviewerForGrade` all have to name a student in
a form a person can read and a generator can resolve.

So `GeneratedStudent` carries both:

| | |
| :--- | :--- |
| `ref` | `student001` — the fixture handle. Every `studentUsername` / `filedByUsername` field in the file holds one of these |
| `username` | `28339473` — the real login, drawn from the fixed PRNG over the same range as `StudentUsernameGenerator`, uniqueness-checked |

`SeedUser` gains the same optional `ref`; staff have none and are referenced by
their own username. `seed.ts` keys every map — passwords, sessions, user ids,
the Phase 7 balance check — on the handle, and uses the real username in exactly
two places: the `user` row it inserts, and the login call.

Drawing from this file's seeded PRNG rather than `Math.random` keeps the
dataset byte-identical between runs, which `MOCK_DATA_GUIDE.md` requires.

### 4.2 The checker

Three new BACKEND invariants: every student login matches `^[1-9][0-9]{7}$`,
all are unique, and they do not form a consecutive run — the last one is the
signature of a sequential allocator, and it is the property §2.1 actually
cared about. **Confirmed red by restoring `studentNNN`: 30 failures.**

## 5. Verification

Fixture invariants pass, and a full seed against the local Docker stack ran to
completion: **exit 0, Phase 7 green for all 30 students.** Phase 2 authenticates
as every one of the 37 accounts in turn, so "the new logins work" is established
by the run itself rather than by inspection.

One further defect surfaced during that run and was fixed: the Phase 6 case
filing resolved a student by `u.username === c.studentUsername`, which stopped
matching once those two stopped being the same value. It failed loudly and
specifically —

```
Case "Manual correction: half hour missed on a shift" is filed by teacher005
for student005, but that user has no linkedStudentNumber in mock-data.ts.
```

— which is the behaviour to want from a seeder; the run died at Phase 6 rather
than writing a case against the wrong student.

Verified in the database afterwards:

| Check | Result |
| :--- | :--- |
| Student logins not matching `^[1-9][0-9]{7}$` | **0** |
| Staff logins | `admin001, sysadmin001, teacher001…005` — unchanged, as intended |
| Sample | `10007944` Jordan W. grade_7, `13239614` Payton Z. grade_10, … |

The shared test server was then re-seeded from the same fixture; see
`integration-test/STATUS.md` for that run.
