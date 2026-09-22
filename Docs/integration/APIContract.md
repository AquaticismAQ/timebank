# TimeBank · RESTful API Contract

> This document defines the top-level API contract required for frontend-driven implementation. It describes resource boundaries, endpoints, statuses, permissions, request bodies, and general protocols. It is **authoritative for API behaviour**; exact response payload shapes are owned by the frontend types module, and no OpenAPI file arbitrates either — see §10.1 for the sources of truth and their precedence. Phase 2 endpoints are prioritized for implementation; Phase 3 and Phase 4 endpoints are reserved as stable extension namespaces.

---

## 1. Contract Objectives

- Support parallel development of the React Web frontend and backend.
- Phase 2 fully covers the Student Portal, Admin/Teacher Portal, and manual review closed loop.
- Maintain resource-oriented, versioned, and testable design; avoid page-specific RPC endpoints.
- All business rules, permissions, and account consistency are enforced by the backend.
- Reserve compatible extension space for Phase 3 POV and Phase 4 redemption, borrowing, and value-added capabilities.

---

## 2. General Protocols

### 2.1 Base Conventions

| Item | Convention |
| --- | --- |
| Base Path | `/api/v1` |
| Protocol | HTTPS |
| Data Format | `application/json; charset=utf-8` |
| Time Format | ISO 8601 with timezone, e.g., `2026-09-01T08:30:00+08:00` |
| Date Format | `YYYY-MM-DD` |
| ID | Server-generated unguessable string; frontend must not parse IDs |
| Duration | Integer **minutes** only. The API never sends hours, and never sends a duration as a decimal |
| Time-coin amounts | Integer **centi-coins** (1 coin = 100 units). `7550` means 75.5 coins. Clients format for display; the API never sends a decimal coin value |
| Field Naming | JSON uses `camelCase` |
| Language | `Accept-Language` on every request; see §2.10 |
| API Versioning | URL major version; compatible fields are append-only, no reusing old semantics |

### 2.2 Authentication

Web recommends using Secure, HttpOnly, SameSite cookies for session management; if access tokens are used, they must be paired with short expiration, secure refresh, and revocation mechanisms.

