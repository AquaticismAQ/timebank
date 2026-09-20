package cn.timebank.proposal.application;

import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalQueueSummaryDto;
import cn.timebank.proposal.application.dto.ProposalRequests.AssignRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.CreateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.UpdateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalReviewDto;
import cn.timebank.proposal.application.dto.RevisionDto;
import cn.timebank.proposal.application.dto.TimelineEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;

/** The `proposal` module's published interface (Design.md §6). Hosts B4.11's atomic project-generation transaction. */
public interface ProposalService {

    /**
     * {@code status}/{@code categoryId}/{@code gradeId} and {@code search} (matches {@code
     * title}) are contract §2.7's {@code filter[...]}/{@code search} —
     * null/blank means unfiltered, an unrecognized {@code status} matches
     * nothing rather than throwing. {@code gradeId} matches the proposal's own snapshotted grade.
     */
    Page<ProposalDto> list(Pageable pageable, Authentication auth, String status, String categoryId, String gradeId,
                            String academicYearId, String search, String resolvedLanguage);

    ProposalDto getDetail(String id, Authentication auth, String resolvedLanguage);

    ProposalDto createDraft(CreateProposalRequest request, String studentId, String actorId, String resolvedLanguage);

    ProposalDto updateDraft(String id, UpdateProposalRequest request, Authentication auth, String resolvedLanguage);

    void deleteDraft(String id, Authentication auth);

    ProposalDto submit(String id, Authentication auth, String idempotencyKey, String resolvedLanguage);

    ProposalDto withdraw(String id, Authentication auth, String resolvedLanguage);

    List<RevisionDto> revisions(String id, Authentication auth);

    List<TimelineEntryDto> timeline(String id, Authentication auth);

    void assignReviewer(String id, String reviewerId, String actorId);

    /**
     * B4.11: the atomic approval transaction when {@code decision == APPROVE}
     * (approval event + status + generated project + `approvedProjectId`
     * backfill + notification), or a narrower event+status+notification
     * update for every other decision — one transaction, wrapped in
     * `Idempotency-Key`.
     */
    ProposalDto decide(String id, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage);

    List<ProposalReviewDto> decisionsForProposal(String id);

    ProposalQueueSummaryDto queueSummary(Authentication auth);
}
