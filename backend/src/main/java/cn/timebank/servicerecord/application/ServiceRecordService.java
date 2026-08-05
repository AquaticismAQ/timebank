package cn.timebank.servicerecord.application;

import cn.timebank.servicerecord.application.dto.AnnualProgressSummary;
import cn.timebank.servicerecord.application.dto.RecordReviewSnapshot;
import cn.timebank.servicerecord.application.dto.RevisionDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.UpdateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.TimelineEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;

/** The `servicerecord` module's published interface (Design.md §6). */
public interface ServiceRecordService {

    Page<ServiceRecordDto> list(Pageable pageable, Authentication auth, String status, String sourceType,
                                 String serviceCategoryId, String search, String resolvedLanguage);

    ServiceRecordDto getDetail(String id, Authentication auth, String resolvedLanguage);

    ServiceRecordDto createDraft(CreateServiceRecordRequest request, String studentId, String actorId, String resolvedLanguage);

    ServiceRecordDto updateDraft(String id, UpdateServiceRecordRequest request, Authentication auth, String resolvedLanguage);

    void deleteDraft(String id, Authentication auth);

    ServiceRecordDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage);

    ServiceRecordDto withdraw(String id, Authentication auth, String resolvedLanguage);

    List<RevisionDto> revisions(String id, Authentication auth);

    List<TimelineEntryDto> timeline(String id, Authentication auth);

    void assignReviewer(String id, String reviewerId, String actorId);

    // --- Consumed by `review` (B3.12) without reaching into this module's domain/infrastructure ---

    /**
     * Loads the record, checks {@code expectedVersion}, performs the implicit
     * {@code SUBMITTED -> UNDER_REVIEW} step if needed, then transitions to
     * {@code targetStatus} (a no-op if already there — the `ESCALATE`
     * decision's case) — all via {@link cn.timebank.servicerecord.domain.ServiceRecordStateMachine}.
     * Returns the fields B3.13's crediting calculation needs.
     */
    RecordReviewSnapshot beginReviewTransition(String recordId, Long expectedVersion, String targetStatus, String actorId);

    /** Sets `creditedUnits`/`accountTransactionId` after {@code AccountService.credit} has succeeded — approval only. */
    void applyCredit(String recordId, int creditedUnits, String accountTransactionId);

    /** B3.11: bounded to one student, one academic year. */
    AnnualProgressSummary summarizeForProgress(String studentId, LocalDate yearStart, LocalDate yearEnd);
}