Authentication endpoints:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/auth/login` | Account-password login |
| `POST` | `/auth/logout` | End current session |
| `POST` | `/auth/refresh` | Refresh session |
| `POST` | `/auth/activate` | Redeem a first-time activation code and set the account's first password |
| `GET` | `/me` | Current user, roles, permissions, data scope, and feature flags |
| `PATCH` | `/me/profile` | Update self-service profile fields |
| `POST` | `/me/change-password` | Change password |

**There is no `/auth/forgot-password` and no `/auth/reset-password`, and no self-service password recovery of any kind** (`Design/*/08-mlps-level1-security-design-guide.md` §8.1/§8.2, issue 089). Both endpoints existed, were never called by any client, and were removed along with their storage. A forgotten password is recovered offline: the user contacts an administrator, who verifies their identity in person and issues an activation slip through `POST /users/{id}/reset-password` (§4.15). Do not add a client-side "forgot password" flow — the login page's *"Forgot password? Contact admin"* copy is the whole of the intended behaviour.

Login response must at least return:

```json
{
  "data": {
    "user": {
      "id": "usr_xxx",
      "displayName": "Example User",
      "roles": ["student"],
      "permissions": ["projects.read", "serviceRecords.create"],
      "studentId": "std_xxx"
    },
    "workspaces": ["student"],
    "preferredLanguage": "zh-CN",
    "featureFlags": {
      "pov": false,
      "redemption": false,
      "borrowing": false,
      "appreciation": false
    }
  }
}
```

`studentId` (added 2026-08-06, `Docs/optimization/01-i3-partner-school-terminology.md`) is `null` for staff accounts (teacher/admin/sysadmin) — it's what lets the frontend exclude "myself" from a co-organizer search without a second round trip.

`roles`/`workspaces` stay array-shaped here (unlike `/users`' `roleCode`, §4.15) but always carry exactly one element — every user holds exactly one role (07-RoleFinetune.md §2.5), so exactly one workspace; there is no multi-workspace account and no workspace picker.

#### `POST /auth/activate` — first-time activation

Students arrive by bulk roster import with an account that has **no password**, so it cannot be logged into at all (`Docs/optimization/10-StudentIdentityAndOnboarding.md` §2.4). The student's printed slip carries a single-use activation code; redeeming it sets their own chosen password and activates the account in one transaction. No password is ever generated server-side.

**Public and unauthenticated by necessity** — together with `/auth/login` it is one of only two such endpoints. Requires a CSRF token like any other mutation.

Request:

```json
{
  "code": "HKTZ4-9QP2M",
  "newPassword": "the-student-chooses-this"
}
```

- `code` — 10 characters: 9 Crockford base32 data characters (`0123456789ABCDEFGHJKMNPQRSTVWXYZ`, no `I`/`L`/`O`/`U`) plus one check character, which is Crockford's standard `value mod 37` symbol over the 37-symbol check alphabet (the data alphabet plus `*~$=U`). The server normalizes before use: case is ignored, separators and whitespace are dropped, and `I`/`L` fold to `1` and `O` to `0`. The printed grouping (`HKTZ4-9QP2M`) is accepted as-is. Clients should verify the check character locally so an obvious mistype is caught without a round trip.
- `newPassword` — 8–128 characters.

**No username is sent, and none is returned.** The code is its own lookup key, so this endpoint is never told — and never confirms — which student a code belongs to. On success the response is `{ "data": null }` with **no session established**: the student then signs in normally with the password they just chose.

Failures are all `422 VALIDATION_FAILED`, distinguished by `fieldErrors[0].code` on the `code` field:

| `fieldErrors[0].code` | Meaning |
| --- | --- |
| `ACTIVATION_CODE_MALFORMED` | Wrong length, wrong alphabet, or the check character does not match — i.e. a mistype |
| `ACTIVATION_CODE_INVALID` | No such code. Deliberately generic: this is what a guess gets |
| `ACTIVATION_CODE_EXPIRED` | The code existed but its window (7 days by default, configurable) has closed — the student needs a reissued slip |
| `ACTIVATION_CODE_ALREADY_USED` | Single-use, and already redeemed |

The distinction between the last three is deliberate and so is its cost: `EXPIRED` and `ALREADY_USED` do confirm that a submitted code exists. That is accepted because a code in either state is already unusable, so it gains an attacker nothing actionable, whereas collapsing them would leave a student with an expired slip retyping a correct code forever. What is withheld in every case is *which account* the code belongs to, and all four share one HTTP status so status codes alone separate nothing.

`429 RATE_LIMITED` is also possible: the endpoint carries a per-IP attempt limit and a **global failure budget** — a few hundred failed guesses per hour across the whole system — which is what closes the distributed case a per-IP limit cannot (§2.5 of the plan). While the budget is exhausted the endpoint refuses everyone, including legitimate students; a successful redemption never consumes it.

### 2.3 CSRF & Origin Protection

- Cookie session mutation requests must validate CSRF tokens.
- API validates `Origin`/`Referer` against allowed origins.
- Login, password reset, file upload, and export endpoints require rate limiting.

### 2.4 Permissions

- API uses permission codes to determine access, e.g., `projects.manage`, `reviews.decide`.
- Teacher data scope may be limited to grades, classes, or specific projects.
- Frontend obtains permissions from `/me` for UI display, but backend re-validates on every request.
- `403` responses must not reveal whether the forbidden resource exists.

### 2.5 Success Response

Single resource:

```json
{
  "data": {
    "id": "rec_xxx"
  },
  "meta": {
    "requestId": "req_xxx"
  }
}
```

List:

```json
{
  "data": [],
  "page": {
    "number": 1,
    "size": 20,
    "totalItems": 0,
    "totalPages": 0
  },
  "meta": {
    "requestId": "req_xxx"
  }
}
```

### 2.6 Error Response

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "The request contains invalid fields.",
    "fieldErrors": [
      {
        "field": "serviceDate",
        "code": "DATE_OUT_OF_RANGE",
        "message": "The service date is outside the allowed range."
      }
    ],
    "requestId": "req_xxx"
  }
}
```

Common error codes:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Malformed request structure |
| 401 | `AUTHENTICATION_REQUIRED` | Not logged in or session expired |
| 403 | `FORBIDDEN` | No permission |
| 404 | `RESOURCE_NOT_FOUND` | Resource does not exist or is not visible |
| 409 | `STATE_CONFLICT` | State not allowed, duplicate operation, or version conflict |
| 413 | `FILE_TOO_LARGE` | File exceeds limit |
| 422 | `VALIDATION_FAILED` | Field or business validation failure |
| 429 | `RATE_LIMITED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Unexpected server error |
| 503 | `SERVICE_UNAVAILABLE` | Maintenance or dependency unavailable |

### 2.7 List, Filter & Sort

Common query parameters:

- `page[number]`: Starting from 1.
- `page[size]`: Default 20, maximum configurable by server.
- `sort`: e.g., `-submittedAt,studentName`, prefix `-` for descending.
- `filter[field]`: Exact or enum filtering.
- `search`: Keyword search when allowed by the resource.

Server must return stable sorting; pagination of the same request must not produce undefined ordering.

### 2.8 Concurrency & Idempotency

- Editable resources return their current `version` in the response body.
- Updates and decisions carry the resource's current `version` back as a **request-body field** — `version` on `PATCH`, `recordVersion` on service-record reviews, `proposalVersion` on proposal reviews — never an `If-Match` header; conflicts return `409 STATE_CONFLICT` carrying the current `version` so the client can retry with it.
- Submission, review decisions, account adjustments/reversal, and import confirmation require `Idempotency-Key`. Record/project/proposal creation does not — see §13.4 for the exact per-endpoint set.
- Same idempotency key and request body return the same business result; same key with different body returns conflict.

### 2.9 Deletion Rules

- Submitted, reviewed, and credited business records cannot be physically deleted.
- Drafts can be deleted; base data is preferentially deactivated or archived.
- Account balances can only change through transactions; no direct balance modification endpoint is provided.

### 2.10 Language and Localization

The web frontend ships in Chinese and English (default `zh-CN`). Localization is split across the API boundary by **what kind of text a field holds**, not by which screen shows it.

| Text class | Examples | Wire representation |
| --- | --- | --- |
| **Codes and enums** | `status`, `decision`, `type`, `sourceType`, `reasonCode`, `recognitionLevel`, `priority` | **Never localized.** The stable code is sent; the client owns the label and must fall back to rendering the raw code for values it does not recognize (§10.2) |
| **Dictionary / base data** | Service categories, organization types, recognition tiers, rule names | Server-localized. The response carries both the stable identifier (`categoryId` / `code`) and the localized `name` for the resolved language. Clients key logic and styling off the identifier, never off `name` |
| **User-authored content** | Project titles and descriptions, proposal fields, review comments, organization names, appeal text | Returned exactly as entered, in whatever language it was written. Never machine-translated, never stored once per language |
| **Server-generated messages** | Notification `title`/`body`, error `message` | Rendered per request language. Notifications additionally carry `templateCode` and parameters so history re-renders correctly after a language switch, and so a client may localize independently |

**Grades and classes are a deliberate exception to the dictionary row above** (issue
010, `Docs/issues/010-grade-class-raw-storage-and-abbreviated-format.md`): neither is
actually translated content. A grade number reads the same in every language (just
formatted differently — "G10" vs. "十年级"), and a class label like "Luna" is a proper
noun, never translated at all. `GET /grades` still returns a server-localized `name`
(unchanged — genuine cross-module consumers depend on it: Project's eligible-grades
display, Reports/CSV export), but every **student-linked** grade/class field
(`StudentListItemDto`/`StudentDetailDto`/`StudentDirectoryEntryDto`/
`ReviewQueueItemDto`) carries a raw `gradeLevel` (int, the grade number) and a raw,
language-invariant `className` instead of a pre-localized `gradeName` string. `GET
/classes`'s `name` is raw for the same reason — `class` has no other consumer. The
client assembles the localized display string itself (`formatGradeClass` /
`formatClassName`, `frontend/src/app/lib/format.ts`).

**Language resolution order:** `Accept-Language` header → the principal's `preferredLanguage` → the school's default language. Requests with no usable match fall back rather than failing, and a missing dictionary translation falls back to the school's default language — a localized `name` is never `null` or empty.

**`preferredLanguage`** is a BCP 47 tag restricted to the supported set (`zh-CN`, `en`). It is returned by `/me`, updatable through `PATCH /me/profile`, and is what asynchronous output uses — exports, e-mail, scheduled notifications — since those have no request header to read. A client may cache the choice locally for instant switching, but the server value is authoritative across devices.

**Locale never changes data semantics.** Numbers, dates, durations, and coin amounts are transported in the canonical forms of §2.1 regardless of language; only presentation differs.

### 2.11 Response Composition

Three rules that the API must satisfy so a page can render from the responses it already has, per §8's prohibition on row-by-row follow-up requests.

**1. Referenced entities ship with their display name.** Any ID a UI renders next to a human-readable label is accompanied by that label in the same response object:

| Field | Accompanied by |
| --- | --- |
| `projectId` | `projectTitle` |
| `studentId` | `studentName` |
| `categoryId` / `serviceCategoryId` | `categoryName` / `serviceCategoryName` |
| `organizationId` | `organizationName` |
| `ownerId`, `reviewerId`, `operatorId` | `ownerName`, `reviewerName`, `operatorName` |
| `relatedRecordId`, `linkedProjectId` | `relatedRecordTitle`, `linkedProjectTitle` |

These labels are **read-only point-in-time snapshots for display**. They are never accepted in a request body, never used as an identifier, and may legitimately differ from the referenced entity's current name if it was renamed after the fact.

**2. Viewer-derived fields are prefixed `my`.** `myApplicationStatus`, `myRole` and similar are computed for the authenticated principal. They are never cached across users and are absent — not `null` — when they do not apply.

**3. Server-computed aggregates are part of the resource.** `enrolledCount`, `projectCount`, `revisionCount`, `daysWaiting`, and progress totals are derived server-side. The client renders them and never recomputes or overrides them.

---

## 3. Core Resources & Statuses

### 3.1 Project `Project`

Core fields:

- `id`, `title`, `summary`, `description`
- `categoryId`, `organizationId`, `ownerId`
- `hostOrganizationId`, `hostOrganizationName`, `deliveryScope`
- `originProposalId`, `studentOrganizerIds`
- `startAt`, `endAt`, `location`
- `capacity`, `recognizedMinutes`
- `eligibleGrades`, `evidenceRequirements`
- `registrationOpenAt`, `registrationCloseAt`
- `status`, `version`, `createdAt`, `updatedAt`

**`hostOrganizationId`/`hostOrganizationName`/`deliveryScope`** (added, `Docs/optimization/03-ServiceRecordFormOptimization.md` §4.2/§10): every project has exactly one host organization — the school itself, a single system-wide singleton (the `isSchoolOrganization: true` organization row — never returned by `GET /organizations`, see §4.16 for its own read/edit endpoints) — resolved server-side and never client-writable. `organizationId`/`organizationName` keep their pre-existing meaning: the project's **partner** organization, if any (never the school organization itself — `POST`/`PATCH /projects` reject an `organizationId` equal to the host as a `400`, since that's automatic already). `deliveryScope` is `onCampus`/`offCampus`/`hybrid`, **required** on `POST /projects` (never inferred from organization type), optional on `PATCH`; projects that pre-date this field were backfilled to `hybrid` ("unknown, needs manual review"), not guessed either way.

Status:

```text
draft → published → registrationOpen → inProgress → completed → archived
              ↘ closed
```

Status is determined by both time and management actions; the frontend does not derive the final status on its own.

### 3.2 Event Proposal `EventProposal`

An event proposal is created by a student to apply for organizing a volunteer activity open to other students. Before approval, a proposal is not an official `Project`, does not appear in the Project Center, and cannot accept registrations.

Core fields:

- `id`, `proposerStudentId`, `coOrganizerStudentIds`, `coOrganizerNames`
- `gradeId`, `gradeLevel`, `className`
- `title`, `objective`, `description`, `expectedOutcome`
- `categoryId`, `plannedStartAt`, `plannedEndAt`, `location`, `format`
- `targetParticipants`, `capacity`, `volunteerTasks`
- `proposedSupervisorId`, `organizationId`, `externalContact`
- `safetyPlan`, `resourceNeeds`, `recruitmentPlan`
- `proposedRecognizedMinutes`, `evidencePlan`
- `status`, `currentRevision`, `assignedReviewerId`
- `approvedProjectId`, `version`, `createdAt`, `submittedAt`, `updatedAt`

`coOrganizerNames` (added 2026-08-06 alongside the write-side fields below, `Docs/optimization/01-i3-partner-school-terminology.md`) is server-resolved, read-only, positionally matched to `coOrganizerStudentIds` — the same pattern `proposedSupervisorName`/`organizationName` already use for `proposedSupervisorId`/`organizationId`.

`gradeId`/`gradeLevel`/`className` (added alongside `Docs/operations/VersionManagement.md`'s session, so admin/teacher list screens can filter and display by grade) are the proposal's own snapshotted grade/class from the proposer at creation time — never a live re-lookup of the proposer's *current* grade, matching `ServiceRecord`'s existing `gradeId`/`className` denormalization. `GET /event-proposals`'s `filter[gradeId]` matches against this snapshotted value, exact-equals (a proposal has exactly one grade, unlike `Project`'s multi-grade `eligibleGradeIds` in §4.2).

**Creating/updating a proposal** (`POST /event-proposals`, `PATCH /event-proposals/{id}`) accepts `organizationId` **or** `newOrganizationName`, never both:

- `organizationId` references an existing organization (from `GET /organizations`) — the backend validates it exists.
- `newOrganizationName` is a student-supplied name for a partner organization not yet in the system. The backend creates the organization (type defaults to `community`) and links its generated id to the proposal in the same transaction — the frontend must never call `POST /organizations` directly for this path (that endpoint stays `organizations.manage`-gated; a student proposing an event never holds it).
- Supplying both is a `400`. Supplying neither leaves the proposal without a partner organization, which remains valid (organization is optional, same as before).

`coOrganizerStudentIds` and `proposedSupervisorId` are both validated server-side against real, active accounts: every co-organizer id must be an existing student (and not the proposer themselves), and `proposedSupervisorId` must resolve to a user holding the `teacher` role — consistent with §4.3's "the designated teacher as project owner" on approval. Both were previously accepted unvalidated; the frontend now resolves them through the directory search below rather than free text.

Status:

```text
draft → submitted → approved
                 ↘ changesRequested → submitted
                 ↘ rejected
draft/submitted → withdrawn
```

(`underReview` was removed 2026-09-16, issue 080. It had become unreachable
when `recommendApproval` went away below — the only remaining way in was a
hop inside the decision transaction that was never committed, so no proposal
could ever be seen or filtered in that state. A reviewer decides in one step,
straight from `submitted`. Confirmed against live data first: no proposal,
and no audit entry, ever held it. The service-record and case flows keep
their own `underReview`/`inReview`, which are real resting states.)

Approval decisions:

- `requestChanges`
- `approve`
- `reject`

(`recommendApproval` — a no-op "keep at `underReview`, log an internal-only endorsement" decision for a two-tier review — was removed 2026-08-20: approval was already a two-step process (proposal approval, then a separate project publish, below), and a third "recommend, then someone else approves" tier made the workflow more complex than that two-step split needed. No live data ever recorded this decision before removal.)

Any authenticated user (`eventProposals.review`, held by `teacher`/`admin`/`sysadmin` today) can `approve`/`reject`/`requestChanges` a proposal — whether a given account should hold `eventProposals.review` is a role-configuration question (`Docs/optimization/07-RoleFinetune.md` §2.9), not something this contract enforces further. The approval action generates an official project draft and backfills `approvedProjectId` in the same transaction, so `approved` must always have a valid linked project. The official project must still be published by a teacher or administrator with publish permission — approval never publishes automatically.

### 3.3 Application `Application`

Status:

```text
pending → confirmed → completed
       ↘ waitlisted → confirmed
       ↘ rejected
confirmed → cancelled
```

### 3.4 Service Record `ServiceRecord`

`sourceType` distinguishes how the service arose, and is set at creation, never changed on update. **New records only accept `project` (performed under a project the student has a `confirmed` registration for) or `self` (student-initiated)** (`Docs/optimization/03-ServiceRecordFormOptimization.md` §3/§7.3, replacing the earlier three-way `project`/`external`/`self` split). `external` is **historical data only** — existing records keep it, are readable/reviewable, and a `draft`/`returned` one can still be edited and resubmitted with its original free-text fields (source never changes), but `POST /service-records` rejects `sourceType: "external"` outright. `sourceType` changes which fields are required/server-derived at creation and submission, not which state machine applies.

Status:

```text
draft → submitted → underReview → approved
                   ↘ returned → submitted
                   ↘ rejected → appealPending
approved → corrected
```

Core fields:

- `id`, `studentId`, `projectId`, `sourceType`, `recordNumber`
- `serviceDate`, `startAt`, `endAt`, `durationMinutes`
- `location`, `organizationId`, `organizationName`
- `hostOrganizationName`, `partnerOrganizationNames`
- `serviceCategoryId`, `description`
- `supervisorKind`, `supervisorSubjectId`, `supervisorName`, `supervisorContact`
- `evidenceFiles`, `includesTravelTime`, `travelTimeReason`
- `status`, `currentRevision`, `reviewSummary`
- `creditedUnits`, `accountTransactionId`
- `version`, `createdAt`, `submittedAt`, `updatedAt`

`includesTravelTime` is a disclosure flag (whether the claimed duration includes travel time), writable at creation and update, not derived; `travelTimeReason` is required (submission-time check, not a create-time hard block — a draft may be saved incomplete) whenever `includesTravelTime` is true, and is never counted toward `durationMinutes`.

**`sourceType: "project"`** (§10 D1/Q1): the client supplies only `projectId` — `organizationId`/`organizationName`/`location`/`serviceCategoryId`/`supervisorKind`/`supervisorSubjectId`/`supervisorName`/`supervisorContact` are all silently ignored if sent, and instead derived server-side from the project and snapshotted immutably at creation (a later edit to the project itself never retroactively changes an already-created record). The registration is validated as `confirmed` at **both** creation and submission — cancelling it after a draft was created blocks submission. `hostOrganizationName`/`partnerOrganizationNames` mirror the project's own host/partner organizations at snapshot time. `supervisorKind` is always `projectOwner`, resolved to the project owner's account (name + email); if that account has no email on file, submission fails with a `supervisorContact`/`CONTACT_MISSING` field error rather than silently omitting contact info. This applies uniformly to on-campus and off-campus (partner-organized) projects alike — the project owner (always an internal teacher/admin) is the recorded supervisor either way, never the partner organization's own contact person.

**`sourceType: "self"`**: `organizationId`/`organizationName` stay free text (no server-side validation against a real organization), same as the old `external` source. `supervisorKind` is one of `internalUser`/`student`/`externalManual` + `supervisorSubjectId` (a real user or student id, resolved server-side to name + email/contact — rejected with a `CONTACT_MISSING` field error if that account has no contact on file) — or, for `externalManual`, `supervisorName`/`supervisorContact` directly from the client, both required up front (no account to fall back on). A missing `supervisorKind` altogether is a `REQUIRED` field error at submission.

**`recordNumber`** (§5.3/§10 D3): server-generated at creation, unique, immutable, format `TB-{academicYearCode}-{gradeLevel}-{studentNumber}-{seq}` where `seq` is a per-(academic year, student) atomically-incrementing counter — never a "select max + 1" race. `null` for historical records that pre-date this field.

### 3.5 Review `Review`

Review decisions:

- `approve`
- `returnForChanges`
- `reject`
- `escalate`

Reviews are non-overwritable events; when a decision is changed, a new review event is created without overwriting history.

### 3.6 Appeal / Supplement `Case`

`type`:

- `appeal`
- `supplement`
- `manualCorrection`

Status:

```text
draft → submitted → resolved
                 ↘ needsInformation → submitted
                 ↘ rejected
submitted → draft
```

(`inReview` was removed 2026-09-16, issue 082 — the same defect issue 080
found in the proposal flow: the only way in was a hop inside the decision
transaction that was never committed, and cases never had a claim endpoint
either. A reviewer decides in one step, straight from `submitted`. Confirmed
against live data first: no case, and no audit entry, ever held it.
`assignedReviewerId` is kept — inert rather than misleading, and the field a
future claim feature would use.)

The `submitted → draft` edge is the student withdrawing their own case
(issue 081), owner-only and deliberately absent from `inReview` — so "only
before a reviewer picks it up" is enforced by the transition table rather
than by a separate precondition, exactly as it is for a service record.

`CaseDto` also carries `gradeId`/`gradeLevel`/`className` — the case's own snapshotted grade/class (set at creation from the filing student's grade at that time, never a live re-lookup), same convention as `EventProposal`'s (§3.2) and `ServiceRecord`'s. `GET /cases`'s `filter[gradeId]` (§4.8) matches against this snapshotted value.

### 3.7 Account & Transactions

`Account` is a balance view; `AccountTransaction` is an immutable ledger entry.

Transaction types:

- `serviceCredit`
- `correction`
- `freeze`
- `unfreeze`
- `redemption` (Phase 4)
- `borrow` (Phase 4)
- `repayment` (Phase 4)
- `bonus` (Phase 4)

### 3.8 Async Job `Job`

Applies to imports, exports, batch processing, and report generation:

```text
queued → running → succeeded
                 ↘ failed
                 ↘ cancelled
```

The frontend polls the job query endpoint; long-lived connections are not a required dependency.

---

## 4. Phase 2 APIs

### 4.1 Current User & In-App Notifications

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/me` | Authenticated | Current user, permissions, workspaces, feature flags |
| `GET` | `/notifications` | Authenticated | Current user notification list |
| `GET` | `/notifications/unread-count` | Authenticated | Unread count |
| `PATCH` | `/notifications/{id}` | Owner | Mark single notification as read |
| `POST` | `/notifications/read-all` | Owner | Mark all as read |
| `GET` | `/announcements` | Authenticated | Active announcements |

### 4.2 Projects

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/projects` | `projects.read` | Query visible projects |
| `GET` | `/projects/{id}` | `projects.read` | Project detail |
| `POST` | `/projects` | `projects.create` | Create draft |
| `PATCH` | `/projects/{id}` | `projects.update` | Update project |
| `POST` | `/projects/{id}/publish` | `projects.publish` | Publish |
| `POST` | `/projects/{id}/archive` | `projects.archive` | Archive a completed project |
| `POST` | `/projects/{id}/cancel` | `projects.cancel` | Cancel and archive a live (or completed) project outright, with a reason |
| `GET` | `/projects/{id}/timeline` | `projects.audit.read` | Everything that happened to the project, oldest first — unpaged. See below |

`deliveryScope` (`onCampus`/`offCampus`/`hybrid`) is required on `POST /projects`, optional on `PATCH` — see §3.1.

Project status changes use action endpoints to avoid bypassing business validation via a generic `PATCH status`.

**`GET /projects/{id}/timeline` (rebuilt 2026-09-22, issue 158).** Returns `TimelineEntryDto[]` — `{ action, actorId, actorName, subject, detail, occurredAt }` — **oldest first and unpaged**: a project has tens of events, and a history with a page missing is not a history. `action` is a **raw audit action code** (`project.publish`, `enrollment.register`, `enrollment.bulk.confirm`), never a sentence: the server authors no localized copy, so the client supplies the words and should degrade an unrecognized code to a humanized form rather than an empty cell. `actorId`/`actorName` are both null for a system-generated event such as the auto-archive sweep; a deleted account leaves the id and drops the name. `detail` carries the row's reason code, or its `after` summary when there is no reason — a cancellation's reason, a registration's resulting status, a review's `APPROVED`/`RETURNED`/`REJECTED` — and is null for events their action fully describes. **`subject` is whose registration or service record the event is about**, and null for the project's own events: the actor on a review decision or a confirmation is the *staff member*, so without it a row never says whose record was approved.

The entries are read from the **audit log**, not from a history table of its own, and cover three resources: the project, its **enrollments**, and its **service records**. The latter two are the non-obvious part — their audit rows are keyed to the enrollment or the record and carry no project id, so the two owning tables are the bridge. The endpoint is therefore correct for projects that ran long before this was built: no backfill was needed. Two entries are **derived rather than audited**, for rows the log never saw because they were inserted directly rather than through the API: `project.created`, from the project's own `createdAt`/`ownerId` when no create event exists, and `enrollment.registered`, from an enrollment's `applied_at` and status when no `enrollment.register` exists for it. Both use a distinct action code from their audited counterparts so provenance stays legible, and a client should give each pair the same wording. A derived entry has **no actor** — the row exists, but no person is on record for it — and is suppressed per resource when the log does cover it, so nothing appears twice.

Deliberately **not** included: field-level before/after — the timeline is a sequence, not a diff, and System Admin → Audit Logs answers that question.

**Publishing (issue 130).** `POST /projects/{id}/publish` applies the same grade rule as `PATCH` (below), and additionally refuses a `DRAFT` whose `registrationCloseAt` is not in the future — `409` with `fieldErrors[0].code = PROJECT_REGISTRATION_WINDOW_PASSED` and the close date as its `message`. Publishing such a draft would send it `PUBLISHED → REGISTRATION_OPEN → ARCHIVED` on the next read with nobody able to enrol.

**Publish notifications (issue 137).** `POST /projects/{id}/publish` announces the project to the year groups it is open to: a `project.published` notification (related resource `project`/its own id) for **every active student in one of the project's `eligibleGradeIds`** and **every staff account assigned to one of those grades** (the `serviceRecords` scope namespace, where a teacher's grades live). `admin`/`sysadmin` are **not** notified — they are school-wide, so "the corresponding grade" is every grade, and a message on every publish is noise. A project naming no eligible grade announces to nobody. Students with no account yet are skipped; pending-activation ones are notified, so it is waiting at first login.

**Visibility and editability (issue 129).** Staff see every project in the school and may copy any of them; `PATCH /projects/{id}` refuses one that is not open to any grade the actor is responsible for, with `403` and `fieldErrors[0].code = PROJECT_OUT_OF_YOUR_GRADES` — except a project the actor owns, which stays editable whatever its grades (creating carries no grade restriction). `admin`/`sysadmin` are unrestricted. A **student** sees only projects whose `eligibleGradeIds` contain their grade, plus any they have already applied to, plus any that name no grade at all (such a project restricts nobody).

**Status machine (revised 2026-08-08, `Docs/optimization/02-PorjectOrganization.md` §2):** `draft` → `published` → `registrationOpen` → `closed` → `inProgress` → `completed` → `archived`, where `registrationOpen`→`closed`→`inProgress`→`completed` are all system-driven (time-derived, synced lazily on read/action access) — `closed` means only "the registration window has elapsed," never a manual action and never a cancellation. `POST /projects/{id}/archive` only succeeds from `completed`, and only once every `CONFIRMED`-enrolled student's service record for that project has reached a terminal review decision (`approved`/`rejected`/`corrected`); otherwise it 409s with a state-conflict error carrying `fieldErrors: [{ field: "status", code: "UNREVIEWED_RECORDS", message: "<count>" }]` — the count is machine-readable there rather than only inside the English `message`, so a client can render it in its own language (issue 120; the same arrangement as `OVERLAPPING_RECORD` in §4.5). Note the admin UI no longer offers `cancel` on a `completed` project (issue 119), so reviewing the outstanding records is the only route to archiving it from the app; the endpoint itself still allows it. `POST /projects/{id}/cancel` (`reason` required, `@NotBlank`, stored as the audit entry's `reasonCode`) is the separate manager override — it archives directly from any of `draft`/`published`/`registrationOpen`/`closed`/`inProgress`/`completed`, bypassing the reviewed-records gate entirely, for discarding an unwanted draft, abandoning a live project outright, or force-closing a stalled one. There used to be a manual `POST /projects/{id}/close` (`projects.close`) action for early cancellation; it is retired now that `closed` is purely automatic — `cancel` replaces its use case. `CreateProjectRequest`/`UpdateProjectRequest` also carry an optional `ownerId`: admin-only (checked by role, not just `projects.create`/`.update` — teachers hold those too), and a non-admin's `ownerId` is silently ignored/forced to themselves rather than rejected.

**No-registrants auto-archive (added 2026-08-08):** the `registrationOpen`→`closed` time-derived transition first checks the project's confirmed-enrollment count — zero confirmed registrants archives the project directly (`registrationOpen`→`archived`, also system-driven) instead of proceeding to `closed`/`inProgress`/`completed`, since there's nothing left for those statuses to mean. A dedicated audit entry (`action: "project.autoArchiveNoRegistrants"`, `reasonCode: "NO_REGISTRANTS_AT_REGISTRATION_CLOSE"`, `actorId`/`permissionUsed` both null — there is no human actor) records why, distinguishable from every other status change. A project with at least one confirmed registrant is unaffected and proceeds through `closed` normally.

The admin "Registrants" screen (`GET /applications?filter[projectId]=`, §4.4) is only meaningful once a project has been published — the frontend hides that menu action for `draft` projects, since registration can never have opened yet.

`GET /projects`'s `filter[gradeId]` matches if the given grade appears **anywhere** in the project's `eligibleGradeIds` (§3.1) — "contains", not "equals", since a project can be eligible for more than one grade at once (unlike `EventProposal`/`Case`'s single-grade `filter[gradeId]`, §3.2/§3.6). Backed by a native `JSON_CONTAINS` query against `eligible_grade_ids` (a MySQL JSON column, not a normalized join table) rather than a JPA Criteria predicate, since Hibernate's `@Convert`-mapped collection columns can't express "array contains" as a Specification.

**`filter[academicYearId]` (added 2026-09-20, issue 112):** a project belongs to the academic year its `startAt` falls in, the same rule `/reports/top-projects` (§4.13) and the `projects` export use, and the window comes from the stored `academic_year` row rather than being recomputed from the turnover date (which is configurable — `TIMEBANK_ACADEMIC_YEAR_START`). Three rules differ deliberately from `/reports/*`:

- **Absent means every year**, not the current one. This endpoint is shared by several screens and any future caller, so a missing parameter must not narrow it.
- **An unknown id is a 404**, never a silent fall back to the current year — answering with a different year's projects hides the caller's bug behind rows that look real, the same reasoning as `ReportAcademicYearResolver`'s.
- **A student is pinned to the current academic year server-side**, whatever they pass. The Project Center is a "what can I join" screen and last year's projects are not joinable; enforcing it in the query means it cannot be sidestepped by editing a request. Consequently the Project Center carries no year control — a dropdown the server overrules would be a lie — while Project Management defaults to the current year and offers **All years**, so past data stays reachable.

### 4.3 Student Event Proposals

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/event-proposals` | Authenticated | Students see own proposals only (`Self` scope); `admin`/`sysadmin` see the full queue (`All` scope); every other non-student role (`teacher`, and any future grade-scoped custom role) sees only its assigned grades' proposals (`Grades` scope) — view access is role-based, not `eventProposals.read`-gated (2026-08-20, `Docs/optimization/07-RoleFinetune.md` §2.9/§2.10) |
| `POST` | `/event-proposals` | `eventProposals.create` | Student creates proposal draft |
| `GET` | `/event-proposals/{id}` | Authenticated | Proposal detail — same view-access rule as the list above |
| `PATCH` | `/event-proposals/{id}` | Proposer & editable | Edit draft or changes-requested proposal |
| `DELETE` | `/event-proposals/{id}` | Proposer & draft only | Delete draft |
| `POST` | `/event-proposals/{id}/submit` | Proposer | Submit for approval |
| `POST` | `/event-proposals/{id}/withdraw` | Proposer/admin permission | Withdraw or request withdrawal |
| `GET` | `/event-proposals/{id}/revisions` | Authenticated | Revision history — same view-access rule |
| `GET` | `/event-proposals/{id}/timeline` | Authenticated | Approval timeline — same view-access rule |
| `POST` | `/event-proposals/{id}/reviews` | `eventProposals.review` | Request changes, approve, or reject |
| `GET` | `/event-proposals/{id}/reviews` | Authenticated | Decision history for a proposal — same view-access rule |
| `POST` | `/event-proposals/{id}/assign` | `eventProposals.assign` | Assign or transfer reviewer |
| `GET` | `/event-proposal-queue/summary` | `eventProposals.review` | Pending counts and approval SLA |

**View vs. decide (2026-08-20):** every read endpoint above is `isAuthenticated()`-gated rather than `eventProposals.read`-gated — any authenticated user can view whatever proposals their data scope covers and open a proposal's detail (in the frontend, decision buttons simply don't render), with `DataScopeResolver` narrowing a student to their own proposals, `admin`/`sysadmin` to the full queue, and every other non-student role to its assigned grades (`Docs/optimization/07-RoleFinetune.md` §2.10's `hasSchoolWideVisibility()` rule, revising §2.1's original "any non-student sees everything"). Only *deciding* (`POST .../reviews`) and *assigning* (`POST .../assign`, `GET /event-proposal-queue/summary`) stay permission-gated on `eventProposals.review`/`.assign` — that split is what actually determines whether an account sees decision buttons, not `eventProposals.read`. `eventProposals.read` still exists in the permission catalog (§6 of `07-RoleFinetune.md`) for API-contract completeness but no longer gates any endpoint in this section.

**Approval notifications (2026-08-20, `Docs/optimization/07-RoleFinetune.md` §2.10.2):** `reject`/`requestChanges` notify the proposer only, unchanged. `approve` notifies **both** the proposer (`eventProposal.approved`, unchanged) **and** every active `projects.publish` holder covering the proposal's grade (`eventProposal.approvedNeedsPublish`, related resource `project`/the newly-generated project's id) — since approval only ever generates a *draft* project (never auto-published, see above), someone concrete needs telling that one is waiting. "Covers the proposal's grade" reuses the same grade-scope assignment that gates that account's own proposal-queue visibility; `admin`/`sysadmin` always count as covering every grade.

Approval request:

```json
{
  "decision": "requestChanges",
  "reasonCode": "SAFETY_PLAN_INCOMPLETE",
  "comment": "Please add an adult supervision and emergency contact plan.",
  "proposalVersion": 2
}
```

When approving a proposal, the backend:

1. Writes a non-overwritable approval event.
2. Updates proposal status to `approved`.
3. Generates an official `Project` draft based on the approved version.
4. Records the student as `studentOrganizer` and the designated teacher as project owner.
5. Backfills `approvedProjectId` and creates an in-app notification.

The frontend must not create a project first and then separately update the proposal, to avoid orphan projects or duplicates.

### 4.4 Applications

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/applications` | `applications.read` | Query own or in-scope applications |
| `GET` | `/applications/{id}` | `applications.read` | Application detail |
| `POST` | `/projects/{projectId}/applications` | `applications.create` | Student registers |
| `POST` | `/applications/{id}/cancel` | Owner/admin permission | Cancel registration |
| `POST` | `/applications/{id}/confirm` | `applications.manage` | Admin confirms |
| `POST` | `/applications/{id}/reject` | `applications.manage` | Reject application |
| `POST` | `/applications/bulk-actions` | `applications.manage` | Batch confirm, reject, or mark complete |

### 4.5 Service Records

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/service-records` | `serviceRecords.read` | Query own or in-scope records; `filter[status]`/`filter[sourceType]`/`filter[serviceCategoryId]`/`filter[studentId]`/`search` (added 2026-08-12 for the admin "view this student's records" screen — see the note after §4.9's table) |
| `POST` | `/service-records` | `serviceRecords.create` | Create draft |
| `GET` | `/service-records/{id}` | `serviceRecords.read` | Record detail |
| `PATCH` | `/service-records/{id}` | Owner & editable state | Update draft/returned record |
| `DELETE` | `/service-records/{id}` | Owner & draft only | Delete draft |
| `POST` | `/service-records/{id}/submit` | Owner | Submit for review |
| `POST` | `/service-records/{id}/withdraw` | Owner & review not started | Withdraw |
| `GET` | `/service-records/{id}/timeline` | `serviceRecords.read` | Record timeline |
| `GET` | `/service-records/{id}/revisions` | `serviceRecords.read` | Revision history |
| `GET` | `/service-records/{id}/reviews` | `serviceRecords.read` | Review history |

On submission, the server performs:

- Required field and format validation.
- Service time and duration rule validation.
- Project eligibility and registration relationship validation.
- Duplicate record detection.
- Evidence material completeness validation.

### 4.6 Files

Recommended controlled direct upload flow:

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `POST` | `/files/upload-intents` | Authenticated | Obtain short-lived upload credentials |
| `POST` | `/files/{id}/complete` | Uploader | Confirm upload complete and trigger validation |
| `GET` | `/files/{id}` | Resource access | Get metadata and short-lived download URL |
| `DELETE` | `/files/{id}` | Uploader & not bound to submitted record | Delete temporary file |

`upload-intents` request includes filename, MIME, size, and purpose; the server returns the allowed upload URL, request headers, expiration, and `fileId`. File status is `processing` until virus scan and type validation complete; files in `processing` status cannot be submitted for review.

### 4.7 Review Center

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/review-queue` | `reviews.read` | Query review queue |
| `GET` | `/review-queue/summary` | `reviews.read` | Pending and SLA summary |
| `POST` | `/service-records/{id}/reviews` | `reviews.decide` | Submit review decision |
| `POST` | `/service-records/reviews/bulk` | `reviews.bulkDecide` | Batch review |
| `POST` | `/service-records/{id}/assign` | `reviews.assign` | Assign or transfer reviewer |

Review request:

```json
{
  "decision": "returnForChanges",
  "reasonCode": "MISSING_EVIDENCE",
  "comment": "Please add confirmation from the organization.",
  "recordVersion": 3
}
```

Review approval must be in a single backend transaction:

1. Write review event.
2. Update service record status.
3. Generate time-coin transaction.
4. Update account balance view.
5. Update progress tracking.
6. Create in-app notification.

The frontend does not sequentially call multiple endpoints to simulate this transaction.

### 4.8 Appeals & Supplements

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/cases` | `cases.read` | Query own or in-scope cases |
| `POST` | `/cases` | `cases.create` | Create appeal/supplement draft |
| `GET` | `/cases/{id}` | `cases.read` | Detail |
| `PATCH` | `/cases/{id}` | Owner & draft/supplement state | Edit |
| `POST` | `/cases/{id}/submit` | Owner | Submit |
| `POST` | `/cases/{id}/withdraw` | Owner & review not started | Withdraw (issue 081) |
| `DELETE` | `/cases/{id}` | Owner & draft only | Delete draft (issue 081) |
| `POST` | `/cases/{id}/decisions` | `cases.decide` | Admin processing |
| `GET` | `/cases/{id}/timeline` | `cases.read` | Timeline |
| `GET` | `/cases/{id}/revisions` | `cases.read` | Revision history (added here 2026-08-12 — was already implemented, just missing from this table) |
| `GET` | `/cases/{id}/decisions` | `cases.read` | Decision history |

`type` is `appeal` \| `supplement` \| `manualCorrection`. `subjectType`/`subjectId` are optional and free-form at the API layer, but only `subjectType: "serviceRecord"` resolves to a human-readable `subjectLabel` today (`CaseServiceImpl.subjectLabel()`) — any other value, or an omitted subject entirely (a standalone request not tied to a specific record), is valid and simply carries no `subjectLabel`. `manualCorrection` is the one type whose `resolve` decision directly adjusts the student's time-coin ledger via `requestedAmountCentiCoins` (§3.6) — `cases.create` is granted to `role_student` as well as staff roles, but student-facing UI only offers `appeal`/`supplement`; `manualCorrection` is staff-initiated-only by convention, not by a permission restriction (`06-StudentCaseFiling.md` §2.1).

`GET /cases` also accepts `filter[gradeId]`, exact-equals against the case's own snapshotted grade (§3.6).

### 4.9 Students, Classes, Grades & Academic Years

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/students` | `students.read` | Student list |
| `POST` | `/students` | `students.manage` | Create student |
| `GET` | `/students/{id}` | Owner/`students.read` | Student detail |
| `PATCH` | `/students/{id}` | `students.manage` | Update authoritative profile |
| `POST` | `/students/{id}/activate` | `students.manage` | Activate |
| `POST` | `/students/{id}/deactivate` | `students.manage` | Deactivate |
| `POST` | `/students/{id}/reminders` | `students.read` | Send this student a service-progress reminder (issue 155) |
| `GET` | `/students/{id}/progress` | Owner/`progress.read` | Progress tracking |
| ~~`GET` `/students/{id}/service-records`~~ | — | Never implemented — this row described the intended shape but no such route exists server-side. Resolved the same way `api.accounts.get(studentId)` was (§13.1's note below): the frontend calls `GET /service-records?filter[studentId]=` instead of a dedicated sub-resource, reusing the list endpoint every other admin service-record screen already calls rather than adding a second route with its own filter/sort/pagination to maintain. |
| `GET` | `/students/directory` | `students.read`/`directory.read` | Minimal `{id, displayName, gradeLevel, className}` search by name, for picking a co-organizer (added 2026-08-06, `Docs/optimization/01-i3-partner-school-terminology.md`; `gradeName` became raw `gradeLevel` in issue 010) |
| `GET` | `/academic-years` | Authenticated | Academic years |
| `GET` | `/grades` | Authenticated | Grades |
| `GET` | `/classes` | Authenticated | Classes |

**`POST /students/{id}/reminders` (added 2026-09-22, issue 155).** Writes one
notification to the student's own login account, from the `student.serviceProgressReminder`
template — so, like every other notification, it is rendered in the *reader's*
language when they open it, not the sender's when it was sent. No request body:
the only thing a reminder carries is who sent it and when, and both are already
known. `students.read` rather than `students.manage`, because the person who can
see a student's progress is the person who should be able to nudge them about it,
and the student data scope decides which students that is — sending a message is
not editing a record.

It **refuses rather than pretending** when the message would reach nobody:
`409 STATE_CONFLICT` with `fieldErrors[0].code = NO_LOGIN_ACCOUNT` (the student
has no account at all — `POST /students` provisions none, only the roster import
does) or `ACCOUNT_NOT_ACTIVE` (pending activation, expired, locked or
deactivated). Reporting success for an undeliverable reminder is the exact bug
issue 155 was filed about. Clicking twice sends twice; there is no de-duplication
window.

**Student records carry no guardian or roster-email fields.** `guardianName`, `guardianContact` and the student's own `email` were dropped in `V26` under data minimization (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1): no screen displayed them, so they were breach surface with no functional return. They are absent from `POST`/`PATCH /students` requests, from `GET /students/{id}`, and from the `students` import template, which is now seven columns (`studentNumber,displayName,gradeId,classId,enrollmentAcademicYearId,graduationYear,joinedAt`). `user.email` is unaffected — a different field on the login account, and the identifier the sign-in page accepts.

A consequence worth knowing: a `supervisorKind: "student"` peer supervisor's contact now resolves through that student's **linked login account** rather than a roster email, so a roster-only student with no account fails `CONTACT_MISSING` as before.

**`GET /students` returns current students unless asked otherwise (changed 2026-09-20, issue 114).** `filter[status]` takes a `StudentStatus` name (`ACTIVE`, `INACTIVE`, `GRADUATED`); **an absent filter means everything except `GRADUATED`**. This narrows on a missing parameter where `GET /projects` deliberately does not (§4.2), and the difference is not an inconsistency: graduation leaves `gradeId` on the grade the student finished (`14-AnnualRollover.md` §3.1), so from the first rollover onward an unfiltered list puts two cohorts in "grade 8" at once and anyone counting their grade 8 gets roughly double with nothing saying why. On `/projects` the extra rows were harmless history; here they collide inside a single number. Naming the status explicitly still reaches every value, `GRADUATED` included — the cohort is hidden by default, never unreachable.

`directory.read` is deliberately narrower than `students.read`: it's the permission `role_student` holds (students otherwise have no way to look up another student's or teacher's id), and its response never carries `studentNumber`/`status`/`graduationYear`/contact fields the way `/students`'s own list response does. `search` is required and results are capped at a small page size (20) — this is a typeahead, not a directory browse.


**`filter[academicYearId]` on `GET /cases` and `GET /event-proposals` (added 2026-09-20, issue 115).** Each is scoped by the date that defines *its* year, and the two are deliberately different:

- **A case belongs to the year it was filed** (`createdAt`). A case has no event of its own — it is an action taken about something else, and filing is its only date that cannot change afterwards. `createdAt` rather than `submittedAt` because a draft has no `submittedAt` and a student's own case list shows drafts; keying on a nullable column would drop every draft out of every year.
- **A proposal belongs to the year its planned event falls in** (`plannedStartAt`). Approving a proposal generates the official project in the same transaction, and `GET /projects` scopes that project by `startAt` (§4.2). Dating the proposal by when it was written would let a proposal and the project it became land in different years — two screens disagreeing about one event.

As on `/projects`: **absent means every year**, and an **unknown id is a 404** rather than a silent fall back. Unlike `/projects` there is **no student pin** — both endpoints serve the students' own case and proposal history, and a pinned year would hide someone's own past from them. The current-year default on Case Review and Proposal Review therefore lives in those screens, not in the API.

### 4.10 Organizations & Service Categories

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/organizations` | `organizations.read` | Organization list — `filter[type]`, `filter[status]`, `search` (name only) all supported (added 2026-08-08, `02-PorjectOrganization.md` §4; previously accepted but silently ignored these params). Never includes the school organization (`isSchoolOrganization: true`) — that row is the school's own archive record, not a partner; read/edit it via `GET`/`PATCH /school/info` (§4.16) instead |
| `POST` | `/organizations` | `organizations.manage` | Create |
| `GET` | `/organizations/{id}` | `organizations.read` | Detail |
| `PATCH` | `/organizations/{id}` | `organizations.manage` | Update |
| `POST` | `/organizations/{id}/archive` | `organizations.manage` | Archive — the only removal action; there is no hard delete, by design (`02-PorjectOrganization.md` §4) |
| `GET` | `/service-categories` | Authenticated | Service categories, active ones only — the list behind every form's picker |
| `GET` | `/service-categories?includeInactive=true` | `serviceCategories.read` | The management view: retired categories included |
| `POST` | `/service-categories` | `serviceCategories.manage` | Create category |
| `PATCH` | `/service-categories/{id}` | `serviceCategories.manage` | Update category (both names, `active`) |
| `DELETE` | `/service-categories/{id}` | `serviceCategories.manage` | Delete category; `409` when any record/project/proposal still references it |

**Service categories got permissions of their own (added 2026-09-22, issue 157).** They used to borrow `rules.manage`, which meant a school could not let someone tidy the category list without also handing them the rules that decide who graduates. `serviceCategories.read` goes to teacher/admin/sysadmin, `serviceCategories.manage` to admin/sysadmin. The **query parameter decides which permission the `GET` needs**, which is unusual enough to state plainly: the plain list is the dictionary behind every form's picker and a student has to have it, so it stays merely authenticated; `?includeInactive=true` is the management view and the only thing it adds is *what has been retired*, so the permission sits exactly where the extra information does. Asking for it without `serviceCategories.read` is a `403`.

`organizations.manage` is **admin-only again from 2026-09-22** (`V35`): it was widened to `role_teacher` on 2026-08-08, and that is reversed. A partner organisation is shared vocabulary — every project and service record points at one, so renaming or archiving one changes what other people's records say about themselves. Teachers keep `organizations.read`, which is what picking a project's host organisation needs. `Organization` gained a `description` field (free text, alongside the existing structured `type`/`contactName`/`contactEmail`/`contactPhone`/`address`) in the same change; `CreateOrganizationRequest`/`UpdateOrganizationRequest`/`OrganizationDto` all carry it.

`OrganizationDto` also carries a read-only `isSchoolOrganization` boolean (`Docs/optimization/03-ServiceRecordFormOptimization.md` §4.1) — exactly one row in the whole system has this `true`, seeded once and never through `POST /organizations`. `PATCH /organizations/{id}` and `POST /organizations/{id}/archive` both `409` on that row — its name/address/contact can only change through `PATCH /school/info` (§4.16, `sysadmin` only), not the normal organization-management flow, and it can never be archived. Every project's host organization (§3.1) resolves to this row.

### 4.11 Accounts & Transactions

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/accounts` | `accounts.read` | In-scope account list |
| `GET` | `/accounts/summary` | `accounts.read` | Totals over every account the caller may see — `totalBalance`, `accountCount`, `accountsWithBalance`, `averageBalance`, all in minimum accounting units. Scoped identically to `GET /accounts`, so a teacher's figures cover their grades and an administrator's the school (issue 128) |
| `GET` | `/accounts/{id}` | Owner/`accounts.read` | Account balance view |
| `GET` | `/accounts/{id}/transactions` | Owner/`accounts.read` | Transactions |
| `GET` | `/account-transactions/{id}` | Owner/`accounts.read` | Transaction detail |
| `POST` | `/accounts/{id}/adjustments` | `accounts.adjust` | Manual adjustment generating transaction |
| `POST` | `/account-transactions/{id}/reverse` | `accounts.reverse` | Reverse specified transaction |
| `POST` | `/accounts/{id}/freeze` | `accounts.freeze` | Freeze |
| `POST` | `/accounts/{id}/unfreeze` | `accounts.freeze` | Unfreeze |

All change requests must include a standardized reason code and description. Reversal generates a reverse transaction; the original transaction is not deleted.

### 4.12 Rules

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/rules` | `rules.read` | Query current or historical rules |
| `POST` | `/rules` | `rules.manage` | Create rule draft |
| `GET` | `/rules/{id}` | `rules.read` | Rule detail |
| `PATCH` | `/rules/{id}` | `rules.manage` | Update draft |
| `POST` | `/rules/{id}/validate` | `rules.manage` | Validate and return impact summary |
| `POST` | `/rules/{id}/publish` | `rules.publish` | Publish and set effective time |
| `POST` | `/rules/{id}/archive` | `rules.manage` | Archive |

Effective rules cannot be modified in place; changes must create a new version.

### 4.13 Dashboards, Statistics & Reports

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/dashboards/student` | Student | Student home aggregation |
| `GET` | `/dashboards/teacher` | Teacher | Teacher pending and scope statistics |
| `GET` | `/dashboards/admin` | Admin | School-wide dashboard |
| `GET` | `/reports/participation` | `reports.read` | Participation rate |
| `GET` | `/reports/completion` | `reports.read` | Compliance rate |
| `GET` | `/reports/service-hours` | `reports.read` | Service hours |
| `GET` | `/reports/review-efficiency` | `reports.read` | Review efficiency |
| `GET` | `/reports/top-projects` | `reports.read` | Highest-participation projects for the selected period |
| `POST` | `/exports` | `exports.{reportType}` | Create export job |
| `GET` | `/exports/{jobId}` | Creator/admin | Query export job |
| `GET` | `/exports/{jobId}/download` | Creator/admin | Get a **single-use** download URL |


**`/reports/service-hours` resolution (issue 133).** The response carries the academic year at two resolutions: `byMonth` (`{month, hours}`, integer hours) for month-level summaries, and `byDay` (`{day, minutes}`, ISO `yyyy-MM-dd`) for the trend line. `byDay` includes **every day of the year, zero-minute days included** — a line chart spaces its points evenly whatever their dates, so a sparse series would draw a fortnight's gap the same width as a weekend. Minutes rather than hours because a day's service is often a fraction of one.

**Scoped comparison series (issue 135).** `/reports/service-hours` also returns `byDayInScope`, and `/reports/top-projects` returns `topProjectsInScope` and `byCategoryInScope` — the same figures narrowed to the caller's own grades, so a teacher can read their cohort against the school. **All three are empty for a school-wide caller** (`admin`/`sysadmin`): an administrator already sees everything, and a second identical series or pie says nothing twice. The scoped figures count through the *enrolled student* (and, for hours, the record's student), not the project's eligible grades — a project open to four year groups is not four times any one teacher's.

**`POST /exports` is authorised per report type.** The permission is
`exports.` + the `reportType` in the body — `exports.studentList`,
`exports.serviceRecords`, `exports.studentCompletion`, `exports.projects`,
`exports.studentParticipation`, `exports.reviewEfficiency`,
`exports.reviewHistory`. The former blanket `exports.create` is retired
(`Design/*/13-DataExport.md` §9.4): "may export" meant "may export
everything", and Review Efficiency is peer performance data that the Student
Development Centre holds and teachers do not. The retired permission row is
kept so the audit trail of who once held it still resolves, but it grants
nothing and cannot be assigned.

Two consequences worth knowing when writing a client:

- **Authorisation is checked before the report type is validated.** Asking for
  a type that does not exist returns `FORBIDDEN` (403), not
  `INVALID_REQUEST` (400), because no one holds `exports.somethingMadeUp`.
- **Only `studentList` and `serviceRecords` are accepted today.** The other
  five are being re-cut per type and return `INVALID_REQUEST`; two earlier
  reports (`serviceHours`, `topProjects`) retired rather than being carried
  forward. The permissions for all seven exist now, so a client may gate its
  UI on them before the corresponding file exists.

**Download URLs are single-use.** The URL from
`GET /exports/{jobId}/download` carries a token valid for 300 seconds *and*
for exactly one fetch. Presenting it twice returns `STATE_CONFLICT` (409),
deliberately distinct from the `INVALID_REQUEST` an expired link gives, so a
client can tell "you waited too long" from "this link is already spent". The
file itself outlives the link, so the recovery is to ask for a new URL —
requesting one does not re-run the export. A client that fetches the content
URL more than once per user action (a retrying download manager, a probe
request before the real one) will burn the link on its own.

**A produced export file is deleted 7 days after the job finishes**, and its
job row's `resultRef` becomes `null` rather than pointing at a key that is no
longer there. Asking for a download URL after that returns
`RESOURCE_NOT_FOUND`.

Dashboard endpoints are for homepage aggregation only; business details are still obtained through resource endpoints. All page data must not be concentrated into a single "mega endpoint."

**Academic-year scope of the five report endpoints.** Each accepts an optional `?academicYearId=` (an `id` from `GET /academic-years`, never the `code`). Omitted or blank means the academic year currently marked current — never "all years". An `academicYearId` that matches no academic year returns `RESOURCE_NOT_FOUND` (404) rather than silently falling back to the current year. Every report echoes the year it applied as `academicYearId` in its response body, so a client can confirm the scope it got is the scope it asked for.

What "scoped to a year" means is not the same for all five, because nothing in the data model tags a student or a service record with an academic year:

| Endpoint | Scoped by |
| --- | --- |
| `/reports/participation` | Service records **dated** inside the year. Population: students who are active and had already enrolled by the end of that year. |
| `/reports/completion` | As participation, plus the annual required minutes that were in effect **during** that year — not today's rule. |
| `/reports/service-hours` | Approved records **dated** inside the year, bucketed by month. |
| `/reports/review-efficiency` | Review decisions **taken** inside the year (when the reviewer decided, not when the service happened). |
| `/reports/top-projects` | Projects **scheduled** inside the year (`startAt`), with hours from approved records dated inside it. |

Two limits are inherent rather than bugs to report: a student who has since left the school is absent from a past year's population, because student status is a present-tense fact and no historical roster is kept; and the export jobs in `POST /exports` carry no period of their own, so a report export is always of the current academic year.

Dashboard endpoints take no `academicYearId`: they describe the present state of the school, and their period-bearing figures already name their own period (`approvedThisMonth`, `totalServiceHoursThisYear`).

### 4.14 Imports

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/imports/templates/{type}` | `imports.create` | Download template |
| `POST` | `/imports` | `imports.create` | Create import job |
| `GET` | `/imports/{id}` | `imports.read` | Import detail |
| `POST` | `/imports/{id}/validate` | `imports.create` | Pre-validate |
| `GET` | `/imports/{id}/errors` | `imports.read` | Error list/file |
| `POST` | `/imports/{id}/commit` | `imports.commit` | Confirm import |
| `POST` | `/imports/{id}/cancel` | `imports.create` | Cancel |
| `GET` | `/imports/window` | `imports.read` | Historical-import window state |

