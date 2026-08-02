package cn.timebank.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Design.md §11: {@code Idempotency-Key header + principal + endpoint}
 * identifies one row, stored in MySQL (not Redis — D1) so "the key was
 * recorded" and "the business write happened" commit or roll back
 * together.
 */
@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_natural_key",
                columnNames = {"idempotency_key", "principal_id", "endpoint"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    public enum Status { IN_PROGRESS, DONE }

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "principal_id", length = 32, nullable = false, updatable = false)
    private String principalId;

    @Column(name = "endpoint", length = 128, nullable = false, updatable = false)
    private String endpoint;

    @Column(name = "request_fingerprint", length = 64, nullable = false, updatable = false)
    private String requestFingerprint;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Setter
    @Column(name = "response_status")
    private Integer responseStatus;

    @Setter
    @Column(name = "response_body", columnDefinition = "longtext")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IdempotencyRecord(String id, String idempotencyKey, String principalId, String endpoint,
                              String requestFingerprint, Instant now) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.principalId = principalId;
        this.endpoint = endpoint;
        this.requestFingerprint = requestFingerprint;
        this.status = Status.IN_PROGRESS;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
