package cn.timebank.caseflow.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.caseflow.application.dto.CaseDecisionDto;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.CreateCaseRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.UpdateCaseRequest;
import cn.timebank.caseflow.application.dto.RevisionDto;
import cn.timebank.caseflow.application.dto.TimelineEntryDto;
import cn.timebank.caseflow.domain.Case;
import cn.timebank.caseflow.domain.CaseRevision;
import cn.timebank.caseflow.domain.CaseStateMachine;
import cn.timebank.caseflow.domain.CaseStateMachine.ActorRole;
import cn.timebank.caseflow.domain.CaseStatus;
import cn.timebank.caseflow.domain.CaseType;
import cn.timebank.caseflow.infrastructure.CaseDecisionEventRepository;
import cn.timebank.caseflow.infrastructure.CaseRepository;
import cn.timebank.caseflow.infrastructure.CaseRevisionRepository;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.student.application.AcademicYearWindowResolver;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.servicerecord.application.ServiceRecordService;
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
class CaseServiceImpl implements CaseService {

    private static final String RESOURCE_TYPE = "cases";

    private final CaseRepository repository;
    private final CaseRevisionRepository revisionRepository;
    private final CaseDecisionEventRepository decisionEventRepository;
    private final StudentService studentService;
    private final ServiceRecordService serviceRecordService;
    private final AcademicStructureService academicStructureService;
    private final AcademicYearWindowResolver academicYearWindows;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final CaseDecisionTransaction decisionTransaction;
    private final CaseScopeFields scopeFields = new CaseScopeFields();

