package cn.timebank.enrollment.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiException;
import cn.timebank.enrollment.domain.Enrollment;
import cn.timebank.enrollment.domain.EnrollmentStatus;
import cn.timebank.enrollment.infrastructure.EnrollmentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * B4.8's "one row's failure does not silently discard the rest of the
 * batch" — a separate bean so {@code REQUIRES_NEW} applies through a real
 * Spring proxy rather than a same-class self-invocation (the same
 * reasoning as {@code IdempotencyTransactions}/{@code
 * ReviewDecisionTransaction}). Holds its own dependencies rather than
 * injecting {@code EnrollmentService} back — that would create a
 * `EnrollmentServiceImpl` → this bean → `EnrollmentService` cycle, since
 * `EnrollmentServiceImpl` is what constructs this bean in the first place.
 * Deliberately lets any exception propagate out uncaught — catching it
 * *inside* this method would leave the transaction rollback-only
 * regardless (the B0 idempotency finding); {@code EnrollmentServiceImpl
 * .bulkActions}'s non-transactional caller is what safely catches it, per row.
 */
@Component
class EnrollmentBulkActionTransaction {

    private final EnrollmentRepository repository;
    private final AuditService auditService;

    EnrollmentBulkActionTransaction(EnrollmentRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void executeOne(String id, String action, String actorId) {
        Enrollment enrollment = repository.findById(id).orElseThrow(() -> ApiException.notFound("Application not found."));
        switch (action) {
            case "confirm" -> {
                requireStatus(enrollment, EnrollmentStatus.PENDING, EnrollmentStatus.WAITLISTED, "confirmed");
                enrollment.setStatus(EnrollmentStatus.CONFIRMED);
                enrollment.setConfirmedAt(Instant.now());
            }
            case "reject" -> {
                requireStatus(enrollment, EnrollmentStatus.PENDING, EnrollmentStatus.WAITLISTED, "rejected");
                enrollment.setStatus(EnrollmentStatus.REJECTED);
            }
            case "markComplete" -> {
                requireStatus(enrollment, EnrollmentStatus.CONFIRMED, EnrollmentStatus.CONFIRMED, "marked complete");
                enrollment.setStatus(EnrollmentStatus.COMPLETED);
                enrollment.setCompletedAt(Instant.now());
            }
            default -> throw ApiException.invalidRequest("Unknown bulk action: " + action);
        }
        auditService.record(new AuditEntry(actorId, "applications.manage", "enrollment.bulk." + action,
                "enrollment", id, null, null, null));
    }

    private void requireStatus(Enrollment enrollment, EnrollmentStatus a, EnrollmentStatus b, String actionDescription) {
        if (enrollment.getStatus() != a && enrollment.getStatus() != b) {
            throw ApiException.stateConflict("This application cannot be " + actionDescription + " from its current status.");
        }
    }
}
