# TimeBank · Frontend Design

> This document defines the top-level functionality, page structure, and interaction flows for the Phase 2 Web frontend of the TimeBank system, while reserving frontend entry points for Phase 3 POV WeChat Mini Program and Phase 4 value expansion. This document does not include component code, visual mockups, or specific API field definitions; interface boundaries are defined in `APIContract.md`.

---

## 1. Build Objectives

Phase 2 adopts a **Frontend-Driven** implementation approach: first determine user tasks, page structures, state, and interface requirements, then have the backend provide capabilities around a stable API contract.

The frontend must meet the following objectives:

- Run the complete workflow of "project publication — student registration — service record — review — time-coin crediting — progress tracking — appeal" using only Web.
- Provide clearly separated **Student Portal** and **Admin Portal**; the Admin Portal displays different features based on administrator, Student Development Center, and teacher permissions.
- Implement using **React + Tailwind CSS**, with a mobile-first responsive design while ensuring desktop management efficiency.
- Phase 2 does not depend on WeChat, Enterprise WeChat, DingTalk, or other IM platforms.
- All business capabilities are obtained through versioned **RESTful APIs**; business rules are not duplicated in the frontend.
- Reserve navigation, routing, permissions, and feature toggles for Phase 3 POV and Phase 4 redemption, borrowing, and value-added features.
- Modern style, Mobile first, theme based UI support dark/light time based auto/manual switch and system mode
- Each table/list should have a search, sort and filter functionality. Use card view for better mobile experience if needed like the table have too many columns.
- Text input area should have default height(10 rows), monospace font, auto line wrap and vertical scroll

---

## 2. Technical & Interaction Principles

### 2.1 Technology Boundaries

| Item | Convention |
| --- | --- |
| Frontend framework | React |
| Styling system | Tailwind CSS |
| Device strategy | Mobile First, progressively enhanced to tablet and desktop |
| API | RESTful API, unified `/api/v1` version prefix |
| Authentication | Web session or short-lived access token, managed by backend |
| Permissions | Backend permissions are the final authority; frontend only handles hiding, disabling, and guiding based on permissions |
| Files | Submitted via controlled upload endpoints or object storage short-lived upload credentials |
| Notifications | Phase 2 uses in-app notifications, no IM dependency |
| Extension control | Feature Flag + permission codes + phase configuration |

### 2.2 Design Principles

- **Task-first**: The homepage first displays what the user should do next, rather than stacking statistics.
- **Mobile-first**: High-frequency student operations are prioritized for one-handed use, weak networks, and small screens; teacher batch review is enhanced on desktop.
- **Progressive disclosure**: Show only essential fields by default; advanced filters, audit information, and rule configurations expand on demand.
- **State visibility**: Projects, registrations, service records, reviews, and account transactions all clearly display current status, next step, and responsible party.
- **Controlled irreversible actions**: Rejection, project closure, account reversal, and similar operations require confirmation and a reason.
- **Draft safety**: Long forms support auto-save drafts, leave warnings, and retry on failure.
- **Traceability**: Detail pages uniformly provide a timeline showing submission, return, supplement, review, and crediting processes.
- **Accessibility & readability**: Color is not the sole status indicator; forms have clear labels, error descriptions, and keyboard operability.

---

## 3. User Roles & Portals

### 3.1 Roles

| Role | Primary Scope |
| --- | --- |
| Student | Browse projects, register, submit service records, view reviews and account, request retroactive entry or appeal |
| Teacher | Manage authorized projects, review records for assigned grades/classes, view statistics within their data scope |
| Student Development Center Administrator | Manage school-wide projects, organizations, reviews, rules, accounts, and statistics |
| System Administrator | Manage accounts, roles, permissions, system parameters, data import/export, and audit logs |

The four rows above are the seeded defaults, not a closed set — Student, Student Development Center Administrator ("Admin"), and System Administrator are protected (undeletable) roles, while Teacher and any additional school-defined staff role are ordinary, manageable roles created and configured from the Admin Portal's Roles screen (`07-RoleFinetune.md`). System Administrator itself is intentionally not exposed in that screen this round — it exists and functions normally, just isn't yet manageable from the UI.

