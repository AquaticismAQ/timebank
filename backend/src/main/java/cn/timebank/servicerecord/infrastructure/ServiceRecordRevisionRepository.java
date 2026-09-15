package cn.timebank.servicerecord.infrastructure;

import cn.timebank.servicerecord.domain.ServiceRecordRevision;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only — bare {@link Repository} marker, mirroring {@code AuditLogRepository}/{@code AccountTransactionRepository}. */
public interface ServiceRecordRevisionRepository extends Repository<ServiceRecordRevision, String> {

    ServiceRecordRevision save(ServiceRecordRevision revision);

    List<ServiceRecordRevision> findByServiceRecordIdOrderByRevisionNumberAsc(String serviceRecordId);

    // Not a mutation of an existing revision (still append-only) — only ever called
    // by ServiceRecordServiceImpl.deleteDraft, which purges the owning record itself,
    // so its revision history has nothing left to be history of (issue 059).
    void deleteByServiceRecordId(String serviceRecordId);
}
