# School Volunteer Service Hours System: MLPS Level 1 Security Design Proposal

> This is the **security design proposal and compliance statement** presented to the school.
> It sets out the security architecture the system uses, the technical measures provided to
> satisfy Level 1 of China's Multi-Level Protection Scheme, and the **infrastructure the
> school must provide**. It is not an internal implementation plan.
> `Design/chinese/08-mlps-level1-security-design-guide.md` is the authoritative source;
> update both in the same change.

---

## 1. Classification and compliance basis

### 1.1 System scope
The system records and manages students' volunteer service hours and supporting evidence
(photos, scans). Its users are school administrators, reviewing teachers, and students. It
serves internal school administration only, is not open to the general public, and is not
connected to public service networks. If the system were disrupted or damaged, the impact
would be confined to internal school administration and would not endanger national
security, public order, or the public interest.

### 1.2 Classification
Under the MLPS classification guide and the MLPS 2.0 standard, this system is classified at
**Level 1 (user self-protection level)**.

Level 1 systems are **self-protected by the operator**: filing with public security
cybersecurity authorities is not mandatory, and a third-party assessment report from an
accredited body is not required. This document and its companion materials constitute the
design basis and self-assessment record for that self-protection.

### 1.3 Three obligations that apply regardless of classification

To avoid misunderstanding, the following are not waived by Level 1's exemption from
assessment, and this proposal covers all three:

1. **Network logs must be retained for at least six months** — required by Article 21 of
   the Cybersecurity Law, independent of MLPS level. See §10.
2. **PIPL applies independently.** The system processes minors' information; personal
   information of minors under 14 is **sensitive personal information** requiring separate
   guardian consent. The school bears that obligation as the personal information handler;
   this system acts as an **entrusted processor**, processing on the school's instructions
   and providing the corresponding safeguards.
3. **Source-masked data is still personal information.** The school retains the mapping from
   masked value to real student, so what the system holds is **de-identified** rather than
   **anonymized** data (PIPL Article 73), and the security obligations remain. The real
   benefit is that even in the event of a breach, the exposed content is insufficient to
   identify an individual student directly, which substantially reduces the consequences.

### 1.4 Cryptographic algorithms
Level 1 does not trigger the commercial cryptography assessment (密评, which generally
applies from Level 3). Strong general-purpose algorithms such as **AES-256-GCM** are
therefore sufficient, and the Chinese national SM4 algorithm is not required. The system
keeps encryption behind a single interface, so if the school later needs SM4 for other
reasons it can be substituted without changing business logic.

---

## 2. Security architecture overview

The design follows four principles:

1. **Data minimization** — personal information the business does not need is not collected;
   identity is masked by the school before it ever enters the system.
2. **Defense in depth** — transport, storage, access control, and audit operate
   independently, so a failure in one layer does not collapse the whole.
3. **Deny by default** — every endpoint requires authentication and data-scope checks; all
   external input is untrusted by default.
4. **Traceability** — every data change and every evidence view leaves a record, and those
   records cannot be altered through business functions.

Target deployment:

```
        HTTPS (TLS 1.2/1.3)
Users ──────────────────────▶ Application server (inside VPC)
                                      │
                       ┌──────────────┼──────────────┐
                       ▼              ▼              ▼
                   RDS database   OSS object      KMS key
                   (TDE)          storage         management
                                  (SSE-KMS)
                       └──── VPC-internal only, no public exposure ────┘
```

---

## 3. Infrastructure the school must provide

Some security capabilities in this proposal are provided by the cloud platform and require
the following services on the school's side. The system is already designed against these
interfaces and can connect once they are enabled.

