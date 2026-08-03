package cn.timebank.file.domain;

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
 * Metadata only — the bytes live behind {@link
 * cn.timebank.file.application.FileStorage}, never in this table (D9).
 * {@code storageKey} is opaque to every caller outside the storage
 * adapter; nothing parses it (contract §2.1's ID convention, applied here
 * to a storage-internal reference too).
 */
@Entity
@Table(name = "evidence_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EvidenceFile {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "uploader_id", length = 32, nullable = false, updatable = false)
    private String uploaderId;

    @Column(name = "purpose", length = 32, nullable = false, updatable = false)
    private String purpose;

    @Column(name = "original_filename", nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "declared_mime_type", length = 100, nullable = false, updatable = false)
    private String declaredMimeType;

    @Column(name = "declared_size_bytes", nullable = false, updatable = false)
    private long declaredSizeBytes;

    @Setter
    @Column(name = "actual_size_bytes")
    private Long actualSizeBytes;

    @Setter
    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private FileStatus status;

    @Setter
    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EvidenceFile(String id, String uploaderId, String purpose, String originalFilename,
                         String declaredMimeType, long declaredSizeBytes, String storageKey, Instant now) {
        this.id = id;
        this.uploaderId = uploaderId;
        this.purpose = purpose;
        this.originalFilename = originalFilename;
        this.declaredMimeType = declaredMimeType;
        this.declaredSizeBytes = declaredSizeBytes;
        this.storageKey = storageKey;
        this.status = FileStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
