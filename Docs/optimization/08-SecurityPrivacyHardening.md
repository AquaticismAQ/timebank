# Security & Privacy Hardening (Phase 1) — Implementation Plan

## 0. Scope and how to read this

This is a **generic, vendor-neutral** security and privacy pass over the system as it
exists today. Every item here is good practice for any deployment in any jurisdiction —
none of it depends on a Chinese regulatory requirement, on Alibaba Cloud, or on any
infrastructure the project does not already have.

`Design/english/08-mlps-level1-security-design-guide.md` (and its Chinese source) frames the
same measures for MLPS Level 1 compliance and is the document shown to a school. **This
document is the engineering plan**, and where the two disagree on sequencing, this one wins.

**Runs entirely on the current test server.** No object storage, no managed database, no
cloud KMS. Local disk stays the evidence store for now; this plan only ensures it cannot
silently become the *production* evidence store.

**Explicitly out of scope, with reasons:**

| Deferred | Why |
| :--- | :--- |
| Multi-factor authentication | Would break the automated test procedure, and the approach must be chosen jointly with the school — six options compared in the design doc §8.3 |
| OSS / SSE-KMS migration | No object storage account exists; tracked separately as `Docs/implementation/14-Backend-B3F-CloudFileStorage.md` |
| Managed database + KMS-held keys | No RDS instance exists |
| Rule-based import validation | Phase 2 — `09-RuleBasedImportValidation.md` |
| SM4 / national cryptographic algorithms | Not required at Level 1; algorithm stays behind an interface so it can be substituted |

---

## 1. What already exists (verified findings)

Checked against the running system, not assumed.

### 1.1 Already correct — do not disturb

- **TLS** via Let's Encrypt with auto-renewal; HTTP 301-redirects to HTTPS.
- **Session cookies** carry `Secure`, `HttpOnly`, `SameSite=Lax`
  (`server.servlet.session.cookie.*`).
- **CSRF** double-submit cookie implemented.
- **Authorization**: `@PreAuthorize` on every endpoint plus the data-scope resolver;
  out-of-scope reads return `404`, not `403`, so resource existence is not disclosed.
- **Login lockout**: `timebank.auth.lockout.max-failed-attempts` (default 5) with a lockout
  duration; passwords are bcrypt-hashed.
- **No public registration** — accounts are administrator-created.
- **Evidence uploads** already do magic-byte type checking, size limits, SHA-256 digests,
  and EXIF/GPS stripping via `FileServiceImpl.stripImageMetadata()` (decode failure is
  treated as rejection, not pass-through).
- **Evidence access** uses HMAC-signed, short-lived download tokens (300s) — not public URLs.
- **Audit log** is append-only with no purge path; balances are derived solely from
  immutable transactions.

### 1.2 Weaknesses this plan fixes

| # | Finding | Evidence |
| :--- | :--- | :--- |
| W-1 | **TLS 1.0 and 1.1 are enabled** | `/etc/nginx/nginx.conf`: `ssl_protocols TLSv1 TLSv1.1 TLSv1.2 TLSv1.3` (stock Ubuntu default) |
| W-2 | **No security response headers at all** — no HSTS, CSP, `X-Content-Type-Options`, or `Referrer-Policy` | no `add_header` directives in the site config |
| W-3 | **Access logs retained 14 days** | `/etc/logrotate.d/nginx`: `daily` + `rotate 14` |
| W-4 | **Journal evicts by size, not time** | `journald.conf` has every retention key commented out; ~1.9G in use, so it is already rotating |
| W-5 | **Unused personal data is collected and stored**: `student.guardian_name`, `student.guardian_contact`, `student.email` | zero references under `frontend/src/app/components/`; present only as pass-through fields in `client.ts` |
| W-6 | **CSV formula injection is unguarded on export** | `ExportJobRunner.csvLine()` quotes commas and quotes per RFC 4180 but does not neutralize a leading `=`, `+`, `-`, `@`; RFC quoting does not prevent spreadsheet evaluation (CWE-1236) |
| W-7 | **Local disk storage has no production guard** | `timebank.file.storage-path` is used unconditionally; nothing prevents a production deployment running on it |
| W-8 | **Evidence carries no viewer attribution** | a leaked screenshot or downloaded file is untraceable to the account that viewed it |
| W-9 | **PDF evidence is accepted without sanitization** | `ALLOWED_MIME_TYPES` includes `application/pdf`; PDFs may carry JavaScript, auto-actions, and embedded files, none of which are stripped |
| W-10 | **Database files are unencrypted at rest** | `default_table_encryption=OFF`; `component_keyring_file.so` is present and available |