| Component | Purpose | Security requirement addressed | Necessity |
| :--- | :--- | :--- | :--- |
| **RDS (MySQL)** | Hosts business data with **TDE** enabled | Confidentiality of stored data | **Required in production** |
| **OSS object storage** | Stores evidence photos and scans, private access + **SSE-KMS** | Confidentiality of stored data | **Required in production** |
| **KMS** | Holds the master keys for TDE and OSS; the application obtains temporary credentials via an instance role | Key management, no static credentials | **Required in production** |
| **VPC** | RDS and OSS reachable only over the internal network; no public database port | Network access control | **Required in production** |
| **Domain and TLS certificate** | Site-wide HTTPS | Transport confidentiality | **Required** |
| **Log storage capacity** | Enough capacity for six-month retention | Cybersecurity Law Art. 21 | **Required** |
| SMS service / WeCom / DingTalk | Multi-factor authentication, if selected (§8) | Stronger authentication | Optional, depends on §8 |

**On development and test environments:** local disk storage may substitute for OSS during
development and testing to keep setup costs down. To prevent that substitute reaching
production, the system includes a **configuration gate**: if the runtime environment is
marked production while storage is still local disk, the application **fails at startup with
an explicit message** rather than running silently in a degraded state.

**On database compatibility:** the system uses standard MySQL 8.0, with which Aliyun RDS
MySQL is fully compatible. TDE encrypts and decrypts transparently below the SQL layer, so
**no application code changes are required**; migration only adjusts connection settings.

---

## 4. Identity and data minimization

### 4.1 Student identity is masked before it enters the system

The system **does not receive students' complete real identities**. The school
de-identifies data before import according to the convention below, so the cloud never holds
directly identifying information. Masked values remain ordinary strings, so search, sort,
and filter functions are entirely unaffected.

| Field | Agreed format | Example |
| :--- | :--- | :--- |
| Name `displayName` | Chinese name with the middle character hidden; English name reduced to given name plus surname initial | `Alex L.(李*红)` |
| Student number `studentNumber` | A stable identifier generated by the school in Crockford base32, carrying no real student-number information | `HKTZ4HKV` |

The Chinese and English parts use **consistent masking strength**: the English name keeps
the given name and the surname initial only (`Alex L.`), not the full surname, so there is
no situation where the Chinese name is masked while the English name still identifies the
student directly. Within the class group a teacher is responsible for — typically under 100
students — the masked name is sufficient to tell students apart; across year groups, an
administrator can distinguish them using grade, class, and the student-number hash together.

### 4.2 Mandatory requirements for the student-number hash

**The hash must be permanently stable for a given student and unique school-wide.**

- The system enforces uniqueness on the student number. A collision causes the import to
  fail with an explicit error rather than silently overwriting data. The format is
  **Crockford base32**, whose alphabet deliberately omits I, L, O and U so that nothing can
  be misread as 1 or 0 — this is a value teachers read off a screen, print on a card and
  type back in. Eight characters give roughly 1.1e12 possible values: about a 1 in 226,000
  collision probability at 5,000 students, against about 1 in 344 for the eight hexadecimal
  digits this replaced.
- **Hashes must not be regenerated per academic year or per batch.** Service record numbers
  take the form `TB-{year}-{grade}-{studentNumber}-{seq}`, so the student number forms part
  of the record number itself. If a student's hash changes, previously issued record
  numbers, historical record linkage, and incremental import matching all break together.
- Asterisk-masked student numbers (such as `******24`) are not recommended: the value space
  of the last few digits is too small, collisions are certain at a few hundred students, and
  asterisks inside a record number damage both its uniqueness and its readability.

### 4.3 Information the system does not collect

Under data minimization, the following **do not enter the system**:

- **Guardian names and contact details** — the system has no use case for them.
- **Student roster email addresses** — not collected.
- **ID numbers, home addresses, biometric data** — not part of the system's data model.

Login accounts retain an optional email field used only for account recovery and future mail
notifications (for schools that need them). It is not mandatory and is kept separate from
student roster data.

---

## 5. Storage encryption

