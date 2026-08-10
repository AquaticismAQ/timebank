package cn.timebank.enrollment.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionRequest;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionResult;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.enrollment.domain.Enrollment;
import cn.timebank.enrollment.domain.EnrollmentStatus;
import cn.timebank.enrollment.infrastructure.EnrollmentRepository;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectCapacitySnapshot;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.student.application.StudentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
class EnrollmentServiceImpl implements EnrollmentService {

    private final EnrollmentRepository repository;
    private final ProjectService projectService;
    private final StudentService studentService;
    private final AuditService auditService;
    private final EnrollmentBulkActionTransaction bulkActionTransaction;

    EnrollmentServiceImpl(EnrollmentRepository repository, ProjectService projectService, StudentService studentService,
                          AuditService auditService, EnrollmentBulkActionTransaction bulkActionTransaction) {
        this.repository = repository;
        this.projectService = projectService;
        this.studentService = studentService;
        this.auditService = auditService;
        this.bulkActionTransaction = bulkActionTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EnrollmentDto> list(Pageable pageable, String status, String projectId, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        Specification<Enrollment> scopeSpec = isManager(principal) ? null
                : (root, query, cb) -> cb.equal(root.get("studentId"), principal.studentId());
        Specification<Enrollment> spec = FilterSpecifications.and(
                scopeSpec, FilterSpecifications.equalToEnum("status", EnrollmentStatus.class, status),
                FilterSpecifications.equalTo("projectId", projectId));
        Page<Enrollment> page = repository.findAll(spec, pageable);

        Map<String, String> projectTitles = projectService.titlesOf(page.getContent().stream().map(Enrollment::getProjectId).distinct().toList());
        Map<String, String> studentNames = studentNameMap(page.getContent(), resolvedLanguage);
        return page.map(e -> toDto(e, projectTitles, studentNames));
    }

    @Override
    @Transactional(readOnly = true)
    public EnrollmentDto getDetail(String id, Authentication auth, String resolvedLanguage) {
        Enrollment enrollment = findInScopeOrThrow(id, auth);
        Map<String, String> projectTitles = projectService.titlesOf(List.of(enrollment.getProjectId()));
        Map<String, String> studentNames = studentNameMap(List.of(enrollment), resolvedLanguage);
        return toDto(enrollment, projectTitles, studentNames);
    }

    @Override
    @Transactional
    public EnrollmentDto register(String projectId, RegisterRequest request, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        String studentId = principal.studentId();

        // Locks the project row FIRST, before any plain (non-locking) read in
        // this transaction. Under MySQL REPEATABLE READ, a transaction's
        // consistent-read snapshot is established at its first plain SELECT;
        // a locking read (FOR UPDATE) bypasses that snapshot and always reads
        // the latest committed data. If a plain read ran before this lock,
        // every concurrent transaction would take its snapshot pre-lock and
        // then re-use that same stale snapshot for the capacity count below
        // even after being serialized by the lock — undercounting confirmed
        // seats and oversubscribing capacity (B4.7's exit criterion caught
        // this). Locking first ensures the count below sees everything
        // committed by the time this transaction acquires the lock.
        ProjectCapacitySnapshot snapshot = projectService.lockForRegistration(projectId);
        if (!"REGISTRATION_OPEN".equals(snapshot.status())) {
            throw ApiException.stateConflict("Registration is not open for this project.");
        }

        if (repository.findByProjectIdAndStudentId(projectId, studentId).isPresent()) {
            throw ApiException.stateConflict("Already registered for this project.");
        }

        long confirmedCount = repository.countByProjectIdAndStatus(projectId, EnrollmentStatus.CONFIRMED);
        EnrollmentStatus status = confirmedCount < snapshot.capacity() ? EnrollmentStatus.CONFIRMED : EnrollmentStatus.WAITLISTED;
        Instant now = Instant.now();
        Enrollment enrollment = new Enrollment(OpaqueIds.generate("enr"), projectId, studentId, status, now);
        enrollment.setNote(request.note());
        if (status == EnrollmentStatus.CONFIRMED) {
            enrollment.setConfirmedAt(now);
        }
        repository.save(enrollment);

        auditService.record(new AuditEntry(principal.id(), "applications.create", "enrollment.register",
                "enrollment", enrollment.getId(), null, status.name(), null));
        return toDto(enrollment, projectService.titlesOf(List.of(projectId)), studentNameMap(List.of(enrollment), resolvedLanguage));
    }

    @Override
    @Transactional
    public EnrollmentDto cancel(String id, Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        Enrollment enrollment = findOrThrow(id);
        if (!enrollment.getStudentId().equals(principal.studentId()) && !principal.hasPermission("applications.manage")) {
            throw ApiException.notFound("Application not found.");
        }
        if (enrollment.getStatus() != EnrollmentStatus.PENDING && enrollment.getStatus() != EnrollmentStatus.CONFIRMED
                && enrollment.getStatus() != EnrollmentStatus.WAITLISTED) {
            throw ApiException.stateConflict("This application can no longer be cancelled.");
        }
        enrollment.setStatus(EnrollmentStatus.CANCELLED);
        enrollment.setCancelledAt(Instant.now());
        repository.saveAndFlush(enrollment);

        auditService.record(new AuditEntry(principal.id(), "applications.manage", "enrollment.cancel",
                "enrollment", enrollment.getId(), null, null, null));
        return toDto(enrollment, projectService.titlesOf(List.of(enrollment.getProjectId())),
                studentNameMap(List.of(enrollment), resolvedLanguage));
    }

    @Override
    @Transactional
    public EnrollmentDto confirm(String id, String actorId, String resolvedLanguage) {
        Enrollment enrollment = findOrThrow(id);
        if (enrollment.getStatus() != EnrollmentStatus.PENDING && enrollment.getStatus() != EnrollmentStatus.WAITLISTED) {
            throw ApiException.stateConflict("Only a pending or waitlisted application can be confirmed.");
        }
        enrollment.setStatus(EnrollmentStatus.CONFIRMED);
        enrollment.setConfirmedAt(Instant.now());
        repository.saveAndFlush(enrollment);
        auditService.record(new AuditEntry(actorId, "applications.manage", "enrollment.confirm",
                "enrollment", enrollment.getId(), null, null, null));
        return toDto(enrollment, projectService.titlesOf(List.of(enrollment.getProjectId())), Map.of());
    }

    @Override
    @Transactional
    public EnrollmentDto reject(String id, String actorId, String resolvedLanguage) {
        Enrollment enrollment = findOrThrow(id);
        if (enrollment.getStatus() != EnrollmentStatus.PENDING && enrollment.getStatus() != EnrollmentStatus.WAITLISTED) {
            throw ApiException.stateConflict("Only a pending or waitlisted application can be rejected.");
        }
        enrollment.setStatus(EnrollmentStatus.REJECTED);
        repository.saveAndFlush(enrollment);
        auditService.record(new AuditEntry(actorId, "applications.manage", "enrollment.reject",
                "enrollment", enrollment.getId(), null, null, null));
        return toDto(enrollment, projectService.titlesOf(List.of(enrollment.getProjectId())), Map.of());
    }

    @Override
    @Transactional
    public EnrollmentDto markComplete(String id, String actorId, String resolvedLanguage) {
        Enrollment enrollment = findOrThrow(id);
        if (enrollment.getStatus() != EnrollmentStatus.CONFIRMED) {
            throw ApiException.stateConflict("Only a confirmed application can be marked complete.");
        }
        enrollment.setStatus(EnrollmentStatus.COMPLETED);
        enrollment.setCompletedAt(Instant.now());
        repository.saveAndFlush(enrollment);
        auditService.record(new AuditEntry(actorId, "applications.manage", "enrollment.markComplete",
                "enrollment", enrollment.getId(), null, null, null));
        return toDto(enrollment, projectService.titlesOf(List.of(enrollment.getProjectId())), Map.of());
    }

    @Override
    public List<BulkActionResult> bulkActions(BulkActionRequest request, String actorId, String resolvedLanguage) {
        // Deliberately not @Transactional: each row runs in the bulk-action
        // bean's own REQUIRES_NEW transaction so one row's failure rolls
        // back only that row, never the rest of the batch (B4.8).
        List<BulkActionResult> results = new ArrayList<>();
        for (String id : request.ids()) {
            try {
                bulkActionTransaction.executeOne(id, request.action(), actorId);
                results.add(new BulkActionResult(id, true, null));
            } catch (RuntimeException e) {
                results.add(new BulkActionResult(id, false, e.getMessage()));
            }
        }
        return results;
    }

    private boolean isManager(TimeBankPrincipal principal) {
        return principal.hasPermission("applications.manage") || principal.roleCodes().contains("admin")
                || principal.roleCodes().contains("sysadmin");
    }

    private Enrollment findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
    }

