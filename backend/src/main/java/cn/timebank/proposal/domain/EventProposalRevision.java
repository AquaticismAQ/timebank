package cn.timebank.proposal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Append-only (B4.9) — business-facing timeline, distinct from `audit_log` (Design.md §13). */
@Entity
@Table(name = "event_proposal_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventProposalRevision {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "event_proposal_id", length = 32, nullable = false, updatable = false)
    private String eventProposalId;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "snapshot_json", nullable = false, updatable = false, columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "edited_by", length = 32, nullable = false, updatable = false)
    private String editedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EventProposalRevision(String id, String eventProposalId, int revisionNumber, String snapshotJson,
                                  String editedBy, Instant now) {
        this.id = id;
        this.eventProposalId = eventProposalId;
        this.revisionNumber = revisionNumber;
        this.snapshotJson = snapshotJson;
        this.editedBy = editedBy;
        this.createdAt = now;
    }
}