### 5.1 Database: transparent data encryption
The business database runs with **TDE** enabled, so data is encrypted automatically when
written to disk and decrypted on read, with the master key held in KMS. Because this
operates below the SQL layer, it covers every field including names and student numbers,
**without affecting any search or sort functionality**.

### 5.2 Backup encryption
Database backup files are **encrypted independently**, with keys kept separately from the
backups. This is called out separately because TDE protects online data files and **exported
backup files fall outside its protection** — backups must be encrypted by the backup process
itself, or they become the weakest link in the chain.

### 5.3 Object storage encryption
Evidence photos and scans are stored in a **private** OSS bucket with **SSE-KMS**
server-side encryption enabled, so files are encrypted on write. The bucket forbids
anonymous public reads; all access requires temporary credentials issued after the system
authorizes the request.

### 5.4 Key management
No database password or encryption key is hardcoded in application code or configuration
files. In production the application obtains short-lived temporary credentials dynamically
through a cloud instance role (RAM Role), performs cryptographic operations in memory, and
the credentials expire automatically.

### 5.5 Note on field-level encryption
This proposal **does not apply separate application-layer encryption to individual fields**
such as name or student number, for the following reasons, offered for the school's
technical review:

- Identity is already **masked at source** (§4.1), so the system does not hold a complete
  identity to begin with, and encrypting it again adds little.
- TDE already covers the same threats (stolen disks, snapshots, or data files) with no loss
  of functionality.
- Field-level encryption makes ciphertext unusable for database search and sorting, which
  would directly break everyday functions such as looking up a student by name or sorting by
  name. Trading core usability for protection that overlaps with TDE is not a sound
  trade-off.
- Backup encryption (§5.2) already covers the one scenario TDE does not — leaked export
  files — and does so for every field at once.

---

## 6. Evidence file security

Evidence is the most sensitive data in the system: photographs may show minors' faces and
locations. The following measures apply.

### 6.1 Access control
- **Private storage:** the bucket is private, with no permanently accessible public address.
- **Short-lived signed links:** when the frontend displays evidence, the backend authorizes
  the request and issues a temporary link valid for **no more than 300 seconds**, which
  expires automatically, cannot be forwarded for lasting use, and cannot be hot-linked
  externally.
- **Scope checks:** teachers can retrieve evidence only for students within their data
  scope; out-of-scope requests return "not found" and do not reveal whether the resource
  exists.

### 6.2 Metadata stripping
Photographs taken directly on a student's phone commonly carry **GPS coordinates and device
identifiers** — a classic leakage path for minors' location data. On receipt, the system
**decodes and re-encodes** every image, discarding all metadata. If decoding fails the file
is rejected rather than stored unprocessed.

### 6.3 Upload content validation
- **True type checking:** the real file type is determined from magic bytes rather than the
  file extension or the client-declared type, preventing an executable from being uploaded
  under a renamed extension.
- **Size limits:** files over the limit are rejected.
- **Integrity digest:** a SHA-256 digest is computed and stored, forming a tamper-evident
  chain for the evidence. Verification is always performed against the file the system
  stores.

### 6.4 Watermarking and traceability
When evidence is viewed in the administrative portal, the system overlays a watermark **into
the file content itself**, carrying the **viewer's account, the time of viewing, and a
statement that the material is for volunteer-hours verification only**, for deterrence and
after-the-fact traceability.

- The watermark is **generated per view, for the specific viewer**, so different viewers
  receive different watermarks and the source of any leak can be identified.
- Watermarking is performed server-side and **does not use a frontend overlay** — a frontend
  overlay can be bypassed by opening the image address directly and would defeat the
  traceability purpose.
- **Images** (JPEG/PNG) are watermarked by server-side image processing.
- **PDFs** (scans) are watermarked using **Apache PDFBox**, overlaid into the page content
  stream and likewise generated per viewer. The system **continues to fully support PDF
  evidence upload and preview**.

PDFs require two additional measures, because the format can carry active content:

