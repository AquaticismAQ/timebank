package cn.timebank.caseflow.infrastructure;

import cn.timebank.caseflow.domain.CaseDecisionEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only (invariant 3) — bare {@link Repository} marker, mirroring {@code ProposalReviewEventRepository}. */
public interface CaseDecisionEventRepository extends Repository<CaseDecisionEvent, String> {

    CaseDecisionEvent save(CaseDecisionEvent event);

    List<CaseDecisionEvent> findByCaseIdOrderByCreatedAtAsc(String caseId);
}
