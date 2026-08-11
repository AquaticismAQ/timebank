package cn.timebank.review.application;

import cn.timebank.review.application.dto.ReviewDecisionDto;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;
import cn.timebank.review.application.dto.ReviewRequests.AssignRequest;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;

/** The `review` module's published interface (Design.md §6). Hosts B3.12's approval transaction. */
public interface ReviewService {

    /** {@code hasAnomaly}/{@code gradeId}/{@code search} are contract §2.7's filter/search — see {@code ReviewQueueRepository.queryPage}. */
    Page<ReviewQueueItemDto> queue(Pageable pageable, Boolean hasAnomaly, String gradeId, String search, Authentication auth, String resolvedLanguage);

    ReviewQueueSummaryDto summary(Authentication auth);

    /**
     * B3.12: one atomic transaction — review event + record status +
     * (on {@code APPROVE} only) account transaction + progress +
     * notification — wrapped in {@code Idempotency-Key} (contract §13.4).
     * A forced failure at any step must leave none of them committed.
     */
    ServiceRecordDto decide(String recordId, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage);

    void assign(String recordId, AssignRequest request, Authentication auth);

    List<ReviewDecisionDto> decisionsForRecord(String recordId);
}
