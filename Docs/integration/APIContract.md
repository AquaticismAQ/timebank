# TimeBank · RESTful API Contract

> This document defines the top-level API contract required for frontend-driven implementation. It describes resource boundaries, endpoints, statuses, permissions, and general protocols, but does not replace backend database design or OpenAPI field-level specifications. Phase 2 endpoints are prioritized for implementation; Phase 3 and Phase 4 endpoints are reserved as stable extension namespaces.

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
| Amounts/Duration | No floating-point numbers; duration in minutes, time-coins in minimum accounting unit integers |
| Field Naming | JSON uses `camelCase` |
| API Versioning | URL major version; compatible fields are append-only, no reusing old semantics |

### 2.2 Authentication

Web recommends using Secure, HttpOnly, SameSite cookies for session management; if access tokens are used, they must be paired with short expiration, secure refresh, and revocation mechanisms.

Authentication endpoints:

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/auth/login` | Account-password login |
| `POST` | `/auth/logout` | End current session |
| `POST` | `/auth/refresh` | Refresh session |
| `POST` | `/auth/forgot-password` | Initiate password reset |
| `POST` | `/auth/reset-password` | Set new password with one-time token |
| `GET` | `/me` | Current user, roles, permissions, data scope, and feature flags |
| `PATCH` | `/me/profile` | Update self-service profile fields |
| `POST` | `/me/change-password` | Change password |

Login response must at least return:

```json
{
  "data": {
    "user": {
      "id": "usr_xxx",
      "displayName": "Example User",
      "roles": ["student"],
      "permissions": ["projects.read", "serviceRecords.create"]
    },
    "workspaces": ["student"],
    "featureFlags": {
      "pov": false,
      "redemption": false,
      "borrowing": false,
      "appreciation": false
    }
  }
}
```

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

- Editable resources return `version` or `ETag`.
- Updates include `If-Match` or `version`; conflicts return `409 STATE_CONFLICT`.
- Record creation, review decisions, account adjustments, and import confirmation support `Idempotency-Key`.
- Same idempotency key and request body return the same business result; same key with different body returns conflict.

### 2.9 Deletion Rules

- Submitted, reviewed, and credited business records cannot be physically deleted.
- Drafts can be deleted; base data is preferentially deactivated or archived.
- Account balances can only change through transactions; no direct balance modification endpoint is provided.

---

## 3. Core Resources & Statuses

### 3.1 Project `Project`

Core fields:

- `id`, `title`, `summary`, `description`
- `categoryId`, `organizationId`, `ownerId`
- `originProposalId`, `studentOrganizerIds`
- `startAt`, `endAt`, `location`
- `capacity`, `recognizedMinutes`
- `eligibleGrades`, `evidenceRequirements`
- `registrationOpenAt`, `registrationCloseAt`
- `status`, `version`, `createdAt`, `updatedAt`

Status:

```text
draft → published → registrationOpen → inProgress → completed → archived
              ↘ closed
```

Status is determined by both time and management actions; the frontend does not derive the final status on its own.

### 3.2 Event Proposal `EventProposal`

An event proposal is created by a student to apply for organizing a volunteer activity open to other students. Before approval, a proposal is not an official `Project`, does not appear in the Project Center, and cannot accept registrations.

Core fields:

- `id`, `proposerStudentId`, `coOrganizerStudentIds`
- `title`, `objective`, `description`, `expectedOutcome`
- `categoryId`, `plannedStartAt`, `plannedEndAt`, `location`, `format`
- `targetParticipants`, `capacity`, `volunteerTasks`
- `proposedSupervisorId`, `organizationId`, `externalContact`
- `safetyPlan`, `resourceNeeds`, `recruitmentPlan`
- `proposedRecognizedMinutes`, `evidencePlan`
- `status`, `currentRevision`, `assignedReviewerId`
- `approvedProjectId`, `version`, `createdAt`, `submittedAt`, `updatedAt`

Status:

```text
draft → submitted → underReview → approved
                   ↘ changesRequested → submitted
                   ↘ rejected
draft/submitted → withdrawn
```

Approval decisions:

- `requestChanges`
- `recommendApproval`
- `approve`
- `reject`

Whether a teacher can directly approve is determined by permissions and approval rules; when Student Development Center final review is required, the teacher uses `recommendApproval`. The approval action generates an official project draft and backfills `approvedProjectId` in the same transaction, so `approved` must always have a valid linked project. The official project must still be published by a teacher or administrator with publish permission.

### 3.3 Application `Application`

Status:

```text
pending → confirmed → completed
       ↘ waitlisted → confirmed
       ↘ rejected
confirmed → cancelled
```

### 3.4 Service Record `ServiceRecord`

Status:

```text
draft → submitted → underReview → approved
                   ↘ returned → submitted
                   ↘ rejected → appealPending
approved → corrected
```

Core fields:

- `id`, `studentId`, `projectId`, `sourceType`
- `serviceDate`, `startAt`, `endAt`, `durationMinutes`
- `location`, `organizationId`, `organizationName`
- `serviceCategoryId`, `description`
- `supervisor`, `evidenceFiles`
- `status`, `currentRevision`, `reviewSummary`
- `creditedUnits`, `accountTransactionId`
- `version`, `createdAt`, `submittedAt`, `updatedAt`

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
draft → submitted → inReview → resolved
                         ↘ needsInformation → submitted
                         ↘ rejected
```

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
| `POST` | `/projects/{id}/close` | `projects.close` | Close early |
| `POST` | `/projects/{id}/archive` | `projects.archive` | Archive |
| `GET` | `/projects/{id}/timeline` | `projects.audit.read` | Project change timeline |

Project status changes use action endpoints to avoid bypassing business validation via a generic `PATCH status`.

### 4.3 Student Event Proposals

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/event-proposals` | Owner/`eventProposals.read` | Students query own proposals; teachers/admins query approval queue within scope |
| `POST` | `/event-proposals` | `eventProposals.create` | Student creates proposal draft |
| `GET` | `/event-proposals/{id}` | Owner/`eventProposals.read` | Proposal detail |
| `PATCH` | `/event-proposals/{id}` | Proposer & editable | Edit draft or changes-requested proposal |
| `DELETE` | `/event-proposals/{id}` | Proposer & draft only | Delete draft |
| `POST` | `/event-proposals/{id}/submit` | Proposer | Submit for approval |
| `POST` | `/event-proposals/{id}/withdraw` | Proposer/admin permission | Withdraw or request withdrawal |
| `GET` | `/event-proposals/{id}/revisions` | Owner/`eventProposals.read` | Revision history |
| `GET` | `/event-proposals/{id}/timeline` | Owner/`eventProposals.read` | Approval timeline |
| `POST` | `/event-proposals/{id}/reviews` | `eventProposals.review` | Request changes, recommend approval, approve, or reject |
| `POST` | `/event-proposals/{id}/assign` | `eventProposals.assign` | Assign or transfer reviewer |
| `GET` | `/event-proposal-queue/summary` | `eventProposals.review` | Pending counts and approval SLA |

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
| `GET` | `/service-records` | `serviceRecords.read` | Query own or in-scope records |
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
