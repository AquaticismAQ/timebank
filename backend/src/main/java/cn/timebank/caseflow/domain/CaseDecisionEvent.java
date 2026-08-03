package cn.timebank.caseflow.domain;

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

/** Append-only (invariant 3, B5.1) — changing a decision appends another event; history is never overwritten. */
@Entity
@Table(name = "case_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseDecisionEvent {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "case_id", length = 32, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "reviewer_id", length = 32, nullable = false, updatable = false)
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 24, nullable = false, updatable = false)
    private CaseDecision decision;

    @Column(name = "reason_code", length = 64, nullable = false, updatable = false)
    private String reasonCode;

    @Column(name = "comment", length = 1000, updatable = false)
    private String comment;

    @Column(name = "case_version_at_decision", nullable = false, updatable = false)
    private long caseVersionAtDecision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CaseDecisionEvent(String id, String caseId, String reviewerId, CaseDecision decision, String reasonCode,
                              String comment, long caseVersionAtDecision, Instant now) {
        this.id = id;
        this.caseId = caseId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.caseVersionAtDecision = caseVersionAtDecision;
        this.createdAt = now;
    }
}
