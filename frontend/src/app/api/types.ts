// API response/request payload types — lifted out of mock/data.ts per
// APIContract.md §10.1/§12.3: this module is authoritative for response
// shapes, mock/data.ts holds fixtures only. Field shapes here already
// reflect the five contract divergences resolved in I0 (needsRevision ->
// changesRequested, coin amounts as centi-coins, minutes-only durations,
// dictionary names, list envelope) — see APIContract.md §13.5.

export interface SchoolInfo {
  name: string;
  fullName: string;
  studentDevCenter: string;
  contact: string;
}

export type Role = 'student' | 'teacher' | 'admin' | 'sysadmin';

export interface User {
  id: string;
  username: string;
  password: string;
  displayName: string;
  roles: Role[];
  workspaces: ('student' | 'admin')[];
  studentId?: string;
  email: string;
  avatar?: string;
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
  organizationId: string;
  organizationName: string;
  ownerId: string;
  ownerName: string;
  startAt: string;
  endAt: string;
  location: string;
  capacity: number;
  enrolledCount: number;
  recognizedMinutes: number;
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
  sourceType: 'project' | 'external' | 'self';
  serviceDate: string;
  startAt: string;
  endAt: string;
  durationMinutes: number;
  location: string;
  organizationId?: string;
  organizationName: string;
  serviceCategoryId: string;
  serviceCategoryName: string;
  description: string;
  supervisorName: string;
  supervisorContact: string;
  evidenceFiles: EvidenceFile[];
  status: RecordStatus;
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

/** Minutes-only on the wire (APIContract.md §2.1); derive hours for display via `minutesToHours`. `timeCoinBalance` is centi-coins. */
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

/** Minutes-only on the wire; derive hours via `minutesToHours`. `timeCoinBalance` is centi-coins. Guardian fields omitted from lists (APIContract.md §9). */
export interface Student {
  id: string;
  studentNumber: string;
  name: string;
  grade: string;
  className: string;
  email: string;
  status: 'active' | 'inactive' | 'graduated';
  completedMinutes: number;
  pendingMinutes: number;
  annualRequiredMinutes: number;
  stageRequiredMinutes: number;
  timeCoinBalance: number;
  joinedAt: string;
  guardianName?: string;
  guardianContact?: string;
}

export type ReviewStatus = 'pending' | 'underReview' | 'approved' | 'returned' | 'rejected';

export interface ReviewQueueItem {
  id: string;
  serviceRecordId: string;
  studentName: string;
  grade: string;
  className: string;
  projectTitle?: string;
  organizationName: string;
  serviceDate: string;
  durationMinutes: number;
  submittedAt: string;
  status: ReviewStatus;
  priority: 'high' | 'normal' | 'low';
  daysWaiting: number;
  hasAnomaly: boolean;
  anomalyNote?: string;
}

export interface Organization {
  id: string;
  name: string;
  type: 'school' | 'community' | 'nonprofit' | 'government' | 'other';
  contactName: string;
  contactEmail: string;
  contactPhone: string;
  address: string;
  status: 'active' | 'inactive' | 'archived';
  projectCount: number;
  createdAt: string;
}

export interface AdminStats {
  totalStudents: number;
  activeStudents: number;
  totalProjects: number;
  activeProjects: number;
  pendingReviews: number;
  approvedThisMonth: number;
  totalServiceHours: number;
  avgCompletionRate: number;
  reviewAvgDays: number;
}

export interface ReportData {
  participationByGrade: { grade: string; total: number; participated: number; rate: number }[];
  completionByGrade: { grade: string; total: number; completed: number; rate: number }[];
  serviceHoursByMonth: { month: string; hours: number }[];
  topProjects: { title: string; participants: number; totalHours: number }[];
}

export type ProposalStatus = 'draft' | 'submitted' | 'underReview' | 'changesRequested' | 'approved' | 'rejected' | 'withdrawn';

export interface ProposalTimeline {
  action: string;
  actor: string;
  time: string;
  note?: string;
}

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
  coOrganizers: string;
  mentorTeacher: string;
  partnerOrg: string;
  contactInfo: string;
  riskMeasures: string;
  materialNeeds: string;
  budgetNeeds: string;
  recruitmentMethod: string;
  eligibility: string;
  proofMethod: string;
  suggestedMinutes: number;
  currentApprovalNode: string;
  reviewerName?: string;
  feedback?: string;
  linkedProjectId?: string;
  linkedProjectTitle?: string;
  revisionCount: number;
  createdAt: string;
  updatedAt: string;
  submittedAt?: string;
  timeline: ProposalTimeline[];
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

/** `GET /me` response shape (APIContract.md §2.2) — distinct from the mock `User` type. */
export interface MeResponse {
  user: {
    id: string;
    displayName: string;
    roles: string[];
    permissions: string[];
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
