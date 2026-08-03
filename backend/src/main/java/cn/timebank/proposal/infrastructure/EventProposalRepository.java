package cn.timebank.proposal.infrastructure;

import cn.timebank.proposal.domain.EventProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventProposalRepository extends JpaRepository<EventProposal, String>, JpaSpecificationExecutor<EventProposal> {
}
