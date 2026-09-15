// API response/request payload types — authoritative for real backend
// response shapes (APIContract.md §10.1/§12.3). Field shapes here already
// reflect the five contract divergences resolved in I0 (needsRevision ->
// changesRequested, coin amounts as centi-coins, minutes-only durations,
// dictionary names, list envelope) — see APIContract.md §13.5.

/** `GET /school/info` (contract §4.16). `fullName` is real as of the School Info edit card (issue 045) — no longer always undefined. */
export interface SchoolInfo {
  name: string;
  fullName?: string;
  studentDevCenter: string;
  contact: string;
  academicYear?: string;
}

/** `PATCH /school/info` (contract §4.16) — partial body, `sysadmin` role only (not merely `admin`); no version field, see the backend doc comment for why. */
export interface UpdateSchoolInfoRequest {
  name?: string;
  fullName?: string;
  studentDevCenter?: string;
  contact?: string;
}

/** `GET /system/version` (contract §4.17) — bare semver, no `v` prefix; display call sites add it. */
export interface SystemVersion {
  version: string;
}

/** `GET /audit-logs` (contract §4.15) — real, append-only, backend-owned. `actorId` is null for system-generated entries (e.g. auto-archive); resolve to a display name client-side via a loaded user directory, never invent one. */
export interface AuditLogEntry {
  id: string;
  actorId: string | null;
  permissionUsed: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  beforeSummary: string | null;
  afterSummary: string | null;
  reasonCode: string | null;
  createdAt: string;
}

/**
 * `GET/POST /rules` (contract §4.12) — one published rule version is one
 * consistent whole (rounding, coin conversion, hour requirements, honour
 * tiers), never edited in place once published; a new draft supersedes it.
 * All durations are integer minutes (no floats, per the project-wide
 * duration convention) — display helpers convert to hours for readability.
 * Middle school (grades 6-8) and high school (grades 9-11) have separate
 * annual/stage requirements — Design/chinese/01-TimeBankDesign.md §6 —
 * exactly two bands, not a per-grade table.
 */
export interface RuleVersion {
  id: string;
  status: 'draft' | 'published' | 'archived';
  roundingMinutes: number;
  minutesPerCoinUnit: number;
  centiCoinsPerUnit: number;
  middleAnnualRequiredMinutes: number;
  middleStageRequiredMinutes: number;
  highAnnualRequiredMinutes: number;
  highStageRequiredMinutes: number;
  honourBronzeMinutes: number;
  honourSilverMinutes: number;
  honourGoldMinutes: number;
  honourExcellentMinutes: number;
  effectiveFrom?: string;
  effectiveTo?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  publishedAt?: string;
}

/** The 11 numeric fields a rule draft is created/edited with — shared by create and update requests. */
export interface RuleDraftFields {
  roundingMinutes: number;
  minutesPerCoinUnit: number;
  centiCoinsPerUnit: number;
  middleAnnualRequiredMinutes: number;
  middleStageRequiredMinutes: number;
  highAnnualRequiredMinutes: number;
  highStageRequiredMinutes: number;
  honourBronzeMinutes: number;
  honourSilverMinutes: number;
  honourGoldMinutes: number;
  honourExcellentMinutes: number;
}

/** `POST /rules/{id}/validate` — `affectedRecordCount` is always 0 until a real usage exists to count against (backend-documented honest placeholder, not a broken query). */
export interface RuleValidationResult {
  valid: boolean;
  issues: string[];
  affectedRecordCount: number;
}

/**
 * 07-RoleFinetune.md: was a fixed 4-value union before roles became
 * data-driven/manageable — now any role code the backend returns, custom
 * roles included. Kept as an alias (rather than inlining `string`) so call
 * sites reads as "a role code," not an arbitrary string.
 */
export type Role = string;

export interface User {
  id: string;
  username: string;
  displayName: string;
  roles: Role[];
  workspaces: ('student' | 'admin')[];
  studentId?: string;
  /** Issue 004: this operator's own grade bond (empty for non-teacher accounts) — used to scope grade filter dropdowns to what they can actually see. */
  scopeGradeIds: string[];
  email: string;
  avatar?: string;
  permissions?: string[];
  /** I3.4: server-authoritative; `localStorage['timebank-lang']` is only a pre-login-response cache, never the source of truth once a real session exists. */
  preferredLanguage?: 'zh-CN' | 'en';
  featureFlags: {
    pov: boolean;
    redemption: boolean;
    borrowing: boolean;
    appreciation: boolean;
  };
}

