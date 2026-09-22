package cn.timebank.enrollment.application;

import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionRequest;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionResult;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

/** The `enrollment` module's published interface (Design.md §6) — the contract's `Application`, REST path `/applications`. */
public interface EnrollmentService {

    Page<EnrollmentDto> list(Pageable pageable, String status, String projectId, Authentication auth, String resolvedLanguage);

    EnrollmentDto getDetail(String id, Authentication auth, String resolvedLanguage);

    /** B4.7: registration is serialized against the project row via {@code ProjectService.lockForRegistration}. */
    EnrollmentDto register(String projectId, RegisterRequest request, Authentication auth, String resolvedLanguage);

    EnrollmentDto cancel(String id, Authentication auth, String resolvedLanguage);

    EnrollmentDto confirm(String id, String actorId, String resolvedLanguage);

    EnrollmentDto reject(String id, String actorId, String resolvedLanguage);

    EnrollmentDto markComplete(String id, String actorId, String resolvedLanguage);

    /** B4.8: one row's failure does not discard the rest — each row runs in its own transaction. */
    List<BulkActionResult> bulkActions(BulkActionRequest request, String actorId, String resolvedLanguage);

    /**
     * Docs/optimization/03-ServiceRecordFormOptimization.md §3.1 — {@code servicerecord}
     * validates a PROJECT-sourced record's {@code projectId} against this at both draft
     * creation and submission (a tampered id must be rejected at both points, not just
     * whichever one the client happened to hit first).
     */
    boolean isConfirmed(String projectId, String studentId);

    /**
     * Every enrollment on a project, as {@code enrollmentId -> student name},
     * for the project timeline (issue 158).
     *
     * <p>Exposed because an enrollment's audit rows are keyed to the enrollment
     * and carry no project id, so this is the only bridge from a project to its
     * registration events — and the `project` module must reach it through this
     * interface rather than the `enrollment` table.</p>
     *
     * <p>The name comes with the id rather than being looked up afterwards,
     * because a timeline row like "Registration confirmed — Teacher Wei Chen"
     * does not say <i>whose</i>, which is the first thing anyone reading it
     * wants to know.</p>
     */
    Map<String, String> enrollmentSubjectsOf(String projectId, String resolvedLanguage);
}
