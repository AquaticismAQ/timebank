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
  UpdateSchoolInfoRequest,
  SystemVersion,
  AuditLogEntry,
  AuditLogFacets,
  RuleVersion,
  RuleDraftFields,
  RuleValidationResult,
  MeResponse,
  EvidenceFile,
  FileMetadata,
  Grade,
  SchoolClass,
  AcademicYear,
  ServiceCategory,
  TimelineEntry,
  Permission,
  RoleSummary,
  RoleTemplateSummary,
} from './types';
import { http, ApiError, buildListQuery, getWithEtag, postPdf, withVersion, type PageResult, type PdfDownload } from './http';
import { toBackendEnum, fromBackendEnum, type ListParams } from '../lib/pagination';
import { localDateTimeToISO, isoToLocalDateTime } from '../lib/datetime';
import { formatGradeClass } from '../lib/format';
import { toast } from 'sonner';
import i18n from '../../i18n';

// Real StudentListItemDto/StudentDetailDto field names/casing genuinely
// differ from the frontend Student shape (displayName not name, gradeLevel
// not grade, status is UPPER_SNAKE) — mapped here once, not passed through
// raw (I1 finding: caught live as a `student.name[0]` crash before this fix).
interface StudentListItemDtoWire {
  id: string; studentNumber: string; displayName: string;
  gradeId: string; gradeLevel: number; classId: string; className: string;
  status: string; graduationYear: number; version: number;
  joinedAt?: string;
}
/** Backend enum fields (status, sourceType, type, ...) arrive UPPER_SNAKE; the frontend renders/compares camelCase literals directly in JSX. Run every response through this for whichever fields the resource has. */
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
  return mapEnumFields({ ...dto, evidenceFiles: dto.evidenceFiles ?? [] }, ['status', 'sourceType', 'supervisorKind']);
}

/** `/review-queue` and `/dashboards/admin`'s embedded `pendingReviews` both return the same real `ReviewQueueItemDto`, including the raw SUBMITTED status name — one mapper, both call sites. */
function fromReviewQueueItemDto(item: Omit<ReviewQueueItem, 'status'> & { status: string }): ReviewQueueItem {
  return { ...item, status: (item.status === 'SUBMITTED' ? 'pending' : fromBackendEnum(item.status)) as ReviewQueueItem['status'] };
}

type RuleVersionDtoWire = Omit<RuleVersion, 'status'> & { status: string };

function fromRuleVersionDto(dto: RuleVersionDtoWire): RuleVersion {
  return { ...dto, status: fromBackendEnum(dto.status) as RuleVersion['status'] };
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
    gradeLevel: dto.gradeLevel,
    classId: dto.classId,
    className: dto.className,
    status: dto.status.toLowerCase() as Student['status'],
    joinedAt: dto.joinedAt ?? '',
    version: dto.version,
  };
}

function meResponseToUser(me: MeResponse, username: string): User {
  return {
    id: me.user.id,
    username,
    displayName: me.user.displayName,
    // 07-RoleFinetune.md: role codes pass through as-is now — Role is any
    // server-defined code, not a fixed 4-value union to coerce into.
    roles: me.user.roles.map(r => r.toLowerCase()) as Role[],
    workspaces: me.workspaces,
    studentId: me.user.studentId ?? undefined,
    scopeGradeIds: me.user.scopeGradeIds ?? [],
    email: '',
    permissions: me.user.permissions,
    preferredLanguage: me.preferredLanguage,
    featureFlags: me.featureFlags,
  };
}

export interface LoginResponse {
  user: User;
}

/** Real wire shape of `JobDto` (imports/exports share the same `job` table, D5) — `totalRows`/`validRows` are absent until the async run behind `create`/`validate`/`commit` actually reaches VALIDATED/COMMITTED. */
export interface ImportJob {
  id: string;
  status: string;
  totalRows?: number;
  validRows?: number;
}