export type ProjectStatus = 'draft' | 'published' | 'registrationOpen' | 'inProgress' | 'completed' | 'archived' | 'closed';

export interface Project {
  id: string;
  title: string;
  summary: string;
  description: string;
  categoryId: string;
  categoryName: string;
  /** The project's PARTNER organization, if any — never the school organization (that's automatic, see hostOrganizationId). */
  organizationId: string;
  organizationName: string;
  /** Always the single school-organization singleton (issue 03 §4.2), server-resolved, never client-writable. */
  hostOrganizationId: string;
  hostOrganizationName: string;
  deliveryScope: 'onCampus' | 'offCampus' | 'hybrid';
  ownerId: string;
  ownerName: string;
  startAt: string;
  endAt: string;
  location: string;
  capacity: number;
  enrolledCount: number;
  recognizedMinutes: number;
  /** Server-localized grade *names* (e.g. "十年级"), not ids — `api.projects.create`/`.update` send this same field as `eligibleGradeIds`, which the backend expects to be real grade ids, so any edit form must convert via the `grades` dictionary rather than round-tripping this array. */
  eligibleGrades: string[];
  registrationOpenAt: string;
  registrationCloseAt: string;
  status: ProjectStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  evidenceRequirements: string;
  myApplicationStatus?: ApplicationStatus;
}

export type ApplicationStatus = 'pending' | 'confirmed' | 'waitlisted' | 'rejected' | 'completed' | 'cancelled';

export interface Application {
  id: string;
  projectId: string;
  projectTitle: string;
  studentId: string;
  studentName: string;
  status: ApplicationStatus;
  appliedAt: string;
  confirmedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  note?: string;
}

export type RecordStatus = 'draft' | 'submitted' | 'underReview' | 'approved' | 'returned' | 'rejected' | 'corrected';

export interface ServiceRecord {
  id: string;
  studentId: string;
  studentName: string;
  projectId?: string;
  projectTitle?: string;
  /** `project`/`self` only for new records; `external` is historical data — still readable/editable/resubmittable, never newly creatable (issue 03 §3/§7.3). */
  sourceType: 'project' | 'external' | 'self';
  serviceDate: string;
  startAt: string;
  endAt: string;
  durationMinutes: number;
  location: string;
  organizationId?: string;
  organizationName: string;
  /** PROJECT source only — the project's own host/partner organizations at snapshot time (issue 03 §3.1). Empty for SELF/EXTERNAL. */
  hostOrganizationName?: string;
  partnerOrganizationNames?: string[];
  serviceCategoryId: string;
  serviceCategoryName: string;
  /** 04-DataViewOptimization.md §3.2 — batch-resolved server-side, same as the review queue's own gradeLevel/className. */
  gradeLevel?: number;
  className?: string;
  description: string;
  /** `projectOwner` (PROJECT, server-derived) | `internalUser` | `student` | `externalManual` (SELF, client-chosen) — undefined for historical EXTERNAL records. */
  supervisorKind?: 'projectOwner' | 'internalUser' | 'student' | 'externalManual';
  supervisorSubjectId?: string;
  supervisorName: string;
  supervisorContact: string;
  includesTravelTime?: boolean;
  travelTimeReason?: string;
  /** Server-generated at creation, unique, immutable; undefined for historical records that pre-date it (issue 03 §5.3). */
  recordNumber?: string;
  evidenceFiles: EvidenceFile[];
  status: RecordStatus;
  /** Rule-based, server-computed at submit time (overlap/travel-time disclosure) — see `ReviewQueueItem`'s copy of the same fields for the queue-row badge. */
  hasAnomaly?: boolean;
  anomalyNote?: string;
  reviewSummary?: string;
  reviewerName?: string;
  reviewedAt?: string;
  creditedUnits?: number;
  version: number;
  createdAt: string;
  submittedAt?: string;
  updatedAt: string;
  returnReason?: string;
  rejectReason?: string;
}

export interface EvidenceFile {
  id: string;
  name: string;
  type: string;
  size: number;
  url: string;
  uploadedAt: string;
  /** Real `FileStatus` (PENDING/PROCESSING/READY/REJECTED) — I3.2, only meaningful for a file uploaded this session. */
  status?: 'pending' | 'processing' | 'ready' | 'rejected';
  rejectionReason?: string;
}

