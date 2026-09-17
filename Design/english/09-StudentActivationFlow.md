# Student Account Activation Flow

> This document describes the complete path a student takes from roster import to
> first login, and the **credential slip** that carries their first credential.
> `Design/chinese/09-StudentActivationFlow.md` is the authoritative source;
> update both in the same change.
> Related: the security and compliance basis is in
> `08-mlps-level1-security-design-guide.md` §8.3 (and the password rule in
> §8.2); the decisions and the
> alternatives that were rejected are in
> `Docs/optimization/10-StudentIdentityAndOnboarding.md`.

---

## 1. The question this design answers

Students arrive by **bulk import**, not one at a time through an admin form. That
forces a question that has to be answered somehow: how does a freshly imported
student get into their account **the first time**?

The conventional answer is "the system generates an initial password and sends it
to them". This design does not do that. Once the server generates a password,
plaintext necessarily exists at some moment — it has to be transmitted, stored,
and destroyed, and all three go wrong.

**The central choice: the server never generates a password at all.** A student's
first access is a single-use **activation code**, and they choose their own
password during activation.

---

## 2. Roles and the boundary between them

| Role | What they may do in this flow |
| :--- | :--- |
| Administrator (admin / sysadmin) | Import the roster; issue slips for a class; reissue for one student (which is what "password reset" means here) |
| Teacher | **No slip path at all**, not even for their own class. A teacher who notices a student never activated must route it through an administrator |
| Student | Activate their account with the slip and choose their own password |

Excluding teachers is deliberate. The slip is the one artefact in the system that
carries a working credential, so keeping its issuance on a single role means one
permission to audit and **no data-scope logic on the credential path**. A
data-scope bug on a reporting endpoint leaks a list; the same bug on this endpoint
mints logins.

The cost is real: the head teacher is usually the one who notices a student never
activated, and every reissue becomes an admin round-trip. That cost was accepted
knowingly, and can be revisited with usage evidence if it proves to be a
bottleneck.

**This flow is not students only.** A staff account is likewise created with no
password, likewise sits pending, and is likewise activated from one single-person
slip as described in §4. The difference lies entirely *upstream* of the slip: a
student's account and username come from the roster import (§3), while a staff
account is created by hand in the admin UI with a username the administrator
chooses. Students are the through-line of this document because they are the bulk
case and the reason the design exists — but "no initial password exists" holds for
every role without exception, and an exception would be the same as not having the
property at all.

---

## 3. The flow end to end

```
① Administrator imports the roster CSV
        │  Once validation passes, each new student gets:
        │    · a roster row
        │    · a login account: 8 random digits as the username,
        │      no password hash, status = pending activation
        ▼
② The account exists but is structurally impossible to log into
        │  No password hash ⇒ no password can ever match
        ▼
③ Administrator picks a class → generates a slip PDF, one per pending student
        │  · the system issues one single-use activation code per student
        │  · the PDF is generated on demand and streamed, **never stored**
        │  · any unused code that class was already holding is retired
        ▼
④ Slips are printed and handed to students — on paper
        ▼
⑤ The student activates (two routes, one code)
        │  · scan the QR → the activation page opens with the code prefilled
        │  · or type the site address → login page → "First time logging in?
        │    Use your activation code" → type the code
        │  The student **chooses their own password** on this page
        ▼
⑥ The account becomes active; the code is spent
        ▼
⑦ The student logs in normally with username + the password they chose
```

### 3.1 When the window closes unused

Codes expire after **7 days** by default — configurable, and printed on the slip.
An account whose window closes unused moves to a **distinct "expired" state**, not
to "locked".

The two are kept apart on purpose. "Locked" means brute-force protection tripped
by repeated failed logins. "Expired" means the slip never reached the student.
An administrator seeing these needs to do completely different things about them,
and collapsing them into one status leaves them unable to tell which they have.

Recovery: the administrator reissues, which is the same path as first issuance.

### 3.2 A lost slip, or a forgotten password

**There is no self-service recovery of any kind.** A student who forgets their
password contacts an administrator **offline** — in person, or by a paper or email
request. The login page's "Forgot password? Contact admin" *is* that rule, not
placeholder copy.

An administrator's reset does exactly what first issuance does: clear the existing
password, return the account to pending, issue a fresh single-use code, and
produce a new slip. **A reset produces no password**, so §1's guarantee holds on
the recovery path too.

The reasoning for having no self-service route is in
`08-mlps-level1-security-design-guide.md` §8.3: an unauthenticated endpoint that
mints a credential for any account the caller names is exactly the attack surface
this design avoids, and supporting delivery would require a trusted channel —
which means collecting minors' phone numbers or email addresses, directly against
§4.3's data minimisation.

### 3.3 Reissuing does not disturb students who already activated

Regenerating a class's slips issues new codes **only for students still pending**.
Anyone who has already chosen a password is untouched, and their password does not
change. Losing five slips must not force the other twenty-five students who were
perfectly fine to activate again.

**For students still pending, however, the old slip stops working immediately.**
That is required rather than incidental: a lost slip is the main reason to
reissue, so the lost paper must stop being usable. The admin screen states this
consequence explicitly before acting on it.

---

## 4. The credential slip

### 4.1 Sample

