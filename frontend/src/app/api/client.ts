import {
  MOCK_USERS,
  MOCK_SCHOOL_INFO,
  MOCK_PROJECTS,
  MOCK_APPLICATIONS,
  MOCK_RECORDS,
  MOCK_ACCOUNT,
  MOCK_TRANSACTIONS,
  MOCK_PROGRESS,
  MOCK_NOTIFICATIONS,
  MOCK_STUDENTS,
  MOCK_REVIEW_QUEUE,
  MOCK_ORGANIZATIONS,
  MOCK_ADMIN_STATS,
  MOCK_REPORT_DATA,
  MOCK_PROPOSALS,
  resolveCategoryName,
} from '../mock/data';
import type {
  User,
  Project,
  Application,
  ServiceRecord,
  Account,
  AccountTransaction,
  StudentProgress,
  Notification,
  Student,
  ReviewQueueItem,
  Organization,
  AdminStats,
  ReportData,
  EventProposal,
  SchoolInfo,
  MeResponse,
} from './types';
import { http } from './http';
import { isRealDomain } from './config';
import i18n from '../../i18n';

const DELAY = 300;

function stampCategoryName<T extends { categoryId?: string; categoryName?: string }>(obj: T): T {
  if (!obj.categoryId) return obj;
  return { ...obj, categoryName: resolveCategoryName(obj.categoryId, i18n.language) };
}

function stampServiceCategoryName<T extends { serviceCategoryId?: string; serviceCategoryName?: string }>(obj: T): T {
  if (!obj.serviceCategoryId) return obj;
  return { ...obj, serviceCategoryName: resolveCategoryName(obj.serviceCategoryId, i18n.language) };
}

function delay<T>(data: T, ms = DELAY): Promise<T> {
  return new Promise(resolve => setTimeout(() => resolve(data), ms));
}

function clone<T>(data: T): T {
  return JSON.parse(JSON.stringify(data));
}

export interface LoginResponse {
  user: User;
}

export interface PaginatedResponse<T> {
  data: T[];
  page: { number: number; size: number; totalItems: number; totalPages: number };
}

