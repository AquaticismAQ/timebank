package cn.timebank.audit.application;

import cn.timebank.audit.application.dto.AuditLogDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

/**
 * B5.12's read side of the operation audit log — deliberately a separate
 * interface from {@link AuditService} (the writer), so no query method can
 * ever be mistaken for part of the write path invariant 10 depends on.
 */
public interface AuditLogQueryService {

    Page<AuditLogDto> search(String resourceType, String resourceId, String actorId,
                              Instant createdFrom, Instant createdTo, Pageable pageable);
}