/** `GET /files/{id}` / `POST /files/{id}/complete` (contract §4.6) — `downloadUrl` is only present once `status === 'ready'`, and is a fresh 5-minute token every time, never to be cached (I3.3). */
export interface FileMetadata {
  id: string;
  uploaderId: string;
  purpose: string;
  filename: string;
  mimeType: string;
  sizeBytes: number;
  status: 'pending' | 'processing' | 'ready' | 'rejected';
  rejectionReason?: string;
  downloadUrl?: string;
  createdAt: string;
  updatedAt: string;
}

/** `amount`/`balanceBefore`/`balanceAfter` are integer centi-coins (APIContract.md §2.1) — format via `formatCoins`. */
export interface AccountTransaction {
  id: string;
  accountId: string;
  type: 'serviceCredit' | 'correction' | 'freeze' | 'unfreeze' | 'redemption' | 'borrow' | 'repayment' | 'bonus';
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  relatedRecordId?: string;
  relatedRecordTitle?: string;
  description: string;
  operatorName: string;
  createdAt: string;
}

/** `balance`/`totalEarned`/`totalRedeemed`/`frozen`/`borrowed` are integer centi-coins — format via `formatCoins`. */
export interface Account {
  id: string;
  studentId: string;
  balance: number;
  totalEarned: number;
  totalRedeemed: number;
  frozen: number;
  borrowed: number;
  updatedAt: string;
}

/**
 * Minutes-only on the wire (APIContract.md §2.1); derive hours for display via `minutesToHours`. `timeCoinBalance` is centi-coins.
 * `grade` here is a pre-formatted display string (via `formatGradeClass`), unlike `Student.gradeLevel`/`className` elsewhere — this
 * DTO has no backing endpoint of its own for grade/class (client.ts backfills it from a separate `/students/{id}` call), so there's
 * no raw `gradeLevel` to expose alongside it worth the extra field.
 */
export interface StudentProgress {
  studentId: string;
  academicYear: string;
  grade: string;
  annualRequiredMinutes: number;
  stageRequiredMinutes: number;
  completedMinutes: number;
  underReviewMinutes: number;
  gapMinutes: number;
  timeCoinBalance: number;
  byCategory: {
    categoryId: string;
    categoryName: string;
    completedMinutes: number;
    underReviewMinutes: number;
  }[];
  recognitionLevel: 'none' | 'bronze' | 'silver' | 'gold' | 'excellent';
}

export interface Notification {
  id: string;
  userId: string;
  category: 'review' | 'project' | 'account' | 'rules' | 'system';
  title: string;
  body: string;
  relatedType?: string;
  relatedId?: string;
  isRead: boolean;
  createdAt: string;
}

/**
 * Minutes-only on the wire; derive hours via `minutesToHours`. `timeCoinBalance` is centi-coins. Guardian fields
 * omitted from lists (APIContract.md §9). The real `GET /students` list item (`StudentListItemDto`) does not
 * carry progress/balance fields at all — those live on `StudentProgress`/`Account`, fetched separately per
 * contract's resource separation (I1 finding, see `Docs/implementation/00-Status.md`) — so the progress fields
 * here are optional and never populated by this list endpoint; the student list screen renders "—" for them.
 */
export interface Student {
  id: string;
  studentNumber: string;
  name: string;
  gradeId: string;
  gradeLevel: number;
  classId: string;
  className: string;
  email: string;
  status: 'active' | 'inactive' | 'graduated';
  completedMinutes?: number;
  pendingMinutes?: number;
  annualRequiredMinutes?: number;
  stageRequiredMinutes?: number;
  timeCoinBalance?: number;
  joinedAt: string;
  guardianName?: string;
  guardianContact?: string;
  version?: number;
}

export type ReviewStatus = 'pending' | 'underReview' | 'approved' | 'returned' | 'rejected';

export interface ReviewQueueItem {
  id: string;
  serviceRecordId: string;
  studentName: string;
  gradeLevel: number;
  className: string;
  projectTitle?: string;
  organizationName: string;
  serviceDate: string;
  durationMinutes: number;
  submittedAt: string;
  status: ReviewStatus;
  priority: 'high' | 'medium' | 'low';
  daysWaiting: number;
  hasAnomaly: boolean;
  anomalyNote?: string;
  recordVersion?: number;
}

