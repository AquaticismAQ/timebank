package cn.timebank.proposal.application.dto;

/** B4.12 — mirrors `review`'s `ReviewQueueSummaryDto` shape from B3. */
public record ProposalQueueSummaryDto(long pendingCount, long overdueCount) {
}