---

## 2. Decisions needed

### 2.1 Watermark content and rendering

The watermark must be **in the delivered bytes**, generated **per viewer per request** — a
frontend CSS overlay is not acceptable, since opening the signed URL directly returns clean
content.

Open choices:
- **Content.** Proposed: viewer account, UTC timestamp, and a fixed purpose string
  ("Volunteer hours verification only"). Should the purpose string be localized to the
  viewer's language, or fixed English for forensic consistency?
- **Appearance.** Diagonal tiled text at low opacity is the usual choice — legible enough to
  deter, light enough to keep the evidence readable.
- **Scope — decided: staff views only.** Admins and teachers viewing evidence get the
  watermark, because that is the leak path the feature exists for. A student viewing
  their own photo sees it clean; marking up their own material buys no traceability.

### 2.2 PDF watermarking — new dependency and removal-resistance

Watermarking PDFs requires **Apache PDFBox** (Apache-2.0, pure Java). This is the first new
runtime dependency in the file module, which until now has used only JDK codecs. Confirm
that is acceptable.

Two approaches, and the choice is a real trade-off:

| | Content-stream overlay (PDFBox) | Rasterize then watermark |
| :--- | :--- | :--- |
| Text selection / search | preserved | **lost** |
| Removal resistance | a determined party with tooling can strip the overlay | effectively baked in |
| Cost | low | high (render every page per view) |

**Decided 2026-09-16: PDFBox accepted, content-stream overlay.** The watermark's purpose is traceability and deterrence, not
tamper-proofing; rasterizing destroys a genuinely useful property of scans for a threat this
system is not trying to defeat.

### 2.3 Log retention target

**Decided 2026-09-16: 180 days**, configurable, applied to nginx access logs
and the journal. Disk headroom must be checked first: the box has 14G free and the journal
alone is already at 1.9G under size-based eviction.

### 2.4 Does dropping `student.email` need a data escape hatch? — **RESOLVED: no**

Settled by checking rather than asking. On the **active** server all 50 students have
`NULL` in all three columns — the rewritten seeder stopped populating them, so there is
nothing to lose. On the **original** server 11 rows carry an email and 2 carry guardian
details, but those are seeded placeholders (`@example-township-school.edu`,
`Zhang Daqiang`), not real school data.

Drop outright, no export. The students **export** already omits all three
(`id,studentNumber,displayName,gradeId,classId,status,graduationYear,joinedAt`),
so nothing downstream changes either.

### 2.5 CSP strictness

The frontend is a Vite SPA using Tailwind v4, which injects styles at runtime. A strict
`style-src 'self'` may break rendering and need `'unsafe-inline'` for styles. Script policy
can and should stay strict (`script-src 'self'`). This needs to be verified in a browser,
not assumed — see §3.2.

---

## 3. Implementation plan

Ordered so that the cheapest risk reductions land first and nothing depends on a later step.

### 3.1 Step 1 — Data minimization: drop unused personal data (W-5)

Removes two adults' PII fields and one unused student field. Reduces breach severity and
shrinks the import surface before Phase 2 rebuilds it.

- **Migration** `V26__drop_unused_student_pii.sql`: drop `student.guardian_name`,
  `student.guardian_contact`, `student.email`.
- **Backend**: remove the three fields from the `Student` entity, `CreateStudentRequest`,
  `UpdateStudentRequest`, `StudentDetailDto`, and `StudentImportRowValidator`'s column
  handling (CSV becomes 7 columns, the three trailing optional ones disappear).