Imports must validate before commit. Duplicate imports are prevented by source file hash, business unique keys, and idempotency keys working together.

**Import is the SDC Admin's, not the System Administrator's.** `imports.create`,
`imports.read` and `imports.commit` are no longer granted to `sysadmin`
(`Design/*/11-RolesAndPermissions.md` §6.1): the System Administrator
maintains the system and does not author student data, and importing service
history is authoring student data.

#### Historical import closes permanently

`serviceRecordsHistorical` exists for the move onto this system and is not a
standing capability. The **first successful** historical import opens a 15-day
window; further historical imports are allowed inside it and **none of them
extends it**. When it runs out, historical import is refused for good — the
only way back is a maintenance utility run on the server console by a System
Administrator (`Docs/backlog/01-HistoricalImportReenableUtility.md`), which
nothing in the API can do.

Roster import (`students`) is **outside** the window and never closes: a school
takes in students every year.

The refusal is enforced twice — at `POST /imports` (before the file is stored)
and again at `POST /imports/{id}/commit`, so a job validated before the window
closed cannot commit after it. Both return `STATE_CONFLICT` (409) naming the
closure date.

`GET /imports/window` returns the state the UI needs to decide whether to offer
the feature at all:

| Field | Meaning |
| --- | --- |
| `state` | `NOT_OPENED` · `OPEN` · `EXPIRED` |
| `windowDays` | The length applied when this window opened — a stored fact, so changing the configured length never reinterprets a window already running |
| `openedAt` / `expiresAt` | `null` while `NOT_OPENED` |
| `secondsRemaining` | Server-computed, so a skewed client clock cannot render a confident wrong countdown; `null` unless `OPEN` |

