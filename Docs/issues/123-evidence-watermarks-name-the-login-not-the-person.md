# 123 — Evidence watermarks name the login, not the person

**Status:** **Fixed and deployed** 2026-09-20, same day, live-verified (§6).
**Raised:** 2026-09-20 by Aquaticism:

> Watermark of pdf and picture should use user's name, email, not username.

## 1. What is wrong

Every evidence file a staff member downloads is marked on the way out with a
line identifying who took the copy (`08-SecurityPrivacyHardening.md` §2.1/§3.6,
finding W-8). That line has, since the feature was built, been the **username**:

```
admin001  ·  2026-09-16 13:04:02 UTC  ·  Volunteer hours verification only
```

The mark exists to be read by a human in a leaked screenshot, by someone who
may not be an administrator of this system — a parent, a partner organisation,
whoever the photo of a minor reached. `admin001` tells that reader nothing. It
is a login handle, meaningful only to whoever can open the Users tab and look
it up. A mark nobody can act on deters nobody, which is half of what the
watermark is for. (Only staff handles ever appear — decision D-1 marks staff
downloads only — so this is not about student logins; it is about the fact that
a handle is not a person.)

The identity the watermark wants is the one a person answers to: their name,
and an address a reader can actually contact.

## 2. What it should say

```
Teacher Wei Chen <wei.chen@example-township-school.edu>  ·  2026-09-16 13:04:02 UTC  ·  Volunteer hours verification only
```

`Name <email>` is RFC 5322's mailbox form — every reader already parses it, and
it is unambiguous about where the name stops and the address starts, which
matters on a diagonal tiled overlay with no punctuation cues.

**The timestamp and the purpose string stay.** They are not decoration: the
timestamp is what distinguishes two copies taken by the same person, and the
purpose string is deliberately fixed English (§2.1) so two copies compare
cleanly regardless of who was logged in with which UI language.

### The fallback, and why there has to be one

**Email is optional on every account** — `user.email` is `NULL`-able,
`CreateUserRequest.email` carries `@Email` and deliberately no `@NotBlank`
(issue 096 §1), and issue 106 is the whole argument for why the Users tab
stopped showing it. So "name and email" is not always available, and a mark
reading `Teacher Wei Chen <>` identifies nothing — display names are not
unique, and nothing in the schema makes them so.

When the account has no email the mark keeps the username instead:

```
Teacher Wei Chen (admin001)  ·  2026-09-16 13:04:02 UTC  ·  Volunteer hours verification only
```

`username` is the only column on `user` with a `UNIQUE` key, so it is what
guarantees a mark resolves to exactly one account. The fallback is not a
grudging compromise with §1 — it is the case where the name alone would be
untraceable.

Worth stating plainly, since the request was "not username": **email is not
unique in the schema either.** What makes any of these forensically resolvable
is the pairing with the audit trail — `file.crossStudentAccess` records the
actor's opaque user id and the file id, and the watermark's UTC timestamp is
what joins a leaked copy to that row. The mark's job is to be *readable*; the
audit log's job is to be *exact*.

## 3. Where the identity comes from

The downloader reaches `FileServiceImpl.downloadContent` as a
`CookieSessionUserDetails`, built from the `AuthenticatedUserView` that
`UserServiceImpl.toAuthView` puts in the session at login. That view already
carried `displayName`; it did **not** carry `email`, so the email had to be
plumbed one level:

| File | Change |
| :--- | :--- |
| `AuthenticatedUserView` | new `email` component |
| `UserServiceImpl.toAuthView` | passes `user.getEmail()` |
| `CookieSessionUserDetails` | `email()` accessor, plus a declared `serialVersionUID` (see below — not optional) |
| `EvidenceWatermarker.describeViewer` | composes the new line |
| `FileServiceImpl.downloadContent` | passes name, email, username |

Session-carried, not looked up per download: the file module would otherwise
have to query `iam` on every delivery for a value the session already holds,
and the module-boundary rules (ArchUnit, B0.2) do not let it reach that way in
any case.