- **Sanitization:** embedded JavaScript, automatic actions, and embedded files are removed on
  upload, retaining only page content.
- **Parsing protection:** page count and file size are limited, and encrypted
  (password-protected) or structurally corrupt PDFs are rejected, so a maliciously crafted
  file cannot consume resources during parsing.

> If the school requires stronger **resistance to watermark removal** (a PDFBox overlay is
> page content, and someone with professional tools could still remove it), PDFs can instead
> be rasterized to images at view time and watermarked then. The cost is losing text
> selection and search. This proposal defaults to the former — preserving usability, with the
> watermark serving **traceability and deterrence** rather than tamper-proofing — and can be
> switched if the school requires it.

---

## 7. Data import validation rules

Bulk import is the main entry point for external data. **All validation is performed
server-side**; frontend validation exists only to improve the filling-in experience and is
not a security boundary.

### 7.1 Processing principles

1. **Reject rather than auto-correct.** Non-compliant rows are returned with the reason; the
   system never silently "fixes" and stores them, because quietly rewriting student data
   would break consistency with the school's source of record.
2. **Two phases.** A read-only validation phase produces a report; only after confirmation
   does the commit phase run, and it **re-runs every check in full** rather than trusting the
   earlier result.
3. **Commit only if the entire file is valid**, so a half-loaded dataset never creates an
   intractable intermediate state.
4. **Per-row error reports** give the row number, field, error type, and explanation so the
   school can locate and correct them.

### 7.2 Data validity checks

| Check type | Rule |
| :--- | :--- |
| Required | Student number and name must not be empty |
| Unique within file | A student number must not repeat within one file |
| Globally unique | A student number must not duplicate an existing student |
| Valid reference | Grade, class, and academic year must already exist in the system |
| Consistent reference | The class must belong to the stated grade |
| Format | Graduation year is an integer no earlier than 2000; enrolment date is `yyyy-MM-dd` |
| Length | Name up to 100 characters; student number up to 32 |
| Student number format | Crockford base32 only (digits and letters excluding I, L, O, U), matching the §4.2 format |

### 7.3 Unsafe content rejection

The following are rejected even when technically well-formed, to prevent attacks entering
through the data channel:

| Risk | Rule |
| :--- | :--- |
| **Spreadsheet formula injection** | Text fields beginning with `=`, `+`, `-`, `@`, tab, or carriage return are rejected. Such content is executed as a formula when an exported file is opened in spreadsheet software, and can be used to exfiltrate data or lure clicks. The system rejects on import and escapes on export, for protection in both directions |
| **Control characters** | NUL bytes and other C0/C1 control characters are rejected |
| **Display spoofing** | Bidirectional override characters (such as U+202E) are rejected, since they can make the displayed text differ from what is stored |
| **Oversized data** | Row count and file size are capped per file and rejected beyond the limit, preventing resource exhaustion |
| **Column misalignment** | Quotes and commas are parsed per RFC 4180, so a name containing a comma does not shift columns |

### 7.4 Authorization and record-keeping
- Import and export endpoints are constrained by both role permissions and data scope; a
  teacher cannot export students outside their responsibility.
- Every import and export writes an audit record: operator, time, source IP, file digest,
  and number of affected rows.
- Temporary files created during processing are deleted deterministically when the job ends.

---

## 8. Authentication and multi-factor authentication (MFA)

### 8.1 Authentication measures in place
- Passwords are stored **salted and hashed** (bcrypt); no recoverable plaintext password
  exists in the system.
- A **strong password policy** and login-failure lockout (the account locks for a period
  after consecutive failures) defend against brute force.
- Sessions use cookies with `Secure`, `HttpOnly`, and `SameSite` attributes, with CSRF
  protection.
- There is **no public registration**; accounts are created by administrators only.

### 8.2 Current status of MFA

The system **has MFA capability reserved but not yet enabled**, for two reasons:

1. The system is currently in its **development and testing stage**, and enabling MFA would
   interrupt automated test procedures. Before go-live, a configuration of "exempt in
   non-production, enforced in production" will resolve this.
2. **More importantly, the MFA approach requires a joint decision with the school** rather
   than being chosen unilaterally by the system provider. The options differ substantially in
   cost, user burden, privacy impact, and dependence on the school's existing IT environment,
   and the choice must fit the school's actual circumstances.

### 8.3 Comparison of MFA approaches

| Approach | Security | Cost | User burden | Privacy impact | Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **① TOTP** (authenticator app) | High. Generated offline, never transmitted, unaffected by carrier or SMS interception | **Zero**, no per-use fee | Must install an app and enrol once; re-enrolment when changing phones | **No new personal information** | None |
| **② SMS one-time code** | Medium. Vulnerable to SIM swap and SMS interception | Per-message charge, an ongoing cost | **Lowest** — the most familiar to users | **Requires collecting mobile numbers**, conflicting with this proposal's data minimization | Requires an SMS service |
| **③ WeCom / DingTalk scan** | High. Backed by an enterprise identity system | Near zero if the school already uses one | Low — teachers usually have it installed | Identity linked to a third-party platform | **The school must already run** WeCom or DingTalk |
| **④ WeChat Mini Program confirmation** | High | Requires mini-program development and certification | Low — no extra app needed | Requires binding a WeChat identity | Could be built together with the Phase 3 witness mini-program |
| **⑤ Email one-time code** | Lower. The mailbox itself is often single-factor | Low | Medium — must check email | Requires a usable mailbox | Requires a mail service |
| **⑥ Passkeys / security keys** (WebAuthn) | **Highest**, phishing-resistant | Hardware keys must be purchased; device biometrics are free | Medium — devices must be registered | No new personal information | Requires a recent browser |

### 8.4 Recommended scope

MFA need not apply uniformly to all users. We suggest tiering by privilege:

| Account type | Recommendation | Rationale |
| :--- | :--- | :--- |
| System and school administrators | **Mandatory** | They can see school-wide data and hold the highest privileges |
| Reviewing teachers | Recommended; the school may decide | They can see student data within their scope |
| Students | **Not required** | Students are mostly minors, and requiring an authenticator app is a disproportionate burden; their accounts reach only their own data |

### 8.5 Recommendation

If the school **already runs WeCom or DingTalk**, we recommend **approach ③**: teachers need
install nothing further, and it is the cheapest to adopt.

If the school does **not** run either, we recommend **approach ① (TOTP)**: zero cost, no new
personal information, no dependence on carrier infrastructure, and it works equally well for
overseas or multi-campus situations.

**We do not recommend SMS codes (approach ②) as a first choice**: it requires the system to
collect and store mobile numbers, directly conflicting with this proposal's decision not to
collect students' or guardians' contact details, carries an ongoing per-message cost, and is
less secure than free TOTP.

The final choice rests with the school on the basis of the comparison above; the system will
be implemented to match.

---

## 9. Transport and interface security

| Item | Measure |
| :--- | :--- |
| Transport encryption | Site-wide **HTTPS**, **TLS 1.2 / 1.3 only**, with known-weak protocol versions and cipher suites disabled |
| Transport policy | HSTS enabled, so browsers always use HTTPS thereafter |
| Content security policy | CSP configured to restrict script sources and limit the impact of cross-site scripting |
| Session security | Cookies set `Secure`, `HttpOnly`, `SameSite`; no sensitive plaintext in client-side storage |
| Cross-site request forgery | CSRF token validation enabled |
| Access control | Every endpoint enforces role-based permission checks and data-scope checks, preventing both horizontal and vertical privilege escalation |
| Input handling | All external input is validated and escaped, preventing injection and cross-site scripting |
| Database connections | The database is reachable only within the VPC, with no public port; cross-host connections use TLS |

---

## 10. Logging and audit