    private Enrollment findInScopeOrThrow(String id, Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        Enrollment enrollment = findOrThrow(id);
        if (!isManager(principal) && !enrollment.getStudentId().equals(principal.studentId())) {
            throw ApiException.notFound("Application not found.");
        }
        return enrollment;
    }

    private Map<String, String> studentNameMap(List<Enrollment> enrollments, String resolvedLanguage) {
        Map<String, String> names = new java.util.HashMap<>();
        for (String studentId : enrollments.stream().map(Enrollment::getStudentId).distinct().toList()) {
            try {
                names.put(studentId, studentService.getDetail(studentId, false, "system", resolvedLanguage).displayName());
            } catch (ApiException e) {
                names.put(studentId, "");
            }
        }
        return names;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isConfirmed(String projectId, String studentId) {
        return repository.findByProjectIdAndStudentId(projectId, studentId)
                .map(e -> e.getStatus() == EnrollmentStatus.CONFIRMED)
                .orElse(false);
    }

    private EnrollmentDto toDto(Enrollment e, Map<String, String> projectTitles, Map<String, String> studentNames) {
        return new EnrollmentDto(e.getId(), e.getProjectId(), projectTitles.getOrDefault(e.getProjectId(), ""),
                e.getStudentId(), studentNames.getOrDefault(e.getStudentId(), ""), e.getStatus().name(),
                e.getAppliedAt(), e.getConfirmedAt(), e.getCompletedAt(), e.getCancelledAt(), e.getNote(), e.getVersion());
    }
}
