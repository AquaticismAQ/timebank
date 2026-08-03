package cn.timebank.review.infrastructure;

import cn.timebank.review.domain.ReviewDecisionEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only (invariant 3) — bare {@link Repository} marker, mirroring {@code AuditLogRepository}/{@code AccountTransactionRepository}. */
public interface ReviewDecisionRepository extends Repository<ReviewDecisionEvent, String> {

    ReviewDecisionEvent save(ReviewDecisionEvent event);

    List<ReviewDecisionEvent> findByServiceRecordIdOrderByCreatedAtAsc(String serviceRecordId);
}