### 10.1 Retention
Application audit logs, server and database operation logs, and access logs are retained
centrally for **no less than six months**, meeting the requirement of Article 21 of the
Cybersecurity Law.

### 10.2 Audit log properties
- **Append-only and immutable:** once written, audit records cannot be modified or deleted
  through business functions, which guarantees traceability structurally.
- **Covers every data change:** creation, modification, review decisions, and hour crediting
  all leave records.
- **Recorded fields:** operator, time, source IP, operation type, object identifier, and
  before/after state.
- **Review decisions are never overwritten:** changing a conclusion appends a new decision
  record rather than editing the original, preserving the full decision history.

### 10.3 Traceability of hour accounts
A student's hour balance is **derived entirely from transactions**; the system provides no
function to edit a balance directly. Corrections and reversals are made by appending an
offsetting transaction, so original records are retained permanently and the account is fully
auditable.

---

## 11. Mapping to MLPS Level 1 requirements

| Control area | Measures in this proposal |
| :--- | :--- |
| Identification and authentication | Strong password policy, salted hashing, login lockout, no public registration; MFA per §8 |
| Access control | RBAC plus data-scope checks, least privilege, no resource-existence disclosure on out-of-scope requests |
| Security audit | Append-only, tamper-resistant audit log covering every data change, retained ≥6 months |
| Intrusion prevention | Input validation, true file-type checking, upload sanitization, rate limiting, minimal open ports |
| Malicious code prevention | Upload type allow-list and content sanitization; removal of PDF active content |
| Data confidentiality | TLS 1.2/1.3 in transit; TDE and OSS SSE-KMS at rest; independently encrypted backups |
| Data integrity | SHA-256 evidence chain; balances derived from immutable transactions |
| Backup and recovery | Regular database backups stored encrypted, with keys held separately |
| Residual information protection | Deterministic temporary-file deletion; mandatory image metadata stripping |
| Personal information protection | Masking at source, no collection of unnecessary personal information, short-lived signed links, watermark traceability |

---

## 12. Implementation and acceptance self-assessment

This table is the self-assessment record for Level 1 self-protection: each item is confirmed
and ticked during implementation and acceptance, and forms part of the archived materials
(§13). Items marked **[School]** require confirmation from the school; the rest are completed
by the implementer.

### 12.1 Infrastructure prerequisites

| ID | Check | Status |
| :--- | :--- | :--- |
| P-01 | **[School]** RDS provisioned with TDE enabled | [ ] |
| P-02 | **[School]** OSS bucket provisioned and set to private access | [ ] |
| P-03 | **[School]** OSS SSE-KMS server-side encryption enabled | [ ] |
| P-04 | **[School]** KMS provisioned; master keys held in KMS | [ ] |
| P-05 | RDS and OSS reachable only via VPC; no public database port | [ ] |
| P-06 | **[School]** Domain and TLS certificate ready | [ ] |
| P-07 | **[School]** Log storage capacity sufficient for six months | [ ] |

### 12.2 Data and storage

| ID | Check | Status |
| :--- | :--- | :--- |
| D-01 | Database TDE active | [ ] |
| D-02 | Master key backed up separately from data, and recovery verified | [ ] |
| D-03 | Database backups encrypted, with keys held separately | [ ] |
| D-04 | No hardcoded passwords or keys; production credentials obtained via instance role | [ ] |
| D-05 | Guardian name, guardian contact, and roster email are not collected | [ ] |
| D-06 | **[School]** Name masking follows the §4.1 convention (consistent across Chinese and English) | [ ] |
| D-07 | **[School]** Student-number hash stability confirmed: unchanged across years and batches | [ ] |
| D-08 | Application fails at startup if local disk storage is used in production | [ ] |

### 12.3 Evidence files

