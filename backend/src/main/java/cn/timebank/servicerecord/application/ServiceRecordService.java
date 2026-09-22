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
import java.util.Map;
import java.util.Optional;

/** The `servicerecord` module's published interface (Design.md §6). */
public interface ServiceRecordService {

    Page<ServiceRecordDto> list(Pageable pageable, Authentication auth, String status, String sourceType,
                                 String serviceCategoryId, String studentId, String search, String resolvedLanguage);

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
     * {@code comment}, when non-blank, is written to {@code reviewSummary} as a
     * denormalized "latest reviewer note" for display — the append-only decision
     * event itself (with its own comment) is `review`'s, not duplicated here.
     * Returns the fields B3.13's crediting calculation needs.
     */
    RecordReviewSnapshot beginReviewTransition(String recordId, Long expectedVersion, String targetStatus, String actorId, String comment);

    /** Sets `creditedUnits`/`accountTransactionId` after {@code AccountService.credit} has succeeded — approval only. */
    void applyCredit(String recordId, int creditedUnits, String accountTransactionId);

    /** B3.11: bounded to one student, one academic year. */
    AnnualProgressSummary summarizeForProgress(String studentId, LocalDate yearStart, LocalDate yearEnd);

    /**
     * Docs/optimization/04-DataViewOptimization.md §4.2 (D8): a human-readable label for
     * {@code CaseDto.subjectLabel} when a case's {@code subjectType} is {@code serviceRecord}.
     * Deliberately unscoped by {@code Authentication} — unlike every other read here — because
     * a case reviewer needs to see what record an appeal is about even if their own data scope
     * wouldn't otherwise let them open that record directly; this returns a display string only,
     * never the full {@code ServiceRecordDto}. Empty if the record doesn't exist or predates
     * {@code recordNumber} (issue-03 §5.3) — never falls back to exposing the raw record id.
     */
    Optional<String> recordLabel(String id);

    /**
     * Every service record filed against a project, as
     * {@code recordId -> studentName}, for the project timeline (issue 158).
     *
     * <p>A record's audit rows are keyed to the record and name no project, so —
     * exactly as with enrollments — this table is the only bridge. The student's
     * name travels with the id because the actor on a review row is the
     * *reviewer*, and "Record approved — Teacher Wei Chen" does not say whose
     * record it was.</p>
     */
    Map<String, String> recordSubjectsOfProject(String projectId);
}
