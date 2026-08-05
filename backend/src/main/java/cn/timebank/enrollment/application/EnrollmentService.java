package cn.timebank.enrollment.application;

import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionRequest;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionResult;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;

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
}
