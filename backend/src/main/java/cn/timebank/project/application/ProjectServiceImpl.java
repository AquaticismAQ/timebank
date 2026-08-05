package cn.timebank.project.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.project.application.dto.ProjectCapacitySnapshot;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectGenerationRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.project.application.dto.TimelineEntryDto;
import cn.timebank.project.domain.Project;
import cn.timebank.project.domain.ProjectRevision;
import cn.timebank.project.domain.ProjectStateMachine;
import cn.timebank.project.domain.ProjectStateMachine.ActorRole;
import cn.timebank.project.domain.ProjectStatus;
import cn.timebank.project.infrastructure.EnrollmentReadRepository;
import cn.timebank.project.infrastructure.ProjectRepository;
import cn.timebank.project.infrastructure.ProjectRevisionRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.AcademicStructureService;
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
    private final EnrollmentReadRepository enrollmentReadRepository;
    private final ServiceCategoryService serviceCategoryService;
    private final OrganizationService organizationService;
    private final AcademicStructureService academicStructureService;
    private final UserService userService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    ProjectServiceImpl(ProjectRepository repository, ProjectRevisionRepository revisionRepository,
                       EnrollmentReadRepository enrollmentReadRepository, ServiceCategoryService serviceCategoryService,
                       OrganizationService organizationService, AcademicStructureService academicStructureService,
                       UserService userService, AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.enrollmentReadRepository = enrollmentReadRepository;
        this.serviceCategoryService = serviceCategoryService;
        this.organizationService = organizationService;
        this.academicStructureService = academicStructureService;
        this.userService = userService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    // Not readOnly: syncTimeBasedStatus conditionally writes (the B2 lesson).
    public Page<ProjectDto> list(Pageable pageable, String status, String categoryId, String search,
                                  Authentication auth, String resolvedLanguage) {
        // search only matches title: Project stores organizationId as a plain
        // string FK (no JPA relationship — cross-module joins aren't allowed
        // per this codebase's module-boundary ArchUnit rule), and there's no
        // denormalized organization name column to search against here.
        Specification<Project> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", ProjectStatus.class, status),
                FilterSpecifications.equalTo("categoryId", categoryId),
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

        return page.map(p -> toDto(p, categoryNames, gradeNames, enrolledCounts, myStatuses, ownerNames, orgNames));
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
    public ProjectDto createDraft(CreateProjectRequest request, String actorId, String resolvedLanguage) {
        requireCategory(request.categoryId());
        if (request.organizationId() != null) {
            organizationService.get(request.organizationId());
        }
        if (request.eligibleGradeIds() != null) {
            requireGrades(request.eligibleGradeIds());
        }

        Instant now = Instant.now();
        Project project = new Project(OpaqueIds.generate("proj"), request.title(), request.categoryId(), actorId, null,
                request.startAt(), request.endAt(), request.capacity(), request.recognizedMinutes(),
                request.registrationOpenAt(), request.registrationCloseAt(), now);
        project.setSummary(request.summary());
        project.setDescription(request.description());
        project.setOrganizationId(request.organizationId());
        project.setLocation(request.location());
        project.setEligibleGradeIds(request.eligibleGradeIds());
        project.setEvidenceRequirements(request.evidenceRequirements());
        repository.save(project);

        auditService.record(new AuditEntry(actorId, "projects.create", "project.create", "project", project.getId(),
                null, null, null));
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto updateDraft(String id, UpdateProjectRequest request, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
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
            organizationService.get(request.organizationId());
            project.setOrganizationId(request.organizationId());
        }
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

        auditService.record(new AuditEntry(actorId, "projects.update", "project.update", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto publish(String id, Long version, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        ConcurrencyControl.checkVersion(project.getVersion(), version);
        ProjectStateMachine.transition(project, ProjectStatus.PUBLISHED, ActorRole.MANAGER);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.publish", "project.publish", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto close(String id, Long version, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        ConcurrencyControl.checkVersion(project.getVersion(), version);
        ProjectStateMachine.transition(project, ProjectStatus.CLOSED, ActorRole.MANAGER);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.close", "project.close", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProjectDto archive(String id, Long version, String actorId, String resolvedLanguage) {
        Project project = findOrThrow(id);
        syncTimeBasedStatus(project);
        ConcurrencyControl.checkVersion(project.getVersion(), version);
        ProjectStateMachine.transition(project, ProjectStatus.ARCHIVED, ActorRole.MANAGER);
        project.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "projects.archive", "project.archive", "project", project.getId(),
                null, null, null));
        repository.saveAndFlush(project);
        return toSingleDtoNoScope(project, actorId, resolvedLanguage);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String id, Authentication auth) {
        Project project = findInScopeOrThrow(id, auth);
        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.add(new TimelineEntryDto("created", "Project created", project.getOwnerId(), project.getCreatedAt()));
        for (ProjectRevision rev : revisionRepository.findByProjectIdOrderByRevisionNumberAsc(id)) {
            entries.add(new TimelineEntryDto("revised", "Fields updated", rev.getEditedBy(), rev.getCreatedAt()));
        }
        entries.sort(java.util.Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
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
        project.setLocation(request.location());
        project.setEvidenceRequirements(request.evidenceRequirements());
        request.studentOrganizerIds().forEach(project::addStudentOrganizer);
        repository.save(project);

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

    // --- time-based status sync (B4.2's scope decision — see plan/status doc) ---

    private void syncTimeBasedStatus(Project project) {
        Instant now = Instant.now();
        if (project.getStatus() == ProjectStatus.PUBLISHED && !now.isBefore(project.getRegistrationOpenAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.REGISTRATION_OPEN, ActorRole.SYSTEM);
        }
        if (project.getStatus() == ProjectStatus.REGISTRATION_OPEN && !now.isBefore(project.getStartAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.IN_PROGRESS, ActorRole.SYSTEM);
        }
        if (project.getStatus() == ProjectStatus.IN_PROGRESS && !now.isBefore(project.getEndAt())) {
            ProjectStateMachine.transition(project, ProjectStatus.COMPLETED, ActorRole.SYSTEM);
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

    private Specification<Project> visibilitySpec(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (isManager(principal)) {
            return null;
        }
        return (root, query, cb) -> cb.or(
                cb.notEqual(root.get("status"), ProjectStatus.DRAFT),
                cb.equal(root.get("ownerId"), principal.id()));
    }

    private boolean isManager(TimeBankPrincipal principal) {
        return principal.hasPermission("projects.update") || principal.roleCodes().contains("admin")
                || principal.roleCodes().contains("sysadmin");
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
        return toDto(p, categoryNames, gradeNames, enrolledCounts, myStatuses, ownerNames, orgNames);
    }

    /** For create/update/action responses, where there is no ambient scope-check to reuse (the actor already passed a permission check). */
    private ProjectDto toSingleDtoNoScope(Project p, String actorId, String resolvedLanguage) {
        Map<String, String> categoryNames = categoryNameMap(resolvedLanguage);
        Map<String, String> gradeNames = gradeNameMap(resolvedLanguage);
        Map<String, Integer> enrolledCounts = enrollmentReadRepository.countConfirmedByProjectIds(List.of(p.getId()));
        Map<String, String> ownerNames = userService.displayNamesOf(List.of(p.getOwnerId()));
        Map<String, String> orgNames = organizationNameMap(List.of(p));
        return toDto(p, categoryNames, gradeNames, enrolledCounts, Map.of(), ownerNames, orgNames);
    }

    private ProjectDto toDto(Project p, Map<String, String> categoryNames, Map<String, String> gradeNames,
                              Map<String, Integer> enrolledCounts, Map<String, String> myStatuses,
                              Map<String, String> ownerNames, Map<String, String> orgNames) {
        List<String> eligibleGradeNames = p.getEligibleGradeIds() == null ? List.of()
                : p.getEligibleGradeIds().stream().map(id -> gradeNames.getOrDefault(id, "")).toList();
        return new ProjectDto(p.getId(), p.getTitle(), p.getSummary(), p.getDescription(),
                p.getCategoryId(), categoryNames.getOrDefault(p.getCategoryId(), ""),
                p.getOrganizationId(), p.getOrganizationId() != null ? orgNames.getOrDefault(p.getOrganizationId(), "") : null,
                p.getOwnerId(), ownerNames.getOrDefault(p.getOwnerId(), ""),
                p.getOriginProposalId(), List.copyOf(p.getStudentOrganizerIds()),
                p.getStartAt(), p.getEndAt(), p.getLocation(),
                p.getCapacity(), enrolledCounts.getOrDefault(p.getId(), 0), p.getRecognizedMinutes(), eligibleGradeNames,
                p.getEvidenceRequirements(), p.getRegistrationOpenAt(), p.getRegistrationCloseAt(),
                p.getStatus().name(), myStatuses.get(p.getId()),
                p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