- **Import template**: `GET /imports/templates/students` must emit the new header.
- **Frontend**: drop `guardianName` / `guardianContact` / `email` from the `Student` type,
  the `students.update()` payload, and the detail mapping in `client.ts`. No component
  changes — nothing renders them.
- **Docs**: `Docs/integration/APIContract.md` **and its `_CN.md` twin** (bilingual pair — both
  must change together), plus `Docs/frontend/Design.md` / `_CN.md` if they list the fields.
- **Seed data**: `integration-test/mock-data.ts` (`SeedStudent` type and all rows),
  `seed.ts`'s insert, and `MOCK_DATA_GUIDE.md`'s "Adding a new student" section.
- **Keep `user.email`** — a different column on a different table; it is the login
  identifier advertised on the login page and the basis for future mail notification.

**Verification:** confirm the columns are gone from the live schema, that a student import
succeeds with the 7-column file, and that Student Management still loads and edits students.

### 3.2 Step 2 — Transport and response headers (W-1, W-2)

nginx-only; no application change.

- `ssl_protocols TLSv1.2 TLSv1.3;` and a modern cipher suite list.
- `Strict-Transport-Security` (start with a short `max-age`, raise once confident).
- `Content-Security-Policy` per §2.5 — script policy strict; style policy determined by
  testing.
- `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`,
  and frame protection (`frame-ancestors 'none'`).
- **`Cache-Control: no-cache` on `index.html`**, leaving the content-hashed
  assets immutable. nginx currently sends only `ETag`/`Last-Modified` for the
  document, so browsers apply heuristic caching and can keep serving a stale
  `index.html` — which pins them to the previous hashed bundle after a deploy.
  This is not hypothetical: it made a verified-correct fix look broken during
  issue 084, where the server was serving the new bundle while the browser ran
  the old one.

**Verification:** the site must be exercised in a real browser afterwards — a CSP that breaks
the SPA fails silently from the server's point of view. Check the console for CSP violations
on the main flows in both portals, and confirm a TLS 1.0/1.1 handshake is refused.

### 3.3 Step 3 — Log retention (W-3, W-4)

- nginx logrotate: raise `rotate` to the §2.3 target, with compression.
- `journald.conf`: set `MaxRetentionSec` and a `SystemMaxUse` ceiling that fits the disk.
- Check free space before and after; the journal is already size-constrained.

### 3.4 Step 4 — Production storage gate (W-7)

Fail fast rather than degrade quietly, matching the existing precedent
(`SchoolOrganizationHealthCheck`, `TIMEBANK_ALLOWED_ORIGINS` with no fallback,
`TIMEBANK_FILE_TOKEN_SECRET:?`).

- On startup, if the resolved storage adapter is local disk **and** `timebank.environment`
  indicates production, abort startup with an explicit message naming the fix.
- `timebank.environment` already exists (`${TIMEBANK_ENVIRONMENT:unspecified}`); this only
  adds the check.

**Verification:** boot once with the production marker set and confirm the process refuses to
start; boot with `test-server` and confirm it starts normally.

### 3.5 Step 5 — Export-side CSV injection escaping (W-6)

Closes the actual exploitation path — a crafted name reaching a spreadsheet — without waiting
for Phase 2.

- In `ExportJobRunner.csvLine()`, prefix any field beginning with `=`, `+`, `-`, `@`, tab, or
  CR with a single quote (or equivalent neutralization), **in addition to** existing RFC 4180
  quoting.
- Applies to every export type, not just students.

The matching **import-side rejection** is a validation rule and belongs to Phase 2
(`09-...` §3). Splitting it this way is deliberate: the export fix alone closes the
vulnerability, and the import rule is defense in depth.

**Verification:** create a student whose name begins with `=`, export, and confirm the cell is
inert when opened in a spreadsheet.

### 3.6 Step 6 — Evidence watermarking (W-8)

Per-viewer, applied **at delivery**, never at upload.