Each user holds exactly one role (`07-RoleFinetune.md` §2.5, retired the earlier multi-role/portal-picker design below). After login:

- A student-role account enters the Student Portal directly.
- Any other role (teacher, Student Development Center Administrator, System Administrator, or a school-defined custom role) enters the Admin Portal directly.

There is no portal-selection page and no workspace switching — a role determines exactly one portal, permanently, for the life of the account.

### 3.2 Login Portal

The unified login page provides:

- Student ID / Employee ID / school email or system account input.
- Password input with show/hide toggle.
- "Remember this device" option.
- Forgot password link.
- Privacy policy, terms of use, and minor's information processing notice.
- Login failure, account lockout, password expiration, and system maintenance status prompts.

Phase 2 does not offer WeChat scan or other IM login. School unified identity authentication may be added as an optional capability later, but must not affect the independent availability of account-password login.

### 3.3 Post-Login Common Features

All roles share:

- Current user, role, and data scope display.
- In-app notification center.
- Personal profile and security settings.
- Change password, logout.
- Help and rules documentation.
- No-permission, session expired, network error, and system maintenance pages.

---

## 4. Global Information Architecture

### 4.1 Student Portal Navigation

Mobile bottom navigation is recommended to be fixed as:

1. **Home**
2. **Projects**
3. **Records**
4. **Account**
5. **Profile**

"Notifications", "Appeal/Retroactive Entry", "Progress" and other features are accessed via homepage cards and the "Profile" section. Phase 3 and Phase 4 entry points are inserted into relevant business areas via feature toggles, without arbitrarily increasing the number of bottom navigation items.

Desktop uses a left sidebar navigation, with content maintaining the same information architecture.

### 4.2 Admin Portal Navigation

The Admin Portal desktop uses a collapsible left sidebar:

1. Dashboard
2. Projects & Registration
3. Review Center
4. Students & Organizations
5. Time-Coin Accounts
6. Statistics & Reports
7. Notifications
8. Data Management
9. System Administration

The mobile Admin Portal uses a top title bar, drawer navigation, and bottom primary action buttons. Complex batch tasks prompt users to switch to a larger screen, but must not completely block necessary single-item reviews.

---

## 5. Student Portal Pages

### 5.1 Student Home

The homepage aggregates:

- Current (and historical) academic year service hours, cumulative time-coins, and phase progress.
- To-do items: records pending supplement, registrations pending confirmation, event proposal feedback, appeal results, unread notifications.
- Projects open for registration or upcoming.
- Recent service records and review status.
- "Submit Service Record" and "Initiate Volunteer Activity" primary action buttons.
- Rule updates, deadlines, and school announcements.

### 5.2 Project Center

#### Project List

- Filter by service category, date, location, grade, and registration status.
- Support keyword search and "only show open registration".
- Project cards display name, time, location, remaining spots, credited hours, and registration deadline.
- Empty states distinguish between "no projects available" and "no filter results".

#### Project Detail

- Project introduction, tasks, time and location, organizer, supervisor, applicable grades, and safety information.
- Capacity, registration period, waitlist rules, proof requirements, and credited hours.
- Current student's registration status and available actions.
- Register, cancel registration, or join waitlist.

#### My Registrations

- Grouped by pending confirmation, registered, waitlisted, completed, and cancelled.
- Display registration status timeline and project change notifications.
- Directly initiate a linked service record after project completion.

### 5.3 Initiate Volunteer Activity

Students can submit volunteer activity proposals to the school, open to other students. This capability is for applying to become an activity initiator or student leader, and does not mean students can directly publish official projects.

#### My Activity Proposals

- Filter by draft, pending review, needs revision, approved, rejected, and withdrawn.
- Display activity name, planned date, personal role, current approval node, and last updated time.
- Approved proposals show the linked official project and subsequent preparation entry.

#### Create/Edit Activity Proposal

Uses a multi-step form:

1. **Activity Overview**: Name, public service goals, service category, activity description, and expected outcomes.
2. **Implementation Plan**: Time, location, activity format, estimated duration, headcount, target participants, and volunteer tasks.
3. **Organization**: Student initiator, co-organizers, proposed supervising teacher, partner organizations, and contact person.
4. **Safety & Resources**: Risk and safety measures, materials, venue, budget, or other support needs.
5. **Recruitment & Crediting**: Recruitment method, registration requirements, capacity, service proof method, and suggested credited hours.
6. **Confirm & Submit**: Complete summary, truthfulness declaration, and submission.