Clients should **hide** the historical-import control on `EXPIRED` rather than
disable it, and hide it when the read fails: a control that cannot be used
should not be on screen.

### 4.15 Users, Roles & Audit

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/users` | `users.read` | User list — `search` (display name), `filter[role]`, `filter[status]`, `filter[gradeId]`, `filter[classId]`, and `sort` by `displayName`/`gradeLevel`/`className`/`username`/`createdAt` (added 2026-09-17, issue 094; the endpoint previously accepted paging only) |
| `POST` | `/users` | `users.manage` | Create user |
| `PATCH` | `/users/{id}` | `users.manage` | Update user |
| `POST` | `/users/{id}/unlock` | `users.manage` | Unlock |
| `POST` | `/users/{id}/reset-password` | `users.manage` + `admin`/`sysadmin` role | Admin-triggered reset — clears the account's password and returns a printable activation slip as a PDF (`Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.7); **no password is returned, because none is generated** |
| `POST` | `/users/activation-slips` | `users.manage` + `admin`/`sysadmin` role | Generate a class's printable activation slips as one PDF (`Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4) |
| `GET` | `/roles` | `roles.read` | Role list |
| `POST` | `/roles` | `roles.manage` | Create role |
| `PATCH` | `/roles/{id}` | `roles.manage` | Update role name/session timeout/permissions |
| `DELETE` | `/roles/{id}` | `roles.manage` | Delete a role (07-RoleFinetune.md §2.2/§2.4) |
| `GET` | `/permissions` | `roles.read` | Permission code catalog |
| `GET` | `/users/directory` | `users.read`/`directory.read` | Minimal `{id, displayName}` search by name and role, for picking a proposed supervisor (added 2026-08-06, `Docs/optimization/01-i3-partner-school-terminology.md`) — `role` query param required (e.g. `teacher`), no other user fields (username/email/studentId/roleCode/status) are exposed |
| `GET` | `/audit-logs` | `auditLogs.read` | Operation audit |
| `GET` | `/audit-logs/facets` | `auditLogs.read` | Distinct `action`/`resourceType` values present, for filter dropdowns (added 2026-09-18, issue 098) |
| `GET` | `/login-logs` | `securityLogs.read` | Login logs |
| `GET` | `/system/settings` | `settings.read` | System parameters |
| `PATCH` | `/system/settings` | `settings.manage` | Update allowed parameters |

