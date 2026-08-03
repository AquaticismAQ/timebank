package cn.timebank.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Append-only (invariant 3, B3.7) — changing a decision appends another
 * event; history is never overwritten. Named {@code ReviewDecisionEvent}
 * (not {@code ReviewDecision}) purely to avoid a same-simple-name clash
 * with the module's own {@code Decision} enum in imports elsewhere.
 */
@Entity
@Table(name = "review_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewDecisionEvent {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "service_record_id", length = 32, nullable = false, updatable = false)
    private String serviceRecordId;

    @Column(name = "reviewer_id", length = 32, nullable = false, updatable = false)
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 24, nullable = false, updatable = false)
    private Decision decision;

    @Column(name = "reason_code", length = 64, nullable = false, updatable = false)
    private String reasonCode;

    @Column(name = "comment", length = 1000, updatable = false)
    private String comment;

    @Column(name = "record_version_at_decision", nullable = false, updatable = false)
    private long recordVersionAtDecision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ReviewDecisionEvent(String id, String serviceRecordId, String reviewerId, Decision decision,
                                String reasonCode, String comment, long recordVersionAtDecision, Instant now) {
        this.id = id;
        this.serviceRecordId = serviceRecordId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.recordVersionAtDecision = recordVersionAtDecision;
        this.createdAt = now;
    }
}
