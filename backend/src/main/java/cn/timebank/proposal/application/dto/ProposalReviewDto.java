package cn.timebank.proposal.application.dto;

import java.time.Instant;

public record ProposalReviewDto(String id, String eventProposalId, String reviewerId, String decision,
                                 String reasonCode, String comment, long proposalVersionAtDecision, Instant createdAt) {
}