- **Images (JPEG/PNG):** extend the existing `BufferedImage` pipeline — the same JDK-only
  approach `stripImageMetadata()` already uses — to draw the §2.1 watermark via `Graphics2D`
  on the way out.
- Generated per request, so responses carrying a watermark must not be cached across users;
  set cache headers accordingly.
- **The stored file is never modified.** The SHA-256 digest continues to describe the stored
  original, and integrity verification is always performed against that, never against what a
  browser received. Document this explicitly so a later reader does not "fix" the apparent
  mismatch.

### 3.7 Step 7 — PDF sanitization and watermarking (W-9, and the rest of W-8)

PDF upload and preview are **retained**; they are a real use case for scanned evidence.

- **Sanitize on upload**: strip embedded JavaScript, automatic/launch actions, and embedded
  files; keep page content.
- **Limits**: cap page count and file size; reject encrypted (password-protected) and
  structurally corrupt PDFs rather than attempting recovery.
- **Watermark on delivery**: PDFBox content-stream overlay per §2.2, same per-viewer content
  as images.
- Treat PDF parsing as untrusted-input handling: bound memory (scratch-file mode) so a
  malformed file cannot exhaust a 1-vCPU box.

**Verification:** a PDF carrying JavaScript must lose it on upload; an encrypted PDF must be
rejected; a viewed PDF must show the viewing account and time, and a second account viewing
the same file must get a visibly different watermark.

### 3.8 Step 8 — Encryption at rest (W-10) *(operations, no code)*

- Enable the keyring component and turn on InnoDB tablespace encryption for the schema.
- **Do not express this as a Flyway migration.** A managed database enables encryption at the
  instance level, so a versioned `ALTER TABLE ... ENCRYPTION='Y'` would make migration
  histories diverge between environments. Record it as server configuration plus a one-time
  DBA step in `Docs/operations/Runbooks.md`.
- **Back up the keyring file separately from the data** and verify recovery — losing it makes
  every encrypted tablespace unrecoverable.
- **Encrypt database dumps.** Tablespace encryption does not cover `mysqldump` output, which
  is plaintext; without this the backup is the weakest link. Note that backup automation
  itself is still unimplemented (`Docs/implementation/00-Status.md` B7), so this requirement
  should land with it rather than be retrofitted.

### 3.9 Step 9 — Client-side storage audit

Confirm `localStorage` holds no sensitive plaintext. The auth context persists a user object;
verify what it contains and trim it to what the UI actually needs.

---

## 4. Execution: parallel tracks

### 4.1 Why this plan is not merged with Phase 2

The two plans stay separate deliberately:

- **Different risk and cadence.** Several steps here are configuration-only and
  can land within an hour (TLS versions, response headers, log retention).
  Phase 2 is a multi-day refactor of a subsystem. Merging would park a live
  TLS 1.0/1.1 exposure behind a rule-engine rewrite.
- **Different decisions.** This plan has five open decisions about watermarks,
  PDF handling, retention and CSP; Phase 2 has five unrelated ones about rule
  storage and expressiveness. A merged decision index would be ten unrelated
  questions with no common reviewer.
- **Different parallelism.** This plan decomposes into independent tracks
  (below). Phase 2 is inherently sequential — parser, then engine, then
  catalogue, then migration — and gains almost nothing from extra agents.

**The seam between them, stated once so neither plan assumes the other:**

- Phase 2 is blocked on **§3.1 only**, not on this plan as a whole. Once the
  column drop lands, Phase 2 can start alongside the remaining steps here.
- §3.1 edits `StudentImportRowValidator`'s positional parsing; Phase 2 §3.6
  deletes that code entirely. **Keep the §3.1 edit minimal** — drop three
  columns, change nothing else. Improving that parser is wasted work.
- CWE-1236 is **split across both plans on purpose**: export-side escaping is
  §3.5 here because it alone closes the exploitation path, and import-side
  rejection is Phase 2 §3.4 as defence in depth. Neither is "the fix" alone.