**`GET /audit-logs` — search, filter and sort** (widened 2026-09-18, issue
098). `search` matches the **actor's display name**, `action`, `resourceId` or
`requestId`, case-insensitively. `filter[action]`, `filter[resourceType]`,
`filter[resourceId]`, `filter[actorId]`, `filter[createdFrom]` and
`filter[createdTo]` (the last two ISO-8601 instants). Sortable on `createdAt`,
`action` and `resourceType`; `-createdAt` is the default, and `a.id desc` is
appended to every ordering because `created_at` is not unique — one transaction
writes several rows in the same millisecond, and without a tiebreaker they can
appear on two pages or on none.

Two of these were previously **accepted and discarded**, which is worse than
absent because a caller cannot tell: `search` was never read, and the statement
ended in a hardcoded `order by created_at desc`, so `sort=createdAt` returned
newest-first exactly like `-createdAt` and ascending order was unreachable.

Each row carries `actorDisplayName` — **resolved server-side**, null for a
system-generated entry. Clients must not resolve it from a loaded user list:
that list is one page, so the name would depend on unrelated state and fall back
to a raw opaque id for anyone outside it. `beforeSummary`/`afterSummary` are
JSON **strings** as stored, whose shape varies per action — format them for
display, do not destructure.

`GET /audit-logs/facets` returns `{ actions: string[], resourceTypes: string[] }`
— the distinct values the log actually holds, sorted. Served rather than
hardcoded because action codes accrue with every feature: a literal client-side
list quietly stops offering the newest one, and the filter looks complete while
being unable to select it. Not narrowed by the caller's active filters, which
would make switching away from the current value impossible.

