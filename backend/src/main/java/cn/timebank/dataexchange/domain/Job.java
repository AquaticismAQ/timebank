package cn.timebank.dataexchange.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * D5's "DB-backed job table" — the one resource `export`/`import` share.
 * {@code status} vocabulary differs by {@link #kind}: EXPORT uses the
 * contract §3.8 machine literally (QUEUED/RUNNING/SUCCEEDED/FAILED/
 * CANCELLED); IMPORT additionally distinguishes the validate and commit
 * phases (QUEUED/VALIDATING/VALIDATED/VALIDATION_FAILED/COMMITTING/
 * COMMITTED/COMMIT_FAILED/CANCELLED) since "validate before commit is
 * mandatory" (contract §4.14) is two separate async runs, not one.
 * {@code claimedBy}/{@code leaseExpiresAt} are what B5.11's reclaim sweep
 * scans; {@code processedRows}/{@code checkpointOffset} are what a resumed
 * run reads to continue instead of restarting.
 */
@Entity
@Table(name = "job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false, updatable = false)
    private JobKind kind;

    @Column(name = "resource_type", length = 64, nullable = false, updatable = false)
    private String resourceType;

    @Setter
    @Column(name = "status", length = 24, nullable = false)
    private String status;

    @Column(name = "created_by", length = 32, nullable = false, updatable = false)
    private String createdBy;

    @Setter
    @Column(name = "params_json", length = 2000)
    private String paramsJson;

    @Setter
    @Column(name = "total_rows")
    private Integer totalRows;

    @Setter
    @Column(name = "valid_rows")
    private Integer validRows;

    @Setter
    @Column(name = "processed_rows", nullable = false)
    private int processedRows;

    @Setter
    @Column(name = "checkpoint_offset", nullable = false)
    private int checkpointOffset;

    @Setter
    @Column(name = "result_ref", length = 255)
    private String resultRef;

    @Setter
    @Column(name = "error_ref", length = 255)
    private String errorRef;

    @Setter
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Setter
    @Column(name = "source_file_key", length = 255)
    private String sourceFileKey;

    @Setter
    @Column(name = "source_file_hash", length = 64)
    private String sourceFileHash;

    @Setter
    @Column(name = "claimed_by", length = 64)
    private String claimedBy;

    @Setter
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Setter
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Setter
    @Column(name = "started_at")
    private Instant startedAt;

    @Setter
    @Column(name = "finished_at")
    private Instant finishedAt;

    public Job(String id, JobKind kind, String resourceType, String status, String createdBy, Instant now) {
        this.id = id;
        this.kind = kind;
        this.resourceType = resourceType;
        this.status = status;
        this.createdBy = createdBy;
        this.processedRows = 0;
        this.checkpointOffset = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
