package cn.timebank.project.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.project.application.dto.ProjectCapacitySnapshot;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectGenerationRequest;
import cn.timebank.project.application.dto.ProjectRequests.CancelProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.project.domain.DeliveryScope;
import cn.timebank.project.domain.Project;
import cn.timebank.project.domain.ProjectOrganization;
import cn.timebank.project.domain.ProjectOrganizationRole;
import cn.timebank.project.domain.ProjectRevision;
import cn.timebank.project.domain.ProjectStateMachine;
import cn.timebank.project.domain.ProjectStateMachine.ActorRole;
import cn.timebank.project.domain.ProjectStatus;
import cn.timebank.project.infrastructure.EnrollmentReadRepository;
import cn.timebank.project.infrastructure.ProjectArchiveReadinessRepository;
import cn.timebank.project.infrastructure.ProjectOrganizationRepository;
import cn.timebank.project.infrastructure.ProjectRepository;
import cn.timebank.project.infrastructure.ProjectRevisionRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.AcademicYearWindowResolver;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.GradeDto;
import cn.timebank.iam.application.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository repository;
    private final ProjectRevisionRepository revisionRepository;
    private final ProjectOrganizationRepository projectOrganizationRepository;
    private final EnrollmentReadRepository enrollmentReadRepository;
    private final ProjectArchiveReadinessRepository archiveReadinessRepository;
    private final ServiceCategoryService serviceCategoryService;
    private final OrganizationService organizationService;
    private final AcademicStructureService academicStructureService;
    private final AcademicYearWindowResolver academicYearWindows;
    private final StudentService studentService;
    private final ProjectPublishNotifier publishNotifier;
    private final DataScopeResolver dataScopeResolver;
    private final UserService userService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    ProjectServiceImpl(ProjectRepository repository, ProjectRevisionRepository revisionRepository,
                       ProjectOrganizationRepository projectOrganizationRepository,
                       EnrollmentReadRepository enrollmentReadRepository, ProjectArchiveReadinessRepository archiveReadinessRepository,
                       ServiceCategoryService serviceCategoryService,
                       OrganizationService organizationService, AcademicStructureService academicStructureService,
                       AcademicYearWindowResolver academicYearWindows,
                       StudentService studentService, ProjectPublishNotifier publishNotifier,
                       DataScopeResolver dataScopeResolver,
                       UserService userService, AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.projectOrganizationRepository = projectOrganizationRepository;
        this.enrollmentReadRepository = enrollmentReadRepository;
        this.archiveReadinessRepository = archiveReadinessRepository;
        this.serviceCategoryService = serviceCategoryService;
        this.organizationService = organizationService;
        this.academicStructureService = academicStructureService;
        this.academicYearWindows = academicYearWindows;
        this.studentService = studentService;
        this.publishNotifier = publishNotifier;
        this.dataScopeResolver = dataScopeResolver;
        this.userService = userService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    // Not readOnly: syncTimeBasedStatus conditionally writes (the B2 lesson).
    public Page<ProjectDto> list(Pageable pageable, String status, String categoryId, String gradeId,
                                  String academicYearId, String search,
                                  Authentication auth, String resolvedLanguage) {
        // search only matches title: Project stores organizationId as a plain
        // string FK (no JPA relationship — cross-module joins aren't allowed
        // per this codebase's module-boundary ArchUnit rule), and there's no
        // denormalized organization name column to search against here.
        Specification<Project> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", ProjectStatus.class, status),
                FilterSpecifications.equalTo("categoryId", categoryId),
                eligibleGradeSpecification(gradeId),
                academicYearSpecification(academicYearId, auth),
                FilterSpecifications.searchAnyContains(search, "title"));
        Page<Project> page = repository.findAll(FilterSpecifications.and(visibilitySpec(auth), filterSpec), pageable);
        page.getContent().forEach(this::syncTimeBasedStatus);
        // Same reason as getDetail's flush — any row's version here might be
        // chained straight into an action call.
        repository.saveAllAndFlush(page.getContent());

        Map<String, String> categoryNames = categoryNameMap(resolvedLanguage);
        Map<String, String> gradeNames = gradeNameMap(resolvedLanguage);
        List<String> projectIds = page.getContent().stream().map(Project::getId).toList();
        Map<String, Integer> enrolledCounts = enrollmentReadRepository.countConfirmedByProjectIds(projectIds);
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        Map<String, String> myStatuses = enrollmentReadRepository.myApplicationStatusByProjectIds(projectIds, principal.studentId());
        Map<String, String> ownerNames = userService.displayNamesOf(
                page.getContent().stream().map(Project::getOwnerId).distinct().toList());
        Map<String, String> orgNames = organizationNameMap(page.getContent());
        OrganizationDto schoolOrg = schoolOrganization();

        return page.map(p -> toDto(p, categoryNames, gradeNames, enrolledCounts, myStatuses, ownerNames, orgNames, schoolOrg));
    }

    @Override
    @Transactional
    public ProjectDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        Project project = findInScopeOrThrow(id, auth);
        syncTimeBasedStatus(project);
        // Flush before building the DTO: syncTimeBasedStatus may have just
        // mutated status, and @Version only increments in memory at flush
        // time. Without this, a caller chaining this response's version into
        // a later publish/close/archive check would see a stale value and
        // hit a spurious optimistic-lock conflict (the same B3 lesson
        // ServiceRecordServiceImpl's mutating methods already apply).
        repository.saveAndFlush(project);
        return toSingleDto(project, auth, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto getDetail(String id, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, null, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto createDraft(CreateProjectRequest request, String actorId, boolean actorIsAdmin, String resolvedLanguage) {
        requireCategory(request.categoryId());
        String schoolOrgId = organizationService.getSchoolOrganization().id();
        if (request.organizationId() != null) {
            requirePartnerOrganization(request.organizationId(), schoolOrgId);
        }
        DeliveryScope deliveryScope = parseDeliveryScope(request.deliveryScope());
        if (request.eligibleGradeIds() != null) {
            requireGrades(request.eligibleGradeIds());
        }
        String ownerId = resolveOwnerIdForCreate(request.ownerId(), actorIsAdmin, actorId);

        Instant now = Instant.now();
        Project project = new Project(OpaqueIds.generate("proj"), request.title(), request.categoryId(), ownerId, null,
                request.startAt(), request.endAt(), request.capacity(), request.recognizedMinutes(),
                request.registrationOpenAt(), request.registrationCloseAt(), now);
        project.setSummary(request.summary());
        project.setDescription(request.description());
        project.setOrganizationId(request.organizationId());
        project.setDeliveryScope(deliveryScope);
        project.setLocation(request.location());
        project.setEligibleGradeIds(request.eligibleGradeIds());
        project.setEvidenceRequirements(request.evidenceRequirements());
        validateDateOrdering(project);
        repository.save(project);
        writeProjectOrganizations(project.getId(), schoolOrgId, request.organizationId());

        auditService.record(new AuditEntry(actorId, "projects.create", "project.create", "project", project.getId(),
                null, null, null));
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto updateDraft(String id, UpdateProjectRequest request, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        String actorId = principal.id();
        boolean actorIsAdmin = principal.hasFullDataScope();
        Project project = findOrThrow(id);
        // Staff see every project and may copy any of them; changing one is
        // restricted to the grades they are responsible for (issue 129).
        requireWithinActorsGrades(project, principal);
        // Before the guard below, so it judges the status the project actually
        // has rather than the one last written: a project whose end date has
        // passed is COMPLETED whether or not anything has read it since.
        syncTimeBasedStatus(project);
        requireEditable(project);
        ConcurrencyControl.checkVersion(project.getVersion(), request.version());
        writeRevision(project, actorId);

        if (request.title() != null) project.setTitle(request.title());
        if (request.summary() != null) project.setSummary(request.summary());
        if (request.description() != null) project.setDescription(request.description());
        if (request.categoryId() != null) {
            requireCategory(request.categoryId());
            project.setCategoryId(request.categoryId());
        }
        if (request.organizationId() != null) {
            String schoolOrgId = organizationService.getSchoolOrganization().id();
            requirePartnerOrganization(request.organizationId(), schoolOrgId);
            project.setOrganizationId(request.organizationId());
            projectOrganizationRepository.deleteByProjectIdAndRole(project.getId(), ProjectOrganizationRole.PARTNER);
            // The flush is the whole fix, not a precaution (issue 122).
            //
            // Hibernate orders inserts before deletes at flush regardless of the
            // order they were requested in. So delete-then-save of the *same*
            // (project, organization, PARTNER) triple sent the insert first and
            // hit `uk_project_organization_role` — a 500 on every ordinary save
            // of a project whose partner organization was left alone, which is
            // the most common edit there is.
            projectOrganizationRepository.flush();
            projectOrganizationRepository.save(new ProjectOrganization(OpaqueIds.generate("porg"), project.getId(),
                    request.organizationId(), ProjectOrganizationRole.PARTNER, 1));
        }
        if (request.deliveryScope() != null) {
            project.setDeliveryScope(parseDeliveryScope(request.deliveryScope()));
        }
        applyOwnerIfPermitted(project, request.ownerId(), actorIsAdmin);
        if (request.startAt() != null) project.setStartAt(request.startAt());
        if (request.endAt() != null) project.setEndAt(request.endAt());
        if (request.location() != null) project.setLocation(request.location());
        if (request.capacity() != null) project.setCapacity(request.capacity());
        if (request.recognizedMinutes() != null) project.setRecognizedMinutes(request.recognizedMinutes());
        if (request.eligibleGradeIds() != null) {
            requireGrades(request.eligibleGradeIds());
            project.setEligibleGradeIds(request.eligibleGradeIds());
        }
        if (request.evidenceRequirements() != null) project.setEvidenceRequirements(request.evidenceRequirements());
        if (request.registrationOpenAt() != null) project.setRegistrationOpenAt(request.registrationOpenAt());
        if (request.registrationCloseAt() != null) project.setRegistrationCloseAt(request.registrationCloseAt());
        project.setUpdatedAt(Instant.now());
        validateDateOrdering(project);

        auditService.record(new AuditEntry(actorId, "projects.update", "project.update", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto publish(String id, Long version, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        String actorId = principal.id();
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        // Publishing is a change like any other, so the same grade rule applies
        // (issue 130). Without it a teacher could publish an out-of-grade draft
        // they are not allowed to edit, which is not a coherent pair of powers.
        requireWithinActorsGrades(project, principal);
        requirePublishableWindow(project);
        ConcurrencyControl.checkVersion(project.getVersion(), version);
        ProjectStateMachine.transition(project, ProjectStatus.PUBLISHED, ActorRole.MANAGER);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.publish", "project.publish", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        // Tell the year groups it is open to (issue 137). After the flush, so a
        // failed publish cannot leave notifications about a project nobody can
        // see; inside the transaction, so a failed announcement rolls the
        // publish back rather than half-announcing it.
        publishNotifier.announce(project.getId(), project.getEligibleGradeIds());
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto archive(String id, Long version, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        ConcurrencyControl.checkVersion(project.getVersion(), version);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            int unresolved = archiveReadinessRepository.countUnresolvedConfirmedStudents(project.getId());
            if (unresolved > 0) {
                // The count travels as a coded field error, not inside the
                // sentence: the sentence is English-only (this backend authors
                // no localized copy) and this one is meant to be read by a
                // teacher, so the client renders its own wording around the
                // number — the same arrangement `OVERLAPPING_RECORD` already
                // uses. Issue 120.
                //
                // No longer says "Use Cancel and archive to override": that
                // button was removed from a completed project in issue 119, and
                // an error naming an action that does not exist is worse than
                // one that names none. Reviewing the records is the way out.
                throw ApiException.stateConflict(
                        unresolved + " confirmed student(s) still need their service record reviewed "
                                + "before this project can be archived.",
                        List.of(new FieldErrorDetail("status", "UNREVIEWED_RECORDS", String.valueOf(unresolved))));
            }
        }
        ProjectStateMachine.transition(project, ProjectStatus.ARCHIVED, ActorRole.MANAGER);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.archive", "project.archive", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    /** The manager override (02-PorjectOrganization.md §2.1) — bypasses the reviewed-records gate {@link #archive} enforces from COMPLETED; requires a reason, logged as the audit entry's reasonCode. */
    @Override
    @Transactional
    public ProjectDto cancel(String id, CancelProjectRequest request, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        ConcurrencyControl.checkVersion(project.getVersion(), request.version());
        ProjectStateMachine.cancel(project);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.cancel", "project.cancel", "project", project.getId(),
                null, null, request.reason()));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }


    @Override
    @Transactional
    public ProjectCapacitySnapshot lockForRegistration(String projectId) {
        Project project = repository.findWithLockById(projectId).orElseThrow(() -> ApiException.notFound("Project not found."));
        syncTimeBasedStatus(project);
        return new ProjectCapacitySnapshot(project.getId(), project.getCapacity(), project.getStatus().name());
    }

    @Override
    @Transactional
    public String createFromProposal(ProjectGenerationRequest request) {
        Instant now = Instant.now();
        Project project = new Project(OpaqueIds.generate("proj"), request.title(), request.categoryId(), request.ownerId(),
                request.originProposalId(), request.startAt(), request.endAt(), request.capacity(),
                request.recognizedMinutes(), now, request.startAt(), now);
        project.setSummary(request.summary());
        project.setDescription(request.description());
        project.setOrganizationId(request.organizationId());
        // The proposal flow has no on/off-campus question of its own — same
        // "unknown, needs manual review" default V18's column default applies to
        // pre-existing projects (issue 03 §10 D5), not a guess either way.
        project.setDeliveryScope(DeliveryScope.HYBRID);
        project.setLocation(request.location());
        project.setEvidenceRequirements(request.evidenceRequirements());
        request.studentOrganizerIds().forEach(project::addStudentOrganizer);
        repository.save(project);
        String schoolOrgId = organizationService.getSchoolOrganization().id();
        writeProjectOrganizations(project.getId(), schoolOrgId, request.organizationId());

        auditService.record(new AuditEntry(request.ownerId(), "projects.create", "project.generatedFromProposal",
                "project", project.getId(), null, request.originProposalId(), null));
        return project.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> titlesOf(List<String> projectIds) {
        return repository.findAllById(projectIds).stream()
                .collect(Collectors.toMap(Project::getId, Project::getTitle));
    }

    /**
     * The two statuses no edit may touch (issue 119).
     *
     * COMPLETED because its recognised hours are already credited to students'
     * accounts — changing them afterwards leaves every transaction citing this
     * project disagreeing with it, and transactions are immutable by design.
     * ARCHIVED because it is terminal. Every other status is fair game: a
     * published or running project may still need its location corrected.
     *
     * Not a {@code ProjectStateMachine} edge, because editing is not a
     * transition — the status does not change, which is precisely the point.
     */
    private static void requireEditable(Project project) {
        if (project.getStatus() == ProjectStatus.COMPLETED || project.getStatus() == ProjectStatus.ARCHIVED) {
            throw ApiException.stateConflict(
                    "A project in status " + project.getStatus() + " cannot be edited.",
                    List.of(new FieldErrorDetail("status", "PROJECT_NOT_EDITABLE", project.getStatus().name())));
        }
    }

    @Override
    @Transactional
    public int reconcileTimeBasedStatuses() {
        List<Project> overtaken = repository.findOvertakenByClock(Instant.now());
        int advanced = 0;
        for (Project project : overtaken) {
            ProjectStatus before = project.getStatus();
            syncTimeBasedStatus(project);
            if (project.getStatus() != before) {
                advanced++;
            }
        }
        // Saved even for the rows that did not move: `syncTimeBasedStatus` is
        // the same call every read makes, and letting the transaction decide
        // what is dirty is cheaper than reasoning about it here.
        repository.saveAllAndFlush(overtaken);
        return advanced;
    }

    // --- time-based status sync (B4.2's scope decision — see plan/status doc) ---

    private void syncTimeBasedStatus(Project project) {
        Instant now = Instant.now();
        if (project.getStatus() == ProjectStatus.PUBLISHED && !now.isBefore(project.getRegistrationOpenAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.REGISTRATION_OPEN, ActorRole.SYSTEM);
        }
        if (project.getStatus() == ProjectStatus.REGISTRATION_OPEN && !now.isBefore(project.getRegistrationCloseAt())) {
            // A registration window that closes with nobody signed up has
            // nothing left to run — self-archive instead of proceeding to
            // CLOSED/IN_PROGRESS, with an audit trail explaining why (no
            // actor to attribute it to, hence the null actorId/permission —
            // AuditLog.actor_id is nullable for exactly this kind of purely
            // system-initiated event).
            if (enrollmentReadRepository.countConfirmed(project.getId()) == 0) {
                ProjectStateMachine.transition(project, ProjectStatus.ARCHIVED, ActorRole.SYSTEM);
                auditService.record(new AuditEntry(null, null, "project.autoArchiveNoRegistrants", "project",
                        project.getId(), null, null, "NO_REGISTRANTS_AT_REGISTRATION_CLOSE"));
            } else {
                ProjectStateMachine.transition(project, ProjectStatus.CLOSED, ActorRole.SYSTEM);
            }
        }
        if (project.getStatus() == ProjectStatus.CLOSED && !now.isBefore(project.getStartAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.IN_PROGRESS, ActorRole.SYSTEM);
        }
        if (project.getStatus() == ProjectStatus.IN_PROGRESS && !now.isBefore(project.getEndAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.COMPLETED, ActorRole.SYSTEM);
        }
    }

    /** Admin-only override; anyone else's `ownerId` is silently forced to themselves (02-PorjectOrganization.md §3), never rejected. */
    private String resolveOwnerIdForCreate(String requestedOwnerId, boolean actorIsAdmin, String actorId) {
        if (requestedOwnerId == null || requestedOwnerId.equals(actorId) || !actorIsAdmin) {
            return actorId;
        }
        requireTeacherOrAdmin(requestedOwnerId);
        return requestedOwnerId;
    }

    /** Reassignment on update is admin-only; a non-admin's `ownerId` field, if sent at all, is ignored rather than applied. */
    private void applyOwnerIfPermitted(Project project, String requestedOwnerId, boolean actorIsAdmin) {
        if (requestedOwnerId == null || !actorIsAdmin) {
            return;
        }
        requireTeacherOrAdmin(requestedOwnerId);
        project.setOwnerId(requestedOwnerId);
    }

    private void requireTeacherOrAdmin(String userId) {
        if (!userService.hasActiveRole(userId, "teacher") && !userService.hasActiveRole(userId, "admin")) {
            throw ApiException.invalidRequest("ownerId must be an active teacher or admin user.");
        }
    }

    /** Required for CLOSED (registration-elapsed) to ever actually reach IN_PROGRESS — a project whose registration window closes after its own start date would otherwise stall in REGISTRATION_OPEN forever, since IN_PROGRESS is now only reachable via CLOSED. */
    /**
     * A draft whose registration window has already closed cannot be published
     * (issue 130).
     *
     * <p>Publishing it is not merely pointless — it is destructive.
     * {@code syncTimeBasedStatus} runs on the very next read: PUBLISHED →
     * REGISTRATION_OPEN → and, with nobody enrolled, straight to ARCHIVED. The
     * draft someone had been preparing is gone, with a success toast and no
     * error anywhere. Issue 119 documented this trap for copied projects; this
     * closes it for every draft.</p>
     *
     * <p>Checking {@code registrationCloseAt} alone is enough because
     * {@link #validateDateOrdering} already requires it to be on or before
     * {@code startAt} — a future close date implies a future start.</p>
     *
     * <p>The auto-archive path itself is untouched and still reachable the way
     * it actually happens: a project published with a live window whose window
     * later elapses with nobody signed up.</p>
     */
    private void requirePublishableWindow(Project project) {
        if (project.getStatus() != ProjectStatus.DRAFT) {
            return;
        }
        if (!project.getRegistrationCloseAt().isAfter(Instant.now())) {
            throw ApiException.stateConflict(
                    "This project's registration window closed at " + project.getRegistrationCloseAt()
                            + ", so publishing it would archive it immediately. Change its dates first.",
                    List.of(new FieldErrorDetail("registrationCloseAt", "PROJECT_REGISTRATION_WINDOW_PASSED",
                            project.getRegistrationCloseAt().toString())));
        }
    }

    private void validateDateOrdering(Project project) {
        if (project.getRegistrationCloseAt().isAfter(project.getStartAt())) {
            throw ApiException.invalidRequest("registrationCloseAt must not be after startAt.");
        }
        if (project.getRegistrationCloseAt().isBefore(project.getRegistrationOpenAt())) {
            throw ApiException.invalidRequest("registrationCloseAt must not be before registrationOpenAt.");
        }
    }

    private void writeRevision(Project project, String editedBy) {
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(Map.of(
                    "title", project.getTitle(), "startAt", project.getStartAt().toString(),
                    "endAt", project.getEndAt().toString(), "capacity", project.getCapacity()));
        } catch (Exception e) {
            snapshot = "{}";
        }
        revisionRepository.save(new ProjectRevision(OpaqueIds.generate("prjr"), project.getId(),
                1, snapshot, editedBy, Instant.now()));
    }

    /** HOST_SCHOOL is automatic (§4.2) — a client naming the school org as `organizationId` is almost certainly confused, not intentional. */
    private void requirePartnerOrganization(String organizationId, String schoolOrgId) {
        organizationService.get(organizationId);
        if (organizationId.equals(schoolOrgId)) {
            throw ApiException.invalidRequest(
                    "organizationId must be a partner organization — the school organization is already the host by default.");
        }
    }

    /** Always writes HOST_SCHOOL; writes PARTNER only if a distinct organization was supplied (defensive on the school-org-equals check for the no-user-input proposal-generation path — that one has nothing to report a field error to). */
    private void writeProjectOrganizations(String projectId, String schoolOrgId, String partnerOrganizationId) {
        projectOrganizationRepository.save(new ProjectOrganization(OpaqueIds.generate("porg"), projectId,
                schoolOrgId, ProjectOrganizationRole.HOST_SCHOOL, 0));
        if (partnerOrganizationId != null && !partnerOrganizationId.equals(schoolOrgId)) {
            projectOrganizationRepository.save(new ProjectOrganization(OpaqueIds.generate("porg"), projectId,
                    partnerOrganizationId, ProjectOrganizationRole.PARTNER, 1));
        }
    }

    private DeliveryScope parseDeliveryScope(String raw) {
        if (raw == null) {
            throw ApiException.invalidRequest("deliveryScope is required.");
        }
        try {
            return DeliveryScope.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown deliveryScope: " + raw);
        }
    }

    private void requireCategory(String categoryId) {
        boolean exists = serviceCategoryService.list("zh-CN").stream().anyMatch(c -> c.id().equals(categoryId));
        if (!exists) {
            throw ApiException.invalidRequest("Unknown service category: " + categoryId);
        }
    }

    private void requireGrades(List<String> gradeIds) {
        Set<String> known = academicStructureService.listGrades("zh-CN").stream().map(GradeDto::id).collect(Collectors.toSet());
        for (String gradeId : gradeIds) {
            if (!known.contains(gradeId)) {
                throw ApiException.invalidRequest("Unknown grade: " + gradeId);
            }
        }
    }

    private Project findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Project not found."));
    }

    /** "Visible" (contract §4.2) = non-draft, or the caller owns it, or the caller can manage projects generally. */
    private Project findInScopeOrThrow(String id, Authentication auth) {
        Specification<Project> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<Project> combined = idSpec.and(visibilitySpec(auth));
        return repository.findOne(combined).orElseThrow(() -> ApiException.notFound("Project not found."));
    }

    /**
     * Narrows the list to one academic year's projects (issue 112).
     *
     * <p><b>A project belongs to the year its {@code startAt} falls in.</b> There
     * is no academic-year column on {@code project}, so the rule is derived — and
     * it is deliberately the same rule {@code /reports/top-projects} and the
     * {@code projects} export already use. Three definitions of "this year's
     * projects" would be three chances for two screens to disagree about the same
     * project.</p>
     *
     * <p>The window is the {@code academic_year} row's own {@code startDate} and
     * {@code endDate}, never 1 September arithmetic: the turnover date is
     * configurable and an existing year keeps the dates it was created with, so
     * recomputing here would drift from the row the moment either changed.</p>
     *
     * <p><b>Students are pinned to the current year</b> whatever they ask for
     * (§3.1). The Project Center is a "what can I join" screen and last year's
     * projects are not joinable; enforcing that here rather than in the component
     * means it cannot be sidestepped by editing a request, the same reasoning that
     * pins a teacher's Review History to their own decisions.</p>
     */
    private Specification<Project> academicYearSpecification(String academicYearId, Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        // Absent means every year for staff, unlike /reports/*, where it means the
        // current one — this endpoint is shared by several screens and any future
        // caller, so narrowing on a missing parameter would change behaviour
        // nobody asked to change. An unknown id is a 404 either way; both rules
        // live in the resolver now (issue 115).
        AcademicYearWindowResolver.Window window = isStudent(principal)
                ? academicYearWindows.currentOrNull()
                : academicYearWindows.resolveOrNull(academicYearId);
        if (window == null) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("startAt"), window.fromInclusive()),
                cb.lessThan(root.get("startAt"), window.untilExclusive()));
    }


    private boolean isStudent(TimeBankPrincipal principal) {
        return principal.roleCodes().contains("student");
    }

    /**
     * Who may see which project.
     *
     * <p><b>Staff see the whole school</b> — that is the design, not an
     * oversight: a teacher browses and copies any project, and is stopped at
     * *editing* one outside their grades ({@link #requireWithinActorsGrades}).
     * The two halves are deliberately different questions.</p>
     *
     * <p><b>A student sees their own grade's projects only</b> (issue 129).
     * Before this they saw all 75 published projects in the school, across
     * every grade — a Year 7 pupil browsing activities they cannot join.</p>
     *
     * <p>With one exception that has to be there: a project they have already
     * applied to stays visible whatever its grades now say. Eligible grades are
     * editable after the fact, and a student whose service record points at a
     * project must be able to open it.</p>
     */
    private Specification<Project> visibilitySpec(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (isManager(principal)) {
            return null;
        }
        Specification<Project> draftsAreOwnersOnly = (root, query, cb) -> cb.or(
                cb.notEqual(root.get("status"), ProjectStatus.DRAFT),
                cb.equal(root.get("ownerId"), principal.id()));

        String studentId = principal.studentId();
        if (studentId == null) {
            return draftsAreOwnersOnly;
        }
        Set<String> visibleIds = new java.util.HashSet<>(
                repository.findIdsByEligibleGrade(studentGradeIdOrNull(studentId)));
        // A project that names no grade restricts nobody.
        visibleIds.addAll(repository.findIdsWithNoEligibleGrades());
        visibleIds.addAll(enrollmentReadRepository.projectIdsAppliedToBy(studentId));
        Set<String> ids = visibleIds.isEmpty() ? Set.of("__none__") : Set.copyOf(visibleIds);
        return FilterSpecifications.and(draftsAreOwnersOnly, (root, query, cb) -> root.get("id").in(ids));
    }

    /**
     * The student's grade, or null.
     *
     * {@code placementsOf} rather than {@code getDetail}: the latter throws
     * {@code notFound} for an unreadable row, and catching that inside the
     * surrounding transaction does not un-mark it rollback-only — the read
     * "succeeds" and the commit then fails with `UnexpectedRollback`, which is
     * how ProjectAcademicYearScopeIT caught the first version of this. A batch
     * lookup that simply omits missing rows has no such edge.
     *
     * Null narrows the list to nothing rather than widening it: not knowing a
     * student's grade is not a reason to show them every grade's projects.
     */
    private String studentGradeIdOrNull(String studentId) {
        StudentService.StudentPlacement placement = studentService.placementsOf(List.of(studentId)).get(studentId);
        return placement == null ? null : placement.gradeId();
    }

    /**
     * A teacher may edit a project that is open to one of their grades — and
     * one they own, whatever its grades (issue 129).
     *
     * <p>The ownership clause is not in the sentence Aquaticism wrote, and it has to
     * be: {@code createDraft} puts no grade restriction on what a teacher may
     * create, so without it a teacher could make a project for another year
     * group and then be unable to touch it again. "Not yours to change" is the
     * rule; "not yours to have made" is not.</p>
     *
     * <p>The scope namespace is {@code serviceRecords}, which is where a
     * teacher's grades are actually assigned — the same borrowing
     * {@code FileServiceImpl} and the dashboard already make, and for the same
     * reason: there is one set of grades a teacher is responsible for, not one
     * per resource type.</p>
     *
     * <p>A teacher with no grade assignment at all resolves to {@code Self} and
     * may therefore edit only their own projects. That is the safe reading of
     * "no grades", but it is a real consequence worth knowing about.</p>
     */
    private void requireWithinActorsGrades(Project project, TimeBankPrincipal principal) {
        // `hasSchoolWideVisibility`, not `hasFullDataScope`: the latter is
        // "any non-student" and would exempt every teacher, which is the whole
        // population this rule is about (07-RoleFinetune.md §2.10 — the same
        // distinction the dashboard and every other grade-scoped read makes).
        // The first version of this used the wrong one and the guard never
        // fired; the test caught it.
        if (principal.hasSchoolWideVisibility()) {
            return;
        }
        if (principal.id().equals(project.getOwnerId())) {
            return;
        }
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, "serviceRecords", false);
        if (scope instanceof ScopeDescriptor.All) {
            return;
        }
        Set<String> mine = scope instanceof ScopeDescriptor.Grades grades ? grades.gradeIds() : Set.of();
        List<String> projectGrades = project.getEligibleGradeIds() == null ? List.of() : project.getEligibleGradeIds();
        if (projectGrades.stream().anyMatch(mine::contains)) {
            return;
        }
        throw ApiException.forbidden(
                "This project is not open to any of the grades you are responsible for.",
                List.of(new FieldErrorDetail("eligibleGradeIds", "PROJECT_OUT_OF_YOUR_GRADES", "")));
    }

    private boolean isManager(TimeBankPrincipal principal) {
        // 07-RoleFinetune.md §2.1
        return principal.hasPermission("projects.update") || principal.hasFullDataScope();
    }

    /**
     * "Contains", not "equals" — {@code eligibleGradeIds} is a JSON array
     * (a project can list more than one grade), so this can't be a plain
     * {@code FilterSpecifications.equalTo}. Resolves the matching id set via
     * {@code ProjectRepository.findIdsByEligibleGrade}'s native {@code
     * JSON_CONTAINS} query first, then folds it into the same
     * Specification pipeline as every other filter via a normal {@code id
     * in (...)} predicate — keeps pagination/sorting/scope all going
     * through one query instead of a second, parallel filtering path.
     */
    private Specification<Project> eligibleGradeSpecification(String gradeId) {
        if (gradeId == null || gradeId.isBlank()) {
            return null;
        }
        List<String> matchingIds = repository.findIdsByEligibleGrade(gradeId);
        Set<String> idSet = matchingIds.isEmpty() ? Set.of("__none__") : Set.copyOf(matchingIds);
        return (root, query, cb) -> root.get("id").in(idSet);
    }

    private Map<String, String> categoryNameMap(String resolvedLanguage) {
        return serviceCategoryService.list(resolvedLanguage).stream()
                .collect(Collectors.toMap(ServiceCategoryDto::id, ServiceCategoryDto::name));
    }

    private Map<String, String> gradeNameMap(String resolvedLanguage) {
        return academicStructureService.listGrades(resolvedLanguage).stream()
                .collect(Collectors.toMap(GradeDto::id, GradeDto::name));
    }

    private Map<String, String> organizationNameMap(List<Project> projects) {
        Set<String> orgIds = projects.stream().map(Project::getOrganizationId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> names = new java.util.HashMap<>();
        for (String orgId : orgIds) {
            try {
                names.put(orgId, organizationService.get(orgId).name());
            } catch (ApiException e) {
                names.put(orgId, "");
            }
        }
        return names;
    }

    private ProjectDto toSingleDto(Project p, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        Map<String, String> categoryNames = categoryNameMap(resolvedLanguage);
        Map<String, String> gradeNames = gradeNameMap(resolvedLanguage);
        Map<String, Integer> enrolledCounts = enrollmentReadRepository.countConfirmedByProjectIds(List.of(p.getId()));
        Map<String, String> myStatuses = enrollmentReadRepository.myApplicationStatusByProjectIds(List.of(p.getId()), principal.studentId());
        Map<String, String> ownerNames = userService.displayNamesOf(List.of(p.getOwnerId()));
        Map<String, String> orgNames = organizationNameMap(List.of(p));
        return toDto(p, categoryNames, gradeNames, enrolledCounts, myStatuses, ownerNames, orgNames, schoolOrganization());
    }

    /** For create/update/action responses, where there is no ambient scope-check to reuse (the actor already passed a permission check). */
    private ProjectDto toSingleDtoNoScope(Project p, String actorId, String resolvedLanguage) {
        Map<String, String> categoryNames = categoryNameMap(resolvedLanguage);
        Map<String, String> gradeNames = gradeNameMap(resolvedLanguage);
        Map<String, Integer> enrolledCounts = enrollmentReadRepository.countConfirmedByProjectIds(List.of(p.getId()));
        Map<String, String> ownerNames = userService.displayNamesOf(List.of(p.getOwnerId()));
        Map<String, String> orgNames = organizationNameMap(List.of(p));
        return toDto(p, categoryNames, gradeNames, enrolledCounts, Map.of(), ownerNames, orgNames, schoolOrganization());
    }

    /** Every project's host is the same singleton (§4.2) — resolved once per response batch, not per project. */
    private OrganizationDto schoolOrganization() {
        return organizationService.getSchoolOrganization();
    }

    private ProjectDto toDto(Project p, Map<String, String> categoryNames, Map<String, String> gradeNames,
                              Map<String, Integer> enrolledCounts, Map<String, String> myStatuses,
                              Map<String, String> ownerNames, Map<String, String> orgNames,
                              OrganizationDto schoolOrg) {
        List<String> eligibleGradeNames = p.getEligibleGradeIds() == null ? List.of()
                : p.getEligibleGradeIds().stream().map(id -> gradeNames.getOrDefault(id, "")).toList();
        return new ProjectDto(p.getId(), p.getTitle(), p.getSummary(), p.getDescription(),
                p.getCategoryId(), categoryNames.getOrDefault(p.getCategoryId(), ""),
                p.getOrganizationId(), p.getOrganizationId() != null ? orgNames.getOrDefault(p.getOrganizationId(), "") : null,
                schoolOrg.id(), schoolOrg.name(), p.getDeliveryScope().name(),
                p.getOwnerId(), ownerNames.getOrDefault(p.getOwnerId(), ""),
                p.getOriginProposalId(), List.copyOf(p.getStudentOrganizerIds()),
                p.getStartAt(), p.getEndAt(), p.getLocation(),
                p.getCapacity(), enrolledCounts.getOrDefault(p.getId(), 0), p.getRecognizedMinutes(), eligibleGradeNames,
                p.getEvidenceRequirements(), p.getRegistrationOpenAt(), p.getRegistrationCloseAt(),
                p.getStatus().name(), myStatuses.get(p.getId()),
                p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