export const api = {
  auth: {
    async login(username: string, password: string): Promise<LoginResponse> {
      const user = MOCK_USERS.find(u => u.username === username && u.password === password);
      if (!user) throw new Error('Invalid username or password');
      return delay({ user: clone(user) });
    },
    // I0.7: the one real-branch call this phase proves the transport layer
    // against (Docs/implementation/10-Integration-I0-ContractPrep.md, exit
    // criteria). Real `/me`'s shape genuinely differs from the mock `User`
    // (APIContract.md §2.2) — reconciling that for real consumers is I1-d1's
    // job, not this phase's.
    async me(): Promise<User | MeResponse> {
      if (isRealDomain('auth')) {
        return http.get<MeResponse>('/me');
      }
      const stored = localStorage.getItem('timebank-user');
      if (stored) return delay(clone(JSON.parse(stored)));
      throw new Error('Not logged in');
    },
    async logout(): Promise<void> {
      return delay(undefined);
    },
  },

  school: {
    async getInfo(): Promise<SchoolInfo> {
      return delay(clone(MOCK_SCHOOL_INFO));
    },
  },

  projects: {
    async list(): Promise<Project[]> {
      return delay(clone(MOCK_PROJECTS).map(stampCategoryName));
    },
    async get(id: string): Promise<Project> {
      const p = MOCK_PROJECTS.find(p => p.id === id);
      if (!p) throw new Error('Project not found');
      return delay(stampCategoryName(clone(p)));
    },
    async create(data: Partial<Project>): Promise<Project> {
      const categoryId = data.categoryId || 'cat_other';
      const p: Project = {
        id: `proj_${Date.now()}`,
        title: data.title || '',
        summary: data.summary || '',
        description: data.description || '',
        categoryId,
        categoryName: resolveCategoryName(categoryId, i18n.language),
        organizationId: data.organizationId || 'org_school',
        organizationName: data.organizationName || '',
        ownerId: data.ownerId || '',
        ownerName: data.ownerName || '',
        startAt: data.startAt || '',
        endAt: data.endAt || '',
        location: data.location || '',
        capacity: data.capacity || 0,
        enrolledCount: 0,
        recognizedMinutes: data.recognizedMinutes || 0,
        eligibleGrades: data.eligibleGrades || [],
        registrationOpenAt: data.registrationOpenAt || '',
        registrationCloseAt: data.registrationCloseAt || '',
        status: 'draft',
        version: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        evidenceRequirements: data.evidenceRequirements || '',
      };
      return delay(clone(p));
    },
    async update(id: string, data: Partial<Project>): Promise<Project> {
      const p = MOCK_PROJECTS.find(p => p.id === id);
      if (!p) throw new Error('Project not found');
      Object.assign(p, data, { updatedAt: new Date().toISOString(), version: p.version + 1 });
      return delay(clone(p));
    },
    async publish(id: string): Promise<Project> {
      return this.update(id, { status: 'published' });
    },
    async close(id: string): Promise<Project> {
      return this.update(id, { status: 'closed' });
    },
    async archive(id: string): Promise<Project> {
      return this.update(id, { status: 'archived' });
    },
  },

  eventProposals: {
    async list(): Promise<EventProposal[]> {
      return delay(clone(MOCK_PROPOSALS).map(stampCategoryName));
    },
    async get(id: string): Promise<EventProposal> {
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      return delay(stampCategoryName(clone(p)));
    },
    async create(data: Partial<EventProposal>): Promise<EventProposal> {
      const categoryId = data.categoryId || 'cat_other';
      const p: EventProposal = {
        id: `prop_${Date.now()}`,
        title: data.title || '',
        status: 'draft',
        myRole: 'Student Initiator',
        categoryId,
        categoryName: resolveCategoryName(categoryId, i18n.language),
        plannedDate: data.plannedDate || '',
        location: data.location || '',
        estimatedDurationMinutes: data.estimatedDurationMinutes || 0,
        estimatedCapacity: data.estimatedCapacity || 0,
        goal: data.goal || '',
        description: data.description || '',
        expectedOutcomes: data.expectedOutcomes || '',
        activityFormat: data.activityFormat || '',
        targetParticipants: data.targetParticipants || '',
        volunteerTasks: data.volunteerTasks || '',
        initiatorName: data.initiatorName || '',
        coOrganizers: data.coOrganizers || '',
        mentorTeacher: data.mentorTeacher || '',
        partnerOrg: data.partnerOrg || '',
        contactInfo: data.contactInfo || '',
        riskMeasures: data.riskMeasures || '',
        materialNeeds: data.materialNeeds || '',
        budgetNeeds: data.budgetNeeds || '',
        recruitmentMethod: data.recruitmentMethod || '',
        eligibility: data.eligibility || '',
        proofMethod: data.proofMethod || '',
        suggestedMinutes: data.suggestedMinutes || 0,
        currentApprovalNode: 'Draft',
        revisionCount: 0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        timeline: [],
      };
      return delay(clone(p));
    },
    async update(id: string, data: Partial<EventProposal>): Promise<EventProposal> {
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      Object.assign(p, data, { updatedAt: new Date().toISOString() });
      return delay(clone(p));
    },
    async submit(id: string): Promise<EventProposal> {
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      p.status = 'submitted';
      p.submittedAt = new Date().toISOString();
      p.updatedAt = new Date().toISOString();
      p.timeline.push({ action: 'Submitted for Review', actor: p.initiatorName, time: p.submittedAt });
      return delay(clone(p));
    },
    async withdraw(id: string): Promise<EventProposal> {
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      p.status = 'withdrawn';
      p.updatedAt = new Date().toISOString();
      return delay(clone(p));
    },
    async review(id: string, decision: string, comment?: string): Promise<EventProposal> {
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      if (decision === 'approve') p.status = 'approved';
      else if (decision === 'reject') p.status = 'rejected';
      else if (decision === 'requestChanges') { p.status = 'changesRequested'; p.revisionCount++; }
      p.feedback = comment;
      p.updatedAt = new Date().toISOString();
      p.timeline.push({ action: decision, actor: 'Reviewer', time: p.updatedAt, note: comment });
      return delay(clone(p));
    },
  },

  applications: {
    async list(): Promise<Application[]> {
      return delay(clone(MOCK_APPLICATIONS));
    },
    async create(projectId: string, studentId: string, studentName: string): Promise<Application> {
      const proj = MOCK_PROJECTS.find(p => p.id === projectId);
      if (!proj) throw new Error('项目不存在');
      const app: Application = {
        id: `app_${Date.now()}`,
        projectId,
        projectTitle: proj.title,
        studentId,
        studentName,
        status: 'pending',
        appliedAt: new Date().toISOString(),
      };
      MOCK_APPLICATIONS.push(app);
      proj.enrolledCount++;
      return delay(clone(app));
    },
    async cancel(id: string): Promise<Application> {
      const app = MOCK_APPLICATIONS.find(a => a.id === id);
      if (!app) throw new Error('Application not found');
      app.status = 'cancelled';
      app.cancelledAt = new Date().toISOString();
      return delay(clone(app));
    },
    async confirm(id: string): Promise<Application> {
      const app = MOCK_APPLICATIONS.find(a => a.id === id);
      if (!app) throw new Error('Application not found');
      app.status = 'confirmed';
      app.confirmedAt = new Date().toISOString();
      return delay(clone(app));
    },
  },

  serviceRecords: {
    async list(): Promise<ServiceRecord[]> {
      return delay(clone(MOCK_RECORDS).map(stampServiceCategoryName));
    },
    async get(id: string): Promise<ServiceRecord> {
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      return delay(stampServiceCategoryName(clone(r)));
    },
    async create(data: Partial<ServiceRecord>): Promise<ServiceRecord> {
      const serviceCategoryId = data.serviceCategoryId || 'cat_other';
      const r: ServiceRecord = {
        id: `rec_${Date.now()}`,
        studentId: data.studentId || 'std_001',
        studentName: data.studentName || 'Jia Ming',
        projectId: data.projectId,
        projectTitle: data.projectTitle,
        sourceType: data.sourceType || 'self',
        serviceDate: data.serviceDate || '',
        startAt: data.startAt || '',
        endAt: data.endAt || '',
        durationMinutes: data.durationMinutes || 0,
        location: data.location || '',
        organizationId: data.organizationId,
        organizationName: data.organizationName || '',
        serviceCategoryId,
        serviceCategoryName: resolveCategoryName(serviceCategoryId, i18n.language),
        description: data.description || '',
        supervisorName: data.supervisorName || '',
        supervisorContact: data.supervisorContact || '',
        evidenceFiles: data.evidenceFiles || [],
        status: 'draft',
        version: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      MOCK_RECORDS.push(r);
      return delay(clone(r));
    },
    async update(id: string, data: Partial<ServiceRecord>): Promise<ServiceRecord> {
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      Object.assign(r, data, { updatedAt: new Date().toISOString(), version: r.version + 1 });
      return delay(clone(r));
    },
    async delete(id: string): Promise<void> {
      const idx = MOCK_RECORDS.findIndex(r => r.id === id);
      if (idx >= 0) MOCK_RECORDS.splice(idx, 1);
      return delay(undefined);
    },
    async submit(id: string): Promise<ServiceRecord> {
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      r.status = 'submitted';
      r.submittedAt = new Date().toISOString();
      r.updatedAt = new Date().toISOString();
      return delay(clone(r));
    },
  },

  reviewQueue: {
    async list(): Promise<ReviewQueueItem[]> {
      return delay(clone(MOCK_REVIEW_QUEUE));
    },
    async summary(): Promise<{ pending: number; urgent: number; avgDays: number }> {
      const pending = MOCK_REVIEW_QUEUE.length;
      const urgent = MOCK_REVIEW_QUEUE.filter(r => r.daysWaiting >= 7 || r.hasAnomaly).length;
      const avgDays = Math.round(MOCK_REVIEW_QUEUE.reduce((s, r) => s + r.daysWaiting, 0) / pending * 10) / 10;
      return delay({ pending, urgent, avgDays });
    },
    async decide(recordId: string, decision: string, comment?: string): Promise<ServiceRecord> {
      const r = MOCK_RECORDS.find(r => r.id === recordId);
      if (!r) throw new Error('Record not found');
      if (decision === 'approve') {
        r.status = 'approved';
        r.reviewSummary = comment || 'Approved';
        r.reviewedAt = new Date().toISOString();
      } else if (decision === 'returnForChanges') {
        r.status = 'returned';
        r.returnReason = comment || 'Please provide additional documentation';
        r.reviewedAt = new Date().toISOString();
      } else if (decision === 'reject') {
        r.status = 'rejected';
        r.rejectReason = comment || 'Does not meet recognition standards';
        r.reviewedAt = new Date().toISOString();
      }
      r.updatedAt = new Date().toISOString();
      const qIdx = MOCK_REVIEW_QUEUE.findIndex(q => q.serviceRecordId === recordId);
      if (qIdx >= 0) MOCK_REVIEW_QUEUE.splice(qIdx, 1);
      return delay(clone(r));
    },
  },

  accounts: {
    async get(studentId: string): Promise<Account> {
      return delay(clone(MOCK_ACCOUNT));
    },
    async transactions(accountId: string): Promise<AccountTransaction[]> {
      return delay(clone(MOCK_TRANSACTIONS));
    },
  },

  students: {
    async list(): Promise<Student[]> {
      return delay(clone(MOCK_STUDENTS));
    },
    async get(id: string): Promise<Student> {
      const s = MOCK_STUDENTS.find(s => s.id === id);
      if (!s) throw new Error('Student not found');
      return delay(clone(s));
    },
    async progress(id: string): Promise<StudentProgress> {
      const progress = clone(MOCK_PROGRESS);
      progress.byCategory = progress.byCategory.map(c => ({ ...c, categoryName: resolveCategoryName(c.categoryId, i18n.language) }));
      return delay(progress);
    },
  },

  organizations: {
    async list(): Promise<Organization[]> {
      return delay(clone(MOCK_ORGANIZATIONS));
    },
  },

  notifications: {
    async list(): Promise<Notification[]> {
      return delay(clone(MOCK_NOTIFICATIONS));
    },
    async unreadCount(): Promise<number> {
      return delay(MOCK_NOTIFICATIONS.filter(n => !n.isRead).length);
    },
    async markRead(id: string): Promise<void> {
      const n = MOCK_NOTIFICATIONS.find(n => n.id === id);
      if (n) n.isRead = true;
      return delay(undefined);
    },
    async markAllRead(): Promise<void> {
      MOCK_NOTIFICATIONS.forEach(n => { n.isRead = true; });
      return delay(undefined);
    },
  },

  dashboards: {
    async student(): Promise<{ progress: StudentProgress; account: Account; recentRecords: ServiceRecord[]; upcomingProjects: Project[]; notifications: Notification[] }> {
      const progress = clone(MOCK_PROGRESS);
      progress.byCategory = progress.byCategory.map(c => ({ ...c, categoryName: resolveCategoryName(c.categoryId, i18n.language) }));
      return delay({
        progress,
        account: clone(MOCK_ACCOUNT),
        recentRecords: clone(MOCK_RECORDS.slice(0, 5)).map(stampServiceCategoryName),
        upcomingProjects: clone(MOCK_PROJECTS.filter(p => p.status === 'registrationOpen').slice(0, 3)).map(stampCategoryName),
        notifications: clone(MOCK_NOTIFICATIONS),
      });
    },
    async admin(): Promise<{ stats: AdminStats; pendingReviews: ReviewQueueItem[]; activeProjects: Project[] }> {
      return delay({
        stats: clone(MOCK_ADMIN_STATS),
        pendingReviews: clone(MOCK_REVIEW_QUEUE.slice(0, 5)),
        activeProjects: clone(MOCK_PROJECTS.filter(p => ['registrationOpen', 'inProgress'].includes(p.status)).slice(0, 3)).map(stampCategoryName),
      });
    },
  },

  reports: {
    async data(): Promise<ReportData> {
      return delay(clone(MOCK_REPORT_DATA));
    },
  },

  users: {
    async list(): Promise<User[]> {
      return delay(clone(MOCK_USERS));
    },
  },
};
