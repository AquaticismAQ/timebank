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
import { http, buildListQuery, getWithEtag, type PageResult } from './http';
import { isRealDomain } from './config';
import { paginateMock, toBackendEnum, fromBackendEnum, type ListParams } from '../lib/pagination';
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

// Real StudentListItemDto/StudentDetailDto field names/casing genuinely
// differ from the frontend Student shape (displayName not name, gradeName
// not grade, status is UPPER_SNAKE) — mapped here once, not passed through
// raw (I1 finding: caught live as a `student.name[0]` crash before this fix).
interface StudentListItemDtoWire {
  id: string; studentNumber: string; displayName: string;
  gradeId: string; gradeName: string; classId: string; className: string;
  status: string; graduationYear: number; version: number;
  email?: string; guardianName?: string; guardianContact?: string; joinedAt?: string;
}
/** Backend enum fields (status, sourceType, type, ...) arrive UPPER_SNAKE; the frontend renders/compares camelCase literals directly in JSX. Run every real-branch response through this for whichever fields the resource has. */
function mapEnumFields<T extends object>(obj: T, fields: (keyof T)[]): T {
  const result = { ...obj } as unknown as Record<string, unknown>;
  for (const field of fields) {
    const value = result[field as string];
    if (typeof value === 'string') {
      result[field as string] = fromBackendEnum(value);
    }
  }
  return result as unknown as T;
}

/** `/service-records` and `/dashboards/*`'s embedded `recentRecords` both return the same real `ServiceRecordDto` — one mapper, both call sites. */
function fromServiceRecordDto(dto: ServiceRecord & { evidenceFileIds?: string[] }): ServiceRecord {
  return mapEnumFields({ ...dto, evidenceFiles: dto.evidenceFiles ?? [] }, ['status', 'sourceType']);
}

/** `/review-queue` and `/dashboards/admin`'s embedded `pendingReviews` both return the same real `ReviewQueueItemDto`, including the raw SUBMITTED status name — one mapper, both call sites. */
function fromReviewQueueItemDto(item: Omit<ReviewQueueItem, 'status'> & { status: string }): ReviewQueueItem {
  return { ...item, status: (item.status === 'SUBMITTED' ? 'pending' : fromBackendEnum(item.status)) as ReviewQueueItem['status'] };
}

type AccountDtoWire = { id: string; studentId: string; balance: number; frozen: boolean; updatedAt: string };
/** `/accounts` and `/dashboards/student`'s embedded `account` both return the same real `AccountDto` — see the totalEarned/frozen approximation note at the `accounts.get` call site. */
function fromAccountDto(dto: AccountDtoWire): Account {
  return {
    id: dto.id, studentId: dto.studentId, balance: dto.balance,
    totalEarned: dto.balance, totalRedeemed: 0, borrowed: 0,
    frozen: dto.frozen ? dto.balance : 0, updatedAt: dto.updatedAt,
  };
}

function categoryFromTemplateCode(templateCode: string): Notification['category'] {
  const code = templateCode.toUpperCase();
  if (code.includes('REVIEW')) return 'review';
  if (code.includes('PROJECT') || code.includes('PROPOSAL')) return 'project';
  if (code.includes('ACCOUNT') || code.includes('CREDIT') || code.includes('ADJUST')) return 'account';
  if (code.includes('RULE')) return 'rules';
  return 'system';
}

