package cn.timebank.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One login attempt, successful or not (contract §4.15's `GET /login-logs`,
 * behind `securityLogs.read`). Append-only like {@link cn.timebank.audit.domain.AuditLog},
 * partitioned by month the same way (B0.10's pattern, applied here per
 * Design.md §16.3) — see {@link cn.timebank.iam.infrastructure.LoginLogRepository}
 * for the structural enforcement.
 */
@Entity
@Table(name = "login_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginLog {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 32)
    private String userId;

    @Column(name = "username_attempted", length = 64, nullable = false)
    private String usernameAttempted;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LoginLog(String id, String userId, String usernameAttempted, boolean success,
                     String failureReason, String ipAddress, String userAgent, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.usernameAttempted = usernameAttempted;
        this.success = success;
        this.failureReason = failureReason;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }
}
