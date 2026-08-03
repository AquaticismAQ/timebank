package cn.timebank.proposal.infrastructure;

import cn.timebank.proposal.domain.EventProposalRevision;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only — bare {@link Repository} marker, same structural enforcement as `AuditLogRepository`. */
public interface EventProposalRevisionRepository extends Repository<EventProposalRevision, String> {

    EventProposalRevision save(EventProposalRevision revision);

    List<EventProposalRevision> findByEventProposalIdOrderByRevisionNumberAsc(String eventProposalId);
}
