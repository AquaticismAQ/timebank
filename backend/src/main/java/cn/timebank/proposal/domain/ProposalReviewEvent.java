package cn.timebank.proposal.domain;

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

/** Append-only (invariant 3, B4.10) — changing a decision appends another event; history is never overwritten. */
@Entity
@Table(name = "event_proposal_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProposalReviewEvent {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "event_proposal_id", length = 32, nullable = false, updatable = false)
    private String eventProposalId;

    @Column(name = "reviewer_id", length = 32, nullable = false, updatable = false)
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 24, nullable = false, updatable = false)
    private ProposalDecision decision;

    @Column(name = "reason_code", length = 64, nullable = false, updatable = false)
    private String reasonCode;

    @Column(name = "comment", length = 1000, updatable = false)
    private String comment;

    @Column(name = "proposal_version_at_decision", nullable = false, updatable = false)
    private long proposalVersionAtDecision;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ProposalReviewEvent(String id, String eventProposalId, String reviewerId, ProposalDecision decision,
                                String reasonCode, String comment, long proposalVersionAtDecision, Instant now) {
        this.id = id;
        this.eventProposalId = eventProposalId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.comment = comment;
        this.proposalVersionAtDecision = proposalVersionAtDecision;
        this.createdAt = now;
    }
}
