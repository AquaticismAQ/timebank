package cn.timebank.proposal.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.iam.application.UserService;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalQueueSummaryDto;
import cn.timebank.proposal.application.dto.ProposalRequests.CreateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.UpdateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalReviewDto;
import cn.timebank.proposal.application.dto.RevisionDto;
import cn.timebank.proposal.application.dto.TimelineEntryDto;
import cn.timebank.proposal.domain.EventProposal;
import cn.timebank.proposal.domain.EventProposalRevision;
import cn.timebank.proposal.domain.ProposalStateMachine;
import cn.timebank.proposal.domain.ProposalStateMachine.ActorRole;
import cn.timebank.proposal.domain.ProposalStatus;
import cn.timebank.proposal.infrastructure.EventProposalRepository;
import cn.timebank.proposal.infrastructure.EventProposalRevisionRepository;
import cn.timebank.proposal.infrastructure.ProposalReviewEventRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.GradeDto;
import cn.timebank.student.application.dto.StudentDetailDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class ProposalServiceImpl implements ProposalService {

    private static final String RESOURCE_TYPE = "eventProposals";

    private final EventProposalRepository repository;
    private final EventProposalRevisionRepository revisionRepository;
    private final ProposalReviewEventRepository reviewEventRepository;
    private final StudentService studentService;
    private final ServiceCategoryService serviceCategoryService;
    private final OrganizationService organizationService;
    private final AcademicStructureService academicStructureService;
    private final UserService userService;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final ProposalApprovalTransaction approvalTransaction;
    private final ProposalScopeFields scopeFields = new ProposalScopeFields();

    ProposalServiceImpl(EventProposalRepository repository, EventProposalRevisionRepository revisionRepository,
                        ProposalReviewEventRepository reviewEventRepository, StudentService studentService,
                        ServiceCategoryService serviceCategoryService, OrganizationService organizationService,
                        AcademicStructureService academicStructureService,
                        UserService userService, AuditService auditService, DataScopeResolver dataScopeResolver,
                        IdempotencyService idempotencyService, ObjectMapper objectMapper,
                        ProposalApprovalTransaction approvalTransaction) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.reviewEventRepository = reviewEventRepository;
        this.studentService = studentService;
        this.serviceCategoryService = serviceCategoryService;
        this.organizationService = organizationService;
        this.academicStructureService = academicStructureService;
        this.userService = userService;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.approvalTransaction = approvalTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProposalDto> list(Pageable pageable, Authentication auth, String status, String categoryId,
                                   String gradeId, String search, String resolvedLanguage) {
        Specification<EventProposal> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", ProposalStatus.class, status),
                FilterSpecifications.equalTo("categoryId", categoryId),
                FilterSpecifications.equalTo("gradeId", gradeId),
                FilterSpecifications.searchAnyContains(search, "title"));
        Specification<EventProposal> spec = FilterSpecifications.and(scopeSpecification(auth), filterSpec);
        Page<EventProposal> page = repository.findAll(spec, pageable);
        return page.map(p -> toDto(p, resolvedLanguage));
    }

    @Override
    @Transactional(readOnly = true)
    public ProposalDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        return toDto(findInScopeOrThrow(id, auth), resolvedLanguage);
    }

    @Override
    @Transactional
    public ProposalDto createDraft(CreateProposalRequest request, String studentId, String actorId, String resolvedLanguage) {
        requireCategory(request.categoryId());
        StudentDetailDto proposer = studentService.getDetail(studentId, false, actorId, resolvedLanguage);

        List<FieldErrorDetail> errors = new ArrayList<>();
        String resolvedOrganizationId = resolveOrganizationId(request.organizationId(), request.newOrganizationName(), actorId, errors);
        validateCoOrganizers(request.coOrganizerStudentIds(), studentId, errors);
        validateSupervisor(request.proposedSupervisorId(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed("Proposal organization/people fields failed validation.", errors);
        }

        Instant now = Instant.now();
        EventProposal proposal = new EventProposal(OpaqueIds.generate("prop"), studentId, proposer.gradeId(),
                proposer.classId(), request.title(), request.categoryId(), request.plannedStartAt(),
                request.plannedEndAt(), request.capacity(), request.proposedSupervisorId(),
                request.proposedRecognizedMinutes(), now);
        proposal.setCoOrganizerStudentIds(request.coOrganizerStudentIds());
        proposal.setObjective(request.objective());
        proposal.setDescription(request.description());
        proposal.setExpectedOutcome(request.expectedOutcome());
        proposal.setLocation(request.location());
        proposal.setFormat(request.format());
        proposal.setTargetParticipants(request.targetParticipants());
        proposal.setVolunteerTasks(request.volunteerTasks());
        proposal.setOrganizationId(resolvedOrganizationId);
        proposal.setExternalContact(request.externalContact());
        proposal.setSafetyPlan(request.safetyPlan());
        proposal.setResourceNeeds(request.resourceNeeds());
        proposal.setRecruitmentPlan(request.recruitmentPlan());
        proposal.setEvidencePlan(request.evidencePlan());
        repository.save(proposal);

        auditService.record(new AuditEntry(actorId, "eventProposals.create", "eventProposal.create",
                "eventProposal", proposal.getId(), null, null, null));
        return toDto(proposal, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProposalDto updateDraft(String id, UpdateProposalRequest request, Authentication auth, String resolvedLanguage) {
        EventProposal proposal = findOwnedOrThrow(id, auth);
        if (proposal.getStatus() != ProposalStatus.DRAFT && proposal.getStatus() != ProposalStatus.CHANGES_REQUESTED) {
            throw ApiException.stateConflict("Only a draft or changes-requested proposal can be edited.");
        }
        ConcurrencyControl.checkVersion(proposal.getVersion(), request.version());
        writeRevision(proposal, principalId(auth));

        // Only touched fields are validated/resolved — an old, already-saved
        // value on a field the caller isn't changing this call is never
        // re-checked (same "only validate what changed" PATCH semantics as
        // the rest of this method).
        List<FieldErrorDetail> errors = new ArrayList<>();
        String resolvedOrganizationId = proposal.getOrganizationId();
        if (request.organizationId() != null || request.newOrganizationName() != null) {
            resolvedOrganizationId = resolveOrganizationId(request.organizationId(), request.newOrganizationName(),
                    principalId(auth), errors);
        }
        if (request.coOrganizerStudentIds() != null) {
            validateCoOrganizers(request.coOrganizerStudentIds(), proposal.getProposerStudentId(), errors);
        }
        if (request.proposedSupervisorId() != null) {
            validateSupervisor(request.proposedSupervisorId(), errors);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed("Proposal organization/people fields failed validation.", errors);
        }

        if (request.coOrganizerStudentIds() != null) proposal.setCoOrganizerStudentIds(request.coOrganizerStudentIds());
        if (request.title() != null) proposal.setTitle(request.title());
        if (request.objective() != null) proposal.setObjective(request.objective());
        if (request.description() != null) proposal.setDescription(request.description());
        if (request.expectedOutcome() != null) proposal.setExpectedOutcome(request.expectedOutcome());
        if (request.categoryId() != null) {
            requireCategory(request.categoryId());
            proposal.setCategoryId(request.categoryId());
        }
        if (request.plannedStartAt() != null) proposal.setPlannedStartAt(request.plannedStartAt());
        if (request.plannedEndAt() != null) proposal.setPlannedEndAt(request.plannedEndAt());
        if (request.location() != null) proposal.setLocation(request.location());
        if (request.format() != null) proposal.setFormat(request.format());
        if (request.targetParticipants() != null) proposal.setTargetParticipants(request.targetParticipants());
        if (request.capacity() != null) proposal.setCapacity(request.capacity());
        if (request.volunteerTasks() != null) proposal.setVolunteerTasks(request.volunteerTasks());
        if (request.proposedSupervisorId() != null) proposal.setProposedSupervisorId(request.proposedSupervisorId());
        if (request.organizationId() != null || request.newOrganizationName() != null) proposal.setOrganizationId(resolvedOrganizationId);
        if (request.externalContact() != null) proposal.setExternalContact(request.externalContact());
        if (request.safetyPlan() != null) proposal.setSafetyPlan(request.safetyPlan());
        if (request.resourceNeeds() != null) proposal.setResourceNeeds(request.resourceNeeds());
        if (request.recruitmentPlan() != null) proposal.setRecruitmentPlan(request.recruitmentPlan());
        if (request.proposedRecognizedMinutes() != null) proposal.setProposedRecognizedMinutes(request.proposedRecognizedMinutes());
        if (request.evidencePlan() != null) proposal.setEvidencePlan(request.evidencePlan());
        proposal.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "eventProposals.create", "eventProposal.update",
                "eventProposal", proposal.getId(), null, null, null));
        repository.saveAndFlush(proposal);
        return toDto(proposal, resolvedLanguage);
    }

    @Override
    @Transactional
    public void deleteDraft(String id, Authentication auth) {
        EventProposal proposal = findOwnedOrThrow(id, auth);
        if (proposal.getStatus() != ProposalStatus.DRAFT) {
            throw ApiException.stateConflict("Only a draft proposal can be deleted.");
        }
        repository.delete(proposal);
        auditService.record(new AuditEntry(principalId(auth), "eventProposals.create", "eventProposal.delete",
                "eventProposal", id, null, null, null));
    }

    @Override
    @Transactional
    // submit() itself carries the transaction; doSubmit() is a plain private
    // helper invoked in-process (same pattern as ServiceRecordServiceImpl
    // .submit — marking doSubmit() itself @Transactional and calling it via
    // `this` from a lambda would silently skip the proxy and run with no
    // transaction at all, the same self-invocation trap documented in B0/B1).
    public ProposalDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /event-proposals/" + id + "/submit",
                Map.of("id", id), ProposalDto.class, () -> doSubmit(id, auth, resolvedLanguage));
    }

    private ProposalDto doSubmit(String id, Authentication auth, String resolvedLanguage) {
        EventProposal proposal = findOwnedOrThrow(id, auth);
        if (proposal.getStatus() != ProposalStatus.DRAFT && proposal.getStatus() != ProposalStatus.CHANGES_REQUESTED) {
            throw ApiException.stateConflict("Only a draft or changes-requested proposal can be submitted.");
        }
        runSubmissionValidation(proposal);

        ProposalStateMachine.transition(proposal, ProposalStatus.SUBMITTED, ActorRole.OWNER);
        proposal.setSubmittedAt(Instant.now());
        proposal.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "eventProposals.create", "eventProposal.submit",
                "eventProposal", proposal.getId(), null, null, null));
        repository.saveAndFlush(proposal);
        return toDto(proposal, resolvedLanguage);
    }

    @Override
    @Transactional
    public ProposalDto withdraw(String id, Authentication auth, String resolvedLanguage) {
        EventProposal proposal = findOwnedOrThrow(id, auth);
        ProposalStateMachine.transition(proposal, ProposalStatus.DRAFT, ActorRole.OWNER);
        proposal.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(principalId(auth), "eventProposals.create", "eventProposal.withdraw",
                "eventProposal", proposal.getId(), null, null, null));
        repository.saveAndFlush(proposal);
        return toDto(proposal, resolvedLanguage);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionDto> revisions(String id, Authentication auth) {
        findInScopeOrThrow(id, auth);
        return revisionRepository.findByEventProposalIdOrderByRevisionNumberAsc(id).stream()
                .map(r -> new RevisionDto(r.getId(), r.getRevisionNumber(), r.getSnapshotJson(), r.getEditedBy(), r.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String id, Authentication auth) {
        EventProposal proposal = findInScopeOrThrow(id, auth);
        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.add(new TimelineEntryDto("created", "Proposal created", proposal.getProposerStudentId(), proposal.getCreatedAt()));
        if (proposal.getSubmittedAt() != null) {
            entries.add(new TimelineEntryDto("submitted", "Proposal submitted for approval", proposal.getProposerStudentId(), proposal.getSubmittedAt()));
        }
        for (EventProposalRevision rev : revisionRepository.findByEventProposalIdOrderByRevisionNumberAsc(id)) {
            entries.add(new TimelineEntryDto("revised", "Fields updated", rev.getEditedBy(), rev.getCreatedAt()));
        }
        for (var review : reviewEventRepository.findByEventProposalIdOrderByCreatedAtAsc(id)) {
            entries.add(new TimelineEntryDto("review." + review.getDecision().name().toLowerCase(),
                    review.getComment(), review.getReviewerId(), review.getCreatedAt()));
        }
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
    }

    @Override
    @Transactional
    public void assignReviewer(String id, String reviewerId, String actorId) {
        EventProposal proposal = findOrThrow(id);
        proposal.setAssignedReviewerId(reviewerId);
        proposal.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "eventProposals.assign", "eventProposal.assignReviewer",
                "eventProposal", id, null, reviewerId, null));
    }

    @Override
    public ProposalDto decide(String id, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        // Deliberately not @Transactional — see ReviewServiceImpl.decide's identical reasoning (B3.12).
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /event-proposals/" + id + "/reviews",
                request, ProposalDto.class, () -> approvalTransaction.execute(id, request, actorId, resolvedLanguage));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProposalReviewDto> decisionsForProposal(String id) {
        return reviewEventRepository.findByEventProposalIdOrderByCreatedAtAsc(id).stream()
                .map(e -> new ProposalReviewDto(e.getId(), e.getEventProposalId(), e.getReviewerId(),
                        e.getDecision().name(), e.getReasonCode(), e.getComment(), e.getProposalVersionAtDecision(), e.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProposalQueueSummaryDto queueSummary(Authentication auth) {
        Specification<EventProposal> scopeSpec = scopeSpecification(auth);
        Specification<EventProposal> pending = statusIn(ProposalStatus.SUBMITTED, ProposalStatus.UNDER_REVIEW);
        Specification<EventProposal> combined = scopeSpec == null ? pending : pending.and(scopeSpec);
        long pendingCount = repository.count(combined);

        Instant overdueThreshold = Instant.now().minus(3, java.time.temporal.ChronoUnit.DAYS);
        Specification<EventProposal> overdue = combined.and(
                (root, query, cb) -> cb.lessThan(root.get("submittedAt"), overdueThreshold));
        long overdueCount = repository.count(overdue);

        return new ProposalQueueSummaryDto(pendingCount, overdueCount);
    }

    // --- B4.9's structural submission validation (static required-field checklist, not a dynamic versioned one — see phase-doc scope note) ---

    private void runSubmissionValidation(EventProposal p) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        requireNonBlank(errors, "objective", p.getObjective());
        requireNonBlank(errors, "description", p.getDescription());
        requireNonBlank(errors, "safetyPlan", p.getSafetyPlan());
        requireNonBlank(errors, "evidencePlan", p.getEvidencePlan());
        if (!p.getPlannedEndAt().isAfter(p.getPlannedStartAt())) {
            errors.add(new FieldErrorDetail("plannedEndAt", "INVALID_RANGE", "End time must be after start time."));
        }
        if (p.getPlannedStartAt().isBefore(Instant.now())) {
            errors.add(new FieldErrorDetail("plannedStartAt", "PAST_DATE", "Planned start must be in the future."));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed("Submission failed validation.", errors);
        }
    }

    private void requireNonBlank(List<FieldErrorDetail> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldErrorDetail(field, "REQUIRED", "This field is required to submit a proposal."));
        }
    }

    private void requireCategory(String categoryId) {
        boolean exists = serviceCategoryService.list("zh-CN").stream().anyMatch(c -> c.id().equals(categoryId));
        if (!exists) {
            throw ApiException.invalidRequest("Unknown service category: " + categoryId);
        }
    }

    private void writeRevision(EventProposal p, String editedBy) {
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(Map.of("title", p.getTitle(),
                    "plannedStartAt", p.getPlannedStartAt().toString(), "capacity", p.getCapacity()));
        } catch (Exception e) {
            snapshot = "{}";
        }
        revisionRepository.save(new EventProposalRevision(OpaqueIds.generate("eprv"), p.getId(),
                p.getCurrentRevision(), snapshot, editedBy, Instant.now()));
        p.setCurrentRevision(p.getCurrentRevision() + 1);
    }

    private Specification<EventProposal> statusIn(ProposalStatus... statuses) {
        return (root, query, cb) -> root.get("status").in((Object[]) statuses);
    }

    private EventProposal findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Event proposal not found."));
    }

    private EventProposal findOwnedOrThrow(String id, Authentication auth) {
        EventProposal proposal = findOrThrow(id);
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (!proposal.getProposerStudentId().equals(principal.studentId())) {
            throw ApiException.notFound("Event proposal not found.");
        }
        return proposal;
    }

    private EventProposal findInScopeOrThrow(String id, Authentication auth) {
        Specification<EventProposal> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<EventProposal> scopeSpec = scopeSpecification(auth);
        Specification<EventProposal> combined = scopeSpec == null ? idSpec : idSpec.and(scopeSpec);
        return repository.findOne(combined).orElseThrow(() -> ApiException.notFound("Event proposal not found."));
    }

    private Specification<EventProposal> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.hasSchoolWideVisibility(); // 07-RoleFinetune.md §2.10
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }

    private ProposalDto toDto(EventProposal p, String resolvedLanguage) {
        String categoryName = serviceCategoryService.list(resolvedLanguage).stream()
                .filter(c -> c.id().equals(p.getCategoryId())).map(ServiceCategoryDto::name).findFirst().orElse("");
        String proposerName = studentService.getDetail(p.getProposerStudentId(), false, "system", resolvedLanguage).displayName();
        String supervisorName = userService.displayNamesOf(List.of(p.getProposedSupervisorId()))
                .getOrDefault(p.getProposedSupervisorId(), "");
        String organizationName = p.getOrganizationId() != null
                ? tryOrganizationName(p.getOrganizationId()) : null;
        List<String> coOrganizerNames = coOrganizerNamesOf(p.getCoOrganizerStudentIds());
        int gradeLevel = academicStructureService.listGrades(resolvedLanguage).stream()
                .filter(g -> g.id().equals(p.getGradeId())).findFirst().map(GradeDto::sequence).orElse(0);
        String className = academicStructureService.listClasses().stream()
                .filter(c -> c.id().equals(p.getClassId())).findFirst().map(ClassDto::name).orElse("");

        return new ProposalDto(p.getId(), p.getProposerStudentId(), proposerName, p.getGradeId(), gradeLevel, className,
                p.getCoOrganizerStudentIds(), coOrganizerNames,
                p.getTitle(), p.getObjective(), p.getDescription(), p.getExpectedOutcome(),
                p.getCategoryId(), categoryName, p.getPlannedStartAt(), p.getPlannedEndAt(), p.getLocation(), p.getFormat(),
                p.getTargetParticipants(), p.getCapacity(), p.getVolunteerTasks(),
                p.getProposedSupervisorId(), supervisorName, p.getOrganizationId(), organizationName, p.getExternalContact(),
                p.getSafetyPlan(), p.getResourceNeeds(), p.getRecruitmentPlan(),
                p.getProposedRecognizedMinutes(), p.getEvidencePlan(),
                p.getStatus().name(), p.getCurrentRevision(), p.getAssignedReviewerId(), p.getApprovedProjectId(),
                p.getVersion(), p.getCreatedAt(), p.getSubmittedAt(), p.getUpdatedAt());
    }

    private String tryOrganizationName(String organizationId) {
        try {
            return organizationService.get(organizationId).name();
        } catch (ApiException e) {
            return "";
        }
    }

    private List<String> coOrganizerNamesOf(List<String> coOrganizerStudentIds) {
        if (coOrganizerStudentIds == null || coOrganizerStudentIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> resolved = studentService.displayNamesOf(coOrganizerStudentIds);
        return coOrganizerStudentIds.stream().map(sid -> resolved.getOrDefault(sid, "")).toList();
    }

    /**
     * `organizationId`/`newOrganizationName` are mutually exclusive
     * (contract §3.2, `Docs/optimization/01-i3-partner-school-terminology.md`).
     * A new organization is created transactionally here — the same
     * "resolve inside the write, never require a permission the caller
     * doesn't hold" pattern already used by proposal approval generating a
     * project (§4.3): a student proposing an event never holds
     * `organizations.manage`, so the frontend must never call
     * `POST /organizations` directly for this path.
     */
    private String resolveOrganizationId(String organizationId, String newOrganizationName, String actorId,
                                          List<FieldErrorDetail> errors) {
        boolean hasExisting = organizationId != null && !organizationId.isBlank();
        boolean hasNew = newOrganizationName != null && !newOrganizationName.isBlank();
        if (hasExisting && hasNew) {
            errors.add(new FieldErrorDetail("organizationId", "AMBIGUOUS",
                    "Provide either organizationId or newOrganizationName, not both."));
            return organizationId;
        }
        if (hasExisting) {
            try {
                organizationService.get(organizationId);
            } catch (ApiException e) {
                errors.add(new FieldErrorDetail("organizationId", "NOT_FOUND", "Unknown organization."));
            }
            return organizationId;
        }
        if (hasNew) {
            OrganizationDto created = organizationService.create(
                    new CreateOrganizationRequest(newOrganizationName, "COMMUNITY", null, null, null, null, null), actorId);
            return created.id();
        }
        return null;
    }

    /** Every id must be a real, active student, and never the proposer themselves. */
    private void validateCoOrganizers(List<String> coOrganizerStudentIds, String proposerStudentId,
                                       List<FieldErrorDetail> errors) {
        if (coOrganizerStudentIds == null || coOrganizerStudentIds.isEmpty()) {
            return;
        }
        if (coOrganizerStudentIds.contains(proposerStudentId)) {
            errors.add(new FieldErrorDetail("coOrganizerStudentIds", "INVALID",
                    "Co-organizers must not include the proposer."));
        }
        Map<String, String> resolved = studentService.displayNamesOf(coOrganizerStudentIds);
        List<String> unknown = coOrganizerStudentIds.stream().filter(sid -> !resolved.containsKey(sid)).toList();
        if (!unknown.isEmpty()) {
            errors.add(new FieldErrorDetail("coOrganizerStudentIds", "NOT_FOUND",
                    "Unknown student id(s): " + String.join(", ", unknown)));
        }
    }

    /**
     * Must resolve to an active user holding {@code eventProposals.review} — contract §4.3's "designated teacher
     * as project owner." 07-RoleFinetune.md §2.2: permission-based rather than the literal {@code teacher} role
     * code, since {@code teacher} is no longer a protected role and could be renamed or deleted. Today this is
     * still exactly the {@code teacher}/{@code admin}/{@code sysadmin} population — no behavior change yet.
     */
    private void validateSupervisor(String proposedSupervisorId, List<FieldErrorDetail> errors) {
        if (proposedSupervisorId == null || proposedSupervisorId.isBlank()) {
            return;
        }
        if (!userService.hasActivePermission(proposedSupervisorId, "eventProposals.review")) {
            errors.add(new FieldErrorDetail("proposedSupervisorId", "NOT_FOUND", "Unknown or ineligible supervisor id."));
        }
    }
}
