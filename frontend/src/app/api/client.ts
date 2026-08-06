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
  Role,
  Project,
  Application,
  ServiceRecord,
  Account,
  AccountTransaction,
  StudentProgress,
  Notification,
  Student,
  ReviewQueueItem,
  Case,
  Organization,
  StudentDirectoryEntry,
  TeacherDirectoryEntry,
  AdminStats,
  ReportData,
  EventProposal,
  SchoolInfo,
  MeResponse,
  EvidenceFile,
  FileMetadata,
} from './types';
import { http, ApiError, buildListQuery, getWithEtag, withVersion, type PageResult } from './http';
import { isRealDomain } from './config';
import { paginateMock, toBackendEnum, fromBackendEnum, type ListParams } from '../lib/pagination';
import { localDateTimeToISO, isoToLocalDateTime } from '../lib/datetime';
import i18n from '../../i18n';
import { toast } from 'sonner';

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

/**
 * The proposal form only collects a single date (`plannedDate`, no time
 * fields) but `CreateProposalRequest`/`UpdateProposalRequest` require
 * distinct `plannedStartAt`/`plannedEndAt` instants, with the backend
 * rejecting `plannedEndAt <= plannedStartAt` (`ProposalServiceImpl`'s
 * INVALID_RANGE check). Sending `plannedDate` verbatim for both — as bare
 * date strings, not even ISO instants — guaranteed a validation failure on
 * every single proposal create/update against the real backend (found live,
 * not by inspection). Defaults to a 9am local start and derives the end
 * from the proposal's own duration estimate.
 */
function plannedStartEnd(plannedDate: string | undefined, durationMinutes: number): { plannedStartAt?: string; plannedEndAt?: string } {
  if (!plannedDate) return {};
  const plannedStartAt = localDateTimeToISO(plannedDate, '09:00');
  const plannedEndAt = new Date(new Date(plannedStartAt).getTime() + Math.max(durationMinutes, 1) * 60000).toISOString();
  return { plannedStartAt, plannedEndAt };
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
    version: dto.version,
  };
}

