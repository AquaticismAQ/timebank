package cn.timebank.servicerecord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only (B3.1) — a business-facing "what changed and when" timeline,
 * distinct from {@code audit_log} (security audit, Design.md §13).
 * {@code snapshotJson} captures the record's field values *before* an edit
 * was applied, written by {@code ServiceRecordServiceImpl.updateDraft}.
 */
@Entity
@Table(name = "service_record_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceRecordRevision {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "service_record_id", length = 32, nullable = false, updatable = false)
    private String serviceRecordId;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "edited_by", length = 32, nullable = false, updatable = false)
    private String editedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ServiceRecordRevision(String id, String serviceRecordId, int revisionNumber, String snapshotJson,
                                  String editedBy, Instant now) {
        this.id = id;
        this.serviceRecordId = serviceRecordId;
        this.revisionNumber = revisionNumber;
        this.snapshotJson = snapshotJson;
        this.editedBy = editedBy;
        this.createdAt = now;
    }
}
