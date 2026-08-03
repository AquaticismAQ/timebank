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
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.DataScopeSpecifications;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.StudentService;
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

@Service
class CaseServiceImpl implements CaseService {

    private static final String RESOURCE_TYPE = "cases";

    private final CaseRepository repository;
    private final CaseRevisionRepository revisionRepository;
    private final CaseDecisionEventRepository decisionEventRepository;
    private final StudentService studentService;
    private final AuditService auditService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final CaseDecisionTransaction decisionTransaction;
    private final CaseScopeFields scopeFields = new CaseScopeFields();

    CaseServiceImpl(CaseRepository repository, CaseRevisionRepository revisionRepository,
                    CaseDecisionEventRepository decisionEventRepository, StudentService studentService,
                    AuditService auditService, DataScopeResolver dataScopeResolver,
                    IdempotencyService idempotencyService, ObjectMapper objectMapper,
                    CaseDecisionTransaction decisionTransaction) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.decisionEventRepository = decisionEventRepository;
        this.studentService = studentService;
        this.auditService = auditService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.decisionTransaction = decisionTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CaseDto> list(Pageable pageable, Authentication auth, String resolvedLanguage) {
        Page<Case> page = repository.findAll(scopeSpecification(auth), pageable);
        return page.map(c -> toDto(c, resolvedLanguage));
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
        StudentDetailDto student = studentService.getDetail(studentId, false, principal.id(), resolvedLanguage);

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
        Specification<Case> combined = scopeSpec == null ? idSpec : idSpec.and(scopeSpec);
        return repository.findOne(combined).orElseThrow(() -> ApiException.notFound("Case not found."));
    }

    private Specification<Case> scopeSpecification(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        ScopeDescriptor scope = dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
        return DataScopeSpecifications.apply(scope, scopeFields, principal.studentId());
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }

    private CaseDto toDto(Case c, String resolvedLanguage) {
        String studentName = studentService.getDetail(c.getStudentId(), false, "system", resolvedLanguage).displayName();
        return new CaseDto(c.getId(), c.getType().name(), c.getStudentId(), studentName, c.getSubjectType(),
                c.getSubjectId(), c.getTitle(), c.getDescription(), c.getRequestedAmountCentiCoins(), c.getReasonCode(),
                c.getStatus().name(), c.getAssignedReviewerId(), c.getVersion(), c.getCreatedAt(), c.getSubmittedAt(),
                c.getUpdatedAt());
    }
}
