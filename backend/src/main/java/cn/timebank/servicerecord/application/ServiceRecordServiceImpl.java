package cn.timebank.servicerecord.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.file.application.FileService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.servicerecord.application.dto.AnnualProgressSummary;
import cn.timebank.servicerecord.application.dto.RecordReviewSnapshot;
import cn.timebank.servicerecord.application.dto.RevisionDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.UpdateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.TimelineEntryDto;
import cn.timebank.servicerecord.domain.ServiceRecord;
import cn.timebank.servicerecord.domain.ServiceRecordRevision;
import cn.timebank.servicerecord.domain.ServiceRecordStateMachine;
import cn.timebank.servicerecord.domain.ServiceRecordStateMachine.ActorRole;
import cn.timebank.servicerecord.domain.ServiceRecordStatus;
import cn.timebank.servicerecord.domain.SourceType;
import cn.timebank.servicerecord.domain.SupervisorKind;
import cn.timebank.servicerecord.infrastructure.ServiceRecordRepository;
import cn.timebank.servicerecord.infrastructure.ServiceRecordRevisionRepository;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.StudentDetailDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class ServiceRecordServiceImpl implements ServiceRecordService {

    private static final String RESOURCE_TYPE = "serviceRecords";
    private static final List<ServiceRecordStatus> DUPLICATE_CHECK_STATUSES = List.of(
            ServiceRecordStatus.SUBMITTED, ServiceRecordStatus.UNDER_REVIEW, ServiceRecordStatus.APPROVED,
            ServiceRecordStatus.RETURNED, ServiceRecordStatus.CORRECTED);

    private final ServiceRecordRepository repository;
    private final ServiceRecordRevisionRepository revisionRepository;
    private final StudentService studentService;
    private final ServiceCategoryService serviceCategoryService;
    private final ProjectService projectService;
    private final EnrollmentService enrollmentService;
    private final UserService userService;
    private final AcademicStructureService academicStructureService;
    private final RecordNumberGenerator recordNumberGenerator;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final FileService fileService;
    private final ServiceRecordScopeFields scopeFields = new ServiceRecordScopeFields();

    ServiceRecordServiceImpl(ServiceRecordRepository repository, ServiceRecordRevisionRepository revisionRepository,
                              StudentService studentService, ServiceCategoryService serviceCategoryService,
                              ProjectService projectService, EnrollmentService enrollmentService, UserService userService,
                              AcademicStructureService academicStructureService, RecordNumberGenerator recordNumberGenerator,
                              AuditService auditService, DataScopeResolver dataScopeResolver,
                              IdempotencyService idempotencyService, ObjectMapper objectMapper,
                              FileService fileService) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.studentService = studentService;
        this.serviceCategoryService = serviceCategoryService;
        this.projectService = projectService;
        this.enrollmentService = enrollmentService;
        this.userService = userService;
        this.academicStructureService = academicStructureService;
        this.recordNumberGenerator = recordNumberGenerator;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.fileService = fileService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ServiceRecordDto> list(Pageable pageable, Authentication auth, String status, String sourceType,
                                        String serviceCategoryId, String search, String resolvedLanguage) {
        Specification<ServiceRecord> scopeSpec = scopeSpecification(auth);
        Specification<ServiceRecord> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", ServiceRecordStatus.class, status),
                FilterSpecifications.equalToEnum("sourceType", SourceType.class, sourceType),
                FilterSpecifications.equalTo("serviceCategoryId", serviceCategoryId),
                FilterSpecifications.searchAnyContains(search, "studentName", "organizationName"));
        Page<ServiceRecord> page = repository.findAll(FilterSpecifications.and(scopeSpec, filterSpec), pageable);
        Map<String, String> categoryNames = categoryNameMap(resolvedLanguage);
        return page.map(r -> toDto(r, categoryNames));
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceRecordDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        ServiceRecord record = findInScopeOrThrow(idEquals(id), auth);
        return toDto(record, categoryNameMap(resolvedLanguage));
    }

    @Override
    @Transactional
    public ServiceRecordDto createDraft(CreateServiceRecordRequest request, String studentId, String actorId, String resolvedLanguage) {
        SourceType sourceType = parseSourceTypeForCreate(request.sourceType());
        StudentDetailDto student = studentService.getDetail(studentId, false, actorId, resolvedLanguage);
        int durationMinutes = (int) Duration.between(request.startAt(), request.endAt()).toMinutes();

        Instant now = Instant.now();
        ServiceRecord record = new ServiceRecord(OpaqueIds.generate("rec"), studentId, student.displayName(),
                student.gradeId(), student.classId(), sourceType, request.serviceDate(), request.startAt(),
                request.endAt(), durationMinutes, request.serviceCategoryId(), now);
        record.setLocation(request.location());
        record.setDescription(request.description());
        record.setEvidenceFileIds(request.evidenceFileIds());
        record.setIncludesTravelTime(request.includesTravelTime());
        record.setTravelTimeReason(request.travelTimeReason());

        if (sourceType == SourceType.PROJECT) {
            applyProjectSnapshot(record, request.projectId(), studentId, resolvedLanguage);
        } else {
            record.setProjectId(null);
            applySelfFields(record, request);
        }
        record.setRecordNumber(generateRecordNumber(student));
        repository.save(record);

        auditService.record(new AuditEntry(actorId, "serviceRecords.create", "serviceRecord.create", "serviceRecord",
                record.getId(), null, null, null));
        return toDto(record, categoryNameMap(resolvedLanguage));
    }

    @Override
    @Transactional
    public ServiceRecordDto updateDraft(String id, UpdateServiceRecordRequest request, Authentication auth, String resolvedLanguage) {
        ServiceRecord record = findOwnedOrThrow(id, auth);
        if (record.getStatus() != ServiceRecordStatus.DRAFT && record.getStatus() != ServiceRecordStatus.RETURNED) {
            throw ApiException.stateConflict("Only a draft or returned record can be edited.");
        }
        ConcurrencyControl.checkVersion(record.getVersion(), request.version());
        writeRevision(record, principalId(auth));

        // Source is locked (never changes on update — issue 03 §3). PROJECT's
        // organization/category/location/supervisor snapshot is likewise locked:
        // it was fixed at creation time and is never re-derived from the project's
        // *current* state, so a later edit to the project can't retroactively alter
        // an already-drafted record's semantics (§4.3). Only date/time/description/
        // evidence/travel-time are ever editable for a PROJECT-sourced record.
        if (record.getSourceType() == SourceType.SELF) {
            applySelfFieldsForUpdate(record, request);
        } else if (record.getSourceType() == SourceType.EXTERNAL) {
            // Historical data only (§7.3) — the pre-issue-03 free-text behavior,
            // unchanged, so an old returned EXTERNAL record can still be edited and
            // resubmitted without being silently rewritten into a new source.
            if (request.organizationId() != null) record.setOrganizationId(request.organizationId());
            if (request.organizationName() != null) record.setOrganizationName(request.organizationName());
            if (request.supervisorName() != null) record.setSupervisorName(request.supervisorName());
            if (request.supervisorContact() != null) record.setSupervisorContact(request.supervisorContact());
            if (request.serviceCategoryId() != null) record.setServiceCategoryId(request.serviceCategoryId());
            if (request.location() != null) record.setLocation(request.location());
        }
        // PROJECT: none of the above — request fields for these are silently ignored.

        if (request.serviceDate() != null) record.setServiceDate(request.serviceDate());
        if (request.startAt() != null) record.setStartAt(request.startAt());
        if (request.endAt() != null) record.setEndAt(request.endAt());
        if (request.startAt() != null || request.endAt() != null) {
            record.setDurationMinutes((int) Duration.between(record.getStartAt(), record.getEndAt()).toMinutes());
        }
        if (request.description() != null) record.setDescription(request.description());
        if (request.evidenceFileIds() != null) record.setEvidenceFileIds(request.evidenceFileIds());
        if (request.includesTravelTime() != null) record.setIncludesTravelTime(request.includesTravelTime());
        if (request.travelTimeReason() != null) record.setTravelTimeReason(request.travelTimeReason());
        record.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "serviceRecords.create", "serviceRecord.update",
                "serviceRecord", record.getId(), null, null, null));
        repository.saveAndFlush(record); // see submit()'s note on why this matters for a chained version check
        return toDto(record, categoryNameMap(resolvedLanguage));
    }

    @Override
    @Transactional
    public void deleteDraft(String id, Authentication auth) {
        ServiceRecord record = findOwnedOrThrow(id, auth);
        if (record.getStatus() != ServiceRecordStatus.DRAFT) {
            // Invariant 4: only a draft may ever be physically deleted.
            throw ApiException.stateConflict("Only a draft record can be deleted.");
        }
        repository.delete(record);
        auditService.record(new AuditEntry(principalId(auth), "serviceRecords.create", "serviceRecord.delete",
                "serviceRecord", id, null, null, null));
    }

    @Override
    @Transactional
    public ServiceRecordDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /service-records/" + id + "/submit",
                Map.of("id", id), ServiceRecordDto.class, () -> doSubmit(id, auth, resolvedLanguage));
    }

    private ServiceRecordDto doSubmit(String id, Authentication auth, String resolvedLanguage) {
        ServiceRecord record = findOwnedOrThrow(id, auth);
        if (record.getStatus() != ServiceRecordStatus.DRAFT && record.getStatus() != ServiceRecordStatus.RETURNED) {
            throw ApiException.stateConflict("Only a draft or returned record can be submitted.");
        }
        runSubmissionValidation(record);

        ServiceRecordStatus from = record.getStatus();
        ServiceRecordStateMachine.transition(record, ServiceRecordStatus.SUBMITTED, ActorRole.OWNER);
        record.setSubmittedAt(Instant.now());
        record.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "serviceRecords.create", "serviceRecord.submit",
                "serviceRecord", record.getId(), from.name(), record.getStatus().name(), null));
        // Explicit flush: without it, `@Version` is only bumped by Hibernate at
        // the *next* flush (normally deferred to transaction commit), so the
        // DTO built here would carry the pre-increment version — fine for
        // display, but wrong for a caller (review's approval transaction) that
        // chains this returned version straight into ConcurrencyControl's next
        // check. Caught live: chaining submit()'s returned version into
        // decide() failed with a spurious "changed by someone else" 409.
        repository.saveAndFlush(record);
        return toDto(record, categoryNameMap(resolvedLanguage));
    }

    @Override
    @Transactional
    public ServiceRecordDto withdraw(String id, Authentication auth, String resolvedLanguage) {
        ServiceRecord record = findOwnedOrThrow(id, auth);
        // The state machine only defines SUBMITTED -> DRAFT for OWNER — a record
        // already UNDER_REVIEW or beyond has no such edge, which is exactly
        // contract §4.5's "review not started" precondition, enforced for free.
        ServiceRecordStateMachine.transition(record, ServiceRecordStatus.DRAFT, ActorRole.OWNER);
        record.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(principalId(auth), "serviceRecords.create", "serviceRecord.withdraw",
                "serviceRecord", record.getId(), null, null, null));
        repository.saveAndFlush(record); // see submit()'s note on why this matters for a chained version check
        return toDto(record, categoryNameMap(resolvedLanguage));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionDto> revisions(String id, Authentication auth) {
        findInScopeOrThrow(idEquals(id), auth);
        return revisionRepository.findByServiceRecordIdOrderByRevisionNumberAsc(id).stream()
                .map(r -> new RevisionDto(r.getId(), r.getRevisionNumber(), r.getSnapshotJson(), r.getEditedBy(), r.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String id, Authentication auth) {
        ServiceRecord record = findInScopeOrThrow(idEquals(id), auth);
        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.add(new TimelineEntryDto("created", "Record created", record.getStudentId(), record.getCreatedAt()));
        if (record.getSubmittedAt() != null) {
            entries.add(new TimelineEntryDto("submitted", "Record submitted for review", record.getStudentId(), record.getSubmittedAt()));
        }
        for (ServiceRecordRevision rev : revisionRepository.findByServiceRecordIdOrderByRevisionNumberAsc(id)) {
            entries.add(new TimelineEntryDto("revised", "Fields updated", rev.getEditedBy(), rev.getCreatedAt()));
        }
        // Review-decision entries are merged in by the controller (ReviewService
        // is a separate module's application interface — a web-layer composition,
        // same reasoning as B1's iam/security split).
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
    }

    @Override
    @Transactional
    public void assignReviewer(String id, String reviewerId, String actorId) {
        ServiceRecord record = findOrThrow(id);
        record.setAssignedReviewerId(reviewerId);
        record.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "reviews.assign", "serviceRecord.assignReviewer",
                "serviceRecord", id, null, reviewerId, null));
    }

    @Override
    @Transactional
    public RecordReviewSnapshot beginReviewTransition(String recordId, Long expectedVersion, String targetStatus, String actorId, String comment) {
        ServiceRecord record = findOrThrow(recordId);
        ConcurrencyControl.checkVersion(record.getVersion(), expectedVersion);
        ServiceRecordStatus target = ServiceRecordStatus.valueOf(targetStatus);

        ServiceRecordStatus before = record.getStatus();
        if (record.getStatus() == ServiceRecordStatus.SUBMITTED) {
            ServiceRecordStateMachine.transition(record, ServiceRecordStatus.UNDER_REVIEW, ActorRole.REVIEWER);
        }
        if (record.getStatus() != target) {
            ServiceRecordStateMachine.transition(record, target, ActorRole.REVIEWER);
        }
        if (comment != null && !comment.isBlank()) record.setReviewSummary(comment);
        record.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "reviews.decide", "serviceRecord.reviewTransition",
                "serviceRecord", record.getId(), before.name(), record.getStatus().name(), null));

        repository.saveAndFlush(record); // see submit()'s note on why this matters for a chained version check
        return new RecordReviewSnapshot(record.getId(), record.getStudentId(), record.getGradeId(), record.getClassId(),
                record.getServiceDate(), record.getDurationMinutes(), record.getVersion(), record.getStatus().name());
    }

    @Override
    @Transactional
    public void applyCredit(String recordId, int creditedUnits, String accountTransactionId) {
        ServiceRecord record = findOrThrow(recordId);
        record.setCreditedUnits(creditedUnits);
        record.setAccountTransactionId(accountTransactionId);
        record.setUpdatedAt(Instant.now());
        repository.saveAndFlush(record);
    }

    @Override
    @Transactional(readOnly = true)
    public AnnualProgressSummary summarizeForProgress(String studentId, LocalDate yearStart, LocalDate yearEnd) {
        int approved = repository.sumDurationMinutes(studentId, yearStart, yearEnd, ServiceRecordStatus.APPROVED);
        int submitted = repository.sumDurationMinutes(studentId, yearStart, yearEnd, ServiceRecordStatus.SUBMITTED);
        int underReview = repository.sumDurationMinutes(studentId, yearStart, yearEnd, ServiceRecordStatus.UNDER_REVIEW);
        return new AnnualProgressSummary(approved, submitted + underReview);
    }

    // --- issue 03: source-aware field resolution ---

    private record OrgSnapshotEntry(String organizationId, String organizationName, String role) {
    }

    /**
     * PROJECT source: every organization/category/location/supervisor field comes
     * from the project, never the client (§3.1/§5.2) — re-validated here (not just
     * trusted from a picker) since a tampered projectId must be rejected the same
     * way at both draft-creation and submission time (§10 D-notes).
     */
    private void applyProjectSnapshot(ServiceRecord record, String projectId, String studentId, String resolvedLanguage) {
        if (projectId == null || projectId.isBlank()) {
            throw ApiException.invalidRequest("A project-sourced record needs a projectId.");
        }
        if (!enrollmentService.isConfirmed(projectId, studentId)) {
            throw ApiException.invalidRequest("You do not have a confirmed registration for this project.");
        }
        ProjectDto project = projectService.getDetail(projectId, resolvedLanguage);

        record.setProjectId(projectId);
        record.setOrganizationId(project.organizationId());
        record.setOrganizationName(project.organizationName());
        record.setLocation(project.location());
        record.setServiceCategoryId(project.categoryId());

        UserDto owner = userService.getById(project.ownerId());
        if (owner.email() == null || owner.email().isBlank()) {
            throw ApiException.validationFailed("Submission failed validation.", List.of(new FieldErrorDetail(
                    "supervisorContact", "CONTACT_MISSING", "The project owner has no contact information on file.")));
        }
        record.setSupervisorKind(SupervisorKind.PROJECT_OWNER);
        record.setSupervisorSubjectId(project.ownerId());
        record.setSupervisorName(project.ownerName());
        record.setSupervisorContact(owner.email());

        List<OrgSnapshotEntry> snapshot = new ArrayList<>();
        snapshot.add(new OrgSnapshotEntry(project.hostOrganizationId(), project.hostOrganizationName(), "HOST_SCHOOL"));
        if (project.organizationId() != null) {
            snapshot.add(new OrgSnapshotEntry(project.organizationId(), project.organizationName(), "PARTNER"));
        }
        record.setProjectOrganizationsSnapshot(writeSnapshot(snapshot));
    }

    /** SELF source (create): free organization text, client-chosen supervisor kind, all server-resolved into a snapshot (§3.2/§6). */
    private void applySelfFields(ServiceRecord record, CreateServiceRecordRequest request) {
        record.setOrganizationId(request.organizationId());
        record.setOrganizationName(request.organizationName());
        resolveSupervisor(record, request.supervisorKind(), request.supervisorSubjectId(),
                request.supervisorName(), request.supervisorContact());
    }

    /** Same as {@link #applySelfFields} but only touches fields the client actually sent (PATCH semantics). */
    private void applySelfFieldsForUpdate(ServiceRecord record, UpdateServiceRecordRequest request) {
        if (request.organizationId() != null) record.setOrganizationId(request.organizationId());
        if (request.organizationName() != null) record.setOrganizationName(request.organizationName());
        if (request.serviceCategoryId() != null) record.setServiceCategoryId(request.serviceCategoryId());
        if (request.location() != null) record.setLocation(request.location());
        if (request.supervisorKind() != null) {
            resolveSupervisor(record, request.supervisorKind(), request.supervisorSubjectId(),
                    request.supervisorName(), request.supervisorContact());
        }
    }

    /** §6: "select an identity, the server resolves it" — never trust a client-submitted name/contact for anything but EXTERNAL_MANUAL. */
    private void resolveSupervisor(ServiceRecord record, String kindRaw, String subjectId, String manualName, String manualContact) {
        if (kindRaw == null) {
            return; // not provided on this call — nothing to resolve (e.g. a PATCH touching unrelated fields)
        }
        SupervisorKind kind;
        try {
            kind = SupervisorKind.valueOf(kindRaw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown supervisorKind: " + kindRaw);
        }
        switch (kind) {
            case INTERNAL_USER -> {
                UserDto user = userService.getById(requireSubjectId(subjectId));
                requireContact(user.email(), "supervisorSubjectId");
                record.setSupervisorKind(SupervisorKind.INTERNAL_USER);
                record.setSupervisorSubjectId(user.id());
                record.setSupervisorName(user.displayName());
                record.setSupervisorContact(user.email());
            }
            case STUDENT -> {
                // includeSensitiveFields=true: a legitimate contact-resolution read,
                // audited the same as any other sensitive-field access (§6 rule 2).
                StudentDetailDto peer = studentService.getDetail(requireSubjectId(subjectId), true, "system", "zh-CN");
                requireContact(peer.email(), "supervisorSubjectId");
                record.setSupervisorKind(SupervisorKind.STUDENT);
                record.setSupervisorSubjectId(peer.id());
                record.setSupervisorName(peer.displayName());
                record.setSupervisorContact(peer.email());
            }
            case EXTERNAL_MANUAL -> {
                if (manualName == null || manualName.isBlank()) {
                    throw ApiException.invalidRequest("supervisorName is required for an external manually-entered supervisor.");
                }
                if (manualContact == null || manualContact.isBlank()) {
                    // §3.2: "允许手工填写外部主管，但其联系方式必填" — unlike INTERNAL_USER/STUDENT
                    // (whose contact is resolved server-side and only fails if the account
                    // genuinely has none on file), a manual entry has no account to fall back
                    // on at all, so this is required up front, not just at submission time.
                    throw ApiException.invalidRequest("supervisorContact is required for an external manually-entered supervisor.");
                }
                record.setSupervisorKind(SupervisorKind.EXTERNAL_MANUAL);
                record.setSupervisorSubjectId(null);
                record.setSupervisorName(manualName);
                record.setSupervisorContact(manualContact);
            }
            case PROJECT_OWNER -> throw ApiException.invalidRequest(
                    "supervisorKind PROJECT_OWNER is derived automatically for PROJECT-sourced records, not client-settable.");
        }
    }

    private String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw ApiException.invalidRequest("supervisorSubjectId is required for this supervisorKind.");
        }
        return subjectId;
    }

    private void requireContact(String contact, String field) {
        if (contact == null || contact.isBlank()) {
            throw ApiException.validationFailed("Submission failed validation.",
                    List.of(new FieldErrorDetail(field, "CONTACT_MISSING", "The selected person has no contact information on file.")));
        }
    }

    /** §5.3/§10 D3 — `TB-{academicYearCode}-{gradeLevel}-{studentNumber}-{seq}`, all fields read from the student's own record, never client input. */
    private String generateRecordNumber(StudentDetailDto student) {
        AcademicYearDto currentYear = academicStructureService.listAcademicYears().stream()
                .filter(AcademicYearDto::current).findFirst()
                .orElseThrow(() -> new IllegalStateException("No current academic year is configured."));
        int seq = recordNumberGenerator.nextSequence(currentYear.id(), student.id());
        return "TB-%s-%d-%s-%03d".formatted(currentYear.code(), student.gradeLevel(), student.studentNumber(), seq);
    }

    private String writeSnapshot(List<OrgSnapshotEntry> entries) {
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<OrgSnapshotEntry> readSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<OrgSnapshotEntry>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- B3.3 submission validation (extended per issue 03 §3/§10) ---

    private void runSubmissionValidation(ServiceRecord record) {
        List<FieldErrorDetail> errors = new ArrayList<>();

        switch (record.getSourceType()) {
            case PROJECT -> {
                if (isBlank(record.getProjectId())) {
                    errors.add(new FieldErrorDetail("projectId", "REQUIRED", "A project-sourced record needs a projectId."));
                } else if (!enrollmentService.isConfirmed(record.getProjectId(), record.getStudentId())) {
                    // Re-checked at submission (§3.1) — a confirmed registration cancelled
                    // between draft-save and submit must block submission too, not just creation.
                    errors.add(new FieldErrorDetail("projectId", "NOT_CONFIRMED",
                            "You no longer have a confirmed registration for this project."));
                }
            }
            case EXTERNAL -> {
                // Historical source only (§7.3) — unchanged original validation.
                if (isBlank(record.getOrganizationName())) {
                    errors.add(new FieldErrorDetail("organizationName", "REQUIRED", "An external record needs an organization name."));
                }
                if (isBlank(record.getSupervisorName())) {
                    errors.add(new FieldErrorDetail("supervisorName", "REQUIRED", "An external record needs a supervisor contact."));
                }
            }
            case SELF -> {
                if (record.getSupervisorKind() == null) {
                    errors.add(new FieldErrorDetail("supervisorKind", "REQUIRED", "A self-initiated record needs a supervisor."));
                }
            }
        }

        if (record.isIncludesTravelTime() && isBlank(record.getTravelTimeReason())) {
            errors.add(new FieldErrorDetail("travelTimeReason", "REQUIRED", "A reason is required when travel time is included."));
        }
        if (!record.getEndAt().isAfter(record.getStartAt())) {
            errors.add(new FieldErrorDetail("endAt", "INVALID_RANGE", "End time must be after start time."));
        }
        if (record.getDurationMinutes() <= 0) {
            errors.add(new FieldErrorDetail("durationMinutes", "INVALID_DURATION", "Duration must be positive."));
        }
        if (record.getServiceDate().isAfter(LocalDate.now())) {
            errors.add(new FieldErrorDetail("serviceDate", "FUTURE_DATE", "Service date cannot be in the future."));
        }
        if (record.getEvidenceFileIds() == null || record.getEvidenceFileIds().isEmpty()) {
            errors.add(new FieldErrorDetail("evidenceFileIds", "EVIDENCE_REQUIRED", "At least one evidence file is required."));
        } else {
            // B3.5: a PENDING/PROCESSING/REJECTED file cannot be attached — enforced
            // here, not merely in the UI. A fileId absent from the map (deleted, or
            // never existed) is treated the same as not-ready.
            Map<String, String> statuses = fileService.statusesOf(record.getEvidenceFileIds());
            for (String fileId : record.getEvidenceFileIds()) {
                if (!"READY".equals(statuses.get(fileId))) {
                    errors.add(new FieldErrorDetail("evidenceFileIds", "EVIDENCE_NOT_READY",
                            "Evidence file " + fileId + " is not ready (status: " + statuses.getOrDefault(fileId, "MISSING") + ")."));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw ApiException.validationFailed("Submission failed validation.", errors);
        }

        // Duplicate detection — a business rule, not a hard block (warning + review routing per the phase doc).
        boolean overlaps = !repository.findOverlapping(record.getStudentId(), record.getId(), record.getStartAt(),
                record.getEndAt(), DUPLICATE_CHECK_STATUSES).isEmpty();
        if (overlaps) {
            record.setHasAnomaly(true);
            record.setAnomalyNote(appendNote(record.getAnomalyNote(),
                    "Overlaps another service record's time window for this student."));
        }

        // Travel-time disclosure (Q5): no automated approval path exists anywhere in this
        // system (every approval is a human reviews.decide action), so "blocking crediting"
        // is satisfied by guaranteeing reviewer visibility, not an auto-adjustment mechanism.
        if (record.isIncludesTravelTime()) {
            record.setHasAnomaly(true);
            record.setAnomalyNote(appendNote(record.getAnomalyNote(), "Travel time disclosed — requires manual review."));
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String appendNote(String existing, String addition) {
        return (existing == null || existing.isBlank()) ? addition : existing + " " + addition;
    }

    /** New creates only accept PROJECT/SELF (§7.3) — EXTERNAL is historical data, reachable only via an existing record's PATCH/submit path. */
    private SourceType parseSourceTypeForCreate(String raw) {
        SourceType type;
        try {
            type = SourceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown sourceType: " + raw);
        }
        if (type == SourceType.EXTERNAL) {
            throw ApiException.invalidRequest("sourceType EXTERNAL is historical data only — new records must be PROJECT or SELF.");
        }
        return type;
    }

    private void writeRevision(ServiceRecord record, String editedBy) {
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(new RecordSnapshotForRevision(record));
        } catch (Exception e) {
            snapshot = "{}";
        }
        ServiceRecordRevision revision = new ServiceRecordRevision(OpaqueIds.generate("rev"), record.getId(),
                record.getCurrentRevision(), snapshot, editedBy, Instant.now());
        revisionRepository.save(revision);
        record.setCurrentRevision(record.getCurrentRevision() + 1);
    }

    private record RecordSnapshotForRevision(String serviceDate, Instant startAt, Instant endAt, int durationMinutes,
                                              String description, String location) {
        RecordSnapshotForRevision(ServiceRecord r) {
            this(r.getServiceDate().toString(), r.getStartAt(), r.getEndAt(), r.getDurationMinutes(),
                    r.getDescription(), r.getLocation());
        }
    }

    private ServiceRecord findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Service record not found."));
    }

    private ServiceRecord findOwnedOrThrow(String id, Authentication auth) {
        ServiceRecord record = findOrThrow(id);
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (!record.getStudentId().equals(principal.studentId())) {
            // 404, not 403 — existence hiding (B1.8).
            throw ApiException.notFound("Service record not found.");
        }
        return record;
    }

    private ServiceRecord findInScopeOrThrow(Specification<ServiceRecord> idSpec, Authentication auth) {
        Specification<ServiceRecord> scopeSpec = scopeSpecification(auth);
        Specification<ServiceRecord> combined = scopeSpec == null ? idSpec : idSpec.and(scopeSpec);
        return repository.findOne(combined).orElseThrow(() -> ApiException.notFound("Service record not found."));
    }

    private Specification<ServiceRecord> idEquals(String id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    private Specification<ServiceRecord> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    private Map<String, String> categoryNameMap(String resolvedLanguage) {
        return serviceCategoryService.list(resolvedLanguage).stream()
                .collect(Collectors.toMap(ServiceCategoryDto::id, ServiceCategoryDto::name));
    }

    private ServiceRecordDto toDto(ServiceRecord r, Map<String, String> categoryNames) {
        List<OrgSnapshotEntry> snapshot = readSnapshot(r.getProjectOrganizationsSnapshot());
        String hostOrganizationName = snapshot.stream().filter(e -> "HOST_SCHOOL".equals(e.role()))
                .map(OrgSnapshotEntry::organizationName).findFirst().orElse(null);
        List<String> partnerOrganizationNames = snapshot.stream().filter(e -> "PARTNER".equals(e.role()))
                .map(OrgSnapshotEntry::organizationName).toList();
        return new ServiceRecordDto(r.getId(), r.getStudentId(), r.getStudentName(), r.getProjectId(),
                r.getSourceType().name(), r.getServiceDate(), r.getStartAt(), r.getEndAt(), r.getDurationMinutes(),
                r.getLocation(), r.getOrganizationId(), r.getOrganizationName(), r.getServiceCategoryId(),
                categoryNames.getOrDefault(r.getServiceCategoryId(), ""), r.getDescription(),
                r.getSupervisorKind() != null ? r.getSupervisorKind().name() : null,
                r.getSupervisorName(), r.getSupervisorContact(), r.getEvidenceFileIds(), r.isIncludesTravelTime(),
                r.getTravelTimeReason(), r.getRecordNumber(), hostOrganizationName, partnerOrganizationNames,
                r.getStatus().name(), r.getCurrentRevision(), r.getAssignedReviewerId(), r.isHasAnomaly(), r.getAnomalyNote(),
                r.getReviewSummary(), r.getCreditedUnits(), r.getAccountTransactionId(), r.getVersion(),
                r.getCreatedAt(), r.getSubmittedAt(), r.getUpdatedAt());
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