| ID | Check | Status |
| :--- | :--- | :--- |
| F-01 | Bucket forbids anonymous public reads; no fixed accessible address | [ ] |
| F-02 | Evidence retrieved via signed links valid ≤300 seconds | [ ] |
| F-03 | Photo metadata (GPS/device) stripped; decode failure treated as rejection | [ ] |
| F-04 | Magic-byte true type checking plus size limits | [ ] |
| F-05 | SHA-256 digest stored; verification against the stored file | [ ] |
| F-06 | Image watermark generated per viewer (account, time, purpose statement) | [ ] |
| F-07 | PDF watermark generated per viewer via Apache PDFBox | [ ] |
| F-08 | Embedded JavaScript, automatic actions, and embedded files removed from PDFs | [ ] |
| F-09 | PDF page-count and size limits; encrypted or corrupt PDFs rejected | [ ] |
| F-10 | Out-of-scope evidence requests return "not found" without disclosing existence | [ ] |

### 12.4 Data import

| ID | Check | Status |
| :--- | :--- | :--- |
| I-01 | Required, in-file unique, globally unique, valid and consistent reference, format, length checks | [ ] |
| I-02 | Two-phase execution with full re-validation at commit | [ ] |
| I-03 | Commit only when the whole file is valid; no partial loads | [ ] |
| I-04 | Formula injection: rejected on import, escaped on export | [ ] |
| I-05 | Control characters and bidirectional override characters rejected | [ ] |
| I-06 | RFC 4180 parsing of quotes and commas; names with commas do not misalign | [ ] |
| I-07 | Row-count and file-size caps per file | [ ] |
| I-08 | Import/export constrained by permissions and data scope | [ ] |
| I-09 | Import/export audited (operator, time, IP, file digest, affected rows) | [ ] |
| I-10 | Temporary files deleted deterministically | [ ] |

### 12.5 Authentication and MFA

| ID | Check | Status |
| :--- | :--- | :--- |
| A-01 | Passwords salted and hashed; no recoverable plaintext | [ ] |
| A-02 | Strong password policy and login lockout enabled | [ ] |
| A-03 | No public registration; accounts created by administrators only | [ ] |
| A-04 | **[School]** MFA approach selected and signed off per the §8.3 comparison | [ ] |
| A-05 | MFA enforced for administrator accounts | [ ] |
| A-06 | Non-production exemption configured so automated testing is unaffected | [ ] |

### 12.6 Transport and interfaces

| ID | Check | Status |
| :--- | :--- | :--- |
| T-01 | Site-wide HTTPS enforced | [ ] |
| T-02 | TLS 1.2/1.3 only; weak versions and cipher suites disabled | [ ] |
| T-03 | HSTS enabled | [ ] |
| T-04 | CSP configured | [ ] |
| T-05 | Cookies `Secure`/`HttpOnly`/`SameSite`; no sensitive plaintext in local storage | [ ] |
| T-06 | CSRF token validation enabled | [ ] |
| T-07 | Every endpoint enforces RBAC and data-scope checks | [ ] |
| T-08 | Cross-host database connections use TLS | [ ] |

### 12.7 Logging and audit

| ID | Check | Status |
| :--- | :--- | :--- |
| O-01 | **Statutory**: application, server, database, and access logs retained ≥6 months | [ ] |
| O-02 | Audit log append-only; not modifiable or deletable through business functions | [ ] |
| O-03 | All data changes recorded, verified by real queries | [ ] |
| O-04 | Review decisions appended, never overwriting the original | [ ] |
| O-05 | Hour balances derived solely from immutable transactions; no direct balance edit | [ ] |

---

## 13. Archive list

1. System Classification Report (MLPS Level 1)
2. System Security and Privacy Protection Design Proposal (this document)
3. Student Data Import Format and Masking Convention — the import agreement handed to the
   school, which must include the **hash stability** requirement from §4.2
4. MFA Approach Selection Confirmation — the §8.3 comparison and the school's final choice
5. Network Security Log Retention and Operations Management Standard (six months)