**Roles are configurable, not a fixed vocabulary** (07-RoleFinetune.md) —
`student`, `admin`, and `sysadmin` are protected (undeletable; `sysadmin` is
also not exposed in the admin UI, though it exists and functions normally)
and `teacher` plus any school-defined custom role are ordinary, deletable
rows. `DELETE /roles/{id}` returns `409` if the role is protected or still
assigned to any user. `POST /roles` / `PATCH /roles/{id}` reject
`permissionCodes` containing `eventProposals.create`, `applications.create`,
or `serviceRecords.create` with a `422` — those three are reserved for the
`student` role, since a manageable role is always staff-type, never a
self-service submitter.

**Every user holds exactly one role, always** (07-RoleFinetune.md §2.5) —
`POST /users` / `PATCH /users/{id}` / the `GET /users` list response carry a
single `roleCode: string`, not a set. (`GET /me`'s `roles`/`workspaces`
arrays, by contrast, are unchanged — always one element in practice now, but
still array-shaped on the wire; see §2.2's login-response example.)

`POST /users` / `PATCH /users/{id}` / the `GET /users` list response also carry
`scopeGradeIds: string[]` (added 2026-08-10, issue 002) — the grade ids a
`teacher`-role account is bonded to (`teacher_scope_assignment` rows, one per
grade per resource type §11 acceptance scenario 8 already scopes: `serviceRecords`,
`eventProposals`, `students`, `accounts`, `cases`). Only meaningful when
`roleCode` is `teacher`: ignored on create for any other role, and
automatically cleared if a `PATCH` changes `roleCode` away from `teacher`.
Not required for `admin`/`sysadmin` — their unrestricted data-scope access is
a permission-level bypass (`hasFullDataScope()`, granted to every non-`student`
role), not scope data, so `scopeGradeIds` is always empty for non-`teacher`
accounts and the UI shows no grade picker for them.

**`scopeGradeIds` is not a grade** (added 2026-09-17, issue 094). `GET /users`,
`POST /users` and `PATCH /users/{id}` also carry `gradeId: string | null`,
`gradeLevel: number | null`, `classId: string | null` and
`className: string | null` — the **linked student's own placement**, read from
the `Student` row `studentId` points at (§4.9). The two sets of fields answer
different questions and neither is a fallback for the other:

- A `student`-role account carries all four, and an empty `scopeGradeIds`.
- Every staff account carries all four as `null`, and expresses its grade reach
  through `scopeGradeIds` instead — empty meaning school-wide.

**`status` is derived, not the stored column** (added 2026-09-17, issue 097).
A brute-force lockout writes `locked_until` and never touches `status`, so
`GET /users` and `GET /users/{id}` report `locked_until > now ? LOCKED : status`
— a locked-out account reads `LOCKED` while its stored row still says `ACTIVE`.
`filter[status]` matches the same derived value, so `LOCKED` returns exactly the
accounts locked out at that moment and `ACTIVE` excludes them. Both self-heal at
expiry: nothing runs when a lock releases, and nothing needs to.

`lockedUntil: string | null` accompanies it — when the lock releases, absent
otherwise (including for an already-expired `locked_until`).

**`PATCH /users/{id}` rejects `status: "LOCKED"`** with `400 INVALID_REQUEST`. It is not a
settable state: a lockout is applied by the login-failure counter and released
by `POST /users/{id}/unlock` or by waiting. Persisting it would produce an
account `unlock` cannot release, because the lock test ORs on the status itself.
A client that seeds an edit form from a row must therefore not send `status`
back unchanged — omit it, which `PATCH` already reads as "don't touch".

`filter[gradeId]`/`filter[classId]` match on the student placement, so they
never return a staff account; `filter[role]` matches the account's single
`roleCode`, and `filter[status]` a `UserStatus` name (`ACTIVE`, `INACTIVE`,
`LOCKED`, `PENDING_ACTIVATION`, `ACTIVATION_EXPIRED`) — an unrecognized value
yields an empty page rather than an error, matching §2.7's convention
everywhere else. `sort=gradeLevel` orders by the grade's sequence (6…11), not
by `gradeId`, which would sort `grade_10` ahead of `grade_6`; accounts with no
grade sort as SQL `NULL` (first ascending, last descending). `search` matches
`displayName` only — not username or email.

`POST /users` / `PATCH /users/{id}` also carry `studentId: string | null` and
`staffId: string | null` (added 2026-08-10, issue 003), both surfaced back in
`GET /users` and the `PATCH` response:

- `studentId` — only for `roleCode` of `student`; required for that
  role. Must be the id of an existing, not-already-linked
  `Student` row (contract §4.9) — creating a student-role account does **not**
  accept a free-typed grade/class/student-number; the client looks one up via
  `GET /students/directory` and submits its id, the same picker pattern used
  for a proposal's co-organizers/supervisor. Linking a `Student` already
  linked to another account, or an unknown id, is a `409`/`400` respectively.
- `staffId` — required for every non-`student` role (widened from
  "teacher or admin only" — 07-RoleFinetune.md — since any staff-type role,
  custom ones included, needs one). A free-text, school-assigned identifier
  (no existing "staff roster" module to link against, unlike `studentId`) —
  must be unique across accounts; a duplicate is a `409`.
- Both follow the same partial-update convention as every other `PATCH`
  field: omitted/`null` means "don't touch," present means "replace."
- These required-ness rules are enforced at request-validation time, not
  bean-level `@NotBlank`, since each is conditional on `roleCode`.

**`POST /users` creates no password** (changed 2026-09-17, issue 096). The
request body has **no `initialPassword` field** — it carried a required one
until that date — and the account it creates is `PENDING_ACTIVATION` with no
stored hash, exactly like a roster-imported student's. Nobody, including the
administrator who created it, can sign into it until an activation slip is
redeemed, so a client must follow the create with
`POST /users/{id}/reset-password` (below) to print one. This is what makes
`10-StudentIdentityAndOnboarding.md` §2.4's *"no password plaintext exists at
any point"* true with **no staff exception**: an administrator-typed initial
password has to be conveyed over a channel nobody controls, which is the
"初始密码清单" `08-mlps-level1-security-design-guide.md` §8.2 tells schools this
system does not produce.

Two consequences for the request body:

- `username` is **required and explicit**, `@Size(min = 3, max = 64)`. The
  admin UI used to send `email.split('@')[0]`.
- `email` is **optional** — `@Email`, no `@NotBlank` — and always has been. It
  only looked required because the username was derived from it. Send it
  omitted or `null`, not `""`, which `@Email` rejects.

Creating a `student`-role account through this endpoint remains *possible* on
the API and is no longer offered in the admin UI: students arrive by roster
import, which mints the 8-random-digit username §2.1 requires. A hand-typed
student username is the enumerable, name-leaking kind that section exists to
avoid.

**`POST /users/activation-slips` — the credential slip document**
(added 2026-09-17, `Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.4).
The only way a student ever receives their first credential: it mints one
single-use activation code per still-pending member of a class and returns the
printable slips as **one PDF**.

```json
{ "classId": "cls_...", "slipsPerPage": 1 }
```

- **Response is the PDF itself**, not §2.5's `data`/`meta` envelope —
  `200 application/pdf` with `Content-Disposition: attachment` and
  `Cache-Control: no-store, private, max-age=0`. This is the same exception
  already made for `GET /files/{id}/content`. A failure *before* the document
  exists still returns a normal error envelope, so clients tell the two apart by
  content type. The document is streamed and **never stored** — deliberately not
  through the `/files` pipeline, which sanitizes and watermarks *evidence* and
  would give a credential a second life behind a short-lived URL.
- **`POST`, not `GET`, even though it downloads a file.** The call mints fresh
  codes and retires whatever codes those students held before, so a prefetch, a
  proxy retry or a back button on a `GET` would silently invalidate slips that
  were already handed out.
- **`slipsPerPage` is a closed enum of `1` or `2`, defaulting to `1`** (§2.8,
  D-8) — not a validated integer range. Any other value fails deserialization
  and returns `400 INVALID_REQUEST` before anything is issued.
- **Admin only** (§2.10, D-9/D-10): `users.manage` *and* the `admin` or
  `sysadmin` role code. A plain role check, not a data-scope resolver — teachers
  get no slip path at all, not even a scoped one for their own class, so there
  is no "own class" branch to get wrong. Anyone else gets `403`. Narrower than
  `users.manage` alone for the same reason `PATCH /school/info` is narrower than
  `settings.manage` (§4.16).
- **Already-activated students are spared** (§2.7): only accounts in
  `PENDING_ACTIVATION` or `ACTIVATION_EXPIRED` are issued for, so a regeneration
  after five lost slips does not reset the twenty-five students who were fine.
  A class where everyone has activated is `409`, not an empty PDF. An unknown
  `classId` is `404`. A roster row with no account at all is skipped rather than
  failing the batch.
- **Each slip carries** the student's display name, their username, a QR, the
  typed activation code in its printed grouping (`HKTZ4-9QP2M`), and a
  provenance line — *issued by, at, valid before* — **on every slip, not once
  per page**, because the sheet gets cut.
- **The QR encodes the same code**, wrapped as `<site root>/activate?code=...`
  (§2.5's "one secret, two delivery forms"), matching the `/activate` route
  described in §2.2. The site's **root** URL is also printed as text beside it
  (§2.9): it is a phishing anchor giving a child something to check the address
  bar against, which justifies printing it regardless of whether anyone types
  it. The deep link is never printed as text.
- **The plaintext code appears in the PDF and nowhere else** — not in the audit
  row, not in a log line, not in an error message. The audit row
  (`user.issueCredentialSlips` on `schoolClass`) records who issued for which
  class, how many slips, and how many students were spared.
- The server needs `TIMEBANK_SITE_ROOT_URL` set (`timebank.auth.activation.site-root-url`);
  it fails fast at startup without it, since a wrong value would print a domain
  the school does not own onto paper handed to students.

**`POST /users/{id}/reset-password` — an admin reset issues an activation slip,
not a password** (changed 2026-09-17,
`Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.7). It used to
generate a temporary password server-side and return the plaintext in
`{ username, temporaryPassword }`. It no longer does, and there is no longer a
JSON response at all.

- **No request body. The response is the PDF**, exactly as
  `POST /users/activation-slips` above — same `Content-Disposition`, same
  `Cache-Control: no-store`, same "errors still arrive as a normal envelope, so
  tell them apart by content type" rule. It is a one-slip document in the same
  format and the same language resolution; the filename identifies nobody
  (`activation-slip-<yyyymmdd>.pdf`).
- **What happens to the account**: its stored password is **cleared** and it
  returns to `PENDING_ACTIVATION`, and a fresh single-use activation code is
  issued, all in one transaction — so a failed render leaves the account
  untouched rather than stranding it with no password and no printed slip. The
  user cannot sign in at all until they redeem the code and choose a password;
  the expiry sweep will move the account to `ACTIVATION_EXPIRED` if the slip is
  never used, and another reset re-issues.
- **Every live session of that account is revoked**, as before. Clearing the
  password does not end a session on its own — a session carries its own
  already-authenticated principal.
- **Admin only** (§2.10, D-9/D-10), narrowed from `users.manage` alone to match
  the slip batch: this call mints a credential.
- **Refused with `409`** for a self-reset (an admin resetting their own account
  — use `POST /me/change-password`, which also revokes sessions, or ask another
  admin) and for an `INACTIVE` account (reactivate it first; a reset would
  silently undo the deactivation). `404` for an unknown user id. A `LOCKED`
  account is reset normally and comes back unlocked — a lockout is the most
  common reason a reset is asked for.
- Any activation code the account was already holding is retired by the reset,
  so exactly one code is usable afterwards (§2.7).
- **The plaintext code appears in the PDF and nowhere else** — not in the
  response headers, not in the audit rows (`user.adminResetCredential` on
  `user`, then `user.issueActivationCode`), not in a log line.

### 4.16 School Info

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/school/info` | Authenticated | School basic info (name, full name, Student Development Center, contact, etc.) |
| `PATCH` | `/school/info` | `sysadmin` role only | Edit school info — role-code check, not a permission, since `admin` and `sysadmin` hold identical permission grants post-role-merge (see §4.15's Roles table) and this must stay narrower than `admin` |

Example response:

```json
{
  "data": {
    "name": "Example Middle School",
    "fullName": "Example Middle School (Full Legal Name)",
    "studentDevCenter": "Student Development Center",
    "contact": "dev-center@example-township-school.edu",
    "academicYear": "2025-2026"
  }
}
```

`PATCH` accepts a partial body (`name`/`fullName`/`studentDevCenter`/`contact`, all optional — only provided fields change); no `version`/optimistic-locking field, since this is a single, rarely-edited row with exactly one class of editor. A non-`sysadmin` caller (including `admin`) gets `403`.

This endpoint returns low-frequency school-level configuration info; the frontend can fetch and cache it during app initialization. The difference from `/system/settings`: `/school/info` is available to all authenticated users and returns school display-level info; `/system/settings` is for administrators and returns configurable system parameters.

### 4.17 System Version

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/system/version` | Authenticated | Deployed product version |

Example response:

```json
{
  "data": {
    "version": "0.1.1"
  }
}
```

`version` is a semver string (`MAJOR.MINOR.PATCH`, optionally `-SNAPSHOT`) sourced from the backend build itself (Maven's `spring-boot-maven-plugin` `build-info` goal, which stamps `pom.xml`'s `<version>` into the jar at build time) — never hand-maintained in application code. See `Docs/operations/VersionManagement.md` for the full versioning/release scheme, including how the frontend's own `package.json` version is kept in lockstep even though this endpoint (not the frontend bundle) is the runtime source of truth for anything displayed in the UI. Prefix with `v` for display (`v0.1.1`) — the API returns the bare number.

---

### 4.18 School Lifecycle Configuration

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/lifecycle/config` | Authenticated | The school-year facts that are configured rather than stored (added 2026-09-20, issue 114) |

Example response:

```json
{
  "data": {
    "exitGradeLevels": [8, 12],
    "academicYearStart": "09-01"
  }
}
```

`exitGradeLevels` are the grade levels that **end a stage** — finishing one graduates the student rather than promoting them (`14-AnnualRollover.md` §2.1), ascending. `academicYearStart` is `MM-DD`: the day the year turns over, and therefore the day promotion and graduation run.

This exists so no client restates either value. A screen offering "grade 8 graduates / grade 12 graduates" would otherwise carry a second literal pair that nothing keeps in step with the process that actually graduates people, and a School Info card printing "September 1" becomes a lie the moment a deployment configures something else.

**Read-only, and there is deliberately no write path.** Both values come from the deployment's environment rather than the database (`TIMEBANK_ACADEMIC_YEAR_START`, see `Docs/operations/DeploymentEnv.md`), so a `PATCH` would be a control that saves nothing. `isAuthenticated()` rather than a permission, matching `GET /academic-years`: it is the same class of structural fact about the school, names no person and no record, and gating it would only mean some staff member's grade filter silently loses its graduate options.

---

## 5. Permission Matrix

| Resource/Action | Student | Teacher | SDC Admin | System Admin |
| --- | --- | --- | --- | --- |
| Browse projects | Visible scope | Visible scope | School-wide | School-wide |
| Create/publish projects | No | Authorized scope | Yes | Per config |
| Submit event proposals | Own | No | Proxy requires special permission | No |
| Approve event proposals | No | Evaluate/recommend within data scope; approve per permission | School-wide approval | Read-only by default |
| Register | Own | No | Proxy requires special permission | No |
| Submit service records | Own | Proxy requires special permission | Proxy requires special permission | No |
| Review records | No | Within data scope | School-wide | Read-only by default |
| Appeal decisions | No | Per authorization | Yes | Read-only by default |
| View accounts | Own | Read-only within data scope | School-wide | Per permission |
| Adjust/reverse accounts | No | No | Special permission | Special permission |
| Rule management | No | Read-only | Yes | System parameters primarily |
| Import/export | Own data not exported separately | Within scope | School-wide | System-level |
| User/role management | No | No | Partial users | Yes |
| Generate activation slips | No | No | Yes | Yes |
| Audit logs | Own timeline | In-scope business logs | School-wide business logs | All |

The permission matrix is for default design; final configuration is determined by school role assignments.

---

## 6. Phase 3 Reserved APIs: POV

The following endpoints may return `404` or a clear `FEATURE_DISABLED` in Phase 2; when enabled, they still use `/api/v1`:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/pov-sessions` | Create on-site POV session |
| `GET` | `/pov-sessions/{id}` | POV status |
| `POST` | `/pov-sessions/{id}/check-ins` | Submit GPS/timestamp |
| `POST` | `/pov-sessions/{id}/media` | Associate desensitized materials |
| `POST` | `/pov-sessions/{id}/witness-invitations` | Create witness QR code/short-lived credentials |
| `POST` | `/witness-confirmations` | Witness confirmation |
| `POST` | `/pov-sessions/{id}/peer-confirmations` | Group member mutual verification |
| `POST` | `/pov-sessions/{id}/guardian-endorsements` | Guardian endorsement |
| `POST` | `/pov-sessions/{id}/submit` | Submit for POV review |
| `GET` | `/pov-review-queue` | Web admin POV queue |
| `GET` | `/service-records/{id}/risk-assessment` | AI/rule risk results |
| `POST` | `/verification-calls` | Create random spot-check task |
| `POST` | `/verification-calls/{id}/result` | Record verification result |
| `GET` | `/attestations/{id}` | Evidence storage status |

POV ultimately generates or links to a standard `ServiceRecord`; after review approval, crediting still occurs through standard account transactions.

---

## 7. Phase 4 Reserved APIs

### 7.1 Redemption

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/redemption-items` | Student-visible redemption catalog |
| `GET` | `/redemption-items/{id}` | Redemption item detail |
| `POST` | `/redemption-orders` | Create redemption request |
| `GET` | `/redemption-orders` | Own or admin-scope orders |
| `POST` | `/redemption-orders/{id}/cancel` | Cancel |
| `POST` | `/redemption-orders/{id}/decisions` | Approve/reject |
| `POST` | `/redemption-orders/{id}/fulfill` | Mark fulfilled |

### 7.2 Borrowing

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/borrowing/eligibility` | Query own eligibility and limit |
| `POST` | `/borrowing-applications` | Create application |
| `GET` | `/borrowing-applications` | Query applications |
| `POST` | `/borrowing-applications/{id}/decisions` | Approve/reject |
| `POST` | `/borrowing-applications/{id}/repayments` | Record repayment |

### 7.3 Value-Added & Recognition

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/recognition-levels` | Recognition tiers |
| `GET` | `/students/{id}/recognitions` | Student recognitions |
| `GET` | `/bonus-rules` | Value-added rules |
| `POST` | `/bonus-rules` | Create rule |
| `POST` | `/bonus-rules/{id}/publish` | Publish rule |
| `GET` | `/bonus-awards` | Bonus award transactions |

These capabilities are controlled by feature flags. When disabled, the frontend does not display action entry points, and the backend rejects mutation requests.

---

## 8. Caching & Performance Conventions

- Low-frequency resources such as dictionaries, grades, and categories may use `ETag`/`If-None-Match`.
- Account, review status, and permission data must not be cached long-term.
- Lists must be paginated; large data exports use async jobs.
- Dashboards allow short-lived aggregate caching and return `generatedAt`.
- API should return data sufficient to complete page tasks, avoiding obvious row-by-row N+1 requests.

---

## 9. Audit & Privacy

- Each mutation request is associated with `requestId`, operator, time, source, and business object.
- Login, permission changes, review decisions, account changes, import/export, and rule publication must write audit logs.
- API must not return unnecessary phone numbers, emails, or guardian information in list responses.
- Sensitive fields are desensitized by permission; download URLs are short-lived.
- Frontend must not write access tokens, sensitive materials, or complete error objects to browser logs.
- Audit logs cannot be modified or deleted through regular admin APIs.

---

## 10. Sources of Truth & Change Management

### 10.1 Sources of Truth and Precedence

The earlier requirement to hand-produce an OpenAPI file *before* implementation is **withdrawn**. It was written when no client existed; a complete React frontend now exists and has already settled the question that a pre-written spec was meant to settle — but only for part of the surface (§13.2). The contract is therefore layered, with each artifact authoritative for what it has actually proven.

| Artifact | Authoritative for | Not authoritative for |
| --- | --- | --- |
| **This document** | Behaviour: resources, status machines, permissions and data scope, transaction boundaries, error codes, idempotency and concurrency, **request bodies** (§13.4) | Exhaustive response field lists |
| **`frontend/src/app/api/types.ts`** — the payload types, lifted unchanged out of `frontend/src/app/mock/data.ts` | **Response payload shapes**: field names, optionality, and units for every endpoint the frontend consumes | Any behaviour, status semantics, or permission rule |
| **`openapi.json`** emitted by springdoc from the running backend | Nothing. A generated byproduct for tooling and for showing an API diff during review | Everything |

**Precedence.** Behaviour conflict → this document wins. Response-field conflict → the types module wins. Where the two genuinely contradict, this document wins and the types module is corrected **in the same pull request**. `openapi.json` never arbitrates: it is regenerated, never hand-edited, and never a merge gate.

**Why not spec-first.** The contract's read surface has been exercised by a working UI that renders every field it receives, which is stronger evidence than a spec review. **Why not code-first-as-arbiter.** That would make the arbiter whatever the backend happens to emit, forcing an existing, working frontend to chase it.

**Revisit when a second client appears.** Once the Phase 3 Mini Program consumes the same API, two consumers can disagree and the frontend types module stops being a neutral arbiter. At that point, generating shared types from `openapi.json` becomes correct. Nothing here blocks that move.

### 10.2 Change Rules

- Adding optional fields is a compatible change.
- Deleting fields, changing types, changing enum semantics, or tightening existing behavior is a breaking change.
- Breaking changes go into a new API major version.
- When enums add new values, clients must have unknown-value fallback rendering (render the raw code).
- **A contract change lands as one pull request** touching this document, its `_CN` counterpart, the types module, the API client, and the backend implementation. A change to only one of them is the definition of drift.

### 10.3 Definition of Done

An endpoint is complete only when all of the following are met:

- Behaviour matches this document, and the response matches the types module.
- Permission and data-scope tests pass, including the invisible-`404` case.
- Normal, validation failure, forbidden, concurrent conflict, and duplicate request behaviours are all defined and tested.
- Audit and idempotency requirements are implemented per risk level.
- **The frontend screen that consumes it renders correctly against the real backend with the mock client disabled.** This is the acceptance gate that replaces the former OpenAPI validation gate — a rendered page is a stricter check than a schema comparison, because it also catches missing display names, wrong units, and unusable pagination.

---

## 11. Phase 2 API Acceptance Scenarios

1. Student submits an event proposal; teacher requests changes; student resubmits; admin approves and generates an official project draft.
2. Admin or responsible teacher publishes the project generated from the proposal; other students query and register; teacher processes the registration list.
3. Student creates a draft, uploads materials, and submits a service record.
4. Teacher returns the record; student supplements and resubmits; teacher approves.
5. Review approval generates account transaction and updates progress in a single transaction.
6. Student initiates an appeal; Student Development Center processes it and produces a complete timeline.
7. Admin imports a historical ledger, views validation errors, and confirms valid data.
8. Teacher can only access authorized grades/classes; out-of-scope access returns `403` or invisible `404`.
9. Duplicate submission, duplicate approval, duplicate review, and duplicate account adjustments do not generate duplicate projects or transactions.
10. Concurrent edits return clear conflicts; other users' modifications are not silently overwritten.
11. Web passes all end-to-end acceptance without WeChat or other IM integration.
12. Every screen renders correctly in both `zh-CN` and `en`, with no localized enum or status value crossing the wire and no dictionary name returned empty for the non-default language.

---

## 12. Frontend Implementation Conventions

### 12.1 API Client Layer

The frontend accesses all backend data through a unified API Client layer (`src/app/api/client.ts`). The API Client is grouped by resource domain (e.g., `api.projects`, `api.serviceRecords`, `api.accounts`), with each method corresponding to a REST endpoint, returning `Promise<T>`.

In the current phase, the API Client uses Mock data as its foundation, simulating network latency via `delay()`. When the backend is ready, method implementations can be replaced with `fetch` calls without any component-level changes.

### 12.2 Data Fetching Pattern

Components use `useState` + `useEffect` pattern to fetch data, without directly importing Mock constants:

```tsx
// ✅ Correct: Fetch through API Client
const [projects, setProjects] = useState<Project[]>([]);
useEffect(() => {
  api.projects.list().then(setProjects).catch(() => {});
}, []);

// ❌ Wrong: Directly importing Mock data
import { MOCK_PROJECTS } from '../../mock/data';
```

When loading state is needed, return a loading placeholder when data is `null` or empty array:

```tsx
const [progress, setProgress] = useState<StudentProgress | null>(null);
useEffect(() => {
  api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
}, [user?.studentId]);

if (!progress) return <div className="p-4 lg:p-6">Loading...</div>;
```

### 12.3 Type Sources

TypeScript types (e.g., `Project`, `ServiceRecord`, `Account`) are currently imported from `../../mock/data`, which doubles as the type definition source. Per §10.1 these types are the **authoritative response shapes**, so they are lifted unchanged into `frontend/src/app/api/types.ts`, leaving `mock/data.ts` holding fixtures only. Components and the API client then import types from the API layer rather than from the mock layer, and deleting the mock fixtures later removes no type information.

### 12.4 Dashboard Aggregation Endpoints

The student home page uses `GET /dashboards/student` to fetch progress, account, recent records, and recommended projects in one call. The admin dashboard uses `GET /dashboards/admin` to fetch statistics, pending review queue, and active projects. Aggregation endpoints are only for home pages; detail pages still use resource endpoints.

### 12.5 Organization & School Info

Organization lists are fetched via `api.organizations.list()`, and school info is fetched via `api.school.getInfo()`. These low-frequency data can be fetched on component mount and cached in component state.

---

## 13. Frontend Consumption Map

This section exists so the backend can be built against what the frontend actually consumes, rather than against an idealized reading of §3–§4. It records the mapping, the shortcuts the mock takes that must **not** be copied, and every known divergence with its resolution.

### 13.1 API Client Method → Endpoint

| Client method | Endpoint | Wired in a screen? |
| --- | --- | --- |
| `auth.login` | `POST /auth/login` | Yes |
| `auth.me` | `GET /me` | No |
| `auth.logout` | `POST /auth/logout` | No |
| `school.getInfo` | `GET /school/info` | Yes |
| `school.updateInfo` | `PATCH /school/info` | Yes |
| `projects.list` | `GET /projects` | Yes |
| `projects.get` | `GET /projects/{id}` | No |
| `projects.create` / `update` | `POST /projects` / `PATCH /projects/{id}` | No |
| `projects.publish` / `archive` / `cancel` | `POST /projects/{id}/publish` / `/archive` / `/cancel` | No |
| `eventProposals.list` | `GET /event-proposals` | Yes |
| `eventProposals.get` | `GET /event-proposals/{id}` | No |
| `eventProposals.create` / `update` | `POST /event-proposals` / `PATCH /event-proposals/{id}` | No |
| `eventProposals.submit` / `withdraw` | `POST /event-proposals/{id}/submit` / `/withdraw` | No |
| `eventProposals.review` | `POST /event-proposals/{id}/reviews` | No |
| `applications.list` | `GET /applications` | No |
| `applications.create` | `POST /projects/{projectId}/applications` | No |
| `applications.cancel` / `confirm` | `POST /applications/{id}/cancel` / `/confirm` | No |
| `serviceRecords.list` | `GET /service-records` | Yes |
| `serviceRecords.get` | `GET /service-records/{id}` | No |
| `serviceRecords.create` / `update` / `delete` | `POST` / `PATCH` / `DELETE /service-records[/{id}]` | No |
| `serviceRecords.submit` | `POST /service-records/{id}/submit` | No |
| `reviewQueue.list` | `GET /review-queue` | Yes |
| `reviewQueue.summary` | `GET /review-queue/summary` | No |
| `reviewQueue.decide` | `POST /service-records/{id}/reviews` | No |
| `accounts.get` | `GET /accounts?filter[studentId]=…` | Yes |
| `accounts.transactions` | `GET /accounts/{id}/transactions` | Yes |
| `students.list` | `GET /students` | Yes |
| `students.get` | `GET /students/{id}` | No |
| `students.progress` | `GET /students/{id}/progress` | Yes |
| `organizations.list` | `GET /organizations` | Yes |
| `notifications.list` | `GET /notifications` | Yes |
| `notifications.unreadCount` | `GET /notifications/unread-count` | Yes |
| `notifications.markRead` / `markAllRead` | `PATCH /notifications/{id}` / `POST /notifications/read-all` | Yes |
| `dashboards.student` / `admin` | `GET /dashboards/student` / `/admin` | Yes |
| `reports.data(academicYearId?)` | Composes `GET /reports/participation`, `/completion`, `/service-hours`, `/top-projects`, passing the same optional `academicYearId` to each | Yes |
| `users.list` | `GET /users` | No |

`api.accounts.get(studentId)` takes a student ID while the account endpoints are keyed by account ID. This is resolved in the client — filter by `studentId`, then use the returned `accountId` — rather than by adding a student-scoped account endpoint.

`serviceRecords.list`'s admin "view this student's records" caller (Student Management's Records button) passes `filter: { studentId }` through the same method/endpoint every other service-record screen uses — same resolution as `accounts.get` above, not a second client method.

### 13.2 What the Frontend Has and Has Not Validated

**Validated: the read surface.** Every page fetches through `api.*` and none imports mock fixtures directly, so response shapes for the "Yes" rows above are exercised by screens that render every field they receive. Those shapes are authoritative (§10.1).

**Not validated: the entire write surface.** No component calls any create, update, submit, review, publish, confirm, or cancel method; those actions currently end in a toast. **No request body has ever been constructed or sent.** File upload does not exist in the frontend at all — evidence appears as pre-built objects carrying a direct `url`.

The practical consequence: the request bodies in §13.4 and the upload flow in §4.6 carry **no** frontend validation and are where integration gaps will concentrate. They must be settled before the frontend wires its mutations, and the wiring is a prerequisite for review-loop acceptance, not follow-up work.

### 13.3 Mock Shortcuts That Are Not the Target Design

The mock client takes deliberate shortcuts that must not be read as contract behaviour:

| Mock shortcut | Real requirement |
| --- | --- |
| `reviewQueue.decide` sets record status and splices the queue item client-side | One backend transaction: review event, status, account transaction, progress, notification (§4.7) |
| Lists return bare arrays | `{data, page, meta}` envelope (§2.5); the client unwraps `data` and exposes `page` where the UI paginates |
| Approving a proposal only flips its status | Approval atomically creates the official project draft and backfills `approvedProjectId` (§4.3) |
| `MOCK_USERS` carry a `password` field | Never present in any response |
| Student list fixtures carry `guardianName` / `guardianContact` | Superseded: the fields no longer exist anywhere, dropped in `V26` (§4.9) |
| `EvidenceFile.url` is a permanent direct link | Short-lived URL issued by `GET /files/{id}` after a permission check, and audited (§4.6) |
| Category names hold i18n keys (`mock.categories.cat_campus`) | Dictionary names are localized server-side; the client keys off `categoryId` (§2.10) |
| Filtering and searching happen in the browser over the full array | Server-side `filter[...]` / `search` / `sort` with pagination (§2.7) |

### 13.4 Request Bodies

Because no write has been exercised by a client, request shapes are specified here rather than inferred:

**The general rule:** a request body is the **writable subset of the resource's core fields** listed in §3. Three field classes are rejected with `422 VALIDATION_FAILED` rather than silently ignored, so a client never believes it set something it did not:

- **Status fields.** Transitions go through action endpoints (`/submit`, `/publish`, `/reviews`), never through `PATCH`.
- **Denormalized display names** (§2.11 rule 1) — `projectTitle`, `studentName`, `categoryName`, and the rest are outputs.
- **Server-computed values** — `enrolledCount`, `durationMinutes` where it is derived from `startAt`/`endAt`, `creditedUnits`, `balanceAfter`, timestamps, and every `my*` field. `version` (and its per-resource names `recordVersion`/`proposalVersion`) follows the create/update split instead of a blanket rejection: rejected at creation (the server assigns `1`), **required** as a body field on updates and decisions (§2.8) — never an `If-Match` header.

**Additional per-endpoint requirements:**

| Endpoint | Body requirements |
| --- | --- |
| `POST /service-records` | `sourceType` plus its required companions (§3.4): `projectId` for `project`, `organizationId`/`organizationName` and supervisor contact for `external`. `serviceDate`, `startAt`, `endAt`, `serviceCategoryId`, `description`, evidence file IDs, `includesTravelTime` |
| `POST /service-records/{id}/submit` | Empty body; `Idempotency-Key` required. All submission validation (§4.5) runs here, not at create |
| `POST /service-records/{id}/reviews` | `decision`, `reasonCode`, `comment`, `recordVersion`; `Idempotency-Key` required |
| `POST /event-proposals/{id}/reviews` | `decision`, `reasonCode`, `comment`, `proposalVersion`; `Idempotency-Key` required |
| `POST /projects/{projectId}/applications` | Empty or `note` only — the student is the principal, never a body field |
| `POST /projects`, `PATCH /projects/{id}` | Writable subset of §3.1's core fields (never `status`, which is action-endpoint only). `PATCH` additionally requires `version` |
| `POST /event-proposals`, `PATCH /event-proposals/{id}` | Writable subset of §3.2's core fields (never `status`). `PATCH` additionally requires `version` |
| `POST /accounts/{id}/adjustments` | `amount` in signed centi-coins, `reasonCode`, `memo`; `Idempotency-Key` required |
| `POST /account-transactions/{id}/reverse` | `reasonCode`, `memo` — no `amount`; the reversal mirrors the original transaction's amount. `Idempotency-Key` required |
| `POST /files/upload-intents` | `filename`, `mimeType`, `sizeBytes`, `purpose` (§4.6) |
| `PATCH /me/profile` | Self-service fields only, including `preferredLanguage` (§2.10) |

### 13.5 Divergences and Their Resolution

Known differences between the frontend as built and this contract. The resolution principle: **the contract wins on behaviour, statuses, units, and privacy; the frontend wins on response shape.**

| # | Item | Frontend today | Resolution | Change lands in |
| --- | --- | --- | --- | --- |
| 1 | Proposal status | `needsRevision`; no `underReview` | Contract wins on `changesRequested`. The frontend's *absence* of `underReview` turned out to be right for a different reason — it was removed from the contract entirely in 2026-09-16 (§3.2, issue 080) | Frontend |
| 2 | Record status | No `appealPending` | Contract wins; add when appeals are wired (§3.4) | Frontend |
| 3 | Coin amounts | `balance: 6` meaning 6 coins | Contract wins: integer centi-coins on the wire, formatted for display (§2.1) | Frontend formatter |
| 4 | Duration | `completedHours` etc. duplicated alongside minutes | Contract wins: minutes only on the wire; hours derived in the UI (§2.1) | Frontend |
| 5 | Category names | Hold i18n keys | Contract wins: localized `name` from the server, keyed by `categoryId` (§2.10) | Frontend + fixtures |
| 6 | Guardian fields in student lists | Present | Superseded: columns dropped entirely in `V26`, not merely hidden (§4.9) | Resolved |
| 7 | Evidence URLs | Permanent direct links | Contract wins: short-lived, permission-checked (§4.6) | Frontend upload wiring |
| 8 | List envelope / pagination | Bare arrays, client-side filtering | Contract wins (§2.5, §2.7) | Frontend client layer |
| 9 | Display names alongside IDs (`projectTitle`, `studentName`, …) | Present throughout | **Frontend wins** — now codified as §2.11 | Contract (done) |
| 10 | `enrolledCount`, `myApplicationStatus`, `daysWaiting`, `hasAnomaly`, `priority` | Present | **Frontend wins** — server-computed per §2.11. In Phase 2 anomaly flags are rule-based, not AI-derived | Contract (done) |
| 11 | Aggregate payloads (`AdminStats`, `ReportData`, `StudentProgress`, `ReviewQueueItem`) | Defined in the frontend types only | **Frontend wins** — the types module is authoritative for these shapes (§10.1) | Types module |
| 12 | Proposal `timeline` embedded in the resource | Embedded | Both: detail responses may embed the timeline; list responses must not, and `/timeline` remains the full history (§4.3) | Contract (done) |
| 13 | Reports | One `reports.data()` blob | Four report endpoints composed in the client; `/reports/top-projects` added (§4.13) | Contract (done) + frontend |
| 14 | Language preference | `localStorage` only | Server-persisted `preferredLanguage`, with the local value as a cache (§2.10) | Frontend + backend |
