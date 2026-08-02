package cn.timebank.audit.application;

import cn.timebank.audit.domain.AuditLog;
import cn.timebank.audit.infrastructure.AuditLogRepository;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.web.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class AuditServiceImpl implements AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    AuditServiceImpl(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(AuditEntry entry) {
        RequestContext ctx = RequestContext.current();
        AuditLog log = new AuditLog(
                OpaqueIds.generate("aud"),
                entry.actorId(),
                entry.permissionUsed(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                toCompactJson(entry.before()),
                toCompactJson(entry.after()),
                entry.reasonCode(),
                ctx.requestId(),
                ctx.ipAddress(),
                truncate(ctx.userAgent(), 256),
                Instant.now());
        repository.save(log);
    }

    private String toCompactJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // An audit row must never block the business transaction it belongs to;
            // fall back to a diagnosable marker rather than throwing.
            return "{\"_serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