function meResponseToUser(me: MeResponse, username: string): User {
  const roleMap: Record<string, Role> = { admin: 'admin', teacher: 'teacher', student: 'student', sysadmin: 'admin' };
  return {
    id: me.user.id,
    username,
    password: '',
    displayName: me.user.displayName,
    roles: me.user.roles.map(r => roleMap[r.toLowerCase()] ?? 'admin') as Role[],
    workspaces: me.workspaces,
    studentId: me.user.studentId ?? undefined,
    email: '',
    permissions: me.user.permissions,
    preferredLanguage: me.preferredLanguage,
    featureFlags: me.featureFlags,
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
      if (isRealDomain('auth')) {
        await http.get<void>('/auth/csrf');
        const me = await http.post<MeResponse>('/auth/login', { username, password });
        const user: User = meResponseToUser(me, username);
        return { user };
      }
      const user = MOCK_USERS.find(u => u.username === username && u.password === password);
      if (!user) throw new Error('Invalid username or password');
      return delay({ user: clone(user) });
    },
    async me(): Promise<User | MeResponse> {
      if (isRealDomain('auth')) {
        return http.get<MeResponse>('/me');
      }
      const stored = localStorage.getItem('timebank-user');
      if (stored) return delay(clone(JSON.parse(stored)));
      throw new Error('Not logged in');
    },
    async logout(): Promise<void> {
      if (isRealDomain('auth')) {
        await http.post<void>('/auth/logout');
        return;
      }
      return delay(undefined);
    },
    // I3.4: `PATCH /me/profile` — self-service fields only (displayName/
    // email/preferredLanguage). `localStorage['timebank-lang']` stays as a
    // cache for the pre-login paint only; once a real session exists this
    // is the source of truth, read back via `/me` on next login.
    async updateProfile(data: { displayName?: string; email?: string; preferredLanguage?: 'zh-CN' | 'en' }): Promise<void> {
      if (isRealDomain('auth')) {
        await http.patch<void>('/me/profile', data);
        return;
      }
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
      if (isRealDomain('projects')) {
        const body = {
          title: data.title,
          summary: data.summary,
          description: data.description,
          categoryId: data.categoryId,
          organizationId: data.organizationId,
          startAt: data.startAt,
          endAt: data.endAt,
          location: data.location,
          capacity: data.capacity,
          recognizedMinutes: data.recognizedMinutes,
          eligibleGradeIds: data.eligibleGrades,
          evidenceRequirements: data.evidenceRequirements,
          registrationOpenAt: data.registrationOpenAt,
          registrationCloseAt: data.registrationCloseAt,
        };
        const dto = await http.post<Project>('/projects', body);
        return mapEnumFields(dto, ['status']);
      }
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
      if (isRealDomain('projects')) {
        const body = withVersion({
          title: data.title,
          summary: data.summary,
          description: data.description,
          categoryId: data.categoryId,
          organizationId: data.organizationId,
          startAt: data.startAt,
          endAt: data.endAt,
          location: data.location,
          capacity: data.capacity,
          recognizedMinutes: data.recognizedMinutes,
          eligibleGradeIds: data.eligibleGrades,
          evidenceRequirements: data.evidenceRequirements,
          registrationOpenAt: data.registrationOpenAt,
          registrationCloseAt: data.registrationCloseAt,
        }, data.version!);
        const dto = await http.patch<Project>(`/projects/${id}`, body);
        return mapEnumFields(dto, ['status']);
      }
      const p = MOCK_PROJECTS.find(p => p.id === id);
      if (!p) throw new Error('Project not found');
      Object.assign(p, data, { updatedAt: new Date().toISOString(), version: p.version + 1 });
      return delay(clone(p));
    },
    async publish(id: string, version?: number): Promise<Project> {
      if (isRealDomain('projects')) {
        const dto = await http.post<Project>(`/projects/${id}/publish`, { version });
        return mapEnumFields(dto, ['status']);
      }
      return this.update(id, { status: 'published' });
    },
    async close(id: string, version?: number): Promise<Project> {
      if (isRealDomain('projects')) {
        const dto = await http.post<Project>(`/projects/${id}/close`, { version });
        return mapEnumFields(dto, ['status']);
      }
      return this.update(id, { status: 'closed' });
    },
    async archive(id: string, version?: number): Promise<Project> {
      if (isRealDomain('projects')) {
        const dto = await http.post<Project>(`/projects/${id}/archive`, { version });
        return mapEnumFields(dto, ['status']);
      }
      return this.update(id, { status: 'archived' });
    },
  },

  eventProposals: {
    async list(params?: ListParams): Promise<PageResult<EventProposal>> {
      if (isRealDomain('eventProposals')) {
        // Real ProposalDto's shape diverges from EventProposal beyond casing
        // (objective/expectedOutcome/format/capacity/safetyPlan/
        // resourceNeeds/recruitmentPlan/evidencePlan vs goal/
        // expectedOutcomes/activityFormat/estimatedCapacity/riskMeasures/
        // materialNeeds+budgetNeeds/recruitmentMethod/proofMethod) and a
        // handful of fields — myRole, currentApprovalNode, reviewerName,
        // feedback, linkedProjectId/Title, revisionCount, timeline,
        // eligibility — genuinely don't exist on the backend at all (no
        // safe default beyond ''/derived-display, never written back by
        // the form). All of the above were originally left unmapped since
        // the list view itself never read them — but `openForm(p, 'edit')`
        // (`Docs/implementation/00-Status.md` §30) now pre-fills the real
        // edit form straight from this list data, and an unmapped field
        // isn't just "missing," it's the literal string "undefined" landing
        // in a real saved proposal if the student doesn't happen to retype
        // it. `materialNeeds`/`budgetNeeds` stay a lossy split of one
        // backend field (whole value into materialNeeds, budgetNeeds
        // blank) — an unedited resave rejoins to the same original string
        // since `formToApiData`'s `.filter(Boolean).join('\n')` drops the
        // blank half, so this doesn't lose data on a no-touch edit.
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        type ProposalDtoWire = Omit<EventProposal, 'coOrganizerStudentIds'> & {
          proposerStudentName?: string; plannedStartAt?: string; proposedRecognizedMinutes?: number;
          proposedSupervisorName?: string; organizationName?: string;
          objective?: string; expectedOutcome?: string; format?: string; capacity?: number;
          safetyPlan?: string; resourceNeeds?: string; recruitmentPlan?: string; evidencePlan?: string;
          externalContact?: string; coOrganizerStudentIds?: string[];
        };
        const page = await http.getPage<ProposalDtoWire>('/event-proposals', buildListQuery({ ...params, filter }));
        const data = page.data.map(dto => mapEnumFields({
          ...dto,
          initiatorName: dto.proposerStudentName ?? dto.initiatorName,
          // The real DTO's `plannedStartAt` is a full ISO instant, not the
          // `<input type="date">`-shaped "YYYY-MM-DD" string this field's
          // name elsewhere assumes — passing it straight through was
          // invisible for a fresh create (the create-branch's `plannedDate`
          // always comes from the date input itself) but crashed `openForm(p,
          // 'edit')`'s resubmit save the moment the date field wasn't
          // re-touched: `plannedStartEnd()` → `localDateTimeToISO()` splits
          // on '-' and gets a NaN month/day from the trailing "T...Z", producing
          // an Invalid Date whose `.toISOString()` throws (found live, not by
          // inspection — the resubmit call failed with a swallowed client-side
          // exception, no network request even sent).
          plannedDate: dto.plannedStartAt ? isoToLocalDateTime(dto.plannedStartAt).date : dto.plannedDate,
          estimatedDurationMinutes: dto.proposedRecognizedMinutes ?? dto.estimatedDurationMinutes,
          suggestedMinutes: dto.proposedRecognizedMinutes ?? dto.suggestedMinutes,
          // Display strings, resolved server-side — the raw ids
          // (coOrganizerStudentIds/proposedSupervisorId/organizationId)
          // pass through unchanged via the ...dto spread, for edit-prefill.
          coOrganizers: (dto.coOrganizerNames ?? []).join(', '),
          mentorTeacher: dto.proposedSupervisorName ?? dto.mentorTeacher,
          partnerOrg: dto.organizationName ?? dto.partnerOrg,
          goal: dto.objective ?? '',
          expectedOutcomes: dto.expectedOutcome ?? '',
          activityFormat: dto.format ?? '',
          estimatedCapacity: dto.capacity ?? 0,
          riskMeasures: dto.safetyPlan ?? '',
          materialNeeds: dto.resourceNeeds ?? '',
          budgetNeeds: '',
          recruitmentMethod: dto.recruitmentPlan ?? '',
          proofMethod: dto.evidencePlan ?? '',
          contactInfo: dto.externalContact ?? '',
          eligibility: '',
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
      if (isRealDomain('eventProposals')) {
        const proposedRecognizedMinutes = data.suggestedMinutes || data.estimatedDurationMinutes || 60;
        const body = {
          title: data.title,
          objective: data.goal,
          description: data.description,
          expectedOutcome: data.expectedOutcomes,
          categoryId: data.categoryId,
          ...plannedStartEnd(data.plannedDate, proposedRecognizedMinutes),
          location: data.location,
          format: data.activityFormat,
          targetParticipants: data.targetParticipants,
          capacity: data.estimatedCapacity,
          volunteerTasks: data.volunteerTasks,
          // Real ids resolved through the co-organizer/supervisor/organization
          // pickers (`Docs/clarification/01-i3-partner-school-terminology.md`)
          // — never the display strings (mentorTeacher/partnerOrg/coOrganizers),
          // which are read-only summaries the server itself resolves on GET.
          proposedSupervisorId: data.proposedSupervisorId,
          organizationId: data.organizationId,
          newOrganizationName: data.newOrganizationName,
          externalContact: data.contactInfo,
          safetyPlan: data.riskMeasures,
          resourceNeeds: [data.materialNeeds, data.budgetNeeds].filter(Boolean).join('\n'),
          recruitmentPlan: data.recruitmentMethod,
          proposedRecognizedMinutes,
          evidencePlan: data.proofMethod,
          coOrganizerStudentIds: data.coOrganizerStudentIds ?? [],
        };
        const dto = await http.post<EventProposal>('/event-proposals', body);
        return mapEnumFields(dto, ['status']);
      }
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
      if (isRealDomain('eventProposals')) {
        const proposedRecognizedMinutes = data.suggestedMinutes || data.estimatedDurationMinutes;
        const body = withVersion({
          title: data.title,
          objective: data.goal,
          description: data.description,
          expectedOutcome: data.expectedOutcomes,
          categoryId: data.categoryId,
          ...plannedStartEnd(data.plannedDate, proposedRecognizedMinutes || 60),
          location: data.location,
          format: data.activityFormat,
          targetParticipants: data.targetParticipants,
          capacity: data.estimatedCapacity,
          volunteerTasks: data.volunteerTasks,
          proposedSupervisorId: data.proposedSupervisorId,
          organizationId: data.organizationId,
          newOrganizationName: data.newOrganizationName,
          externalContact: data.contactInfo,
          safetyPlan: data.riskMeasures,
          resourceNeeds: [data.materialNeeds, data.budgetNeeds].filter(Boolean).join('\n'),
          recruitmentPlan: data.recruitmentMethod,
          proposedRecognizedMinutes,
          evidencePlan: data.proofMethod,
          coOrganizerStudentIds: data.coOrganizerStudentIds,
        }, data.version ?? 0);
        const dto = await http.patch<EventProposal>(`/event-proposals/${id}`, body);
        return mapEnumFields(dto, ['status']);
      }
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      Object.assign(p, data, { updatedAt: new Date().toISOString() });
      return delay(clone(p));
    },
    async submit(id: string, idempotencyKey?: string): Promise<EventProposal> {
      if (isRealDomain('eventProposals')) {
        const dto = await http.post<EventProposal>(`/event-proposals/${id}/submit`, undefined, idempotencyKey);
        return mapEnumFields(dto, ['status']);
      }
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      p.status = 'submitted';
      p.submittedAt = new Date().toISOString();
      p.updatedAt = new Date().toISOString();
      p.timeline.push({ action: 'Submitted for Review', actor: p.initiatorName, time: p.submittedAt });
      return delay(clone(p));
    },
    async withdraw(id: string): Promise<EventProposal> {
      if (isRealDomain('eventProposals')) {
        const dto = await http.post<EventProposal>(`/event-proposals/${id}/withdraw`);
        return mapEnumFields(dto, ['status']);
      }
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      p.status = 'withdrawn';
      p.updatedAt = new Date().toISOString();
      return delay(clone(p));
    },
    async review(id: string, decision: string, comment?: string, reasonCode?: string, proposalVersion?: number, idempotencyKey?: string): Promise<EventProposal> {
      if (isRealDomain('eventProposals')) {
        const decisionMap: Record<string, string> = { approve: 'APPROVE', reject: 'REJECT', requestChanges: 'REQUEST_CHANGES', recommendApproval: 'RECOMMEND_APPROVAL' };
        const body = {
          decision: decisionMap[decision] ?? toBackendEnum(decision),
          reasonCode: reasonCode || decision.toUpperCase(),
          comment,
          proposalVersion: proposalVersion!,
        };
        type ProposalReviewDto = EventProposal & { approvedProjectId?: string };
        const dto = await http.post<ProposalReviewDto>(`/event-proposals/${id}/reviews`, body, idempotencyKey);
        const mapped = mapEnumFields({
          ...dto,
          linkedProjectId: dto.approvedProjectId ?? dto.linkedProjectId,
        }, ['status']);
        if (decision === 'approve') {
          const projectId = dto.approvedProjectId ?? mapped.linkedProjectId;
          if (projectId) {
            toast.success(`Approved — project draft ${projectId} created`);
          }
        }
        return mapped;
      }
      const p = MOCK_PROPOSALS.find(p => p.id === id);
      if (!p) throw new Error('Proposal not found');
      if (decision === 'approve') {
        p.status = 'approved';
        p.linkedProjectId = p.linkedProjectId ?? `proj_${Date.now()}`;
        toast.success(`Approved — project draft ${p.linkedProjectId} created`);
      }
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
        filters: { status: (a, v) => a.status === v, projectId: (a, v) => a.projectId === v },
      });
    },
    async create(projectId: string, _studentId?: string, _studentName?: string, note?: string): Promise<Application> {
      if (isRealDomain('applications')) {
        const dto = await http.post<Application>(`/projects/${projectId}/applications`, note ? { note } : {});
        return mapEnumFields(dto, ['status']);
      }
      const proj = MOCK_PROJECTS.find(p => p.id === projectId);
      if (!proj) throw new Error('项目不存在');
      const app: Application = {
        id: `app_${Date.now()}`,
        projectId,
        projectTitle: proj.title,
        studentId: _studentId || '',
        studentName: _studentName || '',
        status: 'pending',
        appliedAt: new Date().toISOString(),
      };
      MOCK_APPLICATIONS.push(app);
      proj.enrolledCount++;
      return delay(clone(app));
    },
    async cancel(id: string): Promise<Application> {
      if (isRealDomain('applications')) {
        const dto = await http.post<Application>(`/applications/${id}/cancel`);
        return mapEnumFields(dto, ['status']);
      }
      const app = MOCK_APPLICATIONS.find(a => a.id === id);
      if (!app) throw new Error('Application not found');
      app.status = 'cancelled';
      app.cancelledAt = new Date().toISOString();
      return delay(clone(app));
    },
    async confirm(id: string): Promise<Application> {
      if (isRealDomain('applications')) {
        const dto = await http.post<Application>(`/applications/${id}/confirm`);
        return mapEnumFields(dto, ['status']);
      }
      const app = MOCK_APPLICATIONS.find(a => a.id === id);
      if (!app) throw new Error('Application not found');
      app.status = 'confirmed';
      app.confirmedAt = new Date().toISOString();
      return delay(clone(app));
    },
    async reject(id: string): Promise<Application> {
      if (isRealDomain('applications')) {
        const dto = await http.post<Application>(`/applications/${id}/reject`);
        return mapEnumFields(dto, ['status']);
      }
      const app = MOCK_APPLICATIONS.find(a => a.id === id);
      if (!app) throw new Error('Application not found');
      app.status = 'rejected';
      return delay(clone(app));
    },
    // `action` is the backend's own bulk-action vocabulary (confirm/reject/
    // markComplete — cn.timebank.enrollment.application
    // .EnrollmentBulkActionTransaction), not the frontend's camelCase enum
    // style, since it's a direct passthrough with no other mapping needed.
    async bulkActions(ids: string[], action: 'confirm' | 'reject' | 'markComplete', reasonCode?: string, comment?: string): Promise<{ id: string; success: boolean; error?: string }[]> {
      if (isRealDomain('applications')) {
        return http.post(`/applications/bulk-actions`, { ids, action, reasonCode, comment });
      }
      return delay(ids.map(id => {
        const app = MOCK_APPLICATIONS.find(a => a.id === id);
        if (app) app.status = action === 'confirm' ? 'confirmed' : action === 'reject' ? 'rejected' : 'completed';
        return { id, success: !!app, error: app ? undefined : 'Application not found' };
      }));
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
      if (isRealDomain('serviceRecords')) {
        const dto = await http.get<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}`);
        // I3.3: the detail view is exactly where evidence needs real
        // names/ids to be viewable (AuditCenter's expanded row previously
        // showed two hardcoded fake labels — "📄 Service Confirmation" /
        // "🖼 Activity Photos" — regardless of what was actually uploaded,
        // since nothing here ever resolved the real evidenceFileIds).
        const files = await Promise.all(
          (dto.evidenceFileIds ?? []).map(fileId =>
            api.files.get(fileId).then(
              meta => ({ id: fileId, name: meta.filename, type: meta.mimeType, size: meta.sizeBytes, url: '', uploadedAt: meta.createdAt, status: meta.status }),
              () => ({ id: fileId, name: fileId, type: '', size: 0, url: '', uploadedAt: '' }),
            ),
          ),
        );
        return fromServiceRecordDto({ ...dto, evidenceFiles: files });
      }
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      return delay(stampServiceCategoryName(clone(r)));
    },
    async create(data: Partial<ServiceRecord>): Promise<ServiceRecord> {
      if (isRealDomain('serviceRecords')) {
        const body = {
          sourceType: toBackendEnum(data.sourceType || 'self'),
          projectId: data.projectId,
          organizationId: data.organizationId,
          organizationName: data.organizationName,
          serviceDate: data.serviceDate,
          startAt: data.startAt,
          endAt: data.endAt,
          location: data.location,
          serviceCategoryId: data.serviceCategoryId,
          description: data.description,
          supervisorName: data.supervisorName,
          supervisorContact: data.supervisorContact,
          evidenceFileIds: (data.evidenceFiles ?? []).map(f => f.id),
          includesTravelTime: false,
        };
        const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>('/service-records', body);
        return fromServiceRecordDto(dto);
      }
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
      if (isRealDomain('serviceRecords')) {
        const body = withVersion({
          projectId: data.projectId,
          organizationId: data.organizationId,
          organizationName: data.organizationName,
          serviceDate: data.serviceDate,
          startAt: data.startAt,
          endAt: data.endAt,
          location: data.location,
          serviceCategoryId: data.serviceCategoryId,
          description: data.description,
          supervisorName: data.supervisorName,
          supervisorContact: data.supervisorContact,
          evidenceFileIds: data.evidenceFiles?.map(f => f.id),
          includesTravelTime: data.includesTravelTime,
        }, data.version!);
        const dto = await http.patch<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}`, body);
        return fromServiceRecordDto(dto);
      }
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      Object.assign(r, data, { updatedAt: new Date().toISOString(), version: r.version + 1 });
      return delay(clone(r));
    },
    async delete(id: string): Promise<void> {
      if (isRealDomain('serviceRecords')) {
        await http.delete<void>(`/service-records/${id}`);
        return;
      }
      const idx = MOCK_RECORDS.findIndex(r => r.id === id);
      if (idx >= 0) MOCK_RECORDS.splice(idx, 1);
      return delay(undefined);
    },
    async submit(id: string, idempotencyKey?: string): Promise<ServiceRecord> {
      if (isRealDomain('serviceRecords')) {
        const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}/submit`, undefined, idempotencyKey);
        return fromServiceRecordDto(dto);
      }
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      r.status = 'submitted';
      r.submittedAt = new Date().toISOString();
      r.updatedAt = new Date().toISOString();
      return delay(clone(r));
    },
    async withdraw(id: string): Promise<ServiceRecord> {
      if (isRealDomain('serviceRecords')) {
        const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}/withdraw`);
        return fromServiceRecordDto(dto);
      }
      const r = MOCK_RECORDS.find(r => r.id === id);
      if (!r) throw new Error('Record not found');
      r.status = 'draft';
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
    async decide(recordId: string, decision: string, comment?: string, reasonCode?: string, recordVersion?: number, idempotencyKey?: string): Promise<ServiceRecord> {
      if (isRealDomain('reviewQueue')) {
        const decisionMap: Record<string, string> = { approve: 'APPROVE', return: 'RETURN_FOR_CHANGES', returnForChanges: 'RETURN_FOR_CHANGES', reject: 'REJECT', escalate: 'ESCALATE' };
        const body = {
          decision: decisionMap[decision] ?? toBackendEnum(decision),
          reasonCode: reasonCode || decision.toUpperCase(),
          comment,
          recordVersion: recordVersion!,
        };
        const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${recordId}/reviews`, body, idempotencyKey);
        return fromServiceRecordDto(dto);
      }
      const r = MOCK_RECORDS.find(r => r.id === recordId);
      if (!r) throw new Error('Record not found');
      if (decision === 'approve') {
        r.status = 'approved';
        r.reviewSummary = comment || 'Approved';
        r.reviewedAt = new Date().toISOString();
      } else if (decision === 'returnForChanges' || decision === 'return') {
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
    async list(params?: ListParams): Promise<PageResult<Account>> {
      if (isRealDomain('accounts')) {
        const page = await http.getPage<AccountDtoWire>('/accounts', buildListQuery(params));
        return { data: page.data.map(fromAccountDto), page: page.page };
      }
      return paginateMock(clone(MOCK_STUDENTS).map(s => ({ id: 'acct_' + s.id, studentId: s.id, balance: s.timeCoinBalance ?? 0, totalEarned: s.timeCoinBalance ?? 0, totalRedeemed: 0, borrowed: 0, frozen: 0, updatedAt: new Date().toISOString() })), params, {});
    },
    async adjust(accountId: string, amount: number, reasonCode: string, memo?: string, idempotencyKey?: string): Promise<void> {
      if (isRealDomain('accounts')) {
        await http.post<void>(`/accounts/${accountId}/adjustments`, { amount, reasonCode, memo }, idempotencyKey);
        return;
      }
      return delay(undefined);
    },
    async reverse(transactionId: string, reasonCode: string, memo?: string, idempotencyKey?: string): Promise<void> {
      if (isRealDomain('accounts')) {
        await http.post<void>(`/account-transactions/${transactionId}/reverse`, { reasonCode, memo }, idempotencyKey);
        return;
      }
      return delay(undefined);
    },
    async freeze(accountId: string, reasonCode: string, memo?: string): Promise<void> {
      if (isRealDomain('accounts')) {
        await http.post<void>(`/accounts/${accountId}/freeze`, { reasonCode, memo });
        return;
      }
      return delay(undefined);
    },
    async unfreeze(accountId: string, reasonCode: string, memo?: string): Promise<void> {
      if (isRealDomain('accounts')) {
        await http.post<void>(`/accounts/${accountId}/unfreeze`, { reasonCode, memo });
        return;
      }
      return delay(undefined);
    },
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
    async update(id: string, data: { displayName?: string; email?: string; gradeId?: string; classId?: string; guardianName?: string; guardianContact?: string; version: number }): Promise<Student> {
      if (isRealDomain('students')) {
        const dto = await http.patch<StudentListItemDtoWire>(`/students/${id}`, data);
        return fromStudentDto(dto);
      }
      return delay({} as Student);
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
    /**
     * A co-organizer picker's search (contract §4.9,
     * `Docs/clarification/01-i3-partner-school-terminology.md`) — minimal
     * fields, capped, blank on an empty search (this is a typeahead, not a
     * browse). `excludeId` filters out the current student client-side,
     * since the backend has no way to know who "the current proposer" is
     * from a plain name search.
     */
    async directory(search: string, excludeId?: string): Promise<StudentDirectoryEntry[]> {
      if (!search) return [];
      if (isRealDomain('students')) {
        const results = await http.get<StudentDirectoryEntry[]>('/students/directory', { search });
        return results.filter(s => s.id !== excludeId);
      }
      const term = search.toLowerCase();
      return MOCK_STUDENTS
        .filter(s => s.id !== excludeId && s.name.toLowerCase().includes(term))
        .slice(0, 20)
        .map(s => ({ id: s.id, displayName: s.name, gradeName: s.grade, className: s.className }));
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
      if (isRealDomain('notifications')) {
        await http.patch<void>(`/notifications/${id}`);
        return;
      }
      const n = MOCK_NOTIFICATIONS.find(n => n.id === id);
      if (n) n.isRead = true;
      return delay(undefined);
    },
    async markAllRead(): Promise<void> {
      if (isRealDomain('notifications')) {
        await http.post<void>('/notifications/read-all');
        return;
      }
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

  cases: {
    async list(params?: ListParams): Promise<PageResult<Case>> {
      if (isRealDomain('cases')) {
        const filter = { ...params?.filter };
        if (filter.status) filter.status = toBackendEnum(filter.status);
        const page = await http.getPage<Case>('/cases', buildListQuery({ ...params, filter }));
        return { data: page.data.map(c => mapEnumFields(c, ['status', 'type'])), page: page.page };
      }
      return paginateMock<Case>([], params, {});
    },
    async get(id: string): Promise<Case> {
      if (isRealDomain('cases')) {
        const dto = await http.get<Case>(`/cases/${id}`);
        return mapEnumFields(dto, ['status', 'type']);
      }
      throw new Error('Case not found');
    },
    async create(data: { studentId?: string; type: string; subjectType?: string; subjectId?: string; title: string; description?: string; requestedAmountCentiCoins?: number; reasonCode: string }): Promise<Case> {
      if (isRealDomain('cases')) {
        const body = { ...data, type: toBackendEnum(data.type) };
        const dto = await http.post<Case>('/cases', body);
        return mapEnumFields(dto, ['status', 'type']);
      }
      throw new Error('Cases are not available in mock mode');
    },
    async update(id: string, data: { title?: string; description?: string; requestedAmountCentiCoins?: number; reasonCode?: string; version: number }): Promise<Case> {
      if (isRealDomain('cases')) {
        const dto = await http.patch<Case>(`/cases/${id}`, data);
        return mapEnumFields(dto, ['status', 'type']);
      }
      throw new Error('Cases are not available in mock mode');
    },
    async submit(id: string, idempotencyKey?: string): Promise<Case> {
      if (isRealDomain('cases')) {
        const dto = await http.post<Case>(`/cases/${id}/submit`, undefined, idempotencyKey);
        return mapEnumFields(dto, ['status', 'type']);
      }
      throw new Error('Cases are not available in mock mode');
    },
    // Decision vocabulary is CaseDecision (contract §4.8) — distinct from ReviewQueue's Decision enum,
    // not reused even though 'reject' overlaps, since the two case shapes diverge (RESOLVE/REQUEST_INFORMATION here).
    async decide(id: string, decision: 'resolve' | 'requestInformation' | 'reject', reasonCode: string, comment: string | undefined, caseVersion: number, idempotencyKey?: string): Promise<Case> {
      if (isRealDomain('cases')) {
        const body = { decision: toBackendEnum(decision), reasonCode, comment, caseVersion };
        const dto = await http.post<Case>(`/cases/${id}/decisions`, body, idempotencyKey);
        return mapEnumFields(dto, ['status', 'type']);
      }
      throw new Error('Cases are not available in mock mode');
    },
    async timeline(id: string): Promise<{ type: string; description: string; actorId: string; occurredAt: string }[]> {
      if (isRealDomain('cases')) {
        return http.get(`/cases/${id}/timeline`);
      }
      return delay([]);
    },
  },

  files: {
    // Real UploadIntentDto is (fileId, uploadUrl, uploadHeaders, expiresAt)
    // — no `id` field at all. Never had a UI caller until this pass, so
    // this mismatch (every upload would PUT to `/files/undefined/content`)
    // shipped silently; caught live driving the first real evidence-upload
    // flow through the browser, not by inspection.
    async uploadIntent(filename: string, mimeType: string, sizeBytes: number, purpose: string): Promise<{ fileId: string; uploadUrl: string }> {
      if (isRealDomain('files')) {
        return http.post<{ fileId: string; uploadUrl: string }>('/files/upload-intents', { filename, mimeType, sizeBytes, purpose });
      }
      return delay({ fileId: `file_${Date.now()}`, uploadUrl: '' });
    },
    async uploadContent(fileId: string, file: File): Promise<void> {
      if (isRealDomain('files')) {
        // Raw fetch (not http.ts's JSON-only wrapper, since this sends a
        // binary body) — needs the same manual CSRF attachment
        // imports.create() already uses for its own raw multipart fetch;
        // PUT is state-changing and 403s without it.
        const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
        const headers: Record<string, string> = { 'Content-Type': file.type };
        if (csrfToken) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfToken);
        const response = await fetch(`/api/v1/files/${fileId}/content`, {
          method: 'PUT',
          credentials: 'include',
          headers,
          body: file,
        });
        if (!response.ok) {
          const text = await response.text();
          const parsed = text ? JSON.parse(text) : {};
          throw new ApiError(parsed.error ?? { code: 'UPLOAD_FAILED', message: 'File upload failed', requestId: '' });
        }
        return;
      }
      return delay(undefined);
    },
    // `complete()`'s real response is a FileMetadataDto carrying the scan
    // outcome (status READY/REJECTED, rejectionReason) — previously
    // discarded (`Promise<void>`), so a scan-rejected file (bad magic
    // bytes, size mismatch) looked identical to a successful upload right
    // up until the record's own submit failed with no explanation (I3.2).
    async complete(fileId: string): Promise<FileMetadata> {
      if (isRealDomain('files')) {
        const dto = await http.post<FileMetadata>(`/files/${fileId}/complete`);
        return mapEnumFields(dto, ['status']);
      }
      return delay({ id: fileId, filename: '', mimeType: '', sizeBytes: 0, status: 'ready' as const, uploaderId: '', purpose: '', createdAt: '', updatedAt: '' });
    },
    async get(fileId: string): Promise<FileMetadata> {
      if (isRealDomain('files')) {
        const dto = await http.get<FileMetadata>(`/files/${fileId}`);
        return mapEnumFields(dto, ['status']);
      }
      throw new Error('Files are not available in mock mode');
    },
    /** Throws (ApiError-shaped) if the scan rejects the file — callers must not treat a resolved upload() as "attached", only as "uploaded", until this settles. */
    async upload(file: File, purpose: string): Promise<EvidenceFile> {
      const intent = await this.uploadIntent(file.name, file.type, file.size, purpose);
      await this.uploadContent(intent.fileId, file);
      const meta = await this.complete(intent.fileId);
      if (meta.status === 'rejected') {
        throw new ApiError({ code: 'FILE_REJECTED', message: meta.rejectionReason || 'File was rejected.', requestId: '' });
      }
      return { id: meta.id, name: file.name, type: file.type, size: file.size, url: '', uploadedAt: new Date().toISOString(), status: meta.status };
    },
  },

  users: {
    async list(params?: ListParams): Promise<PageResult<{ id: string; username: string; displayName: string; email: string; roles: string[]; status: string; lastLoginAt?: string; version: number }>> {
      if (isRealDomain('users')) {
        type UserDtoWire = { id: string; username: string; displayName: string; email: string; roles: { code: string }[]; status: string; lastLoginAt?: string; version: number };
        const page = await http.getPage<UserDtoWire>('/users', buildListQuery(params));
        return {
          data: page.data.map(u => ({
            id: u.id, username: u.username, displayName: u.displayName, email: u.email,
            roles: u.roles.map(r => r.code), status: fromBackendEnum(u.status),
            lastLoginAt: u.lastLoginAt, version: u.version,
          })),
          page: page.page,
        };
      }
      return delay({ data: MOCK_USERS.map(u => ({ id: u.id, username: u.username, displayName: u.displayName ?? u.username, email: '', roles: u.roles ?? [], status: 'active', version: 1 })), page: { number: 1, size: 20, totalItems: MOCK_USERS.length, totalPages: 1 } });
    },
    async create(data: { username: string; initialPassword: string; displayName: string; email: string; roleCodes: string[]; preferredLanguage?: string }): Promise<{ id: string }> {
      if (isRealDomain('users')) {
        return http.post<{ id: string }>('/users', data);
      }
      return delay({ id: `u${Date.now()}` });
    },
    async update(id: string, data: { displayName?: string; email?: string; status?: string; roleCodes?: string[]; version: number }): Promise<void> {
      if (isRealDomain('users')) {
        const body = { ...data, status: data.status ? toBackendEnum(data.status) : undefined };
        await http.patch<void>(`/users/${id}`, body);
        return;
      }
      return delay(undefined);
    },
    async resetPassword(id: string): Promise<{ temporaryPassword: string }> {
      if (isRealDomain('users')) {
        return http.post<{ temporaryPassword: string }>(`/users/${id}/reset-password`);
      }
      return delay({ temporaryPassword: 'TempPass123!' });
    },
    async unlock(id: string): Promise<void> {
      if (isRealDomain('users')) {
        await http.post<void>(`/users/${id}/unlock`);
        return;
      }
      return delay(undefined);
    },
    /**
     * A supervisor picker's search (contract §4.15,
     * `Docs/clarification/01-i3-partner-school-terminology.md`) — minimal
     * fields, capped, scoped to one role, blank on an empty search.
     */
    async directory(role: string, search: string): Promise<TeacherDirectoryEntry[]> {
      if (!search) return [];
      if (isRealDomain('users')) {
        return http.get<TeacherDirectoryEntry[]>('/users/directory', { role, search });
      }
      const term = search.toLowerCase();
      return MOCK_USERS
        .filter(u => u.roles?.includes(role as Role) && (u.displayName ?? u.username).toLowerCase().includes(term))
        .slice(0, 20)
        .map(u => ({ id: u.id, displayName: u.displayName ?? u.username }));
    },
  },

  rules: {
    async list(params?: ListParams): Promise<PageResult<{ id: string; status: string; effectiveFrom?: string; coinConversionRate: number; minimumRoundingMinutes: number; version: number; createdAt: string }>> {
      if (isRealDomain('rules')) {
        const page = await http.getPage<{ id: string; status: string; effectiveFrom?: string; coinConversionRate: number; minimumRoundingMinutes: number; version: number; createdAt: string }>('/rules', buildListQuery(params));
        return { data: page.data.map(r => mapEnumFields(r, ['status'])), page: page.page };
      }
      return delay({ data: [], page: { number: 1, size: 20, totalItems: 0, totalPages: 0 } });
    },
    async create(data: Record<string, unknown>): Promise<{ id: string }> {
      if (isRealDomain('rules')) {
        return http.post<{ id: string }>('/rules', data);
      }
      return delay({ id: `rule_${Date.now()}` });
    },
    async update(id: string, data: Record<string, unknown>): Promise<void> {
      if (isRealDomain('rules')) {
        await http.patch<void>(`/rules/${id}`, data);
        return;
      }
      return delay(undefined);
    },
    async validate(id: string): Promise<{ valid: boolean; issues: string[]; affectedRecordCount: number }> {
      if (isRealDomain('rules')) {
        return http.post<{ valid: boolean; issues: string[]; affectedRecordCount: number }>(`/rules/${id}/validate`);
      }
      return delay({ valid: true, issues: [], affectedRecordCount: 0 });
    },
    async publish(id: string, effectiveFrom: string, version: number): Promise<void> {
      if (isRealDomain('rules')) {
        await http.post<void>(`/rules/${id}/publish`, { effectiveFrom, version });
        return;
      }
      return delay(undefined);
    },
  },

  imports: {
    async template(type: string): Promise<Blob> {
      const response = await fetch(`/api/v1/imports/templates/${type}`, { credentials: 'include' });
      if (!response.ok) throw new ApiError({ code: 'DOWNLOAD_FAILED', message: 'Template download failed', requestId: '' });
      return response.blob();
    },
    async create(type: string, file: File): Promise<{ id: string; status: string; totalRows: number; validRows: number; invalidRows: number }> {
      if (isRealDomain('imports')) {
        const formData = new FormData();
        formData.append('type', type);
        formData.append('file', file);
        const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
        const headers: Record<string, string> = { 'Accept-Language': i18n.language };
        if (csrfToken) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrfToken);
        const response = await fetch('/api/v1/imports', {
          method: 'POST', credentials: 'include', headers, body: formData,
        });
        if (!response.ok) {
          const text = await response.text();
          const parsed = text ? JSON.parse(text) : {};
          throw new ApiError(parsed.error ?? { code: 'IMPORT_FAILED', message: 'Import failed', requestId: '' });
        }
        const json = await response.json();
        return json.data;
      }
      return delay({ id: `imp_${Date.now()}`, status: 'uploaded', totalRows: 0, validRows: 0, invalidRows: 0 });
    },
    async get(id: string): Promise<{ id: string; status: string; totalRows: number; validRows: number; invalidRows: number }> {
      if (isRealDomain('imports')) {
        return http.get(`/imports/${id}`);
      }
      return delay({ id, status: 'validated', totalRows: 10, validRows: 9, invalidRows: 1 });
    },
    async validate(id: string): Promise<{ id: string; status: string; totalRows: number; validRows: number; invalidRows: number }> {
      if (isRealDomain('imports')) {
        return http.post(`/imports/${id}/validate`);
      }
      return delay({ id, status: 'validated', totalRows: 10, validRows: 9, invalidRows: 1 });
    },
    async errors(id: string): Promise<{ errors: { row: number; field: string; message: string }[] }> {
      if (isRealDomain('imports')) {
        return http.get(`/imports/${id}/errors`);
      }
      return delay({ errors: [] });
    },
    async commit(id: string, idempotencyKey: string): Promise<{ id: string; status: string; committedRows: number }> {
      if (isRealDomain('imports')) {
        return http.post(`/imports/${id}/commit`, undefined, idempotencyKey);
      }
      return delay({ id, status: 'committed', committedRows: 9 });
    },
    async cancel(id: string): Promise<void> {
      if (isRealDomain('imports')) {
        await http.post<void>(`/imports/${id}/cancel`);
        return;
      }
      return delay(undefined);
    },
  },

  exports: {
    async create(reportType: string): Promise<{ jobId: string; status: string }> {
      if (isRealDomain('exports')) {
        return http.post<{ jobId: string; status: string }>('/exports', { reportType });
      }
      return delay({ jobId: `exp_${Date.now()}`, status: 'processing' });
    },
    async get(jobId: string): Promise<{ jobId: string; status: string; downloadUrl?: string }> {
      if (isRealDomain('exports')) {
        return http.get(`/exports/${jobId}`);
      }
      return delay({ jobId, status: 'succeeded', downloadUrl: '#' });
    },
    async download(jobId: string): Promise<{ downloadUrl: string; token: string }> {
      if (isRealDomain('exports')) {
        return http.get(`/exports/${jobId}/download`);
      }
      return delay({ downloadUrl: '#', token: 'mock' });
    },
  },

};
