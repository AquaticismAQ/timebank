package cn.timebank.proposal.infrastructure;

import cn.timebank.proposal.domain.ProposalReviewEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only (invariant 3) — bare {@link Repository} marker, mirroring {@code ReviewDecisionRepository}. */
public interface ProposalReviewEventRepository extends Repository<ProposalReviewEvent, String> {

    ProposalReviewEvent save(ProposalReviewEvent event);

    List<ProposalReviewEvent> findByEventProposalIdOrderByCreatedAtAsc(String eventProposalId);
}