export const api = {
  auth: {
    async login(username: string, password: string): Promise<LoginResponse> {
      await http.get<void>('/auth/csrf');
      const me = await http.post<MeResponse>('/auth/login', { username, password });
      const user: User = meResponseToUser(me, username);
      return { user };
    },
    /**
     * First-time activation (Docs/optimization/10-StudentIdentityAndOnboarding.md
     * §2.4/§3.5): redeems a single-use activation code and sets the account's
     * first password in one call. Public/unauthenticated by necessity — the
     * account has no password yet, and the code is itself the lookup key, so no
     * username is sent.
     *
     * The path and body below were this client's provisional guess and were
     * adopted unchanged by §3.5's backend implementation; both are now in
     * APIContract.md §2.2. Failures come back as `VALIDATION_FAILED` with a
     * distinct `fieldErrors[0].code` (unknown / expired / already used /
     * malformed), which `ActivationPage` translates — the response never says
     * which account a code belongs to.
     */
    async activate(code: string, newPassword: string): Promise<void> {
      await http.get<void>('/auth/csrf');
      await http.post<void>('/auth/activate', { code, newPassword });
    },
    async me(): Promise<User | MeResponse> {
      return http.get<MeResponse>('/me');
    },
    async logout(): Promise<void> {
      await http.post<void>('/auth/logout');
    },
    // I3.4: `PATCH /me/profile` — self-service fields only (displayName/
    // email/preferredLanguage). `localStorage['timebank-lang']` stays as a
    // cache for the pre-login paint only; once a real session exists this
    // is the source of truth, read back via `/me` on next login.
    async updateProfile(data: { displayName?: string; email?: string; preferredLanguage?: 'zh-CN' | 'en' }): Promise<void> {
      await http.patch<void>('/me/profile', data);
    },
  },

  school: {
    /**
     * Name only, and **unauthenticated** — for the login page, which cannot
     * call `getInfo()`: that one is `isAuthenticated()` and returns 401 to a
     * visitor who has not logged in yet, so the school name simply rendered
     * blank there. Carries no contact or location, on purpose.
     */
    async getIdentity(): Promise<{ name: string; fullName?: string }> {
      return http.get<{ name: string; fullName?: string }>('/school/identity');
    },
    async getInfo(): Promise<SchoolInfo> {
      return http.get<SchoolInfo>('/school/info');
    },
    /** `sysadmin` role only (not `admin`) — the backend checks the role code, not a permission, since the two share identical grants post-role-merge. A non-sysadmin caller gets a real 403 from the server; this isn't just a hidden button. */
    async updateInfo(data: UpdateSchoolInfoRequest): Promise<SchoolInfo> {
      return http.patch<SchoolInfo>('/school/info', data);
    },
  },

  system: {
    /** `GET /system/version` (contract §4.17) — the real deployed backend version, never hardcoded client-side. */
    async version(): Promise<SystemVersion> {
      return http.get<SystemVersion>('/system/version');
    },
  },

  /**
   * `GET /audit-logs` (contract §4.15) — the real, backend-owned, append-only trail.
   *
   * Search, `filter[action]`, `filter[resourceType]` and sorting by
   * `createdAt`/`action`/`resourceType` all execute server-side over the whole
   * log (issue 098). Two of these were previously accepted and discarded —
   * `search` was never read, and the statement's `order by` was hardcoded, so
   * `sort=createdAt` returned the same newest-first page as `-createdAt`.
   */
  auditLogs: {
    async list(params?: ListParams): Promise<PageResult<AuditLogEntry>> {
      const page = await http.getPage<AuditLogEntry>('/audit-logs', buildListQuery(params));
      return { data: page.data, page: page.page };
    },
    /** The distinct actions and resource types present, for the filter dropdowns. Served, not hardcoded — see `AuditLogFacets`. */
    async facets(): Promise<AuditLogFacets> {
      return http.get<AuditLogFacets>('/audit-logs/facets');
    },
  },

  /** `GET/POST /rules` (contract §4.12) — versioned, immutable-once-published rule sets; a draft can only be edited/published/archived, never overwritten in place. */
  rules: {
    async list(params?: ListParams): Promise<PageResult<RuleVersion>> {
      const page = await http.getPage<RuleVersionDtoWire>('/rules', buildListQuery(params));
      return { data: page.data.map(fromRuleVersionDto), page: page.page };
    },
    async get(id: string): Promise<RuleVersion> {
      return fromRuleVersionDto(await http.get<RuleVersionDtoWire>(`/rules/${id}`));
    },
    async createDraft(fields: RuleDraftFields): Promise<RuleVersion> {
      return fromRuleVersionDto(await http.post<RuleVersionDtoWire>('/rules', fields));
    },
    async updateDraft(id: string, fields: Partial<RuleDraftFields>, version: number): Promise<RuleVersion> {
      return fromRuleVersionDto(await http.patch<RuleVersionDtoWire>(`/rules/${id}`, { ...fields, version }));
    },
    async validate(id: string): Promise<RuleValidationResult> {
      return http.post<RuleValidationResult>(`/rules/${id}/validate`);
    },
    async publish(id: string, effectiveFrom: string, version: number): Promise<RuleVersion> {
      return fromRuleVersionDto(await http.post<RuleVersionDtoWire>(`/rules/${id}/publish`, { effectiveFrom, version }));
    },
    async archive(id: string): Promise<void> {
      await http.post<void>(`/rules/${id}/archive`);
    },
  },

  /** Reference/dictionary data (contract §2.10) — near-static, real names already localized server-side per `Accept-Language`. Prefer `useDictionaries()` (api/useDictionaries.ts) over calling these directly, so repeated screens share one fetch-once cache instead of each re-fetching the same rarely-changing lists. */
  dictionaries: {
    async grades(): Promise<Grade[]> {
      return http.get<Grade[]>('/grades');
    },
    async classes(): Promise<SchoolClass[]> {
      return http.get<SchoolClass[]>('/classes');
    },
    async academicYears(): Promise<AcademicYear[]> {
      return http.get<AcademicYear[]>('/academic-years');
    },
    async serviceCategories(): Promise<ServiceCategory[]> {
      return http.get<ServiceCategory[]>('/service-categories');
    },
  },

  /** Service-category management (issue 078) — admin-only, gated on `rules.manage`. */
  serviceCategories: {
    /** Management view: inactive categories included, so they can be re-activated. */
    async list(): Promise<ServiceCategory[]> {
      return http.get<ServiceCategory[]>('/service-categories?includeInactive=true');
    },
    /** No code: the backend derives it from the English name (issue 078). */
    async create(data: { nameZh: string; nameEn: string }): Promise<ServiceCategory> {
      return http.post<ServiceCategory>('/service-categories', data);
    },
    async update(id: string, data: { nameZh?: string; nameEn?: string; active?: boolean }): Promise<ServiceCategory> {
      return http.patch<ServiceCategory>(`/service-categories/${id}`, data);
    },
    async remove(id: string): Promise<void> {
      await http.delete<void>(`/service-categories/${id}`);
    },
  },

  projects: {
    async list(params?: ListParams): Promise<PageResult<Project>> {
      const filter = { ...params?.filter };
      if (filter.status) filter.status = toBackendEnum(filter.status);
      const page = await http.getPage<Project>('/projects', buildListQuery({ ...params, filter }));
      return { data: page.data.map(p => mapEnumFields(p, ['status', 'myApplicationStatus', 'deliveryScope'])), page: page.page };
    },
    async get(id: string): Promise<Project> {
      const dto = await http.get<Project>(`/projects/${id}`);
      return mapEnumFields(dto, ['status', 'myApplicationStatus', 'deliveryScope']);
    },
    async create(data: Partial<Project>): Promise<Project> {
      const body = {
        title: data.title,
        summary: data.summary,
        description: data.description,
        categoryId: data.categoryId,
        organizationId: data.organizationId,
        ownerId: data.ownerId,
        // Required (issue 03 §4.2) — never inferred, always explicit.
        deliveryScope: data.deliveryScope ? toBackendEnum(data.deliveryScope) : undefined,
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
      return mapEnumFields(dto, ['status', 'deliveryScope']);
    },
    async update(id: string, data: Partial<Project>): Promise<Project> {
      const body = withVersion({
        title: data.title,
        summary: data.summary,
        description: data.description,
        categoryId: data.categoryId,
        organizationId: data.organizationId,
        ownerId: data.ownerId,
        deliveryScope: data.deliveryScope ? toBackendEnum(data.deliveryScope) : undefined,
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
      return mapEnumFields(dto, ['status', 'deliveryScope']);
    },
    async publish(id: string, version?: number): Promise<Project> {
      const dto = await http.post<Project>(`/projects/${id}/publish`, { version });
      return mapEnumFields(dto, ['status']);
    },
    async archive(id: string, version?: number): Promise<Project> {
      const dto = await http.post<Project>(`/projects/${id}/archive`, { version });
      return mapEnumFields(dto, ['status']);
    },
    // The manager override (02-PorjectOrganization.md §2.1) — archives a live
    // or completed-but-unreviewed project outright; reason required, bypasses
    // archive()'s reviewed-records gate.
    async cancel(id: string, reason: string, version?: number): Promise<Project> {
      const dto = await http.post<Project>(`/projects/${id}/cancel`, { reason, version });
      return mapEnumFields(dto, ['status']);
    },
  },

  eventProposals: {
    async list(params?: ListParams): Promise<PageResult<EventProposal>> {
      // Real ProposalDto's shape diverges from EventProposal beyond casing
      // (objective/expectedOutcome/format/capacity/safetyPlan/
      // resourceNeeds/recruitmentPlan/evidencePlan vs goal/
      // expectedOutcomes/activityFormat/estimatedCapacity/riskMeasures/
      // materialNeeds+budgetNeeds/recruitmentMethod/proofMethod) and a
      // handful of fields — myRole, reviewerName,
      // feedback, linkedProjectId/Title,
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
      const page = await http.getPage<ProposalDtoWire>('/event-proposals', buildListQuery({ ...params, filter }));
      const data = page.data.map(fromProposalDto);
      return { data, page: page.page };
    },
    async get(id: string): Promise<EventProposal> {
      const dto = await http.get<ProposalDtoWire>(`/event-proposals/${id}`);
      return fromProposalDto(dto);
    },
    async create(data: Partial<EventProposal>): Promise<EventProposal> {
      // Two genuinely different numbers, and they must not collapse into one
      // (issue 075): the planned window is sized by how long the activity runs,
      // while `proposedRecognizedMinutes` is how much gets credited. They used to
      // share a value, which silently discarded whatever the student typed for
      // duration *and* mis-sized the project generated on approval, since
      // ProposalApprovalTransaction copies the window into the project verbatim.
      const proposedRecognizedMinutes = data.suggestedMinutes || data.estimatedDurationMinutes || 60;
      const windowMinutes = data.estimatedDurationMinutes || proposedRecognizedMinutes;
      const body = {
        title: data.title,
        objective: data.goal,
        description: data.description,
        expectedOutcome: data.expectedOutcomes,
        categoryId: data.categoryId,
        ...plannedStartEnd(data.plannedDate, windowMinutes),
        location: data.location,
        format: data.activityFormat,
        targetParticipants: data.targetParticipants,
        capacity: data.estimatedCapacity,
        volunteerTasks: data.volunteerTasks,
        // Real ids resolved through the co-organizer/supervisor/organization
        // pickers (`Docs/optimization/01-i3-partner-school-terminology.md`)
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
    },
    async update(id: string, data: Partial<EventProposal>): Promise<EventProposal> {
      const proposedRecognizedMinutes = data.suggestedMinutes || data.estimatedDurationMinutes;
      // Same split as `create` above (issue 075).
      const windowMinutes = data.estimatedDurationMinutes || proposedRecognizedMinutes || 60;
      const body = withVersion({
        title: data.title,
        objective: data.goal,
        description: data.description,
        expectedOutcome: data.expectedOutcomes,
        categoryId: data.categoryId,
        ...plannedStartEnd(data.plannedDate, windowMinutes),
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
    },
    async submit(id: string, idempotencyKey?: string): Promise<EventProposal> {
      const dto = await http.post<EventProposal>(`/event-proposals/${id}/submit`, undefined, idempotencyKey);
      return mapEnumFields(dto, ['status']);
    },
    async withdraw(id: string): Promise<EventProposal> {
      const dto = await http.post<EventProposal>(`/event-proposals/${id}/withdraw`);
      return mapEnumFields(dto, ['status']);
    },
    async review(id: string, decision: string, comment?: string, reasonCode?: string, proposalVersion?: number, idempotencyKey?: string): Promise<EventProposal> {
      const decisionMap: Record<string, string> = { approve: 'APPROVE', reject: 'REJECT', requestChanges: 'REQUEST_CHANGES' };
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
          toast.success(i18n.t('admin.proposals.approvedProjectCreated', { projectId }));
        }
      }
      return mapped;
    },
    /** `GET /event-proposals/{id}/timeline` — already merges revisions + review decisions server-side (04-DataViewOptimization.md §1.3 finding 2, §5.2 finding); this was the missing client wiring, not a new backend capability. */
    async timeline(id: string): Promise<TimelineEntry[]> {
      return http.get(`/event-proposals/${id}/timeline`);
    },
    /** `GET /event-proposal-queue/summary` (`eventProposals.review`-gated) — awaiting-decision count for the dashboard card / sidebar badge. */
    async pendingCount(): Promise<number> {
      const summary = await http.get<{ pendingCount: number; overdueCount: number }>('/event-proposal-queue/summary');
      return summary.pendingCount;
    },
  },

  applications: {
    async list(params?: ListParams): Promise<PageResult<Application>> {
      const filter = { ...params?.filter };
      if (filter.status) filter.status = toBackendEnum(filter.status);
      const page = await http.getPage<Application>('/applications', buildListQuery({ ...params, filter }));
      return { data: page.data.map(a => mapEnumFields(a, ['status'])), page: page.page };
    },
    async create(projectId: string, _studentId?: string, _studentName?: string, note?: string): Promise<Application> {
      const dto = await http.post<Application>(`/projects/${projectId}/applications`, note ? { note } : {});
      return mapEnumFields(dto, ['status']);
    },
    async cancel(id: string): Promise<Application> {
      const dto = await http.post<Application>(`/applications/${id}/cancel`);
      return mapEnumFields(dto, ['status']);
    },
    async confirm(id: string): Promise<Application> {
      const dto = await http.post<Application>(`/applications/${id}/confirm`);
      return mapEnumFields(dto, ['status']);
    },
    async reject(id: string): Promise<Application> {
      const dto = await http.post<Application>(`/applications/${id}/reject`);
      return mapEnumFields(dto, ['status']);
    },
    // `action` is the backend's own bulk-action vocabulary (confirm/reject/
    // markComplete — cn.timebank.enrollment.application
    // .EnrollmentBulkActionTransaction), not the frontend's camelCase enum
    // style, since it's a direct passthrough with no other mapping needed.
    async bulkActions(ids: string[], action: 'confirm' | 'reject' | 'markComplete', reasonCode?: string, comment?: string): Promise<{ id: string; success: boolean; error?: string }[]> {
      return http.post(`/applications/bulk-actions`, { ids, action, reasonCode, comment });
    },
  },

  serviceRecords: {
    async list(params?: ListParams): Promise<PageResult<ServiceRecord>> {
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
    },
    async get(id: string): Promise<ServiceRecord> {
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
    },
    async create(data: Partial<ServiceRecord>): Promise<ServiceRecord> {
      const body = {
        sourceType: toBackendEnum(data.sourceType || 'self'),
        projectId: data.projectId,
        // PROJECT source: the server derives organizationId/organizationName/location/
        // serviceCategoryId/supervisor* from the project itself and ignores these
        // regardless of what's sent — see APIContract.md §3.4. Sent unconditionally
        // here anyway (harmless for PROJECT, needed for SELF) rather than branching,
        // since the backend is the actual source of truth for which source ignores what.
        organizationId: data.organizationId,
        organizationName: data.organizationName,
        serviceDate: data.serviceDate,
        startAt: data.startAt,
        endAt: data.endAt,
        location: data.location,
        serviceCategoryId: data.serviceCategoryId,
        description: data.description,
        supervisorKind: data.supervisorKind ? toBackendEnum(data.supervisorKind) : undefined,
        supervisorSubjectId: data.supervisorSubjectId,
        supervisorName: data.supervisorName,
        supervisorContact: data.supervisorContact,
        evidenceFileIds: (data.evidenceFiles ?? []).map(f => f.id),
        includesTravelTime: data.includesTravelTime ?? false,
        travelTimeReason: data.travelTimeReason,
      };
      const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>('/service-records', body);
      return fromServiceRecordDto(dto);
    },
    async update(id: string, data: Partial<ServiceRecord>): Promise<ServiceRecord> {
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
        supervisorKind: data.supervisorKind ? toBackendEnum(data.supervisorKind) : undefined,
        supervisorSubjectId: data.supervisorSubjectId,
        supervisorName: data.supervisorName,
        supervisorContact: data.supervisorContact,
        evidenceFileIds: data.evidenceFiles?.map(f => f.id),
        includesTravelTime: data.includesTravelTime,
        travelTimeReason: data.travelTimeReason,
      }, data.version!);
      const dto = await http.patch<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}`, body);
      return fromServiceRecordDto(dto);
    },
    async delete(id: string): Promise<void> {
      await http.delete<void>(`/service-records/${id}`);
    },
    async submit(id: string, idempotencyKey?: string): Promise<ServiceRecord> {
      const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}/submit`, undefined, idempotencyKey);
      return fromServiceRecordDto(dto);
    },
    async withdraw(id: string): Promise<ServiceRecord> {
      const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${id}/withdraw`);
      return fromServiceRecordDto(dto);
    },
    /** `GET /service-records/{id}/timeline` — already merges revisions + review decisions server-side (04-DataViewOptimization.md §1.3 finding 2); this was the missing client wiring, not a new backend capability. */
    async timeline(id: string): Promise<TimelineEntry[]> {
      return http.get(`/service-records/${id}/timeline`);
    },
  },

  reviewQueue: {
    async list(params?: ListParams): Promise<PageResult<ReviewQueueItem>> {
      // Backend sends the raw ServiceRecordStatus name (SUBMITTED/
      // UNDER_REVIEW); the frontend's ReviewStatus vocabulary calls the
      // former 'pending', not 'submitted' — mapped explicitly rather
      // than a blind fromBackendEnum (which would produce 'submitted',
      // not a valid ReviewStatus literal). See `fromReviewQueueItemDto`.
      const page = await http.getPage<ReviewQueueItem>('/review-queue', buildListQuery(params));
      const data = page.data.map(fromReviewQueueItemDto);
      return { data, page: page.page };
    },
    async summary(): Promise<{ pending: number; urgent: number; avgDays: number }> {
      // Real ReviewQueueSummaryDto has no avgDays — pendingCount/anomalyCount/overdueCount only.
      // Honest gap, not computed here; a future backend pass would need to add it.
      const summary = await http.get<{ pendingCount: number; anomalyCount: number; overdueCount: number }>('/review-queue/summary');
      return { pending: summary.pendingCount, urgent: summary.anomalyCount, avgDays: 0 };
    },
    async decide(recordId: string, decision: string, comment?: string, reasonCode?: string, recordVersion?: number, idempotencyKey?: string): Promise<ServiceRecord> {
      const decisionMap: Record<string, string> = { approve: 'APPROVE', return: 'RETURN_FOR_CHANGES', returnForChanges: 'RETURN_FOR_CHANGES', reject: 'REJECT', escalate: 'ESCALATE' };
      const body = {
        decision: decisionMap[decision] ?? toBackendEnum(decision),
        reasonCode: reasonCode || decision.toUpperCase(),
        comment,
        recordVersion: recordVersion!,
      };
      const dto = await http.post<ServiceRecord & { evidenceFileIds?: string[] }>(`/service-records/${recordId}/reviews`, body, idempotencyKey);
      return fromServiceRecordDto(dto);
    },
  },

  accounts: {
    async list(params?: ListParams): Promise<PageResult<Account>> {
      const page = await http.getPage<AccountDtoWire>('/accounts', buildListQuery(params));
      return { data: page.data.map(fromAccountDto), page: page.page };
    },
    async adjust(accountId: string, amount: number, reasonCode: string, memo?: string, idempotencyKey?: string): Promise<void> {
      await http.post<void>(`/accounts/${accountId}/adjustments`, { amount, reasonCode, memo }, idempotencyKey);
    },
    async reverse(transactionId: string, reasonCode: string, memo?: string, idempotencyKey?: string): Promise<void> {
      await http.post<void>(`/account-transactions/${transactionId}/reverse`, { reasonCode, memo }, idempotencyKey);
    },
    async freeze(accountId: string, reasonCode: string, memo?: string): Promise<void> {
      await http.post<void>(`/accounts/${accountId}/freeze`, { reasonCode, memo });
    },
    async unfreeze(accountId: string, reasonCode: string, memo?: string): Promise<void> {
      await http.post<void>(`/accounts/${accountId}/unfreeze`, { reasonCode, memo });
    },
    // Contract §13.1: keyed by account id server-side, resolved here via
    // filter[studentId] then the returned accountId — not a student-scoped
    // account endpoint.
    async get(studentId: string): Promise<Account> {
      // Real AccountDto has no totalEarned/totalRedeemed/borrowed at all
      // (Phase 4 concepts, unimplemented — always 0, not a mock gap), and
      // `frozen` is a boolean flag (is this account frozen?), not a
      // frozen-amount like the frontend's `frozen: number` — approximated
      // as "whole balance" when frozen, since freezing blocks all new
      // credits rather than freezing a partial amount. See `fromAccountDto`.
      const account = await this.getOrNull(studentId);
      if (!account) throw new Error('Account not found');
      return account;
    },
    /**
     * `null` when the student has no account row yet, rather than throwing
     * (issue 099).
     *
     * **That is not an error state.** `AccountServiceImpl` creates the row on
     * the first credit, and balances are transaction-derived, so a student who
     * has never had a record approved has no row and a balance of exactly zero
     * — the state *every* student is in from enrolment until their first
     * approval, and 23 of the 50 students on the test server right now.
     * Treating it as a failure left the Account and Profile pages permanently
     * stuck on their loading guard.
     *
     * `get` above still throws, deliberately: the admin screens call it right
     * before `adjust`/`freeze`, which need a real account id, and handing those
     * a zero placeholder would be worse than an error.
     */
    async getOrNull(studentId: string): Promise<Account | null> {
      const page = await http.getPage<AccountDtoWire>('/accounts', buildListQuery({ filter: { studentId } }));
      const dto = page.data[0];
      return dto ? fromAccountDto(dto) : null;
    },
    /**
     * What a student with no account row has: zero of everything. Used for
     * display only — `id` is empty precisely so nothing can mistake it for a
     * real account and send it to a mutation endpoint.
     */
    zeroAccount(studentId: string): Account {
      return { id: '', studentId, balance: 0, totalEarned: 0, totalRedeemed: 0, frozen: 0, borrowed: 0, updatedAt: '' };
    },
    async transactions(accountId: string, params?: ListParams): Promise<PageResult<AccountTransaction>> {
      // Real AccountTransactionDto has no balanceBefore (derivable:
      // balanceAfter - amount), description (reasonCode stands in), or
      // operatorName (only operatorId — no bulk user-name lookup wired
      // for this list, shows the id rather than a blank).
      type AccountTransactionDtoWire = {
        id: string; accountId: string; type: string; amount: number; balanceAfter: number;
        sourceId?: string; reasonCode: string; memo?: string; operatorId: string; createdAt: string;
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
        // memo is the admin-typed free-text explanation, reasonCode a short
        // selected category (e.g. "CORRECTION") — combine into the single
        // description slot the UI renders (Docs/issues/037-*.md, memo used
        // to be silently dropped by the backend and never reached here).
        description: dto.memo ? `${dto.reasonCode}: ${dto.memo}` : dto.reasonCode,
        operatorName: dto.operatorId,
        createdAt: dto.createdAt,
      }));
      return { data, page: page.page };
    },
  },

  students: {
    async list(params?: ListParams): Promise<PageResult<Student>> {
      const filter = { ...params?.filter };
      if (filter.status) filter.status = toBackendEnum(filter.status);
      const page = await http.getPage<StudentListItemDtoWire>('/students', buildListQuery({ ...params, filter }));
      return { data: page.data.map(fromStudentDto), page: page.page };
    },
    async get(id: string): Promise<Student> {
      const dto = await http.get<StudentListItemDtoWire & { joinedAt: string }>(`/students/${id}`);
      return fromStudentDto(dto);
    },
    async update(id: string, data: { studentNumber?: string; displayName?: string; gradeId?: string; classId?: string; version: number }): Promise<Student> {
      const dto = await http.patch<StudentListItemDtoWire>(`/students/${id}`, data);
      return fromStudentDto(dto);
    },
    /**
     * The raw `ProgressDto` only — no grade/academicYear backfill, so it costs
     * one request instead of the three `progress()` below makes. Meant for
     * callers that need progress for a whole page of students at once, where
     * tripling the request count is not acceptable (`StudentList`'s hours
     * column). Use `progress()` when you need the enriched view for one
     * student.
     */
    async progressSummary(id: string): Promise<Pick<StudentProgress,
      'studentId' | 'annualRequiredMinutes' | 'stageRequiredMinutes' | 'completedMinutes' | 'underReviewMinutes' | 'gapMinutes' | 'timeCoinBalance'>> {
      return http.get(`/students/${id}/progress`);
    },
    async progress(id: string): Promise<StudentProgress> {
      // Real ProgressDto (backend doc comment: "the `*Hours` convenience
      // fields... leave the wire") has no academicYear/grade/byCategory at
      // all — grade is backfilled from the student record, and academicYear
      // from `/academic-years`' `current: true` entry (one extra real call
      // each, already wired elsewhere); the per-category breakdown still
      // has no backing endpoint in this pass, defaulted honestly rather
      // than invented.
      type ProgressDtoWire = Omit<StudentProgress, 'academicYear' | 'grade' | 'byCategory'>;
      const [dto, student, academicYears] = await Promise.all([
        http.get<ProgressDtoWire>(`/students/${id}/progress`),
        this.get(id).catch(() => undefined),
        api.dictionaries.academicYears().catch(() => []),
      ]);
      return {
        ...dto, academicYear: academicYears.find(y => y.current)?.code ?? '', byCategory: [],
        grade: student ? formatGradeClass(student.gradeLevel, student.className) : '',
      };
    },
    /**
     * A co-organizer picker's search (contract §4.9,
     * `Docs/optimization/01-i3-partner-school-terminology.md`) — minimal
     * fields, capped, blank on an empty search (this is a typeahead, not a
     * browse). `excludeId` filters out the current student client-side,
     * since the backend has no way to know who "the current proposer" is
     * from a plain name search.
     */
    async directory(search: string, excludeId?: string): Promise<StudentDirectoryEntry[]> {
      if (!search) return [];
      const results = await http.get<StudentDirectoryEntry[]>('/students/directory', { search });
      return results.filter(s => s.id !== excludeId);
    },
  },

  organizations: {
    async list(params?: ListParams): Promise<PageResult<Organization>> {
      const page = await http.getPage<Organization>('/organizations', buildListQuery(params));
      return { data: page.data.map(o => mapEnumFields(o, ['status', 'type'])), page: page.page };
    },
    async get(id: string): Promise<Organization> {
      const dto = await http.get<Organization>(`/organizations/${id}`);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async create(data: Partial<Organization>): Promise<Organization> {
      const body = {
        name: data.name,
        type: data.type ? toBackendEnum(data.type) : undefined,
        description: data.description,
        contactName: data.contactName,
        contactEmail: data.contactEmail,
        contactPhone: data.contactPhone,
        address: data.address,
      };
      const dto = await http.post<Organization>('/organizations', body);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async update(id: string, data: Partial<Organization>): Promise<Organization> {
      const body = withVersion({
        name: data.name,
        description: data.description,
        contactName: data.contactName,
        contactEmail: data.contactEmail,
        contactPhone: data.contactPhone,
        address: data.address,
      }, data.version!);
      const dto = await http.patch<Organization>(`/organizations/${id}`, body);
      return mapEnumFields(dto, ['status', 'type']);
    },
    // No hard delete exists server-side, deliberately (02-PorjectOrganization.md
    // §4) — this soft-archives (status -> archived); the org id and any
    // project that already references it stay valid.
    async archive(id: string): Promise<void> {
      await http.post<void>(`/organizations/${id}/archive`, {});
    },
  },

  notifications: {
    async list(params?: ListParams): Promise<PageResult<Notification>> {
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
    },
    // I1.7: real branch is ETag-aware (getWithEtag) via unreadCountPolled below;
    // this plain version stays for any caller that doesn't need conditional-GET.
    async unreadCount(): Promise<number> {
      const { unreadCount } = await http.get<{ unreadCount: number }>('/notifications/unread-count');
      return unreadCount;
    },
    /** Polling-friendly variant — pass back the previous etag; `unchanged: true` on a real 304, no re-render needed. */
    async unreadCountPolled(previousEtag: string | undefined): Promise<{ count: number; etag: string | undefined } | { unchanged: true }> {
      const result = await getWithEtag<{ unreadCount: number }>('/notifications/unread-count', previousEtag);
      if ('unchanged' in result) return result;
      return { count: result.data.unreadCount, etag: result.etag };
    },
    async markRead(id: string): Promise<void> {
      await http.patch<void>(`/notifications/${id}`);
    },
    async markAllRead(): Promise<void> {
      await http.post<void>('/notifications/read-all');
    },
  },

  dashboards: {
    // `notifications` isn't rendered by any screen (StudentHome.tsx never
    // reads it) — the real DashboardStudentDto carries only
    // `unreadNotificationCount`, not the array, so this leaves it empty
    // rather than making an extra call for dead data.
    async student(studentId?: string): Promise<{ progress: StudentProgress; account: Account; recentRecords: ServiceRecord[]; upcomingProjects: Project[]; notifications: Notification[] }> {
      // `progress`/`account`/`recentRecords`/`upcomingProjects` here are
      // the exact same DTOs `/students/{id}/progress`, `/accounts`,
      // `/service-records`, and `/projects` return (backend
      // `DashboardStudentDto`) — so they need the exact same mapping
      // (evidenceFiles default, status/enum casing, the Phase-4 account
      // field approximation) as those standalone endpoints, not a raw
      // pass-through. `progress` still lacks academicYear/grade/byCategory
      // — backfilled the same way as `students.progress`'s branch (grade
      // needs the caller's own studentId, since the dashboard DTO carries
      // no id to look it up by).
      type DashboardStudentDtoWire = {
        progress: Omit<StudentProgress, 'academicYear' | 'grade' | 'byCategory'>;
        account: AccountDtoWire;
        recentRecords: (ServiceRecord & { evidenceFileIds?: string[] })[];
        upcomingProjects: Project[];
      };
      const [dto, student, academicYears] = await Promise.all([
        http.get<DashboardStudentDtoWire>('/dashboards/student'),
        studentId ? api.students.get(studentId).catch(() => undefined) : Promise.resolve(undefined),
        api.dictionaries.academicYears().catch(() => []),
      ]);
      return {
        progress: {
          ...dto.progress, byCategory: [],
          academicYear: academicYears.find(y => y.current)?.code ?? '',
          grade: student ? formatGradeClass(student.gradeLevel, student.className) : '',
        },
        account: fromAccountDto(dto.account),
        recentRecords: dto.recentRecords.map(fromServiceRecordDto),
        upcomingProjects: dto.upcomingProjects.map(p => mapEnumFields(p, ['status', 'myApplicationStatus'])),
        notifications: [],
      };
    },
    async admin(): Promise<{ stats: AdminStats; pendingReviews: ReviewQueueItem[]; activeProjects: Project[] }> {
      // Field names/units genuinely differ from AdminStats' shape
      // (totalServiceHoursThisYear not totalServiceHours, reviewAvgHours
      // not reviewAvgDays, no avgCompletionRate at all) — mapped here, not
      // passed through raw.
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
        // (SUBMITTED->pending status, enum casing), not a raw pass-through.
        pendingReviews: dto.pendingReviews.map(fromReviewQueueItemDto),
        activeProjects: dto.activeProjects.map(p => mapEnumFields(p, ['status', 'myApplicationStatus'])),
      };
    },
  },

  reports: {
    // I1.6: composes the real report endpoints in parallel (contract §4.13)
    // — field names/units differ from ReportData's shape (gradeName not
    // grade, totalMinutes not totalHours), mapped here.
    /**
     * `academicYearId` is optional and goes to every one of the four
     * endpoints (issue 093 §1). Omitting it is not "all years": the server
     * scopes to the academic year currently marked current. An id no
     * academic year has is a 404, so a bad selection surfaces instead of
     * quietly returning a different year's numbers.
     */
    async data(academicYearId?: string): Promise<ReportData> {
      const query = { academicYearId };
      const [participation, completion, serviceHours, topProjects] = await Promise.all([
        http.get<{ generatedAt: string; academicYearId: string; byGrade: { gradeName: string; totalStudents: number; participatedStudents: number; participationRate: number }[] }>('/reports/participation', query),
        http.get<{ byGrade: { gradeName: string; totalStudents: number; completedStudents: number; completionRate: number }[] }>('/reports/completion', query),
        http.get<{ byMonth: { month: string; hours: number }[] }>('/reports/service-hours', query),
        http.get<{ topProjects: { title: string; participants: number; totalMinutes: number }[] }>('/reports/top-projects', query),
      ]);
      return {
        generatedAt: participation.generatedAt,
        academicYearId: participation.academicYearId,
        participationByGrade: participation.byGrade.map(g => ({ grade: g.gradeName, total: g.totalStudents, participated: g.participatedStudents, rate: g.participationRate })),
        completionByGrade: completion.byGrade.map(g => ({ grade: g.gradeName, total: g.totalStudents, completed: g.completedStudents, rate: g.completionRate })),
        serviceHoursByMonth: serviceHours.byMonth,
        topProjects: topProjects.topProjects.map(p => ({ title: p.title, participants: p.participants, totalMinutes: p.totalMinutes })),
      };
    },
  },

  cases: {
    async list(params?: ListParams): Promise<PageResult<Case>> {
      const filter = { ...params?.filter };
      if (filter.status) filter.status = toBackendEnum(filter.status);
      if (filter.type) filter.type = toBackendEnum(filter.type);
      const page = await http.getPage<Case>('/cases', buildListQuery({ ...params, filter }));
      return { data: page.data.map(c => mapEnumFields(c, ['status', 'type'])), page: page.page };
    },
    async get(id: string): Promise<Case> {
      const dto = await http.get<Case>(`/cases/${id}`);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async create(data: { studentId?: string; type: string; subjectType?: string; subjectId?: string; title: string; description?: string; requestedAmountCentiCoins?: number; reasonCode: string }): Promise<Case> {
      const body = { ...data, type: toBackendEnum(data.type) };
      const dto = await http.post<Case>('/cases', body);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async update(id: string, data: { title?: string; description?: string; requestedAmountCentiCoins?: number; reasonCode?: string; version: number }): Promise<Case> {
      const dto = await http.patch<Case>(`/cases/${id}`, data);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async submit(id: string, idempotencyKey?: string): Promise<Case> {
      const dto = await http.post<Case>(`/cases/${id}/submit`, undefined, idempotencyKey);
      return mapEnumFields(dto, ['status', 'type']);
    },
    /** Owner-only, and only while review has not started (issue 081). */
    async withdraw(id: string): Promise<Case> {
      const dto = await http.post<Case>(`/cases/${id}/withdraw`);
      return mapEnumFields(dto, ['status', 'type']);
    },
    /** Owner-only, drafts only (issue 081). */
    async remove(id: string): Promise<void> {
      await http.delete<void>(`/cases/${id}`);
    },
    // Decision vocabulary is CaseDecision (contract §4.8) — distinct from ReviewQueue's Decision enum,
    // not reused even though 'reject' overlaps, since the two case shapes diverge (RESOLVE/REQUEST_INFORMATION here).
    async decide(id: string, decision: 'resolve' | 'requestInformation' | 'reject', reasonCode: string, comment: string | undefined, caseVersion: number, idempotencyKey?: string): Promise<Case> {
      const body = { decision: toBackendEnum(decision), reasonCode, comment, caseVersion };
      const dto = await http.post<Case>(`/cases/${id}/decisions`, body, idempotencyKey);
      return mapEnumFields(dto, ['status', 'type']);
    },
    async timeline(id: string): Promise<{ type: string; description: string; actorId: string; occurredAt: string }[]> {
      return http.get(`/cases/${id}/timeline`);
    },
    /** No dedicated summary endpoint exists for cases (unlike review-queue/event-proposal-queue) — sums `page.totalItems` across the non-terminal statuses via `pageSize: 1` requests, so this stays cheap regardless of how many cases exist. */
    async pendingCount(): Promise<number> {
      const statuses = ['submitted', 'needsInformation'];
      const totals = await Promise.all(statuses.map(status =>
        this.list({ filter: { status }, pageSize: 1 }).then(r => r.page.totalItems)
      ));
      return totals.reduce((sum, n) => sum + n, 0);
    },
  },

  files: {
    // Real UploadIntentDto is (fileId, uploadUrl, uploadHeaders, expiresAt)
    // — no `id` field at all. Never had a UI caller until this pass, so
    // this mismatch (every upload would PUT to `/files/undefined/content`)
    // shipped silently; caught live driving the first real evidence-upload
    // flow through the browser, not by inspection.
    async uploadIntent(filename: string, mimeType: string, sizeBytes: number, purpose: string): Promise<{ fileId: string; uploadUrl: string }> {
      return http.post<{ fileId: string; uploadUrl: string }>('/files/upload-intents', { filename, mimeType, sizeBytes, purpose });
    },
    async uploadContent(fileId: string, file: File): Promise<void> {
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
    },
    // `complete()`'s real response is a FileMetadataDto carrying the scan
    // outcome (status READY/REJECTED, rejectionReason) — previously
    // discarded (`Promise<void>`), so a scan-rejected file (bad magic
    // bytes, size mismatch) looked identical to a successful upload right
    // up until the record's own submit failed with no explanation (I3.2).
    async complete(fileId: string): Promise<FileMetadata> {
      const dto = await http.post<FileMetadata>(`/files/${fileId}/complete`);
      return mapEnumFields(dto, ['status']);
    },
    async get(fileId: string): Promise<FileMetadata> {
      const dto = await http.get<FileMetadata>(`/files/${fileId}`);
      return mapEnumFields(dto, ['status']);
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
    /**
     * 07-RoleFinetune.md: the real `UserDto` wire field is `roleCode` (a
     * single string, not `roles`/`roleCodes`) — this used to assume a
     * `roles: {code}[]` shape that matched neither the old nor the new
     * backend response, so this list silently threw inside `.then()` and
     * was swallowed by a stray `.catch(() => {})`, leaving SystemAdmin.tsx
     * stuck on its hardcoded mock fallback. Confirm live in Phase 6.
     */
    /**
     * Issue 094: `search` (display name) and `filter[role]`/`filter[status]`/
     * `filter[gradeId]`/`filter[classId]` are all server-side now, as is
     * sorting by `displayName`/`gradeLevel`/`className`. Nothing here filters
     * client-side — the caller pages this endpoint like every other admin list.
     *
     * `gradeLevel`/`classId`/`className` are the linked **student's** own
     * placement and are null for every staff account; `scopeGradeIds` is a
     * staff account's grade bond and is empty for a student. They answer
     * different questions and neither substitutes for the other.
     */
    async list(params?: ListParams): Promise<PageResult<{ id: string; username: string; displayName: string; email: string; roleCode: string; status: string; lockedUntil?: string; lastLoginAt?: string; version: number; scopeGradeIds: string[]; studentId?: string; staffId?: string; gradeId?: string; gradeLevel?: number; classId?: string; className?: string }>> {
      // `email` is optional on the wire and **absent, not null**, when unset:
      // Jackson's non-null inclusion drops the field entirely. Since issue 096
      // made the field genuinely optional, accounts without one are ordinary,
      // and every consumer here treats `email` as a string — one `.trim()` on
      // `undefined` white-screened the Users tab. Normalised to `''` once, at
      // the boundary, rather than guarded at each of the places that read it.
      type UserDtoWire = { id: string; username: string; displayName: string; email?: string | null; roleCode: string; status: string; lockedUntil?: string; lastLoginAt?: string; version: number; scopeGradeIds?: string[]; studentId?: string; staffId?: string; gradeId?: string | null; gradeLevel?: number | null; classId?: string | null; className?: string | null };
      const filter = { ...params?.filter };
      if (filter.status) filter.status = toBackendEnum(filter.status);
      const page = await http.getPage<UserDtoWire>('/users', buildListQuery({ ...params, filter }));
      return {
        data: page.data.map(u => ({
          id: u.id, username: u.username, displayName: u.displayName, email: u.email ?? '',
          roleCode: u.roleCode, status: fromBackendEnum(u.status),
          lockedUntil: u.lockedUntil, lastLoginAt: u.lastLoginAt, version: u.version, scopeGradeIds: u.scopeGradeIds ?? [],
          studentId: u.studentId, staffId: u.staffId,
          gradeId: u.gradeId ?? undefined, gradeLevel: u.gradeLevel ?? undefined,
          classId: u.classId ?? undefined, className: u.className ?? undefined,
        })),
        page: page.page,
      };
    },
    /**
     * **Creates no password** (issue 096 §3/§4). The account is born
     * `PENDING_ACTIVATION` with no stored hash and cannot be signed into by
     * anybody, including whoever created it, until an activation slip is
     * redeemed — so a caller must follow this with `resetPassword(id)` to print
     * one, exactly as a class batch prints them for students.
     *
     * `email` is optional here because it is optional on the server
     * (`CreateUserRequest.email` carries `@Email` and no `@NotBlank`), and
     * `username` is its own field: this used to send `email.split('@')[0]`,
     * which is what made an optional field effectively required.
     */
    async create(data: { username: string; displayName: string; email?: string; roleCode: string; preferredLanguage?: string; scopeGradeIds?: string[]; studentId?: string; staffId?: string }): Promise<{ id: string }> {
      return http.post<{ id: string }>('/users', data);
    },
    async update(id: string, data: { displayName?: string; email?: string; status?: string; roleCode?: string; version: number; scopeGradeIds?: string[]; studentId?: string; staffId?: string }): Promise<void> {
      const body = { ...data, status: data.status ? toBackendEnum(data.status) : undefined };
      await http.patch<void>(`/users/${id}`, body);
    },
    /**
     * An admin reset no longer produces a password to show
     * (`Docs/optimization/10-StudentIdentityAndOnboarding.md` §3.7): it clears
     * the account's password, issues a single-use activation code and streams
     * back the same printable credential slip a class batch prints, so the
     * user sets their own password. There is nothing to display — the caller
     * downloads the PDF.
     *
     * `postPdf`, not `http.post`: the response is `application/pdf`, not the
     * `data`/`meta` envelope `http.ts` unwraps. That path used to be inlined
     * here and is now shared with the class batch below — the two are the same
     * document produced two ways, and the slip's language comes off the
     * `Accept-Language` header `postPdf` sends (§2.9).
     */
    async resetPassword(id: string): Promise<PdfDownload> {
      return postPdf(`/users/${id}/reset-password`, undefined, 'activation-slip.pdf');
    },
    /**
     * Contract §4.15 / plan 10 §3.4: one PDF holding one printable activation
     * slip per still-pending student in `classId`.
     *
     * **This retires codes.** Every slip it prints carries a freshly minted
     * code, and minting one retires whatever code that student held before
     * (§2.7 — a lost slip has to stop working), so paper already handed out for
     * this class stops working the moment this resolves. Callers must confirm
     * before calling, not after.
     *
     * Students who have already chosen a password are skipped server-side, so
     * the PDF legitimately holds fewer slips than the class has students; a
     * class where everyone has activated is a `409`, not an empty document.
     * `slipsPerPage` is 1 or 2 and nothing else (§2.8, D-8) — the union type is
     * the whole rule, matching the backend's closed enum.
     */
    async issueActivationSlips(classId: string, slipsPerPage: 1 | 2): Promise<PdfDownload> {
      return postPdf('/users/activation-slips', { classId, slipsPerPage }, 'activation-slips.pdf');
    },
    /**
     * Releases a brute-force lockout early: clears the failed-login counter and
     * `locked_until`, so the account can sign in again with its existing
     * password. It sets no credential and produces no slip — the user has not
     * forgotten anything, they were locked out by failed attempts. Use
     * `resetPassword` for the forgotten case.
     *
     * A lockout also expires on its own (15 minutes by default), so this is the
     * "don't make them wait" path, not the only way out.
     */
    async unlock(id: string): Promise<void> {
      await http.post<void>(`/users/${id}/unlock`);
    },
    /**
     * A supervisor picker's search (contract §4.15,
     * `Docs/optimization/01-i3-partner-school-terminology.md`) — minimal
     * fields, capped, scoped to one role, blank on an empty search.
     */
    async directory(role: string, search: string): Promise<TeacherDirectoryEntry[]> {
      if (!search) return [];
      return http.get<TeacherDirectoryEntry[]>('/users/directory', { role, search });
    },
  },

  /** 07-RoleFinetune.md §2.2-§2.4: role CRUD, `sysadmin` deliberately not filtered here — the UI layer hides it (§2.2), not the API client. */
  roles: {
    async list(): Promise<RoleSummary[]> {
      return http.get<RoleSummary[]>('/roles');
    },
    async create(data: { code: string; name: string; sessionTimeoutMinutes: number; permissionCodes: string[] }): Promise<RoleSummary> {
      return http.post<RoleSummary>('/roles', data);
    },
    async update(id: string, data: { name?: string; sessionTimeoutMinutes?: number; permissionCodes?: string[]; version: number }): Promise<RoleSummary> {
      return http.patch<RoleSummary>(`/roles/${id}`, data);
    },
    async remove(id: string): Promise<void> {
      await http.delete<void>(`/roles/${id}`);
    },
  },

  /** 07-RoleFinetune.md §2.7: no code/sessionTimeoutMinutes — a template pre-fills a new role's permission set only. Write endpoints are admin/sysadmin-only server-side, not just `roles.manage`-gated. */
  roleTemplates: {
    async list(): Promise<RoleTemplateSummary[]> {
      return http.get<RoleTemplateSummary[]>('/role-templates');
    },
    async create(data: { name: string; permissionCodes: string[] }): Promise<RoleTemplateSummary> {
      return http.post<RoleTemplateSummary>('/role-templates', data);
    },
    async update(id: string, data: { name: string; permissionCodes: string[]; version: number }): Promise<RoleTemplateSummary> {
      return http.patch<RoleTemplateSummary>(`/role-templates/${id}`, data);
    },
    async remove(id: string): Promise<void> {
      await http.delete<void>(`/role-templates/${id}`);
    },
  },

  permissions: {
    async list(): Promise<Permission[]> {
      return http.get<Permission[]>('/permissions');
    },
  },

  imports: {
    async template(type: string): Promise<Blob> {
      const response = await fetch(`/api/v1/imports/templates/${type}`, { credentials: 'include' });
      if (!response.ok) throw new ApiError({ code: 'DOWNLOAD_FAILED', message: 'Template download failed', requestId: '' });
      return response.blob();
    },
    // Real `JobDto` has no `invalidRows` field (compute it as totalRows -
    // validRows once the job is VALIDATED) and no `committedRows` field —
    // the committed count travels in `validRows` too, overloaded the same
    // way `Job.validRows` on the backend itself is documented to mean
    // different things at different phases (00-Status.md §18). `totalRows`/
    // `validRows` are only meaningful once status has moved past
    // QUEUED/VALIDATING/COMMITTING — callers must poll `get()`, not trust
    // the immediate response from `create`/`validate`/`commit`, which
    // return before the async run behind them has done anything.
    async create(type: string, file: File): Promise<ImportJob> {
      const formData = new FormData();
      formData.append('type', type);
      formData.append('file', file);
      const csrfToken = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)?.[1];
      const headers: Record<string, string> = {};
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
    },
    async get(id: string): Promise<ImportJob> {
      return http.get<ImportJob>(`/imports/${id}`);
    },
    async validate(id: string): Promise<ImportJob> {
      return http.post<ImportJob>(`/imports/${id}/validate`);
    },
    async errors(id: string): Promise<{ totalErrors: number; errors: { rowNumber: number; field: string; code: string; message: string }[] }> {
      return http.get(`/imports/${id}/errors`);
    },
    async commit(id: string, idempotencyKey: string): Promise<ImportJob> {
      return http.post<ImportJob>(`/imports/${id}/commit`, undefined, idempotencyKey);
    },
    async cancel(id: string): Promise<void> {
      await http.post<void>(`/imports/${id}/cancel`);
    },
  },

  exports: {
    // The real backend's JobDto field is `id`, not `jobId` — this was
    // silently mistyped, so every real export's `job.jobId` was `undefined`,
    // the polling `GET /exports/undefined` 404'd inside an uncaught
    // `setTimeout` callback, and the "Export started..." toast was the last
    // thing the user ever saw. Mapped explicitly here rather than just
    // renaming the wire field, since `jobId` is what the rest of this file
    // (and SystemAdmin.tsx) already expects.
    async create(reportType: string): Promise<{ jobId: string; status: string }> {
      const dto = await http.post<{ id: string; status: string }>('/exports', { reportType });
      return { jobId: dto.id, status: dto.status };
    },
    async get(jobId: string): Promise<{ jobId: string; status: string }> {
      const dto = await http.get<{ id: string; status: string }>(`/exports/${jobId}`);
      return { jobId: dto.id, status: dto.status };
    },
    // Real `DownloadUrlDto` has one field, `downloadUrl` — already the full
    // path with its one-time token embedded as a query param. There is no
    // separate `token` field to extract; a caller that tried to rebuild the
    // URL from a `token` field got `token=undefined`.
    async download(jobId: string): Promise<{ downloadUrl: string }> {
      return http.get<{ downloadUrl: string }>(`/exports/${jobId}/download`);
    },
  },

};

// `eventProposals.list()`/`.get()` share this real-DTO mapping — factored
// out since both need the identical field translation (see the comment on
// `eventProposals.list` for the full rationale).
type ProposalDtoWire = Omit<EventProposal, 'coOrganizerStudentIds'> & {
  proposerStudentName?: string; plannedStartAt?: string; plannedEndAt?: string; proposedRecognizedMinutes?: number;
  proposedSupervisorName?: string; organizationName?: string;
  objective?: string; expectedOutcome?: string; format?: string; capacity?: number;
  safetyPlan?: string; resourceNeeds?: string; recruitmentPlan?: string; evidencePlan?: string;
  externalContact?: string; coOrganizerStudentIds?: string[];
};
function fromProposalDto(dto: ProposalDtoWire): EventProposal {
  return mapEnumFields({
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
    // Derived from the stored window, not from `proposedRecognizedMinutes` —
    // reading both from the same field is what made the two inputs one (issue 075).
    estimatedDurationMinutes: dto.plannedStartAt && dto.plannedEndAt
      ? Math.max(1, Math.round((new Date(dto.plannedEndAt).getTime() - new Date(dto.plannedStartAt).getTime()) / 60000))
      : dto.estimatedDurationMinutes,
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
  }, ['status']);
}