/** Contract §3.6/§4.8 — appeals, supplements, and staff-filed manual corrections share one state machine and one endpoint family. */
export interface Case {
  id: string;
  type: 'appeal' | 'supplement' | 'manualCorrection';
  studentId: string;
  studentName: string;
  /** The case's own snapshotted grade/class (set at creation, not the student's current grade) — for the grade filter/display Case Review shares with Audit Center. */
  gradeId: string;
  gradeLevel: number;
  className: string;
  subjectType?: string;
  subjectId?: string;
  /** Server-resolved display name for `subjectType`/`subjectId` (e.g. a service record's `recordNumber`) — 04-DataViewOptimization.md §4.2 (D8). Undefined when there's no subject or it isn't resolvable. */
  subjectLabel?: string;
  title: string;
  description: string;
  requestedAmountCentiCoins?: number;
  reasonCode: string;
  status: 'draft' | 'submitted' | 'inReview' | 'needsInformation' | 'resolved' | 'rejected';
  assignedReviewerId?: string;
  version: number;
  createdAt: string;
  submittedAt?: string;
  updatedAt: string;
}

/** Shared shape of `GET .../{id}/timeline` across service records, cases, and proposals — same real backend `TimelineEntryDto` in all three modules (04-DataViewOptimization.md §1.3 finding 2: the merged-history endpoint already existed server-side for all three, only the client wiring was missing). */
export interface TimelineEntry {
  type: string;
  description: string;
  actorId: string;
  occurredAt: string;
}

export interface Organization {
  id: string;
  name: string;
  type: 'school' | 'community' | 'nonprofit' | 'government' | 'other';
  description: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string;
  address: string;
  status: 'active' | 'inactive' | 'archived';
  /** True for exactly one row system-wide — the school itself (issue 03 §4.1). Read-only; never settable via create/update. */
  isSchoolOrganization: boolean;
  projectCount: number;
  version: number;
  createdAt: string;
}

/** `GET /grades` (contract §2.10 dictionary domain) — `name` is already resolved server-side per `Accept-Language`, no local i18n-key indirection needed. */
export interface Grade {
  id: string;
  code: string;
  name: string;
  sequence: number;
}

/** `GET /classes` — `name` is raw, language-invariant data (issue 010), never localized. */
export interface SchoolClass {
  id: string;
  code: string;
  name: string;
  gradeId: string;
  academicYearId: string;
}

/** `GET /academic-years`. */
export interface AcademicYear {
  id: string;
  code: string;
  startDate: string;
  endDate: string;
  current: boolean;
}

/** `GET /service-categories` — `name` is already resolved server-side per `Accept-Language`. */
export interface ServiceCategory {
  id: string;
  code: string;
  /** Already resolved to the caller's language by the backend — what pickers display. */
  name: string;
  /** Raw stored values, for the admin management screen which edits both (issue 078). */
  nameZh: string;
  nameEn: string;
  active: boolean;
}

/** `GET /permissions` — the flat catalog (07-RoleFinetune.md §6); `code`'s prefix before the first `.` is the resource group. */
export interface Permission {
  id: string;
  code: string;
  description: string;
}

