package cn.timebank.audit.application.dto;

import java.time.Instant;

/**
 * {@code actorDisplayName} is resolved server-side (issue 098), from the same
 * query that reads the row. The frontend used to look the actor up in whatever
 * page of accounts the Users tab had loaded, so the name it showed depended on
 * unrelated state on another tab and fell back to the raw opaque id for any
 * actor outside that page. Null for a system-generated row, which genuinely has
 * no actor — distinct from an actor whose account was since deleted, where the
 * id is present and the name is not.
 */
public record AuditLogDto(String id, String actorId, String actorDisplayName, String permissionUsed, String action,
                           String resourceType, String resourceId, String beforeSummary, String afterSummary,
                           String reasonCode, String requestId, String ipAddress, String userAgent,
                           Instant createdAt) {
}
