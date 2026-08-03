package cn.timebank.audit.application.dto;

import java.time.Instant;

public record AuditLogDto(String id, String actorId, String permissionUsed, String action, String resourceType,
                           String resourceId, String beforeSummary, String afterSummary, String reasonCode,
                           String requestId, String ipAddress, String userAgent, Instant createdAt) {
}
