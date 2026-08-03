package cn.timebank.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Append-only (B4.5) — a business-facing timeline, distinct from `audit_log` (Design.md §13). */
@Entity
@Table(name = "project_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectRevision {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 32, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "edited_by", length = 32, nullable = false, updatable = false)
    private String editedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ProjectRevision(String id, String projectId, int revisionNumber, String snapshotJson,
                            String editedBy, Instant now) {
        this.id = id;
        this.projectId = projectId;
        this.revisionNumber = revisionNumber;
        this.snapshotJson = snapshotJson;
        this.editedBy = editedBy;
        this.createdAt = now;
    }
}
