package cn.timebank.caseflow.infrastructure;

import cn.timebank.caseflow.domain.CaseRevision;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only — bare {@link Repository} marker, same structural enforcement as `AuditLogRepository`. */
public interface CaseRevisionRepository extends Repository<CaseRevision, String> {

    CaseRevision save(CaseRevision revision);

    List<CaseRevision> findByCaseIdOrderByRevisionNumberAsc(String caseId);
}