    CaseServiceImpl(CaseRepository repository, CaseRevisionRepository revisionRepository,
                    CaseDecisionEventRepository decisionEventRepository, StudentService studentService,
                    ServiceRecordService serviceRecordService, AcademicStructureService academicStructureService,
                    AcademicYearWindowResolver academicYearWindows,
                    AuditService auditService, DataScopeResolver dataScopeResolver,
                    IdempotencyService idempotencyService, ObjectMapper objectMapper,
                    CaseDecisionTransaction decisionTransaction) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.decisionEventRepository = decisionEventRepository;
        this.studentService = studentService;
        this.serviceRecordService = serviceRecordService;
        this.academicStructureService = academicStructureService;
        this.academicYearWindows = academicYearWindows;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.decisionTransaction = decisionTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CaseDto> list(Pageable pageable, Authentication auth, String status, String type, String gradeId,
                               String academicYearId, String search, String resolvedLanguage) {
        Specification<Case> filterSpec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("status", CaseStatus.class, status),
                FilterSpecifications.equalToEnum("type", CaseType.class, type),
                FilterSpecifications.equalTo("gradeId", gradeId),
                academicYearSpecification(academicYearId),
                FilterSpecifications.searchAnyContains(search, "title"));
        Specification<Case> spec = FilterSpecifications.and(
                scopeSpecification(auth), draftsVisibleOnlyToTheirOwner(auth), filterSpec);
        Page<Case> page = repository.findAll(spec, pageable);
        Map<String, Integer> gradeSequenceById = gradeSequenceById(resolvedLanguage);
        Map<String, String> classNameById = classNameById();
        return page.map(c -> toDto(c, resolvedLanguage, gradeSequenceById, classNameById));
    }


    /**
     * {@code filter[academicYearId]} — a case belongs to the year it was
     * <b>filed</b> (issue 115).
     *
     * <p>A case has no event of its own to date it by: unlike a project or a
     * proposal it is an action taken about something else, and its only dates are
     * {@code createdAt}, {@code submittedAt} and {@code updatedAt}. Filing is the
     * one of those that cannot change afterwards, and it is the question Case
     * Review actually asks — "what came in this year".</p>
     *
     * <p>{@code createdAt} rather than {@code submittedAt} because a draft has no
     * {@code submittedAt}, and a student's own case list shows drafts. Keying on a
     * nullable column would drop every draft out of every year. The two differ only
     * for a case drafted either side of the turnover date, which is a rarity worth
     * the drafts staying visible.</p>
     *
     * <p>Absent means every year, and an unknown id is a 404 — both in
     * {@link AcademicYearWindowResolver}, shared with projects and proposals so the
     * three cannot drift. No student pin here, unlike the Project Center: a student
     * must be able to read their own past appeals.</p>
     */
    private Specification<Case> academicYearSpecification(String academicYearId) {
        AcademicYearWindowResolver.Window window = academicYearWindows.resolveOrNull(academicYearId);
        if (window == null) {
            return null;
        }
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("createdAt"), window.fromInclusive()),
                cb.lessThan(root.get("createdAt"), window.untilExclusive()));
    }

    @Override
    @Transactional(readOnly = true)
    public CaseDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        return toDto(findInScopeOrThrow(id, auth), resolvedLanguage);
    }

    @Override
    @Transactional
    public CaseDto createDraft(CreateCaseRequest request, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        String studentId = request.studentId() != null ? request.studentId() : principal.studentId();
        if (studentId == null) {
            throw ApiException.invalidRequest("studentId is required when the caller is not filing for themselves.");
        }
        CaseType type = parseType(request.type());
        StudentDetailDto student = studentService.getDetail(studentId, resolvedLanguage);

        Instant now = Instant.now();
        Case c = new Case(OpaqueIds.generate("case"), type, studentId, student.gradeId(), student.classId(),
                request.title(), request.reasonCode(), now);
        c.setSubjectType(request.subjectType());
        c.setSubjectId(request.subjectId());
        c.setDescription(request.description());
        c.setRequestedAmountCentiCoins(request.requestedAmountCentiCoins());
        repository.save(c);

        auditService.record(new AuditEntry(principal.id(), "cases.create", "case.create",
                "case", c.getId(), null, null, null));
        return toDto(c, resolvedLanguage);
    }

    @Override
    @Transactional
    public CaseDto updateDraft(String id, UpdateCaseRequest request, Authentication auth, String resolvedLanguage) {
        Case c = findOwnedOrThrow(id, auth);
        if (c.getStatus() != CaseStatus.DRAFT && c.getStatus() != CaseStatus.NEEDS_INFORMATION) {
            throw ApiException.stateConflict("Only a draft or needs-information case can be edited.");
        }
        ConcurrencyControl.checkVersion(c.getVersion(), request.version());
        writeRevision(c, principalId(auth));

        if (request.title() != null) c.setTitle(request.title());
        if (request.description() != null) c.setDescription(request.description());
        if (request.requestedAmountCentiCoins() != null) c.setRequestedAmountCentiCoins(request.requestedAmountCentiCoins());
        if (request.reasonCode() != null) c.setReasonCode(request.reasonCode());
        c.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "cases.create", "case.update",
                "case", c.getId(), null, null, null));
        repository.saveAndFlush(c);
        return toDto(c, resolvedLanguage);
    }

    @Override
    @Transactional
    // submit() itself carries the transaction; doSubmit() is a plain private
    // helper invoked in-process (same self-invocation precedent B0/B1/B4
    // documented — marking doSubmit() itself @Transactional and calling it
    // via `this` from a lambda would silently skip the proxy).
    public CaseDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /cases/" + id + "/submit",
                Map.of("id", id), CaseDto.class, () -> doSubmit(id, auth, resolvedLanguage));
    }

    private CaseDto doSubmit(String id, Authentication auth, String resolvedLanguage) {
        Case c = findOwnedOrThrow(id, auth);
        if (c.getStatus() != CaseStatus.DRAFT && c.getStatus() != CaseStatus.NEEDS_INFORMATION) {
            throw ApiException.stateConflict("Only a draft or needs-information case can be submitted.");
        }
        if (c.getTitle() == null || c.getTitle().isBlank()) {
            throw ApiException.invalidRequest("A title is required to submit a case.");
        }

        CaseStateMachine.transition(c, CaseStatus.SUBMITTED, ActorRole.OWNER);
        c.setSubmittedAt(Instant.now());
        c.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "cases.create", "case.submit",
                "case", c.getId(), null, null, null));
        repository.saveAndFlush(c);
        return toDto(c, resolvedLanguage);
    }

    @Override
    @Transactional
    public CaseDto withdraw(String id, Authentication auth, String resolvedLanguage) {
        Case c = findOwnedOrThrow(id, auth);
        // The state machine only defines SUBMITTED -> DRAFT for OWNER, so a case a
        // reviewer has already decided on is refused here for free.
        CaseStateMachine.transition(c, CaseStatus.DRAFT, ActorRole.OWNER);
        c.setSubmittedAt(null);
        c.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(principalId(auth), "cases.create", "case.withdraw",
                "case", c.getId(), null, null, null));
        repository.saveAndFlush(c);
        return toDto(c, resolvedLanguage);
    }

    @Override
    @Transactional
    public void deleteDraft(String id, Authentication auth) {
        Case c = findOwnedOrThrow(id, auth);
        if (c.getStatus() != CaseStatus.DRAFT) {
            throw ApiException.stateConflict("Only a draft case can be deleted.");
        }
        // A draft edited at least once has case_revision rows FK'd to it, so they
        // go first or the delete below fails on the constraint (issue 059).
        revisionRepository.deleteByCaseId(id);
        repository.delete(c);
        auditService.record(new AuditEntry(principalId(auth), "cases.create", "case.delete",
                "case", id, null, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RevisionDto> revisions(String id, Authentication auth) {
        findInScopeOrThrow(id, auth);
        return revisionRepository.findByCaseIdOrderByRevisionNumberAsc(id).stream()
                .map(r -> new RevisionDto(r.getId(), r.getRevisionNumber(), r.getSnapshotJson(), r.getEditedBy(), r.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String id, Authentication auth) {
        Case c = findInScopeOrThrow(id, auth);
        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.add(new TimelineEntryDto("created", "Case created", c.getStudentId(), c.getCreatedAt()));
        if (c.getSubmittedAt() != null) {
            entries.add(new TimelineEntryDto("submitted", "Case submitted", c.getStudentId(), c.getSubmittedAt()));
        }
        for (CaseRevision rev : revisionRepository.findByCaseIdOrderByRevisionNumberAsc(id)) {
            entries.add(new TimelineEntryDto("revised", "Fields updated", rev.getEditedBy(), rev.getCreatedAt()));
        }
        for (var decision : decisionEventRepository.findByCaseIdOrderByCreatedAtAsc(id)) {
            entries.add(new TimelineEntryDto("decision." + decision.getDecision().name().toLowerCase(),
                    decision.getComment(), decision.getReviewerId(), decision.getCreatedAt()));
        }
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
    }

    @Override
    public CaseDto decide(String id, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        // Deliberately not @Transactional — see ReviewServiceImpl.decide's identical reasoning (B3.12/B4.11).
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /cases/" + id + "/decisions",
                request, CaseDto.class, () -> decisionTransaction.execute(id, request, actorId, resolvedLanguage));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseDecisionDto> decisionsForCase(String id) {
        return decisionEventRepository.findByCaseIdOrderByCreatedAtAsc(id).stream()
                .map(e -> new CaseDecisionDto(e.getId(), e.getCaseId(), e.getReviewerId(),
                        e.getDecision().name(), e.getReasonCode(), e.getComment(), e.getCaseVersionAtDecision(), e.getCreatedAt()))
                .toList();
    }

    private void writeRevision(Case c, String editedBy) {
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(Map.of("title", c.getTitle(), "reasonCode", c.getReasonCode()));
        } catch (Exception e) {
            snapshot = "{}";
        }
        int nextRevisionNumber = revisionRepository.findByCaseIdOrderByRevisionNumberAsc(c.getId()).size() + 1;
        revisionRepository.save(new CaseRevision(OpaqueIds.generate("csrv"), c.getId(), nextRevisionNumber, snapshot, editedBy, Instant.now()));
    }

    private CaseType parseType(String raw) {
        try {
            return CaseType.valueOf(raw.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown case type: " + raw);
        }
    }

    private Case findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Case not found."));
    }

    /** Staff (no studentId) may manage any case; a student-principal must own it. */
    private Case findOwnedOrThrow(String id, Authentication auth) {
        Case c = findOrThrow(id);
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (principal.studentId() != null && !c.getStudentId().equals(principal.studentId())) {
            throw ApiException.notFound("Case not found.");
        }
        return c;
    }

    private Case findInScopeOrThrow(String id, Authentication auth) {
        Specification<Case> idSpec = (root, query, cb) -> cb.equal(root.get("id"), id);
        Specification<Case> scopeSpec = scopeSpecification(auth);
        // Same rule as list(): guessing a draft's id must not be a way around it.
        Specification<Case> combined = idSpec.and(draftsVisibleOnlyToTheirOwner(auth));
        combined = scopeSpec == null ? combined : combined.and(scopeSpec);
        return repository.findOne(combined).orElseThrow(() -> ApiException.notFound("Case not found."));
    }

    private Specification<Case> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.hasSchoolWideVisibility(); // 07-RoleFinetune.md §2.10
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    /**
     * A {@code DRAFT} case is the student's own unsubmitted work. It is not a
     * review item, and a reviewer must not be able to read it at all — the
     * title and description are whatever the student has typed so far, before
     * they have decided to say it. Data scope alone does not express this: a
     * school-wide admin is in scope for every case, so without this the
     * unfiltered list an admin loads by default returned students' drafts,
     * complete with description, and {@code submittedAt: null} beside them.
     *
     * <p>Owners keep seeing their own drafts — the student's My Cases page is
     * built on exactly that, so this cannot simply exclude {@code DRAFT}
     * outright. Staff principals have no {@code studentId}, so for them it
     * reduces to "never a draft".</p>
     */
    private Specification<Case> draftsVisibleOnlyToTheirOwner(Authentication auth) {
        String viewerStudentId = ((CookieSessionUserDetails) auth.getPrincipal()).studentId();
        return (root, query, cb) -> {
            var notDraft = cb.notEqual(root.get("status"), CaseStatus.DRAFT);
            return viewerStudentId == null
                    ? notDraft
                    : cb.or(notDraft, cb.equal(root.get("studentId"), viewerStudentId));
        };
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }

    /** Single-object call sites (create/update/submit/decide) — cheap since {@link AcademicStructureService}'s lists are `@Cacheable`. */
    private CaseDto toDto(Case c, String resolvedLanguage) {
        return toDto(c, resolvedLanguage, gradeSequenceById(resolvedLanguage), classNameById());
    }

    private CaseDto toDto(Case c, String resolvedLanguage, Map<String, Integer> gradeSequenceById, Map<String, String> classNameById) {
        String studentName = studentService.getDetail(c.getStudentId(), resolvedLanguage).displayName();
        return new CaseDto(c.getId(), c.getType().name(), c.getStudentId(), studentName, c.getGradeId(),
                gradeSequenceById.getOrDefault(c.getGradeId(), 0), classNameById.getOrDefault(c.getClassId(), ""),
                c.getSubjectType(), c.getSubjectId(), subjectLabel(c), c.getTitle(), c.getDescription(),
                c.getRequestedAmountCentiCoins(), c.getReasonCode(), c.getStatus().name(), c.getAssignedReviewerId(),
                c.getVersion(), c.getCreatedAt(), c.getSubmittedAt(), c.getUpdatedAt());
    }

    private Map<String, Integer> gradeSequenceById(String resolvedLanguage) {
        return academicStructureService.listGrades(resolvedLanguage).stream()
                .collect(Collectors.toMap(GradeDto::id, GradeDto::sequence));
    }

    private Map<String, String> classNameById() {
        return academicStructureService.listClasses().stream()
                .collect(Collectors.toMap(ClassDto::id, ClassDto::name));
    }

    /** Only {@code serviceRecord} is a resolvable subject type today (the only one any create path sets) — anything else is null, not guessed. */
    private String subjectLabel(Case c) {
        if (c.getSubjectId() == null || !"serviceRecord".equals(c.getSubjectType())) {
            return null;
        }
        return serviceRecordService.recordLabel(c.getSubjectId()).orElse(null);
    }
}
