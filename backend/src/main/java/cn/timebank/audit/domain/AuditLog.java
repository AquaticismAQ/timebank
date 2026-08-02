package cn.timebank.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One append-only entry. Design.md §13: actor, permission used, action,
 * resource type/id, before/after summary, reason code, requestId, IP, user
 * agent, timestamp. Before/after are compact JSON, never full entity
 * snapshots (§13 write-path cost note).
 *
 * No setters and no update/delete repository method exist anywhere in this
 * module (invariant 10) — the absence is structural, not a convention.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "actor_id", length = 32)
    private String actorId;

    @Column(name = "permission_used", length = 64)
    private String permissionUsed;

    @Column(name = "action", length = 64, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 64, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 32)
    private String resourceId;

    @Column(name = "before_summary", columnDefinition = "json")
    private String beforeSummary;

    @Column(name = "after_summary", columnDefinition = "json")
    private String afterSummary;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "request_id", length = 32)
    private String requestId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(String id, String actorId, String permissionUsed, String action,
                     String resourceType, String resourceId, String beforeSummary,
                     String afterSummary, String reasonCode, String requestId,
                     String ipAddress, String userAgent, Instant createdAt) {
        this.id = id;
        this.actorId = actorId;
        this.permissionUsed = permissionUsed;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.beforeSummary = beforeSummary;
        this.afterSummary = afterSummary;
        this.reasonCode = reasonCode;
        this.requestId = requestId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }
}
