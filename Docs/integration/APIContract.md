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

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/cases` | `cases.read` | Query own or in-scope cases |
| `POST` | `/cases` | `cases.create` | Create appeal/supplement draft |
| `GET` | `/cases/{id}` | `cases.read` | Detail |
| `PATCH` | `/cases/{id}` | Owner & draft/supplement state | Edit |
| `POST` | `/cases/{id}/submit` | Owner | Submit |
| `POST` | `/cases/{id}/decisions` | `cases.decide` | Admin processing |
| `GET` | `/cases/{id}/timeline` | `cases.read` | Timeline |

### 4.9 Students, Classes, Grades & Academic Years

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/students` | `students.read` | Student list |
| `POST` | `/students` | `students.manage` | Create student |
| `GET` | `/students/{id}` | Owner/`students.read` | Student detail |
| `PATCH` | `/students/{id}` | `students.manage` | Update authoritative profile |
| `POST` | `/students/{id}/activate` | `students.manage` | Activate |
| `POST` | `/students/{id}/deactivate` | `students.manage` | Deactivate |
| `GET` | `/students/{id}/progress` | Owner/`progress.read` | Progress tracking |
| `GET` | `/students/{id}/service-records` | Owner/`students.read` | Service history |
| `GET` | `/academic-years` | Authenticated | Academic years |
| `GET` | `/grades` | Authenticated | Grades |
| `GET` | `/classes` | Authenticated | Classes |

### 4.10 Organizations & Service Categories

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/organizations` | `organizations.read` | Organization list |
| `POST` | `/organizations` | `organizations.manage` | Create |
| `GET` | `/organizations/{id}` | `organizations.read` | Detail |
| `PATCH` | `/organizations/{id}` | `organizations.manage` | Update |
| `POST` | `/organizations/{id}/archive` | `organizations.manage` | Archive |
| `GET` | `/service-categories` | Authenticated | Service categories |
| `POST` | `/service-categories` | `rules.manage` | Create category |
| `PATCH` | `/service-categories/{id}` | `rules.manage` | Update category |

### 4.11 Accounts & Transactions

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/accounts` | `accounts.read` | In-scope account list |
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
| `POST` | `/exports` | `exports.create` | Create export job |
| `GET` | `/exports/{jobId}` | Creator/admin | Query export job |
| `GET` | `/exports/{jobId}/download` | Creator/admin | Get short-lived download URL |

Dashboard endpoints are for homepage aggregation only; business details are still obtained through resource endpoints. All page data must not be concentrated into a single "mega endpoint."

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

Imports must validate before commit. Duplicate imports are prevented by source file hash, business unique keys, and idempotency keys working together.

### 4.15 Users, Roles & Audit

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/users` | `users.read` | User list |
| `POST` | `/users` | `users.manage` | Create user |
| `PATCH` | `/users/{id}` | `users.manage` | Update user |
| `POST` | `/users/{id}/unlock` | `users.manage` | Unlock |
| `POST` | `/users/{id}/reset-password` | `users.manage` | Admin-triggered reset |
| `GET` | `/roles` | `roles.read` | Role list |
| `POST` | `/roles` | `roles.manage` | Create role |
| `PATCH` | `/roles/{id}` | `roles.manage` | Update role permissions |
| `GET` | `/permissions` | `roles.read` | Permission code catalog |
| `GET` | `/audit-logs` | `auditLogs.read` | Operation audit |
| `GET` | `/login-logs` | `securityLogs.read` | Login logs |
| `GET` | `/system/settings` | `settings.read` | System parameters |
| `PATCH` | `/system/settings` | `settings.manage` | Update allowed parameters |

### 4.16 School Info

| Method | Path | Permission | Description |
| --- | --- | --- | --- |
| `GET` | `/school/info` | Authenticated | School basic info (name, Student Development Center, contact, etc.) |

Example response:

```json
{
  "data": {
    "name": "Example Middle School",
    "studentDevCenter": "Student Development Center",
    "contact": "dev-center@example.edu.cn",
    "academicYear": "2025-2026"
  }
}
```

This endpoint returns low-frequency school-level configuration info; the frontend can fetch and cache it during app initialization. The difference from `/system/settings`: `/school/info` is available to all authenticated users and returns school display-level info; `/system/settings` is for administrators and returns configurable system parameters.

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

## 10. Contract Delivery & Change Management

### 10.1 OpenAPI

Before backend implementation, a machine-readable OpenAPI 3.x file should be generated from this document, including at minimum:

- Paths, methods, permissions, and request/response schemas.
- Enums, statuses, and error codes.
- Example data.
- Pagination, idempotency, and concurrency headers.
- Phase 3 and Phase 4 endpoint markers.

The React frontend API Client and Mock Server should be generated from or validated against the same OpenAPI contract.

### 10.2 Change Rules

- Adding optional fields is a compatible change.
- Deleting fields, changing types, changing enum semantics, or tightening existing behavior is a breaking change.
- Breaking changes go into a new API major version.
- When enums add new values, the frontend must have unknown-value fallback rendering.
- API changes must simultaneously update OpenAPI, Mock, contract tests, and this document.

### 10.3 Definition of Done

An endpoint is considered complete only when all of the following are met:

- OpenAPI is defined and passes validation.
- Permission and data scope tests pass.
- Normal, validation failure, forbidden, concurrent conflict, and duplicate request behaviors are clearly defined.
- Frontend does not depend on undocumented fields.
- Audit and idempotency requirements are implemented per risk level.

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

TypeScript types (e.g., `Project`, `ServiceRecord`, `Account`) are still imported from `../../mock/data` because the Mock data file also serves as the type definition source. Once backend OpenAPI-generated types are available, the import path can be switched to the generated type files.

### 12.4 Dashboard Aggregation Endpoints

The student home page uses `GET /dashboards/student` to fetch progress, account, recent records, and recommended projects in one call. The admin dashboard uses `GET /dashboards/admin` to fetch statistics, pending review queue, and active projects. Aggregation endpoints are only for home pages; detail pages still use resource endpoints.

### 12.5 Organization & School Info

Organization lists are fetched via `api.organizations.list()`, and school info is fetched via `api.school.getInfo()`. These low-frequency data can be fetched on component mount and cached in component state.
