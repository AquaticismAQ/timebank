package cn.timebank.caseflow.infrastructure;

import cn.timebank.caseflow.domain.CaseRevision;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Append-only for the life of its case — bare {@link Repository} marker, same
 * structural enforcement as `AuditLogRepository`. {@code deleteByCaseId} is not
 * an exception to that: a draft case's revisions exist only to support that
 * draft, and they have to go with it when it is deleted or the case delete
 * fails on the foreign key (issue 059's lesson, issue 081's delete path).
 */
public interface CaseRevisionRepository extends Repository<CaseRevision, String> {

    CaseRevision save(CaseRevision revision);

    List<CaseRevision> findByCaseIdOrderByRevisionNumberAsc(String caseId);

    void deleteByCaseId(String caseId);
}