Behavior requirements:

- Support saving drafts, copying existing proposals, and previewing.
- After submission, enters teacher/admin approval; does not directly appear in the Project Center.
- Reviewers can request revisions; students create a new revised version on the original proposal and resubmit.
- Students can withdraw before approval begins; after approval begins, a withdrawal request must be submitted.
- Upon approval, the system generates an official project draft; the student initiator is recorded as the student leader, while the supervising teacher or administrator remains the official project owner.
- Official projects must be confirmed and published by a teacher or administrator with `projects.publish` permission.

#### Activity Proposal Detail

- Display complete proposal, revision history, and attachments.
- Display approval status, responsible teacher, feedback, and timeline.
- Available actions: edit draft, submit, revise per feedback, withdraw, copy proposal.
- After approval, display linked project, personal organizational responsibilities, and project preparation progress.

### 5.4 Service Records

#### Record List

- Filter by draft, pending review, returned for supplement, under review, approved, and rejected.
- Support search by date, project, organization, and academic year.
- Each record displays service date, project/service name, declared hours, review status, and crediting result.

#### Create/Edit Record

Uses a multi-step form. A record's source is one of two chooseable kinds, set once at creation and never changed afterward (`Docs/optimization/03-ServiceRecordFormOptimization.md`):

1. **Source**: Select "Registered Project" (a project the student holds a confirmed registration for) or "Self-Initiated" (student-organized, no project link). A third, historical-only source ("External," pre-dating this design) can still be viewed/edited/resubmitted on existing records but is never offered as a new choice.
2. **Service Information**: Date, start/end time (live duration preview), and description are common to both sources. For a **registered project**, organization, service nature, and location are read-only, pulled from the project itself — the student never types them. For **self-initiated**, the student fills in location, an optional organization name, and service nature directly. Both sources expose a travel-time disclosure toggle with a required reason field once toggled on.
3. **Proof Materials**: For a registered project, the supervisor (the project's own owner) is shown read-only. For self-initiated, the student picks a supervisor kind — a school staff member or another student (searched from the real directory, contact resolved server-side) or an external person (name and contact entered directly) — then uploads evidence files.
4. **Confirm & Submit**: Display complete summary (including the live duration preview), declaration, and submission action. The record's permanent tracking number is assigned on save and shown afterward in the list/detail views, not on this screen.

Behavior requirements:

- Provide draft saving and auto-save feedback.
- Frontend performs required field, format, time range, and file type validation.
- Backend business validation errors are mapped to specific steps and fields.
- After submission, enters read-only detail; returned records create a new revised version, with the original source locked (a returned project-sourced record cannot be turned into a self-initiated one on resubmission, or vice versa).

#### Record Detail

- Service information and material preview.
- Review status, review comments, and credited amount.
- Full-process timeline.
- Available actions: edit draft, supplement materials, withdraw, request appeal.
- Approved records display linked account transactions and evidence storage status (if enabled).

### 5.5 Progress Tracking

- Current grade annual requirements, phase requirements, completed, under review, and shortfall.
- Broken down by service category and academic year.
- Display rule source and last updated time.
- Provide action entry points for near-deadline shortfalls, rather than only showing warnings.

### 5.6 Time-Coin Account

- Current balance, cumulative earned, cumulative redeemed, frozen, and borrowed amounts.
- Transaction list: crediting, reversal, freeze, unfreeze, redemption, borrowing, repayment.
- Transaction detail shows source record, before/after balance, time, and description.
- If Phase 4 features are not enabled, redemption and borrowing entries display "Not yet available" instead of fake actionable buttons.

### 5.7 Retroactive Entry & Appeal

- Initiated from the relevant service record, avoiding duplicate entry of common information.
- Retroactive entry requires selecting a reason and uploading supplementary proof.
- Appeal requires filling in a dispute description.
- List and detail display processing status, review comments, and timeline.

### 5.8 Notifications & Personal Center

- In-app notifications categorized by review, project, account, rules, and system.
- Support read/unread and jump to related business detail.
- Personal center displays basic profile, class and grade, guardian information, privacy authorization status, and security settings.
- Authoritative fields such as student ID and grade cannot be directly modified by students; contact administrator required.

---

## 6. Teacher Portal Pages

Teachers use the Admin Portal but can only access authorized projects and grade/class data.

### 6.1 Teacher Dashboard

- Records pending initial review, pending re-review, and approaching timeout.
- Student activity proposals pending evaluation and proposal revisions pending confirmation.
- Registration and completion status for supervised projects.
- Progress overview and non-compliant student list for assigned students.
- Recent returned, rejected, and abnormal records.

### 6.2 Projects & Registration

- Create or maintain authorized project drafts.
- Submit project for publication or publish directly if authorized.
- View registration lists, waitlists, cancellations, and completion status.
- Batch export lists and import check-in/completion results.

### 6.3 Student Activity Proposal Review

- Pending review list sorted and filtered by submission time, planned activity date, grade, and review SLA.
- Proposal detail displays public service goals, implementation plan, student organizers, target participants, safety plan, resource needs, suggested hours, and all revision history.
- Evaluate educational value, public benefit, feasibility, safety, time crediting, venue conflicts, and resource needs.
- Actions: request revision, recommend approval, approve, reject, or transfer; without final approval authority, can only recommend approval and transfer to Student Development Center.
- Request revision or rejection must select a standardized reason and provide specific comments.
- After approval, take over or assist in refining the system-generated official project draft, confirming student leader responsibilities, supervising teacher, and publication plan.
- Approval timeline is visible to the student proposer, but internal notes are only visible to authorized personnel.

### 6.4 Review Workbench

#### Review List

- Default sort by urgency and submission time.
- Filter by status, grade, class, project, organization, date, and exception flags.
- Desktop supports batch selection; mobile focuses on single-item processing.

#### Review Detail

- Student and service information.
- Material preview.
- Duplicate record, abnormal hours, and rule validation alerts.
- Historical revisions, review, and account impact timeline.
- Actions: approve, return for supplement, reject, transfer for re-review.
- Each decision must record review comments; rejection must select a standardized reason.

### 6.5 Student Progress & Reports

- View student list and progress within permission scope.
- View read-only student service history and account transactions.
- Generate class/grade statistics and export.
- Send in-app reminders to specific students.

---

## 7. Admin Portal Pages

Administrators include Student Development Center administrators and system administrators. Both share the admin framework with different permissions.

### 7.1 Admin Dashboard

- School-wide project, registration, review, account, and progress metrics.
- Pending student activity proposals, other review tasks, exception trends, and review SLA.
- Data import results, system alerts, and recent key operations.
- Switchable by academic year, grade, and time range.

### 7.2 Project, Registration & Organization Management

- Project full lifecycle: draft, published, registering, in progress, ended, closed.
- Project templates, service categories, hour rules, registration and waitlist rules.
- Organization profiles, contacts, qualification status, and historical projects.
- Registration list batch processing, import/export, and change records.

### 7.3 Student Activity Proposal Review Management

- School-wide activity proposal queue with views for needs revision, recommended approval, approved, rejected, and withdrawn.
- Assign reviewers, configure multi-level approval, transfer to Student Development Center for final review, and monitor approval SLA.
- View proposal revision diffs, teacher comments, internal notes, venue conflicts, resource usage, and similar historical activities.
- Execute request revision, approve, or reject; upon approval, generate official project draft and assign supervising teacher, student leader, and publication owner.
- Handle post-approval major changes: return project draft for re-approval or revoke unpublished approval, all operations retaining audit records.
- Configure proposal submission windows, applicable grades, required materials, approval levels, approval SLA, and student-initiated activity quantity limits.

### 7.4 Review Center

- School-wide review queue, appeal queue, and exception records.
- Review task assignment and transfer.
- Batch approval only applies to low-risk records meeting configured conditions.
- View complete evidence, historical versions, review comments, and operation logs.
- Execute reversal on approved records, rather than directly modifying or deleting.

### 7.5 Students & Base Data

- Student, class, grade, academic year, and guardian information management.
- Single-item maintenance and batch import.
- Status changes: class transfer, grade promotion, graduation, deactivation.
- Student account reset, unlock, and role check.

### 7.6 Time-Coin Accounts

- Query accounts and transactions.
- Freeze, unfreeze, manual adjustment, and reversal.
- Manual operations must fill in a reason, link to a record, and pass permission verification.
- Balance cannot be directly edited; all changes generate non-overwritable transactions.
- Phase 4 enables management of redemption, borrowing, and value-added rules.

### 7.7 Rule Configuration

- Grade annual and phase hour requirements.
- Time-coin conversion units and minimum record unit.
- Service types, daily/per-session limits.
- Review status, review SLA, and appeal rules.
- File requirements, allowed formats, and size limits.
- Feature toggles and effective times.
- Rule publication provides impact summary; historical rules are read-only retained.

### 7.8 Statistics & Reports

- Participation rate, compliance rate, total service hours, review efficiency, data completeness rate, and other metrics.
- Drill down by academic year, grade, class, project, organization, and service category.
- Report export runs as an async task, with in-app notification upon completion for download.
- Statistics methodology page displays metric definitions and data update time.

### 7.9 Data & System Management

- Historical ledger import: upload, field mapping, pre-validation, error download, confirm import, and result audit.
- Data export and academic year archiving.
- Users, roles, permissions, and data scopes.
- Login logs, operation logs, exception logs.
- System parameters, in-app notification templates, and maintenance mode.

---

## 8. Phase 2 Core Operation Flows

### 8.1 Project Publication & Registration

```text
Teacher/Admin creates project draft
→ Complete time, location, capacity, service tasks, and crediting rules
→ Publish
→ Students register in Project Center
→ System confirms per rules or enters waitlist
→ Teacher views and manages registration list
→ After project ends, student initiates service record from registration
```

### 8.2 Student-Initiated Activity & Approval

```text
Student creates activity proposal draft
→ Fills in activity goals, implementation plan, organization, safety measures, and recruitment plan
→ Submit for approval
→ Teacher/Admin evaluates
   → Request revision: Student creates new revision per feedback and resubmits
   → Reject: Record standardized reason and explanation
   → Recommend approval: Transfer to Student Development Center for final review
   → Approve: Generate official project draft and assign responsible teacher
→ Teacher/Admin refines project rules and officially publishes
→ Other students register in Project Center
→ Student initiator participates in preparation and registration collaboration via project page
```

Student initiators can only access explicitly authorized project preparation information, and cannot independently modify review rules, approve registrations, credit hours, or publish projects unless separately granted corresponding permissions.

### 8.3 Self-Initiated Service Record & Review

```text
Student creates new record and saves draft
→ Fills in service information and uploads proof
→ Confirm and submit
→ Teacher reviews
   → Approve: Generate time-coin transaction and update progress
   → Return: Student supplements materials and resubmits
   → Reject: Record reason; student can initiate appeal
→ Student views results, account, and progress
```

### 8.4 Appeal

```text
Student initiates appeal from record detail
→ Fills in dispute and supplements materials
→ Student Development Center enters appeal queue
→ Uphold original decision / Change to approved / Request additional supplement
→ System notifies student and records in timeline
```

### 8.5 Historical Ledger Import

```text
Administrator downloads template
→ Uploads historical ledger
→ System parses and pre-validates
→ Administrator views summary and row-by-row errors
→ Correct and re-upload or confirm valid data
→ Backend imports asynchronously
→ Generates service records, account transactions, and import audit report
```

---

## 9. Phase 3 Extension Reservations: POV

The Phase 2 Web frontend reserves the following capabilities, but they are disabled by default via feature toggles:

### 9.1 Student-Side Reservations

- "On-site POV" entry point and POV record status.
- "POV Evidence" read-only section in Web service record detail.
- Witness, group member mutual verification, guardian endorsement, and AI initial review status timeline.
- View POV records and appeal results submitted from the Mini Program via Web.

### 9.2 Admin-Side Reservations

- POV review queue.
- AI risk score, risk labels, and evidence metadata section.
- Random spot-check tasks, witness verification results, and public challenge processing.
- Whitelist bases and witness credit management.
- Evidence storage status and TxHash view entry.

### 9.3 Boundaries

- The Mini Program handles on-site capture; Web handles complete management and manual processing.
- The Mini Program cannot create independent account balances or independent review statuses.
- POV data is integrated through the same `/api/v1` resource system.

---

## 10. Phase 4 Extension Reservations: Redemption, Borrowing & Value-Added

### 10.1 Student-Side Reservations

- Redemption center, redemption detail, confirmation, and redemption history.
- Borrowing eligibility, application, approval progress, and repayment plan.
- Honor tier, continuous service, and special project bonus descriptions.

### 10.2 Admin-Side Reservations

- Redemption catalog, inventory/quota, exchange rates, and approval.
- Borrowing limits, applicable conditions, approval, and overdue tracking.
- Value-added rules, rule scope, and reward transactions.
- Reconciliation, statistics, and exception handling.

### 10.3 Enablement Principles

- Phase 2 can complete routing, permission codes, and data model reservations, but does not open them to students by default.
- Unenabled features uniformly display "Not yet available"; blank pages or failing APIs must not be used as substitutes.
- Once enabled, unified account transactions are still reused; balances are not directly modified.

---

## 11. Responsive Layout & Key Components

### 11.1 Breakpoint Strategy

- Mobile: Single column, bottom navigation, card lists, bottom action area.
- Tablet: Dual-column detail or list + filter.
- Desktop: Fixed sidebar, data tables, list and detail side-by-side, batch operations.

Specific breakpoints use Tailwind CSS unified configuration; business pages must not define inconsistent breakpoint systems.

### 11.2 Shared UI Patterns

- App shell, page title, and breadcrumb.
- Status badges and status timelines.
- Search, filter drawer, sort, and selected conditions display.
- Data tables and mobile card lists.
- Multi-step forms, file upload, and material preview.
- Confirmation dialogs, bottom action panels, and reason input.
- Empty states, skeleton screens, error states, and retry.
- In-app notifications, toast, and global task progress.

### 11.3 Unified Status Language

The same business status uses unified names, colors, and descriptions across all pages. Pages must not create synonymous statuses like "processing" or "under review" on their own; status definitions are governed by `APIContract.md`.

---

## 12. Frontend Module Organization

Recommended organization by business domain, not by page type:

```text
auth
workspace
dashboard
projects
applications
event-proposals
service-records
reviews
students
organizations
accounts
progress
appeals
notifications
reports
imports-exports
administration
future-pov
future-redemption
future-borrowing
```

Each business domain contains its routing, pages, components, queries, form validation, and permission mapping. Cross-domain capabilities such as authentication, file upload, error handling, design tokens, and API client are placed in a shared foundation layer.

---

## 13. Frontend-Driven Delivery Sequence

1. Establish page map, role-permission matrix, and unified status dictionary.
2. Build React/Tailwind app shell, login, routing, permission gates, and Mock API.
3. Prioritize the minimal closed loop of student record submission and teacher review.
4. Add project registration, account transactions, and progress tracking.
5. Complete admin base data, rules, import/export, and audit capabilities.
6. Connect to real RESTful API, replacing Mock with contract tests.
7. Complete mobile, desktop, exception states, permissions, and end-to-end acceptance testing.
8. Retain Phase 3 and Phase 4 routing and permission entry points, but do not load corresponding modules when feature toggles are off.

---

## 14. Phase 2 Acceptance Criteria

- Students, teachers, and administrators can log in with independent accounts and see the correct workspace.
- When permissions are insufficient, the frontend does not display entry points; direct route access is also rejected by the backend.
- Students can complete project registration, record submission, material supplement, appeal, and result query.
- Students can create, submit, revise, and withdraw activity proposals, and view approval comments and linked official projects.
- Teachers can complete project management, registration management, student activity proposal evaluation, record review, return, and progress viewing.
- Administrators can manage activity proposal review rules and queues, and manage school-wide base data, rules, accounts, statistics, import/export, and audit logs.
- Approved reviews generate account transactions and automatically update student progress.
- The Web runs completely without WeChat or other IM integration.
- Mobile completes all student core tasks; desktop supports efficient batch operations for teachers and administrators.
- Network failure, duplicate submission, session expiration, file failure, and concurrent modification all have clear feedback and recovery paths.
- Phase 3 and Phase 4 entry points are controlled by feature toggles and do not affect Phase 2 stability.