function fromStudentDto(dto: StudentListItemDtoWire): Student {
  return {
    id: dto.id,
    studentNumber: dto.studentNumber,
    name: dto.displayName,
    gradeId: dto.gradeId,
    grade: dto.gradeName,
    classId: dto.classId,
    className: dto.className,
    email: dto.email ?? '',
    status: dto.status.toLowerCase() as Student['status'],
    joinedAt: dto.joinedAt ?? '',
    guardianName: dto.guardianName,
    guardianContact: dto.guardianContact,
  };
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
      if (isRealDomain('school')) {
        return http.get<SchoolInfo>('/school/info');
      }
      return delay(clone(MOCK_SCHOOL_INFO));
    },
  },

  projects: {
    async list(params?: ListParams): Promise<PageResult<Project>> {
      if (isRealDomain('projects')) {
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        const page = await http.getPage<Project>('/projects', buildListQuery({ ...params, filter }));
        return { data: page.data.map(p => mapEnumFields(p, ['status', 'myApplicationStatus'])), page: page.page };
      }
      return paginateMock(clone(MOCK_PROJECTS).map(stampCategoryName), params, {
        filters: {
          status: (p, v) => p.status === v,
          categoryId: (p, v) => p.categoryId === v,
        },
        searchFields: p => [p.title, p.organizationName],
        sort: {
          createdAt: (a, b) => a.createdAt.localeCompare(b.createdAt),
          startAt: (a, b) => a.startAt.localeCompare(b.startAt),
          enrolledCount: (a, b) => a.enrolledCount - b.enrolledCount,
        },
      });
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
    async list(params?: ListParams): Promise<PageResult<EventProposal>> {
      if (isRealDomain('eventProposals')) {
        // Real ProposalDto's shape diverges from EventProposal far beyond
        // casing (plannedStartAt/plannedEndAt not plannedDate,
        // proposerStudentName not initiatorName, proposedRecognizedMinutes
        // is one field where the frontend has two (estimatedDurationMinutes
        // /suggestedMinutes), and ~15 form-only fields — myRole,
        // activityFormat, mentorTeacher, partnerOrg, contactInfo,
        // riskMeasures, materialNeeds, budgetNeeds, recruitmentMethod,
        // eligibility, proofMethod, currentApprovalNode, linkedProjectTitle,
        // timeline — don't exist on the backend at all. Mapped here only
        // for what the LIST view actually reads (title/status/categoryName/
        // plannedDate/initiatorName); the rest stay undefined, which is
        // fine for the list (I0.11-style detail-view field reconciliation
        // is I1's list-swap doesn't need it — event-proposals.get was
        // never wired real, so no detail screen depends on it yet).
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        type ProposalDtoWire = EventProposal & {
          proposerStudentName?: string; plannedStartAt?: string; proposedRecognizedMinutes?: number;
        };
        const page = await http.getPage<ProposalDtoWire>('/event-proposals', buildListQuery({ ...params, filter }));
        const data = page.data.map(dto => mapEnumFields({
          ...dto,
          initiatorName: dto.proposerStudentName ?? dto.initiatorName,
          plannedDate: dto.plannedStartAt ?? dto.plannedDate,
          estimatedDurationMinutes: dto.proposedRecognizedMinutes ?? dto.estimatedDurationMinutes,
          suggestedMinutes: dto.proposedRecognizedMinutes ?? dto.suggestedMinutes,
        }, ['status']));
        return { data, page: page.page };
      }
      return paginateMock(clone(MOCK_PROPOSALS).map(stampCategoryName), params, {
        filters: {
          status: (p, v) => p.status === v,
          categoryId: (p, v) => p.categoryId === v,
        },
        searchFields: p => [p.title],
      });
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
    async list(params?: ListParams): Promise<PageResult<Application>> {
      if (isRealDomain('applications')) {
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        const page = await http.getPage<Application>('/applications', buildListQuery({ ...params, filter }));
        return { data: page.data.map(a => mapEnumFields(a, ['status'])), page: page.page };
      }
      return paginateMock(clone(MOCK_APPLICATIONS), params, {
        filters: { status: (a, v) => a.status === v },
      });
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
    async list(params?: ListParams): Promise<PageResult<ServiceRecord>> {
      if (isRealDomain('serviceRecords')) {
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        if (filter.sourceType) filter.sourceType = toBackendEnum(filter.sourceType);
        // Real ServiceRecordDto has `evidenceFileIds: string[]`, not
        // `evidenceFiles: EvidenceFile[]` (no denormalized name/size/url —
        // that needs a per-file GET /files/{id}, out of this list read's
        // scope) — defaulted to [] rather than left undefined, since
        // ServiceRecords.tsx calls `.length`/`.map()` on it directly. See `fromServiceRecordDto`.
        const page = await http.getPage<ServiceRecord & { evidenceFileIds?: string[] }>('/service-records', buildListQuery({ ...params, filter }));
        const data = page.data.map(fromServiceRecordDto);
        return { data, page: page.page };
      }
      return paginateMock(clone(MOCK_RECORDS).map(stampServiceCategoryName), params, {
        filters: {
          status: (r, v) => r.status === v,
          sourceType: (r, v) => r.sourceType === v,
          serviceCategoryId: (r, v) => r.serviceCategoryId === v,
        },
        // studentName/organizationName only — matches what the real backend actually searches (I1 finding).
        searchFields: r => [r.studentName, r.organizationName],
      });
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
    async list(params?: ListParams): Promise<PageResult<ReviewQueueItem>> {
      if (isRealDomain('reviewQueue')) {
        // Backend sends the raw ServiceRecordStatus name (SUBMITTED/
        // UNDER_REVIEW); the frontend's ReviewStatus vocabulary calls the
        // former 'pending', not 'submitted' — mapped explicitly rather
        // than a blind fromBackendEnum (which would produce 'submitted',
        // not a valid ReviewStatus literal). See `fromReviewQueueItemDto`.
        const page = await http.getPage<ReviewQueueItem>('/review-queue', buildListQuery(params));
        const data = page.data.map(fromReviewQueueItemDto);
        return { data, page: page.page };
      }
      return paginateMock(clone(MOCK_REVIEW_QUEUE), params, {
        filters: { hasAnomaly: (r, v) => r.hasAnomaly === (v === 'true') },
        searchFields: r => [r.studentName, r.organizationName, r.projectTitle],
        sort: {
          daysWaiting: (a, b) => a.daysWaiting - b.daysWaiting,
          submittedAt: (a, b) => a.submittedAt.localeCompare(b.submittedAt),
          durationMinutes: (a, b) => a.durationMinutes - b.durationMinutes,
        },
      });
    },
    async summary(): Promise<{ pending: number; urgent: number; avgDays: number }> {
      if (isRealDomain('reviewQueue')) {
        // Real ReviewQueueSummaryDto has no avgDays — pendingCount/anomalyCount/overdueCount only.
        // Honest gap, not computed here; a future backend pass would need to add it.
        const summary = await http.get<{ pendingCount: number; anomalyCount: number; overdueCount: number }>('/review-queue/summary');
        return { pending: summary.pendingCount, urgent: summary.anomalyCount, avgDays: 0 };
      }
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
    // Contract §13.1: keyed by account id server-side, resolved here via
    // filter[studentId] then the returned accountId — not a student-scoped
    // account endpoint.
    async get(studentId: string): Promise<Account> {
      if (isRealDomain('accounts')) {
        // Real AccountDto has no totalEarned/totalRedeemed/borrowed at all
        // (Phase 4 concepts, unimplemented — always 0, not a mock gap), and
        // `frozen` is a boolean flag (is this account frozen?), not a
        // frozen-amount like the frontend's `frozen: number` — approximated
        // as "whole balance" when frozen, since freezing blocks all new
        // credits rather than freezing a partial amount. See `fromAccountDto`.
        const page = await http.getPage<AccountDtoWire>('/accounts', buildListQuery({ filter: { studentId } }));
        const dto = page.data[0];
        if (!dto) throw new Error('Account not found');
        return fromAccountDto(dto);
      }
      return delay(clone(MOCK_ACCOUNT));
    },
    async transactions(accountId: string, params?: ListParams): Promise<PageResult<AccountTransaction>> {
      if (isRealDomain('accounts')) {
        // Real AccountTransactionDto has no balanceBefore (derivable:
        // balanceAfter - amount), description (reasonCode stands in), or
        // operatorName (only operatorId — no bulk user-name lookup wired
        // for this list, shows the id rather than a blank).
        type AccountTransactionDtoWire = {
          id: string; accountId: string; type: string; amount: number; balanceAfter: number;
          sourceId?: string; reasonCode: string; operatorId: string; createdAt: string;
        };
        const page = await http.getPage<AccountTransactionDtoWire>(`/accounts/${accountId}/transactions`, buildListQuery(params));
        const data: AccountTransaction[] = page.data.map(dto => ({
          id: dto.id,
          accountId: dto.accountId,
          type: fromBackendEnum(dto.type) as AccountTransaction['type'],
          amount: dto.amount,
          balanceBefore: dto.balanceAfter - dto.amount,
          balanceAfter: dto.balanceAfter,
          relatedRecordId: dto.sourceId,
          description: dto.reasonCode,
          operatorName: dto.operatorId,
          createdAt: dto.createdAt,
        }));
        return { data, page: page.page };
      }
      return paginateMock(clone(MOCK_TRANSACTIONS), params, {
        sort: { createdAt: (a, b) => a.createdAt.localeCompare(b.createdAt) },
      });
    },
  },

  students: {
    async list(params?: ListParams): Promise<PageResult<Student>> {
      if (isRealDomain('students')) {
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        const page = await http.getPage<StudentListItemDtoWire>('/students', buildListQuery({ ...params, filter }));
        return { data: page.data.map(fromStudentDto), page: page.page };
      }
      return paginateMock(clone(MOCK_STUDENTS), params, {
        filters: {
          gradeId: (s, v) => s.gradeId === v,
          classId: (s, v) => s.classId === v,
          status: (s, v) => s.status === v,
        },
        searchFields: s => [s.name, s.studentNumber],
        sort: {
          name: (a, b) => a.name.localeCompare(b.name),
          grade: (a, b) => a.grade.localeCompare(b.grade),
          completedHours: (a, b) => (a.completedMinutes ?? 0) - (b.completedMinutes ?? 0),
          timeCoinBalance: (a, b) => (a.timeCoinBalance ?? 0) - (b.timeCoinBalance ?? 0),
        },
      });
    },
    async get(id: string): Promise<Student> {
      if (isRealDomain('students')) {
        const dto = await http.get<StudentListItemDtoWire & { email?: string; guardianName?: string; guardianContact?: string; joinedAt: string }>(`/students/${id}`);
        return fromStudentDto(dto);
      }
      const s = MOCK_STUDENTS.find(s => s.id === id);
      if (!s) throw new Error('Student not found');
      return delay(clone(s));
    },
    async progress(id: string): Promise<StudentProgress> {
      if (isRealDomain('students')) {
        // Real ProgressDto (backend doc comment: "the `*Hours` convenience
        // fields... leave the wire") has no academicYear/grade/byCategory at
        // all — grade is backfilled from the student record (one extra real
        // call, already wired); academicYear and the per-category breakdown
        // have no backing endpoint in this pass, defaulted honestly rather
        // than invented.
        type ProgressDtoWire = Omit<StudentProgress, 'academicYear' | 'grade' | 'byCategory'>;
        const [dto, student] = await Promise.all([
          http.get<ProgressDtoWire>(`/students/${id}/progress`),
          this.get(id).catch(() => undefined),
        ]);
        return { ...dto, academicYear: '', grade: student?.grade ?? '', byCategory: [] };
      }
      const progress = clone(MOCK_PROGRESS);
      progress.byCategory = progress.byCategory.map(c => ({ ...c, categoryName: resolveCategoryName(c.categoryId, i18n.language) }));
      return delay(progress);
    },
  },

  organizations: {
    async list(params?: ListParams): Promise<PageResult<Organization>> {
      if (isRealDomain('organizations')) {
        const page = await http.getPage<Organization>('/organizations', buildListQuery(params));
        return { data: page.data.map(o => mapEnumFields(o, ['status', 'type'])), page: page.page };
      }
      return paginateMock(clone(MOCK_ORGANIZATIONS), params, {
        searchFields: o => [o.name],
      });
    },
  },

  notifications: {
    async list(params?: ListParams): Promise<PageResult<Notification>> {
      if (isRealDomain('notifications')) {
        // Real NotificationDto has no `category`/`userId`/`isRead`/
        // `relatedType`/`relatedId` — it has `templateCode`/`read`/
        // `relatedResourceType`/`relatedResourceId` instead, and is
        // already scoped to the caller server-side (no userId needed).
        // `category` is derived from templateCode's prefix, defaulting to
        // 'system' — an honest heuristic, not a real backend field.
        type NotificationDtoWire = {
          id: string; templateCode: string; title: string; body: string;
          relatedResourceType?: string; relatedResourceId?: string; read: boolean; createdAt: string;
        };
        const filter = { ...params?.filter };
        const page = await http.getPage<NotificationDtoWire>('/notifications', buildListQuery({ ...params, filter }));
        const data: Notification[] = page.data.map(dto => ({
          id: dto.id,
          userId: '',
          category: categoryFromTemplateCode(dto.templateCode),
          title: dto.title,
          body: dto.body,
          relatedType: dto.relatedResourceType,
          relatedId: dto.relatedResourceId,
          isRead: dto.read,
          createdAt: dto.createdAt,
        }));
        return { data, page: page.page };
      }
      return paginateMock(clone(MOCK_NOTIFICATIONS), params, {
        filters: { read: (n, v) => n.isRead === (v === 'true') },
        sort: { createdAt: (a, b) => a.createdAt.localeCompare(b.createdAt) },
      });
    },
    // I1.7: real branch is ETag-aware (getWithEtag) via unreadCountPolled below;
    // this plain version stays for any caller that doesn't need conditional-GET.
    async unreadCount(): Promise<number> {
      if (isRealDomain('notifications')) {
        const { unreadCount } = await http.get<{ unreadCount: number }>('/notifications/unread-count');
        return unreadCount;
      }
      return delay(MOCK_NOTIFICATIONS.filter(n => !n.isRead).length);
    },
    /** Polling-friendly variant — pass back the previous etag; `unchanged: true` on a real 304, no re-render needed. */
    async unreadCountPolled(previousEtag: string | undefined): Promise<{ count: number; etag: string | undefined } | { unchanged: true }> {
      if (isRealDomain('notifications')) {
        const result = await getWithEtag<{ unreadCount: number }>('/notifications/unread-count', previousEtag);
        if ('unchanged' in result) return result;
        return { count: result.data.unreadCount, etag: result.etag };
      }
      const count = MOCK_NOTIFICATIONS.filter(n => !n.isRead).length;
      const etag = `"mock-${count}"`;
      if (previousEtag === etag) return { unchanged: true };
      return delay({ count, etag });
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
    // `notifications` isn't rendered by any screen (StudentHome.tsx never
    // reads it) — the real DashboardStudentDto carries only
    // `unreadNotificationCount`, not the array, so the real branch leaves
    // it empty rather than making an extra call for dead data.
    async student(): Promise<{ progress: StudentProgress; account: Account; recentRecords: ServiceRecord[]; upcomingProjects: Project[]; notifications: Notification[] }> {
      if (isRealDomain('dashboards')) {
        // `progress`/`account`/`recentRecords`/`upcomingProjects` here are
        // the exact same DTOs `/students/{id}/progress`, `/accounts`,
        // `/service-records`, and `/projects` return (backend
        // `DashboardStudentDto`) — so they need the exact same mapping
        // (evidenceFiles default, status/enum casing, the Phase-4 account
        // field approximation) as those standalone endpoints, not a raw
        // pass-through. `progress` still lacks academicYear/grade/byCategory
        // (see `students.progress`'s real branch) — defaulted the same way here.
        type DashboardStudentDtoWire = {
          progress: Omit<StudentProgress, 'academicYear' | 'grade' | 'byCategory'>;
          account: AccountDtoWire;
          recentRecords: (ServiceRecord & { evidenceFileIds?: string[] })[];
          upcomingProjects: Project[];
        };
        const dto = await http.get<DashboardStudentDtoWire>('/dashboards/student');
        return {
          progress: { ...dto.progress, academicYear: '', grade: '', byCategory: [] },
          account: fromAccountDto(dto.account),
          recentRecords: dto.recentRecords.map(fromServiceRecordDto),
          upcomingProjects: dto.upcomingProjects.map(p => mapEnumFields(p, ['status', 'myApplicationStatus'])),
          notifications: [],
        };
      }
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
      if (isRealDomain('dashboards')) {
        // Field names/units genuinely differ from the mock's AdminStats
        // shape (totalServiceHoursThisYear not totalServiceHours,
        // reviewAvgHours not reviewAvgDays, no avgCompletionRate at all) —
        // mapped here, not passed through raw.
        const dto = await http.get<{
          stats: {
            totalStudents: number; activeStudents: number; totalProjects: number; activeProjects: number;
            pendingReviews: number; approvedThisMonth: number; totalServiceHoursThisYear: number; reviewAvgHours?: number;
          };
          pendingReviews: ReviewQueueItem[];
          activeProjects: Project[];
        }>('/dashboards/admin');
        return {
          stats: {
            totalStudents: dto.stats.totalStudents,
            activeStudents: dto.stats.activeStudents,
            totalProjects: dto.stats.totalProjects,
            activeProjects: dto.stats.activeProjects,
            pendingReviews: dto.stats.pendingReviews,
            approvedThisMonth: dto.stats.approvedThisMonth,
            totalServiceHours: dto.stats.totalServiceHoursThisYear,
            // `reviewAvgHours` is a nullable Double server-side and the
            // backend's global Jackson config omits null fields from the
            // wire entirely (application.yml `default-property-inclusion:
            // non_null`) rather than sending `null` — so this is
            // `undefined`, not 0, whenever nothing's been reviewed yet.
            // Caught live: an unguarded division produced "NaN 天" on a
            // fresh-data dashboard.
            reviewAvgDays: dto.stats.reviewAvgHours == null ? 0 : Math.round((dto.stats.reviewAvgHours / 24) * 10) / 10,
          },
          // `pendingReviews`/`activeProjects` here are the exact same DTOs
          // `/review-queue` and `/projects` return — need the same mapping
          // (SUBMITTED->pending status, enum casing), not a raw pass-through
          // or (for projects) the mock-only `stampCategoryName` call this
          // previously had, which doesn't apply to real ProjectDto (it
          // already carries a real `categoryName`, unlike the mock fixture).
          pendingReviews: dto.pendingReviews.map(fromReviewQueueItemDto),
          activeProjects: dto.activeProjects.map(p => mapEnumFields(p, ['status', 'myApplicationStatus'])),
        };
      }
      return delay({
        stats: clone(MOCK_ADMIN_STATS),
        pendingReviews: clone(MOCK_REVIEW_QUEUE.slice(0, 5)),
        activeProjects: clone(MOCK_PROJECTS.filter(p => ['registrationOpen', 'inProgress'].includes(p.status)).slice(0, 3)).map(stampCategoryName),
      });
    },
  },

  reports: {
    // I1.6: composes the real report endpoints in parallel (contract §4.13)
    // — field names/units differ from the mock's ReportData shape
    // (gradeName not grade, totalMinutes not totalHours), mapped here.
    async data(): Promise<ReportData> {
      if (isRealDomain('reports')) {
        const [participation, completion, serviceHours, topProjects] = await Promise.all([
          http.get<{ byGrade: { gradeName: string; totalStudents: number; participatedStudents: number; participationRate: number }[] }>('/reports/participation'),
          http.get<{ byGrade: { gradeName: string; totalStudents: number; completedStudents: number; completionRate: number }[] }>('/reports/completion'),
          http.get<{ byMonth: { month: string; hours: number }[] }>('/reports/service-hours'),
          http.get<{ topProjects: { title: string; participants: number; totalMinutes: number }[] }>('/reports/top-projects'),
        ]);
        return {
          participationByGrade: participation.byGrade.map(g => ({ grade: g.gradeName, total: g.totalStudents, participated: g.participatedStudents, rate: g.participationRate })),
          completionByGrade: completion.byGrade.map(g => ({ grade: g.gradeName, total: g.totalStudents, completed: g.completedStudents, rate: g.completionRate })),
          serviceHoursByMonth: serviceHours.byMonth,
          topProjects: topProjects.topProjects.map(p => ({ title: p.title, participants: p.participants, totalHours: Math.round(p.totalMinutes / 60) })),
        };
      }
      return delay(clone(MOCK_REPORT_DATA));
    },
  },

  users: {
    async list(): Promise<User[]> {
      return delay(clone(MOCK_USERS));
    },
  },
};