`email()` sits on `CookieSessionUserDetails` and **not** on the
`TimeBankPrincipal` interface, matching `sessionTimeoutMinutes()`. The
interface is the contract Phase 3's Mini Program principal will also have to
satisfy (`TimeBankPrincipal`'s own note); an optional field one delivery path
formats is not part of it.

### The deploy hazard this nearly shipped with

The session principal is **Java-serialized into `SPRING_SESSION_ATTRIBUTES`**,
so its class shape is a wire format, and `backend/scripts/deploy.sh` does not
clear that table.

`AuthenticatedUserView` is safe on its own: a record class's `serialVersionUID`
is `0L` unless declared (measured: `ObjectStreamClass.lookup(...)` returns `0`),
so adding a component leaves stored blobs readable with the new field `null`.

**`CookieSessionUserDetails` was not.** Its identifier was never declared, and
the default is computed from the **method list**, not just the fields — so
adding one `email()` accessor changed it, and every session open at deploy time
would have failed on its next request:

```
InvalidClassException: cn.timebank.security.application.CookieSessionUserDetails;
  local class incompatible: stream classdesc serialVersionUID = 848046325616438304,
                            local   class     serialVersionUID = -394976465083591385
```

That is a measurement, not a worry: a blob was written with the pre-change
classes in one JVM and read back with the changed ones in another. The fix is
to **declare** the old value, which is what the class should always have done;
the same read then succeeds, with `email` null. So:

- Anyone already logged in when this ships keeps their session and gets §2's
  `Name (username)` fallback until they next log in — which is exactly why a
  post-deploy check must use a **fresh login**, not an open tab.
- Nobody is logged out, and no request 500s on a stale cookie.
- **An 11-argument convenience constructor** keeps the pre-email signature
  alive rather than editing 23 construction sites across 18 test files for a
  watermark fix. `null` there is a real value (§2), not a placeholder — the
  same precedent `CreateUserRequest`/`UpdateUserRequest` already set. There is
  exactly one production construction site (`toAuthView`) and it passes the
  real email.

### The PDF's character-set limit, unchanged but now more likely to bite

The PDF overlay uses Standard-14 Helvetica, which is WinAnsi-encoded;
`EvidenceWatermarker.sanitizeForWinAnsi` already replaces anything outside it
with `?` so an unmappable glyph cannot turn a viewable document into a 500.
Usernames were ASCII, so this almost never fired. **Display names in this
school are Chinese**, so from now on it fires on most PDF downloads and the
name renders as `??? <address>`.

Accepted deliberately, not overlooked. Embedding a CJK-capable font is a
multi-megabyte dependency with its own licence question, for a line whose
identifying half — the address, or the username fallback — is ASCII either way
and survives intact. Images are unaffected: `Graphics2D` with `SANS_SERIF`
renders CJK correctly, and images are the overwhelming majority of evidence.
If the PDF name is ever wanted in full, that is a font decision of its own, not
something to smuggle in here.

## 4. Test plan

`EvidenceWatermarkerTest` asserted on `"admin001"` throughout. Those
assertions are now on the rendered identity, including the two cases the old
tests could not have distinguished:

- the composed line contains the name and the address and **not** the username;
- an account with no email falls back to `Name (username)`;
- two staff who share a display name still produce different marks;
- the PDF path still extracts the address as real, selectable text (D-2's point)
  even when the name degrades to `?`.

## 5. Red check

`identify()` was reverted in place to `return username;` — the old mark,
exactly — and the class re-run. **9 of the 17 tests failed**, which is what
makes the assertions worth having: they discriminate the new text from the old
rather than merely passing beside it.

```
Tests run: 17, Failures: 9, Errors: 0, Skipped: 0

viewerTextCarriesTheNameEmailTimestampAndPurpose
  Expecting actual:  "admin001  ·  2026-09-16 13:04:02 UTC  ·  Volunteer hours verification only"
  to contain:        "Teacher Wei Chen <wei.chen@example-township-school.edu>"

anAccountWithNoEmailFallsBackToTheUsername
  to contain:        "Teacher Wei Chen (admin001)"

theComposedTextItselfKeepsAChineseNameIntact
  to start with:     "李老师 <wei.chen@example-township-school.edu>"

also: theLoginIsNotWhatIdentifiesAViewerWhoHasAnEmail,
      twoStaffSharingADisplayNameAreToldApartByTheEmailAlone,
      watermarkedPdfContainsTheViewersIdentityAsRealText,
      twoViewersOfTheSamePdfGetDifferentDocuments,
      aChineseNameDegradesInAPdfButTheIdentifyingHalfSurvives,
      aChineseNameWithNoEmailStillLeavesTheUsernameReadableInAPdf
```

**`theFallbackNeverRendersEmptyBrackets` survived the revert, correctly** — a
username-only mark has no angle brackets either. It guards a *different*
mistake (`Name <>` for an account with no email), so it is not coverage of this
change and is not counted as such.

The first version of `twoStaffSharingADisplayName…` also survived, because it
varied the username along with the email and the old mark therefore still told
the two apart. Holding the username constant is what turned it into a real
test of the new text — the red check is what exposed that, which is the point
of running one.

Restored, the whole fast suite is `Tests run: 359, Failures: 0, Errors: 0`.

## 6. Verification

### 6.1 The PDF could not draw the name at all — fixed on review

The first version accepted that a CJK display name renders as `???` on PDFs
(Standard-14 Helvetica is WinAnsi-encoded), on the grounds that the email half
still identifies. **In this school most names are Chinese**, so that would have
put `??? <wei.chen@…>` on essentially every downloaded document — which reads as
a rendering bug, and tells a reader trying to trace a leaked file nothing.

So the PDF mark now **drops an undrawable name rather than mangling it**, and
falls back to the email, which identifies a person just as well and is ASCII;
only an account with no email falls through to the username. Images keep the
full `Name <email>` form — `Graphics2D` draws CJK correctly. The two formats
carry different marks on purpose.

Red-checked by disabling the degradation: exactly the three new tests fail —

```
aPdfMarkDropsAnUndrawableNameInFavourOfTheEmailRatherThanPrintingQuestionMarks
aPdfMarkForAnUndrawableNameWithNoEmailFallsBackToTheUsername
everyPdfMarkSurvivesTheWinAnsiSanitizerUnchanged
  [drawable char in 李老师 <wei.chen@…>  ·  2026-09-16 13:04:02 UTC  ·  …]
```

— while `aPdfMarkKeepsAnAsciiNameBecauseItCanActuallyBeDrawn` still passes, so
the guard is not simply "always degrade". `mvn -o test`: 363 green.

### 6.2 The mark on a real delivered file

Not "the code composes the right string" — the bytes the server actually handed
back. An evidence PDF was downloaded as staff over a fresh login, its content
stream inflated, and the `Tj` operand decoded:

```
WATERMARK DRAWN IN THE DELIVERED PDF:
  Teacher Wei Chen <wei.chen@example-township-school.edu>  ·  2026-09-20 12:24:07 UTC  ·  Volunteer hours verification only
```

Where that line previously read `admin001`.

### 6.3 Not exercised

- **The CJK path live.** The only staff account on the test server has an ASCII
  display name, so §6.1's degradation is proven by unit test and red check but
  has not been seen on a delivered file. Worth a look the first time an account
  with a Chinese name downloads a PDF.
- **The image mark**, for the same reason the case-review fix could not check a
  thumbnail: the seeded evidence is all PDF.

### 6.4 The session-serialization hazard

`CookieSessionUserDetails` is Java-serialized into `SPRING_SESSION_ATTRIBUTES`
and had no declared `serialVersionUID`, so adding `email()` changed the derived
value and every open session would have failed with `InvalidClassException` on
its first post-deploy request. Pinning the old value fixes it — see the constant's
own comment. **A session open across this deploy keeps `email=null` and so gets
the `Name (username)` fallback until its owner logs in again**, which is why the
check above was run over a fresh login rather than an open tab.

## 7. What it must not do

- **Must not drop the timestamp or the purpose string.** §2. They are the rest
  of the traceability, and the purpose line must stay fixed English.
- **Must not render an identity with no unique component.** `Name <>` is worse
  than the username it replaced (§2).
- **Must not localize the mark.** §2.1's forensic-consistency rule is unchanged.
- **Must not widen any API response.** The email now travels in the session
  principal only; no DTO the frontend receives gained a field.
- **Must not watermark a student's own evidence.** Decision D-1 is untouched.
- **Must not touch the stored file.** The mark is still burned into a copy on
  delivery, and `EvidenceFile.sha256Hash` still describes the stored original.

*Verified by unit tests against the composed string and by comparing the
integration-suite failure set before and after. Not live-verified: no deploy
and no browser this session, both by instruction — a later session must read a
real downloaded file before this is closed.*