### 4.2 Track decomposition

Ordered into two waves because one step is a shared-file bottleneck.

**Wave 1 — one agent, alone:**

| Track | Steps | Files owned |
| :--- | :--- | :--- |
| **C — Data minimization** | §3.1 | migration, `Student` entity, student DTOs, `StudentImportRowValidator`, import template, `client.ts`, `APIContract.md` + `_CN` twin, `mock-data.ts`, `seed.ts`, `MOCK_DATA_GUIDE.md` |

C runs alone because it touches the most widely-shared files in the repo
(the API client, the contract pair, the seed fixtures) and because everything
in Phase 2 waits on it.

**Wave 2 — parallel, after C lands:**

| Track | Steps | Files owned | Notes |
| :--- | :--- | :--- | :--- |
| **A — Server/ops** | §3.2, §3.3, §3.8 | nginx config, logrotate, journald, MySQL keyring, `Docs/operations/Runbooks.md` | No application code at all |
| **B — Evidence & PDF** | §3.6, §3.7 | `cn.timebank.file`, `pom.xml` (PDFBox) | Largest code track; must be one agent — both steps share watermark content and config |
| **D — Isolated backend** | §3.4, §3.5 | `ExportJobRunner`, the storage-gate startup check | Two small, unrelated single-file changes |
| **E — Frontend audit** | §3.9 | auth context / local storage | Smallest; can fold into C if agent count matters |
| **Phase 2** | `09-*.md` | `cn.timebank.dataexchange` | Starts here, runs its own sequence |

No two tracks own the same file. A and B never touch the same layer; D's
`dataexchange` files are read by Phase 2 but Phase 2 does not start editing
them until its own §3.6.

### 4.3 What the coordinator keeps — do not delegate these

These are not style preferences; each one has bitten this project.

- **All deploys.** One test server, one database, one 1 vCPU build box.
  Concurrent `mvn package` thrashes it, and two agents deploying at once means
  neither knows what it verified against. Agents write code and stop.
- **All live browser verification.** Playwright here is a *single shared
  browser session*, not per-agent isolated — parallel agents driving it fight
  over the same page and the same login. The coordinator drives it.
- **All live database writes**, per `Docs/README.md` §2.4.
- **All commits**, so one reviewer sees the whole change set.

### 4.4 Hazards specific to running agents in parallel here

- **Rate limiters will trip.** nginx allows 10 logins/min per IP;
  `RateLimitInterceptor` allows 20 upload-intents/min **per principal**, 5
  exports/min, 5 imports/min. Several agents exercising the API at once will
  hit these and misread the 429s as bugs. Verification is serialised partly
  for this reason.
- **Use worktree isolation** for the code tracks so agents cannot collide in
  one working tree.
- **Seed state is shared.** Any agent that reseeds destroys every other
  agent's fixtures and invalidates every password in `Docs/TestAccounts.md`.
  Reseeding is a coordinator action.
- **`deploy.sh` currently reports success while the backend crash-loops** — it
  sleeps 5s then checks `systemctl is-active`, which returns `activating`
  during restart backoff. Until that is fixed, "deploy succeeded" is not
  evidence the app is running; check the health endpoint.

## 5. Decision index

| ID | Decision | Status |
| :--- | :--- | :--- |
| D-1 | Watermark content, appearance, and whether students see it on their own evidence (§2.1) | **Resolved 2026-09-16 — staff views only** |
| D-2 | Accept the Apache PDFBox dependency; overlay vs rasterize (§2.2) | **Resolved 2026-09-16 — PDFBox accepted, content-stream overlay** |
| D-3 | Log retention target (§2.3) | **Resolved 2026-09-16 — 180 days**, subject to a disk check first |
| D-4 | Whether `student.email` needs an export before being dropped (§2.4) | **Resolved 2026-09-16 — no export needed**, settled by checking the test server |
| D-5 | CSP style policy, pending browser verification (§2.5) | Open |

---

## 6. Implementation status

Not started. Update this section as steps land, following the convention used by the other
plans in this directory.