One slip per student. Below is the layout of what is actually generated (the
domain and names here are placeholders):

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   First time logging in? Use your activation code           │
│   Scan the code, or go to the address below.                │
│                                        ┌───────────────┐    │
│   Name                                 │ ███ ▄▄ █ ███ │    │
│   Alex L.(李*红)                        │ █ ▄█▀▄▀█▄ █ │    │
│                                        │ ██▄ ▀█▀ ▄███ │    │
│   Username                             │ ▀█▀▄█ ██▄▀█▀ │    │
│   48215577                             │ ███ ▄▀▄▄ ███ │    │
│                                        └───────────────┘    │
│   Activation code                        Scan to activate   │
│   HKTZ4-9QP2M                                               │
│                                                             │
│   Check the address bar says exactly                        │
│   https://timebank.example.com                              │
│                                                             │
│   Issued by Teacher Wei Chen at 2026-09-17 08:15 UTC        │
│     —  valid before 2026-09-24 08:15 UTC                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

One slip per A4 page by default. An administrator may choose two per page at
download time to halve the paper, at the cost of having to cut the sheet — that
setting accepts only 1 or 2, not an arbitrary number.

### 4.2 Why each element is on the paper

| Element | Why it is there |
| :--- | :--- |
| **Name** | A head teacher has to hand thirty pieces of paper to thirty people. Without a name that is impossible. It is the only thing on the slip used to find the right person |
| **Username** | The identifier they will type at login. They have never seen it before |
| **QR code** | Scanning opens the activation page with the code already filled in, so the student types no code at all |
| **The code as text** | The fallback for when the QR cannot be used: no camera, a shared classroom computer, a parent's phone that will not scan. The QR and the text carry **the same** code, not two separate credentials |
| **The site address** | **This is a phishing anchor, not merely an address.** The design asks a child to scan a code and then type a credential into whatever page appears, which is precisely the behaviour phishing exploits. Printing the real domain gives them something to check the address bar against. **This sentence can only live on paper**: a page telling you to trust its own address bar is exactly what a fake page would also say |
| **Provenance line** | Issued by, issued at, valid before. If a slip leaks, this identifies the batch and the issuer |

### 4.3 Provenance goes on every slip, not once per page

This one looks like redundancy and invites being "optimised" away. **It must not
be.** Paper gets cut — especially in the two-per-page layout, where cutting is the
expected action. A page-level mark survives on exactly one of the resulting
pieces, and the other loses its provenance entirely.

### 4.4 The screen must use the slip's words, exactly

The login page entry reads "First time logging in? Use your activation code", and
the activation page uses "Activation code" as its field label. If the paper says
"activation code", the screen cannot say "token" or "sign-up key".

A twelve-year-old holding a slip matches paper to screen by the literal words.
This is a usability requirement, not a copy preference.

### 4.5 What the student's chosen password must satisfy

The password a student sets on the activation page is subject to **the same rule as
every other place a password is set** in the system (see
`08-mlps-level1-security-design-guide.md` §8.2): at least 12 characters; at least 3
of lowercase, uppercase, digit and symbol; not a common password nor containing one;
not containing the system's name or the student's own username; and not a single
repeated character or a sequential run.

For a twelve-year-old that is not a trivial bar. So the activation page shows, **as
they type, which requirements are met and which are not**, rather than rejecting the
password after submission — being told one problem at a time is the fastest way to
make someone give up. The "activate and set password" button stays disabled until
every requirement is met and the two entries match, but the unmet requirements remain
visible, so a disabled button is never a dead end.

One rule deserves singling out: **the password must not contain their own username**.
The student is holding a slip with their 8-digit username printed on it, so reusing it
is the most natural thing to reach for — which is exactly why it has to be refused.

### 4.6 Forgiving input

The code uses the **Crockford base32** alphabet, which deliberately omits I, L, O
and U so nothing can be confused with 1 or 0 in the first place. The activation
page normalises as the student types: uppercasing, dropping the printed
separator, and folding the ambiguous characters back (`I`/`L` → `1`, `O` → `0`).
A misread slip usually still works.

The tenth character is a **check character**, verifiable in the browser. A student
who mistypes or transposes a character is told "that code does not look right"
immediately, rather than making a round trip to be told a bare "invalid".

---

## 5. The security properties this flow depends on

| Property | How it holds |
| :--- | :--- |
| **No account is usable before its slip is delivered** | An unactivated account has no password hash and is structurally impossible to authenticate. The guarantee holds by data structure, not by process discipline; the pre-authentication check requires *both* an active status **and** a stored hash, so setting the status by hand achieves nothing |
| **No password plaintext exists in the system** | The server never generates one. A password only comes into existence when the student sets it |
| **Activation codes are not stored in plaintext** | Stored as a digest and looked up by it. Their strength comes from single use, a short window, and the account holding no data yet — not from the digest algorithm. This is a different mechanism from the one protecting passwords and should not be read as equivalent |
| **Codes are not bound to a username** | The code is its own lookup key, so a student redeems it without typing a username. Whoever guesses one also cannot choose whose account it lands on |
| **Resistance to bulk guessing** | Nine data characters give ~4.5×10¹³ possibilities (the tenth is a check character and adds no strength). What actually closes the attack is the **system-wide failure budget**: distributed guessing evades a per-IP limit but not a global one |
| **Issuance is traceable** | Every issuance is written to the audit log: issuer, time, class, number of slips. **The code itself never reaches a log line, an audit row, or an error message** |

---

## 6. Adjacent, but not part of this flow

- **A student created singly through `POST /students` currently gets no account** —
  only the import path creates one. This is a known gap, not part of this design.
- **Multi-factor authentication** is not enabled; the options are compared in
  `08-mlps-level1-security-design-guide.md` §8.5.