/** `GET/POST /roles`, `PATCH /roles/{id}` — 07-RoleFinetune.md §2.2/§2.3. */
export interface RoleSummary {
  id: string;
  code: string;
  name: string;
  sessionTimeoutMinutes: number;
  permissionCodes: string[];
  isProtected: boolean;
  /** §2.4/§2.9: how many users currently hold this role — `DELETE /roles/{id}` 409s if this is nonzero. */
  userCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** `GET/POST /role-templates`, `PATCH /role-templates/{id}` — 07-RoleFinetune.md §2.7. No `code`/`sessionTimeoutMinutes`: a template is never assigned to a user, so neither is meaningful. */
export interface RoleTemplateSummary {
  id: string;
  name: string;
  permissionCodes: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** `GET /students/directory` row — a co-organizer picker's search result, deliberately narrower than `Student` (contract §4.9). */
export interface StudentDirectoryEntry {
  id: string;
  displayName: string;
  gradeLevel: number;
  className: string;
}

/** `GET /users/directory` row — a supervisor picker's search result, deliberately narrower than `User` (contract §4.15). */
export interface TeacherDirectoryEntry {
  id: string;
  displayName: string;
}

/** `avgCompletionRate` is never populated — the real `AdminStatsDto` has no aggregate completion rate (`/reports/completion` has a per-grade one); optional so the dashboard omits it rather than faking a value. */
export interface AdminStats {
  totalStudents: number;
  activeStudents: number;
  totalProjects: number;
  activeProjects: number;
  pendingReviews: number;
  approvedThisMonth: number;
  totalServiceHours: number;
  avgCompletionRate?: number;
  reviewAvgDays: number;
}

export interface ReportData {
  participationByGrade: { grade: string; total: number; participated: number; rate: number }[];
  completionByGrade: { grade: string; total: number; completed: number; rate: number }[];
  serviceHoursByMonth: { month: string; hours: number }[];
  topProjects: { title: string; participants: number; totalHours: number }[];
}

/** `underReview` removed 2026-09-16 (issue 080): unreachable since the two-tier review went away — a reviewer decides straight from `submitted`. */
export type ProposalStatus = 'draft' | 'submitted' | 'changesRequested' | 'approved' | 'rejected' | 'withdrawn';

/** `estimatedDurationMinutes`/`suggestedMinutes` are minutes-only on the wire; derive hours via `minutesToHours`. */
export interface EventProposal {
  id: string;
  title: string;
  status: ProposalStatus;
  myRole: string;
  categoryId: string;
  categoryName: string;
  plannedDate: string;
  location: string;
  estimatedDurationMinutes: number;
  estimatedCapacity: number;
  goal: string;
  description: string;
  expectedOutcomes: string;
  activityFormat: string;
  targetParticipants: string;
  volunteerTasks: string;
  initiatorName: string;
  /** The proposal's own snapshotted grade/class (set at creation, not the proposer's current grade) — for the grade filter/display Proposal Review shares with Audit Center. */
  gradeId?: string;
  gradeLevel?: number;
  className?: string;
  /** Display-only, comma-joined from `coOrganizerNames`. */
  coOrganizers: string;
  /** Display-only resolved name — `proposedSupervisorName` on the real DTO. */
  mentorTeacher: string;
  /** Display-only resolved name — `organizationName` on the real DTO. */
  partnerOrg: string;
  contactInfo: string;
  /**
   * Real ids behind the display fields above — added alongside the
   * co-organizer/supervisor/organization pickers
   * (`Docs/optimization/01-i3-partner-school-terminology.md`).
   * `newOrganizationName` is write-only (never comes back from a
   * read); it exists here only so `formToApiData` can round-trip through
   * the same `Partial<EventProposal>` the create/update calls already use.
   */
  coOrganizerStudentIds?: string[];
  coOrganizerNames?: string[];
  proposedSupervisorId?: string;
  organizationId?: string;
  newOrganizationName?: string;
  riskMeasures: string;
  materialNeeds: string;
  budgetNeeds: string;
  recruitmentMethod: string;
  eligibility: string;
  proofMethod: string;
  suggestedMinutes: number;
  reviewerName?: string;
  feedback?: string;
  linkedProjectId?: string;
  linkedProjectTitle?: string;
  /** Real backend field (`ProposalDto.currentRevision`, APIContract §3.2) — starts at 1, increments on every draft/changes-requested save. Not the optimistic-lock `version` below. */
  currentRevision: number;
  version?: number;
  createdAt: string;
  updatedAt: string;
  submittedAt?: string;
}

// ── Envelope & error types (I0.4/I0.5) ──────────────────────────────────────

export interface PageMeta {
  number: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ResponseMeta {
  requestId: string;
}

export interface ApiFieldError {
  field: string;
  code: string;
  message: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  fieldErrors?: ApiFieldError[];
  requestId: string;
}

/** `GET /me` response shape (APIContract.md §2.2) — distinct from the client-side `User` type (`meResponseToUser` in client.ts maps between them). */
export interface MeResponse {
  user: {
    id: string;
    displayName: string;
    roles: string[];
    permissions: string[];
    /** `null` for staff accounts (teacher/admin/sysadmin) — added 2026-08-06 so the co-organizer picker can exclude "myself" without a second round trip. */
    studentId: string | null;
    /** Issue 004: the operator's own grade bond, empty for non-teacher accounts — lets a filter dropdown narrow itself to what a scoped teacher can actually see. */
    scopeGradeIds: string[];
  };
  workspaces: ('student' | 'admin')[];
  preferredLanguage: 'zh-CN' | 'en';
  featureFlags: {
    pov: boolean;
    redemption: boolean;
    borrowing: boolean;
    appreciation: boolean;
  };
}
